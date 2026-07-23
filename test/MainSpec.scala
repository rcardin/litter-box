package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

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

  // ===============================================================================================
  // Part C: parseEnv
  // ===============================================================================================

  "parseEnv" should "produce every bash default (loop.sh:100-139) from an empty env map" in {
    val parsed = Main.parseEnv(Settings.referenceOnly, Map.empty)

    parsed.cfg.dryRun shouldBe false
    parsed.cfg.repairBudget shouldBe 2
    parsed.cfg.maxPatchBytes shouldBe 1_000_000L
    parsed.cfg.gateCmd shouldBe "sbt -Werror compile test"
    parsed.cfg.ciWaitCmd shouldBe None
    parsed.cfg.gateTimeout shouldBe 900
    parsed.cfg.iterTimeout shouldBe 1800
    parsed.cfg.ciWaitTimeout shouldBe 900
    parsed.cfg.ciAppearTimeout shouldBe 300
    parsed.cfg.ciAppearInterval shouldBe 10
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

    val parsed = Main.parseEnv(Settings.referenceOnly, env)

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
    val parsed = Main.parseEnv(Settings.referenceOnly, Map("IMPL_CMD" -> "", "CI_WAIT_CMD" -> ""))

    parsed.implCmd shouldBe None
    parsed.cfg.ciWaitCmd shouldBe None
  }

  it should "flip gateOverridden even when GATE_CMD is set to its own default value" in {
    val parsed =
      Main.parseEnv(Settings.referenceOnly, Map("GATE_CMD" -> "sbt -Werror compile test"))

    parsed.gateOverridden shouldBe true
    parsed.cfg.gateCmd shouldBe "sbt -Werror compile test"
  }

  it should "run a GATE_CMD override on the host, whatever gate.sandboxed says" in {
    // The override already skips the sandbox preflight (Main step 6b), so the image the command
    // would run in is never built. Honouring `sandboxed = true` here would hand the operator's
    // command to a container that does not exist.
    Main.parseEnv(Settings.referenceOnly, Map.empty).cfg.gateSandboxed shouldBe true
    Main
      .parseEnv(Settings.referenceOnly, Map("GATE_CMD" -> "true"))
      .cfg
      .gateSandboxed shouldBe false
  }

  it should "parse DRY_RUN=1 as true, and treat 0 / absent / any other string as false" in {
    Main.parseEnv(Settings.referenceOnly, Map("DRY_RUN" -> "1")).cfg.dryRun shouldBe true
    Main.parseEnv(Settings.referenceOnly, Map("DRY_RUN" -> "0")).cfg.dryRun shouldBe false
    Main.parseEnv(Settings.referenceOnly, Map.empty).cfg.dryRun shouldBe false
    Main.parseEnv(Settings.referenceOnly, Map("DRY_RUN" -> "true")).cfg.dryRun shouldBe false
  }

  it should "parse numeric overrides" in {
    val parsed = Main.parseEnv(Settings.referenceOnly, 
      Map(
        "MAX_ITERS"          -> "5",
        "ITER_TIMEOUT"       -> "60",
        "GATE_TIMEOUT"       -> "61",
        "REPAIR_BUDGET"      -> "3",
        "MAX_PATCH_BYTES"    -> "2000",
        "CI_WAIT_TIMEOUT"    -> "62",
        "CI_APPEAR_TIMEOUT"  -> "63",
        "CI_APPEAR_INTERVAL" -> "5"
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

  it should "resolve this checkout's own root by really shelling out to git" in {
    val real = Main.resolveRepoRoot(() =>
      LiveProc.run(
        java.nio.file.Paths.get("").toAbsolutePath,
        Seq("git", "rev-parse", "--show-toplevel")
      )
    )

    // Two environments, one real subprocess, an assertion for each. The sandboxed gate runs this
    // suite against a `git archive` extraction with NO `.git` in it by construction (#9), so there
    // the contract under test is the failure one: say so, rather than quietly falling back to the
    // cwd and letting the loop write into whatever directory it was launched from.
    if Files.isDirectory(Path.of(".git")) then
      real.map(r =>
        java.nio.file.Files.isRegularFile(r.resolve(Settings.ConfigPath))
      ) shouldBe Right(true)
    else real.isLeft shouldBe true
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
