package com.example.consumer

import in.rcard.litterbox.{Init, LitterBox}
// Repo-only test plumbing, in a package of its own so that reaching for it here is visibly NOT this
// file claiming a consumer can see it: nothing under `in.rcard.litterbox.testsupport` ships in either
// published artifact, and the scaffold body above, the part this file actually proves, imports
// `in.rcard.litterbox` alone.
import in.rcard.litterbox.testsupport.RepoTree
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** The genuine CONSUMER half of issue #43's "the scaffolded `loop.scala` runs under scala-cli and
  * reproduces the shipped pipeline" acceptance criterion, following the exact reasoning
  * `ConsumerBoundarySpec`'s own doc (`test/ConsumerBoundarySpec.scala`) already spends its first
  * paragraphs establishing for `AgentDispatch`: a snippet compiled from a file that lives inside
  * `in.rcard.litterbox` is unavoidably LIBRARY code, no matter what it claims to be, so nothing
  * inside that package can prove a genuinely foreign package can reach `LitterBox`/`LoopGraph`. This
  * file is declared `package com.example.consumer`, a physically separate compilation unit standing
  * in for any package this library does not own, and the code below it is exactly what
  * `resources/scaffold/loop.scala.txt` renders, reproduced here as ordinary, top level, REALLY
  * COMPILED Scala rather than a string handed to `scala.compiletime.testing.typeCheckErrors`: this
  * proof is about `scala-cli test .`'s own compiler actually accepting the scaffold's body in a
  * foreign package, not about what a speculative retype says it would do.
  *
  * `scala-cli test .` cannot go further than that and actually resolve `//> using dep
  * in.rcard::litter-box:$Version`. That coordinate does reach Maven Central now (issue #41), but it
  * only reaches it once the tag naming that exact version has been pushed and `release.yml`'s
  * `publish` job has run, which is strictly AFTER this suite runs on the commit that will become
  * that tag; resolving it here would also make the suite hit the network, which TEST.md forbids
  * outright. So the ONE line of the real scaffold this file cannot reproduce stays its own
  * `//> using dep` header. What is left, and what this file actually proves, is that every line
  * AFTER that header, the whole body a consumer reads, type checks against the real public API from
  * outside this library's own package.
  *
  * Nothing here pins the two copies together with a THIRD, independently hand typed string. The
  * block below, between the two `BEGIN`/`END SCAFFOLD BODY` markers, is REAL code the build compiles;
  * `compiledScaffoldBody`, near the bottom of this file, reads THIS file's own source back off disk
  * at test run time and slices out exactly that marked region, so the string compared against what
  * `Init.plan` renders IS the block scala-cli just finished compiling, never a second copy of it
  * typed out again by hand. An earlier version of this file kept that hand typed third copy, and
  * issue #43 review round two's MAJOR finding is what closed it: a hand edit to the compiled block
  * alone, with `resources/scaffold/loop.scala.txt` left unedited, left the hand typed copy stale and
  * still green, so the one drift this file exists to catch was never actually caught. Deriving the
  * string from the compiled block's own text, rather than duplicating it a second time, is what makes
  * that drift a red test instead of a silent gap.
  *
  * Deliberately NOT invoked (TEST.md: everything under `test/` stays Docker free, network free and
  * credential free): `@main def loop` below is declared, never called, and `graph`/`LitterBox.run`
  * are never reached at runtime by anything in this file. Compiling the declaration is the whole
  * proof; running it is exactly the part this suite cannot do, since a real run resolves the
  * published coordinate over the network and then walks a pipeline that wants Docker, `gh` and
  * Claude credentials, none of which anything under `test/` is allowed to touch.
  */

// ---- BEGIN SCAFFOLD BODY (must stay byte identical to resources/scaffold/loop.scala.txt, minus its
//      leading `{{USING_DEP}}` placeholder line, once rendered) --------------------------------

// `litter-box init` scaffolds this file so the pipeline you actually run starts out visible, not
// hidden behind a binary you only configure through flags (issue #43).
//
// Two things worth saying once here, rather than left for you to assume:
//
// - this dependency comes from Maven Central, published by the very tag that built the `litter-box`
//   you ran `init` with (issue #41), so the version pinned above and that binary are always one
//   release rather than two things you have to keep in step yourself. Nothing to install or
//   configure for it: `scala-cli` fetches it on the first run of this file. Releases cut before
//   issue #41 landed published no library at all, so pinning one of those older versions here
//   resolves nowhere.
// - `graph` below names which pipeline this run walks. `LitterBox.shipped` is the default, the exact
//   pipeline `lb` itself runs; `LitterBox.graph(...)` is how you write your own, a factory that hands
//   back a `LoopGraph` built from your own `Node`s and one `Plan`, the single table naming every edge
//   of your graph, without widening anything this library keeps closed (`LoopGraph` stays sealed; see
//   its own scaladoc for why a factory, never a subclass, is the door in). This file states the SHAPE
//   of a run; shape lives here.
//   Everything about how any one run of that shape actually behaves lives in
//   `.litter-box/config.conf` instead (RFC #26 decision 17). One narrow exception is named, not
//   hidden: a graph's `dispatchBudget` is a `Config => Int`, so the intended form reads the number
//   from `config.conf`, but a constant such as `_ => 42` type checks too, the one way a budget
//   number can still land here instead of there. `LitterBox.graph`'s own doc, `src/LitterBox.scala`,
//   states this once and every other mention of it points back there.

import in.rcard.litterbox.{LitterBox, LoopGraph}

val graph: LoopGraph = LitterBox.shipped

@main def loop(args: String*): Unit = LitterBox.run(graph, args)

// ---- END SCAFFOLD BODY --------------------------------------------------------------------------

class ScaffoldedLoopBoundarySpec extends AnyFlatSpec with Matchers:

  /** This file's own path, found by walking up from the JVM's own cwd. `RepoTree`
    * (`test/RepoTree.scala`) owns that walk and states why it is not `git rev-parse
    * --show-toplevel`: this repo's own sandboxed gate materialises a `git archive` tree with no
    * `.git`, so asking git for the repo root the way `Main.resolveRepoRoot` does is not an option
    * every runner of this suite has.
    */
  private def thisFile(): Path =
    RepoTree
      .file("test/ScaffoldedLoopBoundarySpec.scala")
      .getOrElse(
        fail("could not locate test/ScaffoldedLoopBoundarySpec.scala by walking up from the JVM cwd")
      )

  /** The exact text between the two `BEGIN`/`END SCAFFOLD BODY` markers above, read back from this
    * file's own source rather than typed out a second time (issue #43 review round two, MAJOR: this
    * is what closes the gap a hand typed third copy left open, this file's own doc above has the
    * full reasoning). `beginIdx + 2` skips the marker's own two comment lines and lands on the blank
    * line right after them, which stands in for `resources/scaffold/loop.scala.txt`'s own blank line
    * between its `{{USING_DEP}}` placeholder and the comment that follows it (`strippedRenderedScaffold`
    * below strips the placeholder line entirely rather than replacing it with a blank one, so that
    * blank line is the render's own first surviving line too, and the two need to line up here for the
    * byte equality test below to mean anything). `endIdx - 1` excludes the blank line directly above
    * the END marker, which separates the marker from the body and is not part of the body itself.
    */
  private def compiledScaffoldBody(): String =
    val lines    = Files.readString(thisFile()).split("\n", -1).toIndexedSeq
    // Matched against the marker's own COMMENT PREFIX, `// ---- BEGIN`/`// ---- END`, not merely the
    // words "BEGIN SCAFFOLD BODY": this file's own doc, above, names the markers in prose too, and a
    // plain `.contains` match landed on that prose line instead of the real marker the first time
    // this was written, well short of the actual END marker nine lines above this very function.
    val beginIdx = lines.indexWhere(_.startsWith("// ---- BEGIN SCAFFOLD BODY"))
    val endIdx   = lines.indexWhere(_.startsWith("// ---- END SCAFFOLD BODY"))
    if beginIdx < 0 || endIdx < 0 then
      fail("could not find the BEGIN/END SCAFFOLD BODY markers in this file's own source")
    lines.slice(beginIdx + 2, endIdx - 1).mkString("\n")

  /** The rendered scaffold, straight off `Init.plan`, with its `//> using` header stripped so it
    * lines up with `compiledScaffoldBody` above. That header is the one line no compile here can
    * cover: `//> using` is a scala-cli launcher directive, read before a single line of Scala
    * compiles, not Scala source, so it never reaches `scala.compiletime.testing.typeCheckErrors` or
    * this file's own compiled BEGIN/END body, and pinning its literal text a second time would only
    * be a duplicated copy of what `InitSpec` already asserts against `LitterBox.Coordinate`. The
    * COUNT stands in for the compile that region cannot get: asserting there is exactly one
    * `//> using` line, and that it equals the coordinate-derived header, is what would catch a
    * second, unreviewed directive (a stray `//> using dep`, a `//> using scala` pinning a different
    * compiler) added to `loop.scala.txt`, matched by its TRIMMED content so an indented directive
    * counts too (issue #43 review round three, MAJOR: matching only an unindented `//> using` missed
    * one indented into the leading comment block, which scala-cli still honours there). Issue #43
    * review round two, MAJOR: a blanket `filterNot(_.startsWith("//> using"))` here stripped every
    * `//> using` line the render produced, not just this header, so a second directive slipped in
    * beside it left the byte equality guard below green, exactly the region the guard exists to
    * police left uncovered. Not a function of what `init` detected (`InitSpec`'s own "same file for
    * every detection result" test), so an arbitrary `Detected` is enough here.
    */
  private def strippedRenderedScaffold(): String =
    val rendered = Init
      .plan(Init.Detected(buildTool = None, remote = None, jdk = None))
      .toMap
      .getOrElse(".litter-box/loop.scala", fail("init no longer scaffolds .litter-box/loop.scala"))
    val lines      = rendered.linesIterator.toIndexedSeq
    val usingLines = lines.filter(_.trim.startsWith("//> using"))
    usingLines shouldBe List(s"//> using dep ${LitterBox.Coordinate}")
    lines.tail.mkString("\n")

  "the compiled copy of the scaffold body" should "typecheck against the real public API from a foreign package" in {
    // If this file did not compile at all, `scala-cli test .` would already have failed before this
    // test ever ran. Compiling alone does not decide which `LoopGraph` the foreign-package `val
    // graph` above resolves to, though: asserting `LitterBox.shipped` here, from that same foreign
    // package, pins that `graph` is genuinely the shipped pipeline the compiler accepted, not merely
    // some value of the right type.
    graph shouldBe LitterBox.shipped
  }

  it should "stay byte identical to what Init.plan actually renders, minus the //> using header" in {
    compiledScaffoldBody() shouldBe strippedRenderedScaffold()
  }

  it should "refuse to typecheck a consumer's own class implementing LoopGraph, with no access modifier written at all" in {
    // `ConsumerBoundarySpec`'s own convention (`test/ConsumerBoundarySpec.scala`), replayed for the
    // `LoopGraph` boundary issue #43 adds: a foreign package can PASS a `LoopGraph`
    // (`LitterBox.shipped`) to `LitterBox.run`, never WRITE one, because `LoopGraph` is `sealed`
    // (`src/LitterBox.scala`'s own doc). Nothing before this test asserted that against a genuinely
    // foreign compilation unit; it only held in prose (`src/LitterBox.scala:9`, `ARCHITECTURE.md`).
    // Bare top level, no access modifier, the natural shape an unaware consumer would reach for
    // first, the same shape `ConsumerBoundarySpec`'s own `AgentDispatch` negative twin uses.
    //
    // No abstract member overridden here on purpose: the real, live compiler message for this exact
    // snippet (checked by running it, not guessed) is the single sealed violation below, with no
    // second error about unimplemented members riding along, so there is nothing to gain from typing
    // out `workflow`/`shape` stubs that would only add `Faulting`/`Runner.Ledger` visibility noise of
    // their own to a test that is not about those two types.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |class ForeignGraph extends LoopGraph
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("sealed trait LoopGraph")
    messages should include("different source file")
    messages should not include "weaker access privileges"
  }
