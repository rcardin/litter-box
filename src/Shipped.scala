package in.rcard.litterbox

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** A tree of files that ships inside the artifact and has to exist on disk to be useful.
  *
  * The rule both trees obey is the one `Prompts` already made for the prompt skeletons: these files
  * are PROTOCOL, not configuration, so they travel with the code that calls them. A consumer who
  * carried a copy would carry a copy that rots the moment litter-box updates, and every fix would
  * need a re-scaffold of every repo that ever ran `init`.
  *
  * Unlike a prompt, a script has to exist as an executable file before bash or docker can be pointed
  * at it, so the classpath copy is materialised into a cache directory. The cache is keyed by the
  * DIGEST of the shipped contents, not by a version string: an upgraded binary lands in a new
  * directory automatically, a downgrade finds its old one intact, and a local edit under
  * `resources/` during development is picked up with no cache-busting step to remember.
  *
  * ONE implementation, several manifests, and that is the point (issue #15). The sandbox scripts
  * and the observability scripts have nothing to do with each other — one is run by the loop, the
  * other by a human — but the way a file gets from the jar onto disk intact is a single problem with
  * a single set of races in it, and the second copy of that answer is the one that would be missing
  * the atomic rename. Each tree names its own directory and its own manifest and inherits the rest.
  */
abstract class Shipped(val treeName: String):

  /** Every file that ships, as a path relative to this tree's directory.
    *
    * Explicit rather than discovered: a jar classloader cannot be asked for a directory listing
    * portably, and an explicit list is the only form that can be ASSERTED against the source tree.
    * `ShippedSpec` fails if `resources/<treeName>/` gains a file nobody added here, which is exactly
    * the failure this list exists to prevent — a new script that works in a checkout and is absent
    * from every install.
    */
  val ShippedFiles: List[String]

  /** Classpath prefix. Matches `resources/<treeName>/` in the source tree via
    * `//> using resourceDir ./resources`.
    */
  private def resourcePrefix: String = s"/$treeName/"

  /** The scripts the caller execs or sources by name. Anything matching gets the executable bit on
    * extraction; a Dockerfile, a proxy config or a jq filter is read by something else, never run,
    * and stays non-executable.
    */
  private def isScript(rel: String): Boolean = rel.endsWith(".sh")

  /** One shipped file's bytes. A missing resource is a broken build, not a user error — same
    * reasoning as `Prompts.builtIn` — so this throws rather than returning an `Option` every caller
    * would have to pretend might be empty.
    */
  def builtIn(rel: String): Array[Byte] =
    val res = resourcePrefix + rel
    Option(getClass.getResourceAsStream(res)) match
      case Some(in) =>
        try in.readAllBytes()
        finally in.close()
      case None =>
        throw IllegalStateException(s"$treeName resource missing from the artifact: $res")

  /** The cache key: SHA-256 over every shipped file, name and content, in `ShippedFiles` order.
    *
    * Names are hashed alongside contents so a pure RENAME (same bytes, different path) still moves
    * the key. Truncated to 12 hex chars — the git-short-hash convention, and collision-free at any
    * plausible number of litter-box builds on one machine.
    */
  lazy val digest: String =
    val md = MessageDigest.getInstance("SHA-256")
    ShippedFiles.foreach { rel =>
      md.update(rel.getBytes(StandardCharsets.UTF_8))
      md.update(0.toByte)
      md.update(builtIn(rel))
    }
    md.digest().take(6).map(b => f"${b & 0xff}%02x").mkString

  /** Where an extracted tree lives, given a home directory: `~/.cache/litter-box/<tree>/<digest>`.
    *
    * Under `$HOME` rather than `$TMPDIR` for the reason `run-fast-gate.sh` already documents at
    * length: on macOS + colima only `$HOME` is mounted into the Docker VM, and some of these files
    * are `docker build` contexts. A tree under the real TMPDIR would silently build from an empty
    * one.
    */
  def cacheDir(home: Path): Path =
    home.resolve(".cache").resolve("litter-box").resolve(treeName).resolve(digest)

  /** Adds the executable bit for owner, group and other, leaving the rest of the mode alone.
    *
    * A filesystem with no POSIX permission view cannot run these scripts anyway. Failing the whole
    * extraction here would turn "your Docker sandbox will not work" into "litter-box will not
    * start", which is a worse diagnostic for the same condition.
    */
  private def makeExecutable(file: Path): Unit =
    try
      val perms = Files.getPosixFilePermissions(file).asScala
      Files.setPosixFilePermissions(
        file,
        (perms ++ Set(
          PosixFilePermission.OWNER_EXECUTE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_EXECUTE
        )).asJava
      )
    catch case NonFatal(_) => ()

  /** Writes the shipped tree into `dir`, creating it. Total: overwrites whatever is already there.
    *
    * Every file is written to a sibling temporary and renamed into place, so a shipped path is
    * never observable in a half-written state. Writing `dest` directly would truncate it to zero
    * length before the bytes landed, and `resolve` decides whether a tree is complete by asking
    * whether its files EXIST — a second process landing in that window would find a full listing,
    * skip extraction, and exec an empty `lib.sh`. The same window swallows a `^C` between the
    * truncate and the write, which is the interrupted-extraction case `resolve` claims to catch.
    *
    * The rename is atomic and the temporary is a sibling, so it is on the same filesystem; the
    * executable bit goes on before the move, so the visible file never lacks it either.
    *
    * Split out of `resolve` so the extraction itself can be tested against a temp directory,
    * without a cache, a `$HOME`, or an opinion about when extraction is skipped.
    */
  def extract(dir: Path): Unit =
    ShippedFiles.foreach { rel =>
      val dest = dir.resolve(rel)
      Files.createDirectories(dest.getParent)
      val staged = dest.resolveSibling(s".${dest.getFileName}.${UUID.randomUUID()}.tmp")
      try
        // Files.write rather than Files.createTempFile: the latter creates owner-only by
        // construction, which would ship these files at 0700 instead of whatever the umask says.
        Files.write(
          staged,
          builtIn(rel),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE
        )
        if isScript(rel) then makeExecutable(staged)
        Files.move(staged, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      finally Files.deleteIfExists(staged)
    }

  /** The directory to run from, extracting it on first use for this build.
    *
    * The completeness check is per file, not just on the directory: an extraction interrupted
    * half-way (^C, a full disk) would otherwise leave a directory that exists, passes the check,
    * and has no `lib.sh` in it. A complete tree is not re-extracted, because the digest already
    * guarantees its contents.
    *
    * Existence is a sound proxy for completeness only because `extract` renames each file into
    * place whole (see there); a directly-written file exists at zero length first, which is
    * precisely what this check would then wave through.
    *
    * The DIRECTORY is deliberately not staged and moved as a unit. Two litter-box processes racing
    * here write byte-identical files — same digest, same bytes — so per-file renames converge on
    * the right tree no matter how they interleave, whereas swapping the directory would add a
    * failure mode where one process replaces the tree the other is mid-way through executing from.
    */
  def resolve(home: Path): Path =
    val dir = cacheDir(home)
    if !ShippedFiles.forall(rel => Files.isRegularFile(dir.resolve(rel))) then extract(dir)
    dir

  /** The production caller's `home`: `user.home`, which the JVM always defines. */
  def resolve(): Path = resolve(Paths.get(System.getProperty("user.home")))
