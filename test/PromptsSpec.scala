package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Unit tests for `Prompts`: where a skeleton comes from, and what the shipped ones say.
  *
  * THE RULE THIS SPEC IS WRITTEN TO (GitHub issue #4): the tool owns the protocol and the consumer
  * owns only its conventions. So every assertion below is about which of the two wins where, and
  * about the protocol lines surviving a consumer who supplies nothing at all.
  *
  * `Machine.renderTemplate` and `Machine.protectedList` used to be asserted here too, and moved to
  * `test/MachineSpec.scala` under issue #84: a file named after one module while covering another
  * is where a reader stops being able to guess which spec a change of theirs belongs in. What is
  * left still calls `renderTemplate`, because a skeleton with unfilled slots is not readable text,
  * but every assertion below is about what a SKELETON says, never about what the render does to it.
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

  /** The protocol lines: the ones that keep the machine honest. A consumer who deletes one of
    * these breaks the loop with no error at all — the reviewer stops emitting a parseable verdict,
    * or the agent starts pushing branches — so they live in the skeleton and this test says so.
    */
  "the built-in skeletons" should "keep the verdict contract in the reviewer prompt" in:
    val rendered = Machine.renderTemplate(
      Prompts.builtIn(Template.Review),
      "PROTECTED"   -> Machine.protectedList(List(".litter-box/**")),
      "GATE"        -> "sbt test",
      "CONVENTIONS" -> "", // a consumer who wrote nothing at all
      "ISSUE"       -> "an issue",
      "TAMPER"      -> "no tampering",
      "DIFF"        -> "a diff"
    )
    rendered should include("VERDICT: APPROVE")
    rendered should include("VERDICT: REQUEST_CHANGES")

  it should "keep the no-gh and no-success-report rules in the worker prompt" in:
    val rendered = Machine.renderTemplate(
      Prompts.builtIn(Template.Iterate),
      "PROTECTED"   -> Machine.protectedList(List(".litter-box/**")),
      "GATE"        -> "sbt test",
      "CONVENTIONS" -> "",
      "ISSUE"       -> "an issue"
    )
    rendered should include("Do not run any `gh` command")
    rendered should include("You do not report success")

  it should "substitute every slot, leaving no braces behind" in:
    // Guards the whole slot contract at once: a skeleton edit that adds a slot nobody splices
    // fails here rather than shipping literal braces to the model.
    val cases = List(
      Template.Iterate -> Seq(
        "PROTECTED"   -> "- `x`",
        "GATE"        -> "g",
        "CONVENTIONS" -> "c",
        "ISSUE"       -> "i"
      ),
      Template.Fix -> Seq(
        "PROTECTED"   -> "- `x`",
        "GATE"        -> "g",
        "CONVENTIONS" -> "c",
        "ISSUE"       -> "i",
        "FAILURE"     -> "f",
        "COMMENTS"    -> "co"
      ),
      Template.Review -> Seq(
        "PROTECTED"   -> "- `x`",
        "GATE"        -> "g",
        "CONVENTIONS" -> "c",
        "ISSUE"       -> "i",
        "TAMPER"      -> "t",
        "DIFF"        -> "d"
      )
    )
    cases.foreach { case (t, splices) =>
      withClue(s"${t.fileName}: ") {
        Machine.renderTemplate(Prompts.builtIn(t), splices*) should not include "{{"
      }
    }

  it should "leave no slot in the grill-issue skeleton, which nothing renders" in:
    // GrillIssue is invoked by hand against a repo, not by Machine: there is no render site to
    // splice it, so a slot here reaches a human as literal braces rather than as content.
    Prompts.builtIn(Template.GrillIssue) should not include "{{"

  it should "name no project-specific convention" in:
    // The whole point of the slice: a consumer inherits the protocol, never another project's
    // domain. Each of these is a real line the pre-slice-3 skeletons carried.
    val banned = List("US-1", "Register", "Testcontainers", "onion", "src/it", "@nowarn", "-Werror")
    Template.values.foreach { t =>
      val text = Prompts.builtIn(t)
      banned.foreach { word =>
        withClue(s"${t.fileName} still mentions '$word': ") {
          text should not include word
        }
      }
    }
