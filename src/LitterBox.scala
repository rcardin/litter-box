package in.rcard.litterbox

/** What a consumer outside this package can hand to [[LitterBox.run]]: the shape of a run, without
  * the capabilities a real tick needs to walk it (issue #43, closing the minimum of a gap two other
  * places already name in so many words: `Machine.shippedWorkflow`'s own doc and `Runner.Ledger`'s
  * own doc both call out "a public entry point that owns its own `Ledger` and exposes something
  * equivalent to `shippedWorkflow` outside this package" as left to #41/#43).
  *
  * `sealed`, on purpose: a consumer can PASS a `LoopGraph` (`LitterBox.shipped` is, today, the only
  * one that exists) but cannot WRITE one, because `sealed` restricts extending this trait to code in
  * the same file, never merely satisfying its members from elsewhere. `workflow`'s own return type
  * closes the other half of the door: `Workflow[Machine.ShippedStart]` names `Machine.ShippedStart`,
  * itself `private[litterbox]`, so a foreign implementation cannot even write a matching return type,
  * let alone construct one. Naming `Faulting` or `Runner.Ledger` in a signature is NOT what does this
  * work, and saying so would overclaim: `Faulting` is a transparent alias for the public
  * `scala.util.boundary.Label[LoopExit]`, so a foreign class can write that dealiased type in
  * `workflow`'s parameter list and type-check; `Runner.Ledger` is a public type with only its
  * constructor marked `private[litterbox]` (`Runner`'s own doc), so naming it in a parameter position
  * needs no construction at all. The seal plus `workflow`'s `private[litterbox]` return type are what
  * actually keep this shut.
  *
  * Say plainly what this does NOT yet buy, rather than let a reader infer a stronger claim than the
  * one actually made here: `LitterBox.shipped` is the only inhabitant of this type today, so a
  * consumer can run the shipped pipeline end to end and cannot yet swap one of its nodes for their
  * own, or hand `LitterBox.run` a second, differently shaped graph. That is a real, deliberate limit
  * of this issue, not an oversight left for later to notice: a future issue that wants a consumer
  * authored graph has to widen this trait's own surface, not merely add a second value of it.
  */
sealed trait LoopGraph:
  private[litterbox] def workflow(
      cfg: Config,
      caps: Caps,
      faulting: Faulting,
      ledger: Runner.Ledger
  ): Workflow[Machine.ShippedStart]

  private[litterbox] def shape(cfg: Config): Shape

/** The library's own public front door (issue #43): the coordinate a consumer depends on, the one
  * graph this issue ships, and the one way to run it.
  */
object LitterBox:

  /** The version this library ships under. `0.1.1`, not `0.1.0`, because issue #6 already decided
    * the first release under the packaging slice is `v0.1.1`; this constant restates that decision
    * rather than choosing a new one.
    *
    * Defined here, in exactly one place, so [[Coordinate]] below is the only reader of it: a version
    * bumped in one place and quoted stale in another is the #13 defect in miniature this codebase
    * already gave a name to, for a different pair of facts.
    */
  val Version: String = "0.1.1"

  /** The Maven coordinate this library ships under. Real since issue #41: `.github/workflows/
    * release.yml`'s `publish` job runs `scala-cli publish` against the Sonatype Central Portal on
    * every `v*` tag, configured entirely by that job's own flags, so every version
    * this constant can name is a version that tag actually pushed to Central. Versions released
    * before that job existed published no library and resolve nowhere; the tag guard in that same
    * workflow's `build` job is what keeps [[Version]] from ever naming one of them by accident.
    *
    * Deliberately NOT sbt-ci-release, which is how the sibling `yaes` project publishes: this
    * repository has no sbt build and must not grow one (see the header of `project.scala`), and an
    * sbt build added purely to publish would restate the scala version, the dependencies and a non
    * standard source layout in a second file nothing keeps in step with the first.
    *
    * Rendered into that scaffold's own `//> using dep` line through `Machine.renderTemplate`, the
    * same hole mechanism `Init.configConf`/`Init.dockerfile` already use for a fact neither of those
    * templates could hardcode; `InitSpec` asserts the scaffolded text against THIS constant, never a
    * second, duplicated literal, so the two cannot drift the way a version bumped in one string and
    * not the other always eventually does.
    */
  val Coordinate: String = s"in.rcard::litter-box:$Version"

  /** The tag of the sandbox base image a scaffolded repo pins in `.litter-box/Dockerfile`'s
    * `ARG BASE_IMAGE=` default. Lives beside [[Version]] rather than beside `Init` or the release
    * workflow, because the whole point of building both from one tag (issue #6) is that the image
    * and the binary version MOVE TOGETHER: `.github/workflows/release.yml` cuts `litter-box-base`
    * and `litter-box` from the same `v$Version` push, so a `0.1.1` binary that scaffolds a
    * `0.1.0` image default would be a release process that shipped two things under one tag and
    * pointed them at each other's past. That the pushed tag actually IS `v$Version`, and not some
    * other version bumped here and not there, is enforced by a guard step in `release.yml`'s
    * `build` job, which fails before anything is packaged if the tag and this constant disagree;
    * without that step this paragraph would describe an invariant nothing checked. Rendered into
    * the scaffold through the same `Machine.renderTemplate` hole mechanism [[Coordinate]] already
    * uses for `loop.scala`;
    * `InitSpec` asserts the scaffolded Dockerfile against THIS constant, never a second, hand-copied
    * literal that could drift the way the one it replaces did.
    */
  val BaseImage: String = s"ghcr.io/rcardin/litter-box-base:$Version"

  /** The shipped pipeline (`Machine.shippedWorkflow`/`Machine.shippedShape`), as the one publicly
    * reachable `LoopGraph` this issue ships. Delegates rather than reimplements: this is the exact
    * same graph `Machine.runOnce` has always walked, not a second copy of it built for the public
    * surface, so a consumer running `LitterBox.shipped` runs the identical pipeline `lb` itself runs,
    * by construction rather than by two definitions kept in sync by hand.
    */
  val shipped: LoopGraph = new LoopGraph:
    private[litterbox] def workflow(
        cfg: Config,
        caps: Caps,
        faulting: Faulting,
        ledger: Runner.Ledger
    ): Workflow[Machine.ShippedStart] =
      Machine.shippedWorkflow(cfg, caps, faulting, ledger)

    private[litterbox] def shape(cfg: Config): Shape =
      Machine.shippedShape(cfg)

  /** What `lb` itself does with a graph, opened to a consumer that owns none of `Main`'s own Live
    * wiring (issue #43). Reuses that wiring rather than duplicating it: `Main.dispatch` is the one
    * function both `Main.litterBoxLoop` (the `lb` entry point) and this call, so parsing `args`,
    * preflight, and every capability the loop wires stay defined in exactly one place regardless of
    * which of the two doors a caller came in through. For `init`, `eject`, `watch`/`tail` and `help`
    * this behaves identically to `lb` itself, since none of those touch a `LoopGraph` at all; for the
    * loop subcommand, `graph` is what `Machine.runOnce` walks on every tick instead of the default.
    *
    * TERMINATES THE JVM despite its `Unit` return type, and does so on every path: a parse failure,
    * `init`, `eject`, `watch`/`tail` and `help` each end in their own `sys.exit` inside
    * `Main.dispatch`, and the loop subcommand reaches one too, at the trailing
    * `sys.exit(runDriver(...))` inside `Main.runLoop`. Said plainly rather than left for the
    * signature to misstate: a caller of THIS function, unlike a caller of `Main.runDriver`, never
    * gets control back. `Main.runDriver`'s own scaladoc keeps `sys.exit` out of itself so the rc to
    * exit code mapping stays callable and testable without ending the JVM; that discipline stops at
    * `runDriver`'s own caller, `Main.runLoop`, and every OTHER branch `dispatch` can take already
    * called `sys.exit` directly, long before this function existed. Returning the exit code here
    * instead, and leaving the final `sys.exit` to `Main.litterBoxLoop`, was considered and rejected
    * for this issue: `dispatch` has no single trailing expression to return from today, only exits
    * scattered across a `match` and, inside `runLoop`, across a startup preflight that already calls
    * `die`/`die50` at half a dozen points; threading a return value through all of that is a reshape
    * of `Main` as a whole, not a single line change, and this issue does not pay for it. `lb`'s own
    * exit code and the point at which it exits are unchanged here; this is a documentation fix only.
    */
  def run(graph: LoopGraph, args: Seq[String]): Unit =
    Main.dispatch(graph, args)
