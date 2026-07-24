package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Unit tests for the sandbox runtime's new home.
  *
  * THE RULE THIS SPEC IS WRITTEN TO (GitHub issue #9): `litter-box init` scaffolded a repo the loop
  * could not run in, because the loop looked for the container scripts at `<repo>/sandbox` and
  * nothing ever wrote them there. The scripts ship in the artifact now, so the two load-bearing
  * assertions are "everything in the source tree actually ships" and "what ships lands on disk
  * executable" — the two ways a consumer install can be silently incomplete.
  */
class SandboxSpec extends AnyFlatSpec with Matchers:

  /** The source tree the resources are packaged from. Found by walking up from the JVM cwd so the
    * test does not care whether the runner starts in the project root or a subdirectory.
    */
  private def sourceTree(): Path =
    var dir: Path = Path.of("").toAbsolutePath.normalize
    var found     = Option.empty[Path]
    while found.isEmpty && dir != null do
      val candidate = dir.resolve("resources").resolve("sandbox")
      if Files.isDirectory(candidate) then found = Some(candidate)
      dir = dir.getParent
    found.getOrElse(fail("could not locate resources/sandbox from the JVM cwd"))

  "the shipped manifest" should "name every file under resources/sandbox, and no others" in {
    // The failure this exists to prevent: a new script that works in a checkout — where the whole
    // directory is on the classpath — and is missing from every install, because `ShippedFiles` is
    // what `extract` copies and nothing else is consulted. A directory listing cannot be asked of a
    // jar's classloader portably, so the list is hand-written, and hand-written lists rot.
    val tree = sourceTree()
    val onDisk = Files
      .walk(tree)
      .iterator
      .asScala
      .filter(Files.isRegularFile(_))
      .map(tree.relativize(_).toString)
      .toList
      .sorted

    onDisk shouldBe Sandbox.ShippedFiles.sorted
  }

  it should "be readable out of the artifact, byte for byte with the source tree" in {
    val tree = sourceTree()
    Sandbox.ShippedFiles.foreach { rel =>
      withClue(s"$rel: ") {
        Sandbox.builtIn(rel) shouldBe Files.readAllBytes(tree.resolve(rel))
      }
    }
  }

  it should "not carry the Docker-dependent developer tests" in {
    // sandbox/test/ is deliberately outside resources/: a released binary has no business shipping
    // its own test suite, and those scripts need Docker and network egress to say anything.
    Sandbox.ShippedFiles.find(_.contains("test")) shouldBe None
  }

  "extract" should "write every shipped file, with the scripts executable" in {
    val dir = Files.createTempDirectory("sandbox-spec")
    Sandbox.extract(dir)

    Sandbox.ShippedFiles.foreach { rel =>
      withClue(s"$rel: ") {
        Files.isRegularFile(dir.resolve(rel)) shouldBe true
        // The whole reason the tree is materialised rather than read from the classpath: bash and
        // docker take a path, and a path with no execute bit is a preflight that dies on
        // "permission denied" instead of running.
        Files.isExecutable(dir.resolve(rel)) shouldBe rel.endsWith(".sh")
      }
    }
  }

  it should "overwrite a stale file left behind by an earlier extraction" in {
    val dir = Files.createTempDirectory("sandbox-spec")
    Sandbox.extract(dir)
    Files.write(dir.resolve("lib.sh"), "clobbered".getBytes(StandardCharsets.UTF_8))

    Sandbox.extract(dir)

    new String(Files.readAllBytes(dir.resolve("lib.sh")), StandardCharsets.UTF_8) should
      not be "clobbered"
  }

  it should "leave no temporary files behind" in {
    // The staged-then-renamed write is invisible by design, and the way it stops being invisible is
    // a cache directory that accumulates .tmp siblings until someone notices.
    val dir = Files.createTempDirectory("sandbox-spec")
    Sandbox.extract(dir)

    val stray = Files
      .walk(dir)
      .iterator
      .asScala
      .filter(Files.isRegularFile(_))
      .map(dir.relativize(_).toString)
      .filterNot(Sandbox.ShippedFiles.contains)
      .toList

    stray shouldBe empty
  }

  it should "never expose a shipped file at less than its full length" in {
    // The race #9's first pass got wrong: writing dest directly truncates it to zero before the
    // bytes land, and `resolve` decides a tree is complete by asking whether its files exist. A
    // second process landing in that window skips extraction and execs an empty lib.sh.
    val dir      = Files.createTempDirectory("sandbox-spec")
    val expected = Sandbox.ShippedFiles.map(rel => rel -> Sandbox.builtIn(rel).length.toLong).toMap
    val short    = java.util.concurrent.ConcurrentLinkedQueue[String]()

    val writing = java.util.concurrent.atomic.AtomicBoolean(true)
    val writers = (1 to 4).map(_ => Thread(() => (1 to 20).foreach(_ => Sandbox.extract(dir))))
    val reader = Thread { () =>
      while writing.get() do
        Sandbox.ShippedFiles.foreach { rel =>
          val f = dir.resolve(rel)
          if Files.isRegularFile(f) && Files.size(f) != expected(rel) then short.add(rel)
        }
    }

    writers.foreach(_.start())
    reader.start()
    writers.foreach(_.join())
    writing.set(false)
    reader.join()

    short.asScala.toList.distinct shouldBe empty
  }

  "resolve" should "extract on first use and re-extract only what has gone missing" in {
    val home = Files.createTempDirectory("sandbox-spec-home")

    val dir = Sandbox.resolve(home)
    dir shouldBe Sandbox.cacheDir(home)
    Files.isRegularFile(dir.resolve("run-fast-gate.sh")) shouldBe true

    // The half-extracted case (^C, a full disk): the directory exists and is not empty, so a check
    // on the directory alone would pass and the loop would die inside a preflight script instead.
    Files.delete(dir.resolve("run-fast-gate.sh"))
    Sandbox.resolve(home)
    Files.isRegularFile(dir.resolve("run-fast-gate.sh")) shouldBe true
  }

  it should "key the cache on content, so an upgraded binary never reuses an old tree" in {
    val home = Files.createTempDirectory("sandbox-spec-home")
    Sandbox.cacheDir(home).getFileName.toString shouldBe Sandbox.digest
    Sandbox.digest should fullyMatch regex "[0-9a-f]{12}"
  }

  /** bash is the only thing that can truthfully answer what a sourced function or variable holds,
    * and none of the answers the tests below want needs Docker, so they all go through this one
    * seam rather than each spelling out its own `ProcessBuilder`.
    */
  private def libSays(sandboxDir: Path, repoRoot: String, expr: String): String =
    val script = s"""set -eu
                    |source "$$1"
                    |$expr
                    |""".stripMargin
    val pb     = new ProcessBuilder("bash", "-c", script, "bash", sandboxDir.resolve("lib.sh").toString)
    pb.environment().put(Settings.RepoRootEnvVar, repoRoot)
    val proc = pb.start()
    val out  = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    proc.waitFor() shouldBe 0
    out.strip

  "the extracted lib.sh" should "name the shipped allowlist when the repo has not scaffolded one" in {
    val dir  = Files.createTempDirectory("sandbox-spec")
    val repo = Files.createTempDirectory("sandbox-spec-repo")
    Sandbox.extract(dir)

    Path.of(libSays(dir, repo.toString, "effective_allowlist")).toRealPath() shouldBe
      dir.resolve("proxy/allowlist").toRealPath()
  }

  it should "prefer the repo's own allowlist once init has written one" in {
    // Issue #14 turned this from a build-image.sh local into a shared function: start-proxy.sh has
    // to answer "which list must the running proxy be enforcing" with exactly the file the image
    // was built from, and two independent derivations of that is how the image and the file drift
    // apart with nothing noticing.
    val dir  = Files.createTempDirectory("sandbox-spec")
    val repo = Files.createTempDirectory("sandbox-spec-repo")
    Sandbox.extract(dir)
    Files.createDirectories(repo.resolve(".litter-box"))
    val scaffolded = repo.resolve(".litter-box/allowlist")
    Files.write(scaffolded, "example.invalid\n".getBytes(StandardCharsets.UTF_8))

    Path.of(libSays(dir, repo.toString, "effective_allowlist")).toRealPath() shouldBe
      scaffolded.toRealPath()
  }

  it should "take the repo root from the loop, not from its own location" in {
    // The bug this is the fix for: `REPO_ROOT="$(cd "$SANDBOX_DIR/.." && pwd)"` was correct only
    // while the scripts lived inside the repo they operated on. From the extraction cache it names
    // a directory under ~/.cache, so build-image.sh looked for .litter-box/Dockerfile there and
    // found nothing.
    val dir = Files.createTempDirectory("sandbox-spec")
    Sandbox.extract(dir)

    libSays(dir, "/some/consumer/repo", "printf 'REPO_ROOT=%s\\n' \"$REPO_ROOT\"") shouldBe
      "REPO_ROOT=/some/consumer/repo"
  }

  /** The list the fixture repo scaffolds, i.e. the one an operator edited and expects in force. */
  private val ScaffoldedList = "api.anthropic.com\nrepo.maven.apache.org\n"

  /** Any other list, standing in for the copy baked into a stale proxy image. */
  private val StaleList = "api.anthropic.com\n"

  /** A stand in for the docker CLI, first on PATH, so start-proxy.sh can be driven end to end in
    * CI, where there is no daemon and never will be (CONVENTIONS.md: `test/` stays Docker free).
    *
    * Scripted rather than mocked: the assertions below are about what the real script decides to
    * run, so the fake answers the handful of questions the script asks and records every argv it
    * was handed. The answers come from files in FAKE_DOCKER_STATE so one fake serves every
    * scenario, and `build` copying `in-force-after-build` over `in-force` is what lets a test say
    * whether the rebuild was the thing that fixed the mismatch.
    */
  private val FakeDocker = """#!/usr/bin/env bash
    |state="$FAKE_DOCKER_STATE"
    |printf '%s\n' "$*" >>"$state/commands"
    |case "$1" in
    |  network)
    |    # No network yet, so the script takes its create branch.
    |    case "$2" in inspect) exit 1 ;; *) exit 0 ;; esac ;;
    |  inspect) printf 'bridge\n'; exit 0 ;;
    |  logs) printf 'NOTICE Starting main loop. Accepting connections.\n'; exit 0 ;;
    |  exec)
    |    if [[ -f "$state/exec-fails" ]]; then exit 1; fi
    |    cat "$state/in-force"; exit 0 ;;
    |  build)
    |    if [[ -f "$state/in-force-after-build" ]]; then
    |      cp "$state/in-force-after-build" "$state/in-force"
    |    fi
    |    exit 0 ;;
    |esac
    |exit 0
    |""".stripMargin

  /** An extracted sandbox tree, a scaffolded repo and the fake docker's state, which together are
    * everything start-proxy.sh reaches for.
    */
  private case class ProxyFixture(sandbox: Path, repo: Path, state: Path, bin: Path)

  private def proxyFixture(inForce: String): ProxyFixture =
    val sandbox = Files.createTempDirectory("sandbox-spec")
    Sandbox.extract(sandbox)
    val repo = Files.createTempDirectory("sandbox-spec-repo")
    Files.createDirectories(repo.resolve(".litter-box"))
    Files.writeString(repo.resolve(".litter-box/allowlist"), ScaffoldedList)
    val state = Files.createTempDirectory("sandbox-spec-docker")
    Files.writeString(state.resolve("in-force"), inForce)
    val bin    = Files.createTempDirectory("sandbox-spec-bin")
    val docker = bin.resolve("docker")
    Files.writeString(docker, FakeDocker)
    docker.toFile.setExecutable(true) shouldBe true
    ProxyFixture(sandbox, repo, state, bin)

  /** Runs the real start-proxy.sh against the fixture and returns its exit code and its whole
    * output, stderr folded in because every decision the script narrates it logs there.
    */
  private def runStartProxy(f: ProxyFixture): (Int, String) =
    val pb = new ProcessBuilder("bash", f.sandbox.resolve("start-proxy.sh").toString)
    pb.redirectErrorStream(true)
    val env = pb.environment()
    env.put("PATH", s"${f.bin}${java.io.File.pathSeparator}${env.get("PATH")}")
    env.put(Settings.RepoRootEnvVar, f.repo.toString)
    env.put("FAKE_DOCKER_STATE", f.state.toString)
    val proc = pb.start()
    val out  = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    (proc.waitFor(), out)

  /** Every docker argv the run asked for, one per line, in order. */
  private def dockerCommands(f: ProxyFixture): List[String] =
    val log = f.state.resolve("commands")
    if Files.isRegularFile(log) then Files.readAllLines(log).asScala.toList else Nil

  "start-proxy.sh" should "start the proxy and build nothing when it already enforces the effective allowlist" in {
    val f          = proxyFixture(inForce = ScaffoldedList)
    val (rc, logs) = runStartProxy(f)

    withClue(logs) {
      rc shouldBe 0
      dockerCommands(f).count(_.startsWith("build ")) shouldBe 0
      dockerCommands(f).count(_.startsWith("run ")) shouldBe 1
      logs should include("enforcing")
    }
  }

  it should "rebuild the image exactly once and recreate the container when the fence in force differs" in {
    // The whole point of issue #14: the allowlist is COPYed into the proxy image, so an operator's
    // edit only takes effect at the next image build. A run that starts a proxy enforcing the old
    // copy is the silent `403 Filtered` the operator cannot explain from the file they edited.
    val f = proxyFixture(inForce = StaleList)
    Files.writeString(f.state.resolve("in-force-after-build"), ScaffoldedList)

    val (rc, logs) = runStartProxy(f)

    withClue(logs) {
      rc shouldBe 0
      dockerCommands(f).count(_.startsWith("build ")) shouldBe 1
      // Recreated, not reused: a rebuilt image under a surviving container changes nothing.
      dockerCommands(f).count(_.startsWith("run ")) shouldBe 2
      dockerCommands(f).count(_.startsWith("rm -f ")) shouldBe 2
      logs should include("rebuilding")
    }
  }

  it should "abort instead of reporting success when the fence still differs after the rebuild" in {
    // One retry, not a loop: a second rebuild cannot fix what the first one did not, and starting
    // anyway hands the operator a fence their allowlist says cannot be there.
    val f          = proxyFixture(inForce = StaleList)
    val (rc, logs) = runStartProxy(f)

    withClue(logs) {
      rc should not be 0
      dockerCommands(f).count(_.startsWith("build ")) shouldBe 1
      logs should include("still does not enforce")
      logs should not include "up on"
    }
  }

  it should "name docker, and rebuild nothing, when the list in force cannot be read at all" in {
    // `proxy_enforces` answers 2 here rather than folding the docker fault into "differs": a
    // rebuild would spend minutes and then abort under a message blaming an allowlist that is very
    // likely correct.
    val f = proxyFixture(inForce = ScaffoldedList)
    Files.writeString(f.state.resolve("exec-fails"), "")

    val (rc, logs) = runStartProxy(f)

    withClue(logs) {
      rc should not be 0
      dockerCommands(f).count(_.startsWith("build ")) shouldBe 0
      logs should include("docker exec failed")
      logs should not include "up on"
    }
  }

