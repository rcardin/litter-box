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

  /** Build tools we have actually run a loop against. Exactly one today, and that is the point:
    * the enum is the list of presets we are willing to scaffold, so adding a case is a deliberate
    * claim that somebody ran the loop end to end with it.
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
    * result for which this returns a partial scaffold. A repo whose build tool we do not recognise
    * gets the same six files, with the two build-tool-shaped holes filled by a TODO rather than by
    * a guess.
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

  /** The gate command, or a loud absence of one.
    *
    * Omitting `gate.fast` from the scaffolded config would NOT be neutral: the key would fall back
    * to `Settings.Reference`, which is `sbt -Werror compile test`, and a Gradle repo would silently
    * acquire an sbt gate. So the non-sbt case writes an explicit `false`, which is a command that
    * exists on every system and always fails. The loop then reports a red gate on iteration one,
    * which is the correct answer to "you have not told me how to build this".
    */
  private def configConf(d: Detected): String =
    val gate = d.buildTool match
      case Some(BuildTool.Sbt) => """  fast    = "sbt -Werror compile test""""
      case None                =>
        """  # TODO: your fast gate command. It must compile and run the in-memory test tier, and
          |  # must NOT need Docker, the network beyond .litter-box/allowlist, or a credential.
          |  # Until you set it, `false` keeps the gate honestly red rather than inheriting a
          |  # build tool nobody confirmed this project uses.
          |  fast    = "false"""".stripMargin
    Machine.renderTemplate(resource("config.conf"), "GATE" -> gate)

  private def dockerfile(d: Detected): String =
    val (install, entrypoint) = d.buildTool match
      case Some(BuildTool.Sbt) =>
        (
          """ARG SBT_VERSION=1.12.9
            |RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
            |      -o /tmp/sbt.tgz \
            |    && tar -xzf /tmp/sbt.tgz -C /usr/local \
            |    && rm /tmp/sbt.tgz \
            |    && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt""".stripMargin,
          """ENTRYPOINT ["sbt"]"""
        )
      case None =>
        (
          """# TODO: install your build tool here, pinned to an exact version so image rebuilds
            |# are reproducible. It must land on PATH for the non-root `gate` user.""".stripMargin,
          """# TODO: ENTRYPOINT ["<your build tool>"]"""
        )
    Machine.renderTemplate(
      resource("Dockerfile"),
      "BUILD_TOOL" -> install,
      "ENTRYPOINT" -> entrypoint
    )

  /** What went unanswered, in the operator's words. Printed to stderr after a successful `init`,
    * because every one of these is a thing that will otherwise fail on iteration one.
    */
  def warnings(d: Detected): List[String] =
    List(
      Option.when(d.buildTool.isEmpty)(
        "no build tool detected (no build.sbt at the repo root). The scaffolded Dockerfile and " +
          "gate.fast both carry a TODO — litter-box will not guess, because a preset nobody has " +
          "run a loop against is worse than no preset."
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
