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

  /** One of the shipped observability scripts, with the optional path it takes.
    *
    * ONE case for both rather than a `Watch` and a `Tail`, because `Main` does exactly the same
    * thing with either — resolve the repo, resolve the extracted tree, exec — and the only thing
    * that differs between them is a filename `ObserveTool` already carries.
    */
  case Observe(tool: ObserveTool, target: Option[String])
  case Help

/** The observability scripts a subcommand fronts, and the file each one is.
  *
  * The filename lives HERE, in the closed enum, rather than in a lookup `Main` performs: a
  * subcommand naming a script that does not ship is a subcommand that dies on a missing path at the
  * first invocation from an install, which is the class of bug issue #15 is about. `ShippedSpec`
  * asserts every name here is in `Observe.ShippedFiles`, so the check happens at build time.
  *
  * `subcommand` is deliberately not the script's basename: `litter-box tail-claude` would spell the
  * implementation into the CLI grammar, and the grammar is the part that cannot change later.
  */
enum ObserveTool(val subcommand: String, val script: String, val takes: String):
  case Watch extends ObserveTool("watch", "watch.sh", "status.jsonl")
  case Tail  extends ObserveTool("tail", "tail-claude.sh", "logfile")

object ObserveTool:
  /** Lets `Cli.parse` match a subcommand name and bind the tool it names in one pattern, so the
    * grammar stays a table of cases rather than a guard that tests a lookup and a body that repeats
    * it.
    */
  def unapply(name: String): Option[ObserveTool] = values.find(_.subcommand == name)

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
      |  litter-box watch [status.jsonl]
      |                                live view of the run in this repo (needs jq)
      |  litter-box tail [logfile]     follow the worker's stream-json log (needs jq)
      |  litter-box --help | -h | help this message
      |
      |environment variables still work as they always have; a flag beats the matching variable.
      |""".stripMargin

  def parse(args: List[String]): Either[String, Command] = args match
    case Nil                                => Right(Command.Loop(dryRun = false))
    case ("--help" | "-h" | "help") :: Nil  => Right(Command.Help)
    case "init" :: rest                     => flagsOnly(rest, "--force").map(Command.Init.apply)
    case "eject" :: what :: rest if what.nonEmpty && !what.startsWith("-") =>
      flagsOnly(rest, "--force").map(Command.Eject(what, _))
    case "eject" :: _ =>
      Left("eject needs the name of a prompt, e.g. `litter-box eject prompts/iterate-prompt.md`")
    case ObserveTool(tool) :: rest =>
      optionalPath(rest, tool).map(Command.Observe(tool, _))
    case rest if rest.forall(_.startsWith("-")) =>
      flagsOnly(rest, "--dry-run").map(Command.Loop.apply)
    case other => Left(s"unknown command: ${other.head}")

  /** Accepts a trailing argument list that is either empty or exactly the one flag this subcommand
    * takes. Deliberately narrow: `init --dry-run` is an error rather than a silently ignored flag,
    * because ignoring it would imply `init` has a dry-run mode.
    */
  private def flagsOnly(rest: List[String], flag: String): Either[String, Boolean] = rest match
    case Nil           => Right(false)
    case `flag` :: Nil => Right(true)
    case other =>
      // The offending token is whichever one isn't the flag this subcommand recognizes — not
      // necessarily the first one, since a legitimate flag can be followed by a stray argument.
      val unexpected = other.find(_ != flag).getOrElse(other.head)
      Left(s"unexpected argument: $unexpected")

  /** Accepts the one optional path the observability scripts take, and nothing else.
    *
    * A leading dash is rejected rather than passed through, even though the scripts would only ever
    * treat it as a filename: these subcommands take no flags at all, so a `--follow` an operator
    * guessed at must fail here saying so, not reach bash as a path that does not exist.
    *
    * The two rejections are separate messages because they are separate mistakes, and each names the
    * token that is actually the problem — the same rule `flagsOnly` follows, for the same reason:
    * `watch a.jsonl b.jsonl` blaming `a.jsonl` sends the operator to inspect the argument they got
    * right.
    */
  private def optionalPath(rest: List[String], tool: ObserveTool): Either[String, Option[String]] =
    def isPath(arg: String): Boolean = arg.nonEmpty && !arg.startsWith("-")
    rest match
      case Nil => Right(None)
      case first :: extras =>
        if !isPath(first) then
          Left(s"${tool.subcommand} takes no flags, only an optional ${tool.takes} path: $first")
        else
          extras.headOption match
            case None => Right(Some(first))
            case Some(extra) =>
              Left(
                s"${tool.subcommand} takes at most one argument, a ${tool.takes} path: $extra"
              )
