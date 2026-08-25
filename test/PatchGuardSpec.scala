package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The patch guard, driven through its own interface rather than through a whole tick.
  *
  * Two halves, and the split is the reason this file exists at all. [[PatchGuard.rule]] is pure, so
  * the matching and the size cap are asserted with a table of strings and no fakes in sight;
  * [[PatchGuard.stage]] is the seam that turns a ruling into world changes, so it is asserted
  * through a `TestWorld` on what actually reached the index and what was staged in place of a
  * refused patch.
  *
  * The glob assertions moved here from `SettingsSpec` when the matcher moved out of `Settings`: the
  * subject of a glob test is the guard's decision, and it now sits next to the code that makes it.
  * What stayed behind there is the shape of the LIST (the reference floor, a consumer's entries on
  * top of it), which is config layering and not a decision about a patch.
  */
class PatchGuardSpec extends AnyFlatSpec with Matchers:

  /** The guard reads `git apply --numstat` output rather than a list of paths, so every assertion
    * here goes through that exact shape ("<added>\t<deleted>\t<path>", see `NumstatRow.parse`).
    * Anything that broke the numstat parsing would otherwise leave the guard waving patches through
    * on a green glob test.
    */
  private def numstat(paths: String*): String =
    paths.map(p => s"1\t0\t$p").mkString("\n")

  private def cfgWith(protect: List[String], maxPatchBytes: Long = 1_000_000L): Config =
    Config(protect = protect, maxPatchBytes = maxPatchBytes)

  // ===============================================================================================
  // 1. touchesProtected: the glob semantics
  // ===============================================================================================

  behavior of "PatchGuard.touchesProtected"

  it should "match a double-star entry across directory levels" in {
    // The case issue #3 names explicitly: `.github` followed by `/` and a double star has to cover
    // a workflow file nested two levels down, or the agent can rewrite CI and grade its own work.
    PatchGuard.touchesProtected(
      List(".github/**"),
      numstat(".github/workflows/ci.yml")
    ) shouldBe true
  }

  it should "not let a single-star entry cross a directory separator" in {
    // JDK glob semantics, which the schema's notation already assumes. If a single star crossed
    // separators there would be no way left to write a shallow-only rule.
    PatchGuard.touchesProtected(List("src/*.scala"), numstat("src/Main.scala")) shouldBe true
    PatchGuard.touchesProtected(List("src/*.scala"), numstat("src/a/B.scala")) shouldBe false
  }

  it should "treat a bare filename entry as an exact match, not a suffix match" in {
    PatchGuard.touchesProtected(List("CONTEXT.md"), numstat("CONTEXT.md")) shouldBe true
    PatchGuard.touchesProtected(List("CONTEXT.md"), numstat("docs/CONTEXT.md")) shouldBe false
  }

  /** The exact hole the restated guard in `test/ReviewFixLoopExample.scala` used to carry, kept as a
    * test so the two can never diverge again: prefix matching on `g.stripSuffix("**")` reads
    * a `src` + `/` + `*.scala` entry as the prefix `src/`, reports `src/a/B.scala` as protected, and
    * reads
    * `CONTEXT.md` as a prefix that covers nothing else but also matches `CONTEXT.md.bak`. A JDK glob
    * answers differently on both, which is what the rows above and this one pin.
    */
  it should "not degrade to prefix matching on a star that is not at the end" in {
    PatchGuard.touchesProtected(List("src/*.scala"), numstat("src/a/deep/B.scala")) shouldBe false
    PatchGuard.touchesProtected(List("CONTEXT.md"), numstat("CONTEXT.md.bak")) shouldBe false
  }

  it should "report a path covered by nothing in the list as unprotected" in {
    PatchGuard.touchesProtected(
      List(".litter-box/**", ".github/**", "CONTEXT.md"),
      numstat("src/Main.scala")
    ) shouldBe false
  }

  it should "reject a multi-row numstat as soon as ONE row is protected" in {
    PatchGuard.touchesProtected(
      List(".github/**"),
      numstat("src/Main.scala", ".github/workflows/ci.yml", "README.md")
    ) shouldBe true
  }

  /** Totality on junk input, and the reason it is not a throw: the caller is the patch guard, and an
    * exception there aborts the whole iteration (an infra fault, budget untouched, issue stuck)
    * instead of doing the one thing the guard exists to do, which is to reject the patch. Reporting
    * "not protected" cannot widen the hole either, because `git apply --index` still has to accept
    * the same unparseable path afterwards, and it will not.
    */
  it should "be total on a path java.nio refuses to parse" in {
    // An embedded NUL byte is the portable way to make java.nio refuse outright: `Path.of` throws
    // InvalidPathException instead of returning anything. Built from a char rather than written as
    // a literal so the file stays plain text.
    val unparseable = "src/" + 0.toChar + "broken.scala"

    noException should be thrownBy {
      PatchGuard.touchesProtected(List("**"), numstat(unparseable)) shouldBe false
    }
    PatchGuard.touchesProtected(List("**"), numstat(unparseable)) shouldBe false

    // Sanity, so the assertion above is about the parse failure and not about a glob that happens
    // to match nothing: the very same glob does cover a path that parses.
    PatchGuard.touchesProtected(List("**"), numstat("src/Main.scala")) shouldBe true
  }

  it should "read every path a numstat names, junk lines dropped rather than aborting the read" in {
    val mixed = s"not a numstat line\n1\t0\t.github/workflows/ci.yml\nalso junk"

    PatchGuard.numstatPaths(mixed) shouldBe List(".github/workflows/ci.yml")
    PatchGuard.touchesProtected(List(".github/**"), mixed) shouldBe true
  }

  // ===============================================================================================
  // 2. rule: the whole ruling, pure
  // ===============================================================================================

  behavior of "PatchGuard.rule"

  it should "clear a patch within the cap that touches nothing protected" in {
    given Config = cfgWith(List(".litter-box/**"))
    PatchGuard.rule(bytes = 10, numstat("src/Main.scala")) shouldBe Ruling.Clean
  }

  it should "refuse a patch over the cap" in {
    given Config = cfgWith(List(".litter-box/**"), maxPatchBytes = 10)
    PatchGuard.rule(bytes = 11, numstat("src/Main.scala")) shouldBe Ruling.Oversized
  }

  it should "clear a patch exactly at the cap" in {
    // The cap is a ceiling, not a limit one under it: `>` and not `>=`, the same comparison the
    // guard has always made, pinned here so a tidy-up cannot quietly move the boundary by one byte.
    given Config = cfgWith(List(".litter-box/**"), maxPatchBytes = 10)
    PatchGuard.rule(bytes = 10, numstat("src/Main.scala")) shouldBe Ruling.Clean
  }

  it should "refuse a patch touching a protected path" in {
    given Config = cfgWith(List(".github/**"))
    PatchGuard.rule(bytes = 10, numstat(".github/workflows/ci.yml")) shouldBe Ruling.Protected
  }

  /** Order matters for the log line and the marker text, not for the outcome: both refusals stage a
    * marker and route to needs-human. Pinned so the reason an operator reads is stable.
    */
  it should "report a patch that is both oversized and protected as oversized" in {
    given Config = cfgWith(List(".github/**"), maxPatchBytes = 10)
    PatchGuard.rule(bytes = 11, numstat(".github/workflows/ci.yml")) shouldBe Ruling.Oversized
  }

  // ===============================================================================================
  // 3. stage: what actually reaches the index
  // ===============================================================================================

  behavior of "PatchGuard.stage"

  /** Everything `stage` needs, one by one rather than as a `Caps` bundle, which is the shape that
    * makes it callable from a plain function AND from inside a node body through `Caps.given`.
    */
  private def stageIn(world: TestWorld, cfg: Config, patchFile: String): Staged =
    given Config    = cfg
    given Git       = world.git
    given HarnessFs = world.fs
    given Log       = world.logger
    PatchGuard.stage(patchFile)

  it should "reset to the pristine base before it reads anything about the patch" in {
    val world = TestWorld()
    world.files("p.patch") = Script.newFilePatch

    stageIn(world, cfgWith(Nil), "p.patch") shouldBe Staged.Ok("p.patch")

    // The whole threat model in one assertion: whatever the agent left in the tree is gone before a
    // single decision is made about the diff it produced.
    val reset   = world.calls.indexWhere(_.contains("git reset --hard origin/main"))
    val numstat = world.calls.indexWhere(_.contains("git apply --numstat"))
    val applied = world.calls.indexWhere(_.contains("git apply --index"))
    reset should be >= 0
    reset should be < numstat
    numstat should be < applied
  }

  it should "report an absent diff as Empty without applying anything" in {
    val world = TestWorld()

    stageIn(world, cfgWith(Nil), "p.patch") shouldBe Staged.Empty
    world.called("git apply --index") shouldBe false
  }

  it should "refuse an oversized patch, stage the marker instead, and never apply it" in {
    val world = TestWorld()
    world.files("p.patch") = Script.newFilePatch

    stageIn(world, cfgWith(Nil, maxPatchBytes = 1), "p.patch") shouldBe Staged.Oversize

    world.called("git apply --index") shouldBe false
    world.files should contain key "PATCH-REJECTED.md"
    world.files("PATCH-REJECTED.md") should include("must NOT be merged")
    world.called("git add PATCH-REJECTED.md") shouldBe true
    world.logged("patch guard:") shouldBe true
    world.logged("exceeds the 1B cap") shouldBe true
  }

  it should "refuse a patch touching a protected path, stage the marker, and never apply it" in {
    val world = TestWorld()
    world.files("p.patch") = "1\t0\t.github/workflows/ci.yml"

    stageIn(world, cfgWith(List(".github/**")), "p.patch") shouldBe Staged.Protected

    world.called("git apply --index") shouldBe false
    world.files should contain key "PATCH-REJECTED.md"
    world.called("git add PATCH-REJECTED.md") shouldBe true
    world.logged("patch guard: patch touches a protected path") shouldBe true
  }

  /** The marker carries the numstat of the patch it refused, so the audit PR says what was rejected
    * without the rejected change ever being on the branch.
    */
  it should "name the rejected patch's own numstat in the marker" in {
    val world = TestWorld()
    world.files("p.patch") = "1\t0\t.github/workflows/ci.yml"

    stageIn(world, cfgWith(List(".github/**")), "p.patch")

    world.files("PATCH-REJECTED.md") should include(".github/workflows/ci.yml")
  }

  /** Fail open on an unparseable patch, and the backstop that makes it safe: an empty numstat clears
    * the protected-path check, and `git apply --index` then refuses the same patch.
    */
  it should "report a refused apply rather than pretending the patch landed" in {
    val world = TestWorld()
    world.files("p.patch") = "this is not a patch"

    stageIn(world, cfgWith(List("**")), "p.patch") shouldBe Staged.ApplyFail

    world.logged("git apply refused the patch") shouldBe true
    world.files should not contain key("PATCH-REJECTED.md")
  }

  it should "apply a clean patch to the index" in {
    val world = TestWorld()
    world.files("p.patch") = Script.newFilePatch

    stageIn(world, cfgWith(List(".github/**")), "p.patch") shouldBe Staged.Ok("p.patch")

    world.called("git apply --index p.patch") shouldBe true
    world.files should not contain key("PATCH-REJECTED.md")
  }
