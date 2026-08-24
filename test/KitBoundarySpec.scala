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
  * diagnose it creates no dependency. Binding prose would force `KitMacro`'s six compile error
  * messages to stop naming `LitterBox.graph`, the very call they are reporting on, leaving a consumer
  * with an error that cannot say what failed, and it would gag the narration at the top of
  * `Kit.scala` that explains the shipped pipeline the kit was generalised out of. A checker naming
  * the construct it checks is doing its job. So `stripCommentsAndLiterals` blanks both before a
  * single identifier is looked at, and blanks them into SPACES rather than deleting them, so line
  * numbers survive and a failure names the line.
  *
  * The exemption is for prose, not for every literal, and one literal shape in these files is a
  * genuine compile time reference wearing a string's clothes. `KitMacro` resolves nine symbols by
  * fully qualified name, `Symbol.requiredModule("in.rcard.litterbox.Shape")` and its siblings, each
  * of which fails the build the day its target is renamed. That is a resolved dependency by any
  * reading, so [[macroResolvedViolations]] runs those literals through the same denylist. Every one
  * of the nine points at the domain or the kit today; the point of checking is that
  * `Symbol.requiredModule("in.rcard.litterbox.Machine")` would breach the boundary in the hardest
  * possible way, invisibly, and this check is the only thing that would ever see it.
  *
  * A second literal shape carries the identical risk. An interpolation hole, `s"...${Foo.bar}..."`,
  * is executable Scala wearing a literal's clothes exactly as a resolved symbol string is, so
  * [[interpolationViolations]] reads the RAW source too and tokenises every hole's own text against
  * the same denylist, rather than teaching `stripCommentsAndLiterals` to step back into a hole, which
  * would mean tracking brace depth through arbitrary Scala and stop being a lexer.
  *
  * The denylist is DERIVED from the source tree, never written down here, because a hand written list
  * fails open in exactly the way this edge arrived in the first place: someone adds a top level
  * `object` to a file outside the two tiers, the kit names it, and a list nobody remembered to
  * update stays silent while the boundary is gone. Walking every `.scala` file under `src` for what
  * it declares means a new declaration is denied to the kit the moment it is declared, with nothing
  * left to keep in step. The derivation reads each of those files through
  * `stripCommentsAndLiterals` too, for the converse reason: `Prompts.scala`, `Init.scala` and
  * `Shipped.scala` carry embedded templates and prompt text, and a markdown sample inside one that
  * happens to open a line with `val result` would otherwise enter the denylist as a real name and
  * turn a green run red against a declaration nobody made.
  *
  * A consequence worth stating, because the temptation on a red run is to reach for it: an innocent
  * collision, a kit local identifier that happens to share a name with a declaration outside the two
  * tiers, must be reported and resolved, not excluded. Two tiers picking the same name for different
  * things is a real finding about the vocabulary, and one ad hoc exclusion here is the first hole in
  * a check whose entire value is that it has none.
  *
  * Four limits the check knowingly carries, stated together so they are counted in one place rather
  * than discovered one at a time. The first three are holes, shapes a real reference could pass
  * through unseen. The fourth runs the other way and can only over deny.
  *
  *   - An ANONYMOUS `given` declares no name for the derivation to catch, and by construction the
  *     reference to it is resolved implicitly, so no source text check can see either end of it.
  *   - A `given` whose name and type are split across lines is not read as a declaration, since
  *     [[TopLevelDeclaration]] matches within one line.
  *   - A backticked name or a symbolic operator name is invisible to this check at both ends: neither
  *     [[TopLevelDeclaration]] nor [[Identifier]] can tokenise either shape, so a declaration written
  *     either way would be neither denied nor discoverable as a violation. Neither shape is used
  *     anywhere in `src/` today.
  *   - While a top level `extension` block is open, [[declaredNames]] reads every indented `def` as a
  *     member of it, a `def` local to a member's own body included, and after a single line form it
  *     stays open until the next column zero line. A name nobody declared at the top level can
  *     therefore enter the denylist. That direction is deliberate: the worst an extra name does is
  *     deny the kit a word and fail a run that names the line, where a missed name is a boundary
  *     breach nothing ever sees.
  */
class KitBoundarySpec extends AnyFlatSpec with Matchers:

  /** The kit tier: the files the rule binds. */
  private val KitTier = List("Kit.scala", "KitMacro.scala")

  /** The domain tier, plus the kit itself: the files a kit reference is allowed to resolve into.
    * Derived from [[KitTier]] rather than restated, so the two cannot drift: writing the kit half
    * twice has a silent failure mode in each direction, a third kit file listed only here never
    * being scanned at all, and listed only there making every legitimate reference to it a red.
    */
  private val AllowedTierFiles = KitTier.toSet ++ Set("Domain.scala", "Caps.scala")

  behavior of "the kit tier"

  it should "name nothing declared outside the domain tier and the kit itself" in {
    val denied = deniedNames()

    // A derivation that silently came back empty would make every assertion below vacuously true,
    // which is one of the two ways this spec could pass while checking nothing at all.
    withClue("no top level declarations were derived from src/, so the check would be vacuous: ") {
      denied should not be empty
    }

    // And a derivation that came back non empty but SHRANK, because someone narrowed the regex the
    // way it was already once too narrow to see `private[litterbox] object LiveProc`, fails here
    // rather than quietly widening what the kit may name. One name per declaration shape that has
    // actually been missed or is most likely to be: a plain object, one behind an access modifier,
    // and the front door.
    withClue(s"the denylist lost a name it must always hold: ${denied.toList.sorted}: ") {
      denied should contain allOf ("Machine", "LiveProc", "LitterBox")
    }

    val violations = KitTier.flatMap { file =>
      val relative = s"src/$file"
      val source   = read(relative)

      // The other way this spec could pass while checking nothing: the stripper blanking the whole
      // file. An unterminated `"""` or `/*` is resolved by blanking to end of file, so a regression
      // in either produces empty text and a silent green. The anchor is a fragment of real code from
      // each file that no comment or literal contains.
      val stripped = stripCommentsAndLiterals(source)
      withClue(s"$relative stripped to text without `${CodeAnchors(file)}`, so the scan is blind: ") {
        stripped should include(CodeAnchors(file))
      }

      scan(relative, stripped, denied) ++ macroResolvedViolations(relative, source, denied) ++
        interpolationViolations(relative, source, denied)
    }

    withClue(report(violations)) {
      violations shouldBe empty
    }
  }

  behavior of "the source text check itself"

  it should "see a denied name in code and not see the same name in prose" in {
    val planted =
      """object Sample:
        |  // Machine.infraFault in a line comment
        |  /* Machine.infraFault in a block comment */
        |  val message = "Machine.infraFault in a literal"
        |  def go(): Nothing = Machine.infraFault("real")
        |""".stripMargin

    scan("planted.scala", stripCommentsAndLiterals(planted), Set("Machine")) shouldBe
      List(("planted.scala", 5, "Machine"))
  }

  it should "see a denied name spliced into an interpolation hole, which the ordinary scan misses" in {
    val planted =
      """object Sample:
        |  def go(): Nothing = fault.raise(s"boom ${Machine.infraFault}")
        |""".stripMargin

    // The ordinary scan is blind to it: the stripper blanks the interpolated literal end to end,
    // hole included, so `Machine` never reaches a single identifier token.
    scan("planted.scala", stripCommentsAndLiterals(planted), Set("Machine")) shouldBe empty

    // interpolationViolations reads the raw source instead, which is where the reference still lives.
    interpolationViolations("planted.scala", planted, Set("Machine")) shouldBe
      List(("planted.scala", 2, "Machine"))
  }

  it should "deny a name declared in a file whose basename matches an allowed root file but whose " +
    "path does not" in {
      val root = Files.createTempDirectory("kit-boundary-basename")
      try
        Files.writeString(root.resolve("Domain.scala"), "object ExemptRoot\n")
        val nested = Files.createDirectories(root.resolve("internal"))
        Files.writeString(nested.resolve("Domain.scala"), "object Registry\n")

        val denied = deniedNames(root)

        // The nested file shares a basename with an allowed root file, `Domain.scala`, but is not
        // one of the four the kit is allowed to name, so its declaration must stay in the denylist.
        denied should contain("Registry")
        // The root file is one of the four, so its declaration must stay exempt.
        denied should not contain "ExemptRoot"
      finally
        Files.walk(root).iterator.asScala.toList.sortBy(_.toString).reverse.foreach(Files.delete)
    }

  it should "derive a name from an indented def inside a top level extension block" in {
    val planted =
      """object Before:
        |  val ignored = 1
        |
        |extension (state: RunState)
        |  def isBlocked: Boolean = true
        |
        |  private def helper: Int = 2
        |
        |object After:
        |  val alsoIgnored = 2
        |""".stripMargin

    val names = declaredNames(stripCommentsAndLiterals(planted))

    // `extension` itself declares no name; `isBlocked` and `helper` are declared inside its indented
    // block, and `Before`/`After` prove the block correctly closes at the next column zero line.
    names should contain allOf ("Before", "isBlocked", "helper", "After")
    names should not contain "ignored"
    names should not contain "alsoIgnored"
  }

  it should "derive a name from the single line form of a top level extension" in {
    val planted =
      """extension (i: Int) def isZero: Boolean = i == 0
        |
        |object After:
        |  val alsoIgnored = 2
        |""".stripMargin

    val names = declaredNames(stripCommentsAndLiterals(planted))

    // The member sits on the opening line itself, where nothing anchored at the start of a line can
    // reach it, and it is a top level name every bit as much as one written under an indented block.
    names should contain allOf ("isZero", "After")
    names should not contain "alsoIgnored"
  }

  it should "derive a name from the same line brace form of a top level extension" in {
    val planted =
      """extension (i: Int) { def isZero: Boolean = i == 0 }
        |
        |object After:
        |  val alsoIgnored = 2
        |""".stripMargin

    val names = declaredNames(stripCommentsAndLiterals(planted))

    // Same reading as the single line form: the brace changes the layout, not the fact that `isZero`
    // is declared at the top level of the file.
    names should contain allOf ("isZero", "After")
    names should not contain "alsoIgnored"
  }

  it should "blank every construct that can hide an identifier, and no code beside them" in {
    // Written as escaped single line literals rather than a triple quoted block, because three of the
    // eight lines are ABOUT quoting and a block would have to escape its way out of itself anyway.
    // Each line reads: what the stripper must swallow, then what it must leave standing.
    val planted = List(
      "val a = \"Hidden // and /* nested\"",       // comment markers inside a literal
      "// \"Hidden\" and Hidden",                  // a quote inside a line comment
      "/* Hidden /* Hidden */ still */ val b = 1", // block comments nest
      "val c = \"\"\"Hidden \"quoted\" tail\"\"\"\"", // a fourth quote belongs to the literal
      "val d = \"esc \\\" Hidden\"",               // a backslash escapes the closing quote
      "val e = '\"'; val f = Kept",                // a char literal holding a quote
      "val g = '\\''; val h = Kept",               // an escaped char literal
      "val i = '{ Kept }"                          // quotation syntax, which is code, not a literal
    ).mkString("\n")

    val stripped = stripCommentsAndLiterals(planted)

    stripped should not include "Hidden"
    // Line count survives because every blank is a space, which is what lets a failure name a line.
    stripped.linesIterator.size shouldBe planted.linesIterator.size
    List("val a", "val b", "val c", "val d", "val e", "val f", "val g", "val h")
      .foreach(kept => stripped should include(kept))
    // Three `Kept`s: two behind a char literal that a naive quote scan would have swallowed to end
    // of line, one behind Scala 3 quotation syntax that must NOT be read as a char literal.
    "Kept".r.findAllMatchIn(stripped).size shouldBe 3
  }

  /** A fragment of real code from each kit file, used to prove the stripper left the file's code
    * behind. Chosen from a declaration rather than a call, since a declaration is the thing least
    * likely to be moved by an unrelated edit.
    */
  private val CodeAnchors =
    Map("Kit.scala" -> "def workflowOf", "KitMacro.scala" -> "object KitMacro")

  /** Every whole word occurrence of a denied name in one file's stripped source, as (file, line,
    * name). Tokenising the line and asking whether the token is denied, rather than searching for
    * each name, is what makes the match a whole identifier: `Machinery` and `preMachine` tokenise to
    * themselves and never reach the denylist.
    */
  private def scan(
      relative: String,
      stripped: String,
      denied: Set[String]
  ): List[(String, Int, String)] =
    for
      (line, idx) <- stripped.linesIterator.zipWithIndex.toList
      token       <- Identifier.findAllMatchIn(line).map(_.matched)
      if denied.contains(token)
    yield (relative, idx + 1, token)

  /** The head name of every symbol this file resolves by fully qualified string, checked against the
    * same denylist. Reads the RAW source, since the whole point is that the reference lives inside a
    * literal the ordinary scan has already blanked. Reading it raw means a `Symbol.requiredModule`
    * call written out inside a doc comment would also be reported, which is the right way round for
    * a check whose value is that it has no holes: an example of the exact call shape sitting in prose
    * is cheap to reword, and a real one hiding is not.
    */
  private def macroResolvedViolations(
      relative: String,
      source: String,
      denied: Set[String]
  ): List[(String, Int, String)] =
    for
      (line, idx) <- source.linesIterator.zipWithIndex.toList
      name        <- MacroResolvedSymbol.findAllMatchIn(line).map(_.group(1))
      if denied.contains(name)
    yield (relative, idx + 1, name)

  /** A denied name spliced into a string interpolation hole, `s"...${Foo.bar}..."`. Reads the RAW
    * source for the same reason [[macroResolvedViolations]] does: the ordinary scan runs after
    * [[stripCommentsAndLiterals]], which blanks an interpolated literal end to end, hole included, so
    * a reference living inside the hole never reaches a single identifier token there. Teaching the
    * stripper to step back into a hole would mean tracking brace depth through arbitrary Scala, the
    * point at which this stops being a lexer and starts being a parser, so this reads the hole's own
    * text directly instead and tokenises it against the same denylist.
    */
  private def interpolationViolations(
      relative: String,
      source: String,
      denied: Set[String]
  ): List[(String, Int, String)] =
    for
      (line, idx) <- source.linesIterator.zipWithIndex.toList
      hole        <- InterpolationHole.findAllMatchIn(line).map(_.group(1))
      token       <- Identifier.findAllMatchIn(hole).map(_.matched)
      if denied.contains(token)
    yield (relative, idx + 1, token)

  private def report(violations: List[(String, Int, String)]): String =
    val lines = violations.map((file, line, name) => s"  $file:$line names $name").mkString("\n")
    s"the kit tier names ${violations.map(_._3).distinct.size} declaration(s) from outside itself " +
      s"and the domain tier:\n$lines\n"

  /** The names of every top level declaration in `src/`, minus the four files the kit is allowed to
    * name. Compared by the path RELATIVE TO `src/`, never by basename: [[AllowedTierFiles]] denotes
    * the four files sitting at the `src` root, not any file merely sharing one of their tails, so
    * `src/internal/Domain.scala` relativizes to `internal/Domain.scala`, misses the exemption and
    * stays in the denylist exactly as any other Tier 2 file would. Anchored at column zero so that
    * "top level" is read straight off the layout: a nested member is indented, and a nested member is
    * not something the kit could name unqualified anyway.
    */
  private def deniedNames(): Set[String] = deniedNames(srcDir())

  private def deniedNames(dir: Path): Set[String] =
    val outsideTiers =
      srcFiles(dir).filterNot(p => AllowedTierFiles.contains(dir.relativize(p).toString))
    withClue("expected src/ to hold files outside the kit and domain tiers: ") {
      outsideTiers should not be empty
    }
    outsideTiers.flatMap(path => declaredNames(stripCommentsAndLiterals(Files.readString(path)))).toSet

  /** Every top level declaration in one already stripped file, `object`, `enum`, `class`, `trait`,
    * `type`, `val`, `var`, `def` and a named `given` matched by [[TopLevelDeclaration]], plus every
    * `def` a top level `extension` declares. `extension` itself declares no name for
    * [[TopLevelDeclaration]] to capture, since the name being extended is not a name the kit could
    * reference the way it references a declaration, so this folds over the file's lines with one flag:
    * a line whose prefix matches [[ExtensionOpen]] opens the block, and the block closes at the next
    * line that starts at column zero. That keeps "top level" read straight off the layout rather than
    * reached for with a parser, the same reading [[TopLevelDeclaration]] itself already relies on.
    *
    * Members are collected on BOTH sides of that opening line, because Scala writes an extension three
    * ways and only one of them puts the member on a later line. `extension (i: Int) def isZero` and
    * `extension (i: Int) { def isZero }` declare a top level name on the opening line itself, where
    * nothing anchored at the start of a line can reach it, so the opening line is searched for
    * [[ExtensionMember]] anywhere in it, while a following line contributes only when
    * [[ExtensionMember]] matches its PREFIX.
    *
    * It over collects, knowingly, rather than being narrowed. Every indented `def` while the block is
    * open contributes, a `def` local to a member's own body included, and after a single line form the
    * flag stays up until the next column zero line, so a name nobody declared at the top level can
    * reach the denylist. Telling a member apart from a local `def` in its body means tracking
    * indentation depth through arbitrary Scala, which is the parser this whole check is written to
    * avoid, and the two mistakes are not symmetrical: an extra name can only deny the kit a word and
    * surface as a red run naming the line, where a missed name is a boundary breach that passes
    * silently. So this one is stated rather than chased.
    */
  private def declaredNames(stripped: String): List[String] =
    val names       = List.newBuilder[String]
    var inExtension = false
    for line <- stripped.linesIterator do
      if inExtension && line.headOption.exists(!_.isWhitespace) then inExtension = false
      if inExtension then ExtensionMember.findPrefixMatchOf(line).foreach(m => names += m.group(1))
      else
        TopLevelDeclaration.findPrefixMatchOf(line).foreach { m =>
          names += Option(m.group(1)).getOrElse(m.group(2))
        }
        if ExtensionOpen.findPrefixMatchOf(line).isDefined then
          ExtensionMember.findAllMatchIn(line).foreach(m => names += m.group(1))
          inExtension = true
    names.result()

  /** The `src` directory of this repository, located the same way for both the real scan and a test
    * fixture pointed at a temporary tree, so the two never resolve `src` two different ways.
    */
  private def srcDir(): Path =
    RepoTree.dir("src").getOrElse(fail("could not locate src by walking up from the JVM cwd"))

  /** Every `.scala` file under `dir`, at any depth. A walk rather than one directory level, so that
    * the day someone adds `src/live/Proc.scala` its declarations are denied to the kit as
    * automatically as `src/Live.scala`'s are today; a one level listing would pass on the tree
    * happening to be flat rather than on the check being right. Dot directories are skipped:
    * `src/.scala-build` and `src/.bsp` are scala-cli's own build output, not this repository's
    * source.
    */
  private def srcFiles(dir: Path): List[Path] =
    val stream = Files.walk(dir)
    try
      stream.iterator.asScala.toList
        .filter(Files.isRegularFile(_))
        .filter(_.getFileName.toString.endsWith(".scala"))
        .filterNot(p => dir.relativize(p).iterator.asScala.exists(_.toString.startsWith(".")))
        .sortBy(_.toString)
    finally stream.close()

  private def read(relative: String): String =
    Files.readString(
      RepoTree
        .file(relative)
        .getOrElse(fail(s"could not locate $relative by walking up from the JVM cwd"))
    )

  /** Everything Scala 3 lets a file declare at column zero as a SINGLE LINE match: `object`, `enum`,
    * `class`, `trait`, `type`, `val`, `var`, `def` and a NAMED `given`, behind any run of modifiers
    * including an access modifier with its optional qualifier. Does not match `extension`, which
    * declares no name of its own; the names are its members, and they sit either on the opening line,
    * in the single line and same line brace forms, or under the indented block a bare opening line
    * starts. [[declaredNames]] folds over those shapes itself rather than teach this regex a second,
    * non column zero shape. The keyword set is deliberately wider than what `src/` uses today:
    * `private[litterbox] object LiveProc` was invisible to an earlier, narrower version of this
    * regex, and a codebase this `given` based would hide a top level `given` the same way.
    *
    * A named `given` is told from an anonymous one by the colon that a name forces:
    * `given ordering: Ordering[Byte] = ...` declares `ordering`, while `given Ordering[Byte] = ...`
    * declares nothing and would otherwise contribute `Ordering`, a type it merely references, to the
    * denylist.
    *
    * A backticked name or a symbolic operator name never matches the `[A-Za-z_][A-Za-z0-9_]*` capture
    * here, and [[Identifier]] cannot tokenise either shape back out of the kit's own code either, so a
    * declaration written either way is neither denied nor discoverable as a violation, counted as a
    * limit below rather than chased: neither shape is used anywhere in `src/` today.
    */
  private val TopLevelDeclaration: Regex =
    (raw"(?:(?:final|sealed|case|abstract|open|transparent|opaque|infix|" +
      raw"private|protected|implicit|inline|lazy|override)(?:\[[A-Za-z0-9_]+\])?\s+)*" +
      raw"(?:(?:object|enum|class|trait|type|val|var|def)\s+([A-Za-z_][A-Za-z0-9_]*)" +
      raw"|given\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\[[^\]]*\])?\s*(?:\([^)]*\))?\s*:)").r

  /** The opening line of a top level `extension` block, told apart from ordinary code by the keyword
    * alone: `extension` declares no name of its own, so unlike [[TopLevelDeclaration]] this has no
    * capture group, only a boundary [[declaredNames]] folds against.
    */
  private val ExtensionOpen: Regex =
    (raw"(?:(?:final|sealed|case|abstract|open|transparent|opaque|infix|" +
      raw"private|protected|implicit|inline|lazy|override)(?:\[[A-Za-z0-9_]+\])?\s+)*" +
      raw"extension\b").r

  /** A `def` declared by a top level `extension`, which is the only member shape such a block can
    * hold. [[declaredNames]] applies it anywhere in the opening line, so the single line and the same
    * line brace forms are read, and against the prefix of each following line while the block is open.
    * No file under `src/` writes an `extension` at all today, so nothing currently in the denylist
    * rests on this regex; it is here because the shape is legal at the top level and a derivation
    * blind to it would fail open the day the first one lands.
    */
  private val ExtensionMember: Regex =
    (raw"\s+(?:(?:final|sealed|case|abstract|open|transparent|opaque|infix|" +
      raw"private|protected|implicit|inline|lazy|override)(?:\[[A-Za-z0-9_]+\])?\s+)*" +
      raw"def\s+([A-Za-z_][A-Za-z0-9_]*)").r

  /** A symbol this package resolves by fully qualified name from a macro. Only the head name is
    * captured, since `in.rcard.litterbox.Edge.To` is a reference to `Edge` as far as the tier
    * question goes, and `To` is a member the denylist never held.
    */
  private val MacroResolvedSymbol: Regex =
    raw"""Symbol\.required(?:Module|Class|Method|Field)\("in\.rcard\.litterbox\.([A-Za-z_][A-Za-z0-9_]*)""".r

  /** The text of a string interpolation hole, `${...}`, captured without its braces so
    * [[interpolationViolations]] can tokenise what is inside. Does not nest, since a hole containing a
    * `}` of its own, a block expression spliced into a message, is not a shape either kit file uses
    * today; [[Identifier]] would still find every denied token in whatever text such a match does
    * capture, so a nested hole degrades to under matching the tail of the expression rather than
    * missing it outright.
    */
  private val InterpolationHole: Regex = raw"\$$\{([^}]*)\}".r

  /** A Scala identifier as this check cares about it. `$` is part of the token rather than a break so
    * that a compiler generated `Machine$1` is not read as a reference to `Machine`.
    */
  private val Identifier: Regex = raw"[A-Za-z_$$][A-Za-z0-9_$$]*".r

  /** Every comment and string literal replaced by spaces, newlines kept, so the result is the CODE of
    * the file at its original line numbers. A lexer approximation on purpose: the alternative is a
    * parser, and the five constructs below are what can hide an identifier from a regex in this
    * repository's source.
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

    // A CHAR literal, which matters for exactly one member of the set: `'"'` holds a quote that the
    // plain quote branch below would read as a string opener and blank forward from, hiding the rest
    // of that line of real code. Unlike an unbalanced `/*`, which scalac's own scanner turns into a
    // compile failure, that one compiles clean, so nothing else would ever catch it. Recognised by
    // shape and only by shape, so Scala 3 quotation syntax is untouched: `'{`, `'[` and `'ident` have
    // no closing quote where a char literal does, fail this test, and fall through as the code they
    // are. Returns -1 for "not a char literal".
    def endOfCharLiteral(start: Int): Int =
      val closing =
        if source.startsWith("'\\u", start) then start + 7
        else if start + 1 < n && source.charAt(start + 1) == '\\' then start + 3
        else start + 2
      if closing < n && source.charAt(closing) == '\'' then closing + 1 else -1

    var i = 0
    while i < n do
      val next =
        if source.startsWith("//", i) then Some(endOfLineComment(i))
        else if source.startsWith("/*", i) then Some(endOfBlockComment(i))
        else if source.startsWith("\"\"\"", i) then Some(endOfTripleQuoted(i))
        else if source.charAt(i) == '\'' then
          endOfCharLiteral(i) match
            case -1  => None
            case end => Some(end)
        else if source.charAt(i) == '"' then Some(endOfQuoted(i))
        else None
      next match
        case Some(end) =>
          blank(i, math.min(end, n))
          i = math.max(end, i + 1)
        case None => i += 1

    String(out)
