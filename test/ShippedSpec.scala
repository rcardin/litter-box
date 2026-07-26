package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Unit tests for the shipped-tree machinery, run against EVERY tree there is.
  *
  * THE RULE THIS SPEC IS WRITTEN TO (GitHub issues #9 and #15): a file that works in a checkout and
  * is absent from every install is the failure both issues turned out to be. So the two load-bearing
  * assertions are "everything in the source tree actually ships" and "what ships lands on disk whole
  * and executable" — the two ways a consumer install can be silently incomplete — and they are
  * asserted per tree rather than per object, so a third tree added later inherits the whole spec by
  * appearing in `Trees`.
  */
class ShippedSpec extends AnyFlatSpec with Matchers:

  /** Every tree that ships. A new `Shipped` missing from here is a tree with no anti-rot check,
    * which is the same hole one directory over.
    */
  private val Trees: List[Shipped] = List(Sandbox, Observe)

  /** The source tree the resources are packaged from. Found by walking up from the JVM cwd so the
    * test does not care whether the runner starts in the project root or a subdirectory.
    */
  private def sourceTree(tree: Shipped): Path =
    var dir: Path = Path.of("").toAbsolutePath.normalize
    var found     = Option.empty[Path]
    while found.isEmpty && dir != null do
      val candidate = dir.resolve("resources").resolve(tree.treeName)
      if Files.isDirectory(candidate) then found = Some(candidate)
      dir = dir.getParent
    found.getOrElse(fail(s"could not locate resources/${tree.treeName} from the JVM cwd"))

  Trees.foreach { tree =>

    s"the ${tree.treeName} manifest" should
      s"name every file under resources/${tree.treeName}, and no others" in {
        // The failure this exists to prevent: a new script that works in a checkout — where the
        // whole directory is on the classpath — and is missing from every install, because
        // `ShippedFiles` is what `extract` copies and nothing else is consulted. A directory listing
        // cannot be asked of a jar's classloader portably, so the list is hand-written, and
        // hand-written lists rot.
        val dir = sourceTree(tree)
        val onDisk = Files
          .walk(dir)
          .iterator
          .asScala
          .filter(Files.isRegularFile(_))
          .map(dir.relativize(_).toString)
          .toList
          .sorted

        onDisk shouldBe tree.ShippedFiles.sorted
      }

    it should "be readable out of the artifact, byte for byte with the source tree" in {
      val dir = sourceTree(tree)
      tree.ShippedFiles.foreach { rel =>
        withClue(s"$rel: ") {
          tree.builtIn(rel) shouldBe Files.readAllBytes(dir.resolve(rel))
        }
      }
    }

    s"extracting ${tree.treeName}" should "write every shipped file, with the scripts executable" in {
      val dir = Files.createTempDirectory("shipped-spec")
      tree.extract(dir)

      tree.ShippedFiles.foreach { rel =>
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
      val dir   = Files.createTempDirectory("shipped-spec")
      val first = tree.ShippedFiles.head
      tree.extract(dir)
      Files.write(dir.resolve(first), "clobbered".getBytes(StandardCharsets.UTF_8))

      tree.extract(dir)

      new String(Files.readAllBytes(dir.resolve(first)), StandardCharsets.UTF_8) should
        not be "clobbered"
    }

    it should "leave no temporary files behind" in {
      // The staged-then-renamed write is invisible by design, and the way it stops being invisible
      // is a cache directory that accumulates .tmp siblings until someone notices.
      val dir = Files.createTempDirectory("shipped-spec")
      tree.extract(dir)

      val stray = Files
        .walk(dir)
        .iterator
        .asScala
        .filter(Files.isRegularFile(_))
        .map(dir.relativize(_).toString)
        .filterNot(tree.ShippedFiles.contains)
        .toList

      stray shouldBe empty
    }

    it should "never expose a shipped file at less than its full length" in {
      // The race #9's first pass got wrong: writing dest directly truncates it to zero before the
      // bytes land, and `resolve` decides a tree is complete by asking whether its files exist. A
      // second process landing in that window skips extraction and execs an empty lib.sh.
      val dir      = Files.createTempDirectory("shipped-spec")
      val expected = tree.ShippedFiles.map(rel => rel -> tree.builtIn(rel).length.toLong).toMap
      val short    = java.util.concurrent.ConcurrentLinkedQueue[String]()

      val writing = java.util.concurrent.atomic.AtomicBoolean(true)
      val writers = (1 to 4).map(_ => Thread(() => (1 to 20).foreach(_ => tree.extract(dir))))
      val reader = Thread { () =>
        while writing.get() do
          tree.ShippedFiles.foreach { rel =>
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

    s"resolving ${tree.treeName}" should "extract on first use and re-extract only what has gone missing" in {
      val home  = Files.createTempDirectory("shipped-spec-home")
      val first = tree.ShippedFiles.head

      val dir = tree.resolve(home)
      dir shouldBe tree.cacheDir(home)
      Files.isRegularFile(dir.resolve(first)) shouldBe true

      // The half-extracted case (^C, a full disk): the directory exists and is not empty, so a check
      // on the directory alone would pass and the loop would die inside a preflight script instead.
      Files.delete(dir.resolve(first))
      tree.resolve(home)
      Files.isRegularFile(dir.resolve(first)) shouldBe true
    }

    it should "key the cache on content, so an upgraded binary never reuses an old tree" in {
      val home = Files.createTempDirectory("shipped-spec-home")
      tree.cacheDir(home).getFileName.toString shouldBe tree.digest
      tree.digest should fullyMatch regex "[0-9a-f]{12}"
    }
  }

  "two trees" should "never share a cache directory" in {
    // They are extracted independently and one is upgraded without the other, so a shared directory
    // would let one tree's digest decide the other's staleness.
    val home = Files.createTempDirectory("shipped-spec-home")
    Trees.map(_.cacheDir(home)).distinct.size shouldBe Trees.size
  }

  "every observability subcommand" should "front a script that actually ships" in {
    // The gap issue #15 is about, one tier in: a `litter-box watch` naming a file absent from the
    // manifest is a subcommand that dies on a missing path at the first invocation from an install,
    // and a checkout would never notice.
    ObserveTool.values.map(_.script).toList.foreach { script =>
      Observe.ShippedFiles should contain(script)
    }
  }

  it should "front a script the extraction marks executable" in {
    val dir = Files.createTempDirectory("shipped-spec")
    Observe.extract(dir)
    ObserveTool.values.foreach { tool =>
      withClue(s"${tool.subcommand}: ") {
        Files.isExecutable(dir.resolve(tool.script)) shouldBe true
      }
    }
  }

  "the shipped trees" should "not carry the Docker-dependent developer tests" in {
    // sandbox/test/ is deliberately outside resources/: a released binary has no business shipping
    // its own test suite, and those scripts need Docker and network egress to say anything.
    Trees.flatMap(_.ShippedFiles).find(_.contains("test")) shouldBe None
  }

  "the watch scripts" should "resolve their helpers from the extracted tree" in {
    // Both scripts source `$SCRIPT_DIR/lib/…`, which is what makes them work off an install exactly
    // as they work in a checkout — but only if `lib/` is extracted alongside them. A manifest that
    // shipped the two entry points and forgot the helpers would pass every assertion above and fail
    // at the first `litter-box watch`.
    val dir = Files.createTempDirectory("shipped-spec")
    Observe.extract(dir)

    Files.isRegularFile(dir.resolve("lib/banner.sh")) shouldBe true
    Files.isRegularFile(dir.resolve("lib/claude-fmt.jq")) shouldBe true
  }

  it should "render a banner when sourced from the extracted tree" in {
    // bash is the only thing that can truthfully answer whether the extracted `lib/banner.sh` is a
    // sourceable shell library, and this needs no Docker, no jq-free fallback and no loop: it is the
    // end-to-end shape of what `litter-box watch` does on a repo that has never run anything.
    val dir = Files.createTempDirectory("shipped-spec")
    Observe.extract(dir)

    assume(
      Main.findOnPath(sys.env.getOrElse("PATH", ""), "jq", p => Files.isExecutable(Path.of(p))).isDefined,
      "jq not found on PATH (required for banner rendering)"
    )

    val script = s"""set -eu
                    |. "$$1/lib/banner.sh"
                    |render_banner "$$1/does-not-exist.jsonl" 1 1000
                    |""".stripMargin
    val pb = new ProcessBuilder("bash", "-c", script, "bash", dir.toString)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out  = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)

    withClue(out) {
      proc.waitFor() shouldBe 0
      out should include("no run yet")
    }
  }
