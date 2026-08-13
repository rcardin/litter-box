package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import Caps.given

/** Functional, in-memory proof that `LitterBox.graph` (issue #43, RFC #26 decisions 5 and 8) builds a
  * `LoopGraph` `Machine.runOnce` genuinely WALKS, not merely one that type-checks in the parameter
  * position. `ConsumerGraphSpec` (`test/ConsumerGraphSpec.scala`) proves the package-boundary half,
  * that a foreign package can write the calls down at all; this file proves the other half, that
  * `Machine.runOnce`'s own `graph` parameter, `src/Machine.scala`'s own doc used to call untestable
  * plumbing before this issue, is actually read.
  *
  * This file lives inside `in.rcard.litterbox` itself, unlike `ConsumerGraphSpec`, because it drives
  * `Machine.runOnce` directly against `TestWorld` (`test/Recorder.scala`), the same seam every other
  * behavioural spec in this suite uses, and `TestWorld` itself is library-side code, reachable only
  * from within this package (`TestWorld`'s own doc on its `AgentDispatchImpl` override has the reason,
  * RFC #26 decision 14). Nothing here is about the package BOUNDARY; it is about `graph`'s effect once
  * a `LoopGraph`, however obtained, reaches `runOnce`.
  */
class ConsumerGraphRunSpec extends AnyFlatSpec with Matchers:

  private def plainNode(name: String): Node[Unit, Unit] =
    Node[Unit, Unit](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )

  /** A node whose `run` does exactly one observable thing, `caps.logger.log(message)`, before
    * finishing (issue #43 review, MAJOR 3): `plainNode` above makes no capability call at all, so a
    * graph built entirely from it produces the identical `world.logLines` a graph sabotaged to ignore
    * `graph` entirely would also produce, nothing distinguishes "this graph's own nodes ran" from
    * "some other empty walk reached the same `LoopExit`". This helper is what test 11 below uses
    * instead, so the two nodes' own log lines, in order, are a fact only a genuine walk of THIS
    * graph's own `Next.Goto` chain can produce.
    */
  private def loggingNode(name: String, message: String): Node[Unit, Unit] =
    Node[Unit, Unit](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        summon[Caps].logger.log(message)
        NodeOutcome.Done(())
    )

  /** `Machine.runOnce`'s own individual capability parameters, read off a `TestWorld`, the identical
    * shape `TestWorld.runLoop` builds internally (`test/Recorder.scala`) except that helper has no
    * `graph` parameter of its own to vary, so every test below calls `Machine.runOnce` directly rather
    * than through it. Two overloads, the same split `buildCaps` (`test/Recorder.scala`) uses and for
    * the same reason: a caller with no reason to script the clock writes `runOnce(world, myGraph)`,
    * and the timeout test below, the one that does, writes `runOnce(world, myGraph, clock)` instead.
    */
  private def runOnce(world: TestWorld, graph: LoopGraph, cfg: Config = Config()): LoopExit =
    runOnce(world, graph, world.clock, cfg)

  private def runOnce(world: TestWorld, graph: LoopGraph, clock: Clock, cfg: Config): LoopExit =
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
      clock,
      world.logger
    )

  // ---- 11: a two-node consumer graph reaches the expected exit, and world.logLines shows the --------
  // ---- expected sequence: the first proof in this repo's history that Machine.runOnce's own ---------
  // ---- graph parameter is actually read, not merely accepted (src/Machine.scala's own doc used ------
  // ---- to record this as untestable plumbing) -------------------------------------------------------

  "Machine.runOnce" should "walk a two-node consumer graph built through LitterBox.graph, reaching the declared LoopExit and driving the calls its own nodes make" in {
    // Distinguishable observable effects, not `plainNode` (issue #43 review, MAJOR 3, correcting an
    // earlier version of this test): a pristine `TestWorld` run through `Machine.runOnce(1,
    // LitterBox.shipped)` also reaches `LoopExit.Success`, so asserting only on `exit` here cannot
    // tell "this graph's own two nodes actually ran" apart from "`runOnce` silently ignored `graph`
    // and did something else", confirmed by the reviewer sabotaging `runOnce` to ignore its own
    // `graph` parameter entirely and finding this exact assertion still passed. `loggingNode` closes
    // that gap: each node's own log line, and their RELATIVE ORDER in `world.logLines`, is a fact only
    // a genuine walk of this graph's own `Next.Goto(first, ..., _ => Next.Goto(second, ...))` chain,
    // never the shipped graph or an ignored parameter, can produce.
    val first  = loggingNode("First", "consumer node First ran")
    val second = loggingNode("Second", "consumer node Second ran")
    val myGraph = LitterBox.graph(
      workflow = Workflow[Unit](
        "consumer-two-node",
        start = (_: Unit) =>
          Next.Goto(
            first,
            (),
            _ =>
              Next.Goto(
                second,
                (),
                _ => Next.Finish(LoopExit.Success)
              )
          )
      ),
      shape = Shape(entry = List(first), transitions = List(Transition(first, second))),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.Success
    val firstIdx  = world.logLines.indexWhere(_.contains("consumer node First ran"))
    val secondIdx = world.logLines.indexWhere(_.contains("consumer node Second ran"))
    firstIdx should be >= 0
    secondIdx should be >= 0
    firstIdx should be < secondIdx
  }

  // ---- 12: the runner, not the consumer, owns the dispatch budget -----------------------------------

  it should "refuse the second of two Cost.OneDispatch nodes when dispatchBudget declares only one dispatch, proving the runner owns the budget, not the consumer" in {
    val first = Node[Unit, Unit](
      name = "First",
      cost = Cost.OneDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        summon[AgentDispatch].worker(Role.IMPL, "prompt", "patch", "log", None)
        NodeOutcome.Done(())
    )
    val second = Node[Unit, Unit](
      name = "Second",
      cost = Cost.OneDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        summon[AgentDispatch].worker(Role.IMPL, "prompt", "patch", "log", None)
        NodeOutcome.Done(())
    )
    val myGraph = LitterBox.graph(
      workflow = Workflow[Unit](
        "consumer-budget",
        start = (_: Unit) =>
          Next.Goto(first, (), _ => Next.Goto(second, (), _ => Next.Finish(LoopExit.Success)))
      ),
      shape = Shape(entry = List(first), transitions = List(Transition(first, second))),
      dispatchBudget = _ => 1,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.Parked
    world.callCount("dispatch IMPL") shouldBe 1
    world.logLines.exists(l => l.contains("Second") && l.contains("exhausted")) shouldBe true
  }

  // ---- 13: the runner, not the consumer, owns the timeout clock -------------------------------------

  it should "fault a consumer node declaring Timeout.After(1) once a FakeClock steps past it, proving the runner owns the timeout" in {
    val slow = Node[Unit, Unit](
      name = "Slow",
      cost = Cost.NoDispatch,
      timeout = Timeout.After(1),
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )
    val myGraph = LitterBox.graph(
      workflow = Workflow[Unit](
        "consumer-timeout",
        start = (_: Unit) => Next.Goto(slow, (), _ => Next.Finish(LoopExit.Success))
      ),
      shape = Shape(entry = List(slow), transitions = Nil),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val clock = new FakeClock(List(0L, 5000L)) // 5s elapsed against a 1s bound
    val exit  = runOnce(world, myGraph, clock, Config())

    exit shouldBe LoopExit.InfraFault
    world.logLines.exists(l => l.contains("Slow") && l.contains("timeout")) shouldBe true
  }

  // ---- 14: an invalid declared Shape faults rc 50 naming THIS graph's own workflow name, not ---------
  // ---- "shipped" (exercises the `.name` fix at src/Machine.scala, never tested before this issue) ----

  it should "fault rc 50 on an invalid declared Shape, naming the consumer's own workflow name in Runner.invalidShapeMessage rather than \"shipped\"" in {
    val only    = plainNode("Only")
    val orphanA = plainNode("OrphanA")
    val orphanB = plainNode("OrphanB")
    // The `shape` argument to `LitterBox.graph` below has to be written as a literal `Shape(...)`
    // expression right at that call site, never a `val` passed by identifier (issue #43 review,
    // BLOCKER 1, correcting an earlier version of this test): `LitterBox.graph` now splices
    // `checkedShapeStrict`, which hard-errors at compile time on exactly the indirection a `val
    // declaredShape` reused as `shape = declaredShape` used to be. `declaredShape` below still
    // exists, kept textually identical to the literal passed to `LitterBox.graph` two lines
    // further down rather than risking the two drifting apart, but only as the value
    // `Runner.validate`/`Runner.invalidShapeMessage` are asserted against; it is no longer, and
    // under `checkedShapeStrict` could no longer be, the expression `LitterBox.graph` itself
    // receives.
    val declaredShape = Shape(entry = List(only), transitions = List(Transition(orphanA, orphanB)))
    val myGraph = LitterBox.graph(
      workflow = Workflow[Unit](
        "consumer-invalid",
        start = (_: Unit) => Next.Goto(only, (), _ => Next.Finish(LoopExit.Success))
      ),
      shape = Shape(entry = List(only), transitions = List(Transition(orphanA, orphanB))),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.InfraFault
    world.logLines should contain(
      Runner.invalidShapeMessage("consumer-invalid", Runner.validate(declaredShape))
    )
  }

  // ---- 15: dispatchBudget bounds Cost.OneDispatch STARTS, not total spend (issue #43 review round ----
  // ---- 2, MAJOR M2): a Cost.NoDispatch node dispatching under dispatchBudget = _ => 0 still ----------
  // ---- completes, pinning the documented residual rather than leaving it only asserted in prose ------

  it should "let a Cost.NoDispatch node dispatch through AgentDispatch even when dispatchBudget declares zero, pinning the documented residual that dispatchBudget bounds Cost.OneDispatch starts, never total spend" in {
    // This is NOT an endorsement of the behaviour it pins, and it is not a bug this branch left
    // unfixed either: `Runner.Ledger.canAfford(Cost.NoDispatch)` is unconditionally `true`
    // (`Kit.scala`), checked once, before this node's own `run` starts, and nothing inside `run`
    // consults the ledger again, so a node that DECLARES `Cost.NoDispatch` while its own body actually
    // calls `agents.*` spends outside the budget entirely, regardless of what `dispatchBudget` returns.
    // `LoopGraph`'s own trait doc (`src/LitterBox.scala`) and `README.md`'s "Write your own loop"
    // section both name this residual and the reason a stricter, mid-node-enforced ledger was
    // deliberately not attempted on this branch (it would move the shipped graph's own golden-log-pinned
    // budget-exhaustion behaviour). This test exists so that residual is a checked fact, not only a
    // claim in prose that could quietly stop being true.
    val misdeclared = Node[Unit, Unit](
      name = "Misdeclared",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        summon[AgentDispatch].worker(Role.IMPL, "prompt", "patch", "log", None)
        NodeOutcome.Done(())
    )
    val myGraph = LitterBox.graph(
      workflow = Workflow[Unit](
        "consumer-nodispatch-residual",
        start = (_: Unit) => Next.Goto(misdeclared, (), _ => Next.Finish(LoopExit.Success))
      ),
      shape = Shape(entry = List(misdeclared), transitions = Nil),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.Success
    world.callCount("dispatch IMPL") shouldBe 1
  }
