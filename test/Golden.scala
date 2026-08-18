package in.rcard.litterbox

import in.rcard.litterbox.testsupport.RepoTree

import java.nio.file.{Files, Path, Paths}

/** Golden-file support for the log-line contract (`LogParitySpec`).
  *
  * The log stream is not debug output. `watch.sh` parses it, it is `status.jsonl`'s sibling, and the
  * bash parity oracle used to grep ten load-bearing phrases out of it. That oracle
  * (`test/statemachine-test.sh`) is deleted as of slice 1, so the phrases it pinned would otherwise
  * have no coverage at all. These golden files take over that job, and widen it: instead of asserting
  * that eight needles appear SOMEWHERE, they pin the whole stream for each scenario, in order. A
  * reordered, dropped, or reworded line now fails the build.
  *
  * `TestWorld.logLines` is what makes this workable — it records the raw messages `Machine` hands the
  * `Log` capability, with no timestamp and no absolute path in them (`LiveLog` adds the
  * `[loop HH:MM:SS]` prefix in production, downstream of here). So the stream is byte-deterministic
  * across machines and runs, which is the whole precondition for golden files being stable rather
  * than a recurring nuisance.
  *
  * To rewrite the goldens after a DELIBERATE log change:
  * {{{
  *   UPDATE_GOLDEN=1 scala-cli test .
  * }}}
  * then read `git diff test/golden/` line by line before committing. That diff IS the change to the
  * contract `watch.sh` consumes — if it contains a line you did not mean to change, that is the bug
  * this spec exists to catch.
  */
object Golden:

  /** `test/golden`, found by walking up from the JVM cwd, so this works from any working directory a
    * test runner might pick. `RepoTree` (`test/RepoTree.scala`) owns that walk and the reason it does
    * not ask git for the repo root instead.
    */
  private lazy val dir: Path =
    // Regenerating is allowed to create the directory, so a missing one is only fatal when reading.
    RepoTree
      .dir("test/golden")
      .getOrElse(Paths.get("").toAbsolutePath.normalize.resolve("test").resolve("golden"))

  private def updating: Boolean = sys.env.get("UPDATE_GOLDEN").contains("1")

  /** The expected stream for `name`, or the actual stream when regenerating (having written it). */
  def expected(name: String, actual: String): String =
    val file = dir.resolve(s"$name.log")
    if updating then
      Files.createDirectories(dir)
      Files.writeString(file, actual)
      actual
    else if !Files.isRegularFile(file) then
      throw IllegalStateException(
        s"missing golden file $file — run `UPDATE_GOLDEN=1 scala-cli test .` and review the result"
      )
    else Files.readString(file)
