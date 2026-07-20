package harness

/** Domain ADTs for the loop state machine — the typed spine of `harness/loop.sh`.
  *
  * Every exit code, stage outcome and failure class that loop.sh encoded as scattered
  * `exit`/`return` integers and free-form strings lives here as one closed type.
  */

/** Terminal outcome of one driver tick. `rc` is the process exit-code contract shared with the bash
  * harness (and `watch.sh`); it must never change meaning.
  */
enum LoopExit(val rc: Int):
  /** Merged (class-1 auto-merge) or PR opened -> needs-review. */
  case Success extends LoopExit(0)

  /** STOP.md present — manual kill-switch only; the loop never writes it. */
  case ManualStop extends LoopExit(10)

  /** No ready/in-progress issue. Transient: NO sentinel is written (PR #17 latch bug). */
  case Idle extends LoopExit(11)

  /** DRY_RUN=1 stop point — reached before any git/label mutation. */
  case DryRun extends LoopExit(20)

  /** Empty IMPL patch, or nothing staged at the terminal. */
  case NothingMade extends LoopExit(30)

  /** Budget exhausted / guard rejection / CI red — audit PR open, human takes over. */
  case NeedsHuman extends LoopExit(40)

  /** Infra fault: no budget spent past the raise, issue left in-progress so it resumes next tick,
    * loop exits rc 50 for human inspection.
    */
  case InfraFault extends LoopExit(50)

/** What the patch seam (dispatch -> reset -> inspect -> apply) concluded for one agent patch. */
enum StageResult:
  /** Patch inspected, applied and staged; `patch` is the artifact tamper/reviewer read. */
  case Ok(patch: String)

  /** The agent produced no diff. */
  case Empty

  /** Dispatch hit ITER_TIMEOUT — infra fault, never a gate failure. */
  case Timeout

  /** `git apply --index` refused the patch — infra fault, NO budget spent. */
  case ApplyFail

  /** Guard rejection: protected path. Marker staged, gate SKIPPED, needs-human. */
  case Protected

  /** Guard rejection: patch over MAX_PATCH_BYTES. Marker staged, gate SKIPPED, needs-human. */
  case Oversize

/** Result of a gate command (FAST tier, or the CI wait reusing the same runner). */
enum GateResult:
  case Green, Red, Timeout

/** The cold reviewer's verdict sentinel. */
enum Verdict:
  case Approve, RequestChanges

/** Why a US failed to a needs-human terminal. `text` is the exact string the bash harness used in
  * logs, notify messages, commit tags and PR notes (parity oracle greps them).
  */
enum FailureKind(val text: String):
  case GateRed        extends FailureKind("gate-RED")
  case ReviewChanges  extends FailureKind("REQUEST_CHANGES")
  case ProtectedPath  extends FailureKind("protected-path")
  case OversizedPatch extends FailureKind("oversized-patch")
  case EmptyFix       extends FailureKind("empty-fix")

/** The typed infra-fault channel. Raised through `Raise[InfraFault]`; the driver folds it to rc 50.
  * By construction no code after a raise can run, so no fault path can decrement the repair budget
  * or dispatch a FIX — the v3 invariant as a type-level property.
  *
  * `reason` is the bash `log()` line for that fault, copied verbatim from loop.sh, and
  * `Machine.infraFault` writes it to the `Log` capability at the point of the raise. The parity
  * oracle greps some of these (`half-finished worker must not reach the gates`,
  * `infra fault, not a code failure`), so the wording is asserted behaviour: one string per fault,
  * carried and logged, never two that can drift apart.
  */
final case class InfraFault(reason: String)

/** Agent dispatch role (worker seam: IMPL_CMD vs FIX_CMD). */
enum Role:
  case IMPL, FIX

/** Prompt templates the harness renders ({{KEY}} line-splice contract of render_template). */
enum Template:
  case Iterate, Fix, Review

/** One line of `git apply --numstat` output: "<added>\t<deleted>\t<path>". `added`/`deleted` stay
  * `String` (not `Int`) because binary files report "-" instead of a line count.
  */
final case class NumstatRow(added: String, deleted: String, path: String)

object NumstatRow:
  /** Parses one numstat line; `None` on malformed input (wrong arity) or an empty path. */
  def parse(line: String): Option[NumstatRow] =
    line.split('\t') match
      case Array(added, deleted, path) if path.nonEmpty => Some(NumstatRow(added, deleted, path))
      case _                                            => None

/** One status.jsonl event (ts/pid/run are live-handler concerns, added in slice 2). */
final case class StatusEvent(
    iter: Int,
    issue: String,
    phase: String,
    state: String,
    pass: Int,
    budget: Int,
    logfile: String,
    detail: String
)

/** Env-derived knobs — same outward surface as loop.sh (defaults identical). */
final case class Config(
    dryRun: Boolean = false,
    repairBudget: Int = 2,
    maxPatchBytes: Long = 1_000_000L,
    /** GATE_CMD (loop.sh:133). Held repo-RELATIVE, unlike bash's absolute
      * `$SCRIPT_DIR/sandbox/run-fast-gate.sh`, because this record is pure config and knows no repo
      * root. `LiveGateRunner.resolveArgv0` re-absolutises it against that runner's `root` using
      * bash's own lookup rule, so the launched command is identical.
      */
    gateCmd: String = "harness/sandbox/run-fast-gate.sh",
    /** CI_WAIT_CMD seam (loop.sh:446): overrides the WHOLE CI-wait gate command, including the PR
      * number (bash: `cmd="${CI_WAIT_CMD:-gh pr checks $pr_num --watch --fail-fast}"`; the override
      * contains no pr interpolation, it replaces the default verbatim). `None` (the default) means
      * "use the default `gh pr checks $prNum --watch --fail-fast`".
      */
    ciWaitCmd: Option[String] = None,
    gateTimeout: Int = 900,
    iterTimeout: Int = 1800,
    ciWaitTimeout: Int = 900,
    ciAppearTimeout: Int = 300,
    ciAppearInterval: Int = 10
)
