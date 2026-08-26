## Architecture

### The pure machine over capabilities

The central split, and the reason the whole suite runs in memory:

- `src/Caps.scala` — capability traits (`GitHub`, `Git`, `HarnessFs`, `GateRunner`, `AgentDispatch`,
  `Notify`, `Log`, `StatusLog`, `Clock`), passed as `using` context parameters. It also holds
  `HostGateRunner`, which is not a capability of its own but a case class wrapping a `GateRunner`
  (pointedly not a subtype of one, so it can never win `using GateRunner` resolution): same
  mechanics on the host side of the sandbox boundary, a second type purely so `Main.gateRunners`
  has to wire each tier by name from its own `LiveGateRunner` rather than reuse one instance (the
  why is in the `HostGateRunner` scaladoc, issue #11).
- `src/Kit.scala`: the graph kit, `Node`, `Workflow` and the `Runner` that walks one, plus the
  startup validation phase (`Runner.validate`, issue #38): a `Shape` (`entry` nodes and
  `Transition`s) describing the same graph a workflow's `Next.Goto` closures encode, walked BEFORE
  `wf.start` ever runs, so a path into a `Guard.RequiresReview` node that never crosses a
  `Trust.Reviewed` one is rejected before a single node executes. `LitterBox.graph` (issue #43,
  `src/LitterBox.scala`) is the consumer entry point onto this kit: a foreign package builds its own
  `Node`s and ONE `Plan`, the table naming every edge between them (issue #67), and hands that to the
  factory, which derives both the `Workflow` it walks and the `Shape` it validates from that one
  value and returns a `LoopGraph` `LitterBox.run` accepts. The shipped graph keeps writing its
  `Workflow` and its `Shape` by hand (`Machine.shippedWorkflow`/`Machine.shippedShape`), for the
  reason `LitterBox.graph`'s own doc gives: a node built by a `def` reading `Config` at construction
  time is exactly what a macro over a literal cannot read, and that is the shipped graph's idiom.
  One further shipped node is now public too, and deliberately only one more (issue #68), alongside
  `Machine.AskHuman` (issue #44): `Machine.Gate` is a public,
  parameterless `val`, so a consumer's own `Plan` literal can name it and compose the real FAST gate
  into a graph of their own instead of reimplementing it. That node's own scaladoc is where the
  decision lives: which node, why one more rather than a family, why every shipped node other than
  `Gate` and `AskHuman` stays private, and what a consumer takes on by wiring it in. Nothing about `shippedWorkflow` or
  `shippedShape` moved for it; both simply name `Gate` where they used to call `Gate(cfg)`, since that
  parameter was never read.
  The kit is a TIER, and the dependency arrow into it runs one way: no code in a tier 1 file may name
  anything declared outside tier 1 plus `src/Domain.scala` and `src/Caps.scala`, since whatever the
  kit names is transitively part of the surface a consumer depends on. `src/Kit.scala` and
  `src/KitMacro.scala` were the original two; `src/PatchGuard.scala` and `src/Reply.scala` each joined
  under their own amendment, and each entry below says why. `docs/adr/0001-framework-tier-is-kit-only.md` carries the reasoning and
  `test/KitBoundarySpec.scala` holds it.
- `src/PatchGuard.scala`: the patch guard, tier 1 alongside the kit (`docs/adr/0001-framework-tier-is-kit-only.md`
  carries the tier rule and the amendment that added this third file). `PatchGuard.stage` is the whole
  patch seam, reset to the pristine base, rule on the patch, then apply it or stage `PATCH-REJECTED.md`
  in its place, and `PatchGuard.rule` is that ruling on its own, pure over the size cap and the
  `protect` globs. Both are public, because the alternative is what this repository actually had: the
  guard `private` inside `Machine` and `test/ReviewFixLoopExample.scala` restating it by hand with a
  prefix matcher weaker than the real one. The agent dispatch stays OUT of it (that is the node's
  `Cost` and the runner's metering), and so do the status emit and the per role log narration (they
  need a `Cursor` and carry the IMPL/FIX announce/silent split, which is routing). The glob matcher
  moved here out of `Settings` because tier 1 may not name `Settings`; `Settings.protectWithFloor`
  stayed there, since which patterns are in the list is config layering.
- `src/Reply.scala`: the reply protocol, BOTH halves, tier 1 alongside the kit
  (`docs/adr/0001-framework-tier-is-kit-only.md` carries the tier rule, the amendment that added this
  fourth file and the amendment that widened it to the selection half). Four public names and nothing
  else: `since`, `splice`, `Marker` and `Since`.
  `Reply.since` is the selection half: which entries on an issue thread count as a human answering,
  cut at the LAST park marker the viewer posted, split into the accepted ones, the ignored ones and
  the accepted authors. The marker token and the viewer login travel in as arguments, since tier 1
  may not name `Machine.ParkMarker` and the answer belongs to whichever question the caller poses.
  `Reply.Marker` is the caller's CLAIM about a marker the module cannot verify, and it decides one
  thing only: what an absent marker means. `Required` answers no reply, `Proven` keeps the historical
  "then every comment counts" fallback. That was an unstated precondition proved in prose at each
  call site before issue #84, once by a hand written pre check the other caller did not need, and a
  third caller had nothing to read at all. `pickAndSetup` claims `Proven` because it is gated on the
  `parked` label, external evidence a marker was posted; `AskHuman`'s probe claims `Required` because
  it runs the first time an issue is ever parked.
  `Reply.splice` is the render half. It takes the untrusted comment entries a thread holds and
  returns one block of prompt text: escape the entry grammar, defuse a forged fence tag, then cap per entry,
  joined by the separator it owns. The ORDER is the protection rather than the three steps, which is
  the whole reason it is one function and not three: capping first can cut a forged entry boundary in
  half and change whether it still parses, and an escape running after the cap cannot see the text
  the cap already dropped. It was two hand written copies inside `Machine` before, at `runFixRound`
  and at `AskHuman`'s own probe, only one of which carried the comment saying why the order is what it
  is. Every step, the separator, the empty thread sentinel and both character budget constants are
  private to it. The sentinel for a comment read that FAILED deliberately stayed in `runFixRound`,
  since this function is handed what was read and cannot tell an empty thread from an unreadable one.
- `src/Machine.scala` — `Machine.runOnce`, and the shipped `Workflow` (`Machine.shippedWorkflow`) it
  walks through `Runner.run`: pure decision logic. Touches the world through nothing but the
  capabilities. No direct filesystem, subprocess, or clock access.
- `src/Live.scala` — every real side effect (`LiveGit`, `LiveGitHub`, `LiveGateRunner`,
  `LiveAgentDispatch`, `LiveProc`, ...). Handlers take dependencies as constructor params.
- `test/Recorder.scala` — `TestWorld`, scripted in-memory handlers for every capability plus an
  interaction recorder. Scenarios assert on both the outcome and the call sequence. Also the
  published testkit artifact `in.rcard::litter-box-testkit` (issue #42), compiled standalone against
  the library jar off the same tag, so a consumer tests their own graph the way this repo tests its
  own; `TEST.md` has the constraint that follows from that.

Adding behaviour that needs the outside world means adding a capability method in `Caps.scala`, a
decision in `Machine.scala`, an implementation in `Live.scala`, and a script in `Recorder.scala`.

### Startup graph validation (issue #38)

`Runner.validate` walks a `Workflow`'s own declared `Shape` before `Runner.run` ever calls `wf.start`,
rejecting a graph where a path reaches a `Guard.RequiresReview` node without first crossing a
`Trust.Reviewed` one. `Machine.shippedShape`, the shipped loop's own declared `Shape`, does not
declare `Guard.RequiresReview` on any node: `OpenPr` and `Merge` are both reached by legitimate
rejection paths that never cross `Review` (an unreviewed patch, a repair round that never got
reviewed), so a `guard` on either would reject the shipped graph itself. Practically, this means
`Runner.validate` only ever checks declaration hygiene on the shipped graph (an empty `entry`, two
nodes sharing a name but disagreeing on `cost`/`timeout`/`trust`/`guard`, an orphan node declared in
`transitions` but unreachable from `entry`), never review-reachability, since nothing in this graph
ever exercises that check. Issue #39's compile time macro, reading the literal graph the `Next.Goto`
closures encode instead of a hand-declared `Shape`, is what would let a real guard on `OpenPr`/`Merge`
be stated and checked honestly.

All of that is about the SHIPPED graph, the one inhabitant that still writes its `Workflow` and its
`Shape` as two hand-written values. A consumer graph authored through `LitterBox.graph` declares
neither (issue #67): it writes one `Plan`, the table of its own edges, and the `Shape`
`Runner.validate` walks for it is derived from exactly the edges the run itself takes, so the drift
this section's own reasoning has to keep allowing for on the shipped graph cannot arise there.

This claim was rechecked, not merely carried forward, after issue #43 review round 4's Tier 2 made
`Node.apply` derive `Guard.RequiresReview` from a node's own input type extending
`RequiresReviewInput` (`Kit.scala`'s own doc on `markerRequiresReview`/`Node.apply` has the mechanism): that
derivation could, in principle, have made a shipped node newly carry `Guard.RequiresReview` without
anyone editing `Machine.shippedShape` by hand, since the derivation reads a fact of the TYPE, not of
what a node author wrote. It does not, confirmed by grepping every shipped node's own input type for
`RequiresReviewInput`: none extends it, `OpenPr`/`Merge` included, so this paragraph's claim that
`Runner.validate` never exercises the review-reachability half of its own check on the shipped graph
is still true after Tier 2, for the same reason it was true before, `Machine.shippedShape`'s own doc
restates. Issue #68 made one shipped node public without changing that fact, and the consequence is
stated rather than left implicit: `Machine.Gate` carries `Guard.Open`, so `Runner.validate` will accept
a consumer graph routing into it with no reviewer anywhere on the path. That is safe for THAT node
because everything it does is stage the tree and run the operator's own gate command, never a push, a
PR or a merge, and it is precisely why the widening stopped at one node rather than reaching the ones
that do publish outward. Adding `RequiresReviewInput` to a shipped node's input type to make its guard
honest is not the missing fix but the poisonous one, for the reason this section already gives: the
derivation stamps the REAL node, `Runner.validate` reads it at startup against `Machine.shippedShape`,
and the shipped graph would then reject itself on every tick.

Infra faults short-circuit through `boundary.Label[LoopExit]` (aliased `Faulting` in `Machine`).
That is a type-level guarantee, not a convention: no code after a fault can run, so no fault path can
burn repair budget or dispatch a FIX.

### Domain

`src/Domain.scala` holds the closed types: `LoopExit` (the eight terminal states and their process
exit codes 0/10/11/20/30/40/50/60 — the rc contract shared with `watch.sh`, never change a meaning),
`Ruling` and `Staged` (what the patch guard concluded, and what it then did; two types rather than the
one `StageResult` they replace, since the dispatch timeout that used to be a case of it now belongs to
the caller, `src/PatchGuard.scala`), `GateResult`, `Verdict`, `FailureKind` (whose `text` strings appear verbatim in logs,
commits and PR notes), `InfraFault`, `Role`, `Template`, `Config`.

### Configuration layering

Four layers, and the `Settings` object's own scaladoc (`src/Settings.scala`) is the one place that
states which of them beats which, plus the two qualifications on it. Change the rule there and
nowhere else; everything here is about the parts, not the order.

`src/Settings.scala` holds the reference schema as HOCON text, parses `.litter-box/config.conf` onto
it (`loadFile`) and reads `.litter-box/.env` (`loadDotEnv`); `Main.layerDotEnv` combines that file
with the process environment and `Main.parseEnv` layers the result over the file's values. A missing
config file is a `Left`, never silent defaults — the loop exits 50 and names `litter-box init`. So is
a model name outside the `AgentModel` set, wherever it is written, `agent.model.*` or a `*_MODEL`
variable: an unrecognised model has no safe reading, since the only alternative to stopping is to
dispatch on the CLI's default, which on the cold reviewer is a downgrade nothing downstream reports.

`.litter-box/.env` is plain `KEY=value` lines, holds any variable the loop reads rather than a
credential only, and is OPTIONAL, unlike `config.conf`: exporting the variables is the other equally
supported way. A JVM cannot mutate its own environment, so the entries the ambient environment does
not already carry are stamped onto every child through `LiveProc.exportEnv` — the sandbox scripts
read the credential off their own environment, never off the loop.

`protect` is a floor, not a list: a consumer's entries are **unioned** with the reference floor, so
`.litter-box/**` is always covered and the loop cannot be talked into loosening its own guard.

### What ships in the artifact vs what a consumer owns

Prompt skeletons, sandbox scripts and the observability scripts are all **protocol, not
configuration**, so they live under `resources/` and travel inside the jar
(`//> using resourceDir ./resources`):

- `src/Prompts.scala` — resolves `Template` skeletons from the classpath, with
  `.litter-box/prompts/<name>` overriding per file (written by `litter-box eject`).
- `src/Shipped.scala` — one implementation of "get a tree of files out of the jar and onto disk
  intact": materialises `resources/<tree>/**` to `~/.cache/litter-box/<tree>/<digest>`, keyed by
  content digest so an upgrade lands in a new directory with no cache-busting step. Each tree is a
  subclass naming its directory and its explicit `ShippedFiles` manifest; `test/ShippedSpec.scala`
  runs the whole spec against every tree in `Trees` and fails if a `resources/<tree>/` directory
  gains a file that is not in its manifest.
- `src/Sandbox.scala` — the Docker sandbox tree, run by the loop.
- `src/Observe.scala` — `watch.sh`, `tail-claude.sh` and their `lib/`, run by a human. Fronted by
  `litter-box watch` / `litter-box tail` (`ObserveTool` in `Cli.scala`, `Main.runObserve`), because
  nobody types a content digest. They ship rather than scaffold because `watch.sh` parses the
  `status.jsonl` schema `LiveStatusLog` writes, so a consumer-side copy would rot silently.
- `src/Init.scala` — `litter-box init` scaffolds seven files from `resources/scaffold/` into
  `.litter-box/`. A consumer owns only `Dockerfile`, `allowlist`, `config.conf`,
  `prompts/conventions.md` and `loop.scala` (the file that names which graph runs; `LitterBox.shipped`
  by default, or a consumer's own graph built through `LitterBox.graph`).
- `src/LitterBox.scala`: the public front door (`LitterBox`, `LoopGraph`), issue #43: the coordinate
  a consumer depends on, the shipped graph (`LitterBox.shipped`, whose `workflow`/`shape` supply the
  shipped `Machine.shippedWorkflow`/`Machine.shippedShape`, and whose `begin` runs the `Pick` step and
  computes the resume aware dispatch budget seed that `Machine.runOnce` then turns into the `Ledger` it
  owns, because `begin` is the graph shaped hook every graph has, so the shipped graph uses that same
  hook rather than a special case inside `runOnce`), the smart constructor for a consumer's own graph
  (`LitterBox.graph`, RFC #26 decisions 5 and 8), and `LitterBox.run`, which reuses `Main`'s own Live
  wiring rather than duplicating it. A consumer can now author their own `LoopGraph`, but only through
  `graph`, never by writing `extends LoopGraph` themselves: `LoopGraph` stays `sealed`, so extending it
  is rejected outright as a different-source-file violation, and every one of its members stays
  `private[litterbox]`, so nothing about implementing it is nameable from outside this package either
  (issue #43 review round 2, MINOR m3: this held for the trait's own abstract members from the start,
  but `type Start` specifically needed the identical modifier written explicitly on BOTH concrete
  overrides too, `LitterBox.shipped` and `LitterBox.graph`'s own anonymous inhabitant, since Scala
  permits a concrete override to widen an inherited member's access back to public regardless of what
  the abstract member it implements declares; `src/LitterBox.scala`'s own doc on `LoopGraph`'s `type
  Start` has the fuller reasoning and the confirmed repro).
  `graph` is a factory that builds and returns the `new LoopGraph` from inside this same file, the same
  file `sealed` already permits, so widening the trait's own surface turned out not to be the only way
  to let a consumer author a graph. The abstract member `type Start` is what makes a second, differently
  shaped inhabitant possible at all: `LoopGraph` stays non-generic (`Main.dispatch`/`Machine.runOnce`
  pass one around without ever naming its start type), and each inhabitant fixes `Start` to its own
  input type, `Machine.ShippedStart` for `shipped`, whatever `I` the caller chose for `graph`. Budget
  ownership stays with the runner even for a consumer's own graph (RFC #26 decision 9):
  `LitterBox.graph`'s `dispatchBudget` parameter is a plain `Config => Int`, never a `Runner.Ledger`,
  whose constructor stays `private[litterbox]`; `LoopGraph.begin` is where a graph declares that number
  and its own start input, and `Machine.runOnce` is the only place that ever turns the number into a
  real `Ledger`. Decision 17 also promises nothing is expressible in both `config.conf` and
  `loop.scala`; `LitterBox.graph`'s own doc (`src/LitterBox.scala`, on `graph`) names the one accepted
  exception a constant `dispatchBudget` value creates and is the one place that exception is written
  out, not repeated here. Owning the counter and bounding every spend were two different promises
  until issue #69, and decision 9 asks for both, so the runner now performs both. `Runner.Ledger.canAfford`
  still runs once, before a node starts, and refuses to let a `Cost.OneDispatch` node begin on an empty
  ledger, parking the tick. Underneath it, `Runner.step`'s own `charging` decorator asks the ledger for
  each real dispatch AT the moment it happens, whatever `Cost` the node declared, and raises an infra
  fault instead of delegating when nothing is left. So `dispatchBudget` bounds the total dispatches a
  graph's nodes can make, not merely how many `Cost.OneDispatch` nodes may start, and a misdeclared
  `Cost.NoDispatch` node no longer dispatches freely under a budget of zero. `Cost` is therefore an
  assertion the runner checks, not a declaration it trusts; what an honest `Cost` still buys is the
  shape of the refusal, park before the node runs rather than fault partway through it, which is the
  reason to keep declaring it accurately (`Runner.Ledger`'s own doc, `LitterBox.graph`'s own doc, and
  `README.md`'s "Write your own loop" section all state it in those terms).
  No golden log line moved with that change, which is the check that says the SHIPPED graph never
  relied on the hole: `Machine.Implement` and `Machine.Repair` both declare `Cost.OneDispatch` and
  dispatch once, `Machine.Review` runs against its own dedicated `Runner.Ledger(1)`, the repair loop
  tests `ledger.remainingDispatches` itself before every FIX round, and the resume path is only ever
  taken when `cfg.repairBudget` is greater than zero, so no shipped node ever asked for a dispatch the
  ledger could not pay for. The refusal deliberately faults rather than answering the node with a
  "refused" value: a value would be a channel from the runner back INTO a node, letting it probe the
  counter it must not see, and on the review path anything returned would be an `AgentDispatch.Judged`
  minted with no cold session behind it (decision 7), a strictly worse failure than the overspend.
  Pre-emptive TIMEOUT interruption was considered in the same issue and declined, with the reasons
  recorded on `Timeout.After` and `Runner.step`: the real bound already sits at the subprocess
  boundary, an interrupted node leaves the world half written where a killed child does not, and node
  bodies write unsynchronised shared state that a second thread would make concurrent. `startInput` itself is
  `Int => Fault ?=> I`, never `(Caps, Fault) ?=> I` (issue #43
  review, BLOCKER 2): `begin` runs before that real `Ledger` exists, so a `startInput` that could
  summon `Caps` could call `agents.*` on the live `AgentDispatch` outside every check budget, timeout
  and trust ownership actually rest on. `plan` has to be a literal `Plan(...)` expression written
  directly at the `LitterBox.graph` call site, never a `val` passed by identifier (issue #43 review,
  BLOCKER 1): the review-reachability macro this factory runs, `checkedPlan`, is a hard compile
  error on anything else, unlike `checkedShape`'s own opt-in, silently-falls-back behaviour. At the
  time this was written, the reason was that `Runner.validate` was not a reliable backstop for a node
  whose input type extends `RequiresReviewInput` while its own `guard` argument was left at
  `Guard.Open`, the exact shape every shipped `OpenPr`/`Merge`-style node takes. Issue #43 review round
  4's Tier 2 closed that specific gap (`Kit.scala`'s own doc on `markerRequiresReview`/`Node.apply`): `Node.apply`
  now derives `Guard.RequiresReview` on the real `Node` whenever the marker is present, regardless of
  what `guard` was written as, so `Runner.validate` reading that field at startup now agrees with the
  macro on that one fact. The hard error on a non-literal `plan` stays, for two reasons that survive
  Tier 2: catching a violation at compile time is strictly earlier than catching the same one at
  startup, on every graph the macro CAN read; and two alias families remain that no widening of the
  macro's own walk closes (an instance-qualified receiver, declined outright rather than guessed at,
  issue #43 review round 4, Tier 1; and one `Node` value bound under two different stable paths, which
  this walk cannot detect as incomplete at all), for which `Runner.validate` is not a backstop for a
  fact read differently, it is the only check that ever runs against the graph's real, already-resolved
  `Node` values (`KitMacro.checkReconciled`'s own doc, `KitMacro.scala`, has the mechanism for both).
  That hard error is FIVE differently worded errors, not one (issue #43 review round 2, BLOCKER B1,
  part 1; issue #43 review round 3, BLOCKER 1, part 2, adding the third; issue #67 review, adding the
  fourth and the fifth): `plan` not being recognisable as a `Plan(...)` literal at all gets one message;
  `plan` being a genuine literal whose walk still could not read one piece inside it gets another,
  naming that exact piece, so a consumer who already wrote a literal is never told to do what they
  already did; every piece being readable but two different references canonicalising to the identical
  node identity while disagreeing on whether that node needs review gets a third, naming both
  references, so a `class`/`object` companion pair whose two sides DISAGREE on whether the node needs
  review are never silently merged into one node by whichever the reachability walk's own visited set
  happens to keep; one readable reference naming an inline `Node.apply` construction gets a fourth,
  refused outright rather than only on a collision, because `Kit.scala`'s own `Plan.workflowOf` links
  one edge to the next by which runtime OBJECT a node is, `Edge.source(e) eq from`, never by this walk's
  own canonical name, and an inline call allocates a fresh object at every place it is written, so the
  identical construction written twice is never one runtime value, and written once is a node no edge
  can ever leave (`KitMacro.ParseFailure.InlineNodeInPlan`'s own doc, `KitMacro.scala`, has the full
  reasoning, including why `checkedShape`'s own opt in walk, which never links a `Shape` to a real run
  the way `Plan.workflowOf` does, keeps reading an inline construction unchanged); and two readable
  references canonicalising to the identical node identity while coming from two different DEFINING
  SYMBOLS gets a fifth, naming both references, refused on symbol identity alone before agreement or
  disagreement is ever asked about, because `Plan.workflowOf` links an edge to the node it leaves by
  reference identity and never by this walk's own canonical key, so a `class`/`object` companion pair
  whose two sides AGREE on `(trust, guard)` is exactly as broken at runtime as a disagreeing one
  (`KitMacro.ParseFailure.IdentityConflict`'s own doc, `KitMacro.scala`, has the full reasoning, and
  `KitMacro.checkIdentity` is the pass that raises it, restricted to `parsePlan` and never run from
  `parseShape`, since a `Shape` only feeds `Runner.validate`'s reachability check, which keys by name
  the way an agreeing companion pair spells identically on purpose, never `Plan.workflowOf`'s own
  reference identity walk). That second walk was also
  widened in round 2 to read a `val` member of a class referenced unqualified (`this.A` written bare),
  `List.empty`/`List.empty[...]` alongside `List(...)`/`Nil`, the literal's own two named arguments in
  either order, and an edge bound to a `val` local to the same block as the `LitterBox.graph`
  call, and round 3 replaced the identity KEY that walk uses (three incompatible hand-built schemes
  collapsed into one canonical function, `KitMacro.scala`'s own `canonical`) after that key was found
  to both SPLIT one node into two, spelled two different ways, and MERGE two nodes into one, spelled the
  same way by coincidence. Round 4 narrowed the identity key further rather than widen it: an earlier
  patch keyed an INSTANCE-qualified receiver (`a.node` for a stable, non-singleton `a`) by chaining
  through its own qualifier, closing one silent split but opening a narrower one of its own (a class
  member reached through a second `val` aliasing `this`, three real sidesteps defeated the patch meant
  to catch just that case); round 4 stopped keying instance-qualified receivers ENTIRELY, since the
  macro can never distinguish two genuinely different instances from one instance aliased twice, and
  keying either guess risks disagreeing with the runtime (`KitMacro.scala`'s own `stablePathKey` doc has
  the full reasoning and the three sidesteps). What it still cannot read, on purpose, is a node built by
  a `def`: `Machine.shippedShape`'s own config-parameterised idiom, a node whose construction reads a
  per-tick `Config` value, is the sharpest instance of exactly this, and `LitterBox.graph` genuinely
  cannot express it, RFC #26 decision 16's own recorded "graphs cannot be assembled dynamically" landing
  on this one idiom rather than a gap left unclosed; a consumer who needs that reads `Config` inside the
  node's own `probe`/`run` body instead (derivable there from the ambient `Caps`, `Caps.scala`'s own
  `given (using c: Caps): Config = c.cfg`), behind one plain top-level `val` standing for the node,
  never a `def` building a fresh `Node` per call. `src/LitterBox.scala`'s own doc on `LoopGraph` and
  `KitMacro.checkGraph`'s own doc have the fuller reasoning.

A tag publishes **two** Maven artifacts, not one (issue #42, RFC #26 decision 14). The library above,
and `in.rcard::litter-box-testkit` (`LitterBox.TestkitCoordinate`), which is `test/Recorder.scala`
compiled alone against the library and nothing else: `TestWorld`, `Script`, `FakeClock`, `buildCaps`,
`withFaulting`, `NodeRun` (README's Testkit section narrows that to the part a consumer may rely on). A
node author declares it as `test.dep` and drives their own graph through `TestWorld.runGraph`, or one
node of it through `TestWorld.runNode`, with no Docker, no network and no credentials. `runNode` is why
the artifact has to be compiled into `in.rcard.litterbox` rather than merely depend on it: stepping one
node means calling `Runner.step`, whose `using Runner.Ledger` no consumer package can satisfy, and
`runNode` makes that call on their behalf while passing only an `Int` in and an `Int` back out, so
decision 9's "the runner owns the counter" survives the convenience.

Two artifacts rather than a `testkit` package inside the library jar, and this is a security boundary
rather than a packaging preference. `TestWorld` satisfies `AgentDispatchImpl` from inside
`in.rcard.litterbox`, so `world.agents.review(...)` mints a genuine `AgentDispatch.Judged` out of a
scripted fake, which then clears `Guard.RequiresReview` at both the `LitterBox.graph` macro and
`Runner.step`'s runtime class check, because the token is real. There is no type level defence: a
testkit that could not mint could not drive a review node, which is the thing a node author most needs
to test. Artifact scoping is the control, and since issue #71 a startup check backs it: the loop branch
of `Main.dispatch`, the one door `lb` and `LitterBox.run` share, calls
`Main.refuseTestkitOnClasspath` before any node runs and ends startup at rc 1 when
`in.rcard.litterbox.TestWorld` is loadable in the JVM about to start. Only that branch, so `init`,
`eject`, `watch`, `tail` and `help` still work in the repository whose declaration is being fixed.
The decision is a pure function over a class lookup passed in, because this repository compiles
`test/` beside `src/` and could not otherwise assert the refusing answer without ending the suite's
own JVM. It detects the `dep` written where `test.dep` was meant and nothing beyond it: a renamed or
shaded testkit passes, and so does production code that calls `TestWorld` without going through
startup. `src/Caps.scala`'s `AgentDispatch` doc states the residual in full and
`test/TestkitBoundarySpec.scala` reproduces it from a foreign package, so it fails the day the claim
stops being true. `docs/maven-central-setup.md` covers the release mechanics,
including why the testkit compiles against a locally staged library before anything reaches Central.

### The log contract

The operator log stream is parsed by `watch.sh`, so its wording is asserted behaviour, not decoration.
`test/LogParitySpec.scala` freezes the whole stream per scenario against `test/golden/*.log`. To
change a line deliberately: `UPDATE_GOLDEN=1 scala-cli test .`, then read `git diff test/golden` — that
diff **is** the contract change.

`status.jsonl` is the machine-readable sibling, emitted via `StatusLog`.