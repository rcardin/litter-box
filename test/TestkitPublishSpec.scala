package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** Anti-drift for the second published artifact (issue #42), in the same spirit as `InitSpec`'s
  * assertion that the scaffolded `//> using dep` line is rendered from `LitterBox.Coordinate` rather
  * than a hand-copied literal.
  *
  * `LitterBox.TestkitCoordinate` and `.github/workflows/release.yml` are two statements of one fact:
  * what the testkit is called on Maven Central, and at which version. Nothing about the scaladoc on
  * that constant, or README's Testkit section, or the `test.dep` line a consumer copies out of
  * either, is worth anything if the workflow publishes a differently named artifact, and a release
  * workflow is exercised on tags only, so the ordinary way that drift is discovered is a consumer
  * hitting an unresolvable coordinate after the tag has already been cut and Central has already
  * made it permanent.
  *
  * Every assertion below is scoped to ONE `scala-cli` invocation, never to the whole file, and this
  * is the correction #42's own review forced (the first version of this spec asserted
  * `releaseYml should include("--organization in.rcard")` and friends against the file as a whole).
  * The publish job now contains four `scala-cli publish` invocations, two of them staging locally and
  * two of them uploading, and file-wide substring checks are satisfied by ANY of them: swapping
  * `--name litter-box` with `--name litter-box-testkit` between the two Central publishes, the most
  * plausible copy-paste failure in a pair of near-identical fifteen flag blocks, left every one of
  * those assertions green while the tag published each artifact under the other's name.
  *
  * What this spec cannot do is prove the publish SUCCEEDS; that needs credentials and a network, and
  * `.github/workflows/ci.yml`'s `testkit` job covers the part of it that can be checked without
  * either (the testkit publishing standalone against a locally staged library).
  */
class TestkitPublishSpec extends AnyFlatSpec with Matchers:

  /** The workflow file, found by walking up from the JVM cwd, the same way `ShippedSpec` locates
    * `resources/`, so the spec does not care whether the runner starts in the project root or a
    * subdirectory. `lazy`, and not a plain constructor field, so a missing file fails the test that
    * needed it, naming it, rather than aborting the whole suite before any test reports.
    */
  private def repoFile(relative: String): String =
    var dir: Path = Path.of("").toAbsolutePath.normalize
    var found     = Option.empty[Path]
    while found.isEmpty && dir != null do
      val candidate = dir.resolve(relative)
      if Files.isRegularFile(candidate) then found = Some(candidate)
      dir = dir.getParent
    Files.readString(found.getOrElse(fail(s"could not locate $relative from the JVM cwd")))

  private lazy val releaseYml = repoFile(".github/workflows/release.yml")
  private lazy val ciYml      = repoFile(".github/workflows/ci.yml")

  /** Every `scala-cli` invocation in a workflow file, each joined back into one line from the
    * backslash continued block it is written as. Asserting on whole commands rather than on the
    * file's raw text is what lets a test below say "the command that uploads the TESTKIT names the
    * testkit", which is the only form of that assertion a flag swap cannot satisfy.
    */
  private def scalaCliCommands(yaml: String): List[String] =
    val out                 = List.newBuilder[String]
    var acc: StringBuilder  = null
    for raw <- yaml.linesIterator do
      val line = raw.trim
      if acc == null && line.startsWith("scala-cli") then acc = new StringBuilder(line.stripSuffix("\\").trim)
      else if acc != null then acc.append(" ").append(line.stripSuffix("\\").trim)
      if acc != null && !line.endsWith("\\") then
        out += acc.toString
        acc = null
    out.result()

  /** The single command matching every needle. Failing on TWO matches is deliberate: a second,
    * near-identical invocation appearing later is exactly how the flag swap this spec exists to
    * catch gets introduced, and a helper that quietly took the first match would hide it.
    */
  private def theCommand(yaml: String, needles: String*): String =
    scalaCliCommands(yaml).filter(cmd => needles.forall(cmd.contains)) match
      case one :: Nil => one
      case Nil        => fail(s"no scala-cli command matching all of ${needles.mkString(", ")}")
      case many       =>
        fail(s"${many.size} scala-cli commands match all of ${needles.mkString(", ")}:\n${many.mkString("\n")}")

  /** The value a flag was given, quotes stripped. Reading the token AFTER the flag, rather than
    * substring matching `--name litter-box-testkit`, is what makes the assertion fail when the value
    * changes: `include("--name litter-box")` is also satisfied by `--name litter-box-testkit`.
    */
  private def flag(command: String, name: String): String =
    val tokens = command.split("\\s+").toList
    tokens.indexOf(name) match
      case -1                             => fail(s"$name is not passed at all in: $command")
      case i if i + 1 >= tokens.length    => fail(s"$name is the last token, with no value, in: $command")
      case i                              => tokens(i + 1).stripPrefix("\"").stripSuffix("\"")

  private def organizationOf(coordinate: String): String = coordinate.split("::")(0)
  private def artifactOf(coordinate: String): String     =
    coordinate.split("::")(1).stripSuffix(s":${LitterBox.Version}")

  /** The two commands that actually reach Central. `--publish-repository central` is what
    * distinguishes them from the local staging pair in the step above, which publishes the same two
    * artifacts to `~/.ivy2/local` as the release's own dry run.
    */
  private lazy val testkitUpload =
    theCommand(releaseYml, "publish test/Recorder.scala", "--publish-repository central")
  private lazy val libraryUpload =
    theCommand(releaseYml, "publish . ", "--publish-repository central")

  "the release workflow" should "upload the testkit under exactly the coordinate LitterBox.TestkitCoordinate names, and the library under LitterBox.Coordinate's" in {
    // The split helpers did what the rest of this file assumes they did: a `TestkitCoordinate`
    // reshaped into something they read differently would otherwise quietly assert nothing.
    organizationOf(LitterBox.TestkitCoordinate) shouldBe "in.rcard"
    artifactOf(LitterBox.TestkitCoordinate) shouldBe "litter-box-testkit"

    flag(testkitUpload, "--name") shouldBe artifactOf(LitterBox.TestkitCoordinate)
    flag(testkitUpload, "--organization") shouldBe organizationOf(LitterBox.TestkitCoordinate)

    // The other half of the swap: asserting only on the testkit command would still pass if both
    // commands named the testkit.
    flag(libraryUpload, "--name") shouldBe artifactOf(LitterBox.Coordinate)
    flag(libraryUpload, "--organization") shouldBe organizationOf(LitterBox.Coordinate)
  }

  it should "pin the testkit's own version and its library dependency to the same expression, so the two artifacts can never be released at different versions" in {
    // The pairing rule `LitterBox.TestkitCoordinate`'s scaladoc states, enforced rather than merely
    // written down: the testkit exposes the capability traits themselves, so a testkit built against
    // one library version says nothing about any other. Both flags read the tag being released.
    val tagExpr = "${GITHUB_REF_NAME#v}"
    flag(testkitUpload, "--project-version") shouldBe tagExpr
    flag(testkitUpload, "--dep") shouldBe
      s"${organizationOf(LitterBox.Coordinate)}::${artifactOf(LitterBox.Coordinate)}:$tagExpr"
  }

  it should "read the scala and jvm versions out of project.scala, and refuse an empty capture, rather than letting scala-cli substitute its own defaults" in {
    // `--scala ""` is ACCEPTED by scala-cli, which then silently uses its own default version
    // (reproduced during this issue's review: a pom depending on scala3-library_3 3.8.4 while
    // project.scala pinned 3.8.3). `set -u` does not catch it, because the variable is set, just
    // empty. The published testkit's TASTy would be unreadable by any consumer on the library's own
    // scala version, at a coordinate that cannot be taken back.
    flag(testkitUpload, "--scala") shouldBe "$scala_version"
    flag(testkitUpload, "--jvm") shouldBe "$jvm_version"
    releaseYml should include("""${scala_version:?""")
    releaseYml should include("""${jvm_version:?""")
  }

  it should "dry run the whole testkit publish, not merely compile it, before anything reaches Maven Central" in {
    // Central is immutable at a version: a tag that publishes the library and then fails building
    // the testkit cannot be retried, and needs the Portal UI (see the job's own comment and
    // docs/maven-central-setup.md). So the pre-flight is `publish local`, which builds the doc jar,
    // the sources jar and the pom and reads every metadata flag, rather than `compile`, which builds
    // none of them and would leave a scaladoc error in test/Recorder.scala to surface for the first
    // time after the library upload had already spent the version.
    //
    // Asserted on the COMMANDS' own positions, never on the two steps' `name:` lines: moving the
    // pre-flight command into the publish step, below the library upload, would leave both step
    // names exactly where they are while destroying the property this test is named for.
    val stageIdx   = releaseYml.indexOf("publish local test/Recorder.scala")
    val uploadIdx  = releaseYml.indexOf("--publish-repository central")
    stageIdx should be >= 0
    uploadIdx should be >= 0
    stageIdx should be < uploadIdx

    // The pre-flight really is the full publish, with the metadata Central will demand.
    val stagedTestkit = theCommand(releaseYml, "publish local test/Recorder.scala")
    flag(stagedTestkit, "--name") shouldBe artifactOf(LitterBox.TestkitCoordinate)
    stagedTestkit should include("--license")
    stagedTestkit should include("--developer")
  }

  "the CI workflow" should "run the release's own testkit publish on every PR, since the release path that needs it cannot be retried" in {
    // Scoped to the `testkit` job, not to the file: the whole point is that THIS job exists and does
    // this, and a file-wide substring check would still pass with the job deleted and either command
    // left behind somewhere else.
    val jobIdx = ciYml.indexOf("\n  testkit:")
    jobIdx should be >= 0
    val job = ciYml.substring(jobIdx)

    val stagedLibrary = theCommand(job, "publish local .")
    val stagedTestkit = theCommand(job, "publish local test/Recorder.scala")
    flag(stagedLibrary, "--name") shouldBe artifactOf(LitterBox.Coordinate)
    flag(stagedTestkit, "--name") shouldBe artifactOf(LitterBox.TestkitCoordinate)

    // The testkit is built against the library this job just staged, never against whatever version
    // happens to be resolvable from Central, which is what would make the check vacuous on a PR that
    // breaks the testkit's dependency on unreleased library code.
    flag(stagedTestkit, "--dep") shouldBe
      s"${organizationOf(LitterBox.Coordinate)}::${artifactOf(LitterBox.Coordinate)}:${flag(stagedLibrary, "--project-version")}"
  }
