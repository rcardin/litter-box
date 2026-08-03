package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/** Unit tests for the slice-2 part-A live handlers (LiveHarnessFs, LiveStatusLog, LiveNotify,
  * LiveClock), against the bash-parity contracts documented in Live.scala/Caps.scala.
  */
class LiveSpec extends AnyFlatSpec with Matchers:

  // Both handlers under test take their config-derived names off a `Config` in scope, the same way
  // `Machine` does. The reference defaults are what every test below wants, so it is summoned once
  // here rather than restated per construction; the one test that needs another `log-dir` passes
  // its own `Config` explicitly.
  private given Config = Config()

  private def tempRoot(): Path = Files.createTempDirectory("live-spec")

  private def readLines(p: Path): List[String] =
    Files.readAllLines(p).asScala.toList

  // The artifact directory is no longer the hardcoded "logs": it is the `log-dir` config key, whose
  // reference default is ".litter-box/logs". Deriving the expected location from `Config()` rather
  // than re-typing the literal is deliberate. If the reference default ever moves again, these
  // tests follow it instead of pinning a stale path and failing for the wrong reason.
  private val logDir = Config().logDir

  /** Where `LiveStatusLog` is expected to land its status.jsonl for a given root, when the `Config`
    * in scope carries the reference `log-dir`.
    */
  private def defaultStatusFile(root: Path): Path =
    root.resolve(logDir).resolve("status.jsonl")

  // ---- LiveStatusLog -----------------------------------------------------------------------

  "LiveStatusLog" should "emit one JSON line with the exact field order/types for a fixed event" in {
    val root  = tempRoot()
    val log   = LiveStatusLog(root, "1234567890")
    val event = StatusEvent(
      iter = 3,
      issue = "999",
      phase = "FAST",
      state = "GREEN",
      pass = 1,
      budget = 2,
      logfile = s"$logDir/issue-999.fast.log",
      detail = "ok"
    )

    log.append(event)

    val lines = readLines(defaultStatusFile(root))
    lines should have size 1
    // The logfile field travels through the writer verbatim (it is already repo-relative), so the
    // expected literal is quoted into the pattern rather than spelled out: the directory segment
    // comes from the config default and may contain regex metacharacters such as the leading dot.
    val quotedLogfile = java.util.regex.Pattern.quote(s"$logDir/issue-999.fast.log")
    val pattern       =
      ("""\{"ts":(\d+),"pid":(\d+),"run":"1234567890","iter":3,"issue":"999","phase":"FAST","state":"GREEN","pass":1,"budget":2,"logfile":"""" + quotedLogfile + """","detail":"ok"\}""").r
    pattern.matches(lines.head) shouldBe true
  }

  it should "append two events as two lines" in {
    val root  = tempRoot()
    val log   = LiveStatusLog(root, "1")
    val event = StatusEvent(0, "1", "FAST", "START", 0, 0, "", "")

    log.append(event)
    log.append(event)

    val lines = readLines(defaultStatusFile(root))
    lines should have size 2
  }

  it should "sanitize detail: strip backslashes, strip double quotes, collapse newlines to spaces" in {
    val root  = tempRoot()
    val log   = LiveStatusLog(root, "1")
    val event = StatusEvent(0, "1", "FAST", "START", 0, 0, "", """a\b"c\nd""" + "\n" + "e")

    log.append(event)

    val line = readLines(defaultStatusFile(root)).head
    line should include(""""detail":"abcnd e"""")
    line should not include "\\"
  }

  it should "relativize a logfile path with a leading root/ prefix" in {
    val root     = tempRoot()
    val log      = LiveStatusLog(root, "1")
    val absolute = root.resolve(s"$logDir/x.log").toString
    val event    = StatusEvent(0, "1", "FAST", "START", 0, 0, absolute, "")

    log.append(event)

    val line = readLines(defaultStatusFile(root)).head
    line should include(s""""logfile":"$logDir/x.log"""")
  }

  it should "pass a foreign absolute logfile path through unchanged" in {
    val root  = tempRoot()
    val log   = LiveStatusLog(root, "1")
    val event = StatusEvent(0, "1", "FAST", "START", 0, 0, "/etc/foreign/path.log", "")

    log.append(event)

    val line = readLines(defaultStatusFile(root)).head
    line should include(""""logfile":"/etc/foreign/path.log"""")
  }

  it should "write status.jsonl under a non-default log-dir" in {
    val root   = tempRoot()
    val custom = "custom/logs"
    // The configured `log-dir` is the whole point of the change: a consumer repo can put the loop's
    // artifacts wherever its own config says, so the `Config` in scope must win over the reference
    // default. Asserting the default location stays EMPTY is the half that would silently pass if
    // the writer ignored its config and kept using the reference value.
    val log = LiveStatusLog(root, "1")(using Config(logDir = custom))

    log.append(StatusEvent(0, "1", "FAST", "START", 0, 0, "", ""))

    val written = root.resolve(custom).resolve("status.jsonl")
    Files.isRegularFile(written) shouldBe true
    readLines(written) should have size 1
    Files.exists(defaultStatusFile(root)) shouldBe false
  }

  it should "not throw when the status.jsonl parent path is blocked by a file" in {
    val root = tempRoot()
    // Block the configured log directory by planting a plain FILE where its first segment must
    // become a directory (".litter-box" for the reference default), so `createDirectories` for the
    // full ".litter-box/logs" chain cannot succeed. Blocking the leaf alone would no longer work
    // now that the default is nested: this keeps exercising the same failure mode as before, the
    // write path swallowing the IO error instead of letting a dead status line kill the loop.
    val blocked = Paths.get(Config().logDir).getName(0).toString
    Files.write(root.resolve(blocked), "blocked".getBytes)
    val log = LiveStatusLog(root, "1")

    noException should be thrownBy log.append(StatusEvent(0, "1", "FAST", "START", 0, 0, "", ""))
    Files.exists(defaultStatusFile(root)) shouldBe false
  }

  // ---- LiveStatusLog.declare (issue #40) -----------------------------------------------------

  "LiveStatusLog.declare" should "write a single well formed JSON line with kind:\"stages\"" in {
    val root = tempRoot()
    val log  = LiveStatusLog(root, "1234567890")

    log.declare(
      StageSet(
        stages = List(Stage("PICK", "pick", row = 1), Stage("FIX", "fix", row = 1, badge = true)),
        anchor = Some("PICK"),
        terminal = Some("DONE")
      )
    )

    val lines = readLines(defaultStatusFile(root))
    lines should have size 1
    val pattern =
      ("""\{"ts":(\d+),"pid":(\d+),"run":"1234567890","kind":"stages","anchor":"PICK","terminal":"DONE","stages":\[""" +
        """\{"phase":"PICK","chip":"pick","row":1,"badge":false\},""" +
        """\{"phase":"FIX","chip":"fix","row":1,"badge":true\}\]\}""").r
    pattern.matches(lines.head) shouldBe true
  }

  it should "leave append's own output shape unaffected: the two methods write to the same file " +
    "without either one's line shape leaking into the other's" in {
      val root = tempRoot()
      val log  = LiveStatusLog(root, "1")

      log.declare(StageSet(List(Stage("PICK", "pick", row = 1)), Some("PICK"), Some("DONE")))
      log.append(StatusEvent(0, "1", "PICK", "ok", 0, 0, "", ""))

      val lines = readLines(defaultStatusFile(root))
      lines should have size 2
      lines.head should include(""""kind":"stages"""")
      lines(1) should not include "\"kind\""
      lines(1) should include(""""phase":"PICK","state":"ok"""")
    }

  it should "declare an empty stage set (no anchor, no terminal) without crashing" in {
    val root = tempRoot()
    val log  = LiveStatusLog(root, "1")

    log.declare(StageSet(Nil, None, None))

    val line = readLines(defaultStatusFile(root)).head
    line should include(""""anchor":null""")
    line should include(""""terminal":null""")
    line should include(""""stages":[]""")
  }

  it should "sanitize phase/chip/anchor/terminal the same way append sanitizes detail: strip " +
    "backslashes, strip double quotes, collapse newlines to spaces" in {
      val root = tempRoot()
      val log  = LiveStatusLog(root, "1")

      log.declare(
        StageSet(
          stages = List(Stage("""a\b"c""" + "\n" + "d", """x\y"z""", row = 1)),
          anchor = Some("""p\i"ck"""),
          terminal = None
        )
      )

      val line = readLines(defaultStatusFile(root)).head
      line should include(""""phase":"abc d"""")
      line should include(""""chip":"xyz"""")
      line should include(""""anchor":"pick"""")
      line should not include "\\"
    }

  it should "strip carriage returns and every other C0 control character from phase/chip/anchor, " +
    "not merely backslash, quote and newline (issue #40 review MINOR 6)" in {
      val root = tempRoot()
      val log  = LiveStatusLog(root, "1")

      // Carriage return, tab and bell (\u0007) are all C0 control characters `clean` used to
      // leave untouched. A raw carriage return in particular is the one that matters most:
      // `chip` is printed straight into banner.sh's pinned chip rows with no second scrub at
      // the read end (unlike `detail`, whose one caller in banner.sh runs it through its own
      // gsub before printing), so a stray carriage return reaching a real terminal there would
      // move the cursor back to the start of the line on every redraw and corrupt whatever
      // watch.sh painted.
      log.declare(
        StageSet(
          stages = List(Stage("PI\r\tCK", "pi\u0007ck", row = 1)),
          anchor = Some("PI\rCK"),
          terminal = None
        )
      )

      val line = readLines(defaultStatusFile(root)).head
      line should include(""""phase":"PICK"""")
      line should include(""""chip":"pick"""")
      line should include(""""anchor":"PICK"""")
      line should not include "\r"
      line should not include "\t"
      line should not include "\u0007"
    }

  it should "strip DEL and the C1 range 0x80-0x9F, not just C0 control characters " +
    "(issue #40 review round 2, MINOR 3)" in {
      val root = tempRoot()
      val log  = LiveStatusLog(root, "1")

      // DEL (0x7F) sits right after the C0 range `filterNot(_ < ' ')` already covered, and the
      // C1 range 0x80-0x9F sits right after DEL; neither is less than ' ' (0x20), so the old
      // predicate let both through untouched. U+009B in particular is CSI, the byte a terminal
      // that recognizes 8-bit C1 codes treats as equivalent to the two-byte escape sequence
      // introducer ESC '[', so it is the closest thing to an ANSI injection this single
      // character filter can be asked to stop: a `chip` carrying it would reach banner.sh's
      // pinned chip rows with no second scrub at the read end, on a terminal that acts on it,
      // the same corruption MINOR 6's carriage-return case already closed for C0.
      log.declare(
        StageSet(
          stages = List(Stage("PICK", "pick\u007fck", row = 1)),
          anchor = Some("PICK\u009bCK"),
          terminal = None
        )
      )

      val line = readLines(defaultStatusFile(root)).head
      line should include(""""phase":"PICK"""")
      line should include(""""chip":"pickck"""")
      line should include(""""anchor":"PICKCK"""")
      line should not include "\u007f"
      line should not include "\u009b"
    }

  it should "not throw when the status.jsonl parent path is blocked by a file" in {
    val root    = tempRoot()
    val blocked = Paths.get(Config().logDir).getName(0).toString
    Files.write(root.resolve(blocked), "blocked".getBytes)
    val log = LiveStatusLog(root, "1")

    noException should be thrownBy log.declare(StageSet(Nil, None, None))
    Files.exists(defaultStatusFile(root)) shouldBe false
  }

  // ---- LiveNotify ---------------------------------------------------------------------------

  "LiveNotify" should "run the NOTIFY_CMD bash stub with $msg exported, verbatim bash-suite shape" in {
    val root   = tempRoot()
    val out    = root.resolve("notify.log")
    val logged = scala.collection.mutable.ArrayBuffer.empty[String]
    val notify = LiveNotify(
      notifyCmd = Some(s"""printf "%s\n" "$$msg" >> "$out""""),
      ntfyTopic = None,
      log = logged.append(_)
    )

    notify.notify("hello world")

    Files.readAllLines(out).asScala.toList shouldBe List("hello world")
    logged shouldBe empty
  }

  it should "log notify failed (ignored) and not throw when NOTIFY_CMD fails" in {
    val logged = scala.collection.mutable.ArrayBuffer.empty[String]
    val notify = LiveNotify(notifyCmd = Some("exit 1"), ntfyTopic = None, log = logged.append(_))

    noException should be thrownBy notify.notify("hello")

    logged shouldBe List("notify failed (ignored)")
  }

  it should "log the exact no-channel message when neither NOTIFY_CMD nor NTFY_TOPIC is set" in {
    val logged = scala.collection.mutable.ArrayBuffer.empty[String]
    val notify = LiveNotify(notifyCmd = None, ntfyTopic = None, log = logged.append(_))

    notify.notify("hello world")

    logged shouldBe List("notify (no channel configured): hello world")
  }

  it should "treat an empty-string NOTIFY_CMD as unset (falls through to log-only)" in {
    val logged = scala.collection.mutable.ArrayBuffer.empty[String]
    val notify = LiveNotify(notifyCmd = Some(""), ntfyTopic = None, log = logged.append(_))

    notify.notify("hi")

    logged shouldBe List("notify (no channel configured): hi")
  }

  // ---- LiveHarnessFs -------------------------------------------------------------------------

  "LiveHarnessFs" should "create parent directories on write and round-trip through read" in {
    val root = tempRoot()
    val fs   = LiveHarnessFs(root)

    fs.write(s"$logDir/issue-999.prompt.txt", "hello")

    fs.read(s"$logDir/issue-999.prompt.txt") shouldBe "hello"
  }

  it should "report the correct byte size" in {
    val root = tempRoot()
    val fs   = LiveHarnessFs(root)
    fs.write("a.txt", "hello")

    fs.sizeBytes("a.txt") shouldBe 5L
  }

  it should "report size 0 for a missing file instead of throwing" in {
    val root = tempRoot()
    val fs   = LiveHarnessFs(root)

    fs.sizeBytes(s"$logDir/never-created.patch") shouldBe 0L
  }

  it should "report stopRequested false, then true after STOP.md is created" in {
    val root = tempRoot()
    val fs   = LiveHarnessFs(root)

    fs.stopRequested() shouldBe false

    Files.write(root.resolve("STOP.md"), "stop".getBytes)

    fs.stopRequested() shouldBe true
  }

  it should "read templates through Prompts: built-in by default, ejected file on top" in {
    val root = tempRoot()
    val fs   = LiveHarnessFs(root)

    // No ejected override anywhere under root: falls through to the artifact copy.
    fs.readTemplate(Template.Iterate) shouldBe Prompts.builtIn(Template.Iterate)

    // An ejected .litter-box/prompts/<file> wins over the built-in, per-template.
    Files.createDirectories(root.resolve(Prompts.EjectDir))
    Files.write(root.resolve(Prompts.EjectDir).resolve("fix-prompt.md"), "FIX".getBytes)

    fs.readTemplate(Template.Fix) shouldBe "FIX"
    fs.readTemplate(Template.Review) shouldBe Prompts.builtIn(Template.Review)
  }

  it should "read CONTEXT.md for conventions()" in {
    val root = tempRoot()
    Files.write(root.resolve("CONTEXT.md"), "conventions".getBytes)
    val fs = LiveHarnessFs(root)

    fs.conventions() shouldBe "conventions"
  }
