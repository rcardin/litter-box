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
