package in.rcard.litterbox

import scala.annotation.tailrec
import scala.util.boundary

// The graph kit (issue #32): `Node`, `Workflow` and the `Runner` that executes one. `Machine.Pick`
// was the first node built on this; `Machine.Implement` (issue #33) was the second, and the first to
// carry a real dispatch and a real `Timeout.After` through it. `Machine.Gate` and `Machine.Repair`
// (issue #34) were the third and fourth. The former FAST-gate-run/self-repair `while` loop had, by
// then, become a recursion (`Machine.implementAndRepair`'s own `runCycle`) whose retry edge, gate-RED
// or a REQUEST_CHANGES verdict into another `Repair` round, then back into another `Gate` cycle, was
// chosen by what `Gate`/`Repair`'s own OUTPUT said, the same shape `Next.Goto`'s own `andThen`
// describes. It was not literally built from `Next`/`Workflow` values, though: `runCycle`'s own doc
// had the concrete, provable reason (`Next.Finish` is fixed to `LoopExit`, RFC #26 decision 10, and
// that cycle's own terminal values, a domain `Outcome` plus the rest of `Ready`'s fields, had no
// injection into that closed set at that point in `iterate`; `terminal`, untouched by that issue, was
// what turned an `Outcome` into a real `LoopExit` later). The mutable `budget` counter the old loop
// owned was gone by then; the shared `Runner.Ledger` was the only thing either the retry decision or
// the `self-repair: budget now N` log line read (`Machine.attemptRepair`'s own doc). A `pass` var
// still survived inside `implementAndRepair`, but only as a mirror the recursion wrote so `Ready`'s
// own `pass` field could report which cycle was last reached; it decided nothing, the recursion's own
// `p` argument did, and the var itself had exactly one read left in the whole method, the final
// `Ready(pass, ...)` construction (the resume dispatch that used to read it instead passed the
// literal `0` it was provably still holding at that point, `implementAndRepair`'s own doc on that
// call site). `Machine.Review` (issue #35) was the fifth node, the cold-reviewer dispatch; unlike
// `Gate`/`Repair`, its own call site at the time (`implementAndRepair`'s `runCycle`) handed it a
// fresh, dedicated `Runner.Ledger(1)` rather than the shared FIX/IMPL one, for the reason that node's
// own doc gave. `Machine.RouteDecision`, `Machine.CommitAndPush`, `Machine.OpenPr`, `Machine.CiWait`,
// `Machine.Merge` and `Machine.PostMergeCleanup` (issue #36) were the sixth through eleventh, the
// terminal phase's own PR-open and auto-merge chain. At the point issue #36 landed, `Machine.terminal`
// still stayed a plain function dispatching each of these through `Runner.step` in turn rather than
// becoming a graph edge itself, for a reason that held only until `RouteDecision`'s own OUTPUT, a
// `Route`, had somewhere real to route to: `terminal`'s own early returns (the guard checks,
// `Route.Parked`'s dedicated handling, the NeedsHuman notify) were not yet values any node's OUTPUT
// could route through.
//
// Issue #37 is where that stops being true. `Machine.implementAndRepair` and `Machine.terminal` are
// both gone, folded into `Machine.shippedWorkflow`, one `Workflow[Machine.ShippedStart]` value built
// from this file's own public shapes (`Node`, `Next.Goto`, `Next.Finish`, `Workflow`) and
// walked by `Runner.run`. The former hand-recursion (`runCycle`, described above) becomes a plain
// function, `cycle`, that builds one real `Next.Goto(Gate(cfg), ...)` value and returns; the
// repetition a `while` loop, then a recursion, used to own now happens because `Runner.run`'s own
// `@tailrec` walk calls back into whichever `andThen` closure that value carries, which may itself
// call `cycle` again. `RouteDecision` through `PostMergeCleanup` fold into the SAME graph, ending in a
// literal `Next.Finish(exit)`; the four non-node segments `terminal` used to own (the `EmptyFix`
// marker, the `Route.Parked` block, the `NeedsHuman` notify, the PR body render) live inside the
// `Next.Goto` closures between the edges they always sat next to, hand-written glue in exactly the
// place a graph has for it. `Machine.Review` is the one node this issue deliberately leaves OUTSIDE
// the graph proper: it stays a direct `Runner.step` call inside one closure, against its own fresh
// `Runner.Ledger(1)`, never a `Next.Goto` edge, because a `Next.Goto` edge has no way to say "run this
// one node against a different `Ledger` than the rest of the walk" (`Runner.run`'s own `using Ledger`
// is fixed once, before the walk begins). `Machine.shippedWorkflow`'s own doc has the full reasoning.
//
// Two of the concerns a hand-written phase like `pickAndSetup` used to own for itself, spending
// dispatch budget and enforcing a wall-clock timeout, move here, onto the `Runner`, so that no
// future node author can forget either. A node's own signature (`probe`/`run` below) can still read
// the wall clock through `Caps` (every capability, including `Clock`, is reachable from there), but
// it has no way to reach a `Ledger`: that type is not a `Caps` member, so nothing a node's own code
// names can spend budget for itself. Enforcing the timeout is the `Runner`'s job precisely because a
// node CAN see the clock; seeing it is not the same privilege as deciding what an overrun means.
//
// This is not the whole of what `pickAndSetup` did for itself, though: `Caps.gates`/`Caps.hostGates`
// still run real subprocesses, and `Caps.git`/`Caps.gh` still mutate the world, entirely uncharged
// and unbounded by anything here. That is not a scope violation: this header must not claim the
// `Runner` covers more than dispatch budget and wall-clock time.
//
// Issue #38 is decision 16's cheap half. `Node` gains `trust` (derived from its own output type by
// `TrustOf`, never declared by hand: `AgentDispatch.Judged` in, `Trust.Reviewed` out, anything else
// `Trust.Plain`) and `guard` (declared by the node author, since "publishes outward" is a fact about
// what a node's `run` body DOES, not something its type can carry). `Workflow` gains a `shape`, a
// hand-written `Shape` describing the SAME graph its `Next.Goto` closures already encode, because
// those closures cannot be walked without running them (`O` is erased, `andThen` is opaque, and in
// `Machine.shippedWorkflow` running them performs real git/GitHub side effects). `Runner.validate`
// walks a `Shape` alone, pure, and `Runner.run` calls it before `wf.start` ever runs, so a graph with
// a path into a guarded node that never crosses a reviewed one is rejected before any node executes.
// The `Shape`/closure split is an honest gap, not an oversight: nothing here stops the two from
// drifting apart, and issue #39's compile time macro, reading the literal graph instead of a
// hand-declared one, is the intended closer of that gap, still out of scope for this file.

/** The infra-fault short-circuit channel, formerly a private alias inside `Machine` (`Machine.scala`
  * used to declare `private type Faulting = boundary.Label[LoopExit]` at the top of the file). Moved
  * here so the rest of this file's declarations can sit next to it, and named `private[litterbox]`
  * rather than left `private`, because `Runner` (this file) and `Machine` both need to name it, and
  * both live in the same package.
  *
  * The `private[litterbox]` narrowing is naming hygiene, not a gate: `Faulting` is a transparent type
  * alias, so it hides the NAME from a foreign package, not the underlying type, and any parameter
  * typed `Faulting` is satisfied structurally by a `boundary.Label[LoopExit]` the caller never had to
  * name. `boundary.Label` being final happens to make one such label hard to come by today, which is
  * luck rather than design and is not what the invariant rests on. What rests it is
  * `Node`'s own signature (see `Fault` below, and `Runner.Ledger`'s `private[litterbox]` constructor)
  * that is the real gate against `boundary.break` with ANY `LoopExit`, which is what RFC #26 decision
  * 3 forbids a node from doing: `Node`'s `probe`/`run` never take a bare `Faulting` at all, only the
  * narrower `Fault`.
  *
  * `boundary.Label[LoopExit]` is the capability to abandon the current iteration and hand
  * `LoopExit.InfraFault` straight to `Machine.runOnce`'s boundary; that boundary is still established
  * exactly where it always was (`runOnce` itself, transitively wrapping `Pick`'s own throwaway-ledger
  * dispatch and the whole `shippedWorkflow` walk `Runner.run` performs, including the `Runner`), so
  * nothing about WHERE a fault lands moves with this file.
  */
private[litterbox] type Faulting = boundary.Label[LoopExit]

/** The only fault channel a `Node`'s `probe`/`run` ever receives, replacing a bare `Faulting` in that
  * position (issue #32 review finding 1). A node holding the raw `Faulting` label could call
  * `boundary.break` with whatever `LoopExit` it liked, including `Success`, and skip the log line,
  * the notify and the `Runner`'s own ledger/timeout accounting entirely, since none of those run
  * after a `break`. `Fault` offers exactly one operation, `raise`, and that operation always goes
  * through `Machine.infraFault`, the same channel and the same log/notify behaviour every other fault
  * in this loop already uses, so the only terminal a node can ever produce through this channel is an
  * infra fault, never a forged one.
  *
  * `logger`/`notify` are captured here, at construction, by `Runner.step`, rather than taken as a
  * `using` clause on `raise` itself (issue #32 review round 2 finding 1): a `using` clause on `raise`
  * resolves at the CALL site, inside a node's own body, so a node could shadow `Log`/`Notify` with
  * local no-op givens and raise with neither firing, defeating the very guarantee this type exists
  * for. Capturing them where the `Runner` builds the `Fault` means the sinks a fault actually uses
  * are the ones the run is really wired to, never whatever a node's own scope happens to shadow them
  * with.
  *
  * `label` is `private[litterbox]`, not exposed on the public interface, for the same reason: the raw
  * label is how `Machine.Pick`'s adapter recovers a `Faulting` to hand to the untouched
  * `pickAndSetup` (both live in the same file, same package, and `pickAndSetup` already reaches
  * `LoopExit.InfraFault` only through `Machine.infraFault` itself, never a bare `break`), not a route
  * a node's own body is meant to take. `Machine.Implement`'s adapter does the same, for the same
  * reason, to hand a recovered `Faulting` to `dispatchInitialImplement`.
  */
final class Fault private[litterbox] (
    private[litterbox] val label: Faulting,
    logger: Log,
    notify: Notify
):

  /** Abandons the current node with an infra fault, reusing `Machine.infraFault`'s wording and
    * ordering (log line, then notify, then abandon) so a fault raised from inside a node reads
    * identically, in the golden log stream, to every other fault this loop can produce. No second
    * fault path exists to invent a different order or a different message by accident. Takes no
    * `using` clause: `logger`/`notify` are already fixed at construction, so there is nothing left
    * for a caller's own scope to override.
    */
  def raise(reason: String): Nothing =
    Machine.infraFault(reason)(using logger, notify)(using label)

/** What running one `Node` concluded. Named `NodeOutcome`, not the RFC sketch's `Outcome`, because
  * `Outcome` already names an unrelated, unexported enum private to `Machine`
  * (`Success`/`Fail` for one US's implement-and-repair result), and reusing that name here for a
  * completely different concept in the same package would make every future reader re-check which
  * `Outcome` a given signature means. `Machine.Outcome` is left exactly as it is.
  */
enum NodeOutcome[+O]:
  /** The node ran (or was skipped by its own `probe`) and produced `value`; the workflow continues
    * to whatever the caller's `andThen` does with it.
    */
  case Done(value: O)

  /** A terminal reached early: the workflow finishes with `exit` and no later node runs. Never
    * `LoopExit.InfraFault`: an infra fault goes through `infraFault` (`Machine.infraFault`), which
    * logs the fault line and fires the rc-50 notify seam AT THE POINT of the fault, then abandons
    * the iteration via `Fault.raise`; it never returns a value for this case to carry. Routing a
    * fault back as an ordinary `Stopped` value instead would move both the log line and the notify
    * away from the point they actually happened, which is exactly what the golden log contract
    * forbids.
    */
  case Stopped(exit: LoopExit)

/** How much of the run's shared dispatch budget one `Node` execution is declared to cost. Named
  * `Cost`, and its free case named `NoDispatch` rather than the RFC sketch's `None`: `None` collides
  * with `scala.Option.None`, and a node author importing both would either get a shadowing surprise
  * or have to qualify one of them at every use site.
  *
  * A `Cost` value is a CEILING the `Runner` checks affordability against before letting a node run
  * at all (`Ledger.canAfford`), never the thing that actually spends anything: the real charge
  * happens per real dispatch, through the decorator `Runner.step` wraps `Caps.agents` in
  * (`Ledger.chargeDispatch`), because a `Cost` declared here is only ever as honest as the node
  * author who wrote it, while a dispatch call is a fact the `Runner` can observe directly (issue #32
  * review finding 2). Declaring the wrong `Cost` therefore cannot let a node spend for free; it can
  * only get the node blocked from starting when it need not have been, or let it start when the
  * ledger will in fact run out partway through its own body.
  */
enum Cost:
  /** Never blocks on an exhausted ledger: `Ledger.canAfford` answers `true` unconditionally for this
    * case. This is a claim about whether the node is allowed to START, not a guarantee about what
    * its body does; a node that dispatches anyway despite declaring `NoDispatch` still has that
    * dispatch charged for real by the decorator, it is simply never prevented from beginning.
    */
  case NoDispatch

  /** Blocks the node from starting at all once the ledger has nothing left (`Ledger.canAfford`).
    * Declaring this does not itself spend anything; a node that never actually dispatches, despite
    * declaring it might, is never charged.
    */
  case OneDispatch

/** How long one `Node` execution is allowed to run before the `Runner` treats it as an infra fault.
  * `seconds`, matching the unit `Config`'s own timeouts already use (`gateTimeout`, `iterTimeout`,
  * `ciWaitTimeout`, ...), so a node's `timeout` reads on the same scale as everything else in
  * `Config` a reader already knows.
  */
enum Timeout:
  /** No check at all: `Runner.step` still records a start time (so the field cannot be forgotten
    * later), but never compares it against anything.
    */
  case Unbounded

  /** Faulted if the node's `probe`-then-`run` together take longer than `seconds` wall-clock,
    * measured by the `Runner` (see `Runner.step` for why the check is post hoc rather than
    * pre-emptive, and why the window starts before `probe`, not before `run`).
    */
  case After(seconds: Int)

/** Whether a `Node`'s own OUTPUT type carries an `AgentDispatch.Judged` (issue #38, RFC #26
  * decision 16's cheap half). This is the fact `Runner.validate` (below) needs to tell a node that
  * proves a cold review happened apart from one that does not, and it is stamped onto every `Node`
  * by `Node.apply`'s own `given TrustOf[O]`, never written down by the node author: see `TrustOf`'s
  * own doc for why deriving it from `O` closes off HAND mislabelling, and `Runner.step`'s own doc
  * for the runtime check that closes the gap the derivation alone cannot (issue #38 review, BLOCKER
  * 2): `NodeOutcome` is covariant in its output, so `NodeOutcome.Stopped(exit)`, typed
  * `NodeOutcome[Nothing]`, conforms to `NodeOutcome[AgentDispatch.Judged[A]]` for any `A`, and a node
  * whose `run` only ever returns `Stopped`, or only ever throws, or only ever calls `fault.raise`,
  * never calling `AgentDispatch.review` at all, still gets `Trust.Reviewed` stamped on it by this
  * very derivation, because the derivation looks at the DECLARED type, not at what the body actually
  * does. `TrustOf` cannot see through that; only a runtime observation of an actual returned value
  * can, and only for the executions that reach one.
  */
enum Trust:
  case Plain
  case Reviewed

/** Derives a `Trust` from a `Node`'s own output type `O`, so `Node.apply` (below) can stamp the
  * right one without a node author ever naming it. `judged` fires whenever `O` unifies with
  * `AgentDispatch.Judged[A]` for some `A`; `LowPriorityTrustOf.plain` answers every other `O`,
  * kept on a supertype so Scala's own priority rule (a given declared directly on `TrustOf`'s
  * companion always outranks one only inherited from a parent trait) picks `judged` first whenever
  * both would otherwise apply, rather than leaving the two genuinely ambiguous. There is no runtime
  * inspection of a value anywhere in this derivation, only which of these two givens the compiler
  * resolves for one particular `O`, decided once, at the call site that builds the `Node`.
  *
  * `judged` fires only when `O` IS `AgentDispatch.Judged[A]` exactly, never when `O` merely
  * CONTAINS one: `Option[Judged[A]]`, `(Judged[A], B)` and `Either[E, Judged[A]]` all fail to unify
  * with `AgentDispatch.Judged[A]` and so all resolve to `Trust.Plain` through `LowPriorityTrustOf`.
  * A consumer node whose reviewer legitimately returns, say, `(Judged[Verdict], String)` gets
  * `Trust.Plain`, not `Trust.Reviewed`, and a graph that needs it to clear a downstream
  * `Guard.RequiresReview` is rejected at startup with a message about a missing reviewer, even
  * though a review genuinely happened. State that plainly rather than let it surprise a consumer:
  * the type has to BE a `Judged`, not merely carry one somewhere inside it.
  */
sealed trait TrustOf[O]:
  def trust: Trust

object TrustOf extends LowPriorityTrustOf:
  given judged[A]: TrustOf[AgentDispatch.Judged[A]] with
    def trust: Trust = Trust.Reviewed

/** `sealed`, not merely public and open (issue #38 review): a consumer that could
  * `extends LowPriorityTrustOf` would bring `plain` into a scope where Scala's own priority rule
  * (this trait's own doc, above) no longer applies, since that rule only ranks `TrustOf`'s own
  * companion above ITS parent, not above a second, unrelated mixin a consumer wrote: a name clash
  * or an import order accident could then let `plain` outrank `judged` for a real
  * `AgentDispatch.Judged` output, silently downgrading a genuine reviewer node to `Trust.Plain` and
  * hiding it from `Runner.validate` entirely. `sealed` closes that door; nothing outside this file
  * needs to extend this trait; `object TrustOf`'s own `extends LowPriorityTrustOf` still works,
  * since `sealed` only forbids extension from OUTSIDE the file it is declared in, not within it.
  */
sealed trait LowPriorityTrustOf:
  given plain[O]: TrustOf[O] with
    def trust: Trust = Trust.Plain

/** Whether a `Node` publishes outward in a way that must never happen without a review crossing its
  * path first (issue #38, RFC #26 decision 16). Unlike `Trust` above, this is NOT derivable from a
  * type: "opens a PR" or "merges to main" is a fact about what a node's own `run` body DOES, not a
  * shape `O` carries, so declaring it is the node author's own job, the same way `cost`/`timeout`
  * already are. State that limitation plainly rather than let it hide: a future node that calls
  * `gh.createPr` or `gh.merge` without also writing `guard = Guard.RequiresReview` is invisible to
  * `Runner.validate`, exactly the "drift between the declared shape and the closures" this issue's
  * own design accepts as the price of the cheap half. Issue #39's compile time macro, extracting
  * the literal graph from the real `Next` edges instead of a hand-declared `Shape`, is what would
  * close this specific gap; this issue only narrows it.
  *
  * There is a second, sharper limit `guard` alone cannot express (issue #38 review, BLOCKER 1),
  * proven by `Machine.shippedWorkflow` itself: `Merge` never runs unreviewed, but that guarantee is
  * a DATA dependency, not a reachability property. `Merge` is reached only on
  * `Route.AutoMergeCandidate`, produced only from `Outcome.Success`, set only after `Review`'s own
  * `Verdict.Approve`, but `Transition` carries no branch discriminator, so an edge walk cannot see
  * which VALUE of a node's output chose a given edge, only that some edge from that node exists.
  * Every declared path into `Merge` runs through `RouteDecision`/`CommitAndPush`/`OpenPr`, and the
  * rejection paths (an unreviewed patch, or a repair round that never crosses `Review`) legitimately
  * reach those same nodes too, on their way to a `Route.NeedsHuman` audit-trail PR. A `guard` on
  * `OpenPr` or `Merge` in THIS shape would therefore have to reject the shipped graph itself: the
  * cheap half can state "a `Trust.Reviewed` node crosses every path", but it has no way to state "a
  * `Trust.Reviewed` node crosses every path THAT REACHES THIS NODE VIA THAT PARTICULAR OUTPUT VALUE".
  * That is genuinely out of reach for a `Shape`/reachability check, not merely undeclared: declaring
  * a guard reachability cannot honestly discharge is what produced a shape curated to pass its own
  * validator in the first place. So `Guard.RequiresReview` stays a real, tested facility for a
  * consumer graph whose PR or merge node genuinely sits behind nothing but a reviewer (no
  * legitimate unreviewed edge into it at all); `Machine.shippedWorkflow` itself does not use it,
  * precisely because its own `Merge`/`OpenPr` do not meet that description. Issue #39's compile
  * time macro is what would have to close this, too, by reading the actual branch a `Next.Goto`'s
  * `andThen` took rather than a hand-declared edge list blind to it.
  */
enum Guard:
  case Open
  case RequiresReview

/** One step of a graph: a name for logs/errors, its budget `cost` and wall-clock `timeout` (both
  * enforced by the `Runner`, never by the node itself), a `probe` that answers from the outside
  * world whether this step's work is already done, and the `run` that does the work when `probe`
  * says no.
  *
  * `probe`/`run` are typed `I => (Caps, Fault) ?=> O-shaped`, not `I => Caps ?=> Fault ?=> O-shaped`
  * split across `using` clauses on a method: a `Node` is a VALUE (so it can be held in a `Workflow`
  * and passed to the `Runner` as data), and a context function is the value-level equivalent of a
  * `using` clause. `Fault`, not the raw `Faulting`, is what travels alongside `Caps` here (see
  * `Fault`'s own doc for why raw `Faulting` never appears in this signature at all).
  *
  * Neither field can reach a `Ledger`: that type does not appear anywhere in this signature, and
  * `Caps` (`src/Caps.scala`) has no `Ledger` member either. A node that wanted to check its own
  * remaining budget would have nothing in scope to ask: the guarantee is structural, not a runtime
  * check (see `RunnerSpec`'s own note on this). What a node CAN do is dispatch through
  * `Caps.agents`, and every such call is charged for real the moment it happens, regardless of what
  * `cost` claims (`Runner.step`).
  *
  * `trust` and `guard` (issue #38) are what `Runner.validate` reads a `Shape`'s own nodes back
  * through. The primary constructor is `private`, confirmed against Scala 3.8.3 itself (not merely
  * assumed) to also privatize the case class's own synthetic `apply` AND `copy`: a foreign call
  * site attempting either fails to compile, and the two failures read differently, because they are
  * two different members. `new Node(...)` calls the CONSTRUCTOR directly, and the compiler reports
  * that access site by name: "constructor Node cannot be accessed as a member of ... from class ...",
  * "private constructor Node can only be accessed from class Node in package in.rcard.litterbox"
  * (verified against Scala 3.8.3, `GraphValidationSpec`'s own test). `n.copy(...)` similarly reports
  * "method copy cannot be accessed as a member of ..." / "private method copy can only be accessed
  * from class Node in package in.rcard.litterbox". Neither reads "object Node does not take
  * parameters", the message a bare `Node(...)` call would get if NO `apply` resolved at all; that is
  * not this guarantee, since `Node.apply` in the companion object below always resolves for an
  * ordinary call. That same `Node.apply` is the only way in, and it takes `trust` from nowhere a
  * caller can name: `t.trust`, read off the
  * `given TrustOf[O]` its own `using` clause resolves, so a node author cannot write the wrong
  * `Trust` into it BY HAND, the same way `copy` cannot be used to swap it either afterward. That
  * closes off hand mislabelling, not mislabelling as such: `NodeOutcome`'s own covariance still lets
  * a `Node` whose DECLARED output type is `AgentDispatch.Judged[A]` earn `Trust.Reviewed` from this
  * exact derivation while never once calling `AgentDispatch.review` (`Trust`'s own doc has the
  * mechanism; `Runner.step`'s own doc has the runtime check that catches it for every execution that
  * reaches a `Done`). In a codebase whose thesis is distrust, claiming more than that here would be
  * a false structural guarantee, worse than none at all. `guard` stays an ordinary, defaulted
  * parameter on that same `apply`, because (see `Guard`'s own doc) it is not derivable from `O` at
  * all.
  */
final case class Node[I, O] private (
    name: String,
    cost: Cost,
    timeout: Timeout,
    probe: I => (Caps, Fault) ?=> Option[O],
    run: I => (Caps, Fault) ?=> NodeOutcome[O],
    trust: Trust,
    guard: Guard
)

object Node:
  def apply[I, O](
      name: String,
      cost: Cost,
      timeout: Timeout,
      probe: I => (Caps, Fault) ?=> Option[O],
      run: I => (Caps, Fault) ?=> NodeOutcome[O],
      guard: Guard = Guard.Open
  )(using t: TrustOf[O]): Node[I, O] =
    new Node(name, cost, timeout, probe, run, t.trust, guard)

/** One edge of a `Workflow`'s graph, chosen by the PREVIOUS node's output (or, for the first node,
  * by the workflow's own input): either the run finishes here, or execution goes to another `Node`
  * and `andThen` says what to do with ITS output once the `Runner` has it.
  *
  * Not itself generic in `I`/`O`: `Workflow[I]`'s own `start` only ever needs to produce a `Next`,
  * regardless of which node's input/output types that particular edge happens to close over, so
  * `Goto` carries its own `[I, O]` pair rather than infecting `Next`, and transitively every
  * `Workflow`, with a second type parameter that would otherwise have to widen every time the graph
  * grows a node of a different shape.
  */
enum Next:
  /** Terminal: the run cannot productively continue past `exit`, so there is nothing left for any
    * later `Next` in the graph to receive control over.
    */
  case Finish(exit: LoopExit)

  /** `andThen` is a function of the node's own output, not a fixed next `Next` chosen ahead of time:
    * which issue got picked, which verdict a review gave, decides where the graph goes next, and
    * that answer does not exist until `node` has actually run.
    */
  case Goto[I, O](node: Node[I, O], input: I, andThen: O => Next) extends Next

/** One declared edge of a `Workflow`'s own `Shape` (issue #38): from one `Node` to another,
  * existentially typed over each node's `[I, O]` pair for the same reason `Next.Goto` is (that
  * enum case's own doc): a `Shape` has to hold every node the graph reaches, regardless of how many
  * distinct input/output shapes they carry, without inventing a common supertype for all of them.
  */
final case class Transition(from: Node[?, ?], to: Node[?, ?])

/** The declared shape of a `Workflow`'s graph (issue #38, RFC #26 decision 16's cheap half): which
  * nodes a walk can begin at (`entry`), and which node can follow which (`transitions`). This is
  * DATA a workflow author writes by hand, describing the SAME graph the `Next.Goto` closures
  * already encode, never derived from them: `Next.Goto`'s own `andThen: O => Next` is an opaque
  * closure over an erased `O` (`Next`'s own doc), so nothing can walk it without running it, and
  * running it for real, in `Machine.shippedWorkflow`, fires real git/GitHub side effects. `Shape`
  * exists so `Runner.validate` (below) has something to walk that is not those closures.
  *
  * Hand declaring this alongside the closures it describes is the acknowledged weakness of this
  * issue's cheap half: nothing here stops a `Shape` from drifting out of sync with what its own
  * closures actually do, the same way a stale comment can drift from the code beside it. Issue
  * #39's compile time macro, extracting the literal graph straight from the real `Next` values, is
  * what removes that gap; this issue only narrows it, by giving the drift somewhere concrete to be
  * caught. A wrong `Shape` still lets a bad graph pass validation, but a right one genuinely rejects
  * one, which is strictly more than no check at all.
  *
  * A second limit sits beside that drift risk, not caused by it: a `Transition` names two `Node`s,
  * nothing about which VALUE of the `from` node's output chose that edge (`Transition`'s own doc).
  * A guarantee like "`Merge` never runs unreviewed" can be true of a real graph for a DATA reason
  * (only one `Route` value ever reaches it, and that value is only ever produced after a real
  * review) while being false of the same graph's reachability shape (some OTHER edge out of an
  * upstream node reaches the same downstream nodes `Merge` sits behind, without a reviewer). A
  * `Shape` walk cannot tell those two cases apart; it only sees that an edge exists, never which
  * output value it was chosen for. `Machine.shippedWorkflow`'s own doc on its `Merge`/`OpenPr` nodes
  * is the concrete case this limit is not hypothetical for. Issue #39's macro would still have to
  * read the actual branch a running `andThen` took to close this, the same way it closes the drift
  * risk above; naming the limit here is what stops it being silently rediscovered as a fresh bug.
  */
final case class Shape(entry: List[Node[?, ?]], transitions: List[Transition])

/** A named graph: `start` computes the first `Next` from the workflow's own input. The user (whoever
  * builds a `Workflow` value) owns every transition `start`/`andThen` describe; only the `Runner`
  * ever actually walks them, so budget/timeout accounting stays in exactly one place regardless of
  * how many nodes a graph grows to.
  *
  * The result type is fixed to `LoopExit` (RFC #26 decision 10: terminal outcomes are a closed set),
  * not a type parameter on `Workflow` itself: a free result type would infect every shape that
  * touches a `Workflow` (`Next`, `Runner.run`'s own signature, every node's `andThen`) for a
  * degree of freedom nothing in this codebase uses, since every real terminal already IS a
  * `LoopExit`.
  *
  * `shape` (issue #38) defaults to `Shape(Nil, Nil)`, an empty declaration that `Runner.validate`
  * reads as trivially valid (no declared entry means no path to walk, so no violation can ever be
  * found): every `Workflow` value `RunnerSpec` builds with fake nodes predates this field and has
  * nothing to say about review reachability, so the default is what keeps every one of those tests
  * compiling and passing unchanged rather than forcing an unrelated `Shape` onto graphs that were
  * never about this concern. Said plainly, not left to be inferred: a `Workflow` built with no
  * `shape` argument at all is EXPLICITLY UNVALIDATED, not proven safe by an empty result: `validate`
  * finding no violation on `Shape(Nil, Nil)` means nothing was walked, not that nothing is wrong.
  * `Machine.shippedWorkflow` is the one caller that supplies a real one.
  *
  * `stages` (issue #40) is the same spirit as `shape`: data a consumer of the graph reads, never
  * control flow the walk itself branches on. `Runner.run`/`Runner.step` never look at it; it exists
  * so a caller that owns a `StatusLog` (`Machine.runOnce`) can declare, once per tick (issue #40
  * review, MAJOR 1: a declaration always has to sit inside `banner.sh`'s own `tail -n 5000` read
  * window, so it is written ahead of every tick's own first status event, not once per process),
  * what `watch.sh` should render for THIS graph, without the renderer or this file needing to
  * agree on a fixed vocabulary of phase names ahead of time. Defaults to an empty `StageSet`, the
  * same reasoning `shape`'s own
  * default gives: a `Workflow` built with no `stages` argument is a graph that has declared nothing
  * about how it should be rendered, not one proven to render some particular way.
  */
final case class Workflow[I](
    name: String,
    start: I => Next,
    shape: Shape = Shape(Nil, Nil),
    stages: StageSet = StageSet(Nil, None, None)
)

/** Executes a `Workflow` (or, via `step`, a single `Node`) against the capabilities in scope,
  * owning every concern a node itself is not allowed to own: whether a `probe` already answered the
  * question, whether the run's shared budget can afford this node, and whether the node's own
  * `probe`/`run` overran its declared `timeout`. Every node `Machine.shippedWorkflow` (issue #37)
  * walks, `Implement` through `PostMergeCleanup`, is wired through this, plus `Pick`, which
  * `Machine.runOnce` steps outside the walk, against its own throwaway `Ledger`, before the real
  * `Ledger` `shippedWorkflow` runs against even exists (`runOnce`'s own doc has the reason). `RunnerSpec`
  * additionally exercises the mechanics below with fake nodes, so this file stays usable ahead of the
  * next real node joining the graph.
  *
  * Emits no log line and no status event of its own, on any path: the only observable side effect a
  * `Runner` call can ever cause is a `Node`'s own `probe`/`run` body doing something (which already
  * happens through the ordinary capability calls the golden log contract already pins), or a fault
  * going through `Machine.infraFault` (which logs and notifies exactly where every OTHER fault in
  * this loop already does). Adding a distinct trace of its own, "node started", "node finished",
  * would be new, runner-specific observability that no golden pins today; RFC decision 11 is where
  * that gets decided, not here.
  */
object Runner:

  /** The run's shared dispatch budget, owned by the `Runner` and deliberately NOT a member of
    * `Caps`: a node's `probe`/`run` never receives a `Ledger` (see `Node`'s own doc), so there is no
    * path from inside a node back to this counter. The constructor is `private[litterbox]`, not
    * public, for the same reason a node cannot see this type at all: a caller holding `Caps` and a
    * `Fault` has no way to reach a `Ledger` either, so if the constructor stayed public the whole
    * guarantee would depend on nobody happening to import it, rather than on nothing being able to
    * (issue #32 review finding 2c: a node minting its own `Ledger(999)` and re-entering `Runner.step`
    * with it was possible before this).
    */
  final class Ledger private[litterbox] (initial: Int):
    private var remaining: Int = initial

    /** Whether a node declaring `cost` is even allowed to start this tick. Checked once, before
      * `probe`/`run`, never during: this is a ceiling on STARTING, not the spend itself, which is
      * `chargeDispatch` below. Kept a separate method from charging, rather than one method that
      * both checks and decrements, because the two questions have different owners in `Runner.step`:
      * this one gates whether a node runs at all, and only `chargeDispatch` ever changes
      * `remaining`.
      */
    private[litterbox] def canAfford(cost: Cost): Boolean = cost match
      case Cost.NoDispatch  => true
      case Cost.OneDispatch => remaining > 0

    /** The actual spend, called once per real dispatch by the decorator `Runner.step` wraps
      * `Caps.agents` in, never derived from a node's declared `Cost`. This is what closes the three
      * bypasses issue #32 review finding 2 raised: a probe that dispatches is charged exactly the
      * same as a run that does, and a node that dispatches five times is charged five times rather
      * than once per `step` call. `private[litterbox]`, not `private`, because the decorator that
      * calls it lives in the same object as `Ledger` but is still a different value; that visibility
      * means any code in this package could call it directly, not only the decorator, so the "only
      * ever charged by a real dispatch" guarantee rests on the decorator being the only caller by
      * convention within the package, not on the compiler ruling out every other one. Saturates at
      * zero rather than going negative: `canAfford` already refuses to let a `Cost.OneDispatch` node
      * start once empty, so this only ever runs low, not out, on a node that dispatches more than it
      * declared.
      */
    private[litterbox] def chargeDispatch(): Unit =
      if remaining > 0 then remaining -= 1

    /** What is left to charge against. Public, unlike `canAfford`/`chargeDispatch`: a test (or an
      * operator-facing status line, later) reading how much budget survived a run is not the same
      * privilege as being able to spend it or gate on it.
      */
    def remainingDispatches: Int = remaining

  /** Wraps `agents` so that a real dispatch call, made from EITHER `probe` or `run`, is charged
    * against `ledger` at the exact moment it happens, rather than inferred from whatever `Cost` the
    * node declared. This is the fix for issue #32 review finding 2a/2b: a node cannot dispatch from
    * its `probe` for free (the same wrapped `Caps` reaches both `probe` and `run`), and a node that
    * calls `Caps.agents` more than once in a single `step` is charged once per call, not once per
    * `step`. Plain `private`, stricter than `chargeDispatch`'s own `private[litterbox]`: this method
    * only needs to be reachable from within `Runner` itself, so it is scoped to that, not to the
    * whole package.
    */
  private def charging(agents: AgentDispatch, ledger: Ledger): AgentDispatch = new AgentDispatchImpl:
    def worker(
        role: Role,
        promptFile: String,
        patchOut: String,
        logFile: String,
        currentPatch: Option[String]
    ): DispatchOutcome =
      ledger.chargeDispatch()
      agents.worker(role, promptFile, patchOut, logFile, currentPatch)
    // Overrides `dispatchReview`, not `review` (issue #35: see `AgentDispatch.review`'s own doc,
    // `src/Caps.scala`, for why). `.value` below unwraps `agents.review`'s own freshly minted
    // `Judged`, from the WRAPPED `agents`, so the charge lands on the real dispatch. Extends
    // `AgentDispatchImpl`, not `AgentDispatch` directly (round three of issue #35's review:
    // `AgentDispatch` is `sealed`, only extendable from `Caps.scala` itself); `private[litterbox]`
    // here matches the abstract member's own visibility, see `AgentDispatch`'s own doc for the
    // guarantee this whole split buys.
    private[litterbox] def dispatchReview(prompt: String, reviewFile: String): DispatchOutcome =
      ledger.chargeDispatch()
      agents.review(prompt, reviewFile).value

  /** Runs exactly one `Node`. The `Caps` a node's `probe`/`run` actually see is built HERE, not
    * passed through unchanged from the caller (issue #32 review finding 2): `agents` is replaced
    * with the `charging` decorator above, so a dispatch physically cannot happen without being
    * charged, no matter which of `probe`/`run` makes it or how many times.
    *
    * The start time is read before `probe`, not before `run`: `probe` is still arbitrary node code
    * running against a live `Caps`, so giving it a free pass on the wall clock would reopen finding
    * 2a for the clock instead of the ledger. A `Cost.OneDispatch` node the ledger cannot afford is
    * parked rather than run (see that case's own scaladoc for why `Parked`, not `NeedsHuman`), and
    * parking here logs the node and the exhausted budget before returning, since a runner park is
    * otherwise silent: unlike the `Parked` returns inside `pickAndSetup`, nothing here mutates a
    * label, so the log line is the only trace this exit leaves. A node returning
    * `Stopped(LoopExit.InfraFault)` directly, instead of through `Fault.raise`, is routed through
    * `fault.raise` here too, for the same reason `Fault` itself exists: an infra fault that skipped
    * the log line and the notify would defeat the guarantee at the point it is meant to be
    * unavoidable. The elapsed-time check against `timeout` runs after the node returns rather than
    * pre-emptively: pre-emption needs a second thread racing the node's own, and this loop's real
    * timeouts are already enforced one layer down, at the subprocess boundary that actually
    * dispatches a worker/fixer/reviewer/gate. This check exists only to make an overrun observable,
    * not to cut the node off.
    *
    * A second check here, added by issue #38 review BLOCKER 2, is what makes `Trust.Reviewed` an
    * observed fact rather than a claim `TrustOf` merely typechecked: `NodeOutcome`'s own covariance
    * (`NodeOutcome`'s own doc) lets `Stopped(exit)`, typed `NodeOutcome[Nothing]`, satisfy a
    * `run`/`probe` signature declared to return `NodeOutcome[AgentDispatch.Judged[A]]`, so a `Node`
    * whose declared output type happens to be `Judged`-shaped earns `Trust.Reviewed` from `TrustOf`
    * even if its body never calls `AgentDispatch.review` at all, so long as it never returns a
    * `Done`. That is a real gap in what the type-level derivation alone can promise (`Trust`'s own
    * doc), and this runtime check is the other half: whenever a `Trust.Reviewed` node's `outcome`
    * IS a `Done(value)`, `value` is checked against the actual runtime class `AgentDispatch.Judged`,
    * not merely its declared type, and a mismatch faults through the same `fault.raise` every other
    * observed violation in this method already uses. A `Stopped` result is NOT itself a forgery and
    * must not fault here: a node that only ever stops never claims to have produced a review, it
    * simply never reaches the point where that claim could be checked (the walk it sits on ends
    * there instead, so nothing downstream of it runs unreviewed either). This cannot fire on
    * `Machine.shippedWorkflow`: `Machine.Review` is the one node in that graph whose output type is
    * `AgentDispatch.Judged`, and its `run` body only ever produces one through a genuine
    * `AgentDispatch.review` dispatch (`Machine.Review`'s own doc), so its `Done` always carries a
    * real one and no golden log moves.
    *
    * The strongest justification this check has is not the covariance gap above, which at least
    * requires a node author to write a plausible, honestly-typed `Node`: `TrustOf` (`TrustOf`'s own
    * doc) is `sealed`, but sealed only closes off a THIRD PARTY writing a new instance, never a cast
    * on an existing one: `TrustOf.judged.asInstanceOf[TrustOf[MyType]]` compiles today and would
    * stamp `Trust.Reviewed` onto an entirely ordinary node's `MyType` output at the `Node.apply` call
    * site, with nothing in `Node`'s own constructor able to tell the difference. This runtime check
    * is what still catches that forgery, the same way it catches every other one: the first time such
    * a node's `run` actually reaches a `Done`, the value is checked against the real runtime class,
    * not the (forged) declared type, and it faults right there.
    */
  def step[I, O](node: Node[I, O], input: I)(using
      caps: Caps,
      faulting: Faulting,
      ledger: Ledger
  ): NodeOutcome[O] =
    val chargingCaps = caps.copy(agents = charging(caps.agents, ledger))
    val fault         = Fault(faulting, chargingCaps.logger, chargingCaps.notifier)
    val startedAt     = chargingCaps.clock.nowMillis()

    val outcome = node.probe(input)(using chargingCaps, fault) match
      case Some(o) => NodeOutcome.Done(o)
      case None    =>
        if !ledger.canAfford(node.cost) then
          chargingCaps.logger.log(
            s"node '${node.name}' parked: dispatch budget exhausted before it could run"
          )
          NodeOutcome.Stopped(LoopExit.Parked)
        else node.run(input)(using chargingCaps, fault)

    outcome match
      case NodeOutcome.Done(value)
          if node.trust == Trust.Reviewed && !value.isInstanceOf[AgentDispatch.Judged[?]] =>
        fault.raise(
          s"node '${node.name}' is stamped Trust.Reviewed (its declared output type unifies with " +
            "AgentDispatch.Judged) but returned a Done value that is not one at runtime, this node's " +
            "own body never produced a genuine review, so Runner.validate's reachability check would " +
            "have wrongly treated every path through it as reviewed"
        )
      case NodeOutcome.Stopped(LoopExit.InfraFault) =>
        fault.raise(s"node '${node.name}' returned Stopped(LoopExit.InfraFault) directly")
      case _ => ()

    node.timeout match
      case Timeout.Unbounded      => ()
      case Timeout.After(seconds) =>
        val elapsedMs = chargingCaps.clock.nowMillis() - startedAt
        if elapsedMs > seconds.toLong * 1000L then
          Machine.infraFault(
            s"node '${node.name}' overran its ${seconds}s timeout, an infra fault, not a code failure"
          )(using chargingCaps.logger, chargingCaps.notifier)(using faulting)

    outcome

  /** Caps how many violation messages get joined into one fault line (issue #38 review finding 4): a
    * large invalid graph can otherwise produce enough violations for the joined string alone to run
    * to hundreds of megabytes. `20` is generous enough that an ordinary mistake in a hand-declared
    * `Shape` still shows every offending path; past that, the message is summarized rather than
    * spelled out in full, since past a couple dozen the point ("this graph's declaration is wrong")
    * is already made.
    */
  private val MaxReportedViolations = 20

  /** Joins `violations` into one fault line, capped at `MaxReportedViolations` (see that val's own
    * doc): the first `MaxReportedViolations` messages verbatim, then how many more were left out.
    * Shared by `run` (below) and `Machine.runOnce`'s own hoisted validation (issue #38 review finding
    * 3), so an operator reads the same wording no matter which of the two call sites caught the bad
    * graph.
    */
  private[litterbox] def describeViolations(violations: List[String]): String =
    if violations.size <= MaxReportedViolations then violations.mkString("; ")
    else
      violations.take(MaxReportedViolations).mkString("; ") +
        s"; and ${violations.size - MaxReportedViolations} more"

  /** The one operator-facing sentence a `Shape` violation is ever reported through (issue #38 review
    * finding 8/nit): `run` (below) and `Machine.runOnce`'s own hoisted validation used to copy-paste
    * this exact three-line message, `describeViolations` having already been extracted once for the
    * shared CAPPING logic but not for the wording wrapped AROUND it. Extracting the whole sentence
    * here, not just the cap, is what actually keeps the two call sites from drifting apart in
    * wording the next time either one is edited; `describeViolations` alone left that risk open.
    * States plainly that the GRAPH DECLARATION is wrong, not merely that something failed (issue #38
    * review finding 7): unlike every other `infraFault` site in this codebase, a bad `Shape` cannot
    * be healed by the next tick simply retrying, since nothing about the graph's own declared data
    * changes between ticks; an operator reading rc 50 here should reach for the `Shape`, not go
    * hunting for a transient cause.
    */
  private[litterbox] def invalidShapeMessage(name: String, violations: List[String]): String =
    s"workflow '$name' has an invalid declared shape and cannot be walked, fix the Shape " +
      "(the loop cannot proceed, and retrying will not repair a bad graph declaration): " +
      describeViolations(violations)

  /** Walks `shape` from its own declared `entry` nodes over its own declared `transitions`, looking
    * for a path that reaches a `Guard.RequiresReview` node without ever crossing a `Trust.Reviewed`
    * one first (issue #38, RFC #26 decision 16's cheap half). Pure: no `Caps`, no side effect,
    * nothing but a `Shape`'s own two lists in and a list of violation messages out, empty meaning
    * valid, which is what lets `run` (below) call this before a single capability exists.
    *
    * Three checks below run over the DECLARATION itself, before any walk, because each is a fact
    * about the `Shape`'s own data, not about any one path through it (issue #38 review findings 5
    * and 6):
    *
    *   - `entry` empty while `transitions` is not: nothing declares where a walk could even begin,
    *     so every transition is dead data and the walk below would examine nothing at all. Reported
    *     as its own violation ("transitions declared with no entry") instead of silently agreeing,
    *     because `Shape(Nil, Nil)`, the genuine "no shape declared" default (`Workflow`'s own doc),
    *     and `Shape(Nil, someTransitions)`, a `Shape` that forgot its own `entry`, must not read the
    *     same way: only the former is a deliberate opt-out, and this case short circuits with only
    *     that one message, since nothing else below has anything to examine either.
    *   - two nodes sharing one `name` across `entry ++ transitions` that disagree on `cost`,
    *     `timeout`, `trust` or `guard`: `byFrom` below keys on `name` alone, so two nodes with the
    *     same name silently merge their edges and inherit whichever instance a given walk happened
    *     to reach first (issue #38 review finding 6). `name` ALONE is deliberately not enough to
    *     call two `Node` values duplicates, though: `Machine.shippedWorkflow` legitimately declares
    *     the same conceptual node (`Gate(cfg)`, say) more than once across its own
    *     `entry`/`transitions`, and every one of those calls builds a fresh `Node` with fresh
    *     `probe`/`run` closures that are never `==` to one another (functions compare by reference,
    *     not by behaviour), comparing on the closures would flag that legitimate, load-bearing
    *     pattern as a duplicate on every single call. Comparing on `cost`/`timeout`/`trust`/`guard`
    *     instead, the four facts this validator and `Runner.step` actually read, only fires when two
    *     nodes sharing a name genuinely disagree about one of them.
    *   - a node named somewhere in `transitions` (as a `from` or a `to`) that the walk below never
    *     actually reaches from any declared `entry`: previously skipped in silence, now reported,
    *     since an unreachable node in a hand-declared `Shape` is at least as likely to be a forgotten
    *     `entry` or a typoed name as it is a deliberate, dead declaration.
    *
    * The walk itself is a breadth first search over a SINGLE, GLOBAL visited set keyed on
    * `(node name, reviewed so far)`, not a fresh `visited` set threaded down each path (issue #38
    * review finding 4): threading `visited` per path makes the walk enumerate every PATH through the
    * graph rather than every STATE, which is exponential in how many diamonds a graph happens to
    * chain, twenty chained diamonds produced over a million violations in a couple of seconds
    * before this fix, all bound for one `mkString`d fault line. Sharing one set across the whole
    * walk means each `(name, reviewed)` state is expanded at most once, however many paths reach it,
    * which is linear in the size of the declared graph instead. BFS, not DFS, is what makes the
    * reported path the SHORTEST one into a guarded node: this validator's contract is to name a path
    * an operator can act on, and the shortest one is the most directly actionable. The walk is
    * written iteratively, over an explicit queue, not recursively, matching the `@tailrec` standard
    * `run`'s own walk (below) already holds itself to, rather than leaving a large declared graph's
    * stack safety an accident of how deep a recursion happens to go.
    *
    * Crossing a `Trust.Reviewed` node sets the "reviewed so far" flag for every state reached beyond
    * it, and it never clears back, because one review earlier on a path clears every guarded node
    * later on that SAME path. "Beyond it" is deliberate and load bearing: a node's own guard is
    * tested against the flag the walk carried IN, from before that node ran, never against the flag
    * folded with that SAME node's own trust, so a node that happens to be both `Trust.Reviewed` and
    * `Guard.RequiresReview` cannot clear its own guard by virtue of its own trust (issue #38 review,
    * second round: a consumer node that opens a PR and then reviews the very same call is not a
    * review of anything, and the walk must not read it as one). Violation messages name the whole
    * offending path, its node names
    * joined by `->`, and the guarded node it reaches, not merely that some path somewhere is bad:
    * fixing this means knowing which edge needs a review in front of it, and a bare node name leaves
    * that to guesswork on any graph with more than one way to reach it. Ordering is deterministic:
    * `shape.entry` enqueued in its own declared order, and each node's own `transitions` enqueued in
    * their own declared order as that node is dequeued, never a `Set`/`Map` iteration order, so the
    * same `Shape` reports the same violations in the same order on every call.
    */
  def validate(shape: Shape): List[String] =
    if shape.entry.isEmpty && shape.transitions.nonEmpty then
      List("transitions declared with no entry: nothing was validated")
    else
      val allNodes: List[Node[?, ?]] =
        shape.entry ++ shape.transitions.flatMap(t => List(t.from, t.to))

      def identityOf(n: Node[?, ?]) = (n.cost, n.timeout, n.trust, n.guard)

      val declaredNames = allNodes.map(_.name).distinct

      val duplicateNameViolations = declaredNames.flatMap { name =>
        val identities = allNodes.filter(_.name == name).map(identityOf).distinct
        if identities.size > 1 then
          List(
            s"node name '$name' is declared by ${identities.size} structurally different nodes " +
              "(differing cost, timeout, trust or guard) across entry/transitions; two declared " +
              "nodes sharing a name must agree on every fact this validator reads"
          )
        else Nil
      }

      val byFrom: Map[String, List[Node[?, ?]]] =
        shape.transitions.groupBy(_.from.name).view.mapValues(_.map(_.to)).toMap

      final case class Item(node: Node[?, ?], path: List[String], reviewed: Boolean)

      val visited        = scala.collection.mutable.Set.empty[(String, Boolean)]
      val reached        = scala.collection.mutable.Set.empty[String]
      val pathViolations = scala.collection.mutable.ListBuffer.empty[String]
      val queue          = scala.collection.mutable.Queue.empty[Item]
      shape.entry.foreach(e => queue.enqueue(Item(e, Nil, false)))

      while queue.nonEmpty do
        val Item(node, path, reviewed) = queue.dequeue()
        val key                        = (node.name, reviewed)
        if !visited.contains(key) then
          visited += key
          reached += node.name
          val pathHere = path :+ node.name
          // The guard test reads `reviewed`, the flag carried IN from the walk before this node ran
          // at all, never `reviewedHere` below (issue #38 review, second round, real logic bug): a
          // node that is BOTH `Trust.Reviewed` and `Guard.RequiresReview` must not be able to clear
          // its own guard by virtue of its own trust, the same way a reviewer dispatching a PR in the
          // same breath it reviews it is not a review of anything. `reviewed` is exactly the state of
          // the walk one step BEFORE this node's own output is considered.
          if node.guard == Guard.RequiresReview && !reviewed then
            pathViolations +=
              s"path ${pathHere.mkString(" -> ")} reaches '${node.name}' with no reviewed node before it; " +
                "a node whose output is AgentDispatch.Judged must appear on every path into it"
          // `reviewedHere`, unlike the guard test above, DOES fold in this node's own trust: a
          // reviewer node still clears the guard on every node AFTER it on the same path, exactly as
          // before; only this node's own guard, checked one line up, must not be cleared by its own
          // trust.
          val reviewedHere = reviewed || node.trust == Trust.Reviewed
          byFrom
            .getOrElse(node.name, Nil)
            .foreach(child => queue.enqueue(Item(child, pathHere, reviewedHere)))

      val unreachableViolations = declaredNames.collect {
        case name if !reached.contains(name) =>
          s"node '$name' is declared in transitions but is unreachable from any declared entry; it was never examined by this validator"
      }

      duplicateNameViolations ++ pathViolations.toList ++ unreachableViolations

  /** Walks `wf` from `input` to a `LoopExit`, one `step` at a time: `Next.Finish` ends the walk,
    * `Next.Goto` steps its node and, on `NodeOutcome.Done`, hands the value to `andThen` for the
    * next `Next`; `NodeOutcome.Stopped` ends the walk early with that node's own exit, and nothing
    * after it ever runs. `@tailrec` pins the stack safety a graph of unbounded length needs, rather
    * than leaving it an accident of how the match compiles today.
    *
    * `validate(wf.shape)` runs FIRST, before `wf.start(input)` is ever called (issue #38): a
    * violation is a fact about the graph's own declared shape, true before any node has run, so
    * checking it after even one node's side effects fired would be too late to mean "rejects a
    * graph before executing any node". On a violation this goes through the SAME fault channel
    * every other fault in this loop already uses, `Machine.infraFault`, so it logs, notifies and
    * abandons the tick exactly like any other infra fault, never a second, differently shaped exit.
    * On a valid graph, `validate` returns `Nil` and this emits nothing at all: every golden log
    * this suite pins stays byte identical, since the only paths that ever produce a fresh log line
    * here are ones that were already going to abandon the run.
    *
    * `Machine.runOnce` (issue #38 review finding 3) additionally validates `shippedShape(cfg)` at
    * the very TOP of the tick, before even `Pick` runs, for the same reason this call sits before
    * `wf.start`: a full node, `Pick`, otherwise runs (branch created, labels flipped, prompt files
    * written) on an invalid graph before this method ever gets a chance to reject it. That earlier
    * check does not replace this one: a consumer calling `run` directly, bypassing `runOnce`
    * entirely, is still covered here. A valid graph therefore pays for `validate` at most twice, both
    * calls pure and silent, never a third time, and a genuinely valid graph emits nothing either way.
    * The fault message, built by `invalidShapeMessage` (above, issue #38 review findings 7 and 8),
    * states plainly that the GRAPH DECLARATION is wrong, not merely that something failed: unlike
    * every other `infraFault` site in this codebase, a bad `Shape` cannot be healed by the next tick
    * simply retrying, since nothing about the graph's own declared data changes between ticks; an
    * operator reading rc 50 here should reach for the `Shape`, not go hunting for a transient cause.
    * `Machine.runOnce`'s own hoisted validation calls the same `invalidShapeMessage`, so an operator
    * reads identical wording no matter which of the two call sites caught the bad graph, and
    * `describeViolations` (above), which `invalidShapeMessage` itself calls, caps how much of the
    * violation list actually lands in that one line (issue #38 review finding 4).
    */
  def run[I](wf: Workflow[I], input: I)(using
      caps: Caps,
      faulting: Faulting,
      ledger: Ledger
  ): LoopExit =
    val violations = validate(wf.shape)
    if violations.nonEmpty then
      Machine.infraFault(
        invalidShapeMessage(wf.name, violations)
      )(using caps.logger, caps.notifier)(using faulting)
    else
      @tailrec
      def walk(next: Next): LoopExit = next match
        case Next.Finish(exit) => exit
        case g: Next.Goto[i, o] =>
          step(g.node, g.input) match
            case NodeOutcome.Done(o)     => walk(g.andThen(o))
            case NodeOutcome.Stopped(ex) => ex
      walk(wf.start(input))
