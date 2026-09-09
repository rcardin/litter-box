# The minimal kit: fix the pain, keep the graph

Status: alternative design, written 2026-09-07 against the incumbent Flow proposal
(`2026-09-07-litter-box-dsl-design.md`). Not yet implemented.

## Why this alternative exists

The incumbent diagnoses three problems with the kit and prescribes a rewrite: a new `Flow[I, O]`
combinator tree, Scala's experimental capture checking, `-Xfatal-warnings` in every consumer's
`loop.scala`, and the deletion of `Plan`, `Edge`, `Transition`, `Shape`, `Next`, `KitMacro` and
`Role`. Its migration plan has nine steps and rewrites the kit, the shipped pipeline, the testkit,
all twenty eight golden logs and both authoring documents.

This design starts from the opposite premise: measure each complaint against the code as it stands,
find the smallest change that removes the pain, and refuse every change whose justification is a
need the repository does not yet have. The result is three targeted changes, none of them
experimental, that between them delete the macro and its eleven paragraphs of authoring rules, put a
real runtime capability restriction where the incumbent puts an erased compile time one, and leave
the graph model, the shipped pipeline, the testkit, the goldens and every consumer facing name
exactly where they are.

The sharpest claim this document makes, argued in full below: under this project's own threat model,
a capability physically withheld at runtime is not a cheaper substitute for a capture checked proof.
It is the stronger guarantee, because capture sets are erased at the exact moment the adversary
starts existing, and the incumbent's own risk section concedes both the erasure and the deletable
flags it rests on.

## The three complaints, remeasured

### Payload threading by case class per edge

The complaint: an edge sees nothing but the output of the node it leaves, so every fact a later step
needs is threaded by hand through purpose built case classes.

Measured against the two worked examples, the tax is smaller than the complaint implies. The current
consumer example (`test/ReviewFixLoopExample.scala`) declares six carrier types: `Work`,
`ReviewRound`, `Reviewed`, `FixRound`, `PrRequest`, `PrOpened`. The incumbent's own worked example,
with `withInput` and `switch` at its disposal, still declares four: `Work`, `Attempt`, `Round`,
`Ask`, plus the `Answer` enum. The rewrite buys back roughly two case classes per graph, because
most of these types are not plumbing at all. `FixRound` carries the review guarantee marker.
`PrOpened.needsHuman` is the fact the exit edge routes on. `ReviewRound.round` is the cycle bound.
The kit's own documentation calls this a feature rather than a cost, and it is right to: a counter
that rides in a value is visible to the graph, to the validator and to a test, where a counter
captured in a closure is visible to nothing (`Edge`'s doc in `src/Kit.scala`, the paragraph
beginning "Whatever a later node needs to know").

Alternatives were considered and declined, each for a concrete reason:

* **A graph level context type.** `Plan[I, Ctx]` with every `Edge` receiving the context alongside
  the previous output infects every signature with a second type parameter, the exact widening
  `Next`'s own doc declined for `Workflow` when the graph was first built, and it makes the context
  a bag that every edge can read and no edge declares, which is the closure problem with a type
  ascribed to it.
* **A mutable per tick blackboard on `Caps`.** Worse again: state that travels outside the values is
  state the shape cannot see, the startup validator cannot reason about, and a golden log cannot
  pin. It reintroduces exactly what issue #67 removed.
* **An equivalent of the incumbent's `withInput`.** Genuinely elegant, and the one piece of the Flow
  design this document is sorry to leave behind, but it has no honest translation onto an edge
  table: an edge is a function, not a combinator with an ambient input to pair against.

The minimal fix is therefore documentation, not model change: `docs/custom-loops.md` step 2 already
teaches the pattern in four lines. Nothing ships.

### The macro and its eleven paragraphs

The complaint is accurate. `src/KitMacro.scala` is 1386 lines. The rules it imposes, nodes as top
level `val`s, never `def`s, never inline, never exported, never instance qualified, never two
references canonicalising to one identity, fill step 9 of `docs/custom-loops.md`, roughly ninety
lines of the README's kit section, and a section of `TEST.md` that exists only because bloop caches
stale macro expansions in the dangerous direction, so every macro edit must be verified with
`--server=false` or the verification is worthless evidence.

The question the incumbent never asks is what the macro buys that startup validation does not.
Reading `Kit.scala`, `KitMacro.scala` and `ARCHITECTURE.md` closely, the answer is: almost nothing,
and the repository's own documentation says so in pieces.

* The review reachability walk the macro runs is the same BFS `Runner.validate` runs at startup,
  unconditionally, on every tick, against the graph's real, already resolved `Node` values.
  Since issue #43 round 4 folded the `RequiresReviewInput` marker into `Node.apply`'s own derived
  `guard`, the two checks read one fact and cannot disagree.
* For the two alias families the macro cannot read at all, an instance qualified receiver and one
  value bound under two stable paths, `ARCHITECTURE.md` states plainly that `Runner.validate` "is
  not a backstop for a fact read differently, it is the only check that ever runs against the
  graph's real, already resolved `Node` values". The runtime check is the authoritative one today.
* The macro's one unique catch beyond earliness, the inline `Node(...)` construction that allocates
  a fresh object per writing and therefore dead ends the identity keyed walk, is a fact about the
  `Plan`'s own data, checkable in a few dozen pure lines at startup (below), before any node runs.
* The earliness itself is worth close to zero wall clock for the actual user. A consumer's
  `loop.scala` is compiled by `lb` on every run (`docs/custom-loops.md`, "Before you start"). A
  compile error and a startup rc 50 arrive in the same terminal, in the same invocation, seconds
  apart, both before any capability call. There is no deploy gap for the compile time check to
  guard.

What the macro costs, beyond the lines and the rules: it is the sole reason `LitterBox.graph`
demands a literal `Plan` at the call site, which is the sole reason edge lists cannot be composed
(`commonEdges ++ myEdges` is not on the readable forms list), the sole reason a subgraph cannot be a
helper returning `List[Edge]`, and the sole reason RFC #26 decision 16 had to record "graphs cannot
be assembled dynamically". It is also the reason the shipped pipeline itself cannot use
`LitterBox.graph`: `Machine.shippedShape`'s config parameterised `def` idiom is exactly what a macro
over a literal cannot read, so the shipped graph still hand declares a `Workflow` and a `Shape` that
nothing stops from drifting apart, the drift `ARCHITECTURE.md` spends two paragraphs allowing for.

So the proposal is deletion, with one small startup check added to close the inline node gap. The
incumbent deletes the macro too; the difference is that this design deletes it without also deleting
the five types it reads.

### Every node receives the whole `Caps` bundle

The complaint is accurate and it is the one worth real work. A node that only reads an issue can
push, and nothing says otherwise. The incumbent's answer is capture checking. This design's answer
is in two layers, neither experimental, and the second is argued as strictly stronger under this
project's threat model in the section on proofs below.

**Layer one, compile time, already available today: narrow context parameters.** The codebase's own
internal idiom is the answer to the reader who wants a compile time statement of least privilege:
write the node's body as a call to a function whose `using` clause names only what it needs, the way
`Machine`'s own phases always have (`(using cfg: Config, gh: GitHub, ...)`), and let `Caps.given`
derive each capability from the ambient bundle. A body written this way cannot name `git` because
`git` is not in scope, an ordinary lexical fact no flag can delete and no future Scala version can
destabilise. This costs a documentation section, nothing else.

**Layer two, runtime, the new mechanism: per node grants.** `Node.apply` gains one defaulted
parameter:

```scala
Node(
  name    = "Review",
  cost    = Cost.OneDispatch,
  timeout = Timeout.Unbounded,
  grants  = Set(Grant.Gh, Grant.Git, Grant.Agents, Grant.Fs, Grant.Status),
  probe   = ...,
  run     = ...
)
```

`Grant` is a small enum, one case per withholdable member of `Caps`: `Gh`, `Git`, `Agents`, `Gates`,
`HostGates`, `Fs`, `Status`, `Notify`. `Config`, `Clock` and `Log` are always granted: config is
data, and denying a node its log or its clock only makes failures quieter, the wrong direction under
a threat model whose whole observability contract is the log stream. The default is the full set, so
every existing node, shipped and consumer alike, compiles and behaves byte for byte as it does
today, and no golden moves.

`Runner.step` already builds the `Caps` a node actually sees rather than passing the caller's
through: it swaps `agents` for the `charging` decorator. The grants mechanism is that same move
generalised: for each capability the node was not granted, the copy carries a denying implementation
whose every method raises through the tick's one `Fault` channel, naming the node, the capability
and the method, in the same wording family as `Runner.refusedDispatchMessage`. The mechanics were
verified against Scala 3.8.3 in a scratch model of the real shapes (a `Caps` style case class of
capability traits, deny wrappers, a `copy` per step): the denial raises, the message names the node,
and the narrowed bundle holds no reference back to the live object, so even a cast recovers only the
denier.

Precedent inside the repository, so this is an extension of an existing idiom rather than a new
species of mechanism: the `charging` decorator meters dispatches at the capability; `startInput` was
narrowed to carry no `Caps` at all, with the comment on `LitterBox.shipped.begin` stating outright
that `pickAndSetup` carrying no `AgentDispatch` in its `using` clause, "not `Cost.NoDispatch`, is
what actually enforces spends nothing here"; and the testkit on classpath refusal, the `Ledger`'s
private constructor and the sealed `AgentDispatch` are all runtime and structural controls. The
project already trusts absence over annotation. Grants make that trust per node.

A denial is an infra fault, rc 50, tick abandoned, fail closed, before the refused call touches the
world. The same narrowing the budget refusal doc already records applies here: a node body that
wraps the call in a broad catch can observe the denial and keep running, with the fault line and
notify already fired; what a catch can never recover is the capability itself, because the live
object is simply not reachable from anything the node was handed.

### Concurrency, scatter and gather, subgraph composition

Checked against the repository rather than against ambition:

* **Concurrency between nodes has no present customer.** The `Git` trait's own doc describes the
  working tree as serial, one US at a time; every shipped step's input is the previous step's
  output; and `Timeout.After`'s doc records a settled decision, made in issue #69 and written down
  precisely so nobody reopens it casually, that node bodies write unsynchronised shared state and
  putting them on a second thread was declined. The one place the incumbent's `par` could honestly
  run in the shipped pipeline is nowhere: there are no two independent steps in it.
* **Concurrency inside a node is already supported.** `Runner.Ledger` was made synchronized in
  issue #69 explicitly because "nothing on the public `Node` surface stops a consumer's own
  `run`/`probe` body from fanning its own dispatches out across threads it spawns itself". A future
  multi reviewer vote is a node body over virtual threads today, budget metered per dispatch, with
  no kit change.
* **Scatter and gather is shipped.** `ReviewFixLoopExample.Fix` fans out one fixer dispatch per
  reviewer finding as a fold, each dispatch charged, refusal mid fan out bounded by the ledger. The
  incumbent calls this "invisible to the shape", which is true; what the shape gains by seeing it is
  a rendering nicety, not a safety property, since the budget and the guard already bind it.
* **Subgraph composition is blocked by the macro alone**, and falls out of its deletion for free:
  once `plan` is an ordinary parameter, `List[Edge]` values compose with `++`, helpers can return
  edge groups, and a graph may even be assembled from data, because the checks that matter run at
  startup against the real values regardless of how the `Plan` was put together.

Verdict: build none of it, document what already works, and collect subgraph composition as a free
consequence of deleting the macro.

## The config file question, assessed and declined

Should the pipeline shape leave Scala entirely: a declarative file `lb` loads and validates, node
bodies being shipped built ins or named script hooks?

The evidence says no, on three grounds.

**The bodies are the substance and they cannot cross the boundary.** In the worked consumer graph,
the `Plan` is thirty lines and the node bodies are three hundred. `Setup` alone makes a dozen
capability calls with branching, dry run handling and label flips. A config file expresses none of
that, so the logic moves to script hooks, and a script hook runs outside everything this project
exists to enforce: outside `Caps`, so no scripted test world can drive it; outside the `Ledger`, so
its dispatches are unmetered; outside `PatchGuard`, unless it reimplements the seam the kit made
public precisely so nobody would; and outside `Judged`, which is an unforgeable in process token
that cannot cross a process boundary at all. A pipeline of script hooks is `loop.sh`, the artifact
this entire codebase is the replacement for, and the golden logs are the migration receipts.

**The shape is already data.** RFC #26 decision 5, "users write data, not control flow", was
delivered by issue #67: the `Plan` is a table, the `Shape` the validator walks is derived from it,
and `StageSet` already hands `watch` a reified rendering declaration per run. The reifiable part of
the graph is reified. A config file would re express the same table with the types removed.

**The consumer in evidence is a Scala consumer.** The scaffolded default path never writes a graph
at all: `init` emits a one line `loop.scala` naming `LitterBox.shipped`, and behaviour lives in
`config.conf`, which IS the config file, with decision 17 holding the boundary between the two. The
consumer who authors a graph is one who chose a typed kit with a published testkit, compile checked
examples and `TestWorld.runGraph`; every artifact of the project, the testkit coordinate, the
worked example compiled by the suite, the README's four pages of kit prose, presupposes that reader.
What is lost by a config file, typed edges and testable bodies, lands on exactly the person the
evidence says is using this; what is gained, skipping a compile `lb` already drives invisibly,
saves seconds per run.

What the "less Scala" instinct gets right is kept: the default path stays config only, and `lb`
owns the compile so the consumer never faces scala-cli directly.

## What this design changes

Three changes, in rising order of importance.

**1. Two startup checks close the macro's unique catches.** `Plan.declarationViolations` (which
already refuses duplicate names by identity and unreachable exit sources) gains a dead end rule:
every node reachable from the entry over the plan's own edges must be the source, by reference
identity, of at least one edge, `To` or `Exit`. This is the static form of the rule the docs
already state dynamically ("every node needs an outgoing edge that can fire"), and it catches the
inline construction bug, a fresh object no edge leaves, before any node runs instead of when the
walk dead ends. A new `LoopGraph.planProblems` member (`Nil` for the shipped inhabitant, the plan
checks for `graph`'s) is read by `Machine.runOnce` at the same top of tick point where it already
validates `graph.shape(cfg)`, so a bad plan faults before `begin` ever runs `Pick`, the exact
ordering guarantee the shape validation already has.

**2. The macro is deleted.** `KitMacro.scala` goes, `checkedPlan` and `checkedShape` go,
`markerRequiresReview` is replaced by the equivalent runtime subtype test inside a non inline
`Node.apply` (a `TypeTest` or a class check on `I` is not available at a plain method, so the marker
derivation moves to the one place it is actually consumed: `Node.apply` keeps a tiny transparent
inline that reads `compiletime.summonFrom` over `I <:< RequiresReviewInput`, twenty lines where the
macro was fourteen hundred, or, if even that inline proves troublesome, the marker fact is read at
startup off the first input value, with the honest loss stated in the risks section).
`LitterBox.graph` becomes a plain `def`, the literal requirement disappears, and with it: step 9 of
`docs/custom-loops.md`, the ninety line readable forms block in the README, the `--server=false`
section of `TEST.md`, the five differently worded compile errors, and the val versus def versus
export versus instance qualified rule set. One authoring rule survives, enforced at startup with a
named message rather than taught in prose: a node in a `Plan` is one runtime value, so bind it once
and name the binding, because the walk links edges by identity. Review reachability remains exactly
as checked as it is today, by `Runner.validate`, on every tick, before any node runs, against the
real nodes.

**3. Per node grants land in `Runner.step`.** The `Grant` enum, the deny wrappers, the defaulted
`grants` parameter on `Node.apply`, and the narrowing in `step` as described above. The shipped
nodes then declare their honest grants in a follow up commit, which doubles as an audit: any
shipped node that fails its own tightened grant in the suite was reaching for a capability its doc
never mentioned, a finding in itself. `TestWorld.runNode` already goes through `Runner.step`, so a
consumer tests a node's grants with zero new testkit surface.

Everything else stays. `Plan`, `Edge`, `Node`, `Workflow`, `Runner`, `Trust`, `Guard`,
`RequiresReviewInput`, the `Judged` mint, the `Ledger`, `Role`, the goldens, the testkit, the
scaffold, and both docs in their structure.

## What compile time proofs are worth their cost here

The kit already carries the compile time proofs that earn their keep, and none of them needs a flag:
an edge's input function is type checked against the two real node types it joins; `Judged` is
unmintable outside the capability by sealed hierarchy and constructor scoping; `Node`'s constructor
is private so `trust` cannot be hand written; the `Ledger` cannot be constructed or reached from a
node. These are cheap, stable, and aimed at the right party.

The incumbent adds two more proofs, and both fail a cost test this project's own threat model sets.

**Least privilege by capture checking protects against the wrong party and evaporates at runtime.**
Ask who the adversary is. The untrusted party in litter-box is the dispatched agent, and every
channel it has is data: a patch, ruled on by `PatchGuard`; a verdict, carried by `Judged`; a
comment, neutralised by `Reply`. Node bodies are operator code. A compile time capability proof
therefore constrains the trusted party's diligence, a worthwhile thing, and does it with an
experimental feature whose sets are, in the incumbent's own words, "erased at runtime". The
incumbent's risk section then concedes the rest: both proofs rest on compiler directives inside
`.litter-box/loop.scala`, a file in the repository the harness works on, and an agent that edits it
degrades least privilege "silently to nothing". Two mitigations are proposed, protecting the path
and forcing the flags from `lb`, and both are runtime controls propping up a compile time proof.
When the proof needs runtime enforcement to stay true, the runtime enforcement is the load bearing
part, and the question becomes what the proof still adds over enforcing at runtime directly.

**The runtime alternative is stronger where it matters, not merely cheaper.** A node granted
nothing but `Gh` holds a `Caps` in which the live `Git` does not exist. No cast reaches it, no
reflection reaches it, no second JVM language reaches it, no deleted compiler flag resurrects it,
because the withheld object is absent rather than forbidden. Compare the incumbent's own residual
list for the capture route: tracking "is silently erased when a capability is passed at a plain
type"; the guarantee "holds only in files compiled with capture checking on"; and the fallback if
the feature proves untenable is a rework of the whole capability layer. Under a thesis of distrust,
a guarantee that survives hostile bytecode beats one that survives only cooperative compilation.

**The counterarguments, stated fairly.** A compile error is earlier and cheaper than an rc 50
tick, and capture inference asks the author to write nothing, where a grant is declared by hand and
can be over granted. Both points are real and both are smaller than they look. On earliness: the
consumer's compile and the consumer's startup are the same `lb` invocation seconds apart, and a
grant mistake surfaces on the node's first execution under `TestWorld.runNode`, which the docs
already make the step before the first real run. On inference: inferred capture sets prove nothing
by themselves; the incumbent's own design says an annotation is written "only where the compiler
should hold a node to a restriction", so least privilege is opt in per node under both designs, and
the authoring cost of `grants = Set(...)` and `^{w.gh}` is a wash. What inference genuinely enables
that grants cannot is checking a `par` branch's reach without running it; since this design ships
no `par`, that advantage has no referent here.

**Granularity is a shared limit, and the incumbent's worked example trips over it.** Capture
tracking as specced distinguishes `{w.gh}` from `{w.git}`, trait granularity, the same unit as a
`Grant`. The incumbent's example then claims "`Judge` may not: a reviewer that tried to push would
not compile", but its own `Review` node reads `w.git.diffCachedOriginMain()` for the prompt, so
`Judge` captures `{w.git}` and a push inside it compiles fine. Making that claim true requires
splitting `Git` into a read half and a mutate half, and that split is the same one commit of
ordinary capability wiring under either design. Neither design should pretend method level
privilege exists until the trait is split.

**Totality by `-Xfatal-warnings` is a proof rented from a blunt instrument.** Every future warning
in every future Scala version becomes a hard break inside every consumer's `loop.scala`. The
uncovered case it guards against is, in the runner's hands, already a routed infra fault. This
design keeps totality a runtime property and keeps the flag out of consumer files.

## Cost of change

The incumbent: nine migration steps; rewrites `Kit.scala` (1660 lines) and `Machine.scala`'s twelve
nodes (3531 lines), resurfaces `Live.scala`'s types, rewrites the testkit, rewrites all twenty
eight golden logs in a dedicated commit, adds three new modules (prompt builder, decoder, sandbox
profiles) and one sandbox script, deletes seven types, renames the consumer surface end to end, and
signs the public API onto an experimental compiler feature for the life of the 0.x line.

This design, in commit sized steps, each ending with the suite green:

1. **Startup plan checks.** The dead end rule in `Plan.declarationViolations`, the `planProblems`
   member on `LoopGraph`, the read in `Machine.runOnce`. Touches `Kit.scala`, `LitterBox.scala`,
   `Machine.scala` (a few lines), specs. No golden moves.
2. **Delete the macro.** `KitMacro.scala` removed, `checkedPlan`/`checkedShape` removed,
   `LitterBox.graph` de inlined, the marker derivation reduced to its twenty line core,
   `GraphMacroSpec` removed, `GraphValidationSpec` trimmed to the runtime half. No golden moves.
3. **Grants.** `Grant`, the deny wrappers, `Node.apply`'s defaulted parameter, `Runner.step`'s
   narrowing, `RunnerSpec` coverage. Default is the full set: no behaviour change, no golden moves.
4. **Honest grants on shipped nodes.** Each shipped node declares what it uses; the suite is the
   audit. Golden moves only if a node was over reaching, which would be a finding worth a golden.
5. **Docs.** `custom-loops.md` loses step 9 and gains a short grants section; README loses the
   readable forms block; `TEST.md` loses the `--server=false` section.

Net line count is strongly negative. The testkit artifact is untouched. A consumer's existing
`loop.scala` compiles unchanged after every step; after step 2 it is allowed to do things it could
not before (compose edge lists, build plans in helpers).

## Worked example

The consumer graph under this design is `test/ReviewFixLoopExample.scala` nearly verbatim, which is
the point: the deltas fit in a screen, so only the deltas are shown.

Each node declares what it may touch, and the runner withholds the rest at runtime:

```scala
val Setup: Node[Int, Work] = Node(
  name    = "Setup",
  cost    = Cost.NoDispatch,
  timeout = Timeout.Unbounded,
  grants  = Set(Grant.Gh, Grant.Git, Grant.Fs),        // no Agents: Setup provably cannot dispatch
  probe   = _ => None,
  run     = ...                                        // body unchanged
)

val Review: Node[ReviewRound, AgentDispatch.Judged[Reviewed]] = Node(
  name    = "Review",
  cost    = Cost.OneDispatch,
  timeout = Timeout.Unbounded,
  grants  = Set(Grant.Agents, Grant.Git, Grant.Fs, Grant.Status),  // no Gh: cannot open a PR
  probe   = _ => None,
  run     = ...                                        // body unchanged
)
```

A `Review` body that reached for `caps.gh.createPr` faults at that call, rc 50, world untouched by
it, with a log line naming the node, the capability and the method. That holds when the body is
compiled with any flags, when it is called through reflection, and when the value is cast, because
the live `GitHub` is not in the bundle the node was handed. (`Review` still holds `Git` whole for
its diff; splitting `Git` into read and mutate halves is the follow up that makes "a reviewer
cannot push" literally true, and it is the same follow up the incumbent's design needs for the same
sentence.)

The `Plan` is unchanged, except that composition is now legal, so the publish tail a consumer might
share across graphs can be a helper:

```scala
def publishEdges(from: Node[?, PrRequest]): List[Edge] = List(
  Edge.To(from, OpenPr, (r: PrRequest) => Some(r)),
  Edge.Exit(OpenPr, (o: PrOpened) => Some(if o.needsHuman then LoopExit.NeedsHuman else LoopExit.Success))
)

val graph: LoopGraph = LitterBox.graph(
  name = "review-fix-cycle",
  plan = Plan(entry = Setup, edges = cycleEdges ++ publishEdges(routeAfterReview)),
  dispatchBudget = (cfg: Config) => 1 + MaxRounds * (1 + cfg.repairBudget),
  startInput = (tick: Int) => tick,
  stages = myStages
)
```

Nothing else in the file moves. The six carrier case classes stay, the guarded `FixRound` stays and
is checked at startup on every tick, the fan out over findings stays a fold in `Fix`, and the spec
that walks the graph through `TestWorld` runs unmodified. Set against the incumbent's example: that
version is shorter by two carrier types and reads more fluently in the review cycle, and it costs a
new API surface, two compiler flags in the file header, and every name in the file changing.

## Risks and open questions

**The marker derivation without a macro needs one careful commit.** `Node.apply` currently derives
`guard` from `I <:< RequiresReviewInput` via a one line macro inside an inline method. Deleting
`KitMacro.scala` wholesale needs that one line rehomed. The clean shape is a minimal
`inline def` over `compiletime` machinery with no quotes API at all; if that fights the compiler,
the fallback is keeping a single file, twenty line macro object for this one expression, which
still deletes ninety eight percent of `KitMacro.scala` and all of its authoring rules. Open until
prototyped.

**A grant mistake surfaces at runtime.** An under granted node faults on a path first exercised in
production if the consumer's tests never walked that path. Fail closed and named, but later than a
compile error. Mitigated by the default being the full set (no consumer is forced into grants) and
by `TestWorld.runNode` making the first execution cheap; not eliminated.

**Over granting is silent.** Grants enforce, they do not infer, so a node granted everything is
exactly today's node and nothing nudges it narrower. The shipped nodes' honest grants (step 4) set
the example; a lint is imaginable and out of scope.

**Startup rejection replaces an IDE error.** A consumer authoring in an IDE with the macro today
gets a red squiggle on a review reachability mistake at edit time; under this design the same
mistake reads as rc 50 seconds later on the first `lb run` or in the first `TestWorld.runGraph`
test. This is the one genuine regression of deleting the macro, priced against the 1386 lines and
the rule set.

**Dynamic plans widen what a startup check must carry.** Once plans compose, a plan can be built
badly in ways the literal requirement made unwritable. The dead end, duplicate identity and
reachability checks cover the failure modes known today; new composition idioms may surface new
ones, and the answer must stay "add the startup check", not "restore the macro".

**`Role` stays, and stays cramped.** The incumbent is right that `Role` conflates the command
override, the model key and the log wording, and that a third worker kind cannot be expressed
today. The model keys in `config.conf` are already per role (`agent.model.impl/fix/review`), and
issue #73 tracks the seam. This design leaves it for the day a third kind exists, because deleting
`Role` today rewires `Live.scala`, `Settings.scala`, the testkit and the goldens for a consumer
that does not yet exist. Deferral, not disagreement.

## Honest weaknesses

Stated without hedging.

* **The authoring model stays worse than `Flow` in the small.** `switch` with a total match over a
  sealed answer reads better than ordered `Option` edges, `withInput` genuinely dissolves carrier
  types this design keeps, and `Flow.rec` states a cycle more directly than an edge written
  backwards. On pure ergonomics for the graph author, the incumbent wins and this design should not
  pretend otherwise. The claim here is that the gap, measured on the two worked examples, is about
  two case classes and some fluency, and that it does not price in a nine step rewrite plus an
  experimental compiler feature.
* **No static least privilege proof exists at all under this design.** Layer one (narrow context
  parameters) is a discipline, not a check; nothing fails to compile when a node body summons the
  whole bundle. The design bets that enforcement beats proof under this threat model, and that bet
  forfeits the compile time claim entirely rather than weakening it.
* **Trait granularity means the flagship sentence is not yet true.** "A reviewer cannot push" needs
  the `Git` split before either design can say it honestly. Until that follow up lands, grants
  withhold whole capabilities only.
* **The status quo's drift risk on the shipped graph is untouched.** `shippedWorkflow` and
  `shippedShape` remain two hand written statements of one graph. Deleting the macro opens the door
  to expressing the shipped pipeline as a `Plan` (the `def` built node objection dies with the
  literal requirement), which would close the drift for good, but this design does not spend that
  commit and the risk stays.
* **Nothing here helps a consumer who genuinely needs declared concurrency.** If a real scatter
  gather with parallel dispatches arrives as a requirement, a node body over virtual threads is the
  answer on offer, invisible to the shape and to `watch`. That is a real expressiveness ceiling and
  this design chooses to hit it later rather than build for it now.

## Where the incumbent is right

Credit where the analysis holds, including pieces this design recommends adopting on the current
kit, none of which requires the Flow rewrite they are packaged with.

* **The macro is a net loss.** Full agreement on the diagnosis; the designs differ only in what
  else is deleted alongside it.
* **The prompt builder is a straight improvement.** `Prompt.from(...).trusted(...).untrusted(...)`
  making the trust level of every substitution part of the call, with fence neutralisation and
  capping applied to every untrusted slot the way `Reply.splice` already does for comments, is
  better than `renderTemplate` plus convention. It is a kit tier module in the exact mold of
  `Reply` and `PatchGuard`, landable by ADR amendment with no graph model change. Adopt.
* **The nonce fenced decoder is a straight improvement.** A per dispatch marker with zero or many
  matches both reading as `Malformed` defeats echo forgery in a way `grep | tail -1` does not, and
  the incumbent is admirably precise that it does nothing against persuasion. Also landable on the
  current kit: a `Decoder` module plus a change inside `Machine.Review`'s parsing. Adopt.
* **Binding the trust token to a sandbox profile is a stronger statement than today's.** The
  observation that cold isolation is currently a property `LiveAgentDispatch` chooses rather than
  one the signature demands is sharp and true (`AgentDispatch.review`'s own doc concedes it). A
  sealed profile set is worth designing on the current `AgentDispatch` seam when the question
  answering fixer is actually built.
* **The `Role` critique is correct** as noted above, and deferred rather than rejected.
* **The restated meaning of `Judged`** ("a fresh session with no worker context and no write back
  channel", never correctness) is the best one sentence statement of that token anywhere in the
  project and should land in `Caps.scala`'s doc verbatim, whatever else happens.

## Verification

Everything here is testable without Docker, `gh` or credentials, the standing constraint.

* The dead end rule and the pre `begin` plan check are pure data tests plus one `TestWorld` walk
  asserting the fault fires before `Pick` runs.
* Grants are `RunnerSpec` territory: a node granted `Gh` alone whose body calls `git.push` faults
  with the pinned message; the same node through `TestWorld.runNode` reports the same; a granted
  capability behaves identically to today, asserted by running an existing scenario under
  explicitly full grants.
* Macro deletion is verified by the suite compiling without `KitMacro.scala` and by
  `GraphValidationSpec`'s runtime half going green, plus a new spec pinning that a composed
  (`++` built) plan validates and walks.
* The one inline residue (the marker derivation) keeps a negative compilation test through
  scala-cli, the same technique `GraphMacroSpec` uses today, shrunk to one file.
* Shipped node grants are verified by the existing scenario suite: twenty eight goldens run under
  tightened grants is the audit, and any golden that moves is a reviewed finding, not collateral.
