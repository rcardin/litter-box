package harness

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Unit tests for the slice-2 part-B live handlers (LiveGateRunner, LiveAgentDispatch, LiveGit,
  * LiveGitHub), against the bash-parity contracts documented in Live.scala/Caps.scala. Real
  * subprocesses throughout (real `git`, a fake `gh` on PATH, tiny throwaway shell scripts), temp
  * dirs stand in for the repo root, exactly like the bash suite's per-scenario sandbox.
  */
class LiveProcSpec extends AnyFlatSpec with Matchers:

  private def tempRoot(): Path = Files.createTempDirectory("live-proc-spec")

  private def readString(p: Path): String =
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /** Writes an executable script (any shebang line included in `content`) and returns its path. */
  private def writeExecutable(dir: Path, name: String, content: String): Path =
    val p = dir.resolve(name)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"))
    p

  /** Captures the `[loop HH:MM:SS] ...` lines LiveLog writes, with the timestamp prefix stripped,
    * so a test can assert the message BYTE FOR BYTE against the bash `log` line it ports. LiveLog
    * goes straight to `System.err`, so the swap has to happen at the JVM level.
    */
  private def captureLogLines(body: => Unit): Seq[String] =
    val buf   = new java.io.ByteArrayOutputStream()
    val saved = System.err
    try
      System.setErr(new java.io.PrintStream(buf, true, StandardCharsets.UTF_8))
      body
    finally System.setErr(saved)
    new String(buf.toByteArray, StandardCharsets.UTF_8).linesIterator
      .map(_.replaceFirst("""^\[loop \d\d:\d\d:\d\d\] """, ""))
      .toSeq

  // =============================================================================================
  // LiveGateRunner
  // =============================================================================================

  "LiveGateRunner" should "return Green (rc 0) for GATE_CMD=true" in {
    val root = tempRoot()
    val gate = LiveGateRunner(root, timeoutBin = None)

    gate.run(
      "FAST",
      "true",
      timeoutSec = 5,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Green
  }

  it should "return Red for GATE_CMD=false" in {
    val root = tempRoot()
    val gate = LiveGateRunner(root, timeoutBin = None)

    gate.run(
      "FAST",
      "false",
      timeoutSec = 5,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Red
  }

  it should "word-split a multi-token cmd rather than run it through a shell" in {
    val root = tempRoot()
    val gate = LiveGateRunner(root, timeoutBin = None)

    // "test -f /nonexistent" only behaves correctly if split into ["test","-f","/nonexistent"]
    // and exec'd directly (never handed to a shell, which GATE_CMD/MERGE_CMD never are).
    gate.run(
      "FAST",
      "test -f /nonexistent",
      timeoutSec = 5,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Red
  }

  it should "prepend <timeoutBin> <timeoutSec> ahead of the word-split cmd, in that order" in {
    val root        = tempRoot()
    val callsFile   = root.resolve("timeout-calls.log")
    val fakeTimeout = writeExecutable(
      root,
      "faketimeout.sh",
      s"""#!/usr/bin/env bash
         |printf '%s\\n' "$$*" >> "$callsFile"
         |shift
         |exec "$$@"
         |""".stripMargin
    )
    val gate = LiveGateRunner(root, timeoutBin = Some(fakeTimeout.toString))

    gate.run(
      "FAST",
      "true",
      timeoutSec = 42,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Green

    readString(callsFile).strip() shouldBe "42 true"
  }

  // Bash's `$g` is an UNQUOTED expansion: a whitespace-only GATE_CMD/CI_WAIT_CMD word-splits to
  // nothing, so `$g >"$logfile" 2>&1` degrades to a bare redirection — rc 0, log file created and
  // truncated, no command launched. The naive `cmd.trim.split("\\s+")` yields Array("") instead,
  // and ProcessBuilder throws on an empty argv[0].
  it should "treat a whitespace-only cmd as bash's rc-0 no-op (Green), not throw" in {
    val root = tempRoot()
    val gate = LiveGateRunner(root, timeoutBin = None)

    gate.run(
      "FAST",
      "   ",
      timeoutSec = 5,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Green
  }

  it should "still create and truncate the log file for a whitespace-only cmd (bash's redirection)" in {
    val root    = tempRoot()
    val logPath = root.resolve("harness/logs/g.log")
    Files.createDirectories(logPath.getParent)
    Files.write(logPath, "stale output from a previous run\n".getBytes(StandardCharsets.UTF_8))
    val gate = LiveGateRunner(root, timeoutBin = None)

    gate.run(
      "FAST",
      "\t \n ",
      timeoutSec = 5,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Green

    Files.exists(logPath) shouldBe true
    readString(logPath) shouldBe ""
  }

  // With TIMEOUT_BIN set, bash's `$g` is NOT empty: `g="$TIMEOUT_BIN $tmo $g"` still word-splits
  // to the timeout binary plus its duration, which bash then runs with no command of its own.
  it should "still launch <timeoutBin> <timeoutSec> alone for a whitespace-only cmd" in {
    val root        = tempRoot()
    val callsFile   = root.resolve("timeout-calls.log")
    val fakeTimeout = writeExecutable(
      root,
      "faketimeout.sh",
      s"""#!/usr/bin/env bash
         |printf '%s\\n' "$$*" >> "$callsFile"
         |shift
         |exec "$$@"
         |""".stripMargin
    )
    val gate = LiveGateRunner(root, timeoutBin = Some(fakeTimeout.toString))

    gate.run("FAST", "   ", timeoutSec = 42, logFile = "harness/logs/g.log")

    readString(callsFile).strip() shouldBe "42"
  }

  it should "capture both stdout and stderr, in 2>&1 order, into the log file" in {
    val root   = tempRoot()
    val script = writeExecutable(
      root,
      "both-streams.sh",
      "#!/usr/bin/env bash\necho out-line\necho err-line 1>&2\n"
    )
    val gate = LiveGateRunner(root, timeoutBin = None)

    gate.run(
      "FAST",
      script.toString,
      timeoutSec = 5,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Green

    val logged = readString(root.resolve("harness/logs/g.log"))
    logged should include("out-line")
    logged should include("err-line")
  }

  // The default GATE_CMD is repo-RELATIVE (Domain.scala) while bash's is absolute
  // ($SCRIPT_DIR/sandbox/run-fast-gate.sh, loop.sh:133). ProcessBuilder.directory does NOT
  // affect argv[0] lookup — that uses the JVM's own cwd — so this only passes if the runner
  // re-resolves a relative command word against `root`. `root` here is a temp dir, never the
  // JVM cwd.
  it should "resolve a relative cmd path against root, not the JVM cwd" in {
    val root = tempRoot()
    // Same SHAPE as the real default (`harness/sandbox/run-fast-gate.sh`) but a directory name
    // that cannot exist at the JVM cwd: otherwise an unfixed runner would find the repo's own
    // real gate script relative to its cwd and launch the containerized gate for real.
    Files.createDirectories(root.resolve("harness/sandbox-fixture"))
    writeExecutable(
      root.resolve("harness/sandbox-fixture"),
      "run-fast-gate.sh",
      "#!/usr/bin/env bash\necho gate-ran\nexit 0\n"
    )
    val gate = LiveGateRunner(root, timeoutBin = None)

    root.toAbsolutePath.toString should not be Path.of("").toAbsolutePath.toString

    gate.run(
      "FAST",
      "harness/sandbox-fixture/run-fast-gate.sh",
      timeoutSec = 5,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Green
    readString(root.resolve("harness/logs/g.log")) should include("gate-ran")
  }

  // Bash resolves a word with NO slash off PATH, never against the cwd — so a same-named file
  // sitting in `root` must NOT shadow the real binary (this stub would exit 1 -> Red).
  it should "leave a bare binary name to PATH lookup rather than resolving it against root" in {
    val root = tempRoot()
    writeExecutable(root, "true", "#!/usr/bin/env bash\nexit 1\n")
    val gate = LiveGateRunner(root, timeoutBin = None)

    gate.run(
      "FAST",
      "true",
      timeoutSec = 5,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Green
  }

  // Only argv[0] is a command word; bash never path-resolves the remaining arguments.
  it should "not rewrite relative arguments after argv[0]" in {
    val root   = tempRoot()
    val script =
      writeExecutable(root, "echo-args.sh", "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\"\n")
    val gate = LiveGateRunner(root, timeoutBin = None)

    gate.run(
      "FAST",
      s"${script.toString} harness/sandbox/x.sh",
      timeoutSec = 5,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Green

    readString(root.resolve("harness/logs/g.log")).strip() shouldBe "harness/sandbox/x.sh"
  }

  it should "map rc 124 to Timeout" in {
    val root   = tempRoot()
    val script = writeExecutable(root, "exit124.sh", "#!/usr/bin/env bash\nexit 124\n")
    val gate   = LiveGateRunner(root, timeoutBin = None)

    gate.run(
      "FAST",
      script.toString,
      timeoutSec = 5,
      logFile = "harness/logs/g.log"
    ) shouldBe GateResult.Timeout
  }

  it should "log the gate line with an ABSOLUTE log path, byte for byte with bash" in {
    // loop.sh:244: log "$label gate: $cmd (timeout ${tmo}s) -> $logfile"
    // Every run_gate call site passes `$LOG_DIR/...` and $LOG_DIR is "$SCRIPT_DIR/logs" with
    // SCRIPT_DIR a `pwd`, so `$logfile` expands ABSOLUTE. (Contrast status.jsonl's `logfile`
    // field, which loop.sh:161 deliberately relativizes — that one is NOT this line.)
    val root = tempRoot()
    val gate = LiveGateRunner(root, timeoutBin = None)

    val lines = captureLogLines {
      gate.run("FAST", "true", timeoutSec = 900, logFile = "harness/logs/issue-999-pass1.gate.log")
    }

    lines should contain(
      s"FAST gate: true (timeout 900s) -> ${root.resolve("harness/logs/issue-999-pass1.gate.log")}"
    )
  }

  // =============================================================================================
  // LiveAgentDispatch
  // =============================================================================================

  "LiveAgentDispatch.worker" should "let an IMPL_CMD override write $PATCH_OUT, landing the patch and returning Done" in {
    val root     = tempRoot()
    val dispatch = LiveAgentDispatch(
      root,
      timeoutBin = None,
      iterTimeout = 5,
      implCmd = Some("echo hello patch > \"$PATCH_OUT\""),
      fixCmd = None,
      reviewCmd = None
    )

    val outcome = dispatch.worker(
      Role.IMPL,
      promptFile = "unused.txt",
      patchOut = "harness/logs/i.patch",
      logFile = "harness/logs/i.claude.log",
      currentPatch = None
    )

    outcome shouldBe DispatchOutcome.Done
    readString(root.resolve("harness/logs/i.patch")) shouldBe "hello patch\n"
  }

  it should "return TimedOut when the override stub exits 124" in {
    val root     = tempRoot()
    val dispatch =
      LiveAgentDispatch(
        root,
        timeoutBin = None,
        iterTimeout = 5,
        implCmd = Some("exit 124"),
        fixCmd = None,
        reviewCmd = None
      )

    dispatch.worker(
      Role.IMPL,
      "unused.txt",
      "harness/logs/i.patch",
      "harness/logs/i.claude.log",
      None
    ) shouldBe DispatchOutcome.TimedOut
  }

  it should "fold any non-124 exit (including nonzero) to Done" in {
    val root     = tempRoot()
    val dispatch =
      LiveAgentDispatch(
        root,
        timeoutBin = None,
        iterTimeout = 5,
        implCmd = Some("exit 7"),
        fixCmd = None,
        reviewCmd = None
      )

    dispatch.worker(
      Role.IMPL,
      "unused.txt",
      "harness/logs/i.patch",
      "harness/logs/i.claude.log",
      None
    ) shouldBe DispatchOutcome.Done
  }

  it should "select FIX_CMD for Role.FIX, independent of IMPL_CMD" in {
    val root     = tempRoot()
    val dispatch = LiveAgentDispatch(
      root,
      timeoutBin = None,
      iterTimeout = 5,
      implCmd = Some("exit 1"), // would fold to Done too, but must not be the one that runs
      fixCmd = Some("echo fix patch > \"$PATCH_OUT\""),
      reviewCmd = None
    )

    dispatch.worker(
      Role.FIX,
      "unused.txt",
      "harness/logs/f.patch",
      "harness/logs/f.claude.log",
      None
    ) shouldBe DispatchOutcome.Done
    readString(root.resolve("harness/logs/f.patch")) shouldBe "fix patch\n"
  }

  it should "write the worker child's combined output to the given logFile (bash parity: $logf)" in {
    val root     = tempRoot()
    val dispatch = LiveAgentDispatch(
      root,
      timeoutBin = None,
      iterTimeout = 5,
      implCmd = Some("echo worker-stdout; echo worker-stderr 1>&2"),
      fixCmd = None,
      reviewCmd = None
    )

    dispatch.worker(
      Role.IMPL,
      "unused.txt",
      "harness/logs/i.patch",
      "harness/logs/issue-999-iter1.claude.log",
      None
    ) shouldBe DispatchOutcome.Done

    val logged = readString(root.resolve("harness/logs/issue-999-iter1.claude.log"))
    logged should include("worker-stdout")
    logged should include("worker-stderr")
  }

  it should "log the dispatch line with ABSOLUTE log and patch paths, byte for byte with bash" in {
    // loop.sh:274: log "dispatching $role agent -> $logf (patch -> $patch_out)"
    // Both $logf and $patch_out are `$LOG_DIR/...` at the call sites, i.e. absolute.
    val root     = tempRoot()
    val dispatch =
      LiveAgentDispatch(
        root,
        timeoutBin = None,
        iterTimeout = 5,
        implCmd = Some("true"),
        fixCmd = None,
        reviewCmd = None
      )

    val lines = captureLogLines {
      dispatch.worker(
        Role.IMPL,
        "harness/logs/issue-999.prompt.txt",
        "harness/logs/issue-999-iter1.impl.patch",
        "harness/logs/issue-999-iter1.claude.log",
        None
      )
    }

    val logAbs   = root.resolve("harness/logs/issue-999-iter1.claude.log")
    val patchAbs = root.resolve("harness/logs/issue-999-iter1.impl.patch")
    lines should contain(s"dispatching IMPL agent -> $logAbs (patch -> $patchAbs)")
  }

  "LiveAgentDispatch.review" should "land stdout in reviewFile and stderr in reviewFile.stderr" in {
    val root     = tempRoot()
    val dispatch = LiveAgentDispatch(
      root,
      timeoutBin = None,
      iterTimeout = 5,
      implCmd = None,
      fixCmd = None,
      reviewCmd = Some("echo VERDICT: APPROVE; echo diagnostic 1>&2")
    )

    val outcome = dispatch.review("the prompt (unused by the stub)", "harness/logs/r.md")

    outcome shouldBe DispatchOutcome.Done
    readString(root.resolve("harness/logs/r.md")) should include("VERDICT: APPROVE")
    readString(root.resolve("harness/logs/r.md.stderr")) should include("diagnostic")
  }

  it should "log the reviewer dispatch line with an ABSOLUTE review path, byte for byte with bash" in {
    // loop.sh:313: log "dispatching REVIEWER in the sandbox (cold, zero mounts, no mutating tools) -> $review_file"
    // $review_file is set at loop.sh:683 to `$LOG_DIR/issue-${issue}-review.md`, i.e. absolute.
    val root     = tempRoot()
    val dispatch =
      LiveAgentDispatch(
        root,
        timeoutBin = None,
        iterTimeout = 5,
        implCmd = None,
        fixCmd = None,
        reviewCmd = Some("true")
      )

    val lines = captureLogLines { dispatch.review("prompt", "harness/logs/issue-999-review.md") }

    lines should contain(
      s"dispatching REVIEWER in the sandbox (cold, zero mounts, no mutating tools) -> ${root.resolve("harness/logs/issue-999-review.md")}"
    )
  }

  it should "preserve the bash asymmetry: a REVIEW_CMD stub exiting 124 still returns Done" in {
    val root     = tempRoot()
    val dispatch =
      LiveAgentDispatch(
        root,
        timeoutBin = None,
        iterTimeout = 5,
        implCmd = None,
        fixCmd = None,
        reviewCmd = Some("exit 124")
      )

    dispatch.review("prompt", "harness/logs/r.md") shouldBe DispatchOutcome.Done
  }

  // =============================================================================================
  // LiveGit
  // =============================================================================================

  /** A bash-suite-shaped fixture: a bare "origin" plus a work clone with one commit on `main`,
    * pushed to origin, the minimum a `checkoutBranch`/`fetchOriginMain`/`applyIndex` test needs.
    */
  private def setupGitRepo(): Path =
    val bare = Files.createTempDirectory("live-git-bare")
    LiveProc.run(bare, Seq("git", "init", "--quiet", "--bare"))
    val work = Files.createTempDirectory("live-git-work")
    LiveProc.run(work, Seq("git", "init", "--quiet"))
    LiveProc.run(work, Seq("git", "config", "user.email", "t@t"))
    LiveProc.run(work, Seq("git", "config", "user.name", "t"))
    LiveProc.run(work, Seq("git", "config", "commit.gpgsign", "false"))
    Files.write(work.resolve("base.txt"), "base\n".getBytes(StandardCharsets.UTF_8))
    LiveProc.run(work, Seq("git", "add", "-A"))
    LiveProc.run(work, Seq("git", "commit", "--quiet", "-m", "init"))
    LiveProc.run(work, Seq("git", "branch", "--quiet", "-M", "main"))
    LiveProc.run(work, Seq("git", "remote", "add", "origin", bare.toString))
    LiveProc.run(work, Seq("git", "push", "--quiet", "-u", "origin", "main"))
    work

  private def newFilePatch(path: String, content: String): String =
    val lines = content.linesIterator.toList
    val body  = lines.map(l => s"+$l").mkString("\n")
    s"""diff --git a/$path b/$path
       |new file mode 100644
       |--- /dev/null
       |+++ b/$path
       |@@ -0,0 +1,${lines.size} @@
       |$body
       |""".stripMargin

  "LiveGit.statusClean" should "be true on a freshly cloned tree and false once a file is dirtied" in {
    val work = setupGitRepo()
    val git  = LiveGit(work)

    git.statusClean() shouldBe true

    Files.write(work.resolve("untracked.txt"), "x".getBytes(StandardCharsets.UTF_8))
    git.statusClean() shouldBe false
  }

  "LiveGit.fetchOriginMain / push" should "round-trip against the bare origin" in {
    val work = setupGitRepo()
    val git  = LiveGit(work)

    git.fetchOriginMain() shouldBe true

    LiveProc.run(work, Seq("git", "checkout", "--quiet", "-b", "us-1"))
    Files.write(work.resolve("feature.txt"), "feature\n".getBytes(StandardCharsets.UTF_8))
    LiveProc.run(work, Seq("git", "add", "-A"))
    LiveProc.run(work, Seq("git", "commit", "--quiet", "-m", "feature"))

    git.push("us-1")

    val originRef = LiveProc.run(work, Seq("git", "ls-remote", "origin", "refs/heads/us-1"))
    originRef.stdout should include("refs/heads/us-1")
  }

  "LiveGit.checkoutBranch" should "branch off origin/main when the branch does not exist locally, else check it out" in {
    val work = setupGitRepo()
    val git  = LiveGit(work)

    git.checkoutBranch("us-2") shouldBe true
    LiveProc
      .run(work, Seq("git", "rev-parse", "--abbrev-ref", "HEAD"))
      .stdoutTrimmedTrailingNewlines shouldBe "us-2"

    LiveProc.run(work, Seq("git", "checkout", "--quiet", "main"))
    git.checkoutBranch("us-2") shouldBe true // now takes the "exists" path
    LiveProc
      .run(work, Seq("git", "rev-parse", "--abbrev-ref", "HEAD"))
      .stdoutTrimmedTrailingNewlines shouldBe "us-2"
  }

  "LiveGit.resetHardCleanToOriginMain" should "revert tracked edits and remove untracked files" in {
    val work = setupGitRepo()
    val git  = LiveGit(work)

    Files.write(work.resolve("base.txt"), "dirtied\n".getBytes(StandardCharsets.UTF_8))
    Files.write(work.resolve("untracked.txt"), "junk".getBytes(StandardCharsets.UTF_8))

    git.resetHardCleanToOriginMain()

    readString(work.resolve("base.txt")) shouldBe "base\n"
    Files.exists(work.resolve("untracked.txt")) shouldBe false
  }

  "LiveGit.applyNumstat" should "report added/deleted/path for a valid new-file patch" in {
    val work  = setupGitRepo()
    val git   = LiveGit(work)
    val patch = newFilePatch("src/main/scala/New.scala", "object New")
    Files.write(work.resolve("new.patch"), patch.getBytes(StandardCharsets.UTF_8))

    val numstat = git.applyNumstat("new.patch")

    numstat.strip() shouldBe "1\t0\tsrc/main/scala/New.scala"
  }

  it should "fail open: return empty text for an unparseable patch, never throw" in {
    val work = setupGitRepo()
    val git  = LiveGit(work)
    Files.write(
      work.resolve("garbage.patch"),
      "this is not a patch at all\n".getBytes(StandardCharsets.UTF_8)
    )

    noException should be thrownBy {
      git.applyNumstat("garbage.patch") shouldBe ""
    }
  }

  /** PARITY GUARD, and the reason `applyNumstat` ignores the exit code entirely.
    *
    * Bash captures this with `$(git apply --numstat "$patch" 2>/dev/null)` (loop.sh:554), and
    * command substitution keeps whatever reached stdout no matter how the command exited. If the
    * port dropped stdout on a nonzero rc, rows git DID emit would vanish and
    * `Machine.touchesProtected` — the guard whose job is to block writes to `harness/`, `.github/`,
    * `docs/`, CONTEXT.md, PROMPT.md, STOP.md — would see an empty file list and wave the patch
    * through: failing open WIDER than bash, in the one guard where that matters most.
    *
    * Real git 2.50.1 parses a patch in full before emitting any numstat row, so no natural patch we
    * could construct produces rows-then-failure; a fake `git` on PATH pins the contract regardless
    * of which git version (or which failure mode) is underneath.
    */
  it should "keep partial stdout when git exits nonzero (bash $(...) semantics), so the protected-path guard still sees the rows" in {
    val work   = setupGitRepo()
    val binDir = Files.createTempDirectory("fake-git-bin")
    writeExecutable(
      binDir,
      "git",
      """#!/usr/bin/env bash
        |printf '1\t0\tharness/loop.sh\n12\t3\tsrc/main/scala/Ok.scala\n'
        |echo "error: corrupt patch at line 42" >&2
        |exit 128
        |""".stripMargin
    )
    val git = LiveGit(work, extraPath = Some(binDir.toString))

    val numstat = git.applyNumstat("whatever.patch")

    numstat shouldBe "1\t0\tharness/loop.sh\n12\t3\tsrc/main/scala/Ok.scala"
    // The whole point: the guard downstream still rejects the patch.
    Machine.touchesProtected(numstat) shouldBe true
  }

  "LiveGit.applyIndex" should "apply a valid patch (true) and stage it, and refuse a garbage patch (false)" in {
    val work  = setupGitRepo()
    val git   = LiveGit(work)
    val patch = newFilePatch("src/main/scala/New.scala", "object New")
    Files.write(work.resolve("new.patch"), patch.getBytes(StandardCharsets.UTF_8))

    git.applyIndex("new.patch") shouldBe true
    Files.exists(work.resolve("src/main/scala/New.scala")) shouldBe true
    git.anythingStaged() shouldBe true

    Files.write(work.resolve("garbage.patch"), "not a patch\n".getBytes(StandardCharsets.UTF_8))
    git.applyIndex("garbage.patch") shouldBe false

    // bash parity: `git apply --index PATCH >"$patch.apply.err" 2>&1`, unconditional — the file
    // lands with git's own error message even though the apply failed.
    val applyErr = readString(work.resolve("garbage.patch.apply.err"))
    applyErr should not be empty
  }

  // Regression: applyIndex used to call LiveProc.runToFile directly, and runToFile had no
  // pathPrepend parameter — so the documented `extraPath` FAKEBIN seam was honoured by every
  // LiveGit method EXCEPT this one. A fake `git` that always fails must make applyIndex return
  // false (the real git would have applied this patch cleanly) and its output must land in
  // <patch>.apply.err.
  it should "honour the extraPath seam, so a fake git on the fixture PATH is the one that runs" in {
    val work   = setupGitRepo()
    val binDir = Files.createTempDirectory("fake-git-bin")
    writeExecutable(
      binDir,
      "git",
      """#!/usr/bin/env bash
        |echo "fake git refuses: $*"
        |exit 1
        |""".stripMargin
    )
    val git   = LiveGit(work, extraPath = Some(binDir.toString))
    val patch = newFilePatch("src/main/scala/Seam.scala", "object Seam")
    Files.write(work.resolve("seam.patch"), patch.getBytes(StandardCharsets.UTF_8))

    // Real git applies this patch fine; only the fake one can produce `false` here.
    git.applyIndex("seam.patch") shouldBe false
    Files.exists(work.resolve("src/main/scala/Seam.scala")) shouldBe false
    readString(work.resolve("seam.patch.apply.err")) should include(
      "fake git refuses: apply --index seam.patch"
    )
  }

  "LiveGit add/commit/anythingStaged" should "round-trip: dirty -> staged -> committed -> clean" in {
    val work = setupGitRepo()
    val git  = LiveGit(work)

    git.anythingStaged() shouldBe false

    Files.write(work.resolve("new.txt"), "new\n".getBytes(StandardCharsets.UTF_8))
    git.add("new.txt")
    git.anythingStaged() shouldBe true

    git.commit("feat: add new.txt\n\nRefs #1.")
    git.anythingStaged() shouldBe false
    git.statusClean() shouldBe true
  }

  "LiveGit.addAll / diffCachedOriginMain" should "stage everything and diff against origin/main" in {
    val work = setupGitRepo()
    val git  = LiveGit(work)

    Files.write(work.resolve("new.txt"), "new content\n".getBytes(StandardCharsets.UTF_8))
    git.addAll()

    val diff = git.diffCachedOriginMain()
    diff should include("new.txt")
    diff should include("new content")
  }

  // =============================================================================================
  // LiveGitHub
  // =============================================================================================

  /** A fake `gh` on a throwaway PATH dir, mirroring statemachine-test.sh's FAKEBIN mechanism
    * (statemachine-test.sh:80-114): case-dispatches on `$1 $2`, logs every call verbatim to
    * `$GH_CALLS`, and answers deterministically (branching on the issue/PR number in argv rather
    * than env vars, since there is no per-call env-injection hook on LiveGitHub/LiveProc; matches
    * production, which never scrubs or augments PATH's sibling env vars for `gh`).
    */
  private def setupFakeGh(): (Path, Path, Path) =
    val binDir       = Files.createTempDirectory("fake-gh-bin")
    val callsFile    = Files.createTempFile("gh-calls", ".log")
    val capturedBody = Files.createTempFile("gh-captured-body", ".md")
    writeExecutable(
      binDir,
      "gh",
      s"""#!/usr/bin/env bash
         |echo "gh $$*" >> "$callsFile"
         |case "$$1 $$2" in
         |  "issue list")
         |    if [[ "$$*" == *"--label in-progress"* ]]; then echo ""
         |    elif [[ "$$*" == *"--label ready"* ]]; then echo "999"
         |    elif [[ "$$*" == *"--label blocked"* ]]; then echo "555"
         |    fi ;;
         |  "issue view")
         |    id="$$3"
         |    if [[ "$$*" == *"--json title,body"* ]]; then
         |      printf '# US-%s sample\\n\\nAC1: implement.\\n' "$$id"
         |    elif [[ "$$*" == *"--json labels"* ]]; then
         |      echo "ready class-1"
         |    elif [[ "$$*" == *"--json body"* ]]; then
         |      echo "Blocked-by: #999"
         |    elif [[ "$$*" == *"--json state"* ]]; then
         |      echo "CLOSED"
         |    fi ;;
         |  "issue edit") : ;;
         |  "pr create")
         |    for ((i=1;i<=$$#;i++)); do
         |      if [[ "$${!i}" == "--body-file" ]]; then
         |        j=$$((i+1)); cp "$${!j}" "$capturedBody"
         |      fi
         |    done
         |    echo "https://github.com/test/test/pull/123" ;;
         |  "pr comment") : ;;
         |  "pr merge") echo "merged $$3"; echo "merge warning" >&2 ;;
         |  "pr view")
         |    pr="$$3"
         |    if [[ "$$*" == *statusCheckRollup* ]]; then echo "1"
         |    elif [[ "$$pr" == "666" ]]; then exit 1
         |    else echo "MERGED"
         |    fi ;;
         |  *) : ;;
         |esac
         |""".stripMargin
    )
    (binDir, callsFile, capturedBody)

  "LiveGitHub.inProgressIssue / oldestReadyIssue" should "call the exact bash argv and parse the result" in {
    val root                   = tempRoot()
    val (binDir, callsFile, _) = setupFakeGh()
    val gh                     =
      LiveGitHub(root, ciAppearCmd = None, mergeCmd = None, extraPath = Some(binDir.toString))

    gh.inProgressIssue() shouldBe None // the fake answers "" for --label in-progress
    gh.oldestReadyIssue() shouldBe Some(999)

    val calls = readString(callsFile)
    calls should include(
      "gh issue list --state open --label in-progress --json number --jq .[0].number"
    )
    calls should include(
      "gh issue list --state open --label ready --json number,createdAt --jq sort_by(.createdAt) | .[0].number"
    )
  }

  "LiveGitHub.issueTitleAndBody" should "pass the exact jq program as one argv element" in {
    val root                   = tempRoot()
    val (binDir, callsFile, _) = setupFakeGh()
    val gh                     = LiveGitHub(root, None, None, extraPath = Some(binDir.toString))

    val body = gh.issueTitleAndBody(999)

    body should include("# US-999 sample")
    readString(callsFile) should include(
      """gh issue view 999 --json title,body --jq "# " + (.title) + "\n\n" + .body"""
    )
  }

  "LiveGitHub.editLabels" should "build one --add-label per add element and one --remove-label per remove element" in {
    val root                   = tempRoot()
    val (binDir, callsFile, _) = setupFakeGh()
    val gh                     = LiveGitHub(root, None, None, extraPath = Some(binDir.toString))

    gh.editLabels(999, add = List("in-progress"), remove = List("ready")) shouldBe true

    readString(callsFile) should include(
      "gh issue edit 999 --add-label in-progress --remove-label ready"
    )
  }

  "LiveGitHub.createPr" should "write the body to a temp file and pass it via --body-file" in {
    val root                              = tempRoot()
    val (binDir, callsFile, capturedBody) = setupFakeGh()
    val gh = LiveGitHub(root, None, None, extraPath = Some(binDir.toString))

    val prUrl = gh.createPr(
      "us-999",
      "US-999: autonomous iteration (SUCCESS, gate GREEN)",
      "the full PR body\ntext"
    )

    prUrl shouldBe "https://github.com/test/test/pull/123"
    readString(capturedBody) shouldBe "the full PR body\ntext"
    readString(callsFile) should include(
      "gh pr create --base main --head us-999 --title US-999: autonomous iteration (SUCCESS, gate GREEN) --body-file"
    )
  }

  "LiveGitHub.checksRollupCount" should "call the default gh argv when CI_APPEAR_CMD is unset" in {
    val root                   = tempRoot()
    val (binDir, callsFile, _) = setupFakeGh()
    val gh                     =
      LiveGitHub(root, ciAppearCmd = None, mergeCmd = None, extraPath = Some(binDir.toString))

    gh.checksRollupCount(42) shouldBe Some(1)

    readString(callsFile) should include(
      "gh pr view 42 --json statusCheckRollup --jq .statusCheckRollup | length"
    )
  }

  it should "run the CI_APPEAR_CMD seam instead of calling gh at all" in {
    val root                   = tempRoot()
    val (binDir, callsFile, _) = setupFakeGh()
    val gh                     = LiveGitHub(
      root,
      ciAppearCmd = Some("echo 3"),
      mergeCmd = None,
      extraPath = Some(binDir.toString)
    )

    gh.checksRollupCount(42) shouldBe Some(3)

    readString(callsFile) shouldBe ""
  }

  // loop.sh prepends FAKEBIN to PATH process-wide, so an eval'd CI_APPEAR_CMD that shells out to
  // an unqualified `gh` hits the fake. The seam arm has to pass `extraPath` like every other
  // LiveGitHub subprocess call, or the Scala harness silently reaches the REAL `gh` here.
  it should "give the CI_APPEAR_CMD seam's child the extraPath fixture directory on its PATH" in {
    val root    = tempRoot()
    val seamBin = Files.createTempDirectory("ci-appear-bin")
    writeExecutable(seamBin, "gh", "#!/usr/bin/env bash\necho 7\n")
    val gh = LiveGitHub(
      root,
      ciAppearCmd = Some("gh pr view \"$pr_num\" --json statusCheckRollup"),
      mergeCmd = None,
      extraPath = Some(seamBin.toString)
    )

    gh.checksRollupCount(42) shouldBe Some(7)
  }

  private val ciLogRel = "harness/logs/issue-42.ci-wait.log"

  /** Seeds the CI-wait log with what the CI watch would already have written, so an append can be
    * told apart from a truncating write.
    */
  private def seedCiLog(root: Path): Path =
    val p = root.resolve(ciLogRel)
    Files.createDirectories(p.getParent)
    Files.write(p, "CI watch output already here\n".getBytes(StandardCharsets.UTF_8))
    p

  "LiveGitHub.merge" should "call the default gh pr merge argv when MERGE_CMD is unset" in {
    val root                   = tempRoot()
    val (binDir, callsFile, _) = setupFakeGh()
    val gh                     =
      LiveGitHub(root, ciAppearCmd = None, mergeCmd = None, extraPath = Some(binDir.toString))

    gh.merge(42, ciLogRel) shouldBe true

    readString(callsFile) should include("gh pr merge 42 --squash --delete-branch")
  }

  // loop.sh:473 `$merge_cmd >>"$ci_log" 2>&1`: combined stdout+stderr, APPENDED to the CI-wait
  // log the caller already computed. A failed merge has to leave a diagnostic behind, and the
  // parity oracle greps this file.
  it should "append the default merge command's combined output to the CI-wait log, preserving what is there" in {
    val root           = tempRoot()
    val (binDir, _, _) = setupFakeGh()
    val ciLog          = seedCiLog(root)
    val gh             =
      LiveGitHub(root, ciAppearCmd = None, mergeCmd = None, extraPath = Some(binDir.toString))

    gh.merge(42, ciLogRel) shouldBe true

    val contents = readString(ciLog)
    contents should startWith("CI watch output already here\n") // append, NOT truncate
    contents should include("merged 42")                        // child stdout
    contents should include("merge warning")                    // child stderr, via 2>&1
  }

  it should "append a failing MERGE_CMD seam's combined output to the CI-wait log" in {
    val root           = tempRoot()
    val (binDir, _, _) = setupFakeGh()
    val ciLog          = seedCiLog(root)
    val script         = writeExecutable(
      Files.createTempDirectory("merge-cmd"),
      "bad-merge.sh",
      "#!/usr/bin/env bash\necho 'stub stdout'\necho 'stub stderr' >&2\nexit 7\n"
    )
    val gh = LiveGitHub(
      root,
      ciAppearCmd = None,
      mergeCmd = Some(script.toString),
      extraPath = Some(binDir.toString)
    )

    gh.merge(42, ciLogRel) shouldBe false

    val contents = readString(ciLog)
    contents should startWith("CI watch output already here\n")
    contents should include("stub stdout")
    contents should include("stub stderr")
  }

  it should "word-split and run the MERGE_CMD seam instead of calling gh at all" in {
    val root                   = tempRoot()
    val (binDir, callsFile, _) = setupFakeGh()
    val gh                     = LiveGitHub(
      root,
      ciAppearCmd = None,
      mergeCmd = Some("true"),
      extraPath = Some(binDir.toString)
    )

    gh.merge(42, ciLogRel) shouldBe true

    readString(callsFile) shouldBe ""
  }

  // Same unquoted-expansion rule at the MERGE_CMD seam: `$merge_cmd` word-splits to nothing, so
  // no command runs, `merge_rc` stays 0, and auto_merge proceeds to verify the PR state
  // (loop.sh:469-477) — i.e. merge() reports success and never falls back to `gh pr merge`.
  // The bare `>>"$ci_log"` redirection still runs: the file appears, and an existing one is
  // left exactly as it was.
  it should "treat a whitespace-only MERGE_CMD as bash's rc-0 no-op, without calling gh" in {
    val root                   = tempRoot()
    val (binDir, callsFile, _) = setupFakeGh()
    val ciLog                  = seedCiLog(root)
    val gh                     = LiveGitHub(
      root,
      ciAppearCmd = None,
      mergeCmd = Some("   "),
      extraPath = Some(binDir.toString)
    )

    gh.merge(42, ciLogRel) shouldBe true

    readString(callsFile) shouldBe ""
    readString(ciLog) shouldBe "CI watch output already here\n"
  }

  it should "create the CI-wait log on a whitespace-only MERGE_CMD when it does not exist yet" in {
    val root           = tempRoot()
    val (binDir, _, _) = setupFakeGh()
    val gh             = LiveGitHub(
      root,
      ciAppearCmd = None,
      mergeCmd = Some("   "),
      extraPath = Some(binDir.toString)
    )

    gh.merge(42, ciLogRel) shouldBe true

    Files.exists(root.resolve(ciLogRel)) shouldBe true
    readString(root.resolve(ciLogRel)) shouldBe ""
  }

  "LiveProc.wordSplit" should "split like an unquoted bash expansion, with empty input yielding no words" in {
    LiveProc.wordSplit("gh pr merge 42") shouldBe Seq("gh", "pr", "merge", "42")
    LiveProc.wordSplit("  sbt   test  ") shouldBe Seq("sbt", "test")
    LiveProc.wordSplit("") shouldBe Seq.empty
    LiveProc.wordSplit("   ") shouldBe Seq.empty
    LiveProc.wordSplit(" \t\n ") shouldBe Seq.empty
  }

  "LiveProc.seam" should "fold only the literal empty string to unset, exactly like bash's -n test" in {
    LiveProc.seam(None) shouldBe None
    LiveProc.seam(Some("")) shouldBe None
    LiveProc.seam(Some("gh pr merge 42")) shouldBe Some("gh pr merge 42")
    // NOT a trim: `[[ -n "   " ]]` is true, so a whitespace-only override stays set and reaches
    // the seam, where each site turns it into bash's own rc-0 no-op.
    LiveProc.seam(Some("   ")) shouldBe Some("   ")
  }

  "LiveGitHub.prState" should "return the trimmed state on success and empty string on failure" in {
    val root                   = tempRoot()
    val (binDir, callsFile, _) = setupFakeGh()
    val gh                     = LiveGitHub(root, None, None, extraPath = Some(binDir.toString))

    gh.prState(42) shouldBe "MERGED"
    gh.prState(666) shouldBe "" // the fake exits 1 for pr 666

    readString(callsFile) should include("gh pr view 42 --json state --jq .state")
  }

  // =============================================================================================
  // JDK pin (loop.sh:176-182): bash `export JAVA_HOME` + `export PATH="$JAVA_HOME/bin:$PATH"` so
  // CHILDREN inherit JDK 25. A JVM cannot mutate its own env, so the pin lands on every child
  // environment the harness builds, via the LiveProc.builder choke point.
  // =============================================================================================

  /** Runs `f` with the process-wide JDK pin set, always restoring "unpinned" afterwards. */
  private def withJdkPin[A](javaHome: Option[String])(f: => A): A =
    LiveProc.pinJdk(javaHome)
    try f
    finally LiveProc.pinJdk(None)

  "the JDK pin" should "give a spawned child JAVA_HOME and $JAVA_HOME/bin at the front of PATH" in {
    val root    = tempRoot()
    val fakeJdk = root.resolve("jdk25")
    Files.createDirectories(fakeJdk.resolve("bin"))

    val r = withJdkPin(Some(fakeJdk.toString)) {
      LiveProc.run(root, Seq("bash", "-c", "printf '%s\\n%s\\n' \"$JAVA_HOME\" \"$PATH\""))
    }

    val Array(javaHome, path) = r.stdoutTrimmedTrailingNewlines.split("\n", 2)
    javaHome shouldBe fakeJdk.toString
    // Prepended, not replacing: the inherited PATH is still behind it (bash's `:$PATH`).
    path should startWith(s"${fakeJdk.toString}/bin:")
    path.stripPrefix(s"${fakeJdk.toString}/bin:") shouldBe sys.env.getOrElse("PATH", "")
  }

  it should "reach every child the harness builds, not just LiveProc.run (e.g. the GATE_CMD seam)" in {
    val root    = tempRoot()
    val fakeJdk = root.resolve("jdk25")
    Files.createDirectories(fakeJdk.resolve("bin"))
    val probe =
      writeExecutable(root, "probe.sh", "#!/usr/bin/env bash\ntest \"$JAVA_HOME\" = \"$1\"\n")

    val verdict = withJdkPin(Some(fakeJdk.toString)) {
      LiveGateRunner(root, timeoutBin = None)
        .run(
          "FAST",
          s"${probe.toString} ${fakeJdk.toString}",
          timeoutSec = 10,
          logFile = "harness/logs/g.log"
        )
    }

    verdict shouldBe GateResult.Green
  }

  it should "pin nothing when unset, leaving the child's inherited JAVA_HOME and PATH untouched" in {
    val root = tempRoot()

    // Unpinned is exactly bash's behaviour when the pinned JDK is missing: it warns and exports
    // neither var, so the child sees whatever this process itself inherited.
    val r = withJdkPin(None) {
      LiveProc.run(
        root,
        Seq("bash", "-c", "printf '%s\\n%s\\n' \"${JAVA_HOME-<unset>}\" \"$PATH\"")
      )
    }

    val Array(javaHome, path) = r.stdoutTrimmedTrailingNewlines.split("\n", 2)
    javaHome shouldBe sys.env.getOrElse("JAVA_HOME", "<unset>")
    path shouldBe sys.env.getOrElse("PATH", "")
  }
