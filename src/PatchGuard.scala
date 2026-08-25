package in.rcard.litterbox

import java.nio.file.{FileSystems, Path, PathMatcher}

/** The patch seam, as a module of its own rather than a set of private helpers inside the shipped
  * graph: reset to the pristine base, inspect the patch, THEN apply it or stage a refusal marker in
  * its place. The tree the agent edited is data to inspect, never trusted.
  *
  * Why this is a TIER 1 file (`docs/adr/0001-framework-tier-is-kit-only.md` carries the tier rule
  * itself, and this object is the third file the rule now names). Every graph that dispatches an
  * agent has to decide what may reach the index, and until this file existed the only implementation
  * of that decision was `private` inside `Machine`, the shipped graph. A consumer authoring their own
  * graph through `LitterBox.graph` could not call it, so the one worked example this repository
  * ships, `test/ReviewFixLoopExample.scala`, restated the guard by hand and got it WEAKER: prefix
  * matching against a `**` suffix where the real guard runs a JDK glob, a difference that silently
  * stops protecting `CONTEXT.md`, a `src` + `/` + `*.scala` entry, and every other pattern whose
  * star is not at the end.
  * That is the whole argument for the move: a security decision reachable only through one graph's
  * private helper is a security decision every other graph reimplements, and reimplements worse.
  *
  * What deliberately did NOT move with it. The agent DISPATCH stays with the caller, because what a
  * dispatch costs is the node's `Cost` and the runner's own metering (`Runner.Ledger`), and a guard
  * that dispatched would be spending a budget it cannot see. The status emit and the per role log
  * narration stay in `Machine` too, because they need a `Cursor` and they carry the IMPL/FIX
  * `Announce`/`Silent` split, which is routing this graph does, not a fact about the patch. What
  * crosses back is a [[Staged]] value, and the caller decides what it means.
  */
object PatchGuard:

  /** Every path a `git apply --numstat` block names, junk lines dropped rather than rejected: the
    * numstat is read for a REFUSAL decision, so a line this parse cannot read must not be able to
    * abort the read of the lines around it.
    */
  def numstatPaths(numstat: String): List[String] =
    numstat.linesIterator.toList.flatMap(line => NumstatRow.parse(line).map(_.path))

  /** Compiles one `protect` entry into a matcher over repo relative paths.
    *
    * `glob:` semantics are the JDK's (`FileSystems.getDefault.getPathMatcher`), which is what the
    * schema's double star notation already assumes: a single star stops at a directory separator, a
    * double star crosses it. So the entry `.github` followed by `/` and a double star matches
    * `.github/workflows/ci.yml`; `CONTEXT.md` matches only itself; and `src` + `/` + `*.scala`
    * would match `src/Main.scala` but not `src/a/B.scala`.
    *
    * Lived in `Settings` until the guard became a module. It moved rather than being reached for
    * from here because the tier rule forbids naming `Settings` from tier 1, and because compiling a
    * glob is guard mechanics while `Settings.protectWithFloor`, which stayed put, is config
    * layering: one decides what a pattern MEANS, the other decides which patterns are in the list.
    */
  private[litterbox] def matcher(pattern: String): PathMatcher =
    matchers.computeIfAbsent(pattern, p => FileSystems.getDefault.getPathMatcher(s"glob:$p"))

  /** Compiled once per pattern. [[touchesProtected]] runs the whole `protect` list against every
    * path in a numstat, so without this a thousand file patch recompiles the same handful of globs a
    * thousand times. Bounded by the config's own list, so it cannot grow without bound.
    */
  private val matchers = new java.util.concurrent.ConcurrentHashMap[String, PathMatcher]()

  /** Whether a repo relative path is covered by any `protect` glob.
    *
    * Total on junk input: a path `java.nio` refuses to parse (an empty string, an NUL byte) is
    * reported as NOT protected rather than throwing, since the caller is the patch guard and a throw
    * there would abort the iteration instead of rejecting the patch. It cannot widen the hole,
    * because `git apply --index` still has to accept the same path afterwards.
    */
  private def isProtected(protect: List[String], path: String): Boolean =
    try
      val p = Path.of(path)
      protect.exists(pat => matcher(pat).matches(p))
    catch case _: java.nio.file.InvalidPathException => false

  /** Whether a patch touches anything the consumer repo declared off limits in `protect`: CI
    * workflows, the loop's own installed files, the constitution, whatever that repo names.
    *
    * Takes the list rather than reading `Config` so that the matching decision is testable one row
    * at a time. It does NOT union the reference floor into the list it is handed, and that is the
    * decision rather than an omission: `Settings.protectWithFloor` unions the floor once, at load,
    * so every `Config.protect` already carries it, and a second union here would be a second
    * definition of the floor, free to disagree with the first the day either moves. [[stage]] is the
    * door a graph actually uses and it reads `cfg.protect`, so nothing that runs a real patch can be
    * talked out of the floor.
    */
  def touchesProtected(protect: List[String], numstat: String): Boolean =
    numstatPaths(numstat).exists(p => isProtected(protect, p))

  /** The ruling itself: pure, total, no capabilities, over the two facts a refusal can rest on (how
    * big the patch is, what it touches) and the config that bounds them.
    *
    * Split out of [[stage]] so the decision can be driven directly, with a table of numstats and
    * protect lists, instead of only through a staging run that needs a git fake. `stage` is then the
    * one place that turns a ruling into world changes.
    */
  def rule(bytes: Long, numstat: String)(using cfg: Config): Ruling =
    if bytes > cfg.maxPatchBytes then Ruling.Oversized
    else if touchesProtected(cfg.protect, numstat) then Ruling.Protected
    else Ruling.Clean

  /** On a guard rejection the tree is left pristine: a hostile or oversized patch is NEVER applied.
    * Stage a small tracked marker instead, so the terminal still has a diff to open the audit PR
    * with. The marker, not the rejected change, lands on the throwaway branch.
    *
    * No knob over the text: a consumer able to substitute the marker is a consumer able to stage the
    * rejected patch AS the marker, which is the one thing this whole file exists to prevent.
    */
  private def writeRejectMarker(reason: String, numstat: String)(using
      git: Git,
      fs: HarnessFs
  ): Unit =
    fs.write(
      "PATCH-REJECTED.md",
      s"""# Patch rejected by the harness guard
         |
         |$reason
         |
         |This branch is opened for the audit trail ONLY and must NOT be merged. The rejected
         |patch was never applied to the tree. Numstat of the rejected patch (added deleted path):
         |
         |```
         |${numstat.linesIterator.take(100).mkString("\n")}
         |```
         |""".stripMargin
    )
    git.add("PATCH-REJECTED.md")

  /** Reset to the pristine base, inspect `patchFile`, then apply it or stage the refusal marker.
    *
    * The reset runs FIRST, before anything reads the patch, so whatever the agent left in the working
    * tree is gone before a single decision is made about the diff it produced.
    *
    * Fail open on an unparseable patch is DELIBERATE and backstopped: a patch `git apply --numstat`
    * cannot read yields an empty numstat, so the protected path check passes, but `git apply --index`
    * then refuses the same patch, so a malformed patch never reaches the gates. [[Staged.ApplyFail]]
    * is an infra fault at every call site, and no budget is spent on it.
    *
    * Takes the capabilities it needs one by one rather than a whole `Caps` bundle, which is what
    * makes it callable from both worlds `Caps`'s own doc describes: from a plain function that was
    * handed `Config`/`Git`/`HarnessFs`/`Log` individually, and from inside a `Node` body, where
    * `Caps.given` derives each of the four from the ambient bundle.
    *
    * Raises nothing. A guard rejection and a refused apply are both VALUES here, so the caller keeps
    * the choice of what they mean: the shipped graph faults on `ApplyFail` and routes a rejection to
    * needs human, and a consumer graph is free to decide otherwise.
    */
  def stage(patchFile: String)(using
      cfg: Config,
      git: Git,
      fs: HarnessFs,
      logger: Log
  ): Staged =
    git.resetHardCleanToOriginMain()
    if fs.sizeBytes(patchFile) == 0 then Staged.Empty
    else
      val numstat = git.applyNumstat(patchFile)
      val bytes   = fs.sizeBytes(patchFile)
      rule(bytes, numstat) match
        case Ruling.Oversized =>
          logger.log(
            s"patch guard: ${bytes}B exceeds the ${cfg.maxPatchBytes}B cap — rejecting oversized patch (not applied)"
          )
          writeRejectMarker(
            s"Oversized patch: $bytes bytes exceeds the ${cfg.maxPatchBytes}-byte cap.",
            numstat
          )
          Staged.Oversize
        case Ruling.Protected =>
          logger.log(
            s"patch guard: patch touches a protected path (${cfg.protect.mkString(", ")}) — rejecting (not applied)"
          )
          writeRejectMarker(
            "Patch touches a protected path (CI workflow, loop code, docs, or a control/constitution file).",
            numstat
          )
          Staged.Protected
        case Ruling.Clean =>
          if !git.applyIndex(patchFile) then
            logger.log(
              s"git apply refused the patch (see ${patchFile}.apply.err) — infra fault, no budget spent"
            )
            Staged.ApplyFail
          else Staged.Ok(patchFile)
