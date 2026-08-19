package com.example.consumer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import in.rcard.litterbox.*

// The individual capabilities derived from the ambient `Caps` a node body is handed
// (`summon[AgentDispatch]` below). A consumer writing a node that touches any capability needs this
// import too, which is why README's Testkit section carries it in the worked example rather than
// leaving a reader to decode the compiler's suggestion.
import in.rcard.litterbox.Caps.given

/** The CONSUMER half of issue #42: that the testkit artifact `in.rcard::litter-box-testkit`
  * (`LitterBox.TestkitCoordinate`) is genuinely usable from a package this library does not own, and
  * that publishing it does not widen the trust boundary `ConsumerBoundarySpec` pins.
  *
  * `ConsumerGraphRunSpec` (`test/ConsumerGraphRunSpec.scala`) already drives a consumer-shaped graph
  * through `TestWorld`, but it does so from INSIDE `in.rcard.litterbox`, where every member of
  * `test/Recorder.scala` is reachable whether or not it would be from outside. It therefore cannot
  * see the failure this file exists to catch: a testkit member turning `private[litterbox]` (or
  * depending on one), which leaves this repo's own suite perfectly green and the published artifact
  * useless to the only people it is published for. This file is physically declared
  * `package com.example.consumer` for that reason, the same reason `ConsumerBoundarySpec`'s own doc
  * gives at length.
  *
  * Unlike the two `com.example.consumer` specs that came before it, almost every test here is REAL
  * CODE rather than a `scala.compiletime.testing.typeCheckErrors` snippet. Those two files are about
  * what a consumer can WRITE DOWN, a compile-time question a snippet answers honestly. #42's promise
  * is that a consumer can RUN their graph and their nodes, with no Docker, no network and no
  * credentials, and assert on what they did; a snippet that only type-checks would prove none of that.
  * The one snippet below is the exception that proves the rule: `runNode` builds a `Runner.Ledger`
  * internally, so what it is worth pinning about that type is precisely a compile-time question, that
  * a consumer STILL cannot write one down, and the surrounding real code is what proves the same
  * consumer can nonetheless run a node.
  *
  * There is deliberately no "a consumer still cannot implement `AgentDispatch`" negative here, and
  * #42's own review is why: the version of this file that had one compiled `class MyDispatch extends
  * AgentDispatch`, which never mentions the testkit, passes identically with the testkit absent, and
  * is a strictly weaker restatement of `ConsumerBoundarySpec`'s own negatives. That boundary belongs
  * to that file. What is genuinely new here, and lives below instead, is the residual publishing the
  * testkit MAKES reachable.
  *
  * What this file does NOT prove, and cannot: that `test/Recorder.scala` compiles standalone against
  * the published library jar, the way the release actually builds the artifact. Here it compiles as
  * part of this project, next to `src/` and next to scalatest. `.github/workflows/ci.yml`'s `testkit`
  * job is what covers that half, on every PR, because the only other place it would surface is the
  * one path in the release that cannot be retried.
  */
class TestkitBoundarySpec extends AnyFlatSpec with Matchers:

  /** A node whose `run` makes one scripted dispatch and logs one line: enough for the two things a
    * node author most wants from the testkit, an outcome to assert on and a recorded call sequence,
    * and small enough that what the assertions below pin is unambiguous.
    */
  private def dispatchingNode(name: String, message: String): Node[Unit, Unit] =
    Node[Unit, Unit](
      name = name,
      cost = Cost.OneDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        summon[AgentDispatch].worker(Role.IMPL, s"$name.md", s"$name.patch", s"$name.log", None)
        summon[Caps].logger.log(message)
        NodeOutcome.Done(())
    )

  // ---- the artifact's whole promise: a consumer runs their own graph against scripted fakes -------

  "a consumer using only the testkit" should "run their own graph against scripted capabilities and assert on both the outcome and the recorded call sequence" in {
    // #42's acceptance criteria 2, 3 and 5 in one test: every capability is scripted in memory, the
    // run needs no Docker, no network and no credentials (nothing in this file names any), and this
    // is the worked example README's Testkit section describes in prose.
    val world = new TestWorld
    world.implScript = Script.WorkerScript.Produces(Script.newFilePatch)

    val first  = dispatchingNode("First", "consumer node First ran")
    val second = dispatchingNode("Second", "consumer node Second ran")
    val myGraph = LitterBox.graph(
      name = "consumer-testkit",
      plan = Plan(
        entry = first,
        edges = List(
          Edge.To(first, second, _ => Some(())),
          Edge.Exit(second, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = _ => 2,
      startInput = _ => ()
    )

    val exit = world.runGraph(myGraph)

    exit shouldBe LoopExit.Success

    // The RECORDED CALL SEQUENCE, not merely the exit: the exit alone cannot tell a genuine walk of
    // this graph apart from some other walk that happened to end the same way (the sabotage
    // `ConsumerGraphRunSpec`'s own test 11 records). Both dispatches, in this graph's own order.
    val dispatches = world.calls.filter(_.startsWith("dispatch "))
    dispatches should have size 2
    dispatches(0) should include("promptFile=First.md")
    dispatches(1) should include("promptFile=Second.md")

    val firstIdx  = world.logLines.indexWhere(_.contains("consumer node First ran"))
    val secondIdx = world.logLines.indexWhere(_.contains("consumer node Second ran"))
    firstIdx should be >= 0
    secondIdx should be >= 0
    firstIdx should be < secondIdx
  }

  it should "still be refused the dispatch a graph never declared a budget for, because the runner owns the ledger and the testkit does not hand one over" in {
    // The testkit exposes `runGraph`, never a `Runner.Ledger`: RFC #26 decision 9 says the runner
    // owns the counter, and an escape hatch that let a consumer construct one under test would make
    // every budget assertion they write meaningless. `runGraph` takes an already-built `LoopGraph`
    // and `Machine.runOnce` derives the ledger from that graph's own `dispatchBudget`, so a budget
    // of one is a budget of one here exactly as it is in production.
    val world = new TestWorld

    val first  = dispatchingNode("First", "first ran")
    val second = dispatchingNode("Second", "second ran")
    val myGraph = LitterBox.graph(
      name = "consumer-testkit-budget",
      plan = Plan(
        entry = first,
        edges = List(
          Edge.To(first, second, _ => Some(())),
          Edge.Exit(second, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = _ => 1,
      startInput = _ => ()
    )

    val exit = world.runGraph(myGraph)

    exit shouldBe LoopExit.Parked
    world.callCount("dispatch IMPL") shouldBe 1
    world.logged("second ran") shouldBe false
  }

  // ---- one node on its own, the unit-test half of the same promise -------------------------------

  it should "run a single node of their own to Done, reporting the outcome and recording the call exactly as a graph run does" in {
    // The question `runGraph` cannot answer cheaply: what does THIS node do. Before `runNode` a
    // consumer had to wrap one node in a `Plan` and a `LitterBox.graph` call to find out, and then
    // read the answer off an exit code the whole walk contributed to.
    val world = new TestWorld
    world.ready = Some(7)

    val plain = Node[Unit, Int](
      name = "Plain",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        val issue = summon[GitHub].oldestReadyIssue()
        summon[Caps].logger.log("plain node ran")
        NodeOutcome.Done(issue.getOrElse(0))
    )

    val result = world.runNode(plain, ())

    // The whole result in one assertion: the happy path is a `Right` (no fault), the node's own
    // `NodeOutcome`, and the budget that survived, which a `Cost.NoDispatch` node never touches.
    result shouldBe Right(NodeRun(NodeOutcome.Done(7), 1))

    // And the recorder saw this node's capability call exactly the way it sees a graph run's: same
    // buffers, same wording, so a consumer asserting on the call sequence of one node writes what
    // they already write for a whole graph.
    world.calls.toList shouldBe List("gh issue list --label ready")
    world.logged("plain node ran") shouldBe true
  }

  it should "charge a dispatching node against the budget it was given, so the surviving count is readable without ever holding the ledger" in {
    // `NodeRun.remainingDispatches` is the only thing this testkit ever says about the ledger, and it
    // is a plain `Int` read off the run's own counter. Two budgeted, one dispatch made, one left.
    val world = new TestWorld

    val result = world.runNode(dispatchingNode("Solo", "solo ran"), (), dispatchBudget = 2)

    result.map(_.outcome) shouldBe Right(NodeOutcome.Done(()))
    result.map(_.remainingDispatches) shouldBe Right(1)
    world.callCount("dispatch IMPL") shouldBe 1
    world.logged("solo ran") shouldBe true
  }

  it should "park a node the budget cannot afford, without running it at all" in {
    // The single-node echo of the graph-level budget test above: `Runner.step` checks affordability
    // BEFORE `run`, so a `Cost.OneDispatch` node on an empty budget is parked rather than started,
    // and a consumer can prove that about one node without building a two-node graph to starve.
    val world = new TestWorld

    val result = world.runNode(dispatchingNode("Starved", "starved ran"), (), dispatchBudget = 0)

    result shouldBe Right(NodeRun(NodeOutcome.Stopped(LoopExit.Parked), 0))
    world.callCount("dispatch IMPL") shouldBe 0
    world.logged("starved ran") shouldBe false
    world.logged("dispatch budget exhausted") shouldBe true
  }

  it should "report a node that raises a fault the same way withFaulting already reports one, as a Left carrying the exit" in {
    // A fault is not a value a node returns, it abandons the iteration at the boundary `runNode`
    // establishes internally, so there is no `NodeOutcome` to report and `Left(exit)` is the whole
    // answer. Same shape `withFaulting` uses, deliberately, rather than a third story for the same
    // event. The log line and the rc-50 notify land in this world's own buffers, which is what makes
    // a fault assertable at all rather than merely observable as a missing result.
    val world = new TestWorld

    val faulty = Node[Unit, Unit](
      name = "Faulty",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => summon[Fault].raise("consumer node hit something it cannot recover from")
    )

    val result = world.runNode(faulty, ())

    result shouldBe Left(LoopExit.InfraFault)
    world.logged("consumer node hit something it cannot recover from") shouldBe true
    world.notifications.exists(_.contains("rc=50")) shouldBe true
  }

  it should "still be refused a Runner.Ledger of its own, even though runNode builds one internally" in {
    // The line `runNode` had to hold to exist at all. `Runner.Ledger`'s constructor is
    // `private[litterbox]` on purpose (that class's own doc, issue #32 review finding 2c: a node
    // minting `Ledger(999)` and re-entering `Runner.step` with it), and RFC #26 decision 9 rests on
    // a node never reaching one. `runNode` mints a ledger inside `in.rcard.litterbox`, where that is
    // allowed, and hands back a plain `Int`; this pins that nothing about it widened the type itself.
    //
    // A snippet is the right instrument here and real code is not: the claim is that this expression
    // does not COMPILE, so a real file asserting it would be a file that cannot be compiled. It
    // inherits this file's own package, `com.example.consumer` (see `ConsumerBoundarySpec`'s doc for
    // why that inheritance is reliable), which is exactly the vantage point a node author has.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val myLedger = Runner.Ledger(3)
        |""".stripMargin
    )

    errors should not be empty
    // Pinned on the wording, not merely on non-emptiness (the failure mode round three of #35 found,
    // `GraphValidationSpec`'s own note on it): this has to fail as a private ACCESS violation naming
    // the package that owns it, never as some unrelated resolution error that would keep passing if
    // the constructor were widened tomorrow.
    val messages = errors.map(_.message).mkString("\n")
    messages should include("private")
    messages should include("in.rcard.litterbox")
  }

  // ---- the residual issue #42 makes reachable, pinned rather than left to prose -------------------

  it should "obtain a genuine AgentDispatch.Judged out of a scripted fake, the mint half of the residual src/Caps.scala names" in {
    // `src/Caps.scala`'s fourth guarantee paragraph states that anyone holding a `TestWorld` can mint
    // a real `Judged` with no real reviewer behind it, and that artifact scoping (`test.dep`, never
    // `dep`) is the only control there is. That claim was first reproduced in a scratch miniature
    // that no longer exists. This test is the MINT half of it, run from a foreign package against
    // the real types; the test below is the half that matters, that such a value then clears the
    // guard. Split in two rather than left as one, because the mint alone would be harmless.
    val world = new TestWorld
    world.reviewScripts = List(Script.ReviewScript.Says("VERDICT: APPROVE"))

    val judged: AgentDispatch.Judged[DispatchOutcome] =
      world.agents.review("no reviewer read this", "review.md")

    // A genuine token, and its payload is whatever the script said, never a real reviewer's answer.
    judged.value shouldBe DispatchOutcome.Done
    judged.map(_ => "anything at all").value shouldBe "anything at all"
    world.files("review.md") shouldBe "VERDICT: APPROVE"
  }

  it should "clear a Guard.RequiresReview node with that forged Judged, at both the compile time macro and Runner.step's runtime class check, which is the residual in full" in {
    // The half `src/Caps.scala` actually warns about, and the half its first version of this file
    // left unfalsifiable (#42 review): minting a `Judged` is harmless on its own, what makes it a
    // forged review is that the guard then opens for it. Both gates are exercised here and neither
    // is asserted by hand.
    //
    // The COMPILE TIME gate is this file compiling at all: `LitterBox.graph` runs `checkedPlan`
    // over the literal `Plan` below, and `Guarded`'s input extends `RequiresReviewInput`, so a path
    // reaching it without crossing a `Trust.Reviewed` node is a hard compile error. It crosses
    // `ForgedReview`, which earns `Trust.Reviewed` purely from its declared output type.
    //
    // The RUNTIME gate is `Runner.step`, which faults a `Trust.Reviewed` node whose `Done` value is
    // not an `AgentDispatch.Judged` at runtime. It is not, because the value really is one: scripted
    // fake behind it or not, `TestWorld.agents.review` went through the same `final` mint site
    // `LiveAgentDispatch` does. `LoopExit.Success` below is that check passing.
    val world = new TestWorld
    world.reviewScripts = List(Script.ReviewScript.Says("nothing a real reviewer wrote"))

    val forged = LitterBox.graph(
      name = "consumer-forged-review",
      plan = Plan(
        entry = ForgedReview,
        edges = List(
          Edge.To(ForgedReview, Guarded, _ => Some(PrInput())),
          Edge.Exit(Guarded, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val exit = world.runGraph(forged)

    exit shouldBe LoopExit.Success
    world.logged("guarded node ran on a forged review") shouldBe true

    // And the only reviewer that ever ran was the scripted one. No Docker, no network, no
    // credentials, which is exactly why this is a residual and not merely a testing convenience.
    world.callCount("dispatch REVIEW") shouldBe 1
  }

  /** The two nodes the forged-review test walks. Declared as `val` members with their types spelled
    * out, and referenced unqualified in the `Shape` literal above, because both facts that test
    * exercises are read off those DECLARED TYPES: `ForgedReview`'s `AgentDispatch.Judged[Unit]` output
    * is what earns it `Trust.Reviewed`, and `Guarded`'s `PrInput` input is what earns it
    * `Guard.RequiresReview`. The shared `dispatchingNode` helper above is `Node[Unit, Unit]` and
    * carries neither, so it could not stand in here whatever the macro happened to accept.
    *
    * What the macro itself constrains is narrower than "no `def`": `LitterBox.graph` reads the SOURCE
    * of the `Plan` literal at its own call site, so each element written there has to be a stable
    * path, a `val`, local or an unqualified member of this class, never a node built inline at its own
    * point of use (issue #67 review, `KitMacro.ParseFailure.InlineNodeInPlan`'s own doc has the
    * reasoning: this walk keys such a call by its literal name, but the actual run needs the SAME
    * runtime object everywhere the node is named, and an inline call never gives it one). A helper
    * `def` CALL written straight into the `Plan` is what it also declines, since it never runs that
    * call; binding either kind of construction to a `val` first, exactly what the tests above do with
    * `dispatchingNode`, reads fine and is why they compile.
    */
  private case class PrInput() extends RequiresReviewInput

  private val ForgedReview: Node[Unit, AgentDispatch.Judged[Unit]] = Node(
    name = "ForgedReview",
    cost = Cost.NoDispatch,
    timeout = Timeout.Unbounded,
    probe = _ => None,
    run = _ =>
      NodeOutcome.Done(summon[AgentDispatch].review("no reviewer read this", "review.md").map(_ => ()))
  )

  private val Guarded: Node[PrInput, Unit] = Node(
    name = "Guarded",
    cost = Cost.NoDispatch,
    timeout = Timeout.Unbounded,
    probe = _ => None,
    run = _ =>
      summon[Caps].logger.log("guarded node ran on a forged review")
      NodeOutcome.Done(())
  )
