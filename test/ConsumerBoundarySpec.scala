package com.example.consumer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The genuine CONSUMER half of issue #35's "a self grading agent does not compile" guarantee, round
  * three of review. `RunnerSpec`'s own `AgentDispatch.Judged` section (`test/RunnerSpec.scala`)
  * proves the mint site is closed from inside `in.rcard.litterbox` itself; it cannot prove the
  * boundary RFC #26 decision 7 actually promises, that a CONSUMER, in a package this library does
  * not own, cannot mint one either. It cannot prove that because
  * `scala.compiletime.testing.typeCheckErrors` type-checks its string argument spliced in at the call
  * site's own lexical position (same imports, same enclosing package), not as a fresh, independently
  * packaged compilation unit. Confirmed empirically while drafting this file: a snippet handed to
  * `typeCheckErrors` from `RunnerSpec.scala` resolves `Caps` and friends unqualified with NO import at
  * all, and a `package foo { ... }` clause written inside the string itself is flatly rejected ("this
  * kind of statement is not allowed here"), the same restriction ordinary Scala places on a `package`
  * clause appearing inside a method body. So a snippet run from `RunnerSpec.scala` is always,
  * unavoidably, LIBRARY code, no matter what it claims to be.
  *
  * This file is the fix for that: it is a genuinely separate compilation unit, physically declared
  * `package com.example.consumer`, standing in for any package this library does not own. Every
  * `typeCheckErrors` call below inherits ITS package, com.example.consumer, because the same
  * call-site-splicing behaviour that trapped `RunnerSpec.scala` inside `in.rcard.litterbox` traps this
  * file inside `com.example.consumer` just as reliably, which is exactly the property this file needs.
  *
  * Two things round three of issue #35's review found broken about the round two version of this
  * file, both fixed here.
  *
  * First, every snippet, positive and negative alike, now sits at BARE TOP LEVEL (a top level
  * `given`, a top level `def`, a top level `class`), never nested inside a `Node(...)` constructor
  * call. That nesting was tried first and, empirically, made `typeCheckErrors` report ZERO errors for
  * a fabrication snippet that demonstrably fails to compile for real (verified by pasting the
  * identical snippet into an ordinary top level `.scala` file and running `scala-cli compile`, where
  * it fails exactly as expected). That gap is a quirk of `typeCheckErrors`'s own speculative retyping
  * around a case class constructor call whose parameter type is a context function (`Node`'s
  * `run: I => (Caps, Fault) ?=> NodeOutcome[O]`), not a gap in the guarantee itself, and the bare top
  * level shape below does not trip it.
  *
  * Second, the two negatives that used to write `dispatchReview` as `protected` passed for the WRONG
  * reason: `protected` is itself a weaker access privileges violation against the
  * `private[litterbox]` abstract member, so those tests were really proving "I wrote the wrong
  * keyword", not "a foreign package cannot implement this capability". Both negatives below instead
  * OMIT any access modifier (`public`, the natural thing an unaware consumer would write), and assert
  * on the message a `sealed trait` violation actually produces, never on "weaker access privileges".
  *
  * The positive control and its two `AgentDispatch`-implementing negative twins are IDENTICAL except
  * for the one expression each fabricates: same import, same declared result type
  * (`AgentDispatch.Judged[Verdict]`), same trailing `.review(...).map(...)` chain. The only thing that
  * varies is whether the `AgentDispatch` on the left of `.review` was legitimately obtained (an
  * implicit parameter this package never constructs) or freshly fabricated by this package itself.
  */
class ConsumerBoundarySpec extends AnyFlatSpec with Matchers:

  it should "typecheck a consumer's own review node that only ever calls AgentDispatch.review on an AgentDispatch it was handed, never implements AgentDispatch" in {
    // The RFC #26 sketch itself (`MyReview`): a consumer writes their own prompt, their own parsing,
    // and is still bound to the real dispatch, because `review` is the only way to reach a `Judged`
    // and `AgentDispatch` is not something this package can implement (see the negative tests below).
    // This is the positive control every negative test in this file is measured against: the
    // `AgentDispatch` here is an implicit parameter this snippet receives, never one it builds.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |def myReviewNode(using AgentDispatch): AgentDispatch.Judged[Verdict] =
        |  summon[AgentDispatch].review("prompt", "review-file").map(_ => Verdict.Approve)
        |""".stripMargin
    )

    errors shouldBe empty
  }

  it should "refuse to typecheck a consumer's own class implementing AgentDispatch, with no access modifier written at all" in {
    // The natural way a consumer, unaware of the boundary, would try to supply their own dispatch:
    // subclass `AgentDispatch` the ordinary OOP way and override what looks like the one abstract
    // member, writing no access modifier at all (the default, `public`, is the widest one exists and
    // the one an unaware author reaches for first). `AgentDispatch` is `sealed`
    // (`src/Caps.scala`), so no access modifier this class could write closes the gap; the class
    // itself cannot exist, because `sealed` restricts EXTENDING the trait, not merely satisfying one
    // of its members, to code in the same file as the trait's own declaration.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |class ForeignDispatch extends AgentDispatch:
        |  def dispatchReview(prompt: String, reviewFile: String): DispatchOutcome =
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
    messages should not include "weaker access privileges"
  }

  it should "refuse to typecheck a consumer's own anonymous AgentDispatch minting a genuine Judged with no real dispatch behind it, with no access modifier written at all" in {
    // The exact shape of the round two BLOCKER finding, replayed with round three's fix in place: an
    // anonymous `AgentDispatch`, defined and instantiated entirely by consumer code, that never
    // touches a real `Caps`-wired capability and still calls `.review(...)` on itself. Before round
    // three, `dispatchReview` being merely `private[litterbox]` did not stop this: a subclass can
    // always WIDEN an inherited member's access, so this package could declare its own
    // `dispatchReview` `public` and mint a genuine `Judged[Verdict]` carrying `Verdict.Approve`, the
    // self grading agent issue #35 says must not compile. `sealed` closes it at the one place a
    // widening trick cannot reach: this package cannot write the `extends AgentDispatch` clause at
    // all, so it cannot instantiate one, so it never reaches `review`.
    //
    // Identical to the positive control above except for this one expression: `summon[AgentDispatch]`
    // there is replaced by a freshly fabricated anonymous `AgentDispatch` here.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |
        |def fabricated: AgentDispatch.Judged[Verdict] =
        |  (new AgentDispatch:
        |     def worker(
        |         role: Role,
        |         promptFile: String,
        |         patchOut: String,
        |         logFile: String,
        |         currentPatch: Option[String]
        |     ): DispatchOutcome = DispatchOutcome.Done
        |     def dispatchReview(prompt: String, reviewFile: String): DispatchOutcome =
        |       DispatchOutcome.Done
        |  ).review("prompt", "review-file").map(_ => Verdict.Approve)
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("sealed trait AgentDispatch")
    messages should include("different source file")
    messages should not include "weaker access privileges"
  }

  it should "typecheck a consumer's own graph naming Machine.AskHuman, Machine.AskHumanInput and " +
    "Machine.AskHumanReply directly and constructing a Workflow from them (issue #44 review MAJOR, " +
    "round 3: the naming half of acceptance criterion 1 had no pin outside in.rcard.litterbox itself)" in {
      // `RunnerSpec`'s own `AskHuman` section (`test/RunnerSpec.scala`) drives this node end to end
      // through `Runner.run`, proving the FUNCTIONAL half of criterion 1; it lives inside
      // `in.rcard.litterbox` itself, so, exactly like the `AgentDispatch` proofs above, it cannot
      // prove the PACKAGE BOUNDARY half, that a foreign package can even write this graph down. This
      // is that proof, following the same top-level-snippet shape every other check in this file uses.
      //
      // Deliberately NOT a `Runner.run` call, and deliberately not attempted (issue #44 review MAJOR:
      // do not widen `Runner.Ledger`'s constructor to paper over this): `Ledger`'s own constructor
      // stays `private[litterbox]` (RFC #26 decision 9, budget ownership belongs to the runner), so a
      // consumer can name and compose this exact `Workflow` today but cannot yet construct a `Ledger`
      // to run it outside the library. That gap is left to the publishing tickets (#41, #43), not this
      // one; `AskHuman`'s own scaladoc (`src/Machine.scala`) states the same boundary in the library's
      // own words.
      val errors = scala.compiletime.testing.typeCheckErrors(
        """
          |import in.rcard.litterbox._
          |
          |val marker = "<!-- consumer:waiting-on-a-human -->"
          |val input = Machine.AskHumanInput(
          |  cur = Machine.Cursor(),
          |  issue = 4242,
          |  marker = marker,
          |  body = s"$marker\nwhich branch should this land on?",
          |  kindText = "consumer-question",
          |  gateStatus = "n/a"
          |)
          |val next: Machine.AskHumanReply => Next = _ => Next.Finish(LoopExit.Success)
          |val wf: Workflow[Unit] = Workflow(
          |  "consumer-graph",
          |  start = (_: Unit) => Next.Goto(Machine.AskHuman(Config()), input, next)
          |)
          |""".stripMargin
      )

      errors shouldBe empty
    }

  it should "refuse to typecheck a consumer constructing LiveAgentDispatch directly" in {
    // Round three finding 2: `LiveAgentDispatch` (`src/Live.scala`) used to be a public class with a
    // public constructor, so a consumer needed no implementation of `AgentDispatch` at all, only the
    // constructor arguments, to obtain a genuine, dispatch-capable instance and mint real `Judged`
    // values from it. The constructor is now `private[litterbox]`, so this package cannot name it
    // either; the operator-facing `REVIEW_CMD` stub seam stays reachable only through `Main.scala`,
    // inside the library's own package.
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
        |import in.rcard.litterbox._
        |import java.nio.file.Paths
        |
        |val dispatch = new LiveAgentDispatch(Paths.get("."), Paths.get("."), None, 1, None, None, Some("true"))
        |""".stripMargin
    )

    errors should not be empty
    val messages = errors.map(_.message).mkString("\n")
    messages should include("constructor LiveAgentDispatch")
  }
