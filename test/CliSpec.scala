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
    Cli.parse(List("eject")).isLeft shouldBe true

  it should "accept every spelling of help" in:
    Cli.parse(List("--help")) shouldBe Right(Command.Help)
    Cli.parse(List("-h")) shouldBe Right(Command.Help)
    Cli.parse(List("help")) shouldBe Right(Command.Help)

  it should "reject an unknown subcommand" in:
    Cli.parse(List("frobnicate")).isLeft shouldBe true

  it should "reject an unknown flag on a known subcommand" in:
    Cli.parse(List("init", "--wat")).isLeft shouldBe true

  it should "reject a flag that belongs to another subcommand" in:
    // --dry-run is a loop flag. Accepting it on `init` silently would suggest init has a dry-run
    // mode, which it does not.
    Cli.parse(List("init", "--dry-run")).isLeft shouldBe true

  "the usage text" should "mention every subcommand" in:
    Cli.Usage should include("init")
    Cli.Usage should include("eject")
    Cli.Usage should include("--dry-run")
