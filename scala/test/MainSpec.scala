package harness

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit tests for the pure parts of `Main`: env parsing (Part C) and the driver's rc ->
  * process-exit-code map (Part B). Preflight (PATH scanning against the real host,
  * build-image.sh/start-proxy.sh subprocesses) is deliberately NOT exercised here; those are
  * live-process concerns, out of scope for this spec per the task brief.
  */
class MainSpec extends AnyFlatSpec with Matchers:

  // ===============================================================================================
  // Part C: parseEnv
  // ===============================================================================================

  "parseEnv" should "produce every bash default (loop.sh:100-139) from an empty env map" in {
    val parsed = Main.parseEnv(Map.empty)

    parsed.cfg.dryRun shouldBe false
    parsed.cfg.repairBudget shouldBe 2
    parsed.cfg.maxPatchBytes shouldBe 1_000_000L
    parsed.cfg.gateCmd shouldBe "harness/sandbox/run-fast-gate.sh"
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

    val parsed = Main.parseEnv(env)

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
    val parsed = Main.parseEnv(Map("IMPL_CMD" -> "", "CI_WAIT_CMD" -> ""))

    parsed.implCmd shouldBe None
    parsed.cfg.ciWaitCmd shouldBe None
  }

  it should "flip gateOverridden even when GATE_CMD is set to its own default value" in {
    val parsed = Main.parseEnv(Map("GATE_CMD" -> "harness/sandbox/run-fast-gate.sh"))

    parsed.gateOverridden shouldBe true
    parsed.cfg.gateCmd shouldBe "harness/sandbox/run-fast-gate.sh"
  }

  it should "parse DRY_RUN=1 as true, and treat 0 / absent / any other string as false" in {
    Main.parseEnv(Map("DRY_RUN" -> "1")).cfg.dryRun shouldBe true
    Main.parseEnv(Map("DRY_RUN" -> "0")).cfg.dryRun shouldBe false
    Main.parseEnv(Map.empty).cfg.dryRun shouldBe false
    Main.parseEnv(Map("DRY_RUN" -> "true")).cfg.dryRun shouldBe false
  }

  it should "parse numeric overrides" in {
    val parsed = Main.parseEnv(
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
      LoopExit.Success
    ) shouldBe "iteration 3 done (SUCCESS — auto-merged, or PR -> needs-review)"
    Main.driverLog(
      3,
      LoopExit.NeedsHuman
    ) shouldBe "iteration 3 done (FAIL terminal -> needs-human, PR open for audit)"
    Main.driverLog(3, LoopExit.ManualStop) shouldBe "manual STOP.md — exiting"
    Main.driverLog(3, LoopExit.Idle) shouldBe "no actionable issue — idle, exiting"
    Main.driverLog(3, LoopExit.DryRun) shouldBe "dry run reached its stop point — exiting"
    Main.driverLog(
      3,
      LoopExit.NothingMade
    ) shouldBe "iteration 3 produced nothing — exiting for inspection"
    Main.driverLog(
      3,
      LoopExit.InfraFault
    ) shouldBe "infra fault — exiting for inspection (issue stays in-progress)"
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
  // resolveRepoRoot (loop.sh:102-105's SCRIPT_DIR/REPO_ROOT derivation)
  // ===============================================================================================

  /** Fake filesystem: the marker exists at exactly one absolute path. */
  private def markerAt(root: java.nio.file.Path): java.nio.file.Path => Boolean =
    p => p == root.resolve(Main.RootMarker)

  "resolveRepoRoot" should "return the cwd when the cwd itself is the harness repo root" in {
    val root = java.nio.file.Paths.get("/work/repo")

    Main.resolveRepoRoot(root, markerAt(root)) shouldBe Right(root)
  }

  it should "walk up to the repo root when invoked from a subdirectory, as bash does" in {
    val root = java.nio.file.Paths.get("/work/repo")

    Main.resolveRepoRoot(root.resolve("harness/scala/src"), markerAt(root)) shouldBe Right(root)
  }

  it should "normalise the start path before walking (relative / dot segments)" in {
    val root = java.nio.file.Paths.get("/work/repo")

    Main.resolveRepoRoot(root.resolve("harness/./scala/.."), markerAt(root)) shouldBe Right(root)
  }

  it should "fail loudly instead of silently taking a cwd that is not inside the repo" in {
    val root = java.nio.file.Paths.get("/work/repo")

    val result = Main.resolveRepoRoot(java.nio.file.Paths.get("/somewhere/else"), markerAt(root))

    result.isLeft shouldBe true
    result.left.getOrElse("") should include(Main.RootMarker)
    result.left.getOrElse("") should include("/somewhere/else")
  }

  it should "find the real repo root from this test run's own cwd" in {
    val real = Main.resolveRepoRoot(
      java.nio.file.Paths.get("").toAbsolutePath,
      java.nio.file.Files.isRegularFile(_)
    )

    real.map(r => java.nio.file.Files.isRegularFile(r.resolve(Main.RootMarker))) shouldBe Right(
      true
    )
  }
