# Alternative A: a direct style loop with no graph

Status: design alternative, drafted 2026-09-07 against the incumbent proposal in
`2026-09-07-litter-box-dsl-design.md`. Not approved, not implemented. Every Scala claim below was
compiled on Scala 3.8.3 with scratch files; the Verification section lists what was checked.

## Why

The incumbent design replaces one reified graph with a better reified graph: `Flow` combinators
instead of a `Plan` the macro reads. This document explores the sharper question the incumbent does
not ask: whether the graph itself is the mistake.

Look at what the reified graph is actually for, today and in the incumbent. Sequencing, branching
and looping are things Scala already does with `;`, `match` and recursion, and both designs rebuild
them as data (`Edge` lists today, `switch` and `Flow.rec` tomorrow) purely so a runner can walk the
result. The payload threading tax, the eleven paragraphs of macro authoring rules, `withInput`, the
case class per edge, the `on(input)` plumbing inside `switch` arms: every one of these exists to
smuggle ordinary lexical scoping back into a world that gave it up. A direct style method has all of
it for free. A value a later step needs is a `val` in scope. A loop counter is a parameter of a
recursive `def`. A branch is a `match`, and the compiler's exhaustivity check is the totality proof.

The honest question is then what the reification buys that plain code cannot, and the answer is
shorter than either graph design admits: a shape that can be inspected before and without running.
Everything else the runner needs, status events, the budget ledger, timeouts, fault routing,
`lb watch`, golden logs, resume, is already obtained from the capabilities and from a thin scope
around each unit of work, because that is how the current runner obtains them too: `Runner.step`
wraps a node body with a clock read, a charging decorator over `AgentDispatch` and a status emit,
none of which reads the graph. This design keeps exactly that wrapper, renames it `step`, and
deletes the graph around it.

## What this design changes

The consumer's loop becomes one ordinary function. `LitterBox.run` calls it once per tick, inside a
`boundary` that gives it a typed early exit, and hands it a `World` whose only verbs are `step`,
`scatter`, `publish` and a read only `cfg`. Capabilities exist only inside a `step` scope, delivered
at a phantom typed context that makes least privilege a compile time fact at every function
signature, with no experimental flag.

Deleted outright: `Plan`, `Edge`, `Transition`, `Shape`, `Next`, `Workflow`, `Flow`, `Node` as a
value, `KitMacro`, `Runner.validate`, `startInput`, and every authoring rule that served the macro.
Kept, unchanged in mechanism: `Runner.Ledger` and the charging decorator, `Fault`, `Cost`,
`Timeout`, `LoopExit`, `PatchGuard`, `Reply`, the golden log contract, `status.jsonl` and the
`StageSet` declaration `watch` reads.

Adopted from the incumbent verbatim, because they are orthogonal to the graph question: sandbox
profiles (`Worktree`, `Cold`, `Question`), the `Prompt` builder with `trusted` and `untrusted`,
`Decoder` and `Decoded`, the nonce fence rule, the three way reviewer answer with a bounded consult
loop, and the deletion of `Role` in favour of per step configuration keyed by step name.

## Non goals

No shape exists before a run, so nothing here renders a pipeline diagram from the code, and this
design does not pretend a trace viewer is the same thing. The losses are named in full below rather
than papered over.

No backward compatibility, same as the incumbent: litter-box has no consumers yet.

Not a policy DSL. The vocabulary is steps and capabilities, and the loop domain words live in the
shipped helpers, not in the kit.

## The core model

Three types replace the graph.

```scala
final class World private[litterbox] (...):
  val cfg: Config
  def step[R, A](
      name:    String,
      grant:   Grant[R],
      cost:    Cost = Cost.NoDispatch,
      timeout: Timeout = Timeout.Unbounded,
      probe:   Ctx[R] ?=> Option[A] = None
  )(body: Ctx[R] ?=> A): A
  def scatter[R, A, B](name: String, grant: ParGrant[R])(items: List[A])(f: A => Ctx[R] ?=> B): List[B]
  def publish(token: AgentDispatch.Judged[?]): Publish

final class Ctx[+R] private[litterbox] (...)   // the capability holder inside one step
final class Grant[+R] private (...)            // which capabilities a step is handed
```

`Ctx[+R]` is one object holding every wired capability, covariant in a phantom type `R` that names
the set the holder may touch, as an intersection of empty marker traits, one per capability:

```scala
object Cap:
  sealed trait GH; sealed trait GIT; sealed trait AGENTS; sealed trait GATES
  sealed trait HOSTGATES; sealed trait FS; sealed trait STATUS; sealed trait NOTIFY
  sealed trait CLOCK; sealed trait LOG; sealed trait CFG

extension [R](c: Ctx[R])
  def gh(using R <:< Cap.GH): GitHub          = c.gh0
  def git(using R <:< Cap.GIT): Git           = c.git0
  def agents(using R <:< Cap.AGENTS): Agents  = c.agents0
  // ... one accessor per capability
```

The accessor's `<:<` evidence is the whole proof apparatus. A function declares what it needs in its
own signature, `def pick()(using c: Ctx[Cap.GH]): Option[Work]`, and a body that reaches for
`c.git` fails to compile with `Cannot prove that Cap.GH <:< Cap.GIT`. A wider context satisfies a
narrower requirement through covariance, so a caller holding `Ctx[Cap.GH & Cap.GIT]` calls `pick()`
with nothing written. Both directions were compiled and confirmed on 3.8.3, no flags.

`Grant[R]` is the value that pairs the compile time set with a runtime allowlist. `Grant.gh` is a
`Grant[Cap.GH]` carrying the string `"gh"`; `++` intersects the types and unions the names:

```scala
w.step("Pick", Grant.gh ++ Grant.log) { pick() }   // body given: Ctx[Cap.GH & Cap.LOG]
```

The type argument to `step` is inferred from the grant, so the consumer writes the set once, as a
value, and the compiler carries it into the body's context. Verified, including the negative case:
a body under `Grant.gh` that summons `Ctx[Cap.GH]` and calls `.git` is refused at compile time.

The two levels are deliberate and load bearing. At the World level, between steps, no capability
exists at all: the tick function holds only plain values, `w.cfg` and the `step` door, so glue code
cannot touch the outside world, and everything that does touch it is inside a named, timed, metered,
status emitting scope by construction. At the Ctx level, inside a step, capabilities exist and
`step` does not: `Ctx` carries no way to open a scope, and opening one through a captured `w` is
refused at runtime (nested step scopes fault). One level orchestrates, the other works.

### Least privilege: the three mechanisms, compared honestly

**(a) Capture checking**, the incumbent's choice. The only mechanism of the three that can seal an
inline lexical region: annotate a `par` branch `^{w.gh}` and the compiler refuses a body that
touches `w.git`, even written inline where wider things are in scope. Costs an experimental flag in
the consumer's own `loop.scala`, error messages in a notation nobody has seen, instability across
Scala versions, and a guarantee that silently evaporates in any file compiled without the flag.

**(b) The phantom requirement set**, `Ctx[R]` and `Grant[R]` above. Stable Scala 3.8.3, no flag, no
macro, inference does the composition (`R1 & R2` through `++`), and the refusal message is legible.
This is what the incumbent itself names as its fallback; this design promotes it to the mechanism.
Its precise limit, confirmed by compilation rather than assumed: an inline region cannot be sealed.
A narrower given introduced inside a lambda does not hide a wider given from an enclosing scope, so
a body that summons the wider `Ctx` type gets it. Two things answer that here. First, the enclosing
scope of a step body holds no `Ctx` at all, wide or narrow, because capabilities never exist at the
World level, so within one tick function there is nothing wider to summon; the leak requires a
consumer to deliberately store a `Ctx` from one step in an outer `var` and replay it in another.
Second, that replay is caught at runtime: every capability method is instrumented, and each call
checks the current step's grant allowlist, so a smuggled `git` inside a `Grant.gh` step faults at
the call, rc 50, same channel as an unaffordable dispatch. A proof for the honest author at compile
time, a tripwire for the devious one at runtime.

**(c) Ordinary context parameters**, `def pick()(using gh: GitHub, log: Log)`. Also a genuine
compile time proof at every def boundary, zero machinery. What it lacks against (b): no single
parameter (ten capabilities is ten `using` entries), no set algebra for the runner to receive (the
runner cannot ask "what does this step claim to need" because the claim is spread over parameters of
unrelated types), and no attenuation value to hand a scatter branch. This design still permits it,
because a helper that takes `(using gh: GitHub)` can be called from a step body as
`helper(using c.gh)`, and the signature remains a proof. The kit standardises on (b) for the step
seam because the grant value is what the runtime enforcement and the status events are built from.

The decision: (b) as the kit's mechanism, (c) freely usable underneath it, (a) declined. What is
genuinely given up relative to (a) is stated again in Honest weaknesses.

## The step scope and the runner

`step` is `Runner.step` with the node dissolved into arguments.

**Status and watch.** Entering and leaving a step appends `StatusEvent`s carrying the step name as
the phase string. `StageSet` stays a declaration handed to `LitterBox.run`, exactly as it is a hand
written declaration today (`stages = StageSet(...)` in the current `custom-loops.md` is already not
derived from the `Plan`), so `lb watch` is unchanged: it reads the declared stages off the run and
draws chips for the phases it sees. Step names may repeat within a tick (the gate reruns after every
repair), so events carry a per name occurrence counter, which is what the current `Cursor.pass`
already does for gate log filenames; uniqueness stops being a validation rule because identity is no
longer how edges are linked.

**Budget.** Unchanged in mechanism and location. The ledger stays owned by the runner, its
constructor stays `private[litterbox]`, and the charging decorator stays wrapped around
`AgentDispatch` so every real dispatch is charged at the moment it happens, whatever the step
declared. `dispatchBudget: Config => Int` moves from `LitterBox.graph` to a `LitterBox.run`
parameter. The current `Ledger` is already `synchronized` precisely because node bodies may thread,
so `scatter` needs nothing new from it.

**Affordability parking.** `cost = Cost.OneDispatch` on a step is checked against the ledger before
the body runs, and an unaffordable honest step ends the tick at `LoopExit.Parked` with the world
untouched, through the early exit boundary. This is the incumbent's pre check, kept whole: neither
the current runner nor the incumbent's ever looked further ahead than the node about to run, so
nothing the graph enabled here is lost. What a reified shape could in principle enable, summing the
minimum remaining cost of a whole path before starting it, is foreclosed; no version of this system
has ever done it.

**Timeouts** stay post hoc, measured around probe plus body, for the reasons issue #69 recorded:
the real bound is the subprocess timeout, and interrupting a body mid write is worse than observing
the overrun.

**Faults** keep today's meaning and channel. The tick runs inside `boundary.Label[LoopExit]`;
`Fault.raise` logs, notifies and breaks, and a red gate stays a value, never a fault. The typed
early exit the consumer uses deliberately mirrors it: `end.finish(LoopExit.Idle): Nothing`, so an
exit is an expression usable in any branch and the `match` around it stays total.

**Resume.** The `probe` moves onto `step` unchanged in meaning: a `Some` skips the body and flows
onward. A crashed tick reruns the whole tick function from the top, and every completed step's probe
answers from the world (`prForBranch` before `createPr`, exactly as today). The discipline this
demands, that recovery facts are read from the world rather than from local state, is the same
discipline RFC #26 decision 6 already imposes; direct style neither helps nor hurts it. Code between
steps reruns too, and can only compute, never touch the world, because no capability exists there;
under the graph model the reran equivalents were edge functions, with the same purity expectation
and less enforcement.

**No anonymous work.** Because `Ctx` is born inside `step` and dies with it, a dispatch, a push or a
gate run outside any step is not a policy violation to detect but a program that cannot be written
without first smuggling a `Ctx` out, and the smuggled call faults at runtime against the absent
grant. Everything observable therefore happens inside a named scope, which is what keeps the golden
log and status streams complete without a graph to enumerate the work.

## What deleting the reified graph actually loses

Answered per question, without softening.

**Does `lb watch` still work?** Yes, unchanged. It reads `status.jsonl` and the declared `StageSet`,
neither of which was ever derived from the graph. Nothing validates that declared stages match the
step names that actually run, and nothing does today either; that drift risk is inherited, not
introduced.

**Can the pipeline shape still be rendered for docs or a status UI?** No, and this is the real
loss. There is no data structure to draw before a run. A trace of one run can be rendered from
`status.jsonl`, and a union of traces approximates the shape over time, but a branch never yet taken
is invisible, and documentation diagrams go back to being hand drawn and hand rotted. The incumbent
renders its `Flow` tree exactly; this design cannot, and no instrumentation trick recovers a static
property from dynamic observation.

**Can the runner park a step it cannot afford before it runs?** Yes, kept in full, because the
existing pre check is local to the step about to run and `step` carries the same declared `Cost`.
Lost only in the speculative sense named above: a runner that wanted to refuse a whole path as
unaffordable before its first step would need the shape back.

**Can reachability and totality be validated?** Recast rather than kept. Totality gets stronger:
branching is `match` over sealed types, and the scaffolded `loop.scala` carries `-Xfatal-warnings`
(verified: a missing case is a hard error), which beats today's runtime "no edge answered Some"
fault and equals the incumbent. Reachability dissolves: an unreachable node cannot be expressed
because a step that is never called is just dead code, harmless to budget and shape, where today an
orphan in the edge table is a startup fault. What is honestly lost is the third validation,
duplicate names with conflicting declarations, which becomes meaningless (names are no longer
identity), and the general ability to add future static checks over a shape, because there is no
shape. An infinite loop the author forgot to bound is caught by neither design; both terminate
cycles by a counter in the author's own hands.

## The review guarantee without `afterReview`

The incumbent moves the review guarantee from a macro plus a path walk into a combinator signature.
This design moves it into the capability layer entirely, which is where the token is already minted,
and the result is types only, with no combinator and no path anywhere.

Minting is unchanged: `AgentDispatch` stays sealed, `review` stays `final`, `Judged.mint` stays
reachable from one line in one file, and under the incumbent's sandbox profiles the token means what
the incumbent restates: a fresh session, no worker context, no write back channel.

Spending is the new half. The capability that publishes outward past the point of no return does not
exist on any `Ctx`. `Grant` has no constructor for it, so no step can be granted it. The only way to
obtain it is:

```scala
final class Publish private[litterbox] (...):
  def merge(pr: Int, ciLog: String): Int

// on World:
def publish(token: AgentDispatch.Judged[?]): Publish
```

A `Judged` is the price of the door, checked by the compiler as an ordinary argument type. Guarded
work is reachable only after a review because the value that performs it cannot be named, built,
granted or summoned any other way; `RequiresReviewInput`, `Guard`, `Trust`, `TrustOf` and the entire
reachability walk are deleted with nothing replacing them, because there is no path left to walk.
A consumer who wants more behind the door than `merge` wraps their own guarded helper to take a
`Publish`, and the proof composes for free through ordinary parameter passing.

Where the boundary sits is a decision this design makes explicitly where the shipped graph today
leaves it implicit: `merge` is behind the token; branch push, PR creation and PR comments are not.
The shipped loop's own rejection paths legitimately open a needs human PR that no reviewer ever saw
(ARCHITECTURE.md records that a guard on `OpenPr` would reject the shipped graph itself), so a PR is
an escalation to human eyes, and the merge is the publication. Anyone wanting PR creation guarded
composes it behind their own token taking function.

Two residuals, stated rather than hidden. `judged.map(_ => Approve)` still launders a verdict, in
this design, in the incumbent and today; the token proves the dispatch happened, never what it said.
And a token is not tick scoped: a consumer who stores a `Judged` in an outer `var` across ticks can
unlock `publish` with last week's review. The incumbent has the same hole (`Flow.value(stored)`
feeds `afterReview` equally well), so this is parity, not regression, but direct style makes the
smuggling syntactically easier, so the strengthening is worth recording: run the tick through a
polymorphic function, `def run[A](tick: [T] => World[T] => A): A`, brand the token `Judged[T, A]`,
and a stored token's type becomes unnameable outside the tick that minted it. Verified to compile on
3.8.3. Declined for the core design because the signature it forces on every consumer is exotic for
a guarantee that only bites a hostile author, the same class of author package injection already
admits; recorded here as the known upgrade if that judgment changes.

## Concurrency

`scatter` runs one virtual thread per item and joins them all before returning or faulting, so no
branch is abandoned mid mutation. Branch grants use `ParGrant`, a second grant vocabulary whose
constructors simply do not include `git`, `gates` or `publish` adjacent capabilities, so a branch
that could touch the working tree cannot be expressed; this is the compile time exclusion the
incumbent gets from capture set allowlists, obtained by construction instead (there is no way to say
"not GIT" in an intersection encoding, so the safe set is enumerated rather than the unsafe set
subtracted). The runtime allowlist backs it against leaked references, per branch. Events from
branches carry a branch id and `watch` groups by it, the same scope item the incumbent flags.
The ledger is already safe under this, verified in the current source rather than assumed: every
read and write is synchronized, with a comment recording the eight thread overspend it closes.

The structured answer machinery, the nonce fence rule (zero blocks malformed, two or more blocks
malformed, deliberately), the sandbox profile table, the `Prompt` builder's trusted and untrusted
split with single pass substitution, and the reviewer's bounded consult loop with a fixer that can
run anything and emit nothing but text: all adopted from the incumbent unchanged. They live at the
capability and dispatch layer, which this design keeps whole; nothing about deleting the graph
touches them.

## Per step configuration

As in the incumbent: `Role` is deleted, and a dispatching step reads its command override, model key
and phase wording keyed by its own step name (`agent.model.Implement`, `Implement_CMD`). The step
name is already in hand at the `step` call, so nothing extra travels.

## Worked example

The shipped pipeline, PICK through MERGE, with the bounded repair loop and the bounded review
consults fixer loop. Helper signatures are the least privilege proofs; the tick body is the shape.

```scala
//> using dep in.rcard::litter-box:0.10.0
//> using options -Xfatal-warnings

import in.rcard.litterbox.*
import in.rcard.litterbox.Cap.*

final case class Work(issue: Int, branch: String)

enum Answer derives Decoder:
  case Approve
  case Changes(findings: List[String])
  case Question(ask: String)

val MaxRounds = 3

// ---- helpers: each signature is the privilege claim, held by the compiler ----

def pick()(using c: Ctx[GH]): Option[Work] =
  c.gh.nextReady().map(i => Work(i, s"us-$i"))

def implement(work: Work)(using c: Ctx[AGENTS & FS & GH & GIT & CFG]): Staged =
  val out = c.agents.dispatch(
    in     = Sandbox.Worktree,
    prompt = Prompt.from(Template.Iterate)
      .trusted("GATE", c.cfg.gateCmd)
      .trusted("CONVENTIONS", c.fs.conventions())
      .untrusted("ISSUE", c.gh.issueBody(work.issue)))
  PatchGuard.stage(out.patchFile)

def repair(work: Work, gateLog: String)(using c: Ctx[AGENTS & FS & GIT & CFG]): Staged =
  val out = c.agents.dispatch(
    in     = Sandbox.Worktree,
    prompt = Prompt.from(Template.Fix)
      .trusted("GATE", c.cfg.gateCmd)
      .untrusted("GATE_LOG", c.fs.read(gateLog)))
  PatchGuard.stage(out.patchFile)

def reviewOnce(work: Work, evidence: Option[String])(
    using c: Ctx[AGENTS & GIT & GH & FS & CFG]): AgentDispatch.Judged[Decoded[Answer]] =
  c.agents.dispatch(
    in     = Sandbox.Cold,
    as     = Decoder.json[Answer],
    prompt = Prompt.from(Template.Review)
      .trusted("PROTECTED", c.cfg.protect.mkString(" "))
      .trusted("CONVENTIONS", c.fs.conventions())
      .untrusted("DIFF", c.git.diffCachedOriginMain())
      .untrusted("ISSUE", c.gh.issueBody(work.issue))
      .untrusted("EVIDENCE", evidence.getOrElse("none")))

def consultFixer(question: String)(using c: Ctx[AGENTS & FS]): String =
  c.agents.dispatch(
    in     = Sandbox.Question,
    prompt = Prompt.from(Template.Verify).untrusted("QUESTION", question)).text

// ---- the loop -----------------------------------------------------------------

@main def loop(args: String*): Unit = LitterBox.run(
  args,
  dispatchBudget = cfg => 1 + cfg.repairBudget + 2 * MaxRounds,
  stages = StageSet(
    stages = List(
      Stage("Implement", "impl",   1),
      Stage("FastGate",  "gate",   1),
      Stage("Repair",    "fix",    1, badge = true),
      Stage("Review",    "review", 2),
      Stage("Verify",    "verify", 2, badge = true),
      Stage("Merge",     "merge",  2)),
    anchor = Some("Implement"), terminal = Some("Merge"))
) { (w: World, end: Exit) =>

  // PICK
  val work = w.step("Pick", Grant.gh) {
    pick() match
      case Some(work) => work
      case None       => end.finish(LoopExit.Idle)
  }

  // IMPLEMENT: parked before the body runs if the ledger cannot pay
  val grantImpl = Grant.agents ++ Grant.fs ++ Grant.gh ++ Grant.git ++ Grant.cfg
  val staged = w.step("Implement", grantImpl,
      cost = Cost.OneDispatch, timeout = Timeout.After(w.cfg.iterTimeout)) {
    implement(work)
  }
  staged match
    case Staged.Ok(_) => ()
    case _            => end.finish(LoopExit.NothingMade)   // empty or rejected patch

  // GATE and REPAIR: recursion is the cycle, the counter is a parameter
  val grantGate = Grant.gates ++ Grant.git
  val grantFix  = Grant.agents ++ Grant.fs ++ Grant.git ++ Grant.cfg

  def greenOrOut(round: Int, verdict: GateVerdict): Unit = verdict match
    case GateVerdict.Green                                   => ()
    case GateVerdict.Red(_) if round >= w.cfg.repairBudget   => end.finish(LoopExit.NothingMade)
    case GateVerdict.Red(log) =>
      w.step("Repair", grantFix, cost = Cost.OneDispatch) { repair(work, log) }
      greenOrOut(round + 1, w.step("FastGate", grantGate) { Machine.fastGate() })

  greenOrOut(0, w.step("FastGate", grantGate) { Machine.fastGate() })

  // REVIEW, consulting the fixer until it can decide, bounded
  val grantReview = Grant.agents ++ Grant.git ++ Grant.gh ++ Grant.fs ++ Grant.cfg

  def judge(round: Int, evidence: Option[String]): AgentDispatch.Judged[Answer] =
    val j = w.step("Review", grantReview, cost = Cost.OneDispatch) {
      reviewOnce(work, evidence)
    }
    j.value match
      case Decoded.Ok(Answer.Question(q)) if round < MaxRounds =>
        val report = w.step("Verify", Grant.agents ++ Grant.fs, cost = Cost.OneDispatch) {
          consultFixer(q)
        }
        judge(round + 1, Some(report))
      case Decoded.Ok(answer)   => j.map(_ => answer)
      case Decoded.Malformed(_) => j.map(_ => Answer.Changes(Nil))   // fail safe

  val judged = judge(1, None)
  judged.value match
    case Answer.Approve => ()
    case _              => end.finish(LoopExit.NeedsHuman)

  // PR and CI: shipped helpers, plain functions with narrow signatures
  w.step("CommitAndPush", Grant.git ++ Grant.cfg) { Machine.commitAndPush(work.branch) }
  val pr = w.step("OpenPr", Grant.gh ++ Grant.fs,
      probe = summon[Ctx[GH]].gh.prForBranch(work.branch)) {
    Machine.openPr(work.issue, work.branch)
  }
  w.step("CiWait", Grant.gh ++ Grant.hostGates ++ Grant.clock) { Machine.ciWait(pr) }

  // MERGE: reachable only through the token; no Grant constructor can mint a Publish
  val door = w.publish(judged)
  w.step("Merge", Grant.gh) { door.merge(pr) }
  LoopExit.Success
}
```

`pick` provably touches nothing but GitHub, `judge`'s helpers provably cannot push, and the merge is
unreachable without a `Judged`, all checked by a stock compiler. The whole pipeline is one page with
zero payload case classes: `Attempt`, `Round` and `Ask` from the incumbent's example do not exist,
because the values they threaded are parameters and locals.

## Testing

The standing constraint holds: no Docker, no `gh`, no credentials.

* `TestWorld` keeps its scripted capabilities and recorder buffers unchanged. `runGraph` becomes
  `runTick(tick)`, which is nothing but building a `World` over the scripted capabilities and
  calling the function; the testkit sheds the graph walker entirely.
* A helper is tested by calling it directly: the testkit exposes `world.ctx[R]` (it lives inside
  the package and may mint anything, exactly as it mints `Judged` today), so
  `pick()(using world.ctx)` is a unit test with no step machinery. `runNode` dissolves.
* The capability proofs are negative compilation tests through scala-cli, same shape as the
  incumbent's plan: a file that must not compile, asserted by the suite. Cheaper here, because the
  assertions are ordinary type errors, not capture checker behaviour.
* Grant enforcement, parking, charging, fault routing, the bounded loops and the nonce contract are
  ordinary `TestWorld` tests; the golden log mechanism is untouched.
* `scatter` determinism in tests: the testkit runs branches sequentially in declaration order
  through the same seam, so scenario logs stay stable.

## Migration

Each step ends with the suite green.

1. **The direct kit.** `World`, `Ctx`, `Cap`, `Grant`, `ParGrant`, `step`, `scatter`, `Exit`,
   `Publish`, beside the current kit. The ledger, charging decorator, `Fault`, `Cost`, `Timeout`
   move over unchanged.
2. **Prompts and decoding.** The `Prompt` builder, `Decoder`, `Decoded`, the nonce contract. Shared
   with the incumbent plan, step for step.
3. **Sandbox profiles.** The third script for `Sandbox.Question`. Also shared.
4. **The shipped pipeline as a tick.** `Machine`'s twelve node bodies become public defs with
   `Ctx[R]` signatures (they are already context functions over `Caps`, so this is mostly signature
   narrowing), and `runOnce` becomes the tick function. Goldens rewritten in this commit alone.
5. **The reviewer consults a fixer.** Three way answers, the bounded loop, budget accounting.
6. **Delete the graph** in one commit: `KitMacro.scala` whole, `Plan`, `Edge`, `Next`, `Workflow`,
   `Shape`, `Transition`, `Runner.validate`, `Node`, `Role`, `TrustOf`, `Guard`,
   `RequiresReviewInput`. This is roughly three thousand lines, including the thousand line macro
   the incumbent also deletes and the validation walk it keeps in spirit.
7. **Testkit**: `runTick`, `ctx[R]`, drop `runGraph`/`runNode`.
8. **Docs.** `custom-loops.md` shrinks hard: the eleven paragraphs of macro rules, the edge payload
   guidance and the two step identity rules all go; what replaces them is one page on step scopes,
   grants and probes.

Net movement is smaller than the incumbent's: no combinator library to build, no `Flow` interpreter
to write, no capture checked `World` resurfacing, no `-language:experimental` protection story for
`loop.scala`, and the `Machine` bodies migrate by signature change rather than by rewrite into
`switch` arms.

## Risks and open questions

**The grant vocabulary is a parallel artifact.** `Cap` markers, `Grant` constructors and the
allowlist strings restate the capability roster and must grow in lockstep with `Caps.scala`. This is
mechanical and boundable by one test (every capability has a marker, a constructor and an accessor),
but it is boilerplate the incumbent's inferred capture sets never need.

**Current step tracking under threads.** The runtime allowlist needs a current scope per thread;
`scatter` sets each branch's scope explicitly at branch start, so no inheritance is relied on, but a
consumer spawning raw threads inside a step body escapes the tracking (the calls fault as
"no active step", failing closed, which is the right direction but a surprise to document).

**Token staleness.** The cross tick `Judged` residual and its polymorphic brand fix, discussed
above. Open question: adopt the brand from day one and eat the signature, or keep the door simple.

**`-Xfatal-warnings` in a consumer file** is deletable by the agent under harness, same as the
incumbent's flags, and the same two mitigations apply: `loop.scala` in the protected paths, and `lb`
forcing the flags on the compile it drives. Less is at stake than in the incumbent, because only
totality rests on a flag here; the capability proofs are ordinary typing that no flag can turn off.

**Probes are now the whole resume story.** With no shape, nothing can even enumerate which steps a
resumable tick should probe; correctness is entirely the author's placement of probes, guided by
docs. Today's model has the same truth but the node list makes an audit easier to eyeball.

**Does `watch` need more?** Step names now come from call sites with no registry; a typo in a stage
declaration versus a step name silently draws no chip. Inherited from today, but the graph deletion
removes one place a future cross check could have hung.

## Honest weaknesses

Where this design is worse than the incumbent, plainly.

1. **Inline regions are not sealed.** Capture checking refuses a violating inline `par` branch at
   compile time; here the compile time proof exists only at signatures that declare a `Ctx[R]`, and
   an inline body that replays a smuggled wider `Ctx` is caught at runtime, not at compile time.
   This was verified, not assumed: the narrower given does not hide the wider one. The design
   compensates with structure (no capabilities at the World level) and a runtime tripwire, but the
   incumbent's guarantee is categorically stronger inside a lexical scope.
2. **The shape is gone.** No static rendering, no reachability analysis, no future static checks,
   no "the graph that is checked and the graph that runs cannot differ" sentence, because there is
   no graph to check. Anything the project later wants a shape for means reintroducing reification.
3. **The review guarantee is a token, not a path.** `afterReview` proves the guarded flow's input is
   the reviewer's output this tick, structurally adjacent; `publish(judged)` proves a token exists.
   Both admit laundering by `map` and replay from storage, but the incumbent's shape makes misuse
   syntactically louder, and the brand fix that closes replay costs this design an exotic signature
   the incumbent does not need.
4. **Least privilege is opt in below the step.** A consumer who writes one giant step with a full
   grant gets one honest declaration and no interior proof; the incumbent infers per node capture
   sets whether the author cares or not. The grant at least makes the overreach visible and
   greppable at the call site.
5. **Concurrency exclusion is by enumeration.** `ParGrant` lists the safe capabilities because the
   type encoding cannot subtract; a new capability added carelessly to `ParGrant` widens every
   scatter in existence, where a capture allowlist names what it permits per site.

## Verification

Compiled on Scala 3.8.3, scala-cli, no experimental flags, during this design:

* `Ctx[+R]` with extension accessors over `<:<` evidence; a full context satisfying a narrower
  `using` through covariance; the violating access refused with `Cannot prove that GH <:< GIT`.
* The inline region leak: a wider outer given remains summonable inside a narrowed context lambda;
  the same body as a standalone helper def is refused with `No given instance`.
* `Grant[R]` with `++` producing `R & S`, `step` inferring `R` from the grant and injecting
  `Ctx[R]`, and the negative case under a narrow grant failing to compile.
* The `Judged` gated `Publish` miniature, faithful to the sealed dispatch and private mint.
* A full direct style tick: boundary based exits, bounded repair recursion, step scopes, all under
  `-Xfatal-warnings`; a non exhaustive match over a sealed enum failing the build under that flag.
* Context function literals with named parameters; the polymorphic function brand; a virtual thread
  scatter and join.

Everything else follows the incumbent's own verification plan: `TestWorld` tests for semantics,
negative compilation tests for the proofs, decoder unit tests for the nonce contract, and a runtime
test that the ledger cannot be double spent.
