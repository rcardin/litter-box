package com.example.reviewfix

// A worked, RUNNABLE example of a consumer graph authored on litter-box's public kit API: the
// `loop.scala` a consumer repository writes for itself, carrying its own nodes, its own edge types
// and its own bounded review/fix cycle, and handing the whole thing to `LitterBox.run`.
//
// IMPLEMENT -> GATE -> REVIEW -> FIX (one dispatch per finding), cycling REVIEW/FIX at most
// `MaxRounds` times, then opening a PR.
//
// It lives under `test/`, not under `docs/`, and that is the whole reason it is worth reading. An
// example nobody compiles rots silently against the API it claims to demonstrate; this one is
// compiled by `scala-cli test .` and WALKED by `test/ReviewFixLoopExampleSpec.scala` against a
// `TestWorld`, so the day a kit signature moves, this file is a build failure rather than a lie.
//
// A consumer's own copy differs from this one in two ways, neither of which they have to carry: it
// is a package-less top level script, and it opens with a scala-cli `using dep` directive naming
// `in.rcard::litter-box`. TEST.md, under "The worked consumer example", has why this copy has a
// package and no directive, and why `docs/` was not an option.
//
// `@main def loop` at the very bottom is declared and never invoked, the identical discipline
// `test/ScaffoldedLoopBoundarySpec.scala` follows for the identical reason (TEST.md: everything
// under `test/` stays Docker free, network free and credential free, and `LitterBox.run` both
// resolves a published coordinate over the network and terminates the JVM).
//
// What this file CANNOT do on 0.3.0: pick a model per role. Every dispatch below, worker and
// reviewer alike, runs whatever model the `claude` CLI defaults to inside the sandbox image
// (see issue #73). The role split is here and ready for the day the model is configurable.

import in.rcard.litterbox.*
import in.rcard.litterbox.Caps.given

// ---------------------------------------------------------------------------------------------
// The values that travel along the graph's edges.
// ---------------------------------------------------------------------------------------------

/** One picked issue and the paths this tick writes under. `patch` is the CUMULATIVE patch vs
  * origin/main produced so far: every FIX dispatch is seeded with it, so a fixer starts from the
  * work already done rather than from a pristine tree.
  */
final case class Work(
    issue: Int,
    branch: String,
    bodyFile: String,
    patch: Option[String],
    gateRedLog: Option[String]
)

/** What travels into `Gate` and out of it into `Review`: the work so far, and which round of the
  * cycle is about to be reviewed. Plain: reaching the reviewer needs no prior review.
  *
  * The round number rides in this value rather than being captured by a closure because the graph is
  * a table now (issue #67), and an edge sees nothing but the output of the node it leaves. Making the
  * counter part of the data is what lets the same `Gate -> Review` edge serve both the first pass and
  * every later one, stated once.
  */
final case class ReviewRound(work: Work, round: Int)

/** What the reviewer answered, once parsed. Travels wrapped in `AgentDispatch.Judged`, which is
  * what stamps `Trust.Reviewed` on the `Review` node and clears `Fix`'s own guard.
  */
final case class Reviewed(work: Work, round: Int, findings: List[String])

/** `Fix`'s input. Extending `RequiresReviewInput` stamps `Guard.RequiresReview` on the node, so
  * `LitterBox.graph`'s macro rejects, at COMPILE time, any shape that can reach `Fix` without
  * crossing `Review` first. This is the whole point of routing a gate failure through the reviewer
  * below instead of straight into `Fix`.
  */
final case class FixRound(work: Work, round: Int, findings: List[String]) extends RequiresReviewInput

/** `OpenPr`'s input: `needsHuman` is true when the rounds ran out with findings still open. */
final case class PrRequest(work: Work, needsHuman: Boolean)

/** `OpenPr`'s output. Carries `needsHuman` back out again so the edge that ends the run can pick the
  * `LoopExit` from the value alone: which exit this graph reaches is a fact about how the cycle
  * ended, and the only place an edge can read such a fact is the output of the node it leaves.
  */
final case class PrOpened(pr: Int, needsHuman: Boolean)

// ---------------------------------------------------------------------------------------------
// Helpers. Nothing here is library API: `Machine.renderTemplate`, `Machine.touchesProtected` and
// `Settings.isProtected` are all `private[litterbox]`, so a consumer graph restates them.
// ---------------------------------------------------------------------------------------------

val MaxRounds = 3

/** The reviewer contract this graph imposes on its own review prompt: one `FINDING: <text>` line
  * per problem, and `VERDICT: APPROVE` when there is nothing left. No findings and no APPROVE
  * sentinel is read as "findings we could not parse", never as an approval.
  */
val FindingMarker = "FINDING:"
val ApproveMarker = "VERDICT: APPROVE"

/** What a reviewer answer carrying neither is turned into, so the cycle keeps going instead of
  * reading silence as approval.
  */
val UnreadableFinding = "the reviewer's answer could not be parsed; re-read the diff and state the problems"

def artifact(issue: Int, suffix: String)(using cfg: Config): String =
  s"${cfg.logDir}/issue-$issue$suffix"

/** `render_template`'s line-splice contract: a line containing `{{KEY}}` is REPLACED by the
  * spliced content, one pass, so spliced text is never rescanned for another marker.
  */
def splice(template: String, values: (String, String)*): String =
  template.linesIterator
    .map(line =>
      values.collectFirst { case (key, content) if line.contains(s"{{$key}}") => content }
        .getOrElse(line)
    )
    .mkString("\n")

/** The review prompt: the shared template, spliced with everything a cold reviewer is allowed to
  * see, plus the answer shape `parseFindings` below reads back. The two sit next to each other so
  * that a change to either is read against the other.
  */
def reviewPrompt(work: Work, diff: String)(using caps: Caps): String =
  val cfg = caps.cfg
  splice(
    caps.fs.readTemplate(Template.Review),
    "PROTECTED"   -> cfg.protect.map(p => s"- $p").mkString("\n"),
    "GATE"        -> cfg.gateCmd,
    "CONVENTIONS" -> caps.fs.conventions(),
    "ISSUE"       -> caps.fs.read(work.bodyFile),
    "TAMPER"      -> work.gateRedLog.map(caps.fs.read).getOrElse("(the FAST gate was green)"),
    "DIFF"        -> diff
  ) + s"""
         |
         |Answer in this shape, and in no other:
         |  one line `$FindingMarker <what is wrong, and where>` per problem you found,
         |  then a final line `$ApproveMarker` if and only if you found none.
         |""".stripMargin

/** One finding per marked line, the marker and the space around it dropped. Anything else the
  * reviewer wrote is not a finding: the caller decides what an answer carrying none of these means.
  */
def parseFindings(text: String): List[String] =
  text.linesIterator
    .map(_.trim)
    .filter(_.startsWith(FindingMarker))
    .map(_.drop(FindingMarker.length).trim)
    .filter(_.nonEmpty)
    .toList

/** The patch guard, restated. Prefix matching on the `**` glob shape `protect` uses, which is
  * narrower than the library's own JDK-glob matcher: widen it if your `protect` list uses `*`
  * anywhere but at the end.
  */
def touchesProtected(protect: List[String], numstat: String): Boolean =
  val paths = numstat.linesIterator.toList.flatMap(line => NumstatRow.parse(line).map(_.path))
  paths.exists(p => protect.exists(g => p.startsWith(g.stripSuffix("**").stripSuffix("*"))))

def emit(
    issue: Int,
    phase: String,
    state: String,
    round: Int,
    logFile: String,
    detail: String = ""
)(using caps: Caps): Unit =
  caps.status.append(
    StatusEvent(
      iter = 0,
      issue = issue.toString,
      phase = phase,
      state = state,
      pass = round,
      budget = caps.cfg.repairBudget,
      logfile = logFile,
      detail = detail
    )
  )

/** Dispatch one worker, then INSPECT the patch before applying it: reset to the pristine base
  * first, refuse an oversized or protected-path patch, and only then `git apply --index`. The tree
  * the agent edited is data, never trust.
  *
  * `Some(patchOut)` = applied. `None` = the agent produced nothing. A timeout, a guard rejection or
  * a refused apply raises an infra fault: none of them is a code verdict.
  */
def stagePatch(
    role: Role,
    promptFile: String,
    patchOut: String,
    logFile: String,
    currentPatch: Option[String]
)(using caps: Caps, fault: Fault): Option[String] =
  val cfg = caps.cfg
  caps.agents.worker(role, promptFile, patchOut, logFile, currentPatch) match
    case DispatchOutcome.TimedOut =>
      fault.raise(s"$role dispatch timed out — infra fault, no budget spent")
    case DispatchOutcome.Done => ()
  caps.git.resetHardCleanToOriginMain()
  if caps.fs.sizeBytes(patchOut) == 0 then None
  else
    val numstat = caps.git.applyNumstat(patchOut)
    if caps.fs.sizeBytes(patchOut) > cfg.maxPatchBytes then
      fault.raise(s"patch guard: $patchOut exceeds the ${cfg.maxPatchBytes}-byte cap — not applied")
    if touchesProtected(cfg.protect, numstat) then
      fault.raise(s"patch guard: $patchOut touches a protected path — not applied")
    if !caps.git.applyIndex(patchOut) then
      fault.raise(s"git apply refused $patchOut — infra fault, no budget spent")
    Some(patchOut)

// ---------------------------------------------------------------------------------------------
// The nodes. Every one is a top-level `val`: `LitterBox.graph`'s macro reads the SOURCE of the
// shape below, so a node built by a `def` would be unreadable to it.
// ---------------------------------------------------------------------------------------------

/** Pick an issue, branch off origin/main, render the IMPLEMENT prompt. Dispatches nothing. */
val Setup: Node[Int, Work] = Node(
  name = "Setup",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = (_: Int) =>
    val caps  = summon[Caps]
    val cfg   = caps.cfg
    val fault = summon[Fault]
    if caps.fs.stopRequested() then NodeOutcome.Stopped(LoopExit.ManualStop)
    else
      caps.gh.inProgressIssue().orElse(caps.gh.oldestReadyIssue()) match
        case None => NodeOutcome.Stopped(LoopExit.Idle)
        case Some(issue) =>
          val bodyFile = artifact(issue, ".body.md")
          caps.fs.write(bodyFile, caps.gh.issueTitleAndBody(issue))
          caps.fs.write(
            artifact(issue, ".impl.prompt.txt"),
            splice(
              caps.fs.readTemplate(Template.Iterate),
              "PROTECTED"   -> cfg.protect.map(p => s"- $p").mkString("\n"),
              "GATE"        -> cfg.gateCmd,
              "CONVENTIONS" -> caps.fs.conventions(),
              "ISSUE"       -> caps.fs.read(bodyFile)
            )
          )
          // Read-only up to here, so the dry run stops before the first mutation.
          if cfg.dryRun then NodeOutcome.Stopped(LoopExit.DryRun)
          else
            if !caps.git.statusClean() then fault.raise("working tree not clean — refusing to start")
            if !caps.git.fetchOriginMain() then fault.raise("cannot fetch origin/main")
            val branch = s"us-$issue"
            if !caps.git.checkoutBranch(branch) then fault.raise("cannot branch off origin/main")
            if !caps.gh.editLabels(issue, add = List(cfg.labels.active), remove = List(cfg.labels.ready))
            then caps.logger.log(s"WARNING: could not flip #$issue to ${cfg.labels.active}")
            caps.logger.log(s"picked issue #$issue on $branch")
            NodeOutcome.Done(Work(issue, branch, bodyFile, patch = None, gateRedLog = None))
)

/** One IMPLEMENT dispatch. */
val Implement: Node[Work, Work] = Node(
  name = "Implement",
  cost = Cost.OneDispatch,
  // Unbounded: the real bound is `timeouts.iter`, enforced at the subprocess boundary, and a node
  // level `Timeout.After` would have to read `Config`, which is only in scope inside this body.
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = (work: Work) =>
    val caps    = summon[Caps]
    val logFile = artifact(work.issue, ".impl.log")
    emit(work.issue, "IMPLEMENT", "start", 0, logFile)
    val staged = stagePatch(
      Role.IMPL,
      artifact(work.issue, ".impl.prompt.txt"),
      artifact(work.issue, ".patch"),
      logFile,
      currentPatch = None
    )
    staged match
      case None =>
        emit(work.issue, "IMPLEMENT", "red", 0, logFile, "empty patch")
        caps.logger.log("the worker produced no changes")
        NodeOutcome.Stopped(LoopExit.NothingMade)
      case Some(patch) =>
        emit(work.issue, "IMPLEMENT", "ok", 0, logFile)
        NodeOutcome.Done(work.copy(patch = Some(patch)))
)

/** The FAST gate. A red gate is NOT routed straight into `Fix`: `Fix` is guarded, so that path
  * would be rejected at compile time. The red log travels on `Work` into the review prompt
  * instead, and the reviewer's findings are what the fixer acts on.
  */
val Gate: Node[ReviewRound, ReviewRound] = Node(
  name = "Gate",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = (pending: ReviewRound) =>
    val caps    = summon[Caps]
    val cfg     = caps.cfg
    val fault   = summon[Fault]
    val work    = pending.work
    val gateLog = artifact(work.issue, ".gate.log")
    emit(work.issue, "FAST_GATE", "start", 0, gateLog)
    caps.gates.run("FAST", cfg.gateCmd, cfg.gateTimeout, gateLog) match
      case GateResult.Timeout =>
        fault.raise(s"FAST gate hit the ${cfg.gateTimeout}s timeout — infra fault, not a code failure")
      case GateResult.Red =>
        emit(work.issue, "FAST_GATE", "red", 0, gateLog)
        caps.logger.log(s"FAST gate RED — the reviewer will see $gateLog")
        NodeOutcome.Done(pending.copy(work = work.copy(gateRedLog = Some(gateLog))))
      case GateResult.Green =>
        emit(work.issue, "FAST_GATE", "ok", 0, gateLog)
        NodeOutcome.Done(pending.copy(work = work.copy(gateRedLog = None)))
)

/** One adversarial review. Output is `AgentDispatch.Judged`, which is what earns this node
  * `Trust.Reviewed` and clears `Fix`'s guard on every path through here.
  */
val Review: Node[ReviewRound, AgentDispatch.Judged[Reviewed]] = Node(
  name = "Review",
  cost = Cost.OneDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = (round: ReviewRound) =>
    val caps       = summon[Caps]
    val fault      = summon[Fault]
    val work       = round.work
    val diffFile   = artifact(work.issue, s"-r${round.round}.diff.patch")
    val reviewFile = artifact(work.issue, s"-r${round.round}.review.md")
    caps.fs.write(diffFile, caps.git.diffCachedOriginMain())
    val prompt = reviewPrompt(work, caps.fs.read(diffFile))
    emit(work.issue, "REVIEW", "start", round.round, reviewFile)
    val judged = caps.agents.review(prompt, reviewFile)
    judged.value match
      case DispatchOutcome.TimedOut =>
        emit(work.issue, "REVIEW", "red", round.round, reviewFile, "timeout")
        fault.raise("REVIEWER timed out — infra fault; exiting without spending budget")
      case DispatchOutcome.Done => ()
    val text = caps.fs.read(reviewFile)
    if text.isBlank then
      emit(work.issue, "REVIEW", "red", round.round, reviewFile, "empty review")
      fault.raise("reviewer produced no output — infra fault (crashed or timed-out reviewer)")
    val parsed = parseFindings(text)
    // Fail safe: no findings AND no approval sentinel is an answer we could not read, never an
    // approval. A red gate is a finding of its own, whatever the reviewer said.
    val unreadable = parsed.isEmpty && !text.contains(ApproveMarker)
    if unreadable then
      caps.logger.log("reviewer emitted neither a finding nor an approval — fail-safe REQUEST_CHANGES")
    val gateFinding = work.gateRedLog.map(l => s"the FAST gate is RED, see $l — make it green")
    val findings    = gateFinding.toList ++ parsed ++ Option.when(unreadable)(UnreadableFinding).toList
    emit(work.issue, "REVIEW", "ok", round.round, reviewFile, s"findings=${findings.size}")
    caps.logger.log(s"review round ${round.round}: ${findings.size} finding(s)")
    // `map`, not a fresh value: the token stays on the answer that came out of the dispatch.
    NodeOutcome.Done(judged.map(_ => Reviewed(work, round.round, findings)))
)

/** One FIX dispatch PER FINDING, sequentially, each seeded with the cumulative patch so far.
  *
  * `cost = Cost.OneDispatch` is the honest declaration the kit offers, and it is a floor, not a
  * ceiling: `dispatchBudget` gates whether this node may START, while the runner charges every one
  * of the N dispatches this body makes. Size `budgets.repair` for MaxRounds * findings, or cap
  * `findings` here.
  */
val Fix: Node[FixRound, ReviewRound] = Node(
  name = "Fix",
  cost = Cost.OneDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = (fix: FixRound) =>
    val caps = summon[Caps]
    val cfg  = caps.cfg
    val work = fix.work
    val fixed = fix.findings.zipWithIndex.foldLeft(work) { case (acc, (finding, i)) =>
      val tag        = s"r${fix.round}-f$i"
      val promptFile = artifact(work.issue, s"-$tag.fix.prompt.txt")
      val logFile    = artifact(work.issue, s"-$tag.fix.log")
      val patchOut   = artifact(work.issue, s"-$tag.patch")
      caps.fs.write(
        promptFile,
        splice(
          caps.fs.readTemplate(Template.Fix),
          "PROTECTED"   -> cfg.protect.map(p => s"- $p").mkString("\n"),
          "GATE"        -> cfg.gateCmd,
          "CONVENTIONS" -> caps.fs.conventions(),
          "ISSUE"       -> caps.fs.read(work.bodyFile),
          "FAILURE"     -> s"A cold reviewer found this, and only this, to fix:\n\n$finding",
          "COMMENTS"    -> ""
        )
      )
      emit(work.issue, "FIX", "start", fix.round, logFile, finding.take(120))
      stagePatch(Role.FIX, promptFile, patchOut, logFile, currentPatch = acc.patch) match
        case Some(patch) =>
          emit(work.issue, "FIX", "ok", fix.round, logFile)
          acc.copy(patch = Some(patch))
        case None =>
          // An empty fix is an outcome, not a fault: the next round's reviewer sees the finding
          // unaddressed and says so again.
          emit(work.issue, "FIX", "red", fix.round, logFile, "empty patch")
          caps.logger.log(s"the fixer produced no changes for: $finding")
          acc
    }
    // The round is incremented HERE, on the way out, because the edge back into `Gate` reads its
    // next round from this value and nowhere else.
    NodeOutcome.Done(ReviewRound(fixed, fix.round + 1))
)

/** Commit, push, open the PR. Adopts an OPEN PR already on the branch rather than trusting a
  * stored position, so a crashed tick that already opened one does not try again.
  */
val OpenPr: Node[PrRequest, PrOpened] = Node(
  name = "OpenPr",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = (req: PrRequest) =>
    val caps  = summon[Caps]
    val fault = summon[Fault]
    val work  = req.work
    caps.git.addAll()
    if !caps.git.anythingStaged() then
      caps.logger.log("nothing staged — no PR to open")
      NodeOutcome.Stopped(LoopExit.NothingMade)
    else
      caps.git.commit(s"feat: #${work.issue}\n\nCloses #${work.issue}")
      caps.git.push(work.branch)
      val pr = caps.gh.prForBranch(work.branch).getOrElse {
        val url = caps.gh.createPr(
          work.branch,
          s"#${work.issue}: automated change",
          if req.needsHuman then
            s"Closes #${work.issue}\n\nThe review/fix cycle ran out after $MaxRounds rounds with findings still open. NEEDS HUMAN."
          else s"Closes #${work.issue}\n\nA cold reviewer found nothing left to fix."
        )
        url.split('/').lastOption.flatMap(_.toIntOption).getOrElse {
          fault.raise(s"could not read a PR number out of '$url'")
        }
      }
      emit(work.issue, "PR", "ok", 0, "", s"pr=$pr")
      caps.logger.log(s"opened PR #$pr for issue #${work.issue}")
      NodeOutcome.Done(PrOpened(pr, req.needsHuman))
)

// ---------------------------------------------------------------------------------------------
// The graph.
// ---------------------------------------------------------------------------------------------

/** The whole graph, written once (issue #67, RFC #26 decision 5): each edge names where it leaves,
  * where it arrives, and what the arriving node is handed, and nothing states any of that a second
  * time. `LitterBox.graph` derives the walk the runner executes AND the `Shape` its validator and its
  * compile time macro read from this one table, so the check and the run can no longer describe
  * different graphs.
  *
  * The cycle is an ordinary edge back to a node already named, `Fix -> Gate`, not a recursive
  * function: a table can name a node it has already named, which is what makes a bounded loop
  * expressible as data at all. What bounds it is the round number riding in `ReviewRound`, read by
  * the two edges leaving `Review`.
  *
  * Those two edges are ALTERNATIVES and answer `None` for the value that is not theirs: findings
  * still open with rounds left goes to `Fix`, everything else goes to `OpenPr`, which is also where
  * the `needsHuman` decision is made and handed on. Written as a literal right here, out of top level
  * `val`s, because this is what the macro reads to prove no path reaches `Fix` without crossing
  * `Review`.
  */
val graph: LoopGraph = LitterBox.graph(
  name = "review-fix-cycle",
  plan = Plan(
    entry = Setup,
    edges = List(
      Edge.To(Setup, Implement, (work: Work) => Some(work)),
      Edge.To(Implement, Gate, (work: Work) => Some(ReviewRound(work, 1))),
      Edge.To(Gate, Review, (pending: ReviewRound) => Some(pending)),
      Edge.To(
        Review,
        Fix,
        (judged: AgentDispatch.Judged[Reviewed]) =>
          val reviewed = judged.value
          if reviewed.findings.nonEmpty && reviewed.round < MaxRounds then
            Some(FixRound(reviewed.work, reviewed.round, reviewed.findings))
          else None
      ),
      Edge.To(
        Review,
        OpenPr,
        (judged: AgentDispatch.Judged[Reviewed]) =>
          val reviewed = judged.value
          if reviewed.findings.isEmpty then Some(PrRequest(reviewed.work, needsHuman = false))
          else if reviewed.round >= MaxRounds then Some(PrRequest(reviewed.work, needsHuman = true))
          else None
      ),
      Edge.To(Fix, Gate, (pending: ReviewRound) => Some(pending)),
      Edge.Exit(
        OpenPr,
        (opened: PrOpened) =>
          Some(if opened.needsHuman then LoopExit.NeedsHuman else LoopExit.Success)
      )
    )
  ),
  // One IMPLEMENT + MaxRounds * (one REVIEW + one FIX node start). The FIX node dispatches once
  // per finding and every one of those is charged, so raise `budgets.repair` if your reviewer is
  // wordy: the runner refuses the NEXT node once this runs out, mid-cycle.
  dispatchBudget = (cfg: Config) => 1 + MaxRounds * (1 + cfg.repairBudget),
  startInput = (tick: Int) => tick,
  stages = StageSet(
    stages = List(
      Stage("IMPLEMENT", "impl", 1),
      Stage("FAST_GATE", "gate", 1),
      Stage("REVIEW", "review", 2),
      Stage("FIX", "fix", 2, badge = true),
      Stage("PR", "pr", 2)
    ),
    anchor = Some("IMPLEMENT"),
    terminal = Some("PR")
  )
)

@main def loop(args: String*): Unit = LitterBox.run(graph, args)
