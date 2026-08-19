package com.example.consumer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The genuine CONSUMER half of RFC #26 decisions 5 and 8: that a package this library does not own
  * can AUTHOR a graph, not merely run the shipped one, through `LitterBox.graph`
  * (`src/LitterBox.scala`). `ConsumerBoundarySpec` (`test/ConsumerBoundarySpec.scala`) already proves
  * the pieces that graph is built FROM (`Node`, `Workflow`, `Shape`, `checkedShape`,
  * `AgentDispatch.review`) are reachable from a foreign package; this file's job is the one thing that
  * spec predates, that the factory itself, and the mandatory macro check it now forces on every graph
  * regardless of whether the caller remembered to splice `checkedShape` themselves, both work from
  * outside `in.rcard.litterbox`.
  *
  * Every snippet below follows the exact top-level-snippet shape `ConsumerBoundarySpec`'s own doc
  * (`test/ConsumerBoundarySpec.scala:9-50`) explains and justifies at length: this file is physically
  * declared `package com.example.consumer`, a genuinely separate compilation unit, so the same
  * call-site-splicing behaviour that traps a snippet run from a file inside `in.rcard.litterbox`
  * traps this file inside `com.example.consumer` just as reliably, and every snippet sits at bare top
  * level (a top level `val`/`def`/`class`), never nested inside a constructor call, for the identical
  * reason that doc gives (a nested snippet can make `typeCheckErrors` under-report on a context
  * function parameter).
  */
class ConsumerGraphSpec extends AnyFlatSpec with Matchers:

  // ---- positive 1: the RFC sketch itself, verbatim -----------------------------------------------

  it should "typecheck a consumer's own Nodes and Plan passed to LitterBox.graph, whose result is passed to LitterBox.run" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class MyStart(n: Int)
        |
        |val Pick: Node[MyStart, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Review: Node[Unit, Unit] = Node(
        |  name = "Review", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |
        |val myStages = StageSet(stages = Nil, anchor = None, terminal = None)
        |
        |def loop(args: Seq[String]): Unit =
        |  LitterBox.run(
        |    LitterBox.graph(
        |      name = "my-loop",
        |      plan = Plan(
        |        entry = Pick,
        |        edges = List(
        |          Edge.To(Pick, Review, _ => Some(())),
        |          Edge.Exit(Review, _ => Some(LoopExit.Success))
        |        )
        |      ),
        |      dispatchBudget = cfg => cfg.repairBudget + 1,
        |      startInput = n => MyStart(n),
        |      stages = myStages
        |    ),
        |    args
        |  )
        |""".stripMargin
    )

    errors shouldBe empty
  }

  // ---- positive 2: dispatchBudget reading cfg.repairBudget directly ------------------------------

  it should "typecheck LitterBox.graph with a dispatchBudget: Config => Int reading cfg.repairBudget" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val Only: Node[Unit, Unit] = Node(
        |  name = "Only", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |
        |val myGraph = LitterBox.graph(
        |  name = "budget-graph",
        |  plan = Plan(entry = Only, edges = List(Edge.Exit(Only, _ => Some(LoopExit.Success)))),
        |  dispatchBudget = (cfg: Config) => cfg.repairBudget,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  // ---- positive 3: startInput calling fault.raise off the ambient Fault ---------------------------

  it should "typecheck a consumer's own startInput calling fault.raise off the ambient Fault, still aborting the tick, with no Caps in scope at all" in {
    // `startInput: Int => Fault ?=> I` (narrowed from `(Caps, Fault) ?=> I`, issue #43 review, BLOCKER
    // 2, correcting an earlier version of this test that also summoned `Caps` here): `LitterBox.graph`'s
    // own doc has the full reasoning for why `begin` cannot hand `startInput` a `Caps` at all. This
    // snippet is the positive half of that fix, that `Fault` alone is still enough for a `fault.raise`
    // inside `startInput` to type-check and still abort the tick to rc 50 exactly as a fault inside any
    // node does; the negative half, that `Caps` genuinely is not summonable there any more, is below.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val Only: Node[Unit, Unit] = Node(
        |  name = "Only", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |
        |val myGraph = LitterBox.graph(
        |  name = "fault-graph",
        |  plan = Plan(entry = Only, edges = List(Edge.Exit(Only, _ => Some(LoopExit.Success)))),
        |  dispatchBudget = (cfg: Config) => cfg.repairBudget,
        |  startInput = (n: Int) =>
        |    if n < 0 then summon[Fault].raise("negative tick number") else ()
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  // ---- positive 4: a review node feeding a Guard.RequiresReview node, reviewer on every path -------

  it should "typecheck a consumer review node doing c.agents.review(p, f).map(parseMyScore) feeding a Guard.RequiresReview node whose input extends RequiresReviewInput, with a reviewer on every path" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |case class PrInput() extends RequiresReviewInput
        |case class MyScore(approved: Boolean)
        |def parseMyScore(o: DispatchOutcome): MyScore = MyScore(true)
        |
        |val Pick: Node[Unit, Unit] = Node(
        |  name = "Pick", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val Review: Node[Unit, AgentDispatch.Judged[MyScore]] = Node(
        |  name = "Review", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None,
        |  run = _ =>
        |    NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(parseMyScore))
        |)
        |val OpenPr: Node[PrInput, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |)
        |
        |val myGraph = LitterBox.graph(
        |  name = "reviewed-graph",
        |  plan = Plan(
        |    entry = Pick,
        |    edges = List(
        |      Edge.To(Pick, Review, _ => Some(())),
        |      Edge.To(Review, OpenPr, _ => Some(PrInput())),
        |      Edge.Exit(OpenPr, _ => Some(LoopExit.Success))
        |    )
        |  ),
        |  dispatchBudget = (cfg: Config) => cfg.repairBudget,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  // ---- negative 5: an unreviewed path into a guarded node, through LitterBox.graph, must fail at ---
  // ---- compile time: this is the new MANDATORY macro guarantee, not the opt-in one -----------------

  it should "refuse to typecheck LitterBox.graph(...) whose literal Plan reaches a RequiresReviewInput node with no reviewer on the path" in {
    // `ConsumerBoundarySpec`'s own equivalent negative (`:247-285`) only fires when the consumer
    // remembers to splice `checkedShape` themselves around their own local `shape` value; here the
    // exact same violation reaches the macro because `LitterBox.graph`'s own `inline shape` parameter
    // splices it automatically, with no `checkedShape` call anywhere in this snippet at all.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
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
        |val myGraph = LitterBox.graph(
        |  name = "unreviewed-graph",
        |  plan = Plan(
        |    entry = Pick,
        |    edges = List(
        |      Edge.To(Pick, Implement, _ => Some(())),
        |      Edge.To(Implement, OpenPr, _ => Some(PrInput())),
        |      Edge.Exit(OpenPr, _ => Some(LoopExit.Success))
        |    )
        |  ),
        |  dispatchBudget = (cfg: Config) => cfg.repairBudget,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("Pick -> Implement -> OpenPr")
    messages should include("'OpenPr'")
  }

  // ---- negative 6: a foreign package naming Runner.Ledger's own constructor ------------------------

  it should "refuse to typecheck a foreign package naming Runner.Ledger's own constructor, either as new Runner.Ledger(1) or the bare creator application Runner.Ledger(1)" in {
    val newErrors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val ledger = new Runner.Ledger(1)
        |""".stripMargin
    )
    newErrors should not be empty
    newErrors.map(_.message).mkString("\n") should include("private")

    val bareErrors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val ledger = Runner.Ledger(1)
        |""".stripMargin
    )
    bareErrors should not be empty
    bareErrors.map(_.message).mkString("\n") should include("private")
  }

  // ---- negative 7: a foreign package cannot call Runner.run/Runner.step at all: no Ledger it -------
  // ---- could ever construct is summonable, so the `using` clause has nothing to resolve against ----

  it should "refuse to typecheck a foreign package calling Runner.run(wf, input) or Runner.step(node, input), since no Runner.Ledger it could construct is ever summonable" in {
    // Both snippets below hand-supply every OTHER given `Runner.run`/`Runner.step` needs, `Caps` and
    // `scala.util.boundary.Label[LoopExit]` (the dealiased form of `Faulting`, not nameable from a
    // foreign package, `ConsumerGraphSpec`'s own doc above has the reason), so `Runner.Ledger` is
    // provably the ONLY unresolved given left, not merely one of several (issue #43 review, MAJOR 4,
    // correcting an earlier version of this test that supplied neither and asserted on a bare
    // non-emptiness that would have passed just as well if `Runner.Ledger`'s own constructor had been
    // made fully public: the compiler stops at the FIRST unresolved given, `caps`, and never reaches
    // `ledger`, so an earlier version of this snippet's errors were about `Caps`, never about the
    // `Ledger` invariant `test/ConsumerBoundarySpec.scala:152-157` asks this file to pin). Asserting
    // `messages should include("Runner.Ledger")` is what makes that the checked fact instead of an
    // accident of which error happened to come back first.
    val runErrors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val wf: Workflow[Unit] = Workflow("x", start = (_: Unit) => Next.Finish(LoopExit.Success))
        |def go(using Caps, scala.util.boundary.Label[LoopExit]): Unit = { Runner.run(wf, ()); () }
        |""".stripMargin
    )
    runErrors should not be empty
    runErrors.map(_.message).mkString("\n") should include("Runner.Ledger")

    val stepErrors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val node: Node[Unit, Unit] = Node(
        |  name = "x", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |def go(using Caps, scala.util.boundary.Label[LoopExit]): Unit = { Runner.step(node, ()); () }
        |""".stripMargin
    )
    stepErrors should not be empty
    stepErrors.map(_.message).mkString("\n") should include("Runner.Ledger")
  }

  // ---- negative 8: the seal still bites for a genuinely foreign package's own LoopGraph ------------

  it should "refuse to typecheck a foreign package's own class MyGraph extends LoopGraph" in {
    // The identical proof `ScaffoldedLoopBoundarySpec` already carries (`test/ScaffoldedLoopBoundarySpec.scala`),
    // restated here so this file's own claim ("the factory is the only door in") is pinned beside the
    // positive tests above that exercise that door, rather than only in a spec about the scaffold.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |class MyGraph extends LoopGraph
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("sealed trait LoopGraph")
    messages should include("different source file")
  }

  // ---- negative 9: Machine.ShippedStart / Machine.shippedWorkflow stay unnameable -------------------

  it should "refuse to typecheck a foreign package naming Machine.ShippedStart or calling Machine.shippedWorkflow" in {
    val startErrors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val x: Machine.ShippedStart = ???
        |""".stripMargin
    )
    startErrors should not be empty
    startErrors.map(_.message).mkString("\n") should include("private")

    val workflowErrors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |def build(cfg: Config, caps: Caps): Unit = Machine.shippedWorkflow(cfg, caps, ???, ???)
        |""".stripMargin
    )
    workflowErrors should not be empty
    workflowErrors.map(_.message).mkString("\n") should include("private")
  }

  // ---- negative 10: Fault's own constructor stays unnameable ---------------------------------------

  it should "refuse to typecheck a foreign package constructing new Fault(...)" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val f = new Fault(???, ???, ???)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("constructor Fault")
    messages should include("private")
  }

  // ---- negative 11: startInput cannot summon Caps at all (issue #43 review, BLOCKER 2) -------------

  it should "refuse to typecheck a consumer's own startInput calling a capability off summon[Caps], since begin runs before this tick's real Ledger exists and Caps has to stay out of reach there" in {
    // This is the negative twin of positive 3 above: the exact snippet an earlier version of this
    // file accepted, `summon[Caps].logger.log(...)` inside `startInput`, now has to fail, because a
    // `startInput` that COULD reach `Caps` could call `agents.*` on the LIVE `AgentDispatch` before
    // any `Ledger`, `Timeout` or `Trust.Reviewed` check ever sees it (`LitterBox.graph`'s own doc has
    // the reproduced, compiled proof this fix closes). Asserting `messages should include("Caps")`
    // pins WHICH given failed to resolve, not merely that something did.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val Only: Node[Unit, Unit] = Node(
        |  name = "Only", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |
        |val myGraph = LitterBox.graph(
        |  name = "caps-graph",
        |  plan = Plan(entry = Only, edges = List(Edge.Exit(Only, _ => Some(LoopExit.Success)))),
        |  dispatchBudget = (cfg: Config) => cfg.repairBudget,
        |  startInput = (n: Int) =>
        |    summon[Caps].logger.log(s"starting tick $n")
        |)
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("Caps")
  }

  // ---- negative 12: LitterBox.graph refuses a non-literal shape at compile time (issue #43 review, --
  // ---- BLOCKER 1): the mandatory-macro guarantee this factory exists to grant would otherwise -------
  // ---- silently degrade to checkedShape's own lenient, opt-in fallback the moment shape is passed ---
  // ---- by identifier instead of being written as a literal at this call site ------------------------

  it should "refuse to typecheck LitterBox.graph(plan = someVal, ...) since plan must be written as a literal Plan(...) expression at the call site" in {
    // Reproduced against this working tree before this fix (issue #43 review, BLOCKER 1): a graph
    // reaching a `Guard.RequiresReview` node whose input type extends `RequiresReviewInput`, with no
    // reviewer anywhere on the path, compiled clean and `Runner.validate` on the same graph returned
    // `List()` too, purely because the declaration was written as a `val` and passed by identifier
    // here. This snippet does not even need a guarded node to demonstrate the fix: `checkedPlan`
    // rejects a non-literal `plan` unconditionally, before it would ever get as far as walking for a
    // violation.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val Only: Node[Unit, Unit] = Node(
        |  name = "Only", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val myPlan = Plan(entry = Only, edges = Nil)
        |
        |val myGraph = LitterBox.graph(
        |  name = "val-shape-graph",
        |  plan = myPlan,
        |  dispatchBudget = (cfg: Config) => cfg.repairBudget,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("requires a literal Plan")
  }

  // ---- 13 through 19: the idiom-by-idiom table from issue #43 review round 2, BLOCKER B1 -----------
  // ---- part 2, widening what the mandatory check can read, and part 1, the two DIFFERENTLY worded ---
  // ---- strict errors that replace the single "write a literal here" message every one of these -----
  // ---- idioms used to get regardless of why this walk actually declined -----------------------------

  it should "typecheck nodes declared as val members of a class, referenced unqualified (issue #43 review round 2, BLOCKER B1 part 2: stablePathKey now accepts a This prefix)" in {
    // Reproduced against this working tree before this fix: `A` inside a class body types as
    // `Select(This, A)`, which `stablePathKey` did not recognise, so this CONSUMER idiom, a class
    // declaring its own nodes as val members and referring to them unqualified, fell to the "not a
    // literal at all" message even though every piece here is written inline. NOT the shipped graph's
    // own idiom (issue #43 review round 3, MINOR 2, correcting this comment's earlier attribution):
    // `Machine` is an `object`, never a class, and `shippedShape`'s own `entry`/`transitions` are
    // `def`-built nodes (`Implement(cfg)` and its kin), which stay unreadable by this walk regardless.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |class MyLoop13:
        |  val A: Node[Unit, Unit] = Node(
        |    name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val B: Node[Unit, Unit] = Node(
        |    name = "B", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val g: LoopGraph = LitterBox.graph(
        |    name = "c13",
        |    plan = Plan(entry = A, edges = List(Edge.To(A, B, _ => Some(())))),
        |    dispatchBudget = (_: Config) => 0,
        |    startInput = (_: Int) => ()
        |  )
        |""".stripMargin
    )

    errors shouldBe empty
  }

  it should "refuse to typecheck nodes declared as val members of a class, referenced unqualified, when the marker-guarded one has no reviewer on the path (issue #43 review round 3, MAJOR 1: the negative twin of the positive above, closing the gap that let BLOCKER 1 through, a positive test alone cannot prove the parser still CHECKS what it now additionally accepts)" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class PrInput13() extends RequiresReviewInput
        |
        |class MyLoop13Neg:
        |  val A: Node[Unit, Unit] = Node(
        |    name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val OpenPr: Node[PrInput13, Unit] = Node(
        |    name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val g: LoopGraph = LitterBox.graph(
        |    name = "c13neg",
        |    plan = Plan(entry = A, edges = List(Edge.To(A, OpenPr, _ => Some(PrInput13())))),
        |    dispatchBudget = (_: Config) => 0,
        |    startInput = (_: Int) => ()
        |  )
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("rejected at compile time")
    messages should include("'OpenPr'")
  }

  it should "typecheck edges = List.empty and edges = List.empty[Edge] (issue #43 review round 2, BLOCKER B1 part 2: literalListElements now accepts List.empty)" in {
    val bareEmpty = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val Only: Node[Unit, Unit] = Node(
        |  name = "Only", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val myGraph = LitterBox.graph(
        |  name = "c14a",
        |  plan = Plan(entry = Only, edges = List.empty),
        |  dispatchBudget = (_: Config) => 0,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )
    bareEmpty shouldBe empty

    val typedEmpty = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val Only: Node[Unit, Unit] = Node(
        |  name = "Only", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val myGraph = LitterBox.graph(
        |  name = "c14b",
        |  plan = Plan(entry = Only, edges = List.empty[Edge]),
        |  dispatchBudget = (_: Config) => 0,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )
    typedEmpty shouldBe empty
  }

  it should "refuse to typecheck edges = List.empty when the entry itself is the marker-guarded node with no reviewer (issue #43 review round 3, MAJOR 1: the negative twin of List.empty above, pinning that a fully-parsed empty list still lets the BFS run rather than silently falling back)" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class PrInput14() extends RequiresReviewInput
        |val OpenPr14: Node[PrInput14, Unit] = Node(
        |  name = "OpenPr14", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val myGraph = LitterBox.graph(
        |  name = "c14neg",
        |  plan = Plan(entry = OpenPr14, edges = List.empty),
        |  dispatchBudget = (_: Config) => 0,
        |  startInput = (_: Int) => PrInput14()
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("rejected at compile time")
    messages should include("'OpenPr14'")
  }

  it should "typecheck Plan(edges = ..., entry = ...) with named arguments out of Plan's own declaration order (issue #43 review round 2, BLOCKER B1 part 2: companionApplyArgs now reads through the compiler's own out-of-order-named-argument temp-val block)" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val A: Node[Unit, Unit] = Node(
        |  name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val B: Node[Unit, Unit] = Node(
        |  name = "B", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val myGraph = LitterBox.graph(
        |  name = "c15",
        |  plan = Plan(edges = List(Edge.To(A, B, _ => Some(()))), entry = A),
        |  dispatchBudget = (_: Config) => 0,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  it should "refuse to typecheck Plan(edges = ..., entry = ...) out of declaration order when the marker-guarded node has no reviewer on the path (issue #43 review round 3, MAJOR 1: the negative twin of the out-of-order-named-arguments positive above)" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class PrInput15() extends RequiresReviewInput
        |val A: Node[Unit, Unit] = Node(
        |  name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val OpenPr15: Node[PrInput15, Unit] = Node(
        |  name = "OpenPr15", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val myGraph = LitterBox.graph(
        |  name = "c15neg",
        |  plan = Plan(edges = List(Edge.To(A, OpenPr15, _ => Some(PrInput15()))), entry = A),
        |  dispatchBudget = (_: Config) => 0,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("rejected at compile time")
    messages should include("'OpenPr15'")
  }

  it should "typecheck a val-bound Edge, declared local to the same block as the LitterBox.graph call that uses it, referenced as a list element (issue #43 review round 2, BLOCKER B1 part 2: parseTransition now reads through a LOCAL val binding to its own Transition(...) initialiser)" in {
    // `parseTransition`'s own doc (`KitMacro.scala`) has the reason this resolves for a LOCAL val
    // (its initialiser is still part of the tree macro expansion is currently typing) but declines for
    // a class-or-object-MEMBER val (its initialiser is compiled away into that type's own constructor,
    // unreadable through Symbol.tree no matter how this walk is widened): this snippet exercises the
    // LOCAL case, the one round 2's own fix accepts.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val A: Node[Unit, Unit] = Node(
        |  name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val B: Node[Unit, Unit] = Node(
        |  name = "B", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |def buildGraph(): LoopGraph =
        |  val t1 = Edge.To(A, B, (_: Unit) => Some(()))
        |  LitterBox.graph(
        |    name = "c16",
        |    plan = Plan(entry = A, edges = List(t1)),
        |    dispatchBudget = (_: Config) => 0,
        |    startInput = (_: Int) => ()
        |  )
        |""".stripMargin
    )

    errors shouldBe empty
  }

  it should "refuse to typecheck a val-bound Edge, local to the same block as the LitterBox.graph call, when the marker-guarded node has no reviewer on the path (issue #43 review round 3, MAJOR 1: the negative twin of the local-val-bound-Transition positive above)" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class PrInput16() extends RequiresReviewInput
        |val A: Node[Unit, Unit] = Node(
        |  name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val OpenPr16: Node[PrInput16, Unit] = Node(
        |  name = "OpenPr16", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |def buildGraph(): LoopGraph =
        |  val t1 = Edge.To(A, OpenPr16, (_: Unit) => Some(PrInput16()))
        |  LitterBox.graph(
        |    name = "c16neg",
        |    plan = Plan(entry = A, edges = List(t1)),
        |    dispatchBudget = (_: Config) => 0,
        |    startInput = (_: Int) => ()
        |  )
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("rejected at compile time")
    messages should include("'OpenPr16'")
  }

  it should "refuse to typecheck a node built by a def, with the NEW UnreadablePiece wording, never the NotALiteral wording a val or a function result gets (issue #43 review round 2, BLOCKER B1 part 1)" in {
    // This is the one idiom round 2's own fix deliberately leaves unreadable (issue #43 review round 2,
    // BLOCKER B1 part 3): `LitterBox.graph`'s own scaladoc has the fuller reasoning for why a node
    // built by a `def` can never be identified by a macro that reasons about trees, never about a
    // running value. The assertion here is on WHICH of the two strict messages fires, not merely that
    // one does: `include("could not identify or fully parse")` is `UnreadablePiece`'s own wording,
    // `NotALiteral`'s is `"requires a literal Shape(entry = List"`, asserted absent so a future edit
    // that collapsed the two messages back into one could never pass this test by accident.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |def A: Node[Unit, Unit] = Node(
        |  name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val myGraph = LitterBox.graph(
        |  name = "c17",
        |  plan = Plan(entry = A, edges = Nil),
        |  dispatchBudget = (_: Config) => 0,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("could not identify or fully parse")
    messages should not include "requires a literal Plan(entry ="
  }

  it should "refuse to typecheck an exported member, with wording that names it as a def forwarder rather than telling the consumer to do the object-member form they just used (issue #43 review round 3, MINOR 3)" in {
    // `export ZZSrc.*` synthesises a FORWARDER def, never a val, so `isStablePathLink` rightly declines
    // it; the failure itself was always correct. What was wrong is that the UnreadablePiece message's
    // own list of readable forms explicitly names "an object member", which is exactly what `ZZExp.Start`
    // looks like at this call site, so a consumer reading the message could reasonably (and wrongly)
    // conclude they had already done what it asked. This pins that the message now calls the export
    // forwarder out by name instead.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |object ZZSrc:
        |  val Start: Node[Unit, Unit] = Node(
        |    name = "Start", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val OpenPr: Node[Unit, Unit] = Node(
        |    name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |object ZZExp:
        |  export ZZSrc._
        |val myGraph = LitterBox.graph(
        |  name = "export-fwd",
        |  plan = Plan(entry = ZZExp.Start, edges = List(Edge.To(ZZExp.Start, ZZExp.OpenPr, _ => Some(())))),
        |  dispatchBudget = (_: Config) => 0,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("could not identify or fully parse")
    messages should include("def forwarder")
  }

  it should "refuse to typecheck Machine.shippedShape's own config-parameterised A(cfg) idiom through LitterBox.graph, with the UnreadablePiece wording (issue #43 review round 2, BLOCKER B1 part 3: the documented residue, not expressible through this factory)" in {
    // The sharpest row of round 2's own idiom table: the shipped graph's OWN node-construction idiom,
    // a node built from a per-tick `Config`, cannot be authored through the public factory. This is not
    // a bug this branch left unfixed, it is RFC #26 decision 16's own recorded consequence
    // ("graphs cannot be assembled dynamically") landing on the one idiom that happens to read a
    // config value at construction time; `LitterBox.graph`'s own scaladoc names the workaround (read
    // the config inside the node's own run/probe body instead of at construction).
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class MyCfg(budget: Int)
        |def A(cfg: MyCfg): Node[Unit, Unit] = Node(
        |  name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |def B(cfg: MyCfg): Node[Unit, Unit] = Node(
        |  name = "B", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val myCfg = MyCfg(1)
        |val myGraph = LitterBox.graph(
        |  name = "c18",
        |  plan = Plan(entry = A(myCfg), edges = List(Edge.To(A(myCfg), B(myCfg), _ => Some(())))),
        |  dispatchBudget = (_: Config) => 0,
        |  startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("could not identify or fully parse")
  }

  // ---- 20: LitterBox.shipped.Start and a LitterBox.graph result's own Start stay unnameable ----------
  // ---- (issue #43 review round 2, MINOR m3: both concrete LoopGraph inhabitants now write ------------
  // ---- `private[litterbox] type Start = ...` explicitly, rather than a bare `type Start = ...` that ---
  // ---- Scala permits a subclass to WIDEN back to public even though the trait's own abstract member --
  // ---- already carries the modifier) ------------------------------------------------------------------

  it should "refuse to typecheck a foreign package naming LitterBox.shipped.Start or a LitterBox.graph result's own Start" in {
    // Before this fix, `type Start = Machine.ShippedStart` and `type Start = I` on the two anonymous
    // `LoopGraph` inhabitants (`LitterBox.scala`) carried no access modifier of their own, and Scala
    // permits a subclass to WIDEN an inherited member back to public even when the trait's own abstract
    // member is `private[litterbox]`, confirmed by compiling `val s: LitterBox.shipped.Start = ???`
    // clean from this package against the tree before this fix. Unreachable on its own even then,
    // `Start` is abstract, `shipped` is ascribed `: LoopGraph`, and every method actually naming `Start`
    // stays `private[litterbox]`, but the trait's own doc and `ARCHITECTURE.md` both credit the MODIFIER
    // for keeping this shut, so a bare `type Start` was the one member that did not actually carry the
    // modifier the doc's own argument rests on. `private[litterbox] type Start = ...` written explicitly
    // on both inhabitants is what this fix adds; this test is the first to pin that it actually closes.
    val shippedStartErrors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val s: LitterBox.shipped.Start = ???
        |""".stripMargin
    )
    shippedStartErrors should not be empty
    shippedStartErrors.map(_.message).mkString("\n") should include("private")

    val graphStartErrors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val Only: Node[Unit, Unit] = Node(
        |  name = "Only", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val myGraph = LitterBox.graph(
        |  name = "c20",
        |  plan = Plan(entry = Only, edges = Nil),
        |  dispatchBudget = (_: Config) => 0,
        |  startInput = (_: Int) => ()
        |)
        |val s: myGraph.Start = ???
        |""".stripMargin
    )
    graphStartErrors should not be empty
    graphStartErrors.map(_.message).mkString("\n") should include("private")
  }

  // ---- 21 through 23: issue #43 review round 3, BLOCKER 1 regressions -------------------------------
  // ---- the macro's node identity key used to be computed three incompatible ways (Ident's bare -------
  // ---- t.symbol.fullName, Select(This(_), _)'s identical t.symbol.fullName, and the general Select ----
  // ---- case's own hand-built "prefix.name" concatenation), so the SAME node, spelled two different ----
  // ---- ways in one Shape, silently SPLIT into two keys (an edge never links, a marker-only-guarded ----
  // ---- node past it is never reached, no violation reported), and two DIFFERENT nodes could -----------
  // ---- silently MERGE into one key (a class/object companion pair, or two inline Node.apply calls -----
  // ---- sharing one literal name), letting the visited set pick a winner between disagreeing facts. ----
  // ---- The fix: one canonical keying function (stablePathKey's own `canonical`, KitMacro.scala), and --
  // ---- a reconciliation pass (`checkReconciled`) that hard-errors when two references canonicalise ----
  // ---- to the same key while disagreeing on (trust, guard), rather than silently picking a winner. ----

  it should "refuse to typecheck a class member referenced both bare (This-qualified) and through a second val the SAME class initialises to this, when the This-qualified spelling is unreviewed (issue #43 review round 3, BLOCKER 1, reproduction (d): a class member reached through this-aliasing)" in {
    // `self0.Start` and bare `Start` are the identical `Node` at runtime (`self0: ZZC = this`), but this
    // macro has no dataflow analysis that could prove that, only that `self0` is a STABLE value; keying
    // them the same regardless would risk merging two shapes that could, for all this macro can prove,
    // be genuinely different. `stablePathKey` (`KitMacro.scala`) declines `self0.Start` outright rather
    // than guess either way: the whole shape is `UnreadablePiece`, a hard error, never a silent pass.
    // The MECHANISM behind that decline changed since this test was first written, though the OUTCOME
    // did not (issue #43 review round 4, Tier 1, correcting this comment rather than only the source it
    // describes): a round 3 patch declined `self0.Start` only because its own widened type, `ZZCd`,
    // happened to equal `callSiteClass`, the class enclosing this macro's own splice site; round 4 found
    // three sidesteps that defeat that narrower test without touching `this` at all (`ConsumerGraphSpec`'s
    // own n1/n2/n12/n13 tests below pin each one) and replaced it with a blanket decline of every
    // instance-qualified receiver, `self0.Start` included, regardless of what it widens to. Confirmed
    // discriminating as a snippet when this test was first written (unlike (a)/(b)/(c) below): run
    // against the tree before either fix, this snippet's own `errors` came back EMPTY, the exact silent
    // hole the original fix closed.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class PrInputD() extends RequiresReviewInput
        |
        |class ZZCd:
        |  val Start: Node[Unit, Unit] = Node(
        |    name = "Start", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val OpenPr: Node[PrInputD, Unit] = Node(
        |    name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val self0: ZZCd = this
        |  val g: LoopGraph = LitterBox.graph(
        |    name = "classmixed",
        |    plan = Plan(entry = Start, edges = List(Edge.To(self0.Start, OpenPr, _ => Some(PrInputD())))),
        |    dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |  )
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("could not identify or fully parse")
  }

  it should "refuse to typecheck a class and its own companion object each declaring a member of the same name, one guarded and one not (issue #43 review round 3, BLOCKER 1, reproduction (e): a companion class/object MERGE)" in {
    // `object ZZX`'s own `A` and `class ZZX`'s own `A` canonicalise to the SAME key (`canonical` strips
    // the trailing `$` the module class carries, `stablePathKey`'s own doc has the mechanism), even
    // though they build two genuinely different `Node`s here, one plain, one marker-guarded. Before this
    // fix the visited set silently kept whichever was dequeued first (the object's plain `A`, entered
    // via `entry`) and skipped the class's own guarded `A` as "already visited": this snippet's own
    // `errors` came back EMPTY against the pre-fix keying scheme. `checkReconciled` now catches the
    // disagreement (differing `guard`) and hard-errors before the BFS ever runs.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class PrInputE() extends RequiresReviewInput
        |
        |object ZZXe:
        |  val A: Node[Unit, Unit] = Node(
        |    name = "A-object", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |
        |class ZZXe:
        |  val A: Node[PrInputE, Unit] = Node(
        |    name = "A-class", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val Start: Node[Unit, Unit] = Node(
        |    name = "Start", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val g: LoopGraph = LitterBox.graph(
        |    name = "companion-merge",
        |    plan = Plan(
        |      entry = Start,
        |      edges = List(Edge.To(Start, A, _ => Some(PrInputE())), Edge.Exit(ZZXe.A, _ => Some(LoopExit.Success)))
        |    ),
        |    dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |  )
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("canonicalise to the same node identity")
  }

  it should "refuse to typecheck a class and its own companion object each declaring a member of the same name, both agreeing on trust and guard (issue #67 review, companion key merge splits derived walk)" in {
    // The MERGE reproduction above disagrees on `guard`, so `checkReconciled`'s own `(trust, guard)`
    // comparison already catches it. Here `object ZZP`'s own `A` and `class ZZP`'s own `A` are both
    // plain `Node[Unit, Unit]`, agreeing on every fact `checkReconciled` reads, while still being two
    // genuinely different runtime objects: `canonical` strips the module class's own trailing `$`, so
    // `this.A` and `ZZP.A` share one key regardless, and before this fix `checkReconciled` let an
    // agreeing group straight through, `parsePlan` merged the two into one `NodeRef`, and this snippet
    // compiled and validated clean. `Plan.workflowOf` (`Kit.scala`) still walks by REFERENCE IDENTITY,
    // so the object `Start`'s own edge produces, the class's `A`, is never `eq` to the object the
    // `Edge.Exit` names, the object's own `A`, and a real run dead ends into `Machine.infraFault` on
    // its very first tick, after `Start` and `A` already ran. This is caught now regardless of
    // agreement, because two references built from two different defining symbols are never the same
    // runtime `Node`, whatever they happen to agree on.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |object ZZP:
        |  val A: Node[Unit, Unit] = Node(
        |    name = "A-object", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |
        |class ZZP:
        |  val A: Node[Unit, Unit] = Node(
        |    name = "A-class", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val Start: Node[Unit, Unit] = Node(
        |    name = "Start", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val g: LoopGraph = LitterBox.graph(
        |    name = "companion-split",
        |    plan = Plan(
        |      entry = Start,
        |      edges = List(Edge.To(Start, A, _ => Some(())), Edge.Exit(ZZP.A, _ => Some(LoopExit.Success)))
        |    ),
        |    dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |  )
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("canonicalise to the same node identity")
  }

  it should "refuse to typecheck two inline Node.apply calls sharing one literal name, one guarded and one not (issue #43 review round 3, BLOCKER 1, reproduction (f): an inline-construction MERGE, now caught earlier by issue #67 review's InlineNodeInPlan check)" in {
    // `identifyRef` keys an inline `Node.apply` on its bare literal `name` string, in its own
    // `inline:` namespace; three inline constructions all named `"A"` are therefore one macro identity
    // here regardless of input type, even though one genuinely requires review and the other two do
    // not. Before issue #67 review's own fix, that MERGE was caught only by `checkReconciled`, once it
    // found the disagreement, so this snippet's own message named a key conflict. `parsePlan` now
    // refuses every inline construction in a `Plan` outright (`ParseFailure.InlineNodeInPlan`'s own
    // doc, `KitMacro.scala`, has the reasoning: `Plan.workflowOf` walks by REFERENCE IDENTITY, and an
    // inline call is never the same runtime object twice, agreeing or not), so this snippet is refused
    // for that reason now, before `checkReconciled` ever runs, and the message names the FIRST inline
    // reference this walk visits, `entry`'s own `"A"`, rather than the pair that disagrees.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class PrInputF() extends RequiresReviewInput
        |val myGraph: LoopGraph = LitterBox.graph(
        |  name = "inline-merge",
        |  plan = Plan(
        |    entry = Node(name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded, probe = _ => None, run = _ => NodeOutcome.Done(())),
        |    edges = List(Edge.To(
        |      Node(name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded, probe = _ => None, run = _ => NodeOutcome.Done(())),
        |      Node[PrInputF, Unit](name = "A", cost = Cost.NoDispatch, timeout = Timeout.Unbounded, probe = _ => None, run = _ => NodeOutcome.Done(())),
        |      (_: Unit) => Some(PrInputF())
        |    ))
        |  ),
        |  dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("inline Node(name = \"...\", ...) construction")
  }

  it should "refuse to typecheck a single node built by two agreeing inline Node.apply calls, once as a Plan's own entry and once as an Edge.Exit's own from (issue #67 review, walk identity splits node macro merges)" in {
    // The failure this pins is narrower and quieter than the MERGE reproduction above, and existed
    // before this fix even though the two references never disagree on anything `checkReconciled`
    // reads: `identifyRef` keys both inline `Node("ProbeOnly", ...)` calls to the identical
    // `inline:ProbeOnly`, so, before this fix, this literal compiled clean (no key ever collided with
    // a DIFFERENT `(trust, guard)` pair) and `Runner.validate` also passed (the derived `Shape` is
    // `Shape(List(entryNode), Nil)`, no transition for its own BFS to ever fault on). `Plan.workflowOf`
    // (`Kit.scala`) is where the gap actually bites: it links an edge to the node it leaves by
    // REFERENCE IDENTITY, `Edge.source(e) eq from`, and the object built at `entry` and the object
    // built again at the `Edge.Exit`'s own `from` are two DIFFERENT allocations, so a real tick
    // reaching `ProbeOnly` finds no edge whose source matches it and dead ends into
    // `Machine.infraFault`, a graph that compiled clean and validated clean nonetheless failing its
    // very first tick. `InlineNodeInPlan` refuses this outright now, before either agreement or
    // disagreement is ever asked about.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |val probeDupInline: LoopGraph = LitterBox.graph(
        |  name = "probeDupInline",
        |  plan = Plan(
        |    entry = Node(name = "ProbeOnly", cost = Cost.NoDispatch, timeout = Timeout.Unbounded, probe = _ => None, run = _ => NodeOutcome.Done(())),
        |    edges = List(Edge.Exit(
        |      Node(name = "ProbeOnly", cost = Cost.NoDispatch, timeout = Timeout.Unbounded, probe = _ => None, run = _ => NodeOutcome.Done(())),
        |      _ => Some(LoopExit.Success)
        |    ))
        |  ),
        |  dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("inline Node(name = \"...\", ...) construction")
    errors.map(_.message).mkString("\n") should include("ProbeOnly")
  }

  // Reproductions (a) (a wildcard-imported bare reference mixed with a qualified one), (b) (a
  // package-qualified reference to a top-level val mixed with a bare one) and (c) (an object member
  // spelled bare in one place and qualified in another, the mirror direction of (a)) are the other
  // three SPLIT cases issue #43 review round 3's BLOCKER 1 reproduced and this same canonicalisation
  // fix closes, confirmed by compiling each as a REAL top-level file in `package com.example.consumer`
  // against both the pre-fix and post-fix tree (silently clean before, a hard "rejected at compile
  // time" naming the violation after). None of the three can be pinned here as a `scala.compiletime
  // .testing.typeCheckErrors` snippet, confirmed by writing exactly these three snippets and running
  // them against the PRE-FIX keying scheme, not merely assumed:
  //   - (a) and (c) both involve `import Holder.*` immediately followed by a BARE reference to the
  //     imported member. Inside the synthetic top-level wrapper `typeCheckErrors` compiles its string
  //     under, that bare reference does not type as the same `Ident` shape a real top-level file
  //     produces; the snippet's own `errors` came back NON-EMPTY, correctly naming the violation, even
  //     against the PRE-FIX keying scheme that is supposed to still have this hole. A snippet that
  //     "passes" whether or not the fix is present cannot discriminate a regression, so it is not
  //     included here; ConsumerBoundarySpec's own doc (`test/ConsumerBoundarySpec.scala:9-50`) already
  //     documents one other way a nested snippet under-reports (a context function parameter), this is
  //     a second, independent way the top-level-snippet shape diverges from a real file, specific to
  //     wildcard imports.
  //   - (b) uses a fully package-qualified reference, `com.example.consumer.ZZpStart`, to a top-level
  //     `val` declared earlier in the SAME snippet. `typeCheckErrors`' own synthetic wrapper does not
  //     place a snippet's top-level members at the package path the snippet declares, so the qualified
  //     reference fails with "value ZZpStart is not a member of com.example.consumer", an error about
  //     the SNIPPET HARNESS itself, not about this macro, on both the pre-fix and post-fix tree alike.
  // Both defects are exactly the class of thing issue #43 review round 3's own verification note warns
  // about: "typeCheckErrors snippets and real files behave differently in this macro." (d), (e) and (f)
  // above were each independently confirmed to discriminate correctly before being kept.

  // ---- 24 through 27: issue #43 review round 4, Tier 1 regressions ----------------------------------
  // ---- round 3's own fix to reproduction (d) declined an instance-qualified receiver only when its ----
  // ---- own widened type EXACTLY matched callSiteClass, the class directly enclosing the macro's own ----
  // ---- splice site. Round 4 found three sidesteps, none involving `this` at all, that defeat that ----
  // ---- narrower test while still reaching the identical SPLIT: ascribing the alias to a supertype -----
  // ---- of the enclosing class, moving the LitterBox.graph call into a nested object (so the splice ----
  // ---- site's own enclosing class is the object's module class, never the outer class the alias is ----
  // ---- typed as), and aliasing something that is not a class at all. The fix: stop keying EVERY -------
  // ---- instance-qualified receiver, not only the ones callSiteClass happened to recognise -------------
  // ---- (`stablePathKey`'s own doc, `KitMacro.scala`, has the full reasoning for why no narrower --------
  // ---- test can close this without guessing). All four below are ordinary top-level snippets, no -------
  // ---- wildcard import and no package-qualified reference to an earlier snippet member involved, so ----
  // ---- none of them hits the harness divergence (a)/(b)/(c) above are excluded for.

  it should "refuse to typecheck a class member reached through a val aliased to the module singleton itself, not to This (issue #43 review round 4, Tier 1, reproduction n1: aliasing something that is not a class at all)" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class PrInputN1() extends RequiresReviewInput
        |
        |object N1H:
        |  val Start: Node[Unit, Unit] = Node(
        |    name = "Start", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val OpenPr: Node[PrInputN1, Unit] = Node(
        |    name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |
        |val n1h: N1H.type = N1H
        |val g: LoopGraph = LitterBox.graph(
        |  name = "n1",
        |  plan = Plan(entry = N1H.Start, edges = List(Edge.To(n1h.Start, N1H.OpenPr, _ => Some(PrInputN1())))),
        |  dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("could not identify or fully parse")
  }

  it should "refuse to typecheck two ordinary top-level vals of the same class, one aliased to the other, referenced through both spellings (issue #43 review round 4, Tier 1, reproduction n2: a plain instance alias with no This anywhere)" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class PrInputN2() extends RequiresReviewInput
        |
        |class N2H:
        |  val Start: Node[Unit, Unit] = Node(
        |    name = "Start", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val OpenPr: Node[PrInputN2, Unit] = Node(
        |    name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |
        |val n2a: N2H = N2H()
        |val n2b: N2H = n2a
        |val g: LoopGraph = LitterBox.graph(
        |  name = "n2",
        |  plan = Plan(entry = n2a.Start, edges = List(Edge.To(n2b.Start, n2a.OpenPr, _ => Some(PrInputN2())))),
        |  dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("could not identify or fully parse")
  }

  it should "refuse to typecheck a This-aliased class member reached from a NESTED object, where the macro's own splice site is enclosed by the nested object rather than the outer class the alias is typed as (issue #43 review round 4, Tier 1, reproduction n12: callSiteClass becomes the wrong class entirely)" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class PrInputN12() extends RequiresReviewInput
        |
        |class N12C:
        |  val Start: Node[Unit, Unit] = Node(
        |    name = "Start", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val OpenPr: Node[PrInputN12, Unit] = Node(
        |    name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val self0: N12C = this
        |  object Inner:
        |    val g: LoopGraph = LitterBox.graph(
        |      name = "n12",
        |      plan = Plan(entry = Start, edges = List(Edge.To(self0.Start, OpenPr, _ => Some(PrInputN12())))),
        |      dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |    )
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("could not identify or fully parse")
  }

  it should "refuse to typecheck a This-aliased class member where the alias is ascribed to a SUPERTYPE of the enclosing class rather than the class itself (issue #43 review round 4, Tier 1, reproduction n13: the widened receiver type no longer equals the splice site's own enclosing class)" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |trait N13Base:
        |  val Start: Node[Unit, Unit]
        |case class PrInputN13() extends RequiresReviewInput
        |
        |class N13C extends N13Base:
        |  val Start: Node[Unit, Unit] = Node(
        |    name = "Start", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val OpenPr: Node[PrInputN13, Unit] = Node(
        |    name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |    probe = _ => None, run = _ => NodeOutcome.Done(())
        |  )
        |  val self0: N13Base = this
        |  val g: LoopGraph = LitterBox.graph(
        |    name = "n13",
        |    plan = Plan(entry = Start, edges = List(Edge.To(self0.Start, OpenPr, _ => Some(PrInputN13())))),
        |    dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |  )
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("could not identify or fully parse")
  }

  // ---- 28 and 29: issue #43 review round 4, Family 2 -------------------------------------------------
  // ---- one Node VALUE bound under two different top-level vals. Not closable by keying, and not an ----
  // ---- oversight left standing: `checkReconciled`'s own doc (`KitMacro.scala`) records at length why ----
  // ---- no widening of this macro's own walk, and no decline rule expressible over a single Shape -------
  // ---- literal, can recover the fact that n19Same's own initialiser IS n19Start. This macro reads -------
  // ---- SOURCE, never a running VALUE, so two symbols that happen to alias the same value at runtime ----
  // ---- key differently here regardless, and there is no way to notice the alias without evaluating -----
  // ---- exactly the code this macro is built never to run. What makes this residual SURVIVABLE, not -----
  // ---- merely accepted, is issue #43 review round 4's own Tier 2 (`Kit.scala`'s own doc on -------------
  // ---- markerRequiresReview/Node.apply): Runner.validate reads the REAL, already-resolved Node values, --
  // ---- n19Start and n19Same are not two facts to reconcile, only one value read twice, so it still -----
  // ---- finds this violation even though the macro cannot.

  it should "compile a literal Plan whose only path into a marker-guarded node crosses the marker-guarded node through a SECOND val aliased to the first, silently missing the violation (issue #43 review round 4, family 2: one Node value bound under two stable paths, the residual Tier 1/Tier 2 cannot close by keying)" in {
    // This is the exact shape `README.md`'s own residual paragraph documents, kept in sync with it
    // rather than merely asserted here: `n19Same` is not a second `Node`, it is `n19Start` itself,
    // read back out through a second `val`. `stablePathKey`'s own `canonical` keys each `val` on its
    // OWN symbol, so `n19Start` and `n19Same` key differently even though they name one runtime value,
    // and the edge written through `n19Same` never links to the `entry` element written through
    // `n19Start`: `n19Open` is never reached by this walk's own BFS, so it reports nothing wrong, even
    // though the real graph has exactly one path into `n19Open` and it crosses no reviewer at all.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class ZZInN19() extends RequiresReviewInput
        |
        |val n19Start: Node[Unit, Unit] = Node(
        |  name = "Start", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val n19Same: Node[Unit, Unit] = n19Start
        |val n19Open: Node[ZZInN19, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val g: LoopGraph = LitterBox.graph(
        |  name = "n19",
        |  plan = Plan(entry = n19Start, edges = List(Edge.To(n19Same, n19Open, _ => Some(ZZInN19())))),
        |  dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    // Documenting the residual, not endorsing it: `errors shouldBe empty` here is the known gap, and
    // `Runner.validate` (`GraphValidationSpec`'s own "Guard is derived from the input type" section)
    // is the runtime backstop that survives this branch precisely because Tier 2 made it read the real
    // Node's own guard field correctly for a marker-carrying input, unaffected by which val aliased it.
    errors shouldBe empty
  }

  it should "control for the test above: refuse to typecheck the IDENTICAL plan once the alias is removed and every reference spells the same val consistently" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class ZZInN19b() extends RequiresReviewInput
        |
        |val n19bStart: Node[Unit, Unit] = Node(
        |  name = "Start", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val n19bOpen: Node[ZZInN19b, Unit] = Node(
        |  name = "OpenPr", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)
        |val g: LoopGraph = LitterBox.graph(
        |  name = "n19-control",
        |  plan = Plan(entry = n19bStart, edges = List(Edge.To(n19bStart, n19bOpen, _ => Some(ZZInN19b())))),
        |  dispatchBudget = (_: Config) => 0, startInput = (_: Int) => ()
        |)
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("rejected at compile time")
  }

  // ---- 30: the marker fact, derived in a REAL, separately compiled foreign package -------------------
  // ---- (issue #26 PR review). Everything above this line is a `typeCheckErrors` snippet, and TEST.md --
  // ---- is explicit that a snippet resolves imports and package paths differently from a real file, ----
  // ---- which is why two earlier rounds of this review sequence each found a BLOCKER a snippet-only -----
  // ---- pass had missed. The suppression this fix closes was a fact read off a `using GuardOf[I]` -------
  // ---- clause a call site could pass its own argument for, so the thing worth proving is that a --------
  // ---- foreign package writing down every argument `Node.apply` still accepts, in a genuinely ----------
  // ---- separately compiled unit, cannot move the derived guard: `GraphValidationSpec` pins the ---------
  // ---- negative half (the old suppression no longer compiles at all) through snippets, and this ---------
  // ---- pins the positive half for real.

  /** A consumer's own input type carrying the marker, declared at bare top level of this class rather
    * than inside the test body, so it is an ordinary, separately compiled type in a package this
    * library does not own, exactly what a real consumer would write.
    */
  private final case class ConsumerMarkerInput() extends in.rcard.litterbox.RequiresReviewInput

  it should "stamp Guard.RequiresReview on a foreign package's own marker-input node even when that call site writes guard = Guard.Open and supplies Node.apply's one remaining using argument by hand" in {
    import in.rcard.litterbox._

    val node = Node[ConsumerMarkerInput, Unit](
      name = "ConsumerSuppressionAttempt",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(()),
      guard = Guard.Open
    )(using TrustOf.plain[Unit])

    node.guard shouldBe Guard.RequiresReview
  }
