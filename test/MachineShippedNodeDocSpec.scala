package in.rcard.litterbox

import in.rcard.litterbox.testsupport.RepoTree

import java.nio.file.Files
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the "which shipped nodes are public" claim to be told once, consistently, everywhere it is
  * made (issue #68 review finding
  * only-public-shipped-node-claim-is-false-askhuman-is-one). `Machine.AskHuman` has been public since
  * issue #44; issue #68 adds `Machine.Gate` as a second public shipped node, not the first, and the
  * five sites this spec pins (`src/Machine.scala`'s own `Gate`/`GateInput` scaladoc, `README.md`'s
  * "Write your own loop" section, and `ARCHITECTURE.md`'s kit paragraph) all have to say that, or a
  * reader of one of them walks away believing `Gate` is the only public shipped node and reimplements
  * `AskHuman` instead of composing it, exactly the documentation drift the issue's own RISKS section
  * warns against.
  *
  * Reads the real files rather than typing the claim out a third time: a source of truth this spec
  * disagrees with is exactly the drift this spec exists to catch, and reading it back is what makes
  * that catch automatic instead of relying on a reviewer noticing the words changed underneath a
  * scaladoc that still cites them.
  */
class MachineShippedNodeDocSpec extends AnyFlatSpec with Matchers:

  private def readRepoFile(relative: String): String =
    val path = RepoTree
      .file(relative)
      .getOrElse(fail(s"could not locate $relative by walking up from the JVM cwd"))
    Files.readString(path)

  "Machine.scala" should "not claim Gate is the only public shipped node, since AskHuman already is" in {
    val src = readRepoFile("src/Machine.scala")
    src should not include "and the only shipped node that is"
    src should include("like `AskHumanInput` and unlike every other shipped node's input type")
  }

  it should "name AskHuman as the public shipped node it stays silent about otherwise, right where Gate's own doc enumerates the private siblings" in {
    val src = readRepoFile("src/Machine.scala")
    // The paragraph opens with this exact sentence (Gate's own doc); AskHuman must be named
    // somewhere between it and the next paragraph break, not left out of "the whole set".
    val start = src.indexOf(
      "Why each sibling node stays private, since the decision this issue asks for is the whole set"
    )
    start should be >= 0
    val end        = src.indexOf("\n\n", start)
    end should be >= 0
    val paragraph = src.substring(start, end)
    paragraph should include("AskHuman")
    paragraph should include("public")
  }

  "README.md" should "not tell a reader Gate is the one shipped node this library exposes" in {
    val readme = readRepoFile("README.md")
    readme should not include "the one shipped node this library exposes"
    readme should not include "why it is the one shipped node that is public"
    readme should include("AskHuman")
  }

  "ARCHITECTURE.md" should "not claim Gate is the only shipped node made public, or that every remaining sibling stays private" in {
    val arch = readRepoFile("ARCHITECTURE.md")
    arch should not include "One shipped node is the exception, and deliberately only one"
    arch should not include "why every remaining shipped node stays private"
    arch should include("AskHuman")
  }
