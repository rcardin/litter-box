package in.rcard.litterbox

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Production capability handlers: no effect-library machinery, just plain trait implementations over
  * `java.nio.file` and `scala.sys.process` / `java.lang.ProcessBuilder`, matching `harness/loop.sh`
  * byte-for-byte where the bash reference is explicit. Task 1 built the four handlers that need no
  * `gh`/`git` subprocess work: `HarnessFs`, `StatusLog`, `Notify`, `Clock`. This task (slice 2,
  * part B) adds `GitHub`, `Git`, `AgentDispatch`, `GateRunner` to the same file, plus the shared
  * `LiveProc` subprocess helper they all build on.
  */

/** loop.sh's shared `log()` helper (loop.sh:141), reused by every Live handler that needs to log
  * (Notify today; GitHub/Git/AgentDispatch/GateRunner in task 2). Format is
  * `[loop HH:MM:SS] <msg>`, written to stderr, verbatim parity, since the slice 3 parity oracle
  * greps log lines.
  */
object LiveLog extends Log:
  private val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

  def log(msg: String): Unit =
    val ts = java.time.LocalTime.now().format(fmt)
    System.err.println(s"[loop $ts] $msg")

/** `mkdir -p "$(dirname "$file")"` — the one-liner every site that writes or redirects into a
  * harness-owned path needs before it writes, since bash's `>`/`>>` are always preceded by the loop
  * having created `$LOG_DIR` (loop.sh:121) and the Scala side cannot assume that for paths nested
  * deeper (e.g. `$patch.apply.err`). Total: a path with no parent (a bare filename) is a no-op, not
  * a failure.
  */
private[litterbox] object LiveFiles:
  def ensureParentDir(p: Path): Unit =
    Option(p.getParent).foreach(Files.createDirectories(_))
    ()

/** Filesystem the harness owns: prompts, logs, markers, the stop file. All paths passed into
  * `write`/`read`/`sizeBytes` arrive repo-relative from Machine (already carrying the configured
  * `log-dir`) and are resolved against `root`; `root` is a constructor parameter (never hardcoded to
  * the process cwd) so tests can point it at a temp dir.
  *
  * The two config-derived names this handler needs (`stop-file`, `conventions`) arrive as the whole
  * `Config`, threaded the same way `Machine`'s own methods take it — `(using cfg: Config)` — rather
  * than unpacked into loose `String`s at every call site. `Main` already has the `Config` in scope
  * as a `given` when it wires this handler, so the summon is free there and tests say which config
  * they mean instead of leaning on a per-parameter default.
  */
final class LiveHarnessFs(root: Path)(using cfg: Config) extends HarnessFs:

  /** loop.sh:623 checks `$REPO_ROOT/STOP.md`, i.e. repo-root-relative, not under harness/. The name
    * is `stop-file` in the config now, so a consumer repo that already means something else by
    * `STOP.md` can pick another.
    */
  def stopRequested(): Boolean =
    Files.isRegularFile(root.resolve(cfg.stopFile))

  /** The skeleton for `t`, resolved by `Prompts`: an ejected `.litter-box/prompts/<file>` if the
    * repo has one, else the copy that ships in the artifact. Slice 2 read
    * `prompts/<file>` out of the consumer repo, which made every consumer the owner of the
    * protocol; `Prompts` explains why that moved.
    */
  def readTemplate(t: Template): String =
    Prompts.resolve(root, t)

  /** loop.sh:119: `CONVENTIONS="$REPO_ROOT/CONTEXT.md"`, now the `conventions` config key. */
  def conventions(): String =
    readString(root.resolve(cfg.conventions))

  def write(path: String, content: String): Unit =
    val p = root.resolve(path)
    LiveFiles.ensureParentDir(p)
    Files.write(
      p,
      content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING
    )
    ()

  def read(path: String): String =
    readString(root.resolve(path))

  /** 0 for a missing/non-regular file, matching bash `[[ -s ]]` on an absent path. */
  def sizeBytes(path: String): Long =
    val p = root.resolve(path)
    if Files.isRegularFile(p) then Files.size(p) else 0L

  private def readString(p: Path): String =
    new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8)

/** status.jsonl appender (loop.sh:151-171, `phase()`). One JSON line per event, appended to
  * `root/<log-dir>/status.jsonl`. Fire and forget: the whole write path swallows exceptions,
  * matching bash's `>>"$STATUS_FILE" 2>/dev/null || true`, a wrong/missing event is a wrong banner,
  * never a wrong merge.
  *
  * `log-dir` arrives as the whole `Config`, on the same reasoning as `LiveHarnessFs` above.
  */
final class LiveStatusLog(root: Path, runId: String)(using cfg: Config) extends StatusLog:

  private val statusFile = root.resolve(cfg.logDir).resolve("status.jsonl")

  def append(event: StatusEvent): Unit =
    try
      val ts  = System.currentTimeMillis() / 1000
      val pid = ProcessHandle.current().pid()
      // Repo-relative logfile normalization (loop.sh:164, the single choke point): strip a
      // leading "root/" prefix if present; empty stays empty; a foreign absolute path passes
      // through unchanged. Machine already passes repo-relative paths, so this is normally a
      // no-op, kept here to match bash's own belt-and-suspenders choke point.
      val rootPrefix = root.toString + java.io.File.separator
      val logfile    =
        if event.logfile.startsWith(rootPrefix) then event.logfile.substring(rootPrefix.length)
        else event.logfile
      // Detail sanitization (loop.sh:167): strip all backslashes, strip all double quotes,
      // replace every newline with a single space, in that exact order. Machine already
      // sanitizes detail before constructing the event (Machine.sanitizeDetail); re-applying
      // here is idempotent and mirrors bash's single choke point inside phase() itself.
      val detail = event.detail.replace("\\", "").replace("\"", "").replace("\n", " ")
      val line   =
        s"""{"ts":$ts,"pid":$pid,"run":"$runId","iter":${event.iter},"issue":"${event.issue}","phase":"${event.phase}","state":"${event.state}","pass":${event.pass},"budget":${event.budget},"logfile":"$logfile","detail":"$detail"}""" + "\n"
      LiveFiles.ensureParentDir(statusFile)
      Files.write(
        statusFile,
        line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND
      )
      ()
    catch case NonFatal(_) => ()

  /** One `kind":"stages"` line, `banner.sh`'s only source of stage identity now (issue #40). Same
    * fire and forget contract as `append`: a dropped declaration degrades the banner to its four
    * line, empty chip fallback, never the loop itself, so the whole write path swallows exceptions
    * here too.
    *
    * Sanitized the same way `append` sanitizes `detail`, field by field rather than once over the
    * whole line, because a phase/chip string sits inside a JSON array element here, not a single
    * top level string value; stripping backslashes and quotes and collapsing newlines still keeps
    * every field from breaking out of its own JSON string regardless of where in the line it sits.
    *
    * `clean` also strips every remaining C0 control character (issue #40 review, MINOR 6),
    * including the carriage return `append`'s own `detail` scrub never had to name (`detail` only
    * ever needed backslash/quote/newline stripped): `banner.sh` prints a chip's own `chip` label
    * straight into the banner's pinned chip rows with no second scrub at the read end, unlike
    * `detail`, whose one caller (the terminal status line) runs it through its own
    * `gsub("[\r\n]+"; " ")` before printing. A raw carriage return reaching that pinned row would
    * move the terminal cursor back to the start of the line on every redraw, corrupting whatever
    * `watch.sh` painted there; stripping it (and every other C0 control character) at the one place
    * this data is ever written closes that off at the source instead of leaning on a scrub the read
    * side does not actually have for this field.
    *
    * `clean` also strips DEL (0x7F) and the whole C1 range (0x80-0x9F), not just C0 (issue #40
    * review round 2, MINOR 3): `filterNot(_ < ' ')` alone stops at 0x1F, so DEL and C1 both passed
    * through untouched, including U+009B, the 8-bit encoding of CSI, the same escape-sequence
    * introducer a 7-bit `ESC [` collapses to on a terminal that recognizes 8-bit C1 codes. Left
    * unclosed, that gap kept the threat model above from actually holding: a `chip` carrying CSI
    * would still reach the pinned banner rows with no second scrub at the read end, the exact
    * corruption the C0 stripping above exists to prevent.
    */
  def declare(stages: StageSet): Unit =
    try
      val ts    = System.currentTimeMillis() / 1000
      val pid   = ProcessHandle.current().pid()
      def clean(s: String): String =
        s.replace("\\", "")
          .replace("\"", "")
          .replace("\n", " ")
          .filterNot(c => c < ' ' || (c >= '\u007f' && c <= '\u009f'))
      val stageJson = stages.stages
        .map { s =>
          s"""{"phase":"${clean(s.phase)}","chip":"${clean(s.chip)}","row":${s.row},"badge":${s.badge}}"""
        }
        .mkString("[", ",", "]")
      val anchorJson   = stages.anchor.map(a => s""""${clean(a)}"""").getOrElse("null")
      val terminalJson = stages.terminal.map(t => s""""${clean(t)}"""").getOrElse("null")
      val line =
        s"""{"ts":$ts,"pid":$pid,"run":"$runId","kind":"stages","anchor":$anchorJson,"terminal":$terminalJson,"stages":$stageJson}""" + "\n"
      LiveFiles.ensureParentDir(statusFile)
      Files.write(
        statusFile,
        line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND
      )
      ()
    catch case NonFatal(_) => ()

/** Notify seam (loop.sh:373-382). Three-way branch, every branch swallows failure: a dead
  * notification channel must never change loop behavior.
  */
final class LiveNotify(notifyCmd: Option[String], ntfyTopic: Option[String], log: String => Unit)
    extends Notify:

  // "" means unset, folded once on the way in (LiveProc.seam).
  private val cmdSeam   = LiveProc.seam(notifyCmd)
  private val topicSeam = LiveProc.seam(ntfyTopic)

  def notify(msg: String): Unit =
    (cmdSeam, topicSeam) match
      case (Some(cmd), _) =>
        try
          val pb = LiveProc.builder(Seq("bash", "-c", cmd))
          pb.environment().put("msg", msg)
          pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
          pb.redirectError(ProcessBuilder.Redirect.INHERIT)
          val rc = pb.start().waitFor()
          if rc != 0 then log("notify failed (ignored)")
        catch case NonFatal(_) => log("notify failed (ignored)")
      case (None, Some(topic)) =>
        try
          val pb = LiveProc.builder(
            Seq(
              "curl",
              "-s",
              "--max-time",
              "10",
              "-d",
              msg,
              s"https://ntfy.sh/$topic"
            )
          )
          pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
          pb.redirectError(ProcessBuilder.Redirect.DISCARD)
          val rc = pb.start().waitFor()
          if rc != 0 then log("notify failed (ignored)")
        catch case NonFatal(_) => log("notify failed (ignored)")
      case (None, None) =>
        log(s"notify (no channel configured): $msg")

/** Wall-clock waits (CI-appear poll). Interrupt handling: none, matching bash `sleep`. */
object LiveClock extends Clock:
  def sleepSeconds(s: Int): Unit =
    Thread.sleep(s * 1000L)

  def nowMillis(): Long = System.currentTimeMillis()

/** Shared subprocess-execution helper for the `gh`/`git` handlers below. Captures stdout/stderr as
  * raw bytes on two drain threads (never line-reconstructed, so diff/patch text stays byte-exact)
  * to avoid the classic deadlock where a child blocks on a full pipe nobody is reading.
  *
  * TEST AFFORDANCE, deliberate (canonical description; `LiveGit.extraPath` and
  * `LiveGitHub.extraPath` are the two public doors onto it and defer here). `pathPrepend` names a
  * directory placed in FRONT of the child's PATH so a fake `gh` / `git` / gate binary out of a test
  * fixture wins the lookup over the real one. It mirrors the FAKEBIN mechanism the deleted bash
  * suite used, and nothing in `Main` sets it: its consumer is `LiveProcSpec`, which uses it to drive
  * the real `LiveGit` / `LiveGitHub` against fake `git` and `gh` binaries. That is the only way to
  * test those handlers' exact argv without touching a real repo or a real GitHub.
  *
  * Why a parameter rather than mutating the ambient PATH: tests must be able to run concurrently and
  * leave the host's real PATH untouched, so the substitution has to be per child process, not per
  * JVM.
  *
  * PRODUCTION ALWAYS PASSES `None`. Every non-test call site leaves it defaulted, and with `None`
  * the child's PATH is exactly the inherited parent PATH: never scrubbed, never reordered. Any
  * production call site that starts passing `Some(...)` is a bug.
  */
private[litterbox] object LiveProc:

  /** The JDK pin (loop.sh:176-182), as it applies to CHILDREN.
    *
    * Bash pins by `export JAVA_HOME=...; export PATH="$JAVA_HOME/bin:$PATH"`, so every process the
    * loop forks (the gate, the agents, anything they shell out to) inherits JDK 25. A JVM cannot
    * mutate its own environment, so the equivalent here is to stamp the pin onto the environment of
    * each child the harness builds. `builder` below is the single choke point every
    * `ProcessBuilder` in the harness goes through, so no call site has to remember.
    *
    * Set once by Main at startup, and only when the pinned JDK actually exists: when it does not,
    * bash logs the warning and pins NOTHING (children keep the default java), and `None` reproduces
    * exactly that.
    */
  @volatile private var jdkPin: Option[String] = None

  def pinJdk(javaHome: Option[String]): Unit = jdkPin = javaHome

  /** Variables stamped onto EVERY child, on the same reasoning as `jdkPin`: bash got this by
    * `export`ing into its own environment, a JVM cannot, so `builder` — the one choke point every
    * `ProcessBuilder` in the harness goes through — does it per child instead.
    *
    * Two sources, composed in this order by `Main.runLoop` (step 2b). FIRST the `.litter-box/.env`
    * entries the ambient environment did not answer (`Main.layerDotEnv`'s `forChildren`), because
    * the sandbox scripts read the credential off THEIR OWN environment — `resources/sandbox/lib.sh`'s
    * `sandbox_credential_env` tests `$CLAUDE_CODE_OAUTH_TOKEN` then `$ANTHROPIC_API_KEY` and never
    * asks the loop for either — so a file entry that only reached this JVM would satisfy the
    * credential preflight and then fail the first dispatch one step later (#12).
    *
    * SECOND, and therefore winning any contested key, the config-derived `LITTER_BOX_INSTANCE` /
    * `LITTER_BOX_REPO_ROOT` pair (`Settings.childEnv`), which `lib.sh` turns into the Docker
    * image/network/proxy/volume names and the answer to "which repo is this run about" — the
    * second one being unanswerable from inside the sandbox scripts since they stopped living in the
    * repo (#9). Last on purpose: the loop reasons with those two values itself, so a `.env` allowed
    * to overwrite them would rename the Docker resources out from under the run in flight.
    *
    * Set once by `Main` at startup; empty everywhere else, so a test that never sets it sees an
    * unmodified child environment.
    */
  @volatile private var exported: Map[String, String] = Map.empty

  def exportEnv(vars: Map[String, String]): Unit = exported = vars

  /** Bash word-splitting of an UNQUOTED `$cmd` expansion, the single helper every word-split seam
    * (`GATE_CMD` / `CI_WAIT_CMD` via `LiveGateRunner`, `MERGE_CMD` via `LiveGitHub.merge`) goes
    * through.
    *
    * Total by construction: a value that is empty or whitespace-only splits to NOTHING, which is
    * exactly what bash does (`g=" "; $g` runs no command at all). Returning the empty case in the
    * type — an empty `Seq` the caller must handle — is deliberate: the naive
    * `cmd.trim.split("\\s+")` yields `Array("")`, a one-word command whose word is the empty
    * string, and handing that to `ProcessBuilder` throws instead of reproducing bash's rc-0 no-op.
    * Callers decide what bash's no-op means at their own site.
    */
  def wordSplit(cmd: String): Seq[String] =
    val trimmed = cmd.trim
    if trimmed.isEmpty then Seq.empty else trimmed.split("\\s+").toSeq

  /** bash's `[[ -n "$VAR" ]]` test on a seam-override env var (NOTIFY_CMD / NTFY_TOPIC / IMPL_CMD /
    * FIX_CMD / REVIEW_CMD / CI_APPEAR_CMD / MERGE_CMD): the literal empty string means UNSET, take
    * the default branch.
    *
    * Applied ONCE per handler, on the way in at construction, so the override sites below are plain
    * two-way `Some`/`None` matches instead of each re-deciding "is this seam set" with its own
    * `case Some(c) if c.nonEmpty` guard. Only the SHAPE is shared: what each site does on `Some`
    * and on `None` stays written out at the site, because those bash behaviours genuinely differ
    * (see `LiveGateRunner.run` and `LiveGitHub.merge` on the empty-argv case alone).
    *
    * Why fold here rather than delete the check: on the production path it is already done —
    * `Main.parseEnv`'s `str` helper filters empties, so a literally-empty var never reaches a
    * handler as `Some("")`. But these constructors are public and take a raw `Option[String]` from
    * anywhere (tests today, the slice-3 parity oracle next), and bash's answer for `VAR=""` is
    * unambiguous. One named fold at the boundary is cheaper than five guards inland.
    *
    * NOT a trim: a whitespace-only value is not empty to bash's `-n`, so it stays `Some` and does
    * reach the seam, where `wordSplit` (or `bash -c`) gives it bash's rc-0 no-op.
    */
  def seam(raw: Option[String]): Option[String] = raw.filter(_.nonEmpty)

  /** Creates a child `ProcessBuilder` with the JDK pin applied. Use this instead of
    * `new ProcessBuilder(...)` anywhere in the harness.
    */
  def builder(args: Seq[String]): ProcessBuilder =
    val pb = new ProcessBuilder(args.asJava)
    exported.foreach { case (k, v) => pb.environment().put(k, v) }
    jdkPin.foreach { javaHome =>
      val procEnv = pb.environment() // inherits the parent process environment; never cleared
      procEnv.put("JAVA_HOME", javaHome)
      val binDir = s"$javaHome${java.io.File.separator}bin"
      val cur    = Option(procEnv.get("PATH")).getOrElse("")
      procEnv.put(
        "PATH",
        if cur.isEmpty then binDir else s"$binDir${java.io.File.pathSeparator}$cur"
      )
    }
    pb

  final case class Result(rc: Int, stdout: String, stderr: String):
    /** Bash `$(...)` command-substitution semantics: strip ALL trailing newlines, nothing else (not
      * other whitespace, not leading/internal newlines).
      */
    def stdoutTrimmedTrailingNewlines: String = stdout.replaceAll("\n+$", "")

  def run(
      cwd: Path,
      args: Seq[String],
      env: Map[String, String] = Map.empty,
      pathPrepend: Option[String] = None
  ): Result =
    // NOTE: modifying the child's PATH env var alone is NOT enough to make an unqualified
    // command (e.g. "gh") resolve against a prepended test-fixture directory: on this JDK,
    // ProcessBuilder's own executable lookup for an unqualified command name uses the JVM's
    // OWN inherited PATH (a documented posix_spawn-launch-mechanism quirk), not the
    // ProcessBuilder's `environment()` map: confirmed empirically while writing this task's
    // tests. So when `pathPrepend` names a directory containing the command, resolve it to an
    // absolute path here and use THAT as argv[0]; the PATH env var is still set (belt and
    // suspenders, and correct for anything the child itself spawns via fork+exec, e.g. a
    // `bash -c` seam command that shells out further).
    val proc = prepare(cwd, args, env, pathPrepend).start()
    // Deliberate and inert: no harness child (`git`/`gh`/GATE_CMD/MERGE_CMD/the worker or
    // reviewer stub) ever reads stdin, so closing it immediately is a no-op for all of them.
    proc.getOutputStream.close()
    val outBaos = new java.io.ByteArrayOutputStream()
    val errBaos = new java.io.ByteArrayOutputStream()
    val outT    = new Thread(() => { proc.getInputStream.transferTo(outBaos); () })
    val errT    = new Thread(() => { proc.getErrorStream.transferTo(errBaos); () })
    outT.start(); errT.start()
    val rc = proc.waitFor()
    outT.join(); errT.join()
    Result(
      rc,
      new String(outBaos.toByteArray, StandardCharsets.UTF_8),
      new String(errBaos.toByteArray, StandardCharsets.UTF_8)
    )

  /** The shape every "run a child with its output redirected into a file" site shares: build the
    * child (JDK pin), chdir it to `cwd`, apply the `pathPrepend` test seam to argv[0] and PATH,
    * then overlay `env`. Everything AFTER this — which stream goes where, and whether the file is
    * truncated or appended to — is the caller's, because that is where the bash sites genuinely
    * differ. Returns the builder, not a started process, so the three `runTo*` wrappers below can
    * each state their own redirection in bash's own vocabulary.
    */
  private def prepare(
      cwd: Path,
      args: Seq[String],
      env: Map[String, String],
      pathPrepend: Option[String]
  ): ProcessBuilder =
    val pb = LiveProc.builder(resolveWithPathPrepend(args, pathPrepend))
    pb.directory(cwd.toFile)
    val procEnv = applyPathPrepend(pb, pathPrepend)
    env.foreach { case (k, v) => procEnv.put(k, v) }
    pb

  /** Bash `cmd >>"$file" 2>&1`: runs `args` with the child's combined stdout+stderr APPENDED to
    * `logPath`, and returns only the exit code. The file is created when absent and NEVER truncated
    * — an existing log keeps everything already in it, exactly like `>>`. argv[0] and PATH follow
    * the same rule as `run`.
    */
  def runAppending(
      cwd: Path,
      args: Seq[String],
      logPath: Path,
      pathPrepend: Option[String] = None
  ): Int =
    val pb = prepare(cwd, args, Map.empty, pathPrepend)
    LiveFiles.ensureParentDir(logPath)
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile)) // appends, matches ">>file"
    pb.redirectErrorStream(true)                                        // matches "2>&1"
    pb.start().waitFor()

  /** Bash `cmd >"$file" 2>&1`: the TRUNCATING counterpart of `runAppending`. Combined stdout+stderr
    * replace whatever `logPath` held; the file is created when absent.
    *
    * Deliberately a separate method rather than a flag on `runAppending`: `>` versus `>>` is a real
    * distinction at the call sites (the gate log and `$patch.apply.err` are per-run files bash
    * overwrites; the CI-wait log already holds the CI watch's output and must survive the merge
    * child), and a boolean would put that decision back at every call site in a form the reader has
    * to decode.
    *
    * `pathPrepend` carries the `extraPath` test seam exactly as `run`/`runAppending` do, and for
    * the same reason: `LiveGit.applyIndex` needs BOTH this truncating redirection and the seam, so
    * a fake `git` on the fixture PATH is honoured there like it is by every other `LiveGit` method.
    * `LiveGateRunner` has no seam and passes the `None` default.
    */
  def runToFile(
      cwd: Path,
      args: Seq[String],
      logPath: Path,
      env: Map[String, String] = Map.empty,
      pathPrepend: Option[String] = None
  ): Int =
    val pb = prepare(cwd, args, env, pathPrepend)
    LiveFiles.ensureParentDir(logPath)
    pb.redirectOutput(logPath.toFile) // truncates, matches ">file"
    pb.redirectErrorStream(true)      // matches "2>&1"
    pb.start().waitFor()

  /** Bash `cmd >"$out" 2>"$err"`: stdout and stderr to SEPARATE files, both truncating, streams
    * never merged. The reviewer dispatch is the only shape that needs this — its stdout is the
    * review document Machine parses, so the child's stderr has to land somewhere else or it would
    * corrupt the verdict.
    */
  def runToFiles(
      cwd: Path,
      args: Seq[String],
      stdoutPath: Path,
      stderrPath: Path,
      env: Map[String, String] = Map.empty
  ): Int =
    val pb = prepare(cwd, args, env, pathPrepend = None)
    LiveFiles.ensureParentDir(stdoutPath)
    LiveFiles.ensureParentDir(stderrPath)
    pb.redirectOutput(stdoutPath.toFile) // truncates, matches ">out"
    pb.redirectError(stderrPath.toFile)  // truncates, matches "2>err"
    pb.start().waitFor()

  /** Resolves argv[0] against a `pathPrepend` fixture directory when it names an executable there.
    * Shared by every `prepare` caller; see the NOTE in `run` for why the PATH env var alone is not
    * enough.
    */
  private def resolveWithPathPrepend(args: Seq[String], pathPrepend: Option[String]): Seq[String] =
    pathPrepend match
      case Some(dir) if args.nonEmpty =>
        val candidate = Path.of(dir, args.head)
        if Files.isExecutable(candidate) then candidate.toString +: args.tail else args
      case _ => args

  /** Prepends `pathPrepend` onto the child's PATH and returns the (inherited, never cleared)
    * environment map. Already carries the JDK pin (see `builder`); `pathPrepend`, a test-only seam,
    * goes in FRONT of it, so a fake `gh`/`git` fixture still wins the lookup.
    */
  private def applyPathPrepend(
      pb: ProcessBuilder,
      pathPrepend: Option[String]
  ): java.util.Map[String, String] =
    val procEnv = pb.environment()
    pathPrepend.foreach { p =>
      val cur = Option(procEnv.get("PATH")).getOrElse("")
      procEnv.put("PATH", if cur.isEmpty then p else s"$p${java.io.File.pathSeparator}$cur")
    }
    procEnv

/** run_gate (loop.sh:242-250): a tier command under a timeout, log captured. `timeoutBin` is
  * resolved once by Main (`command -v timeout || command -v gtimeout`, verified loop.sh:174) and
  * passed in; when `None` the gate runs unbounded, matching bash's own fallback when neither binary
  * is on PATH. `cmd` is WORD-SPLIT, never `eval`'d (GATE_CMD/MERGE_CMD class).
  *
  * `sandboxRunner` is the extracted `run-fast-gate.sh` (`Sandbox.resolve`) when `gate.sandboxed` is
  * on, and `None` when the command runs on the host. Note the two paths differ in more than a
  * prefix: a sandboxed command is passed through as ONE argument, unsplit, because the thing that
  * splits it is `bash -c` inside the container, and word-splitting it here would only mean rejoining
  * it there — losing every quote the operator wrote in the process. Host commands keep bash's
  * splitting verbatim, as they always have.
  */
final class LiveGateRunner(
    root: Path,
    timeoutBin: Option[String],
    sandboxRunner: Option[Path] = None
) extends GateRunner:

  def run(label: String, cmd: String, timeoutSec: Int, logFile: String): GateResult =
    // loop.sh:244: `log "$label gate: $cmd (timeout ${tmo}s) -> $logfile"`. Every call site passes
    // `$LOG_DIR/...`, which expands ABSOLUTE, so the logged path is absolute — resolve first, log
    // the resolved path. (The `logfile` field of a status.jsonl event is the opposite contract:
    // repo-relative, normalized at loop.sh:161's choke point. Do not confuse the two.)
    val logPath = root.resolve(logFile)
    val where   = if sandboxRunner.isDefined then " sandboxed" else ""
    LiveLog.log(s"$label gate:$where $cmd (timeout ${timeoutSec}s) -> $logPath")
    val split = LiveProc.wordSplit(cmd)
    val words = sandboxRunner match
      // An empty/whitespace-only command falls through to the host branch even when sandboxed, so
      // the "empty gate is green" reading below stays the same answer in both modes rather than
      // becoming an infra fault from inside the container in one of them.
      case Some(runner) if split.nonEmpty => Seq(runner.toString, cmd)
      case _                              => LiveGateRunner.resolveArgv0(root, split)
    val fullArgs = timeoutBin match
      case Some(tb) => tb +: timeoutSec.toString +: words
      case None     => words
    if fullArgs.isEmpty then
      // bash: `local g="$cmd"` with an empty/whitespace-only `$cmd` and no TIMEOUT_BIN makes
      // `$g >"$logfile" 2>&1` a bare redirection — no command runs, the log file is still
      // created/truncated, and `rc` stays 0 (loop.sh:246-249). Green, not an exception.
      // (With a TIMEOUT_BIN set, `$g` is NOT empty: bash runs `$TIMEOUT_BIN $tmo` with no command
      // and gets that binary's usage error, which `fullArgs` reproduces verbatim below.)
      LiveFiles.ensureParentDir(logPath)
      Files.write(
        logPath,
        Array.emptyByteArray,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
      GateResult.Green
    else
      // bash: `$g >"$logfile" 2>&1` (loop.sh:246).
      LiveProc.runToFile(root, fullArgs, logPath) match
        case 0   => GateResult.Green
        case 124 => GateResult.Timeout
        case _   => GateResult.Red

object LiveGateRunner:

  /** Resolves the command word (argv[0]) of a word-split gate command the way bash would.
    *
    * loop.sh:105 does `cd "$REPO_ROOT"` before anything else runs, so for bash "the current
    * directory" and "the repo root" are the same thing for the whole run, and GATE_CMD
    * (loop.sh:133) is resolved against it.
    *
    * On this JDK/Unix `pb.directory(root)` ALREADY gives that: the child chdir's before exec, so a
    * relative argv[0] containing a slash resolves against `root` and a bare name still comes off
    * PATH — both verified against a temp-dir `root` by LiveProcSpec. But `ProcessBuilder.start`
    * explicitly documents the meaning of a relative command as system-dependent, so the parity
    * guarantee is currently resting on an unspecified detail. Doing it here pins it: same launched
    * command, no longer platform-conditional.
    *
    * The rule below is exactly bash's own, no wider: a command word CONTAINING a slash is a path,
    * resolved relative to the (bash) cwd = `root`; a word with NO slash is a bare binary name that
    * must come off PATH and is left untouched; an already-absolute path is left untouched because
    * resolving it against `root` is a no-op anyway. Only argv[0] is touched — later words are the
    * command's own arguments and bash never resolves those. So a repo-relative gate script
    * (bash's `$SCRIPT_DIR/sandbox/run-fast-gate.sh`, loop.sh:133) lands on the repo root, while a
    * `GATE_CMD` / `CI_WAIT_CMD` override keeps bash's word-splitting AND its lookup semantics
    * verbatim (`gh pr checks ...` still resolves `gh` off PATH).
    *
    * Only the HOST path comes through here now. A sandboxed gate command is resolved by bash inside
    * the container, against that image's PATH, and never reaches this function.
    */
  private[litterbox] def resolveArgv0(root: Path, words: Seq[String]): Seq[String] =
    words match
      case head +: tail if head.contains('/') && !Path.of(head).isAbsolute =>
        root.resolve(head).toString +: tail
      case _ => words

/** dispatch_worker (loop.sh:268-297) / dispatch_review (loop.sh:311-334).
  *
  * One deliberate deviation from a byte-exact bash port, flagged prominently here and in the
  * slice-2 task report: the `REVIEW_CMD` stub path ALWAYS returns `Done`, regardless of the child's
  * own exit code. This is bash's OWN asymmetry with the worker stub path (loop.sh: 314-317: a
  * review stub cannot simulate a reviewer timeout at all in the current suite), not something this
  * port introduced. Preserved verbatim, not "fixed" into worker-like rc-124 propagation.
  */
final class LiveAgentDispatch private[litterbox] (
    root: Path,
    sandboxDir: Path,
    timeoutBin: Option[String],
    iterTimeout: Int,
    implCmd: Option[String],
    fixCmd: Option[String],
    reviewCmd: Option[String],
    models: AgentModels = AgentModels()
) extends AgentDispatchImpl:

  // "" means unset, folded once on the way in (LiveProc.seam).
  private val implSeam   = LiveProc.seam(implCmd)
  private val fixSeam    = LiveProc.seam(fixCmd)
  private val reviewSeam = LiveProc.seam(reviewCmd)

  /** The `-e ANTHROPIC_MODEL` half of a dispatch, as the environment the runner script reads it off
    * (`Settings.AgentModelEnvVar`). ALWAYS sets the key, empty string for an unset role, never an
    * absent entry: `LiveProc.prepare` only ADDS keys on top of whatever the child inherits, it never
    * clears the environment first, so an absent entry here would let an ambient
    * `LITTER_BOX_AGENT_MODEL` (the operator's own shell, or a value `.litter-box/.env` stamped onto
    * every child through `LiveProc.exportEnv`) ride along untouched into a dispatch that asked for no
    * model at all. Stamping it shut with `""` is what `sandbox_model_env` (`lib.sh`) already treats
    * as no model, so the container still receives no `-e ANTHROPIC_MODEL`, but no ambient value can
    * survive under it.
    *
    * Not folded into the argv, deliberately. A model identifier comes out of a file the consumer
    * owns, and every path from that file to `docker run` has to keep it ONE argv element: an env
    * entry stays one by construction, where a positional argument would sit next to the reviewer's
    * back compat `$1`-is-the-prompt rule and invite a string built command line.
    */
  private def modelEnv(model: Option[String]): Map[String, String] =
    Map(Settings.AgentModelEnvVar -> model.getOrElse(""))

  def worker(
      role: Role,
      promptFile: String,
      patchOut: String,
      logFile: String,
      currentPatch: Option[String]
  ): DispatchOutcome =
    val overrideCmd = role match
      case Role.IMPL => implSeam
      case Role.FIX  => fixSeam
    val patchOutAbs = root.resolve(patchOut)
    val logPath     = root.resolve(logFile)
    LiveLog.log(s"dispatching $role agent -> $logPath (patch -> $patchOutAbs)")
    overrideCmd match
      case Some(cmd) =>
        // Eval'd through a shell (IMPL_CMD/FIX_CMD class), PATCH_OUT exported to the child.
        val rc = LiveProc.runToFile(
          root,
          Seq("bash", "-c", cmd),
          logPath,
          env = Map("PATCH_OUT" -> patchOutAbs.toString)
        )
        LiveLog.log(s"$role stub exited rc=$rc")
        if rc == 124 then DispatchOutcome.TimedOut else DispatchOutcome.Done
      case None =>
        // Real path: <sandbox>/run-agent.sh PROMPT_FILE PATCH_OUT [CURRENT_PATCH], where <sandbox>
        // is the extracted shipped tree (Sandbox.resolve), NOT a directory in the consumer's repo.
        val runner          = sandboxDir.resolve("run-agent.sh").toString
        val promptAbs       = root.resolve(promptFile).toString
        val currentPatchArg = currentPatch.map(root.resolve(_).toString).getOrElse("")
        val args            = (timeoutBin match
          case Some(tb) => Seq(tb, iterTimeout.toString)
          case None     => Seq.empty
        ) ++ Seq(runner, promptAbs, patchOutAbs.toString, currentPatchArg)
        val rc = LiveProc.runToFile(root, args, logPath, env = modelEnv(models.forRole(role)))
        if rc == 124 then
          LiveLog.log(
            s"WARNING: $role sandbox dispatch failed rc=124 (${iterTimeout}s timeout or infra fault: missing image/proxy/Docker/API key/prior-patch)"
          )
          DispatchOutcome.TimedOut
        else
          LiveLog.log(s"$role sandbox dispatch exited rc=$rc (patch written by the container)")
          DispatchOutcome.Done

  // Overrides `dispatchReview`, not `review` (issue #35: see `AgentDispatch.review`'s own doc,
  // `src/Caps.scala`, for why). Extends `AgentDispatchImpl`, not `AgentDispatch` directly (round
  // three of issue #35's review: `AgentDispatch` is `sealed`, so only `Caps.scala` itself may extend
  // it; `AgentDispatchImpl` is this file's way in). `private[litterbox]` here matches the abstract
  // member's own visibility, which is what `AgentDispatch`'s own doc explains the exact guarantee
  // and residual limits of.
  private[litterbox] def dispatchReview(prompt: String, reviewFile: String): DispatchOutcome =
    // loop.sh:313 logs `$review_file`, which the call site (loop.sh:683) sets to `$LOG_DIR/...`,
    // i.e. absolute. Same rule as the gate and worker lines above: log the resolved path.
    val reviewPath = root.resolve(reviewFile)
    val stderrPath = root.resolve(s"$reviewFile.stderr")
    LiveLog.log(
      s"dispatching REVIEWER in the sandbox (cold, zero mounts, no mutating tools) -> $reviewPath"
    )
    reviewSeam match
      case Some(cmd) =>
        val rc = LiveProc.runToFiles(root, Seq("bash", "-c", cmd), reviewPath, stderrPath)
        LiveLog.log(s"REVIEWER stub exited rc=$rc")
        DispatchOutcome.Done // bash asymmetry: the stub path never reads back rc==124
      case None =>
        // Real path: REVIEW_PROMPT env carries the prompt text, never argv.
        val runner = sandboxDir.resolve("run-reviewer.sh").toString
        val args   = (timeoutBin match
          case Some(tb) => Seq(tb, iterTimeout.toString)
          case None     => Seq.empty
        ) :+ runner
        val rc = LiveProc.runToFiles(
          root,
          args,
          reviewPath,
          stderrPath,
          env = Map("REVIEW_PROMPT" -> prompt) ++ modelEnv(models.review)
        )
        if rc == 124 then
          LiveLog.log(
            s"WARNING: REVIEWER sandbox dispatch failed rc=124 (${iterTimeout}s timeout or infra fault: missing image/proxy/Docker/API key)"
          )
          DispatchOutcome.TimedOut
        else
          LiveLog.log(s"REVIEWER sandbox dispatch exited rc=$rc")
          DispatchOutcome.Done

/** `git` operations (loop.sh's scattered git calls across run_gate/stage_patch/iterate/
  * auto_merge/flip_blocked). Every method runs with cwd=root; parity source is loop.sh, line
  * numbers cited per method below.
  *
  * `extraPath` is the deliberate TEST AFFORDANCE described in full on `LiveProc`: a fixture
  * directory prepended onto the child's PATH so a fake `git` wins the lookup, kept because the
  * slice-3 parity oracle needs the FAKEBIN substitution this harness has no other way to accept.
  * PRODUCTION ALWAYS PASSES `None` (`Main` never sets it), and with `None` the child's PATH is
  * exactly the inherited parent PATH.
  */
final class LiveGit(root: Path, extraPath: Option[String] = None) extends Git:

  private def git(args: String*): LiveProc.Result =
    LiveProc.run(root, "git" +: args, pathPrepend = extraPath)

  /** loop.sh:660: `[[ -z "$(git status --porcelain)" ]]`. */
  def statusClean(): Boolean =
    git("status", "--porcelain").stdout.isEmpty

  /** loop.sh:664: `git fetch --quiet origin main`. */
  def fetchOriginMain(): Boolean =
    git("fetch", "--quiet", "origin", "main").rc == 0

  /** loop.sh:666-671. */
  def checkoutBranch(branch: String): Boolean =
    if git("show-ref", "--verify", "--quiet", s"refs/heads/$branch").rc == 0 then
      LiveLog.log(s"branch $branch exists — checking it out")
      git("checkout", "--quiet", branch).rc == 0
    else git("checkout", "--quiet", "-b", branch, "origin/main").rc == 0

  /** loop.sh:545-546 (inside stage_patch): `git reset -q --hard origin/main` then `git clean -qfd`.
    * Bash never checks either exit code; neither does this port.
    */
  def resetHardCleanToOriginMain(): Unit =
    git("reset", "-q", "--hard", "origin/main")
    git("clean", "-qfd")
    ()

  /** loop.sh:554 (and the tamper-report twin at loop.sh:346): `git apply --numstat PATCH
    * 2>/dev/null`, fail-open (never thrown).
    *
    * The exit code is DELIBERATELY ignored. Bash captures this with `$(...)`, which keeps whatever
    * reached stdout no matter how the command exited; discarding stdout on a nonzero rc would fail
    * open WIDER than bash in `Machine.touchesProtected`, the guard whose whole job is to block
    * writes to protected paths — rows git did emit would be dropped and the patch would sail past
    * the guard. (Empirically, git 2.50.1 parses a patch in full before emitting any numstat row, so
    * every failure mode we could construct yielded zero stdout bytes; this is parity on principle,
    * not a demonstrated live hole. Do not rely on that being true of every git version or every
    * failure mode.)
    *
    * DEVIATION FROM THE TASK BRIEF: the brief described `patch` as text to materialize into a temp
    * file first. It is not: `patch` is a repo-relative PATH to a patch file ALREADY on disk.
    * Evidence: Machine.scala's only call sites (`git.applyNumstat(patchOut)` /
    * `git.applyIndex(patchOut)`, Machine.scala:531,542) both pass the very same `patchOut` string
    * that was just handed to `AgentDispatch.worker` as the file the agent/stub writes its patch to,
    * and bash's own `$patch`/`$patch_out` variable is, likewise, always a path (the worker/fixer
    * writes directly to `$PATCH_OUT`; there is no separate "patch content" variable anywhere in
    * loop.sh). loop.sh wins per this task's own precedence rule, so this method resolves `patch` as
    * a path (cwd=root makes the relative form work directly) instead of writing a temp file.
    */
  def applyNumstat(patch: String): String =
    git("apply", "--numstat", patch).stdoutTrimmedTrailingNewlines

  /** loop.sh:568: `git apply --index PATCH >"$patch.apply.err" 2>&1` (unconditional: the redirect
    * runs whether the apply succeeds or fails, combined stdout+stderr). `patch` is already a path
    * (see `applyNumstat`'s docstring), so `<patch>.apply.err` is derivable directly from it, same
    * as bash's own `$patch.apply.err`.
    */
  def applyIndex(patch: String): Boolean =
    val errPath = root.resolve(s"$patch.apply.err")
    LiveProc.runToFile(
      root,
      Seq("git", "apply", "--index", patch),
      errPath,
      pathPrepend = extraPath
    ) == 0

  /** loop.sh:524,841: `git add PATH` (the PATCH-REJECTED.md / FIX-EMPTY.md marker paths). */
  def add(path: String): Unit =
    git("add", path)
    ()

  /** loop.sh:725,843: `git add -A`. */
  def addAll(): Unit =
    git("add", "-A")
    ()

  /** loop.sh:772: `git diff --cached origin/main` (the reviewer's diff / PR body detail). */
  def diffCachedOriginMain(): String =
    git("diff", "--cached", "origin/main").stdout

  /** loop.sh:844: `git diff --cached --quiet HEAD` (exit 0 = nothing staged),, matching Caps' own
    * docstring `"! git diff --cached --quiet HEAD"`.
    */
  def anythingStaged(): Boolean =
    git("diff", "--cached", "--quiet", "HEAD").rc != 0

  /** loop.sh:876: `git commit --quiet -m MESSAGE`. Bash's message is itself a multi-line string
    * baked into one `-m` argument (no shell re-parses it, so embedded newlines are safe as a single
    * argv element), same here, no `-F` temp file needed.
    */
  def commit(message: String): Unit =
    git("commit", "--quiet", "-m", message)
    ()

  /** loop.sh:883: `git push --quiet -u origin BRANCH`. */
  def push(branch: String): Unit =
    git("push", "--quiet", "-u", "origin", branch)
    ()

/** `gh` operations. Every method runs with cwd=root.
  *
  * `extraPath` is the deliberate TEST AFFORDANCE described in full on `LiveProc`: a fixture
  * directory prepended onto the child's PATH so a fake `gh` script wins the lookup, exactly as the
  * deleted bash suite's FAKEBIN mechanism did, and used by `LiveProcSpec` to pin this class's exact
  * `gh` argv without touching a real GitHub. PRODUCTION ALWAYS PASSES `None`
  * (`Main` never sets it), and with `None` the child's PATH is exactly the inherited parent PATH.
  */
final class LiveGitHub(
    root: Path,
    ciAppearCmd: Option[String],
    mergeCmd: Option[String],
    extraPath: Option[String] = None
)(using cfg: Config)
    extends GitHub:

  /** The three queue labels off `issues.labels`. Only the three QUERY methods below need them —
    * they bake a label into the `gh` argv; the label EDITS are `Machine`'s, which threads the same
    * `Config` this handler now takes, so neither side can be handed a different set.
    */
  private val labels: Labels = cfg.labels

  // "" means unset, folded once on the way in (LiveProc.seam).
  private val ciAppearSeam = LiveProc.seam(ciAppearCmd)
  private val mergeSeam    = LiveProc.seam(mergeCmd)

  private def gh(args: String*): LiveProc.Result =
    LiveProc.run(root, "gh" +: args, pathPrepend = extraPath)

  /** The jq program shared by `issueComments` and `prComments`, one argv element: both read the same
    * `comments` JSON shape, off `gh issue view` and `gh pr view` respectively, so the program itself
    * does not need to differ. Emits one `@json`-encoded string PER comment rather than joining the
    * array into one preformatted string (see `Caps.GitHub.issueComments` for why joining is unsafe).
    * `@json` escapes any newline inside a comment to a literal `\n`, so `gh`'s `--jq` output is one
    * physical line per comment, and `commentsOf` below splits on that.
    */
  private val commentsJqProgram =
    ".comments[] | (\"@\" + .author.login + \" (\" + .authorAssociation + \"):\\n\" + .body) | @json"

  /** Runs `gh <kind> view <id> --json comments --jq commentsJqProgram` and decodes the result back
    * to one plain-text entry per comment. `kind` is `"issue"` or `"pr"`, the only two argv words
    * `issueComments`/`prComments` differ by.
    */
  private def commentsOf(kind: String, id: Int): Option[List[String]] =
    val r = gh(kind, "view", id.toString, "--json", "comments", "--jq", commentsJqProgram)
    // `None` on a nonzero exit rather than folding it into `Some(Nil)`: auth expiry, rate limiting,
    // and a broken jq program all fail this way, and the two must stay distinguishable (see
    // `Caps.GitHub.issueComments`). `Machine.fixRound` turns this back into prompt text with a
    // different sentinel for each case.
    if r.rc == 0 then
      Some(r.stdoutTrimmedTrailingNewlines.linesIterator.filter(_.nonEmpty).map(decodeJsonString).toList)
    else None

  /** Reverses `@json`'s escaping of ONE line of `commentsOf`'s output. Not a general JSON parser:
    * this repo has no JSON library (`CONVENTIONS.md`), and `commentsJqProgram` only ever hands this
    * a single double-quoted string, so undoing the handful of escapes `@json` can produce (quote,
    * backslash, the control-character shorthands, and `\u` for anything else under 0x20) is enough.
    */
  private def decodeJsonString(line: String): String =
    val inner = line.substring(1, line.length - 1) // strip the surrounding quotes @json always adds
    val sb    = new StringBuilder(inner.length)
    var i     = 0
    while i < inner.length do
      val c = inner.charAt(i)
      if c == '\\' && i + 1 < inner.length then
        inner.charAt(i + 1) match
          case '"'   => sb += '"'; i += 2
          case '\\'  => sb += '\\'; i += 2
          case '/'   => sb += '/'; i += 2
          case 'b'   => sb += '\b'; i += 2
          case 'f'   => sb += '\f'; i += 2
          case 'n'   => sb += '\n'; i += 2
          case 'r'   => sb += '\r'; i += 2
          case 't'   => sb += '\t'; i += 2
          case 'u'   =>
            sb += Integer.parseInt(inner.substring(i + 2, i + 6), 16).toChar
            i += 6
          case other => sb += other; i += 2
      else
        sb += c; i += 1
    sb.toString

  /** loop.sh:627: `gh issue list --state open --label <active> --json number --jq .[0].number`.
    * Crash-resume asks for the CONFIGURED active label, never the literal `in-progress`.
    */
  def inProgressIssue(): Option[Int] =
    gh(
      "issue",
      "list",
      "--state",
      "open",
      "--label",
      labels.active,
      "--json",
      "number",
      "--jq",
      ".[0].number"
    ).stdoutTrimmedTrailingNewlines.toIntOption

  /** loop.sh:629-630; the whole jq program is ONE argv element.
    *
    * `--limit` pinned explicitly (issue #50 review, round 3, finding I; same class as
    * `parkedIssues`' own `--limit 1000` below): without it, `gh`'s own default page size (30) sorts
    * and takes `.[0]` from only the newest 30 open `ready` issues, not the oldest overall, so a repo
    * with more than 30 open `ready` issues can silently pick a newer one ahead of a genuinely older
    * one still waiting.
    */
  def oldestReadyIssue(): Option[Int] =
    gh(
      "issue",
      "list",
      "--state",
      "open",
      "--label",
      labels.ready,
      "--limit",
      "1000",
      "--json",
      "number,createdAt",
      "--jq",
      "sort_by(.createdAt) | .[0].number"
    ).stdoutTrimmedTrailingNewlines.toIntOption

  /** Added for #28, same shape as `oldestReadyIssue` above with the configured PARKED label,
    * except every match is returned (oldest first) rather than only the first: `Machine`'s resume
    * probe has to walk the whole list looking for the first one with an accepted human reply, so a
    * newer parked issue with a reply is never starved behind an older one still waiting (review
    * finding on issue #28's first pass).
    *
    * `None` on a nonzero `gh` exit rather than folding a failed read into `Some(Nil)`, same
    * reasoning as `commentsOf` (issue #28 review finding 7, round 3): a rate limited or auth
    * expired read must read as "the loop does not know", not as "the queue is empty".
    *
    * `--limit` pinned explicitly, well above `gh`'s own default page size of 30 (issue #50 review
    * finding 4): before this, a repo with more than 30 open parked issues silently truncated the
    * list, and truncation used to only affect WHICH issue got picked, a self-correcting mistake the
    * next tick's read would fix on its own. It stopped being self-correcting once `carriesParked`
    * (`Machine.pickAndSetup`) started deciding whether `parked` is removed at the terminal from
    * membership in this same list: an issue outside a truncated window would read as "not parked"
    * even while genuinely carrying the label, stranding it exactly like the review's finding 1.
    */
  def parkedIssues(): Option[List[Int]] =
    val r = gh(
      "issue",
      "list",
      "--state",
      "open",
      "--label",
      labels.parked,
      "--limit",
      "1000",
      "--json",
      "number,createdAt",
      "--jq",
      "sort_by(.createdAt) | .[].number"
    )
    if r.rc == 0 then
      Some(r.stdout.linesIterator.flatMap(_.strip().toIntOption).toList)
    else None

  /** `gh api user --jq .login`: the account `gh` is authenticated as, i.e. the one the harness's
    * own `issueComment` calls post as. `None` on a nonzero exit.
    */
  def viewerLogin(): Option[String] =
    val r = gh("api", "user", "--jq", ".login")
    if r.rc == 0 then Option(r.stdoutTrimmedTrailingNewlines).filter(_.nonEmpty) else None

  /** loop.sh:643-644. */
  def issueTitleAndBody(issue: Int): String =
    gh(
      "issue",
      "view",
      issue.toString,
      "--json",
      "title,body",
      "--jq",
      "\"# \" + (.title) + \"\\n\\n\" + .body"
    ).stdout

  /** loop.sh:395 (flip_blocked's dependency-body scan). */
  def issueBody(issue: Int): String =
    gh("issue", "view", issue.toString, "--json", "body", "--jq", ".body").stdout

  /** Added for #27: `gh issue view` already returns `comments`, this repo just never read it. See
    * the trait docstring in `Caps.scala` for why this is a `List` of prefixed entries rather than a
    * joined string, and why the `Option` keeps a failed read distinct from a comment-free issue.
    */
  def issueComments(issue: Int): Option[List[String]] = commentsOf("issue", issue)

  /** loop.sh:650, minus the join/split round-trip (a bash string-matching artifact only, Machine
    * does `.contains("class-1")` on the `List` form directly, Machine.scala:108).
    */
  def issueLabels(issue: Int): List[String] =
    val joined = gh(
      "issue",
      "view",
      issue.toString,
      "--json",
      "labels",
      "--jq",
      "[.labels[].name] | join(\" \")"
    ).stdoutTrimmedTrailingNewlines.strip()
    if joined.isEmpty then Nil else joined.split("\\s+").toList

  /** loop.sh:401 (flip_blocked's per-reference state read). */
  def issueState(issue: Int): String =
    gh(
      "issue",
      "view",
      issue.toString,
      "--json",
      "state",
      "--jq",
      ".state"
    ).stdoutTrimmedTrailingNewlines

  /** loop.sh:406,463,674,920: one `--add-label` per `add` element, one `--remove-label` per
    * `remove` element, order preserved (bash always passes exactly one of each; this generalizes to
    * the `List` shape `Caps` demands). Stdout discarded (bash: `>/dev/null`).
    */
  def editLabels(issue: Int, add: List[String], remove: List[String]): Boolean =
    val args = Seq("issue", "edit", issue.toString) ++
      add.flatMap(l => Seq("--add-label", l)) ++
      remove.flatMap(l => Seq("--remove-label", l))
    gh(args*).rc == 0

  /** loop.sh:392: `gh issue list --state open --label <blocked> --json number --jq .[].number`.
    *
    * `--limit` pinned explicitly, same reasoning and same value as `oldestReadyIssue` and
    * `parkedIssues` above (issue #50 review, round 3, finding I): every match is meant to be
    * returned, not just `gh`'s own default first page of 30.
    */
  def openBlockedIssues(): List[Int] =
    gh(
      "issue",
      "list",
      "--state",
      "open",
      "--label",
      labels.blocked,
      "--limit",
      "1000",
      "--json",
      "number",
      "--jq",
      ".[].number"
    ).stdout.linesIterator
      .flatMap(_.strip().toIntOption)
      .toList

  /** loop.sh:903-905: body goes to a temp file first, `--body-file`, never `--body`. */
  def createPr(branch: String, title: String, body: String): String =
    val tmp = Files.createTempFile("pr-body", ".md")
    try
      Files.write(tmp, body.getBytes(StandardCharsets.UTF_8))
      gh(
        "pr",
        "create",
        "--base",
        "main",
        "--head",
        branch,
        "--title",
        title,
        "--body-file",
        tmp.toString
      ).stdoutTrimmedTrailingNewlines
    finally Files.deleteIfExists(tmp)

  /** Added for issue #36 (`Caps.GitHub.prForBranch`'s own doc has the reason): `gh pr view` resolves
    * by head branch as well as by number/URL, so this needs no separate lookup subcommand. Any
    * nonzero exit (no PR for this branch, or the read itself failed) reads as `None`, the same
    * fail-open shape `prState`'s own `if r.rc == 0` gives.
    *
    * Reads `state` alongside `number` and only returns a number when `state` is `OPEN` (issue #36
    * review, BLOCKER 1): `gh pr view <branch>` resolves a CLOSED or MERGED pull request for that
    * head branch exactly as readily as an OPEN one, and `branch` is deterministic (`s"us-$issue"`,
    * `Machine.pickAndSetup`'s own `val branch`), so without this filter a tick resuming an issue whose
    * earlier PR was already merged (or closed) would adopt that stale PR instead of opening a fresh
    * one, and every node downstream of `OpenPr` would then reason about a PR that has nothing to do
    * with this iteration's own commits. `--json`/`--jq` ask for both fields in one call rather than a
    * second round trip: `.number,.state` prints the two on separate lines, the same shape
    * `commentsOf` above already relies on `linesIterator` to split.
    */
  def prForBranch(branch: String): Option[Int] =
    val r = gh("pr", "view", branch, "--json", "number,state", "--jq", ".number,.state")
    if r.rc != 0 then None
    else
      r.stdoutTrimmedTrailingNewlines.linesIterator.toList match
        case numStr :: state :: Nil if state == "OPEN" => numStr.toIntOption
        case _                                         => None

  /** loop.sh:462: `gh pr comment PR --body BODY` (argv, not `--body-file`). */
  def prComment(pr: Int, body: String): Unit =
    gh("pr", "comment", pr.toString, "--body", body)
    ()

  /** Added for #28, same shape as `prComment` above, over the issue thread instead of the PR one,
    * except the rc is reported rather than discarded: see `Caps.GitHub.issueComment` for why the
    * marker post's success has to be observable to the caller here.
    */
  def issueComment(issue: Int, body: String): Boolean =
    gh("issue", "comment", issue.toString, "--body", body).rc == 0

  /** Mirrors `issueComments` over the PR thread instead of the issue thread. */
  def prComments(pr: Int): Option[List[String]] = commentsOf("pr", pr)

  /** loop.sh:479: stderr discarded, trimmed; any nonzero exit -> empty string (bash: `|| true` with
    * `2>/dev/null`).
    */
  def prState(pr: Int): String =
    val r = gh("pr", "view", pr.toString, "--json", "state", "--jq", ".state")
    if r.rc == 0 then r.stdoutTrimmedTrailingNewlines else ""

  /** loop.sh:430 (default) / eval'd CI_APPEAR_CMD seam (loop.sh:433). `pr_num` is exported into the
    * seam's `bash -c` subshell so an override that references it, as bash's own `eval` would
    * resolve it from the enclosing shell's local `$pr_num`, still resolves correctly, though the
    * current bash test suite's CI_APPEAR_CMD stubs never reference it.
    */
  def checksRollupCount(pr: Int): Option[Int] =
    val out = ciAppearSeam match
      case Some(cmd) =>
        LiveProc
          .run(
            root,
            Seq("bash", "-c", cmd),
            env = Map("pr_num" -> pr.toString),
            pathPrepend = extraPath
          )
          .stdoutTrimmedTrailingNewlines
      case None =>
        gh(
          "pr",
          "view",
          pr.toString,
          "--json",
          "statusCheckRollup",
          "--jq",
          ".statusCheckRollup | length"
        ).stdoutTrimmedTrailingNewlines
    if out.matches("^[0-9]+$") then out.toIntOption else None

  /** loop.sh:469,473 (default) / word-split MERGE_CMD seam (MERGE_CMD class, never `eval`'d).
    *
    * `$merge_cmd >>"$ci_log" 2>&1`: the merge child's combined output is APPENDED to the CI-wait
    * log the caller already computed (`$LOG_DIR/issue-N.ci-wait.log`), which by then holds the CI
    * watch's own output — hence append, never truncate. Both arms redirect, the default
    * `gh pr merge` one included: in bash the redirection is on the expansion of `$merge_cmd`, not
    * on the override.
    */
  def merge(pr: Int, ciLog: String): Int =
    val logPath = root.resolve(ciLog)
    mergeSeam match
      case Some(cmd) =>
        LiveProc.wordSplit(cmd) match
          // bash: `$merge_cmd >>"$ci_log" 2>&1` with a whitespace-only MERGE_CMD expands to
          // nothing — no command runs and `merge_rc` stays 0, i.e. the merge is treated as having
          // succeeded and auto_merge goes on to verify the PR state (loop.sh:469-477). The bare
          // redirection still runs, so the log file is created if absent (and left untouched
          // otherwise: `>>` never truncates).
          case Seq() =>
            LiveFiles.ensureParentDir(logPath)
            Files.write(
              logPath,
              Array.emptyByteArray,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND
            )
            0
          case words => LiveProc.runAppending(root, words, logPath, pathPrepend = extraPath)
      case None =>
        LiveProc.runAppending(
          root,
          Seq("gh", "pr", "merge", pr.toString, "--squash", "--delete-branch"),
          logPath,
          extraPath
        )
