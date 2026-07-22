package in.rcard.litterbox

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/** Where a prompt skeleton comes from.
  *
  * Slice 2 read the four templates out of the consumer repo's own `prompts/` directory, which made
  * every consumer the owner of the protocol: the lines that keep the machine honest ("you do not
  * report success", the `VERDICT:` contract, the no-`gh` rule) sat in a file the consumer was
  * invited to edit, and a consumer who mangled one broke the loop silently, with no error. So the
  * skeletons ship in the artifact instead, and the consumer owns exactly one file: the conventions
  * spliced in as `{{CONVENTIONS}}`.
  *
  * A repo that genuinely needs to change a skeleton still can, by ejecting it — but that is now a
  * deliberate act with a command behind it, not the default state of every install.
  */
object Prompts:

  /** Where an ejected skeleton lands, relative to the repo root. Under `.litter-box/`, which the
    * existing `protect` floor already covers (see `Settings.protectFloor`): an agent under harness
    * must not be able to rewrite the prompt that constrains it.
    */
  val EjectDir = ".litter-box/prompts"

  /** Classpath prefix. Matches `resources/prompts/` in the source tree via
    * `//> using resourceDir ./resources`.
    */
  private val ResourcePrefix = "/prompts/"

  /** The skeleton as it ships. A missing resource is a broken build, not a user error — the file
    * is in the same repo as this code and `Template` names it — so this throws rather than
    * returning an `Option` that every caller would have to pretend might be empty.
    */
  def builtIn(t: Template): String =
    val res = ResourcePrefix + t.fileName
    Option(getClass.getResourceAsStream(res)) match
      case Some(in) =>
        try new String(in.readAllBytes(), StandardCharsets.UTF_8)
        finally in.close()
      case None =>
        throw IllegalStateException(s"built-in prompt resource missing from the artifact: $res")

  /** The skeleton this repo actually uses: an ejected file if there is one, else the built-in.
    *
    * Disk wins, because that is what ejecting is for. The check is `isRegularFile` rather than
    * `exists` so a directory left at that path falls through to the built-in instead of throwing
    * mid-iteration.
    */
  def resolve(root: Path, t: Template): String =
    val onDisk = root.resolve(EjectDir).resolve(t.fileName)
    if Files.isRegularFile(onDisk) then
      new String(Files.readAllBytes(onDisk), StandardCharsets.UTF_8)
    else builtIn(t)

  /** Maps what an operator typed at `litter-box eject <what>` onto a template.
    *
    * Both spellings are accepted (`iterate-prompt.md` and `prompts/iterate-prompt.md`) because the
    * issue's own example uses the prefixed form while the file on disk after ejecting has none, and
    * an operator who copies either back out of their shell history should not get an error.
    */
  def parseName(what: String): Either[String, Template] =
    val bare = what.stripPrefix("prompts/").stripPrefix("./")
    Template.values.find(_.fileName == bare) match
      case Some(t) => Right(t)
      case None    =>
        Left(
          s"unknown prompt '$what' — expected one of: ${Template.values.map(_.fileName).mkString(", ")}"
        )

  /** Copies a built-in skeleton to `EjectDir` so the repo can edit it.
    *
    * Refuses to clobber without `force`, on the same reasoning as `init`: the file it would
    * overwrite is by definition one somebody hand-edited, and silently replacing it with the stock
    * text would undo that work with no diff to notice.
    */
  def eject(root: Path, what: String, force: Boolean): Either[String, Path] =
    parseName(what).flatMap { t =>
      val dest = root.resolve(EjectDir).resolve(t.fileName)
      if Files.exists(dest) && !force then
        Left(s"$dest already exists — pass --force to overwrite it")
      else
        Files.createDirectories(dest.getParent)
        Files.write(
          dest,
          builtIn(t).getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
        Right(dest)
    }
