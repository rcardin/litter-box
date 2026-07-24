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
    */
  def parseEnv(fromFile: TsConfig, env: Map[String, String]): ParsedEnv =
    val base = Settings.parse(fromFile)

    def str(key: String): Option[String]    = env.get(key).filter(_.nonEmpty)
    def int(key: String, default: Int): Int = env.get(key).flatMap(_.toIntOption).getOrElse(default)
    def long(key: String, default: Long): Long =
      env.get(key).flatMap(_.toLongOption).getOrElse(default)

    // loop.sh:129-133: GATE_OVERRIDDEN is captured BEFORE GATE_CMD's own default is applied, so
    // setting GATE_CMD to its own default value still counts as "overridden". It is deliberately
    // about the ENV var alone: a `gate.fast` in the config file is the repo's normal gate, not an
    // operator saying "skip the sandbox preflight for this run".
    val gateOverridden = env.get("GATE_CMD").exists(_.nonEmpty)

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
      ciAppearInterval = int("CI_APPEAR_INTERVAL", base.ciAppearInterval)
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
    * for.
    *
    * The FAST gate is the tier `gate.sandboxed` is about: agent-authored code compiled and tested
    * behind the container's network policy. The CI wait is not that tier at all: it is `gh pr
    * checks --watch` against github.com, and the gate image carries no `gh`, no credentials and no
    * egress to it, so a sandboxed CI wait cannot do anything but exit non-zero. That non-zero rc is
    * indistinguishable from a genuinely red check, which is how one shared runner turned a green PR
    * into `CI RED -> needs-human` for every consumer on the default config (issue #11).
    *
    * Both runners keep the same `root`, the same `timeoutBin` and the same log capture: the ONLY
    * difference is which side of the sandbox boundary the command runs on.
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
    * rc exits the process immediately. `LoopExit` is closed to exactly these 7 cases, so there is
    * no bash `*)` passthrough branch to reproduce here.
    */
  private[litterbox] def driverAction(exit: LoopExit): DriverAction = exit match
    case LoopExit.Success | LoopExit.NeedsHuman                => DriverAction.Continue
    case LoopExit.ManualStop | LoopExit.Idle | LoopExit.DryRun => DriverAction.Exit(0)
    case LoopExit.NothingMade                                  => DriverAction.Exit(1)
    case LoopExit.InfraFault                                   => DriverAction.Exit(50)

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
    */
  private[litterbox] def applyDryRunFlag(flagged: Boolean, env: Map[String, String]): Boolean =
    flagged || env.getOrElse("DRY_RUN", "0") == "1"

  /** `litter-box init`. Runs before every preflight the loop does: a repo with no config is the
    * whole reason to run this, so requiring one would be circular, and there is no reason to insist
    * on Docker or a credential to write six files.
    */
  private def runInit(force: Boolean): Int =
    val cwd  = Paths.get("").toAbsolutePath
    val root = resolveRepoRoot(() => LiveProc.run(cwd, Seq("git", "rev-parse", "--show-toplevel")))
    root match
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
    val cwd  = Paths.get("").toAbsolutePath
    resolveRepoRoot(() => LiveProc.run(cwd, Seq("git", "rev-parse", "--show-toplevel"))) match
      case Left(msg) => LiveLog.log(s"FATAL: $msg"); 1
      case Right(r)  =>
        Prompts.eject(r, what, force) match
          case Left(msg)   => LiveLog.log(s"FATAL: $msg"); 1
          case Right(dest) =>
            LiveLog.log(s"wrote ${r.relativize(dest)} — it now overrides the built-in")
            0

  @main def litterBoxLoop(args: String*): Unit =
    Cli.parse(args.toList) match
      case Left(msg) =>
        LiveLog.log(s"FATAL: $msg")
        Console.err.println(Cli.Usage)
        sys.exit(1)
      case Right(Command.Help) =>
        Console.out.println(Cli.Usage)
        sys.exit(0)
      case Right(Command.Init(force))        => sys.exit(runInit(force))
      case Right(Command.Eject(what, force)) => sys.exit(runEject(what, force))
      case Right(Command.Loop(dryRun))       => runLoop(dryRun)

  /** The loop, which is everything this file did before there were subcommands. */
  private def runLoop(dryRunFlag: Boolean): Unit =
    val env = sys.env

    // 1. root = the git work tree the process was launched inside. Everything downstream is
    // relative to it, so an unanswerable question here is rc 50 and no further work.
    val cwd  = Paths.get("").toAbsolutePath
    val root = resolveRepoRoot(() =>
      LiveProc.run(cwd, Seq("git", "rev-parse", "--show-toplevel"))
    ) match
      case Right(r)  => r
      case Left(msg) => die50(msg)

    // 2. Config file, then env vars on top of it (Part C). A repo with no config has not been
    // `litter-box init`ed; that is an infra fault, not a loop failure.
    val fromFile = Settings.loadFile(root) match
      case Right(c)  => c
      case Left(msg) => die50(msg)
    val parsed0 = parseEnv(fromFile, env)
    val parsed  = parsed0.copy(cfg =
      parsed0.cfg.copy(dryRun = applyDryRunFlag(dryRunFlag, env))
    )

    // 2b. instance-name and the repo root reach the sandbox scripts as env vars on every child (see
    // LiveProc.export). Set BEFORE the preflight below, which is itself the first child.
    LiveProc.exportEnv(Settings.childEnv(parsed.cfg, root))

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
        die(
          "neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY set — the sandboxed worker/fixer has no other way to authenticate"
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
