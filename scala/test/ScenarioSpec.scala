package harness

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import Script.*

/** The scenario matrix of harness/test/statemachine-test.sh (scenarios DRY, A-T) plus the semantics
  * bullets of the design doc, ported to in-memory scripted handlers.
  */
class ScenarioSpec extends AnyFlatSpec with Matchers:

  def runLoop(w: TestWorld, cfg: Config = Config()): LoopExit =
    Machine.runOnce(1)(using
      cfg,
      w.github,
      w.git,
      w.agents,
      w.gates,
      w.status,
      w.notifier,
      w.fs,
      w.clock
    )

  // ---- STOP.md ----------------------------------------------------------------------------

  "The machine" should "exit ManualStop (rc 10) when STOP.md is present, touching nothing" in {
    val w = TestWorld()
    w.stopFile = true

    val exit = runLoop(w)

    exit shouldBe LoopExit.ManualStop
    exit.rc shouldBe 10
    w.called("gh issue list") shouldBe false
    w.called("gh issue edit") shouldBe false
    w.calls shouldBe empty
  }

  // ---- Scenario F: idle must not latch ---------------------------------------------------

  it should "exit Idle (rc 11) with NO sentinel written when no in-progress or ready issue exists" in {
    val w = TestWorld()
    w.inProgress = None
    w.ready = None

    val exit = runLoop(w)

    exit shouldBe LoopExit.Idle
    exit.rc shouldBe 11
    w.files shouldBe empty                   // idle writes nothing, ever (PR #17 latch bug)
    w.called("gh issue edit") shouldBe false // nothing started
    w.called("gh issue list --label in-progress") shouldBe true
    w.called("gh issue list --label ready") shouldBe true
  }

  it should "resume automatically on the very next tick once a US goes ready, with no manual reset" in {
    val w = TestWorld()
    w.inProgress = None
    w.ready = None

    val first = runLoop(w)

    first shouldBe LoopExit.Idle
    first.rc shouldBe 11
    w.files shouldBe empty

    // Now a US goes ready: the very next tick must resume on its own.
    w.ready = Some(999)

    val second = runLoop(w)

    second shouldBe LoopExit.Success
    second.rc shouldBe 0
    w.called("gh issue edit 999 --add-label in-progress --remove-label ready") shouldBe true
  }

  it should "resume an in-progress issue before considering ready ones" in {
    val w = TestWorld()
    w.inProgress = Some(777)
    w.ready = Some(999)

    runLoop(w, Config(dryRun = true))

    w.called("gh issue view 777 --json title,body") shouldBe true
    w.called("gh issue view 999") shouldBe false
  }

  // ---- Scenario DRY: DRY_RUN renders the worker prompt, no mutation ------------------------

  it should "stop at DryRun (rc 20) with the worker prompt rendered and zero mutations" in {
    val w = TestWorld()

    val exit = runLoop(w, Config(dryRun = true))

    exit shouldBe LoopExit.DryRun
    exit.rc shouldBe 20
    // the worker prompt was rendered with the issue body spliced in
    w.files("harness/logs/issue-999.prompt.txt") should include("AC1: implement the slice")
    // truly read-only: no label mutation, no branch, no fetch, no PR
    w.called("gh issue edit") shouldBe false
    w.called("gh pr create") shouldBe false
    w.called("git checkout") shouldBe false
    w.called("git fetch") shouldBe false
    w.phaseSeq shouldBe List("PICK", "DONE")
  }

  // ---- Scenario A: APPROVE happy path -> needs-review, exit 0 ------------------------------

  it should "reach a PR and needs-review on APPROVE (Scenario A)" in {
    val w = TestWorld()

    val exit = runLoop(w)

    exit shouldBe LoopExit.Success
    exit.rc shouldBe 0
    w.called("gh issue edit 999 --add-label in-progress --remove-label ready") shouldBe true
    w.callCount("gate FAST") shouldBe 1    // one fast-gate pass only
    w.callCount("dispatch FIX") shouldBe 0 // no repair
    w.callCount("dispatch REVIEW") shouldBe 1
    // the review prompt got conventions, tamper report and the diff spliced in
    val reviewPrompt = w.files("harness/logs/issue-999-pass1.review.prompt.txt")
    reviewPrompt should include("Conventions: onion layout")
    reviewPrompt should include("Test-tamper report")
    reviewPrompt should include("src/main/scala/Slice.scala")
    w.commitMessages should have size 1
    w.commitMessages.head should include(
      "feat(US-999): autonomous iteration — reviewer APPROVE, gate GREEN"
    )
    w.pushedBranches shouldBe List("us-999")
    w.called("gh pr create --head us-999") shouldBe true
    w.prBodies.head should include("Closes #999")
    w.prBodies.head should include("Not auto-merged")
    w.called("gh issue edit 999 --add-label needs-review --remove-label in-progress") shouldBe true
    // no auto-merge machinery on the non-class-1 path
    w.called("gate CI-WAIT") shouldBe false
    w.called("gh pr merge") shouldBe false
    w.notifications shouldBe empty
    // logfile fields are repo-relative, never absolute
    w.events.foreach(e => e.logfile should not startWith "/")
    w.phaseSeq shouldBe List("PICK", "IMPL", "FAST_GATE", "REVIEW", "PR", "DONE")
  }

  // ---- Scenario M: class-2 SUCCESS -> stop-at-PR, no CI wait, no merge ---------------------

  it should "stop at the PR for a class-2 SUCCESS: needs-review, no CI wait, no merge (Scenario M)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-2")

    val exit = runLoop(w)

    exit shouldBe LoopExit.Success
    w.called("gh issue edit 999 --add-label needs-review --remove-label in-progress") shouldBe true
    w.called("gate CI-WAIT") shouldBe false
    w.called("gh pr view 123 --json statusCheckRollup") shouldBe false
    w.called("gh pr merge") shouldBe false
  }

  // ---- Scenario J: class-1 SUCCESS + CI green -> auto-merge, flip blocked, notify ----------

  it should "auto-merge a class-1 SUCCESS after CI green, flip unblocked dependents and notify (Scenario J)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.blockedIssues = List(555, 666)
    w.issueBodies = Map(555 -> "Blocked-by: #999\n", 666 -> "Blocked-by: #999\nBlocked-by: #777\n")
    w.issueStates = Map(777 -> "OPEN")

    val exit = runLoop(w)

    exit shouldBe LoopExit.Success
    w.called("gate CI-WAIT") shouldBe true // CI wait ran
    w.called("gh pr merge 123 --squash --delete-branch") shouldBe true
    // loop.sh:473 appends the merge output to the SAME ci_log the CI watch just wrote
    w.called(
      "gate CI-WAIT cmd=gh pr checks 123 --watch --fail-fast log=harness/logs/issue-999.ci-wait.log"
    ) shouldBe true
    w.called(
      "gh pr merge 123 --squash --delete-branch >>harness/logs/issue-999.ci-wait.log"
    ) shouldBe true
    w.called("gh pr view 123 --json state") shouldBe true // merge verified
    w.called("--add-label needs-review") shouldBe false   // auto-merge owns the fate
    w.called("gh issue edit 999 --remove-label in-progress") shouldBe true
    w.notifications shouldBe List("harness: #999 auto-merged (PR #123, CI green, reviewer APPROVE)")
    // blocked -> ready flip: 555's only dep is the just-merged issue; 666 still waits on #777
    w.called("gh issue edit 555 --add-label ready --remove-label blocked") shouldBe true
    w.called("gh issue edit 666 --add-label ready") shouldBe false
    // post-merge fetch so the next tick starts from the new main
    w.callCount("git fetch origin main") shouldBe 2
    w.phaseSeq shouldBe List(
      "PICK",
      "IMPL",
      "FAST_GATE",
      "REVIEW",
      "PR",
      "CI_WAIT",
      "MERGE",
      "DONE"
    )
  }

  // ---- Scenario B: REQUEST_CHANGES -> exactly one fix, re-gate, re-review APPROVE ----------

  it should "dispatch exactly one FIX on REQUEST_CHANGES, re-gate, and approve (Scenario B)" in {
    val w = TestWorld()
    w.reviewScripts = List(
      ReviewScript.Says("VERDICT: REQUEST_CHANGES"),
      ReviewScript.Says("VERDICT: APPROVE")
    )
    w.fixScripts = List(WorkerScript.Produces("1\t0\tsrc/main/scala/SliceFixed.scala"))

    val exit = runLoop(w)

    exit shouldBe LoopExit.Success
    w.callCount("dispatch FIX") shouldBe 1 // exactly one fix
    w.callCount("gate FAST") shouldBe 2    // re-gate, no third pass
    w.callCount("dispatch REVIEW") shouldBe 2
    w.called("gh issue edit 999 --add-label needs-review --remove-label in-progress") shouldBe true
    // the fix prompt carried the reviewer's complaint and was rendered per pass
    w.files("harness/logs/issue-999-pass1.fix.prompt.txt") should include(
      "The independent reviewer requested changes"
    )
    // one budget unit spent: the FIX phase event carries budget 1 (of 2)
    w.events.filter(_.phase == "FIX").map(_.budget).distinct shouldBe List(1)
    // the FIX dispatch was seeded with the prior cumulative patch
    w.calls.find(_.startsWith("dispatch FIX")).get should include(
      "currentPatch=harness/logs/issue-999-iter1.impl.patch"
    )
  }

  // ---- Scenario C: gate-RED exhausts the shared budget -> needs-human + audit PR -----------

  it should "exhaust the shared budget on repeated gate-RED and route to needs-human with an audit PR (Scenario C)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red, GateResult.Red, GateResult.Red)
    w.fixScripts = List(
      WorkerScript.Produces("1\t0\tsrc/main/scala/Fix1.scala"),
      WorkerScript.Produces("1\t0\tsrc/main/scala/Fix2.scala")
    )

    val exit = runLoop(w)

    exit shouldBe LoopExit.NeedsHuman
    exit.rc shouldBe 40
    w.callCount("dispatch FIX") shouldBe 2    // exactly two fixes (budget 2)
    w.callCount("gate FAST") shouldBe 3       // 2 fixes + final RED, no fourth pass
    w.callCount("dispatch REVIEW") shouldBe 0 // RED never renders a review prompt
    w.called("gh issue edit 999 --add-label needs-human --remove-label in-progress") shouldBe true
    w.called("gh pr create") shouldBe true // PR still opened (audit trail)
    w.notifications shouldBe List("harness: #999 needs-human (gate-RED, gate RED)")
    w.commitMessages.head should include("self-repair budget exhausted (gate-RED), gate RED")
    w.prBodies.head should include(
      "**Needs human** — self-repair budget of 2 exhausted on gate-RED (last gate RED)"
    )
  }

  it should "exhaust the shared budget on repeated REQUEST_CHANGES via the same pool" in {
    val w = TestWorld()
    w.reviewScripts = List(
      ReviewScript.Says("VERDICT: REQUEST_CHANGES"),
      ReviewScript.Says("VERDICT: REQUEST_CHANGES"),
      ReviewScript.Says("VERDICT: REQUEST_CHANGES")
    )
    w.fixScripts = List(
      WorkerScript.Produces("1\t0\tsrc/main/scala/Fix1.scala"),
      WorkerScript.Produces("1\t0\tsrc/main/scala/Fix2.scala")
    )

    val exit = runLoop(w)

    exit shouldBe LoopExit.NeedsHuman
    w.callCount("dispatch FIX") shouldBe 2
    w.callCount("dispatch REVIEW") shouldBe 3
    w.called("gh issue edit 999 --add-label needs-human --remove-label in-progress") shouldBe true
    w.notifications shouldBe List("harness: #999 needs-human (REQUEST_CHANGES, gate GREEN)")
    w.commitMessages.head should include(
      "self-repair budget exhausted (REQUEST_CHANGES), gate GREEN"
    )
  }

  // ---- Scenario D: IMPL dispatch timeout -> rc 50, budget untouched, nothing dispatched ----

  it should "exit InfraFault (rc 50) on an IMPL dispatch timeout: no budget spent, no gates, no PR, resumable (Scenario D)" in {
    val w = TestWorld()
    w.implScript = WorkerScript.TimedOut
    w.fixScripts = List(WorkerScript.Produces(newFilePatch)) // must never be consumed

    val exit = runLoop(w)

    exit shouldBe LoopExit.InfraFault
    exit.rc shouldBe 50
    w.callCount("dispatch FIX") shouldBe 0 // zero FIX (no budget spent)
    w.callCount("gate FAST") shouldBe 0    // a timed-out worker never reaches the gates
    w.called("gh pr create") shouldBe false
    w.called("needs-human") shouldBe false
    w.called("gh issue edit 999 --add-label in-progress --remove-label ready") shouldBe true
    w.callCount("--remove-label in-progress") shouldBe 0 // resumable next tick
    w.phaseSeq shouldBe List("PICK", "IMPL", "DONE")     // stops at the timed-out IMPL
    w.events.find(e => e.phase == "IMPL" && e.state == "red").get.detail shouldBe "timeout"
    w.notifications shouldBe List(
      "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
    )
  }

  // ---- Scenario E: FIX dispatch timeout -> rc 50, no PR, in-progress kept ------------------

  it should "exit InfraFault on a FIX dispatch timeout without spending further budget (Scenario E)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(WorkerScript.TimedOut)

    val exit = runLoop(w)

    exit shouldBe LoopExit.InfraFault
    w.callCount("dispatch FIX") shouldBe 1 // one FIX attempted, then halted
    w.callCount("dispatch REVIEW") shouldBe 0
    w.called("gh pr create") shouldBe false
    w.called("needs-human") shouldBe false
    w.callCount("--remove-label in-progress") shouldBe 0 // resumable next tick
    w.notifications shouldBe List(
      "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
    )
  }

  // ---- REVIEW dispatch timeout -> rc 50, budget untouched ----------------------------------

  it should "exit InfraFault on a REVIEW dispatch timeout without spending budget" in {
    val w = TestWorld()
    w.reviewScripts = List(ReviewScript.TimedOut)
    w.fixScripts = List(WorkerScript.Produces(newFilePatch)) // must never be consumed

    val exit = runLoop(w)

    exit shouldBe LoopExit.InfraFault
    w.callCount("dispatch FIX") shouldBe 0
    w.called("gh pr create") shouldBe false
    w.events.find(e => e.phase == "REVIEW" && e.state == "red").get.detail shouldBe "timeout"
    w.callCount("--remove-label in-progress") shouldBe 0
    w.notifications shouldBe List(
      "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
    )
  }

  // ---- Scenario H: empty reviewer output = infra fault (NOT a fail-safe verdict) -----------

  it should "treat an empty reviewer output as an infra fault, not a verdict (Scenario H)" in {
    val w = TestWorld()
    w.reviewScripts = List(ReviewScript.Says("  \n \t "))
    w.fixScripts = List(WorkerScript.Produces(newFilePatch)) // must never be consumed

    val exit = runLoop(w)

    exit shouldBe LoopExit.InfraFault
    w.callCount("dispatch FIX") shouldBe 0 // spends nothing
    w.called("gh pr create") shouldBe false
    w.called("needs-human") shouldBe false
    w.events.find(e => e.phase == "REVIEW" && e.state == "red").get.detail shouldBe "empty review"
    w.callCount("--remove-label in-progress") shouldBe 0
  }

  // ---- fail-safe: non-empty review missing the VERDICT sentinel = REQUEST_CHANGES ----------

  it should "fail-safe a non-empty review with no VERDICT sentinel to REQUEST_CHANGES, spending budget" in {
    val w = TestWorld()
    w.reviewScripts = List(
      ReviewScript.Says("looks plausible; I forgot the sentinel."),
      ReviewScript.Says(approveReview)
    )
    w.fixScripts = List(WorkerScript.Produces("1\t0\tsrc/main/scala/Fix1.scala"))

    val exit = runLoop(w)

    exit shouldBe LoopExit.Success
    w.callCount("dispatch FIX") shouldBe 1 // the fail-safe verdict SPENDS budget
    w.events
      .filter(e => e.phase == "REVIEW" && e.state == "ok")
      .head
      .detail shouldBe "verdict=REQUEST_CHANGES"
  }

  it should "honour the LAST VERDICT sentinel in a review (grep | tail -1)" in {
    val w = TestWorld()
    w.reviewScripts = List(
      ReviewScript.Says("draft says VERDICT: REQUEST_CHANGES but on reflection\nVERDICT: APPROVE")
    )

    val exit = runLoop(w)

    exit shouldBe LoopExit.Success
    w.callCount("dispatch FIX") shouldBe 0
  }

  // ---- Scenario I: gate timeout (rc 124) = infra fault, no budget spent --------------------

  it should "exit InfraFault on a gate timeout without spending budget (Scenario I)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Timeout)
    w.fixScripts = List(WorkerScript.Produces(newFilePatch)) // must never be consumed

    val exit = runLoop(w)

    exit shouldBe LoopExit.InfraFault
    w.callCount("dispatch FIX") shouldBe 0
    w.callCount("dispatch REVIEW") shouldBe 0
    w.called("gh pr create") shouldBe false
    w.called("needs-human") shouldBe false
    w.notifications shouldBe List(
      "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
    )
  }

  // ---- Scenario K: class-1 + CI RED -> needs-human, NO merge, no self-repair ---------------

  it should "flip to needs-human on CI RED after green local gates, never merging or self-repairing (Scenario K)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.ciWaitResult = GateResult.Red
    w.fixScripts = List(WorkerScript.Produces(newFilePatch)) // must never be consumed

    val exit = runLoop(w)

    exit shouldBe LoopExit.NeedsHuman
    w.called("gh pr merge") shouldBe false      // NO merge attempted
    w.called("gh pr comment 123") shouldBe true // PR comment explains CI red
    w.called("gh issue edit 999 --add-label needs-human --remove-label in-progress") shouldBe true
    w.callCount("dispatch FIX") shouldBe 0 // never self-repair against CI
    w.notifications shouldBe List("harness: #999 CI RED -> needs-human (PR #123)")
  }

  // ---- Scenario L: CI wait timeout -> rc 50, issue stays in-progress -----------------------

  it should "exit InfraFault when the CI wait hits its bound, leaving the issue in-progress (Scenario L)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.ciWaitResult = GateResult.Timeout

    val exit = runLoop(w)

    exit shouldBe LoopExit.InfraFault
    w.called("gh pr merge") shouldBe false
    w.called("needs-human") shouldBe false
    w.callCount("--remove-label in-progress") shouldBe 0 // stays in-progress for resume
    w.notifications shouldBe List(
      "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
    )
  }

  // ---- Scenario P: CI check registers late -> the loop waits, then merges ------------------

  it should "poll until the CI check registers, then watch and merge (Scenario P)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.rollupCounts = List(0, 0, 1)
    val cfg = Config(ciAppearInterval = 1, ciAppearTimeout = 30)

    val exit = runLoop(w, cfg)

    exit shouldBe LoopExit.Success
    w.callCount("gh pr view 123 --json statusCheckRollup") shouldBe 3 // polled until registered
    w.sleeps shouldBe List(1, 1)
    w.called("gate CI-WAIT") shouldBe true // watch ran only after appearance
    w.called("gh pr merge 123 --squash --delete-branch") shouldBe true
    w.called("needs-human") shouldBe false // empty rollup is not a red build
    w.called("gh pr comment") shouldBe false
  }

  // ---- Scenario Q: CI check never registers -> rc 50, NOT needs-human ----------------------

  it should "exit InfraFault when no CI check ever registers, never reading that as red (Scenario Q)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.rollupCounts = List(0)
    val cfg = Config(ciAppearInterval = 1, ciAppearTimeout = 3)

    val exit = runLoop(w, cfg)

    exit shouldBe LoopExit.InfraFault
    w.called("gate CI-WAIT") shouldBe false // nothing to watch
    w.called("gh pr merge") shouldBe false
    w.called("needs-human") shouldBe false
    w.callCount("--remove-label in-progress") shouldBe 0
    w.sleeps shouldBe List(1, 1, 1)
    w.notifications shouldBe List(
      "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
    )
  }

  // ---- Scenario N: merge not verified (PR state != MERGED) -> rc 50 ------------------------

  it should "exit InfraFault when the merge cannot be verified as MERGED (Scenario N)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.prStateAnswer = "OPEN"

    val exit = runLoop(w)

    exit shouldBe LoopExit.InfraFault
    w.called("gh pr merge 123") shouldBe true            // merge WAS attempted
    w.callCount("--remove-label in-progress") shouldBe 0 // unverified: nothing flipped
    w.notifications shouldBe List(
      "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
    )
  }

  // ---- Scenario O: merge command fails -> rc 50, verify not reached ------------------------

  it should "exit InfraFault when the merge command fails, before any verification (Scenario O)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.mergeSucceeds = false

    val exit = runLoop(w)

    exit shouldBe LoopExit.InfraFault
    w.called("gh pr view 123 --json state") shouldBe false // verify not reached
    w.callCount("--remove-label in-progress") shouldBe 0
    w.notifications shouldBe List(
      "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
    )
  }

  // ---- Scenarios G/R: protected-path patch -> marker, gate SKIPPED, needs-human + audit PR -

  it should "reject a protected-path IMPL patch: marker staged, repair loop skipped, needs-human (Scenarios G/R)" in {
    val w = TestWorld()
    w.implScript = WorkerScript.Produces("1\t0\tharness/evil.txt")
    w.fixScripts = List(WorkerScript.Produces(newFilePatch)) // must never be consumed

    val exit = runLoop(w)

    exit shouldBe LoopExit.NeedsHuman
    w.callCount("dispatch FIX") shouldBe 0    // fixer = violating agent class
    w.callCount("dispatch REVIEW") shouldBe 0 // reviewer never ran
    w.callCount("gate FAST") shouldBe 0       // guard rejection short-circuits the gates
    w.appliedPatches shouldBe empty           // the rejected patch was NEVER applied
    w.files("PATCH-REJECTED.md") should include("protected path")
    w.files("PATCH-REJECTED.md") should include("harness/evil.txt") // numstat in the marker
    w.called("git add PATCH-REJECTED.md") shouldBe true
    w.called("gh issue edit 999 --add-label needs-human --remove-label in-progress") shouldBe true
    w.called("gh pr create") shouldBe true // PR still opened (audit trail)
    w.commitMessages.head should include("patch guard rejection (protected-path), gate SKIPPED")
    w.notifications shouldBe List("harness: #999 needs-human (protected-path, gate SKIPPED)")
    w.prBodies.head should include("must NOT be merged")
  }

  it should "guard every protected path class and let ordinary source paths through" in {
    def numstat(p: String) = s"1\t0\t$p"
    val protectedPaths     =
      List(
        "harness/evil.txt",
        ".github/workflows/evil.yml",
        "docs/x.md",
        "CONTEXT.md",
        "PROMPT.md",
        "STOP.md"
      )
    protectedPaths.foreach(p => withClue(p) { Machine.touchesProtected(numstat(p)) shouldBe true })
    List("src/main/scala/A.scala", "src/test/scala/ATest.scala", "build.sbt", "README.md")
      .foreach(p => withClue(p) { Machine.touchesProtected(numstat(p)) shouldBe false })
  }

  // ---- Scenario T: oversized patch -> marker, gate SKIPPED, needs-human + audit PR ---------

  it should "reject an oversized IMPL patch without applying it and route to needs-human (Scenario T)" in {
    val w = TestWorld()
    w.fixScripts = List(WorkerScript.Produces(newFilePatch)) // must never be consumed

    val exit = runLoop(w, Config(maxPatchBytes = 10)) // any real patch exceeds the tiny cap

    exit shouldBe LoopExit.NeedsHuman
    w.callCount("dispatch FIX") shouldBe 0
    w.callCount("dispatch REVIEW") shouldBe 0
    w.callCount("gate FAST") shouldBe 0
    w.appliedPatches shouldBe empty // oversized patch NOT applied
    w.files("PATCH-REJECTED.md") should include("Oversized patch")
    w.called("gh issue edit 999 --add-label needs-human --remove-label in-progress") shouldBe true
    w.called("gh pr create") shouldBe true
    w.commitMessages.head should include("patch guard rejection (oversized-patch), gate SKIPPED")
    w.notifications shouldBe List("harness: #999 needs-human (oversized-patch, gate SKIPPED)")
  }

  // ---- Scenario S: patch that fails to apply -> rc 50, no budget spent ---------------------

  it should "treat an apply conflict as an infra fault, never a gate failure (Scenario S)" in {
    val w = TestWorld()
    w.applySucceeds = false                                  // valid patch, conflicts with the base
    w.fixScripts = List(WorkerScript.Produces(newFilePatch)) // must never be consumed

    val exit = runLoop(w)

    exit shouldBe LoopExit.InfraFault
    w.callCount("dispatch FIX") shouldBe 0 // no budget spent
    w.callCount("gate FAST") shouldBe 0    // apply precedes the gates
    w.called("gh pr create") shouldBe false
    w.called("needs-human") shouldBe false
    w.callCount("--remove-label in-progress") shouldBe 0
    w.notifications shouldBe List(
      "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
    )
  }

  it should "fail open on an unparseable patch: empty numstat passes the guard, apply then faults (backstop)" in {
    val w = TestWorld()
    w.implScript = WorkerScript.Produces("this is not a unified diff at all")

    val exit = runLoop(w)

    exit shouldBe LoopExit.InfraFault // ApplyFail, never a gate failure
    w.callCount("gate FAST") shouldBe 0
    w.files.contains("PATCH-REJECTED.md") shouldBe false // guard passed (fail-open)
    w.callCount("dispatch FIX") shouldBe 0
  }

  // ---- empty IMPL patch -> rc 30, no PR, issue stays in-progress ---------------------------

  it should "exit NothingMade (rc 30) on an empty IMPL patch, leaving the issue in-progress" in {
    val w = TestWorld()
    w.implScript = WorkerScript.Empty

    val exit = runLoop(w)

    exit shouldBe LoopExit.NothingMade
    exit.rc shouldBe 30
    w.called("gh pr create") shouldBe false
    w.callCount("gate FAST") shouldBe 0
    w.callCount("--remove-label in-progress") shouldBe 0
    w.events.find(e => e.phase == "IMPL" && e.state == "ok").get.detail shouldBe "no diff"
    w.notifications shouldBe empty
  }

  // ---- empty FIX patch -> FIX-EMPTY marker, needs-human ------------------------------------

  it should "route an empty FIX patch to needs-human with the FIX-EMPTY audit marker" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(WorkerScript.Empty) // the fixer reverted all prior work

    val exit = runLoop(w)

    exit shouldBe LoopExit.NeedsHuman
    w.callCount("dispatch FIX") shouldBe 1
    w.files("FIX-EMPTY.md") should include("Fixer produced no diff")
    w.called("git add FIX-EMPTY.md") shouldBe true
    w.called("gh issue edit 999 --add-label needs-human --remove-label in-progress") shouldBe true
    w.called("gh pr create") shouldBe true // audit PR with only the marker
    w.commitMessages.head should include("fixer produced no diff (empty-fix), gate RED")
    w.notifications shouldBe List("harness: #999 needs-human (empty-fix, gate RED)")
    w.prBodies.head should include("the prior implementation is NOT on it")
  }

  // ---- guard rejection on a FIX patch -> terminal FAIL, no further repair ------------------

  it should "route a protected-path FIX patch straight to needs-human without further repair" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(
      WorkerScript.Produces("1\t0\tharness/evil.txt"),
      WorkerScript.Produces(newFilePatch) // must never be consumed
    )

    val exit = runLoop(w)

    exit shouldBe LoopExit.NeedsHuman
    w.callCount("dispatch FIX") shouldBe 1 // the rejection breaks the loop
    // only the initial IMPL patch was ever applied; the rejected FIX patch never was
    w.appliedPatches shouldBe List("harness/logs/issue-999-iter1.impl.patch")
    w.files("PATCH-REJECTED.md") should include("protected path")
    w.called("gh issue edit 999 --add-label needs-human --remove-label in-progress") shouldBe true
    w.commitMessages.head should include("patch guard rejection (protected-path), gate RED")
    w.notifications shouldBe List("harness: #999 needs-human (protected-path, gate RED)")
  }

  // ---- status-event hygiene ----------------------------------------------------------------

  it should "sanitize status-event details (strip backslash, double quote, newlines)" in {
    Machine.sanitizeDetail("a\\b\"c\nd") shouldBe "abc d"
    Machine.sanitizeDetail("clean") shouldBe "clean"
  }

  // ---- CI_WAIT_CMD seam: overrides the WHOLE CI-wait gate command (loop.sh:446) ------------

  it should "run the CI_WAIT_CMD override instead of the default gh pr checks command (class-1 merge)" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")

    val exit = runLoop(w, Config(ciWaitCmd = Some("false")))

    exit shouldBe LoopExit.Success
    w.called("gate CI-WAIT cmd=false") shouldBe true
    w.called("gate CI-WAIT cmd=gh pr checks") shouldBe false
  }
