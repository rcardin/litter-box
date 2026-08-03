package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Proves the shipped pipeline (issue #37) is a genuine `Workflow[Machine.ShippedStart]` value, built
  * from the public kit constructs (`Node`, `Next`, `Workflow`, `Runner`) and drivable directly through
  * them, rather than only ever exercised indirectly through `Machine.runOnce`. That directness holds
  * only for a test inside this package, not for an outside consumer: `Machine.shippedWorkflow` is
  * `private[litterbox]`, and so are both parameter types a caller needs to reach it, `Runner.Ledger`'s
  * constructor and `Faulting` (`Kit.scala`). `ScenarioSpec`/`LogParitySpec` stay the oracle for
  * BEHAVIOUR (the 27 goldens plus the scenario matrix, byte for byte); this file's job is narrower and
  * structural: that `Machine.shippedWorkflow` really is a value built from `Next.Goto`/`Workflow`, not
  * a hand-rolled control flow that merely happens to produce the same `LoopExit`s.
  *
  * Capabilities come from `TestWorld` (`test/Recorder.scala`), the same seam `RunnerSpec` reuses for
  * exactly this reason: it already answers every trait `Caps` bundles, so this file needs no second,
  * locally reimplemented fake.
  */
class ShippedWorkflowSpec extends AnyFlatSpec with Matchers:

  // `buildCaps`/`withFaulting` now live next to `TestWorld` itself (`test/Recorder.scala`, issue #38
  // review nit): this file, `RunnerSpec` and `GraphValidationSpec` each used to carry an identical,
  // separately maintained copy of both.

  private def start(resumeAuthors: Option[List[String]]): Machine.ShippedStart =
    Machine.ShippedStart(
      n = 1,
      cur = Machine.Cursor(),
      issue = 999,
      bodyFile = "issue-999.body.md",
      workerPromptFile = "issue-999.worker.prompt.txt",
      isClass1 = false,
      branch = "us-999",
      resumeAuthors = resumeAuthors,
      carriesParked = false,
      resumedFromInProgress = false
    )

  // `wf.name shouldBe "shipped"` alone is not much more than what the return type
  // `Workflow[ShippedStart]` already forces just by typechecking (a bare `Workflow` case-class field
  // read is not a structural proof of anything `Next.Goto[?, ?]` below doesn't already need to
  // hold), so it is paired here with an assertion the type does NOT force: `start`'s own behaviour
  // on an ordinary tick.
  "Machine.shippedWorkflow" should "be a Workflow[ShippedStart] value named \"shipped\" whose start " +
    "produces a Next.Goto edge into the Implement node on an ordinary (non-resumed) tick" in {
      val world = TestWorld()
      val caps  = buildCaps(world)

      val result = withFaulting:
        val ledger = Runner.Ledger(3)
        val wf     = Machine.shippedWorkflow(Config(), caps, summon[Faulting], ledger)
        wf.name shouldBe "shipped"
        wf.start(start(resumeAuthors = None))

      result match
        case Right(g: Next.Goto[?, ?]) => g.node.name shouldBe "Implement"
        case Right(other)              => fail(s"expected Next.Goto(Implement, ...), got $other")
        case Left(exit)                => fail(s"expected a Next value, faulted instead with $exit")
    }

  it should "start a resumed tick (an accepted human reply on a parked issue) with a Next.Goto edge " +
    "straight into the Repair node, skipping Implement entirely (issue #28 review finding F4)" in {
      val world = TestWorld()
      val caps  = buildCaps(world)

      val result = withFaulting:
        val ledger = Runner.Ledger(2)
        val wf     = Machine.shippedWorkflow(Config(), caps, summon[Faulting], ledger)
        wf.start(start(resumeAuthors = Some(List("alice"))))

      result match
        case Right(g: Next.Goto[?, ?]) => g.node.name shouldBe "Repair"
        case Right(other)              => fail(s"expected Next.Goto(Repair, ...), got $other")
        case Left(exit)                => fail(s"expected a Next value, faulted instead with $exit")
    }

  it should "walk end to end through Runner.run and reach LoopExit.Success on the happy path, proving " +
    "the whole graph, not only its first edge, is reachable off the public Workflow value" in {
      val world = TestWorld()
      val caps  = buildCaps(world)

      val result = withFaulting:
        val ledger = Runner.Ledger(3)
        Runner.run(
          Machine.shippedWorkflow(Config(), caps, summon[Faulting], ledger),
          start(resumeAuthors = None)
        )(using caps, summon[Faulting], ledger)

      result shouldBe Right(LoopExit.Success)
      world.callCount("dispatch IMPL") shouldBe 1
      world.callCount("gate FAST") shouldBe 1
      world.callCount("dispatch REVIEW") shouldBe 1
    }
