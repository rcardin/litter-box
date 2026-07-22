package in.rcard.litterbox

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}

/** Unit tests for `Settings` and for the wiring that carries a config value all the way to the
  * thing it is supposed to control.
  *
  * THE RULE THIS SPEC IS WRITTEN TO (GitHub issue #3): a config with NON-DEFAULT values must
  * demonstrably drive the `gh` argv, the docker resource names and the patch guard. A test that only
  * exercises defaults proves nothing, because every default is ALSO the hardcoded literal the slice
  * was supposed to remove: a handler that ignored its constructor parameter entirely and kept
  * reading `Config()` would pass such a test. So every assertion below that concerns a wired value
  * uses a value that appears nowhere in `Settings.Reference`: the instance name `other`, the labels
  * `lbox-ready` / `lbox-active` / `lbox-blocked`, a `protect` list of its own, `custom/logs`,
  * `HALT.md` and `RULES.md`.
  */
class SettingsSpec extends AnyFlatSpec with Matchers:

  private def tempRoot(): Path = Files.createTempDirectory("settings-spec")

  private def readString(p: Path): String =
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /** Writes an executable script (any shebang line included in `content`) and returns its path. The
    * same helper `LiveProcSpec` uses; the fake-binary-on-PATH fixture below depends on it.
    */
  private def writeExecutable(dir: Path, name: String, content: String): Path =
    val p = dir.resolve(name)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"))
    p

  /** The HOCON a consumer repo that changed EVERY wired knob would ship. Deliberately not a
    * `Config` literal: the production path is text on disk parsed by typesafe config, and a test
    * that built the case class directly would skip the very step (key name spelled right, type read
    * right) most likely to be wrong.
    */
  private val nonDefaultHocon: String =
    """instance-name = "other"
      |conventions   = "RULES.md"
      |stop-file     = "HALT.md"
      |log-dir       = "custom/logs"
      |gate { fast = "mill __.compile", timeout = 111 }
      |ci { required-check = "verify" }
      |issues.labels { ready = "lbox-ready", active = "lbox-active", blocked = "lbox-blocked" }
      |protect = ["secrets/**", "Makefile"]
      |budgets { repair = 7, max-patch-bytes = 4242 }
      |timeouts { iter = 60, ci-wait = 61, ci-appear = 62, ci-appear-interval = 63 }
      |""".stripMargin

  private def nonDefaultConfig: Config =
    Settings.parse(Settings.onReference(ConfigFactory.parseString(nonDefaultHocon)))

  // ===============================================================================================
  // 1. Reference text vs case-class defaults
  // ===============================================================================================

  /** THE PIN that keeps `Settings.Reference` (HOCON text) and `Config`'s case-class defaults from
    * drifting apart. Both exist for a reason: the text doubles as the file `litter-box init` writes
    * and as the schema documentation, the case class is what every handler defaults to when a test
    * constructs one by hand. Nothing in the compiler relates them, so the day someone bumps
    * `budgets.repair` in the reference and forgets the case class, production (which always goes
    * through the file) and every hand-built test fixture would disagree about the same knob. This
    * assertion is the only thing that notices.
    */
  "Settings.parse(referenceOnly)" should "equal Config()'s own case-class defaults, key for key" in {
    Settings.parse(Settings.referenceOnly) shouldBe Config()
  }

  // ===============================================================================================
  // 2. Partial file merged onto the reference
  // ===============================================================================================

  /** A consumer repo's config should be as short as the things it actually changes. That only holds
    * if a file setting two keys still parses into a TOTAL `Config`, with the untouched keys coming
    * off the reference rather than off whatever typesafe config does with a missing path (which is
    * to throw). Both halves are asserted: the set keys moved, the unset ones did not.
    */
  "a partial config file" should "override only the keys it sets and inherit the rest from the reference" in {
    val partial = ConfigFactory.parseString(
      """log-dir = "build/loop-logs"
        |budgets.repair = 5
        |""".stripMargin
    )

    val cfg = Settings.parse(Settings.onReference(partial))

    cfg.logDir shouldBe "build/loop-logs"
    cfg.repairBudget shouldBe 5

    // Untouched, so still exactly the reference values.
    cfg.instanceName shouldBe "litter-box"
    cfg.stopFile shouldBe "STOP.md"
    cfg.conventions shouldBe "CONTEXT.md"
    cfg.labels shouldBe Labels("ready", "in-progress", "blocked")
    cfg.maxPatchBytes shouldBe 1_000_000L
    cfg.iterTimeout shouldBe 1800
  }

  it should "read every non-default key off the file, not off the reference" in {
    val cfg = nonDefaultConfig

    cfg.instanceName shouldBe "other"
    cfg.conventions shouldBe "RULES.md"
    cfg.stopFile shouldBe "HALT.md"
    cfg.logDir shouldBe "custom/logs"
    cfg.gateCmd shouldBe "mill __.compile"
    cfg.gateTimeout shouldBe 111
    cfg.requiredCheck shouldBe "verify"
    cfg.labels shouldBe Labels("lbox-ready", "lbox-active", "lbox-blocked")
    cfg.protect shouldBe List("secrets/**", "Makefile")
    cfg.repairBudget shouldBe 7
    cfg.maxPatchBytes shouldBe 4242L
    cfg.iterTimeout shouldBe 60
    cfg.ciWaitTimeout shouldBe 61
    cfg.ciAppearTimeout shouldBe 62
    cfg.ciAppearInterval shouldBe 63
  }

  // ===============================================================================================
  // 3. Missing config file
  // ===============================================================================================

  /** A repo nobody ran `litter-box init` in must NOT default its way into a run. The loop's whole
    * job is to act on the repo it was pointed at, and acting on the wrong labels or writing logs to
    * the wrong place is strictly worse than refusing to start, so the missing file is an error
    * value carrying an actionable instruction rather than a silent fallback.
    *
    * WHY THE rc 50 ITSELF IS NOT EXERCISED HERE: the exit code lives in `Main.die50`, which calls
    * `sys.exit(LoopExit.InfraFault.rc)` and therefore kills the test JVM if invoked. What is
    * testable without forking is the value `die50` is fed, which is this `Left`. The rc constant
    * itself is pinned by `LoopExit.InfraFault.rc` in `Domain.scala` and by `Main.driverAction`'s own
    * tests.
    */
  "Settings.loadFile" should "be a Left naming `litter-box init` when the repo has no config file" in {
    val root = tempRoot() // empty: no .litter-box/ at all

    val result = Settings.loadFile(root)

    result.isLeft shouldBe true
    val msg = result.swap.getOrElse("")
    msg should include("litter-box init")
    msg should include(Settings.ConfigPath)
  }

  it should "be a Right merged onto the reference when the file exists" in {
    val root = tempRoot()
    val file = root.resolve(Settings.ConfigPath)
    Files.createDirectories(file.getParent)
    Files.write(file, """instance-name = "other"""".getBytes(StandardCharsets.UTF_8))

    val cfg = Settings.parse(Settings.loadFile(root).getOrElse(fail("expected a Right")))

    cfg.instanceName shouldBe "other"
    cfg.stopFile shouldBe "STOP.md" // came off the reference, so the merge really happened
  }

  // ===============================================================================================
  // 4. Env overlay on top of the file
  // ===============================================================================================

  /** The documented layering is env var > config file > reference. An operator overriding one knob
    * for one run must not have to edit a tracked file, and must not lose the file's answer for every
    * OTHER knob while doing it. Both directions are asserted from the same call: the three keys with
    * an env var take the env value, and a key with no env var keeps the file value.
    */
  "Main.parseEnv" should "let an env var win over the config file, key by key" in {
    val fromFile = Settings.onReference(ConfigFactory.parseString(nonDefaultHocon))

    val parsed = Main.parseEnv(
      fromFile,
      Map(
        "GATE_CMD"      -> "sbt scalafmtCheckAll",
        "REPAIR_BUDGET" -> "9",
        "ITER_TIMEOUT"  -> "4242"
      )
    )

    parsed.cfg.gateCmd shouldBe "sbt scalafmtCheckAll"
    parsed.cfg.repairBudget shouldBe 9
    parsed.cfg.iterTimeout shouldBe 4242

    // No env var for these, so the FILE is still the answer, not the reference and not a literal
    // baked into parseEnv.
    parsed.cfg.gateTimeout shouldBe 111
    parsed.cfg.ciWaitTimeout shouldBe 61
    parsed.cfg.logDir shouldBe "custom/logs"
    parsed.cfg.stopFile shouldBe "HALT.md"
    parsed.cfg.conventions shouldBe "RULES.md"
    parsed.cfg.labels shouldBe Labels("lbox-ready", "lbox-active", "lbox-blocked")

    // GATE_CMD set by the operator is what "overridden" means, and it turns the sandbox preflight
    // off; a `gate.fast` in the file is the repo's normal gate and must never do that.
    parsed.gateOverridden shouldBe true
  }

  /** The regression this guards is the one `parseEnv` used to have by construction: helpers that
    * defaulted to a LITERAL instead of to the config-derived value silently threw the file away for
    * every key the operator did not set. With an empty env map the parsed config must be
    * indistinguishable from the file's own parse.
    */
  it should "leave every file value untouched when the env map is empty" in {
    val fromFile = Settings.onReference(ConfigFactory.parseString(nonDefaultHocon))

    val parsed = Main.parseEnv(fromFile, Map.empty)

    parsed.cfg shouldBe nonDefaultConfig
    parsed.gateOverridden shouldBe false
    parsed.cfg.dryRun shouldBe false
    parsed.cfg.ciWaitCmd shouldBe None
  }

  // ===============================================================================================
  // 5. protect globs and the patch guard
  // ===============================================================================================

  /** `Machine.touchesProtected` is the patch guard's whole decision, and it is reached through
    * `git apply --numstat` output rather than a list of paths, so the glob semantics are asserted
    * through that exact shape ("<added>\t<deleted>\t<path>", see `NumstatRow.parse`) instead of
    * through `Settings.isProtected` alone. Anything that broke the numstat parsing would otherwise
    * leave the guard waving patches through with a green glob test.
    */
  private def numstat(paths: String*): String =
    paths.map(p => s"1\t0\t$p").mkString("\n")

  "the protect guard" should "match a double-star entry across directory levels" in {
    // The case issue #3 names explicitly: `.github` followed by `/` and a double star has to cover
    // a workflow file nested two levels down, or the agent can rewrite CI and grade its own work.
    Machine.touchesProtected(
      List(".github/**"),
      numstat(".github/workflows/ci.yml")
    ) shouldBe true
  }

  it should "not let a single-star entry cross a directory separator" in {
    // JDK glob semantics, which the schema's notation already assumes. If a single star crossed
    // separators there would be no way left to write a shallow-only rule.
    Machine.touchesProtected(List("src/*.scala"), numstat("src/Main.scala")) shouldBe true
    Machine.touchesProtected(List("src/*.scala"), numstat("src/a/B.scala")) shouldBe false
  }

  it should "treat a bare filename entry as an exact match, not a suffix match" in {
    Machine.touchesProtected(List("CONTEXT.md"), numstat("CONTEXT.md")) shouldBe true
    Machine.touchesProtected(List("CONTEXT.md"), numstat("docs/CONTEXT.md")) shouldBe false
  }

  it should "report a path covered by nothing in the list as unprotected" in {
    Machine.touchesProtected(
      List(".litter-box/**", ".github/**", "CONTEXT.md"),
      numstat("src/Main.scala")
    ) shouldBe false
  }

  /** The CONFIGURED list is what the guard consults, so a repo that protects `secrets/` and nothing
    * else must see exactly that, and must NOT still be protecting `.github/` out of habit. This is
    * the patch-guard half of the issue #3 rule.
    */
  it should "consult the configured protect list rather than the reference one" in {
    val protect = nonDefaultConfig.protect

    Machine.touchesProtected(protect, numstat("secrets/deploy.key")) shouldBe true
    Machine.touchesProtected(protect, numstat("Makefile")) shouldBe true
    Machine.touchesProtected(protect, numstat(".github/workflows/ci.yml")) shouldBe false
  }

  it should "reject a multi-row numstat as soon as ONE row is protected" in {
    Machine.touchesProtected(
      List(".github/**"),
      numstat("src/Main.scala", ".github/workflows/ci.yml", "README.md")
    ) shouldBe true
  }

  /** Totality on junk input, and the reason it is not a throw: the caller is the patch guard, and an
    * exception there aborts the whole iteration (an infra fault, budget untouched, issue stuck)
    * instead of doing the one thing the guard exists to do, which is to reject the patch. Reporting
    * "not protected" cannot widen the hole either, because `git apply --index` still has to accept
    * the same unparseable path afterwards, and it will not.
    */
  "Settings.isProtected" should "be total on a path java.nio refuses to parse" in {
    // An embedded NUL byte is the portable way to make java.nio refuse outright: `Path.of` throws
    // InvalidPathException instead of returning anything. Built from a char rather than written as
    // a literal so the file stays plain text.
    val unparseable = "src/" + 0.toChar + "broken.scala"

    noException should be thrownBy {
      Settings.isProtected(List("**"), unparseable) shouldBe false
    }
    Settings.isProtected(List("**"), unparseable) shouldBe false

    // Sanity, so the assertion above is about the parse failure and not about a glob that happens
    // to match nothing: the very same glob does cover a path that parses.
    Settings.isProtected(List("**"), "src/Main.scala") shouldBe true
  }

  // ===============================================================================================
  // 6. Configured labels reach the gh argv
  // ===============================================================================================

  /** A fake `gh` on a throwaway PATH directory, the same FAKEBIN idiom `LiveProcSpec` uses: it logs
    * every call verbatim to `$GH_CALLS` and answers on the CONFIGURED labels only. Answering on
    * `lbox-*` and on nothing else is the point: a `LiveGitHub` that ignored its `labels` parameter
    * and baked in `ready` / `in-progress` / `blocked` would get an empty answer here, so both the
    * recorded argv AND the parsed return value catch the regression.
    */
  private def setupLabelRecordingGh(): (Path, Path) =
    val binDir    = Files.createTempDirectory("fake-gh-labels-bin")
    val callsFile = Files.createTempFile("gh-label-calls", ".log")
    writeExecutable(
      binDir,
      "gh",
      s"""#!/usr/bin/env bash
         |echo "gh $$*" >> "$callsFile"
         |case "$$1 $$2" in
         |  "issue list")
         |    if [[ "$$*" == *"--label lbox-active"* ]]; then echo "111"
         |    elif [[ "$$*" == *"--label lbox-ready"* ]]; then echo "222"
         |    elif [[ "$$*" == *"--label lbox-blocked"* ]]; then printf '333\\n444\\n'
         |    fi ;;
         |  *) : ;;
         |esac
         |""".stripMargin
    )
    (binDir, callsFile)

  private def labelledGh(binDir: Path, root: Path): LiveGitHub =
    LiveGitHub(
      root,
      ciAppearCmd = None,
      mergeCmd = None,
      extraPath = Some(binDir.toString),
      labels = Labels("lbox-ready", "lbox-active", "lbox-blocked")
    )

  "the configured labels" should "be the ones the three gh query methods put on the wire" in {
    val root                = tempRoot()
    val (binDir, callsFile) = setupLabelRecordingGh()
    val gh                  = labelledGh(binDir, root)

    // Crash resume asks for the configured ACTIVE label.
    gh.inProgressIssue() shouldBe Some(111)
    // Queue pickup asks for the configured READY label.
    gh.oldestReadyIssue() shouldBe Some(222)
    // The blocked sweep asks for the configured BLOCKED label.
    gh.openBlockedIssues() shouldBe List(333, 444)

    val calls = readString(callsFile)
    calls should include(
      "gh issue list --state open --label lbox-active --json number --jq .[0].number"
    )
    calls should include(
      "gh issue list --state open --label lbox-ready --json number,createdAt --jq sort_by(.createdAt) | .[0].number"
    )
    calls should include(
      "gh issue list --state open --label lbox-blocked --json number --jq .[].number"
    )

    // And the literals the slice was meant to remove never appear on the wire at all.
    calls should not include "--label ready"
    calls should not include "--label in-progress"
    calls should not include "--label blocked"
  }

  /** `Main` builds its `LiveGitHub` with `labels = parsed.cfg.labels`, so the file value has to
    * survive `Settings.parse` unchanged all the way to that constructor. Passing the parsed config's
    * own labels (rather than a `Labels` literal) is what closes the loop from HOCON text to argv.
    */
  it should "arrive at LiveGitHub straight off the parsed config, with no literal in between" in {
    val root                = tempRoot()
    val (binDir, callsFile) = setupLabelRecordingGh()
    val gh                  = LiveGitHub(
      root,
      ciAppearCmd = None,
      mergeCmd = None,
      extraPath = Some(binDir.toString),
      labels = nonDefaultConfig.labels
    )

    gh.oldestReadyIssue() shouldBe Some(222)

    readString(callsFile) should include("--label lbox-ready")
  }

  // ===============================================================================================
  // 7. instance-name reaches the docker resource names
  // ===============================================================================================

  "Settings.childEnv" should "carry the configured instance name under LITTER_BOX_INSTANCE" in {
    Settings.childEnv(nonDefaultConfig) shouldBe Map(Settings.InstanceEnvVar -> "other")
    Settings.childEnv(Config()) shouldBe Map(Settings.InstanceEnvVar -> "litter-box")
  }

  /** Finds `sandbox/lib.sh` by walking up from the JVM cwd, so the test does not care whether the
    * runner starts in the project root or in a subdirectory.
    */
  private def libSh(): Path =
    var dir: Path = Path.of("").toAbsolutePath.normalize
    var found     = Option.empty[Path]
    while found.isEmpty && dir != null do
      val candidate = dir.resolve(Machine.SandboxDir).resolve("lib.sh")
      if Files.isRegularFile(candidate) then found = Some(candidate)
      dir = dir.getParent
    found.getOrElse(fail("could not locate sandbox/lib.sh from the JVM cwd"))

  /** Sources `sandbox/lib.sh` in a bash child and prints the five derived docker identifiers.
    *
    * Sourcing the REAL script rather than reasserting the naming scheme in Scala is the entire point
    * of this test: the derivation lives in bash, nothing on the Scala side can typecheck it, and a
    * rename there would otherwise be caught only by a docker-level failure at runtime. `instance` of
    * `None` means the variable is UNSET in the child, which is the state an operator running the
    * sandbox scripts by hand is in.
    *
    * Needs bash but NOT docker: lib.sh only assigns variables and defines functions when sourced, so
    * this is safe on any CI box.
    */
  private def sandboxNames(instance: Option[String]): Map[String, String] =
    val script =
      """set -eu
        |source "$1"
        |printf 'IMAGE=%s\n' "$IMAGE"
        |printf 'PROXY_IMAGE=%s\n' "$PROXY_IMAGE"
        |printf 'NETWORK=%s\n' "$NETWORK"
        |printf 'PROXY_NAME=%s\n' "$PROXY_NAME"
        |printf 'COURSIER_VOLUME=%s\n' "$COURSIER_VOLUME"
        |""".stripMargin
    val pb = new ProcessBuilder("bash", "-c", script, "bash", libSh().toString)
    pb.redirectErrorStream(true)
    instance match
      case Some(v) => pb.environment().put(Settings.InstanceEnvVar, v)
      case None    => pb.environment().remove(Settings.InstanceEnvVar)
    val proc = pb.start()
    val out  = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    proc.waitFor() shouldBe 0
    out.linesIterator
      .flatMap(_.split("=", 2) match
        case Array(k, v) => Some(k -> v)
        case _           => None
      )
      .toMap

  "sandbox/lib.sh" should "namespace every docker identifier by LITTER_BOX_INSTANCE" in {
    // The failure this prevents is not cosmetic: start-proxy.sh does `docker rm -f "$PROXY_NAME"`
    // before any issue label is consulted, so machine-global names let a second launch tear down a
    // running instance's proxy mid iteration, which no amount of label discipline can prevent.
    val names = sandboxNames(Some("other"))

    names("IMAGE") shouldBe "other-sandbox:v6"
    names("PROXY_IMAGE") shouldBe "other-sandbox-proxy:v6"
    names("NETWORK") shouldBe "other-net"
    names("PROXY_NAME") shouldBe "other-proxy"
    names("COURSIER_VOLUME") shouldBe "other-coursier-cache"

    // No leftover global name anywhere: a partially converted lib.sh would still pass the five
    // assertions above if it kept, say, NETWORK hardcoded, but not this one.
    names.values.foreach(_ should not include "litter-box")
  }

  it should "keep the litter-box defaults when the variable is unset, so hand runs are unaffected" in {
    val names = sandboxNames(None)

    names("IMAGE") shouldBe "litter-box-sandbox:v6"
    names("PROXY_IMAGE") shouldBe "litter-box-sandbox-proxy:v6"
    names("NETWORK") shouldBe "litter-box-net"
    names("PROXY_NAME") shouldBe "litter-box-proxy"
    names("COURSIER_VOLUME") shouldBe "litter-box-coursier-cache"
  }

  /** The bash fallback and the reference config have to agree, or `build-image.sh` run by hand
    * builds an image the loop will never look for.
    */
  it should "fall back to exactly the reference config's instance-name" in {
    sandboxNames(None)("NETWORK") shouldBe s"${Config().instanceName}-net"
  }

  // ===============================================================================================
  // 8. log-dir / stop-file / conventions reach the live handlers
  // ===============================================================================================

  "LiveStatusLog" should "write status.jsonl under the configured log-dir, not under the default one" in {
    val root = tempRoot()
    val log  = LiveStatusLog(root, "1", "custom/logs")

    log.append(
      StatusEvent(
        iter = 1,
        issue = "999",
        phase = "IMPL",
        state = "start",
        pass = 0,
        budget = 2,
        logfile = "custom/logs/issue-999.log",
        detail = ""
      )
    )

    val written = root.resolve("custom/logs/status.jsonl")
    Files.isRegularFile(written) shouldBe true
    readString(written) should include("\"phase\":\"IMPL\"")
    // The default location must stay empty, or the handler is reading Config() rather than its
    // constructor parameter and the watcher would be tailing a file nothing writes.
    Files.exists(root.resolve(Config().logDir).resolve("status.jsonl")) shouldBe false
  }

  "LiveHarnessFs" should "read the kill switch off the configured stop-file only" in {
    val root = tempRoot()
    val fs   = LiveHarnessFs(root, stopFile = "HALT.md", conventionsFile = "RULES.md")

    fs.stopRequested() shouldBe false

    // The DEFAULT name must not trip it: a consumer repo that already means something else by
    // STOP.md would otherwise have its loop refuse to start for no reason.
    Files.write(root.resolve("STOP.md"), "not the switch\n".getBytes(StandardCharsets.UTF_8))
    fs.stopRequested() shouldBe false

    Files.write(root.resolve("HALT.md"), "stop please\n".getBytes(StandardCharsets.UTF_8))
    fs.stopRequested() shouldBe true
  }

  it should "read conventions out of the configured file" in {
    val root = tempRoot()
    Files.write(root.resolve("RULES.md"), "the house rules\n".getBytes(StandardCharsets.UTF_8))
    Files.write(root.resolve("CONTEXT.md"), "the default file\n".getBytes(StandardCharsets.UTF_8))
    val fs = LiveHarnessFs(root, stopFile = "HALT.md", conventionsFile = "RULES.md")

    // Both files exist, so only reading the configured name can produce this answer. Whatever comes
    // back here is spliced into the reviewer prompt as {{CONVENTIONS}}, which is to say the cold
    // reviewer grades against it.
    fs.conventions() shouldBe "the house rules\n"
  }

  /** End to end for these three keys: HOCON text in, live handlers out, with `Main`'s own wiring
    * expressions reproduced verbatim. If a key were read under the wrong name, or a handler kept
    * defaulting to `Config()`, the paths below would point somewhere nobody configured.
    */
  it should "receive its paths from the parsed config, the way Main wires them" in {
    val root = tempRoot()
    val cfg  = nonDefaultConfig
    Files.write(root.resolve(cfg.conventions), "house rules\n".getBytes(StandardCharsets.UTF_8))
    Files.write(root.resolve(cfg.stopFile), "halt\n".getBytes(StandardCharsets.UTF_8))

    val fs        = LiveHarnessFs(root, cfg.stopFile, cfg.conventions)
    val statusLog = LiveStatusLog(root, "run-1", cfg.logDir)

    fs.stopRequested() shouldBe true
    fs.conventions() shouldBe "house rules\n"

    statusLog.append(
      StatusEvent(1, "999", "IMPL", "start", 0, cfg.repairBudget, "", "")
    )
    Files.isRegularFile(root.resolve(cfg.logDir).resolve("status.jsonl")) shouldBe true
  }
