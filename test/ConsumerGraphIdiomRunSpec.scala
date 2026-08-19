package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import Caps.given

/** The functional half of the proof `test/ConsumerGraphIdioms.scala` starts. That file is the genuine
  * CONSUMER half, a real, separately compiled top level file declared `package com.example.consumer`,
  * proving every graph authoring idiom it declares compiles from outside `in.rcard.litterbox`; this
  * file is the other half, following the identical split `ConsumerGraphSpec`/`ConsumerGraphRunSpec`
  * already draw for the shipped boundary proof (`test/ConsumerGraphRunSpec.scala`'s own doc has the
  * reasoning restated here): `TestWorld` (`test/Recorder.scala`) is code belonging to the library,
  * reachable only from within this package (RFC #26 decision 14), so driving any of those foreign
  * package graphs through `Machine.runOnce` against a real `TestWorld` has to happen from a file
  * living inside `in.rcard.litterbox` itself, importing the already compiled graphs rather than
  * rebuilding them.
  *
  * Every graph asserted on below is IMPORTED, never re declared: the value that ran is the exact same
  * value `ConsumerGraphIdioms.scala`'s own top level `val` built, from outside this package, through
  * `LitterBox.graph`. That is what makes this a pin on the idiom actually working, not only on this
  * file's own understanding of what the idiom should do.
  */
class ConsumerGraphIdiomRunSpec extends AnyFlatSpec with Matchers:

  private def runOnce(world: TestWorld, graph: LoopGraph, cfg: Config = Config()): LoopExit =
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
      world.clock,
      world.logger
    )

  private def runOnce(world: TestWorld, n: Int, graph: LoopGraph, cfg: Config): LoopExit =
    Machine.runOnce(n, graph)(using
      cfg,
      world.github,
      world.git,
      world.agents,
      world.gates,
      world.hostGates,
      world.status,
      world.notifier,
      world.fs,
      world.clock,
      world.logger
    )

  "Machine.runOnce" should "walk com.example.consumer.classMemberGraph, a class member node referenced unqualified, in order" in {
    val world = new TestWorld
    val exit  = runOnce(world, com.example.consumer.classMemberGraph)

    exit shouldBe LoopExit.Success
    val startIdx  = world.logLines.indexWhere(_.contains("idiom class member Start ran"))
    val finishIdx = world.logLines.indexWhere(_.contains("idiom class member Finish ran"))
    startIdx should be >= 0
    finishIdx should be >= 0
    startIdx should be < finishIdx
  }

  it should "walk com.example.consumer.listEmptyGraph, a lone entry node whose one edge ends the run" in {
    val world = new TestWorld
    val exit  = runOnce(world, com.example.consumer.listEmptyGraph)

    exit shouldBe LoopExit.Success
    world.logLines.exists(_.contains("idiom empty transition list lone node ran")) shouldBe true
  }

  it should "walk com.example.consumer.namedArgOrderGraph, whose Plan named entry and edges out of declaration order" in {
    val world = new TestWorld
    val exit  = runOnce(world, com.example.consumer.namedArgOrderGraph)

    exit shouldBe LoopExit.Success
    val aIdx = world.logLines.indexWhere(_.contains("idiom named argument order A ran"))
    val bIdx = world.logLines.indexWhere(_.contains("idiom named argument order B ran"))
    aIdx should be >= 0
    bIdx should be >= 0
    aIdx should be < bIdx
  }

  it should "walk com.example.consumer.localTransitionGraph, whose Edge was a local val bound in the same block as the LitterBox.graph call" in {
    val world = new TestWorld
    val exit  = runOnce(world, com.example.consumer.localTransitionGraph)

    exit shouldBe LoopExit.Success
    val aIdx = world.logLines.indexWhere(_.contains("idiom local transition A ran"))
    val bIdx = world.logLines.indexWhere(_.contains("idiom local transition B ran"))
    aIdx should be >= 0
    bIdx should be >= 0
    aIdx should be < bIdx
  }

  it should "walk com.example.consumer.wildcardImportGraph, whose nodes were reached bare through a wildcard import, and honour dispatchBudget: Config => Int reading cfg.repairBudget" in {
    val world = new TestWorld
    val exit  = runOnce(world, com.example.consumer.wildcardImportGraph, Config(repairBudget = 3))

    exit shouldBe LoopExit.Success
    val startIdx  = world.logLines.indexWhere(_.contains("idiom wildcard import Start ran"))
    val finishIdx = world.logLines.indexWhere(_.contains("idiom wildcard import Finish ran"))
    startIdx should be >= 0
    finishIdx should be >= 0
    startIdx should be < finishIdx
  }

  it should "walk com.example.consumer.packageQualifiedGraph, whose nodes were named by their full package qualified path" in {
    val world = new TestWorld
    val exit  = runOnce(world, com.example.consumer.packageQualifiedGraph)

    exit shouldBe LoopExit.Success
    val startIdx  = world.logLines.indexWhere(_.contains("idiom package qualified Start ran"))
    val finishIdx = world.logLines.indexWhere(_.contains("idiom package qualified Finish ran"))
    startIdx should be >= 0
    finishIdx should be >= 0
    startIdx should be < finishIdx
  }

  it should "walk com.example.consumer.reviewedGraph end to end, genuinely dispatching a review before the Guard.RequiresReview node runs" in {
    // The single most important guarantee in this suite, and, before this file, pinned only as a
    // typeCheckErrors snippet (`ConsumerGraphSpec`'s own positive test 4): this is the first proof
    // that a foreign package's own review gated graph not only type checks but actually dispatches a
    // real review, through `world.agents`, before its own guarded node ever runs.
    val world = new TestWorld
    val exit  = runOnce(world, com.example.consumer.reviewedGraph)

    exit shouldBe LoopExit.Success
    world.callCount("dispatch REVIEW") shouldBe 1
    val pickIdx   = world.logLines.indexWhere(_.contains("idiom reviewed Pick ran"))
    val reviewIdx = world.logLines.indexWhere(_.contains("idiom reviewed Review ran"))
    val openPrIdx = world.logLines.indexWhere(_.contains("idiom reviewed OpenPr ran"))
    pickIdx should be >= 0
    reviewIdx should be >= 0
    openPrIdx should be >= 0
    pickIdx should be < reviewIdx
    reviewIdx should be < openPrIdx
  }

  it should "run com.example.consumer.faultStartInputGraph's own node normally when startInput's ambient tick number is not negative" in {
    val world = new TestWorld
    val exit  = runOnce(world, 1, com.example.consumer.faultStartInputGraph, Config())

    exit shouldBe LoopExit.Success
    world.logLines.exists(_.contains("idiom fault start input node ran")) shouldBe true
  }

  it should "abort com.example.consumer.faultStartInputGraph to LoopExit.InfraFault through startInput's own fault.raise when the ambient tick number is negative, never reaching the node" in {
    val world = new TestWorld
    val exit  = runOnce(world, -1, com.example.consumer.faultStartInputGraph, Config())

    exit shouldBe LoopExit.InfraFault
    world.logLines should contain("idiom negative tick number")
    world.logLines.exists(_.contains("idiom fault start input node ran")) shouldBe false
  }
