package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import Script.*

/** The load-bearing log-line contract, frozen as golden files.
  *
  * Each test drives one scenario through the loop and asserts the harness's entire operator log
  * stream against a `.log` file under `test/golden`. See `Golden` for the regeneration workflow.
  *
  * This replaces an older bash oracle, since deleted, that scored a bash implementation which no
  * longer exists. That oracle grepped eight individual needles out of the harness's stderr; the
  * goldens re-pin those same scenarios whole. Golden files rather than needles because the audience
  * is a parser, not a human: `watch.sh` reads this stream, and the eight needles were only ever a
  * sample of it. Pinning the whole stream per scenario catches a dropped line, a reordered pair, and
  * a reworded phrase — all of which the needle form let through.
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

  // ---- the fault and budget-exhaustion scenarios, pinned whole ------------------------------

  "The log stream" should "match the golden for an IMPL worker timeout" in {
    val w = TestWorld()
    w.implScript = WorkerScript.TimedOut

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logShouldMatchGolden("impl-timeout")
  }

  it should "match the golden for a FIX worker timeout during a repair round" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(WorkerScript.TimedOut)

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logShouldMatchGolden("fix-timeout")
  }

  it should "match the golden for a gate timeout" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Timeout)

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logShouldMatchGolden("gate-timeout")
  }

  it should "match the golden for a patch rejected for touching a protected path" in {
    val w = TestWorld()
    // `.github/**` is in the reference `protect` list, so this drives a real guard rejection
    // without the scenario having to carry a bespoke config.
    w.implScript = WorkerScript.Produces("1\t0\t.github/workflows/evil.yml")

    w.runLoop() shouldBe LoopExit.NeedsHuman

    w.logShouldMatchGolden("protected-path")
  }

  it should "match the golden for a patch rejected for exceeding the size cap" in {
    val w = TestWorld()

    w.runLoop(Config(maxPatchBytes = 10)) shouldBe LoopExit.NeedsHuman

    w.logShouldMatchGolden("oversized-patch")
  }

  it should "match the golden for the idle tick, with no ready and no in-progress issue" in {
    val w = TestWorld()
    w.inProgress = None
    w.ready = None

    w.runLoop() shouldBe LoopExit.Idle

    w.logShouldMatchGolden("idle")
    w.files shouldBe empty // still no sentinel: logging is not writing
  }

  it should "match the golden for three gate REDs exhausting the repair budget" in {
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

  /** The "third-party comments were spliced" log line was previously asserted only by a substring
    * check (`ScenarioSpec`), which a reword or reorder could pass right through. Pinning it here,
    * whole stream and all, gives it the same protection every other operator-facing line in this
    * file already has.
    */
  it should "match the golden for a gate-RED repair round with a third-party issue comment spliced in (issue #27)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))
    w.issueCommentBodies = Map(999 -> List("please also handle the empty-list case"))

    w.runLoop() shouldBe LoopExit.Success

    w.logShouldMatchGolden("gate-red-repair-comment")
  }

  /** The other two operator log lines this feature added, the failed-read line and the
    * missing-`{{COMMENTS}}`-marker warning, were asserted only by substring checks in
    * `ScenarioSpec`, which a reword sails through. These two pin them the same way as
    * `gate-red-repair-comment` above.
    */
  it should "match the golden for a gate-RED repair round whose issue comments read failed (issue #27)" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))
    w.issueCommentsFail = Set(999)

    w.runLoop() shouldBe LoopExit.Success

    w.logShouldMatchGolden("gate-red-repair-comments-read-failed")
  }

  it should "match the golden for a gate-RED repair round warning about a missing {{COMMENTS}} marker (issue #27)" in {
    val w = TestWorld()
    // An ejected fix-prompt.md that predates issue #27 has no {{COMMENTS}} line at all.
    w.templates = w.templates.updated(
      Template.Fix,
      "You are the fixer.\n{{ISSUE}}\n{{FAILURE}}\nProduce a patch."
    )
    w.gateResults = List(GateResult.Red)
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))
    w.issueCommentBodies = Map(999 -> List("please also handle the empty-list case"))

    w.runLoop() shouldBe LoopExit.Success

    w.logShouldMatchGolden("gate-red-repair-missing-comments-marker")
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

  /** The probe-hit path's own operator lines (issue #36 review, MINOR 5): the "already open" log
    * line `OpenPr`'s probe emits, and the `PR:skip` event, were pinned by no golden before this,
    * unlike every other terminal-phase log line in this file. `w.inProgress`/`w.ready` (not
    * `w.labels`) are what make this a genuine crash-resume rather than a fresh pick landing on a
    * stale PR (issue #36 review, MAJOR 2; see `ScenarioSpec`'s own tests for that distinction spelled
    * out mechanically).
    */
  it should "match the golden for a resumed tick recognising the PR its own earlier attempt already opened" in {
    val w = TestWorld()
    w.inProgress = Some(999)
    w.ready = None
    w.labels = List("in-progress", "class-1")
    w.existingPrNumber = Some(123)

    w.runLoop() shouldBe LoopExit.Success

    w.logShouldMatchGolden("openpr-probe-hit-resumed")
  }

  it should "match the golden for a failed merge, carrying the child's rc into the failure line" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.mergeRc = 3 // not 1: a wrong-but-nonzero rc would still pass a zero/one-only assertion

    w.runLoop() shouldBe LoopExit.InfraFault

    w.logShouldMatchGolden("merge-rc-carried")
    // Zero `gh pr view ... --json state` calls (issue #36 review, MAJOR 3/MINOR 8): `Merge`'s own
    // probe is `_ => None` unconditionally (see that node's own doc), so `performMerge` calls
    // `gh.merge` unconditionally too, exactly as `main` does; the only read of this string would be
    // the post-merge VERIFICATION `performMerge` makes once `mergeRc == 0`, never reached here
    // because the merge command itself fails first.
    w.called("gh pr view 123 --json state") shouldBe false
  }

  it should "match the golden for a CI-RED auto-merge candidate, with no self-repair" in {
    val w = TestWorld()
    w.labels = List("ready", "class-1")
    w.ciWaitResult = GateResult.Red

    w.runLoop() shouldBe LoopExit.NeedsHuman

    w.logShouldMatchGolden("ci-red-needs-human")
    w.callCount("dispatch FIX") shouldBe 0
  }

  it should "match the golden for a CI-RED whose needs-human label flip fails, which only warns" in {
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

    // The stage_patch line precedes the caller's own; the golden pins that pairing.
    w.logShouldMatchGolden("git-apply-refused")
  }

  it should "match the golden for an empty IMPL patch, leaving the issue in-progress" in {
    val w = TestWorld()
    w.implScript = WorkerScript.Empty

    w.runLoop() shouldBe LoopExit.NothingMade

    w.logShouldMatchGolden("empty-impl-patch")
    w.called("gh pr create") shouldBe false
  }

  // ---- issue #28: parked terminal ------------------------------------------------------------

  it should "match the golden for the repair budget exhausting into a parked issue, not a needs-human PR" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red, GateResult.Red, GateResult.Red)
    w.fixScripts = List(
      WorkerScript.Produces("1\t0\tsrc/main/scala/Fix1.scala"),
      WorkerScript.Produces("1\t0\tsrc/main/scala/Fix2.scala")
    )

    w.runLoop() shouldBe LoopExit.Parked

    w.logShouldMatchGolden("parked-on-exhaustion")
  }

  /** Issue #44: the reply path through the new `AskHuman` edge. A reply is already sitting after the
    * marker the MOMENT the issue reaches `Route.Parked` for the first time, so `AskHuman`'s own probe
    * hits; `finish`'s own closure logs why the reply cannot be spent this tick (the shared ledger is
    * provably exhausted by the time `Route.Parked` is ever reached, `decideRoute`'s own invariant) and
    * then calls `reparkKeepingReply`, NOT the `parkIssue` the probe-miss path (`askHumanRun`) uses
    * (issue #44 review, MAJOR, round 2: posting a fresh marker on this path was the actual bug,
    * burying the very reply the log line just named), so this golden's tail differs from
    * `parked-on-exhaustion` above in exactly one line, the one naming the reply, and posts no comment
    * at all, unlike that scenario's own trailing marker post. Kept as its own scenario rather than
    * folded into `parked-on-exhaustion`: the two differ in exactly the one fact this issue adds,
    * whether a reply is already there, and that one extra line is worth pinning on its own.
    */
  it should "match the golden for a reply already waiting the moment the issue parks, re-parking WITHOUT a fresh marker so the same reply resumes next tick" in {
    val w = TestWorld()
    w.gateResults = List(GateResult.Red, GateResult.Red, GateResult.Red)
    w.fixScripts = List(
      WorkerScript.Produces("1\t0\tsrc/main/scala/Fix1.scala"),
      WorkerScript.Produces("1\t0\tsrc/main/scala/Fix2.scala")
    )
    w.issueCommentBodies = Map(
      999 -> List(
        s"@litter-box (OWNER):\n${Machine.ParkMarker}\nparked, awaiting a reply",
        "@alice (OWNER):\ntry using a HashMap instead"
      )
    )

    w.runLoop() shouldBe LoopExit.Parked

    // The bug this round fixes, restated as a positive: no comment posted at all on this path, so
    // alice's original reply is still the newest thing after the newest marker for the next tick.
    w.postedIssueComments shouldBe empty

    w.logShouldMatchGolden("parked-reply-already-waiting")
  }

  it should "match the golden for a parked issue with no reply, exiting Parked again with nothing spent" in {
    val w = TestWorld()
    w.inProgress = None
    w.ready = None
    w.parked = List(777)
    w.issueCommentBodies =
      Map(777 -> List(s"@litter-box (OWNER):\n${Machine.ParkMarker}\nparked, awaiting a reply"))

    w.runLoop() shouldBe LoopExit.Parked

    w.logShouldMatchGolden("parked-no-reply")
    w.files shouldBe empty
  }

  it should "match the golden for resuming a parked issue with a human reply, straight to a FIX" in {
    val w = TestWorld()
    w.inProgress = None
    w.ready = None
    w.parked = List(777)
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))
    w.issueCommentBodies = Map(
      777 -> List(
        s"@litter-box (OWNER):\n${Machine.ParkMarker}\nparked, awaiting a reply",
        "@alice (OWNER):\ntry using a HashMap instead"
      )
    )

    w.runLoop() shouldBe LoopExit.Success

    w.logShouldMatchGolden("parked-resume")
  }

  // ---- issue #50: parked survives an infra fault for the whole tick -------------------------

  it should "match the golden for an infra fault during a resumed FIX, then resuming with the same reply next tick" in {
    val w = TestWorld()
    w.inProgress = None
    w.ready = None
    w.parked = List(777)
    w.fixScripts = List(WorkerScript.TimedOut) // infra-faults the resumed FIX round
    w.issueCommentBodies = Map(
      777 -> List(
        s"@litter-box (OWNER):\n${Machine.ParkMarker}\nparked, awaiting a reply",
        "@alice (OWNER):\ntry using a HashMap instead"
      )
    )

    w.runLoop() shouldBe LoopExit.InfraFault

    // Model what `gh` now reports on the next tick: the pick-time flip in the first tick already
    // added in-progress and left parked untouched (issue #50), so both labels are on #777, and the
    // comment thread is unchanged by the fault.
    w.inProgress = Some(777)
    w.labels = List("parked", "in-progress")
    w.fixScripts = List(WorkerScript.Produces(newFilePatch))

    w.runLoop() shouldBe LoopExit.Success

    w.logShouldMatchGolden("fault-then-parked-resume")
  }
