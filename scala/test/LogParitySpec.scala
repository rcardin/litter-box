package harness

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import Script.*

/** The load-bearing log-line compatibility table (slice 3).
  *
  * `harness/test/statemachine-test.sh` scores the harness on its stderr as well as on its
  * behaviour: ten of its assertions are `checkc NEEDLE "$SB/loop.out"`. Eight of those needles are
  * emitted from inside `Machine.runOnce`'s log stream and are reproduced here, one test per needle,
  * against the same scenario the bash suite uses to provoke them, so a parity break shows up in
  * `scala-cli test` rather than only in the oracle run. The remaining two (statemachine-test.sh:347
  * and :580, needle `infra fault — exiting for inspection`) are printed by the driver on its exit
  * path rather than by the loop, so they are pinned in `Main.scala` / `MainSpec.scala`, not here.
  *
  * The remaining lines this spec covers are the ones an operator reads a run by (which issue was
  * picked, which gate pass went red, which PR opened): not currently grepped by the oracle, but
  * part of the same ported `log()` surface and cheap to pin.
  */
class LogParitySpec extends AnyFlatSpec with Matchers:

  // ---- the eight loop-stream needles the bash oracle greps for -----------------------------

  "The log stream" should "carry the IMPL-timeout needle (statemachine-test.sh:241)" in {
    val w = TestWorld()
    w.implScript = WorkerScript.TimedOut

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logged("half-finished worker must not reach the gates") shouldBe true
  }

  it should "carry the FIX-timeout needle (statemachine-test.sh:266)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(WorkerScript.TimedOut)

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logged("FIX worker timed out") shouldBe true
  }

  it should "carry the gate-timeout needle (statemachine-test.sh:372)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Timeout)

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logged("infra fault, not a code failure") shouldBe true
  }

  it should "carry the protected-path needle (statemachine-test.sh:329, :558)" in {
    val w = TestWorld()
    w.implScript = WorkerScript.Produces("1\t0\tharness/evil.txt")

    w.runLoop() shouldBe LoopExit.NeedsHuman

    w.logged("protected-path") shouldBe true
  }

  it should "carry the oversized-patch needle (statemachine-test.sh:600)" in {
    val w = TestWorld()

    w.runLoop(Config(maxPatchBytes = 10)) shouldBe LoopExit.NeedsHuman

    w.logged("oversized-patch") shouldBe true
  }

  it should "carry the idle needle (statemachine-test.sh:292)" in {
    val w = TestWorld()
    w.inProgress = None
    w.ready = None

    w.runLoop() shouldBe LoopExit.Idle

    w.logged("idle, exiting") shouldBe true
    w.files shouldBe empty // still no sentinel: logging is not writing
  }

  it should "carry the needs-human needle (statemachine-test.sh:212)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red, GateResult.Red, GateResult.Red)

    w.runLoop() shouldBe LoopExit.NeedsHuman

    w.logged("needs-human") shouldBe true
  }

  // ---- the operator-facing narration ------------------------------------------------------

  it should "name the picked issue, the gate outcome, the verdict, the PR and the label flip" in {
    val w = TestWorld()

    w.runLoop() shouldBe LoopExit.Success

    w.logged("iteration 1 -> issue #999") shouldBe true
    w.logged("FAST gate GREEN (pass 1)") shouldBe true
    w.logged("reviewer verdict: APPROVE (pass 1)") shouldBe true
    w.logged("PR #123 opened for #999") shouldBe true
    w.logged("issue #999 -> needs-review") shouldBe true
  }

  it should "narrate a gate-RED repair round with the budget left after it" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))

    w.runLoop() shouldBe LoopExit.Success

    w.logged("FAST gate RED (pass 1") shouldBe true
    w.logged("self-repair: budget now 1 — dispatching FIX for gate-RED") shouldBe true
  }

  it should "narrate a REQUEST_CHANGES repair round" in {
    val w = TestWorld()
    w.reviewScripts = List(
      ReviewScript.Says("tests are missing.\nVERDICT: REQUEST_CHANGES"),
      ReviewScript.Says(approveReview)
    )
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))

    w.runLoop() shouldBe LoopExit.Success

    w.logged("reviewer verdict: REQUEST_CHANGES (pass 1)") shouldBe true
    w.logged("self-repair: budget now 1 — dispatching FIX for REQUEST_CHANGES") shouldBe true
  }

  it should "flag a review with no VERDICT sentinel as the fail-safe, not as an APPROVE" in {
    val w = TestWorld()
    w.reviewScripts =
      List(ReviewScript.Says("looks fine to me"), ReviewScript.Says(approveReview))
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))

    w.runLoop() shouldBe LoopExit.Success // budget spends, then the second review approves

    w.logged("reviewer emitted no VERDICT sentinel — fail-safe REQUEST_CHANGES") shouldBe true
  }

  it should "narrate the auto-merge chain and the dependency flip" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.blockedIssues = List(555)
    w.issueBodies = Map(555 -> "Blocked-by: #999")

    w.runLoop() shouldBe LoopExit.Success

    w.logged("CI check registered on PR #123") shouldBe true
    w.logged("CI green — merging PR #123") shouldBe true
    w.logged("dependency #999 closed — flipping #555 blocked -> ready") shouldBe true
  }

  it should "carry the merge child's rc into the merge-failure line (loop.sh:475)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.mergeRc = 3 // not 1: a wrong-but-nonzero rc would still pass a zero/one-only assertion

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logged("merge command failed rc=3 — infra fault") shouldBe true
    w.called("gh pr view 123 --json state") shouldBe false // fault raised before verification
  }

  it should "report a CI-RED auto-merge candidate as needs-human without self-repair" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.ciWaitResult = GateResult.Red

    w.runLoop() shouldBe LoopExit.NeedsHuman

    w.logged(
      "CI RED on PR #123 after local gates green — needs-human, no merge, no self-repair"
    ) shouldBe true
    w.callCount("dispatch FIX") shouldBe 0
  }

  it should "warn when the CI-RED needs-human flip fails, as bash does (loop.sh:464)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.ciWaitResult = GateResult.Red
    w.labelEditSucceeds = false

    w.runLoop() shouldBe LoopExit.NeedsHuman

    w.logged("WARNING: could not flip #999 to needs-human (flip by hand)") shouldBe true
  }

  it should "log the STOP.md kill-switch without writing anything" in {
    val w = TestWorld()
    w.stopFile = true

    w.runLoop() shouldBe LoopExit.ManualStop

    w.logged("STOP.md present (manual kill-switch) — exiting") shouldBe true
    w.files shouldBe empty
  }

  it should "log the DRY_RUN stop point, naming the prompt it rendered" in {
    val w = TestWorld()

    w.runLoop(Config(dryRun = true)) shouldBe LoopExit.DryRun

    w.logged(
      "DRY_RUN=1 — rendered worker prompt for #999 -> harness/logs/issue-999.prompt.txt"
    ) shouldBe true
  }

  it should "log BOTH the stage-level and the caller-level line when git apply refuses the patch" in {
    val w = TestWorld()
    w.applySucceeds = false // valid patch, conflicts with the base

    w.runLoop() shouldBe LoopExit.InfraFault

    // loop.sh:569, emitted inside stage_patch before the caller's own line.
    w.logged(
      "git apply refused the patch (see harness/logs/issue-999-iter1.impl.patch.apply.err) — infra fault, no budget spent"
    ) shouldBe true
    w.logged("IMPL patch did not apply — infra fault, no budget spent") shouldBe true
  }

  it should "log an empty IMPL patch as nothing-produced, leaving the issue in-progress" in {
    val w = TestWorld()
    w.implScript = WorkerScript.Empty

    w.runLoop() shouldBe LoopExit.NothingMade

    w.logged("no changes produced by the iteration") shouldBe true
    w.called("gh pr create") shouldBe false
  }
