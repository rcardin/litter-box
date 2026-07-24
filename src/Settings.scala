package in.rcard.litterbox

import com.typesafe.config.{Config as TsConfig, ConfigException, ConfigFactory, ConfigParseOptions}

import java.nio.charset.StandardCharsets
import java.nio.file.{FileSystems, Files, Path, PathMatcher}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** `.litter-box/config.conf` — everything repo-specific, in one HOCON file at the consumer repo's
  * root.
  *
  * Slice 1 kept every knob either in an env var or as a literal in the source, which worked only
  * because the loop lived inside the single repo it worked on. This object is the other half of
  * cutting that tie: `Machine`/`Live` read the values off `Config`, `Config` is built here, and the
  * only thing left that knows a literal like `in-progress` is the reference block below.
  *
  * Layering, outermost wins: **env var > `.litter-box/.env` > config file > [[Reference]]**.
  *
  * THIS PARAGRAPH IS THE PROJECT'S ONE STATEMENT OF THAT ORDER. `Main.layerDotEnv`, `MainSpec`,
  * `ARCHITECTURE.md` and the README all point here instead of repeating it, because a rule written
  * down in six places is a rule that takes six edits to change and only has to be missed once to
  * leave a copy that lies. It lives with [[Reference]] rather than in a doc because the bottom
  * layer, the config file's parse and `.litter-box/.env`'s parse are all in this file.
  *
  * The config file may set any subset of the schema; the rest falls back to the reference, so a
  * consumer repo's config stays as short as the things it actually changes. Env vars keep the exact
  * names and meanings loop.sh gave them (`GATE_CMD`, `REPAIR_BUDGET`, ...), so an operator can still
  * override one knob for one run without editing a tracked file — and `.litter-box/.env` (see
  * [[loadDotEnv]]) is the same variables written down instead of exported, so it loses to an export
  * for the same reason the config file does.
  *
  * Two qualifications, each of them this same rule rather than a second one, with the reasoning at
  * the site that implements it: an EMPTY exported variable is an absent one everywhere else in the
  * loop and so shadows nothing the file says (`Main.layerDotEnv`), and a `GATE_CMD` read from the
  * file sets the gate command but never counts as an operator bypassing the sandbox preflight, which
  * only an export can say (`Main.parseEnv`).
  */
object Settings:

  /** Where the config file lives relative to the repo root. Also the directory `litter-box init`
    * scaffolds, and (via `protect`) a directory the agent may not rewrite.
    */
  val ConfigPath = ".litter-box/config.conf"

  /** The schema, with every default. Together with `Config`'s case-class defaults (Domain.scala)
    * this is the only place in `src/` that names a label, a log directory or a control file as a
    * literal: everywhere else those values are read off the `Config` in scope. The two literal sets
    * must agree, and `SettingsSpec` asserts it rather than leaving it to this comment.
    *
    * Kept as HOCON text rather than as a `Config` literal so it doubles as the documentation of the
    * file `litter-box init` writes, and so `SettingsSpec` can pin it against `Config()`'s own
    * case-class defaults — the two must agree, and a test says so rather than a comment.
    */
  val Reference: String =
    """instance-name = "litter-box"
      |conventions   = "CONTEXT.md"
      |stop-file     = "STOP.md"
      |log-dir       = ".litter-box/logs"
      |
      |gate {
      |  fast      = "sbt -Werror compile test"
      |  sandboxed = true
      |  timeout   = 900
      |}
      |issues.labels { ready = "ready", active = "in-progress", blocked = "blocked" }
      |protect  = [".litter-box/**", ".github/**", "CONTEXT.md"]
      |budgets  { repair = 2, max-patch-bytes = 1000000 }
      |timeouts { iter = 1800, ci-wait = 900, ci-appear = 300, ci-appear-interval = 10 }
      |""".stripMargin

  private lazy val reference: TsConfig = ConfigFactory.parseString(Reference)

  /** Parses `root/.litter-box/config.conf`, already merged onto [[Reference]].
    *
    * A missing file is a `Left`, never a silent default-everything: the loop's whole job is to act
    * on a repo it was pointed at, and acting on the wrong labels or writing logs to the wrong place
    * is worse than not starting. The caller turns this into rc 50 (infra fault), so an operator who
    * has not run `litter-box init` yet gets the same "stop, nothing was touched" treatment as a
    * Docker outage.
    */
  def loadFile(root: Path): Either[String, TsConfig] =
    val file = root.resolve(ConfigPath)
    if !Files.isRegularFile(file) then
      Left(
        s"no config at $file — run `litter-box init` in this repo to scaffold one"
      )
    else
      try
        Right(
          ConfigFactory
            .parseFile(file.toFile, ConfigParseOptions.defaults().setAllowMissing(false))
            .withFallback(reference)
            .resolve()
        )
      catch case e: ConfigException => Left(s"could not parse $file: ${e.getMessage}")

  /** [[Reference]] alone — the shape `parse` expects when there is no file to merge. Test-facing:
    * production always goes through `loadFile`.
    */
  private[litterbox] def referenceOnly: TsConfig = reference

  // ---- .litter-box/.env ------------------------------------------------------------------------

  /** The credential file `litter-box init` scaffolds an example of and tells the operator to fill
    * in. Read here rather than nowhere: until issue #12 nothing in `src/` opened it, so an operator
    * who followed `init`'s next steps to the letter got a FATAL saying the credential was unset in a
    * repo where it was present and correct.
    */
  val DotEnvPath = ".litter-box/.env"

  /** `root/.litter-box/.env` as a variable map, or the reason it could not be read.
    *
    * A MISSING file is `Right(empty)`, the opposite of [[loadFile]]'s treatment of `config.conf`,
    * and the difference is deliberate: `config.conf` is what tells the loop which repo it is acting
    * on, while this file is one of two equally supported ways to supply a credential — exporting the
    * variable in the shell is the other, and is what CI does. A file that EXISTS but cannot be read
    * is a `Left`, because it is the operator saying "the credential is in here" and the loop
    * failing to look; the caller turns that into rc 50 like any other unusable environment.
    */
  def loadDotEnv(root: Path): Either[String, Map[String, String]] =
    val file = root.resolve(DotEnvPath)
    if !Files.isRegularFile(file) then Right(Map.empty)
    else
      try Right(parseDotEnv(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)))
      catch case NonFatal(e) => Left(s"could not read $file: ${e.getMessage}")

  /** A valid environment-variable name, and the only thing this parser will accept as a key. Junk
    * that is not one is a line to skip, not a variable to invent.
    */
  private val EnvName = "[A-Za-z_][A-Za-z0-9_]*".r

  /** The `KEY=value` shape `resources/scaffold/env.example` produces, parsed the way an operator
    * editing that file expects and no further.
    *
    * TOTAL BY CONSTRUCTION: a line this does not understand is skipped, never thrown on. The file is
    * hand-edited at the one moment the loop has never run successfully yet, so a parser that gave up
    * wholesale over a stray line would reproduce the exact FATAL issue #12 is about, for a file that
    * holds a perfectly good token two lines further down.
    *
    * Nothing inside a value is interpreted, and the `#` that would open a comment in a shell least of
    * all: a credential is opaque text, so silently truncating one at a character it may legally
    * contain is a 401 that looks like an outage.
    */
  private[litterbox] def parseDotEnv(text: String): Map[String, String] =
    text.linesIterator.flatMap(dotEnvEntry).toMap

  private def dotEnvEntry(rawLine: String): Option[(String, String)] =
    // `export KEY=value` is accepted because the workaround issue #12 documents is
    // `set -a; source .litter-box/.env`: operators have been writing this file as something bash
    // sources, and a file that sources correctly must load correctly.
    val line = rawLine.strip.stripPrefix("export ").strip
    if line.isEmpty || line.startsWith("#") then None
    else
      line.split("=", 2) match
        case Array(rawKey, rawValue) if EnvName.matches(rawKey.strip) =>
          Some(rawKey.strip -> unquote(rawValue.strip))
        case _ => None

  private def unquote(value: String): String =
    val quoted = value.length >= 2 &&
      ((value.startsWith("\"") && value.endsWith("\"")) ||
        (value.startsWith("'") && value.endsWith("'")))
    if quoted then value.substring(1, value.length - 1) else value

  // ---- protect: floor and glob matching ---------------------------------------------------------

  /** The consumer's `protect` list with [[Reference]]'s entries unioned in underneath, deduplicated.
    *
    * HOCON list semantics are REPLACE, not merge, so `withFallback` gives a repo that sets its own
    * `protect` exactly that list and nothing else — dropping the reference entries silently, the
    * moment the consumer writes the key at all. The one it drops that matters is the `.litter-box`
    * double-star entry: without it the agent under harness can rewrite `.litter-box/config.conf` and
    * so widen its own guard, which is the single edit no patch may ever make.
    *
    * Unioning rather than merging makes the list monotone — a consumer entry can only ever ADD
    * protection, never take any away — which is what `Machine.touchesProtected`'s scaladoc and the
    * README already promise. The cost is that a repo cannot un-protect `.github` or `CONTEXT.md` by
    * omission; that is the intended trade, since both are the loop grading its own work.
    */
  private[litterbox] def protectWithFloor(conf: TsConfig): List[String] =
    (conf.getStringList("protect").asScala.toList ++ protectFloor).distinct

  /** [[Reference]]'s own `protect` entries, read off the parsed reference rather than restated here
    * so the floor cannot drift from the schema it is supposed to be the floor of.
    */
  private lazy val protectFloor: List[String] =
    reference.getStringList("protect").asScala.toList

  /** Compiles one `protect` entry into a matcher over repo-relative paths.
    *
    * `glob:` semantics are the JDK's (`FileSystems.getDefault.getPathMatcher`), which is what the
    * schema's double-star notation already assumes: a single star stops at a directory separator, a
    * double star crosses it. So the entry `.github` followed by `/` and a double star matches
    * `.github/workflows/ci.yml`; `CONTEXT.md` matches only itself; and `src` + `/` + `*.scala`
    * would match `src/Main.scala` but not `src/a/B.scala`.
    */
  private[litterbox] def matcher(pattern: String): PathMatcher =
    matchers.computeIfAbsent(pattern, p => FileSystems.getDefault.getPathMatcher(s"glob:$p"))

  /** Compiled once per pattern. `isProtected` runs the whole `protect` list against every path in a
    * numstat, so without this a thousand-file patch recompiles the same handful of globs a thousand
    * times. Bounded by the config's own list, so it cannot grow without bound.
    */
  private val matchers = new java.util.concurrent.ConcurrentHashMap[String, PathMatcher]()

  /** Whether a repo-relative path is covered by any `protect` glob.
    *
    * Total on junk input: a path `java.nio` refuses to parse (an empty string, an NUL byte) is
    * reported as NOT protected rather than throwing — the caller is the patch guard, and a throw
    * there would abort the iteration instead of rejecting the patch. It cannot widen the hole,
    * because `git apply --index` still has to accept the same path afterwards.
    */
  private[litterbox] def isProtected(protect: List[String], path: String): Boolean =
    try
      val p = Path.of(path)
      protect.exists(pat => matcher(pat).matches(p))
    catch case _: java.nio.file.InvalidPathException => false

  // ---- sandbox naming --------------------------------------------------------------------------

  /** Env var carrying `instance-name` into every sandbox script. `lib.sh` derives the Docker
    * image, network, proxy-container and cache-volume names from it.
    *
    * This matters even though litter-box never runs two instances at once: `start-proxy.sh` does
    * `docker rm -f "$PROXY_NAME"` before any issue label is consulted, so with machine-global names
    * a mistaken second launch tears down the running instance's proxy mid-iteration — a failure the
    * label discipline cannot prevent because it happens before the labels are read.
    */
  val InstanceEnvVar = "LITTER_BOX_INSTANCE"

  /** Env var carrying the repo the loop is working on into every sandbox script.
    *
    * The scripts cannot derive it any more. They used to live at `<repo>/sandbox`, so `$SCRIPT_DIR/..`
    * WAS the repo; they now ship in the artifact and run from a cache directory (`Sandbox`) that has
    * no relationship to the repo at all. `build-image.sh` needs the answer to find
    * `.litter-box/Dockerfile` and `.litter-box/allowlist`, which are the two files a consumer owns.
    */
  val RepoRootEnvVar = "LITTER_BOX_REPO_ROOT"

  /** The environment every child of the loop inherits. A function rather than a constant so the
    * call sites in `Main` stay honest about the entries being derived, not fixed.
    */
  def childEnv(cfg: Config, root: Path): Map[String, String] =
    Map(InstanceEnvVar -> cfg.instanceName, RepoRootEnvVar -> root.toString)

  // ---- config -> Config ------------------------------------------------------------------------

  /** Reads the schema off `conf` (already merged onto [[Reference]] by `loadFile`).
    * Env overlay is `Main.parseEnv`'s job, not this one's: this function is the file half of the
    * layering and stays free of `sys.env`.
    */
  def parse(conf: TsConfig): Config =
    Config(
      instanceName = conf.getString("instance-name"),
      conventions = conf.getString("conventions"),
      stopFile = conf.getString("stop-file"),
      logDir = conf.getString("log-dir"),
      gateCmd = conf.getString("gate.fast"),
      gateSandboxed = conf.getBoolean("gate.sandboxed"),
      gateTimeout = conf.getInt("gate.timeout"),
      labels = Labels(
        ready = conf.getString("issues.labels.ready"),
        active = conf.getString("issues.labels.active"),
        blocked = conf.getString("issues.labels.blocked")
      ),
      protect = protectWithFloor(conf),
      repairBudget = conf.getInt("budgets.repair"),
      maxPatchBytes = conf.getLong("budgets.max-patch-bytes"),
      iterTimeout = conf.getInt("timeouts.iter"),
      ciWaitTimeout = conf.getInt("timeouts.ci-wait"),
      ciAppearTimeout = conf.getInt("timeouts.ci-appear"),
      ciAppearInterval = conf.getInt("timeouts.ci-appear-interval")
    )
