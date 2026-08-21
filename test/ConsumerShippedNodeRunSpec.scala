package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The functional half of the proof `test/ConsumerShippedNodes.scala` starts, split from it for the
  * identical reason `ConsumerGraphIdiomRunSpec` is split from `ConsumerGraphIdioms`: `TestWorld`
  * (`test/Recorder.scala`) is code belonging to the library, reachable only from within this package
  * (RFC #26 decision 14), so driving a foreign package graph through `Machine.runOnce` against a real
  * `TestWorld` has to happen from a file living inside `in.rcard.litterbox` itself.
  *
  * The graph asserted on is IMPORTED, never re declared: the value walked here is the exact value
  * `ConsumerShippedNodes.scala`'s own top level `val` built, from outside this package, through
  * `LitterBox.graph`. Compiling that file proves `Machine.Gate` is nameable and composable from a
  * foreign package; this file proves the node's OWN body ran, by asserting on the FAST gate capability
  * call and on the log line `runFastGate` itself emits, neither of which any node declared in that
  * consumer file could have produced.
  */
class ConsumerShippedNodeRunSpec extends AnyFlatSpec with Matchers:

  private def runOnce(world: TestWorld, graph: LoopGraph, cfg: Config = Config()): LoopExit =
    Machine.runOnce(1, graph)(using
      cfg,
      world.github,
      world.git,
      world.agents,
      world.gates,
      world.hostGates,
      world.status,
      world.notifier,
      world.fs,
      world.clock,
      world.logger
    )

  "Machine.runOnce" should "walk com.example.consumer.shippedGateGraph through the shipped Gate " +
    "node's own body on a GREEN gate" in {
    val world = new TestWorld
    world.gateResults = List(GateResult.Green)

    val exit = runOnce(world, com.example.consumer.shippedGateGraph)

    exit shouldBe LoopExit.Success
    // `runFastGate`'s own work, none of which any node in the consumer file could have done: it
    // stages the tree, runs the FAST tier through the `GateRunner` capability, and logs its verdict.
    world.callCount("gate FAST") shouldBe 1
    world.callCount("git add -A") shouldBe 1
    world.logged("FAST gate GREEN (pass 1)") shouldBe true
    val startIdx = world.logLines.indexWhere(_.contains("consumer shipped gate Start ran"))
    val gateIdx  = world.logLines.indexWhere(_.contains("FAST gate GREEN (pass 1)"))
    val greenIdx = world.logLines.indexWhere(_.contains("consumer shipped gate Green ran"))
    startIdx should be >= 0
    gateIdx should be > startIdx
    greenIdx should be > gateIdx
    world.logLines.exists(_.contains("consumer shipped gate Red ran")) shouldBe false
  }

  it should "route com.example.consumer.shippedGateGraph onto its own RED edge, carrying the gate " +
    "log path the shipped GateVerdict.Red produced" in {
    val world = new TestWorld
    world.gateResults = List(GateResult.Red)

    val exit = runOnce(world, com.example.consumer.shippedGateGraph, Config(logDir = "artifacts"))

    // The consumer's own routing, not the shipped pipeline's: a RED gate ends this graph at
    // NeedsHuman with no repair round at all, which is the point of composing the node rather than
    // reimplementing the loop around it.
    exit shouldBe LoopExit.NeedsHuman
    world.callCount("gate FAST") shouldBe 1
    world.logged("FAST gate RED (pass 1, see artifacts/issue-77-pass1.gate.log)") shouldBe true
    world.logged("consumer shipped gate Red ran with artifacts/issue-77-pass1.gate.log") shouldBe true
    world.logLines.exists(_.contains("consumer shipped gate Green ran")) shouldBe false
  }

  it should "emit the shipped FAST_GATE status phase from a consumer graph that never declared it" in {
    // The residual `Machine.Gate`'s own scaladoc documents: the node emits `FAST_GATE`, a phase
    // string `Machine.shippedStages` declares and a consumer's own `StageSet` (empty here, the
    // `LitterBox.graph` default) does not, so `watch.sh` draws no chip for it.
    val world = new TestWorld
    world.gateResults = List(GateResult.Green)

    runOnce(world, com.example.consumer.shippedGateGraph)

    world.events.map(_.phase) should contain("FAST_GATE")
    world.declaredStages.flatMap(_.stages).map(_.phase) should not contain "FAST_GATE"
  }

  it should "carry the consumer's own Cursor fields into every status event the shipped Gate node emits" in {
    // The other residual that doc names: a `Machine.Cursor` is the four fields every `StatusEvent`
    // carries, so a consumer graph that leaves `issue` empty or `iter` at zero degrades `watch.sh`'s
    // live view for reasons nothing in the loop reports. This graph fills them, and this is the pin
    // that the values a consumer wrote are the values that travel.
    val world = new TestWorld
    world.gateResults = List(GateResult.Green)

    runOnce(world, com.example.consumer.shippedGateGraph)

    val gateEvents = world.events.filter(_.phase == "FAST_GATE")
    gateEvents should not be empty
    all(gateEvents.map(_.issue)) shouldBe "77"
    all(gateEvents.map(_.iter)) shouldBe 1
    all(gateEvents.map(_.pass)) shouldBe 1
  }

  it should "walk the SAME Gate object the shipped Shape declares, since Gate is one val and no " +
    "longer a fresh Node per call" in {
    // The identity churn issue #68 names as a risk, pinned rather than assumed. `Gate` used to be a
    // `def(cfg: Config)` minting a fresh `Node` at every one of `shippedWorkflow`'s and
    // `shippedShape`'s call sites, safe only because `Runner.validate` keys by name. It is one `val`
    // now, which is what lets a `Plan` literal name it (`Plan.workflowOf` links edges by
    // `Edge.source(e) eq from`), and this is the pin that the shipped shape and any consumer graph
    // are talking about the identical object rather than two that merely agree on their name.
    Machine.Gate should be theSameInstanceAs(Machine.Gate)
    val shapeGateNodes =
      Machine.shippedShape(Config()).transitions.flatMap(t => List(t.from, t.to)).filter(_.name == "Gate")
    shapeGateNodes should not be empty
    all(shapeGateNodes.map(_ eq Machine.Gate)) shouldBe true
  }
