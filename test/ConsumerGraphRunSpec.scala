package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import Caps.given

/** Functional, in-memory proof that `LitterBox.graph` (issue #43, RFC #26 decisions 5 and 8) builds a
  * `LoopGraph` `Machine.runOnce` genuinely WALKS, not merely one that type-checks in the parameter
  * position. `ConsumerGraphSpec` (`test/ConsumerGraphSpec.scala`) proves the package-boundary half,
  * that a foreign package can write the calls down at all; this file proves the other half, that
  * `Machine.runOnce`'s own `graph` parameter, `src/Machine.scala`'s own doc used to call untestable
  * plumbing before this issue, is actually read.
  *
  * This file lives inside `in.rcard.litterbox` itself, unlike `ConsumerGraphSpec`, because it drives
  * `Machine.runOnce` directly against `TestWorld` (`test/Recorder.scala`), the same seam every other
  * behavioural spec in this suite uses, and `TestWorld` itself is library-side code, reachable only
  * from within this package (`TestWorld`'s own doc on its `AgentDispatchImpl` override has the reason,
  * RFC #26 decision 14). Nothing here is about the package BOUNDARY; it is about `graph`'s effect once
  * a `LoopGraph`, however obtained, reaches `runOnce`.
  */
class ConsumerGraphRunSpec extends AnyFlatSpec with Matchers:

  /** A node input carrying the review marker, declared out here rather than inside a test body
    * because `Node.apply` derives the guard from this type at the CALL site and a local type would
    * make that derivation read differently from how a consumer's own top level type reads.
    */
  private final case class GuardedInput() extends RequiresReviewInput

  private def plainNode(name: String): Node[Unit, Unit] =
    Node[Unit, Unit](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )

  /** A node whose `run` does exactly one observable thing, `caps.logger.log(message)`, before
    * finishing (issue #43 review, MAJOR 3): `plainNode` above makes no capability call at all, so a
    * graph built entirely from it produces the identical `world.logLines` a graph sabotaged to ignore
    * `graph` entirely would also produce, nothing distinguishes "this graph's own nodes ran" from
    * "some other empty walk reached the same `LoopExit`". This helper is what test 11 below uses
    * instead, so the two nodes' own log lines, in order, are a fact only a genuine walk of THIS
    * graph's own `Next.Goto` chain can produce.
    */
  private def loggingNode(name: String, message: String): Node[Unit, Unit] =
    Node[Unit, Unit](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        summon[Caps].logger.log(message)
        NodeOutcome.Done(())
    )

  /** `Machine.runOnce`'s own individual capability parameters, read off a `TestWorld`, the identical
    * shape `TestWorld.runLoop` builds internally (`test/Recorder.scala`) except that helper has no
    * `graph` parameter of its own to vary, so every test below calls `Machine.runOnce` directly rather
    * than through it. Two overloads, the same split `buildCaps` (`test/Recorder.scala`) uses and for
    * the same reason: a caller with no reason to script the clock writes `runOnce(world, myGraph)`,
    * and the timeout test below, the one that does, writes `runOnce(world, myGraph, clock)` instead.
    */
  private def runOnce(world: TestWorld, graph: LoopGraph, cfg: Config = Config()): LoopExit =
    runOnce(world, graph, world.clock, cfg)

  private def runOnce(world: TestWorld, graph: LoopGraph, clock: Clock, cfg: Config): LoopExit =
    Machine.runOnce(1, graph)(using
      cfg,
      world.github,
      world.git,
      world.agents,
      world.gates,
      world.hostGates,
      world.status,
      world.notifier,
      world.fs,
      clock,
      world.logger
    )

  // ---- 11: a two-node consumer graph reaches the expected exit, and world.logLines shows the --------
  // ---- expected sequence: the first proof in this repo's history that Machine.runOnce's own ---------
  // ---- graph parameter is actually read, not merely accepted (src/Machine.scala's own doc used ------
  // ---- to record this as untestable plumbing) -------------------------------------------------------

  "Machine.runOnce" should "walk a two-node consumer graph built through LitterBox.graph, reaching the declared LoopExit and driving the calls its own nodes make" in {
    // Distinguishable observable effects, not `plainNode` (issue #43 review, MAJOR 3, correcting an
    // earlier version of this test): a pristine `TestWorld` run through `Machine.runOnce(1,
    // LitterBox.shipped)` also reaches `LoopExit.Success`, so asserting only on `exit` here cannot
    // tell "this graph's own two nodes actually ran" apart from "`runOnce` silently ignored `graph`
    // and did something else", confirmed by the reviewer sabotaging `runOnce` to ignore its own
    // `graph` parameter entirely and finding this exact assertion still passed. `loggingNode` closes
    // that gap: each node's own log line, and their RELATIVE ORDER in `world.logLines`, is a fact only
    // a genuine walk of this graph's own `Next.Goto(first, ..., _ => Next.Goto(second, ...))` chain,
    // never the shipped graph or an ignored parameter, can produce.
    val first  = loggingNode("First", "consumer node First ran")
    val second = loggingNode("Second", "consumer node Second ran")
    val myGraph = LitterBox.graph(
      name = "consumer-two-node",
      plan = Plan(
        entry = first,
        edges = List(
          Edge.To(first, second, _ => Some(())),
          Edge.Exit(second, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.Success
    val firstIdx  = world.logLines.indexWhere(_.contains("consumer node First ran"))
    val secondIdx = world.logLines.indexWhere(_.contains("consumer node Second ran"))
    firstIdx should be >= 0
    secondIdx should be >= 0
    firstIdx should be < secondIdx
  }

  // ---- 12: the runner, not the consumer, owns the dispatch budget -----------------------------------

  it should "refuse the second of two Cost.OneDispatch nodes when dispatchBudget declares only one dispatch, proving the runner owns the budget, not the consumer" in {
    val first = Node[Unit, Unit](
      name = "First",
      cost = Cost.OneDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        summon[AgentDispatch].worker(Role.IMPL, "prompt", "patch", "log", None)
        NodeOutcome.Done(())
    )
    val second = Node[Unit, Unit](
      name = "Second",
      cost = Cost.OneDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        summon[AgentDispatch].worker(Role.IMPL, "prompt", "patch", "log", None)
        NodeOutcome.Done(())
    )
    val myGraph = LitterBox.graph(
      name = "consumer-budget",
      plan = Plan(
        entry = first,
        edges = List(
          Edge.To(first, second, _ => Some(())),
          Edge.Exit(second, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = _ => 1,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.Parked
    world.callCount("dispatch IMPL") shouldBe 1
    world.logLines.exists(l => l.contains("Second") && l.contains("exhausted")) shouldBe true
  }

  // ---- 13: the runner, not the consumer, owns the timeout clock -------------------------------------

  it should "fault a consumer node declaring Timeout.After(1) once a FakeClock steps past it, proving the runner owns the timeout" in {
    val slow = Node[Unit, Unit](
      name = "Slow",
      cost = Cost.NoDispatch,
      timeout = Timeout.After(1),
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )
    val myGraph = LitterBox.graph(
      name = "consumer-timeout",
      plan = Plan(entry = slow, edges = List(Edge.Exit(slow, _ => Some(LoopExit.Success)))),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val clock = new FakeClock(List(0L, 5000L)) // 5s elapsed against a 1s bound
    val exit  = runOnce(world, myGraph, clock, Config())

    exit shouldBe LoopExit.InfraFault
    world.logLines.exists(l => l.contains("Slow") && l.contains("timeout")) shouldBe true
  }

  // ---- 14: an invalid declared Shape faults rc 50 naming THIS graph's own workflow name, not ---------
  // ---- "shipped" (exercises the `.name` fix at src/Machine.scala, never tested before this issue) ----

  it should "fault rc 50 on an invalid declared Shape, naming the consumer's own workflow name in Runner.invalidShapeMessage rather than \"shipped\"" in {
    val only    = plainNode("Only")
    val orphanA = plainNode("OrphanA")
    val orphanB = plainNode("OrphanB")
    // The `plan` argument to `LitterBox.graph` below has to be written as a literal `Plan(...)`
    // expression right at that call site, never a `val` passed by identifier (issue #43 review,
    // BLOCKER 1, correcting an earlier version of this test): `LitterBox.graph` splices
    // `checkedPlan`, which hard-errors at compile time on exactly the indirection a `val` passed by
    // identifier used to be. `declaredShape` below is what the plan two lines further down DERIVES
    // (issue #67: nothing hand writes a second `Shape` any more), spelled out here only so this test
    // can assert the exact message `Runner.invalidShapeMessage` builds for it.
    val declaredShape = Shape(entry = List(only), transitions = List(Transition(orphanA, orphanB)))
    val myGraph = LitterBox.graph(
      name = "consumer-invalid",
      plan = Plan(
        entry = only,
        edges = List(
          Edge.To(orphanA, orphanB, _ => Some(())),
          Edge.Exit(only, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.InfraFault
    world.logLines should contain(
      Runner.invalidShapeMessage("consumer-invalid", Runner.validate(declaredShape))
    )
  }

  // ---- 15: dispatchBudget bounds Cost.OneDispatch STARTS, not total spend (issue #43 review round ----
  // ---- 2, MAJOR M2): a Cost.NoDispatch node dispatching under dispatchBudget = _ => 0 still ----------
  // ---- completes, pinning the documented residual rather than leaving it only asserted in prose ------

  it should "let a Cost.NoDispatch node dispatch through AgentDispatch even when dispatchBudget declares zero, pinning the documented residual that dispatchBudget bounds Cost.OneDispatch starts, never total spend" in {
    // This is NOT an endorsement of the behaviour it pins, and it is not a bug this branch left
    // unfixed either: `Runner.Ledger.canAfford(Cost.NoDispatch)` is unconditionally `true`
    // (`Kit.scala`), checked once, before this node's own `run` starts, and nothing inside `run`
    // consults the ledger again, so a node that DECLARES `Cost.NoDispatch` while its own body actually
    // calls `agents.*` spends outside the budget entirely, regardless of what `dispatchBudget` returns.
    // `LoopGraph`'s own trait doc (`src/LitterBox.scala`) and `README.md`'s "Write your own loop"
    // section both name this residual and the reason a stricter, mid-node-enforced ledger was
    // deliberately not attempted on this branch (it would move the shipped graph's own golden-log-pinned
    // budget-exhaustion behaviour). This test exists so that residual is a checked fact, not only a
    // claim in prose that could quietly stop being true.
    val misdeclared = Node[Unit, Unit](
      name = "Misdeclared",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        summon[AgentDispatch].worker(Role.IMPL, "prompt", "patch", "log", None)
        NodeOutcome.Done(())
    )
    val myGraph = LitterBox.graph(
      name = "consumer-nodispatch-residual",
      plan =
        Plan(entry = misdeclared, edges = List(Edge.Exit(misdeclared, _ => Some(LoopExit.Success)))),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.Success
    world.callCount("dispatch IMPL") shouldBe 1
  }

  // ---- 16: issue #67, the single representation: the graph below names each edge in exactly one ------
  // ---- place, and the walk Machine.runOnce performs is derived from that one place ------------------

  it should "walk a two-node consumer graph whose edges are declared exactly once, as a Plan handed to LitterBox.graph" in {
    val first  = loggingNode("First", "plan node First ran")
    val second = loggingNode("Second", "plan node Second ran")
    val myGraph = LitterBox.graph(
      name = "consumer-plan-two-node",
      plan = Plan(
        entry = first,
        edges = List(
          Edge.To(first, second, _ => Some(())),
          Edge.Exit(second, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.Success
    val firstIdx  = world.logLines.indexWhere(_.contains("plan node First ran"))
    val secondIdx = world.logLines.indexWhere(_.contains("plan node Second ran"))
    firstIdx should be >= 0
    secondIdx should be >= 0
    firstIdx should be < secondIdx
  }

  // ---- 17: issue #67, a plan that runs out of edges is a fault, never a quiet success ---------------

  it should "fault rc 50, naming the node it got stuck at, when every edge leaving a node declines the value that node produced" in {
    // The failure mode this pins is the one a derived walk could plausibly have chosen instead:
    // inventing a `LoopExit` nobody declared, or ending the tick as if it had succeeded. Neither is
    // honest, so a plan with nowhere left to go goes through the same fault channel every other
    // failure in this loop uses.
    val stuck = loggingNode("Stuck", "stuck node ran")
    val myGraph = LitterBox.graph(
      name = "consumer-dead-end",
      plan = Plan(entry = stuck, edges = List(Edge.Exit(stuck, _ => None))),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.InfraFault
    world.logLines.exists(l => l.contains("consumer-dead-end") && l.contains("'Stuck'")) shouldBe true
    // The node itself really did run: this is a graph that got stuck AFTER doing work, not one
    // rejected before it started, which is what makes the distinction from an invalid Shape sharp.
    world.logged("stuck node ran") shouldBe true
  }

  // ---- 18: issue #67, the runtime backstop survives for the one graph the compile time check --------
  // ---- cannot fully read: one Node VALUE bound under two vals, which the macro keys as two ----------

  it should "fault rc 50 through Runner.validate on a Plan whose review violation the compile time macro cannot see, because one node value is bound under two different vals" in {
    // `alias` is not a second node, it is `entry` read back out through a second `val`, so the macro
    // keys the two references differently and its own walk never links the edge to the entry: this
    // graph compiles. `Runner.validate` reads the REAL, already-resolved `Node` values off the
    // derived `Shape`, where an alias is one value read twice rather than a fact to reconstruct, so
    // the violation is caught at startup instead. `ConsumerGraphSpec`'s own n19 test pins the compile
    // time half of this exact residual; this pins that the backstop behind it still fires.
    val entry   = loggingNode("Entry", "backstop entry ran")
    val alias   = entry
    val guarded = Node[GuardedInput, Unit](
      name = "Guarded",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )
    val myGraph = LitterBox.graph(
      name = "consumer-alias-residual",
      plan = Plan(
        entry = entry,
        edges = List(
          Edge.To(alias, guarded, _ => Some(GuardedInput())),
          Edge.Exit(guarded, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.InfraFault
    world.logLines.exists(l =>
      l.contains("consumer-alias-residual") && l.contains("Entry -> Guarded") && l.contains("'Guarded'")
    ) shouldBe true
    // Rejected BEFORE any node ran, unlike the dead end above: a bad declaration is a fact about the
    // graph that is true before the first node's own side effects could fire.
    world.logged("backstop entry ran") shouldBe false
  }

  // ---- 19: issue #67 review, the derived walk keys an edge's source on the NODE it leaves, ----------
  // ---- never on that node's name, so two distinct nodes sharing one name are never merged -----------

  it should "walk the edges leaving each of two distinct nodes that happen to share one name separately, never treating the second as an alias of the first" in {
    // `dupA` and `dupB` are two DIFFERENT `Node` values, deliberately given the same `name`, "Dup",
    // and identical `cost`/`timeout`/`trust`/`guard` so `Runner.validate`'s own duplicate-name check
    // (`identityOf`, `Kit.scala`) has nothing to disagree on: this test is entirely about the WALK,
    // never about a violation `validate` was already going to catch. A walk keyed on `name` alone
    // cannot tell `dupA`'s own outgoing edge from `dupB`'s, so once `dupB` runs it wrongly takes
    // `dupA`'s edge back into `dupMid`, and the walk cycles Mid/Dup forever, never reaching `dupEnd`.
    // Stepping by hand, a bounded number of hops, rather than running the graph through
    // `Machine.runOnce`/`TestWorld.runGraph`, is deliberate: that cycle is a genuine infinite loop on
    // the unfixed code (confirmed by hand: tens of millions of lines in under two minutes before being
    // killed), so a test that let it run to completion could hang the suite instead of failing it.
    val dupA = Node[Unit, Unit](
      name = "Dup",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )
    val dupMid = loggingNode("Mid", "dup mid ran")
    val dupB = Node[Unit, Unit](
      name = "Dup",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )
    val dupEnd = loggingNode("End", "dup end ran")

    val plan = Plan(
      entry = dupA,
      edges = List(
        Edge.To(dupA, dupMid, (_: Unit) => Some(())),
        Edge.To(dupMid, dupB, (_: Unit) => Some(())),
        Edge.To(dupB, dupEnd, (_: Unit) => Some(())),
        Edge.Exit(dupEnd, (_: Unit) => Some(LoopExit.Success))
      )
    )

    val world = new TestWorld
    val caps  = buildCaps(world)

    val stepped = withFaulting:
      val fault    = Fault(summon[Faulting], caps.logger, caps.notifier)
      val workflow = Plan.workflowOf("dup-walk", plan, StageSet(Nil, None, None))(using caps, summon[Faulting])

      @scala.annotation.tailrec
      def hop(next: Next, remaining: Int): Next =
        next match
          case f: Next.Finish => f
          case _ if remaining == 0 => next
          case Next.Goto(node, input, andThen) =>
            val out = node.run(input)(using caps, fault) match
              case NodeOutcome.Done(value)   => value
              case NodeOutcome.Stopped(exit) => fail(s"node '${node.name}' stopped early with $exit")
            hop(andThen(out), remaining - 1)

      // A straight walk of this plan is four hops: dupA (entry), dupMid, dupB, dupEnd, then Finish.
      // Ten is generous headroom without being unbounded: on the unfixed code the walk is cycling
      // Mid/Dup well before hop 10 and never reaches Finish at all.
      hop(workflow.start(()), 10)

    stepped shouldBe Right(Next.Finish(LoopExit.Success))
    world.logged("dup end ran") shouldBe true
  }

  // ---- 20: Edge's own declaration order precedence rule, pinned by a plan where two edges leaving ----
  // ---- the same node both answer Some for the identical produced value ------------------------------

  it should "take the first-declared edge out of a node when a second, later edge out of that same node also answers Some for the produced value" in {
    // Every runnable plan elsewhere in this suite gives each source node at most one edge that can
    // answer Some for a given value, so declaration order is never exercised by them: replacing
    // Plan.workflowOf's collectFirst with a last-Some or an arbitrary-Some selection would leave the
    // whole suite green. Branch declares two edges to two different, distinguishable destinations,
    // both predicates unconditionally Some, so only reading the FIRST one, in the order written here,
    // reaches firstDest rather than secondDest.
    val branch     = loggingNode("Branch", "branch ran")
    val firstDest  = loggingNode("FirstDest", "first dest ran")
    val secondDest = loggingNode("SecondDest", "second dest ran")
    val myGraph = LitterBox.graph(
      name = "consumer-edge-precedence",
      plan = Plan(
        entry = branch,
        edges = List(
          Edge.To(branch, firstDest, _ => Some(())),
          Edge.To(branch, secondDest, _ => Some(())),
          Edge.Exit(firstDest, _ => Some(LoopExit.Success)),
          Edge.Exit(secondDest, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = _ => 0,
      startInput = _ => ()
    )

    val world = new TestWorld
    val exit  = runOnce(world, myGraph)

    exit shouldBe LoopExit.Success
    world.logged("first dest ran") shouldBe true
    world.logged("second dest ran") shouldBe false
  }
