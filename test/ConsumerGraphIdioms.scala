package com.example.consumer

import in.rcard.litterbox._
import in.rcard.litterbox.Caps.given

/** The real, separately compiled POSITIVE half TEST.md's own new rule asks for: `ConsumerGraphSpec`
  * (`test/ConsumerGraphSpec.scala`) already pins, snippet by snippet through
  * `scala.compiletime.testing.typeCheckErrors`, that a foreign package can WRITE down every one of the
  * graph authoring idioms issue #43's review sequence found and fixed (a class member node referenced
  * unqualified, an empty edge list, `Plan`'s named arguments out of declaration order, a local val
  * bound `Edge`, a review node feeding a `Guard.RequiresReview` node, `startInput` calling
  * `fault.raise` off the ambient `Fault`). What none of those snippets can pin, and what this
  * file exists to close, is that the identical idioms survive being genuinely, separately compiled: a
  * snippet handed to `typeCheckErrors` resolves imports and package paths differently from a real top
  * level file, `ConsumerGraphSpec`'s own doc and `ConsumerBoundarySpec`'s own doc both establish that
  * at length, and `ConsumerGraphSpec`'s own trailing comment records two concrete places where a
  * bare reference reached through a wildcard import, and a fully qualified reference to an earlier
  * snippet member, each behave differently inside the synthetic wrapper `typeCheckErrors` compiles its
  * string under than they do in a real file. This file is declared `package com.example.consumer`, a
  * genuinely separate compilation unit, standing in for any package this library does not own, the
  * same convention `ConsumerBoundarySpec` and `ScaffoldedLoopBoundarySpec` already use.
  *
  * Every `LoopGraph` below is built through `LitterBox.graph`, never fabricated by hand, and every one
  * of them is actually WALKED, not merely declared: `test/ConsumerGraphIdiomRunSpec.scala`, a new file
  * living inside `in.rcard.litterbox` itself for the identical reason `test/ConsumerGraphRunSpec.scala`
  * does (`TestWorld` is code belonging to the library, reachable only from within this package, RFC
  * #26 decision 14), imports every graph declared here and drives each one through `Machine.runOnce`
  * against a `TestWorld`, asserting on `world.logLines` that this graph's own nodes, and only this
  * graph's own nodes, actually ran. Compiling this file is the first half of the proof (the idiom is
  * genuinely writable from outside `in.rcard.litterbox`); the run spec is the second half (the graph
  * that idiom produces is genuinely walkable, not merely well typed).
  *
  * `runIdiomGraphAsAConsumerLoopWould` at the very bottom mirrors the RFC #26 sketch itself,
  * `LitterBox.graph(...)` handed straight to `LitterBox.run`, declared but never invoked for the
  * identical reason `ScaffoldedLoopBoundarySpec`'s own `@main def loop` stays uncalled (TEST.md:
  * everything under `test/` stays Docker free, network free and credential free, and `LitterBox.run`
  * both resolves a published coordinate over the network and terminates the JVM). Named differently
  * from that file's own top level `loop`, since both files share `package com.example.consumer` and
  * scala-cli compiles every file under `test/` together.
  */

// ---- idiom 1: a class member node, referenced unqualified inside its own class body (the This --------
// ---- qualified stable path ConsumerGraphSpec's own test 13 only ever proved through a snippet) --------

class ClassMemberGraph:
  val Start: Node[Unit, Unit] = Node(
    name = "IdiomClassMemberStart",
    cost = Cost.NoDispatch,
    timeout = Timeout.Unbounded,
    probe = _ => None,
    run = _ =>
      summon[Caps].logger.log("idiom class member Start ran")
      NodeOutcome.Done(())
  )
  val Finish: Node[Unit, Unit] = Node(
    name = "IdiomClassMemberFinish",
    cost = Cost.NoDispatch,
    timeout = Timeout.Unbounded,
    probe = _ => None,
    run = _ =>
      summon[Caps].logger.log("idiom class member Finish ran")
      NodeOutcome.Done(())
  )
  val graph: LoopGraph = LitterBox.graph(
    name = "idiomClassMember",
    plan = Plan(
      entry = Start,
      edges = List(Edge.To(Start, Finish, _ => Some(())), Edge.Exit(Finish, _ => Some(LoopExit.Success)))
    ),
    dispatchBudget = _ => 0,
    startInput = _ => ()
  )

val classMemberGraph: LoopGraph = ClassMemberGraph().graph

// ---- idiom 2: a lone entry node whose only edge ends the run (ConsumerGraphSpec's own test 14 is -----
// ---- the empty edge list itself, which no graph that actually RUNS can carry: a walk that reaches ----
// ---- a node with no edge left to take is a fault, so the runnable half of that idiom is this one) -----

val loneNode: Node[Unit, Unit] = Node(
  name = "IdiomLoneNode",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom empty transition list lone node ran")
    NodeOutcome.Done(())
)

val listEmptyGraph: LoopGraph = LitterBox.graph(
  name = "idiomListEmpty",
  plan = Plan(entry = loneNode, edges = List(Edge.Exit(loneNode, _ => Some(LoopExit.Success)))),
  dispatchBudget = _ => 0,
  startInput = _ => ()
)

// ---- idiom 3: Plan's named arguments out of declaration order (ConsumerGraphSpec's own test 15) -------

val orderA: Node[Unit, Unit] = Node(
  name = "IdiomOrderA",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom named argument order A ran")
    NodeOutcome.Done(())
)
val orderB: Node[Unit, Unit] = Node(
  name = "IdiomOrderB",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom named argument order B ran")
    NodeOutcome.Done(())
)

val namedArgOrderGraph: LoopGraph = LitterBox.graph(
  name = "idiomNamedArgOrder",
  plan = Plan(
    edges = List(Edge.To(orderA, orderB, _ => Some(())), Edge.Exit(orderB, _ => Some(LoopExit.Success))),
    entry = orderA
  ),
  dispatchBudget = _ => 0,
  startInput = _ => ()
)

// ---- idiom 4: a local val bound Edge, declared inside the same block as the LitterBox.graph -----------
// ---- call that uses it (ConsumerGraphSpec's own test 16) -----------------------------------------------

val localA: Node[Unit, Unit] = Node(
  name = "IdiomLocalA",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom local transition A ran")
    NodeOutcome.Done(())
)
val localB: Node[Unit, Unit] = Node(
  name = "IdiomLocalB",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom local transition B ran")
    NodeOutcome.Done(())
)

def buildLocalTransitionGraph(): LoopGraph =
  val t1 = Edge.To(localA, localB, (_: Unit) => Some(()))
  LitterBox.graph(
    name = "idiomLocalTransition",
    plan = Plan(entry = localA, edges = List(t1, Edge.Exit(localB, _ => Some(LoopExit.Success)))),
    dispatchBudget = _ => 0,
    startInput = _ => ()
  )

val localTransitionGraph: LoopGraph = buildLocalTransitionGraph()

// ---- idiom 5: an object member reached through a wildcard import, referenced bare thereafter (the -----
// ---- positive, self consistent counterpart of the SPLIT reproductions ConsumerGraphSpec's own ---------
// ---- trailing comment documents cannot be pinned as a typeCheckErrors snippet at all: inside that ------
// ---- snippet's own synthetic wrapper a bare reference reached through a wildcard import does not -------
// ---- type as the same Ident shape a real top level file produces. Also exercises dispatchBudget: -------
// ---- Config => Int reading cfg.repairBudget directly, only ever pinned as a snippet before this file ---

object IdiomHolder:
  val WStart: Node[Unit, Unit] = Node(
    name = "IdiomWildcardStart",
    cost = Cost.NoDispatch,
    timeout = Timeout.Unbounded,
    probe = _ => None,
    run = _ =>
      summon[Caps].logger.log("idiom wildcard import Start ran")
      NodeOutcome.Done(())
  )
  val WFinish: Node[Unit, Unit] = Node(
    name = "IdiomWildcardFinish",
    cost = Cost.NoDispatch,
    timeout = Timeout.Unbounded,
    probe = _ => None,
    run = _ =>
      summon[Caps].logger.log("idiom wildcard import Finish ran")
      NodeOutcome.Done(())
  )

import IdiomHolder.*

val wildcardImportGraph: LoopGraph = LitterBox.graph(
  name = "idiomWildcardImport",
  plan = Plan(
    entry = WStart,
    edges = List(Edge.To(WStart, WFinish, _ => Some(())), Edge.Exit(WFinish, _ => Some(LoopExit.Success)))
  ),
  dispatchBudget = (cfg: Config) => cfg.repairBudget,
  startInput = (_: Int) => ()
)

// ---- idiom 6: a fully qualified reference to a top level val declared earlier in this same file --------
// ---- (the positive, self consistent counterpart of the other SPLIT reproduction ConsumerGraphSpec's ----
// ---- own trailing comment documents fails inside a snippet's own synthetic wrapper with an error -------
// ---- about the harness itself, never about this macro, so a snippet could never confirm this idiom -----
// ---- actually compiles against the real public API from a genuinely qualified reference) ----------------

val QStart: Node[Unit, Unit] = Node(
  name = "IdiomQualifiedStart",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom package qualified Start ran")
    NodeOutcome.Done(())
)
val QFinish: Node[Unit, Unit] = Node(
  name = "IdiomQualifiedFinish",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom package qualified Finish ran")
    NodeOutcome.Done(())
)

val packageQualifiedGraph: LoopGraph = LitterBox.graph(
  name = "idiomPackageQualified",
  plan = Plan(
    entry = com.example.consumer.QStart,
    edges = List(
      Edge.To(com.example.consumer.QStart, com.example.consumer.QFinish, _ => Some(())),
      Edge.Exit(com.example.consumer.QFinish, _ => Some(LoopExit.Success))
    )
  ),
  dispatchBudget = _ => 0,
  startInput = _ => ()
)

// ---- idiom 7: a review node feeding a Guard.RequiresReview node, a reviewer on every path into it ------
// ---- (ConsumerGraphSpec's own positive test 4, RFC #26's whole reason this factory forces a mandatory --
// ---- macro check at all). The single most important guarantee in this file, and, before this file, -----
// ---- pinned only as a typeCheckErrors snippet: never actually run through a real reviewer mock, so -----
// ---- nothing before this proved the review this idiom demands actually happens, only that the shape ----
// ---- naming it type checks. -----------------------------------------------------------------------------

case class IdiomPrInput() extends RequiresReviewInput
case class IdiomScore(approved: Boolean)
def parseIdiomScore(o: DispatchOutcome): IdiomScore = IdiomScore(true)

val IdiomPick: Node[Unit, Unit] = Node(
  name = "IdiomReviewedPick",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom reviewed Pick ran")
    NodeOutcome.Done(())
)
val IdiomReview: Node[Unit, AgentDispatch.Judged[IdiomScore]] = Node(
  name = "IdiomReviewedReview",
  // `Cost.OneDispatch` against the graph's own budget of one, since issue #69: this node genuinely
  // dispatches a review, and the runner meters every dispatch at the capability now, so an idiom
  // declaring `Cost.NoDispatch` here would be an idiom that faults rc 50 the first time it runs.
  cost = Cost.OneDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom reviewed Review ran")
    NodeOutcome.Done(summon[AgentDispatch].review("prompt", "reviewFile").map(parseIdiomScore))
)
val IdiomOpenPr: Node[IdiomPrInput, Unit] = Node(
  name = "IdiomReviewedOpenPr",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom reviewed OpenPr ran")
    NodeOutcome.Done(()),
  guard = Guard.RequiresReview
)

val reviewedGraph: LoopGraph = LitterBox.graph(
  name = "idiomReviewed",
  plan = Plan(
    entry = IdiomPick,
    edges = List(
      Edge.To(IdiomPick, IdiomReview, _ => Some(())),
      Edge.To(IdiomReview, IdiomOpenPr, _ => Some(IdiomPrInput())),
      Edge.Exit(IdiomOpenPr, _ => Some(LoopExit.Success))
    )
  ),
  dispatchBudget = _ => 1,
  startInput = _ => ()
)

// ---- idiom 8: startInput calling fault.raise off the ambient Fault, with no Caps in scope at all -------
// ---- (ConsumerGraphSpec's own positive test 3). Run twice by the companion run spec: once with a -------
// ---- tick number that is not negative, where the node runs normally, and once with a negative one, -----
// ---- where the fault genuinely aborts the tick to LoopExit.InfraFault before the node is ever -----------
// ---- reached, proving this idiom is not merely well typed but genuinely wired to the same fault ---------
// ---- channel every other fault in this library routes through. ------------------------------------------

val faultGuardedNode: Node[Unit, Unit] = Node(
  name = "IdiomFaultGuardedNode",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("idiom fault start input node ran")
    NodeOutcome.Done(())
)

val faultStartInputGraph: LoopGraph = LitterBox.graph(
  name = "idiomFaultStartInput",
  plan = Plan(
    entry = faultGuardedNode,
    edges = List(Edge.Exit(faultGuardedNode, _ => Some(LoopExit.Success)))
  ),
  dispatchBudget = _ => 0,
  startInput = (n: Int) =>
    if n < 0 then summon[Fault].raise("idiom negative tick number") else ()
)

// ---- the RFC #26 sketch itself, declared but never invoked (TEST.md: everything under test/ stays ------
// ---- Docker free, network free and credential free; ScaffoldedLoopBoundarySpec's own @main def loop ----
// ---- follows the identical discipline for the identical reason). Named differently from that file's ----
// ---- own top level loop, in the same package, to avoid a duplicate definition once every file under ----
// ---- test/ compiles together. --------------------------------------------------------------------------

def runIdiomGraphAsAConsumerLoopWould(args: Seq[String]): Unit = LitterBox.run(classMemberGraph, args)
