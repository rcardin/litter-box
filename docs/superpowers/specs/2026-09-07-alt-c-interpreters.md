# Alternative C: the loop as an interpreted phase machine

Status: design, alternative to `2026-09-07-litter-box-dsl-design.md` (the incumbent `Flow[I, O]`
combinator tree with capture checked capabilities). Not implemented. Every Scala claim below marked
verified was compiled against Scala 3.8.3 with `scala-cli compile --server=false`; the scratch files
are described in the Verification section.

## Why

Same three problems as the incumbent, restated for this shape:

**Authoring costs too much.** The `Plan` and `Edge` model plus `KitMacro`'s literal reading rules
cost eleven paragraphs of authoring guide, and an edge sees nothing but the output of the node it
leaves, so facts are threaded through case classes written for no other purpose.

**Missing expressiveness.** No declared concurrency, no scatter and gather, no subgraph composition.

**Weak proof surface.** Every node receives the whole `Caps` bundle, so a node that only reads an
issue can force push, and only one property is proved (review reachability, by macro plus walk).

The assignment for this alternative: an interpreter architecture. The consumer writes a pure
description; a runner interprets it. Two flavours were investigated. The finding, stated up front
because it shapes everything below: **litter-box already is an interpreter architecture, one tier
down.** `Machine` and every node body are pure decision logic over the `Caps` traits, and the two
interpreters that exist are `Live.scala` and the published `TestWorld`. The question is therefore
not whether to adopt interpretation but whether to add a second interpretable layer above the one
the repo already has. For flavour 1 the answer turns out to be no, and the evidence is in the repo
itself. What survives from both flavours combines into the design proposed here: **a sealed `Phase`
enum plus one total `step` function, driven by the runner, with least privilege expressed by which
narrowed capability traits each phase handler's signature demands.** No macro, no experimental
compiler feature, no `F[_]`.

## Flavour 1 investigated: the loop as an algebra

The shape: the loop instruction set as tagless final traits, a consumer pipeline as a program in
that algebra, and least privilege as which algebras the program's type demands.

```scala
trait GitHubAlg[F[_]]:
  def nextReady: F[Option[Int]]
trait DispatchAlg[F[_]]:
  def implement(prompt: String): F[String]

def pickAndImplement[F[_]: Monad](using gh: GitHubAlg[F], d: DispatchAlg[F]): F[Option[Work]]
```

This compiles on 3.8.3 with a hand rolled `Monad` (verified), and the least privilege claim is
real: a program typed over `GitHubAlg` alone provably cannot push. Four things sink it for this
repo.

**The multiple interpreter payoff was checked against what the repo actually has and wants, and
it is mostly absent.**

* *Dry run.* litter-box's dry run is a runtime stop point, not a plan printer:
  `Machine.pickAndSetup` returns `LoopExit.DryRun` when `cfg.dryRun` is set, after the read only
  work and before the first mutation (`src/Machine.scala` line 748, and `docs/custom-loops.md`
  step 12 tells a consumer to do the same in their own first node). Nothing in the repo asks for a
  printed plan. And a monadic program cannot honestly provide one: an interpreter cannot see past
  `flatMap` without a value to feed it, so a dry run or costing interpreter must fabricate answers
  and can only ever print one path, not the plan. This was verified concretely: the recording
  interpreter in the scratch (`Rec`) has to invent `Some(1)` for `nextReady` to make the trace
  continue at all. The incumbent's reified `Flow` tree genuinely can be printed; a monadic algebra
  program genuinely cannot. Claiming that pair of interpreters as the architecture's payoff would
  be claiming value the shape cannot deliver.
* *Cost estimation.* The budget is already enforced better than an estimator could promise:
  `Runner.Ledger` meters every real dispatch at the capability, whatever `Cost` a node declared
  (`src/Kit.scala`, `charging`). What a costing interpreter could add is deriving the
  `dispatchBudget` formula consumers currently hand compute (`docs/custom-loops.md` step 6,
  `1 + MaxRounds * (1 + cfg.repairBudget)`), and it hits the same `flatMap` wall: a data dependent
  loop's cost is not statically summable. Weak value.
* *Resume.* This repo's resume philosophy is explicit and repeated: state is re derived from the
  world, never stored. `Caps.scala` on `parkedIssues`: parking is "never a stored position", and
  on `prForBranch`: a crashed tick is recognised "by asking GitHub, never by a stored position
  (RFC #26 decision 6)". Node `probe`s are the mechanism. A replayable program log adds a second
  source of truth the repo has deliberately refused once already. Anti value.
* *Recording test interpreter.* `TestWorld` already records every interaction at the capability
  seam, is a published artifact (`in.rcard::litter-box-testkit`), and scenario tests already
  assert on call sequences and `phaseSeq`. A program level recorder is a second copy of an
  existing facility.

**The one dependency rule bites.** `CONVENTIONS.md`: one runtime dependency, and adding one is a
design decision. No cats, no zio. The `Monad`, its syntax extensions and every algebra would be
hand rolled kit tier code and therefore public supported surface a consumer's errors mention.

**The ergonomics tax is real and was measured.** Two mistakes a consumer will actually make were
compiled. The natural for comprehension that forgets `Option` sits inside `F` yields
`Found: (i : Option[Int]) Required: Int`, which is survivable; but calling a program without
naming `F` where two interpreters are in scope yields
`Ambiguous given instances: both object given_Monad_Id ... and object given_Monad_Rec ... match
type T of parameter x of method summon`, and every such message names `F[_]` machinery the
audience of `docs/custom-loops.md` has never seen. That guide teaches its reader step by step how
to write a `val` and a case class; it is written for an operator wiring a loop, not a Scala
library author. A kit whose error messages require understanding higher kinded type inference is
the wrong kit for that reader.

**What survives from flavour 1** is the load bearing idea, separable from the monad: least
privilege as a fact about a signature's context. In Scala 3 direct style, "which algebras the
program demands" is spelled "which capability traits the function takes as `using` parameters",
and that needs no `F[_]`, no `Monad`, and no experimental flag. The design below keeps exactly
this and drops the rest.

## Flavour 2 investigated: the resumable state machine

The shape: `sealed trait Phase`, a total `def step(p: Phase): StepOut[Phase]`, the runner drives
it, the state is a serialisable value.

Two of its three promises check out against the repo and one is rejected.

* *Totality as exhaustivity*: verified. A `step` match missing one phase fails compilation under
  `-Werror` with `match may not be exhaustive. It would fail on pattern case:
  Phase.Consult(_, _, _)`, naming the exact missing case. This is a strictly better authoring
  error than either the current macro's five failure messages or the incumbent's capture notation.
* *A trivially testable pure transition function*: verified in shape. `step` is one function from
  a value to a value, over capabilities `TestWorld` already fakes; a test walks it one transition
  at a time or end to end and asserts on the phase sequence, the same way `phaseSeq` already
  asserts on status events.
* *Persisting the state gives crash resume and `lb watch` for free*: **rejected.** Resume in this
  repo is re derivation from the world (RFC #26 decision 6, cited above); a serialised phase file
  is a stored position that can lie about a world that moved while the process was dead, which is
  precisely why the repo refuses stored positions. And `lb watch` is fed by `status.jsonl`
  events, which a phase machine emits the same way the current graph does; it gets nothing from a
  state file. So the serialisation half of flavour 2 is dropped, and resume stays what it is
  today: `begin` and the world reading equivalents of `probe` reconstruct the right phase to
  start from by asking GitHub and git, not a file.

## The decision

Flavour 2's spine carrying flavour 1's constraint idea: **a state machine of direct style
capability programs.** The consumer writes a sealed `Phase` enum (the pure description), a total
`step` function whose match arms hand each phase to a handler, and each handler's signature
demands only the narrowed capability traits it uses (the proof). The runner interprets: it owns
the walk, the ledger, the timeouts and the fault channel. Interpretation of effects stays at the
capability seam where this repo already does it, `Live` for production and `TestWorld` for tests,
and no second interpretable layer is added above it.

## The core model

Kit tier, replacing `Plan`, `Edge`, `Transition`, `Shape`, `Next`, `Workflow`, `KitMacro`,
`checkedShape`, `checkedPlan`, `Runner.validate`, `Trust`, `TrustOf`, `Guard`,
`RequiresReviewInput` and `Role`:

```scala
enum StepOut[+P]:
  case Continue(next: P)
  case Stop(exit: LoopExit)

final case class StepPolicy(cost: Cost, timeout: Timeout)

LitterBox.machine[P](
  name           = "my-loop",
  begin          = (tick: Int) => (f: Fault) ?=> firstPhase,        // may fault, no Caps, as today
  step           = (p: P) => (w: World, f: Fault) ?=> stepImpl(p),
  policy         = (p: P, cfg: Config) => StepPolicy(...),          // cost and timeout per phase
  dispatchBudget = (cfg: Config) => ...,
  stages         = myStages
): LoopGraph
```

`World` is today's `Caps` under a friendlier name, unchanged in wiring, plus narrow view
supertraits added in `Caps.scala`:

```scala
trait GitHubRead  { def oldestReadyIssue(): Option[Int]; def issueBody(i: Int): String; ... }
trait GitHubWrite { def createPr(...): String; def editLabels(...): Boolean; ... }
trait GitHub extends GitHubRead, GitHubWrite      // LiveGitHub and TestWorld satisfy it unchanged

trait GitRead  { def diffCachedOriginMain(): String; def statusClean(): Boolean; ... }
trait GitWrite { def push(b: String): Unit; def commit(m: String): Unit; ... }
trait Git extends GitRead, GitWrite
```

`AgentDispatch` splits along the incumbent's sandbox profile line instead: the worktree worker,
the cold reviewer (the only `Judged` minter, unchanged), and the question fixer are three
capabilities, so a handler that holds only the reviewer capability cannot dispatch a patch
producing worker. `LiveGit`, `LiveGitHub` and `TestWorld` change not one line; only the types
things are surfaced at change, the same migration property the incumbent claims for its step 2.

The runner's walk is the whole interpreter:

```scala
@tailrec def walk(p: P): LoopExit =
  emitTransition(p)                       // status.jsonl only, and only if stages were declared
  if !ledger.canAfford(policy(p, cfg).cost) then park(p)
  else stepWithChargedCapsAndTimeout(p) match
    case StepOut.Continue(next) => walk(next)
    case StepOut.Stop(exit)     => exit
```

`Runner.Ledger` and the `charging` decorator survive verbatim: every real dispatch is charged at
the capability at the moment it happens, a refusal on the review path can never mint a `Judged`,
and the ledger is already synchronised for concurrent branches (`src/Kit.scala`, `Ledger`'s own
doc records the eight thread hammer test). `canAfford` parks an honest `Cost.OneDispatch` phase
before it runs, as today. Timeouts stay post hoc per transition, as today. A `MatchError` escaping
a `step` whose author deleted `-Werror` and shipped a non total match is caught by the runner and
raised as an infra fault, rc 50, tick abandoned.

## Least privilege, concretely

The consumer's `step` is the grant table and the handlers are the proofs:

```scala
def step(p: Phase)(using w: World, f: Fault): StepOut[Phase] = p match
  case Phase.Pick                => pick(using w.gh, f)                       // GitHubRead only
  case Phase.Implement(work)     => implement(work)(using w.gh, w.agents, f)
  case Phase.Review(work, r, ev) => review(work, r, ev)(using w.cfg, w.git, w.gh, w.reviews, f)
  ...

def pick(using gh: GitHubRead, f: Fault): StepOut[Phase] = ...
```

`pick` provably cannot push: nothing in its scope has a write method. Verified on 3.8.3, no
experimental flag, and the two violation shapes produce these exact errors:

* naming a write method on a read view:
  `value createPr is not a member of GitHubRead`
* calling a helper that demands a capability the handler was not granted:
  `No given instance of type GitHubWrite was found for parameter gh of method helperNeedsWrite`

Compared against the incumbent's capture checking:

* **No experimental flag is needed.** Context parameters over ordinary traits are stable Scala.
  The incumbent's proof rests on `-language:experimental.captureChecking` inside
  `.litter-box/loop.scala` and inherits that feature's instability across compiler versions; this
  one rests on nothing but the standard type checker.
* **The error messages are ordinary.** `not a member of` and `No given instance` are messages the
  audience has already met; the incumbent concedes its own notation (`Work^'s1`,
  `Int ->{git} Work`) has to be taught.
* **The proof is written, not inferred.** Capture sets are inferred and a node author annotates
  only where restriction is wanted; here every handler writes its `using` clause. That is more
  ceremony per handler, and it is also the audit trail: the privilege of every phase is readable
  off one signature, and the grant of every phase off one match arm.
* **Erasure.** Both approaches erase: capture sets vanish at runtime, and a narrow view is the
  same live object at a supertype, recoverable by `asInstanceOf`. Neither proof is a runtime
  fence. The difference is what degradation looks like. The incumbent's stated risk is that a
  harnessed agent deletes the compiler flags from `loop.scala` and least privilege silently
  becomes nothing, mitigated by protecting the file and by `lb` forcing the flags. Here least
  privilege does not depend on any flag at all; deleting `-Werror` degrades only totality, and
  only from a compile error to a runtime rc 50 fault, never to silence. The file protection
  mitigation, for what it still guards, already exists: `protect` is a floor and `.litter-box/**`
  is always covered (`ARCHITECTURE.md`, configuration layering), so `loop.scala` is already a
  path no worker patch may touch. A hostile edit that widens a handler's own `using` clause is
  visible in exactly the diff the cold reviewer reads, one line, in a protected file.
* **The honest limit.** The dispatcher itself holds the full `World`, so the grant site is
  audited rather than compiler forced: the compiler proves a handler cannot exceed what its
  signature demands, and a human (or the reviewer) reads the ten line match to see what each
  phase was handed. A cast recovers everything, and that is accepted the way the repo already
  accepts package injection (`Caps.scala`, `AgentDispatch` doc, third residual): a deliberate
  hostile act by the loop author, not an accident, and greppable. Handing out genuinely separate
  wrapper objects per view would close the cast at the price of a wrapper class per capability;
  declined for now, revisitable without changing any signature.

Totality is proved the same way the incumbent proves it, with the same flag: the scaffolded
`loop.scala` carries `-Werror`, and an uncovered phase is a compile error naming the missing case
(verified, quoted above). Reachability is where this design honestly gives ground: a phase no arm
ever continues to is dead code, flagged by nothing. It is also harmless dead code, a case that
never runs, not a hole a walk can fall through; the incumbent gets unreachability excluded by
construction and this design merely gets it made inert.

## The review guarantee

Three parts, two unchanged and one improved.

**Minting is unchanged.** `AgentDispatch.Judged` stays mintable only by the sealed capability's
`final review`, with `mint` scoped `private[AgentDispatch]` (`src/Caps.scala`). The incumbent's
improvement is adopted with it: the token binds to the `Sandbox.Cold` profile, so cold isolation
is demanded by the signature rather than chosen by `LiveAgentDispatch`.

**Guarding becomes a data dependency, which is the stronger form.** A guarded phase carries the
token as a field:

```scala
case PublishMerge(work: Work, approval: Judged[Answer])
```

`PublishMerge` cannot be constructed without a `Judged`, and a `Judged` cannot exist without a
cold dispatch, so every path into the merge handler crossed a reviewer, proved by ordinary type
checking of an ordinary constructor. No macro, no `Shape` walk, no `Guard` enum, no
`RequiresReviewInput` marker, no `afterReview` combinator. This is precisely the property
`Kit.scala`'s own `Guard` doc says the current apparatus cannot state: "`Merge` never runs
unreviewed, but that guarantee is a DATA dependency, not a reachability property", genuinely out
of reach for a reachability check because `Transition` carries no branch discriminator. The
shipped graph's real shape, where `OpenPr` is also reached by legitimate unreviewed rejection
paths, is expressible for the first time without curating a validator: `PublishMerge` demands the
token and `PublishNeedsHuman` does not, two phases instead of one node reached two ways.

**The residuals are today's residuals, restated, not new ones.** `judged.map(_ => Approve)`
still forges a verdict inside a genuine token (`Judged`'s own doc owns this). The testkit still
mints by design, controlled by artifact scoping plus the startup tripwire (`Caps.scala`, fourth
residual; `Main.refuseTestkitOnClasspath`). One new consequence is stated rather than hidden: a
tick that crashes between review and merge has lost its token, and `begin`'s re derivation cannot
reconstruct one, so the resumed tick reviews again. That costs one dispatch and fails in the safe
direction, which is the direction this codebase always chooses. And one runtime check becomes
unnecessary: `Runner.step`'s `Trust.Reviewed` class check existed because trust was stamped from
a declared type covariance could lie about; here no trust is stamped anywhere, the token itself
travels as data, and its class is real by construction.

## What is adopted from the incumbent unchanged, and what changes shape

These pieces are orthogonal to the program representation and the incumbent's designs for them
are adopted as written: **sandbox profiles** (`Worktree`, `Cold`, `Question`, the sealed set, the
`Question` fixer with no patch channel), **the prompt builder** (`trusted` and `untrusted`
substitutions, fence neutralisation, size caps, single pass), **structured answers**
(`Decoder`, `Decoded.Ok` or `Malformed`, `Judged[Decoded[A]]` and never the reverse), **the nonce
fence rule** (one marked block exactly, zero or many is `Malformed`, defeats echo forgery and
does nothing against persuasion), and **per phase configuration keyed by name** replacing `Role`
(`agent.model.Implement`, `Implement_CMD`).

Two pieces change shape here:

**Concurrency** needs no capture checking. The kit offers `par` and `Par.all` whose branches are
context functions over a restricted bundle that omits the working tree and the gate:

```scala
final case class ParSafe(gh: GitHubRead, git: GitRead, agents: WorkerDispatch, fs: HarnessFs)
def par[A, B](a: ParSafe ?=> A, b: ParSafe ?=> B)(using World): (A, B)
```

Branches run on virtual threads and are joined; a fault in any branch fails the step after
joining. A branch that touches the tree is refused by ordinary typing, verified:
`value push is not a member of GitRead`. The ledger is already synchronised, so a shared budget
cannot be double spent (existing `Ledger` doc and test). Branch ids in `status.jsonl` and
`watch` grouping are the same open scope item the incumbent names.

**Scatter and gather** is code, not shape. A fixer looping over findings is
`Par.all(findings)(f => fix(f))` inside one handler, metered per dispatch by the ledger like
everything else. The incumbent reifies this as a declared `fanOut` the shape can see; this design
has no shape to see it with, and says so plainly in the weaknesses section rather than pretending
the loss away. What the phase machine does reify is the boundary that matters to the runner: each
gate round, review round and consult round is its own transition, so bounds, budget refusals and
status events land per round, not per hand written inner loop.

## Ergonomics, honestly assessed

The audience is fixed by `docs/custom-loops.md`: an operator who is walked through writing a
`val`, a case class and an `Option`, with a checklist at the end. Not a Scala library author.

What this design deletes from that person's life: the entire step 9 rule set (top level `val`s
only, no `def`s, no inline nodes, no exports, no instance qualified receivers, canonicalisation
collisions), the literal `Plan` requirement and its five macro error messages, the `Shape` and
stage drift concerns, `Cost` versus actual dispatch honesty bookkeeping at the node level (policy
is one function, and the ledger enforces regardless), and the whole edge ordering rule ("first
edge answering `Some` wins"). What replaces them: one enum, one match, plain functions. The
compiler's own two error messages, quoted above, are the worst thing a consumer sees, plus the
exhaustivity error that names the phase they forgot.

What this design does not delete: the case class tax at phase boundaries. A counter threaded
through a cycle still rides in the phase value (`Gate(work, attempt)`), because the phase value
is the only thing that survives a transition. The improvement over today is where the boundaries
sit: a handler is plain code, so everything between two dispatches is local variables, and a
phase is declared only where the author wants a budget, timeout, status or resume boundary. The
incumbent's `withInput` combinator solves the same tax differently; neither design removes it.

There is no monadic plumbing anywhere: no `F[_]`, no for comprehension over an abstract effect,
no `Monad` in the kit, no inference of higher kinded types. That entire tax category, measured in
the flavour 1 investigation above, is avoided by construction.

The `using` clause per handler is a real cost: a consumer writes `(using gh: GitHubRead, f:
Fault)` where today they write one `summon[Caps]`. The lazy escape hatch stays open on purpose: a
handler may take the whole `World` and forfeit its own least privilege proof without forfeiting
anyone else's, so the guide can teach narrowing as the second lesson rather than the first wall.

## Worked example: the shipped pipeline

`PICK → IMPLEMENT → GATE → REPAIR → REVIEW → PR → CI → MERGE`, with the bounded repair loop and
the bounded review consults fixer loop. The phase enum, handlers, dispatch match, both bounds and
the walk compile verbatim on 3.8.3 under `-Werror` against mirrored capability traits (see
Verification); `Node.dispatch`, `Prompt`, `Decoder` and `LitterBox.machine` are this design's
API and are compiling shaped, not yet compiled.

```scala
//> using dep in.rcard::litter-box:0.10.0
//> using options -Werror

import in.rcard.litterbox.*

final case class Work(issue: Int, branch: String)

enum Answer derives Decoder:
  case Approve
  case Changes(findings: List[String])
  case Question(ask: String)

val MaxRounds = 3

enum Phase:
  case Pick
  case Implement(work: Work)
  case Gate(work: Work, attempt: Int)
  case Repair(work: Work, attempt: Int, gateLog: String)
  case Review(work: Work, round: Int, evidence: Option[String])
  case Consult(work: Work, round: Int, question: String)
  case PublishMerge(work: Work, approval: Judged[Answer])   // unbuildable without a review
  case PublishNeedsHuman(work: Work, findings: List[String])
  case CiWait(work: Work, pr: Int, approval: Judged[Answer])

def step(p: Phase)(using w: World, f: Fault): StepOut[Phase] = p match
  case Phase.Pick                     => pick(using w.gh, f)
  case Phase.Implement(work)          => implement(work)(using w.cfg, w.gh, w.fs, w.workers, f)
  case Phase.Gate(work, n)            => gate(work, n)(using w.cfg, w.gates, f)
  case Phase.Repair(work, n, log)     => repair(work, n, log)(using w.cfg, w.fs, w.workers, f)
  case Phase.Review(work, r, ev)      => review(work, r, ev)(using w.cfg, w.git, w.gh, w.fs, w.reviews, f)
  case Phase.Consult(work, r, q)      => consult(work, r, q)(using w.questions, f)
  case Phase.PublishMerge(work, ok)   => publishMerge(work, ok)(using w.git, w.gh, f)
  case Phase.PublishNeedsHuman(wk, x) => publishNeedsHuman(wk, x)(using w.git, w.gh, f)
  case Phase.CiWait(work, pr, ok)     => ciWait(work, pr, ok)(using w.cfg, w.gh, w.hostGates, f)

def pick(using gh: GitHubRead, f: Fault): StepOut[Phase] =
  gh.oldestReadyIssue() match
    case None    => StepOut.Stop(LoopExit.Idle)
    case Some(i) => StepOut.Continue(Phase.Implement(Work(i, s"us-$i")))

def implement(work: Work)(using cfg: Config, gh: GitHubRead, fs: HarnessFs,
    d: WorkerDispatch, f: Fault): StepOut[Phase] =
  val prompt = Prompt.from(Template.Iterate)
    .trusted("GATE", cfg.gateCmd)
    .trusted("CONVENTIONS", fs.conventions())
    .untrusted("ISSUE", gh.issueBody(work.issue))
  d.dispatch(Sandbox.Worktree, prompt) match
    case Staged.Ok(_) => StepOut.Continue(Phase.Gate(work, attempt = 0))
    case _            => StepOut.Stop(LoopExit.NothingMade)   // empty or rejected patch

def gate(work: Work, attempt: Int)(using cfg: Config, g: GateRunner, f: Fault): StepOut[Phase] =
  runGate(g) match
    case GateVerdict.Green                                   =>
      StepOut.Continue(Phase.Review(work, round = 1, evidence = None))
    case GateVerdict.Red(_) if attempt >= cfg.repairBudget   =>
      StepOut.Stop(LoopExit.NothingMade)                     // repair budget exhausted
    case GateVerdict.Red(log)                                =>
      StepOut.Continue(Phase.Repair(work, attempt + 1, log))

def repair(work: Work, attempt: Int, gateLog: String)(using cfg: Config, fs: HarnessFs,
    d: WorkerDispatch, f: Fault): StepOut[Phase] =
  val prompt = Prompt.from(Template.Fix)
    .trusted("GATE", cfg.gateCmd)
    .untrusted("GATE_LOG", fs.read(gateLog))
  d.dispatch(Sandbox.Worktree, prompt)
  StepOut.Continue(Phase.Gate(work, attempt))                // back to the gate, counter intact

def review(work: Work, round: Int, evidence: Option[String])(using cfg: Config, git: GitRead,
    gh: GitHubRead, fs: HarnessFs, r: ReviewDispatch, f: Fault): StepOut[Phase] =
  val prompt = Prompt.from(Template.Review)
    .trusted("PROTECTED", cfg.protect.mkString("\n"))
    .trusted("CONVENTIONS", fs.conventions())
    .untrusted("DIFF", git.diffCachedOriginMain())
    .untrusted("ISSUE", gh.issueBody(work.issue))
    .untrusted("EVIDENCE", evidence.getOrElse(""))
  val judged: Judged[Decoded[Answer]] = r.review(Sandbox.Cold, prompt, Decoder.json[Answer])
  judged.value match
    case Decoded.Ok(Answer.Question(q)) if round < MaxRounds =>
      StepOut.Continue(Phase.Consult(work, round, q))
    case Decoded.Ok(Answer.Approve)                          =>
      StepOut.Continue(Phase.PublishMerge(work, judged.map(_ => Answer.Approve)))
    case Decoded.Ok(Answer.Changes(fs0))                     =>
      StepOut.Continue(Phase.PublishNeedsHuman(work, fs0))
    case Decoded.Ok(Answer.Question(_))                      =>   // out of rounds: fail safe
      StepOut.Continue(Phase.PublishNeedsHuman(work, List("reviewer out of consult rounds")))
    case Decoded.Malformed(_)                                =>   // unreadable: fail safe
      StepOut.Continue(Phase.PublishNeedsHuman(work, List("unreadable review answer")))

def consult(work: Work, round: Int, question: String)(using q: QuestionDispatch,
    f: Fault): StepOut[Phase] =
  val report = q.ask(Sandbox.Question, Prompt.from(Template.Verify).untrusted("QUESTION", question))
  StepOut.Continue(Phase.Review(work, round + 1, evidence = Some(report)))

def publishMerge(work: Work, approval: Judged[Answer])(using git: GitWrite, gh: GitHubWrite,
    f: Fault): StepOut[Phase] =
  git.commit(s"#${work.issue}"); git.push(work.branch)
  val pr = gh.createPr(work.branch, s"#${work.issue}", "approved by cold review")
  StepOut.Continue(Phase.CiWait(work, prNumber(pr), approval))

def publishNeedsHuman(work: Work, findings: List[String])(using git: GitWrite,
    gh: GitHubWrite, f: Fault): StepOut[Phase] =
  git.commit(s"#${work.issue} needs-human"); git.push(work.branch)
  gh.createPr(work.branch, s"#${work.issue}", findings.mkString("\n"))
  StepOut.Stop(LoopExit.NeedsHuman)                          // audit trail PR, no merge

def ciWait(work: Work, pr: Int, approval: Judged[Answer])(using cfg: Config, gh: GitHubWrite,
    host: HostGateRunner, f: Fault): StepOut[Phase] =
  if watchChecks(host, pr) && gh.merge(pr, ciLog(pr)) == 0 then StepOut.Stop(LoopExit.Success)
  else StepOut.Stop(LoopExit.NeedsHuman)

val graph: LoopGraph = LitterBox.machine[Phase](
  name           = "shipped",
  begin          = tick => Phase.Pick,
  step           = step,
  policy         = (p, cfg) => p match
    case _: Phase.Implement => StepPolicy(Cost.OneDispatch, Timeout.After(cfg.iterTimeout))
    case _: Phase.Repair    => StepPolicy(Cost.OneDispatch, Timeout.After(cfg.iterTimeout))
    case _: Phase.Review    => StepPolicy(Cost.OneDispatch, Timeout.Unbounded)
    case _: Phase.Consult   => StepPolicy(Cost.OneDispatch, Timeout.Unbounded)
    case _                  => StepPolicy(Cost.NoDispatch, Timeout.Unbounded),
  dispatchBudget = cfg => 1 + cfg.repairBudget + 2 * MaxRounds,
  stages         = shippedStages)

@main def loop(args: String*): Unit = LitterBox.run(graph, args)
```

Both bounds are visible in the shape: the repair loop is `Gate` and `Repair` cycling on `attempt`
against `cfg.repairBudget`, and the consult loop is `Review` and `Consult` cycling on `round`
against `MaxRounds`, out of rounds falling to `PublishNeedsHuman`, the same fail safe direction
as a malformed answer. `Merge` is reachable only through `CiWait`, only through `PublishMerge`,
only with a `Judged` in hand. `pick` provably touches nothing but GitHub reads. `review` holds no
write capability at all: a reviewer that tried to push would not compile, and the error names the
missing member.

## Migration

Order of work, each step ending with the suite green.

1. **Narrow views.** The read and write supertraits in `Caps.scala`, plus the dispatch split
   along sandbox profiles. Pure widening: `LiveGitHub`, `LiveGit` and `TestWorld` implement the
   same combined traits and change nothing.
2. **The machine runner.** `StepOut`, `StepPolicy`, the `@tailrec` walk with `canAfford`,
   `charging` and the post hoc timeout, built beside the current `Runner`, reusing `Ledger` and
   `charging` as they are. `LitterBox.machine` returning a `LoopGraph`, alongside
   `LitterBox.graph`.
3. **Testkit.** `TestWorld.runMachine(graph)` and a transition trace (`List[String]` of phase
   names) beside `runGraph`; `runNode` gains a `runPhase` sibling. Consumer facing surface, so
   README's testkit section moves with it.
4. **Prompts, decoding, profiles.** The `Prompt` builder, `Decoder`, `Decoded`, the nonce
   contract, and the third sandbox script for `Sandbox.Question`. Identical to the incumbent's
   steps 3 and 4; nothing here is representation specific.
5. **The shipped pipeline as phases.** `Machine.shippedWorkflow`'s twelve nodes become one enum
   and one match; `begin` keeps the resume aware budget seed and the world derived resume
   reconstruction `LitterBox.shipped.begin` already owns. Golden logs rewritten in this commit,
   alone.
6. **The reviewer consults a fixer.** Three way answers, the bounded consult loop, budget
   accounting for the extra dispatches.
7. **Delete the old kit** in one commit: `Plan`, `Edge`, `Transition`, `Shape`, `Next`,
   `Workflow`, `KitMacro`, `checkedShape`, `checkedPlan`, `Runner.validate`, `Trust`, `TrustOf`,
   `Guard`, `RequiresReviewInput`, `Role`, `policyOf`, `forRole`. This deletes `KitMacro.scala`
   entirely (1386 lines) and most of `GraphMacroSpec` (1063 lines), `GraphValidationSpec` (960
   lines) and `ConsumerGraphSpec` (1305 lines); the review guarantee tests are rewritten as
   ordinary negative compilation tests (a `PublishMerge` built without a `Judged` does not
   compile) plus `TestWorld` walks.
8. **Docs.** `docs/custom-loops.md` rewritten around phases; every section that exists to explain
   the macro disappears with the macro.

Relative to the incumbent the kit build is smaller (no `Flow` interpreter, no capture checked
`World`, no `par` capture allowlist machinery, no experimental flag support burden) and the
deletion is larger (the macro and its three biggest test files go outright rather than being
replaced by a combinator equivalent). The test churn in step 7 is the biggest single cost either
design pays, and it is roughly equal between them.

## Honest weaknesses

* **There is no reified shape at all.** Nothing can draw this graph, count its phases, or check
  reachability; a dead phase is silent dead code. The incumbent's `Flow` tree can be walked,
  printed and validated; the current kit's `Plan` can at least be checked at startup. This design
  trades every shape level analysis for the deletion of the machinery that made shapes checkable,
  and bets that the two properties worth proving (least privilege, review before publish) are
  better proved by signatures and data than by walks. If litter-box later wants a plan printer, a
  simulator or shape diffing, this design has nothing to offer.
* **The grant site is audited, not enforced.** The `step` dispatcher holds the full `World`; the
  compiler proves each handler cannot exceed its signature, but which capabilities each phase is
  handed is a fact a reviewer reads off the match, not one the compiler derives. And every narrow
  view is cast defeatable, accepted under the same reasoning as the package injection residual.
* **The multi interpreter promise of the assigned architecture is deliberately abandoned.** The
  investigation concluded the repo neither has nor wants program level dry run, costing, replay
  resume or recording, and a monadic representation could not deliver the static ones anyway.
  This design should be judged as what it is, a simplification of the existing machine over the
  existing capability interpreters, not as an interpreter tower.
* **Totality rides on `-Werror` in a consumer owned file.** Mitigated the same two ways the
  incumbent proposes (the protect floor already covers `.litter-box/**`, and `lb` can force the
  flags when it drives the compile), and the degradation is a runtime rc 50 fault rather than
  silence. Still weaker than a proof no flag can remove.
* **The phase data tax is reduced, not removed.** Counters and carried facts still live in phase
  case classes; there is no `withInput` equivalent because there is no combinator layer for one
  to live in. A consumer with many boundary crossing facts writes wide phase constructors.
* **Scatter and gather is invisible to the shape**, because there is no shape. `Par.all` inside a
  handler is metered and type restricted but not declared, so `watch` sees its dispatch events
  and nothing structural. The branch id work for `status.jsonl` is inherited from the incumbent
  unsolved.
* **A twelve phase `step` match is a long function.** The handler convention keeps each arm to
  one line, but nothing enforces that convention, and a consumer who writes bodies inline in the
  match forfeits both readability and the per handler privilege proof at once.
* **A crashed tick reviews again.** The token does not survive the process, by design; the cost
  is one cold dispatch per crash between review and merge, and the resume aware budget seed has
  to account for it.
* **The testkit minting residual is unchanged**, as is everything downstream of it.

## Verification

Compiled with `scala-cli compile --server=false` on `//> using scala 3.8.3`, scratch files under
`/Users/rcardin/.claude/jobs/420cb9ae/tmp/`, none touching the repo:

* `machine_core.scala`: the full phase machine skeleton of the worked example (mirrored
  capability traits, `Judged` with a private constructor, nine phases, the dispatcher, all nine
  handlers with narrowed `using` clauses, both bounded loops, the `@tailrec` walk) compiles clean
  under `-Werror -deprecation -feature -unchecked`.
* `neg_privilege.scala`: both least privilege violations fail with the exact messages quoted in
  the least privilege section.
* `neg_totality.scala`: a dispatcher missing the `Consult` arm fails under `-Werror` with the
  exhaustivity error quoted above; without `-Werror` it is a warning and a runtime `MatchError`.
* `par_check.scala`: `par` over a `ParSafe ?=> A` branch type compiles, and a branch calling
  `git.push` fails with `value push is not a member of GitRead`.
* `tagless.scala`: the flavour 1 program compiles with a hand rolled `Monad`, an `Id` interpreter
  and a recording interpreter; the recorder demonstrably must fabricate `Some(1)` to trace past
  the first `flatMap`. The two consumer mistakes and their error messages quoted in the flavour 1
  section were compiled and captured verbatim.

Not compiled, because they are design surface rather than mechanism: `LitterBox.machine`'s exact
factory signature, `Prompt`, `Decoder`, the sandbox profile enum. Each reuses a mechanism either
verified here or already living in the repo.

Everything testable stays testable without Docker, `gh` or credentials: the review guarantee
becomes negative compilation tests plus `TestWorld` walks, transition semantics and budget
charging are ordinary `TestWorld` tests, and the nonce contract is the same decoder unit test
matrix the incumbent lists.
