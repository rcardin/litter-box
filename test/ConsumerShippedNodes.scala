package com.example.consumer

import in.rcard.litterbox._
import in.rcard.litterbox.Caps.given

/** The real, separately compiled proof obligation issue #68 states: a graph written in a package this
  * library does not own that composes a node of the SHIPPED pipeline rather than only nodes of its
  * own. Declared `package com.example.consumer`, the same convention `ConsumerGraphIdioms`,
  * `ConsumerBoundarySpec` and `ScaffoldedLoopBoundarySpec` already follow, and compiled by
  * `scala-cli test .` like any other file under `test/`.
  *
  * A separately compiled file rather than a `scala.compiletime.testing.typeCheckErrors` snippet, for
  * the reason TEST.md records and `ConsumerBoundarySpec`'s own doc restates at length: a snippet
  * resolves imports and package paths differently from a real top level file, and rounds 3 and 4 of
  * issue #43's review each found a BLOCKER a snippet only pass had missed. Nothing about "a shipped
  * node composes from outside this package" is provable by a snippet, because the thing being proved
  * is exactly what a real compilation unit sees.
  *
  * Compiling this file is only half the proof. `test/ConsumerShippedNodeRunSpec.scala` is the other
  * half: it lives inside `in.rcard.litterbox` (`TestWorld` belongs to the library, RFC #26 decision
  * 14), imports the graph declared here rather than rebuilding it, and drives it through
  * `Machine.runOnce` so that `Machine.Gate`'s OWN body genuinely runs, observed through the gate
  * capability call and the log line `runFastGate` emits, never merely through this graph type
  * checking.
  */

/** Feeds `Machine.Gate` its input. A consumer's own node is the only thing that can build a
  * `Machine.GateInput`, which is exactly the point: the shipped node is composable, its input is
  * nameable, and the `Machine.Cursor` inside it is this graph's own to populate (`Machine.Gate`'s own
  * scaladoc has what a consumer owes `watch.sh` by filling those four fields honestly).
  */
val ConsumerGateStart: Node[Unit, Machine.GateInput] = Node(
  name = "ConsumerGateStart",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    val cur = Machine.Cursor()
    cur.iter = 1
    cur.issue = "77"
    cur.budget = 0
    summon[Caps].logger.log("consumer shipped gate Start ran")
    NodeOutcome.Done(Machine.GateInput(cur, issue = 77, pass = 1))
)

/** Where a GREEN verdict goes in this graph, which is nothing like where it goes in the shipped one:
  * the point of composing a shipped node is that the ROUTING around it is the consumer's.
  */
val ConsumerGateGreen: Node[Unit, Unit] = Node(
  name = "ConsumerGateGreen",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("consumer shipped gate Green ran")
    NodeOutcome.Done(())
)

/** Where a RED verdict goes. Takes the gate log path `Machine.GateVerdict.Red` carries, so this file
  * also pins that the output type's own payload is readable from a foreign package, not only its
  * cases.
  */
val ConsumerGateRed: Node[String, Unit] = Node(
  name = "ConsumerGateRed",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = gateLog =>
    summon[Caps].logger.log(s"consumer shipped gate Red ran with $gateLog")
    NodeOutcome.Done(())
)

/** `Machine.Gate` is written three times in this ONE `Plan` literal, once as an edge's destination and
  * twice as an edge's source, deliberately: `Plan.workflowOf` links an edge to the node it leaves by
  * reference identity (`Edge.source(e) eq from`), so a node that could not be named by a single stable
  * path would be split into three objects here and the walk would dead end at the first one. That is
  * the whole reason `Machine.Gate` is a `val` rather than the `def(cfg: Config)` factory every other
  * shipped node is (its own scaladoc has the decision).
  */
val shippedGateGraph: LoopGraph = LitterBox.graph(
  name = "consumerShippedGate",
  plan = Plan(
    entry = ConsumerGateStart,
    edges = List(
      Edge.To(ConsumerGateStart, Machine.Gate, (input: Machine.GateInput) => Some(input)),
      Edge.To(
        Machine.Gate,
        ConsumerGateRed,
        {
          case Machine.GateVerdict.Red(gateLog) => Some(gateLog)
          case Machine.GateVerdict.Green        => None
        }
      ),
      Edge.To(
        Machine.Gate,
        ConsumerGateGreen,
        {
          case Machine.GateVerdict.Green => Some(())
          case Machine.GateVerdict.Red(_) => None
        }
      ),
      Edge.Exit(ConsumerGateRed, _ => Some(LoopExit.NeedsHuman)),
      Edge.Exit(ConsumerGateGreen, _ => Some(LoopExit.Success))
    )
  ),
  dispatchBudget = _ => 0,
  startInput = _ => ()
)
