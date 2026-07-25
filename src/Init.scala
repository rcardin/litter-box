package in.rcard.litterbox

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.util.control.NonFatal

/** `litter-box init` — the way into a repo that is not litter-box.
  *
  * Slice 2 made the loop read everything repo-specific out of `.litter-box/config.conf`; nothing
  * wrote that file, so the only repo that could run the loop was the one whose config was already
  * committed. This is the other half.
  *
  * Split pure/IO on the same line `Settings` is: `plan` and `warnings` are total functions of what
  * was detected and hold every decision worth testing, while `run` is a thin shell that writes what
  * `plan` returned. So `InitSpec` can assert on the content of a scaffold without a filesystem, and
  * on the refusal semantics with one.
  */
object Init:

  val Dir = ".litter-box"

  /** Build tools `init` can put a NAME to. Not presets: since #13 nothing here buys an install
    * block or a gate command, so a case is only a claim that we can recognise the marker file and
    * say so in the TODO the operator fills in. Adding one is correspondingly cheap, and buys
    * correspondingly little — the honest trade, given that seeing a build file tells you the tool
    * and never the invocation.
    */
  enum BuildTool:
    case Sbt

  final case class Detected(
      buildTool: Option[BuildTool],
      remote: Option[String],
      jdk: Option[String]
  )

  /** What `init` found. Nothing here is fatal: a repo can gain a remote or a build file after
    * scaffolding, and refusing to write a correct `.litter-box/` because `gh` is not installed
    * would be a worse trade than warning about it.
    *
    * TEST AFFORDANCE, deliberate, and the same shape as `Main.findOnPath`'s `exists` and
    * `Main.resolveRepoRoot`'s `revParse`: `exec` is the subprocess, injected so detection can be
    * exercised against invented results. THE ONLY PRODUCTION CALLER IS `Main`, which passes the
    * real `LiveProc.run`.
    */
  def detect(root: Path, exec: Seq[String] => LiveProc.Result): Detected =
    val buildTool =
      if Files.isRegularFile(root.resolve("build.sbt")) then Some(BuildTool.Sbt) else None

    val remote =
      try
        val r = exec(Seq("gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"))
        Option(r.stdoutTrimmedTrailingNewlines.strip()).filter(_ => r.rc == 0).filter(_.nonEmpty)
      catch case NonFatal(_) => None

    val jdk =
      try
        val r = exec(Seq("java", "-version"))
        // `java -version` writes to stderr, and has done since 1.0. The major version is the first
        // dotted component of the quoted version string on 9 and later ("21.0.5"), but the SECOND
        // on 8 and earlier, which spell themselves "1.8.0_392". Skipping an optional leading `1.`
        // reads both, rather than telling an operator on 8 that they build under JDK 1.
        "\"(?:1\\.)?(\\d+)".r.findFirstMatchIn(r.stderr + r.stdout).map(_.group(1))
      catch case NonFatal(_) => None

    Detected(buildTool, remote, jdk)

  /** Every file `init` writes, as repo-relative path to content. Total: there is no detection
    * result for which this returns a partial scaffold, and since #13 no detection result for which
    * it returns a DIFFERENT one either. Every repo gets the same six files, with the two
    * project-shaped holes — the Dockerfile's install layer and `gate.fast` — filled by a TODO that
    * quotes what was detected rather than by a command nobody has run.
    */
  def plan(d: Detected): List[(String, String)] =
    List(
      s"$Dir/config.conf"            -> configConf(d),
      s"$Dir/Dockerfile"             -> dockerfile(d),
      s"$Dir/allowlist"              -> resource("allowlist"),
      s"$Dir/prompts/conventions.md" -> resource("conventions.md"),
      s"$Dir/.env.example"           -> resource("env.example"),
      s"$Dir/.gitignore"             -> resource("gitignore")
    )

  /** The gate command, which is always the loud absence of one.
    *
    * Omitting `gate.fast` from the scaffolded config would NOT be neutral: the key would fall back
    * to `Settings.Reference`, and a repo would silently acquire litter-box's own idea of a build.
    * So the scaffold writes an explicit `false`, a command that exists on every system and always
    * fails. The loop then reports a red gate on iteration one, which is the correct answer to "you
    * have not told me how to build this".
    *
    * Until #13 that discipline applied only to the repos we had failed to recognise, and a
    * `build.sbt` bought `sbt -Werror compile test` instead. That command does not run: `-Werror` is
    * a scalac flag, sbt parses bare arguments as commands, and it answers `Not a valid command: -`.
    * So the case we were confident about was the only one we got wrong, and nothing caught it
    * because litter-box's own gate is scala-cli and no test can run sbt. The lesson is not "fix the
    * sbt string" — it is that detecting a build tool tells you the tool and never the invocation,
    * so there is no string to fix. Detection survives here as the TEXT of the TODO, which is the
    * most it was ever entitled to be.
    *
    * The command runs INSIDE the sandbox container (`gate.sandboxed`, defaulted true in the
    * scaffolded config.conf), so it is read against that image's PATH and not the host's. Before #9
    * it was a host command, which meant every scaffolded repo ran a weaker gate tier than litter-box
    * ran on itself and nothing anywhere said so.
    */
  private def configConf(d: Detected): String =
    val detected = d.buildTool match
      case Some(BuildTool.Sbt) =>
        """  # build.sbt was found at the repo root, so this project builds with sbt. That is the end
          |  # of what detection knows. Scalac flags in particular are not sbt commands: sbt reads a
          |  # bare `-Werror` as a command and answers "Not a valid command: -", so a build that
          |  # wants it either sets scalacOptions in build.sbt or forces it with
          |  #   sbt 'set ThisBuild/scalacOptions += "-Werror"' compile test""".stripMargin
      case None =>
        """  # No build file litter-box recognises was found at the repo root, so it has nothing to
          |  # say about what this project is built with.""".stripMargin
    val gate =
      s"""  # TODO: your fast gate command.
         |$detected
         |  #
         |  # It must compile and run the in-memory test tier, and must NOT need Docker, the network
         |  # beyond .litter-box/allowlist, or a credential. It runs inside the container built from
         |  # .litter-box/Dockerfile, so name the tool you installed there. Until you set it, `false`
         |  # keeps the gate honestly red rather than running a command nobody confirmed.
         |  fast      = "false"""".stripMargin
    Machine.renderTemplate(resource("config.conf"), "GATE" -> gate)

  /** The gate image. One hole, and it is a TODO in every case: the project-specific layer.
    *
    * The skeleton around the hole is everything litter-box can actually guarantee — the
    * `ARG BASE_IMAGE`/`FROM` pair, `USER root` for the install, `USER gate` to come back down to a
    * non-root user, `WORKDIR /workspace`, no ENTRYPOINT. Those lines are the sandbox contract. The
    * JDK and the build tool are not: they are properties of a project litter-box has read two files
    * of, and #13 is what asserting them cost. `detect` reads the HOST's `java -version` and
    * `warnings` duly told the operator their JDK was not 21 — and then this method filled only the
    * build-tool hole and scaffolded temurin 21 anyway, so `init` contradicted its own warning
    * inside the same run. Against a `-release:25` project that fails as late as the tier allows:
    * image built, proxy up, 50 sources compiled, then `25 is not a valid choice for
    * -java-output-version`. Detection now writes the TODO's text instead, which reports the
    * evidence without pretending to be a decision.
    *
    * No ENTRYPOINT hole any more (#9). All three runners override the image's entrypoint — the gate
    * runs `gate.fast` through `bash -c`, the agent and reviewer run their own entrypoint script — so
    * an ENTRYPOINT here would be dead, and the sbt preset's `ENTRYPOINT ["sbt"]` was worse than
    * dead: `run-fast-gate.sh` appended sbt's own flags to whatever it was, which put the build-tool
    * coupling straight back one layer below where #4 removed it.
    */
  private def dockerfile(d: Detected): String =
    val jdkNote = d.jdk match
      case Some("21") =>
        """# JDK: `java -version` here reported 21, which is what the base image already ships, so
          |# this project probably needs no JDK layer at all. Confirm that and move on.""".stripMargin
      case Some(v) =>
        s"""# JDK: `java -version` here reported $v, and the base image ships temurin 21. Mind the
           |# gap: nothing fails early on it. The image builds, the proxy starts, the sources
           |# compile, and then the gate dies on something like `$v is not a valid choice for
           |# -java-output-version`. Install the JDK this project targets above, or confirm 21 is
           |# what it should be built against.""".stripMargin
      case None =>
        """# JDK: the version this host builds under could not be read, so nothing was assumed about
          |# it. The base image ships temurin 21 — confirm that is what this project wants.""".stripMargin

    val toolNote = d.buildTool match
      case Some(BuildTool.Sbt) =>
        """# Build tool: build.sbt was found at the repo root, so this project builds with sbt.
          |# litter-box still does not install it for you. The launcher and the version are this
          |# project's decision, and a pin nobody confirmed is a gate that breaks on the first
          |# rebuild, in a container, where it is hardest to read.""".stripMargin
      case None =>
        """# Build tool: no build file litter-box recognises was found at the repo root, so it has
          |# nothing to say about what this project is built with.""".stripMargin

    val layer =
      s"""# TODO: install this project's JDK and build tool here. This block is the only part of the
         |# sandbox that knows what this project is, which is exactly why `init` will not write it:
         |# what it saw on the HOST is evidence about this container, never a decision about it.
         |#
         |$jdkNote
         |#
         |$toolNote
         |#
         |# Pin every version exactly so an image rebuild is reproducible, and make sure whatever you
         |# install lands on PATH for the non-root `gate` user, because gate.fast runs as that user
         |# inside this image.""".stripMargin
    Machine.renderTemplate(resource("Dockerfile"), "PROJECT_LAYER" -> layer)

  /** What went unanswered, in the operator's words. Printed to stderr after a successful `init`,
    * because every one of these is a thing that will otherwise fail on iteration one.
    *
    * The build-tool warning fires whatever was detected, since #13. It used to fire only when
    * nothing was found, which was the scaffolder's mistake restated: recognising a build file was
    * being read as having answered the question. The unanswered thing is the TODO, and every repo
    * now has two of them.
    */
  def warnings(d: Detected): List[String] =
    List(
      Some(d.buildTool match
        case Some(BuildTool.Sbt) =>
          "build.sbt found, so this project builds with sbt — which names the tool and not the " +
            "command. The scaffolded Dockerfile and gate.fast both carry a TODO; fill them in " +
            "before the first run, or the gate is red by construction."
        case None =>
          "no build tool detected (no build.sbt at the repo root). The scaffolded Dockerfile and " +
            "gate.fast both carry a TODO — litter-box will not guess, because a command nobody " +
            "has run is worse than no command."
      ),
      Option.when(d.remote.isEmpty)(
        "no GitHub remote found via `gh`. The loop reads and writes issues, labels and PRs, so it " +
          "needs one before it can run."
      ),
      d.jdk.filterNot(_ == "21").map { v =>
        s"this repo builds under JDK $v, but the litter-box base image ships temurin 21. Either " +
          "add your JDK in .litter-box/Dockerfile or confirm 21 is fine."
      }
    ).flatten

  /** Printed on success. The three things a fresh scaffold cannot do for itself. */
  def nextSteps(d: Detected): List[String] =
    List(
      s"fill in $Dir/prompts/conventions.md — it is spliced into every prompt and is the highest-value file here",
      s"cp $Dir/.env.example $Dir/.env and fill in one credential",
      "create the labels: gh label create ready && gh label create in-progress && gh label create blocked"
    )

  /** Writes the scaffold. Returns the paths written, or the reason nothing was.
    *
    * The existence check covers the WHOLE directory and happens before the first write, so a
    * refused `init` is guaranteed to have changed nothing. Checking per file instead would let a
    * refusal land halfway, which is the state hardest to recover from: neither scaffolded nor
    * untouched, and no way to tell which files are yours.
    */
  def run(root: Path, d: Detected, force: Boolean): Either[String, List[String]] =
    val dir = root.resolve(Dir)
    if Files.exists(dir) && !force then
      Left(s"$dir already exists — pass --force to overwrite it")
    else
      val files = plan(d)
      files.foreach { case (rel, content) =>
        val p = root.resolve(rel)
        Files.createDirectories(p.getParent)
        Files.write(
          p,
          content.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
      }
      Right(files.map(_._1))

  /** A scaffold template out of the artifact. Same reasoning as `Prompts.builtIn`: a missing
    * resource is a broken build, not a user error.
    */
  private def resource(name: String): String =
    val res = s"/scaffold/$name"
    Option(getClass.getResourceAsStream(res)) match
      case Some(in) =>
        try new String(in.readAllBytes(), StandardCharsets.UTF_8)
        finally in.close()
      case None =>
        throw IllegalStateException(s"scaffold resource missing from the artifact: $res")
