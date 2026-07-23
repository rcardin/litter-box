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

  "the extracted lib.sh" should "take the repo root from the loop, not from its own location" in {
    // The bug this is the fix for: `REPO_ROOT="$(cd "$SANDBOX_DIR/.." && pwd)"` was correct only
    // while the scripts lived inside the repo they operated on. From the extraction cache it names
    // a directory under ~/.cache, so build-image.sh looked for .litter-box/Dockerfile there and
    // found nothing.
    val dir = Files.createTempDirectory("sandbox-spec")
    Sandbox.extract(dir)

    val script = """set -eu
                   |source "$1"
                   |printf 'REPO_ROOT=%s\n' "$REPO_ROOT"
                   |""".stripMargin
    val pb     = new ProcessBuilder("bash", "-c", script, "bash", dir.resolve("lib.sh").toString)
    pb.redirectErrorStream(true)
    pb.environment().put(Settings.RepoRootEnvVar, "/some/consumer/repo")
    val proc = pb.start()
    val out  = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    proc.waitFor() shouldBe 0

    out.linesIterator.toList should contain("REPO_ROOT=/some/consumer/repo")
  }
