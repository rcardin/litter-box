package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Unit tests for the prompt layer.
  *
  * THE RULE THIS SPEC IS WRITTEN TO (GitHub issue #4): the tool owns the protocol and the consumer
  * owns only its conventions. So every assertion below is about which of the two wins where, and
  * about the protocol lines surviving a consumer who supplies nothing at all.
  */
class PromptsSpec extends AnyFlatSpec with Matchers:

  private def tempRoot(): Path = Files.createTempDirectory("prompts-spec")

  private def write(p: Path, s: String): Path =
    Option(p.getParent).foreach(Files.createDirectories(_))
    Files.write(p, s.getBytes(StandardCharsets.UTF_8))

  "builtIn" should "read every template out of the classpath" in:
    Template.values.foreach { t =>
      Prompts.builtIn(t) should not be empty
    }

  "resolve" should "return the built-in when the repo has no override" in:
    val root = tempRoot()
    Prompts.resolve(root, Template.Iterate) shouldBe Prompts.builtIn(Template.Iterate)

  it should "prefer an ejected file over the built-in" in:
    val root = tempRoot()
    write(root.resolve(Prompts.EjectDir).resolve("iterate-prompt.md"), "MINE")
    Prompts.resolve(root, Template.Iterate) shouldBe "MINE"

  it should "override one template without affecting the others" in:
    val root = tempRoot()
    write(root.resolve(Prompts.EjectDir).resolve("fix-prompt.md"), "MINE")
    Prompts.resolve(root, Template.Fix) shouldBe "MINE"
    Prompts.resolve(root, Template.Iterate) shouldBe Prompts.builtIn(Template.Iterate)

  "parseName" should "accept a bare name and a prompts/-prefixed one" in:
    Prompts.parseName("iterate-prompt.md") shouldBe Right(Template.Iterate)
    Prompts.parseName("prompts/iterate-prompt.md") shouldBe Right(Template.Iterate)
    Prompts.parseName("grill-issue-prompt.md") shouldBe Right(Template.GrillIssue)

  it should "reject an unknown name" in:
    Prompts.parseName("nope.md").isLeft shouldBe true

  "eject" should "write the built-in to the override directory" in:
    val root = tempRoot()
    val out  = Prompts.eject(root, "prompts/review-prompt.md", force = false)
    out.isRight shouldBe true
    val written = new String(Files.readAllBytes(out.toOption.get), StandardCharsets.UTF_8)
    written shouldBe Prompts.builtIn(Template.Review)

  it should "refuse to clobber an existing file without force" in:
    val root = tempRoot()
    val dest = root.resolve(Prompts.EjectDir).resolve("review-prompt.md")
    write(dest, "MINE")
    Prompts.eject(root, "review-prompt.md", force = false).isLeft shouldBe true
    new String(Files.readAllBytes(dest), StandardCharsets.UTF_8) shouldBe "MINE"

  it should "overwrite with force" in:
    val root = tempRoot()
    val dest = root.resolve(Prompts.EjectDir).resolve("review-prompt.md")
    write(dest, "MINE")
    Prompts.eject(root, "review-prompt.md", force = true).isRight shouldBe true
    new String(Files.readAllBytes(dest), StandardCharsets.UTF_8) shouldBe Prompts.builtIn(
      Template.Review
    )
