package in.rcard.litterbox

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

  /** The stop file is present — manual kill-switch only; the loop never writes it. */
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

  /** Repair budget exhausted on the generic sub-case (gate-RED or REQUEST_CHANGES, never a guard
    * rejection or an empty fix), with `issues.park-on-exhaustion` true: the issue is labelled
    * `parked` and left there rather than opened as a needs-human PR. Parking is the terminal state
    * of ONE tick, never a stored position. The next tick re-derives "is there a reply" from GitHub
    * alone (RFC #26 decision 6), the same way `Idle` re-derives "is there a ready issue" rather than
    * latching (see `Idle`'s own note, PR #17).
    */
  case Parked extends LoopExit(60)

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

/** Prompt templates the harness renders ({{KEY}} line-splice contract of render_template). Each
  * case carries its own filename, resolved through `Prompts` (built into the artifact, overridable
  * per repo), so the reader and `Prompts.parseName` cannot drift apart.
  */
enum Template(val fileName: String):
  case Iterate extends Template("iterate-prompt.md")
  case Fix     extends Template("fix-prompt.md")
  case Review  extends Template("review-prompt.md")

  /** Not dispatched by `Machine` today. It exists as a case anyway because `Prompts` enumerates
    * `Template.values` to decide what can be ejected and what ships in the artifact: a fourth
    * skeleton that is not a case is a skeleton a consumer cannot override and the build cannot
    * check, which is a worse failure than an unused case.
    */
  case GrillIssue extends Template("grill-issue-prompt.md")

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

/** One phase's rendering data for the banner (issue #40), the thing `watch.sh` used to know by
  * hardcoded name instead of by reading it off the run. `phase` is the status.jsonl phase string a
  * `StatusEvent` carries; `chip` is the short label the banner prints next to its symbol; `row` says
  * which of the banner's two chip rows the phase belongs to (1 or 2, `banner.sh`'s own two fixed
  * chip lines); `badge` marks a phase rendered as a counted badge (the `fix N` counter) rather than
  * as an ordinary chip, so the renderer knows to count its `start` events instead of drawing a
  * symbol for its latest state.
  *
  * `row` is a plain `Int`, not a two case enum closed over 1 and 2, deliberately (issue #40 review
  * round 2, MINOR 4): `banner.sh` only ever draws two chip rows, but that is a fact about the
  * renderer, not about every `Stage` a graph could ever declare, so this type stays open rather
  * than baking the renderer's own current limit into the data every future consumer graph has to
  * construct. A stage declaring a row other than 1 or 2 does not crash; `Machine.declareStages`
  * logs one line naming it instead, which is what makes the renderer quietly dropping that stage
  * visible rather than a silent surprise the next time someone reads the banner.
  */
final case class Stage(phase: String, chip: String, row: Int, badge: Boolean = false)

/** The whole declared stage set for one run, written once per TICK to status.jsonl before that
  * tick's own first `StatusEvent` (issue #40 review, MAJOR 1: `banner.sh` reads only the last
  * `tail -n 5000` lines of status.jsonl, so a declaration written once per PROCESS eventually
  * scrolls out of that window on a long MAX_ITERS run; writing one ahead of every tick keeps a
  * declaration always inside it) so `banner.sh` can render a graph it has never seen the shape
  * of. `anchor` is the
  * phase that starts one iteration, the same phase the banner used to scope its chips to by literal
  * name ("PICK"); `terminal` is the phase whose event ends the run, the same phase the banner used to
  * treat as "the run is over" by literal name ("DONE"). Both are optional because a consumer graph
  * may have neither: `banner.sh` falls back to scoping over the whole run when `anchor` is absent,
  * and to liveness alone (no terminal line) when `terminal` is absent.
  */
final case class StageSet(stages: List[Stage], anchor: Option[String], terminal: Option[String])

/** The four issue labels the loop drives its own state machine with (`issues.labels`). Named rather
  * than a bare `Map[String, String]` so a caller cannot ask for a key that does not exist, and so
  * "which label does crash-resume query" has one answer (`active`) instead of a string lookup.
  *
  * `class-1`, `needs-review` and `needs-human` are deliberately NOT here: those are the loop's own
  * vocabulary for outcomes it produces, not the consumer repo's queue labels it consumes. `parked`
  * differs from those three the same way: it is QUERIED BACK the next tick (`GitHub.parkedIssues`)
  * rather than only ever written, which is exactly why it lives here instead of being a literal
  * next to them.
  */
final case class Labels(
    ready: String = "ready",
    active: String = "in-progress",
    blocked: String = "blocked",
    parked: String = "parked"
)

/** A model a dispatch may ask for: one member of a closed set, not the free string issue #73 first
  * shipped.
  *
  * A TRAIT over per provider enums rather than one enum, because the model is the first place a
  * second agent vendor would show up: a `CodexModel` enum joins this hierarchy beside
  * [[ClaudeModel]] and every seam between here and the dispatch keeps its type, since Scala 3
  * cannot have one enum extend another. Nothing else in the loop is provider aware yet, and this
  * trait deliberately does not pretend otherwise: `resources/sandbox/run-agent.sh` execs `claude`
  * and `lib.sh` writes `-e ANTHROPIC_MODEL`, so a second provider is a second runner and a second
  * variable, and the members below stay the two facts that are true of every model rather than a
  * `provider` or `envVar` the loop could not honour today.
  *
  * Closed rather than open for the reason the free string turned out to be wrong: a model name is
  * one typo away from a dispatch that silently runs the CLI's default, and the one place that
  * matters most, `agent.model.review`, is exactly where the loop can never notice (see
  * [[AgentModels]]). A typo is now a parse failure at startup instead, and every call site in
  * `src/`, in the testkit and in a consumer's own graph code can only name a model that exists.
  */
sealed trait AgentModel:
  /** What the provider's own CLI is told, and the value that reaches the container as
    * `ANTHROPIC_MODEL`.
    *
    * A FULL model id, never a family alias like `opus`: an alias floats to whatever that family's
    * latest release is, so the same commit of the same repo would dispatch to a different model
    * from one week to the next with nothing in the config file, the logs or the PR recording that
    * it moved. A run's model is part of what produced its diff, so it is pinned. The cost is that a
    * new family release is an edit here, which is a release note rather than a silent change.
    */
  def id: String

  /** How `.litter-box/config.conf` and the `*_MODEL` env vars spell this model, which is the case
    * name lowercased (`opus`, `haiku`).
    *
    * BARE, with no provider prefix, so the spelling stays the one an operator already types at a
    * CLI. That makes the names one flat namespace across every provider: when a `CodexModel`
    * arrives, its case names have to stay distinct from these, and a collision is a rename, not a
    * qualified name. [[AgentModel.parse]] is where that flatness is enforced.
    */
  def configName: String = toString.toLowerCase

object AgentModel:

  /** Every model any provider offers, and the ONE list both the parse and its error message read,
    * so a case that is addable is by construction also namable and reportable.
    */
  val values: Seq[AgentModel] = ClaudeModel.values.toIndexedSeq

  /** A config or env spelling, resolved to the model it names.
    *
    * The `Left` is what an operator sees, so it names every valid spelling: this is the one moment
    * the loop can catch `agent.model.review = "opuss"`, and a message that only said "unknown"
    * would leave them guessing at the very key whose mistake nothing downstream can detect.
    *
    * Case insensitive and trimmed, because the input is hand typed into a HOCON file or an
    * `export`, and `Opus` cannot mean anything other than `opus` in a namespace this small. That is
    * leniency about TYPOGRAPHY only: a name no case carries is still a failed run.
    */
  def parse(name: String): Either[String, AgentModel] =
    val wanted = name.strip
    values
      .find(_.configName.equalsIgnoreCase(wanted))
      .toRight(
        s"unknown model \"$wanted\" — valid models are ${values.map(_.configName).mkString(", ")}"
      )

/** The Claude models a dispatch can ask for, one per family.
  *
  * One case per FAMILY rather than per released snapshot: a consumer picking a model is choosing
  * how strong and how expensive a dispatch is, which is what a family name says and what a dated
  * snapshot buries. The `id` each case carries is still a full pinned id, so the choice is a fact
  * about the run rather than a moving target (see [[AgentModel.id]]).
  */
enum ClaudeModel(val id: String) extends AgentModel:
  case Haiku  extends ClaudeModel("claude-haiku-4-5")
  case Sonnet extends ClaudeModel("claude-sonnet-5")
  case Opus   extends ClaudeModel("claude-opus-5")
  case Fable  extends ClaudeModel("claude-fable-5")

/** Which model each of the three model touched dispatches asks for (`agent.model`), resolved on the
  * HOST and carried into the container, so the choice is stated once in `.litter-box/config.conf`
  * and no node body has to know a container exists.
  *
  * A named record rather than three loose fields on `Config` for the reason issue #73's own risk
  * list gives: `LiveAgentDispatch` already takes three `Option[String]` seam overrides
  * (`IMPL_CMD`/`FIX_CMD`/`REVIEW_CMD`) sitting immediately next to these, so three more parameters
  * would let a positional slip compile and silently wire a seam as a model. One distinct type
  * cannot be confused with any of them, and [[AgentModel]] being a type of its own rather than a
  * `String` now makes that slip a compile error rather than a convention.
  *
  * Every role is INDEPENDENTLY optional and there is no default model anywhere: `None` means the
  * dispatch carries no model at all, so the container keeps whatever `.litter-box/Dockerfile` or the
  * CLI itself already provides. Shipping a default here would silently move every consumer's spend
  * and every consumer's answers at once.
  *
  * PER ROLE, NOT PER NODE, deliberately (issue #73's one open design question, decided here): the
  * model is a property of what a dispatch IS, an implementer, a fixer, a cold reviewer, not of which
  * node in which graph happens to make it, so it is resolved from `Config` at the dispatch handler
  * and `AgentDispatch`'s own signature does not move. A node that wanted a model of its own would
  * put the choice in graph code, which is agent authored in this loop's own threat model, and out of
  * reach of the patch guard that protects the `.litter-box` directory.
  *
  * `review` is the dangerous one. The cold reviewer's independence is this project's central safety
  * property, and pointing this key at a weak model weakens the adversarial gate with nothing in the
  * loop able to detect it or refuse it, the same shape `gate.sandboxed` takes: the only available
  * control is saying so here and in the README.
  */
final case class AgentModels(
    impl: Option[AgentModel] = None,
    fix: Option[AgentModel] = None,
    review: Option[AgentModel] = None
):
  /** The model a worker dispatch of `role` asks for. Here rather than at the two call sites so
    * "which key does a FIX read" has one answer instead of a match repeated per handler.
    */
  def forRole(role: Role): Option[AgentModel] = role match
    case Role.IMPL => impl
    case Role.FIX  => fix

/** The per-iteration knobs, read from `.litter-box/config.conf` and overlaid with the loop.sh env
  * vars (see `Settings` for the layering and `Settings.Reference` for the schema).
  *
  * Every default below must equal the matching key in `Settings.Reference`; `SettingsSpec` asserts
  * that rather than trusting this comment. The two exceptions carry no config key at all and are
  * env-only, because both are per-RUN operator switches rather than per-repo facts: `dryRun`
  * (`DRY_RUN`) and `ciWaitCmd` (`CI_WAIT_CMD`).
  */
final case class Config(
    dryRun: Boolean = false,
    repairBudget: Int = 2,
    maxPatchBytes: Long = 1_000_000L,
    /** `instance-name` — namespaces the Docker image/network/proxy/cache-volume names, carried into
      * the sandbox scripts as `Settings.InstanceEnvVar`.
      */
    instanceName: String = "litter-box",
    /** `conventions` — the file spliced into the worker, fixer and reviewer prompts as
      * `{{CONVENTIONS}}`.
      */
    conventions: String = "CONTEXT.md",
    /** `stop-file` — the manual kill switch. The loop reads it and never writes it. */
    stopFile: String = "STOP.md",
    /** `log-dir` — where every per-iteration artifact (prompts, patches, gate logs, status.jsonl)
      * is written, relative to the repo root.
      */
    logDir: String = ".litter-box/logs",
    labels: Labels = Labels(),
    /** `issues.park-on-exhaustion`: true routes generic repair-budget exhaustion (gate-RED or
      * REQUEST_CHANGES, never a guard rejection or an empty fix) to `LoopExit.Parked` instead of
      * the needs-human PR. Not env-overridable: unlike `repairBudget` or `gateCmd`, this is a
      * per-repo policy about what "waiting on a human" means, not a per-run operator switch.
      */
    parkOnExhaustion: Boolean = true,
    /** `protect` — globs (JDK `glob:` syntax) the patch guard rejects any patch touching. */
    protect: List[String] = List(".litter-box/**", ".github/**", "CONTEXT.md"),
    /** `gate.fast`, overridable by GATE_CMD (loop.sh:133). The command itself, not a path to a
      * runner: where it runs is `gateSandboxed`'s job below.
      *
      * A HOST command is held repo-RELATIVE, unlike bash's absolute
      * `$SCRIPT_DIR/sandbox/run-fast-gate.sh`, because this record is pure config and knows no repo
      * root; `LiveGateRunner.resolveArgv0` re-absolutises it against that runner's `root` using
      * bash's own lookup rule, so the launched command is identical. A SANDBOXED command is never
      * resolved here at all — it is read by bash inside the container, against the image's PATH.
      *
      * The default is `false`, the POSIX command that always fails (bash resolves it as a builtin
      * inside the container, while the host path execs it off PATH), for the reason `Init` refuses
      * to scaffold a build-tool preset (#13): a default gate is a gate litter-box has not verified,
      * and the failure mode of guessing wrong is a repo whose gate cannot run, or worse, one whose
      * gate passes without checking what the operator thought it checked. `false` is the only
      * command that is honest about knowing nothing: it fails loudly on the first pass and names
      * itself in the log. `init` always writes the key explicitly, so no scaffolded repo inherits
      * this; it is reachable only by a consumer who deletes `gate.fast` by hand, and stopping them
      * is the point.
      */
    gateCmd: String = "false",
    /** `gate.sandboxed` — whether `gateCmd` runs inside the sandbox container or on the host.
      *
      * Sandboxed is the default because isolation is the reason litter-box exists, and because the
      * alternative default is the one that was silently in force before #9: `litter-box init`
      * scaffolded a host `sbt` command while THIS repo ran its own gate in a container, so every
      * consumer quietly got a weaker gate tier than the tool's own, and nothing said so.
      *
      * Forced false when the `GATE_CMD` env var is set (`Main.parseEnv`): that override already
      * skips the sandbox preflight entirely, so there would be no image to run the command in.
      */
    gateSandboxed: Boolean = true,
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
    ciAppearInterval: Int = 10,
    /** `timeouts.implement-slack`, overridable by `IMPLEMENT_SLACK` (issue #33 review round 2 finding
      * D). Added on top of `iterTimeout` for `Machine.Implement`'s own `Node.timeout`, never used
      * bare: `Runner.step`'s window for a node starts before `probe` and ends only after `run`
      * returns (`Kit.scala`), and for `Implement` that window brackets the probe's own git read, the
      * worker dispatch, AND the git work `Machine.dispatchInitialImplement` still does after the
      * worker child already exited (`stagePatch`'s reset-then-inspect-then-apply, plus a status
      * emit). `LiveAgentDispatch` (`Live.scala`) bounds only the worker CHILD process, at
      * `iterTimeout` alone; declaring the node's own bound as that identical number, with no slack,
      * would make it a strictly LARGER window guarded by an identical figure, so a worker that
      * legitimately exits a second under `iterTimeout` could still let the git work push the node's
      * own elapsed time past it, faulting a tick that would otherwise have gone on to the gate.
      *
      * A config key, not a literal, for a second reason beyond the usual per-repo-knob one: on a
      * host with neither `timeout` nor `gtimeout` on `PATH`, `Main`'s preflight leaves the worker
      * child's process wrapper unset (`timeoutBin = None`) and `LiveAgentDispatch` prepends no
      * wrapper at all, so the worker child itself is UNBOUNDED there. This node's own bound
      * (`iterTimeout + implementSlack`) is then the only bound left in the whole system, and it
      * cannot cut anything off — `Runner.step` checks it only after the node has already returned —
      * it can only re-label an already-finished success as an infra fault: a legitimate worker run
      * that simply ran long, exited 0 and staged its patch successfully would still fault at that
      * post hoc check, leaving the index staged for the next tick's `git.statusClean()` guard to trip
      * over. An operator on that host class needs a knob to raise the slack above their worker's real
      * running time; on a host WITH `timeout`/`gtimeout` the worker child is bounded independently, so
      * `iterTimeout + implementSlack` is provably unreachable and this is the pure backstop it
      * otherwise claims to be — the tunability matters for the unbounded-child host class only.
      */
    implementSlack: Int = 300,
    /** `agent.model`: which model each of the three model touched dispatches asks for. See
      * [[AgentModels]] for why it is one record, why every role is independently optional and why
      * pointing `review` at a weak model is the dangerous edit.
      */
    models: AgentModels = AgentModels()
)
