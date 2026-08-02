package in.rcard.litterbox

import scala.annotation.tailrec
import scala.util.boundary

// The graph kit (issue #32): `Node`, `Workflow` and the `Runner` that executes one. `Machine.Pick`
// is the first node built on this; `Machine.Implement` (issue #33) is the second, and the first to
// carry a real dispatch and a real `Timeout.After` through it. The rest of `iterate` (repair,
// terminal) stays a plain function for now.
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
// and unbounded by anything here. That is not a scope violation, since decision 16's macro is the
// intended future guard and is out of scope for this file, but this header must not claim the
// `Runner` covers more than dispatch budget and wall-clock time.

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
  * exactly where it always was (`runOnce`, transitively wrapping `iterate` and everything `iterate`
  * calls, including the `Runner`), so nothing about WHERE a fault lands moves with this file.
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
  */
final case class Node[I, O](
    name: String,
    cost: Cost,
    timeout: Timeout,
    probe: I => (Caps, Fault) ?=> Option[O],
    run: I => (Caps, Fault) ?=> NodeOutcome[O]
)

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
  */
final case class Workflow[I](name: String, start: I => Next)

/** Executes a `Workflow` (or, via `step`, a single `Node`) against the capabilities in scope,
  * owning every concern a node itself is not allowed to own: whether a `probe` already answered the
  * question, whether the run's shared budget can afford this node, and whether the node's own
  * `probe`/`run` overran its declared `timeout`. `Machine.Pick` and `Machine.Implement` are the
  * nodes wired through this today (`Machine.iterate`); `RunnerSpec` additionally exercises the
  * mechanics below with fake nodes, so this file stays usable ahead of a third real node joining the
  * graph.
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
  private def charging(agents: AgentDispatch, ledger: Ledger): AgentDispatch = new AgentDispatch:
    def worker(
        role: Role,
        promptFile: String,
        patchOut: String,
        logFile: String,
        currentPatch: Option[String]
    ): DispatchOutcome =
      ledger.chargeDispatch()
      agents.worker(role, promptFile, patchOut, logFile, currentPatch)
    def review(prompt: String, reviewFile: String): DispatchOutcome =
      ledger.chargeDispatch()
      agents.review(prompt, reviewFile)

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

  /** Walks `wf` from `input` to a `LoopExit`, one `step` at a time: `Next.Finish` ends the walk,
    * `Next.Goto` steps its node and, on `NodeOutcome.Done`, hands the value to `andThen` for the
    * next `Next`; `NodeOutcome.Stopped` ends the walk early with that node's own exit, and nothing
    * after it ever runs. `@tailrec` pins the stack safety a graph of unbounded length needs, rather
    * than leaving it an accident of how the match compiles today.
    */
  def run[I](wf: Workflow[I], input: I)(using Caps, Faulting, Ledger): LoopExit =
    @tailrec
    def walk(next: Next): LoopExit = next match
      case Next.Finish(exit) => exit
      case g: Next.Goto[i, o] =>
        step(g.node, g.input) match
          case NodeOutcome.Done(o)     => walk(g.andThen(o))
          case NodeOutcome.Stopped(ex) => ex
    walk(wf.start(input))
