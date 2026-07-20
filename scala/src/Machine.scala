package harness

import in.rcard.yaes.Raise

/** The loop state machine for one US, ported from `harness/loop.sh` iterate(). */
object Machine:

  val LogDir = "harness/logs"

  /** The four CUR_* globals of loop.sh: the status-event context. iterate() keeps them current;
    * emit() only reads them, so a terminal DONE from the driver still carries the right issue.
    */
  final class Cursor:
    var iter: Int     = 0
    var issue: String = ""
    var pass: Int     = 0
    var budget: Int   = 0

  /** Detail sanitization: never model-controlled, but strip anything that could break out of the
    * JSON string anyway (backslash, double quote, newlines).
    */
  private[harness] def sanitizeDetail(detail: String): String =
    detail.replace("\\", "").replace("\"", "").replace("\n", " ")

  /** Extracts the PR number from a `gh pr create` PR URL (last path segment), e.g.
    * `https://github.com/o/r/pull/42` -> `Some(42)`. `None` if the URL has no numeric last segment.
    */
  private[harness] def prNumberOf(prUrl: String): Option[Int] =
    prUrl.split('/').lastOption.flatMap(_.toIntOption)

  private def emit(
      cur: Cursor,
      phase: String,
      state: String,
      logfile: String = "",
      detail: String = ""
  )(using
      log: StatusLog
  ): Unit =
    log.append(
      StatusEvent(
        cur.iter,
        cur.issue,
        phase,
        state,
        cur.pass,
        cur.budget,
        logfile,
        sanitizeDetail(detail)
      )
    )

  /** render_template: each line containing the literal `{{KEY}}` is replaced by the spliced content
    * (whole-line replacement, embedded newlines preserved), one key per pass.
    */
  private[harness] def renderTemplate(template: String, splices: (String, String)*): String =
    splices.foldLeft(template) { case (acc, (key, content)) =>
      acc.linesIterator
        .flatMap { line =>
          if line.contains(s"{{$key}}") then content.linesIterator else Iterator(line)
        }
        .mkString("\n")
    }

  /** Logs an infra fault the way bash does — the message on the operator's log stream at the point
    * of the fault, not at the fold — and raises it. Single helper rather than a log+raise pair at
    * each of the ten raise sites: `InfraFault.reason` IS the bash log line (see `InfraFault`), so
    * there is exactly one string per fault and no way to log one wording and carry another.
    */
  private def infraFault(reason: String)(using logger: Log)(using Raise[InfraFault]): Nothing =
    logger.log(reason)
    Raise.raise(InfraFault(reason))

  /** One driver tick: folds the infra-fault channel to LoopExit.InfraFault (rc 50) and emits the
    * terminal DONE status event, exactly like the bash driver.
    */
  def runOnce(n: Int)(using
      Config,
      GitHub,
      Git,
      AgentDispatch,
      GateRunner,
      StatusLog,
      Notify,
      HarnessFs,
      Clock,
      Log
  ): LoopExit =
    val cur  = Cursor()
    val exit = Raise.fold(iterate(n, cur)) { (_: InfraFault) =>
      // rc-50 exits fire the notify seam: exit for inspection, issue stays in-progress.
      summon[Notify].notify(
        "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
      )
      LoopExit.InfraFault
    }(identity)
    emit(cur, "DONE", "end", detail = s"rc=${exit.rc}")
    exit

  /** One US, start to terminal. Infra faults short-circuit via Raise[InfraFault]: no code past a
    * raise can spend repair budget or dispatch a FIX.
    */
  def iterate(n: Int, cur: Cursor)(using
      cfg: Config,
      gh: GitHub,
      git: Git,
      agents: AgentDispatch,
      gates: GateRunner,
      log: StatusLog,
      notify: Notify,
      fs: HarnessFs,
      clock: Clock,
      logger: Log
  )(using Raise[InfraFault]): LoopExit =
    // STOP.md is a MANUAL kill-switch only: the loop never writes it itself.
    if fs.stopRequested() then
      logger.log("STOP.md present (manual kill-switch) — exiting")
      return LoopExit.ManualStop

    // Pick US (deterministic, no LLM): resume an in-progress one, else oldest ready.
    // No issue = transient idle — nothing is written, nothing is labelled, so the very next
    // tick resumes on its own when a US goes ready (the idle state must never latch).
    val issue = gh.inProgressIssue().orElse(gh.oldestReadyIssue()) match
      case None =>
        logger.log("no in-progress or ready issue — idle, exiting (next tick resumes when one goes ready)")
        return LoopExit.Idle
      case Some(i) => i
    cur.iter = n; cur.issue = issue.toString; cur.pass = 0; cur.budget = cfg.repairBudget
    emit(cur, "PICK", "ok", detail = s"issue=$issue")
    logger.log(s"iteration $n -> issue #$issue")

    // Render the worker prompt with the issue body injected (read-only).
    val bodyFile = s"$LogDir/issue-$issue.body.md"
    fs.write(bodyFile, gh.issueTitleAndBody(issue))
    val workerPromptFile = s"$LogDir/issue-$issue.prompt.txt"
    fs.write(
      workerPromptFile,
      renderTemplate(fs.readTemplate(Template.Iterate), "ISSUE" -> fs.read(bodyFile))
    )

    // Auto-merge is earned by class-1 only. Detect the class once, at pick time.
    val isClass1 = gh.issueLabels(issue).contains("class-1")

    // Dry run stops here — before ANY git/label mutation, so it is truly read-only.
    if cfg.dryRun then
      logger.log(
        s"DRY_RUN=1 — rendered worker prompt for #$issue -> $workerPromptFile; no mutation; stopping"
      )
      return LoopExit.DryRun

    // Require a clean tree on a fresh branch off main. Serial loop: one US at a time.
    // These are die() paths in bash (exit 1): fatal misconfiguration, not part of the
    // rc 0..50 state machine, so they surface as exceptions.
    if !git.statusClean() then
      throw IllegalStateException("working tree not clean — refusing to start")
    // Stale-base guard: everything downstream is measured against origin/main; no fallback.
    if !git.fetchOriginMain() then
      throw IllegalStateException("cannot fetch origin/main — refusing to run against a stale base")
    val branch = s"us-$issue"
    if !git.checkoutBranch(branch) then throw IllegalStateException("cannot branch off origin/main")

    // Mark in-progress so a crashed run resumes the same US next tick.
    gh.editLabels(issue, add = List("in-progress"), remove = List("ready"))

    // --- bounded self-repair state -------------------------------------------------------
    // Declared BEFORE the initial dispatch: a patch-guard rejection on the very first worker
    // patch sets outcome/failureKind and skips the loop straight to the terminal.
    var budget                           = cfg.repairBudget
    var pass                             = 0
    var outcome: Option[Outcome]         = None
    var gateStatus                       = ""
    var failureKind: Option[FailureKind] = None
    var currentPatch: Option[String]     = None
    val reviewFile                       = s"$LogDir/issue-$issue-review.md"
    fs.write(reviewFile, "") // empty until the first review
    var reviewed = false

    // Initial worker dispatch (fresh context), crossing the patch seam. The tree the worker
    // edited is never committed directly.
    val implLog   = s"$LogDir/issue-$issue-iter$n.claude.log"
    val implPatch = s"$LogDir/issue-$issue-iter$n.impl.patch"
    emit(cur, "IMPL", "start", implLog)
    stagePatch(Role.IMPL, workerPromptFile, implPatch, implLog, currentPatch) match
      case StageResult.Empty =>
        emit(cur, "IMPL", "ok", implLog, "no diff")
        logger.log("no changes produced by the iteration — leaving issue in-progress, not opening a PR")
        return LoopExit.NothingMade
      case result =>
        handleStageResult(cur, Role.IMPL, implLog, result) match
          case StageVerdict.Applied(p)     => currentPatch = Some(p)
          case StageVerdict.Rejected(kind) =>
            outcome = Some(Outcome.Fail); failureKind = Some(kind); gateStatus = "SKIPPED"

    // The fixer dispatch across the patch seam plus the mapping of its StageResult onto the
    // repair loop's control flow (bash dispatch_fix + handle_fix_result). Infra faults raise;
    // guard rejections and an empty fix become the terminal FAIL; Ok advances currentPatch.
    def fixRound(pass: Int, failFile: String): Unit =
      val fixPromptFile = s"$LogDir/issue-$issue-pass$pass.fix.prompt.txt"
      fs.write(
        fixPromptFile,
        renderTemplate(
          fs.readTemplate(Template.Fix),
          "ISSUE"   -> fs.read(bodyFile),
          "FAILURE" -> fs.read(failFile)
        )
      )
      val fixLog   = s"$LogDir/issue-$issue-pass$pass.fix.claude.log"
      val fixPatch = s"$LogDir/issue-$issue-pass$pass.fix.patch"
      emit(cur, "FIX", "start", fixLog)
      stagePatch(Role.FIX, fixPromptFile, fixPatch, fixLog, currentPatch) match
        case StageResult.Empty =>
          // The fixer reverted all prior work — route to needs-human, never re-gate an empty tree.
          emit(cur, "FIX", "red", fixLog, "empty fix")
          logger.log("FIX produced no diff (the fixer reverted all prior work); routing to needs-human")
          outcome = Some(Outcome.Fail); failureKind = Some(FailureKind.EmptyFix)
        case result =>
          handleStageResult(cur, Role.FIX, fixLog, result) match
            case StageVerdict.Applied(p)     => currentPatch = Some(p)
            case StageVerdict.Rejected(kind) =>
              outcome = Some(Outcome.Fail); failureKind = Some(kind)

    // Shared shape of both repair triggers (gate-RED, REQUEST_CHANGES): out of budget fails the
    // outcome, otherwise spend one unit, write the fail file with the stage-specific content, and
    // dispatch a FIX round. failureKind/gateStatus are set by the caller before this runs.
    def spendOrExhaust(trigger: FailureKind, failContent: String): Unit =
      if budget == 0 then outcome = Some(Outcome.Fail)
      else
        budget -= 1; cur.budget = budget
        logger.log(s"self-repair: budget now $budget — dispatching FIX for ${trigger.text}")
        val failFile = s"$LogDir/issue-$issue-pass$pass.failure.md"
        fs.write(failFile, failContent)
        fixRound(pass, failFile)

    // --- bounded self-repair loop --------------------------------------------------------
    // Skipped entirely if the initial patch was already rejected (outcome set above).
    while outcome.isEmpty do
      pass += 1
      git.addAll() // stage so new files show in diff/gate/tamper
      cur.pass = pass
      val gateLog = s"$LogDir/issue-$issue-pass$pass.gate.log"
      emit(cur, "FAST_GATE", "start", gateLog)
      gates.run("FAST", cfg.gateCmd, cfg.gateTimeout, gateLog) match
        case GateResult.Timeout =>
          infraFault(
            s"WARNING: FAST gate hit the ${cfg.gateTimeout}s timeout — infra fault, not a code failure"
          )
        case GateResult.Red =>
          gateStatus = "RED"
          failureKind = Some(FailureKind.GateRed)
          emit(cur, "FAST_GATE", "red", gateLog)
          logger.log(s"FAST gate RED (pass $pass, see $gateLog)")
          spendOrExhaust(
            FailureKind.GateRed,
            s"## Fast-gate failure — `${cfg.gateCmd}` (compile under -Werror, then in-memory tests)\n\n" +
              s"Tail of the fast-gate log:\n\n```\n${fs.read(gateLog)}\n```\n"
          )
        case GateResult.Green =>
          gateStatus = "GREEN"
          emit(cur, "FAST_GATE", "ok", gateLog)
          logger.log(s"FAST gate GREEN (pass $pass) — running tamper check + cold reviewer")

          // Tamper check feeds the reviewer (the harness surfaces, does not block).
          val tamperFile = s"$LogDir/issue-$issue-tamper.md"
          fs.write(tamperFile, tamperReport(currentPatch.map(git.applyNumstat).getOrElse("")))
          val diffFile = s"$LogDir/issue-$issue-diff.patch"
          fs.write(diffFile, git.diffCachedOriginMain())
          val reviewPromptFile = s"$LogDir/issue-$issue-pass$pass.review.prompt.txt"
          fs.write(
            reviewPromptFile,
            renderTemplate(
              fs.readTemplate(Template.Review),
              "ISSUE"       -> fs.read(bodyFile),
              "CONVENTIONS" -> fs.conventions(),
              "TAMPER"      -> fs.read(tamperFile),
              "DIFF"        -> fs.read(diffFile)
            )
          )
          emit(cur, "REVIEW", "start", reviewFile)
          agents.review(fs.read(reviewPromptFile), reviewFile) match
            case DispatchOutcome.TimedOut =>
              emit(cur, "REVIEW", "red", reviewFile, "timeout")
              infraFault("REVIEWER timed out — infra fault; exiting without spending budget")
            case DispatchOutcome.Done => ()
          reviewed = true

          // An empty (or whitespace-only) review is a crashed reviewer, not a verdict.
          if fs.read(reviewFile).isBlank then
            emit(cur, "REVIEW", "red", reviewFile, "empty review")
            infraFault("reviewer produced no output — infra fault (crashed or timed-out reviewer)")

          // Grep, not parse. Missing sentinel -> REQUEST_CHANGES (fail safe, never auto-approve).
          val verdict = parseVerdict(fs.read(reviewFile)) match
            case Some(v) => v
            case None    =>
              logger.log("reviewer emitted no VERDICT sentinel — fail-safe REQUEST_CHANGES")
              Verdict.RequestChanges
          logger.log(s"reviewer verdict: ${verdictText(verdict)} (pass $pass)")
          emit(cur, "REVIEW", "ok", reviewFile, s"verdict=${verdictText(verdict)}")
          verdict match
            case Verdict.Approve =>
              outcome = Some(Outcome.Success)
            case Verdict.RequestChanges =>
              // REQUEST_CHANGES — spend from the same shared budget as gate-RED.
              failureKind = Some(FailureKind.ReviewChanges)
              spendOrExhaust(
                FailureKind.ReviewChanges,
                s"## The independent reviewer requested changes\n\n${fs.read(reviewFile)}\n\n${fs.read(tamperFile)}"
              )
    end while

    // --- terminal: commit, push, PR (SUCCESS -> needs-review, FAIL -> needs-human) --------
    // A fixer that produced no diff left the tree pristine (stagePatch reset to origin/main
    // before it saw the empty patch), so the "nothing staged" guard below would otherwise fire
    // first and mask the routing. Stage a small tracked marker so the needs-human audit PR
    // still opens. In the cumulative-patch model an empty fix reverts all prior work, so this
    // branch legitimately holds only the marker.
    if failureKind.contains(FailureKind.EmptyFix) then
      fs.write(
        "FIX-EMPTY.md",
        s"""# Fixer produced no diff
           |
           |The self-repair fixer returned an empty patch. In the cumulative-patch model that
           |reverts all prior work on this branch, so the loop routed the issue to human review
           |instead of re-gating an empty tree. Opened for the audit trail ONLY; do NOT merge.
           |""".stripMargin
      )
      git.add("FIX-EMPTY.md")
    git.addAll()
    if !git.anythingStaged() then
      logger.log("nothing staged at terminal — unexpected; leaving in-progress")
      return LoopExit.NothingMade

    val outcomeText = if outcome.contains(Outcome.Success) then "SUCCESS" else "FAIL"
    val kindText    = failureKind.map(_.text).getOrElse("?")

    // Terminal route decided ONCE, here. Every downstream site (label, notify, PR note,
    // auto-merge dispatch, exit code) threads this value instead of re-testing
    // outcome/isClass1 or comparing against a "needs-human" label string.
    val route =
      if outcome.contains(Outcome.Success) && isClass1 then Route.AutoMergeCandidate
      else if outcome.contains(Outcome.Success) then Route.NeedsReview
      else Route.NeedsHuman

    val (label, commitTag, prNote) =
      route match
        case Route.AutoMergeCandidate =>
          // no flip: the auto-merge path owns the issue's fate
          (
            "",
            s"reviewer APPROVE, gate $gateStatus",
            s"**Reviewer: APPROVE** · gate $gateStatus · class-1 — v4 auto-merge candidate: the loop merges after the required CI check goes green."
          )
        case Route.NeedsReview =>
          (
            "needs-review",
            s"reviewer APPROVE, gate $gateStatus",
            s"**Reviewer: APPROVE** · gate $gateStatus (containerized in-memory FAST tier green; the real-PG IT tier is judged by CI on this PR). Not class-1, so not auto-merged: a human reviews and merges."
          )
        case Route.NeedsHuman =>
          if failureKind.contains(FailureKind.ProtectedPath) || failureKind.contains(
              FailureKind.OversizedPatch
            )
          then
            (
              "needs-human",
              s"patch guard rejection ($kindText), gate $gateStatus",
              s"**Needs human** — the patch guard rejected the agent's patch ($kindText: a CI workflow / harness / docs / control-or-constitution file, or a patch over the size cap). The rejected change was NOT applied; this branch holds only a rejection marker and must NOT be merged."
            )
          else if failureKind.contains(FailureKind.EmptyFix) then
            (
              "needs-human",
              s"fixer produced no diff (empty-fix), gate $gateStatus",
              s"**Needs human**: the self-repair fixer produced no diff. In the cumulative-patch model that reverts all prior work, so this branch holds only an audit marker (the prior implementation is NOT on it). Opened for the audit trail; do NOT merge."
            )
          else
            (
              "needs-human",
              s"self-repair budget exhausted ($kindText), gate $gateStatus",
              s"**Needs human** — self-repair budget of ${cfg.repairBudget} exhausted on $kindText (last gate $gateStatus). Opened for the audit trail; do NOT merge without review."
            )

    if route == Route.NeedsHuman then
      notify.notify(s"harness: #$issue needs-human ($kindText, gate $gateStatus)")

    git.commit(
      s"""feat(US-$issue): autonomous iteration — $commitTag
         |
         |Refs #$issue. Loop iteration $n, $pass gate pass(es). Outcome: $outcomeText.
         |This commit was produced by an unattended claude -p iteration (harness v2).
         |
         |Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>""".stripMargin
    )
    git.push(branch)

    val prBody = StringBuilder()
    prBody ++= s"Autonomous harness (v2) iteration $n for #$issue.\n\n"
    prBody ++= s"$prNote\n\n"
    if reviewed then
      prBody ++= s"<details><summary>Independent reviewer output</summary>\n\n```\n${fs.read(reviewFile)}\n```\n\n</details>\n\n"
    if route == Route.AutoMergeCandidate then
      prBody ++= "v4 auto-merge: class-1 + reviewer APPROVE — the loop merges once the required CI check is green.\n\n"
    else
      prBody ++= "Not auto-merged (v4 merges class-1 + APPROVE only): a human reviews and merges.\n\n"
    prBody ++= s"Closes #$issue\n"
    fs.write(s"$LogDir/issue-$issue.pr-body.md", prBody.toString)

    val prUrl = gh.createPr(
      branch,
      s"US-$issue: autonomous iteration ($outcomeText, gate $gateStatus)",
      prBody.toString
    )
    val prNum = prNumberOf(prUrl) match
      case None =>
        infraFault("could not determine PR number from gh pr create output — infra fault")
      case Some(p) => p
    logger.log(s"PR #$prNum opened for #$issue (outcome $outcomeText)")
    emit(cur, "PR", "ok", detail = s"pr=$prNum outcome=$outcomeText")

    route match
      case Route.AutoMergeCandidate => autoMerge(issue, prNum, cur)
      case Route.NeedsReview | Route.NeedsHuman =>
        gh.editLabels(issue, add = List(label), remove = List("in-progress"))
        logger.log(s"issue #$issue -> $label")
        if route == Route.NeedsReview then LoopExit.Success else LoopExit.NeedsHuman

  /** v4 auto-merge (class-1 + APPROVE only): wait-appear -> watch -> merge -> VERIFY the PR state
    * is MERGED (unverified = infra fault) -> drop in-progress -> flip blocked -> fetch -> notify.
    * CI red after green local gates = needs-human WITHOUT self-repair: the loop never repairs
    * against the independent check.
    */
  private def autoMerge(issue: Int, prNum: Int, cur: Cursor)(using
      cfg: Config,
      gh: GitHub,
      git: Git,
      gates: GateRunner,
      log: StatusLog,
      notify: Notify,
      clock: Clock,
      logger: Log
  )(using Raise[InfraFault]): LoopExit =
    val ciLog = s"$LogDir/issue-$issue.ci-wait.log"
    emit(cur, "CI_WAIT", "start", ciLog)
    // Discriminate on data, not on the exit code: a fresh PR routinely reports zero checks
    // for a few seconds (push races the workflow scheduler, PR #28 / issue #26). Block until
    // the rollup is non-empty, and only then let the CI watch judge. A check that never
    // registers is a scheduler/infra problem, never rc 40.
    if !waitForChecks(prNum) then
      infraFault(
        s"no CI check registered on PR #$prNum within ${cfg.ciAppearTimeout}s — infra fault; PR open, issue stays in-progress"
      )
    gates.run(
      "CI-WAIT",
      cfg.ciWaitCmd.getOrElse(s"gh pr checks $prNum --watch --fail-fast"),
      cfg.ciWaitTimeout,
      ciLog
    ) match
      case GateResult.Timeout =>
        infraFault(
          s"CI wait hit the ${cfg.ciWaitTimeout}s bound — infra fault; PR open, issue stays in-progress"
        )
      case GateResult.Red =>
        emit(cur, "CI_WAIT", "red", ciLog)
        logger.log(
          s"CI RED on PR #$prNum after local gates green — needs-human, no merge, no self-repair"
        )
        gh.prComment(
          prNum,
          "CI red after local gates were green. The loop never self-repairs against the independent check (v3 hands-off rule) — a human must look."
        )
        // bash guards this flip (loop.sh:464): a failed flip is a warning, not a hard stop.
        if !gh.editLabels(issue, add = List("needs-human"), remove = List("in-progress")) then
          logger.log(s"WARNING: could not flip #$issue to needs-human (flip by hand)")
        notify.notify(s"harness: #$issue CI RED -> needs-human (PR #$prNum)")
        LoopExit.NeedsHuman
      case GateResult.Green =>
        emit(cur, "CI_WAIT", "ok", ciLog)
        logger.log(s"CI green — merging PR #$prNum")
        emit(cur, "MERGE", "start")
        // Same `ciLog` the CI watch just wrote: bash appends the merge output to it (loop.sh:473).
        val mergeRc = gh.merge(prNum, ciLog)
        // loop.sh:475 prints the rc: it is what tells "PR not mergeable" from "gh auth expired".
        if mergeRc != 0 then infraFault(s"merge command failed rc=$mergeRc — infra fault")
        val state = gh.prState(prNum)
        if state != "MERGED" then
          // bash's `${state:-unknown}` (loop.sh:481): an empty answer from `gh pr view` is the
          // very case this fault exists to report, so it must not print as an empty pair of quotes.
          val shown = if state.isEmpty then "unknown" else state
          infraFault(s"merge NOT verified (PR state '$shown') — infra fault")
        emit(cur, "MERGE", "ok", detail = s"pr=$prNum")
        gh.editLabels(issue, add = Nil, remove = List("in-progress"))
        flipBlocked(issue)
        // a post-merge fetch failure is tolerated: next tick re-fetches
        if !git.fetchOriginMain() then
          logger.log("post-merge fetch failed (next iteration re-fetches anyway)")
        notify.notify(s"harness: #$issue auto-merged (PR #$prNum, CI green, reviewer APPROVE)")
        LoopExit.Success

  /** Poll the rollup length until > 0, bounded by ciAppearTimeout. True once >=1 check is
    * registered, false on timeout.
    */
  private def waitForChecks(
      prNum: Int
  )(using cfg: Config, gh: GitHub, clock: Clock, logger: Log): Boolean =
    var waited = 0
    while waited < cfg.ciAppearTimeout do
      gh.checksRollupCount(prNum) match
        case Some(n) if n > 0 =>
          logger.log(s"CI check registered on PR #$prNum after ${waited}s")
          return true
        case _ => ()
      clock.sleepSeconds(cfg.ciAppearInterval)
      waited += cfg.ciAppearInterval
    false

  /** `Blocked-by: #N` references in an issue body. */
  private[harness] def parseBlockedBy(body: String): List[Int] =
    "Blocked-by: #(\\d+)".r.findAllMatchIn(body).map(_.group(1).toInt).toList

  /** After a verified merge, flip every open `blocked` issue whose Blocked-by refs are ALL closed.
    * The just-merged issue counts as closed even if GitHub's async close lags the merge. Issues
    * without the sentinel are left alone (human-managed).
    */
  private def flipBlocked(mergedIssue: Int)(using gh: GitHub, logger: Log): Unit =
    gh.openBlockedIssues().foreach { b =>
      val refs = parseBlockedBy(gh.issueBody(b))
      if refs.nonEmpty then
        val allClosed = refs.forall(r => r == mergedIssue || gh.issueState(r) == "CLOSED")
        if allClosed then
          logger.log(s"dependency #$mergedIssue closed — flipping #$b blocked -> ready")
          if !gh.editLabels(b, add = List("ready"), remove = List("blocked")) then
            logger.log(s"WARNING: could not flip #$b blocked -> ready (flip by hand)")
    }

  private enum Outcome:
    case Success, Fail

  /** The terminal route for a US, decided once in `iterate` and threaded to every downstream site
    * (label, notify, PR note, auto-merge dispatch, exit code).
    */
  private enum Route:
    case AutoMergeCandidate, NeedsReview, NeedsHuman

  private def verdictText(v: Verdict): String = v match
    case Verdict.Approve        => "APPROVE"
    case Verdict.RequestChanges => "REQUEST_CHANGES"

  /** Last `VERDICT: (APPROVE|REQUEST_CHANGES)` occurrence wins (grep | tail -1). */
  private[harness] def parseVerdict(review: String): Option[Verdict] =
    "VERDICT: (APPROVE|REQUEST_CHANGES)".r
      .findAllMatchIn(review)
      .toList
      .lastOption
      .map(m => if m.group(1) == "APPROVE" then Verdict.Approve else Verdict.RequestChanges)

  /** What `handleStageResult` concluded once the stage-specific `Empty` case has already been
    * peeled off by the caller.
    */
  private enum StageVerdict:
    case Applied(patch: String)
    case Rejected(kind: FailureKind)

  /** Whether a stage narrates a patch-guard rejection on its own log line, or deliberately stays
    * silent about it.
    *
    * `Silent` is a decision, not a missing value: loop.sh:710/714 log the guard rejection naming the
    * patch that was rejected, while loop.sh's handle_fix_result (:608-609) logs NOTHING for the same
    * two results on a FIX, because the fixer's rejection is already narrated by the guard line
    * inside stage_patch. The asymmetry is kept, not tidied: the oracle greps this stream.
    */
  private enum RejectionNarration:
    /** Emit one guard-rejection line naming `subject` as the patch that was rejected. */
    case Announce(subject: String)

    /** Emit nothing; the rejection is already narrated elsewhere. */
    case Silent

  /** Everything `handleStageResult` does differently for an IMPL than for a FIX, in one place. The
    * five strings used to travel as five parameters of `handleStageResult`, always in lockstep; the
    * only thing that genuinely varies per call is the log file, which stays a parameter.
    */
  private case class StagePolicy(
      stage: String,
      rejectionNarration: RejectionNarration,
      timeoutMsg: String,
      applyFailMsg: String
  )

  private def policyOf(role: Role): StagePolicy = role match
    case Role.IMPL =>
      StagePolicy(
        stage = "IMPL",
        rejectionNarration = RejectionNarration.Announce("the initial worker patch"),
        timeoutMsg =
          "IMPL worker timed out — infra fault; a half-finished worker must not reach the gates",
        applyFailMsg = "IMPL patch did not apply — infra fault, no budget spent"
      )
    case Role.FIX =>
      StagePolicy(
        stage = "FIX",
        rejectionNarration = RejectionNarration.Silent,
        timeoutMsg = "FIX worker timed out (infra fault); exiting without spending further budget",
        applyFailMsg = "FIX patch did not apply (infra fault, no budget spent)"
      )

  /** Shared shape of a stagePatch(...) result match, common to both the IMPL and FIX call sites:
    * Timeout and ApplyFail both raise InfraFault (infra fault, no budget spent); Protected and
    * Oversize both fail the outcome with the matching FailureKind; Ok emits the ok status and
    * yields the applied patch. The Empty case is genuinely stage-specific (IMPL exits NothingMade,
    * FIX routes to needs-human) and is handled by each call site before it delegates the rest here.
    */
  private def handleStageResult(
      cur: Cursor,
      role: Role,
      logFile: String,
      result: StageResult
  )(using log: StatusLog, logger: Log)(using Raise[InfraFault]): StageVerdict =
    val policy = policyOf(role)
    val stage  = policy.stage
    def logRejection(kind: FailureKind): Unit =
      policy.rejectionNarration match
        case RejectionNarration.Announce(subject) =>
          logger.log(s"patch guard rejected $subject (${kind.text}) — routing to needs-human")
        case RejectionNarration.Silent => ()
    result match
      case StageResult.Timeout =>
        emit(cur, stage, "red", logFile, "timeout")
        infraFault(policy.timeoutMsg)
      case StageResult.ApplyFail =>
        emit(cur, stage, "red", logFile, "patch apply conflict")
        infraFault(policy.applyFailMsg)
      case StageResult.Protected =>
        emit(cur, stage, "red", logFile, "protected-path")
        logRejection(FailureKind.ProtectedPath)
        StageVerdict.Rejected(FailureKind.ProtectedPath)
      case StageResult.Oversize =>
        emit(cur, stage, "red", logFile, "oversized patch")
        logRejection(FailureKind.OversizedPatch)
        StageVerdict.Rejected(FailureKind.OversizedPatch)
      case StageResult.Ok(p) =>
        emit(cur, stage, "ok", logFile)
        StageVerdict.Applied(p)
      case StageResult.Empty =>
        // Unreachable: both call sites match Empty themselves before delegating here.
        throw IllegalStateException("handleStageResult called with StageResult.Empty")

  /** The patch seam: dispatch the agent, reset to the pristine base, inspect the patch, THEN apply
    * it. The tree the agent edited is data to inspect, never trusted.
    */
  private def stagePatch(
      role: Role,
      promptFile: String,
      patchOut: String,
      logFile: String,
      currentPatch: Option[String]
  )(using
      cfg: Config,
      git: Git,
      agents: AgentDispatch,
      fs: HarnessFs,
      logger: Log
  ): StageResult =
    agents.worker(role, promptFile, patchOut, logFile, currentPatch) match
      case DispatchOutcome.TimedOut => return StageResult.Timeout
      case DispatchOutcome.Done     => ()
    // Reset to the pristine base BEFORE looking at the patch.
    git.resetHardCleanToOriginMain()
    if fs.sizeBytes(patchOut) == 0 then return StageResult.Empty
    // Inspect, THEN apply. Fail-open is DELIBERATE and backstopped: an unparseable patch
    // yields an empty numstat (guard passes) but `git apply --index` then refuses it, so a
    // malformed patch never reaches the gates (ApplyFail = infra fault, no budget).
    val numstat = git.applyNumstat(patchOut)
    val bytes   = fs.sizeBytes(patchOut)
    if bytes > cfg.maxPatchBytes then
      logger.log(
        s"patch guard: ${bytes}B exceeds the ${cfg.maxPatchBytes}B cap — rejecting oversized patch (not applied)"
      )
      writeRejectMarker(
        s"Oversized patch: $bytes bytes exceeds the ${cfg.maxPatchBytes}-byte cap.",
        numstat
      )
      return StageResult.Oversize
    if touchesProtected(numstat) then
      logger.log(
        "patch guard: patch touches a protected path (.github/, harness/, docs/, CONTEXT.md, PROMPT.md or STOP.md) — rejecting (not applied)"
      )
      writeRejectMarker(
        "Patch touches a protected path (CI workflow, harness code, docs, or a control/constitution file).",
        numstat
      )
      return StageResult.Protected
    if !git.applyIndex(patchOut) then
      logger.log(
        s"git apply refused the patch (see ${patchOut}.apply.err) — infra fault, no budget spent"
      )
      return StageResult.ApplyFail
    StageResult.Ok(patchOut)

  /** On a guard rejection the tree is left pristine — a hostile or oversized patch is NEVER
    * applied. Stage a small tracked marker instead, so the terminal still has a diff to open the
    * audit PR with. The marker, not the rejected change, lands on the throwaway branch.
    */
  private def writeRejectMarker(reason: String, numstat: String)(using
      git: Git,
      fs: HarnessFs
  ): Unit =
    fs.write(
      "PATCH-REJECTED.md",
      s"""# Patch rejected by the harness guard
         |
         |$reason
         |
         |This branch is opened for the audit trail ONLY and must NOT be merged. The rejected
         |patch was never applied to the tree. Numstat of the rejected patch (added deleted path):
         |
         |```
         |${numstat.linesIterator.take(100).mkString("\n")}
         |```
         |""".stripMargin
    )
    git.add("PATCH-REJECTED.md")

  private[harness] def numstatPaths(numstat: String): List[String] =
    numstat.linesIterator.toList.flatMap(line => NumstatRow.parse(line).map(_.path))

  /** The three classes the sandbox must never let an agent rewrite (CI workflows, harness code, the
    * constitution) plus docs/ and the control files.
    */
  private[harness] def touchesProtected(numstat: String): Boolean =
    numstatPaths(numstat).exists { p =>
      p.startsWith(".github/") || p.startsWith("harness/") || p.startsWith("docs/") ||
      p == "CONTEXT.md" || p == "PROMPT.md" || p == "STOP.md"
    }

  /** Test-tamper report over the applied patch's numstat, filtered to src/test and src/it. */
  private[harness] def tamperReport(numstat: String): String =
    val parsed = numstat.linesIterator.toList.flatMap(line => NumstatRow.parse(line).map(line -> _))
    def isTestPath(row: NumstatRow): Boolean =
      row.path.startsWith("src/test/") || row.path.startsWith("src/it/")
    val rows    = parsed.collect { case (line, row) if isTestPath(row) => line }
    val touched = rows.size
    val netDel  = parsed.count { case (_, row) =>
      isTestPath(
        row
      ) && row.added != "-" && row.deleted != "-" && row.deleted.toInt > row.added.toInt
    }
    val raw =
      if rows.nonEmpty then s"```\n${rows.mkString("\n")}\n```"
      else "(no test files changed vs origin/main)"
    s"""# Test-tamper report (git apply --numstat on the applied patch, filtered to src/test, src/it)
       |
       |**Summary: $touched test file(s) touched, $netDel with net deletions.**
       |
       |Raw numstat (added  deleted  path; a deleted file shows all lines as deletions):
       |
       |$raw
       |""".stripMargin
