package in.rcard.litterbox

import scala.collection.mutable
import scala.util.boundary

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import Caps.given

/** Generic `Node`/`Workflow`/`Runner` mechanics (issue #32), driven with FAKE nodes rather than the
  * real Pick node: Pick's own conversion is end to end covered by `ScenarioSpec`/`LogParitySpec`
  * already (they are the oracle and must not be touched), so this spec's job is the engine those
  * two specs exercise indirectly (declared order, probe short circuiting, ledger accounting, the
  * timeout boundary, and the fault channel), none of which needs a real capability.
  *
  * Capabilities come from `TestWorld` (`test/Recorder.scala`), the shared seam `CONVENTIONS.md`
  * names for exactly this: it already answers every trait `Caps` bundles, and its own `calls`/
  * `logLines`/`notifications` give every assertion below for free instead of a second, locally
  * reimplemented recorder. Only `Clock` is swapped for `FakeClock` per test: `TestWorld.clock`
  * answers a constant (no scenario there drives a `Timeout.After` node yet), and the timeout tests
  * below need to script two distinct readings.
  *
  * `import Caps.given` mirrors `Machine.scala`'s own import: a fake node's `run` body below reaches
  * `Caps.agents`/`Fault` the same way `Machine.Pick`'s real `run` body does, through the ambient
  * `Caps`/`Fault` a context function body carries, never through a named parameter this file also
  * declares (so there is no given-ambiguity trap here either, see `Caps`'s own doc).
  *
  * A node cannot spend budget or observe the ledger BY CONSTRUCTION, not by a runtime check: `Node`'s
  * `probe`/`run` fields are typed `(Caps, Fault) ?=> ...` only, and `Caps` (`src/Caps.scala`) has
  * no `Ledger` member, so there is no context parameter through which a node's own code could ever
  * name a `Ledger` type, let alone summon one. A compile time negative test would only reassert what
  * the signatures already state; the behavioural tests below (ledger charged only through the real
  * dispatch decorator, never through a declared `Cost`) are the structural guarantee's runtime half.
  */
class RunnerSpec extends AnyFlatSpec with Matchers:

  /** A `Clock` a test can script by hand. `nowMillis` answers each element of `answers` in turn,
    * and repeats the last one once exhausted, so a test only has to name as many readings as it
    * cares about (one per `caps.clock.nowMillis()` call `Runner.step` makes around a node's
    * `probe`/`run`).
    */
  private class FakeClock(answers: List[Long]) extends Clock:
    private var remaining = answers
    def sleepSeconds(s: Int): Unit = ()
    def nowMillis(): Long = remaining match
      case head :: tail =>
        remaining = tail
        head
      case Nil => answers.lastOption.getOrElse(0L)

  private def buildCaps(world: TestWorld, clock: Clock): Caps =
    Caps(
      cfg = Config(),
      gh = world.github,
      git = world.git,
      agents = world.agents,
      gates = world.gates,
      hostGates = world.hostGates,
      status = world.status,
      notifier = world.notifier,
      fs = world.fs,
      clock = clock,
      logger = world.logger
    )

  /** Every `Runner.step`/`Runner.run` call needs a `Faulting` in scope, exactly the way every real
    * call site gets one: from the `boundary[LoopExit]` `Machine.runOnce` establishes for a real run.
    * Reusing that same shape here, rather than a lighter-weight substitute, means a fault under test
    * aborts to this boundary exactly the way it would in the real loop. `body` runs to completion and
    * its result is captured in `Right`; a fault instead lands here as `Left(exit)`, `exit` being
    * whatever `LoopExit` the fault broke to.
    */
  private def withFaulting[T](body: Faulting ?=> T): Either[LoopExit, T] =
    var out: Option[T] = None
    val exit = boundary[LoopExit]:
      out = Some(body)
      LoopExit.Idle // never read: `out` is `Some` whenever this line is reached
    out match
      case Some(t) => Right(t)
      case None    => Left(exit)

  private def tracingNode(label: String, trace: mutable.ArrayBuffer[String]): Node[Unit, Unit] =
    Node(
      name = label,
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        trace += label
        NodeOutcome.Done(())
    )

  /** A node that dispatches for real (`calls` many `worker` calls) rather than merely declaring a
    * `Cost`: the ledger tests below need a fact the `Runner`'s charging decorator can observe, not a
    * claim in `cost` it has to trust. Defaults to one call for the tests that only care about that.
    */
  private def dispatchingNode(cost: Cost, calls: Int = 1): Node[Unit, Unit] =
    Node(
      name = "dispatching",
      cost = cost,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        (1 to calls).foreach(_ => summon[AgentDispatch].worker(Role.IMPL, "prompt", "patch", "log", None))
        NodeOutcome.Done(())
    )

  // ---- declared order --------------------------------------------------------------------

  "Runner.run" should "run a graph of fake nodes in declared order and finish with the declared LoopExit" in {
    val trace = mutable.ArrayBuffer.empty[String]
    val n1    = tracingNode("n1", trace)
    val n2    = tracingNode("n2", trace)
    val n3    = tracingNode("n3", trace)
    val wf: Workflow[Unit] = Workflow(
      "three-node",
      start = _ =>
        Next.Goto(n1, (), _ => Next.Goto(n2, (), _ => Next.Goto(n3, (), _ => Next.Finish(LoopExit.Success))))
    )
    val world = new TestWorld
    val clock = new FakeClock(List(0L))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.run(wf, ())

    result shouldBe Right(LoopExit.Success)
    trace.toList shouldBe List("n1", "n2", "n3")
  }

  // ---- probe short circuits run -----------------------------------------------------------

  it should "skip run and continue from the probed value when probe answers Some" in {
    var ran = false
    val probed = Node[Unit, Int](
      name = "probed",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => Some(42),
      run = _ =>
        ran = true
        NodeOutcome.Done(0)
    )
    val world = new TestWorld
    val clock = new FakeClock(List(0L))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.step(probed, ())

    result shouldBe Right(NodeOutcome.Done(42))
    ran shouldBe false
  }

  // ---- ledger accounting -------------------------------------------------------------------

  it should "charge the ledger once per real dispatch, not once per declared Cost.OneDispatch step" in {
    // Ledger(1) with a single dispatch would saturate to 0 whether the charge happened once or
    // twice, proving nothing about per-call charging (issue #32 review round 2 finding 8). Ledger(3)
    // with two real dispatch calls only lands on 1 remaining if each call was charged separately.
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(3)
    val node   = dispatchingNode(Cost.OneDispatch, calls = 2)

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(node, ())

    result shouldBe Right(NodeOutcome.Done(()))
    ledger.remainingDispatches shouldBe 1
    world.callCount("dispatch IMPL") shouldBe 2
  }

  it should "saturate the ledger at zero, not go negative, when a node dispatches more than its exhausted budget allows" in {
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(1)
    val node   = dispatchingNode(Cost.OneDispatch, calls = 2)

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(node, ())

    result shouldBe Right(NodeOutcome.Done(()))
    ledger.remainingDispatches shouldBe 0
  }

  it should "charge a dispatch made from probe, not only one made from run" in {
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(1)
    val probing = Node[Unit, Int](
      name = "probing",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ =>
        summon[AgentDispatch].worker(Role.IMPL, "prompt", "patch", "log", None)
        Some(1),
      run = _ => NodeOutcome.Done(0)
    )

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(probing, ())

    result shouldBe Right(NodeOutcome.Done(1))
    ledger.remainingDispatches shouldBe 0
    world.callCount("dispatch IMPL") shouldBe 1
  }

  it should "charge a dispatch made through review, not only one made through worker" in {
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(1)
    val reviewing = Node[Unit, Unit](
      name = "reviewing",
      cost = Cost.OneDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        summon[AgentDispatch].review("prompt", "review-file")
        NodeOutcome.Done(())
    )

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(reviewing, ())

    result shouldBe Right(NodeOutcome.Done(()))
    ledger.remainingDispatches shouldBe 0
    world.callCount("dispatch REVIEW") shouldBe 1
  }

  it should "leave the ledger untouched for a Cost.NoDispatch node that never actually dispatches" in {
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(1)
    val node   = tracingNode("free", mutable.ArrayBuffer.empty)

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(node, ())

    result shouldBe Right(NodeOutcome.Done(()))
    ledger.remainingDispatches shouldBe 1
  }

  it should "run a Cost.NoDispatch node even when the ledger is already exhausted" in {
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(0)
    val node   = tracingNode("free-on-empty", mutable.ArrayBuffer.empty)

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(node, ())

    result shouldBe Right(NodeOutcome.Done(()))
  }

  it should "not run a Cost.OneDispatch node whose ledger is exhausted, and finish LoopExit.Parked" in {
    var ran     = false
    val costly = Node[Unit, Unit](
      "costly",
      Cost.OneDispatch,
      Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        ran = true
        NodeOutcome.Done(())
    )
    val world = new TestWorld
    val clock = new FakeClock(List(0L))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.step(costly, ())

    result shouldBe Right(NodeOutcome.Stopped(LoopExit.Parked))
    ran shouldBe false
    // Every `pickAndSetup` `Parked` return logs first; a runner park must too, naming the node
    // (issue #32 review round 2 finding 4), or an operator sees rc 60 with no idea which node or why.
    world.logLines.exists(l => l.contains("costly") && l.contains("exhausted")) shouldBe true
  }

  // ---- fault before dispatch spends nothing ------------------------------------------------

  it should "leave the ledger untouched, and still log and notify, when a node faults before it ever dispatches" in {
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(1)
    val faultsFirst = Node[Unit, Unit](
      "faults-first",
      Cost.OneDispatch,
      Timeout.Unbounded,
      probe = _ => None,
      run = _ => summon[Fault].raise("boom, before any dispatch")
    )

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(faultsFirst, ())

    result shouldBe Left(LoopExit.InfraFault)
    ledger.remainingDispatches shouldBe 1
    // Asserting on the ledger alone would still pass against a `Fault.raise` reduced to a bare
    // `boundary.break`, no log, no notify (issue #32 review round 2 finding 3): the ledger is
    // untouched either way, since the fault happens before any dispatch either way.
    world.logLines.exists(_.contains("boom, before any dispatch")) shouldBe true
    world.notifications should not be empty
  }

  // ---- timeout -----------------------------------------------------------------------------

  it should "fault, through the same infraFault channel as every other fault, a node that overruns its Timeout.After" in {
    val slow = Node[Unit, Unit](
      "slow",
      Cost.NoDispatch,
      Timeout.After(2),
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )
    val world = new TestWorld
    val clock = new FakeClock(List(0L, 5000L)) // 5s elapsed against a 2s bound

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.step(slow, ())

    result shouldBe Left(LoopExit.InfraFault)
    world.logLines.exists(_.contains("slow")) shouldBe true
    world.notifications should not be empty
  }

  it should "never fault a Timeout.Unbounded node no matter how long it takes" in {
    val slowButUnbounded = Node[Unit, Unit](
      "slow-unbounded",
      Cost.NoDispatch,
      Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )
    val world = new TestWorld
    val clock = new FakeClock(List(0L, Long.MaxValue))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.step(slowButUnbounded, ())

    result shouldBe Right(NodeOutcome.Done(()))
    world.logLines shouldBe empty
  }

  it should "finish Done, with no log line, when a Timeout.After node completes inside its bound" in {
    val fast = Node[Unit, Unit](
      "fast",
      Cost.NoDispatch,
      Timeout.After(2),
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )
    val world = new TestWorld
    val clock = new FakeClock(List(0L, 1000L)) // 1s elapsed against a 2s bound

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.step(fast, ())

    result shouldBe Right(NodeOutcome.Done(()))
    world.logLines shouldBe empty
  }

  it should "not fault a Timeout.After node whose elapsed time exactly equals the bound" in {
    val onBoundary = Node[Unit, Unit](
      "on-boundary",
      Cost.NoDispatch,
      Timeout.After(2),
      probe = _ => None,
      run = _ => NodeOutcome.Done(())
    )
    val world = new TestWorld
    val clock = new FakeClock(List(0L, 2000L)) // exactly 2s against a 2s bound

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.step(onBoundary, ())

    result shouldBe Right(NodeOutcome.Done(()))
    world.logLines shouldBe empty
  }

  it should "count a probe's own wall-clock time against Timeout.After, not only run's" in {
    // Three `nowMillis` calls happen around a `Some`-answering probe: the start read, one made
    // from inside `probe` itself (standing in for a probe that genuinely takes wall-clock time),
    // and the final elapsed read. If the start read moved to after `probe` returns (issue #32
    // review round 2 finding 3), the elapsed window would be measured from the in-probe reading
    // (5000L) to the final reading (5000L), a 0ms window that never faults. Measured from the true
    // start (0L) to the final reading (5000L), it is a 5s window against a 2s bound, and does.
    val probing = Node[Unit, Unit](
      "probing-slow",
      Cost.NoDispatch,
      Timeout.After(2),
      probe = _ =>
        summon[Caps].clock.nowMillis()
        Some(())
      ,
      run = _ => NodeOutcome.Done(())
    )
    val world = new TestWorld
    val clock = new FakeClock(List(0L, 5000L, 5000L))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.step(probing, ())

    result shouldBe Left(LoopExit.InfraFault)
  }

  // ---- the fault channel cannot be handed different sinks than the runner built it with ------

  it should "route Fault.raise through the sinks the runner captured when it built the Fault, even if a node shadows Log/Notify locally" in {
    // Before issue #32 review round 2 finding 1, `Fault.raise` took `(using Log, Notify)`, resolved
    // at the CALL site inside a node's own body, not at the point the runner built the `Fault`. A
    // node could shadow both with local no-op givens and raise silently. Capturing the sinks in the
    // `Fault` at construction removes the `using` clause from `raise` entirely, so a local shadow
    // like the one below has nothing left to intercept.
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(0)
    val shadowing = Node[Unit, Unit](
      "shadowing",
      Cost.NoDispatch,
      Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        given shadowLog: Log       = (_: String) => ()
        given shadowNotify: Notify = (_: String) => ()
        summon[Fault].raise("boom")
    )

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(shadowing, ())

    result shouldBe Left(LoopExit.InfraFault)
    world.logLines.exists(_.contains("boom")) shouldBe true
    world.notifications should not be empty
  }

  it should "route a node's own Stopped(LoopExit.InfraFault) through the fault channel rather than returning it silently" in {
    // `NodeOutcome`'s own scaladoc says `Stopped` is "never `LoopExit.InfraFault`", but nothing
    // enforced that (issue #32 review round 2 finding 2): a node returning it directly used to reach
    // the caller as an ordinary `Stopped` value, with no log line and no notify, the accidental twin
    // of finding 1's forged-sinks bug.
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(0)
    val silent = Node[Unit, Unit](
      "silent-fault",
      Cost.NoDispatch,
      Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Stopped(LoopExit.InfraFault)
    )

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(silent, ())

    result shouldBe Left(LoopExit.InfraFault)
    world.logLines should not be empty
    world.notifications should not be empty
  }

  // ---- Stopped ends the graph ---------------------------------------------------------------

  it should "finish the graph on NodeOutcome.Stopped and never run a later node" in {
    var laterRan = false
    val stopper = Node[Unit, Unit](
      "stopper",
      Cost.NoDispatch,
      Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Stopped(LoopExit.NeedsHuman)
    )
    val later = Node[Unit, Unit](
      "later",
      Cost.NoDispatch,
      Timeout.Unbounded,
      probe = _ => None,
      run = _ =>
        laterRan = true
        NodeOutcome.Done(())
    )
    val wf: Workflow[Unit] = Workflow(
      "stop-wf",
      start = _ => Next.Goto(stopper, (), _ => Next.Goto(later, (), _ => Next.Finish(LoopExit.Success)))
    )
    val world = new TestWorld
    val clock = new FakeClock(List(0L))

    val result = withFaulting:
      given caps: Caps            = buildCaps(world, clock)
      given ledger: Runner.Ledger = Runner.Ledger(0)
      Runner.run(wf, ())

    result shouldBe Right(LoopExit.NeedsHuman)
    laterRan shouldBe false
  }
