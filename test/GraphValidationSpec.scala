package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import Caps.given

/** Startup graph validation (issue #38, RFC #26 decision 16's cheap half): `Runner.validate` walks a
  * `Workflow`'s own DECLARED `Shape` (`Kit.scala`), never the `Next.Goto` closures themselves, since
  * those closures are opaque and, in `Machine.shippedWorkflow`, side effecting (`Kit.scala`'s own
  * doc on `Shape` has the full reasoning for why a second, hand written shape has to exist at all).
  *
  * Every graph in this file is built from fake nodes, the same style `RunnerSpec` already uses for
  * the generic `Node`/`Workflow`/`Runner` mechanics: this spec's job is `validate` and the `Trust`
  * derivation it depends on, neither of which needs a real capability to exercise. The handful of
  * tests that DO need one (`Runner.run` short circuiting before `wf.start`, and `Runner.step`'s own
  * runtime `Trust` check) genuinely borrow `TestWorld`'s own `FakeClock`/`buildCaps`/`withFaulting`
  * trio from `test/Recorder.scala` (issue #38 review nit), the same seam `RunnerSpec` and
  * `ShippedWorkflowSpec` use, rather than a second, locally reimplemented copy.
  */
class GraphValidationSpec extends AnyFlatSpec with Matchers:

  /** A `Trust.Plain` fake node (any output type but `AgentDispatch.Judged`), for the parts of a
    * declared `Shape` that are not the reviewer itself.
    */
  private def plainNode(name: String, guard: Guard = Guard.Open): Node[Unit, Unit] =
    Node[Unit, Unit](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(()),
      guard = guard
    )

  /** A `Trust.Reviewed` fake node: its OUTPUT type is `AgentDispatch.Judged[Unit]`, so `Node.apply`'s
    * own `given TrustOf[O]` stamps `Trust.Reviewed` without this helper ever saying so (that
    * derivation is exactly what "Trust is derived from the output type" below asserts). Minting the
    * `Judged` for real, through `AgentDispatch.review`, rather than a shortcut, because that is the
    * only way this file (or any file) can ever produce one at all (`AgentDispatch.Judged`'s own doc,
    * `src/Caps.scala`); this `run` body is never actually invoked by the tests that only inspect
    * `.trust`, but it still has to type check like a real node's would.
    */
  private def reviewedNode(name: String, guard: Guard = Guard.Open): Node[Unit, AgentDispatch.Judged[Unit]] =
    Node[Unit, AgentDispatch.Judged[Unit]](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ())),
      guard = guard
    )

  /** The forgery issue #38 review BLOCKER 2 traced: a node whose DECLARED output type is
    * `AgentDispatch.Judged[Unit]`, so it earns `Trust.Reviewed` from the exact same derivation
    * `reviewedNode` above relies on, but whose `run` body never calls `AgentDispatch.review` at all,
    * only ever returning `Stopped(exit)`. `NodeOutcome.Stopped` carries no `O`-typed value (its own
    * declaration is `case Stopped(exit: LoopExit)`), so it types as `NodeOutcome[Nothing]`, and
    * `NodeOutcome`'s own covariance in `O` lets that satisfy the declared `NodeOutcome[Judged[Unit]]`
    * return type without ever constructing a real `Judged`. `Runner.step`'s own doc explains why a
    * `Stopped` result is NOT itself something that check faults on.
    */
  private def neverReviewsNode(name: String, exit: LoopExit): Node[Unit, AgentDispatch.Judged[Unit]] =
    Node[Unit, AgentDispatch.Judged[Unit]](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Stopped(exit)
    )

  /** Casts `outcome` across its type parameter through erasure, not a direct `asInstanceOf` on the
    * PAYLOAD (`NodeOutcome` itself is the thing being cast, and `NodeOutcome[Unit]`/
    * `NodeOutcome[AgentDispatch.Judged[Unit]]` erase to the same raw class), so the cast succeeds
    * silently at this call site instead of throwing a `ClassCastException` immediately, the way
    * casting the boxed `Unit` PAYLOAD directly to `AgentDispatch.Judged` would. This is what lets
    * the forgery below reach `Runner.step` at all: the mismatch only becomes observable where
    * `Runner.step` itself inspects the real payload, which is exactly the point its own runtime
    * check exists to guard.
    */
  private def widen[A, B](outcome: NodeOutcome[A]): NodeOutcome[B] = outcome.asInstanceOf[NodeOutcome[B]]

  /** A second, sharper forgery than `neverReviewsNode`: this node's `run` DOES return a `Done`, but
    * the value inside is not really an `AgentDispatch.Judged` at runtime, only cast to look like one
    * at the type level. Building this honestly is impossible (`Judged`'s own constructor is
    * `private[AgentDispatch]`, see `RunnerSpec`'s own mint-site tests), so this stands in for
    * whatever unsound path, a bug elsewhere, an unsafe cast nobody meant to ship, could otherwise
    * hand `Runner.step` a `Done` for a `Trust.Reviewed` node that never went through a genuine
    * review. `Runner.step`'s own runtime check (`Kit.scala`) is what catches this at the one point
    * it can still be observed.
    */
  private def forgingDoneNode(name: String): Node[Unit, AgentDispatch.Judged[Unit]] =
    Node[Unit, AgentDispatch.Judged[Unit]](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => widen[Unit, AgentDispatch.Judged[Unit]](NodeOutcome.Done(()))
    )

  /** A cast-FREE version of the same forgery `forgingDoneNode` above needs `widen`'s own
    * `asInstanceOf` to reach: `NodeOutcome.Done(null)` typechecks against the declared return type
    * `NodeOutcome[AgentDispatch.Judged[Unit]]` with no cast anywhere in sight, because `Null` is a
    * subtype of every reference type (`AgentDispatch.Judged[Unit]` is a class, hence an `AnyRef`),
    * and `null.isInstanceOf[AgentDispatch.Judged[?]]` is `false`, so `Runner.step`'s own runtime
    * check (`Kit.scala`) still catches it on the first `Done`. This is the more realistic sibling of
    * `forgingDoneNode`: nobody has to reach for `asInstanceOf` at all to produce a `Node` whose
    * declared output type says `Trust.Reviewed` but whose body never built one. A `run` that
    * returns `null` on some path it forgot to cover (a `Map` lookup, an uninitialised field from
    * interop code, ...) reaches the exact same gap.
    */
  private def nullDoneNode(name: String): Node[Unit, AgentDispatch.Judged[Unit]] =
    Node[Unit, AgentDispatch.Judged[Unit]](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(null)
    )

  /** A marker input, so a `Node[MarkerInput, Unit]` built here is exactly the shape issue #43 review
    * round 4's Tier 2 is about: its own input type extends `RequiresReviewInput`, so
    * `markerRequiresReview[MarkerInput]` (`Kit.scala`) answers `true` and `Node.apply` stamps
    * `Guard.RequiresReview` on the constructed value whatever else the call site wrote.
    */
  private final case class MarkerInput() extends RequiresReviewInput

  /** A `Node` whose INPUT type carries the `RequiresReviewInput` marker (`MarkerInput` above), built
    * with whatever `guard` the caller passes, defaulting to `Node.apply`'s own default `Guard.Open`
    * exactly the way `Machine.OpenPr`/`Machine.Merge` are built (their own doc, `Machine.scala`, has
    * the reason those two never extend the marker themselves; this helper is what a node author who
    * DID extend it, by mistake or on purpose, looks like).
    */
  private def markerNode(name: String, guard: Guard = Guard.Open): Node[MarkerInput, Unit] =
    Node[MarkerInput, Unit](
      name = name,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(()),
      guard = guard
    )

  /** A chain of `depth` diamonds, `D0 -> (A1, B1) -> D1 -> (A2, B2) -> D2 -> ... -> Dn`, `Dn` alone
    * guarded and never reviewed. Before issue #38 review finding 4's fix, a depth first walk that
    * threaded a fresh `visited` set down each path enumerated every PATH through this shape, `2^depth`
    * of them, all converging on the same violation at `Dn`; the fix's global, `(name, reviewed)`-keyed
    * visited set instead expands each STATE once, so this stays linear in `depth`.
    */
  private def diamondChain(depth: Int): Shape =
    val d0 = plainNode("D0")
    val (allNodes, transitions) =
      (1 to depth).foldLeft((List(d0), List.empty[Transition])) { case ((nodes, trans), i) =>
        val prev = nodes.last
        val a    = plainNode(s"A$i")
        val b    = plainNode(s"B$i")
        val d    = if i == depth then plainNode(s"D$i", guard = Guard.RequiresReview) else plainNode(s"D$i")
        (
          nodes :+ d,
          trans ++ List(Transition(prev, a), Transition(prev, b), Transition(a, d), Transition(b, d))
        )
      }
    Shape(entry = List(d0), transitions = transitions)

  // ---- Trust is derived from the output type, never declared by hand -----------------------

  "A Node's Trust" should "be Reviewed when its output type is AgentDispatch.Judged[?], with no author declaration" in {
    reviewedNode("Reviewer").trust shouldBe Trust.Reviewed
  }

  it should "be Plain for any other output type, with no author declaration" in {
    plainNode("Plain").trust shouldBe Trust.Plain
  }

  it should "be forgeable (issue #38 review, BLOCKER 2): Reviewed on a node whose run only ever " +
    "returns Stopped and never calls AgentDispatch.review" in {
      val forged = neverReviewsNode("never-reviews", LoopExit.Idle)
      forged.trust shouldBe Trust.Reviewed
    }

  // ---- Guard is derived from the input type when it carries the marker, folded with the hand ----
  // ---- written default rather than replacing it (issue #43 review round 4, Tier 2) ---------------

  "A Node's Guard" should "be RequiresReview when its input type extends RequiresReviewInput, even though the guard argument was left at its default Guard.Open" in {
    // This is the exact shape `Machine.OpenPr`/`Machine.Merge` deliberately avoid (their own doc has
    // the reason) and the exact shape issue #43 review round 4's own reproductions n1/n2/n12/n13
    // reached with no reviewer on the path, undetected by `Runner.validate` before this fix because
    // `Runner.validate` read this field, which used to stay `Guard.Open` here regardless of the marker.
    markerNode("Marker").guard shouldBe Guard.RequiresReview
  }

  it should "still be RequiresReview when the input type extends the marker and guard was ALSO written explicitly as Guard.RequiresReview, the consistent spelling" in {
    markerNode("Marker", guard = Guard.RequiresReview).guard shouldBe Guard.RequiresReview
  }

  it should "be RequiresReview, from the hand written argument alone, when guard was written explicitly but the input type does NOT extend the marker" in {
    // `Node.apply`'s own combination (`Kit.scala`, issue #43 review round 5, FINDING 2) never discards
    // the hand written argument in favour of the marker fact: absent the marker,
    // `markerRequiresReview[I]` is `false`, so the OR reduces to exactly what the author wrote,
    // `Guard.RequiresReview` preserved unchanged rather than downgraded to `Guard.Open`.
    plainNode("Guarded", guard = Guard.RequiresReview).guard shouldBe Guard.RequiresReview
  }

  it should "be Open when the input type does not extend the marker and guard was left at its default, ordinary use" in {
    plainNode("Plain").guard shouldBe Guard.Open
  }

  it should "stay Open on Machine.OpenPr-shaped and Machine.Merge-shaped nodes: neither extends the marker, so this fix changes nothing about them" in {
    // Stated as a positive assertion, not merely inferred from the two nodes' own source: `plainNode`
    // here plays the role `OpenPr`/`Merge` do, a `Unit`-shaped input built with `guard` left at its
    // default, and the derivation added by this fix has no marker to key on, so it reduces to the
    // hand written default exactly as it always did.
    plainNode("OpenPrShaped").guard shouldBe Guard.Open
  }

  // ---- Runner.validate is now a genuine backstop for a marker-only-guarded node (issue #43 ----
  // ---- review round 4, Tier 2), closing the family this walk could never see before: a node ----
  // ---- whose input type extends RequiresReviewInput while its own guard argument was left at ----
  // ---- Guard.Open used to leave Runner.validate with nothing to find, ever, since it read only ----
  // ---- the guard field, which stayed Guard.Open. Node.apply's own new derivation (above) means ----
  // ---- the SAME field now genuinely is Guard.RequiresReview on the real, constructed Node, so ----
  // ---- this walk, unchanged itself, finds the violation it always should have. ---------------------

  "Runner.validate" should "report a violation for a marker-only-guarded node reached with no reviewer on the path, even though guard was never written by hand" in {
    val guarded = markerNode("Guarded") // guard left at its default Guard.Open; the marker alone does the work now
    val entry   = plainNode("Entry")
    val shape   = Shape(entry = List(entry), transitions = List(Transition(entry, guarded)))

    val violations = Runner.validate(shape)

    violations should not be empty
    violations.head should include("Guarded")
  }

  it should "find nothing wrong with the identical shape once a Trust.Reviewed node sits on the path before the marker-only-guarded node" in {
    val reviewer = reviewedNode("Reviewer")
    val guarded  = markerNode("Guarded")
    val entry    = plainNode("Entry")
    val shape = Shape(
      entry = List(entry),
      transitions = List(Transition(entry, reviewer), Transition(reviewer, guarded))
    )

    Runner.validate(shape) shouldBe empty
  }

  // ---- Runner.step's runtime check on Trust.Reviewed nodes (issue #38 review, BLOCKER 2) -----

  "Runner.step" should "not fault when a Trust.Reviewed node returns Stopped: a Stopped result is not a forgery by itself" in {
    val forged = neverReviewsNode("never-reviews", LoopExit.Idle)
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.step(forged, ())

    result shouldBe Right(NodeOutcome.Stopped(LoopExit.Idle))
  }

  it should "fault, through the same channel as every other runtime fault, when a Trust.Reviewed node's Done is not really an AgentDispatch.Judged" in {
    val forged = forgingDoneNode("forger")
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.step(forged, ())

    result shouldBe Left(LoopExit.InfraFault)
    world.logLines.exists(l => l.contains("forger") && l.contains("Trust.Reviewed")) shouldBe true
    world.notifications should not be empty
  }

  it should "fault on a cast-free forgery too: a Trust.Reviewed node whose Done carries null, reachable with no asInstanceOf anywhere" in {
    val forged = nullDoneNode("null-forger")
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.step(forged, ())

    result shouldBe Left(LoopExit.InfraFault)
    world.logLines.exists(l => l.contains("null-forger") && l.contains("Trust.Reviewed")) shouldBe true
    world.notifications should not be empty
  }

  // ---- Node's constructor and copy stay closed to any file but Kit.scala itself ----------------

  "Node" should "refuse to typecheck a foreign call site's new Node(...) or .copy(...)" in {
    // Same idiom as `RunnerSpec`'s own mint-site tests (~line 281) and `ConsumerBoundarySpec`:
    // `scala.compiletime.testing.typeCheckErrors` type-checks the snippet at THIS call site's own
    // lexical position (package `in.rcard.litterbox`, the same package `Node` itself lives in), so
    // this proves the constructor is closed even to library code in a DIFFERENT file, not merely to
    // a consumer's package (`ConsumerBoundarySpec` proves that half separately).
    val ctorErrors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |new Node[Unit, Unit](
        |  "x",
        |  Cost.NoDispatch,
        |  Timeout.Unbounded,
        |  (_: Unit) => None,
        |  (_: Unit) => NodeOutcome.Done(()),
        |  Trust.Reviewed,
        |  Guard.Open
        |)
        |""".stripMargin
    )
    ctorErrors should not be empty
    // Round three of #35 found two tests passing for the wrong reason (`RunnerSpec.scala`'s own
    // note on this, ~line 245); asserting only non-emptiness here would have the same failure mode,
    // so this pins the actual wording `new Node(...)` gets: a private CONSTRUCTOR access failure,
    // never "object Node does not take parameters" (that message belongs to a bare `Node(...)` call
    // finding no `apply` at all, not to `new`, see `Node`'s own scaladoc, `Kit.scala`).
    ctorErrors.map(_.message).mkString("\n") should include("private")

    val copyErrors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |val n = Node[Unit, Unit](
        |  name = "x",
        |  cost = Cost.NoDispatch,
        |  timeout = Timeout.Unbounded,
        |  probe = (_: Unit) => None,
        |  run = (_: Unit) => NodeOutcome.Done(())
        |)
        |n.copy(trust = Trust.Reviewed)
        |""".stripMargin
    )
    copyErrors should not be empty
    val copyMessages = copyErrors.map(_.message).mkString("\n")
    copyMessages should include("private")
    copyMessages should include("copy")
  }

  // ---- valid graphs validate clean -----------------------------------------------------------

  "Runner.validate" should "report no violations when a reviewed node sits on every path into a guarded node" in {
    val a     = plainNode("A")
    val r     = reviewedNode("R")
    val guard = plainNode("Guarded", guard = Guard.RequiresReview)
    val shape = Shape(
      entry = List(a),
      transitions = List(Transition(a, r), Transition(r, guard))
    )

    Runner.validate(shape) shouldBe empty
  }

  it should "accept a guarded node reachable only after the reviewer through a longer chain" in {
    val a     = plainNode("A")
    val b     = plainNode("B")
    val r     = reviewedNode("R")
    val c     = plainNode("C")
    val guard = plainNode("Guarded", guard = Guard.RequiresReview)
    val shape = Shape(
      entry = List(a),
      transitions = List(
        Transition(a, b),
        Transition(b, r),
        Transition(r, c),
        Transition(c, guard)
      )
    )

    Runner.validate(shape) shouldBe empty
  }

  it should "accept a path crossing two reviewers before the guarded node" in {
    val a     = plainNode("A")
    val r1    = reviewedNode("R1")
    val r2    = reviewedNode("R2")
    val guard = plainNode("Guarded", guard = Guard.RequiresReview)
    val shape = Shape(
      entry = List(a),
      transitions = List(Transition(a, r1), Transition(r1, r2), Transition(r2, guard))
    )

    Runner.validate(shape) shouldBe empty
  }

  it should "not flag two declarations sharing a name as duplicates when they agree on cost, timeout, trust and guard" in {
    // `Machine.shippedWorkflow` legitimately declares the same conceptual node (`Repair(cfg)`, say)
    // more than once across its own entry/transitions; each call builds a fresh, non-`==` `Node`
    // value. This is the positive control for the duplicate-name check just below: reusing a name
    // for the SAME conceptual node must never be flagged.
    val a     = plainNode("A")
    val same1 = plainNode("Same")
    val same2 = plainNode("Same")
    val shape = Shape(
      entry = List(a),
      transitions = List(Transition(a, same1), Transition(same1, same2))
    )

    Runner.validate(shape) shouldBe empty
  }

  // ---- missing reviewer entirely --------------------------------------------------------------

  it should "reject a graph reaching a guarded node with no reviewer anywhere on the path, naming the path and the node" in {
    val a     = plainNode("A")
    val guard = plainNode("Guarded", guard = Guard.RequiresReview)
    val shape = Shape(
      entry = List(a),
      transitions = List(Transition(a, guard))
    )

    val violations = Runner.validate(shape)

    violations should have size 1
    violations.head should include("A -> Guarded")
    violations.head should include("'Guarded'")
  }

  it should "reject a guard declared on an entry node itself" in {
    val guardedEntry = plainNode("Entry", guard = Guard.RequiresReview)
    val shape        = Shape(entry = List(guardedEntry), transitions = Nil)

    val violations = Runner.validate(shape)

    violations should have size 1
    violations.head should include("'Entry'")
  }

  it should "reject a node that is both Trust.Reviewed and Guard.RequiresReview when reachable directly " +
    "from entry: a node must not be able to clear its own guard (issue #38 review, second round)" in {
      // `reviewedNode`'s output type is `AgentDispatch.Judged[Unit]`, so this node earns
      // `Trust.Reviewed` from the very same `TrustOf` derivation `reviewedNode` above relies on, and
      // it ALSO carries its own `Guard.RequiresReview`. Before the fix, the walk computed
      // `reviewedHere = reviewed || node.trust == Trust.Reviewed` and then tested the GUARD against
      // `reviewedHere`, so this node's own trust satisfied its own guard: a node that opens a PR and
      // reviews it in the same breath passed validation. The fix tests the guard against `reviewed`,
      // the flag the walk carried in from BEFORE this node ran, which is `false` here since it sits
      // directly on `entry` with nothing before it.
      val guardedReviewer = reviewedNode("GuardedReviewer", guard = Guard.RequiresReview)
      val shape           = Shape(entry = List(guardedReviewer), transitions = Nil)

      val violations = Runner.validate(shape)

      violations should have size 1
      violations.head should include("'GuardedReviewer'")
    }

  it should "reject a guarded node reached before any reviewer, even when a reviewer follows it later on the same path" in {
    val a     = plainNode("A")
    val guard = plainNode("Guarded", guard = Guard.RequiresReview)
    val r     = reviewedNode("R")
    val shape = Shape(
      entry = List(a),
      transitions = List(Transition(a, guard), Transition(guard, r))
    )

    val violations = Runner.validate(shape)

    violations should have size 1
    violations.head should include("A -> Guarded")
  }

  // ---- fork: only the bad branch is named -----------------------------------------------------

  it should "reject only the branch of a fork that skips the reviewer, naming that branch alone" in {
    val a          = plainNode("A")
    val reviewed   = reviewedNode("Reviewed")
    val unreviewed = plainNode("Unreviewed")
    val guard      = plainNode("Guarded", guard = Guard.RequiresReview)
    val shape = Shape(
      entry = List(a),
      transitions = List(
        Transition(a, reviewed),
        Transition(a, unreviewed),
        Transition(reviewed, guard),
        Transition(unreviewed, guard)
      )
    )

    val violations = Runner.validate(shape)

    violations should have size 1
    violations.head should include("A -> Unreviewed -> Guarded")
    violations.head should not include "Reviewed ->"
  }

  // ---- cycles terminate, and a cycle that bypasses the reviewer is still caught --------------

  it should "reject a cycle that never crosses the reviewer, and terminate rather than loop forever" in {
    val a     = plainNode("A")
    val guard = plainNode("Guarded", guard = Guard.RequiresReview)
    val shape = Shape(
      entry = List(a),
      transitions = List(Transition(a, guard), Transition(guard, a))
    )

    val violations = Runner.validate(shape)

    violations should have size 1
    violations.head should include("A -> Guarded")
  }

  it should "terminate on a self loop at the guarded node itself, reporting exactly one violation" in {
    val a     = plainNode("A")
    val guard = plainNode("Guarded", guard = Guard.RequiresReview)
    val shape = Shape(
      entry = List(a),
      transitions = List(Transition(a, guard), Transition(guard, guard))
    )

    val violations = Runner.validate(shape)

    violations should have size 1
    violations.head should include("A -> Guarded")
  }

  it should "report all violations in one call, deterministically ordered by declared entry and transition order" in {
    val a      = plainNode("A")
    val guard1 = plainNode("Guarded1", guard = Guard.RequiresReview)
    val guard2 = plainNode("Guarded2", guard = Guard.RequiresReview)
    val shape = Shape(
      entry = List(a),
      transitions = List(Transition(a, guard1), Transition(a, guard2))
    )

    val violations = Runner.validate(shape)

    violations should have size 2
    violations(0) should include("'Guarded1'")
    violations(1) should include("'Guarded2'")
  }

  // ---- exponential path enumeration, fixed (issue #38 review finding 4) -----------------------

  it should "stay linear on a chain of diamonds, reporting exactly one violation instead of enumerating every path" in {
    // Before the fix, a depth first walk threading a fresh `visited` set down each path enumerated
    // every one of the 2^20 paths through this shape (over a million violations, a few seconds);
    // the global, state-keyed visited set instead expands `D20` (the one guarded node) exactly once.
    val violations = Runner.validate(diamondChain(20))

    violations should have size 1
  }

  it should "cap a large violation list, in Runner.describeViolations, to the first N plus a summary count" in {
    val a      = plainNode("A")
    val guards = (1 to 25).map(i => plainNode(s"Guarded$i", guard = Guard.RequiresReview)).toList
    val shape  = Shape(entry = List(a), transitions = guards.map(g => Transition(a, g)))

    val violations = Runner.validate(shape)
    violations should have size 25

    val message = Runner.describeViolations(violations)
    message should include("and 5 more")
  }

  // ---- three ways to get zero validation silently, closed (issue #38 review finding 5) --------

  it should "report a violation for transitions declared with no entry, rather than silently agreeing" in {
    val a     = plainNode("A")
    val b     = plainNode("B")
    val shape = Shape(entry = Nil, transitions = List(Transition(a, b)))

    Runner.validate(shape) shouldBe List("transitions declared with no entry: nothing was validated")
  }

  it should "not flag Workflow's own Shape(Nil, Nil) default (no entry, no transitions) as a violation" in {
    Runner.validate(Shape(Nil, Nil)) shouldBe empty
  }

  it should "report an orphan subgraph: a node named in transitions but unreachable from any declared entry" in {
    val a     = plainNode("A")
    val b     = plainNode("B")
    val x     = plainNode("X")
    val y     = plainNode("Y")
    val shape = Shape(entry = List(a), transitions = List(Transition(a, b), Transition(x, y)))

    val violations = Runner.validate(shape)

    violations.count(_.contains("unreachable")) shouldBe 2
    violations.exists(v => v.contains("'X'") && v.contains("unreachable")) shouldBe true
    violations.exists(v => v.contains("'Y'") && v.contains("unreachable")) shouldBe true
  }

  // ---- duplicate node names fail open, closed (issue #38 review finding 6) --------------------

  it should "reject a Shape where two distinct nodes share a name but disagree on cost, timeout, trust or guard" in {
    val a        = plainNode("A")
    val guardedX = plainNode("X", guard = Guard.RequiresReview)
    val openX    = plainNode("X", guard = Guard.Open)
    val shape = Shape(
      entry = List(a),
      transitions = List(Transition(a, guardedX), Transition(a, openX))
    )

    val violations = Runner.validate(shape)

    violations.exists(_.contains("node name 'X'")) shouldBe true
  }

  // ---- the real shipped workflow's declared shape ---------------------------------------------

  "Machine.shippedWorkflow's declared shape" should "validate clean" in {
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(5)

    val violations = withFaulting:
      given caps: Caps = buildCaps(world, clock)
      val faulting     = summon[Faulting]
      val wf           = Machine.shippedWorkflow(Config(), caps, faulting, ledger)
      Runner.validate(wf.shape)

    violations shouldBe Right(Nil)
  }

  it should "declare exactly this edge set, so an edit to the closures not mirrored here fails loudly instead of drifting silently" in {
    // Pins `Machine.shippedShape`'s own declared data as a literal (issue #38 review finding 1c): a
    // `Shape` is hand-declared DATA describing the same graph the `Next.Goto` closures encode
    // (`Kit.scala`'s own doc on `Shape`), and nothing stops the two from drifting apart. This test
    // does not prove they agree, only `ShippedWorkflowSpec`/`ScenarioSpec` walk the real closures,
    // it proves that a future edit to this declaration itself shows up as a failing assertion.
    val shape = Machine.shippedShape(Config())

    shape.entry.map(_.name) shouldBe List("Implement", "Repair")
    shape.transitions.map(t => t.from.name -> t.to.name) shouldBe List(
      "Implement"     -> "Gate",
      "Implement"     -> "Route",
      "Gate"          -> "Repair",
      "Gate"          -> "Route",
      "Repair"        -> "Gate",
      "Repair"        -> "Route",
      "Gate"          -> "Review",
      "Review"        -> "Repair",
      "Review"        -> "Route",
      "Route"         -> "CommitAndPush",
      "Route"         -> "AskHuman",
      "CommitAndPush" -> "OpenPr",
      "OpenPr"        -> "CiWait",
      "CiWait"        -> "Merge",
      "Merge"         -> "PostMergeCleanup"
    )
  }

  it should "reject the rejection paths a truthful copy of this edge set has, if its PR node were guarded" in {
    // issue #38 review finding 1c: proves the validator has teeth on a REALISTIC graph. Mirrors
    // `Machine.shippedShape`'s own truthful edge set (entry, the Implement/Gate/Repair-to-Route
    // rejection edges, the Gate-Review-RouteDecision trust chain), with a stand-in "PR" node
    // carrying `Guard.RequiresReview` exactly where `OpenPr`'s own doc explains the REAL shipped
    // shape deliberately does not carry one (a DATA guarantee, not a reachability one). If `OpenPr`
    // genuinely carried that guard, this is exactly the kind of path `Runner.validate` would (rightly)
    // reject; the real shipped shape stays clean only because it does not declare the guard, not
    // because the validator has no teeth.
    val implement = plainNode("Implement")
    val gate       = plainNode("Gate")
    val repair     = plainNode("Repair")
    val review     = reviewedNode("Review")
    val route      = plainNode("Route")
    val commit     = plainNode("CommitAndPush")
    val pr         = plainNode("PR", guard = Guard.RequiresReview)

    val shape = Shape(
      entry = List(implement, repair),
      transitions = List(
        Transition(implement, gate),
        Transition(implement, route),
        Transition(gate, repair),
        Transition(gate, route),
        Transition(repair, gate),
        Transition(repair, route),
        Transition(gate, review),
        Transition(review, repair),
        Transition(review, route),
        Transition(route, commit),
        Transition(commit, pr)
      )
    )

    val violations = Runner.validate(shape)

    violations should have size 1
    violations.head should include("Implement -> Route -> CommitAndPush -> PR")
    violations.head should include("'PR'")
  }

  // ---- Runner.run validates before a single node ever runs ------------------------------------

  "Runner.run" should "run no node and end the tick as an infra fault when handed an invalid workflow" in {
    var started = false
    var ran     = false
    val guarded = Node[Unit, Unit](
      name = "guarded",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        ran = true
        NodeOutcome.Done(()),
      guard = Guard.RequiresReview
    )
    val invalidShape = Shape(entry = List(guarded), transitions = Nil)
    val wf: Workflow[Unit] = Workflow(
      "invalid",
      start = _ =>
        started = true
        Next.Goto(guarded, (), _ => Next.Finish(LoopExit.Success)),
      shape = invalidShape
    )
    val world = new TestWorld
    val clock = new FakeClock(List(0L))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.run(wf, ())

    result shouldBe Left(LoopExit.InfraFault)
    started shouldBe false
    ran shouldBe false
    // Issue #38 review finding 8/nit: this used to assert only the exit and the two flags above,
    // never the one message an operator watching the log stream actually reads. `Runner.run` and
    // `Machine.runOnce`'s own hoisted check now share one builder, `Runner.invalidShapeMessage`
    // (`Kit.scala`), so this pins that the log line `Runner.run` actually produced is exactly what
    // that shared builder says it should be, not merely that some line, any line, was logged.
    world.logLines should contain(
      Runner.invalidShapeMessage("invalid", Runner.validate(invalidShape))
    )
  }

  // ---- Node.apply's guard combination never discards either fact in favour of the other -------
  // ---- (issue #43 review round 5, FINDING 2) ----------------------------------------------------

  "Node.apply's guard combination" should "never downgrade an explicitly passed Guard.RequiresReview, whether or not the input type also carries the marker" in {
    plainNode("ArgumentOnly", guard = Guard.RequiresReview).guard shouldBe Guard.RequiresReview
    markerNode("MarkerAndArgument", guard = Guard.RequiresReview).guard shouldBe Guard.RequiresReview
  }

  it should "produce Guard.RequiresReview whenever EITHER the marker or the argument says so, matching the OR the scaladoc states" in {
    // The full truth table: `Guard.Open` only when both the marker and the argument agree it need
    // not be, `Guard.RequiresReview` in every other cell, never a case where one fact is silently
    // discarded in favour of the other.
    markerNode("MarkerOnly").guard shouldBe Guard.RequiresReview // marker true, argument default Open
    plainNode("ArgumentOnly", guard = Guard.RequiresReview).guard shouldBe Guard.RequiresReview // marker false, argument true
    markerNode("Both", guard = Guard.RequiresReview).guard shouldBe Guard.RequiresReview // marker true, argument true
    plainNode("Neither").guard shouldBe Guard.Open // marker false, argument default Open
  }

  // ---- the marker half of that combination is no longer a fact a CALL SITE can answer for ----------
  // ---- (issue #26 PR review). It used to be: `Node.apply` read it off a `using g: GuardOf[I]` -------
  // ---- clause, and `GuardOf.open[I]`, the fallback that answers "no marker" for EVERY `I`, was a ----
  // ---- public, nameable given inherited into `object GuardOf`, so passing it explicitly built a -----
  // ---- node stamped `Guard.Open` whose own input type extended `RequiresReviewInput`, leaving -------
  // ---- `Runner.validate` nothing to find and putting it back into exactly the disagreement with -----
  // ---- `KitMacro.checkShapeImpl`'s own structural read that Tier 2 existed to end. The fact now -----
  // ---- comes from `markerRequiresReview[I]` (`Kit.scala`), a macro over the type argument alone, ----
  // ---- inside an `inline def apply`, so there is no argument position left to hand it anything. -----

  it should "no longer offer any GuardOf given for a call site to pass, the exact suppression that used to compile and stamp Guard.Open on a marker input node" in {
    // The reproduction verbatim, kept as source rather than described in prose, so a future editor who
    // reintroduces a `using`-shaped derivation for this fact finds out here rather than in review.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class SuppressedInput() extends RequiresReviewInput
        |
        |val suppressed: Node[SuppressedInput, Unit] = Node[SuppressedInput, Unit](
        |  name = "Suppressed", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)(using summon[TrustOf[Unit]], GuardOf.open[SuppressedInput])
        |""".stripMargin
    )

    errors should not be empty
    errors.map(_.message).mkString("\n") should include("GuardOf")
  }

  it should "leave Node.apply exactly one using argument position, so a call site has nowhere to put a second one whatever it names" in {
    // The narrower half of the test above: even a caller who does not name `GuardOf` at all, and
    // simply hands a second `using` argument of any type whatsoever, is rejected, so this pins the
    // SHAPE of the parameter list rather than the absence of one particular given.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |case class SecondSlotInput() extends RequiresReviewInput
        |
        |val twoArguments: Node[SecondSlotInput, Unit] = Node[SecondSlotInput, Unit](
        |  name = "TwoArguments", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |  probe = _ => None, run = _ => NodeOutcome.Done(())
        |)(using summon[TrustOf[Unit]], summon[TrustOf[Unit]])
        |""".stripMargin
    )

    errors should not be empty
  }

  it should "still stamp Guard.RequiresReview from the marker when the caller writes guard = Guard.Open AND supplies the one using argument Node.apply still takes, by hand" in {
    // The positive half, and the reason it matters: `TrustOf[O]` remains an ordinary `using` parameter
    // a call site can answer for, deliberately (`Node.apply`'s own doc, `Kit.scala`, has the reason
    // that residual is accepted and where the runtime check that catches it lives). This proves that
    // supplying it changes nothing about the marker fact: the caller here writes down the weakest
    // `guard` it can and the most explicit `using` argument it can, and the constructed node is guarded
    // anyway, because nothing in this call reaches the derivation at all.
    val node = Node[MarkerInput, Unit](
      name = "ExplicitlyOpened",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(()),
      guard = Guard.Open
    )(using TrustOf.plain[Unit])

    node.guard shouldBe Guard.RequiresReview
  }

  // ---- KitMacro reads an explicit `guard = Guard.RequiresReview` argument too, not only the ----
  // ---- RequiresReviewInput marker on a node's own input type (issue #43 review round 5, ----
  // ---- FINDING 1): `Guard`'s own scaladoc (`Kit.scala`) calls declaring the guard "the node ----
  // ---- author's own job", so the documented, hand written form now earns a compile time check ----
  // ---- of its own, the same way the marker already did, rather than only the runtime backstop ----
  // ---- `Runner.validate` has always given it. -----------------------------------------------------

  "checkedShape" should "refuse to typecheck a literal Shape reaching an inline node guarded only by its own explicit guard argument, its input type carrying no RequiresReviewInput marker at all, with no reviewer on the path" in {
    // `nodeFacts`/`explicitRequiresReviewGuard` (`KitMacro.scala`) read the `guard` argument straight
    // off the reference's own AST, which only exists at an INLINE `Node(...)` construction, never at a
    // `val` reference to one built elsewhere (`identifyRef`'s own doc, `KitMacro.scala`, has the
    // identical limit for reading a literal `name`); this test, and the two below it, accordingly
    // write every node inline, at the point the shape uses it, matching the idiom
    // `GraphMacroSpec.scala`'s own "inline Node.apply constructions" test already establishes for the
    // marker-only case.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Node[Unit, Unit](
        |      name = "Entry", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |      probe = _ => None, run = _ => NodeOutcome.Done(())
        |    )),
        |    transitions = List(Transition(
        |      Node[Unit, Unit](
        |        name = "Entry", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |        probe = _ => None, run = _ => NodeOutcome.Done(())
        |      ),
        |      Node[Unit, Unit](
        |        name = "Guarded", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |        probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |      )
        |    ))
        |  )
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("Entry -> Guarded")
    messages should include("'Guarded'")
    messages should include("review")
  }

  it should "still refuse to typecheck when the explicit guard argument is written positionally rather than named, as Node.apply's own sixth argument" in {
    // `explicitRequiresReviewGuard` (`KitMacro.scala`) does not test WHICH position or name an argument
    // was written with; it only asks whether ANY main argument names the literal `Guard.RequiresReview`
    // case, which `Node.apply`'s own signature makes unambiguous, since `guard` is the only parameter of
    // type `Guard`. This test is what caught the first version of that function actually shipped
    // (issue #43 review round 5, second pass): it keyed on a fixed sixth POSITIONAL slot guarded by
    // `mainArgs.exists(_.isInstanceOf[NamedArg])`, a check that silently always answered `true`, because
    // `quotes.reflect.NamedArg` is an abstract type this macro cannot `isInstanceOf` against precisely,
    // so the positional branch could never fire and this exact snippet compiled clean with no violation
    // reported, `scala-cli test . --server=false` being the only way that surfaced, a `typeCheckErrors`
    // run alone did not (`explicitRequiresReviewGuard`'s own doc has the confirmed mechanism). A NAMED
    // `guard` written out of `Node.apply`'s own declared parameter order remains a different,
    // pre-existing case this fix does not reach: the compiler's own synthetic-`val` rewrite for
    // out-of-order named arguments hides the whole call from `identifyRef` too, so that reference already
    // falls back to the marker-only test, the same safe direction every other unrecognised shape in this
    // file takes.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Node[Unit, Unit](
        |      name = "Entry", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |      probe = _ => None, run = _ => NodeOutcome.Done(())
        |    )),
        |    transitions = List(Transition(
        |      Node[Unit, Unit](
        |        name = "Entry", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |        probe = _ => None, run = _ => NodeOutcome.Done(())
        |      ),
        |      Node[Unit, Unit](
        |        "Guarded", Cost.NoDispatch, Timeout.Unbounded, _ => None, _ => NodeOutcome.Done(()), Guard.RequiresReview
        |      )
        |    ))
        |  )
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("'Guarded'")
    messages should include("review")
  }

  it should "compile the identical inline shape clean once a reviewer sits on the path before the node guarded only by its explicit argument" in {
    // The positive control for the two tests above: proves the macro is actually reading the explicit
    // `guard` argument as a real fact that participates in the BFS, not merely rejecting every shape
    // that reaches a node carrying one.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |val shape = checkedShape(
        |  Shape(
        |    entry = List(Node[Unit, Unit](
        |      name = "Entry", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |      probe = _ => None, run = _ => NodeOutcome.Done(())
        |    )),
        |    transitions = List(
        |      Transition(
        |        Node[Unit, Unit](
        |          name = "Entry", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |          probe = _ => None, run = _ => NodeOutcome.Done(())
        |        ),
        |        Node[Unit, AgentDispatch.Judged[Unit]](
        |          name = "Reviewer", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |          probe = _ => None,
        |          run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ()))
        |        )
        |      ),
        |      Transition(
        |        Node[Unit, AgentDispatch.Judged[Unit]](
        |          name = "Reviewer", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |          probe = _ => None,
        |          run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ()))
        |        ),
        |        Node[Unit, Unit](
        |          name = "Guarded", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
        |          probe = _ => None, run = _ => NodeOutcome.Done(()), guard = Guard.RequiresReview
        |        )
        |      )
        |    )
        |  )
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }
