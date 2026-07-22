package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit tests for the argument parser.
  *
  * The parser is pure and total so this spec is a table: every accepted invocation, and the shape
  * of the rejection for everything else. `Main` gets no test of its own for dispatch, because
  * dispatch is a three-way match over exactly this enum.
  */
class CliSpec extends AnyFlatSpec with Matchers:

  "parse" should "default to the loop with dry-run off" in:
    Cli.parse(Nil) shouldBe Right(Command.Loop(dryRun = false))

  it should "accept --dry-run" in:
    Cli.parse(List("--dry-run")) shouldBe Right(Command.Loop(dryRun = true))

  it should "accept init with and without --force" in:
    Cli.parse(List("init")) shouldBe Right(Command.Init(force = false))
    Cli.parse(List("init", "--force")) shouldBe Right(Command.Init(force = true))

  it should "accept eject with a prompt name" in:
    Cli.parse(List("eject", "prompts/iterate-prompt.md")) shouldBe
      Right(Command.Eject("prompts/iterate-prompt.md", force = false))
    Cli.parse(List("eject", "fix-prompt.md", "--force")) shouldBe
      Right(Command.Eject("fix-prompt.md", force = true))

  it should "reject eject with no argument" in:
    val result = Cli.parse(List("eject"))
    result.isLeft shouldBe true
    result.left.toOption.get should include("eject needs the name of a prompt")

  it should "reject eject with an empty prompt name" in:
    // An empty string does not start with "-", so a guard that only checks for a leading dash
    // would let it through as if it were a real prompt name.
    val result = Cli.parse(List("eject", ""))
    result.isLeft shouldBe true
    result.left.toOption.get should include("eject needs the name of a prompt")

  it should "accept every spelling of help" in:
    Cli.parse(List("--help")) shouldBe Right(Command.Help)
    Cli.parse(List("-h")) shouldBe Right(Command.Help)
    Cli.parse(List("help")) shouldBe Right(Command.Help)

  it should "reject an unknown subcommand" in:
    val result = Cli.parse(List("frobnicate"))
    result.isLeft shouldBe true
    result.left.toOption.get should include("frobnicate")

  it should "reject an unknown flag on a known subcommand" in:
    val result = Cli.parse(List("init", "--wat"))
    result.isLeft shouldBe true
    result.left.toOption.get should include("--wat")

  it should "reject a flag that belongs to another subcommand" in:
    // --dry-run is a loop flag. Accepting it on `init` silently would suggest init has a dry-run
    // mode, which it does not.
    val result = Cli.parse(List("init", "--dry-run"))
    result.isLeft shouldBe true
    result.left.toOption.get should include("--dry-run")

  it should "blame the actual unexpected token, not the legitimate flag next to it" in:
    // `init --force extra` was previously reported as `unexpected argument: --force`, blaming
    // the flag the caller got right instead of the token that was actually the problem.
    val result = Cli.parse(List("init", "--force", "extra"))
    result.isLeft shouldBe true
    result.left.toOption.get should include("extra")
    result.left.toOption.get should not include "unexpected argument: --force"

  "the usage text" should "mention every subcommand" in:
    Cli.Usage should include("init")
    Cli.Usage should include("eject")
    Cli.Usage should include("--dry-run")
