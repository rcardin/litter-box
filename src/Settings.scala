package in.rcard.litterbox

import com.typesafe.config.{Config as TsConfig, ConfigException, ConfigFactory, ConfigParseOptions}

import java.nio.file.{FileSystems, Files, Path, PathMatcher}
import scala.jdk.CollectionConverters.*

/** `.litter-box/config.conf` — everything repo-specific, in one HOCON file at the consumer repo's
  * root.
  *
  * Slice 1 kept every knob either in an env var or as a literal in the source, which worked only
  * because the loop lived inside the single repo it worked on. This object is the other half of
  * cutting that tie: `Machine`/`Live` read the values off `Config`, `Config` is built here, and the
  * only thing left that knows a literal like `in-progress` is the reference block below.
  *
  * Layering, outermost wins: **env var > config file > [[Reference]]**. The file may set any subset
  * of the schema; the rest falls back to the reference, so a consumer repo's config stays as short
  * as the things it actually changes. Env vars keep the exact names and meanings loop.sh gave them
  * (`GATE_CMD`, `REPAIR_BUDGET`, ...), so an operator can still override one knob for one run
  * without editing a tracked file.
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
      |  fast    = "sbt -Werror compile test"
      |  timeout = 900
      |}
      |ci { required-check = "build" }
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

  /** Merges an already-loaded config file onto [[Reference]]. The seam `parseString`-based tests use
    * so they never touch a real file.
    */
  private[litterbox] def onReference(conf: TsConfig): TsConfig = conf.withFallback(reference)

  // ---- protect: glob matching ------------------------------------------------------------------

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

  /** Env var carrying `instance-name` into every sandbox script. `sandbox/lib.sh` derives the Docker
    * image, network, proxy-container and cache-volume names from it.
    *
    * This matters even though litter-box never runs two instances at once: `start-proxy.sh` does
    * `docker rm -f "$PROXY_NAME"` before any issue label is consulted, so with machine-global names
    * a mistaken second launch tears down the running instance's proxy mid-iteration — a failure the
    * label discipline cannot prevent because it happens before the labels are read.
    */
  val InstanceEnvVar = "LITTER_BOX_INSTANCE"

  /** The environment every child of the loop inherits. One entry today; a function rather than a
    * constant so the call sites in `Main` stay honest about it being config-derived.
    */
  def childEnv(cfg: Config): Map[String, String] =
    Map(InstanceEnvVar -> cfg.instanceName)

  // ---- config -> Config ------------------------------------------------------------------------

  /** Reads the schema off `conf` (already merged onto [[Reference]] by `loadFile`/`onReference`).
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
      gateTimeout = conf.getInt("gate.timeout"),
      requiredCheck = conf.getString("ci.required-check"),
      labels = Labels(
        ready = conf.getString("issues.labels.ready"),
        active = conf.getString("issues.labels.active"),
        blocked = conf.getString("issues.labels.blocked")
      ),
      protect = conf.getStringList("protect").asScala.toList,
      repairBudget = conf.getInt("budgets.repair"),
      maxPatchBytes = conf.getLong("budgets.max-patch-bytes"),
      iterTimeout = conf.getInt("timeouts.iter"),
      ciWaitTimeout = conf.getInt("timeouts.ci-wait"),
      ciAppearTimeout = conf.getInt("timeouts.ci-appear"),
      ciAppearInterval = conf.getInt("timeouts.ci-appear-interval")
    )
