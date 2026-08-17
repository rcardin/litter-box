package in.rcard.litterbox

import scala.collection.mutable
import scala.util.boundary

/** Scripted in-memory handlers for every capability, plus an interaction recorder.
  *
  * Scenarios assert on BOTH the outcome (LoopExit, labels flipped, PR opened, budget left) and the
  * interaction sequence (`calls`): no FIX after an infra fault, no merge without verification,
  * marker staged on a guard rejection.
  *
  * Patch contents use a tiny numstat DSL: each line `added<TAB>deleted<TAB>path` (or `-` for
  * binary). FakeGit's applyNumstat returns exactly the well-formed lines — so garbage content
  * yields an empty numstat (the bash fail-open) — and applyIndex succeeds only when the whole patch
  * is well-formed and `applySucceeds` is not scripted false.
  */
object Script:
  /** What a scripted worker/fixer dispatch does. */
  enum WorkerScript:
    /** Writes `content` (numstat DSL or garbage) to patchOut. */
    case Produces(content: String)

    /** Writes nothing: the agent produced no diff. */
    case Empty

    /** Simulates the container-dispatch timeout (rc 124). */
    case TimedOut

  /** What a scripted reviewer dispatch does. */
  enum ReviewScript:
    /** Writes `output` to the review file. */
    case Says(output: String)

    /** Simulates the reviewer dispatch timeout (rc 124). */
    case TimedOut

  val newFilePatch: String  = "1\t0\tsrc/main/scala/Slice.scala"
  val approveReview: String = "checked AC1/AC2, tests present.\nVERDICT: APPROVE"

final class TestWorld:
  import Script.*

  // ---- recorder --------------------------------------------------------------------------
  val calls: mutable.ArrayBuffer[String]         = mutable.ArrayBuffer.empty
  val notifications: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val events: mutable.ArrayBuffer[StatusEvent]   = mutable.ArrayBuffer.empty
  val files: mutable.Map[String, String]         = mutable.Map.empty

  /** Every `StatusLog.declare` call a scenario's `runLoop` calls made, in order (issue #40). A
    * separate buffer from `events`, the same split `StatusLog` itself draws between `append` and
    * `declare`: a scenario asserting "declared once per tick" reads this one, never `events`, so
    * neither buffer has to grow a filter to answer a question the other already answers cleanly.
    */
  val declaredStages: mutable.ArrayBuffer[StageSet] = mutable.ArrayBuffer.empty

  /** Whether `events` was still empty at the moment each `declare` call in `declaredStages` landed,
    * same index for same index. A scenario proving "the declaration is written before the first
    * status event" reads this rather than inferring order from two buffers that carry no shared
    * sequence number of their own.
    */
  val declaredBeforeAnyEvent: mutable.ArrayBuffer[Boolean] = mutable.ArrayBuffer.empty

  /** Everything the machine wrote to the operator log stream, in order. */
  val logLines: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty

  def record(c: String): Unit         = calls += c
  def called(needle: String): Boolean = calls.exists(_.contains(needle))
  def callCount(needle: String): Int  = calls.count(_.contains(needle))

  /** The in-memory equivalent of the bash suite's `checkc NEEDLE "$SB/loop.out"`: a substring
    * search over the whole log stream, which is exactly what the oracle's `grep -q` does.
    */
  def logged(needle: String): Boolean = logLines.exists(_.contains(needle))

  /** Phase sequence with consecutive duplicates collapsed (the bash suite's phase_seq). */
  def phaseSeq: List[String] =
    events.map(_.phase).foldLeft(List.empty[String]) { (acc, p) =>
      if acc.lastOption.contains(p) then acc else acc :+ p
    }

  /** phase:state sequence, collapsing only exact consecutive duplicates (phase_state_seq). */
  def phaseStateSeq: List[String] =
    events.map(e => s"${e.phase}:${e.state}").foldLeft(List.empty[String]) { (acc, p) =>
      if acc.lastOption.contains(p) then acc else acc :+ p
    }

  // ---- script knobs (defaults = the happy APPROVE path on issue 999) ----------------------
  var stopFile: Boolean       = false
  var inProgress: Option[Int] = None
  var ready: Option[Int]      = Some(999)

  /** Every open parked issue, oldest first, `GitHub.parkedIssues`'s own contract (issue #28
    * review finding 6: the probe has to walk the whole list, not just the oldest).
    */
  var parked: List[Int] = Nil

  /** Scripts `GitHub.parkedIssues`'s failed-read case (`None`), distinct from `parked = Nil`,
    * which is a SUCCESSFUL read reporting no parked issues at all (issue #28 review finding 7,
    * round 3).
    */
  var parkedIssuesFail: Boolean = false

  /** The login `GitHub.viewerLogin` answers, the account the harness's own marker comment is
    * posted as. Defaults to the same login the scenarios' `markerEntry` helper writes, so a
    * scenario that scripts a genuine marker without touching this field still recognises it
    * (issue #28 review finding 3, round 3).
    */
  var viewerLoginAnswer: Option[String] = Some("litter-box")
  var titleBody: String = "# US-999 sample\n\nAC1: implement the slice.\nAC2: cover it with a test."
  var labels: List[String]              = List("ready")
  var implScript: WorkerScript          = WorkerScript.Produces(newFilePatch)
  var fixScripts: List[WorkerScript]    = Nil
  var reviewScripts: List[ReviewScript] = List(ReviewScript.Says(approveReview))
  var gateResults: List[GateResult]     = Nil       // empty => Green forever
  var ciWaitResult: GateResult          = GateResult.Green
  var rollupCounts: List[Int]           = List(1)   // last value repeats
  var prUrl: String                     = "https://github.com/test/test/pull/123"

  /** `GitHub.prForBranch`'s answer (issue #36): `None` by default, the ordinary fresh-tick shape
    * (no PR open yet, `Machine`'s PR-open node's own probe finds nothing and proceeds to
    * `createPr`). Script `Some(n)` to simulate a crashed tick resuming after a PR was already
    * opened: the probe then recognises it and `gh pr create` is never called a second time,
    * PROVIDED `existingPrState` below still reads `"OPEN"` (see that field's own doc).
    */
  var existingPrNumber: Option[Int] = None

  /** The state `prForBranch`'s probe reads for `existingPrNumber` (issue #36 review, BLOCKER 1):
    * separate from `prStateAnswer` below, which models the state THIS run's own `Merge` node reads
    * post-merge, because the crash-resume story `existingPrNumber` alone cannot script is a PR that
    * is already CLOSED or MERGED when this tick starts, a fact entirely independent of anything this
    * tick's own `gh.merge` call ever does. Defaults `"OPEN"`, so every scenario predating this field,
    * which scripts `existingPrNumber` alone, keeps adopting it exactly as it always did.
    */
  var existingPrState: String = "OPEN"

  /** `GitHub.prState`'s answer to `Merge`'s own post-merge VERIFICATION read (issue #36): the only
    * call site left that reads it, now that `Merge`'s own probe is `_ => None` unconditionally (issue
    * #36 review, BLOCKER 2/MAJOR 3: `performMerge` always calls `gh.merge`, matching `main`, so
    * there is no pre-merge read of this to model separately). Defaults `"MERGED"`, the ordinary
    * happy-path shape. Script `"OPEN"` (Scenario N) to simulate an unverifiable merge.
    */
  var prStateAnswer: String  = "MERGED"
  var mergeRc: Int           = 0    // bash's $merge_rc; nonzero = merge cmd failed
  var applySucceeds: Boolean = true

  /** How much `Clock.nowMillis()` advances on every single call, starting from `0`; defaults to `0`,
    * an unmoving clock, so every scenario that never touches this knob keeps reading a constant `0`
    * exactly as before this field existed. Deliberately NOT a scripted list of exact answers (issue
    * #33 review finding 6): `Runner.step` (`Kit.scala`) reads the clock once before a node's
    * `probe`/`run` and, for a `Timeout.After` node, once after, so a scripted list's overrun entry
    * would only land where intended if a test also got the exact number of PRIOR reads right, e.g.
    * how many nodes ran before the one under test and whether each is `Unbounded` or `After`; adding
    * or reordering a node anywhere ahead of the one under test would silently shift the list. A
    * per-call step avoids that: set it above whatever bound is under test and ANY two reads at least
    * one call apart see a gap that already exceeds the bound, so a scenario driving a `Timeout.After`
    * node into an overrun does not have to know or care how many reads happened first.
    */
  var clockStepMillis: Long = 0L
  var cleanTree: Boolean                = true
  var fetchSucceeds: Boolean            = true
  var checkoutSucceeds: Boolean         = true
  var labelEditSucceeds: Boolean        = true

  /** Per-call override for `editLabels`, consumed in call order, one entry per call, the last entry
    * repeating once the list is exhausted (same shape as `rollupCounts` below); falls back to
    * `labelEditSucceeds` for every call while this stays `Nil` (issue #50 review finding 3: a single
    * flag cannot express a tick where the pick-time flip succeeds and a LATER `editLabels` call in
    * the same tick fails, which is the scenario the livelock regression test actually needs to pin,
    * a marker posted and a re-park attempted but only the re-park's OWN label flip failing).
    */
  var labelEditResults: List[Boolean] = Nil

  /** Scripts `GitHub.issueComment`'s return value (issue #28 review finding 8: the marker post's
    * success has to be observable, not swallowed like `prComment`'s).
    */
  var issueCommentSucceeds: Boolean = true

  var blockedIssues: List[Int]          = Nil
  var issueBodies: Map[Int, String]     = Map.empty

  /** Named plural, like `issueBodies`, never `issueComments`/`prComments`: a field with the exact
    * method name would shadow it inside the `new GitHub { ... }` block below and not compile
    * cleanly. One entry per comment, oldest first (see `Caps.GitHub.issueComments` for why a
    * `List` rather than a joined string).
    */
  var issueCommentBodies: Map[Int, List[String]] = Map.empty
  var prCommentBodies: Map[Int, List[String]]    = Map.empty // stays unwired; no consumer yet

  /** Issues whose scripted `gh` comments read should fail (`None`), distinct from an issue with no
    * entry in `issueCommentBodies`, which is a SUCCESSFUL read of `Nil` (see `Caps.GitHub.issueComments`
    * for why the fake must be able to tell the two apart).
    */
  var issueCommentsFail: Set[Int] = Set.empty

  var issueStates: Map[Int, String]     = Map.empty // default CLOSED
  var templates: Map[Template, String]  = Map(
    Template.Iterate -> "You are the worker. Fresh context.\n{{ISSUE}}\nProduce a patch.",
    Template.Fix ->
      "You are the fixer.\n{{PROTECTED}}\n{{GATE}}\n{{CONVENTIONS}}\n{{ISSUE}}\n{{FAILURE}}\n{{COMMENTS}}\nProduce a patch.",
    Template.Review -> "Cold review.\n{{ISSUE}}\n{{CONVENTIONS}}\n{{TAMPER}}\n{{DIFF}}\nEmit a VERDICT."
  )
  var conventionsText: String = "# CONTEXT\nConventions: onion layout, use-case error enum."

  // ---- derived state the fakes maintain ---------------------------------------------------
  var appliedPatches: List[String] = Nil
  var staged: Boolean              = false
  var commitMessages: List[String] = Nil
  var pushedBranches: List[String] = Nil
  var prBodies: List[String]       = Nil
  var sleeps: List[Int]            = Nil

  /** `(issue, body)` for every `GitHub.issueComment` call, in order: the parking marker's content
    * is asserted against this, the way `prBodies` pins `createPr`'s.
    */
  var postedIssueComments: List[(Int, String)] = Nil

  private def isNumstatLine(l: String): Boolean =
    NumstatRow.parse(l).exists { row =>
      (row.added
        .matches("\\d+") || row.added == "-") && (row.deleted.matches("\\d+") || row.deleted == "-")
    }

  // ---- capability instances ---------------------------------------------------------------
  val github: GitHub = new GitHub:
    def inProgressIssue(): Option[Int] =
      record("gh issue list --label in-progress"); inProgress
    def oldestReadyIssue(): Option[Int] =
      record("gh issue list --label ready"); ready
    def parkedIssues(): Option[List[Int]] =
      record("gh issue list --label parked")
      if parkedIssuesFail then None else Some(parked)
    def viewerLogin(): Option[String] =
      record("gh api user"); viewerLoginAnswer
    def issueTitleAndBody(issue: Int): String =
      record(s"gh issue view $issue --json title,body"); titleBody
    def issueBody(issue: Int): String =
      record(s"gh issue view $issue --json body"); issueBodies.getOrElse(issue, "")
    def issueComments(issue: Int): Option[List[String]] =
      record(s"gh issue view $issue --json comments")
      if issueCommentsFail(issue) then None else Some(issueCommentBodies.getOrElse(issue, Nil))
    def issueLabels(issue: Int): List[String] =
      record(s"gh issue view $issue --json labels"); labels
    def issueState(issue: Int): String =
      record(s"gh issue view $issue --json state"); issueStates.getOrElse(issue, "CLOSED")
    def editLabels(issue: Int, add: List[String], remove: List[String]): Boolean =
      val a = add.map(l => s" --add-label $l").mkString
      val r = remove.map(l => s" --remove-label $l").mkString
      record(s"gh issue edit $issue$a$r")
      labelEditResults match
        case Nil      => labelEditSucceeds
        case h :: Nil => h
        case h :: t   => labelEditResults = t; h
    def openBlockedIssues(): List[Int] =
      record("gh issue list --label blocked"); blockedIssues
    def createPr(branch: String, title: String, body: String): String =
      record(s"gh pr create --head $branch --title $title"); prBodies = prBodies :+ body; prUrl
    def prForBranch(branch: String): Option[Int] =
      record(s"gh pr view $branch --json number,state")
      existingPrNumber.filter(_ => existingPrState == "OPEN")
    def prComment(pr: Int, body: String): Unit =
      record(s"gh pr comment $pr")
    def issueComment(issue: Int, body: String): Boolean =
      record(s"gh issue comment $issue")
      if issueCommentSucceeds then
        postedIssueComments = postedIssueComments :+ (issue -> body)
        // Fold the post back into the thread `issueComments` reads (issue #44 review, MAJOR, round
        // 2): a fake that records a write but never lets a later read see it cannot prove any WORLD
        // fact property, and `AskHuman`'s whole design rests on one (RFC #26 decision 6: parking is
        // never a stored position, always re-derived from what `gh` reports). `Live.scala`'s own
        // `commentsJqProgram` format, `"@login (association):\n<body>"`: `viewerLoginAnswer`, not a
        // second knob, names the login, because the account that can post a comment at all and the
        // account `gh api user` answers with are the SAME authenticated token in real life; falling
        // back to the same default that login defaults to (`"litter-box"`) when a scenario has
        // scripted `viewerLoginAnswer = None` keeps every entry well-formed even though that field
        // models a SEPARATE `gh` call (`viewerLogin()`) that a scenario is free to fail independently
        // of comment posting. `"OWNER"` is hardcoded, not a second scriptable field: every existing
        // scenario's own hand-written `markerEntry` already assumes the harness posts as `OWNER`
        // (this file's own `markerEntry`, `ScenarioSpec.scala`), so a harness-authored entry folded in
        // here has to carry the same association or it would silently stop matching what those
        // scenarios already assert by hand.
        val login = viewerLoginAnswer.getOrElse("litter-box")
        val entry = s"@$login (OWNER):\n$body"
        issueCommentBodies = issueCommentBodies.updated(issue, issueCommentBodies.getOrElse(issue, Nil) :+ entry)
      issueCommentSucceeds
    def prComments(pr: Int): Option[List[String]] =
      record(s"gh pr view $pr --json comments")
      Some(prCommentBodies.getOrElse(pr, Nil))
    def prState(pr: Int): String =
      record(s"gh pr view $pr --json state")
      prStateAnswer
    def checksRollupCount(pr: Int): Option[Int] =
      record(s"gh pr view $pr --json statusCheckRollup")
      rollupCounts match
        case Nil      => Some(1)
        case h :: Nil => Some(h)
        case h :: t   => rollupCounts = t; Some(h)
    def merge(pr: Int, ciLog: String): Int =
      record(s"gh pr merge $pr --squash --delete-branch >>$ciLog"); mergeRc

  val git: Git = new Git:
    def statusClean(): Boolean                  = { record("git status --porcelain"); cleanTree }
    def fetchOriginMain(): Boolean              = { record("git fetch origin main"); fetchSucceeds }
    def checkoutBranch(branch: String): Boolean = { record(s"git checkout $branch"); checkoutSucceeds }
    def resetHardCleanToOriginMain(): Unit      =
      record("git reset --hard origin/main && git clean -fd"); staged = false
    def applyNumstat(patch: String): String =
      record(s"git apply --numstat $patch")
      files.getOrElse(patch, "").linesIterator.filter(isNumstatLine).mkString("\n")
    def applyIndex(patch: String): Boolean =
      record(s"git apply --index $patch")
      val content = files.getOrElse(patch, "")
      val ok      = applySucceeds && content.linesIterator.nonEmpty &&
        content.linesIterator.forall(isNumstatLine)
      if ok then
        appliedPatches = appliedPatches :+ patch
        staged = true
      ok
    def add(path: String): Unit        = { record(s"git add $path"); staged = true }
    def addAll(): Unit                 = record("git add -A")
    def diffCachedOriginMain(): String =
      record("git diff --cached origin/main")
      appliedPatches.lastOption.map(files.getOrElse(_, "")).getOrElse("")
    def anythingStaged(): Boolean     = { record("git diff --cached --quiet HEAD"); staged }
    def commit(message: String): Unit =
      record("git commit"); commitMessages = commitMessages :+ message
    def push(branch: String): Unit =
      record(s"git push -u origin $branch"); pushedBranches = pushedBranches :+ branch

  val agents: AgentDispatch = new AgentDispatchImpl:
    def worker(
        role: Role,
        promptFile: String,
        patchOut: String,
        logFile: String,
        currentPatch: Option[String]
    ): DispatchOutcome =
      record(
        s"dispatch $role promptFile=$promptFile patchOut=$patchOut logFile=$logFile currentPatch=${currentPatch.getOrElse("")}"
      )
      val script = role match
        case Role.IMPL => implScript
        case Role.FIX  =>
          fixScripts match
            case Nil    => WorkerScript.Empty
            case h :: t => fixScripts = t; h
      script match
        case WorkerScript.Produces(content) =>
          files(patchOut) = content; DispatchOutcome.Done
        case WorkerScript.Empty    => DispatchOutcome.Done
        case WorkerScript.TimedOut => DispatchOutcome.TimedOut
    // Overrides `dispatchReview`, not `review` (issue #35: see `AgentDispatch.review`'s own doc,
    // `src/Caps.scala`, for why). Extends `AgentDispatchImpl`, not `AgentDispatch` directly (round
    // three of issue #35's review: `AgentDispatch` is `sealed`, only extendable from `Caps.scala`
    // itself). `TestWorld` can write this override at all only because it lives inside this
    // library's own package, in.rcard.litterbox; that is a boundary a consumer's own package cannot
    // cross to build their own fake `AgentDispatch`, but it is NOT a boundary the published testkit
    // itself respects, since `TestWorld` is exactly that library-side code and mints by design the
    // moment anyone puts it on a test classpath (RFC #26 decision 14; see `AgentDispatch`'s own doc,
    // `src/Caps.scala`, for the guarantee stated once, in full).
    private[litterbox] def dispatchReview(prompt: String, reviewFile: String): DispatchOutcome =
      record(s"dispatch REVIEW reviewFile=$reviewFile")
      reviewScripts match
        case Nil    => DispatchOutcome.Done
        case h :: t =>
          reviewScripts = t
          h match
            case ReviewScript.Says(out) => files(reviewFile) = out; DispatchOutcome.Done
            case ReviewScript.TimedOut  => DispatchOutcome.TimedOut

  /** The receiving runner is recorded as a trailing `runner=` field rather than in a second book, so
    * the routing property (see `HostGateRunner`, issue #11) is asserted against the same `calls`
    * every other scenario reads. It trails `cmd=`/`log=` because scenarios match gate calls by
    * substring prefix, so a field appended at the end cannot silently unmatch them.
    */
  private def runGate(runner: String, label: String, cmd: String, logFile: String): GateResult =
    record(s"gate $label cmd=$cmd log=$logFile runner=$runner")
    if label == "CI-WAIT" then ciWaitResult
    else
      gateResults match
        case Nil    => GateResult.Green
        case h :: t => gateResults = t; h

  val gates: GateRunner = new GateRunner:
    def run(label: String, cmd: String, timeoutSec: Int, logFile: String): GateResult =
      runGate("sandboxable", label, cmd, logFile)

  val hostGates: HostGateRunner = HostGateRunner(new GateRunner:
    def run(label: String, cmd: String, timeoutSec: Int, logFile: String): GateResult =
      runGate("host", label, cmd, logFile)
  )

  val status: StatusLog = new StatusLog:
    def append(event: StatusEvent): Unit = events += event
    def declare(stages: StageSet): Unit =
      declaredBeforeAnyEvent += events.isEmpty
      declaredStages += stages

  val notifier: Notify = new Notify:
    def notify(msg: String): Unit = { record(s"notify $msg"); notifications += msg }

  val fs: HarnessFs = new HarnessFs:
    def stopRequested(): Boolean                   = stopFile
    def readTemplate(template: Template): String   = templates(template)
    def conventions(): String                      = conventionsText
    def write(path: String, content: String): Unit = files(path) = content
    def read(path: String): String                 = files.getOrElse(path, "")
    def sizeBytes(path: String): Long = files.get(path).map(_.length.toLong).getOrElse(0L)

  private var clockCalls: Long = 0

  val clock: Clock = new Clock:
    def sleepSeconds(s: Int): Unit = sleeps = sleeps :+ s
    // Advances by `clockStepMillis` on every call, starting at `0` (issue #33 review finding 6):
    // `Implement` is now a `Timeout.After` node, so a scenario testing its overrun needs a moving
    // clock; `RunnerSpec` additionally scripts its own `Clock` for the generic runner-mechanics
    // tests. With the default `0L` step this stays the same unmoving `0` every scenario before
    // `clockStepMillis` existed relied on.
    def nowMillis(): Long =
      val at = clockCalls * clockStepMillis
      clockCalls += 1
      at

  val logger: Log = new Log:
    def log(msg: String): Unit = logLines += msg

  // ---- driving the machine ----------------------------------------------------------------

  /** Runs one iteration of the machine against this world's scripted capabilities, walking whichever
    * `LoopGraph` the caller names.
    *
    * The `using` clause lives here rather than in each spec so that adding a capability to
    * `Machine` is a one-line edit instead of a lockstep edit across every spec that drives it.
    * Per-scenario differences stay at the call site: `w.runGraph(g)` for the defaults,
    * `w.runGraph(g, Config(dryRun = true))` to vary config, `w.runGraph(g, iteration = 2)` to vary
    * the iteration number the machine reports.
    *
    * This is the testkit's entry point for a CONSUMER's own graph (issue #42, RFC #26 decision 14):
    * a node author builds a `LoopGraph` through `LitterBox.graph` and drives it here, with no
    * Docker, no network and no credentials, exactly the way this repo drives its own shipped graph
    * through [[runLoop]] below. It deliberately takes an already-built `LoopGraph` and never
    * constructs one, and it names no `Runner.Ledger`: `Machine.runOnce` builds the ledger from the
    * graph's own declared `dispatchBudget`, so the testkit does not become the `Ledger` escape hatch
    * RFC #26 decision 9 exists to prevent. An earlier draft of this method grew `runNode`/
    * `runWorkflow` helpers that would have had to build one; issue #43's public `LitterBox.graph`
    * removed the need for them entirely.
    */
  def runGraph(graph: LoopGraph, cfg: Config = Config(), iteration: Int = 1): LoopExit =
    Machine.runOnce(iteration, graph)(using
      cfg,
      github,
      git,
      agents,
      gates,
      hostGates,
      status,
      notifier,
      fs,
      clock,
      logger
    )

  /** Runs one iteration of the SHIPPED graph against this world's scripted capabilities: the
    * `PICK -> IMPLEMENT -> GATE -> REPAIR -> REVIEW -> PR -> CI -> MERGE` pipeline `lb` itself walks.
    *
    * Every behavioural spec in this repo drives the machine through here. Kept as its own method,
    * delegating to [[runGraph]] rather than being replaced by it, because `LitterBox.shipped` is the
    * one graph the overwhelming majority of call sites want and spelling it out at each of them
    * would be several hundred lines of noise saying the same thing.
    */
  def runLoop(cfg: Config = Config(), iteration: Int = 1): LoopExit =
    runGraph(LitterBox.shipped, cfg, iteration)

/** A `Clock` a test can script by hand. `nowMillis` answers each element of `answers` in turn, and
  * repeats the last one once exhausted, so a test only has to name as many readings as it cares
  * about (one per `caps.clock.nowMillis()` call `Runner.step` makes around a node's `probe`/`run`).
  *
  * Lifted here, next to `TestWorld` (issue #38 review nit), rather than left a third, separately
  * reimplemented copy: `RunnerSpec`, `ShippedWorkflowSpec` and `GraphValidationSpec` each need either
  * a scriptable clock, or a `Caps` built from `TestWorld` plus one, and `TestWorld.clock`'s own
  * auto-incrementing answer (its own doc above) is the wrong shape for a test that has to script two
  * distinct readings around a `Timeout.After` node.
  */
final class FakeClock(answers: List[Long]) extends Clock:
  private var remaining = answers
  def sleepSeconds(s: Int): Unit = ()
  def nowMillis(): Long = remaining match
    case head :: tail =>
      remaining = tail
      head
    case Nil => answers.lastOption.getOrElse(0L)

/** Builds the `Caps` bundle a spec driving `Runner`/`Machine` directly (rather than through
  * `TestWorld.runLoop`) needs, from a `TestWorld`'s own scripted capabilities plus whichever `Clock`
  * the test wants. The one-argument overload uses `world.clock` itself (its own auto-incrementing
  * answer), so a caller with no reason to script the clock can still write `buildCaps(world)` alone,
  * the same call `ShippedWorkflowSpec` makes; a caller scripting a `FakeClock` writes
  * `buildCaps(world, clock)` instead, the same call `RunnerSpec`/`GraphValidationSpec` make. Two
  * overloads, not one method with a default parameter referencing `world`, because a top level `def`
  * default parameter cannot see an earlier parameter of the same list the way a method inside a
  * class can. Lifted next to `TestWorld`/`FakeClock` for the same reason as that class's own doc.
  */
def buildCaps(world: TestWorld): Caps = buildCaps(world, world.clock)

def buildCaps(world: TestWorld, clock: Clock): Caps =
  Caps(
    cfg = Config(),
    gh = world.github,
    git = world.git,
    agents = world.agents,
    gates = world.gates,
    hostGates = world.hostGates,
    status = world.status,
    notifier = world.notifier,
    fs = world.fs,
    clock = clock,
    logger = world.logger
  )

/** Every `Runner.step`/`Runner.run` call needs a `Faulting` in scope, exactly the way every real call
  * site gets one: from the `boundary[LoopExit]` `Machine.runOnce` establishes for a real run. Reusing
  * that same shape here, rather than a lighter-weight substitute, means a fault under test aborts to
  * this boundary exactly the way it would in the real loop. `body` runs to completion and its result
  * is captured in `Right`; a fault instead lands here as `Left(exit)`, `exit` being whatever
  * `LoopExit` the fault broke to.
  *
  * Lifted next to `TestWorld`/`FakeClock`/`buildCaps` for the same reason (issue #38 review nit):
  * `RunnerSpec`, `ShippedWorkflowSpec` and `GraphValidationSpec` each reimplemented this identically,
  * a third copy of the same handful of lines, before this lift.
  */
def withFaulting[T](body: Faulting ?=> T): Either[LoopExit, T] =
  var out: Option[T] = None
  val exit = boundary[LoopExit]:
    out = Some(body)
    LoopExit.Idle // never read: `out` is `Some` whenever this line is reached
  out match
    case Some(t) => Right(t)
    case None    => Left(exit)
