package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import Script.*

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

  // ---- issue #44: Route.Parked is a real Next.Goto edge into AskHuman -------------------------

  it should "walk Implement, Gate and RouteDecision into AskHuman, reaching LoopExit.Parked, when " +
    "the repair budget exhausts on a gate-RED (the same edge ScenarioSpec/LogParitySpec pin end to " +
    "end; this proves it off the public Workflow value directly, the way the happy-path test above " +
    "does for the ordinary chain)" in {
      val world = TestWorld()
      world.gateResults = List(GateResult.Red, GateResult.Red, GateResult.Red)
      world.fixScripts = List(
        WorkerScript.Produces(newFilePatch),
        WorkerScript.Produces(newFilePatch)
      )
      val caps = buildCaps(world)

      val result = withFaulting:
        val ledger = Runner.Ledger(3) // Config().repairBudget (2) + 1, the ordinary-tick seed
        Runner.run(
          Machine.shippedWorkflow(Config(), caps, summon[Faulting], ledger),
          start(resumeAuthors = None)
        )(using caps, summon[Faulting], ledger)

      result shouldBe Right(LoopExit.Parked)
      world.called("gh issue comment 999") shouldBe true // AskHuman's own probe-miss park
      world.postedIssueComments.last shouldBe (999 -> Machine.ParkBody)
    }

  // ---- declared stage set (issue #40) --------------------------------------------------------

  it should "carry the exact shipped stage set as its own stages field, not a second hand " +
    "maintained list watch.sh would have to be trusted to match" in {
      val world = TestWorld()
      val caps  = buildCaps(world)

      val result = withFaulting:
        val ledger = Runner.Ledger(3)
        Machine.shippedWorkflow(Config(), caps, summon[Faulting], ledger).stages

      result shouldBe Right(Machine.shippedStages)
    }

  // ---- issue #40 review MAJOR 3: the declaration reads Workflow.stages, not the module constant ---
  //
  // The test above and `Machine.declareStages`'s own production call site both drive
  // `shippedWorkflow`, so on their own they can only catch a MISSING declare call, never a WRONG
  // stage set: a `declareStages` that ignored its argument entirely and always logged
  // `Machine.shippedStages` by name would still pass both. This test drives `declareStages` with a
  // `Workflow` whose `stages` genuinely differs from the shipped set, so it can only pass if the
  // declaration really is read off the argument.

  "Machine.declareStages" should "declare the given workflow's own stages field, not the shipped " +
    "one, when the two differ" in {
      val world = TestWorld()

      val customStages = StageSet(
        stages = List(Stage("FOO", "foo", row = 1)),
        anchor = Some("FOO"),
        terminal = None
      )
      val fakeWorkflow = Workflow[Unit]("fake", start = _ => Next.Finish(LoopExit.Success), stages = customStages)

      Machine.declareStages(fakeWorkflow)(using world.status, world.logger)

      world.declaredStages shouldBe List(customStages)
      customStages should not be Machine.shippedStages
    }

  // ---- issue #40 review round 2, MINOR 4: an invalid row is visible, not silently dropped ------

  it should "log one line naming a stage whose row is neither 1 nor 2, since banner.sh silently " +
    "drops it from both chip rows instead of crashing" in {
      val world = TestWorld()

      val badRowStages = StageSet(
        stages = List(Stage("FOO", "foo", row = 3)),
        anchor = None,
        terminal = None
      )
      val fakeWorkflow =
        Workflow[Unit]("fake", start = _ => Next.Finish(LoopExit.Success), stages = badRowStages)

      Machine.declareStages(fakeWorkflow)(using world.status, world.logger)

      world.logLines should have size 1
      world.logLines.head should include("FOO")
      world.logLines.head should include("row 3")
    }

  it should "log nothing when every declared stage's row is 1 or 2, the only rows banner.sh " +
    "ever draws" in {
      val world = TestWorld()

      val goodRowStages = StageSet(
        stages = List(Stage("FOO", "foo", row = 1), Stage("BAR", "bar", row = 2)),
        anchor = None,
        terminal = None
      )
      val fakeWorkflow =
        Workflow[Unit]("fake", start = _ => Next.Finish(LoopExit.Success), stages = goodRowStages)

      Machine.declareStages(fakeWorkflow)(using world.status, world.logger)

      world.logLines shouldBe empty
    }

  "Machine.shippedStages" should "declare row 1 (PICK, IMPL, FAST_GATE, plus FIX as a badge), row 2 " +
    "(REVIEW, PR, CI_WAIT, MERGE), anchor PICK and terminal DONE, reproducing today's banner" in {
      Machine.shippedStages shouldBe StageSet(
        stages = List(
          Stage("PICK", "pick", row = 1, badge = false),
          Stage("IMPL", "impl", row = 1, badge = false),
          Stage("FAST_GATE", "fast", row = 1, badge = false),
          Stage("FIX", "fix", row = 1, badge = true),
          Stage("REVIEW", "rev", row = 2, badge = false),
          Stage("PR", "pr", row = 2, badge = false),
          Stage("CI_WAIT", "ci", row = 2, badge = false),
          Stage("MERGE", "merge", row = 2, badge = false)
        ),
        anchor = Some("PICK"),
        terminal = Some("DONE")
      )
    }
