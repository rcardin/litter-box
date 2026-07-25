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

  it should "accept watch and tail, with and without a path" in:
    Cli.parse(List("watch")) shouldBe Right(Command.Observe(ObserveTool.Watch, None))
    Cli.parse(List("watch", "logs/status.jsonl")) shouldBe
      Right(Command.Observe(ObserveTool.Watch, Some("logs/status.jsonl")))
    Cli.parse(List("tail")) shouldBe Right(Command.Observe(ObserveTool.Tail, None))
    Cli.parse(List("tail", "logs/issue-5-iter1.claude.log")) shouldBe
      Right(Command.Observe(ObserveTool.Tail, Some("logs/issue-5-iter1.claude.log")))

  it should "reject a second argument to watch, blaming the extra one" in:
    // Same rule as `init --force extra`: naming the first path sends the operator to inspect the
    // argument they got right.
    val result = Cli.parse(List("watch", "one.jsonl", "two.jsonl"))
    result.isLeft shouldBe true
    result.left.toOption.get should include("at most one argument")
    result.left.toOption.get should include("two.jsonl")
    result.left.toOption.get should not include "one.jsonl"

  it should "reject a flag on watch rather than passing it through as a path" in:
    // These subcommands take no flags at all, so a `--follow` an operator guessed at has to fail
    // here saying so, instead of reaching bash as a filename that does not exist.
    val result = Cli.parse(List("watch", "--follow"))
    result.isLeft shouldBe true
    result.left.toOption.get should include("takes no flags")
    result.left.toOption.get should include("--follow")

  it should "reject an empty path argument" in:
    // Same hole `eject ""` had: an empty string does not start with "-", so a guard that only looks
    // for a leading dash would pass it to the script as a path.
    val result = Cli.parse(List("tail", ""))
    result.isLeft shouldBe true
    // An empty argument is not a flag, so it must not be diagnosed as one: the flag message would
    // quote the offender back, and here there is nothing to quote.
    result.left.toOption.get shouldBe "tail was given an empty logfile path"

  it should "reject a whitespace-only path argument the same way" in:
    val result = Cli.parse(List("watch", "   "))
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe "watch was given an empty status.jsonl path"

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
    ObserveTool.values.foreach(tool => Cli.Usage should include(tool.subcommand))
