package in.rcard.litterbox

import in.rcard.litterbox.testsupport.RepoTree

import java.nio.file.Files
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the one code example README.md offers a consumer for `Reply.splice`, the render half of the
  * reply protocol (`src/Reply.scala`, `src/Caps.scala`).
  *
  * The example is the thing a consumer copies, so it has to demonstrate the split it is teaching
  * rather than break it. `Caps.GitHub.issueComments` answers `None` when the `gh` read itself failed
  * and `Some(Nil)` when it succeeded and there simply were no comments; collapsing the two with
  * `.getOrElse(Nil)` tells a fixer an empty thread when the harness in fact could not see it, which
  * is the same fault issue #28 review finding 7 closed inside the shipped loop. A README that shows
  * that collapse teaches every reader to reopen it.
  *
  * The example's own comment also used to claim the rendered block arrives fenced. It does not:
  * `Reply.splice` only defuses a forged copy of the `<untrusted-comments>` fence tag inside the data,
  * the genuine fence lives in the prompt skeleton under `resources/`, and a reader who believed the
  * comment would ship untrusted text with no boundary marker at all, the exact injection surface
  * issue #27 introduced the fence for.
  */
class ReadmeReplyExampleSpec extends AnyFlatSpec with Matchers:

  private def readmeText: String =
    val path = RepoTree
      .file("README.md")
      .getOrElse(fail("could not locate README.md by walking up from the JVM cwd"))
    Files.readString(path)

  private def replyExample: String =
    val readme = readmeText
    val start  = readme.indexOf("val commentsSlot")
    start should be >= 0
    val end = readme.indexOf("```", start)
    end should be >= 0
    readme.substring(start, end)

  "the Reply.splice example" should "not collapse a failed gh read into an empty thread with getOrElse" in {
    val example = replyExample
    example should not include "getOrElse(Nil)"
    example should include("None")
    example should include("Some(entries)")
  }

  it should "not claim the rendered block arrives fenced" in {
    replyExample should not include "fenced"
  }
