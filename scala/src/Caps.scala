package harness

/** Capability traits (yaes-style: passed as `using` context parameters).
  *
  * Slice 1 provides only in-memory scripted handlers (test/Recorder.scala); the live
  * subprocess/fs/gh handlers are slice 2 (Live.scala). Machine.iterate is pure decision logic over
  * these — it touches the world through nothing else.
  *
  * Deviation from the design doc's capability table: `pickIssue` is split into `inProgressIssue` /
  * `oldestReadyIssue` so the resume-in-progress-first decision stays in Machine (under test)
  * instead of hiding inside a handler.
  */

/** `gh` operations. */
trait GitHub:
  /** Open issue currently labelled in-progress (a crashed run resumes it), if any. */
  def inProgressIssue(): Option[Int]

  /** Oldest open issue labelled ready, if any. */
  def oldestReadyIssue(): Option[Int]

  /** `"# " + title + "\n\n" + body` — the shape loop.sh writes to the body file. */
  def issueTitleAndBody(issue: Int): String

  /** Raw body only (flip_blocked scans it for `Blocked-by: #N`). */
  def issueBody(issue: Int): String
  def issueLabels(issue: Int): List[String]
  def issueState(issue: Int): String

  /** Label edit; a false return is logged, never fatal (matches `|| log WARNING`). */
  def editLabels(issue: Int, add: List[String], remove: List[String]): Boolean

  /** Open issues labelled blocked (flip_blocked candidates). */
  def openBlockedIssues(): List[Int]

  /** Returns the PR URL (empty/garbage URL is the caller's infra fault to raise). */
  def createPr(branch: String, title: String, body: String): String
  def prComment(pr: Int, body: String): Unit
  def prState(pr: Int): String

  /** `statusCheckRollup | length`; None when the query itself failed. */
  def checksRollupCount(pr: Int): Option[Int]

  /** MERGE_CMD seam (`gh pr merge --squash --delete-branch`). False = merge command failed.
    *
    * The merge child's combined stdout+stderr is APPENDED to `ciLog` — bash's
    * `$merge_cmd >>"$ci_log" 2>&1` (loop.sh:473), where `$ci_log` is the CI-wait log the caller
    * already computed. Append, never truncate: the CI watch's own output is already in there.
    */
  def merge(pr: Int, ciLog: String): Boolean

/** `git` operations, all against the serial one-US-at-a-time working tree. */
trait Git:
  def statusClean(): Boolean
  def fetchOriginMain(): Boolean

  /** Checkout `branch` if it exists, else `-b branch origin/main`. False = cannot branch. */
  def checkoutBranch(branch: String): Boolean

  /** `git reset --hard origin/main && git clean -fd` — the pristine-base reset of the seam. */
  def resetHardCleanToOriginMain(): Unit

  /** `git apply --numstat PATCH` text; empty when the patch is unparseable (fail-open). */
  def applyNumstat(patch: String): String

  /** `git apply --index PATCH`. False = apply refused (infra fault upstream). */
  def applyIndex(patch: String): Boolean
  def add(path: String): Unit
  def addAll(): Unit

  /** `git diff --cached origin/main` — the reviewer's diff. */
  def diffCachedOriginMain(): String

  /** Whether anything is staged vs HEAD (`! git diff --cached --quiet HEAD`). */
  def anythingStaged(): Boolean
  def commit(message: String): Unit
  def push(branch: String): Unit

/** Worker/fixer/reviewer dispatch outcome: the only bit the loop reads is rc==124. */
enum DispatchOutcome:
  case Done
  case TimedOut

/** The agent seam (IMPL_CMD / FIX_CMD / REVIEW_CMD / sandbox run-agent.sh, run-reviewer.sh). */
trait AgentDispatch:
  /** Runs the worker; the contract is "a patch is produced at `patchOut`" (possibly empty). The
    * child's combined stdout+stderr is written to `logFile` (bash's `$logf`). `currentPatch` seeds
    * the container tree with the prior cumulative work on a FIX.
    */
  def worker(
      role: Role,
      promptFile: String,
      patchOut: String,
      logFile: String,
      currentPatch: Option[String]
  ): DispatchOutcome

  /** Runs the cold reviewer; its stdout is written to `reviewFile`. */
  def review(prompt: String, reviewFile: String): DispatchOutcome

/** run_gate: a tier command under a timeout, log captured. Reused by the CI wait. */
trait GateRunner:
  def run(label: String, cmd: String, timeoutSec: Int, logFile: String): GateResult

/** status.jsonl appender. Pure observability: a wrong event is a wrong banner, never a wrong merge.
  * Sanitization/normalization happens in Machine before the event reaches here.
  */
trait StatusLog:
  def append(event: StatusEvent): Unit

/** Notify seam. Fires on exactly: needs-human terminals, rc-50 exits, successful auto-merges. A
  * dead channel must never change loop behavior (live handler swallows).
  */
trait Notify:
  def notify(msg: String): Unit

/** Filesystem the harness owns: prompts, logs, markers, STOP.md. */
trait HarnessFs:
  /** STOP.md present — manual kill-switch. */
  def stopRequested(): Boolean
  def readTemplate(template: Template): String

  /** CONTEXT.md contents (spliced into the review prompt). */
  def conventions(): String
  def write(path: String, content: String): Unit
  def read(path: String): String

  /** Size in bytes; 0 for a missing file (matches `[[ -s ]]` / `wc -c` use). */
  def sizeBytes(path: String): Long

/** Wall-clock waits (CI-appear poll). In-memory tests script it; slice 2 sleeps for real. */
trait Clock:
  def sleepSeconds(s: Int): Unit
