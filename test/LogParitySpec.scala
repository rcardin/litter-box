package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import Script.*

/** The load-bearing log-line contract, frozen as golden files.
  *
  * Until slice 1 this spec pinned eight individual needles that `test/statemachine-test.sh` grepped
  * out of the harness's stderr (`checkc NEEDLE "$SB/loop.out"`). That bash oracle is deleted — it
  * scored a bash implementation that no longer exists — so the needles it protected are re-pinned
  * here instead, against the `.log` files under `test/golden`.
  *
  * Golden files rather than needles because the audience is a parser, not a human: `watch.sh` reads
  * this stream, and the eight needles were only ever a sample of it. Pinning the whole stream per
  * scenario catches a dropped line, a reordered pair, and a reworded phrase — all of which the
  * needle form let through. See `Golden` for the regeneration workflow.
  *
  * Assertions that are NOT about the log stream (`w.files`, `w.called`, `w.callCount`) stay as
  * explicit expectations: a golden file freezes what was said, never what was done.
  */
class LogParitySpec extends AnyFlatSpec with Matchers:

  extension (w: TestWorld)
    /** Asserts the whole operator log stream for this scenario against `test/golden/<name>.log`. */
    private def logShouldMatchGolden(name: String): Unit =
      // Trailing newline so the goldens are well-formed text files and `git diff` on them is clean.
      val actual = w.logLines.mkString("", "\n", "\n")
      withClue(s"golden mismatch for '$name' — see Golden's regeneration note\n") {
        actual shouldBe Golden.expected(name, actual)
      }

  // ---- the scenarios the bash oracle used to grep, now pinned whole -------------------------

  "The log stream" should "match the golden for an IMPL timeout (statemachine-test.sh:241)" in {
    val w = TestWorld()
    w.implScript = WorkerScript.TimedOut

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logShouldMatchGolden("impl-timeout")
  }

  it should "match the golden for a FIX timeout (statemachine-test.sh:266)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(WorkerScript.TimedOut)

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logShouldMatchGolden("fix-timeout")
  }

  it should "match the golden for a gate timeout (statemachine-test.sh:372)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Timeout)

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logShouldMatchGolden("gate-timeout")
  }

  it should "match the golden for a protected-path rejection (statemachine-test.sh:329, :558)" in {
    val w = TestWorld()
    w.implScript = WorkerScript.Produces("1\t0\tharness/evil.txt")

    w.runLoop() shouldBe LoopExit.NeedsHuman

    w.logShouldMatchGolden("protected-path")
  }

  it should "match the golden for an oversized patch (statemachine-test.sh:600)" in {
    val w = TestWorld()

    w.runLoop(Config(maxPatchBytes = 10)) shouldBe LoopExit.NeedsHuman

    w.logShouldMatchGolden("oversized-patch")
  }

  it should "match the golden for the idle tick (statemachine-test.sh:292)" in {
    val w = TestWorld()
    w.inProgress = None
    w.ready = None

    w.runLoop() shouldBe LoopExit.Idle

    w.logShouldMatchGolden("idle")
    w.files shouldBe empty // still no sentinel: logging is not writing
  }

  it should "match the golden for three reds exhausting the budget (statemachine-test.sh:212)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red, GateResult.Red, GateResult.Red)

    w.runLoop() shouldBe LoopExit.NeedsHuman

    w.logShouldMatchGolden("three-reds-needs-human")
  }

  // ---- the operator-facing narration --------------------------------------------------------

  it should "match the golden for the happy path: issue, gate, verdict, PR and label flip" in {
    val w = TestWorld()

    w.runLoop() shouldBe LoopExit.Success

    w.logShouldMatchGolden("happy-path")
  }

  it should "match the golden for a gate-RED repair round" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))

    w.runLoop() shouldBe LoopExit.Success

    w.logShouldMatchGolden("gate-red-repair")
  }

  it should "match the golden for a REQUEST_CHANGES repair round" in {
    val w = TestWorld()
    w.reviewScripts = List(
      ReviewScript.Says("tests are missing.\nVERDICT: REQUEST_CHANGES"),
      ReviewScript.Says(approveReview)
    )
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))

    w.runLoop() shouldBe LoopExit.Success

    w.logShouldMatchGolden("request-changes-repair")
  }

  it should "match the golden for a review with no VERDICT sentinel (fail-safe, not APPROVE)" in {
    val w = TestWorld()
    w.reviewScripts =
      List(ReviewScript.Says("looks fine to me"), ReviewScript.Says(approveReview))
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))

    w.runLoop() shouldBe LoopExit.Success // budget spends, then the second review approves

    w.logShouldMatchGolden("missing-verdict")
  }

  it should "match the golden for the auto-merge chain and the dependency flip" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.blockedIssues = List(555)
    w.issueBodies = Map(555 -> "Blocked-by: #999")

    w.runLoop() shouldBe LoopExit.Success

    w.logShouldMatchGolden("auto-merge-chain")
  }

  it should "carry the merge child's rc into the merge-failure line (loop.sh:475)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.mergeRc = 3 // not 1: a wrong-but-nonzero rc would still pass a zero/one-only assertion

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logShouldMatchGolden("merge-rc-carried")
    w.called("gh pr view 123 --json state") shouldBe false // fault raised before verification
  }

  it should "match the golden for a CI-RED auto-merge candidate, with no self-repair" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.ciWaitResult = GateResult.Red

    w.runLoop() shouldBe LoopExit.NeedsHuman

    w.logShouldMatchGolden("ci-red-needs-human")
    w.callCount("dispatch FIX") shouldBe 0
  }

  it should "warn when the CI-RED needs-human flip fails, as bash does (loop.sh:464)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.ciWaitResult = GateResult.Red
    w.labelEditSucceeds = false

    w.runLoop() shouldBe LoopExit.NeedsHuman

    w.logShouldMatchGolden("ci-red-label-flip-failed")
  }

  it should "match the golden for the STOP.md kill-switch, which writes nothing" in {
    val w = TestWorld()
    w.stopFile = true

    w.runLoop() shouldBe LoopExit.ManualStop

    w.logShouldMatchGolden("stop-md")
    w.files shouldBe empty
  }

  it should "match the golden for the DRY_RUN stop point, naming the prompt it rendered" in {
    val w = TestWorld()

    w.runLoop(Config(dryRun = true)) shouldBe LoopExit.DryRun

    w.logShouldMatchGolden("dry-run")
  }

  it should "log BOTH the stage-level and the caller-level line when git apply refuses the patch" in {
    val w = TestWorld()
    w.applySucceeds = false // valid patch, conflicts with the base

    w.runLoop() shouldBe LoopExit.InfraFault

    // loop.sh:569's stage_patch line precedes the caller's own; the golden pins that pairing.
    w.logShouldMatchGolden("git-apply-refused")
  }

  it should "match the golden for an empty IMPL patch, leaving the issue in-progress" in {
    val w = TestWorld()
    w.implScript = WorkerScript.Empty

    w.runLoop() shouldBe LoopExit.NothingMade

    w.logShouldMatchGolden("empty-impl-patch")
    w.called("gh pr create") shouldBe false
  }
