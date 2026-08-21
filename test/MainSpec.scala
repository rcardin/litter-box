package in.rcard.litterbox

import in.rcard.litterbox.testsupport.RepoTree
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}

/** Unit tests for the pure parts of `Main`: env parsing (Part C) and the driver's rc ->
  * process-exit-code map (Part B). Preflight (PATH scanning against the real host,
  * build-image.sh/start-proxy.sh subprocesses) is deliberately NOT exercised here; those are
  * live-process concerns, out of scope for this spec per the task brief.
  *
  * Every `parseEnv` case below passes `Settings.referenceOnly` as the file layer, i.e. "a repo whose
  * `.litter-box/config.conf` sets nothing". That keeps these tests about the one thing they are for,
  * the ENV layer on top, and leaves the file layer's own behaviour to `SettingsSpec`. It also means
  * the "default" a no-env case asserts is by construction the reference's value, which is the
  * definition of the default now that no default is written twice.
  */
class MainSpec extends AnyFlatSpec with Matchers:

  /** `Main.parseEnv` is a `Left` on a `*_MODEL` variable naming no model (issue #73). Same unwrap,
    * same reason, as `parseOk`: these cases are about the layering, not about a rejected name.
    */
  private def parseEnvOk(
      fromFile: com.typesafe.config.Config,
      env: Map[String, String]
  ): Main.ParsedEnv =
    Main.parseEnv(fromFile, env).fold(msg => fail(s"expected a parseable env, got: $msg"), identity)

  private def parseEnvOk(
      fromFile: com.typesafe.config.Config,
      env: Map[String, String],
      ambient: Map[String, String]
  ): Main.ParsedEnv =
    Main
      .parseEnv(fromFile, env, ambient)
      .fold(msg => fail(s"expected a parseable env, got: $msg"), identity)

  // ===============================================================================================
  // Part C: parseEnv
  // ===============================================================================================

  "parseEnv" should "produce every bash default (loop.sh:100-139) from an empty env map" in {
    val parsed = parseEnvOk(Settings.referenceOnly, Map.empty)

    parsed.cfg.dryRun shouldBe false
    parsed.cfg.repairBudget shouldBe 2
    parsed.cfg.maxPatchBytes shouldBe 1_000_000L
    parsed.cfg.gateCmd shouldBe "false"
    parsed.cfg.ciWaitCmd shouldBe None
    parsed.cfg.gateTimeout shouldBe 900
    parsed.cfg.iterTimeout shouldBe 1800
    parsed.cfg.ciWaitTimeout shouldBe 900
    parsed.cfg.ciAppearTimeout shouldBe 300
    parsed.cfg.ciAppearInterval shouldBe 10
    parsed.cfg.implementSlack shouldBe 300
    parsed.maxIters shouldBe 1
    parsed.implCmd shouldBe None
    parsed.fixCmd shouldBe None
    parsed.reviewCmd shouldBe None
    parsed.notifyCmd shouldBe None
    parsed.ciAppearCmd shouldBe None
    parsed.mergeCmd shouldBe None
    parsed.ntfyTopic shouldBe None
    parsed.gateOverridden shouldBe false
  }

  it should "turn every non-empty seam env var into Some" in {
    val env = Map(
      "GATE_CMD"      -> "stub-gate",
      "IMPL_CMD"      -> "stub-impl",
      "FIX_CMD"       -> "stub-fix",
      "REVIEW_CMD"    -> "stub-review",
      "NOTIFY_CMD"    -> "stub-notify",
      "CI_WAIT_CMD"   -> "stub-ci-wait",
      "CI_APPEAR_CMD" -> "stub-ci-appear",
      "MERGE_CMD"     -> "stub-merge",
      "NTFY_TOPIC"    -> "some-topic"
    )

    val parsed = parseEnvOk(Settings.referenceOnly, env)

    parsed.cfg.gateCmd shouldBe "stub-gate"
    parsed.cfg.ciWaitCmd shouldBe Some("stub-ci-wait")
    parsed.implCmd shouldBe Some("stub-impl")
    parsed.fixCmd shouldBe Some("stub-fix")
    parsed.reviewCmd shouldBe Some("stub-review")
    parsed.notifyCmd shouldBe Some("stub-notify")
    parsed.ciAppearCmd shouldBe Some("stub-ci-appear")
    parsed.mergeCmd shouldBe Some("stub-merge")
    parsed.ntfyTopic shouldBe Some("some-topic")
    parsed.gateOverridden shouldBe true
  }

  it should "treat an empty-string seam as unset (None), matching bash's [[ -n ]] test" in {
    val parsed = parseEnvOk(Settings.referenceOnly, Map("IMPL_CMD" -> "", "CI_WAIT_CMD" -> ""))

    parsed.implCmd shouldBe None
    parsed.cfg.ciWaitCmd shouldBe None
  }

  it should "flip gateOverridden even when GATE_CMD is set to its own default value" in {
    val parsed =
      parseEnvOk(Settings.referenceOnly, Map("GATE_CMD" -> "sbt -Werror compile test"))

    parsed.gateOverridden shouldBe true
    parsed.cfg.gateCmd shouldBe "sbt -Werror compile test"
  }

  /** `.litter-box/.env` reaches `parseEnv` through the layered environment (issue #12), which is
    * exactly what it must do for the credential and every ordinary value. It must NOT reach
    * `gateOverridden`: that flag means "an operator is skipping the sandbox preflight for this run",
    * and a permanent untracked file is the config-file case, not the per-run export case. Were it to
    * flip, the credential check the file exists to feed would be the first thing skipped.
    */
  it should "not let a GATE_CMD from .litter-box/.env flip gateOverridden" in {
    val ambient = Map.empty[String, String]
    val layered = Main.layerDotEnv(dotEnv = Map("GATE_CMD" -> "true"), ambient = ambient)

    val parsed = parseEnvOk(Settings.referenceOnly, layered.effective, ambient)

    parsed.gateOverridden shouldBe false
    // The value still lands, and still runs sandboxed: a `.env` gate command is a configured gate,
    // indistinguishable in kind from a `gate.fast` in `config.conf`.
    parsed.cfg.gateCmd shouldBe "true"
    parsed.cfg.gateSandboxed shouldBe true
  }

  it should "still flip gateOverridden for a GATE_CMD the operator exported for this run" in {
    val ambient = Map("GATE_CMD" -> "true")
    val layered = Main.layerDotEnv(dotEnv = Map.empty, ambient = ambient)

    val parsed = parseEnvOk(Settings.referenceOnly, layered.effective, ambient)

    parsed.gateOverridden shouldBe true
    parsed.cfg.gateSandboxed shouldBe false
  }

  it should "run a GATE_CMD override on the host, whatever gate.sandboxed says" in {
    // The override already skips the sandbox preflight (Main step 6b), so the image the command
    // would run in is never built. Honouring `sandboxed = true` here would hand the operator's
    // command to a container that does not exist.
    parseEnvOk(Settings.referenceOnly, Map.empty).cfg.gateSandboxed shouldBe true
    parseEnvOk(Settings.referenceOnly, Map("GATE_CMD" -> "true")).cfg.gateSandboxed shouldBe false
  }

  it should "parse DRY_RUN=1 as true, and treat 0 / absent / any other string as false" in {
    parseEnvOk(Settings.referenceOnly, Map("DRY_RUN" -> "1")).cfg.dryRun shouldBe true
    parseEnvOk(Settings.referenceOnly, Map("DRY_RUN" -> "0")).cfg.dryRun shouldBe false
    parseEnvOk(Settings.referenceOnly, Map.empty).cfg.dryRun shouldBe false
    parseEnvOk(Settings.referenceOnly, Map("DRY_RUN" -> "true")).cfg.dryRun shouldBe false
  }

  it should "parse numeric overrides" in {
    val parsed = parseEnvOk(Settings.referenceOnly, 
      Map(
        "MAX_ITERS"          -> "5",
        "ITER_TIMEOUT"       -> "60",
        "GATE_TIMEOUT"       -> "61",
        "REPAIR_BUDGET"      -> "3",
        "MAX_PATCH_BYTES"    -> "2000",
        "CI_WAIT_TIMEOUT"    -> "62",
        "CI_APPEAR_TIMEOUT"  -> "63",
        "CI_APPEAR_INTERVAL" -> "5",
        "IMPLEMENT_SLACK"    -> "64"
      )
    )

    parsed.maxIters shouldBe 5
    parsed.cfg.iterTimeout shouldBe 60
    parsed.cfg.gateTimeout shouldBe 61
    parsed.cfg.repairBudget shouldBe 3
    parsed.cfg.maxPatchBytes shouldBe 2000L
    parsed.cfg.ciWaitTimeout shouldBe 62
    parsed.cfg.ciAppearTimeout shouldBe 63
    parsed.cfg.ciAppearInterval shouldBe 5
    parsed.cfg.implementSlack shouldBe 64
  }

  // ===============================================================================================
  // Part B: driverAction / driverLog (loop.sh:925-944)
  // ===============================================================================================

  "driverAction" should "Continue on Success and NeedsHuman (rc 0 / 40 never exit)" in {
    Main.driverAction(LoopExit.Success) shouldBe Main.DriverAction.Continue
    Main.driverAction(LoopExit.NeedsHuman) shouldBe Main.DriverAction.Continue
  }

  it should "Exit(0) on ManualStop, Idle, and DryRun (rc 10 / 11 / 20)" in {
    Main.driverAction(LoopExit.ManualStop) shouldBe Main.DriverAction.Exit(0)
    Main.driverAction(LoopExit.Idle) shouldBe Main.DriverAction.Exit(0)
    Main.driverAction(LoopExit.DryRun) shouldBe Main.DriverAction.Exit(0)
  }

  it should "Exit(1) on NothingMade (rc 30)" in {
    Main.driverAction(LoopExit.NothingMade) shouldBe Main.DriverAction.Exit(1)
  }

  it should "Exit(50) on InfraFault (rc 50)" in {
    Main.driverAction(LoopExit.InfraFault) shouldBe Main.DriverAction.Exit(50)
  }

  it should "Exit(60) on Parked (issue #28)" in {
    Main.driverAction(LoopExit.Parked) shouldBe Main.DriverAction.Exit(60)
  }

  "driverLog" should "copy loop.sh's exact log lines, including the em-dash separator" in {
    Main.driverLog(
      3,
      LoopExit.Success,
      "STOP.md"
    ) shouldBe "iteration 3 done (SUCCESS — auto-merged, or PR -> needs-review)"
    Main.driverLog(
      3,
      LoopExit.NeedsHuman,
      "STOP.md"
    ) shouldBe "iteration 3 done (FAIL terminal -> needs-human, PR open for audit)"
    Main.driverLog(3, LoopExit.ManualStop, "STOP.md") shouldBe "manual STOP.md — exiting"
    Main.driverLog(3, LoopExit.Idle, "STOP.md") shouldBe "no actionable issue — idle, exiting"
    Main.driverLog(3, LoopExit.DryRun, "STOP.md") shouldBe "dry run reached its stop point — exiting"
    Main.driverLog(
      3,
      LoopExit.NothingMade,
      "STOP.md"
    ) shouldBe "iteration 3 produced nothing — exiting for inspection"
    Main.driverLog(
      3,
      LoopExit.InfraFault,
      "STOP.md"
    ) shouldBe "infra fault — exiting for inspection (issue stays in-progress)"
    Main.driverLog(
      3,
      LoopExit.Parked,
      "STOP.md"
    ) shouldBe "iteration 3 parked, exiting (next tick re-checks)"
  }

  /** The stop file is `stop-file` in the config now, not a constant, so the ManualStop line has to
    * NAME the file the operator was actually told to create. A non-default value proves the
    * parameter reaches the string: with `STOP.md` everywhere, a `driverLog` that ignored its third
    * argument entirely would still pass every assertion above.
    */
  it should "name the configured stop file, not a hardcoded STOP.md" in {
    Main.driverLog(1, LoopExit.ManualStop, "HALT.md") shouldBe "manual HALT.md — exiting"
  }

  // ===============================================================================================
  // findOnPath (command -v equivalent), optional per the brief, small and cheap
  // ===============================================================================================

  "findOnPath" should "find a name in the first PATH dir that satisfies the exists predicate" in {
    val path = List("/nonexistent", "/usr/bin", "/bin").mkString(java.io.File.pathSeparator)

    Main.findOnPath(path, "gh", _ == "/usr/bin/gh") shouldBe Some("/usr/bin/gh")
    Main.findOnPath(path, "missing-tool", _ => false) shouldBe None
  }

  // ===============================================================================================
  // missingGateTool: is the CONFIGURED gate command runnable, whatever build tool it names?
  // ===============================================================================================

  private val gateRoot = java.nio.file.Path.of("/work/consumer-repo")
  private val gatePath = List("/usr/bin", "/bin").mkString(java.io.File.pathSeparator)

  "missingGateTool" should "find None when a bare tool name is on PATH" in {
    Main.missingGateTool(gateRoot, "true", gatePath, _ == "/usr/bin/true") shouldBe None
  }

  it should "return the bare tool name when it is on no PATH dir" in {
    Main.missingGateTool(gateRoot, "sbt", gatePath, _ => false) shouldBe Some("sbt")
  }

  it should "find None when a repo-relative script path resolves to an executable file" in {
    val script = "/work/consumer-repo/sandbox/run-fast-gate.sh"
    Main.missingGateTool(
      gateRoot,
      "sandbox/run-fast-gate.sh",
      gatePath,
      _ == script
    ) shouldBe None
  }

  it should "return the resolved absolute path when a repo-relative script is missing" in {
    val script = "/work/consumer-repo/sandbox/run-fast-gate.sh"
    Main.missingGateTool(gateRoot, "sandbox/run-fast-gate.sh", gatePath, _ => false) shouldBe
      Some(script)
  }

  it should "find None for an empty gate command, bash's own no-op reading" in {
    Main.missingGateTool(gateRoot, "", gatePath, _ => false) shouldBe None
  }

  it should "find None for a whitespace-only gate command" in {
    Main.missingGateTool(gateRoot, "   ", gatePath, _ => false) shouldBe None
  }

  it should "accept GATE_CMD=true, the shape the dry-run verification and sandbox scripts use" in {
    Main.missingGateTool(gateRoot, "true", gatePath, _ == "/bin/true") shouldBe None
  }

  // ===============================================================================================
  // resolveRepoRoot: the CONSUMER repo's work tree, per `git rev-parse --show-toplevel`
  // ===============================================================================================

  /** The unit cases here stub `git` rather than run it, because what they pin is the parsing and the
    * failure mapping, and neither is reachable from a real invocation: a healthy checkout only ever
    * produces the happy path, and the two failures below would need a directory outside any work
    * tree and a git that answers rc 0 with nothing, neither of which a test can manufacture without
    * being at least as fragile as the stub. The integration case at the end covers the other half,
    * that the real subprocess is wired up the way this stub pretends it is.
    */
  "resolveRepoRoot" should "take git's toplevel from stdout, trailing newline and all" in {
    val result =
      Main.resolveRepoRoot(() => LiveProc.Result(0, "/work/consumer-repo\n", ""))

    result shouldBe Right(java.nio.file.Path.of("/work/consumer-repo"))
  }

  it should "normalise the path git reports, so the root is absolute and dot-free" in {
    val result =
      Main.resolveRepoRoot(() => LiveProc.Result(0, "/work/./consumer-repo/sub/..\n", ""))

    result shouldBe Right(java.nio.file.Path.of("/work/consumer-repo"))
  }

  it should "fail loudly when the cwd is outside any work tree (git's rc 128)" in {
    val result = Main.resolveRepoRoot(() =>
      LiveProc.Result(128, "", "fatal: not a git repository (or any of the parent directories)\n")
    )

    result.isLeft shouldBe true
    result.left.getOrElse("") should include("git rev-parse")
  }

  /** rc 0 with nothing on stdout is not a root anyone can use, and treating it as one would resolve
    * to the JVM's cwd via `Path.of("")` — the silent wrong-directory run this function exists to
    * prevent. So an empty answer is a failure even though git said it succeeded.
    */
  it should "reject a blank stdout even on rc 0" in {
    Main.resolveRepoRoot(() => LiveProc.Result(0, "  \n", "")).isLeft shouldBe true
  }

  /** The Done criterion that "running from a subdirectory of a consumer repo resolves the same root
    * as running from its top". The `revParse` thunk is what carries the CWD (`LiveProc.run`'s first
    * argument), so the only thing that can make the two runs disagree is git being asked from the
    * wrong place — which is exactly the walk-up this pins. Both are compared to each other AND to
    * the fixture's own real path, because two runs that both wrongly returned the JVM's cwd would
    * agree with each other and prove nothing. `toRealPath` is required on macOS, where the temp dir
    * lives under a `/var -> /private/var` symlink that git resolves and `createTempDirectory` does
    * not.
    */
  it should "resolve the same root from a subdirectory of a consumer repo as from its top" in {
    val top = java.nio.file.Files.createTempDirectory("main-spec-consumer")
    LiveProc.run(top, Seq("git", "init", "--quiet"))
    java.nio.file.Files.createDirectories(top.resolve(Settings.ConfigPath).getParent)
    java.nio.file.Files.writeString(top.resolve(Settings.ConfigPath), "instance-name = \"other\"\n")
    val nested = java.nio.file.Files.createDirectories(top.resolve("src/main/scala"))

    def rootFrom(cwd: java.nio.file.Path) =
      Main.resolveRepoRoot(() => LiveProc.run(cwd, Seq("git", "rev-parse", "--show-toplevel")))

    val fromTop    = rootFrom(top)
    val fromNested = rootFrom(nested)

    fromNested shouldBe fromTop
    fromNested shouldBe Right(top.toRealPath())
  }

  /** The real-subprocess half of the contract, over a work tree this test BUILDS rather than over
    * the checkout it happens to be running in (#17).
    *
    * It used to branch on `Files.isDirectory(".git")` and assert the success contract only when one
    * was there. That made the success path unreachable in the environment this project made
    * canonical for its own gate: `run-fast-gate.sh` materialises the workspace with `git archive`,
    * so there is no `.git` by construction (#9), and the branch quietly reported green while
    * covering half of what it claimed. Building the work tree here removes the condition instead of
    * testing around it, so both contracts below hold in every environment the suite runs in.
    *
    * `toRealPath` is required on macOS, where the temp dir lives under a `/var -> /private/var`
    * symlink that git resolves and `createTempDirectory` does not.
    */
  it should "resolve a real work tree's root by really shelling out to git" in {
    val top = Files.createTempDirectory("main-spec-real-git").toRealPath()
    LiveProc.run(top, Seq("git", "init", "--quiet"))
    Files.createDirectories(top.resolve(Settings.ConfigPath).getParent)
    Files.writeString(top.resolve(Settings.ConfigPath), "instance-name = \"other\"\n")

    val real =
      Main.resolveRepoRoot(() => LiveProc.run(top, Seq("git", "rev-parse", "--show-toplevel")))

    real shouldBe Right(top)
    // The root is what every path downstream hangs off, so the assertion that matters is not that
    // git answered but that the answer is the directory holding this repo's config.
    real.map(r => Files.isRegularFile(r.resolve(Settings.ConfigPath))) shouldBe Right(true)
  }

  /** The failure contract against the same real subprocess, and the reason it is manufacturable
    * after all: `GIT_CEILING_DIRECTORIES` stops git's walk up the tree at the temp directory's
    * parent, so the answer cannot depend on whether the machine happens to keep its temp directory
    * inside somebody's checkout. Without it this case would be a bet on the host's filesystem.
    *
    * The alternative — quietly falling back to the cwd — is a loop that writes logs, reads
    * conventions and applies patches in whatever directory it was launched from.
    */
  it should "fail on a real directory that is inside no work tree" in {
    val outside = Files.createTempDirectory("main-spec-no-git").toRealPath()

    val real = Main.resolveRepoRoot(() =>
      LiveProc.run(
        outside,
        Seq("git", "rev-parse", "--show-toplevel"),
        env = Map("GIT_CEILING_DIRECTORIES" -> outside.getParent.toString)
      )
    )

    real.isLeft shouldBe true
    real.swap.getOrElse("") should include("git rev-parse")
  }

  // ===============================================================================================
  // applyDryRunFlag: the one-way OR between --dry-run and DRY_RUN=1
  // ===============================================================================================

  "the --dry-run flag" should "turn dry-run on" in:
    Main.applyDryRunFlag(flagged = true, Map.empty) shouldBe true

  it should "leave an operator's DRY_RUN=1 alone when the flag is absent" in:
    // The flag is one-way on purpose. An invocation that could silently disarm a dry run is an
    // invocation that mutates a repo somebody believed was safe.
    Main.applyDryRunFlag(flagged = false, Map("DRY_RUN" -> "1")) shouldBe true

  it should "turn dry-run on even when DRY_RUN explicitly says off" in:
    // The only combination where the OR does real work: the flag beating an env var that
    // explicitly says off, not just an absent one.
    Main.applyDryRunFlag(flagged = true, Map("DRY_RUN" -> "0")) shouldBe true

  it should "be off when neither says otherwise" in:
    Main.applyDryRunFlag(flagged = false, Map("DRY_RUN" -> "0")) shouldBe false
    Main.applyDryRunFlag(flagged = false, Map.empty) shouldBe false

  // ===============================================================================================
  // layerDotEnv: `.litter-box/.env` UNDER the ambient environment (GitHub issue #12)
  // ===============================================================================================

  /** The file the operator was told to fill in has to reach the credential check, or `init`'s own
    * next step ends in a FATAL saying the credential is unset. `forChildren` is the other half: the
    * sandboxed worker, fixer, reviewer and gate all read the credential out of THEIR OWN
    * environment (`lib.sh`'s `sandbox_credential_env`), so an entry that reached only this JVM would
    * fix the preflight and then fail one dispatch later.
    */
  "layerDotEnv" should "carry a file-only entry into the effective env and onto every child" in {
    val layered = Main.layerDotEnv(
      dotEnv = Map("CLAUDE_CODE_OAUTH_TOKEN" -> "from-file"),
      ambient = Map("PATH" -> "/usr/bin")
    )

    layered.effective shouldBe Map("CLAUDE_CODE_OAUTH_TOKEN" -> "from-file", "PATH" -> "/usr/bin")
    // A child inherits this JVM's own environment, so only the entry it would otherwise MISS has to
    // be stamped on it.
    layered.forChildren shouldBe Map("CLAUDE_CODE_OAUTH_TOKEN" -> "from-file")
  }

  /** The precedence the project already states for `config.conf` (`Settings`' layering scaladoc: an
    * environment variable beats the file), applied to the credential file rather than invented
    * again. An operator who exports a variable for one run must not be silently overruled by a file
    * they filled in weeks ago.
    */
  it should "let the ambient environment win on a key both set" in {
    val layered = Main.layerDotEnv(
      dotEnv = Map("ANTHROPIC_API_KEY" -> "from-file", "MAX_ITERS" -> "9"),
      ambient = Map("ANTHROPIC_API_KEY" -> "from-shell")
    )

    layered.effective shouldBe Map("ANTHROPIC_API_KEY" -> "from-shell", "MAX_ITERS" -> "9")
    // Nothing to stamp for the contested key: the child inherits the ambient value already, and
    // stamping the file's would hand the child the loser of the very comparison just made.
    layered.forChildren shouldBe Map("MAX_ITERS" -> "9")
  }

  /** `export ANTHROPIC_API_KEY=` is not an operator overruling the file: it is the shape
    * `resources/scaffold/env.example` hands operators, and the shape a CI `env:` block produces from
    * a missing secret. Letting it shadow would reproduce the very FATAL issue #12 exists to fix, and
    * it would be a SECOND precedence rule on top of the one `parseEnv` already applies to every value
    * it reads (`filter(_.nonEmpty)`).
    */
  it should "not let an empty ambient value shadow a non-empty .env value" in {
    val layered = Main.layerDotEnv(
      dotEnv = Map("ANTHROPIC_API_KEY" -> "from-file"),
      ambient = Map("ANTHROPIC_API_KEY" -> "", "PATH" -> "/usr/bin")
    )

    layered.effective shouldBe Map("ANTHROPIC_API_KEY" -> "from-file", "PATH" -> "/usr/bin")
    // Both halves have to name the same winner: the child inherits the empty ambient value from this
    // JVM, so a key the file won here and did not stamp there is a dispatch authenticating with the
    // value the preflight just decided to ignore.
    layered.forChildren shouldBe Map("ANTHROPIC_API_KEY" -> "from-file")
  }

  it should "keep an empty ambient value the .env does not answer" in {
    // Empty-is-absent is about which of TWO values wins, never about deleting an environment variable
    // this process really has: `effective` is what the run reasons with, so it stays the truth about
    // the environment wherever there is nothing to compare against.
    val layered = Main.layerDotEnv(dotEnv = Map.empty, ambient = Map("NTFY_TOPIC" -> ""))

    layered.effective shouldBe Map("NTFY_TOPIC" -> "")
    layered.forChildren shouldBe Map.empty
  }

  it should "be the ambient environment untouched when there is no .env at all" in {
    val layered = Main.layerDotEnv(Map.empty, Map("PATH" -> "/usr/bin"))

    layered.effective shouldBe Map("PATH" -> "/usr/bin")
    layered.forChildren shouldBe Map.empty
  }

  // ===============================================================================================
  // observeChild: what `litter-box watch` / `litter-box tail` is about to run (GitHub issue #15)
  // ===============================================================================================

  /** A repo the observe subcommands can be pointed at. `toRealPath` for the same macOS reason the
    * git cases above give, so an assertion comparing a path this test built against one the function
    * returned cannot fail on `/var` vs `/private/var` alone.
    */
  private def observeRoot(config: String): Path =
    val root = Files.createTempDirectory("main-spec-observe").toRealPath()
    val file = root.resolve(Settings.ConfigPath)
    Files.createDirectories(file.getParent)
    Files.writeString(file, config)
    root

  /** Stands in for the content-addressed cache the scripts are unpacked into. A real directory
    * rather than an invented string, so the argv the child gets is built the way production builds
    * it and the assertions stay about the decisions rather than about path syntax.
    */
  private def observeScripts(): Path = Files.createTempDirectory("main-spec-observe-scripts")

  private val ObserveConfig =
    """instance-name = "other"
      |log-dir       = "moved/logs"
      |""".stripMargin

  /** The precedence the scripts themselves document (`${LITTER_BOX_LOG_DIR:-...}`), pinned on the
    * side that a repo's own config could silently take back. An operator who exported the variable
    * is pointing the watcher at a copied log directory on purpose.
    */
  "observeChild" should "let an exported LITTER_BOX_LOG_DIR beat the repo's log-dir" in {
    val child = Main.observeChild(
      root = observeRoot(ObserveConfig),
      callerCwd = Paths.get("").toAbsolutePath,
      scriptDir = observeScripts(),
      inherited = Map(Settings.LogDirEnvVar -> "/copied/logs"),
      tool = ObserveTool.Watch,
      target = None
    )

    // Nothing stamped at all, rather than the exported value stamped back over itself: the child
    // inherits it already, so anything here would be the config winning.
    child.env shouldBe Map.empty
  }

  it should "stamp the repo's own log-dir when the environment leaves the variable unset" in {
    val root    = observeRoot(ObserveConfig)
    val scripts = observeScripts()

    def envFor(inherited: Map[String, String]) =
      Main
        .observeChild(root, Paths.get("").toAbsolutePath, scripts, inherited, ObserveTool.Watch, None)
        .env

    // A repo that moved its log-dir has to get a watcher that follows it there.
    envFor(Map.empty) shouldBe Map(Settings.LogDirEnvVar -> "moved/logs")
    // Empty-is-absent, the rule `parseEnv` and `layerDotEnv` already apply to every value they read,
    // and the shape a CI `env:` block built from an unset variable really produces.
    envFor(Map(Settings.LogDirEnvVar -> "")) shouldBe Map(Settings.LogDirEnvVar -> "moved/logs")
  }

  it should "stamp nothing when neither the environment nor a readable config answers" in {
    // No `.litter-box/config.conf` at all. Watching is passive and a run that has gone wrong is
    // exactly when an operator needs this to start, so the variable is left alone and the scripts
    // fall back to the default they carry themselves.
    val root = Files.createTempDirectory("main-spec-observe-bare").toRealPath()

    val child =
      Main.observeChild(root, root, observeScripts(), Map.empty, ObserveTool.Tail, None)

    child.env shouldBe Map.empty
    child.cwd shouldBe root
  }

  it should "make a relative target absolute against the caller's cwd" in {
    val root    = observeRoot(ObserveConfig)
    val scripts = observeScripts()
    val caller  = Files.createDirectories(root.resolve("modules/core"))

    val child = Main.observeChild(
      root,
      callerCwd = caller,
      scriptDir = scripts,
      inherited = Map.empty,
      tool = ObserveTool.Tail,
      target = Some("logs/iter-3.log")
    )

    child.command shouldBe List(
      scripts.resolve("tail-claude.sh").toString,
      caller.resolve("logs/iter-3.log").toString
    )
    // And the reason the argument cannot stay relative: the child is run from the repo root, which
    // is not where the operator typed the path.
    child.cwd shouldBe root
  }

  it should "pass an absolute target through untouched" in {
    val scripts = observeScripts()

    val child = Main.observeChild(
      observeRoot(ObserveConfig),
      callerCwd = Files.createTempDirectory("main-spec-observe-caller"),
      scriptDir = scripts,
      inherited = Map.empty,
      tool = ObserveTool.Watch,
      target = Some("/var/tmp/copied/status.jsonl")
    )

    child.command shouldBe List(
      scripts.resolve("watch.sh").toString,
      "/var/tmp/copied/status.jsonl"
    )
  }

  it should "hand the script no argument when the operator named no target" in {
    val scripts = observeScripts()

    val child = Main.observeChild(
      observeRoot(ObserveConfig),
      Paths.get("").toAbsolutePath,
      scripts,
      Map.empty,
      ObserveTool.Watch,
      target = None
    )

    // An empty string argument is not the same thing as no argument: the scripts default the path
    // themselves, and only an absent one lets them.
    child.command shouldBe List(scripts.resolve("watch.sh").toString)
  }

  // ===============================================================================================
  // liveAgentDispatch: the parsed `agent.model.*` config -> the constructed dispatch (issue #73's
  // review thread on this join). `runLoop` itself cannot be reached from a unit test (PATH probes,
  // the Docker preflight, `sys.exit`), so this exercises the one function `runLoop` calls to build
  // the dispatch, with a real (unseamed) `LiveAgentDispatch` and a fake sandbox script standing in
  // for `run-agent.sh`, exactly the way `LiveProcSpec` pins the model half of that constructor.
  // ===============================================================================================

  private def tempRoot(): Path = Files.createTempDirectory("main-spec-dispatch")

  private def readString(p: Path): String =
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /** Writes an executable script and returns its path. */
  private def writeExecutable(dir: Path, name: String, content: String): Path =
    val p = dir.resolve(name)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"))
    p

  /** A stand in for `run-agent.sh`, reporting the model it was handed, so the model that
    * `liveAgentDispatch` wired in can be observed with no Docker anywhere near the test.
    */
  private val ModelRecorder = """#!/usr/bin/env bash
    |printf 'MODEL=[%s]\n' "${LITTER_BOX_AGENT_MODEL-<absent>}"
    |""".stripMargin

  "liveAgentDispatch" should "carry parsed.cfg.models into the dispatch it constructs" in {
    val root       = tempRoot()
    val sandboxDir = root.resolve("sandbox")
    Files.createDirectories(sandboxDir)
    writeExecutable(sandboxDir, "run-agent.sh", ModelRecorder)

    val parsed = parseEnvOk(
      Settings.referenceOnly,
      Map("IMPL_MODEL" -> "opus", "FIX_MODEL" -> "haiku")
    )
    parsed.cfg.models shouldBe AgentModels(impl = Some(ClaudeModel.Opus), fix = Some(ClaudeModel.Haiku))

    val dispatch = Main.liveAgentDispatch(parsed, root, sandboxDir, timeoutBin = None)
    dispatch.worker(Role.IMPL, "p.txt", "logs/i.patch", "logs/i.log", None)

    // This is the assertion that a dropped `models = parsed.cfg.models` argument would fail: with
    // the constructor default (`AgentModels()`) standing in, the recorder would see `<absent>`
    // rather than the model IMPL_MODEL=opus named above.
    readString(root.resolve("logs/i.log")) should include("MODEL=[claude-opus-5]")
  }

  // ===============================================================================================
  // refuseTestkitOnClasspath: the testkit is a test dependency, and a run says so (GitHub issue #71)
  // ===============================================================================================

  "refuseTestkitOnClasspath" should "refuse when the probed testkit class is reachable" in {
    val refusal = Main.refuseTestkitOnClasspath(_ == Main.TestkitProbeClass)

    val message = refusal.fold(fail("expected a refusal for a reachable testkit"))(_.message)
    // The operator is told the rule by name, which artifact broke it, and what to do about it.
    message should include("test.dep")
    message should include(LitterBox.TestkitCoordinate)
    message should include("main classpath")
  }

  it should "let a run with no testkit in sight continue" in {
    // The false positive is the expensive answer here: a probe that fires on a correctly scoped
    // consumer halts a working loop at startup, which is a worse outcome than the misconfiguration.
    Main.refuseTestkitOnClasspath(_ => false) shouldBe None
  }

  /** The half of the check that no amount of scripting can prove: that the name being probed is a
    * name the testkit really answers to. This repository compiles `test/Recorder.scala` next to
    * `src/`, so its own test JVM is the one place the production probe can be pointed at a real
    * classpath and observed saying yes.
    */
  it should "probe a class name this repository's own testkit really defines" in {
    Class.forName(Main.TestkitProbeClass, false, getClass.getClassLoader) shouldBe classOf[TestWorld]

    Main.liveClassReachable(Main.TestkitProbeClass) shouldBe true
    Main.liveClassReachable("in.rcard.litterbox.NoSuchTypeLivesUnderThisName") shouldBe false
  }

  /** rc 1 rather than rc 50, and pinned here because an operator and an autonomous scheduler both
    * read the exit code before they read the message. A testkit on the main classpath is a build
    * declaration that will say the same thing on every retry, which is what rc 1 already means for
    * a missing `gh` or an unrunnable gate command; rc 50 promises the opposite, an environment that
    * may well be fixed by the time the next tick starts, and a scheduler acting on that promise
    * loops on this forever.
    */
  it should "refuse with the broken install exit code, never the retryable infra one" in {
    val refusal = Main
      .refuseTestkitOnClasspath(_ => true)
      .getOrElse(fail("expected a refusal for a reachable testkit"))

    refusal.rc shouldBe 1
    refusal.rc should not be LoopExit.InfraFault.rc
  }

  /** `refusal.rc` is only behaviour if `dispatch` reads it, and `dispatch`'s Loop branch cannot be
    * driven directly here, since the branch under test ends in `sys.exit`, which would take this
    * suite's own JVM down with it. That rules out proving the wiring by calling it, so this pins the
    * wiring the same way `TestkitPublishSpec` pins `scripts/publish-testkit.sh`'s call sites: by
    * reading the source `dispatch` actually compiles from and asserting on the exact call, so a
    * change back to a hardcoded rc (`die(refusal.message)` or a stray `die50(refusal.message)`)
    * fails the build instead of leaving `StartupRefusal.rc` a field nothing consults.
    *
    * The `die` call alone is not enough to pin: it reads the same whether `dispatch` feeds it from
    * `refuseTestkitOnClasspath(liveClassReachable)` or from a lookup rewritten to `_ => false`, so a
    * regression that silences the check in production leaves this same substring standing. The
    * assertion below therefore covers the whole wired expression, the call together with the case
    * that consumes it, so the live lookup itself is what is pinned, not only what it hands off to.
    *
    * Placement is pinned the same way. `refuseTestkitOnClasspath` reads identically whether it sits
    * in the `Loop` branch or above `Cli.parse` entirely, so a move that starts refusing `init`,
    * `eject`, `watch`, `tail` and `help` too would still satisfy an assertion on the call alone. The
    * ordering check against `Cli.parse(args.toList) match` is what catches that move.
    */
  it should "have dispatch pass the refusal's own rc to die, not a hardcoded one" in {
    val mainSource = RepoTree
      .file("src/Main.scala")
      .getOrElse(fail("could not locate src/Main.scala from the JVM cwd"))

    val source = Files.readString(mainSource)
    val lines  = source.linesIterator.map(_.trim).toIndexedSeq

    val wiredIdx = lines.indexOf("refuseTestkitOnClasspath(liveClassReachable) match")
    wiredIdx should be >= 0
    lines(wiredIdx + 1) shouldBe "case Some(refusal) => die(refusal.message, refusal.rc)"

    val parseIdx = source.indexOf("Cli.parse(args.toList) match")
    parseIdx should be >= 0
    source.indexOf("refuseTestkitOnClasspath(liveClassReachable)") should be > parseIdx
  }
