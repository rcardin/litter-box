package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Unit tests for the scaffolder.
  *
  * THE RULE THIS SPEC IS WRITTEN TO (GitHub issue #4): what `init` writes must be something the
  * slice-2 config loader accepts, and a repo whose build tool we do not recognise must get a
  * visible TODO rather than a preset we have never run a loop against. So the two load-bearing
  * assertions below are "Settings.loadFile accepts the output" and "a non-sbt repo gets no sbt".
  *
  * ISSUE #13 GENERALISED THAT RULE TO EVERY REPO. `init` used to abandon it exactly where it
  * thought it knew the answer: a `build.sbt` bought an install block and a `gate.fast` of
  * `sbt -Werror compile test`, which sbt rejects outright, while the detected JDK was warned about
  * and then dropped on the floor by the Dockerfile. So the fixtures below are exercised as a LIST
  * rather than one at a time wherever the assertion is about the skeleton: any detection result
  * that produces an install instruction or a runnable-looking gate command is the bug back.
  */
class InitSpec extends AnyFlatSpec with Matchers:

  private def tempRoot(): Path = Files.createTempDirectory("init-spec")

  private def readString(p: Path): String =
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /** An `exec` seam that answers only the `java -version` probe, on stderr where the real tool
    * writes it, and fails everything else so a test asserting on the JDK cannot pass by reading
    * the `gh` result instead.
    */
  private def javaBanner(banner: String): Seq[String] => LiveProc.Result =
    case Seq("java", _*) => LiveProc.Result(0, "", banner)
    case _               => LiveProc.Result(1, "", "")

  /** Every file under `root`, as repo-relative path strings, sorted. Used to assert that a refused
    * `init` changed literally nothing.
    */
  private def tree(root: Path): List[String] =
    if !Files.exists(root) then Nil
    else
      Files
        .walk(root)
        .iterator
        .asScala
        .filter(Files.isRegularFile(_))
        .map(p => root.relativize(p).toString + ":" + readString(p).hashCode)
        .toList
        .sorted

  /** tinyproxy's filter reader ends a line at the first whitespace or unescaped `#` and skips
    * whatever is left empty, so a full-line comment contributes nothing and everything else is a
    * host pattern.
    */
  private def allowlistEntries(text: String): List[String] =
    text.linesIterator.map(_.strip).filterNot(l => l.isEmpty || l.startsWith("#")).toList

  private val sbtRepo   = Init.Detected(Some(Init.BuildTool.Sbt), Some("rcardin/x"), Some("21"))
  private val plainRepo = Init.Detected(None, None, Some("21"))

  /** The shape #13 was found against: an sbt repo that builds under a JDK the base image does not
    * ship, plus one where `java -version` answered nothing at all. Every skeleton assertion runs
    * over all four, because the defect was precisely that one detection result took a different
    * path from the rest.
    */
  private val jdk25Repo = Init.Detected(Some(Init.BuildTool.Sbt), Some("rcardin/x"), Some("25"))
  private val noJdkRepo = Init.Detected(None, Some("rcardin/x"), None)

  private val everyRepo = List(sbtRepo, plainRepo, jdk25Repo, noJdkRepo)

  /** The scaffold as `plan` returns it, without a filesystem. Keyed by repo-relative path, which is
    * what `plan` promises, so a renamed file breaks the lookup rather than silently asserting on
    * nothing.
    */
  private def scaffolded(d: Init.Detected, rel: String): String =
    Init.plan(d).toMap.getOrElse(rel, fail(s"init scaffolded no $rel"))

  /** Handed back raw rather than as entries because one of the allowlist tests is precisely about
    * the comment lines `allowlistEntries` drops.
    */
  private def scaffoldedAllowlist(): String =
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    readString(root.resolve(".litter-box/allowlist"))

  /** The fallback the scaffolded file overrides, and therefore the floor the scaffold is measured
    * against.
    */
  private val builtInAllowlist: String =
    new String(Sandbox.builtIn("proxy/allowlist"), StandardCharsets.UTF_8)

  "plan" should "write every file the slice promises" in:
    val paths = Init.plan(sbtRepo).map(_._1)
    paths should contain allOf (
      ".litter-box/config.conf",
      ".litter-box/Dockerfile",
      ".litter-box/allowlist",
      ".litter-box/prompts/conventions.md",
      ".litter-box/.env.example",
      ".litter-box/.gitignore",
      ".litter-box/loop.scala"
    )

  it should "produce a config the slice-2 loader accepts" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false).isRight shouldBe true
    val loaded = Settings.loadFile(root)
    loaded.isRight shouldBe true
    val cfg = Settings.parse(loaded.toOption.get)
    cfg.conventions shouldBe ".litter-box/prompts/conventions.md"
    cfg.gateCmd shouldBe "false"

  /** A scaffolded repo must never be in the state the `gate.sandboxed` migration warning is about
    * (#17): the warning exists for configs written before the key, and `init` writes it explicitly.
    * This is what notices if someone ever trims the scaffold to "the default is fine", which would
    * hand every freshly scaffolded repo a warning about a decision `init` made for it.
    */
  it should "say gate.sandboxed out loud, so the migration warning never fires on a fresh scaffold" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    Settings.omitsGateSandboxed(root) shouldBe false

  it should "point conventions at a file it actually wrote" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val cfg = Settings.parse(Settings.loadFile(root).toOption.get)
    Files.isRegularFile(root.resolve(cfg.conventions)) shouldBe true

  it should "keep .litter-box protected in the scaffolded config" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val cfg = Settings.parse(Settings.loadFile(root).toOption.get)
    cfg.protect should contain(".litter-box/**")

  it should "gitignore the logs and the env file" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val ignored = readString(root.resolve(".litter-box/.gitignore"))
    ignored should include("logs/")
    ignored should include(".env")

  it should "carry no credential value in the env example" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val env = readString(root.resolve(".litter-box/.env.example"))
    env should include("CLAUDE_CODE_OAUTH_TOKEN=")
    env should include("ANTHROPIC_API_KEY=")
    env should not include "sk-ant"

  it should "scaffold a SANDBOXED gate, not a host one" in:
    // The defect this exists for (issue #9): `init` wrote a host `sbt` command while litter-box's
    // own repo ran its gate inside a container, so every scaffolded consumer silently got a weaker
    // gate tier than the tool ran on itself, and nothing told them the isolation they installed
    // litter-box for did not cover the gate.
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val cfg = Settings.parse(Settings.loadFile(root).toOption.get)
    cfg.gateSandboxed shouldBe true

  it should "write a Dockerfile the sandbox actually builds from" in:
    // The other half of #9: `init` wrote .litter-box/Dockerfile and build-image.sh built
    // litter-box's own sbt-hardcoded sandbox/Dockerfile instead, so this file was read by nothing.
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val dockerfile = readString(root.resolve(".litter-box/Dockerfile"))
    dockerfile should include("FROM ${BASE_IMAGE}")
    // No ENTRYPOINT instruction (the comments explain its absence, hence the line-level check):
    // all three runners override it, and the sbt preset's `ENTRYPOINT ["sbt"]` was what let
    // run-fast-gate.sh append sbt's own flags to every consumer's build tool.
    dockerfile.linesIterator.exists(_.trim.startsWith("ENTRYPOINT")) shouldBe false

  "the scaffolded Dockerfile" should "carry the whole contract skeleton, whatever was detected" in:
    // These four lines are the ones litter-box can guarantee, so they are the ones it writes. The
    // `USER gate` in particular is not decoration: the fast gate runs as that user, and a scaffold
    // that ended on root would hand every consumer a container that is not a sandbox.
    everyRepo.foreach { d =>
      withClue(s"Dockerfile scaffolded for $d: ") {
        val dockerfile = scaffolded(d, ".litter-box/Dockerfile")
        dockerfile should include("ARG BASE_IMAGE=")
        dockerfile should include("FROM ${BASE_IMAGE}")
        dockerfile should include("USER root")
        dockerfile should include("USER gate")
        dockerfile should include("WORKDIR /workspace")
        dockerfile.linesIterator.exists(_.trim.startsWith("ENTRYPOINT")) shouldBe false
      }
    }

  it should "pin the base image tag to this binary's own version, never a stale copy" in:
    // Asserted against LitterBox.BaseImage, not against a hand-copied literal (the bug this test
    // exists for): a 0.1.1 binary that scaffolds a Dockerfile pointing at 0.1.0 has silently
    // drifted, and a test quoting the same stale string back would agree with the drift forever.
    //
    // `include` alone is too weak here: a template carrying both a correctly rendered
    // `ARG BASE_IMAGE=` line AND a leftover hardcoded one would still contain the rendered value as
    // a substring, so `include` would pass over a scaffold docker actually reads the WRONG line
    // from (the last `ARG BASE_IMAGE=` line before FROM is the one docker actually reads, so a
    // leftover duplicate placed after the correct line would win, and that is not necessarily the
    // one this assertion found). Two checks close that:
    // no `{{` marker survives rendering at all, and exactly one line starts with `ARG BASE_IMAGE=`.
    //
    // Still not enough on its own (review round two, MINOR): the splice value passed to
    // `Machine.renderTemplate` already carries the `ARG BASE_IMAGE=` prefix, so a hypothetical
    // future `renderTemplate` that substitutes the `{{BASE_IMAGE}}` marker IN PLACE of just
    // replacing the whole line would double it into `ARG BASE_IMAGE=ARG BASE_IMAGE=...`, and every
    // check above would still pass: `include` finds the wanted string as a substring of the doubled
    // one, no `{{` marker survives, and the doubled line still starts with `ARG BASE_IMAGE=` and is
    // the only one. An exact whole line match is the one assertion a doubled prefix cannot satisfy.
    everyRepo.foreach { d =>
      withClue(s"Dockerfile scaffolded for $d: ") {
        val dockerfile = scaffolded(d, ".litter-box/Dockerfile")
        dockerfile should include(s"ARG BASE_IMAGE=${LitterBox.BaseImage}")
        dockerfile should not include "{{"
        dockerfile.linesIterator.count(_.startsWith("ARG BASE_IMAGE=")) shouldBe 1
        dockerfile.linesIterator.count(_ == s"ARG BASE_IMAGE=${LitterBox.BaseImage}") shouldBe 1
      }
    }

  it should "leave the project layer a TODO instead of installing anything" in:
    // The defect this exists for (issue #13): a detected `build.sbt` bought a curl-and-untar block
    // pinning an sbt version nobody confirmed this project wants. Detection is not verification, so
    // no detection result may produce an install instruction any more: a scaffold with a RUN in it
    // is a scaffold that asserted something `init` cannot know.
    everyRepo.foreach { d =>
      withClue(s"Dockerfile scaffolded for $d: ") {
        val dockerfile = scaffolded(d, ".litter-box/Dockerfile")
        dockerfile should include("TODO")
        dockerfile.linesIterator.exists(_.trim.startsWith("RUN")) shouldBe false
      }
    }

  it should "name the build tool it detected in that TODO rather than acting on it" in:
    // Detection stays and still pays for itself: it writes the TODO's text. What went is the
    // assertion, so the operator reads "build.sbt found, confirm the command" and not an install.
    scaffolded(sbtRepo, ".litter-box/Dockerfile") should include("build.sbt")

  it should "carry the JDK it detected, rather than warning and scaffolding otherwise" in:
    // The second half of #13: `Init.detect` read the JDK and `Init.warnings` warned about it, but
    // `dockerfile` never looked at `d.jdk`, so `init` knew the answer, told the operator the
    // answer, and then scaffolded a container that contradicted it. Against yaes (`-release:25`
    // over the temurin 21 base) that surfaced as `25 is not a valid choice for
    // -java-output-version`, after a successful image build and 50 compiled sources.
    val mismatched = scaffolded(jdk25Repo, ".litter-box/Dockerfile")
    mismatched should include("25")
    mismatched should include("21")

    // A JDK that could not be read is its own answer and has to say so, or the TODO reads as if 21
    // had been confirmed.
    scaffolded(noJdkRepo, ".litter-box/Dockerfile") should include("could not be read")

  it should "report the host matching the base image as evidence, never as a decision" in:
    // The branch that reads hardest as harmless and is the likeliest to be wrong: the host happens
    // to run the version the base image ships, so the TODO used to conclude that "this project
    // probably needs no JDK layer at all". That is a claim about the CONTAINER drawn from a reading
    // of the HOST, which is the whole defect #13 exists to remove, and a project can perfectly well
    // target a JDK other than the one its developer runs. The branch stays because the coincidence
    // is worth knowing; what it may not do is decide.
    val matched = scaffolded(sbtRepo, ".litter-box/Dockerfile")
    matched should include("the same version the base image ships")
    matched.toLowerCase should include("confirm")
    matched.toLowerCase should not include "needs no jdk layer"

  it should "point the JDK install at the layer the TODO sits in" in:
    // The whole TODO is spliced into the scaffold as {{PROJECT_LAYER}}, which sits between
    // `USER root` and `USER gate`. So "install it above" sends the operator at `FROM` and
    // `USER root`, and an install there is either impossible or lands outside the root window.
    val mismatched = scaffolded(jdk25Repo, ".litter-box/Dockerfile")
    mismatched should include("in this block")
    mismatched should not include "targets above"

  "the scaffolded gate command" should "be the explicit non-command, whatever was detected" in:
    // `sbt -Werror compile test` is not a valid sbt invocation: sbt parses bare arguments as
    // commands and answers "Not a valid command: -". So every sbt repo `init` ever scaffolded got a
    // gate that could not run, and nothing caught it because litter-box's own gate is scala-cli.
    // `false` is the honest version of the same unknown: a command that exists everywhere and
    // always fails, so iteration one goes red with "you have not told me how to build this".
    everyRepo.foreach { d =>
      val root = tempRoot()
      Init.run(root, d, force = false).isRight shouldBe true
      val cfg = Settings.parse(Settings.loadFile(root).toOption.get)
      withClue(s"gate.fast scaffolded for $d: ") { cfg.gateCmd shouldBe "false" }
    }

  it should "come with a TODO naming what was detected" in:
    val conf = scaffolded(sbtRepo, ".litter-box/config.conf")
    conf should include("TODO")
    conf should include("build.sbt")

  it should "scaffold an allowlist no narrower than the built-in one it overrides" in:
    // The defect this exists for (issue #14): the scaffolded file was the single line
    // `api.anthropic.com`, and build-image.sh prefers .litter-box/allowlist over the shipped
    // proxy/allowlist whenever it exists. So running `init` NARROWED egress to something no JVM
    // build can resolve through, and a repo that had never been scaffolded was better off. The
    // scaffold may say more than the fallback, never less.
    val scaffolded = allowlistEntries(scaffoldedAllowlist())
    val builtIn    = allowlistEntries(builtInAllowlist)

    builtIn should not be empty
    scaffolded should contain allElementsOf builtIn

  it should "carry the host the sbt launcher probes" in:
    // Named on its own because it is the one host the failure in #14 proved is needed and that
    // neither file had: the launcher resolves itself through repo.typesafe.com before any build
    // definition is read, so a gate without it dies before the project is even loaded.
    allowlistEntries(scaffoldedAllowlist()) should contain("repo.typesafe.com")
    allowlistEntries(builtInAllowlist) should contain("repo.typesafe.com")

  it should "explain itself in lines tinyproxy reads as comments" in:
    // The header is only safe because tinyproxy's filter reader treats a leading `#` as a comment
    // (src/filter.c, verified against the 1.11.2 the alpine image ships). The other half of that
    // guarantee is that no entry can hide a comment or a stray word: an entry is cut at the first
    // whitespace, so `foo.example.com # mirror` silently becomes a pattern and anything with a
    // space in it becomes a DIFFERENT pattern than the file appears to name.
    val text = scaffoldedAllowlist()

    text.linesIterator.exists(_.startsWith("#")) shouldBe true
    allowlistEntries(text).foreach(e => e should fullyMatch regex "[A-Za-z0-9.*?-]+")

  "a non-sbt repo" should "get a TODO Dockerfile and no sbt anywhere" in:
    // Still the sharpest single assertion in this spec: a repo where nothing was recognised may not
    // acquire the word at all, not even inside the TODO that explains what was NOT found.
    val root = tempRoot()
    Init.run(root, plainRepo, force = false).isRight shouldBe true
    val dockerfile = readString(root.resolve(".litter-box/Dockerfile"))
    dockerfile should include("TODO")
    dockerfile.toLowerCase should not include "sbt"
    readString(root.resolve(".litter-box/config.conf")).toLowerCase should not include "sbt"

  it should "not silently inherit the sbt gate from the reference config" in:
    // The trap this test exists for: omitting `gate.fast` from the scaffolded config would make it
    // fall back to Settings.Reference, which is `sbt -Werror compile test`. That is exactly the
    // silent sbt preset the issue forbids.
    val root = tempRoot()
    Init.run(root, plainRepo, force = false)
    val cfg = Settings.parse(Settings.loadFile(root).toOption.get)
    cfg.gateCmd should not include "sbt"

  it should "warn out loud" in:
    Init.warnings(plainRepo).mkString(" ") should include("build tool")

  it should "still succeed" in:
    Init.run(tempRoot(), plainRepo, force = false).isRight shouldBe true

  "an sbt repo" should "still be told its Dockerfile and gate are unanswered" in:
    // The warning used to fire only when nothing was detected, which was the same mistake as the
    // scaffold: recognising the build tool was treated as having answered the question. It is the
    // TODOs that are unanswered, and they exist for every repo now, so the warning does too.
    val warned = Init.warnings(sbtRepo).mkString(" ")
    warned should include("build.sbt")
    warned should include("TODO")

  "a repo with no GitHub remote" should "be warned about it" in:
    Init.warnings(plainRepo).mkString(" ").toLowerCase should include("remote")

  "a repo on a JDK the base image does not ship" should "be warned about it" in:
    Init.warnings(jdk25Repo).mkString(" ") should include("JDK 25")

  it should "not be warned when it builds under the 21 the base image ships" in:
    Init.warnings(sbtRepo).mkString(" ") should not include "JDK"

  it should "be measured against the JDK the base image actually ships" in:
    // The guard `Init.BaseImageJdk` exists for. That constant is a COPY of a fact decided in
    // `resources/sandbox/base.Dockerfile`, and no compiler ties the two together, so a base image
    // bumped to a newer temurin would leave every scaffolded Dockerfile and every warning quoting
    // the version that is gone. Reading the `FROM` line back turns that into a red test here
    // rather than a gate that dies inside a container on `is not a valid choice for
    // -java-output-version`.
    val baseImage = new String(Sandbox.builtIn("base.Dockerfile"), StandardCharsets.UTF_8)
    val shipped = "(?m)^FROM eclipse-temurin:(\\d+)".r
      .findFirstMatchIn(baseImage)
      .map(_.group(1))
      .getOrElse(fail("base.Dockerfile no longer names its JDK as FROM eclipse-temurin:<major>"))

    // The prose the operator reads has to name that same version, and a host already on it has
    // nothing to be warned about.
    scaffolded(noJdkRepo, ".litter-box/Dockerfile") should include(s"temurin $shipped")
    Init
      .warnings(Init.Detected(None, Some("rcardin/x"), Some(shipped)))
      .mkString(" ") should not include "JDK"

  "a second init" should "fail and change nothing without --force" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false).isRight shouldBe true
    Files.write(
      root.resolve(".litter-box/prompts/conventions.md"),
      "MINE".getBytes(StandardCharsets.UTF_8)
    )
    val before = tree(root)
    Init.run(root, sbtRepo, force = false).isLeft shouldBe true
    tree(root) shouldBe before

  it should "overwrite with --force" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    Files.write(
      root.resolve(".litter-box/prompts/conventions.md"),
      "MINE".getBytes(StandardCharsets.UTF_8)
    )
    Init.run(root, sbtRepo, force = true).isRight shouldBe true
    readString(root.resolve(".litter-box/prompts/conventions.md")) should not be "MINE"

  "the scaffolded loop.scala" should "carry the //> using dep line rendered from LitterBox.Coordinate, never a duplicated literal" in:
    // Asserted against the CONSTANT, not against the literal string "in.rcard::litter-box:0.1.1"
    // (issue #43): a version bumped in `LitterBox.Version` and left stale here would otherwise pass
    // this test right up until a consumer's own `scala-cli run` disagreed with it.
    val loopScala = scaffolded(sbtRepo, ".litter-box/loop.scala")
    loopScala should include(s"//> using dep ${LitterBox.Coordinate}")

  it should "carry that //> using line and no other, so a consumer's launcher resolves exactly one directive" in:
    // Issue #43 review round two, MAJOR: the test above only proved the coordinate line is PRESENT,
    // never that it is the ONLY `//> using` line. `ScaffoldedLoopBoundarySpec`'s drift guard strips
    // every `//> using` line before comparing the rest of the file byte for byte, so a second,
    // unreviewed directive added anywhere in loop.scala.txt (a stray dependency, a pinned compiler
    // version) would leave that guard green too. This is the count that guard cannot take the place
    // of.
    val loopScala = scaffolded(sbtRepo, ".litter-box/loop.scala")
    loopScala.linesIterator.filter(_.trim.startsWith("//> using")).toList shouldBe
      List(s"//> using dep ${LitterBox.Coordinate}")

  it should "name LitterBox.shipped" in:
    scaffolded(sbtRepo, ".litter-box/loop.scala") should include("LitterBox.shipped")

  it should "be the same file for every detection result, since the shipped pipeline is not a function of what init detected" in:
    val rendered = everyRepo.map(d => scaffolded(d, ".litter-box/loop.scala"))
    rendered.distinct should have size 1

  it should "say where the coordinate is resolved from, and never claim it resolves nowhere" in:
    // The inverse of what this test asserted before issue #41: while nothing was published, the
    // scaffold had to warn that its own header could not resolve. Now that `release.yml` publishes
    // the library on every tag, the stale warning would be worse than no comment at all, sending a
    // consumer off to debug a resolution failure that is not happening. Asserting the ABSENCE of the
    // old phrasing, not just the presence of the new one, is what makes this test notice a
    // half-finished edit that adds the sentence and leaves the contradiction sitting under it.
    val loopScala = scaffolded(sbtRepo, ".litter-box/loop.scala")
    loopScala should include("Maven Central")
    loopScala should include("#41")
    loopScala.toLowerCase should not include "not resolvable"

  it should "settle no knob that .litter-box/config.conf can also settle" in:
    // The executable form of the acceptance criterion "no knob is settable in both config.conf and
    // loop.scala": every key path Settings.Reference's own schema declares (issue #43) must not
    // appear in the scaffolded loop.scala at all, or the same fact would be settable from either
    // file with no rule saying which one wins for THAT particular knob.
    val loopScala = scaffolded(sbtRepo, ".litter-box/loop.scala")
    val knobKeys  = Settings.referenceOnly.entrySet().asScala.map(_.getKey).toList
    knobKeys should not be empty
    knobKeys.foreach { key =>
      withClue(s"knob key '$key' must not appear in the scaffolded loop.scala: ") {
        loopScala should not include key
      }
    }

  "detect" should "recognise sbt by build.sbt at the root" in:
    val root = tempRoot()
    Files.write(root.resolve("build.sbt"), Array.emptyByteArray)
    val d = Init.detect(root, _ => LiveProc.Result(1, "", ""))
    d.buildTool shouldBe Some(Init.BuildTool.Sbt)

  it should "report no build tool when there is no build.sbt" in:
    val d = Init.detect(tempRoot(), _ => LiveProc.Result(1, "", ""))
    d.buildTool shouldBe None

  it should "read the GitHub remote off gh" in:
    val d = Init.detect(tempRoot(), _ => LiveProc.Result(0, "rcardin/litter-box\n", ""))
    d.remote shouldBe Some("rcardin/litter-box")

  it should "survive gh being absent" in:
    val d = Init.detect(tempRoot(), _ => throw java.io.IOException("no gh"))
    d.remote shouldBe None

  it should "read the major version off a modern java -version banner" in:
    val d = Init.detect(tempRoot(), javaBanner("openjdk version \"21.0.5\" 2024-10-15"))
    d.jdk shouldBe Some("21")

  it should "read the major version off a legacy 1.x java -version banner" in:
    // A JDK 8 banner spells its major version as the SECOND dotted component: taking the first
    // digits after the quote warns the operator that "this repo builds under JDK 1".
    val d = Init.detect(tempRoot(), javaBanner("java version \"1.8.0_392\""))
    d.jdk shouldBe Some("8")
