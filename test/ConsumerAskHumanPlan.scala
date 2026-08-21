package com.example.consumer

import in.rcard.litterbox._
import in.rcard.litterbox.Caps.given

/** The falsification `AskHuman`'s own scaladoc (issue #68 review) needed and did not have: a real,
  * separately compiled file in a package this library does not own that binds `Machine.AskHuman(cfg)`
  * ONCE to a top level `val` and names that `val`, not the `def` call itself, inside a `Plan` literal
  * handed to `LitterBox.graph`. If the claim under review, that such a `Plan` "does not compile at
  * all", were true, this file would fail to compile and `scala-cli test .` would fail with it. It
  * compiles clean, the same way `test/ConsumerShippedNodes.scala` already proves for `Machine.Gate`:
  * `KitMacro`'s `isStablePathLink` keys a stable `val` read regardless of what expression initialised
  * it, a `def(cfg: Config)` call included, so the one thing a consumer actually cannot do is write
  * `AskHuman(cfg)` INLINE inside the `Plan` literal itself (that allocates a fresh `Node` at the call
  * site, `KitMacro`'s `InlineNodeInPlan` refusal), not name the node at all.
  */
val ProbeAskHumanStart: Node[Unit, Machine.AskHumanInput] = Node(
  name = "ProbeAskHumanStart",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    val cur = Machine.Cursor()
    cur.iter = 1
    cur.issue = "99"
    cur.budget = 0
    NodeOutcome.Done(
      Machine.AskHumanInput(
        cur,
        issue = 99,
        marker = "<!-- litter-box:ask-human probe -->",
        body = "probe body",
        kindText = "probe",
        gateStatus = "n/a"
      )
    )
)

/** Where `ProbeAskHuman`'s reply goes, so the reply type is also nameable and usable from here, not
  * only the node itself.
  */
val ProbeAskHumanExit: Node[Machine.AskHumanReply, Unit] = Node(
  name = "ProbeAskHumanExit",
  cost = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ => NodeOutcome.Done(())
)

/** `Machine.AskHuman(Config())` bound ONCE here, exactly the shape the corrected scaladoc on
  * `Machine.AskHuman` describes: a consumer's own top level `val` standing for the node, so every
  * reference to `ProbeAskHuman` below reads the identical object `Plan.workflowOf`'s reference
  * identity walk needs.
  */
val ProbeAskHuman: Node[Machine.AskHumanInput, Machine.AskHumanReply] = Machine.AskHuman(Config())

/** The `Plan` literal itself. `ProbeAskHuman` is named twice, once as an edge's destination and once
  * as an edge's source, which only type-checks and `checkedPlan`-validates because both reads resolve
  * to the same `val`.
  */
val probeAskHumanPlanGraph: LoopGraph = LitterBox.graph(
  name = "probeAskHumanPlan",
  plan = Plan(
    entry = ProbeAskHumanStart,
    edges = List(
      Edge.To(ProbeAskHumanStart, ProbeAskHuman, (input: Machine.AskHumanInput) => Some(input)),
      Edge.To(ProbeAskHuman, ProbeAskHumanExit, (reply: Machine.AskHumanReply) => Some(reply)),
      Edge.Exit(ProbeAskHumanExit, _ => Some(LoopExit.Success))
    )
  ),
  dispatchBudget = _ => 0,
  startInput = _ => ()
)
