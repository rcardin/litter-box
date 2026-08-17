package in.rcard.litterbox

/** What a consumer outside this package can hand to [[LitterBox.run]]: the shape of a run, without
  * the capabilities a real tick needs to walk it (issue #43, closing the minimum of a gap two other
  * places already name in so many words: `Machine.shippedWorkflow`'s own doc and `Runner.Ledger`'s
  * own doc both call out "a public entry point that owns its own `Ledger` and exposes something
  * equivalent to `shippedWorkflow` outside this package" as left to #41/#43).
  *
  * `sealed`, on purpose: a consumer can PASS a `LoopGraph` but cannot WRITE one, because `sealed`
  * restricts extending this trait to code in the same file, never merely satisfying its members from
  * elsewhere. That was true when `LitterBox.shipped` was this trait's only inhabitant, and RFC #26
  * decision 5/8 (issue #26) is what it still costs nothing to keep true now that a consumer can
  * author their own graph too: [[LitterBox.graph]] below is a FACTORY, a `def` that builds and
  * returns a `new LoopGraph` from inside this same file, never a second place outside it that gets to
  * write `extends LoopGraph`. Widening the trait's own surface, the limit this scaladoc used to
  * describe as left for a later issue, turned out not to be the only door: a smart constructor opens
  * the same capability, authoring a graph, without spending the seal at all. `workflow`'s own return
  * type used to close the other half of the door by naming `Machine.ShippedStart`, itself
  * `private[litterbox]`, unreachable from outside this package; that trick cannot survive a second,
  * differently shaped inhabitant, since `Machine.ShippedStart` is exactly the type the SHIPPED graph
  * uses and no other graph should have to. The abstract member `type Start` below is what replaces
  * it: each inhabitant of this trait fixes `Start` to its own input type, `LitterBox.shipped` to
  * `Machine.ShippedStart` still, [[LitterBox.graph]]'s own inhabitant to whatever `I` its caller
  * chose, and `LoopGraph` itself stays non-generic, so `Main.dispatch`/`Main.runLoop`/
  * `Machine.runOnce` can keep passing one around without ever naming its own particular `Start`.
  * Naming `Faulting` or `Runner.Ledger` in a signature is NOT what keeps a foreign implementation
  * out, and saying so would overclaim: `Faulting` is a transparent alias for the public
  * `scala.util.boundary.Label[LoopExit]`, so a foreign class can write that dealiased type in
  * `workflow`'s parameter list and type-check; `Runner.Ledger` is a public type with only its
  * constructor marked `private[litterbox]` (`Runner`'s own doc), so naming it in a parameter position
  * needs no construction at all. The seal plus every member here being `private[litterbox]` are what
  * actually keep this shut; `test/ScaffoldedLoopBoundarySpec.scala` pins the seal from a genuinely
  * foreign package.
  *
  * Say plainly what this still does NOT buy, rather than let a reader infer a stronger claim than the
  * one actually made here: a consumer graph built through [[LitterBox.graph]] owns its own `Node`s,
  * `Workflow` and `Shape`, but the runner mechanics underneath, budget accounting (`Runner.Ledger`'s
  * constructor stays `private[litterbox]`), timeout enforcement (`Runner.step`) and the fault channel
  * (`Fault`'s constructor stays `private[litterbox]`) stay exactly as closed to that consumer as they
  * always were. RFC #26 decision 9 draws that line on purpose: a consumer declares a dispatch budget
  * as a plain `Int`, computed as a function of `Config`, and the runner is still the only thing that
  * ever turns that number into a `Ledger` or spends against one.
  *
  * "Owns budget accounting" is a claim about WHO holds the counter, not a claim about how tightly that
  * counter bounds a node's own spend, and the two should not be read as the same promise (issue #43):
  * `Runner.Ledger.canAfford` (`Kit.scala`) is checked once, before a node's
  * own `probe`/`run` starts, never again while it runs, and `Cost.NoDispatch` is unconditionally
  * affordable regardless of what remains, so a `Cost.NoDispatch` node is never gated by `dispatchBudget`
  * at all, no matter how many times it actually calls `agents.*`, and a `Cost.OneDispatch` node that
  * dispatches more than once inside one call is charged once per real dispatch (`chargeDispatch`,
  * `Kit.scala`) but not interrupted mid-node once the ledger reaches zero, only refused at the START of
  * whichever node runs next. `dispatchBudget`, precisely, bounds how many `Cost.OneDispatch` nodes may
  * START, not how many dispatches a graph's own nodes can make in total. `Runner.Ledger`'s own doc
  * (`Kit.scala`) has the mechanism; this is named here as a residual, in the same spirit as the
  * `inline$graphImpl` residual `graph`'s own doc (below) names, because a consumer author reading only
  * this trait's doc, never `Runner.Ledger`'s, should not have to independently discover it.
  */
sealed trait LoopGraph:
  /** The input type this graph's own walk begins from. Abstract, so `LoopGraph` itself stays
    * non-generic: `Main.dispatch`/`Main.runDriver`/`Machine.runOnce` all pass a `LoopGraph` around
    * without ever needing to name its start type, reading `graph.workflow`/`graph.begin` off the
    * path-dependent `graph.Start` the same way regardless of which inhabitant `graph` happens to be.
    * `LitterBox.shipped` fixes this to `Machine.ShippedStart`; [[LitterBox.graph]]'s own inhabitant
    * fixes it to whatever `I` its caller chose. `private[litterbox]` (issue #43 review): the trait doc
    * above claims "the seal plus every member here being `private[litterbox]`" is what keeps this
    * shut, and a bare `type Start` with no modifier was, at one point, the one member that did not
    * actually carry one, confirmed by compiling `val s: LitterBox.shipped.Start = ???` clean from
    * `package com.example.consumer`. Not exploitable on its own, `Start` is abstract, `LitterBox
    * .shipped` is ascribed `: LoopGraph`, and `begin` stays `private[litterbox]`, so nothing nameable
    * actually flows through a bare `Start` reference, but a scaladoc whose entire argument is "the
    * guarantee rests on exactly these modifiers" owes an accurate list of them.
    *
    * TRAP for a future editor: marking THIS abstract declaration `private[litterbox]` is not, on its
    * own, the whole fix. Scala permits a concrete OVERRIDE to WIDEN an inherited member's access back
    * to public even when the abstract member it implements is narrower, so `LitterBox.shipped`'s own
    * `type Start = Machine.ShippedStart` and `LitterBox.graph`'s own `type Start = I` (both below) each
    * need the identical modifier written explicitly on THEM too, confirmed by narrowing only the
    * abstract declaration and finding `val s: LitterBox.shipped.Start = ???` still compiled clean
    * against that tree, both anonymous `LoopGraph` class bodies having carried a bare
    * `type Start = ...` of their own. Both now write `private[litterbox] type Start = ...` explicitly;
    * `ConsumerGraphSpec` pins that this closes it for both inhabitants, not merely for the abstract
    * member in isolation.
    */
  private[litterbox] type Start

  private[litterbox] def workflow(
      cfg: Config,
      caps: Caps,
      faulting: Faulting,
      ledger: Runner.Ledger
  ): Workflow[Start]

  private[litterbox] def shape(cfg: Config): Shape

  /** Everything that has to happen before this tick's real `Ledger` can even exist: compute the
    * walk's own start input, and declare how many dispatches this tick may spend. `Machine.runOnce`
    * calls this once per tick, before it ever builds a real `Ledger`, and reads the number back to
    * build one (RFC #26 decision 9): the runner is the only thing that ever sees a `Ledger` itself,
    * so this returns the NUMBER a graph wants to spend, never the counter that would let a graph
    * spend it directly. `NodeOutcome`, the same result type an ordinary `Node.run` returns, rather
    * than a bespoke result type of its own: a `Done` continues the tick with the started input and
    * budget; a `Stopped` ends the tick right here, with no walk of `workflow` at all, exactly the way
    * `LitterBox.shipped.begin`'s own `Pick` step can end a tick before `Implement` ever runs.
    */
  private[litterbox] def begin(
      n: Int,
      cur: Machine.Cursor,
      cfg: Config,
      caps: Caps,
      faulting: Faulting
  ): NodeOutcome[LoopGraph.Started[Start]]

/** Companion to [[LoopGraph]]: holds nothing but the carrier `begin` hands back, kept in its own
  * object rather than nested inside the trait so a `LoopGraph.Started[...]` reads as a type any
  * inhabitant's `begin` can name without also inheriting it as a member.
  */
object LoopGraph:
  /** What `begin` concluded on a tick that is going to run: the walk's own start input, and the
    * dispatch budget `Machine.runOnce` builds the tick's real `Ledger` from. `private[litterbox]`,
    * matching every other member `begin` touches: a consumer's own `startInput`/`dispatchBudget`
    * functions (`LitterBox.graph`) produce the VALUES this carries, but never see this wrapper type
    * itself, since only `Machine.runOnce`, inside this package, ever pattern matches on it.
    */
  private[litterbox] final case class Started[S](input: S, dispatchBudget: Int)

/** The library's own public front door (issue #43): the coordinate a consumer depends on,
  * [[shipped]], the graph the binary runs, and [[graph]], the public smart constructor a consumer
  * calls to author and run their own graph on the same runner.
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
  val Version: String = "0.2.0"

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

  /** The shipped pipeline (`Machine.shippedWorkflow`/`Machine.shippedShape`), the one `LoopGraph`
    * `lb` itself walks by default. Delegates rather than reimplements: `workflow`/`shape` call the
    * exact same functions `Machine.runOnce` always walked, not a second copy of them built for the
    * public surface, so a consumer running `LitterBox.shipped` runs the identical pipeline `lb`
    * itself runs, by construction rather than by two definitions kept in sync by hand.
    */
  val shipped: LoopGraph = new LoopGraph:
    private[litterbox] type Start = Machine.ShippedStart

    private[litterbox] def workflow(
        cfg: Config,
        caps: Caps,
        faulting: Faulting,
        ledger: Runner.Ledger
    ): Workflow[Machine.ShippedStart] =
      Machine.shippedWorkflow(cfg, caps, faulting, ledger)

    private[litterbox] def shape(cfg: Config): Shape =
      Machine.shippedShape(cfg)

    /** The `Pick` step and the resume-aware `Ledger` seed, moved here VERBATIM from
      * `Machine.runOnce` (issue #43): same calls, same comments, same order, so this reshape cannot
      * move a golden log line, and so the F4 review reasoning and the `REPAIR_BUDGET=0` scenario
      * constraint that justify the seed formula travel with the code they justify rather than being
      * left behind as a comment over a call site that no longer makes the call. `LoopGraph.begin`'s
      * own doc has the general shape every inhabitant's `begin` fills in; this is the shipped one.
      */
    private[litterbox] def begin(
        n: Int,
        cur: Machine.Cursor,
        cfg: Config,
        caps: Caps,
        faulting: Faulting
    ): NodeOutcome[LoopGraph.Started[Machine.ShippedStart]] =
      // `pickAndSetup`'s own `using` clause carries no `AgentDispatch` at all, so nothing inside it
      // can call `agents.*` regardless of what `Cost` `Pick` declares or what `Ledger` it runs under;
      // that signature, not `Cost.NoDispatch`, is what actually enforces "spends nothing" here, since
      // `Cost` alone only gates whether `Runner.step` lets a node START (`Cost`'s own doc), never
      // what it can spend once running. A throwaway `Ledger(0)` is enough to satisfy `Runner.step`'s
      // own signature here. The REAL, shared `Ledger` cannot be built before this call returns (issue
      // #34 review finding F4): whether the tick is a resume is `Pick`'s own OUTPUT
      // (`resumeAuthors`), not known any earlier, and the seed below needs it.
      Runner.step(Machine.Pick, Machine.PickInput(n, cur))(using caps, faulting, Runner.Ledger(0)) match
        case NodeOutcome.Stopped(exit) => NodeOutcome.Stopped(exit)
        case NodeOutcome.Done(setup) =>
          // The shared dispatch `Ledger` every `Cost.OneDispatch` node from here on (`Implement`,
          // `Repair`) draws from, seeded resume-aware (issue #34 review finding F4) now that
          // `setup.resumeAuthors` is known:
          //
          //   - an ORDINARY tick runs `Implement` (one dispatch) and then up to `cfg.repairBudget`
          //     `Repair` rounds, so the seed is `cfg.repairBudget + 1`;
          //   - a RESUMED tick (`resumeAuthors.isDefined`) skips `Implement` entirely and dispatches
          //     its own first FIX straight through `Repair` (`shippedWorkflow`'s own `resumeAuthors`
          //     branch), so the seed is `cfg.repairBudget` with no `Implement`-sized headroom added
          //     on top.
          //
          // Every `Implement`/`Repair` dispatch this tick can make charges this one `Ledger`, through
          // `Runner.step`'s own decorator, so `remainingDispatches` cannot drift from what
          // `attemptRepairNext`'s own local reasoning (`shippedWorkflow`) tracks by hand for THOSE
          // two nodes. `Review` (issue #35) is also a node and also runs through `Runner.step`, but
          // NOT against this `Ledger` (D1 of issue #37's own design: `shippedWorkflow`'s own doc has
          // the full reasoning), so a review dispatch still spends nothing against THIS `Ledger`.
          // That split is what keeps `ledgerSeed` here exactly the FIX/IMPL slice of the tick's real
          // dispatch count, and what keeps every `self-repair: budget now N` golden line true
          // regardless of how many review rounds preceded it.
          //
          // `math.max(0, cfg.repairBudget)`, not a bare `cfg.repairBudget` (issue #33 review round 2
          // finding A, still relevant here): `REPAIR_BUDGET` reaches here through a bare
          // `toIntOption` (`Main.scala`) or a bare `conf.getInt` (`Settings.scala`), neither of which
          // rejects a negative value, and this codebase already treats negatives as reachable (the
          // `<= 0`, not `== 0`, guard at `cfg.repairBudget <= 0` in `pickAndSetup`). The `+ 1` on the
          // ordinary path keeps `Implement` affordable no matter how small `cfg.repairBudget` is
          // configured, including `REPAIR_BUDGET=0`, which must still dispatch the initial IMPL
          // rather than park before ever running it (the `ScenarioSpec` case this ledger is required
          // to keep green); a resumed tick never runs `Implement`, so it needs no matching `+ 1`, and
          // `pickAndSetup` only ever sets `resumeAuthors` when `cfg.repairBudget > 0` (that guard's
          // own doc), so this seed is guaranteed positive on that path without a floor of its own.
          val ledgerSeed =
            if setup.resumeAuthors.isDefined then math.max(0, cfg.repairBudget)
            else math.max(0, cfg.repairBudget) + 1
          NodeOutcome.Done(
            LoopGraph.Started(
              Machine.ShippedStart(
                n = n,
                cur = cur,
                issue = setup.issue,
                bodyFile = setup.bodyFile,
                workerPromptFile = setup.workerPromptFile,
                isClass1 = setup.isClass1,
                branch = setup.branch,
                resumeAuthors = setup.resumeAuthors,
                carriesParked = setup.carriesParked,
                resumedFromInProgress = setup.resumedFromInProgress
              ),
              ledgerSeed
            )
          )

  /** The smart constructor RFC #26 decisions 5 and 8 ask for: builds a [[LoopGraph]] from a
    * consumer's own `Workflow`, without widening anything this file already keeps closed
    * (`LoopGraph`'s own doc has the full reasoning for why a factory, not a subclass, is the door).
    *
    * `inline`, and `shape` an `inline` parameter, for exactly one reason: `checkedShapeStrict`
    * (`Kit.scala`) is itself an `inline def` whose macro reads the CALLER's own literal `Shape`
    * expression, not a value it evaluates at runtime (`checkedShapeStrict`'s own doc, and
    * `KitMacro.checkShapeImpl`'s own doc, `KitMacro.scala`, have the reason a macro can only ever read
    * a literal this way). Splicing `checkedShapeStrict(shape)` inside a non-inline `graph` would have
    * the macro read `shape` as this function's own bare PARAMETER reference, a local variable, never
    * the literal `Shape(...)` expression the caller actually wrote; every call would then hit
    * `checkedShapeStrict`'s own hard error for a non-literal shape (below), even a caller who genuinely
    * wrote one, through no fault of their own. Making `graph` itself `inline`, with `shape` marked
    * `inline` too, is what lets the macro see straight through this function to the caller's own
    * literal, the same way `checkedShape` sees straight through an ordinary local
    * `val shape = checkedShape(Shape(...))` today. The body below is kept to exactly the one splice
    * that needs to be inline, `checkedShapeStrict(shape)`, calling a private, non-inline `graphImpl`
    * for everything else, so inlining this function does not also inline the whole `LoopGraph`
    * construction into every call site.
    *
    * `checkedShapeStrict`, not `checkedShape` (issue #43 review): `checkedShape`'s own lenient
    * fallback, silently returning `shape` unchanged the moment it cannot read a literal, left a node
    * whose input type extends `RequiresReviewInput` while its own `guard` argument stayed the default
    * `Guard.Open`, exactly the shape every shipped `OpenPr`/`Merge`-style node takes, with no backstop
    * at all: not at compile time (the lenient macro fell back silently) and, at the time, not at
    * runtime either (`Runner.validate` read `guard`, never the marker, so it found nothing wrong with
    * that graph). That specific absence of a backstop is closed now (`Kit.scala`'s own doc on
    * `markerRequiresReview` and `Node.apply` has the mechanism): `Node.apply` derives `Guard.RequiresReview` on the real
    * constructed `Node` whenever its input type carries the marker, so `Runner.validate` correctly
    * flags that identical graph too. `checkedShapeStrict` still refuses to compile a non-literal
    * `shape` here regardless (`checkedShapeStrict`'s own doc, `Kit.scala`, has the two reasons that
    * survive: catching a violation at compile time beats catching the same one at startup, and two
    * alias residuals exist that no widening of this walk closes, for which `Runner.validate` is the
    * only check that ever runs at all). This is RFC #26 decision 16's own recorded consequence, "graphs
    * cannot be assembled dynamically", made to actually bite at this one entry point rather than merely
    * being written down: a consumer who cannot write a literal `Shape` at this call site cannot use
    * `LitterBox.graph` at all, and has to compose `Runner.run` directly instead, outside the compile
    * time half of this guarantee, though still inside `Runner.validate`'s own. `checkedShape` and
    * `checkedShapeStrict` remain two separate functions, never one flag-carrying one;
    * `checkedShapeStrict`'s own doc (`Kit.scala`) has the fuller reasoning for why that split is the
    * right one.
    *
    * The set of literal forms this walk can read (a `val` member of the enclosing class referenced
    * bare, `List.empty`, out-of-order named arguments, a local `val`-bound `Transition`, ...) is wider
    * than earlier versions of this macro accepted, and `checkedShape` and `checkedShapeStrict` share
    * that same parsing walk (`KitMacro.checkShapeImpl`'s own doc, and the docs on
    * `stablePathKey`/`literalListElements`/`companionApplyArgs` it points to, have the full table and
    * the reasoning for each form): a `Shape` whose only unreadable piece is one of those now gets fully
    * parsed under `checkedShape` too, not only under `checkedShapeStrict`, so a shape that used to
    * compile clean under lenient `checkedShape` can newly fail to compile if what this walk can now
    * actually read turns out to have a genuine review-reachability violation. The direction is safe,
    * strictly more checking, never less; it is still a behaviour change to a published, public
    * `inline def`, and `README.md`'s version policy section names it as exactly the kind of change the
    * `0.x` no-stability-promise line exists for.
    *
    * `dispatchBudget: Config => Int`, not a `Runner.Ledger` or a bare `Int` (RFC #26 decision 9,
    * `test/ConsumerBoundarySpec.scala:152-157`, the test that pins the `Ledger` constructor closed):
    * budget ownership belongs to the runner, never to a graph, so a consumer cannot hand this
    * function anything that already IS a `Ledger`, only a plain number the runner turns into one.
    * Declared as a function of `Config` so it CAN read `.litter-box/config.conf` at runtime, the same
    * shape `Machine.runOnce`'s own `ledgerSeed` computation has always had (RFC #26 decision 17: a
    * run's BEHAVIOUR is a fact of `config.conf`, never a second fact hardcoded into the graph that
    * describes its shape), never a promise that the function has to read `cfg` at all: nothing rejects
    * `dispatchBudget = _ => 42`, a constant is a legal, if unusual, instance of `Config => Int`; the
    * `Config =>` shape is a convention that keeps the door open to reading config, not a constraint
    * that forbids one who does not need to.
    *
    * This is the one place RFC #26 decision 17's own promise, that nothing is expressible in both
    * `config.conf` and `loop.scala`, gets a named exception instead of a silent one: the intended form
    * of `dispatchBudget` reads the knob out of `config.conf`, keeping the layered config the single
    * source of the number (`cfg => cfg.repairBudget + 1`, README.md's own sketch of this call, is
    * exactly that shape); a constant such as `dispatchBudget = _ => 42` is legal too, and writing one
    * is the one way a graph author can still put a budget number directly into `loop.scala`, which
    * decision 17 asks them not to do, not one that makes it impossible. Recorded once, here, rather
    * than as five separate paraphrases: `ARCHITECTURE.md`, README.md's own "Write your own loop"
    * section and the scaffolded `loop.scala` comment (`resources/scaffold/loop.scala.txt`) all point
    * back to this paragraph instead of restating it.
    *
    * The number returned bounds how many `Cost.OneDispatch` nodes may START, not the total number of
    * dispatches a graph's own nodes can make: `LoopGraph`'s own trait doc (above) has the precise
    * statement of that residual, and `Runner.Ledger`'s own doc (`Kit.scala`) has the mechanism
    * (`canAfford`/`chargeDispatch`).
    *
    * `shape` is a separate parameter from `workflow.shape`, never read off the `Workflow` value
    * itself, because `workflow.shape` is a RUNTIME field, not a literal the macro splicing this
    * function's own `inline shape` parameter could ever see (the same reason `checkedShape` itself
    * takes `shape` as a parameter rather than reading it off some already-built `Workflow`). Unlike
    * `checkedShape`'s own callers, a consumer whose `shape` argument here is not, in fact, a literal
    * `Shape(entry = ..., transitions = ...)` expression written directly at this call site DOES get a
    * compile failure for that alone (BLOCKER 1 above): `checkedShapeStrict` aborts rather than falling
    * back to whatever `Runner.validate(graph.shape(cfg))` would otherwise find at startup. `Node.apply`'s
    * own doc (`Kit.scala`) has the fix that makes `Runner.validate` a reliable backstop for a
    * marker-only-guarded node, by making the real constructed `Node`'s own `guard` field agree with the
    * marker rather than leaving the two free to diverge. `Runner.validate` still runs every tick inside
    * `Machine.runOnce`, unconditionally, and is the genuine backstop for two things: that marker/`guard`
    * agreement, and the two alias residuals `checkedShapeStrict`'s own doc (`Kit.scala`) names, which no
    * widening of this macro's own walk can close, because they are facts about which SOURCE REFERENCES
    * name the same node, not facts about a hand written field the macro reads differently. It remains
    * true that `Runner.validate` is not this parameter's fallback for an unreadable literal, only ever a
    * second, later check behind the compile time one: erroring here on a non-literal `shape` stays
    * mandatory regardless of how reliable the runtime check underneath it becomes, since compile time is
    * strictly earlier for every graph this macro CAN read.
    *
    * "Not a literal at all" is not the only way `checkedShapeStrict` can abort on `shape`, and the two
    * are DELIBERATELY worded differently rather than sharing one message: `shape` can also be a genuine
    * literal `Shape(entry = ..., transitions = ...)` written directly here whose walk still cannot make
    * sense of ONE piece inside it, and that failure is reported with different words, naming the exact
    * unreadable term, so a consumer who already wrote a literal never receives advice they have already
    * followed. `KitMacro.checkShapeImpl`'s own doc has the mechanism (`ParseFailure`), the reasoning for
    * keeping the two messages apart, and the full table of forms this walk can and cannot read.
    *
    * The identity KEY this walk deduplicates node references on is computed by ONE canonical function
    * (`KitMacro.scala`'s own `canonical`, `stablePathKey`'s own doc has the mechanism and the reasoning
    * for why a single scheme is needed), and a reconciliation pass (`checkReconciled`, `KitMacro.scala`)
    * runs before the reachability walk and hard-errors, naming both references, the instant two of them
    * canonicalise to one key while disagreeing on whether the node they name needs review, rather than
    * letting the walk's own visited set silently keep whichever reference it happened to dequeue first.
    *
    * One residue is left standing on purpose, not an oversight: a node built by a `def`, including
    * `Machine.shippedShape`'s own `A(cfg)` idiom (`src/Machine.scala`, config read at node CONSTRUCTION
    * time rather than inside the node's own body), cannot be identified by this walk and never will be,
    * because this macro reasons about TREES written at the call site, never about a VALUE, and a `def`
    * call's own result is a value this walk would have to evaluate to know, which a macro, by
    * construction, cannot do (`KitMacro.checkShapeImpl`'s own doc has the fuller reasoning). This is not
    * a narrower version of a gap this branch meant to close: RFC #26 decision 16 recorded, before this
    * factory was written at all, that "graphs cannot be assembled dynamically" through it, and
    * `Machine.shippedShape`'s own config-parameterised idiom is the sharpest instance of exactly that
    * decision, `LitterBox.graph` genuinely cannot express the shipped graph's own node-construction
    * style, and is not meant to. A consumer who needs a node's behaviour to vary with `Config` reads
    * that `Config` inside the node's own `probe`/`run` body instead, through the given `Config` already
    * derivable from the ambient `Caps` every `probe`/`run` body receives (`Caps.scala`'s own `given
    * (using c: Caps): Config = c.cfg`), behind a single, plain, top-level `val` standing for the node
    * itself, never a `def` that builds a differently configured `Node` value per call.
    *
    * `graphImpl`'s own `wf.copy(shape = checked)` (below) OVERWRITES `workflow.shape` with this
    * parameter, UNCONDITIONALLY and SILENTLY, regardless of what `workflow.shape` was set to; the
    * comment at that call site has the full reasoning for why a silent overwrite, stated plainly in the
    * docs rather than guarded by a `require`, is the right trade here.
    *
    * `startInput: Int => Fault ?=> I` (narrowed from `(Caps, Fault) ?=> I`, issue #43 review) receives
    * the tick number and a `Fault`, wired to the same log/notify sinks the rest of this tick already
    * uses, but no `Caps`:
    * `begin` runs before this tick's real `Ledger` exists (`LoopGraph.begin`'s own doc), so a
    * `startInput` that COULD summon `Caps` could call `agents.*` on the LIVE `AgentDispatch` from
    * there, genuinely dispatching agents, entirely outside `Runner.step`'s own `charging` decorator,
    * its `canAfford` check, its `Timeout` enforcement and its `Trust.Reviewed` runtime check.
    * Reproduced and confirmed against this working tree before this fix: a `startInput` declaring
    * `dispatchBudget = _ => 0` and then making a thousand real dispatches through
    * `agents.worker`/`agents.review` built without error, minting a genuine, unforged `Judged` value
    * with no ledger, timeout or shape check ever having seen it, one that a node's own `run` could then
    * hand straight back to satisfy `Runner.step`'s `Trust.Reviewed` check as if it had been earned
    * honestly. `LitterBox.shipped.begin`'s own comment on `pickAndSetup`'s signature already makes this
    * exact argument for the SHIPPED graph, that carrying no `AgentDispatch` in scope, not merely a
    * `Cost` declaration, is what actually enforces "spends nothing here"; narrowing `startInput`
    * applies the identical reasoning to a consumer's own graph instead of leaving it the one place this
    * library's own argument did not hold for itself. A `fault.raise` inside `startInput` still aborts
    * the tick to rc 50 exactly as a fault inside any node does, since `Fault` alone is enough for that;
    * a consumer whose first step genuinely needs a capability call puts that work in a node instead.
    */
  inline def graph[I](
      workflow: Workflow[I],
      inline shape: Shape,
      dispatchBudget: Config => Int,
      startInput: Int => Fault ?=> I
  ): LoopGraph =
    graphImpl(workflow, checkedShapeStrict(shape), dispatchBudget, startInput)

  /** The non-inline half of [[graph]]: everything past the one splice that has to
    * stay inline, `checkedShapeStrict(shape)` above, lives here instead, so calling `LitterBox.graph`
    * does not also inline a whole `LoopGraph` construction, `Fault` construction and `NodeOutcome`
    * match into every call site the way inlining this entire body would. `wf`, not `workflow`, names
    * the first parameter: the anonymous `LoopGraph` below declares its OWN member also named
    * `workflow` (matching every other inhabitant of this trait, `LitterBox.shipped` included), and
    * that member would otherwise shadow this parameter inside its own body, the same trap a class
    * member sharing a name with a captured constructor parameter always sets.
    *
    * Making `graph` `inline` while this stays `private` makes Scala 3 synthesise a public accessor in
    * the compiled bytecode, confirmed with `javap`: a `public final
    * LoopGraph inline$graphImpl(Workflow, Shape, Function1, ...)` method, named after this one with an
    * `inline$` prefix. Scala source cannot name it, `private` still keeps every ordinary Scala caller
    * out, but a Java or Kotlin caller of the same jar can invoke that accessor directly, skipping
    * `checkedShapeStrict` entirely, since the macro only ever runs at the Scala call site `graph`'s
    * own `inline` splices into. This is the same residual `src/Caps.scala:205-208` already names for
    * `Judged.mint`, a Scala COMPILE TIME guarantee only, nothing more, accepted here for the identical
    * reason: defending against a non Scala caller of the same jar is out of scope for this issue.
    *
    * A second, related residual, not merely the same one restated: the anonymous `new LoopGraph` this
    * function returns compiles to its own named class on the JVM,
    * `LitterBox$$anon$2`, and `javap -p` on it shows a PUBLIC constructor and PUBLIC `workflow`/`shape`/
    * `begin` methods, `private[litterbox]` again being a Scala source concept with no JVM access
    * modifier under it. So a Java or Kotlin caller of the same jar has a second way past this
    * guarantee, beside `inline$graphImpl` above: constructing `new LitterBox$$anon$2(wf, shape, ...)`
    * directly, with an unchecked `Shape` of its own choosing, skips `checkedShapeStrict` AND this whole
    * factory, not merely the macro. Same class of residual, same acceptance, for the identical reason:
    * a Scala compile time guarantee is what this issue promises, and defending the same jar's own
    * bytecode against a non-Scala caller who reads `javap` output and writes to it directly is out of
    * scope for it.
    *
    * A third, unrelated residual worth naming beside these two rather than leaving implicit:
    * `startInput`'s own signature, `Int => Fault ?=> I`, keeps `Caps` out of `startInput`'s own SCOPE,
    * so nothing inside it can SUMMON one, but that is a claim about what this function can ask for, not
    * about what it can be handed. A node body from a PREVIOUS tick can stash a `Caps` it summoned
    * honestly (closing over a mutable `var` across ticks) and a LATER tick's `startInput`, reading that
    * stashed value back, can call a real capability through it, running outside the `Ledger`, `Timeout`
    * and shape checks that SAME tick's own walk would otherwise apply to a capability call. Materially
    * narrower than the gap `startInput`'s own narrowed signature (above) closed, since it needs a node
    * to deliberately stash a `Caps` across a tick boundary first, and every value obtained this way was
    * minted by a genuine dispatch, never a forged one, so this is not a reopening of that gap, only a
    * residual worth naming: `README.md`'s "Write your own loop" section states it in the same terms for
    * a reader who never opens this file.
    *
    * A fourth and fifth residual, both about `checkedShapeStrict`'s own node-identity walk rather than
    * this factory itself: an INSTANCE-QUALIFIED node reference (`holder.node` for an ordinary,
    * non-singleton `holder`) is unreadable by that walk, uniformly and on purpose, because it cannot
    * tell two distinct instances of the same class apart from one instance aliased twice
    * (`stablePathKey`'s own doc, `KitMacro.scala`, has the full reasoning). And one `Node` VALUE bound
    * under two different top-level `val`s (`val a = mkNode(); val b = a`) is unreadable for a related
    * but distinct reason: this walk keys each `val` on its own declared symbol, so `a` and `b` key
    * differently even though they are the identical `Node` at runtime (`checkReconciled`'s own doc,
    * `KitMacro.scala`, has the mechanism). Neither is closable by keying more cleverly; both are
    * survivable, not merely accepted, because `Runner.validate` (unconditional, every tick,
    * `Machine.runOnce`) reads the graph's real, already-resolved `Node` values, where an aliased or
    * instance-qualified reference is not a fact this walk has to reconstruct at all, only one value read
    * however many times source code happened to name it.
    */
  private def graphImpl[I](
      wf: Workflow[I],
      checked: Shape,
      dispatchBudget: Config => Int,
      startInput: Int => Fault ?=> I
  ): LoopGraph =
    // `wf.copy(shape = checked)` below OVERWRITES `workflow.shape` with THIS function's own `checked`
    // argument, unconditionally and with no diagnostic. A `require` guarding this, rejecting a
    // `workflow` whose own `shape` field was already non-default, was tried and rejected (issue #43
    // review): `require` throws a bare `IllegalArgumentException` straight out of this factory, on the
    // consumer's own thread, while `workflow` (an ordinary, non-`inline` parameter) is still being
    // EVALUATED, which is before `LitterBox.run` is ever entered, before `Main.dispatch`'s own
    // preflight runs, before `Config` is loaded, before `.litter-box/logs/` even exists. Concretely:
    // no `status.jsonl` event, no log line, no notify seam fired, and a JVM default exit code of 1,
    // never this codebase's own rc 50, so an operator's rc-50 alerting sees nothing at all; the same
    // throw fires for `lb --help` and `lb --dry-run` too, since the `graph` argument to `LitterBox.run`
    // is evaluated regardless of what `args` says, and `LitterBox.run` is not even reached yet. Every
    // OTHER failure in this codebase routes through the one fault channel instead (`Fault.raise` ->
    // `Machine.infraFault` -> rc 50 with a log line and a notify, `Kit.scala`'s own doc on `Fault` has
    // the mechanism), and `require` here bypassed it entirely. Rejecting the non-default `wf.shape`
    // cleanly turned out not to be trivial: there is no COMPILE TIME position for the check, since
    // `workflow` is an ordinary parameter, not the `inline shape` parameter the macro splices into, and
    // every RUNTIME position for it sits on the wrong side of the one fault channel, before a `Caps`,
    // a `Fault` or even a `Config` exists to raise one through. So this stays a silent overwrite, with
    // the overwrite stated plainly instead: `LitterBox.graph`'s own scaladoc above, and
    // `Workflow.shape`'s own doc (`Kit.scala`), both say outright that `workflow.shape` is discarded
    // here in favour of this factory's own `shape` parameter, so a reader who wonders what happened to
    // a `Shape` they set on their own `Workflow` finds the answer at either place they might have gone
    // looking, rather than silence.
    new LoopGraph:
      private[litterbox] type Start = I

      private[litterbox] def workflow(
          cfg: Config,
          caps: Caps,
          faulting: Faulting,
          ledger: Runner.Ledger
      ): Workflow[I] = wf.copy(shape = checked)

      private[litterbox] def shape(cfg: Config): Shape = checked

      /** `Fault(faulting, caps.logger, caps.notifier)` is the same construction `Runner.step`
        * (`Kit.scala`) performs for every node's own `run`/`probe`; this factory is library code, in
        * the same file `Fault`'s constructor is declared in, so it can make that same call, and it
        * makes it here so `startInput` receives a genuine `Fault`, not a node body's own, wired to the
        * same log/notify sinks the rest of this tick already uses. `caps` is used ONLY to build that
        * `Fault` (`caps.logger`, `caps.notifier`), never passed to `startInput` itself (`graph`'s own
        * doc above has the full reasoning for why `startInput` no longer receives a `Caps` at all).
        * `math.max(0, ...)` mirrors the floor `Machine.runOnce`'s
        * own `ledgerSeed` computation applies (`src/Machine.scala`), so a hostile or merely careless
        * `dispatchBudget` cannot hand `Runner.Ledger` a negative seed.
        */
      private[litterbox] def begin(
          n: Int,
          cur: Machine.Cursor,
          cfg: Config,
          caps: Caps,
          faulting: Faulting
      ): NodeOutcome[LoopGraph.Started[I]] =
        val fault = Fault(faulting, caps.logger, caps.notifier)
        NodeOutcome.Done(
          LoopGraph.Started(startInput(n)(using fault), math.max(0, dispatchBudget(cfg)))
        )

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
