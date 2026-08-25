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

  /** `Settings.parse` is a `Left` on a model name no `AgentModel` case carries (issue #73), so every
    * case below that is about a config which PARSES unwraps here rather than at each call site. A
    * config that does not parse is a test failure with the message an operator would have seen.
    */
  private def parseOk(conf: com.typesafe.config.Config): Config =
    Settings.parse(conf).fold(msg => fail(s"expected a parseable config, got: $msg"), identity)

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
      |issues.labels { ready = "lbox-ready", active = "lbox-active", blocked = "lbox-blocked", parked = "lbox-parked" }
      |issues.park-on-exhaustion = false
      |protect = ["secrets/**", "Makefile"]
      |budgets { repair = 7, max-patch-bytes = 4242 }
      |timeouts { iter = 60, ci-wait = 61, ci-appear = 62, ci-appear-interval = 63, implement-slack = 64 }
      |""".stripMargin

  private def nonDefaultConfig: Config =
    parseOk(ConfigFactory.parseString(nonDefaultHocon).withFallback(Settings.referenceOnly))

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
  "parseOk(referenceOnly)" should "equal Config()'s own case-class defaults, key for key" in {
    parseOk(Settings.referenceOnly) shouldBe Config()
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

    val cfg = parseOk(partial.withFallback(Settings.referenceOnly))

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
    cfg.labels shouldBe Labels("lbox-ready", "lbox-active", "lbox-blocked", "lbox-parked")
    cfg.parkOnExhaustion shouldBe false
    // The file's own entries first, then the reference floor unioned in (see the `protect` section
    // below for why the floor is not droppable).
    cfg.protect shouldBe List("secrets/**", "Makefile", ".litter-box/**", ".github/**", "CONTEXT.md")
    cfg.repairBudget shouldBe 7
    cfg.maxPatchBytes shouldBe 4242L
    cfg.iterTimeout shouldBe 60
    cfg.ciWaitTimeout shouldBe 61
    cfg.ciAppearTimeout shouldBe 62
    cfg.ciAppearInterval shouldBe 63
    cfg.implementSlack shouldBe 64
  }

  // ===============================================================================================
  // 2b. agent.model: three independently optional keys
  // ===============================================================================================

  /** Why the three model keys are asserted one at a time rather than as a block: a consumer picking
    * a cheap fixer while leaving the implementer and the reviewer on whatever the CLI defaults to is
    * the shape issue #73 exists for, and an all-or-nothing block would silently unset the other two
    * the moment the file mentions the block at all. That is the same trap `protect` documents, one
    * level down.
    */
  "a config file naming one agent model" should "leave the other two roles unset" in {
    val partial = ConfigFactory.parseString(
      """agent.model.fix = "haiku"
        |""".stripMargin
    )

    val cfg = parseOk(partial.withFallback(Settings.referenceOnly))

    cfg.models shouldBe AgentModels(fix = Some(ClaudeModel.Haiku))
  }

  it should "read all three when the file names all three" in {
    val partial = ConfigFactory.parseString(
      """agent.model { impl = "opus", fix = "haiku", review = "sonnet" }
        |""".stripMargin
    )

    val cfg = parseOk(partial.withFallback(Settings.referenceOnly))

    cfg.models shouldBe AgentModels(
      impl = Some(ClaudeModel.Opus),
      fix = Some(ClaudeModel.Haiku),
      review = Some(ClaudeModel.Sonnet)
    )
  }

  it should "leave every role unset when the file mentions no agent.model key at all" in {
    parseOk(Settings.referenceOnly).models shouldBe AgentModels()
  }

  /** The four names an operator may write, and the ids those names dispatch on. Pinned as a pair
    * because the two answer different questions and both are load bearing: `configName` is the
    * spelling `.litter-box/config.conf` and the README promise, so changing one silently breaks
    * every consumer config that used it, while `id` is what reaches the CLI inside the container,
    * so changing one silently moves what a run costs and what it produces.
    *
    * Every id is a FULL model id rather than a family alias like `opus`, which is the property this
    * asserts and the reason a case exists per family at all: an alias would float to whatever that
    * family's latest release is, so the same commit would dispatch differently from one week to the
    * next with nothing recording the move.
    */
  "ClaudeModel" should "name each family once and dispatch it on a pinned full model id" in {
    ClaudeModel.values.map(m => m.configName -> m.id).toList shouldBe List(
      "haiku"  -> "claude-haiku-4-5",
      "sonnet" -> "claude-sonnet-5",
      "opus"   -> "claude-opus-5",
      "fable"  -> "claude-fable-5"
    )
  }

  /** The spellings have to stay unique across every provider, not just within one enum, because
    * `.litter-box/config.conf` names a model BARE (`fix = "haiku"`) with no provider prefix. The day
    * a second provider's enum joins `AgentModel.values` a collision would make one of the two
    * unreachable through the config file, silently, and this is what says so first.
    */
  it should "spell every model distinctly across every provider in AgentModel.values" in {
    val names = AgentModel.values.map(_.configName)
    names.distinct should have size names.size
  }

  "AgentModel.parse" should "accept every model's own config spelling, whatever its case" in {
    AgentModel.values.foreach { m =>
      AgentModel.parse(m.configName) shouldBe Right(m)
      AgentModel.parse(m.configName.toUpperCase) shouldBe Right(m)
      AgentModel.parse(s"  ${m.configName}  ") shouldBe Right(m)
    }
  }

  /** The message is the whole point of failing rather than defaulting: the operator is told what
    * they may write instead. A bare "unknown model" would leave them guessing at exactly the key,
    * `agent.model.review`, whose mistake nothing downstream can report.
    */
  it should "be a Left naming every valid spelling when the name is not one of them" in {
    val result = AgentModel.parse("opuss")

    result.isLeft shouldBe true
    val msg = result.swap.getOrElse("")
    msg should include("opuss")
    AgentModel.values.foreach(m => msg should include(m.configName))
  }

  /** A typo in the config file stops the run, and the message names WHICH of the three keys carries
    * it: a file may set all three, and "unknown model" alone would send the operator looking at all
    * of them. The alternative to failing is a dispatch on the CLI's default, which on `review` is
    * the one downgrade the loop can never notice (see `AgentModels`).
    */
  "a config file naming a model no case carries" should "be a Left naming the key and the run's options" in {
    val partial = ConfigFactory.parseString("""agent.model.review = "gpt-4"""")

    val result = Settings.parse(partial.withFallback(Settings.referenceOnly))

    result.isLeft shouldBe true
    val msg = result.swap.getOrElse("")
    msg should include("agent.model.review")
    msg should include("gpt-4")
    msg should include("opus")
  }

  /** An override that named nothing must fail rather than fall through to the file's answer. Silently
    * losing looks, from the operator's side, exactly like never being read: they would watch a run
    * they believe is on the model they exported, and the recorded evidence would agree with the file
    * instead. Asserted with the file naming a VALID model for the same role, since that is the case
    * a fall-through would hide.
    */
  "an env var naming a model no case carries" should "be a Left even when the file names a valid one" in {
    val fromFile = ConfigFactory
      .parseString("""agent.model.impl = "opus"""")
      .withFallback(Settings.referenceOnly)

    val result = Main.parseEnv(fromFile, Map("IMPL_MODEL" -> "opuss"))

    result.isLeft shouldBe true
    val msg = result.swap.getOrElse("")
    msg should include("IMPL_MODEL")
    msg should include("opuss")
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

    val cfg = parseOk(Settings.loadFile(root).getOrElse(fail("expected a Right")))

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
    val fromFile = ConfigFactory.parseString(nonDefaultHocon).withFallback(Settings.referenceOnly)

    val parsed = parseEnvOk(
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
    parsed.cfg.implementSlack shouldBe 64
    parsed.cfg.logDir shouldBe "custom/logs"
    parsed.cfg.stopFile shouldBe "HALT.md"
    parsed.cfg.conventions shouldBe "RULES.md"
    parsed.cfg.labels shouldBe Labels("lbox-ready", "lbox-active", "lbox-blocked", "lbox-parked")
    parsed.cfg.parkOnExhaustion shouldBe false

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
    val fromFile = ConfigFactory.parseString(nonDefaultHocon).withFallback(Settings.referenceOnly)

    val parsed = parseEnvOk(fromFile, Map.empty)

    parsed.cfg shouldBe nonDefaultConfig
    parsed.gateOverridden shouldBe false
    parsed.cfg.dryRun shouldBe false
    parsed.cfg.ciWaitCmd shouldBe None
  }

  /** The three model keys are layered one at a time for the same reason the file half is read one at
    * a time: an operator raising the fixer to a stronger model for one run must not silently unset
    * the implementer and the reviewer their config already names. The empty `REVIEW_MODEL` here is
    * the project's existing rule about an exported empty value, not a fourth case: it shadows
    * nothing, exactly as an unset variable does.
    */
  it should "layer an env override over each agent model independently" in {
    val fromFile = ConfigFactory
      .parseString(
        """agent.model { impl = "opus", fix = "sonnet", review = "opus" }
          |""".stripMargin
      )
      .withFallback(Settings.referenceOnly)

    val parsed = parseEnvOk(fromFile, Map("FIX_MODEL" -> "haiku", "REVIEW_MODEL" -> ""))

    parsed.cfg.models shouldBe AgentModels(
      impl = Some(ClaudeModel.Opus),
      fix = Some(ClaudeModel.Haiku),
      review = Some(ClaudeModel.Opus)
    )
  }

  it should "leave a role unset when neither the file nor the environment names a model" in {
    val parsed = parseEnvOk(Settings.referenceOnly, Map("IMPL_MODEL" -> "opus"))

    parsed.cfg.models shouldBe AgentModels(impl = Some(ClaudeModel.Opus))
  }

  // ===============================================================================================
  // 5. the protect list and its floor
  // ===============================================================================================

  /** The protect list is data this file's own parsing produces, so what stays here are the tests
    * whose subject is the LIST: the floor union, and a consumer's entries landing on top of it.
    * Everything whose subject is the MATCH moved to `PatchGuardSpec` with the matcher itself.
    *
    * Asserted through `git apply --numstat` output rather than a list of paths, since that is the
    * exact shape the guard reads ("<added>\t<deleted>\t<path>", see `NumstatRow.parse`).
    */
  private def numstat(paths: String*): String =
    paths.map(p => s"1\t0\t$p").mkString("\n")

  /** The CONFIGURED list is what the guard consults ON TOP of the reference floor: a repo that
    * protects `secrets/` gets `secrets/` too, but does not stop protecting `.github/` by writing the
    * key. This is the patch-guard half of the issue #3 rule.
    */
  "the protect list" should "be consulted on top of the reference one" in {
    val protect = nonDefaultConfig.protect

    PatchGuard.touchesProtected(protect, numstat("secrets/deploy.key")) shouldBe true
    PatchGuard.touchesProtected(protect, numstat("Makefile")) shouldBe true
    PatchGuard.touchesProtected(protect, numstat(".github/workflows/ci.yml")) shouldBe true

    // Still a real list and not "protect everything": a path nobody named stays writable.
    PatchGuard.touchesProtected(protect, numstat("src/Main.scala")) shouldBe false
  }

  /** THE FLOOR, and the reason it exists. HOCON list semantics are REPLACE, not merge, so before
    * `Settings.protectWithFloor` a consumer repo that wrote ANY `protect` list silently dropped the
    * `.litter-box` double-star entry with it. That is not one path among others: it is the one that stops
    * the agent under harness from editing `.litter-box/config.conf`, i.e. from rewriting the guard
    * that is judging its own patch. The list below is a plausible consumer list, naming the repo's
    * own secrets and nothing of the loop's, and the assertion is that it cannot open that door.
    */
  it should "protect .litter-box even when the consumer list omits it entirely" in {
    val cfg = parseOk(
      ConfigFactory
        .parseString("""protect = ["secrets/**"]""")
        .withFallback(Settings.referenceOnly)
    )

    PatchGuard.touchesProtected(cfg.protect, numstat(Settings.ConfigPath)) shouldBe true
    PatchGuard.touchesProtected(cfg.protect, numstat(".litter-box/logs/status.jsonl")) shouldBe true

    // The consumer's own entry still takes effect, so the floor added to the list rather than
    // replacing it in the other direction.
    PatchGuard.touchesProtected(cfg.protect, numstat("secrets/deploy.key")) shouldBe true
  }

  /** A consumer list that repeats a floor entry must not double it: the guard runs every entry
    * against every numstat path, so duplicates are pure cost, and a config that reads back the
    * `.github` entry twice invites someone to "fix" it by dropping the floor.
    */
  it should "not duplicate an entry the consumer already names" in {
    val cfg = parseOk(
      ConfigFactory
        .parseString("""protect = [".github/**", "secrets/**"]""")
        .withFallback(Settings.referenceOnly)
    )

    cfg.protect.distinct shouldBe cfg.protect
    cfg.protect should contain(".litter-box/**")
  }

  // ===============================================================================================
  // 6. Configured labels reach the gh argv
  // ===============================================================================================

  /** A fake `gh` on a throwaway PATH directory, the same FAKEBIN idiom `LiveProcSpec` uses: it logs
    * every call verbatim to `$GH_CALLS` and answers on the CONFIGURED labels only. Answering on
    * `lbox-*` and on nothing else is the point: a `LiveGitHub` that ignored its config's labels and
    * baked in `ready` / `in-progress` / `blocked` would get an empty answer here, so both the
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
         |    elif [[ "$$*" == *"--label lbox-parked"* ]]; then echo "555"
         |    fi ;;
         |  *) : ;;
         |esac
         |""".stripMargin
    )
    (binDir, callsFile)

  private def labelledGh(binDir: Path, root: Path): LiveGitHub =
    LiveGitHub(root, ciAppearCmd = None, mergeCmd = None, extraPath = Some(binDir.toString))(using
      Config(labels = Labels("lbox-ready", "lbox-active", "lbox-blocked", "lbox-parked"))
    )

  "the configured labels" should "be the ones the four gh query methods put on the wire" in {
    val root                = tempRoot()
    val (binDir, callsFile) = setupLabelRecordingGh()
    val gh                  = labelledGh(binDir, root)

    // Crash resume asks for the configured ACTIVE label.
    gh.inProgressIssue() shouldBe Some(111)
    // Queue pickup asks for the configured READY label.
    gh.oldestReadyIssue() shouldBe Some(222)
    // The blocked sweep asks for the configured BLOCKED label.
    gh.openBlockedIssues() shouldBe List(333, 444)
    // The parked probe (issue #28) asks for the configured PARKED label.
    gh.parkedIssues() shouldBe Some(List(555))

    val calls = readString(callsFile)
    calls should include(
      "gh issue list --state open --label lbox-active --json number --jq .[0].number"
    )
    calls should include(
      "gh issue list --state open --label lbox-ready --limit 1000 --json number,createdAt --jq sort_by(.createdAt) | .[0].number"
    )
    calls should include(
      "gh issue list --state open --label lbox-blocked --limit 1000 --json number --jq .[].number"
    )
    calls should include(
      "gh issue list --state open --label lbox-parked --limit 1000 --json number,createdAt --jq sort_by(.createdAt) | .[].number"
    )

    // And the literals the slice was meant to remove never appear on the wire at all.
    calls should not include "--label ready"
    calls should not include "--label in-progress"
    calls should not include "--label blocked"
    calls should not include "--label parked"
  }

  /** `Main` builds its `LiveGitHub` against the same `Config` it gives `Machine`, so the file value
    * has to survive `Settings.parse` unchanged all the way to that constructor. Handing it the
    * parsed config (rather than a `Labels` literal) is what closes the loop from HOCON text to argv.
    */
  it should "arrive at LiveGitHub straight off the parsed config, with no literal in between" in {
    val root                = tempRoot()
    val (binDir, callsFile) = setupLabelRecordingGh()
    val gh                  =
      LiveGitHub(root, ciAppearCmd = None, mergeCmd = None, extraPath = Some(binDir.toString))(using
        nonDefaultConfig
      )

    gh.oldestReadyIssue() shouldBe Some(222)

    readString(callsFile) should include("--label lbox-ready")
  }

  // ===============================================================================================
  // 7. instance-name reaches the docker resource names
  // ===============================================================================================

  "Settings.childEnv" should "carry the configured instance name under LITTER_BOX_INSTANCE" in {
    val root = Path.of("/some/consumer/repo")
    Settings.childEnv(nonDefaultConfig, root) shouldBe Map(
      Settings.InstanceEnvVar -> "other",
      Settings.RepoRootEnvVar -> "/some/consumer/repo"
    )
    Settings.childEnv(Config(), root) shouldBe Map(
      Settings.InstanceEnvVar -> "litter-box",
      Settings.RepoRootEnvVar -> "/some/consumer/repo"
    )
  }

  /** `lib.sh` as the loop will actually run it: extracted out of the artifact (`Sandbox`), not read
    * out of the source tree. Was a walk up from the JVM cwd looking for `sandbox/lib.sh`, which is
    * a directory no install has any more (#9).
    */
  private def libSh(): Path =
    val dir = Files.createTempDirectory("settings-spec-sandbox")
    Sandbox.extract(dir)
    dir.resolve("lib.sh")

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
    val log  = LiveStatusLog(root, "1")(using Config(logDir = "custom/logs"))

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
    // The default location must stay empty, or the handler is reading the reference default rather
    // than the config it was given, and the watcher would be tailing a file nothing writes.
    Files.exists(root.resolve(Config().logDir).resolve("status.jsonl")) shouldBe false
  }

  "LiveHarnessFs" should "read the kill switch off the configured stop-file only" in {
    val root = tempRoot()
    val fs   = LiveHarnessFs(root)(using Config(stopFile = "HALT.md", conventions = "RULES.md"))

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
    val fs = LiveHarnessFs(root)(using Config(stopFile = "HALT.md", conventions = "RULES.md"))

    // Both files exist, so only reading the configured name can produce this answer. Whatever comes
    // back here is spliced into the worker, fixer and reviewer prompts as {{CONVENTIONS}}, which is
    // to say the cold reviewer grades against the same file the worker was told to follow.
    fs.conventions() shouldBe "the house rules\n"
  }

  /** End to end for these three keys: HOCON text in, live handlers out, with `Main`'s own wiring
    * expressions reproduced verbatim — one `Config` in scope, both handlers built off it. If a key
    * were read under the wrong name, or a handler kept reading the reference defaults, the paths
    * below would point somewhere nobody configured.
    */
  it should "receive its paths from the parsed config, the way Main wires them" in {
    val root = tempRoot()
    given cfg: Config = nonDefaultConfig
    Files.write(root.resolve(cfg.conventions), "house rules\n".getBytes(StandardCharsets.UTF_8))
    Files.write(root.resolve(cfg.stopFile), "halt\n".getBytes(StandardCharsets.UTF_8))

    val fs        = LiveHarnessFs(root)
    val statusLog = LiveStatusLog(root, "run-1")

    fs.stopRequested() shouldBe true
    fs.conventions() shouldBe "house rules\n"

    statusLog.append(
      StatusEvent(1, "999", "IMPL", "start", 0, cfg.repairBudget, "", "")
    )
    Files.isRegularFile(root.resolve(cfg.logDir).resolve("status.jsonl")) shouldBe true
  }

  // ===============================================================================================
  // 9. `.litter-box/.env` (GitHub issue #12)
  // ===============================================================================================

  /** Writes a `.litter-box/.env` under a fresh root and returns the root. */
  private def rootWithDotEnv(content: String): Path =
    val root = tempRoot()
    val file = root.resolve(Settings.DotEnvPath)
    Files.createDirectories(file.getParent)
    Files.write(file, content.getBytes(StandardCharsets.UTF_8))
    root

  private def dotEnvOf(root: Path): Map[String, String] =
    Settings.loadDotEnv(root).getOrElse(fail("expected a Right"))

  /** The file `init` tells the operator to create is OPTIONAL, unlike `config.conf`: a repo whose
    * credential is exported in the shell is the normal case and must not be turned into a startup
    * failure by the absence of a file nobody promised to write.
    */
  "Settings.loadDotEnv" should "be an empty map when .litter-box/.env is absent" in {
    dotEnvOf(tempRoot()) shouldBe Map.empty
  }

  /** The whole point of issue #12: an operator who followed `init`'s own next step to the letter has
    * the token in this file and nowhere else.
    */
  it should "read the credential out of the file init tells the operator to create" in {
    val root = rootWithDotEnv("CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat-not-a-real-token\n")

    dotEnvOf(root) shouldBe Map("CLAUDE_CODE_OAUTH_TOKEN" -> "sk-ant-oat-not-a-real-token")
  }

  /** Exactly the shape `resources/scaffold/env.example` produces once filled in: a comment header,
    * blank lines, and the second credential left as an empty assignment. Plus the two tolerances an
    * operator editing a shell-ish file expects, surrounding whitespace and quotes.
    */
  it should "skip comments and blank lines, and tolerate whitespace and matched quotes" in {
    val root = rootWithDotEnv(
      """# Copy to .env and fill in ONE of the two.
        |
        |CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat-not-a-real-token
        |ANTHROPIC_API_KEY=
        |   # an indented comment
        |  SPACED  =   spaced-value
        |QUOTED="double quoted"
        |SINGLE='single quoted'
        |""".stripMargin
    )

    dotEnvOf(root) shouldBe Map(
      "CLAUDE_CODE_OAUTH_TOKEN" -> "sk-ant-oat-not-a-real-token",
      "ANTHROPIC_API_KEY"       -> "",
      "SPACED"                  -> "spaced-value",
      "QUOTED"                  -> "double quoted",
      "SINGLE"                  -> "single quoted"
    )
  }

  /** A junk line must cost the operator that line, never the credential on the line below it: this
    * file is edited by hand, at the exact moment the loop has never run successfully yet, so a parse
    * that gave up wholesale would reproduce the very FATAL issue #12 is about.
    */
  it should "ignore a malformed line rather than losing the rest of the file" in {
    val root = rootWithDotEnv(
      """this line has no equals sign
        |=novalue
        |1BAD_NAME=x
        |CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat-not-a-real-token
        |""".stripMargin
    )

    dotEnvOf(root) shouldBe Map("CLAUDE_CODE_OAUTH_TOKEN" -> "sk-ant-oat-not-a-real-token")
  }

  /** The workaround issue #12 documents is `set -a; source .litter-box/.env`, so operators have been
    * writing this file as something bash sources. A file that sources correctly must load correctly,
    * and an `export` silently swallowed as a malformed key would put the original FATAL back.
    */
  it should "tolerate a leading export, the way the documented `source` workaround does" in {
    val root = rootWithDotEnv("export CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat-not-a-real-token\n")

    dotEnvOf(root) shouldBe Map("CLAUDE_CODE_OAUTH_TOKEN" -> "sk-ant-oat-not-a-real-token")
  }

  // ===============================================================================================
  // 10. The gate.sandboxed migration warning (GitHub issue #17)
  // ===============================================================================================

  /** Writes a `.litter-box/config.conf` under a fresh root and returns the root. */
  private def rootWithConfig(content: String): Path =
    val root = tempRoot()
    val file = root.resolve(Settings.ConfigPath)
    Files.createDirectories(file.getParent)
    Files.write(file, content.getBytes(StandardCharsets.UTF_8))
    root

  /** THE QUESTION `withFallback` CANNOT ANSWER, and the whole reason this reads the file again: the
    * config `loadFile` returns has `gate.sandboxed` set either way, so it cannot tell a repo that
    * asked for a sandboxed gate from one that inherited it by being written before the key existed.
    * That inheritance is silent and it moves the gate off the host and into a container, which is
    * the single most consequential thing about a gate run.
    */
  "Settings.omitsGateSandboxed" should "be true for a config written before the key existed" in {
    val root = rootWithConfig(
      """instance-name = "other"
        |gate { fast = "sbt -Werror compile test", timeout = 900 }
        |""".stripMargin
    )

    Settings.omitsGateSandboxed(root) shouldBe true
    // And the value it inherits really is the container, so the warning is about a flip that
    // happens rather than about one that might.
    val merged = parseOk(Settings.loadFile(root).getOrElse(fail("expected a Right")))
    merged.gateSandboxed shouldBe true
  }

  /** Both ways out of the warning, and they are the two the message names. Saying `true` has to
    * silence it as surely as saying `false`: an operator who read the message and decided the
    * container is what they want must not keep being told about a decision they have made.
    */
  it should "be false once the consumer says so, whichever answer they give" in {
    Settings.omitsGateSandboxed(rootWithConfig("gate.sandboxed = false\n")) shouldBe false
    Settings.omitsGateSandboxed(rootWithConfig("gate.sandboxed = true\n")) shouldBe false
    // The block form the scaffold writes, not just the dotted one, since that is the shape a
    // consumer editing `config.conf` by hand actually has in front of them.
    val block = rootWithConfig("gate {\n  fast = \"true\"\n  sandboxed = false\n}\n")
    Settings.omitsGateSandboxed(block) shouldBe false
  }

  /** A warning must never be the thing that reports a broken install: both of these are already a
    * `Left` out of `loadFile` and an rc 50 out of `Main`, so answering "nothing to warn about" here
    * leaves the real diagnostic as the only one the operator sees.
    */
  it should "be false when there is no readable config to have an opinion about" in {
    Settings.omitsGateSandboxed(tempRoot()) shouldBe false
    Settings.omitsGateSandboxed(rootWithConfig("gate { fast = \n")) shouldBe false
  }

  /** The message is the whole deliverable: a warning that does not say how to opt out is a warning
    * that only tells an operator they have a problem.
    */
  "the gate.sandboxed warning" should "name the key, the file and both ways to answer it" in {
    Settings.GateSandboxedWarning should include(Settings.ConfigPath)
    Settings.GateSandboxedWarning should include("gate.sandboxed")
    Settings.GateSandboxedWarning should include("sandboxed = false")
    Settings.GateSandboxedWarning should include("sandboxed = true")
  }
