package in.rcard.litterbox

import com.typesafe.config.Config as TsConfig

import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

/** Entry point: `scala-cli run .`. Env parsing, preflight, and the MAX_ITERS driver
  * loop that reproduces the original loop.sh's outer shell (loop.sh:100-215 for startup/preflight,
  * loop.sh:925-944 for the driver) over the Machine/Live wiring tasks 1-2 built.
  *
  * Args reach `Cli.parse` first. `init`, `eject` and `help` run and exit before any preflight,
  * because a repo with no config is the whole reason to run `init`; everything else the loop needs
  * still comes from the environment, exactly like bash.
  */
object Main:

  // ---- Part C: pure env parsing, testable without touching the real environment ------------

  /** Everything `Main` needs from the environment, parsed once. Mirrors loop.sh:100-139's env-var
    * defaulting block; `Config` (Domain.scala) carries the per-iteration knobs, this carries the
    * driver-level (`maxIters`) and Live-handler-only (seams, ntfyTopic, gateOverridden) knobs
    * Config has no field for.
    */
  final case class ParsedEnv(
      cfg: Config,
      maxIters: Int,
      implCmd: Option[String],
      fixCmd: Option[String],
      reviewCmd: Option[String],
      notifyCmd: Option[String],
      ciAppearCmd: Option[String],
      mergeCmd: Option[String],
      ntfyTopic: Option[String],
      gateOverridden: Boolean
  )

  /** Config file first, env vars on top. `fromFile` is `.litter-box/config.conf` already merged onto
    * `Settings.Reference` (so it is total over the schema); each env var below then overrides the
    * one key it has always meant. Nothing here reads a default of its own any more — a key's default
    * is whatever the reference says, which is why the `int`/`long`/`str` helpers take the
    * config-derived value as their fallback instead of a literal.
    *
    * TWO environments, not one, and the split is the whole point: `env` is the layered environment
    * (`.litter-box/.env` under the ambient one, see `layerDotEnv`) and answers every VALUE lookup,
    * while `ambient` is the exported environment alone and answers the single question that is about
    * the operator rather than about a value — `gateOverridden`.
    */
  def parseEnv(
      fromFile: TsConfig,
      env: Map[String, String],
      ambient: Map[String, String]
  ): ParsedEnv =
    val base = Settings.parse(fromFile)

    def str(key: String): Option[String]    = env.get(key).filter(_.nonEmpty)
    def int(key: String, default: Int): Int = env.get(key).flatMap(_.toIntOption).getOrElse(default)
    def long(key: String, default: Long): Long =
      env.get(key).flatMap(_.toLongOption).getOrElse(default)

    // loop.sh:129-133: GATE_OVERRIDDEN is captured BEFORE GATE_CMD's own default is applied, so
    // setting GATE_CMD to its own default value still counts as "overridden". It is deliberately
    // about the EXPORTED env var alone: a `gate.fast` in the config file is the repo's normal gate,
    // not an operator saying "skip the sandbox preflight for this run". `.litter-box/.env` is the
    // config-file case too, not the export case — it is a permanent, untracked, invisible file, so a
    // `GATE_CMD` written there would switch the preflight (and with it the credential check that
    // file exists to feed) off for every future run, silently. Hence `ambient` here while `gateCmd`
    // below still takes the layered value: a `.env` gate command is honoured, it just does not get
    // to claim it is an operator bypassing the sandbox for one run.
    val gateOverridden = ambient.get("GATE_CMD").exists(_.nonEmpty)

    val cfg = base.copy(
      dryRun = env.getOrElse("DRY_RUN", "0") == "1", // loop.sh:654: `[[ "$DRY_RUN" == "1" ]]`
      repairBudget = int("REPAIR_BUDGET", base.repairBudget),
      maxPatchBytes = long("MAX_PATCH_BYTES", base.maxPatchBytes),
      gateCmd = str("GATE_CMD").getOrElse(base.gateCmd),
      // A `GATE_CMD` override is by definition a host command: it is what an operator exports to
      // run the loop with no sandbox at all, and it already skips the preflight that would build
      // the image (step 6b below). Leaving `gate.sandboxed` true there would hand the override to
      // a container that was never built.
      gateSandboxed = base.gateSandboxed && !gateOverridden,
      ciWaitCmd = str("CI_WAIT_CMD"),
      gateTimeout = int("GATE_TIMEOUT", base.gateTimeout),
      iterTimeout = int("ITER_TIMEOUT", base.iterTimeout),
      ciWaitTimeout = int("CI_WAIT_TIMEOUT", base.ciWaitTimeout),
      ciAppearTimeout = int("CI_APPEAR_TIMEOUT", base.ciAppearTimeout),
      ciAppearInterval = int("CI_APPEAR_INTERVAL", base.ciAppearInterval),
      implementSlack = int("IMPLEMENT_SLACK", base.implementSlack)
    )

    ParsedEnv(
      cfg = cfg,
      maxIters = int("MAX_ITERS", 1),
      implCmd = str("IMPL_CMD"),
      fixCmd = str("FIX_CMD"),
      reviewCmd = str("REVIEW_CMD"),
      notifyCmd = str("NOTIFY_CMD"),
      ciAppearCmd = str("CI_APPEAR_CMD"),
      mergeCmd = str("MERGE_CMD"),
      ntfyTopic = str("NTFY_TOPIC"),
      gateOverridden = gateOverridden
    )

  /** The same parse for a run with NO `.litter-box/.env`, where the layered environment and the
    * exported one are the same map.
    *
    * THE ONLY CALLERS ARE TESTS, deliberately: `runLoop` always holds both answers (step 1b), so
    * leaving the three-argument form the only production door means production cannot collapse the
    * distinction by omission. A default argument would not do — Scala lets a default refer only to an
    * earlier parameter LIST, and splitting the parameters would make the ordinary call read as two.
    */
  def parseEnv(fromFile: TsConfig, env: Map[String, String]): ParsedEnv =
    parseEnv(fromFile, env, env)

  // ---- `.litter-box/.env` layered under the ambient environment (issue #12) -------------------

  /** The environment the run actually has, plus the part of it a child would not inherit on its own.
    *
    * Two answers rather than one because a JVM cannot mutate its own environment: `effective` is
    * what THIS process reasons with (`parseEnv`, the credential preflight, the PATH scans), while
    * `forChildren` is what has to be stamped onto every child through `LiveProc.exportEnv`, since
    * the sandbox scripts read the credential off their own environment (`lib.sh`'s
    * `sandbox_credential_env`) and never ask the loop for it. Deriving both here, in one place,
    * is what keeps them from drifting into two different ideas of what the environment is.
    */
  private[litterbox] final case class DotEnvLayer(
      effective: Map[String, String],
      forChildren: Map[String, String]
  )

  /** `.litter-box/.env` UNDER the ambient process environment.
    *
    * The precedence is the project's existing one, not a second rule: `Settings`' layering already
    * says an environment variable beats the config file, and this file is the same variables written
    * down instead of exported. An operator exporting a variable for one run must not be silently
    * overruled by a file they filled in weeks ago.
    *
    * An EMPTY exported value overrules nothing, which is again the existing rule rather than a second
    * one: `parseEnv` already reads every value through `filter(_.nonEmpty)` / `exists(_.nonEmpty)`,
    * so an empty variable is an absent one everywhere else in the loop and has to be one here too.
    * It is also the shape operators really have — `resources/scaffold/env.example` hands them
    * `ANTHROPIC_API_KEY=`, and a CI `env:` block built from a missing secret produces the same thing —
    * so letting it shadow the file would reproduce the FATAL this layering exists to remove.
    *
    * `forChildren` is the file's entries MINUS every key the ambient environment actually answers,
    * because a child inherits this JVM's environment before anything is stamped on it: stamping a
    * contested key would hand the child the loser of the comparison just made. `effective` is then
    * built FROM `forChildren`, so the two halves cannot disagree about who won a contested key.
    */
  private[litterbox] def layerDotEnv(
      dotEnv: Map[String, String],
      ambient: Map[String, String]
  ): DotEnvLayer =
    val shadowing   = ambient.filter((_, v) => v.nonEmpty).keySet
    val fileEntries = dotEnv -- shadowing
    DotEnvLayer(effective = ambient ++ fileEntries, forChildren = fileEntries)

  // ---- `command -v` equivalent ---------------------------------------------------------------

  /** Scans `pathEnv` (colon-separated, like `$PATH`) for an executable named `name`.
    *
    * TEST AFFORDANCE, deliberate: `exists` is the file-executable probe, injected rather than
    * called directly so the scan is a pure function of its arguments and can be tested against
    * invented PATH strings without depending on what the host machine happens to have installed.
    * The sibling of the `pathPrepend` / `extraPath` seam in `Live.scala`: both exist so tests can
    * substitute the filesystem's answer about a binary. It differs in shape only, having no `None`
    * default because there is no meaningful no-op probe. THE ONLY PRODUCTION CALLER IS `onRealPath`
    * below, which passes the real `Files.isExecutable`.
    */
  private[litterbox] def findOnPath(
      pathEnv: String,
      name: String,
      exists: String => Boolean
  ): Option[String] =
    pathEnv
      .split(java.io.File.pathSeparator)
      .toList
      .filter(_.nonEmpty)
      .map(dir => s"$dir${java.io.File.separator}$name")
      .find(exists)

  private def onRealPath(pathEnv: String, name: String): Option[String] =
    findOnPath(pathEnv, name, p => Files.isExecutable(Path.of(p)))

  // ---- gate-tool preflight: is the CONFIGURED gate command runnable at all? ------------------

  /** Whether the configured gate command can actually be launched, checked the same way
    * `LiveGateRunner.run` itself resolves and launches it — word-split, then argv[0] resolved
    * against `root` by `resolveArgv0` — rather than by a hard-coded guess at what build tool the
    * gate happens to use.
    *
    * Replaces a former hard-coded `sbt` PATH probe (loop.sh:196), a verbatim port of a bash script
    * that only ever ran one repo's own sbt build. That assumption does not survive `litter-box`
    * becoming a tool other repos consume: a scaffolded consumer's gate is whatever `gate.fast`
    * says, sbt or not, and hard-coding `sbt` produced the wrong diagnostic (`sbt not found`) for
    * every one of them. Probing the CONFIGURED command's own argv0 is the one preflight correct for
    * every consumer, because it is the exact thing `LiveGateRunner` is about to try to exec.
    *
    * Only asked about a HOST gate. A sandboxed `gate.fast` (the scaffolded default, and this repo's
    * own) executes against the image's PATH, so the host has no opinion worth having about it —
    * see the call site in `runLoop`.
    *
    * An empty/whitespace-only `gateCmd` returns `None` (nothing missing), not an error:
    * `LiveProc.wordSplit` gives it bash's own no-op reading, the same one `LiveGateRunner.run`'s
    * empty-argv branch relies on (Live.scala:450-464) to stay green on an empty gate. A preflight
    * that treated an empty gate as a missing tool would reject a configuration the gate runner
    * itself accepts and runs green — a regression this function must not introduce.
    *
    * TEST AFFORDANCE, deliberate, same shape as `findOnPath`'s `exists`: injected so the resolution
    * can be exercised against invented PATHs, repo roots and gate commands without depending on what
    * the host machine happens to have installed. THE ONLY PRODUCTION CALLER IS `onRealGateTool`
    * below, which passes the real `Files.isExecutable`.
    *
    * Returns the argv0 AS RESOLVED — a repo-relative script made absolute under `root`, a bare name
    * left untouched — when it could not be found, so the operator sees the actual thing that was
    * looked for rather than the raw config string.
    */
  private[litterbox] def missingGateTool(
      root: Path,
      gateCmd: String,
      pathEnv: String,
      exists: String => Boolean
  ): Option[String] =
    LiveProc.wordSplit(gateCmd) match
      case Seq() => None // empty/whitespace-only gate: bash's no-op, nothing to find
      case words =>
        val argv0 = LiveGateRunner.resolveArgv0(root, words).head
        val runnable =
          if argv0.contains('/') then exists(argv0)
          else findOnPath(pathEnv, argv0, exists).isDefined
        Option.unless(runnable)(argv0)

  private def onRealGateTool(root: Path, gateCmd: String, pathEnv: String): Option[String] =
    missingGateTool(root, gateCmd, pathEnv, p => Files.isExecutable(Path.of(p)))

  /** The two runners the loop needs, built together so the difference between them is stated once,
    * here, instead of being a property of whichever single instance the wiring happened to reach
    * for. `gate.sandboxed` is about the FAST gate's agent-authored code, never about the CI wait —
    * see `HostGateRunner` (issue #11).
    *
    * Constraint on future edits: the sandbox boundary must stay the only difference between the two,
    * or the loop grows a second policy split that no type checks.
    */
  private[litterbox] def gateRunners(
      root: Path,
      timeoutBin: Option[String],
      sandboxDir: Path,
      sandboxed: Boolean
  ): (GateRunner, HostGateRunner) =
    (
      LiveGateRunner(
        root,
        timeoutBin,
        Option.when(sandboxed)(sandboxDir.resolve("run-fast-gate.sh"))
      ),
      HostGateRunner(LiveGateRunner(root, timeoutBin))
    )

  // ---- Part B: driver rc -> process-exit-code map (loop.sh:925-944) ------------------------

  enum DriverAction:
    case Continue
    case Exit(code: Int)

  /** loop.sh:931-943's `case` block: rc 0 (Success) and rc 40 (NeedsHuman) are the only two that do
    * NOT `exit`; the driver logs and lets the `for` loop advance to the next iteration. Every other
    * rc exits the process immediately. `LoopExit` is closed to exactly these 8 cases (issue #28
    * added `Parked`, rc 60), so there is no bash `*)` passthrough branch to reproduce here.
    *
    * `Parked` is deliberately `Exit`, not `Continue`, even though it shares a lot with
    * `NeedsHuman` (a bounded self-repair budget running out). It belongs with `Idle` instead:
    * "nothing actionable without a human, stop and let the next scheduled run re-check." Making it
    * `Continue` would spin the driver through `MAX_ITERS` ticks that each re-probe GitHub and can
    * do nothing until a human replies, exactly what `Idle`'s exit already exists to avoid. An
    * operator who wants the old keep-going-past-exhaustion behaviour sets
    * `issues.park-on-exhaustion = false`, which routes exhaustion to `NeedsHuman` instead, and
    * that already `Continue`s. This was raised again in the issue #28 review and rejected on
    * purpose: it is not an oversight, so do not "fix" it into `Continue`.
    */
  private[litterbox] def driverAction(exit: LoopExit): DriverAction = exit match
    case LoopExit.Success | LoopExit.NeedsHuman                => DriverAction.Continue
    case LoopExit.ManualStop | LoopExit.Idle | LoopExit.DryRun => DriverAction.Exit(0)
    case LoopExit.NothingMade                                  => DriverAction.Exit(1)
    case LoopExit.InfraFault                                   => DriverAction.Exit(50)
    case LoopExit.Parked                                       => DriverAction.Exit(60)

  /** The repo the loop works on: the git work tree containing the process's CWD.
    *
    * Slice 1 walked ancestors looking for `project.scala`, i.e. "find the litter-box checkout",
    * because the loop and the repo it worked on were the same directory. They are not any more, so
    * the question changed: the root is the CONSUMER repo, and `git rev-parse --show-toplevel` is
    * what answers it. It also keeps the property the ancestor walk existed to preserve — running
    * from a subdirectory resolves the same root as running from the top — for free, since that is
    * what `--show-toplevel` means.
    *
    * Not being inside a work tree is an rc-50 infra fault, not a silent fallback to the CWD: every
    * path the loop touches hangs off this answer, so a wrong root is a run that writes logs, reads
    * conventions and applies patches somewhere nobody asked for.
    *
    * TEST AFFORDANCE, deliberate, and the same shape as `findOnPath`'s `exists`: `revParse` is the
    * `git` call, injected so the parsing and the failure mapping can be exercised against invented
    * results instead of only ever against this one real checkout. THE ONLY PRODUCTION CALLER IS
    * `main`, which passes the real subprocess.
    */
  private[litterbox] def resolveRepoRoot(revParse: () => LiveProc.Result): Either[String, Path] =
    val r = revParse()
    val out = r.stdoutTrimmedTrailingNewlines.strip()
    if r.rc != 0 || out.isEmpty then
      Left(
        "not inside a git work tree (git rev-parse --show-toplevel failed) — run litter-box from inside the repo it should work on"
      )
    else Right(Path.of(out).toAbsolutePath.normalize)

  /** The one place the seam above is tied to the live world: the real `git` subprocess, run from the
    * process's own CWD. Every entry point asks the same question of the same directory, so binding
    * it once keeps `resolveRepoRoot` injectable for the tests while leaving the callers with nothing
    * to get subtly different from each other.
    */
  private def liveRepoRoot(): Either[String, Path] =
    val cwd = Paths.get("").toAbsolutePath
    resolveRepoRoot(() => LiveProc.run(cwd, Seq("git", "rev-parse", "--show-toplevel")))

  /** The exact bash log line for one iteration's outcome (loop.sh:932-941), copied byte-for-byte
    * including loop.sh's em-dash separator character. rc 50's notify already fires inside
    * `Machine.runOnce` (Machine.scala:69); this function only logs, it never notifies a second time.
    */
  private[litterbox] def driverLog(i: Int, exit: LoopExit, stopFile: String): String = exit match
    case LoopExit.Success    => s"iteration $i done (SUCCESS — auto-merged, or PR -> needs-review)"
    case LoopExit.NeedsHuman =>
      s"iteration $i done (FAIL terminal -> needs-human, PR open for audit)"
    case LoopExit.ManualStop  => s"manual $stopFile — exiting"
    case LoopExit.Idle        => "no actionable issue — idle, exiting"
    case LoopExit.DryRun      => "dry run reached its stop point — exiting"
    case LoopExit.NothingMade => s"iteration $i produced nothing — exiting for inspection"
    case LoopExit.InfraFault  => "infra fault — exiting for inspection (issue stays in-progress)"
    case LoopExit.Parked      =>
      // Not "waiting on a human reply": that used to be true of every `Parked` exit, but issue #50
      // review finding 2 adds one where a human HAS already replied and the loop is waiting on the
      // operator to raise `REPAIR_BUDGET` instead (the per-tick log line from `pickAndSetup`
      // already says which one this actually is). Worded generically so it stays true of both.
      s"iteration $i parked, exiting (next tick re-checks)"

  /** loop.sh:927-943: run up to `maxIters` ticks, applying the rc -> action map after each one.
    * Returns the process exit code the caller must `sys.exit` with; `sys.exit` itself stays out of
    * this function (and out of `driverAction`/`driverLog`) so the mapping logic is callable and
    * testable without terminating the JVM.
    */
  private def runDriver(maxIters: Int)(using
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
  ): Int =
    var i = 1
    while i <= maxIters do
      val exit = Machine.runOnce(i)
      LiveLog.log(driverLog(i, exit, summon[Config].stopFile))
      driverAction(exit) match
        case DriverAction.Continue   => i += 1
        case DriverAction.Exit(code) => return code
    LiveLog.log(s"hit MAX_ITERS=$maxIters — exiting")
    0

  // ---- fatal preflight die() (loop.sh:142: `die() { log "FATAL: $*"; exit 1; }`) -----------

  private def die(msg: String): Nothing =
    LiveLog.log(s"FATAL: $msg")
    sys.exit(1)

  /** The two startup failures that are INFRA faults rather than misconfiguration-of-the-loop: a CWD
    * outside any git work tree, and a repo with no `.litter-box/config.conf`. Both exit 50, the same
    * code a Docker outage gets, because both mean "nothing was touched, fix the environment and run
    * again" — an operator watching exit codes must not have to tell them apart from a broken
    * install (rc 1).
    */
  private def die50(msg: String): Nothing =
    LiveLog.log(s"FATAL: $msg")
    sys.exit(LoopExit.InfraFault.rc)

  /** Runs one of the sandbox preflight scripts (build-image.sh, start-proxy.sh) with cwd=root and
    * no args. Their stdio is inherited so the operator sees the build/startup progress live,
    * matching how loop.sh invokes them (their output is not redirected in bash either).
    */
  private def runPreflightScript(cwd: Path, script: Path): Int =
    val pb = LiveProc.builder(Seq(script.toString))
    pb.directory(cwd.toFile)
    pb.inheritIO()
    pb.start().waitFor()

  /** Runs the sandbox teardown script (stop-proxy.sh) on the way out, with cwd=root and no args.
    * Both streams are discarded so shutdown noise never trails litter-box's final output, matching
    * bash's EXIT-trap invocation verbatim (loop.sh:210:
    * `"$SCRIPT_DIR/sandbox/stop-proxy.sh" >/dev/null 2>&1 || true`).
    */
  private def runTeardownScript(cwd: Path, script: Path): Int =
    val pb = LiveProc.builder(Seq(script.toString))
    pb.directory(cwd.toFile)
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    pb.redirectError(ProcessBuilder.Redirect.DISCARD)
    pb.start().waitFor()

  // ---- Part B: entry point -------------------------------------------------------------------

  /** `--dry-run` ORs with `DRY_RUN=1` rather than replacing it.
    *
    * One-way on purpose: the flag can turn a dry run ON, never off. `DRY_RUN=1` is what an operator
    * exports when they want to be sure nothing mutates, and the sandbox test scripts set it the same
    * way; a flag able to clear it would be a way to mutate a repo somebody believed was safe.
    *
    * Unlike `gateOverridden`, this one reads the LAYERED environment, `.litter-box/.env` included,
    * and that is safe for the same reason the OR is one-way: every way in can only arm a dry run,
    * and an armed dry run is the state in which nothing is mutated and no safety check is skipped.
    */
  private[litterbox] def applyDryRunFlag(flagged: Boolean, env: Map[String, String]): Boolean =
    flagged || env.getOrElse("DRY_RUN", "0") == "1"

  /** `litter-box init`. Runs before every preflight the loop does: a repo with no config is the
    * whole reason to run this, so requiring one would be circular, and there is no reason to insist
    * on Docker or a credential to write six files.
    */
  private def runInit(force: Boolean): Int =
    liveRepoRoot() match
      case Left(msg) => LiveLog.log(s"FATAL: $msg"); 1
      case Right(r)  =>
        val detected = Init.detect(r, args => LiveProc.run(r, args))
        Init.run(r, detected, force) match
          case Left(msg)      => LiveLog.log(s"FATAL: $msg"); 1
          case Right(written) =>
            written.foreach(p => LiveLog.log(s"wrote $p"))
            Init.warnings(detected).foreach(w => LiveLog.log(s"WARNING: $w"))
            LiveLog.log("next steps:")
            Init.nextSteps(detected).foreach(s => LiveLog.log(s"  - $s"))
            0

  /** `litter-box eject <prompt>`. Same reasoning as `runInit` for skipping preflight. */
  private def runEject(what: String, force: Boolean): Int =
    liveRepoRoot() match
      case Left(msg) => LiveLog.log(s"FATAL: $msg"); 1
      case Right(r)  =>
        Prompts.eject(r, what, force) match
          case Left(msg)   => LiveLog.log(s"FATAL: $msg"); 1
          case Right(dest) =>
            LiveLog.log(s"wrote ${r.relativize(dest)} — it now overrides the built-in")
            0

  /** The child one of the shipped observability scripts is: its argv, the directory to run it from,
    * and the variables that have to be stamped on top of the ones it inherits.
    */
  private[litterbox] final case class ObserveChild(
      command: List[String],
      cwd: Path,
      env: Map[String, String]
  )

  /** WHAT `litter-box watch` / `litter-box tail` runs, decided apart from running it.
    *
    * Same visibility idiom as `resolveRepoRoot`, and for the same reason: both deciding branches
    * below are invisible from outside a child that has already been launched with inherited stdio,
    * so leaving them inside the live wrapper would leave them untestable. The wrapper keeps only the
    * part that genuinely needs the live world: resolving jq, resolving the repo, unpacking the tree.
    *
    * The target is made absolute against the CALLER's cwd rather than left alone, because the child
    * is about to be run from the repo root instead: a relative path an operator typed means
    * "relative to where I typed it".
    *
    * Log-dir precedence, as the scripts themselves document it (`${LITTER_BOX_LOG_DIR:-...}` in
    * watch.sh): an exported LITTER_BOX_LOG_DIR first, then the repo's own log-dir so a repo that
    * moved it gets a watcher that follows, then the reference default the scripts carry. So the
    * config value is stamped only when the inherited environment left the variable unset or empty
    * (empty-is-absent, the same rule `parseEnv` and `layerDotEnv` already apply to every value they
    * read), because an operator pointing this at a copied log directory is being deliberate and the
    * config must not silently take it back. A repo with no readable config stamps nothing rather
    * than failing: refusing to watch a run because the config went missing would be the wrong moment
    * to insist on one.
    */
  private[litterbox] def observeChild(
      root: Path,
      callerCwd: Path,
      scriptDir: Path,
      inherited: Map[String, String],
      tool: ObserveTool,
      target: Option[String]
  ): ObserveChild =
    val logDir =
      if inherited.get(Settings.LogDirEnvVar).exists(_.nonEmpty) then None
      else Settings.loadFile(root).toOption.map(conf => Settings.parse(conf).logDir)
    ObserveChild(
      command = scriptDir.resolve(tool.script).toString :: target
        .map(t => callerCwd.resolve(t).toString)
        .toList,
      cwd = root,
      env = logDir.map(Settings.LogDirEnvVar -> _).toMap
    )

  /** `litter-box watch` / `litter-box tail`: exec one of the shipped observability scripts against
    * this repo.
    *
    * The whole subcommand exists because these scripts are invoked by a HUMAN (issue #15). The loop
    * is happy to resolve `~/.cache/litter-box/observe/9d1badf60ba2/watch.sh`; nobody types a content
    * digest that changes on every upgrade, so shipping them without a front door would ship them
    * unreachable.
    *
    * Preflight is `jq` and nothing else. No config, no credential, no Docker, no `gh`: watching is
    * passive, it reads a file the loop already wrote, and a run that has gone wrong is exactly when
    * an operator needs this to start. `jq` is checked HERE rather than in the loop's own preflight
    * for the same reason — the loop does not use it, and a missing jq must not stop a run.
    *
    * The child inherits stdio and this process waits on it, rather than the exec-and-replace bash
    * would do, because a JVM cannot replace itself. The practical difference is signals, and there
    * is none that matters: a `^C` at the terminal goes to the whole foreground process group, so
    * `watch.sh`'s own INT trap still restores the terminal.
    */
  private def runObserve(tool: ObserveTool, target: Option[String]): Int =
    val cwd = Paths.get("").toAbsolutePath
    // Lazy so that a `watch` outside a git work tree fails on the repo, and writes nothing to the
    // cache on the way to saying so.
    lazy val extracted =
      try Right(Observe.resolve())
      catch
        case NonFatal(e) => Left(s"could not unpack the observability scripts: ${e.getMessage}")

    val prepared =
      for
        root <- liveRepoRoot()
        _    <- Either.cond(
                  onRealPath(sys.env.getOrElse("PATH", ""), "jq").isDefined,
                  (),
                  s"jq not found — `litter-box ${tool.subcommand}` renders the loop's JSON logs through it"
                )
        dir <- extracted
      yield (root, dir)

    prepared match
      case Left(msg)          => LiveLog.log(s"FATAL: $msg"); 1
      case Right((root, dir)) =>
        // `sys.env` IS the environment the child inherits on this path: nothing here calls
        // `LiveProc.exportEnv` (that is the loop's own wiring, step 2b below), so this JVM's
        // environment reaches the child unmodified and is what the precedence must be decided
        // against.
        val child = observeChild(root, cwd, dir, sys.env, tool, target)
        val pb    = LiveProc.builder(child.command)
        pb.directory(child.cwd.toFile)
        child.env.foreach((k, v) => pb.environment().put(k, v))
        pb.inheritIO()
        pb.start().waitFor()

  @main def litterBoxLoop(args: String*): Unit =
    Cli.parse(args.toList) match
      case Left(msg) =>
        LiveLog.log(s"FATAL: $msg")
        Console.err.println(Cli.Usage)
        sys.exit(1)
      case Right(Command.Help) =>
        Console.out.println(Cli.Usage)
        sys.exit(0)
      case Right(Command.Init(force))           => sys.exit(runInit(force))
      case Right(Command.Eject(what, force))    => sys.exit(runEject(what, force))
      case Right(Command.Observe(tool, target)) => sys.exit(runObserve(tool, target))
      case Right(Command.Loop(dryRun))          => runLoop(dryRun)

  /** The loop, which is everything this file did before there were subcommands. */
  private def runLoop(dryRunFlag: Boolean): Unit =
    val ambient = sys.env

    // 1. root = the git work tree the process was launched inside. Everything downstream is
    // relative to it, so an unanswerable question here is rc 50 and no further work.
    val root = liveRepoRoot() match
      case Right(r)  => r
      case Left(msg) => die50(msg)

    // 1b. `.litter-box/.env`, the file `init` tells the operator to fill in, layered UNDER the
    // ambient environment (issue #12). Everything below this line reads `env`, so the file reaches
    // the credential preflight, `parseEnv` and the seams by the same door an export does.
    val dotEnv = Settings.loadDotEnv(root) match
      case Right(vars) => vars
      case Left(msg)   => die50(msg)
    val layered = layerDotEnv(dotEnv, ambient)
    val env     = layered.effective
    if dotEnv.nonEmpty then
      // Count only. The file holds a credential, so nothing here names a key or a value.
      LiveLog.log(
        s"loaded ${dotEnv.size} variable(s) from ${Settings.DotEnvPath} (an exported variable still wins)"
      )

    // 2. Config file, then env vars on top of it (Part C). A repo with no config has not been
    // `litter-box init`ed; that is an infra fault, not a loop failure.
    val fromFile = Settings.loadFile(root) match
      case Right(c)  => c
      case Left(msg) => die50(msg)
    // `ambient` is passed alongside the layered `env` on purpose: every value comes from the layered
    // one, but "the operator is bypassing the sandbox preflight for this run" can only be said by an
    // exported variable — see `parseEnv`.
    val parsed0 = parseEnv(fromFile, env, ambient)
    val parsed  = parsed0.copy(cfg =
      parsed0.cfg.copy(dryRun = applyDryRunFlag(dryRunFlag, env))
    )

    // 2a. `gate.sandboxed` defaults to true, so a repo whose config predates the key starts running
    // its gate in a container the first time the operator upgrades the binary. Warn rather than
    // fail: the inherited value is the SAFER of the two, and refusing to start over a key nobody
    // was ever asked for would break every upgrade. Asked of the run's EFFECTIVE answer as well as
    // of the file, because a `GATE_CMD` export has already forced the sandbox back off (`parseEnv`)
    // and there would be nothing to warn about.
    if parsed.cfg.gateSandboxed && Settings.omitsGateSandboxed(root) then
      LiveLog.log(s"WARNING: ${Settings.GateSandboxedWarning}")

    // 2b. instance-name and the repo root reach the sandbox scripts as env vars on every child (see
    // LiveProc.export), and so do the `.litter-box/.env` entries this JVM's own environment does not
    // carry — the sandboxed worker, fixer, reviewer and gate read the credential off THEIR OWN
    // environment, so a file entry that stopped at the preflight would fail one dispatch later.
    // The config-derived pair is stamped last: it is derived from `config.conf`, which the loop's
    // own docker naming must agree with, so a `.env` cannot rename the containers out from under it.
    // Set BEFORE the preflight below, which is itself the first child.
    LiveProc.exportEnv(layered.forChildren ++ Settings.childEnv(parsed.cfg, root))

    // 2c. The sandbox scripts, extracted from the artifact to a content-addressed cache. They used
    // to be read from `<repo>/sandbox`, which no repo but litter-box's own ever had — see `Sandbox`.
    // Done before the preflight because the preflight is two of these scripts.
    val sandboxDir =
      try Sandbox.resolve()
      catch case NonFatal(e) => die50(s"could not unpack the sandbox scripts: ${e.getMessage}")

    // 3. (was: the JAVA_HOME pin block, loop.sh:176-192.) Bash pinned JDK 25 onto every child it
    // forked because the old effect library needed JDK 25's StructuredTaskScope API. That dependency
    // is gone and the project targets JDK 21 LTS, so there is nothing left to pin and the block is
    // deleted.
    // `LiveProc.pinJdk` survives unset — its default is None, i.e. "stamp nothing" — so children
    // inherit the ambient JDK, which is the behaviour bash had whenever the pinned JDK was absent.

    // 4. mkdir -p <log-dir> (loop.sh:120-121: LOG_DIR="$SCRIPT_DIR/logs"; mkdir -p "$LOG_DIR").
    Files.createDirectories(root.resolve(parsed.cfg.logDir))

    // 5. RUN_ID = epoch seconds at startup, as a String (loop.sh:152: `RUN_ID="$(date +%s)"`).
    val runId = (System.currentTimeMillis() / 1000).toString

    val pathEnv = env.getOrElse("PATH", "")

    // 6a. gh/claude must be findable on PATH (loop.sh:195-197); a HOST gate command must be
    // launchable whatever it is (see `missingGateTool`).
    //
    // A sandboxed gate is exempt: its argv0 is resolved by bash INSIDE the container, against that
    // image's PATH, so probing the host for it would reject a correct configuration — a Gradle
    // consumer whose gradle lives only in the image is the normal case, not an error.
    if onRealPath(pathEnv, "gh").isEmpty then die("gh not found")
    if !parsed.cfg.gateSandboxed then
      onRealGateTool(root, parsed.cfg.gateCmd, pathEnv).foreach(t =>
        die(s"gate command not runnable: $t not found (gate.fast in .litter-box/config.conf)")
      )
    if onRealPath(pathEnv, "claude").isEmpty then die("claude not found")

    // 6b. Sandbox preflight (loop.sh:198-211), skipped entirely when GATE_CMD is overridden.
    if !parsed.gateOverridden then
      if env
          .getOrElse("CLAUDE_CODE_OAUTH_TOKEN", "")
          .isEmpty && env.getOrElse("ANTHROPIC_API_KEY", "").isEmpty
      then
        // Both places the credential may live are named, because the message used to send an
        // operator who HAD filled in `.litter-box/.env` looking for a bug in their shell (issue #12).
        die(
          s"neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY set, in the environment or in ${Settings.DotEnvPath} — the sandboxed worker/fixer has no other way to authenticate"
        )
      if runPreflightScript(root, sandboxDir.resolve("build-image.sh")) != 0 then
        die("sandbox image build failed")
      if runPreflightScript(root, sandboxDir.resolve("start-proxy.sh")) != 0 then
        die("sandbox proxy failed to start")
      // loop.sh:210's `trap ... EXIT` equivalent: fires on normal completion, any sys.exit
      // (including from a later die()), or an uncaught exception; addShutdownHook guarantees
      // this the same way bash's EXIT trap does.
      sys.addShutdownHook {
        try runTeardownScript(root, sandboxDir.resolve("stop-proxy.sh"))
        catch case NonFatal(_) => 0
        ()
      }

    // 6c. Conventions file existence (loop.sh:119, 212-215). The prompt-template check that used
    // to sit here is gone: the skeletons ship in the artifact now (`Prompts.builtIn`), so there is
    // no consumer-side file whose absence could be a startup failure. A consumer repo that has
    // never ejected anything has no `prompts/` directory at all, and that is the normal case.
    val conventions = root.resolve(parsed.cfg.conventions)
    if !Files.isRegularFile(conventions) then die(s"missing conventions file: $conventions")

    // 7. timeoutBin = first of `timeout`, `gtimeout` found on PATH, else None (loop.sh:174).
    val timeoutBin = onRealPath(pathEnv, "timeout").orElse(onRealPath(pathEnv, "gtimeout"))

    // 8. Wire the Live handlers as a single `using` bundle for Machine.runOnce.
    given Config        = parsed.cfg
    given GitHub        = LiveGitHub(root, parsed.ciAppearCmd, parsed.mergeCmd)
    given Git           = LiveGit(root)
    given AgentDispatch =
      LiveAgentDispatch(
        root,
        sandboxDir,
        timeoutBin,
        parsed.cfg.iterTimeout,
        parsed.implCmd,
        parsed.fixCmd,
        parsed.reviewCmd
      )
    val (fastGates, hostGates) =
      gateRunners(root, timeoutBin, sandboxDir, parsed.cfg.gateSandboxed)
    given GateRunner     = fastGates
    given HostGateRunner = hostGates
    given StatusLog      = LiveStatusLog(root, runId)
    given Notify         = LiveNotify(parsed.notifyCmd, parsed.ntfyTopic, LiveLog.log)
    given HarnessFs      = LiveHarnessFs(root)
    given Clock          = LiveClock
    given Log            = LiveLog

    // 9. loop.sh:926's start line. Unlike loop.sh, this build has a second way into dry-run
    // (`--dry-run`), so the raw env var and the mode the run is actually in can now disagree — the
    // banner has to report `parsed.cfg.dryRun`, the folded value `applyDryRunFlag` already produced,
    // or an operator who passed `--dry-run` alone would be told DRY_RUN=0 while the run genuinely
    // stops at the dry-run stop point. The banner is the operator's confirmation of which mode a run
    // is in, so it must match the mode the run is actually taking, not one of the two inputs to it.
    LiveLog.log(
      s"v2 loop start (MAX_ITERS=${parsed.maxIters}, ITER_TIMEOUT=${parsed.cfg.iterTimeout}s, REPAIR_BUDGET=${parsed.cfg.repairBudget}, DRY_RUN=${if parsed.cfg.dryRun then "1" else "0"})"
    )

    sys.exit(runDriver(parsed.maxIters))
