package in.rcard.litterbox

/** What the operator asked for.
  *
  * Kept as a closed enum parsed up front rather than as flags read where they are needed, so
  * `Main`'s dispatch is a total match and an invocation that means nothing fails before the process
  * has touched a repo.
  */
enum Command:
  /** The loop, which is what litter-box does when asked for nothing in particular. */
  case Loop(dryRun: Boolean)
  case Init(force: Boolean)
  case Eject(what: String, force: Boolean)
  case Help

/** Argument parsing, by hand.
  *
  * A parser library would give help text and validation for free, at the cost of a dependency and
  * its transitive tree on the classpath of a process whose whole design is about limiting what runs
  * near an agent. The grammar is four commands and two flags; it does not earn a dependency.
  *
  * Pure and total: every input maps to a `Right(Command)` or a `Left(message)`, and nothing here
  * reads the environment, the filesystem or the clock. That is what lets `CliSpec` be a table
  * rather than a set of process invocations.
  */
object Cli:

  val Usage: String =
    """usage:
      |  litter-box [--dry-run]        run the loop (default)
      |  litter-box init [--force]     scaffold .litter-box/ in this repo
      |  litter-box eject <prompt> [--force]
      |                                copy a built-in prompt to .litter-box/prompts/ to override it
      |  litter-box --help             this message
      |
      |environment variables still work as they always have; a flag beats the matching variable.
      |""".stripMargin

  def parse(args: List[String]): Either[String, Command] = args match
    case Nil                                => Right(Command.Loop(dryRun = false))
    case ("--help" | "-h" | "help") :: Nil  => Right(Command.Help)
    case "init" :: rest                     => flagsOnly(rest, "--force").map(Command.Init.apply)
    case "eject" :: what :: rest if !what.startsWith("-") =>
      flagsOnly(rest, "--force").map(Command.Eject(what, _))
    case "eject" :: _ =>
      Left("eject needs the name of a prompt, e.g. `litter-box eject prompts/iterate-prompt.md`")
    case rest if rest.forall(_.startsWith("-")) =>
      flagsOnly(rest, "--dry-run").map(Command.Loop.apply)
    case other => Left(s"unknown command: ${other.head}")

  /** Accepts a trailing argument list that is either empty or exactly the one flag this subcommand
    * takes. Deliberately narrow: `init --dry-run` is an error rather than a silently ignored flag,
    * because ignoring it would imply `init` has a dry-run mode.
    */
  private def flagsOnly(rest: List[String], flag: String): Either[String, Boolean] = rest match
    case Nil          => Right(false)
    case `flag` :: Nil => Right(true)
    case other        => Left(s"unexpected argument: ${other.head}")
