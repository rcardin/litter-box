# Alternative B: a typed reified graph for litter-box

Status: design alternative, written 2026-09-07 against the incumbent proposal in
`2026-09-07-litter-box-dsl-design.md`. Not implemented. Every compile time claim below was verified
on Scala 3.8.3 through scratch files compiled with `scala-cli compile --server=false`; the ones that
failed or degraded are reported as such, with numbers.

## Why this alternative exists

The incumbent design fixes the three real problems of the current kit, the case class per edge
payload tax, the macro's eleven paragraphs of authoring rules, and the unused proving apparatus, by
replacing the reified `Plan` with a `Flow` combinator tree. That trade buys ergonomics and pays for
it with the graph itself. A `Flow` is a tree whose branches live inside `switch` closures and whose
cycles live inside `Flow.rec`, so the whole pipeline is never again a value anything can walk
without running it. The incumbent gives up, permanently, the property this repository spent issues
38, 39, 43 and 67 converging on: one data value that is at once what the runner executes, what the
validator checks, and what a tool can read. `Plan`'s own scaladoc states the law this violates:
data can always be turned into closures; closures can never be turned back into data.

This design keeps the graph reified and fixes the two things that made the current reified graph
bad. The macro that read the consumer's syntax tree is replaced by type level structure that the
compiler infers, so every authoring rule that existed to serve `KitMacro`'s literal walk dies. The
untyped, thread everything by hand edge payloads are replaced by typed edges plus declared reads,
so the case class per edge tax dies too. The two properties the incumbent promotes to compile time
proofs, least privilege and review reachability, are proved here as well, by different machinery,
and one of the two proofs comes out with wider scope than the incumbent's.

## The thesis in one paragraph

Give every node a singleton string name in its type and a requirement parameter naming the
capabilities its body may touch. Let a fluent builder accumulate, purely by type inference, a type
level registry of the graph: the node names, the edge pairs, which nodes are reviewers, which are
guarded, and the intersection of every requirement. Then check totality, reachability, duplicate
names, the review guarantee and every declared cross edge read against that registry at compile
time, at the single `done` call that seals the graph. Nothing reads the consumer's syntax. The
consumer may build nodes in `def`s, alias them, wrap them in helpers, or compute them in loops
over data; the types flow through all of it, because inference is the normaliser that `KitMacro`'s
tree walk could never be.

## Non goals

Same as the incumbent's. Vocabulary stays graph shaped. Backward compatibility is not a constraint;
litter-box has no consumers. The incumbent's prompt builder, nonce rule, structured `Decoder`
answers, sandbox profiles and the reviewer that consults a fixer are all orthogonal to how the
graph is represented; this design adopts them unchanged and does not restate them. The comparison
here is about the graph layer only.

## The core model

```scala
final case class Node[N <: String & Singleton, I, O, -R](
    name:    N,
    cost:    Cost,
    timeout: Timeout,
    probe:   I => (R, Fault) ?=> Option[O],
    run:     I => (R, Fault) ?=> NodeOutcome[O]
)
```

Verified on 3.8.3: `Node("Pick", ...)` infers `N` as the literal type `"Pick"` because of the
`Singleton` bound, the two member context function with an abstract `R` compiles and runs, and `R`
being contravariant lets a node needing less stand wherever more is offered.

`trust` and `guard` disappear as fields. Both were always facts about the node's types, `O` being
`Judged[?]` and `I` extending `RequiresReviewInput`, and here they are read off the types directly
by match types at graph assembly, so there is no field for a call site to forge and no `TrustOf`
given for a caller to pass an explicit argument to. That closes the residual `Kit.scala`'s own docs
accept today on `TrustOf`.

The builder:

```scala
val g = Graph.entry(Pick)
  .add(Implement).add(FastGate).add(Repair).add(Review)
  .edge(Pick, Implement)(w => Some(w))
  .edge(FastGate, Review) { case Gated(w, Green) => Some(Round(w)); case _ => None }
  .cycle(FastGate, Repair, max = _.repairBudget) { case Gated(w, Red(log)) => Some(Job(w, log)); case _ => None }
  .edge(Repair, FastGate)(job => Some(job.attempt))
  .exit(FastGate) { case Gated(_, Red(_)) => Some(LoopExit.NothingMade); case _ => None }
  .done
```

Its type accumulates six parameters the consumer never writes:

```scala
Graph[Entry <: String, Reg <: Tuple, Es <: Tuple, Rs <: Tuple, Gs <: Tuple, -R]
```

`Reg` is the registry of `(name, outputType)` pairs, `Es` the edge pairs as literal string tuples,
`Rs` the reviewer names, `Gs` the guarded names, `R` the intersection of every node's requirement.
Verified: after a three node chain the compiler proves, through `summon`, that the accumulated type
is exactly `Graph["Pick", ?, (("Review","OpenPr"), ("Pick","Review")), "Review" *: EmptyTuple,
"OpenPr" *: EmptyTuple, HasGh & HasLog & HasGit]`, with zero type arguments written at any call.

Edges keep today's semantics exactly: every edge out of a node is an alternative, the first one
answering `Some` in declaration order is taken, and a value no edge accepts is an infra fault. An
`edge` call is only constructible when its carry function fits both endpoints; a mismatch is an
ordinary local error, verified on 3.8.3 as `Found: Int, Required: Work` at the carry expression,
not a wall of tuple types.

Deleted outright, exactly as the incumbent deletes them: `KitMacro` (1386 lines), `Plan`, `Edge`,
`Shape`, `Transition`, `Runner.validate`'s reachability half, `Role`, `TrustOf`, `Guard` as a
declared field, and all eleven paragraphs of authoring rules. A node may now be built by a `def`,
returned from a helper, aliased under two names, or constructed inline in the chain, because
identity is the name in the type, checked for duplicates at `add` (a second node named `"Gate"` is
a compile error naming the collision), not the runtime object the old `Plan.workflowOf` linked by
`eq`.

## What `done` proves, and with what machinery

Four checks run where the graph is sealed, all against the inferred registry, none against syntax.

1. **Totality.** Every declared node is reachable from the entry, and every node has at least one
   outgoing edge or exit. Failure reads `unreachable from the entry: node Repair`.
2. **Unique names.** Checked at each `add`, so the error points at the offending call.
3. **The review guarantee.** For every guarded node `g`, delete the reviewer nodes from the edge
   set and require `g` unreachable from the entry in the residual graph. This is exactly the
   statement that every path into `g` crosses a reviewer, reformulated so one reachability engine
   answers both this and totality. Failure reads `a path reaches guarded node Merge without
   crossing a reviewer`, verified to fire on a deliberately planted bypass edge and to stay silent
   on the shipped shape, cycles included.
4. **Declared reads.** For every edge that names an earlier node's output (next section), the named
   node must dominate the edge's source: cutting it must make the source unreachable. Failure
   reads `edge out of Review reads Repair, which is not on every path to it`, verified both ways.

What stays a runtime concern, honestly: which edge fires is value dependent and always will be, so
a node whose every edge declines its actual output is still a runtime infra fault, exactly as
today. The type level checks quantify over the shape, never over which value chose an edge; the
limit `Shape`'s scaladoc states about `Transition` applies here unchanged.

### The engine, and the measured ceiling

Two interchangeable engines were built and verified behind the same `done` signature.

**Match types.** `Contains`, `Step`, `Reach` with a fuel counter bounded by the node count, and
`Cut` for deleting the reviewer set, all over tuples of string literal types, with errors raised by
`inline if constValue[...] then ... else error("..." + constValue[N])`. This needs no macro at all
and produced the error messages quoted above. It works, and it does not scale. Measured wall clock
for the whole scratch project, cold `--server=false` compiles: 9 nodes with two cycles, about 2
seconds. 13 nodes, 2.8 seconds. 17 nodes, 7.4 seconds. 20 nodes, 43 seconds. 26 nodes was killed
unfinished after nine minutes. The blowup is the known match type re reduction pathology: `Reach`
passes its growing visited set unreduced into every `Contains`, and reduction results are not
shared, so work multiplies with depth. The shipped pipeline at 9 to 12 nodes sits comfortably under
the knee; a consumer graph of 20 nodes sits on it; anything larger is unusable.

**A macro over types.** Sixty lines, verified: an `inline def validate[Entry, Ns, Es, Rs, Gs]`
whose implementation decomposes the tuple types via `Type.of` and `ConstantType(StringConstant(s))`
extraction, runs a plain breadth first search at macro time, and reports through
`report.errorAndAbort` with the same message texts. Linear, instant at any size. This is a macro,
and the design says so plainly rather than hiding it, but it shares nothing with `KitMacro` except
the word: it reads the types inference already agreed on, never the consumer's trees, so there is
no literal plan rule, no val versus def rule, no canonicalisation, no alias analysis, and no way
for two spellings of one node to disagree, because a type is already canonical. The stale bloop
cache hazard `TEST.md` documents for macro edits applies to this file and must keep its
`--server=false` verification rule.

The recommendation is the macro engine as shipped, the match type engine kept in the test suite as
an executable proof that the design does not depend on macros for its soundness, only for its
compile time. If the macro is ever unacceptable, the fallback is real but capped near 15 nodes.

## Payload threading: declared reads instead of case class relays

Today a value a late edge needs must be copied through the output type of every node between its
producer and its reader; the incumbent fixes this with `withInput`, which reaches exactly one step
back. This design fixes it with reads declared on the edge:

```scala
.edgeReading(Review, OpenPr, reads = Pick) { (w: Work, j: Judged[Answer]) =>
  Some(PrRequest(w))    // w is Pick's output, typed, no threading through four nodes
}
```

The read is data: it lands in the edge's type and in the runtime edge record, so a rendering shows
it as a dashed arc, and `done` proves the named node sits on every path into the edge's source, by
the dominator reformulation above. The runner keeps one `Map[String, Any]` of the latest output per
node name along the walk; a read is a lookup, and the dominance proof is what makes the lookup
total, cycles included, since a dominator has necessarily run at least once before the reader on
every possible walk. Verified end to end: the typed read compiles with the value at the producer's
output type, and a read of a node that is not on every path is refused at compile time with the
message quoted earlier. A typed record variant (`rec("Pick")` over a type level map with a
membership guard) was also verified and works; declared reads are preferred because the reads are
then visible in the reified graph rather than buried in a lambda body, and because arity stays
explicit. The cost is one overload per read arity; two should ship, and a graph needing more reads
on one edge is a graph asking for an intermediate node.

Where the incumbent reaches one step back with `withInput`, a declared read reaches any dominator.
Where the incumbent's counter rides in the value through `loopWhile`, here the counter disappears
entirely, next section.

## Bounded cycles as data

A cycle today terminates because a counter rides in the payload and an edge predicate reads it,
invisible to the shape. The incumbent keeps that, with the counter in the value. Here a back edge
declares its bound:

```scala
.cycle(FastGate, Repair, max = _.repairBudget)(carry)
```

`max` is `Config => Int`, read at tick start. The runner owns the trip counter per tick; when the
bound is spent the edge simply declines, and the value falls through to the next alternative in
declaration order, which is where the author writes the exhaustion behaviour, a repair loop falling
through to `exit(... NothingMade)`, a review loop falling through to the fail safe `Changes`
routing. Three things follow. The `Attempt(work, n, log)` style case class whose only job was the
counter dies. The termination argument becomes visible in the reified shape rather than provable
only by reading a predicate body. And the dispatch budget becomes derivable: with every cycle
bounded and every node carrying `Cost`, `done` can compute the worst case dispatch count of the
whole graph as a `Config => Int`, which deletes the hand written formula
`docs/custom-loops.md` step 6 currently asks every consumer to derive and get wrong at their own
expense. `Cost` keeps its current meaning as an assertion the ledger checks at the moment of
dispatch, never a declaration the runner trusts; the derived budget is a ceiling computed from
assertions, so the ledger's charge at the capability stays, unchanged, as the enforcement.

## Least privilege: requirement parameters, not capture checking

The capability views are one line each, and `Caps` implements them all:

```scala
trait HasGh     { def gh: GitHub }
trait HasGit    { def git: Git }
trait HasAgents { def agents: AgentDispatch }
trait HasGates  { def gates: GateRunner }
trait HasFs     { def fs: HarnessFs }
trait HasLog    { def logger: Log }
// ... one per Caps field

final case class Caps( ... ) extends HasGh, HasGit, HasAgents, ...
```

A node body is a context function over the intersection it needs, `(HasGh & HasLog, Fault) ?=> ...`,
and the runner satisfies any such intersection with the one `given Caps` it already owns, since
`Caps` is a subtype of every view. All verified: resolution through the intersection works, the
builder accumulates `R1 & R2 & ...` across nodes, and a body that names a capability outside its
declared `R` fails with the compiler's ordinary message, `No given instance of type HasGh was
found`, at the exact expression. Exclusion is expressible without any new machinery:

```scala
def par[...](a: ..., b: ...)(using NotGiven[R1 <:< HasGit], NotGiven[R2 <:< HasGit])
```

verified to refuse a branch requiring the working tree with `No given instance of type
NotGiven[HasGit <:< HasGit]`; a dedicated evidence type with `@implicitNotFound` would word that
better and is a one liner.

Against the incumbent's capture checking, the honest comparison. Capture checking infers the
capability set from the body, so the author writes nothing and cannot understate; a requirement
parameter is declared, so an author can write `R = Caps` and opt out of the whole discipline, and
nothing but review culture stops them. That is the real advantage of the incumbent's choice and
this design does not pretend otherwise. Everything else favours requirements. They need no
experimental flag, so the proof does not rest on a compiler directive inside `loop.scala` that a
worker patch can delete; the incumbent's own risk section needs two mitigations for exactly that,
and here the entire deletable surface is `-Xfatal-warnings`, which this design does not depend on.
They survive being passed at plain types, where capture tracking silently erases, the incumbent's
own probe finding. Their errors are ordinary implicit search failures every Scala programmer has
seen, not `Int ->{git} Work` notation the authoring guide must teach. And because `R` is an
ordinary type parameter of a reified node, it composes with everything else here: the graph's
accumulated `R` is part of the sealed value, a rendering can print which nodes may push, and a test
can drive a node with a stub implementing exactly its views and nothing else, which is itself a
proof the declaration is not understated. Capture sets are erased and can do none of that. The
incumbent's own risk section names inferred requirement parameters as its fallback if capture
checking becomes untenable; this design simply starts on the fallback's stable ground and keeps
the reified graph the incumbent traded away.

## The review guarantee, compared honestly

`Judged` stays minted exactly as today, by the `final` `AgentDispatch.review` around a
`private[AgentDispatch]` constructor, and the incumbent's improvement of binding the mint to the
`Sandbox.Cold` profile rather than to the implementation's good behaviour is adopted as is. The
question is only where "guarded nodes run after review" is proved.

The incumbent proves it at one composition point: `afterReview` accepts a left side whose output is
`Judged[?]`, so the guarded flow is embedded behind a reviewer by construction. On a tree that is a
real proof, and a cheap one. But it is a local proof about one splice, and the incumbent has no
whole pipeline artifact on which a global statement could even be posed: past any `switch` the
shape lives in a closure, so no tool can enumerate what else reaches the guarded region, and a
guarded subflow value that leaks into a second context is a fact no signature states.

Here the proof is global. `done` quantifies over every edge of the sealed graph, cycles and shared
tails included: no path from the entry reaches any guarded node without crossing a reviewer,
checked by cutting the reviewers and demanding unreachability, and verified to catch a bypass edge
planted in an otherwise correct pipeline. The graph that is checked is the value that runs, so the
`Shape` drift `Kit.scala`'s docs spend paragraphs on cannot arise. It is also the check the current
`Runner.validate` runs at startup, moved to compile time and freed from the macro's authoring
rules, which makes it a strictly earlier catch of a strictly wider net.

What it is not, stated plainly: both designs prove shape, not data. `judged.map(_ => Approve)`
forges a verdict inside a genuine token in either design, a carry function can fabricate the value
it passes onward in either design, and a closure can smuggle a stale reviewed value in either
design. The claim is therefore: same depth of proof as the incumbent, wider scope, on a stabler
foundation, with one artifact where the incumbent has a construction discipline.

## Concurrency and scatter gather as data

A completed subgraph collapses into a node, which is the composition unit the current kit lacks:

```scala
val Green: Node["Green", Work, Work, HasGates & HasAgents & HasGit] =
  Graph.entry(FastGate). ... .done.asNode("Green")
```

Internal names are prefixed (`Green/Repair`) so uniqueness is preserved, and a rendering can show
the node collapsed or expanded. `par` and `fanOut` are then graph data, not combinators:

```scala
.par("Checks", left = LintG, right = AuditG)   // requires NotGiven on each branch's R, as above
.fanOut("FixAll", over = _.findings, each = FixOne, cap = _.fanOutCap)
```

Both are nodes wrapping subgraph values, so `lb watch` can draw the branches and the budget
derivation can count `each` at its declared `cap`. Execution is the incumbent's own plan, adopted:
virtual threads, join before fault, synchronised charging capability, branch ids on status events.
The exclusion of tree touching capabilities from `par` branches is the `NotGiven` evidence already
verified, standard Scala rather than capture checked allowlists.

## What the reified graph buys, audited against the repo

Claims of tooling value need the repository's own evidence, so each is graded.

**Watch topology, real.** `docs/custom-loops.md` step 7 makes every consumer hand declare a
`StageSet` and warns that a wrong `phase` string or a missed stage quietly degrades `watch`. A
sealed graph knows its nodes, its cycles (badge candidates), its entry and its exits; the
`StageSet` becomes derived output with an override hook, and the quiet degradation class dies.
`watch.sh` itself keeps parsing `status.jsonl` unchanged.

**Budget derivation, real.** Step 6's hand formula, with its own warning that an undercounted fan
out parks the tick, is replaced by the derived ceiling described above. This is the single most
concrete consumer facing win of reification.

**Pipeline rendering, real but small.** An `lb graph` subcommand printing the sealed graph as
mermaid costs an afternoon because the value is already there, and gives README and consumer PRs a
diagram that cannot rot. The incumbent cannot have this at all past the first `switch`.

**Dry run planning, speculative.** Which nodes a tick would visit is value dependent; today's dry
run is a node body decision (step 12) and stays one. Reification adds a static over approximation
at best; not claimed.

**Resume from node, mostly already owned.** Per node `probe` already makes a tick resumable
(step 3), and it does so on evidence in the world, which is stronger than replaying a recorded
position. Reification adds little here; not claimed.

**Diffing two pipelines, speculative.** litter-box has no consumers, the incumbent says so itself;
there is nobody to show a diff to yet. The value exists, structural diffing of two sealed graphs is
trivial, but it earns no weight in this decision.

## Testability under the standing constraint

Nothing here needs Docker, `gh` or credentials, and the split follows the repo's existing pattern.

* Compile time checks get negative compilation tests as real, separately compiled files in a
  scratch consumer package, asserted through `scala-cli`, the exact discipline `TEST.md` already
  mandates over `typeCheckErrors` snippets, with `--server=false` mandatory around the macro
  engine for the stale cache reason `TEST.md` documents.
* The match type engine, kept as the macro's executable specification, is tested by the same
  negative files compiled against it.
* `TestWorld` implements the same views `Caps` does, so `runGraph` and `runNode` survive with new
  type parameters and old semantics; the testkit boundary story (`Judged` minting inside the
  package, `test.dep` scoping, the startup tripwire) is untouched because the mint is untouched.
* Least privilege becomes testable positively: a node declaring `HasGh & HasLog` is driven by a
  stub world implementing exactly those two views, which no test can do today because every node
  takes the whole `Caps`.
* Runner semantics, first `Some` wins, bounded cycle exhaustion falling through in declaration
  order, read map population, derived budget equals ledger behaviour, fan out charging, are
  ordinary `TestWorld` scenario tests with goldens. Golden churn concentrates where the incumbent's
  does, in the shipped pipeline rewrite commit.

## Worked example: the shipped pipeline

Compiling shaped Scala 3.8.3 against the surface this document defines. Both loops are bounded, and
neither bound rides in a payload.

```scala
//> using dep in.rcard::litter-box:0.11.0

import in.rcard.litterbox.*

final case class Work(issue: Int, branch: String)
final case class Gated(work: Work, verdict: GateVerdict)
final case class Round(work: Work)
final case class Ask(question: String)
final case class PrRequest(work: Work)          extends RequiresReviewInput
final case class CiPassed(work: Work, pr: Int)  extends RequiresReviewInput

enum Answer derives Decoder:
  case Approve
  case Changes(findings: List[String])
  case Question(ask: String)

val Pick = Node("Pick", Cost.NoDispatch, Timeout.Unbounded,
  probe = _ => None,
  run   = (_: Tick) => (r: HasGh & HasLog, f: Fault) ?=>
    r.gh.nextReady() match
      case None    => NodeOutcome.Stopped(LoopExit.Idle)
      case Some(i) => NodeOutcome.Done(Work(i, s"us-$i")))

val Implement = Node.dispatch("Implement",         // adopted from the incumbent: profile named,
  in     = Sandbox.Worktree,                       // prompt built trusted/untrusted, patch staged
  prompt = (w: Work) => Prompt.from(Template.Iterate)
    .trusted("GATE", _.cfg.gateCmd)
    .untrusted("ISSUE", r => r.gh.issueBody(w.issue)))
  // : Node["Implement", Work, Staged, HasAgents & HasFs & HasGh & HasCfg]

val FastGate = Node.gate("FastGate")               // : Node["FastGate", Work, Gated, HasGates & HasGit & HasCfg]

val Repair = Node.dispatch("Repair",
  in     = Sandbox.Worktree,
  prompt = (g: Gated) => Prompt.from(Template.Fix)
    .trusted("GATE", _.cfg.gateCmd)
    .untrusted("GATE_LOG", r => g.verdict.logText(r.fs)))

val Review = Node.dispatch("Review",
  in     = Sandbox.Cold,                           // the profile that mints Judged
  as     = Decoder.json[Answer],
  prompt = (rd: Round) => Prompt.from(Template.Review)
    .trusted("CONVENTIONS", r => r.fs.conventions())
    .untrusted("DIFF", r => r.git.diffCachedOriginMain())
    .untrusted("ISSUE", r => r.gh.issueBody(rd.work.issue)))
  // : Node["Review", Round, Judged[Decoded[Answer]], HasAgents & HasGit & HasGh & HasFs]

val Verify = Node.dispatch("Verify",
  in     = Sandbox.Question,                       // code and tools, no patch channel
  prompt = (a: Ask) => Prompt.from(Template.Verify).untrusted("QUESTION", _ => a.question))

val CommitPush = Machine.CommitAndPush             // shipped nodes, reused
val OpenPr     = Machine.OpenPr                    //   input PrRequest extends RequiresReviewInput
val CiWait     = Machine.CiWait
val Merge      = Machine.Merge                     //   input CiPassed extends RequiresReviewInput

val pipeline = Graph.entry(Pick)
  .add(Implement).add(FastGate).add(Repair)
  .add(Review).add(Verify)
  .add(CommitPush).add(OpenPr).add(CiWait).add(Merge)

  .edge(Pick, Implement)(w => Some(w))
  // Implement's output is what the patch guard concluded; the Work it was
  // given is read back off Pick by declaration, not threaded through Staged
  .edgeReading(Implement, FastGate, reads = Pick) { (w, s) =>
    s match { case Staged.Ok(_) => Some(w); case _ => None } }
  .exit(Implement) { case Staged.Ok(_) => None; case _ => Some(LoopExit.NothingMade) }

  // repair loop: bound owned by the runner, exhaustion falls through to the exit below
  .edge(FastGate, Review) { case Gated(w, GateVerdict.Green) => Some(Round(w)); case _ => None }
  .cycle(FastGate, Repair, max = _.repairBudget) {
    case g @ Gated(_, GateVerdict.Red(_)) => Some(g); case _ => None }
  .edge(Repair, FastGate)(g => Some(g.work))
  .exit(FastGate) { case Gated(_, GateVerdict.Red(_)) => Some(LoopExit.NothingMade); case _ => None }

  // review loop: the reviewer may consult the fixer, three rounds, then fail safe
  .cycle(Review, Verify, max = _ => 3) { j => j.value match
    case Decoded.Ok(Answer.Question(q)) => Some(Ask(q)); case _ => None }
  .edgeReading(Verify, Review, reads = Pick)((w, report) => Some(Round(w)))
  .edge(Review, CommitPush) { j => j.value match
    case Decoded.Ok(Answer.Approve) => Some(j); case _ => None }
  .exit(Review)(_ => Some(LoopExit.NothingMade))   // Changes, Malformed, or rounds exhausted

  // publish tail: guarded, and reads Pick's Work by declaration instead of threading it
  .edgeReading(CommitPush, OpenPr, reads = Pick)((w, _) => Some(PrRequest(w)))
  .edge(OpenPr, CiWait)(pr => Some(pr))
  .edge(CiWait, Merge)(ci => Some(ci))
  .exit(Merge)(_ => Some(LoopExit.Success))

  .done   // totality, unique names, review guarantee, read dominance, derived budget

@main def loop(args: String*): Unit = LitterBox.run(LitterBox.graph(pipeline), args)
```

What `done` proves here: every node reachable; `OpenPr`, `CiWait`, `Merge` guarded by their input
types and unreachable once `Review` is cut, so the planted bypass class of bug cannot compile; the
`CommitPush` edge's read of `Pick` is total because `Pick` dominates everything; the worst case
dispatch count, one implement, `repairBudget` repairs, four reviewer dispatches, three verifier
dispatches, is a derived `Config => Int` no human wrote. `Pick` can read GitHub and the log and
provably nothing else; `Review` cannot touch the working tree; a reviewer body that tried
`r.git.push()` is a missing given at the exact line. The two counters that today ride inside
`Attempt` and `ReviewRound` do not exist.

## Migration

Ordered so the suite is green after every step.

1. **Views on `Caps`.** Ten one line traits, `Caps extends` them. Purely additive.
2. **The typed kit beside the old.** `Node` with `N` and `R`, the builder, both check engines, the
   bounded cycle runner, declared reads, `asNode`, `par`, `fanOut`. The runner's walk is today's
   `Runner.run` generalised from `Plan` derived edges to the sealed graph's edges; erasure casts
   stay confined to the one `advance` style function, as today.
3. **Negative compilation harness.** The scratch consumer package files and the `--server=false`
   wiring, before anything depends on the checks.
4. **The shipped pipeline as one sealed graph**, goldens rewritten in this commit alone. This is
   the big commit, as it is in the incumbent's plan; `Machine.scala`'s node bodies mostly survive
   with narrowed context signatures.
5. **Adopt the orthogonal incumbent pieces**, prompts, decoder, nonce, sandbox profiles, the
   consulting reviewer, unchanged from that design.
6. **Delete the old kit**: `KitMacro`, `Plan`, `Edge`, `Shape`, `Transition`, `Runner.validate`,
   `Role`, `TrustOf`, and the macro sections of `docs/custom-loops.md`.
7. **Testkit and docs.** `runGraph`/`runNode` over the new types; the authoring guide loses steps
   2, 5, 6, 9 and 10 in their current form and gains one page on reading the compile errors.

Rough cost against the incumbent's own plan: the same order of work, the same two heavy commits
(the kit, the shipped rewrite). This design spends extra on the check engines and their negative
tests, and saves the capture checking work, the `World^` resurfacing of every capability, the
testkit's capture typed mirror, and the two mitigations for deletable compiler flags.

## Honest weaknesses

**The match type engine hits a wall, measured.** 2 seconds at 9 nodes, 43 at 20, killed at 26.
The shipped pipeline is safely inside the knee, but the pure no macro story is only true for small
graphs, and the design must either ship the type reading macro or accept a documented ceiling.
That macro is sixty lines against `KitMacro`'s 1386 and reads types rather than trees, but the
sentence "we replaced the macro" is honestly "we replaced the macro with a vastly smaller and
rule free one, plus a macro free engine that proves the semantics and caps out early".

**`R` is declared, not inferred.** A node author can write `R = Caps` and keep today's everything
bundle; least privilege here is a discipline the types can express and check but not force into
existence, where capture checking infers it from the body with no opt out. The mitigating facts:
the width of `R` is visible in every diff and renderable from the sealed graph, and a too narrow
`R` cannot happen, only a too wide one. But the incumbent is genuinely stronger on this one axis,
at the price of an experimental flag and erasable tracking.

**Shape proof, not data proof.** The review guarantee quantifies over edges, not values.
`judged.map` forgery, fabricated carry values and closure smuggling survive, exactly as they
survive the incumbent and today's kit. Wider scope than `afterReview`, same depth.

**Inference is the foundation, and inference can be cut.** A builder value ascribed at a widened
type (`val g: Graph[?, ?, ?, ?, ?, Caps]`) loses the registry. Verified behaviour is a loud
failure, `done` refuses a non literal entry rather than passing silently, which is the right
failure direction, but the guide must say "seal the graph in one expression or let the type be
inferred".

**Type noise at the seams.** The sealed graph's type is a wall of tuples in IDE hovers and in any
signature that must name it. The worked example never names it, `LitterBox.graph(pipeline)` erases
it into `LoopGraph` at the front door, and every consumer facing error message was verified to be
short and named, but a consumer who writes a helper function over graphs will meet the raw type.

**Error quality is good where verified, unproven elsewhere.** The five failure modes shown above
all produce one line, named, correctly located messages, and that is real evidence. What was not
survey tested is the long tail: a carry lambda whose inference fails mid chain can report against
the accumulated builder type, and match type stuck states, if any check is written carelessly,
report as unreduced types. The negative compilation suite has to grow with every such report.

**Names are types now.** Renaming a node touches its literal type, every `edgeReading` that names
it, and the golden logs. Names were already load bearing in the log contract, so this is a
smaller change than it sounds, but rename is no longer a string edit.

**Two engines is a maintenance tax.** Keeping the match type engine as the macro's executable
specification is principled and costs upkeep; dropping it quietly would leave the macro
unspecified. This is a standing tension, not a solved problem.

**`edgeReading` arity.** One overload per read count. Two ship; a third is a smell the guide
should call an intermediate node.

## Verification appendix

All scratch files compiled on Scala 3.8.3, temurin 21, `scala-cli 1.16.0`, `--server=false`.

* Literal `N` inference, abstract `(R, Fault) ?=>` bodies, contravariant `R`, intersection
  satisfaction by one `given`, and the three negative shapes (edge type mismatch, `par` branch
  exclusion via `NotGiven`, missing capability) with their exact messages: verified.
* Builder accumulation of all six type parameters with zero annotations, including match type
  classification of `Judged` outputs and `RequiresReviewInput` inputs in both directions, pinned
  by `summon` against the expected literal tuple types: verified.
* `Reach`/`Cut` engine: totality true and residual reachability false on the shipped shape with
  both cycles; named errors on an orphan node and on a planted bypass edge: verified.
* Dominator reformulation for reads, positive and negative, and the typed record variant with a
  membership guarded `apply`: verified, including the initially missed stuck match type hole,
  which compiled silently until the explicit `HasKey` guard was added; that hole is why every
  check in this design must end in `constValue` or `error`, never in a type left to maybe reduce.
* Compile time scaling of the match type engine: 9, 13, 17, 20 nodes at 2.0, 2.8, 7.4, 43
  seconds; 26 nodes killed after nine minutes.
* The type reading macro: literal extraction from tuple types, BFS, `errorAndAbort` with named
  messages, both checks, both directions: verified, instant.
