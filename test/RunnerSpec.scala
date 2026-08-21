package in.rcard.litterbox

import scala.collection.mutable

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

  // `FakeClock`, `buildCaps` and `withFaulting` now live next to `TestWorld` itself
  // (`test/Recorder.scala`, issue #38 review nit): this file, `ShippedWorkflowSpec` and
  // `GraphValidationSpec` all used to carry an identical, separately maintained copy of each.

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

  it should "refuse the SECOND dispatch a Cost.OneDispatch node makes under a budget of exactly one, rather than merely leaving it uncharged, and never let remainingDispatches go negative" in {
    // Before issue #69 this exact node ran to `Done`: `chargeDispatch` saturated at zero, so the
    // second dispatch reached the agent for free and, after the fact, a spend and an overspend read
    // identically off the counter. The counter still never goes negative, but now for the stronger
    // reason: the second charge never happens because the dispatch it would have paid for never
    // happens either. The refusal is asserted three ways, since any one alone is weak evidence: the
    // agent saw exactly one call, the run ended at the fault channel rather than at a value, and the
    // operator facing line names the node and what it tried to dispatch.
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(1)
    val node   = dispatchingNode(Cost.OneDispatch, calls = 2)

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(node, ())

    result shouldBe Left(LoopExit.InfraFault)
    ledger.remainingDispatches shouldBe 0
    world.callCount("dispatch IMPL") shouldBe 1
    // Spelled out in full rather than read back off `Runner.refusedDispatchMessage`: this line is an
    // operator facing contract, and a test that composed it the same way the code does would follow
    // any rewording silently.
    world.logLines should contain(
      "node 'dispatching' attempted a dispatch (IMPL) with an exhausted dispatch budget, refused " +
        "before it reached the agent: the runner meters every dispatch at the capability, so no " +
        "node can spend past the budget its graph declared, whatever Cost it declares"
    )
    world.notifications should not be empty
  }

  it should "refuse a review dispatch on an exhausted ledger through the fault channel, so no AgentDispatch.Judged can exist without a real dispatch behind it" in {
    // The trust half of issue #69, and the reason a refusal faults rather than answering the node
    // with some `DispatchOutcome.Refused` case. `AgentDispatch.review` is the only minter of
    // `Judged` (RFC #26 decision 7), so a refusal that RETURNED anything at all on this path would
    // have to return a `Judged` with no cold session behind it, a strictly worse failure than the
    // overspend this issue set out to stop. `Fault.raise` returns `Nothing`, so the mint below is
    // unreachable at the type level, and this test is the runtime half: the scripted reviewer was
    // never called, and the walk ended at the boundary rather than at a value.
    val world  = new TestWorld
    val clock  = new FakeClock(List(0L))
    val ledger = Runner.Ledger(0)
    val reviewing = Node[Unit, AgentDispatch.Judged[Unit]](
      name = "reviewing-on-empty",
      cost = Cost.NoDispatch, // the misdeclaration: honest here would be Cost.OneDispatch
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file").map(_ => ()))
    )

    val result = withFaulting:
      given caps: Caps       = buildCaps(world, clock)
      given l: Runner.Ledger = ledger
      Runner.step(reviewing, ())

    result shouldBe Left(LoopExit.InfraFault)
    world.callCount("dispatch REVIEW") shouldBe 0
    ledger.remainingDispatches shouldBe 0
    world.logLines should contain(
      Runner.refusedDispatchMessage("reviewing-on-empty", "REVIEW")
    )
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

  // ---- Judged is minted only by AgentDispatch.review, never fabricated by LIBRARY code (issue #35) --

  /** Every test below in this section is a compile time check, not a runtime one, because the
    * property under test IS "this does not typecheck": `AgentDispatch.Judged`'s only constructor
    * and `Judged.mint` are both `private[AgentDispatch]` (`src/Caps.scala`), so a fabrication
    * attempt cannot be exercised at runtime the way the rest of this file exercises `Runner`, there
    * is no value to hand a runtime assertion because the compiler refuses to produce one. The
    * snippet under test has to live inside a string literal handed to
    * `scala.compiletime.testing.typeCheckErrors` (Scala 3 stdlib, no new test dependency, see
    * `project.scala`'s single `scalatest` test dep and `CONVENTIONS.md`'s one test dep rule):
    * writing the fabrication as ordinary code in this file would just stop `RunnerSpec.scala` itself
    * from compiling, which proves nothing about the review node design and only breaks this suite.
    *
    * What this section can and cannot prove, precisely (round two of issue #35's review):
    * `typeCheckErrors` type-checks its string argument spliced in at THIS call site's own lexical
    * position, not as an independent, freshly packaged compilation unit (confirmed while drafting
    * this section: a snippet run from here resolves `Caps` and friends unqualified with NO import at
    * all, and a `package foo { ... }` clause written inside the string is flatly rejected, "this kind
    * of statement is not allowed here", the same restriction ordinary Scala places on a `package`
    * clause inside a method body). So every snippet below is compiled from inside package
    * `in.rcard.litterbox`, the SAME package `LiveAgentDispatch`, `Runner.charging` and `TestWorld`
    * live in, no matter what it claims to be: this section proves the mint site is closed to LIBRARY
    * code, not that it is closed to a CONSUMER's package. `Caps.given` has to be imported by name
    * inside each snippet for the same reason: `summon[AgentDispatch]` inside a node's `run` body only
    * resolves through the given conversions `Caps.scala` declares, and those are not ambient just
    * because the snippet sits in the right package.
    *
    * The other half, that a genuinely foreign package cannot implement `AgentDispatch` at all (the
    * actual round two BLOCKER: an anonymous `AgentDispatch` a node body defines and instantiates
    * itself, never touching a real `Caps`, still minting a genuine `Judged`), is proved separately in
    * `ConsumerBoundarySpec` (`test/ConsumerBoundarySpec.scala`), a spec file physically declared
    * `package com.example.consumer` so the same call-site splicing that traps this section inside
    * `in.rcard.litterbox` traps that one inside a package this library does not own.
    *
    * Every negative snippet below has a positive twin identical in every line except the one that
    * fabricates: the `Node` shape, its `cost`, its declared result type, all held constant, so the
    * only variable between a snippet that fails and one that succeeds is the fabrication itself.
    * Without that twin, a negative test proves nothing: a typo anywhere in the snippet (a misspelled
    * `Node` field, a missing import) also makes it fail to compile, for a completely wrong reason,
    * and `assertDoesNotCompile` alone cannot tell the two apart. `typeCheckErrors`, not
    * `assertDoesNotCompile`, carries the negative cases specifically so the assertion can also pin
    * the REASON: each one asserts the reported message names the exact inaccessible member
    * (`mint`, the `Judged` constructor, or `review` itself), not merely that some error, any error,
    * came back.
    *
    * Round three of issue #35's review found `private[litterbox]` alone does not close the
    * foreign-IMPLEMENTOR hole (a subclass can always widen an inherited member's access); the fix is
    * `AgentDispatch` being `sealed`, with `AgentDispatchImpl` as the one file-local child every real
    * implementor extends instead (`src/Caps.scala` carries the full guarantee and its residual
    * limits, stated once there). That change reaches the snippets below too: a snippet in THIS file
    * that tries `extends AgentDispatch` directly is now also a different-file subclass of a sealed
    * trait, so it fails for that reason as well as, or instead of, whatever it originally set out to
    * prove; the tests further down that need a working library-side implementation
    * (`AgentDispatch`'s `review` being `final`, `dispatchReview` being overridable) extend
    * `AgentDispatchImpl` instead, and a dedicated test proves the direct-`extends`-`AgentDispatch`
    * route is genuinely closed even for library code in a different file.
    */

  it should "refuse to typecheck a node body that fabricates a Judged by calling AgentDispatch.Judged.mint directly" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |val node: Node[Unit, AgentDispatch.Judged[DispatchOutcome]] = Node(
        |  name = "fabricator",
        |  cost = Cost.OneDispatch,
        |  timeout = Timeout.Unbounded,
        |  probe = _ => None,
        |  run = _ => NodeOutcome.Done(AgentDispatch.Judged.mint(DispatchOutcome.Done))
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("mint")
    messages should include("private[AgentDispatch]")
  }

  it should "refuse to typecheck a node body that fabricates a Judged by subclassing or directly constructing it" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |val node: Node[Unit, AgentDispatch.Judged[DispatchOutcome]] = Node(
        |  name = "fabricator",
        |  cost = Cost.OneDispatch,
        |  timeout = Timeout.Unbounded,
        |  probe = _ => None,
        |  run = _ => NodeOutcome.Done(new AgentDispatch.Judged[DispatchOutcome](DispatchOutcome.Done) {})
        |)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    // Two separate reasons stack here, `sealed` and the `private[AgentDispatch]` constructor (see
    // `AgentDispatch.Judged`'s own scaladoc on why the constructor narrowing is the one that
    // actually closes the gap `sealed` alone leaves open); asserting on the constructor message
    // specifically is what proves the narrower, load bearing guard is doing real work here, not
    // merely riding on `sealed` rejecting an out of file subclass for an unrelated reason.
    messages should include("constructor Judged")
    messages should include("private[AgentDispatch]")
  }

  it should "typecheck the analogous node body that mints its Judged the only legitimate way, by dispatching through review" in {
    // The positive control every negative snippet above is measured against (RFC #26 decision 7):
    // same `Node` shape, same declared result type, same `cost`, and the one line that differs is
    // exactly the line each negative test above also varies. If this failed to compile too, the
    // negative tests would be passing on a shared typo rather than on the fabrication each claims
    // to catch.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import in.rcard.litterbox.Caps.given
        |
        |val node: Node[Unit, AgentDispatch.Judged[DispatchOutcome]] = Node(
        |  name = "reviewer",
        |  cost = Cost.OneDispatch,
        |  timeout = Timeout.Unbounded,
        |  probe = _ => None,
        |  run = _ => NodeOutcome.Done(summon[AgentDispatch].review("prompt", "review-file"))
        |)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  it should "refuse to typecheck a library-side subclass of AgentDispatchImpl overriding review, while overriding dispatchReview instead still typechecks" in {
    // `review` is `final` precisely so no implementation, decorator (`Runner.charging` above) or
    // hostile node author can skip the real dispatch and hand back a `Judged` it invented; this is
    // the guard on the OTHER side of the trait from the two tests above, the mint site rather than
    // the call site. `dispatchReview` is the one seam `AgentDispatch` actually leaves open, so its
    // override is the positive twin proving the rejection above is `final` doing its job, not some
    // unrelated typo in the subclass body.
    //
    // Extends `AgentDispatchImpl`, not `AgentDispatch` directly (round three of issue #35's review:
    // `AgentDispatch` is `sealed`, extendable only from `Caps.scala` itself, so a snippet run from
    // here, a different file, could not even name it in an `extends` clause without failing for that
    // reason FIRST, which would make the assertion below prove nothing about `final`).
    // `AgentDispatchImpl` is the file-local escape hatch `LiveAgentDispatch`/`Runner.charging`/
    // `TestWorld` all use for the same reason.
    val overrideReview = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |class Cheater extends AgentDispatchImpl:
        |  private[litterbox] def dispatchReview(prompt: String, reviewFile: String): DispatchOutcome =
        |    DispatchOutcome.Done
        |  override def review(prompt: String, reviewFile: String): AgentDispatch.Judged[DispatchOutcome] =
        |    review(prompt, reviewFile)
        |  def worker(
        |      role: Role,
        |      promptFile: String,
        |      patchOut: String,
        |      logFile: String,
        |      currentPatch: Option[String]
        |  ): DispatchOutcome = DispatchOutcome.Done
        |""".stripMargin
    )

    overrideReview should not be empty
    val messages = overrideReview.map(_.message).mkString("\n")
    messages should include("cannot override final member")

    // `private[litterbox]`, matching the trait's own modifier: this snippet is still LIBRARY code
    // (it runs from inside `RunnerSpec.scala`, package `in.rcard.litterbox`, see this section's own
    // header above), so it can write the exact same modifier `LiveAgentDispatch`/`Runner.charging`/
    // `TestWorld` do, and `AgentDispatchImpl` is nameable from any file in this package.
    // `ConsumerBoundarySpec` (`test/ConsumerBoundarySpec.scala`) is the twin of THIS test from
    // outside that package, where neither `AgentDispatch` nor `AgentDispatchImpl` can be named at
    // all.
    val overrideDispatchReview = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |class Honest extends AgentDispatchImpl:
        |  private[litterbox] def dispatchReview(prompt: String, reviewFile: String): DispatchOutcome =
        |    DispatchOutcome.Done
        |  def worker(
        |      role: Role,
        |      promptFile: String,
        |      patchOut: String,
        |      logFile: String,
        |      currentPatch: Option[String]
        |  ): DispatchOutcome = DispatchOutcome.Done
        |""".stripMargin
    )

    overrideDispatchReview shouldBe empty
  }

  it should "refuse to typecheck a library-side class extending the sealed AgentDispatch directly from a different file" in {
    // The other half of round three's fix: `sealed` is what actually keeps a foreign IMPLEMENTOR
    // out (see `AgentDispatch`'s own doc, `src/Caps.scala`), and that restriction applies even to
    // LIBRARY code, not only to a consumer's package, the moment the attempt is in a different file
    // than `Caps.scala`. `AgentDispatchImpl` two tests above is the escape hatch every real
    // implementor uses instead; this test is what proves the direct route it avoids is actually
    // closed, not merely unused by convention.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |class DirectSubclass extends AgentDispatch:
        |  private[litterbox] def dispatchReview(prompt: String, reviewFile: String): DispatchOutcome =
        |    DispatchOutcome.Done
        |  def worker(
        |      role: Role,
        |      promptFile: String,
        |      patchOut: String,
        |      logFile: String,
        |      currentPatch: Option[String]
        |  ): DispatchOutcome = DispatchOutcome.Done
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("sealed trait AgentDispatch")
    messages should include("different source file")
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

  // ---- issue #44: AskHuman is genuinely usable from a THIRD PARTY graph, not merely the shipped
  // ---- one, and a probe hit hands the reply TEXT on to whatever node the graph author wires next ---

  /** A node that records the exact value it received, standing in for whatever a real consumer's
    * "next node" would do with a human's reply (splice it into its own prompt, say). `Node[String,
    * Unit]`, not `Node[Machine.AskHumanReply, Unit]`: this is deliberately the SHAPE acceptance
    * criterion 3 asks for, "comment text available to the next node", so this node's own input is
    * the text alone, exactly what `AskHuman`'s own `andThen` below hands it, not the whole reply
    * value with the text still buried inside it.
    */
  private def textSinkNode(sink: mutable.ArrayBuffer[String]): Node[String, Unit] =
    Node(
      name = "consumer-next",
      cost = Cost.NoDispatch,
      timeout = Timeout.Unbounded,
      probe = _ => None,
      run = text =>
        sink += text
        NodeOutcome.Done(())
    )

  it should "let a graph independent of Machine.shippedWorkflow drive AskHuman end to end, handing a " +
    "probe hit's reply TEXT on to the graph author's own next node (issue #44 acceptance criterion 3)" in {
      // Not `Machine.shippedWorkflow`: this `Workflow` value is built here, in this spec, from
      // nothing but `Machine.AskHuman`, `Machine.AskHumanInput` and `Machine.AskHumanReply`, all
      // public (issue #44 review, MAJOR: they used to be `private[litterbox]`, reachable only from
      // inside `in.rcard.litterbox` itself, which a genuine external consumer graph can never be).
      // This spec still lives in that same package, so it cannot prove cross-package reachability by
      // itself (issue #44 review, MAJOR, round 3): that half of acceptance criterion 1 is now pinned
      // separately, in `test/ConsumerBoundarySpec.scala`, a genuinely separate compilation unit
      // (that file's own doc has the reason `RunnerSpec` cannot prove it). What THIS test proves is
      // the narrower, load-bearing FUNCTIONAL half: nothing about `AskHuman`'s own node ties it to
      // `Machine.shippedWorkflow`'s own closures, ledger or marker, so a graph that owns none of
      // those can still drive it end to end and receive the reply text criterion 3 asks for.
      val world = new TestWorld
      world.viewerLoginAnswer = Some("litter-box")
      val ownMarker = "<!-- consumer:waiting-on-a-human -->"
      world.issueCommentBodies = Map(
        4242 -> List(
          s"@litter-box (OWNER):\n$ownMarker\nwhich branch should this land on?",
          "@carol (MEMBER):\nland it on release/4.2, not main"
        )
      )
      val clock  = new FakeClock(List(0L))
      val sink   = mutable.ArrayBuffer.empty[String]
      val cur    = Machine.Cursor()
      val input = Machine.AskHumanInput(
        cur = cur,
        issue = 4242,
        marker = ownMarker,
        body = s"$ownMarker\nwhich branch should this land on?",
        kindText = "consumer-question",
        gateStatus = "n/a"
      )
      val wf: Workflow[Unit] = Workflow(
        "consumer-graph",
        start = _ =>
          Next.Goto(
            Machine.AskHuman(Config()),
            input,
            (reply: Machine.AskHumanReply) =>
              Next.Goto(textSinkNode(sink), reply.text, _ => Next.Finish(LoopExit.Success))
          )
      )

      val result = withFaulting:
        given caps: Caps            = buildCaps(world, clock)
        given ledger: Runner.Ledger = Runner.Ledger(3)
        Runner.run(wf, ())

      // A probe hit: no question comment was posted a second time, no label touched, nothing reset,
      // and the walk continued past `AskHuman` rather than parking (`NodeOutcome.Done`, not
      // `Stopped`, is what `Runner.run`'s own walk needs to keep going, `Kit.scala`'s own doc).
      result shouldBe Right(LoopExit.Success)
      world.called("gh issue comment 4242") shouldBe false
      world.called("gh issue edit") shouldBe false
      // Criterion 3, proven honestly: the NEXT node received the reply's own text, not a list of
      // author logins standing in for it.
      sink.toList shouldBe List("@carol (MEMBER):\nland it on release/4.2, not main")
  }

  it should "let a graph independent of Machine.shippedWorkflow drive AskHuman's own PROBE-MISS side " +
    "effects too, not only the benign probe-hit path above (issue #44 review, MAJOR F3, round 3: the " +
    "only consumer-facing test before this one exercised the probe hit alone, so a reader of this " +
    "file had no test showing what a probe MISS actually does to a consumer's own issue)" in {
      // No comment thread at all on this issue: `AskHuman`'s own probe (`AskHumanProbe`'s doc,
      // `src/Machine.scala`) reads no marker, answers a genuine miss, and `Runner.step` falls through
      // to `askHumanRun`. `AskHuman`'s own scaladoc now states plainly (issue #44 review F3) that this
      // path hard resets the working tree, flips labels and emits a `PARK` status event, none of which
      // is "it posts a comment and waits" alone; this test is where a consumer-minded reader can see
      // all three actually happen, from outside `Machine.shippedWorkflow` entirely, the same way the
      // probe-hit test above proves the benign path.
      val world = new TestWorld
      world.viewerLoginAnswer = Some("litter-box")
      world.issueCommentBodies = Map.empty
      val clock  = new FakeClock(List(0L))
      val cur    = Machine.Cursor()
      val ownMarker = "<!-- consumer:waiting-on-a-human -->"
      val input = Machine.AskHumanInput(
        cur = cur,
        issue = 4343,
        marker = ownMarker,
        body = s"$ownMarker\nwhich branch should this land on?",
        kindText = "consumer-question",
        gateStatus = "n/a"
      )
      val wf: Workflow[Unit] = Workflow(
        "consumer-graph-probe-miss",
        start = _ => Next.Goto(Machine.AskHuman(Config()), input, (_: Machine.AskHumanReply) => Next.Finish(LoopExit.Success))
      )

      val result = withFaulting:
        given caps: Caps            = buildCaps(world, clock)
        given ledger: Runner.Ledger = Runner.Ledger(3)
        Runner.run(wf, ())

      // A probe miss: the walk stops right here (`NodeOutcome.Stopped`, never reaching the `andThen`
      // above), and every side effect the node's own scaladoc now names actually lands.
      result shouldBe Right(LoopExit.Parked)
      world.called("gh issue comment 4343") shouldBe true
      world.postedIssueComments.last shouldBe (4343 -> input.body)
      world.called("gh issue edit 4343 --add-label parked --remove-label in-progress") shouldBe true
      world.called("git reset --hard origin/main && git clean -fd") shouldBe true
  }
