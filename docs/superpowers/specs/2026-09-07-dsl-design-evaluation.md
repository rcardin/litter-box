# Evaluation of the five loop authoring designs

Status: judgment, written 2026-09-07 against the incumbent (`2026-09-07-litter-box-dsl-design.md`)
and alternatives A through D. Every load bearing claim spot checked below was checked against the
repository at commit `8c41f61` or recompiled under scala-cli 1.16.0 on Scala 3.8.3, cold, with
`--server=false`, in scratch directories under `/Users/rcardin/.claude/jobs/420cb9ae/tmp/judge/`.
No tracked file was modified. The project's test suite was not run.

## 1. Fact check findings

### Claims verified true

**Line counts and inventories, claimed by B, C and D.** `KitMacro.scala` is 1386 lines, `Kit.scala`
is 1660, `Machine.scala` is 3531. There are exactly 28 golden logs under `test/golden/`.
`GraphMacroSpec` is 1063 lines, `GraphValidationSpec` 960, `ConsumerGraphSpec` 1305. All exact.
`test/ReviewFixLoopExample.scala` declares exactly the six carrier types D names (`Work`,
`ReviewRound`, `Reviewed`, `FixRound`, `PrRequest`, `PrOpened`) and its `Fix` node fans out one
dispatch per finding as a `foldLeft` at line 393, as D says.

**B's match type scaling numbers, the ones most worth distrusting, reproduce.** I recompiled B's own
scratch engine cold with `--server=false`: the 13 node stress file took 2.9 seconds wall against
B's claimed 2.8, and the 20 node file took 45.4 seconds against B's claimed 43. The blowup between
13 and 20 nodes is a factor of fifteen, exactly the knee B describes. I did not rerun the 26 node
kill test, but with the curve confirmed at two points the claim is credible. B measured honestly.

**A's phantom encoding claims reproduce.** A's own probes recompile clean on stock 3.8.3. The
negative case fails with exactly `Cannot prove that probe1.kit.GitHubCap <:< probe1.kit.GitCap`, as
quoted. The inline leak probe compiles, confirming both halves of what A asserts: the encoding
proves privilege at signatures, and a narrower context lambda cannot hide a wider outer given. A
reported its own weakness accurately, including the claim it attributes to itself about sealed
inline regions being unachievable in its encoding. That claim is true: the leak probe demonstrates
it, and only capture checking closes it.

**The incumbent's capture checking mechanics are real on 3.8.3.** I wrote a fresh probe. With
`-language:experimental.captureChecking`, a step annotated `^{w.gh}` whose body calls `w.git.push`
fails with `capability `w.git` cannot flow into capture set {w.gh}`, near verbatim the wording the
incumbent quotes, and the path dependent granularity between `{w.gh}` and `{w.git}` is genuinely
distinguished. The mechanism claims are honest. One caution: unlike A, B and C, the incumbent left
no scratch files behind, so its findings rest on my reverification rather than on its own receipts.

**C's compilation claims reproduce.** C's `machine_core.scala`, the full nine phase skeleton with
narrowed `using` clauses and both bounded loops, compiles clean under
`-Werror -deprecation -feature -unchecked` on 3.8.3. A match missing one enum case is a hard error
under `-Werror`, confirmed with a fresh scratch. C's quoted repository facts are verbatim: the
`Guard` scaladoc in `Kit.scala` really says the merge guarantee "is a DATA dependency, not a
reachability property"; the dry run stop point in `Machine.pickAndSetup` sits where C cites it;
`ARCHITECTURE.md` really states that `protect` is a floor and `.litter-box/**` is always covered;
the `Ledger`'s eight thread overspend comment exists at `Kit.scala` line 1194.

**D's repository readings hold.** The shipped graph genuinely cannot use `LitterBox.graph`
(`ARCHITECTURE.md` records the `def` built node idiom as exactly what the macro cannot read), the
`Workflow` and `Shape` drift allowance is real, `startInput` was genuinely narrowed with the
"spends nothing here" comment at `LitterBox.scala` line 457, and D's scratch model of deny
wrappers compiles. D's central factual attack on the incumbent is examined next, and it holds.

### Claims found false or overstated, by spec

**Incumbent, overstated, the flagship sentence.** "`Judge` may not: a reviewer that tried to push
would not compile." False as the design is written, on two independent grounds. First, the
incumbent's own `Review` node reads `w.git.diffCachedOriginMain()` at line 348 of the spec, so the
`Judge` flow captures `{w.git}`, and capture tracking is trait grained: a body holding `{w.git}`
can call `w.git.push` and the compiler has nothing to say. D caught this and D is right. Making
the sentence true requires splitting `Git` into a read half and a mutate half, which no part of the
incumbent's design or migration plan mentions. Second, the incumbent's own findings say capture
sets are inferred and enforced only where annotated, and the worked example writes no annotation on
`Judge` at all, so even the trait grained restriction is not being asked for.

**Incumbent, a safety bug in the worked example, previously uncaught by any alternative.** The main
flow ends `.andThen(Judge).afterReview(Publish)`, and `Publish` is
`CommitAndPush andThen OpenPr andThen CiWait andThen Merge`. `Judge` returns `Judged[Answer]` for
every verdict, `Approve` and `Changes` alike, and `afterReview` demands only `O <:< Judged[?]`.
Nothing between `Judge` and `Publish` routes on what the reviewer said. As written, a `Changes`
verdict flows into commit, push, PR and auto merge. The example therefore violates the fail safe
direction the same document preaches, and it silently drops the shipped pipeline's needs human
path. `afterReview` proves a review happened, never what it concluded, and the incumbent's own
example demonstrates how easy that confusion is to write.

**A, overstated, the same trait granularity trap.** "`judge`'s helpers provably cannot push."
`consultFixer` holds `AGENTS & FS` and indeed cannot. But `reviewOnce` demands
`Ctx[AGENTS & GIT & GH & FS & CFG]` because it needs the diff, and A's `Cap.GIT` gates the whole
`Git` trait, push included. Half the claim is false for exactly the reason the incumbent's version
is false. A's `Cap` roster has no read and write split.

**B, overstated, same trap again.** "`Review` cannot touch the working tree; a reviewer body that
tried `r.git.push()` is a missing given at the exact line." B's own worked example annotates
`Review` as `Node["Review", Round, Judged[Decoded[Answer]], HasAgents & HasGit & HasGh & HasFs]`,
and B's `HasGit` is `{ def git: Git }`, the whole trait. With `HasGit` in scope, `r.git.push()`
resolves fine. The negative message B quotes is real only for a node that never needed the diff.

Only two specs handle this honestly. C actually splits `GitRead` from `GitWrite`, so its version of
the sentence is true, verified in its compiled skeleton. D states outright that the sentence is not
yet true under any design until the trait is split, and that the split is the same one commit of
capability wiring for everyone. That is the correct statement of the situation.

**B, a quiet behavioural regression in the worked example.** On `Answer.Changes` B's pipeline exits
`LoopExit.NothingMade`. The shipped loop opens a needs human audit trail PR on that path. Not a
false claim, since B never claims parity, but the example presents itself as "the shipped pipeline"
and is not. A's example similarly ends `NeedsHuman` without opening the audit PR. C's example is
the only one that preserves the `PublishNeedsHuman` audit trail behaviour.

**Minor.** C cites the dry run stop at `Machine.scala` line 748; it is at 752 in the current file.
The recurring "eleven paragraphs" figure for the macro rules is a loose count of step 9 of
`docs/custom-loops.md` (an intro, five readable forms, six refusals, a closing rule); close enough
that nobody should be dinged for it. D's reference to issue #73 tracking the `Role` seam was not
verifiable without `gh` and is left unverified.

## 2. Comparison across the repository's own criteria

**Strength and honesty of the compile time proofs.** The question the repo itself poses, through
its threat model and through the `Guard` scaladoc, is which party a proof constrains and whether
it survives the conditions the project actually runs under, including a harnessed agent editing the
repository it works on. The incumbent's capture proofs are the strongest inside a lexical region
and the weakest under the threat model: experimental, erased at runtime, silently void in any file
compiled without the flag, and dependent on directives in `loop.scala` that the incumbent's own
risk section says an agent can delete. The patch guard's `.litter-box/**` floor does protect that
file from the worker patch channel, which the incumbent undersells and C correctly credits, but a
proof that needs two runtime mitigations to stay true has conceded D's point that the runtime
control is the load bearing part. A's signature proofs and B's requirement parameters are stable
stock Scala and survive everything except a deliberate cast; both are declared rather than
inferred, so both are disciplines the compiler checks rather than facts it discovers. C's proofs
are the same species as A's and B's for least privilege, but its review guarantee is a different
and better species: `PublishMerge(work, approval: Judged[Answer])` is unbuildable without a token,
which is precisely the "data dependency" form the repository's own `Guard` documentation spends a
page explaining that reachability checking cannot express and that the shipped graph cannot state
about itself. That is the one place any of these designs makes the repo's central guarantee
stronger rather than merely cheaper. D offers no compile time privilege proof at all and is honest
about it; in exchange its withheld capability is the only guarantee on offer that survives hostile
bytecode, a cast, or a deleted flag, because the denied object is absent rather than forbidden.

**Authoring ergonomics for the real audience.** The audience is fixed by `docs/custom-loops.md`: an
operator taught to write a `val`, a case class and an `Option`. C asks that person for one enum,
one match and plain functions, with the two worst error messages being `not a member of` and
`No given instance`. A asks for step scopes, a `Grant` algebra and boundary based exits; clean, but
three new kit concepts. B asks for a fluent builder whose accumulated type is a wall of tuples the
moment a consumer writes a helper over graphs, with good errors where B verified them and admitted
unknowns in the long tail. The incumbent asks for combinators plus capture notation
(`Int ->{git} Work`) that its own risk section says the guide must teach. D asks for nothing new at
all. Ranking for this audience: D, then C, then A, then B, then the incumbent.

**Expressiveness needed today versus anticipated.** Nothing in the repository demands declared
concurrency: issue #69's decline of a second thread is recorded on `Timeout.After`, the `Git` trait
documents the working tree as serial, and the shipped pipeline has no two independent steps. The
scatter and gather ask is already shipped as a fold in the worked example, metered by the ledger.
Subgraph composition is real and is blocked by the macro alone; D collects it free by deleting the
macro, and every other design also gets it. The one genuinely new capability everyone wants, the
reviewer that consults a fixer, is orthogonal to representation and every spec adopts it in the
same form. So the honest expressiveness scoreboard is nearly flat; the incumbent's `par` and
`fanOut` are anticipatory, and the incumbent's own `par` design quietly contradicts the recorded
reasoning of issue #69 about node bodies and shared state.

**Error message quality.** Verified best in C (ordinary type errors, exhaustivity naming the missing
phase). A close behind (`Cannot prove that GH <:< GIT` is legible). B good at the five verified
failure points, unproven in the tail, with the stuck match type hazard B itself documents. D keeps
today's runtime messages plus new named denial faults. The incumbent is last by its own admission.

**Dependence on experimental compiler features.** Only the incumbent signs the public 0.x surface
onto `experimental.captureChecking` for the life of the line. A, C and D rest fatal warning
totality on a deletable flag but degrade to a runtime rc 50 fault rather than to silence; B does
not depend on the flag at all but reintroduces a macro, sixty lines reading types rather than
trees, and must keep the `--server=false` discipline `TEST.md` exists to enforce.

**Migration cost.** D is smallest by far: five commits, net negative lines, no golden moves until
the honest grants audit, testkit untouched. C is a real rewrite but the smallest of the three
rewrites: no combinator interpreter, no capture typed `World`, no type level registry; one heavy
goldens commit, one deletion commit that removes the macro plus roughly three thousand lines of
graph tests. A is comparable to C plus a new enforcement layer (per thread step scope tracking,
instrumented capability methods, a `Grant` vocabulary that must mirror `Caps` forever). B and the
incumbent are the two heavy builds, and B adds the standing two engine maintenance tax it admits.

**Testability under no Docker, no gh, no credentials.** All five pass. C and D are the cheapest:
ordinary `TestWorld` walks plus a small negative compilation suite. B needs the largest negative
compilation harness and inherits the macro cache hazard. The incumbent's negative tests assert
capture checker behaviour, the most fragile thing to pin across compiler upgrades. The testkit
boundary story (the `Judged` mint inside the package, `test.dep` scoping, the startup tripwire) is
untouched by C and D, resurfaced by the incumbent and A.

**What is irreversibly lost.** A and C delete the reified shape: no rendering, no derived
`StageSet`, no derived worst case budget, no future static checks over a value. Against today that
loses nothing the repo has (the `StageSet` is already hand declared, the budget already hand
derived), but it forecloses B's two real wins, which are the only concrete consumer facing payoffs
of reification anyone demonstrated. The incumbent loses the whole pipeline value too, past the
first `switch`, while paying graph prices everywhere else. D loses nothing and gains nothing on
this axis. B is the only design under which the shape becomes worth more than it is today.

## 3. The adversarial read

Every author advocated. Where each solved the assigned problem rather than the project's:

**The incumbent's three complaints, audited.** The macro complaint is fully real; every design
including D agrees and deletes it. The privilege complaint is real. The expressiveness complaint is
mostly manufactured: concurrency was affirmatively declined by this repository with reasons written
down, scatter and gather ships today as a fold, and the missing subgraph composition needs only the
macro's deletion, not a new model. The incumbent then spends its two new proofs on the mechanism
least at home in this codebase, an experimental erased feature guarded by deletable flags, and its
worked example both overstates the privilege proof and contains the one genuine safety bug found in
any of the five documents. The design reads as a solution built around a feature the author wanted
to use.

**A** asked the sharpest question in the pile, whether the graph is the mistake, and answered it
with unusual honesty; its verification section is a model. What it underplays is that its runtime
tripwire is a whole second enforcement system (per thread current step tracking, every capability
method instrumented, a grant vocabulary in permanent lockstep with `Caps`), which is D's mechanism
wearing A's types, and that its stage declaration drift gets worse when step names come from call
sites with no registry at all.

**B** was assigned "keep the graph, kill the macro" and delivered it, including the measurement
programme the others should envy. But the honest summary of B is its own sentence: the macro is
replaced by a smaller macro plus an engine that provably cannot scale, and the design's distinctive
payoffs, derived budgets and derived stage sets and mermaid output, are conveniences the repository
has lived without, purchased with the most exotic type machinery of the five and a permanent two
engine upkeep. B also repeats the false reviewer sentence and quietly drops the needs human path.

**C** was assigned interpreters and had the discipline to reject its own assignment on repository
evidence, documenting why the tagless flavour fails this codebase and which half of the state
machine flavour (serialised resume) the repo has already refused once. That is judging against
reality. Its residual softness is real too: the grant site is a match a reviewer reads, not a fact
the compiler derives, and the phase case class tax is reduced, not removed.

**D** argues from the repository better than anyone and its fact checking of the incumbent is the
single most valuable paragraph across the five documents. Its blind spot is treating today's
authoring model as adequate once the macro dies: the bind once identity rule survives as a startup
check, `Role` stays cramped, the first `Some` edge ordering stays a rule to teach, the shipped
graph keeps its two hand written statements that nothing stops from drifting, and above all the
`Guard` apparatus keeps mis stating the project's central guarantee, a defect the repo's own
scaladoc describes at length and D leaves standing. Minimalism is the right instinct at the wrong
moment: the project has zero consumers, which is the one time a representation change is nearly
free, and D's own argument that goldens and testkit are expensive to churn gets stronger every
release, which means "not now" quietly becomes "never".

## 4. Recommendation

**Adopt C, the sealed phase machine, as an explicit hybrid: C's `Phase` enum, total `step` and
narrowed capability views including the `GitRead` and `GitWrite` split; D's runtime deny wrappers
added behind those views at the dispatcher, so a withheld capability is absent at runtime as well
as unnameable at compile time; and the orthogonal modules every candidate already agrees on from
the incumbent, the `Prompt` builder with trusted and untrusted slots, the nonce fenced `Decoder`,
the sealed sandbox profiles with `Sandbox.Question`, the restated meaning of `Judged`, and the
deletion of `Role` for per phase configuration.**

Why C over the field, in the repository's own terms. First, it is the only design that turns the
guarantee this project exists for into the form the project's own documentation says is the true
one: a `Judged` carried in a phase constructor is a data dependency, checkable by the stock
compiler, expressible for the shipped pipeline itself, which the current reachability apparatus
cannot honestly guard and never has. Second, it buys the real fixes, macro deletion, least
privilege at every handler signature, honest totality, on nothing but stable Scala with the best
verified error messages of the five, so the kit's public surface signs onto no experimental
feature and no new macro. Third, it fits the audience and the tier rules: one enum and one match is
the smallest conceptual surface of the three rewrites, `Live.scala` and `TestWorld` change no
lines, and the whole thing is testable through the existing capability seam exactly as `TEST.md`
demands. D's deny wrappers compose with it naturally because C already hands each phase a narrowed
view; wrapping what is withheld costs one commit and closes the cast residual C itself flags.

What this pick gives up, plainly. The reified shape dies, permanently: no rendering, no derived
stage set, no derived worst case budget, no future static analysis over a graph value, and if the
project ever wants those back the answer is a second reification effort, not a patch. Reachability
validation dissolves into dead code harmlessness. The goldens are rewritten once, the testkit gains
`runMachine` and consumers of `runGraph` would break if any existed, which today they do not. The
phase case class tax survives, smaller than today but real. And the incumbent's one categorical
advantage, sealing an inline lexical region by inference with no opt out, is declined; under this
design an author who hands a handler the whole `World` has opted out of their own proof, visibly.

Sequencing, stated because it is honest rather than as a hedge: land the orthogonal security
modules first (prompt builder, nonce decoder, `Judged` restatement), since they are representation
independent and are the highest value per line in any of these documents; then the capability
splits; then the phase machine and the one goldens commit; then the macro and graph deletion; then
D's deny wrappers as hardening. If the programme stalls after the first step, the project has still
banked the security improvements on the current kit, which is exactly D's fallback position.

## 5. Runner up

**D.** Switch to it if either condition arrives. First, if the golden rewrite plus testkit surface
change is judged unaffordable in the near term, because the loop's behavioural work (the consulting
reviewer, the prompt and nonce hardening, the sandbox profiles) should not wait behind a
representation rewrite; D delivers the macro deletion, runtime least privilege and free subgraph
composition for a net negative diff with no golden churn, and every one of the incumbent's
orthogonal modules lands on the current kit unchanged. Second, if a first real consumer appears
before the rewrite starts, at which point D's no migration property stops being a convenience and
becomes the responsible choice.

One further trigger points elsewhere and should be recorded: if shape derived tooling ever becomes
a demanded feature rather than an imagined one, a rendered pipeline for consumer PRs, a derived
`StageSet`, a derived dispatch ceiling, then B, not D, is the design to reopen, because it is the
only candidate under which the reified shape earns more than it costs, and its measurements were
the most rigorously honest work in the pile.
