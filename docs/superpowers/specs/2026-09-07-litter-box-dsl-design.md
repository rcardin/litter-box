# A new graph DSL for litter-box

Status: design, approved in conversation on 2026-09-07. Not yet implemented.

## Why

The kit tier gives a consumer a graph API: `Node`, `Edge`, `Plan`, `Workflow`, `Runner`, plus four
declared axes (`Cost`, `Timeout`, `Trust`, `Guard`) and a macro that refuses any `Plan` it cannot
read literally. It works, and the shipped pipeline runs on it. Three things about it are wrong
enough to justify a rewrite rather than a patch.

**Authoring costs too much.** An edge sees nothing but the output of the node it leaves, so every
fact a later step needs has to be threaded by hand through a chain of case classes written for no
other purpose. Nodes must be top level vals, never defs, never inline, never exported, never
instance qualified, and two references that canonicalise to the same identity are refused. Those
rules exist only so `KitMacro` can read the `Plan`. They are eleven paragraphs of the authoring
guide, and none of them is about the loop.

**The model cannot say what we want to say.** There is no way to declare that two steps are
independent, so nothing can run concurrently. There is no scatter and gather, so a fixer looping
over findings is a hand written loop inside one node body, invisible to the shape. There is no way
to compose a subgraph as a unit.

**The proving apparatus is barely used.** One property is proved: a node whose input extends
`RequiresReviewInput` is reached only along a path that crossed a reviewer. Everything else is a
runtime check or a convention. In particular every node receives the whole `Caps` bundle, so a node
that only reads an issue can force push, and nothing in the type system says otherwise.

## What this design changes

Two properties become compile time proofs:

* **Capability least privilege.** A node can touch only what its body actually names, and the
  compiler holds it to that when asked.
* **Totality and reachability.** Every branch is covered and every node is reachable, by
  construction rather than by a startup walk.

One property becomes cheaper to prove: the review guarantee, today a macro plus a path walk, becomes
a combinator signature.

Two capabilities become expressible: declared concurrency, and structured answers from agents.

## Non goals

Vocabulary stays graph shaped. This is not a policy DSL that hides nodes and edges behind loop
domain verbs.

Nothing here is about backward compatibility. litter-box has no consumers, so config keys, artifact
names and the whole public kit surface may change without a migration path.

## The core model

Two types replace five.

```scala
final class Node[I, O] private (
    val name:    String,
    val cost:    Cost,
    val timeout: Timeout,
    val probe:   I => Option[O],
    val run:     I => Outcome[O]
)

sealed trait Flow[I, O]
```

`Node` is the leaf, one unit of work, and keeps the fields it has today. `Flow` is a tree of leaves
and combinators, produced by composition. A `Flow` is its own shape, so nothing has to recover a
shape from a literal expression, and the graph that is checked and the graph that runs cannot
differ.

Deleted outright: `Plan`, `Edge`, `Transition`, `Shape`, `Next`, `KitMacro`, and every authoring rule
that existed to serve the macro.

## Capabilities

Capabilities are delivered through Scala's experimental capture checking. The runner owns them and
hands them to the consumer's builder as one capture checked bundle:

```scala
@main def loop(args: String*): Unit = LitterBox.run(args) { w =>   // w: World^
  val Pick = Node("Pick", Cost.None, Timeout.Unbounded):
    (_: Tick) => Outcome.Done(Work(w.gh.nextReady()))              // captures {w.gh}
  ...
}
```

Capture sets are inferred, so a node author writes no annotation. An annotation is written only
where the compiler should hold a node to a restriction, for example a node intended to stay read
only, or a branch of `par`.

Findings from the feasibility probe, all verified against Scala 3.8.3:

* Capture checking compiles on 3.8.3 with no dependency, enabled per file by
  `import language.experimental.captureChecking` or project wide by
  `-language:experimental.captureChecking`.
* Capture sets are inferred, unioned across composition, and enforced when annotated. A step
  annotated `^{w.gh}` whose body calls `w.git.push` fails with `capability w.git cannot flow into
  capture set {w.gh}`.
* Tracking survives givens and `summon`, so direct style is preserved.
* Path dependent granularity works through a bundle: `{w.gh}` and `{w.git}` are distinguished, so
  the builder takes one parameter rather than ten.
* Tracking is silently erased when a capability is passed at a plain type. Today's `Caps` case class
  with its `given` derivations is exactly that shape. Capabilities must therefore be exposed only at
  `^` types, and `Caps.given` in its current form goes away.
* The guarantee holds only in files compiled with capture checking on. In a file without it, `^`
  does not parse, and a violating value handed to a bounded API is accepted unchecked.

## Combinators

```scala
f andThen g                                    // sequence
f.map(o => p)                                  // pure adapter between two nodes' types
f.switch { o => ... target.on(input) ... }     // branch, any arity, one total match
f.withInput                                    // pair a flow's output with its own input
f.loopWhile(o => Option[I])                    // cycle with the counter in the value
Flow.rec { self => ... }                       // general back edge
f.fanOut(o => List[A])(each: Flow[A, B])       // scatter and gather, declared
par(a, b)                                      // concurrent, capture restricted
f.afterReview(guarded)                         // the review guarantee
Flow.value(x) / Flow.stop(exit)                // yield a value, or end the tick
```

`switch` replaces the edge list. Each arm names the target and the value it carries, through
`target.on(input)`, so the branch payload is visible where the branch is taken and is type checked
against the target's input type. Terminals are `Flow.stop(LoopExit.X)` inside an arm, so there is no
separate exit edge concept.

`withInput` answers the one thing an edge could never do today: read something the node it leaves
did not produce. `f.withInput : Flow[I, (I, O)]` pairs a flow's output with the input it was given,
so a branch can consult a counter or an identifier without every node in between copying it into its
own output type. This is what removes most of the case class per edge payload tax.

`afterReview` carries the review guarantee:

```scala
extension [I, O](f: Flow[I, O])
  def afterReview[P](guarded: Flow[O, P])(using O <:< Judged[?]): Flow[I, P]
```

A node whose input extends `RequiresReviewInput` is reachable only through `afterReview`, which
accepts only a left side whose output is exactly `Judged[?]`. The current rule that `Option[Judged[A]]`
does not count follows from `<:<` with nothing extra written.

### Totality

Exhaustivity of a sealed match is a scalac warning, not an error. For totality to be a proof, the
scaffolded `loop.scala` carries `-Xfatal-warnings` alongside the capture checking flag. Without that
flag an uncovered case is a `MatchError`, which the runner reports as an infra fault. Reachability
needs no check at all: a flow is a tree, so an unreachable node cannot be written into it.

### What remains a runtime check

Duplicate node names. Names are strings and cannot be typed away, so `Runner.validate` survives for
this alone.

## The runner

**Budget.** The ledger moves from a decorator around nodes to a decorator around the capability:
before the `World^` reaches the builder, the runner replaces the dispatch capability with a charging
one. Every dispatch is charged by construction, from `probe` or from `run`, whatever the node
declared. `Cost` survives for one purpose: an honest `Cost.OneDispatch` node that cannot be afforded
is parked before it runs, leaving the world untouched, where a dishonest one faults halfway through
its own side effects.

**Timeouts** stay post hoc, as today. A `par` measures each branch separately and the flow by its
slowest branch.

**Faults** keep today's meaning: infrastructure only, rc 50, tick abandoned. A red gate or an empty
patch is an outcome, not a fault.

**Concurrency.** `par` branches run on virtual threads and are joined. Three things make it safe:

1. The working tree is excluded by the capture set. `par` accepts only branches whose capture sets
   fall within an allowlist that omits the git and gate capabilities. This is enforced by the
   compiler, verified in the probe.
2. The charging capability synchronises, so a shared budget cannot be double spent.
3. A fault in any branch fails the flow after joining, never mid flight, so no branch is abandoned
   holding a half mutated world.

Status events from concurrent branches interleave in `status.jsonl`, so each event carries a branch
id and `watch` groups by it.

## Sandbox profiles

The worker and reviewer distinction disappears as an API concept. What actually differs is
isolation, so a dispatch names its sandbox and the kit owns a sealed set of profiles. A consumer
chooses among sanctioned profiles and cannot invent one.

| Profile | Mounts | Tools | Output | Mints `Judged` |
|---|---|---|---|---|
| `Sandbox.Worktree` | workspace, input, output | mutating | patch | no |
| `Sandbox.Cold` | none | read only | stdout | yes |
| `Sandbox.Question` | workspace copy, input | mutating inside the copy | stdout | no |

`Sandbox.Question` is the fixer a reviewer can consult: it has the code and the tools, so it can run
tests, greps, or a mutation run, and it has no output mount, so it cannot emit a patch. Its report is
text.

The trust token's meaning is restated precisely, because it is broader than the current wording and
narrower than readers assume: **`Judged` asserts that the answer came from a fresh session with no
worker context and no write back channel.** It never asserted correctness, and it does not assert
blindness to the code.

Because the token is bound to the profile rather than to which method was called, the guarantee is
stronger than today's, where cold isolation is a property `LiveAgentDispatch` chooses to provide and
the signature cannot demand.

## Prompts

`renderTemplate` becomes a builder that makes the trust level of each substitution part of the call:

```scala
Prompt.from(Template.Review)
  .trusted("GATE", w.cfg.gateCmd)
  .trusted("CONVENTIONS", w.fs.conventions())
  .untrusted("DIFF", w.git.diffCachedOriginMain())
  .untrusted("ISSUE", w.gh.issueBody(issue))
```

`trusted` inserts a value the harness controls, verbatim. `untrusted` inserts text somebody else
wrote, after neutralising fence markers so the content cannot close the block it sits in and start
issuing instructions, and after capping its size so one input cannot crowd out the prompt. This is
what `Reply.splice` does for comments today, applied to every untrusted substitution.

Substitution stays a single pass, so a value inserted into one slot is never rescanned for others.

## Structured answers

A dispatch may declare a decoder, so an agent's answer arrives as a typed value rather than as text
somebody greps.

```scala
Node.dispatch("Review",
  in     = Sandbox.Cold,
  as     = Decoder.json[Answer],
  prompt = ...)
// Node[Round, Judged[Decoded[Answer]]]
```

`Decoded[A]` is `Ok(A)` or `Malformed(raw)`, never an exception, preserving the current fail safe
behaviour: a bad answer is a value the graph routes on. Trust and decoding do not mix:
`Judged[Decoded[A]]`, never the reverse, because a decoded case class is still agent authored.

### The nonce rule

The kit mints a random marker per dispatch, splices it into the prompt, and accepts an answer only
inside a fence carrying that marker:

    ```lb:7f3a91c4e88b2d05
    {"answer": "approve"}
    ```

Zero matching blocks is `Malformed`. Two or more is also `Malformed`, deliberately, because
ambiguity is the attack. This replaces `grep | tail -1`, where the last occurrence wins.

What the rule buys, stated precisely: it defeats echo forgery, where a reviewer quotes the diff and
the diff contains a planted verdict line. It does nothing against persuasion, where an agent is
argued into a genuine approval. No parsing layer can fix persuasion, and the design must not imply
otherwise.

## The reviewer may consult a fixer

A reviewer answers with one of three things, not one verdict line:

```scala
enum Answer derives Decoder:
  case Approve
  case Changes(findings: List[String])
  case Question(ask: String)
```

`Question` routes to a `Sandbox.Question` dispatch, which has the code and can run things, and whose
report returns into the reviewer's next prompt through `untrusted`. The reviewer decides on the
evidence, or asks again, up to a bounded number of rounds. Out of rounds is `Changes`, the same fail
safe direction as a malformed answer.

Two rules keep this honest. The consulted fixer cannot emit a patch, or the reviewer's question
becomes a way to get code written and then approved by the reviewer that asked for it. And its
report is untrusted text, not fact, because an agent can answer wrongly or shallowly.

Where a question can be phrased as a command with a pass or fail answer, a gate node is stronger
than a fixer, because nobody has to be believed. Mutation testing is the motivating example and is a
gate, not an agent.

## Per node configuration

`Role` is deleted. It is a two value enum standing in for three unrelated per node settings: which
command override to use, which model key to read, and the wording of status and fault messages. A
third worker kind cannot be expressed today without borrowing another role's model and seam.

A dispatch takes what it needs, keyed by node name: `agent.model.Implement`, `Implement_CMD`, and a
phase string defaulting to the node name. `Role`, `Models.forRole` and `Machine.policyOf` all go.

## Worked example

```scala
//> using dep in.rcard::litter-box:0.10.0
//> using options -language:experimental.captureChecking -Xfatal-warnings

import in.rcard.litterbox.*

final case class Work(issue: Int, branch: String)
final case class Attempt(work: Work, n: Int, gateLog: Option[String])
final case class Round(work: Work, round: Int)
final case class Ask(round: Round, question: String)

enum Answer derives Decoder:
  case Approve
  case Changes(findings: List[String])
  case Question(ask: String)

val MaxRounds = 3

@main def loop(args: String*): Unit = LitterBox.run(args) { w =>

  val Pick = Node("Pick", Cost.None, Timeout.Unbounded):
    (_: Tick) =>
      w.gh.nextReady() match
        case None        => Outcome.Stopped(LoopExit.Idle)
        case Some(issue) => Outcome.Done(Work(issue, s"us-$issue"))

  val Implement = Node.dispatch("Implement",
    in      = Sandbox.Worktree,
    timeout = Timeout.After(w.cfg.iterTimeout),
    prompt  = (work: Work) =>
      Prompt.from(Template.Iterate)
        .trusted("GATE", w.cfg.gateCmd)
        .trusted("CONVENTIONS", w.fs.conventions())
        .untrusted("ISSUE", w.gh.issueBody(work.issue)))

  val Repair = Node.dispatch("Repair",
    in     = Sandbox.Worktree,
    prompt = (a: Attempt) =>
      Prompt.from(Template.Fix)
        .trusted("GATE", w.cfg.gateCmd)
        .untrusted("GATE_LOG", a.gateLog.fold("")(w.fs.read)))

  val FastGate = Node.gate("FastGate", cmd = w.cfg.gateCmd)

  val Review = Node.dispatch("Review",
    in     = Sandbox.Cold,
    as     = Decoder.json[Answer],
    prompt = (r: Round) =>
      Prompt.from(Template.Review)
        .trusted("PROTECTED", w.cfg.protect)
        .trusted("CONVENTIONS", w.fs.conventions())
        .untrusted("DIFF", w.git.diffCachedOriginMain())
        .untrusted("ISSUE", w.gh.issueBody(r.work.issue)))

  val Verify = Node.dispatch("Verify",
    in     = Sandbox.Question,
    prompt = (a: Ask) =>
      Prompt.from(Template.Verify).untrusted("QUESTION", a.question))

  // gate, repairing until green or out of budget. The attempt counter rides in the value.
  lazy val Green: Flow[Attempt, Work] = Flow.rec { self =>
    FastGate.withInput.switch {
      case (_, Gated(work, GateVerdict.Green))       => Flow.value(work)
      case (a, Gated(work, GateVerdict.Red(log)))
        if a.n >= w.cfg.repairBudget                 => Flow.stop(LoopExit.NothingMade)
      case (a, Gated(work, GateVerdict.Red(log)))    =>
        (Repair andThen self).on(Attempt(work, a.n + 1, Some(log)))
    }
  }

  // review, consulting the fixer until it can decide. The round counter rides in the value.
  lazy val Judge: Flow[Round, Judged[Answer]] = Flow.rec { self =>
    Review.withInput.switch { (round, j) =>
      j.value match
        case Decoded.Ok(Answer.Question(q)) if round.round < MaxRounds =>
          (Verify andThen self).on(Ask(round, q))
        case Decoded.Ok(Answer.Approve)     => Flow.value(j.map(_ => Answer.Approve))
        case Decoded.Ok(Answer.Changes(fs)) => Flow.value(j.map(_ => Answer.Changes(fs)))
        case Decoded.Ok(Answer.Question(_)) => Flow.value(j.map(_ => Answer.Changes(Nil)))
        case Decoded.Malformed(_)           => Flow.value(j.map(_ => Answer.Changes(Nil)))
    }
  }

  // shipped nodes the kit exposes, reused rather than reimplemented
  val Publish = Machine.CommitAndPush andThen Machine.OpenPr andThen Machine.CiWait andThen Machine.Merge

  Pick
    .andThen(Implement.withInput)
    .switch {
      case (work, Staged.Ok(_)) => Green.on(Attempt(work, 0, None))
      case (_, _)               => Flow.stop(LoopExit.NothingMade)   // empty or rejected patch
    }
    .map(work => Round(work, 1))
    .andThen(Judge)
    .afterReview(Publish)
}
```

`Pick` provably touches nothing but GitHub. `Green` may reach the working tree. `Judge` may not: a
reviewer that tried to push would not compile. `afterReview` is the only door into `Publish`.

## Migration

Order of work. Each step ends with the suite green.

1. **The kit.** `Node`, `Flow`, the combinators, `Outcome`, the capture checked `World`, and the
   runner with its ledger and virtual thread `par`. Built beside the current kit, not in place.
2. **Capabilities at capture checked types.** `Caps` stops handing out plain typed capabilities.
   `Live.scala` implementations are unchanged; only the types they are surfaced at change.
3. **Prompts and decoding.** The `Prompt` builder with `trusted` and `untrusted`, `Decoder`,
   `Decoded`, and the nonce contract, including the reviewer template's rewrite.
4. **Sandbox profiles.** A third script alongside `run-agent.sh` and `run-reviewer.sh` for
   `Sandbox.Question`: workspace copy and tools, no output mount.
5. **The shipped pipeline.** The twelve nodes are rewritten as one flow. Golden log files are
   rewritten in this commit, alone, so the rewrite is reviewable.
6. **The reviewer consults a fixer.** Three way answers, the bounded round loop, budget accounting
   for the extra dispatches.
7. **Delete the old kit** in one commit: `KitMacro`, `Plan`, `Edge`, `Transition`, `Shape`, `Next`,
   `Role`, `policyOf`, `forRole`.
8. **Testkit.** `TestWorld` surfaces capabilities at the same capture checked types, or every test
   silently loses the guarantee it exists to test. `runGraph` becomes `runFlow`.
9. **Docs.** `docs/custom-loops.md` rewritten around flows, losing the sections that exist only to
   explain the macro. README's kit section follows.

## Risks and open questions

**Capture checking is experimental.** A public 0.x DSL inherits its instability across Scala
versions. The fallback, if it becomes untenable, is an inferred requirement type parameter that a
consumer never writes: `Step[I, O, R]` with `andThen` returning `R1 & R2`. That fallback needs no
experimental feature and was validated as workable during the design, so the risk is a rework of the
capability layer, not of the DSL.

**The compiler flags are deletable.** Both proofs rest on directives inside `.litter-box/loop.scala`,
a file in the repository the harness works on. An agent under harness that edits it can delete them,
and least privilege silently degrades to nothing, since capture sets are erased at runtime. Two
mitigations, and the design should take both: `loop.scala` joins the protected paths a worker patch
may not touch, and `lb` drives the compile itself with the flags forced rather than trusting the
file's own directives.

**Error message noise.** Capture checking reports inferred types with notation a consumer has not
seen, for example `Work^'s1` and `Int ->{git} Work`. The authoring guide has to teach it.

**Concurrency and the status log.** Interleaved events need a branch id, and `watch` needs to group
by it. Scope for the concurrency step, not an afterthought.

**A reviewer that browses a merged tree.** `Sandbox.Question` can read files that an earlier auto
merged patch planted, which a prompt only reviewer never saw. Small but real widening, and the
reason its checkout carries no git history.

## Verification

Everything in this design is testable without Docker, `gh` or credentials, which is the standing
constraint on the suite.

* Capture set proofs are compile time, so they are tested with negative compilation tests: a file
  that must not compile, asserted through scala-cli.
* Combinator semantics, budget charging, fault routing and the bounded review loop are ordinary
  `TestWorld` tests.
* The nonce contract is a decoder unit test: zero blocks, one block, two blocks, wrong nonce,
  nonce echoed inside quoted content.
* `par` safety has both halves: a negative compilation test that a branch touching the working tree
  is refused, and a runtime test that the ledger cannot be double spent.
