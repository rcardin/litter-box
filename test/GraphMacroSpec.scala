package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import Caps.given

/** The compile time half of RFC #26 decision 16 (issue #39): `checkedShape` (`Kit.scala`) and its
  * macro implementation `KitMacro.checkShapeImpl` (`KitMacro.scala`). `GraphValidationSpec` already
  * covers the RUNTIME rule this macro mirrors (`Runner.validate`); this file's job is only the
  * compile time half, so it does not derive `Runner.validate`'s own reachability cases all over again, only
  * proves the macro finds the same shape of violation earlier, with a message a human can act on, and
  * that it never blocks a graph it cannot fully read.
  *
  * The negative cases below are genuine compile failures, so, exactly like `RunnerSpec`'s own
  * `AgentDispatch.Judged` section and `GraphValidationSpec`'s own constructor test, each one has to
  * live inside a string literal handed to `scala.compiletime.testing.typeCheckErrors`: writing the
  * failing snippet as ordinary code in this file would just stop this whole suite from compiling,
  * proving nothing about the macro. Every snippet imports `in.rcard.litterbox._` and
  * `in.rcard.litterbox.Caps.given` the same way those two files' own snippets do, for the same reason:
  * `typeCheckErrors` checks its argument spliced in at THIS call site's own lexical position, so
  * every name a snippet uses still has to be reachable from here.
  */
class GraphMacroSpec extends AnyFlatSpec with Matchers:

  private def plainNode(name: String): Node[Unit, Unit] =
    Node[Unit, Unit](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )

  private def reviewedNode(name: String): Node[Unit, AgentDispatch.Judged[Unit]] =
    Node[Unit, AgentDispatch.Judged[Unit]](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ()))
    )

  // A node's own INPUT type is what the macro reads a guard off (`RequiresReviewInput`, `Kit.scala`),
  // so a guarded node in this file needs an input type of its own to carry that marker, rather
  // than the bare `Unit` every plain/reviewed node above shares.
  private case class PrInput() extends RequiresReviewInput

  private def guardedNode(name: String): Node[PrInput, Unit] =
    Node[PrInput, Unit](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(()),
      guard = Guard.RequiresReview
    )

  // ---- a genuinely safe graph compiles, and runs -----------------------------------------------

  "checkedShape" should "compile a literal Shape whose only path into a guarded node crosses a reviewer first, and Runner.run walks it to completion" in {
    // `PrInput` has to extend `RequiresReviewInput` HERE, at its own declaration, not merely be named
    // `PrInput`: the marker trait is what this macro keys off, never the type's own name, and
    // `Node.apply`'s own `guard` argument still has to be set to match by hand (`RequiresReviewInput`'s
    // own doc, `Kit.scala`), which the guarded node below does explicitly.
    case class ReviewedPrInput() extends RequiresReviewInput

    val pick: Node[Unit, Unit]                             = plainNode("Pick")
    val review: Node[Unit, AgentDispatch.Judged[Unit]]      = reviewedNode("Review")
    val openPr: Node[ReviewedPrInput, Unit] = Node[ReviewedPrInput, Unit](
      name = "OpenPr",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(()),
      guard = Guard.RequiresReview
    )

    val shape: Shape = checkedShape(
      Shape(
        entry = List(pick),
        transitions = List(Transition(pick, review), Transition(review, openPr))
      )
    )

    val wf: Workflow[Unit] = Workflow(
      "reviewed-graph",
      start = (_: Unit) =>
        Next.Goto(
          pick,
          (),
          _ => Next.Goto(review, (), _ => Next.Goto(openPr, ReviewedPrInput(), _ => Next.Finish(LoopExit.Success)))
        ),
      shape = shape
    )

    val world = new TestWorld
    val clock = new FakeClock(List(0L, 0L, 0L, 0L))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(1)
      Runner.run(wf, ())

    result shouldBe Right(LoopExit.Success)
    world.callCount("dispatch REVIEW") shouldBe 1
  }

  // ---- degrades gracefully on anything it cannot read as a literal ------------------------------

  it should "compile (and fall back to Runner.validate at runtime) when the Shape argument is not a literal the macro can read" in {
    // `dangerousShape` below has the exact same violation `checkedShape` rejects at compile time in
    // the negative tests further down, an unreviewed path into a guarded node, but it is built by a
    // FUNCTION CALL, not a literal `Shape(entry = List(...), transitions = List(...))` at this call
    // site. `checkShapeImpl`'s own doc states plainly why that has to compile rather than fail: a
    // macro that aborted on anything it merely could not parse, rather than only on a genuine
    // violation it could prove, would make `checkedShape` unsafe to use on any graph not yet written
    // fully by hand as a literal.
    def dangerousShape(): Shape =
      val pick  = plainNode("Pick")
      val guard = guardedNode("Guarded")
      Shape(entry = List(pick), transitions = List(Transition(pick, guard)))

    val shape: Shape = checkedShape(dangerousShape())

    // The macro did not, and could not, catch this one: `Runner.validate` still does, unchanged,
    // proving the fallback is real rather than merely "no crash".
    val violations = Runner.validate(shape)
    violations should have size 1
    violations.head should include("Guarded")
  }

  // ---- checkedShape's own PARSING was widened alongside the mandatory check's, and that is a --------
  // ---- source-breaking change to checkedShape ITSELF, pinned here rather than left only in prose -----
  // ---- (issue #43 review round 2, MAJOR M3: three doc paragraphs said checkedShape was "UNCHANGED", -
  // ---- which was false, since literalListElements/stablePathKey/companionApplyArgs/parseTransition ---
  // ---- are shared between checkedShape and checkedPlan, never mandatory-check-only) ------------------

  it should "refuse to typecheck a literal Shape whose only unreadable piece was a Nil transitions list reaching an unreviewed guarded node, a shape that used to compile clean under checkedShape before this walk learned to read Nil" in {
    // `entry = List(OpenPr)` alone, `OpenPr` marker-guarded and unreviewed, `transitions = Nil`: the
    // violation sits entirely in `entry` (a path of length one, `OpenPr` itself, with no reviewed node
    // anywhere before it), so this shape needs no `transitions` edge to demonstrate it at all, only a
    // `Nil` the walk has to actually PARSE rather than decline on to ever reach `entry` and find it.
    // Before issue #43 review's own `Nil` recognition (`KitMacro.literalListElements`), `transitions =
    // Nil` fell back exactly like a variable or a `:+`/`++` chain would, and `parseShape`'s own fold
    // declines the WHOLE shape the moment ONE piece is unreadable (`parseShape`'s own doc,
    // `KitMacro.scala`, has the reasoning), `entry` included even though `entry` itself was perfectly
    // literal: so this exact snippet compiled clean under `checkedShape` before this fix, confirmed by
    // running it against the tree immediately before issue #43 review's own `Nil` fix and finding no
    // error at all. It fails now, under `checkedShape` itself, its mandatory sibling `checkedPlan`
    // untouched by this particular test, because `Nil` recognition is not mandatory-check-only, the
    // exact fact three doc paragraphs got wrong (`Kit.scala`'s `checkedShape`/`checkedPlan` doc,
    // `LitterBox.scala`'s `LitterBox.graph` doc, `KitMacro.literalListElements`'s own doc, all corrected
    // by this same review round).
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |
        |val shape = checkedShape(Shape(entry = List(OpenPr), transitions = Nil))
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("'OpenPr'")
    messages should include("review")
  }

  // ---- negative: missing reviewer entirely -------------------------------------------------------

  it should "refuse to typecheck a literal Shape whose only path into a guarded node has no reviewer anywhere on it" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Implement: Node[Unit, Unit] = Node(
        |  name = "Implement", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Pick),
        |    transitions = List(Transition(Pick, Implement), Transition(Implement, OpenPr))
        |  )
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("Pick -> Implement -> OpenPr")
    messages should include("'OpenPr'")
    messages should include("review")
    messages should not include "quotes.reflect"
  }

  // ---- negative: a reviewer on only one branch of a fork -----------------------------------------

  it should "refuse to typecheck a literal Shape where a fork has a reviewer on one branch but not the other, both reaching the same guarded node" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Review: Node[Unit, AgentDispatch.Judged[Unit]] = Node(
        |  name = "Review", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None,
        |  run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ()))
        |)
        |val Implement: Node[Unit, Unit] = Node(
        |  name = "Implement", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Pick),
        |    transitions = List(
        |      Transition(Pick, Review),
        |      Transition(Pick, Implement),
        |      Transition(Review, OpenPr),
        |      Transition(Implement, OpenPr)
        |    )
        |  )
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    // The reviewed branch (Pick -> Review -> OpenPr) must not appear as an offending path; only the
    // branch that skips Review is a violation.
    messages should include("Pick -> Implement -> OpenPr")
    messages should not include "Pick -> Review -> OpenPr"
    messages should include("'OpenPr'")
  }

  // ---- negative: a cycle that bypasses the reviewer ------------------------------------------------

  it should "refuse to typecheck a literal Shape where a cycle among unreviewed nodes reaches a guarded node, and terminate rather than loop" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Implement: Node[Unit, Unit] = Node(
        |  name = "Implement", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Pick),
        |    transitions = List(
        |      Transition(Pick, Implement),
        |      Transition(Implement, Pick),
        |      Transition(Implement, OpenPr)
        |    )
        |  )
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("Pick -> Implement -> OpenPr")
    messages should include("'OpenPr'")
  }

  // ---- superseded: the zero argument method allowance itself turned out unsound (issue #39 review --
  // ---- round 3, B2) and is dropped entirely; the idiom now falls back rather than being read --------

  it should "compile (and fall back to Runner.validate at runtime) when the Shape argument's nodes are referenced through repeated zero argument method calls" in {
    // `pick()`/`impl()`/`openPr()` below are the shape an earlier version of `identifyRef` trusted: a
    // call with no argument in any curried layer, keyed by the called method alone on the theory that
    // a zero argument call is fully determined by which method it calls. Issue #39 review round 3, B2,
    // found that theory false in general, a zero argument call can still be a STATEFUL factory,
    // building a different `Node` on every call (the two stateful factory cases below have the
    // concrete repro), so the allowance is dropped entirely rather than patched: from here on, a
    // method call reference falls back unless it resolves into `Node.apply` itself with a literal
    // `name` (`identifyRef`'s own doc, `KitMacro.scala`). This particular snippet is deterministic and
    // genuinely safe to read the old way, but the macro cannot tell that apart from the stateful cases
    // by looking at the call site alone, so it now falls back here too, and still compiles clean:
    // `Runner.validate` remains the check that actually walks it, and would find the real violation
    // this graph has (no reviewer between `Pick` and `OpenPr`) at startup instead of at compile time.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |def pick(): Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |def impl(): Node[Unit, Unit] = Node(
        |  name = "Implement", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |def openPr(): Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(pick()),
        |    transitions = List(Transition(pick(), impl()), Transition(impl(), openPr()))
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  // ---- negative (previously false positive): two distinct receivers of the same instance member ---
  // ---- must not collapse into one node (issue #39 review round 3, B1) -------------------------------

  it should "compile a literal Shape whose entry/transitions reference the same instance val member through two distinct receivers, each building a differently named node" in {
    // `Holder`'s own `node` is one member symbol shared by every instance of `Holder`; keying a
    // reference by that member symbol alone, the bug B1 reports, throws the receiver away, so `a.node`
    // and `b.node` would read as the SAME node even though they build two genuinely different `Node`
    // values here, "Implement" and "CommitAndPush", if this walk still tried to key an instance-
    // qualified receiver at all. It no longer does (issue #43 review round 4, Tier 1, correcting this
    // comment rather than only the source it describes): between B1's own fix and this one, this walk
    // DID key `a.node`/`b.node` distinctly, by chaining through the receiver's own symbol, and this
    // test used to demonstrate a genuine BFS walking four real nodes with a reviewer in the middle and
    // finding nothing wrong. Round 4 found that receiver-chaining scheme unsound in a different, related
    // case (`stablePathKey`'s own doc, `KitMacro.scala`, has the three sidesteps) and replaced it with a
    // blanket decline of every instance-qualified receiver, `a.node`/`b.node` included. So this shape
    // now DECLINES instead of being walked, and `errors shouldBe empty` below holds for a different
    // reason than it used to: `checkedShape` is the LENIENT entry point, so a decline falls back
    // silently rather than aborting, exactly as it does for a shape this walk never recognised at all.
    // `Runner.validate` is what actually confirms the real four node path is fine, unconditionally,
    // every tick, never this macro for an instance-qualified reference any more.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |class Holder(nm: String):
        |  val node: Node[Unit, Unit] = Node(
        |    name = nm, cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |
        |val a = Holder("Implement")
        |val b = Holder("CommitAndPush")
        |val Review: Node[Unit, AgentDispatch.Judged[Unit]] = Node(
        |  name = "Review", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None,
        |  run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ()))
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(a.node),
        |    transitions = List(
        |      Transition(a.node, Review),
        |      Transition(Review, b.node),
        |      Transition(b.node, OpenPr)
        |    )
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  it should "compile a literal Shape whose entry/transitions reference the same zero argument instance method through two distinct receivers" in {
    // The identical merge as the val member case above, one layer further: `a.node()` and `b.node()`
    // reach `identifyRef` through the APPLY branch, not the bare Ident/Select branch, and the old zero
    // argument allowance keyed them by the CALLED METHOD symbol alone, still throwing the receiver
    // away.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |class Holder(nm: String):
        |  def node(): Node[Unit, Unit] = Node(
        |    name = nm, cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |
        |val a = Holder("Implement")
        |val b = Holder("CommitAndPush")
        |val Review: Node[Unit, AgentDispatch.Judged[Unit]] = Node(
        |  name = "Review", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None,
        |  run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ()))
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(a.node()),
        |    transitions = List(
        |      Transition(a.node(), Review),
        |      Transition(Review, b.node()),
        |      Transition(b.node(), OpenPr)
        |    )
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  it should "compile a literal Shape whose node reference is a zero argument call to a stateful factory building a different node on every call" in {
    // `stage()` genuinely returns a DIFFERENT `Node` on each call, "Implement" the first time and
    // "CommitAndPush" every time after; a zero argument call is not proof of a stable identity, only
    // the theory the old rule relied on. `Runner.validate` reads the graph off the actual constructed
    // `Node` values, so it sees whatever this factory really built; whatever it built, the point this
    // test pins is narrower and does not depend on the exact runtime names: the macro must not abort
    // compiling this literal by pretending every `stage()` call is the same node.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |var callCount = 0
        |def stage(): Node[Unit, Unit] =
        |  callCount += 1
        |  val n = if callCount == 1 then "Implement" else "CommitAndPush"
        |  Node(
        |    name = n, cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |val Review: Node[Unit, AgentDispatch.Judged[Unit]] = Node(
        |  name = "Review", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None,
        |  run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ()))
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(stage()),
        |    transitions = List(Transition(stage(), Review), Transition(Review, stage()), Transition(stage(), OpenPr))
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  it should "compile a literal Shape whose node reference is a paramless, no parens call to a stateful factory building a different node on every call" in {
    // `stage`'s own twin: no parameter list AT ALL, not even empty parens, so this reference reaches
    // `identifyRef` through the Ident branch, the identical branch a stable `val` goes through, not
    // the Apply branch the parens twin above goes through. The old rule trusted this unconditionally,
    // never checking whether the symbol behind an `Ident`/`Select` was a `val` or a `def` at all.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |var callCount = 0
        |def stage: Node[Unit, Unit] =
        |  callCount += 1
        |  val n = if callCount == 1 then "Implement" else "CommitAndPush"
        |  Node(
        |    name = n, cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |val Review: Node[Unit, AgentDispatch.Judged[Unit]] = Node(
        |  name = "Review", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None,
        |  run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ()))
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(stage),
        |    transitions = List(Transition(stage, Review), Transition(Review, stage), Transition(stage, OpenPr))
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  // ---- negative (previously false positive): a consumer's own varargs helper, not List.apply itself -
  // ---- must not be read as though every argument were a literal entry element (issue #39 review, M1) -

  it should "compile a literal Shape whose entry is built by a consumer's own varargs helper, not List.apply itself" in {
    // `firstOnly` takes the same varargs shape `literalListElements` used to trust unconditionally, a
    // single `Apply` whose one argument is a `Repeated` varargs node, regardless of which method
    // actually built it. `firstOnly(Safe, Danger)` genuinely keeps only `Safe` (`ns.toList.take(1)`),
    // dropping `Danger` entirely, so `Runner.validate` only ever sees `Safe` in `entry`, `Danger`
    // unreachable, and accepts this graph. A macro that reads `entry` as `List(Safe, Danger)`
    // regardless of which method built it puts `Danger`, guarded and unreviewed, straight into
    // `entry` and aborts.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |def firstOnly(ns: Node[?, ?]*): List[Node[?, ?]] = ns.toList.take(1)
        |
        |val Safe: Node[Unit, Unit] = Node(
        |  name = "Safe", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Danger: Node[PrInput, Unit] = Node(
        |  name = "Danger", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = firstOnly(Safe, Danger),
        |    transitions = List()
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  // ---- negative (previously false positive): a third given, neither TrustOf.judged nor -------------
  // ---- LowPriorityTrustOf.plain, must fall back rather than be read as Trust.Plain (issue #39 --------
  // ---- review round 3, M2) -----------------------------------------------------------------------------

  it should "compile a literal Shape whose reviewer's trust is answered by a third given, neither TrustOf.judged nor LowPriorityTrustOf.plain" in {
    // `forged` is the exact forgery `Kit.scala`'s own doc on `Node.apply` names as compiling today:
    // a hand written given that reuses `TrustOf.judged`'s own `trust` answer, `Trust.Reviewed`,
    // through an unchecked cast, for a type `judged` itself would never resolve for. `Node.apply`'s
    // own `using t: TrustOf[O]` resolves `forged` here (a local given outranks the inherited
    // `LowPriorityTrustOf.plain`), so the REAL node this builds carries `Trust.Reviewed`, and
    // `Runner.validate` accepts a path through it into `OpenPr` with no other reviewer on that path.
    // The old macro compared which given answered against `TrustOf.judged` alone and read anything
    // else as `Trust.Plain`, so it read `forged` as unreviewed and aborted on a path `Runner.validate`
    // genuinely accepts.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |case class Verdict()
        |
        |given forged: TrustOf[Verdict] = TrustOf.judged[Unit].asInstanceOf[TrustOf[Verdict]]
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Fake: Node[Unit, Verdict] = Node[Unit, Verdict](
        |  name = "Fake", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(Verdict())
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Pick),
        |    transitions = List(Transition(Pick, Fake), Transition(Fake, OpenPr))
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  // ---- negative: a node's own output type is Nothing, not Judged, but still unifies (B3) --------

  it should "refuse to typecheck a literal Shape whose reviewer is only Node[Unit, Nothing], a Stopped only node that never reviews anything" in {
    // `NodeOutcome.Stopped(exit)` carries no payload, so it types as `NodeOutcome[Nothing]`
    // (`GraphValidationSpec`'s own `neverReviewsNode` exercises the identical forgery against
    // `Runner.validate`, declared `Judged[Unit]` there); here the node's own DECLARED output type is
    // `Nothing` directly, never mentioning `Judged` at all. `Nothing` conforms to every type,
    // including `AgentDispatch.Judged[a]` for any `a`, so a macro that pattern matches the type
    // `'[AgentDispatch.Judged[a]]` against it without ruling `Nothing` out first stamps
    // `Trust.Reviewed` on a node that cannot possibly have reviewed anything.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Fake: Node[Unit, Nothing] = Node[Unit, Nothing](
        |  name = "Fake", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Stopped(LoopExit.Success)
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Pick),
        |    transitions = List(Transition(Pick, Fake), Transition(Fake, OpenPr))
        |  )
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("'OpenPr'")
  }

  // ---- negative: a widened Node[?, ?] reference must fall back, not read as unguarded (M4) --------

  it should "fall back for the whole shape, rather than silently reading a widened Node[?, ?] reference as unguarded, when an unrelated violation sits elsewhere in the same literal" in {
    // `Weird` below is built with a concrete, guarded `Node[WeirdInput, Unit]`, exactly like `OpenPr`
    // in the same shape, but bound at a WIDENED type. The macro can only ever see `Node[?, ?]` at
    // `Weird`'s own reference, never the real `WeirdInput`, so it has no honest way to know `Weird`
    // requires a review at all; reading the widened reference as `Guard.Open` instead of declining is
    // what let a real macro implementation stay confident enough to still report `OpenPr`'s own,
    // completely unrelated violation while missing `Weird`'s. Falling back for the WHOLE shape once
    // ANY piece cannot be honestly read (`parseShape`'s own doc, `KitMacro.scala`) is what turns that
    // partial, wrong confidence into an honest "not checked here", leaving `Runner.validate` as the
    // sole backstop for the whole graph, `Weird` and `OpenPr` alike.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |case class WeirdInput() extends RequiresReviewInput
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Implement: Node[Unit, Unit] = Node(
        |  name = "Implement", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |val Weird: Node[?, ?] = Node[WeirdInput, Unit](
        |  name = "Weird", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Pick),
        |    transitions = List(
        |      Transition(Pick, Implement),
        |      Transition(Implement, OpenPr),
        |      Transition(Pick, Weird)
        |    )
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty

    // Companion assertion (issue #39 review, Mi1): the SAME literal, minus `Weird`'s own widened
    // reference, must still error on `OpenPr`, pinning that the empty result above is a genuine
    // fallback caused by `Weird`, not a macro that silently parses nothing at all.
    val errorsWithoutWiden = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Implement: Node[Unit, Unit] = Node(
        |  name = "Implement", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Pick),
        |    transitions = List(
        |      Transition(Pick, Implement),
        |      Transition(Implement, OpenPr)
        |    )
        |  )
        |)
        |""".stripMargin
    )

    errorsWithoutWiden should not be empty
    errorsWithoutWiden.map(_.message).mkString("\n") should include("'OpenPr'")
  }

  // ---- negative (previously false positive): a node factory called with different literal ---------
  // ---- string arguments for different nodes must not collapse into one key (issue #39 review, B1) --

  it should "compile a literal Shape whose entry/transitions reference a node factory called with different string literal arguments for different nodes" in {
    // `stage(n)` below builds a genuinely different `Node` per call, `n` chosen by the LITERAL
    // argument at each call site, not merely by which method is called. A macro keyed on the callee
    // symbol alone, ignoring the argument (the exact bug B1 reported), reads `stage("Implement")` and
    // `stage("CommitAndPush")` as the SAME node, so the real edge from `Review` into
    // `stage("CommitAndPush")` gets merged onto `stage("Implement")`'s own outgoing edges too,
    // producing a path into `OpenPr` that never actually crosses `Review` on a graph that genuinely
    // does cross it. `Runner.validate` finds nothing wrong with this graph, so the macro must not
    // reject it either; it cannot safely read `stage`'s own body to recover the real name, so falling
    // back for the whole shape, rather than guessing, is the only sound answer.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |def stage(n: String): Node[Unit, Unit] = Node(
        |  name = n, cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Review: Node[Unit, AgentDispatch.Judged[Unit]] = Node(
        |  name = "Review", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None,
        |  run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ()))
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(stage("Implement")),
        |    transitions = List(
        |      Transition(stage("Implement"), Review),
        |      Transition(Review, stage("CommitAndPush")),
        |      Transition(stage("CommitAndPush"), OpenPr)
        |    )
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  // ---- negative: two inline Node.apply constructions naming the same literal `name` are one node ---
  // ---- (issue #39 review, B2, and M1's display requirement) ------------------------------------------

  it should "refuse to typecheck a literal Shape whose nodes are inline Node.apply constructions, recognising two occurrences with the same literal name as the same node, and naming that literal in the message" in {
    // Both `Node[Unit, Unit](name = "Pick", ...)` constructions below build a genuinely fresh `Node`
    // object each, but the SAME `name`, "Pick": at runtime `Runner.validate` keys on that string, so
    // it reads entry's `Pick` and the transition's `Pick` as the identical node. A macro that keys an
    // inline construction by its own source position instead sees two DIFFERENT nodes, `byFrom` never
    // links `entry` to the one real transition this shape has, and the walk stops dead at `entry`,
    // silently missing the one violation this graph actually has: no reviewer sits before `OpenPr`.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Node[Unit, Unit](
        |      name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |      probe = _ => None, run = _ => NodeOutcome.Done(())
        |    )),
        |    transitions = List(Transition(
        |      Node[Unit, Unit](
        |        name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |        probe = _ => None, run = _ => NodeOutcome.Done(())
        |      ),
        |      Node[PrInput, Unit](
        |        name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |        probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |      )
        |    ))
        |  )
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("Pick -> OpenPr")
    messages should include("'OpenPr'")
    // M1: the message names the literal `name` argument, never the desugared tree the macro walks.
    messages should not include "contextual$"
    messages should not include "TrustOf.plain"
    messages should not include "quotes.reflect"
  }

  // ---- negative: trust forged past AgentDispatch.Judged by mere subtyping, not just Nothing (B3) ---

  it should "refuse to typecheck a literal Shape whose reviewer is only Node[Unit, Null], which unifies with AgentDispatch.Judged[a] by subtyping but is not Judged at all" in {
    // `Null` conforms to every reference type, `AgentDispatch.Judged[a]` included, so a type PATTERN
    // match against `Judged[a]` (matching by subtyping) stamps `Trust.Reviewed` on this node, while
    // the real `TrustOf[Null]`, resolved the identical way `Node.apply`'s own `using` clause resolves
    // it, gives `Trust.Plain`: `Fake` never reviews anything, so a path through it into `OpenPr`
    // genuinely lacks a reviewer, and `Runner.validate` rejects it at startup.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Fake: Node[Unit, Null] = Node[Unit, Null](
        |  name = "Fake", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => ???
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Pick),
        |    transitions = List(Transition(Pick, Fake), Transition(Fake, OpenPr))
        |  )
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("'OpenPr'")
  }

  it should "refuse to typecheck a literal Shape whose reviewer's output is AgentDispatch.Judged[Unit] & Serializable, an intersection that unifies with Judged[a] by subtyping but resolves TrustOf to Plain" in {
    // The identical forgery as the `Null` case above, one layer more disguised: `Judged[Unit] &
    // Serializable` still conforms to `Judged[a]` for `a = Unit` by plain subtyping, but `given
    // judged[A]: TrustOf[Judged[A]]` needs its result type to unify EXACTLY with the target, which an
    // intersection type never does, so the real `TrustOf` resolves to `LowPriorityTrustOf.plain` here
    // too. A macro that pattern matches the type instead of summoning the given cannot tell this
    // apart from a genuine `Judged[Unit]`.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Fake: Node[Unit, AgentDispatch.Judged[Unit] & java.io.Serializable] =
        |  Node[Unit, AgentDispatch.Judged[Unit] & java.io.Serializable](
        |    name = "Fake", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => ???
        |  )
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Pick),
        |    transitions = List(Transition(Pick, Fake), Transition(Fake, OpenPr))
        |  )
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("'OpenPr'")
  }

  // ---- negative (previously false positive): a curried call must not be keyed by its outer layer's -
  // ---- own arguments alone (issue #39 review, M5) ------------------------------------------------------

  it should "compile a literal Shape whose node reference is a curried method call, rather than mis-keying it by only the outer call's own argument list" in {
    // `mk("x")("y")` is two curried `Apply` layers; a walk that only peels the OUTERMOST one before
    // reading arguments sees `_` (M5's own repro), the same blindness `identifyRef` fixes for B1's
    // single layer case. `mk` genuinely has arguments here (in both layers), so the sound answer is
    // to decline and fall back for the whole shape, leaving `Runner.validate` as the backstop, never
    // to key on the callee symbol alone as though the call carried none.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |def mk(a: String)(b: String): Node[Unit, Unit] = Node(
        |  name = a + b, cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(mk("x")("y")),
        |    transitions = List(Transition(mk("x")("y"), OpenPr))
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  // ---- negative: Shape.unapply must not crash the compiler (B1) -----------------------------------

  it should "fall back, rather than crash the compiler, when the Shape argument is Shape.unapply(base)" in {
    // Scala 3 synthesises a `Shape.unapply` on the case class's own companion, owned by the exact
    // same module class `Shape.apply` is (`companionApplyArgs`'s own doc, `KitMacro.scala`, matches on
    // that OWNER alone). `Shape.unapply(base): Shape` (a plain product extractor, not `Option`
    // wrapped, for a case class shaped like this one) is therefore an `Apply` this macro recognises as
    // "a call into Shape's own companion", with exactly ONE argument, `base`, not the two `parseShape`
    // assumes; a hand written `List(entryTerm, transitionsTerm) = args` unchecked pattern match
    // crashes with a `MatchError` on that single element list instead of gracefully declining. `base`
    // below carries a genuine violation (`OpenPr` reachable with no reviewer on the only path into
    // it), not an empty `Shape` (issue #39 review round 3, Mi2, correcting a version of this test that
    // used an empty `Shape` and pinned nothing a do-nothing macro would also pass): an empty `Shape`
    // has no violation to miss either way, so it cannot tell "declined because `Shape.unapply` is
    // unreadable" apart from "declined because this macro never checks anything at all". The
    // companion assertion below, in the style of the widened reference case above, is what rules the
    // second reading out.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val base: Shape = Shape(entry = List(Pick), transitions = List(Transition(Pick, OpenPr)))
        |val shape = checkedShape(Shape.unapply(base))
        |""".stripMargin
    )

    errors shouldBe empty

    // Companion assertion (issue #39 review round 3, Mi2): the SAME violation, reached through a
    // genuine literal rather than `Shape.unapply`, must still error, pinning that the empty result
    // above is a genuine fallback caused by `Shape.unapply`, not a macro that silently parses nothing
    // at all.
    val errorsWithoutUnapply = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val shape = checkedShape(
        |  Shape(entry = List(Pick), transitions = List(Transition(Pick, OpenPr)))
        |)
        |""".stripMargin
    )

    errorsWithoutUnapply should not be empty
    errorsWithoutUnapply.map(_.message).mkString("\n") should include("'OpenPr'")
  }
