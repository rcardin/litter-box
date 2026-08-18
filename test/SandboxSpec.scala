package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Unit tests for what the sandbox scripts DO once they are on disk.
  *
  * Getting them there is `ShippedSpec`'s subject, not this one's, and has been since issue #15 gave
  * the observability scripts a tree of their own: extraction, the manifest's anti-rot check and the
  * cache key are properties of every shipped tree, so asserting them per object would have been the
  * second copy that only covers one. What is left here is everything specific to THESE scripts —
  * where `lib.sh` looks for the allowlist, what `start-proxy.sh` decides — and all of it runs the
  * real bash against an extracted tree.
  */
class SandboxSpec extends AnyFlatSpec with Matchers:

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


  // ===============================================================================================
  // ANTHROPIC_MODEL forwarding (issue #73)
  // ===============================================================================================

  /** A second stand in for the docker CLI, answering the questions the two model touched runners
    * ask (`info`, `image inspect`, `inspect -f`, `run`, `logs`, `wait`, `rm`) and recording the
    * `run` invocation ONE ARGUMENT PER LINE.
    *
    * Per line and not `"$*"` like the proxy fixture above, because the claim under test is about
    * argv ELEMENTS: a model identifier comes out of a file the consumer owns, and it is safe only
    * while it stays one separate, quoted element inside the runner's bash array. A recording that
    * flattened the argv back into a string could not tell that apart from a command line built by
    * concatenation, which is the failure mode.
    */
  private val ArgvDocker = """#!/usr/bin/env bash
    |state="$FAKE_DOCKER_STATE"
    |case "$1" in
    |  run)    printf '%s\n' "$@" >"$state/run-argv" ;;
    |  wait)   printf '0\n' ;;
    |  inspect) printf 'true\n' ;;
    |esac
    |exit 0
    |""".stripMargin

  /** An extracted sandbox tree, the fake docker's state and the bin directory holding it. */
  private case class RunnerFixture(sandbox: Path, state: Path, bin: Path)

  private def runnerFixture(): RunnerFixture =
    val sandbox = Files.createTempDirectory("sandbox-spec")
    Sandbox.extract(sandbox)
    val state  = Files.createTempDirectory("sandbox-spec-docker")
    val bin    = Files.createTempDirectory("sandbox-spec-bin")
    val docker = bin.resolve("docker")
    Files.writeString(docker, ArgvDocker)
    docker.toFile.setExecutable(true) shouldBe true
    RunnerFixture(sandbox, state, bin)

  /** The `docker run` argv the fixture recorded, one element per entry, in order. */
  private def runArgv(f: RunnerFixture): List[String] =
    val recorded = f.state.resolve("run-argv")
    if Files.isRegularFile(recorded) then Files.readAllLines(recorded).asScala.toList else Nil

  /** A repository the way `run-agent.sh` needs to find one: a work tree with a commit and an
    * `origin/main` ref for its `git archive` to read. The remote is faked with a local ref rather
    * than a real one, since nothing here is allowed to reach the network.
    */
  private def repoWithOriginMain(): Path =
    val repo = Files.createTempDirectory("sandbox-spec-repo")
    Files.writeString(repo.resolve("README.md"), "hello\n")
    LiveProc.run(repo, Seq("git", "init", "--quiet"))
    LiveProc.run(repo, Seq("git", "config", "user.email", "t@t"))
    LiveProc.run(repo, Seq("git", "config", "user.name", "t"))
    LiveProc.run(repo, Seq("git", "config", "commit.gpgsign", "false"))
    LiveProc.run(repo, Seq("git", "add", "-A"))
    LiveProc.run(repo, Seq("git", "commit", "--quiet", "-m", "init"))
    LiveProc.run(repo, Seq("git", "update-ref", "refs/remotes/origin/main", "HEAD"))
    repo

  /** Runs one of the two runners against the fake docker, with a dummy credential and a tmp root of
    * its own so nothing lands in the operator's `$HOME`.
    */
  private def runRunner(
      f: RunnerFixture,
      script: String,
      cwd: Path,
      args: Seq[String],
      extraEnv: Map[String, String]
  ): (Int, String) =
    val pb = new ProcessBuilder((f.sandbox.resolve(script).toString +: args).asJava)
    pb.directory(cwd.toFile)
    pb.redirectErrorStream(true)
    val env = pb.environment()
    env.put("PATH", s"${f.bin}${java.io.File.pathSeparator}${env.get("PATH")}")
    env.put("FAKE_DOCKER_STATE", f.state.toString)
    env.put("CLAUDE_CODE_OAUTH_TOKEN", "dummy-token")
    env.remove("ANTHROPIC_API_KEY")
    env.put("LITTER_BOX_SANDBOX_TMP_ROOT", Files.createTempDirectory("sandbox-spec-tmp").toString)
    env.remove(Settings.AgentModelEnvVar)
    extraEnv.foreach((k, v) => env.put(k, v))
    val proc = pb.start()
    val out  = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    (proc.waitFor(), out)

  private def runAgent(f: RunnerFixture, extraEnv: Map[String, String]): (Int, String) =
    val repo   = repoWithOriginMain()
    val prompt = repo.resolve("prompt.txt")
    Files.writeString(prompt, "do the thing\n")
    runRunner(
      f,
      "run-agent.sh",
      repo,
      Seq(prompt.toString, repo.resolve("out.patch").toString),
      extraEnv
    )

  private def runReviewer(f: RunnerFixture, extraEnv: Map[String, String]): (Int, String) =
    runRunner(
      f,
      "run-reviewer.sh",
      f.sandbox,
      Seq.empty,
      extraEnv + ("REVIEW_PROMPT" -> "judge this")
    )

  "run-agent.sh" should "pass the model the loop asked for as one -e ANTHROPIC_MODEL argument" in {
    val f          = runnerFixture()
    val (rc, logs) = runAgent(f, Map(Settings.AgentModelEnvVar -> "strong-model"))

    withClue(logs) {
      rc shouldBe 0
      // `contain inOrder` only checks relative order, not adjacency, so it cannot tell an
      // `-e ANTHROPIC_MODEL=strong-model` pair from the flag landing elsewhere in the argv with
      // the model turning into a stray positional argument. `sliding(2)` proves adjacency.
      runArgv(f).sliding(2).toList should contain(List("-e", "ANTHROPIC_MODEL=strong-model"))
    }
  }

  it should "keep a model holding a space as ONE argv element" in {
    // The injection the risk list names: a model identifier comes out of `.litter-box/config.conf`,
    // a file the consumer owns, and the only thing that keeps it from becoming extra words on a
    // `docker run` is that it never leaves the bash array it is quoted inside.
    val f          = runnerFixture()
    val (rc, logs) = runAgent(f, Map(Settings.AgentModelEnvVar -> "strong model"))

    withClue(logs) {
      rc shouldBe 0
      runArgv(f) should contain("ANTHROPIC_MODEL=strong model")
    }
  }

  it should "pass NO model argument at all when the loop supplied none" in {
    // Absent, never present-and-empty: an empty `-e ANTHROPIC_MODEL=` would clobber an
    // `ENV ANTHROPIC_MODEL` a consumer set in their own `.litter-box/Dockerfile`, which is the
    // route issue #73 replaces.
    val f          = runnerFixture()
    val (rc, logs) = runAgent(f, Map.empty)

    withClue(logs) {
      rc shouldBe 0
      runArgv(f).filter(_.startsWith("ANTHROPIC_MODEL")) shouldBe empty
    }
  }

  it should "treat an empty model variable as no model at all" in {
    val f          = runnerFixture()
    val (rc, logs) = runAgent(f, Map(Settings.AgentModelEnvVar -> ""))

    withClue(logs) {
      rc shouldBe 0
      runArgv(f).filter(_.startsWith("ANTHROPIC_MODEL")) shouldBe empty
    }
  }

  "run-reviewer.sh" should "pass the model the loop asked for as one -e ANTHROPIC_MODEL argument" in {
    val f          = runnerFixture()
    val (rc, logs) = runReviewer(f, Map(Settings.AgentModelEnvVar -> "cold-model"))

    withClue(logs) {
      rc shouldBe 0
      // Adjacency, not just relative order: see the matching comment on run-agent.sh's test above.
      runArgv(f).sliding(2).toList should contain(List("-e", "ANTHROPIC_MODEL=cold-model"))
    }
  }

  it should "pass NO model argument at all when the loop supplied none, and keep the deny flags positional" in {
    // The second half is the back compat contract the model must not disturb: the reviewer's deny
    // flags travel as POSITIONAL arguments after the `_` placeholder, because the prompt itself
    // rides in an env var and must never enter argv.
    val f          = runnerFixture()
    val (rc, logs) = runReviewer(f, Map.empty)

    withClue(logs) {
      rc shouldBe 0
      runArgv(f).filter(_.startsWith("ANTHROPIC_MODEL")) shouldBe empty
      runArgv(f) should contain inOrder ("_", "--disallowed-tools")
    }
  }

  it should "still honour the prompt as $1 with no model supplied" in {
    val f          = runnerFixture()
    val (rc, logs) = runRunner(f, "run-reviewer.sh", f.sandbox, Seq("judge this"), Map.empty)

    withClue(logs) {
      rc shouldBe 0
      runArgv(f) should contain("REVIEW_PROMPT=judge this")
    }
  }
