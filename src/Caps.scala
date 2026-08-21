package in.rcard.litterbox

/** Capability traits (passed as `using` context parameters).
  *
  * Slice 1 provides only in-memory scripted handlers (test/Recorder.scala); the live
  * subprocess/fs/gh handlers are slice 2 (Live.scala). Machine.runOnce, and the shipped Workflow
  * (Machine.shippedWorkflow) it walks through Runner.run, are pure decision logic over these — they
  * touch the world through nothing else.
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

  /** The number of the PR already OPEN for `branch`, if any (`gh pr view <branch>`, which resolves
    * by head branch as well as by number). Added for issue #36: `Machine`'s PR-open node reads this
    * BEFORE calling `createPr`, so a crashed tick that already opened a PR is recognised by asking
    * GitHub, never by a stored position (RFC #26 decision 6): `gh pr create` itself refuses a
    * second PR for a branch that already has one open, so re-attempting it on every resumed tick
    * would either fail outright or, worse, depend on that refusal alone to avoid a duplicate.
    *
    * `None` on a failed read, the same fail-open answer `checksRollupCount` gives on its own failed
    * read: this method exists only to SKIP an unnecessary `createPr`, and a caller that cannot tell
    * "no PR yet" from "the read itself failed" must still be free to attempt one, the safe default
    * either way.
    *
    * Also `None` when a PR exists for `branch` but its state is not `OPEN` (issue #36 review,
    * BLOCKER 1): `gh pr view <branch>` resolves a CLOSED or MERGED PR for that head branch just as
    * readily as an OPEN one, and the justification above holds only for an OPEN PR, since `gh pr
    * create` refuses a SECOND OPEN PR for a branch, it does not refuse one after the first was merged or
    * closed. A caller that adopted a MERGED PR's number here would treat this iteration's own commits
    * as already shipped without ever opening a PR for them, or merging one.
    */
  def prForBranch(branch: String): Option[Int]
  def prComment(pr: Int, body: String): Unit

  /** Posts a comment on an issue. Mirrors `prComment`, except the return value is load-bearing:
    * the marker comment IS the cross-tick boundary `Machine`'s resume probe reads back (see
    * `Machine.ParkMarker`), so a caller that could not tell a failed post from a successful one
    * would risk labelling an issue `parked` with no marker on it at all, after which every comment
    * ever left on the issue reads as "the reply" on the next tick.
    *
    * `false` = the underlying post failed (rc != 0); `Machine.shippedWorkflow`'s own `finish` (its
    * `Route.Parked` branch) treats that as an infra fault rather than completing the park.
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

/** Worker/fixer/reviewer dispatch outcome: the only bit the loop reads is rc==124.
  *
  * Both cases describe a subprocess that actually RAN, and issue #69 deliberately did not add a third
  * meaning "the runner refused this dispatch, the budget is empty". A refusal is not an outcome a node
  * gets to read: it raises an infra fault inside `Runner.charging` (`Kit.scala`, that method's own doc
  * has both reasons, the decisive one being that `review` below is the only minter of `Judged`, so a
  * refusal that RETURNED anything on that path would mint a trust token with no cold session behind
  * it).
  */
enum DispatchOutcome:
  case Done
  case TimedOut

/** The agent seam (IMPL_CMD / FIX_CMD / REVIEW_CMD / sandbox run-agent.sh, run-reviewer.sh).
  *
  * `sealed` (issue #35 review, round three): `private[litterbox]` on the abstract member below
  * restricts who can CALL `dispatchReview`, not who can IMPLEMENT it, because Scala always lets a
  * subclass widen an inherited member's access. A foreign package could define
  * `class Sneaky extends AgentDispatch` and declare its own `dispatchReview` `public`, which
  * compiles, so `private[litterbox]` alone never closed the hole RFC #26 decision 7 asks for.
  * `sealed` is the qualifier Scala actually enforces against a foreign IMPLEMENTOR: only code in
  * THIS file may extend `AgentDispatch` directly, and a foreign package cannot even write the
  * `extends` clause.
  *
  * That is why no implementor below extends this trait directly. `AgentDispatchImpl` is the one
  * child living in this file, and every real implementation (`LiveAgentDispatch` in
  * `src/Live.scala`, `Runner.charging` in `src/Kit.scala`, `TestWorld`'s scripted fake in
  * `test/Recorder.scala`) extends `AgentDispatchImpl` instead, each from its own file. Scala permits
  * that because `AgentDispatchImpl` is not `sealed`, only `private[litterbox]`, a modifier that
  * still lets any file inside this package extend it, while a foreign package cannot even name it to
  * try.
  *
  * The guarantee this buys, stated once here rather than re-derived at every call site that used to
  * repeat it (`dispatchReview` and `review` below, `AgentDispatch.Judged`, `AgentDispatchImpl`,
  * `TestWorld` in `test/Recorder.scala`, `RunnerSpec`'s Judged section), has four parts.
  *
  * First, no foreign package can implement `AgentDispatch` (extend it directly, extend
  * `AgentDispatchImpl`, or widen `dispatchReview`'s access), so no foreign package can construct one,
  * so `review` (`final`, the only way to mint a `Judged`) is reachable only through a dispatch this
  * library itself wired. RFC #26 decision 7's trust boundary sits at the capability, not at a shipped
  * step. Read this together with the fourth part below, never on its own: "a dispatch this library
  * wired" now includes the scripted one the PUBLISHED TESTKIT wires, which a consumer can hold, and
  * that is exactly what the fourth part is about.
  *
  * Second, this is a Scala COMPILE TIME guarantee, nothing more. `Judged.mint` is `public` in the
  * compiled bytecode (`private[AgentDispatch]` is a Scala access control construct with no JVM
  * equivalent), so a Java or Kotlin caller of the same jar can invoke it directly. Nothing here
  * defends against a non Scala caller.
  *
  * Third, PACKAGE INJECTION defeats it: a consumer file whose first line is `package
  * in.rcard.litterbox` compiles as library code and can do everything library code can, including
  * satisfy `AgentDispatchImpl`. Accepted as a residual, not chased further, because it requires a
  * consumer to deliberately misdeclare their own file's package, a hostile act no accidental
  * agent authored node performs.
  *
  * Fourth, `TestWorld` MINTS BY DESIGN, and since issue #42 it does so from a PUBLISHED artifact,
  * `in.rcard::litter-box-testkit` (`LitterBox.TestkitCoordinate`), so this is now a residual a
  * consumer can reach rather than one confined to this repo's own suite. `TestWorld.agents` is an
  * `AgentDispatch` (it satisfies `AgentDispatchImpl` from inside this package, which is the whole
  * point of decision 14), `review` below is `final` and is the mint site, so anyone holding a
  * `TestWorld` can call `world.agents.review(...)` and receive a genuine `AgentDispatch.Judged` with
  * no dispatch behind it. `.map(_ => Verdict.Approve)` on that value then clears
  * `Guard.RequiresReview` at BOTH gates, the `LitterBox.graph` macro and `Runner.step`'s runtime
  * class check, because the value really is a `Judged`, not an imitation of one. This was reproduced
  * end to end in a faithful miniature (sealed capability, `private[pkg]` impl trait, `final` mint
  * method, `private[Ctor]` constructor) from a foreign package while scoping #42, not merely reasoned
  * about.
  *
  * There is no type level defence available: the mint is inherent to decision 14, since a testkit
  * that could not produce a `Judged` could not drive a review node at all, which is the one thing a
  * node author most needs to test. The controls are therefore ARTIFACT SCOPING plus saying this
  * plainly. The fakes ship as their OWN artifact and are deliberately not in the library jar, so
  * `//> using test.dep in.rcard::litter-box-testkit:<version>` puts them on the test classpath only,
  * where running against a fake world is the whole intent; a consumer who writes `dep` instead of
  * `test.dep` has put the mint on their production compile classpath and hands themselves a forged
  * review.
  *
  * That mistake is now DETECTED, at the one point a run can look at its own classpath:
  * `Main.refuseTestkitOnClasspath`, wired into the loop branch of `Main.dispatch` (issue #71), asks
  * whether `in.rcard.litterbox.TestWorld` is loadable in the JVM about to start the loop, and if it
  * is, startup ends at rc 1 with a message naming the `test.dep` rule, before any node has run.
  * What that check is NOT, said here so the paragraph above is not read as closed: it detects the
  * honest misconfiguration and defends against nothing. It asks about one class name, so a shaded,
  * relocated or renamed testkit passes it unnoticed. It sits on the supported startup door, so a
  * consumer whose production code holds a `TestWorld` and calls `world.agents.review(...)` or
  * `TestWorld.runGraph` itself never reaches it. And it leaves the other three parts of this
  * guarantee exactly where they were. Artifact scoping is still the control; this is the tripwire
  * on the way a consumer most plausibly gets it wrong. README's Testkit section and
  * `LitterBox.TestkitCoordinate`'s own scaladoc repeat the `test.dep` rule at the two other points a
  * reader meets the artifact.
  */
sealed trait AgentDispatch:
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

  /** The real reviewer subprocess call (issue #35): what `LiveAgentDispatch` and `TestWorld`'s
    * scripted fake each override to run their own version of "dispatch the cold reviewer, write its
    * stdout to `reviewFile`". `review` below is the ONLY caller, so an implementation can vary how
    * the dispatch actually happens without ever being able to vary what wraps its answer, since
    * nothing outside this trait's own body can call `dispatchReview` directly and hand the result to
    * anything but `review`.
    *
    * `private[litterbox]` here is what every implementor overrides at, unchanged, because every
    * implementor lives inside this package (that is what `AgentDispatchImpl` is for). This modifier
    * alone does not keep a foreign package out, a subclass can always widen it; `sealed` on
    * `AgentDispatch` above is what actually does that job. See this trait's own doc for the full
    * guarantee and its exact residual limits.
    */
  private[litterbox] def dispatchReview(prompt: String, reviewFile: String): DispatchOutcome

  /** Runs the reviewer; its stdout is written to `reviewFile`. Mints `AgentDispatch.Judged` around
    * whatever `dispatchReview` answers (issue #35, RFC #26 decisions 3 and 7: "the trust token is
    * minted by the capability, not by a shipped step"). `final`, so no subclass, no decorator
    * (`Runner.charging`, `src/Kit.scala`) and no consumer writing their own review node against this
    * trait can override `review` itself to skip the real dispatch and hand back a `Judged` built
    * from a value it invented; the only thing an implementation is free to vary is `dispatchReview`,
    * the actual subprocess call, and whatever it returns is always wrapped by this SAME mint call,
    * never re-implemented at a second site a future override could diverge from.
    *
    * What the token binds to is this `AgentDispatch` present in `Caps`, not to any particular
    * quality of the dispatch itself. `dispatchReview` is abstract, so the mint site cannot know, and
    * does not claim, that the reviewer session started cold: a scripted fake or a hostile
    * implementation can return a canned `DispatchOutcome` and still receive a genuine `Judged`. Cold,
    * zero-mount, no-mutating-tools isolation is a property `LiveAgentDispatch` chooses to provide
    * (`src/Live.scala`), not something this signature can enforce. Whoever wires the concrete
    * `AgentDispatch` a `Caps` carries is bounded by construction, not merely trusted, for the reasons
    * this trait's own doc states in full.
    */
  final def review(prompt: String, reviewFile: String): AgentDispatch.Judged[DispatchOutcome] =
    AgentDispatch.Judged.mint(dispatchReview(prompt, reviewFile))

/** The one direct child of the `sealed` `AgentDispatch` above, and the sole extension point every
  * real implementor uses instead of extending `AgentDispatch` itself (see that trait's own doc for
  * why the split exists and exactly what it buys). Not `sealed` itself, only `private[litterbox]`:
  * every real implementor lives in a DIFFERENT file (`src/Live.scala`, `src/Kit.scala`,
  * `test/Recorder.scala`), so sealing this type too would just relocate the "same file" wall to a
  * name none of them could cross. `private[litterbox]` is the modifier that keeps them out instead:
  * a foreign package cannot even name `AgentDispatchImpl` to write an `extends` clause against it.
  */
private[litterbox] trait AgentDispatchImpl extends AgentDispatch

object AgentDispatch:
  /** The trust token a cold review verdict travels in, from the moment a real dispatch answers to
    * the moment `Machine`'s review node reads it (issue #35). Unforgeable by construction, not by
    * convention: the only constructor, `Judged.mint`, is `private[AgentDispatch]`, reachable from
    * `review`'s own body above and from nowhere else, not a subclass of `AgentDispatch`, not a
    * consumer's own review node, not even other code elsewhere in this same file, past this point.
    *
    * `sealed` alone would only stop a foreign FILE from writing a new subclass; it would not stop
    * code inside THIS file from writing `new Judged(fakeValue) {}` directly, skipping `mint`
    * entirely. Narrowing the constructor itself to `private[AgentDispatch]` is what closes that gap:
    * the only place both the class and a value to build one from are in scope together is `mint`'s
    * own one-line body.
    *
    * `value`/`map` are the only operations offered. `map` is deliberately total over an unconstrained
    * `A => B`: RFC #26's own consumer sketch is `judged.map(parseMyScore)`, so a node must be able to
    * parse whatever the reviewer actually wrote into its own verdict type, with its own logic. That
    * freedom is also the exact limit of what this token proves. `Judged[A]` guarantees that a
    * dispatch through `AgentDispatch.review` happened; it does NOT guarantee that the `A` a consumer
    * ends up holding was derived from that dispatch's answer at all, since nothing stops
    * `judged.map(_ => Verdict.Approve)`, which returns a genuine `Judged[Verdict]` carrying a verdict
    * the reviewer never gave. There is still no route back to a constructor (no `copy`, no public
    * `apply`, no `unapply`), so the one fact this token certifies, that the dispatch happened, cannot
    * be forged. What a downstream node reads out of a `Judged` payload beyond that is only as
    * trustworthy as the mapping chain that produced it, the same trust a node already extends to any
    * value it computes for itself. Who can even get hold of an `AgentDispatch` to call `review` on in
    * the first place is `AgentDispatch`'s own doc, above, not this one's.
    */
  sealed abstract class Judged[+A] private[AgentDispatch] (raw: A):
    def value: A                     = raw
    def map[B](f: A => B): Judged[B] = Judged.mint(f(raw))

  object Judged:
    private[AgentDispatch] def mint[A](value: A): Judged[A] = new Judged[A](value) {}

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
  *
  * `declare` (issue #40) is a second, separate method rather than a special `StatusEvent` shape:
  * `phaseSeq`/`phaseStateSeq` (`test/Recorder.scala`) and every scenario that reads `events` walk
  * `StatusEvent`s only, so a stage declaration that travelled through `append` would either grow a
  * phase string those helpers were never meant to see, or need a filter added at every one of their
  * call sites. A second method keeps the existing event stream untouched and gives the declaration
  * its own, honestly typed shape (`StageSet`) instead of overloading `StatusEvent`'s fields to carry
  * data they were not designed for.
  */
trait StatusLog:
  def append(event: StatusEvent): Unit
  def declare(stages: StageSet): Unit

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
  * already in this file taking the individual capabilities as their own separate `using` parameters
  * (`Machine.pickAndSetup`, ...) would have to change in lockstep, for a task scoped to converting
  * one phase (Pick) into one node. The
  * `given` accessors below are what make BOTH worlds compile unchanged: a function still declared
  * with `(using cfg: Config, gh: GitHub, ...)` keeps resolving those individually wherever a plain
  * `Caps` is the only thing actually in scope (a `Node`'s own `probe`/`run` body), because each
  * accessor derives its one capability from that `Caps` value.
  *
  * Given-ambiguity trap this design invites, and how it is avoided here: importing `Caps.given` into
  * a scope where BOTH a real `given Caps` AND the individual capabilities (as their own named `using`
  * parameters) are simultaneously visible would make every one of these accessors genuinely
  * ambiguous with the local parameter of the same type. Nothing in this codebase does that: the only
  * place a real `given Caps` exists is inside a `Node`'s own context-function body (`Machine.Pick`
  * and, since issue #33, `Machine.Implement`'s `run` as well), which never also carries the
  * individual capabilities as named parameters, and the only place the
  * individual capabilities are named parameters (`Machine.runOnce` and everything it calls) never
  * also introduces a `given Caps`: `runOnce` builds a plain, non-given `Caps` value instead,
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
