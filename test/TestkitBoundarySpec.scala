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
  * Unlike the two `com.example.consumer` specs that came before it, every test here is REAL CODE,
  * with no `scala.compiletime.testing.typeCheckErrors` snippet anywhere. Those two files are about
  * what a consumer can WRITE DOWN, a compile-time question a snippet answers honestly. #42's promise
  * is that a consumer can RUN their node, with no Docker, no network and no credentials, and assert
  * on what it did; a snippet that only type-checks would prove none of that.
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
      workflow = Workflow[Unit](
        "consumer-testkit",
        start = (_: Unit) =>
          Next.Goto(first, (), _ => Next.Goto(second, (), _ => Next.Finish(LoopExit.Success)))
      ),
      shape = Shape(entry = List(first), transitions = List(Transition(first, second))),
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
      workflow = Workflow[Unit](
        "consumer-testkit-budget",
        start = (_: Unit) =>
          Next.Goto(first, (), _ => Next.Goto(second, (), _ => Next.Finish(LoopExit.Success)))
      ),
      shape = Shape(entry = List(first), transitions = List(Transition(first, second))),
      dispatchBudget = _ => 1,
      startInput = _ => ()
    )

    val exit = world.runGraph(myGraph)

    exit shouldBe LoopExit.Parked
    world.callCount("dispatch IMPL") shouldBe 1
    world.logged("second ran") shouldBe false
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
    // The COMPILE TIME gate is this file compiling at all: `LitterBox.graph` runs `checkedShapeStrict`
    // over the literal `Shape` below, and `Guarded`'s input extends `RequiresReviewInput`, so a path
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
      workflow = Workflow[Unit](
        "consumer-forged-review",
        start = (_: Unit) =>
          Next.Goto(
            ForgedReview,
            (),
            _ => Next.Goto(Guarded, PrInput(), _ => Next.Finish(LoopExit.Success))
          )
      ),
      shape = Shape(entry = List(ForgedReview), transitions = List(Transition(ForgedReview, Guarded))),
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

  /** The two nodes the forged-review test walks. Top level `val` members of this class, referenced
    * unqualified in the `Shape` literal below, because `LitterBox.graph`'s macro reads the SOURCE at
    * its own call site: a node built by a `def` is a form it declines outright, so the helper shape
    * every other test in this file uses would not compile there.
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
