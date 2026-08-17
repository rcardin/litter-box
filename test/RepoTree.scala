package in.rcard.litterbox.testsupport

import java.nio.file.{Files, Path, Paths}

/** Locating a file of this repository's own source tree from a test, without asking git.
  *
  * Four specs grew their own copy of the same upward walk, which is four places to fix when the rule
  * changes and four chances for one of them to drift into asking git again. The rule itself is
  * `Golden`'s (`test/Golden.scala`) and its reasoning is the load-bearing part: this repo runs its own
  * gate inside the sandbox, and `run-fast-gate.sh` materialises the staged tree with `git archive`,
  * deliberately WITHOUT a `.git`. So `git rev-parse --show-toplevel`, which is what
  * `Main.resolveRepoRoot` does, is not an answer every runner of this suite can get, and a spec that
  * asks for it fails describing the harness rather than the code (issue #9). These are ordinary files
  * in the source tree and were never a git question.
  *
  * The walk starts at the JVM cwd rather than at a fixed root because nothing here gets to choose the
  * directory a test runner starts in, and it climbs so that a runner starting in a subdirectory still
  * finds the tree above it.
  *
  * Every lookup returns an `Option` rather than throwing, because the four call sites want genuinely
  * different things from a miss: `Golden` regenerating goldens is allowed to create the directory it
  * could not find, while a spec reading a file that must exist wants scalatest's own `fail`, which
  * this object cannot reach without dragging scalatest into it.
  */
object RepoTree:

  /** The first directory named by `relative` at or above the JVM cwd. */
  def dir(relative: String): Option[Path] = walkUp(relative, Files.isDirectory(_))

  /** The first regular file named by `relative` at or above the JVM cwd. */
  def file(relative: String): Option[Path] = walkUp(relative, Files.isRegularFile(_))

  private def walkUp(relative: String, accept: Path => Boolean): Option[Path] =
    var d: Path             = Paths.get("").toAbsolutePath.normalize
    var found: Option[Path] = None
    while found.isEmpty && d != null do
      val candidate = d.resolve(relative)
      if accept(candidate) then found = Some(candidate)
      d = d.getParent
    found
