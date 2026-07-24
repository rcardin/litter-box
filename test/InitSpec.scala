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

  /** The entries tinyproxy would actually load out of an allowlist: its filter reader ends a line
    * at the first whitespace or unescaped `#` and skips whatever is left empty, so a full-line
    * comment contributes nothing and everything else is a host pattern.
    */
  private def allowlistEntries(text: String): List[String] =
    text.linesIterator.map(_.strip).filterNot(l => l.isEmpty || l.startsWith("#")).toList

  private val sbtRepo   = Init.Detected(Some(Init.BuildTool.Sbt), Some("rcardin/x"), Some("21"))
  private val plainRepo = Init.Detected(None, None, Some("21"))

  "plan" should "write every file the slice promises" in:
    val paths = Init.plan(sbtRepo).map(_._1)
    paths should contain allOf (
      ".litter-box/config.conf",
      ".litter-box/Dockerfile",
      ".litter-box/allowlist",
      ".litter-box/prompts/conventions.md",
      ".litter-box/.env.example",
      ".litter-box/.gitignore"
    )

  it should "produce a config the slice-2 loader accepts" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false).isRight shouldBe true
    val loaded = Settings.loadFile(root)
    loaded.isRight shouldBe true
    val cfg = Settings.parse(loaded.toOption.get)
    cfg.conventions shouldBe ".litter-box/prompts/conventions.md"
    cfg.gateCmd should include("sbt")

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
    dockerfile should include("sbt")
    // No ENTRYPOINT instruction (the comments explain its absence, hence the line-level check):
    // all three runners override it, and the sbt preset's `ENTRYPOINT ["sbt"]` was what let
    // run-fast-gate.sh append sbt's own flags to every consumer's build tool.
    dockerfile.linesIterator.exists(_.trim.startsWith("ENTRYPOINT")) shouldBe false

  it should "scaffold an allowlist no narrower than the built-in one it overrides" in:
    // The defect this exists for (issue #14): the scaffolded file was the single line
    // `api.anthropic.com`, and build-image.sh prefers .litter-box/allowlist over the shipped
    // proxy/allowlist whenever it exists. So running `init` NARROWED egress to something no JVM
    // build can resolve through, and a repo that had never been scaffolded was better off. The
    // scaffold may say more than the fallback, never less.
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val scaffolded = allowlistEntries(readString(root.resolve(".litter-box/allowlist")))
    val builtIn    =
      allowlistEntries(new String(Sandbox.builtIn("proxy/allowlist"), StandardCharsets.UTF_8))

    builtIn should not be empty
    scaffolded should contain allElementsOf builtIn

  it should "carry the host the sbt launcher probes" in:
    // Named on its own because it is the one host the failure in #14 proved is needed and that
    // neither file had: the launcher resolves itself through repo.typesafe.com before any build
    // definition is read, so a gate without it dies before the project is even loaded.
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    allowlistEntries(readString(root.resolve(".litter-box/allowlist"))) should contain(
      "repo.typesafe.com"
    )
    allowlistEntries(
      new String(Sandbox.builtIn("proxy/allowlist"), StandardCharsets.UTF_8)
    ) should contain("repo.typesafe.com")

  it should "explain itself in lines tinyproxy reads as comments" in:
    // The header is only safe because tinyproxy's filter reader treats a leading `#` as a comment
    // (src/filter.c, verified against the 1.11.2 the alpine image ships). The other half of that
    // guarantee is that no entry can hide a comment or a stray word: an entry is cut at the first
    // whitespace, so `foo.example.com # mirror` silently becomes a pattern and anything with a
    // space in it becomes a DIFFERENT pattern than the file appears to name.
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val text = readString(root.resolve(".litter-box/allowlist"))

    text.linesIterator.exists(_.startsWith("#")) shouldBe true
    allowlistEntries(text).foreach(e => e should fullyMatch regex "[A-Za-z0-9.*?-]+")

  "a non-sbt repo" should "get a TODO Dockerfile and no sbt anywhere" in:
    val root = tempRoot()
    Init.run(root, plainRepo, force = false).isRight shouldBe true
    val dockerfile = readString(root.resolve(".litter-box/Dockerfile"))
    dockerfile should include("TODO: install your build tool")
    dockerfile.toLowerCase should not include "sbt"

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

  "a repo with no GitHub remote" should "be warned about it" in:
    Init.warnings(plainRepo).mkString(" ").toLowerCase should include("remote")

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
