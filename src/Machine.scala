package in.rcard.litterbox

import scala.util.boundary
import scala.util.boundary.break

/** The loop state machine for one US, ported from `harness/loop.sh` iterate(). */
object Machine:

  /** The infra-fault short-circuit channel, formerly a `Raise[InfraFault]` effect capability.
    *
    * `boundary.Label[LoopExit]` is the capability to abandon the current iteration and hand
    * `LoopExit.InfraFault` straight to `runOnce`'s boundary. It carries the same guarantee the
    * `Raise` capability did, and for the same reason: a function that can fault must SAY so in its
    * signature, and no code after a fault can run — so no fault path can decrement the repair budget
    * or dispatch a FIX. That is the v3 invariant, still enforced by the type system.
    *
    * The alias exists so the four signatures that need it read as one named concept rather than as
    * an incidental `boundary.Label`.
    */
  private type Faulting = boundary.Label[LoopExit]

  /** Path of one per-iteration artifact (prompt, patch, gate log, marker) for a US.
    *
    * (was: `val LogDir = "logs"`.) The artifact directory is `Config.logDir` now — a consumer repo
    * says where its loop writes, and `watch.sh` / `tail-claude.sh` read the same key. This is the
    * single place that key and the `issue-<n>` naming convention meet: every artifact site below
    * goes through here, so the layout cannot drift file by file.
    *
    * `suffix` carries its own separator (`.body.md`, `-pass$pass.gate.log`) because the convention
    * uses both `.` and `-` and the caller is the one that knows which.
    */
  private def artifact(issue: Int, suffix: String)(using cfg: Config): String =
    s"${cfg.logDir}/issue-$issue$suffix"

  // (was: `val SandboxDir = "sandbox"`.) Bash's `$SCRIPT_DIR/sandbox` (loop.sh:198-211) was a
  // directory in the repo being worked on, which was correct only while that repo and litter-box's
  // own checkout were the same one. The scripts ship in the artifact now and run from an extraction
  // cache — see `Sandbox`, and #9 for the three ways the old answer broke a scaffolded consumer.

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
  private[litterbox] def sanitizeDetail(detail: String): String =
    detail.replace("\\", "").replace("\"", "").replace("\n", " ")

  /** Extracts the PR number from a `gh pr create` PR URL (last path segment), e.g.
    * `https://github.com/o/r/pull/42` -> `Some(42)`. `None` if the URL has no numeric last segment.
    */
  private[litterbox] def prNumberOf(prUrl: String): Option[Int] =
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
  private[litterbox] def renderTemplate(template: String, splices: (String, String)*): String =
    splices.foldLeft(template) { case (acc, (key, content)) =>
      acc.linesIterator
        .flatMap { line =>
          if line.contains(s"{{$key}}") then content.linesIterator else Iterator(line)
        }
        .mkString("\n")
    }

  /** `{{PROTECTED}}`: the patch guard's list, as markdown bullets for the prompt.
    *
    * The SHAPE of the sentence around it is protocol and stays in the skeleton; the LIST is this
    * repo's and so cannot be. Rendered from `Config.protect` — which `Settings.protectWithFloor`
    * has already unioned with the reference floor — so the prompt names exactly the paths the guard
    * will actually reject, never a stale hand-maintained copy of them.
    */
  private[litterbox] def protectedList(protect: List[String]): String =
    protect.map(p => s"- `$p`").mkString("\n")

  /** Logs an infra fault the way bash does — the message on the operator's log stream at the point
    * of the fault — fires the rc-50 notify seam, and abandons the iteration. Single helper rather
    * than a log+break pair at each of the ten fault sites: `InfraFault.reason` IS the bash log line
    * (see `InfraFault`), so there is exactly one string per fault and no way to log one wording and
    * carry another.
    *
    * The notify used to fire in the old effect library's `Raise.fold` handler, one frame up. With a
    * `boundary` that breaks straight to `LoopExit.InfraFault` there is no handler to hang it on, so
    * it moves here — the observable order (fault line, then notify, then the terminal DONE event
    * `runOnce` emits) is unchanged.
    */
  private def infraFault(reason: String)(using logger: Log, notify: Notify)(using Faulting): Nothing =
    logger.log(reason)
    notify.notify(
      "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
    )
    break(LoopExit.InfraFault)

  /** One driver tick: bounds the infra-fault channel, so a fault anywhere inside `iterate` lands as
    * LoopExit.InfraFault (rc 50), and emits the terminal DONE status event, exactly like the bash
    * driver.
    */
  def runOnce(n: Int)(using
      Config,
      GitHub,
      Git,
      AgentDispatch,
      GateRunner,
      HostGateRunner,
      StatusLog,
      Notify,
      HarnessFs,
      Clock,
      Log
  ): LoopExit =
    val cur  = Cursor()
    val exit = boundary[LoopExit](iterate(n, cur))
    emit(cur, "DONE", "end", detail = s"rc=${exit.rc}")
    exit

  /** One US, start to terminal. Infra faults short-circuit via the `Faulting` boundary: no code past
    * a fault can spend repair budget or dispatch a FIX.
    */
  def iterate(n: Int, cur: Cursor)(using
      cfg: Config,
      gh: GitHub,
      git: Git,
      agents: AgentDispatch,
      gates: GateRunner,
      hostGates: HostGateRunner,
      log: StatusLog,
      notify: Notify,
      fs: HarnessFs,
      clock: Clock,
      logger: Log
  )(using Faulting): LoopExit =
    val setup = pickAndSetup(n, cur) match
      case PickAndSetup.StoppedEarly(exit) => return exit
      case ready: PickAndSetup.Ready       => ready
    import setup.{issue, bodyFile, workerPromptFile, isClass1, branch}

    val implemented = implementAndRepair(n, cur, issue, bodyFile, workerPromptFile) match
      case ImplementAndRepair.StoppedEarly(exit) => return exit
      case ready: ImplementAndRepair.Ready       => ready
    import implemented.{pass, outcome, gateStatus, failureKind, reviewed, reviewFile}

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

    val outcomeText = if outcome == Outcome.Success then "SUCCESS" else "FAIL"
    val kindText    = failureKind.map(_.text).getOrElse("?")

    // Terminal route decided ONCE, here. Every downstream site (label, notify, PR note,
    // auto-merge dispatch, exit code) threads this value instead of re-testing
    // outcome/isClass1 or comparing against a "needs-human" label string.
    val route =
      if outcome == Outcome.Success && isClass1 then Route.AutoMergeCandidate
      else if outcome == Outcome.Success then Route.NeedsReview
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
    fs.write(artifact(issue, ".pr-body.md"), prBody.toString)

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
        gh.editLabels(issue, add = List(label), remove = List(cfg.labels.active))
        logger.log(s"issue #$issue -> $label")
        if route == Route.NeedsReview then LoopExit.Success else LoopExit.NeedsHuman

  /** This is the first of the phase extractions `iterate` is being split into (issue #29 / RFC #26
    * decision 12); making that later split easy is why the pick-and-setup logic gets a name and a
    * return type of its own before anything about its shape changes.
    *
    * Takes `(using Faulting)` even though nothing here calls `infraFault` today: threading the
    * label keeps this phase inside `runOnce`'s boundary, so it cannot return a fault normally into
    * the caller. A fault site added here later will additionally need `Notify` threaded through,
    * since `infraFault` requires it and this function's using clause does not provide it yet.
    */
  private def pickAndSetup(n: Int, cur: Cursor)(using
      cfg: Config,
      gh: GitHub,
      git: Git,
      fs: HarnessFs,
      log: StatusLog,
      logger: Log
  )(using Faulting): PickAndSetup =
    // The stop file is a MANUAL kill-switch only: the loop never writes it itself.
    if fs.stopRequested() then
      logger.log(s"${cfg.stopFile} present (manual kill-switch) — exiting")
      return PickAndSetup.StoppedEarly(LoopExit.ManualStop)

    // Pick US (deterministic, no LLM): resume an in-progress one, else oldest ready.
    // No issue = transient idle — nothing is written, nothing is labelled, so the very next
    // tick resumes on its own when a US goes ready (the idle state must never latch).
    val issue = gh.inProgressIssue().orElse(gh.oldestReadyIssue()) match
      case None =>
        logger.log("no in-progress or ready issue — idle, exiting (next tick resumes when one goes ready)")
        return PickAndSetup.StoppedEarly(LoopExit.Idle)
      case Some(i) => i
    cur.iter = n; cur.issue = issue.toString; cur.pass = 0; cur.budget = cfg.repairBudget
    emit(cur, "PICK", "ok", detail = s"issue=$issue")
    logger.log(s"iteration $n -> issue #$issue")

    // Render the worker prompt with the issue body injected (read-only).
    val bodyFile = artifact(issue, ".body.md")
    fs.write(bodyFile, gh.issueTitleAndBody(issue))
    val workerPromptFile = artifact(issue, ".prompt.txt")
    fs.write(
      workerPromptFile,
      renderTemplate(
        fs.readTemplate(Template.Iterate),
        // Config-derived slots FIRST, untrusted content last: `renderTemplate` folds left, so a
        // slot spliced early has its injected text scanned by every later pass. With ISSUE first,
        // an issue body containing the literal {{GATE}} would have that line rewritten by the
        // harness. Nothing here is secret from the agent, but a prompt reshaped by its own inputs
        // is a prompt nobody reviewed.
        "PROTECTED"   -> protectedList(cfg.protect),
        "GATE"        -> cfg.gateCmd,
        "CONVENTIONS" -> fs.conventions(),
        "ISSUE"       -> fs.read(bodyFile)
      )
    )

    // Auto-merge is earned by class-1 only. Detect the class once, at pick time.
    val isClass1 = gh.issueLabels(issue).contains("class-1")

    // Dry run stops here — before ANY git/label mutation, so it is truly read-only.
    if cfg.dryRun then
      logger.log(
        s"DRY_RUN=1 — rendered worker prompt for #$issue -> $workerPromptFile; no mutation; stopping"
      )
      return PickAndSetup.StoppedEarly(LoopExit.DryRun)

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

    // Mark active so a crashed run resumes the same US next tick.
    gh.editLabels(issue, add = List(cfg.labels.active), remove = List(cfg.labels.ready))

    PickAndSetup.Ready(issue, bodyFile, workerPromptFile, isClass1, branch)

  /** The second of the phase extractions `iterate` is being split into (issue #30 / RFC #26
    * decision 12); the same reasoning as `pickAndSetup` applies here: naming this phase and giving
    * it a return type of its own is what makes the later node conversion a reshape instead of a
    * rewrite. The locals that used to be this phase's real interface, declared before the initial
    * dispatch and carried across the phases by capture, are now the fields of `Ready` below; see
    * that case's scaladoc for which ones and why.
    *
    * `reviewFile` is computed here, not passed in as a parameter: this phase is the only writer
    * (an empty seed before the first review, then each review's raw output), so it belongs with
    * the values `Ready` produces, not with the caller-supplied `bodyFile`/`workerPromptFile`. It
    * is returned as a field of `Ready` so the terminal phase of `iterate` can still read the
    * reviewer transcript for the PR body.
    *
    * `(using Faulting)` still spans the whole function, for the same reason it spans
    * `pickAndSetup`: a fault path that could return normally here would be a fault path that can
    * spend repair budget, and the type system is what rules that out, not code review.
    */
  private def implementAndRepair(
      n: Int,
      cur: Cursor,
      issue: Int,
      bodyFile: String,
      workerPromptFile: String
  )(using
      cfg: Config,
      git: Git,
      agents: AgentDispatch,
      gates: GateRunner,
      fs: HarnessFs,
      log: StatusLog,
      logger: Log,
      notify: Notify
  )(using Faulting): ImplementAndRepair =
    // --- bounded self-repair state -------------------------------------------------------
    // Declared BEFORE the initial dispatch: a patch-guard rejection on the very first worker
    // patch sets outcome/failureKind and skips the repair loop entirely. This function still
    // returns a `Ready`, with `gateStatus` left at "SKIPPED".
    var budget                           = cfg.repairBudget
    var pass                             = 0
    var outcome: Option[Outcome]         = None
    var gateStatus                       = ""
    var failureKind: Option[FailureKind] = None
    var currentPatch: Option[String]     = None
    val reviewFile                       = artifact(issue, "-review.md")
    fs.write(reviewFile, "") // empty until the first review
    var reviewed = false

    // Initial worker dispatch (fresh context), crossing the patch seam. The tree the worker
    // edited is never committed directly.
    val implLog   = artifact(issue, s"-iter$n.claude.log")
    val implPatch = artifact(issue, s"-iter$n.impl.patch")
    emit(cur, "IMPL", "start", implLog)
    stagePatch(Role.IMPL, workerPromptFile, implPatch, implLog, currentPatch) match
      case StageResult.Empty =>
        emit(cur, "IMPL", "ok", implLog, "no diff")
        logger.log("no changes produced by the iteration — leaving issue in-progress, not opening a PR")
        return ImplementAndRepair.StoppedEarly(LoopExit.NothingMade)
      case result =>
        handleStageResult(cur, Role.IMPL, implLog, result) match
          case StageVerdict.Applied(p)     => currentPatch = Some(p)
          case StageVerdict.Rejected(kind) =>
            outcome = Some(Outcome.Fail); failureKind = Some(kind); gateStatus = "SKIPPED"

    // The fixer dispatch across the patch seam plus the mapping of its StageResult onto the
    // repair loop's control flow (bash dispatch_fix + handle_fix_result). Infra faults raise;
    // guard rejections and an empty fix become the terminal FAIL; Ok advances currentPatch.
    def fixRound(pass: Int, failFile: String): Unit =
      val fixPromptFile = artifact(issue, s"-pass$pass.fix.prompt.txt")
      fs.write(
        fixPromptFile,
        renderTemplate(
          fs.readTemplate(Template.Fix),
          "PROTECTED"   -> protectedList(cfg.protect),
          "GATE"        -> cfg.gateCmd,
          "CONVENTIONS" -> fs.conventions(),
          "ISSUE"       -> fs.read(bodyFile),
          "FAILURE"     -> fs.read(failFile)
        )
      )
      val fixLog   = artifact(issue, s"-pass$pass.fix.claude.log")
      val fixPatch = artifact(issue, s"-pass$pass.fix.patch")
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
        val failFile = artifact(issue, s"-pass$pass.failure.md")
        fs.write(failFile, failContent)
        fixRound(pass, failFile)

    // --- bounded self-repair loop --------------------------------------------------------
    // Skipped entirely if the initial patch was already rejected (outcome set above).
    while outcome.isEmpty do
      pass += 1
      git.addAll() // stage so new files show in diff/gate/tamper
      cur.pass = pass
      val gateLog = artifact(issue, s"-pass$pass.gate.log")
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
            s"## FAST gate RED (pass $pass)\n\n" +
              s"The fast tier gate command is `${cfg.gateCmd}`. It ran at the repository root and " +
              s"exited with a nonzero status.\n\n" +
              s"Tail of the fast-gate log:\n\n```\n${fs.read(gateLog)}\n```\n"
          )
        case GateResult.Green =>
          gateStatus = "GREEN"
          emit(cur, "FAST_GATE", "ok", gateLog)
          logger.log(s"FAST gate GREEN (pass $pass) — running tamper check + cold reviewer")

          // Tamper check feeds the reviewer (the harness surfaces, does not block).
          val tamperFile = artifact(issue, "-tamper.md")
          fs.write(tamperFile, tamperReport(currentPatch.map(git.applyNumstat).getOrElse("")))
          val diffFile = artifact(issue, "-diff.patch")
          fs.write(diffFile, git.diffCachedOriginMain())
          val reviewPromptFile = artifact(issue, s"-pass$pass.review.prompt.txt")
          fs.write(
            reviewPromptFile,
            renderTemplate(
              fs.readTemplate(Template.Review),
              "PROTECTED"   -> protectedList(cfg.protect),
              "GATE"        -> cfg.gateCmd,
              "CONVENTIONS" -> fs.conventions(),
              "ISSUE"       -> fs.read(bodyFile),
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

    // `outcome.getOrElse(Outcome.Fail)`: unreachable in practice; see `Ready`'s scaladoc.
    ImplementAndRepair.Ready(pass, outcome.getOrElse(Outcome.Fail), gateStatus, failureKind, reviewed, reviewFile)

  /** v4 auto-merge (class-1 + APPROVE only): wait-appear -> watch -> merge -> VERIFY the PR state
    * is MERGED (unverified = infra fault) -> drop in-progress -> flip blocked -> fetch -> notify.
    * CI red after green local gates = needs-human WITHOUT self-repair: the loop never repairs
    * against the independent check.
    */
  private def autoMerge(issue: Int, prNum: Int, cur: Cursor)(using
      cfg: Config,
      gh: GitHub,
      git: Git,
      hostGates: HostGateRunner,
      log: StatusLog,
      notify: Notify,
      clock: Clock,
      logger: Log
  )(using Faulting): LoopExit =
    val ciLog = artifact(issue, ".ci-wait.log")
    emit(cur, "CI_WAIT", "start", ciLog)
    // Discriminate on data, not on the exit code: a fresh PR routinely reports zero checks
    // for a few seconds (push races the workflow scheduler, PR #28 / issue #26). Block until
    // the rollup is non-empty, and only then let the CI watch judge. A check that never
    // registers is a scheduler/infra problem, never rc 40.
    if !waitForChecks(prNum) then
      infraFault(
        s"no CI check registered on PR #$prNum within ${cfg.ciAppearTimeout}s — infra fault; PR open, issue stays in-progress"
      )
    // The HOST runner, never the gate one — see `HostGateRunner` (issue #11).
    hostGates.run(
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
        if !gh.editLabels(issue, add = List("needs-human"), remove = List(cfg.labels.active)) then
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
        gh.editLabels(issue, add = Nil, remove = List(cfg.labels.active))
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
  private[litterbox] def parseBlockedBy(body: String): List[Int] =
    "Blocked-by: #(\\d+)".r.findAllMatchIn(body).map(_.group(1).toInt).toList

  /** After a verified merge, flip every open `blocked` issue whose Blocked-by refs are ALL closed.
    * The just-merged issue counts as closed even if GitHub's async close lags the merge. Issues
    * without the sentinel are left alone (human-managed).
    */
  private def flipBlocked(mergedIssue: Int)(using cfg: Config, gh: GitHub, logger: Log): Unit =
    val (blocked, ready) = (cfg.labels.blocked, cfg.labels.ready)
    gh.openBlockedIssues().foreach { b =>
      val refs = parseBlockedBy(gh.issueBody(b))
      if refs.nonEmpty then
        val allClosed = refs.forall(r => r == mergedIssue || gh.issueState(r) == "CLOSED")
        if allClosed then
          logger.log(s"dependency #$mergedIssue closed — flipping #$b $blocked -> $ready")
          if !gh.editLabels(b, add = List(ready), remove = List(blocked)) then
            logger.log(s"WARNING: could not flip #$b $blocked -> $ready (flip by hand)")
    }

  /** What `pickAndSetup` concluded: either `iterate` stops immediately with the carried `LoopExit`
    * (manual stop, idle, dry run — none of them mutate git or labels), or the phase ran to
    * completion and everything the rest of `iterate` needs is here.
    *
    * A sum type rather than, say, an `Option` of a result tuple plus a separate exit code: the two
    * cases really do have different shapes, and naming both is what lets `iterate` read as "call the
    * phase, then branch" instead of re-deriving the early-exit condition at the call site (issue #29
    * / RFC #26 decision 12 — extract the phase first, so the later node conversion is a reshape).
    */
  private enum PickAndSetup:
    /** The phase stopped on its own before touching git or labels; `exit` is what `iterate` must
      * return unchanged. `exit` is never `LoopExit.InfraFault`: an infra fault goes through
      * `infraFault`, not through this case, because routing it here would skip the fault log line
      * and the notify that `infraFault` is responsible for.
      */
    case StoppedEarly(exit: LoopExit)

    /** So the call site reads as plain names instead of `setup.foo` accessors, the field names are
      * the ones `iterate` imports them as.
      */
    case Ready(
        issue: Int,
        bodyFile: String,
        workerPromptFile: String,
        isClass1: Boolean,
        branch: String
    )

  /** What `implementAndRepair` concluded: either the initial IMPL patch was empty and `iterate`
    * stops immediately with the carried `LoopExit`, or the phase ran to completion (the initial
    * dispatch, zero or more repair passes, and a final gate/review outcome) and everything the
    * terminal phase of `iterate` reads is here.
    *
    * Same shape as `PickAndSetup` and for the same reason (issue #30 / RFC #26 decision 12): the
    * two cases genuinely differ, so naming both lets `iterate` read as "call the phase, then
    * branch" instead of re-deriving the early-exit condition at the call site.
    */
  private enum ImplementAndRepair:
    /** The only early stop in this phase: an empty initial IMPL patch. `exit` is always
      * `LoopExit.NothingMade` in practice; kept as `LoopExit` rather than hardcoded so this case
      * has the same shape as `PickAndSetup.StoppedEarly`. Never `LoopExit.InfraFault`, for the
      * same reason as `PickAndSetup.StoppedEarly`: a fault goes through `infraFault`'s `break`,
      * which never returns here at all.
      */
    case StoppedEarly(exit: LoopExit)

    /** The values that used to be mutable locals declared before the initial dispatch and read by
      * `iterate` long after this phase returned, plus `reviewFile`. `budget` and `currentPatch`
      * are not here. `currentPatch` is pure bookkeeping internal to the repair loop, never read
      * once this function returns. `budget` leaves through the shared `cur.budget`, which `emit`
      * copies into every `StatusEvent`; it does not leave through this return value, so there is
      * no local copy to carry here either. Field names match what `iterate` imports them as, same
      * convention as `PickAndSetup.Ready`.
      *
      * `outcome` is `Outcome`, not `Option[Outcome]`. The `while outcome.isEmpty` loop above can
      * only exit with `Some`, and the only other way to reach this `Ready` is the initial-patch
      * rejection path, which already set `Some(Outcome.Fail)`. So `None` was never reachable here;
      * this is the one field where the explicit result type discharges that invariant for free
      * instead of carrying a case nothing produces. The construction collapses it with
      * `outcome.getOrElse(Outcome.Fail)`, which is exactly what the terminal used to do by reading
      * `outcome.contains(Outcome.Success)`: a `None` there already meant `Fail`, so the collapse
      * changes no observable behaviour. `failureKind` stays `Option[FailureKind]`: that one
      * genuinely can be `None` (a clean gate GREEN plus a reviewer APPROVE never sets it).
      */
    case Ready(
        pass: Int,
        outcome: Outcome,
        gateStatus: String,
        failureKind: Option[FailureKind],
        reviewed: Boolean,
        reviewFile: String
    )

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
  private[litterbox] def parseVerdict(review: String): Option[Verdict] =
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
  )(using log: StatusLog, logger: Log, notify: Notify)(using Faulting): StageVerdict =
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
    if touchesProtected(cfg.protect, numstat) then
      logger.log(
        s"patch guard: patch touches a protected path (${cfg.protect.mkString(", ")}) — rejecting (not applied)"
      )
      writeRejectMarker(
        "Patch touches a protected path (CI workflow, loop code, docs, or a control/constitution file).",
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

  private[litterbox] def numstatPaths(numstat: String): List[String] =
    numstat.linesIterator.toList.flatMap(line => NumstatRow.parse(line).map(_.path))

  /** Whether a patch touches anything the consumer repo declared off-limits in `protect` — CI
    * workflows, the loop's own installed files, the constitution, whatever that repo names.
    *
    * The list used to be a literal here, enumerating THIS repo's layout, which only worked while the
    * loop and the repo it worked on were the same checkout. Now it arrives as globs off the config,
    * so a consumer repo protects its own paths and the loop protects everything under
    * `.litter-box` — including the config file that defines this very list, which is what stops an
    * agent from widening its own guard.
    */
  private[litterbox] def touchesProtected(protect: List[String], numstat: String): Boolean =
    numstatPaths(numstat).exists(p => Settings.isProtected(protect, p))

  /** Test-tamper report over the applied patch's numstat, filtered to src/test and src/it. */
  private[litterbox] def tamperReport(numstat: String): String =
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
