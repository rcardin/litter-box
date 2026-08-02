package in.rcard.litterbox

/** Capability traits (passed as `using` context parameters).
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

  /** Every open issue labelled parked, oldest first (issue #28). Queried back every tick rather
    * than remembered anywhere: parking is the terminal state of ONE tick, never a stored
    * position, so `Machine.pickAndSetup` re-derives "is there still a parked issue, and does it
    * have a reply" from GitHub alone (RFC #26 decision 6).
    *
    * A `List`, not the single oldest issue: `Machine.pickAndSetup` has to walk every parked issue
    * looking for the first one with an accepted human reply, so an older parked issue with no
    * reply yet can never starve a newer one a human already steered (review finding on issue #28's
    * first pass, where only the oldest was ever probed).
    *
    * `None` on a failed `gh` read (auth expiry, rate limit), same shape and reasoning as
    * `issueComments` below: folding that into `Some(Nil)` would report the queue empty and let the
    * loop settle into `Idle` (rc 11, a healthy looking exit) while it actually has no idea whether
    * a parked issue is out there waiting on a human (issue #28 review finding 7, round 3).
    */
  def parkedIssues(): Option[List[Int]]

  /** The login `gh` is authenticated as, i.e. the account whose comments the harness itself posts
    * (`gh api user --jq .login`). This is how `Machine`'s resume probe tells its own park marker
    * comment apart from a forged one: association (`OWNER`/`MEMBER`/`COLLABORATOR`) is not a safe
    * test for the marker, because a bot or GitHub App token's `authorAssociation` reads `NONE` even
    * though it IS the harness's own account, which would make the harness unable to recognise its
    * own marker under such a token (issue #28 review finding 3, round 3).
    *
    * `None` on a failed read: the loop then cannot tell its own marker from a forgery, so it must
    * not resume anything off that answer.
    */
  def viewerLogin(): Option[String]

  /** `"# " + title + "\n\n" + body` — the shape loop.sh writes to the body file. */
  def issueTitleAndBody(issue: Int): String

  /** Raw body only (flip_blocked scans it for `Blocked-by: #N`). */
  def issueBody(issue: Int): String

  /** Comments left on the issue, oldest first, one entry per comment, added for #27 (a third party
    * steering a run mid-flight was invisible until something read this back).
    *
    * A `List`, not one joined string: any separator this trait could pick is text a commenter can
    * type into their own comment body, so joining would let a drive by comment forge a fake second
    * entry (for example one impersonating the maintainer) inside its own text. The boundary between
    * entries is `List` structure this trait draws from `gh`'s own comment array, never a substring
    * scanned out of a blob afterward, so no comment body can merge into, or split off from, its
    * neighbour.
    *
    * Each entry is also prefixed with its author's `@login (association):`. That names who `gh`
    * recorded as the author of THIS entry; it is provenance, not a guarantee, since nothing here
    * stops an entry's own body from containing a line that looks like another author prefix.
    *
    * `None` when the `gh` read itself failed (auth expiry, rate limit, a broken jq program);
    * `Some(Nil)` when it succeeded and there simply were no comments (same shape as
    * `checksRollupCount` below). Collapsing the two would tell the fixer something untrue.
    */
  def issueComments(issue: Int): Option[List[String]]
  def issueLabels(issue: Int): List[String]
  def issueState(issue: Int): String

  /** Label edit; a false return is logged, never fatal (matches `|| log WARNING`). */
  def editLabels(issue: Int, add: List[String], remove: List[String]): Boolean

  /** Open issues labelled blocked (flip_blocked candidates). */
  def openBlockedIssues(): List[Int]

  /** Returns the PR URL (empty/garbage URL is the caller's infra fault to raise). */
  def createPr(branch: String, title: String, body: String): String
  def prComment(pr: Int, body: String): Unit

  /** Posts a comment on an issue. Mirrors `prComment`, except the return value is load-bearing:
    * the marker comment IS the cross-tick boundary `Machine`'s resume probe reads back (see
    * `Machine.ParkMarker`), so a caller that could not tell a failed post from a successful one
    * would risk labelling an issue `parked` with no marker on it at all, after which every comment
    * ever left on the issue reads as "the reply" on the next tick.
    *
    * `false` = the underlying post failed (rc != 0); `Machine.terminal` treats that as an infra
    * fault rather than completing the park.
    */
  def issueComment(issue: Int, body: String): Boolean

  /** Comments on the PR opened for this issue: same shape and reasoning as `issueComments` above.
    * Nothing splices this into a prompt (only the issue's own comments feed the FIX prompt); this
    * read exists in full because a third party is just as likely to steer from the PR thread, but
    * it stays unwired on purpose until something actually needs it.
    */
  def prComments(pr: Int): Option[List[String]]
  def prState(pr: Int): String

  /** `statusCheckRollup | length`; None when the query itself failed. */
  def checksRollupCount(pr: Int): Option[Int]

  /** MERGE_CMD seam (`gh pr merge --squash --delete-branch`). Returns the merge child's exit code
    * (bash's `$merge_rc`); 0 = merged. The rc itself is load-bearing, not just its zero-ness: it is
    * the only diagnostic in the failure log line separating "PR not mergeable" from "gh auth
    * expired", and loop.sh:475 prints it verbatim.
    *
    * The merge child's combined stdout+stderr is APPENDED to `ciLog` — bash's
    * `$merge_cmd >>"$ci_log" 2>&1` (loop.sh:473), where `$ci_log` is the CI-wait log the caller
    * already computed. Append, never truncate: the CI watch's own output is already in there.
    */
  def merge(pr: Int, ciLog: String): Int

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

/** The runner for tiers that borrow `run_gate`'s mechanics (one command, one timeout, one captured
  * log) but must execute on the HOST rather than inside the gate container. The CI wait is the only
  * one: `gh pr checks --watch` needs the `gh` binary, the operator's credentials and github.com
  * egress, and the gate image is built to have none of the three.
  *
  * A distinct type rather than a second `GateRunner` given, because a single given is exactly how
  * the bug happened (issue #11): `gate.sandboxed` is on by default, so the one runner Main built
  * was the sandboxed one, and the CI watch it also served died with `gh: command not found`, a
  * non-zero rc indistinguishable from a genuinely red check — so a green PR was annotated `CI RED
  * -> needs-human` for every consumer on the default config. Two types mean the wiring has to say
  * which runner each tier gets, and the compiler checks that it did.
  *
  * This scaladoc is the one home for that argument; the call sites (`Main.gateRunners`, the CI-WAIT
  * step in `Machine`, `TestWorld.runGate`, `LiveProcSpec`, `ScenarioSpec`) point here instead of
  * restating it.
  */
final case class HostGateRunner(runner: GateRunner):
  def run(label: String, cmd: String, timeoutSec: Int, logFile: String): GateResult =
    runner.run(label, cmd, timeoutSec, logFile)

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

/** Filesystem the harness owns: prompts, logs, markers, the configured stop file. */
trait HarnessFs:
  /** The `stop-file` is present — manual kill-switch. */
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

  /** Wall-clock now, in milliseconds, added for issue #32: `Runner.step` reads this once before a
    * `Node`'s `probe`/`run` and once after, and subtracts. Millisecond resolution rather than
    * `Config`'s usual whole seconds: the two readings are subtracted first, and only that difference
    * is ever compared against a bound (`seconds.toLong * 1000L`), so pre-truncating either reading to
    * the second would risk stacking up to a whole extra second of drift into the comparison for no
    * reason.
    */
  def nowMillis(): Long

/** The operator-facing log stream (bash's `log()` helper, loop.sh:141 — `[loop HH:MM:SS] msg` on
  * stderr).
  *
  * A capability rather than a direct `LiveLog` call inside Machine for the same reason every other
  * side effect is one: Machine stays a pure decision function over its `using` clause, and the
  * scenario tests can assert on what was logged. That matters more here than it looks — this stream
  * is `watch.sh`'s input contract, and `LogParitySpec` freezes it whole against the golden files
  * under `test/golden`. Its load-bearing phrases (`half-finished worker must not reach the gates`,
  * `protected-path`, `oversized-patch`, ...) are asserted behaviour, not decoration: the wording is
  * copied from the original loop.sh verbatim, and changing one is a contract break, not a style
  * change. Deliberate rewordings go through `UPDATE_GOLDEN=1` and a read of the resulting diff.
  */
trait Log:
  def log(msg: String): Unit

/** The capability bundle a `Node` (`src/Kit.scala`) receives as its one context parameter, added for
  * issue #32. Every function above it takes each capability as its own separate `using` parameter,
  * and that stays true for all of them: this bundle is additive, not a replacement.
  *
  * Why bundle at all: the RFC's node signature is `I => Caps ?=> O`, one context parameter, so a
  * node author writes exactly one `using` in their own code no matter how many capabilities the
  * loop grows. Why not migrate every existing function to take `Caps` instead: every call site
  * already in this file (`Machine.pickAndSetup`, `implementAndRepair`, `terminal`, ...) would have
  * to change in lockstep, for a task scoped to converting one phase (Pick) into one node. The
  * `given` accessors below are what make BOTH worlds compile unchanged: a function still declared
  * with `(using cfg: Config, gh: GitHub, ...)` keeps resolving those individually wherever a plain
  * `Caps` is the only thing actually in scope (a `Node`'s own `probe`/`run` body), because each
  * accessor derives its one capability from that `Caps` value.
  *
  * Given-ambiguity trap this design invites, and how it is avoided here: importing `Caps.given` into
  * a scope where BOTH a real `given Caps` AND the individual capabilities (as their own named `using`
  * parameters) are simultaneously visible would make every one of these accessors genuinely
  * ambiguous with the local parameter of the same type. Nothing in this codebase does that: the only
  * place a real `given Caps` exists is inside a `Node`'s own context-function body (`Machine.Pick`),
  * which never also carries the individual capabilities as named parameters, and the only place the
  * individual capabilities are named parameters (`Machine.iterate` and everything it calls) never
  * also introduces a `given Caps`: `iterate` builds a plain, non-given `Caps` value instead,
  * precisely so it never becomes a second candidate for its own already-named parameters.
  */
final case class Caps(
    cfg: Config,
    gh: GitHub,
    git: Git,
    agents: AgentDispatch,
    gates: GateRunner,
    hostGates: HostGateRunner,
    status: StatusLog,
    // Named `notifier`, not the RFC sketch's `notify`: a case class field named `notify` generates a
    // zero-arg `def notify: Notify` accessor, which collides with `java.lang.Object.notify(): Unit`
    // (a FINAL method every Scala class already inherits), and fails to compile no matter what it
    // returns. `Recorder.scala`'s own `TestWorld` already sidesteps the same clash the same way
    // (its capability val is `notifier`, not `notify`), so this keeps the two files' vocabulary for
    // the same capability in agreement.
    notifier: Notify,
    fs: HarnessFs,
    clock: Clock,
    logger: Log
)

object Caps:
  given (using c: Caps): Config         = c.cfg
  given (using c: Caps): GitHub         = c.gh
  given (using c: Caps): Git            = c.git
  given (using c: Caps): AgentDispatch  = c.agents
  given (using c: Caps): GateRunner     = c.gates
  given (using c: Caps): HostGateRunner = c.hostGates
  given (using c: Caps): StatusLog      = c.status
  given (using c: Caps): Notify         = c.notifier
  given (using c: Caps): HarnessFs      = c.fs
  given (using c: Caps): Clock          = c.clock
  given (using c: Caps): Log            = c.logger
