package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import Script.*

/** Drives `test/ReviewFixLoopExample.scala`, the worked consumer example, through `TestWorld`.
  *
  * This is the half of the example that makes it worth shipping. An example that is only compiled
  * proves the kit's signatures still line up; it proves nothing about the loop the example claims to
  * describe, so its whole selling point, a REVIEW/FIX cycle that fans out one fixer dispatch per
  * finding and stops after a bounded number of rounds, would be prose asserted by nobody. Every
  * claim the example's own header makes is pinned below against the recorder buffers `TestWorld`
  * keeps, never against a log line reading like a summary of itself.
  *
  * This file lives inside `in.rcard.litterbox` while the example it drives lives in
  * `com.example.reviewfix`, the same split `ConsumerGraphIdioms`/`ConsumerGraphIdiomRunSpec` already
  * draw and for the same reason: `TestWorld` (`test/Recorder.scala`) is library-side code, reachable
  * only from within this package (RFC #26 decision 14), so the example has to be IMPORTED here
  * rather than restated. The graph asserted on below is the exact value the example's own top level
  * `val graph` built, from outside this package, through `LitterBox.graph`.
  */
class ReviewFixLoopExampleSpec extends AnyFlatSpec with Matchers:

  // Read back off `Config()` rather than written out, the same discipline `ScenarioSpec` follows:
  // these paths track whatever the reference default becomes instead of pinning a second copy of it.
  private val logDir = Config().logDir

  /** Distinct numstat rows per dispatch, never one shared constant: `TestWorld`'s `FakeGit` keys
    * everything it applies by patch PATH, so identical content would still be distinguishable, but a
    * different touched file per round is what makes a misrouted patch legible in a failure message.
    */
  private def patchTouching(path: String): WorkerScript = WorkerScript.Produces(s"1\t0\t$path")

  private def reviewDispatches(w: TestWorld): List[String] =
    w.calls.filter(_.startsWith("dispatch REVIEW")).toList

  private def fixDispatches(w: TestWorld): List[String] =
    w.calls.filter(_.startsWith("dispatch FIX")).toList

  // ---- the happy path: approved on round 1 ------------------------------------------------

  "The review/fix example graph" should "open a PR and exit Success when the reviewer approves on round 1" in {
    val w = TestWorld()
    w.implScript = patchTouching("src/Slice.scala")
    w.reviewScripts = List(ReviewScript.Says(approveReview))

    val exit = w.runGraph(com.example.reviewfix.graph)

    exit shouldBe LoopExit.Success
    w.callCount("dispatch IMPL") shouldBe 1
    reviewDispatches(w) shouldBe List(s"dispatch REVIEW reviewFile=$logDir/issue-999-r1.review.md")
    // The cycle never entered its fixing half: an approval on round 1 is the one path through this
    // graph on which the fixer is never dispatched at all.
    fixDispatches(w) shouldBe Nil
    w.logged("review round 1: 0 finding(s)") shouldBe true
    w.pushedBranches shouldBe List("us-999")
    w.commitMessages shouldBe List("feat: #999\n\nCloses #999")
    w.called("gh pr create --head us-999") shouldBe true
    w.prBodies.head should include("A cold reviewer found nothing left to fix.")
    w.logged("opened PR #123 for issue #999") shouldBe true
  }

  // ---- the cycle: two findings fan out to two fixers, round 2 approves ---------------------

  it should "dispatch one fixer per finding and re-review the result on the next round" in {
    val w = TestWorld()
    w.implScript = patchTouching("src/Slice.scala")
    w.reviewScripts = List(
      ReviewScript.Says(
        "FINDING: Slice.scala dereferences the parsed value without a null check\n" +
          "FINDING: SliceSpec asserts the happy path only, never the error branch"
      ),
      ReviewScript.Says(approveReview)
    )
    w.fixScripts = List(patchTouching("src/Slice.scala"), patchTouching("test/SliceSpec.scala"))

    val exit = w.runGraph(com.example.reviewfix.graph)

    exit shouldBe LoopExit.Success

    // The fan-out, observed rather than assumed: TWO fixer dispatches for the two findings of round
    // 1, tagged `r1-f0`/`r1-f1` by the example's own artifact naming, and the second one SEEDED with
    // the first one's patch. That last field is the example's cumulative-patch claim: a fixer that
    // started from the pristine tree would carry `currentPatch=` empty here, or carry the IMPLEMENT
    // patch a second time, and either reading would mean the second finding's fix silently discarded
    // the first one's work.
    fixDispatches(w) shouldBe List(
      s"dispatch FIX promptFile=$logDir/issue-999-r1-f0.fix.prompt.txt " +
        s"patchOut=$logDir/issue-999-r1-f0.patch logFile=$logDir/issue-999-r1-f0.fix.log " +
        s"currentPatch=$logDir/issue-999.patch",
      s"dispatch FIX promptFile=$logDir/issue-999-r1-f1.fix.prompt.txt " +
        s"patchOut=$logDir/issue-999-r1-f1.patch logFile=$logDir/issue-999-r1-f1.fix.log " +
        s"currentPatch=$logDir/issue-999-r1-f0.patch"
    )

    // The cycle, observed rather than assumed: a SECOND review dispatch, against the round 2 review
    // file, which only a `reviewCycle(gated, round + 1)` edge taken after the fixer can produce.
    reviewDispatches(w) shouldBe List(
      s"dispatch REVIEW reviewFile=$logDir/issue-999-r1.review.md",
      s"dispatch REVIEW reviewFile=$logDir/issue-999-r2.review.md"
    )
    w.logged("review round 1: 2 finding(s)") shouldBe true
    w.logged("review round 2: 0 finding(s)") shouldBe true

    // Each fixer is prompted with ITS OWN finding and no other: the example splits the review into
    // one dispatch per finding precisely so a fixer never sees a list it can pick from.
    w.files(s"$logDir/issue-999-r1-f0.fix.prompt.txt") should include(
      "Slice.scala dereferences the parsed value without a null check"
    )
    w.files(s"$logDir/issue-999-r1-f0.fix.prompt.txt") should not include
      "SliceSpec asserts the happy path only"

    // The gate runs once after IMPLEMENT and once after the round 1 fixers, never between the two
    // fixers of a single round.
    w.callCount("gate FAST") shouldBe 2
    w.prBodies.head should include("A cold reviewer found nothing left to fix.")
  }

  // ---- the round cap: three rounds, then the needs-human PR --------------------------------

  it should "stop after exactly MaxRounds review rounds and open the needs-human PR" in {
    val w = TestWorld()
    w.implScript = patchTouching("src/Slice.scala")
    // Four scripted answers for three rounds. The fourth exists so that a graph running one round
    // too many would still get a well-formed review to parse and would fail this test on the round
    // COUNT, the fact under test, rather than on the example's fail-safe raising an infra fault
    // against an empty review file and reporting an exit code that says nothing about the cap.
    w.reviewScripts = List.fill(4)(ReviewScript.Says("FINDING: the error branch is still untested"))
    w.fixScripts = List(patchTouching("test/SliceSpec.scala"), patchTouching("test/SliceSpec.scala"))

    val exit = w.runGraph(com.example.reviewfix.graph)

    exit shouldBe LoopExit.NeedsHuman
    reviewDispatches(w) shouldBe List(
      s"dispatch REVIEW reviewFile=$logDir/issue-999-r1.review.md",
      s"dispatch REVIEW reviewFile=$logDir/issue-999-r2.review.md",
      s"dispatch REVIEW reviewFile=$logDir/issue-999-r3.review.md"
    )
    // Two fixing rounds, not three: round 3's findings are what END the cycle, so the fixer is never
    // dispatched a third time and the round 3 findings travel to a human instead.
    w.callCount("dispatch FIX") shouldBe 2
    w.called(s"dispatch FIX promptFile=$logDir/issue-999-r3-f0.fix.prompt.txt") shouldBe false

    // The PR is still opened, carrying the work done so far and saying plainly why it stopped.
    w.pushedBranches shouldBe List("us-999")
    w.prBodies.head should include("ran out after 3 rounds with findings still open")
    w.prBodies.head should include("NEEDS HUMAN")
  }
