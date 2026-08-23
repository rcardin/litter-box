package in.rcard.litterbox

import in.rcard.litterbox.testsupport.RepoTree
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

/** Pins the one directional rule that makes the framework tier kit only: no CODE in `src/Kit.scala`
  * or `src/KitMacro.scala` may name anything declared outside those two files and `src/Domain.scala`
  * plus `src/Caps.scala`.
  *
  * Tier applies to a FILE, not to a package or a marker trait, and that is the whole reason this
  * check has to exist as a spec at all. `Kit` and `Machine` are members of the same package, so every
  * reference this spec forbids compiles perfectly, which is why the two sibling boundary specs'
  * `typeCheckErrors` idiom (`test/ConsumerBoundarySpec.scala`, `test/TestkitBoundarySpec.scala`) is
  * no help here: those pin what a FOREIGN package can write, a question the compiler already answers,
  * while this one pins a layering the compiler has no opinion about. Nothing but reading the source
  * text catches it.
  *
  * What the layering buys: the kit is the surface a consumer authoring their own graph compiles
  * against, so whatever the kit names is, transitively, part of the framework they depend on. A kit
  * that reaches into `Machine` publishes the shipped pipeline's own machinery as framework by
  * accident, and the shipped loop stops being merely the first consumer of the kit and becomes a
  * thing the kit cannot be understood, tested or evolved without. The arrow has to run one way:
  * shipped code onto kit onto domain, never back.
  *
  * The rule binds CODE ONLY, and the exemption for comments and string literals is deliberate rather
  * than an accident of how the check is written. Naming a construct in order to explain it or to
  * diagnose it creates no dependency; only a reference the compiler resolves does. Binding prose
  * would force `KitMacro`'s six compile error messages to stop naming `LitterBox.graph`, the very
  * call they are reporting on, leaving a consumer with an error that cannot say what failed, and it
  * would gag the narration at the top of `Kit.scala` that explains the shipped pipeline the kit was
  * generalised out of. A checker naming the construct it checks is doing its job. So
  * `stripCommentsAndLiterals` blanks both before a single identifier is looked at, and blanks them
  * into SPACES rather than deleting them, so line numbers survive and a failure names the line.
  *
  * The denylist is DERIVED from the source tree, never written down here, because a hand written list
  * fails open in exactly the way this edge arrived in the first place: someone adds a top level
  * `object` to a file outside the two tiers, the kit names it, and a list nobody remembered to
  * update stays silent while the boundary is gone. Scanning every `.scala` file under `src` for what
  * it declares means a new declaration is denied to the kit the moment it is declared, with nothing
  * left to keep in step.
  *
  * A consequence worth stating, because the temptation on a red run is to reach for it: an innocent
  * collision, a kit local identifier that happens to share a name with a declaration outside the two
  * tiers, must be reported and resolved, not excluded. Two tiers picking the same name for different
  * things is a real finding about the vocabulary, and one ad hoc exclusion here is the first hole in
  * a check whose entire value is that it has none.
  *
  * One limit the check knowingly carries: an interpolation hole is blanked along with the literal
  * that contains it, so a reference smuggled inside `s"...${Foo.bar}..."` would be missed. Teaching
  * the stripper to step back into a hole means tracking nesting depth through arbitrary Scala, and
  * the failure mode it would cover, a reference reachable only from inside a message string, is not
  * one this code has ever shown.
  */
class KitBoundarySpec extends AnyFlatSpec with Matchers:

  /** The kit tier: the files the rule binds. */
  private val KitTier = List("src/Kit.scala", "src/KitMacro.scala")

  /** The domain tier, plus the kit itself: the files a kit reference is allowed to resolve into.
    * These are the names excluded when the denylist is derived, so the derivation and the rule state
    * the same four file tier membership exactly once.
    */
  private val AllowedTierFiles =
    Set("Kit.scala", "KitMacro.scala", "Domain.scala", "Caps.scala")

  behavior of "the kit tier"

  it should "name nothing declared outside the domain tier and the kit itself" in {
    val denied = deniedNames()

    // A derivation that silently came back empty would make every assertion below vacuously true,
    // which is the one way this spec could pass while checking nothing at all.
    withClue("no top level declarations were derived from src/, so the check would be vacuous: ") {
      denied should not be empty
    }

    val violations = KitTier.flatMap(relative => scan(relative, denied))

    if violations.nonEmpty then
      val report = violations.map((file, line, name) => s"  $file:$line names $name").mkString("\n")
      fail(
        s"the kit tier names ${violations.size} declaration(s) from outside itself and the domain " +
          s"tier:\n$report"
      )
  }

  /** Every whole word occurrence of a denied name in one file's stripped source, as (file, line,
    * name). Tokenising the line and asking whether the token is denied, rather than searching for
    * each name, is what makes the match a whole identifier: `Machinery` and `preMachine` tokenise to
    * themselves and never reach the denylist.
    */
  private def scan(relative: String, denied: Set[String]): List[(String, Int, String)] =
    val text = stripCommentsAndLiterals(read(relative))
    for
      (line, idx) <- text.linesIterator.zipWithIndex.toList
      token       <- Identifier.findAllMatchIn(line).map(_.matched)
      if denied.contains(token)
    yield (relative, idx + 1, token)

  /** The names of every top level declaration in `src/`, minus the four files the kit is allowed to
    * name. Anchored at column zero so that "top level" is read straight off the layout: a nested
    * member is indented, and a nested member is not something the kit could name unqualified anyway.
    */
  private def deniedNames(): Set[String] =
    val outsideTiers = srcFiles().filterNot(p => AllowedTierFiles.contains(p.getFileName.toString))
    withClue("expected src/ to hold files outside the kit and domain tiers: ") {
      outsideTiers should not be empty
    }
    outsideTiers.flatMap { path =>
      Files
        .readString(path)
        .linesIterator
        .map(TopLevelDeclaration.findPrefixMatchOf)
        .collect { case Some(m) => m.group(1) }
    }.toSet

  private def srcFiles(): List[Path] =
    val dir =
      RepoTree.dir("src").getOrElse(fail("could not locate src by walking up from the JVM cwd"))
    val stream = Files.list(dir)
    try
      stream.iterator.asScala.toList
        .filter(_.getFileName.toString.endsWith(".scala"))
        .sortBy(_.getFileName.toString)
    finally stream.close()

  private def read(relative: String): String =
    Files.readString(
      RepoTree
        .file(relative)
        .getOrElse(fail(s"could not locate $relative by walking up from the JVM cwd"))
    )

  /** `object`, `enum`, `class`, `trait` and `type`, with any modifiers Scala allows in front of them,
    * declared at column zero.
    */
  private val TopLevelDeclaration: Regex =
    (raw"(?:(?:final|sealed|case|abstract|open|transparent|opaque)\s+)*" +
      raw"(?:object|enum|class|trait|type)\s+([A-Za-z_][A-Za-z0-9_]*)").r

  /** A Scala identifier as this check cares about it. `$` is part of the token rather than a break so
    * that a compiler generated `Machine$1` is not read as a reference to `Machine`.
    */
  private val Identifier: Regex = raw"[A-Za-z_$$][A-Za-z0-9_$$]*".r

  /** Every comment and string literal replaced by spaces, newlines kept, so the result is the CODE of
    * the file at its original line numbers. A lexer approximation on purpose: the alternative is a
    * parser, and the four constructs below are the whole of what can hide an identifier from a
    * regex in this repository's source.
    */
  private def stripCommentsAndLiterals(source: String): String =
    val out = source.toCharArray
    val n   = source.length

    def blank(from: Int, until: Int): Unit =
      var k = from
      while k < until do
        if out(k) != '\n' then out(k) = ' '
        k += 1

    def endOfLineComment(start: Int): Int =
      source.indexOf('\n', start) match
        case -1  => n
        case idx => idx

    // Block comments nest in Scala, so a depth counter rather than a search for the first closing
    // marker.
    def endOfBlockComment(start: Int): Int =
      var depth = 1
      var j     = start + 2
      while j < n && depth > 0 do
        if source.startsWith("/*", j) then
          depth += 1
          j += 2
        else if source.startsWith("*/", j) then
          depth -= 1
          j += 2
        else j += 1
      j

    // A triple quoted literal ends at the LAST quote of a run, since a fourth quote is legal there and
    // belongs to the literal rather than starting a new one.
    def endOfTripleQuoted(start: Int): Int =
      source.indexOf("\"\"\"", start + 3) match
        case -1  => n
        case idx =>
          var j = idx + 3
          while j < n && source.charAt(j) == '"' do j += 1
          j

    // A backslash escapes whatever follows it, the closing quote included. A newline before any
    // closing quote means the source does not compile; stopping there keeps one malformed line from
    // blanking the rest of the file.
    def endOfQuoted(start: Int): Int =
      var j    = start + 1
      var done = false
      while j < n && !done do
        source.charAt(j) match
          case '\\' => j += 2
          case '"'  =>
            j += 1
            done = true
          case '\n' => done = true
          case _    => j += 1
      j

    var i = 0
    while i < n do
      val next =
        if source.startsWith("//", i) then Some(endOfLineComment(i))
        else if source.startsWith("/*", i) then Some(endOfBlockComment(i))
        else if source.startsWith("\"\"\"", i) then Some(endOfTripleQuoted(i))
        else if source.charAt(i) == '"' then Some(endOfQuoted(i))
        else None
      next match
        case Some(end) =>
          blank(i, math.min(end, n))
          i = math.max(end, i + 1)
        case None => i += 1

    String(out)
