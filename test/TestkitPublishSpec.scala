package in.rcard.litterbox

import in.rcard.litterbox.testsupport.RepoTree
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Anti-drift for the second published artifact (issue #42), in the same spirit as `InitSpec`'s
  * assertion that the scaffolded `//> using dep` line is rendered from `LitterBox.Coordinate` rather
  * than a hand-copied literal.
  *
  * `LitterBox.TestkitCoordinate` and the publish that ships it are two statements of one fact: what
  * the testkit is called on Maven Central, and at which version. Nothing about the scaladoc on that
  * constant, or README's Testkit section, or the `test.dep` line a consumer copies out of either, is
  * worth anything if the release publishes a differently named artifact, and a release workflow is
  * exercised on tags only, so the ordinary way that drift is discovered is a consumer hitting an
  * unresolvable coordinate after the tag has already been cut and Central has already made it
  * permanent.
  *
  * The flag set itself now lives in `scripts/publish-testkit.sh`, called three times: by
  * `.github/workflows/ci.yml`'s `testkit` job, and by both halves of `release.yml`'s publish job.
  * Written out at those three sites instead, as it was, the two blocks inside release.yml were near
  * identical fifteen flag walls whose only real differences were `local` and a repository, which is
  * exactly the shape in which a copy paste swaps a `--name` with nothing noticing. So the assertions
  * below run the real script, with a fake `scala-cli` first on PATH recording the argv it was handed,
  * the way `SandboxSpec` drives the real sandbox scripts. That answers the question a substring check
  * never could, which is what scala-cli is ACTUALLY invoked with, and it holds for all three callers
  * at once because there is now one definition for them to call.
  *
  * What is left for the workflow files themselves is that they call it, in the right mode, at the
  * right version, and in the right order relative to the irreversible upload.
  *
  * What this spec cannot do is prove the publish SUCCEEDS; that needs credentials and a network, and
  * `.github/workflows/ci.yml`'s `testkit` job covers the part of it that can be checked without
  * either (the testkit publishing standalone against a locally staged library).
  */
class TestkitPublishSpec extends AnyFlatSpec with Matchers:

  /** `RepoTree` (`test/RepoTree.scala`) owns the upward walk and states why it is not `git rev-parse
    * --show-toplevel`: this repo's own sandboxed gate materialises a `git archive` tree with no
    * `.git`, so asking git for the repo root the way `Main.resolveRepoRoot` does is not an option
    * every runner of this suite has. A miss becomes scalatest's `fail`, which `RepoTree` cannot
    * reach itself, so the translation from `Option` to failure lives here.
    */
  private def repoPath(relative: String): Path =
    RepoTree.file(relative).getOrElse(fail(s"could not locate $relative from the JVM cwd"))

  private def repoFile(relative: String): String = Files.readString(repoPath(relative))

  /** `lazy`, and not a plain constructor field, so a missing file fails the test that needed it,
    * naming it, rather than aborting the whole suite before any test reports.
    */
  private lazy val releaseYml = repoFile(".github/workflows/release.yml")

  /** `lazy` for the same reason `releaseYml` is. */
  private lazy val ciYml = repoFile(".github/workflows/ci.yml")

  /** `lazy` for the same reason `releaseYml` is. */
  private lazy val publishTestkit = repoPath("scripts/publish-testkit.sh")

  /** A stand in for scala-cli, first on PATH, recording the argv the script hands it and running
    * nothing. Scripted rather than mocked for the reason `SandboxSpec`'s fake docker is: the subject
    * here is what the real script decides to invoke, and only the real script can answer that.
    *
    * `>` and not `>>`: one file per run, so a test reads the argv of its own invocation only.
    */
  private val FakeScalaCli = """#!/usr/bin/env bash
    |printf '%s\n' "$@" >"$FAKE_SCALA_CLI_ARGV"
    |""".stripMargin

  private case class Run(exitCode: Int, argv: List[String], output: String)

  /** Runs the publish script under a fake scala-cli and reports everything the assertions below need
    * to distinguish "invoked correctly" from "refused to invoke at all".
    */
  private def run(script: Path, args: String*): Run =
    val bin  = Files.createTempDirectory("testkit-publish-bin")
    val fake = bin.resolve("scala-cli")
    Files.writeString(fake, FakeScalaCli)
    fake.toFile.setExecutable(true) shouldBe true
    val argvFile = Files.createTempFile("testkit-publish-argv", "")

    // `bash <path>` rather than executing the file directly: the exec bit is a property of the
    // checkout, and a test that depends on it fails describing the harness rather than the script.
    val pb  = new ProcessBuilder(("bash" :: script.toString :: args.toList)*)
    pb.redirectErrorStream(true)
    val env = pb.environment()
    env.put("PATH", s"$bin${java.io.File.pathSeparator}${env.get("PATH")}")
    env.put("FAKE_SCALA_CLI_ARGV", argvFile.toString)
    val proc = pb.start()
    val out  = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val code = proc.waitFor()
    Run(code, Files.readString(argvFile).linesIterator.toList, out)

  /** The argv of a run that was supposed to reach scala-cli. */
  private def argvOf(args: String*): List[String] =
    val r = run(publishTestkit, args*)
    withClue(s"${args.mkString(" ")} exited ${r.exitCode}:\n${r.output}") { r.exitCode shouldBe 0 }
    r.argv

  /** The version every assertion below publishes at. Deliberately neither `0.0.0-CI` nor a tag: it
    * is the argument, and reading it back out of `--project-version` and `--dep` is what proves the
    * script pins both to the one value it was handed rather than to anything of its own.
    */
  private val Version = "9.9.9-SPEC"

  private lazy val staged   = argvOf("local", Version)
  private lazy val uploaded = argvOf("central", Version, "--secret-key-password", "env:PGP_PASSPHRASE")

  /** The value a flag was given. Reading the token AFTER the flag, rather than substring matching
    * `--name litter-box-testkit`, is what makes the assertion fail when the value changes:
    * `include("--name litter-box")` is also satisfied by `--name litter-box-testkit`.
    */
  private def flag(argv: List[String], name: String): String =
    argv.indexOf(name) match
      case -1                          => fail(s"$name is not passed at all in: ${argv.mkString(" ")}")
      case i if i + 1 >= argv.length   => fail(s"$name is the last argument, with no value")
      case i                           => argv(i + 1)

  private def organizationOf(coordinate: String): String = coordinate.split("::")(0)
  private def artifactOf(coordinate: String): String     =
    coordinate.split("::")(1).stripSuffix(s":${LitterBox.Version}")

  "the testkit publish script" should "publish test/Recorder.scala under exactly the coordinate LitterBox.TestkitCoordinate names" in {
    // The split helpers did what the rest of this file assumes they did: a `TestkitCoordinate`
    // reshaped into something they read differently would otherwise quietly assert nothing.
    organizationOf(LitterBox.TestkitCoordinate) shouldBe "in.rcard"
    artifactOf(LitterBox.TestkitCoordinate) shouldBe "litter-box-testkit"

    staged should contain("test/Recorder.scala")
    flag(staged, "--name") shouldBe artifactOf(LitterBox.TestkitCoordinate)
    flag(staged, "--organization") shouldBe organizationOf(LitterBox.TestkitCoordinate)

    // The upload is the same script, so the same assertion is the same fact; stated anyway because
    // this is the invocation that cannot be taken back.
    flag(uploaded, "--name") shouldBe artifactOf(LitterBox.TestkitCoordinate)
    flag(uploaded, "--organization") shouldBe organizationOf(LitterBox.TestkitCoordinate)
  }

  it should "pin its own version and its library dependency to the single version argument, so the two artifacts can never be released at different versions" in {
    // The pairing rule `LitterBox.TestkitCoordinate`'s scaladoc states, made structural rather than
    // merely written down: the testkit exposes the capability traits themselves, so a testkit built
    // against one library version says nothing about any other. One argument feeds both flags.
    flag(staged, "--project-version") shouldBe Version
    flag(staged, "--dep") shouldBe
      s"${organizationOf(LitterBox.Coordinate)}::${artifactOf(LitterBox.Coordinate)}:$Version"

    flag(uploaded, "--project-version") shouldBe Version
    flag(uploaded, "--dep") shouldBe
      s"${organizationOf(LitterBox.Coordinate)}::${artifactOf(LitterBox.Coordinate)}:$Version"
  }

  it should "hand scala-cli the scala and jvm versions project.scala names, never an empty value it would silently replace with its own default" in {
    // `--scala ""` is ACCEPTED by scala-cli, which then silently uses its own default version
    // (reproduced during issue #42's review: a pom depending on scala3-library_3 3.8.4 while
    // project.scala pinned 3.8.3). The published testkit's TASTy would be unreadable by any consumer
    // on the library's own scala version, at a coordinate that cannot be taken back. So the values
    // are asserted against project.scala itself, which is the only thing that makes an empty capture
    // visible from here.
    val projectScala = repoFile("project.scala")
    val scalaVersion = """(?m)^//> using scala (.*)$""".r.findFirstMatchIn(projectScala).get.group(1)
    val jvmVersion   = """(?m)^//> using jvm (.*)$""".r.findFirstMatchIn(projectScala).get.group(1)
    scalaVersion should not be empty
    jvmVersion should not be empty

    flag(staged, "--scala") shouldBe scalaVersion
    flag(staged, "--jvm") shouldBe jvmVersion
    flag(uploaded, "--scala") shouldBe scalaVersion
    flag(uploaded, "--jvm") shouldBe jvmVersion
  }

  it should "refuse to publish anything at all when a directive it reads out of project.scala is missing" in {
    // The `:?` guard, exercised rather than described. `set -u` fires on an UNSET variable, never on
    // a set but empty one, so a `project.scala` whose directive line is reformatted or removed is the
    // case that reaches scala-cli as `--scala ""`. Both directives get their own run: a guard that
    // covered only one would leave the other free to go empty.
    for missing <- List("//> using scala 3.8.3", "//> using jvm temurin:21") do
      val root = Files.createTempDirectory("testkit-publish-repo")
      Files.createDirectories(root.resolve("scripts"))
      val copied = root.resolve("scripts/publish-testkit.sh")
      Files.copy(publishTestkit, copied)
      // The script reads project.scala relative to its OWN location, so this stands in for the real
      // one: everything except the directive under test.
      Files.writeString(
        root.resolve("project.scala"),
        List("//> using scala 3.8.3", "//> using jvm temurin:21").filterNot(_ == missing).mkString("\n") + "\n"
      )

      val r = run(copied, "local", Version)
      withClue(s"without `$missing`, the script:\n${r.output}") {
        r.exitCode should not be 0
        r.argv shouldBe empty
      }
  }

  it should "refuse an empty version, which scala-cli would silently replace with one of its own" in {
    // Neither guard already in place catches this one. The argument count check sees two arguments,
    // and `set -u` fires on an UNSET variable, never on a set but empty one, so an empty version
    // reaches scala-cli as `--project-version ""` and `--dep in.rcard::litter-box:`. It matters now
    // because both release.yml call sites pass a shell variable rather than a literal, so an empty
    // value is a thing that can actually arrive, and on the Central path it lands an artifact at a
    // coordinate nobody chose and nobody can take back.
    val r = run(publishTestkit, "local", "")
    withClue(s"at an empty version, the script:\n${r.output}") {
      r.exitCode should not be 0
      r.argv shouldBe empty
    }
  }

  it should "differ between the dry run and the Central upload by the destination alone, so the dry run rehearses the upload" in {
    // Central is immutable at a version: a tag that publishes the library and then fails building the
    // testkit cannot be retried, and needs the Portal UI (see release.yml's own comment and
    // docs/maven-central-setup.md). The pre-flight is `publish local` and not `compile` because
    // `compile` builds no doc jar, no sources jar and no pom and reads none of the metadata flags. It
    // is worth nothing, though, unless it runs the SAME flags, which is what this asserts: every
    // difference between the two invocations, in both directions, is named here.
    (staged.toSet diff uploaded.toSet) shouldBe Set("local")
    (uploaded.toSet diff staged.toSet) shouldBe Set(
      "--publish-repository",
      "central",
      "--user",
      "env:SONATYPE_USERNAME",
      "--password",
      "env:SONATYPE_PASSWORD",
      "--secret-key",
      "env:PGP_SECRET_ARMORED",
      // Passed through by release.yml rather than decided here, since the library publish in that
      // same step needs the identical decision.
      "--secret-key-password",
      "env:PGP_PASSPHRASE"
    )

    // Named individually as well, because the set difference above would also be satisfied by both
    // invocations losing the metadata Central demands together.
    staged should contain allOf ("--license", "--url", "--vcs", "--description", "--developer")

    // No credential reaches the PR job's invocation. It runs on every pull request and has none.
    staged.filter(_.startsWith("env:")) shouldBe empty
  }

  /** Every call of the publish script in a workflow file, split into its arguments. */
  private def scriptCalls(yaml: String): List[List[String]] =
    yaml.linesIterator
      .map(_.trim)
      .filter(_.startsWith("scripts/publish-testkit.sh"))
      .map(_.split("\\s+").toList.map(_.stripPrefix("\"").stripSuffix("\"")))
      .toList

  /** Every `scala-cli` invocation in a workflow file, each joined back into one line from the
    * backslash continued block it is written as. The two library publishes still live in the
    * workflows themselves, and asserting on whole commands rather than on the file's raw text is
    * what lets a test below say "the command that uploads the LIBRARY names the library".
    */
  private def scalaCliCommands(yaml: String): List[String] =
    val out                = List.newBuilder[String]
    var acc: StringBuilder = null
    for raw <- yaml.linesIterator do
      val line = raw.trim
      if acc == null && line.startsWith("scala-cli") then acc = new StringBuilder(line.stripSuffix("\\").trim)
      else if acc != null then acc.append(" ").append(line.stripSuffix("\\").trim)
      if acc != null && !line.endsWith("\\") then
        out += acc.toString
        acc = null
    out.result()

  /** The single command matching every needle. Failing on TWO matches is deliberate: a second,
    * near identical invocation appearing later is exactly how the flag swap this spec exists to
    * catch gets introduced, and a helper that quietly took the first match would hide it.
    */
  private def theCommand(yaml: String, needles: String*): String =
    scalaCliCommands(yaml).filter(cmd => needles.forall(cmd.contains)) match
      case one :: Nil => one
      case Nil        => fail(s"no scala-cli command matching all of ${needles.mkString(", ")}")
      case many       =>
        fail(s"${many.size} scala-cli commands match all of ${needles.mkString(", ")}:\n${many.mkString("\n")}")

  private def commandFlag(command: String, name: String): String =
    flag(command.split("\\s+").toList, name).stripPrefix("\"").stripSuffix("\"")

  /** The tag being released, as release.yml's `build` job reads it. */
  private val TagExpr = "${GITHUB_REF_NAME#v}"

  /** The one expression every publish in release.yml's `publish` job spends, library and testkit
    * alike. A shell variable rather than the tag expression written out at each site, because the
    * property the tests below need is that there is ONE derivation, not four that happen to be
    * spelled the same today.
    */
  private val VersionExpr = "$VERSION"

  "the release workflow" should "call the publish script twice, dry running against the locally staged library before anything reaches Central" in {
    // Asserted on the COMMANDS' own positions, never on the two steps' `name:` lines: moving the
    // pre-flight call into the publish step, below the library upload, would leave both step names
    // exactly where they are while destroying the property this test is named for.
    // Both call sites invoke the script directly rather than through `bash`, so its exec bit is
    // something the release needs from the checkout, and a mode that got lost on the way into git
    // would surface first on a tag.
    Files.isExecutable(publishTestkit) shouldBe true

    val stageIdx  = releaseYml.indexOf("scripts/publish-testkit.sh local")
    val uploadIdx = releaseYml.indexOf("--publish-repository central")
    stageIdx should be >= 0
    uploadIdx should be >= 0
    stageIdx should be < uploadIdx

    scriptCalls(releaseYml) shouldBe List(
      List("scripts/publish-testkit.sh", "local", VersionExpr),
      // `$args` is the optional `--secret-key-password`, decided once in that step for the library
      // publish and this one both.
      List("scripts/publish-testkit.sh", "central", VersionExpr, "$args")
    )
  }

  /** One job's own text, from its key down to the next job at the same indentation. Job level keys
    * are the only ones written at two spaces, so the next such line ends the job. Scoped rather than
    * file wide because `release.yml` has five jobs and several of them legitimately name the same
    * things: `formula` reads the very same `needs.build.outputs.version`, and an assertion about
    * what `publish` does exactly once has to mean once IN `publish`.
    */
  private def jobText(yaml: String, name: String): String =
    val start = yaml.indexOf(s"\n  $name:")
    if start < 0 then fail(s"no `$name:` job in the workflow")
    val body = yaml.substring(start + 1)
    """(?m)^  [a-z]""".r.findAllMatchIn(body).find(_.start > 0) match
      case Some(next) => body.substring(0, next.start)
      case None       => body

  it should "publish both artifacts at one version derived once, never at two versions computed two ways" in {
    // The finding this test exists for. The library used to go out under scala-cli's
    // `--compute-version git:tag` while the testkit took `${GITHUB_REF_NAME#v}`: two different
    // functions of the repository state, agreeing on an ordinary one tag per commit release and
    // parting company otherwise. Measured with scala-cli 1.16.0, `v0.2.0` and `v0.10.0` on one
    // commit: `git:tag` computes `0.2.0` whichever of the two was pushed, `${GITHUB_REF_NAME#v}` is
    // whichever actually triggered the run, and `git describe --tags` says `v0.10.0`, so all three
    // disagree. Where they part company the testkit ships a `--dep` on a library version that was
    // never published, at a coordinate Central is immutable at. Nothing compared them, and CI runs
    // the whole path at `0.0.0-CI` for both, so a real tag would have been the first exercise of the
    // difference.
    val publishJob = jobText(releaseYml, "publish")
    val versions   =
      List(
        // The dry run's staged library, so the rehearsal covers the version the upload uses rather
        // than merely the flags.
        commandFlag(theCommand(publishJob, "publish local ."), "--project-version"),
        commandFlag(theCommand(publishJob, "publish . ", "--publish-repository central"), "--project-version")
      ) ::: scriptCalls(publishJob).map(_(2))
    versions.distinct shouldBe List(VersionExpr)

    // Asserted as the WHOLE list of bindings in the job, not as "contains": a second `VERSION:` here
    // is a second derivation wearing the first one's name, and the four sites above would then be
    // reading whichever of the two is in scope where they sit.
    publishJob.linesIterator.map(_.trim).filter(_.startsWith("VERSION:")).toList shouldBe
      List("VERSION: ${{ needs.build.outputs.version }}")

    // What that single binding resolves to: the tag, by way of the `build` job that already refuses
    // a tag disagreeing with `LitterBox.Version`, so the two artifacts and the binary's own constant
    // are one fact rather than three.
    releaseYml should include(s"version=$TagExpr")

    // The regression itself, named. `--compute-version` asks scala-cli to work out a version from
    // the git history, which is precisely the second derivation this test exists to keep out. On the
    // COMMANDS, not on the file's text: the comments explaining why it is gone name the flag, and a
    // test that forbade the words would be a test against the explanation.
    scalaCliCommands(releaseYml).filter(_.contains("--compute-version")) shouldBe empty
  }

  it should "upload the library under exactly the coordinate LitterBox.Coordinate names" in {
    // The other half of a swap: the testkit's own name is pinned in the script, and this is the one
    // remaining publish written out in the workflow, so this is where the library could still be
    // uploaded under the wrong name.
    val libraryUpload = theCommand(releaseYml, "publish . ", "--publish-repository central")
    commandFlag(libraryUpload, "--name") shouldBe artifactOf(LitterBox.Coordinate)
    commandFlag(libraryUpload, "--organization") shouldBe organizationOf(LitterBox.Coordinate)
  }

  "the CI workflow" should "run the release's own testkit publish on every PR, since the release path that needs it cannot be retried" in {
    // Scoped to the `testkit` job, not to the file: the whole point is that THIS job exists and does
    // this, and a file-wide substring check would still pass with the job deleted and either command
    // left behind somewhere else.
    val jobIdx = ciYml.indexOf("\n  testkit:")
    jobIdx should be >= 0
    val job = ciYml.substring(jobIdx)

    val stagedLibrary = theCommand(job, "publish local .")
    commandFlag(stagedLibrary, "--name") shouldBe artifactOf(LitterBox.Coordinate)

    // The testkit is built against the library this job just staged, never against whatever version
    // happens to be resolvable from Central, which is what would make the check vacuous on a PR that
    // breaks the testkit's dependency on unreleased library code. The script derives its `--dep` from
    // this argument, so passing the staged version is the whole of that guarantee.
    scriptCalls(job) shouldBe List(
      List("scripts/publish-testkit.sh", "local", commandFlag(stagedLibrary, "--project-version"))
    )
  }
