package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/** Unit tests for the slice-2 part-A live handlers (LiveHarnessFs, LiveStatusLog, LiveNotify,
  * LiveClock), against the bash-parity contracts documented in Live.scala/Caps.scala.
  */
class LiveSpec extends AnyFlatSpec with Matchers:

  private def tempRoot(): Path = Files.createTempDirectory("live-spec")

  private def readLines(p: Path): List[String] =
    Files.readAllLines(p).asScala.toList

  // The artifact directory is no longer the hardcoded "logs": it is the `log-dir` config key, whose
  // reference default is ".litter-box/logs". Deriving the expected location from `Config()` rather
  // than re-typing the literal is deliberate. If the reference default ever moves again, these
  // tests follow it instead of pinning a stale path and failing for the wrong reason.
  private val logDir = Config().logDir

  /** Where `LiveStatusLog` is expected to land its status.jsonl for a given root, when the caller
    * lets the `logDir` parameter fall back to its configured default.
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

  it should "write status.jsonl under an explicit non-default logDir" in {
    val root   = tempRoot()
    val custom = "custom/logs"
    // The third parameter is the whole point of the change: a consumer repo can put the loop's
    // artifacts wherever its own `log-dir` says, so an explicitly passed directory must win over
    // the configured default. Asserting the default location stays EMPTY is the half that would
    // silently pass if the writer ignored the parameter and kept using its fallback.
    val log = LiveStatusLog(root, "1", custom)

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

  it should "read the three template paths under prompts/" in {
    val root = tempRoot()
    Files.createDirectories(root.resolve("prompts"))
    Files.write(root.resolve("prompts/iterate-prompt.md"), "ITERATE".getBytes)
    Files.write(root.resolve("prompts/fix-prompt.md"), "FIX".getBytes)
    Files.write(root.resolve("prompts/review-prompt.md"), "REVIEW".getBytes)
    val fs = LiveHarnessFs(root)

    fs.readTemplate(Template.Iterate) shouldBe "ITERATE"
    fs.readTemplate(Template.Fix) shouldBe "FIX"
    fs.readTemplate(Template.Review) shouldBe "REVIEW"
  }

  it should "read CONTEXT.md for conventions()" in {
    val root = tempRoot()
    Files.write(root.resolve("CONTEXT.md"), "conventions".getBytes)
    val fs = LiveHarnessFs(root)

    fs.conventions() shouldBe "conventions"
  }
