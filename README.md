![Made for Scala 3](https://img.shields.io/badge/Scala%203-%23de3423.svg?logo=scala&logoColor=white)
![GitHub Workflow Status (with branch)](https://img.shields.io/github/actions/workflow/status/rcardin/litter-box/ci.yml?branch=main)
![Maven Central](https://img.shields.io/maven-central/v/in.rcard/litter-box_3)
![GitHub release (latest by date)](https://img.shields.io/github/v/release/rcardin/litter-box)
[![javadoc](https://javadoc.io/badge2/in.rcard/litter-box_3/javadoc.svg)](https://javadoc.io/doc/in.rcard/litter-box_3)

# litter-box

A distrustful autonomous coding loop for JVM projects.

It picks one labelled GitHub issue, dispatches a fresh `claude -p` worker inside a
network-restricted Docker sandbox, gates the result, has a **cold independent reviewer** judge the
diff, opens a PR, and lets CI decide. The model never picks its own task and never reports its own
success.

Extracted from the `harness/` directory of
[functional-event-sourcing-with-yaes](https://github.com/rcardin/functional-event-sourcing-with-yaes)
and being generalized into an installable tool. See [#1](https://github.com/rcardin/litter-box/issues/1)
for the design record.

## Status

`litter-box init`, `litter-box eject`, `litter-box run`, `litter-box watch` and `litter-box tail`
exist and work; see [Getting started](#getting-started) and [Watching a run](#watching-a-run) for
what each subcommand does. A tagged release publishes a binary, a sandbox base image and a
homebrew formula together, from one `.github/workflows/release.yml`; see
[#6](https://github.com/rcardin/litter-box/issues/6) for the packaging work that made that true.

[Install](#install) is the fastest path to a working binary. Check the
[releases page](https://github.com/rcardin/litter-box/releases) and the
[ghcr package page](https://github.com/rcardin/litter-box/pkgs/container/litter-box-base) for what
is published right now rather than trusting a version number written here. Building from source with
scala-cli stays the contributor path (`scala-cli --power package . -o lb --assembly`, see this
repo's own CLAUDE.md for the full command reference).

### Version policy

litter-box ships `0.x`. No stability promise holds until `1.0`: the CLI grammar, the config schema,
the scaffolded files, the base image contract and the log format can all change in a `0.x` release
without a deprecation cycle. Pin an exact version if a change landing under you would be a problem,
and read a release's notes before bumping past it.

`checkedShape`, the opt-in half of the compile-time review-reachability macro (`in.rcard.litterbox
.checkedShape`, public since `0.2.0`), is exactly this kind of change as of `0.3.0` (issue #43
review round 2): the walk it splices was widened to read `Nil`, `List.empty`, an unqualified
reference to a `val` member of the enclosing class, out-of-order named arguments, and a local
`val`-bound `Transition` as literal pieces it can now parse instead of silently falling back on.
That means a `Shape` whose only previously unreadable piece was one of those now gets fully checked,
and can newly fail to compile if that check finds a genuine review-reachability violation it could
not see before. The direction is safe, strictly more checking, never less, but it is still a
behaviour change to a published `inline def`; pin an exact version if that possibility is a problem
for you.

`Node.apply` (`in.rcard.litterbox.Node`, public since `0.1.1`) picks up a second such change as of
`0.3.0` (issue #43 review round 4): it now derives `Guard.RequiresReview` on the real, constructed
`Node` whenever the node's own input type extends `RequiresReviewInput`, regardless of what `guard`
was written as. A node built with `guard` at its default `Guard.Open` and an input type extending
that marker used to get exactly `Guard.Open`; it now gets `Guard.RequiresReview` instead. The
direction is again safe for `Runner.validate`, strictly more graphs get correctly flagged, never
fewer, but any code that read a `Node`'s own `guard` field back out and compared it against
`Guard.Open` for a marker-carrying node can observe the difference; pin an exact version if that is
a concern.

`checkedShape` picks up a third such change as of `0.3.0` (issue #43 review round 5): the walk it
splices now also reads an explicit `guard = Guard.RequiresReview` argument, named or positional,
written at an inline `Node(...)` construction inside the `Shape` itself, where before it read only
the `RequiresReviewInput` marker on a node's own input type. A shape reaching such a node with no
reviewer on the path used to compile and be rejected by `Runner.validate` at startup; it now fails
to compile. The direction is again safe, strictly more checking, never less, and the scope is inline
constructions only: a node built as a `val` elsewhere and merely referenced in the `Shape` carries
no argument list the macro can read, so an explicit `guard` on one of those is still checked at
startup and not while you compile. It is still a behaviour change to a published `inline def`; pin
an exact version if that possibility is a problem for you.

That same derivation is a source-breaking change of its own as of `0.3.0` (issue #26's PR review).
It used to be read off a `using GuardOf[I]` clause on `Node.apply`, and `GuardOf.open[I]`, the given
that answers "no marker" for every `I`, was public and nameable, so a call site could pass it
explicitly and get `Guard.Open` stamped onto a node whose input type extends `RequiresReviewInput`,
defeating the derivation entirely. `Node.apply` is now an `inline def` that reads the marker off its
own type argument through a macro, so: `GuardOf` and `LowPriorityGuardOf` are gone from the public
API, `Node.apply`'s `using` clause takes `TrustOf[O]` alone, and code that passed either given by
hand no longer compiles. Ordinary call sites, including every form the scaffold and this README
show, are unaffected. `TrustOf[O]` deliberately stays an ordinary `using` parameter you can answer
for yourself; that residual, and the runtime check that catches it, are documented on `Trust` and
`Runner.step` (`Kit.scala`).

`LitterBox.graph` changes shape in the next release after `0.3.0` (issue #67), and this is the
largest source-breaking change on this list. It used to take a `Workflow` full of `Next.Goto`
closures AND a hand-written `Shape` restating the same edges; it now takes a `name`, ONE `Plan`
naming each edge exactly once, and an optional `stages`, and derives both the walk and the `Shape`
from that single value. `Plan` and `Edge` are new; `checkedShapeStrict` is gone, replaced by
`checkedPlan`; `Shape`, `Transition`, `Next` and `Workflow` all stay exactly as they were, for the
shipped graph and for anyone composing `Runner.run` directly. A `loop.scala` written against
`0.1.0` through `0.3.0` has to be rewritten onto the new call, which is the point: the old signature
made it possible for the graph that was checked and the graph that ran to be two different graphs,
and no additive change can take that possibility away.

## Install

Every published version is on the [releases page](https://github.com/rcardin/litter-box/releases);
the formula in `rcardin/homebrew-tap` tracks whichever one is newest. See
[docs/homebrew-tap-setup.md](docs/homebrew-tap-setup.md) for how that tap and its push credential
are set up, which matters if you are forking this project rather than installing it.

```bash
brew tap rcardin/tap
brew trust rcardin/tap
brew install litter-box
litter-box init
```

The `trust` line is not optional and not a workaround. Homebrew 6 refuses to load a formula from any
tap outside the official ones until you say so explicitly, failing with `Refusing to load formula
rcardin/tap/litter-box from untrusted tap rcardin/tap`. A formula is Ruby that brew executes on your
machine with your permissions, and a tap is a GitHub repository whose owner can change that Ruby at
any time, so brew is asking you to decide whether you trust this project with that. It is the same
question this project asks about the code its own agents write, which is why the answer belongs to
you and the step is documented here rather than hidden inside an install script. Trusting is
recorded once per machine in `~/.homebrew/trust.json`, or under `$XDG_CONFIG_HOME/homebrew` if that
is set. `brew trust --formula rcardin/tap/litter-box` narrows the decision to this one formula
instead of the whole tap, and is the tighter choice if you want it.

Bare `brew install litter-box`, without the `tap` line first, resolves against `homebrew-core`, the
tap brew searches by default, and litter-box is not in it: `homebrew-core` has its own review bar for
what it accepts, and a `0.x` project that changes its own grammar between releases with no stability
promise (see [Version policy](#version-policy) above) is not a fit for that bar today. `brew tap
rcardin/tap` points brew at `github.com/rcardin/homebrew-tap` instead, so the second command
resolves the name there. The repository is named with the `homebrew-` prefix because that is brew's
own naming convention for a tap; typing `rcardin/tap` (the short form) is what makes brew look for
`rcardin/homebrew-tap` in the first place, so the two spellings are not a typo, they are the same
rule applied at two different points.

The formula depends on `openjdk@21` and installs a single self-executing jar as `litter-box`; see
`.github/formula/litter-box.rb.template` for exactly what it does, and
[.github/workflows/release.yml](.github/workflows/release.yml) for how a tag turns into a published
formula, binary, base image and library together.

The same tag also publishes two artifacts to Maven Central, `in.rcard::litter-box` and
`in.rcard::litter-box-testkit` (see [Testing your own loop](#testing-your-own-loop) for the second
one). You do not install either half:
`litter-box init` scaffolds a `.litter-box/loop.scala` that opens with `//> using dep
in.rcard::litter-box:<version>`, pinned to the exact version of the binary that scaffolded it, and
`scala-cli` fetches it on the first run. See
[docs/maven-central-setup.md](docs/maven-central-setup.md) for how that publishing is set up, which
again matters if you are forking rather than installing.

### Write your own loop

`.litter-box/loop.scala` names `LitterBox.shipped` by default, the exact `PICK -> IMPLEMENT -> GATE ->
REPAIR -> REVIEW -> PR -> CI -> MERGE` pipeline described in [The pipeline](#the-pipeline). A repo that
wants a genuinely different graph, not just different config, builds one through `LitterBox.graph`
instead and edits that one line:

```scala
//> using dep in.rcard::litter-box:<version>
import in.rcard.litterbox.*

@main def loop(args: String*): Unit =
  LitterBox.run(
    LitterBox.graph(
      name           = "my-loop",
      plan           = Plan(
        entry = Pick,
        edges = List(
          Edge.To(Pick, Review, picked => Some(picked)),
          Edge.Exit(Review, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = cfg => cfg.repairBudget + 1,
      startInput     = n => MyStart(n),
      stages         = myStages
    ),
    args
  )
```

Your own `Node`s and your `Plan` are ordinary values built from the public kit
(`in.rcard.litterbox.{Node, Plan, Edge}`). The `Plan` is the ONE place your graph's edges are written
down: `LitterBox.graph` derives from it both the walk the runner executes and the `Shape` the startup
validator and the compile-time check read, so the graph that is checked and the graph that runs cannot
be two different graphs. Each edge says where it leaves, where it arrives, and how the arriving node's
input is computed from the departing node's output; the `Option` it answers with is what lets one node
declare several outgoing edges as alternatives, the first one answering `Some`, in the order you wrote
them, being the edge taken. A node whose every outgoing edge declines what it produced is an infra
fault (rc 50), never a quiet success. One consequence worth knowing up front: an edge sees nothing but
the output of the node it leaves, so anything a later edge needs, a round counter in a review/fix
cycle, say, has to ride in the values your nodes produce rather than being captured in a closure.
`dispatchBudget` is always a
`Config => Int`, never a `Ledger` you build yourself, because the runner, not the graph, owns the
counter and the timeout clock for every node it walks. That function shape means `dispatchBudget` CAN
read `.litter-box/config.conf` at runtime, the way the sketch above reads `cfg.repairBudget`; it does
not mean it must, `dispatchBudget = _ => 42` is just as legal a value. That constant is the one
accepted, named exception to decision 17's own promise that nothing is expressible in both
`config.conf` and `loop.scala`: the intended form reads the knob from `config.conf`, and a constant is
the one way a budget number can still land in `loop.scala` instead, something decision 17 asks a graph
author not to do rather than something it makes impossible. `LitterBox.graph`'s own scaladoc
(`src/LitterBox.scala`) states this once; nothing here restates it further.

Precisely what `dispatchBudget` bounds, stated exactly: it is a hard ceiling on the TOTAL dispatches
your graph's nodes may make in one tick, and it is enforced in two places. Before a node's `run`
starts, on the branch where its `probe` answered `None`, a node declaring `Cost.OneDispatch` is
refused, and the tick parks, if nothing is left. Then every real
`agents.*` call any node makes, from `probe` or from `run`, whatever `Cost` that node declared, is
charged at the capability itself and REFUSED once the counter is empty, as an infra fault (rc 50) that
never reaches the agent. So `dispatchBudget = _ => 0` stops a `Cost.NoDispatch` node from dispatching
at all, and a node that dispatches three times against a budget of one gets one dispatch and a fault,
not three dispatches and a silent overspend. A refusal is signalled as an infra fault, not a value
your node's own code can pattern match on, but the fault itself travels through `boundary.break`, an
ordinary `RuntimeException` underneath, so a node body that wraps its own dispatch call in a broad
catch can still observe the refusal and keep running past it rather than the tick abandoning outright.
What a catch can never recover is the dispatch itself, refused before it ever reaches the agent, and
on the review path there is no value to catch your way back to at all: a refusal there raises before
`AgentDispatch.review` ever mints, so `AgentDispatch.Judged` stays impossible to obtain without a real
cold dispatch behind it.

Declaring `Cost` honestly still matters, and now for a sharper reason than tidiness: `Cost` decides
the SHAPE of the refusal. An honest `Cost.OneDispatch` node whose `probe` answers `None` without
itself dispatching, and whose `run` the budget then cannot afford, is parked before that `run` starts,
a resumable terminal that leaves the world untouched. A node that declares `Cost.NoDispatch` and
dispatches anyway is faulted partway through whatever its body already did. The park covers `run`
only: a node whose own `probe` dispatches is charged at the capability like any other real dispatch,
so on an empty budget it faults (rc 50) however honest its `Cost`, and whatever that `probe` did on
its way to the dispatch call has already happened.
Declare `Cost.OneDispatch` on every node that dispatches, size `dispatchBudget` for the dispatches
your nodes really make, and the budget behaves exactly like the ceiling it looks like.

`plan` above has to be written exactly like that, a literal `Plan(entry = ..., edges = ...)`
expression right at this call site, never a `val` you build first and pass by name. `LitterBox.graph`
compile-time checks that every path into a node whose own input type extends `RequiresReviewInput`
crosses a reviewer first, the same macro `checkedShape` runs, but unconditionally rather than opt in:
pass anything other than a literal here and the call refuses to compile at all, naming what it needs
instead, rather than silently skipping the check the way an opt-in macro would. This reads TWO facts and
combines them with an OR, the same combination `Node.apply` performs on the real constructed value: the
INPUT TYPE, whether it extends `RequiresReviewInput`, and the hand-written `guard =
Guard.RequiresReview` argument you can also pass to `Node`, named or positional, in the one place the
macro can see one. The two halves do not reach equally far, and, since issue #67 review made every node
named in a `Plan` refuse to compile if it is written as an inline `Node(...)` call rather than bound to
a `val` first (the `plan` paragraph below has the reasoning), only one of them can ever fire through
`LitterBox.graph` at all now. The marker half is read off the reference's own static type, so it fires
through a plain `val` reference exactly as it always did. The argument half needs the SOURCE of
an inline `Node(name = "...", ...)` call to read a `guard = Guard.RequiresReview` argument off, and a
`Plan` never has one of those to read: this check sees only the `val` reference itself, never the
initializer that built it, so a `guard = Guard.RequiresReview` you wrote when you declared that `val`
stays invisible to `LitterBox.graph` at compile time and is caught only by `Runner.validate` at startup,
which reads the constructed node's real `guard` field rather than the source that produced it
(`ARCHITECTURE.md` has the fuller reasoning for why the two checks read different facts on purpose).
The reverse used to be a real gap and no longer is: a node whose input type DOES extend
`RequiresReviewInput` gets `Guard.RequiresReview` stamped onto its real `guard` field by `Node.apply`
itself now, regardless of whether you wrote `guard = ...` at all, so both the compile-time macro
(reading the marker) and `Runner.validate` at startup (reading the now-consistent field) catch a node
like that; before this, only the compile-time macro could, and only if the graph was written as a
literal here. If your graph genuinely cannot be written as a literal (built in a loop, read from
configuration, ...),
`LitterBox.graph` is not for you; compose `Runner.run` directly instead, outside the compile-time half
of this guarantee, though `Runner.validate` still runs against whatever `Shape` you hand `Runner.run`.

Five different compile errors can fire here, worded differently on purpose so none is mistaken for
another (issue #43 review round 3, BLOCKER 1, adding the third; issue #67 review, adding the fourth and
the fifth): `plan` was not recognisably a `Plan(...)` literal at all (a `val`, a function result, an
indirection of any kind); `plan` genuinely was a literal written right here but one piece inside it (a
node reference, an `Edge`, a list) was not written in a form the check can read; every piece WAS
readable but two different references canonicalise to the identical node identity while disagreeing on
whether that node needs review, most often a `class` and its own companion `object` each declaring a
member of the same name; one readable reference named a node built by an inline `Node(name = "...",
...)` call, refused outright rather than accepted, because `Runner`'s own walk of a `Plan` links one
edge to the next by which runtime OBJECT a node is, never by this check's own canonical name, and an
inline call allocates a fresh object at every place it is written, so the identical `Node(name = "X",
...)` written twice, or even once with nothing else naming that same object, is never the value a real
run needs; or two different references canonicalise to the identical node identity while AGREEING on
both `trust` and `guard`, still most often a `class` and its own companion `object` declaring a member
of the same name, this time with both sides' input types agreeing on review, refused just as hard as the
disagreeing case above because `Plan.workflowOf` still links one edge to the next by which runtime
OBJECT a node is, never by this check's own canonical name, so two agreeing declarations are still two
different objects, and a real run dead ends the moment it reaches one without an edge naming that exact
object as its own source, after both objects already produced whatever side effects they carry. The
forms it
can read: a node or `Edge` referred to through a stable path (a top-level `val`, an `object` member, or
an unqualified `val` member of an enclosing `class`, `this.A` written bare as `A`); an inline
`Edge.To(from, to, input)` or `Edge.Exit(from, exit)` call; an `Edge` bound to a `val` local to the same
block as the `LitterBox.graph` call that uses it; a list written as `List(...)`, `List.empty`,
`List.empty[...]`, or `Nil`; and `Plan`'s own two named arguments in either order. A node itself has to
be bound to a top level `val` or an `object` member first, never written as an inline `Node(...)` call
at its point of use in the `Plan`; `checkedShape` still reads an inline `Node(...)` call directly, since
a `Shape` alone is never walked by runtime object identity the way a `Plan` is. What
it cannot read, and never will: a node built by a `def`, because this check reasons about the SOURCE
written at this call site, never about a value it would have to run code to get. That includes a
config-parameterised node-building idiom (`def openPr(cfg: Config): Node[...] = ...`, used as
`openPr(cfg)` in `entry`/`edges`): `LitterBox.graph` cannot express that shape, on purpose,
RFC #26 decision 16 records "graphs cannot be assembled dynamically" as a deliberate consequence of
this whole compile-time route, not an oversight left for a later issue. Read the config value you need
inside the node's own `probe`/`run` body instead (both already receive an ambient `Config`, derivable
from the `Caps` every node body is handed), behind one plain, top-level `val` standing for the node,
rather than a `def` that builds a differently configured `Node` per call. `Machine.Gate`, one of the two
shipped nodes this library exposes for your own graph to compose, is written that way for exactly this
reason. A few more forms read like
something on the readable list above but are not. An `export`ed member (`export Source.*`) reads like
an `object` member at the call site but is a compiler-synthesised `def` forwarder, never a `val`, so it
is unreadable for the identical reason a `def`-built node is; refer to the original `val` on `Source`
directly instead. And an INSTANCE-QUALIFIED receiver, `holder.node` for an ordinary, non-singleton
`holder` (a `val holder: SomeClass = ...`, however many stable aliases stand between it and this call),
is unreadable too, deliberately and unconditionally (issue #43 review round 4): this check can prove
`holder` is a STABLE value, never rebinding, but has no way to prove whether a SECOND such reference
elsewhere in the same shape names the same instance or a genuinely different one, and guessing either
answer risks disagreeing with what the graph actually does at runtime, so it declines every
instance-qualified receiver rather than guess at some and not others. Two things follow from that:
first, an idiom like `a.node`/`b.node` on two truly distinct instances is safe to write but is not
CHECKED by `LitterBox.graph`'s macro at all (it falls straight to the "could not identify" error), so
lean on `Runner.validate` at startup for a shape built that way, or restructure the reference onto a
top-level `val` or an `object` member, either of which this check reads directly; second, one `Node`
VALUE bound under two different top-level `val`s (`val
a = mkNode(); val b = a`) is unreadable for a related but distinct reason, not an instance-qualified
receiver at all, but two ordinary stable paths that happen to alias the same value: this check keys each
`val` on its own declaration, so `a` and `b` key differently even though they are the same `Node` at
runtime, and no widening of this check can close that without evaluating source it is not allowed to
run. `Runner.validate`, which sees the real, already-resolved `Node` values rather than the source that
built them, is the backstop for both of these, unconditionally, every tick.

`startInput: Int => Fault ?=> I` gets the tick number and a `Fault`, not a `Caps`: it runs before this
tick's real dispatch budget exists, so no capability is in scope inside `startInput`, only
`fault.raise` to abort the tick early. A first step that genuinely needs a capability belongs in a
node, not in `startInput`. That is a claim about what `startInput` can SUMMON, not about what it can
ever be handed: a node body from a PREVIOUS tick can stash a `Caps` it summoned honestly (`var stolen:
Option[Caps] = None` closed over by that node's own `run`) and a later tick's `startInput` can read that
stashed value back and call a capability through it, a real dispatch, running outside every `Ledger`,
`Timeout` and shape check that tick's own walk would otherwise apply. This is a documented residual
(named beside the `inline$graphImpl` one in `LitterBox.graph`'s own scaladoc), not a route to a forged
result: every `Judged` obtained this way was minted honestly by a real dispatch, only outside the
accounting a same-tick capability call would have gone through.

**Composing a shipped node.** Your graph does not have to be built entirely out of your own nodes.
`Machine.Gate`, the shipped pipeline's own FAST gate, is public and is an ordinary `val`, so a `Plan`
literal can name it exactly like one of yours:

```scala
Edge.To(MyStart, Machine.Gate, (i: Machine.GateInput) => Some(i)),
Edge.To(Machine.Gate, MyRepair, { case Machine.GateVerdict.Red(log) => Some(log); case _ => None }),
Edge.To(Machine.Gate, MyReview, { case Machine.GateVerdict.Green => Some(()); case _ => None })
```

It stages the working tree, runs your configured `gate.cmd` in the sandbox, logs the verdict and hands
you back `Machine.GateVerdict.Green` or `Machine.GateVerdict.Red(gateLogPath)`. A gate TIMEOUT never
arrives as a value: it is an infra fault and short circuits the tick, which is why the verdict type is
its own two case enum rather than the three case `GateResult`. Three things to know before you wire it
in. You build the `Machine.GateInput`, and it carries a `Machine.Cursor` whose `iter`, `issue`, `pass`
and `budget` fields are copied verbatim into every `status.jsonl` event this node emits, so leaving
`issue` empty or `iter` at zero quietly degrades `litter-box watch`; `pass` is also what the gate log
filename is built from, so two gate runs sharing a `pass` overwrite one another's log. The node emits
the phase string `FAST_GATE`, which only the shipped pipeline's own stage set declares, so unless your
`stages` argument declares a `Stage("FAST_GATE", ...)` of its own the events are written but the banner
draws no chip for them. And it carries no review guard, because it publishes nothing outward: it is
safe to place anywhere in your graph, unlike the shipped nodes that open PRs or merge, which is exactly
why it is one of the two shipped nodes that are public, the other being `Machine.AskHuman`, a needs-human
parking step made public by issue #44 before this one. `Machine.Gate`'s own scaladoc records the full
decision, including why every other shipped node stays private.

**The patch guard.** Every graph that dispatches an agent has to decide what that agent's patch may
be allowed to touch, and you do not write that decision yourself. `PatchGuard.stage` is the whole
patch seam and it is public:

```scala
caps.agents.worker(Role.IMPL, promptFile, patchOut, logFile, currentPatch) match
  case DispatchOutcome.TimedOut => fault.raise("IMPL dispatch timed out")
  case DispatchOutcome.Done     => ()

PatchGuard.stage(patchOut) match
  case Staged.Ok(patch)  => // applied to the index; carry `patch` onward
  case Staged.Empty      => // the agent produced no diff
  case Staged.Oversize   => // over `patch.max-bytes`; PATCH-REJECTED.md staged instead
  case Staged.Protected  => // touches a `protect` glob; PATCH-REJECTED.md staged instead
  case Staged.ApplyFail  => // git apply refused it; an infra fault at every shipped call site
```

It resets the working tree to the pristine base BEFORE it reads anything about the patch, rules on the
patch against your `patch.max-bytes` and your `protect` list, and then either applies it with
`git apply --index` or stages a `PATCH-REJECTED.md` marker in its place, so a rejected patch is never
on the branch and the audit PR still has a diff to open with. It logs its own two `patch guard:` lines
and raises nothing: what a refusal MEANS is yours to decide from the `Staged` value you get back.

The dispatch stays yours, deliberately: what it costs is charged against your node's own `Cost`. So
does the routing, which is why nothing here emits a `status.jsonl` event or decides what a rejection
should do next.

`PatchGuard.rule(bytes, numstat)` is the same ruling with no capabilities, answering `Ruling.Clean`,
`Ruling.Oversized` or `Ruling.Protected`, if you want to assert on the decision in a unit test without
a `TestWorld` around it. `PatchGuard.touchesProtected(protect, numstat)` is the glob matching alone,
JDK `glob:` semantics: a single star stops at a directory separator, a double star crosses it, and a
bare filename is an exact match rather than a prefix. Do not reimplement it. The `protect` list your
config carries has already been unioned with the reference floor, so `.litter-box/**` is covered
whether or not you named it.

**Putting a human's words in front of a worker.** If a node of yours reads issue comments and
splices them into a prompt, do not build that block yourself. `Reply.splice` is public and is the one
render half the shipped loop uses too:

```scala
val commentsSlot: String = caps.gh.issueComments(issue) match
  case None          => "[harness: comments could not be read]" // your own sentinel, not Reply's
  case Some(entries) => Reply.splice(entries) // neutralised and capped per entry; you still supply the fence
```

It takes the entries as `Caps.GitHub.issueComments` returns them, oldest first, and answers one block
of prompt text. Every comment is text an attacker can write, so before joining anything it neutralises
the entry grammar itself (a body that forges its own `@login (ASSOC):` line or a separator, trying to
make the block read as if someone the repository trusts wrote the next paragraph), defuses a forged
`<untrusted-comments>` fence tag (a body that closes the fence early and lands the rest as
authoritative looking text at top level), and only THEN caps each entry to its own share of the
harness's character budget. That order is the protection and it is why this is one call rather than
three: capping first can cut a forged boundary in half and change whether it still parses, and
escaping after capping cannot see the text the cap already dropped. Capping PER ENTRY rather than over
the joined block is the other half: no commenter's length can push another commenter's text out of the
prompt, whichever end of the thread they sit on.

An empty list answers with the harness's own `[harness: no comments]` sentinel, so an empty slot says
so in words. A comment read that FAILED is yours to render, deliberately: this function is handed the
entries that were read and cannot tell a thread with nothing in it from a thread nobody could see, and
those two facts must not collapse into one string a worker reads the same way.

**Deciding which comments are the reply.** `Reply` is both halves of the protocol, not just the
render. `Reply.since(marker, viewer, comments, claim)` is the other one: it cuts the thread at the
LAST comment from `viewer` whose body STARTS with `marker`, and answers a `Reply.Since` carrying the
entries after it that count as a reply, the entries that do not, and the accepted authors,
deduplicated in first seen order. Counting means an author association of `OWNER`, `MEMBER` or
`COLLABORATOR` and a body that is not blank, so a drive by comment from an account your repository
has never vouched for cannot resume anything. The marker token and the viewer login are yours to
supply: this file is on the kit tier and may not read the shipped loop's own park marker.

`claim` is the argument to read twice. It is your statement about a marker the function cannot verify
for itself, and it decides one thing only: what an ABSENT marker means. `Reply.Marker.Required`
answers no reply, and is the honest claim unless you hold evidence, read outside this call, that a
marker was posted on this thread at some point. `Reply.Marker.Proven` says you do hold that evidence,
and a thread with no marker then counts entirely as the reply. On a thread that DOES contain a marker
the two answer identically. In the shipped loop the only such evidence is the `parked` label, which
the pick phase confirms before it asks, because the one place that applies that label also posts the
marker; the park node's own probe claims `Required`, because it runs the first time an issue is ever
parked. Claiming `Proven` without evidence is how an issue's ordinary discussion gets read as an
answer to a question nobody asked.

This whole surface sits under the same `0.x` no-stability-promise policy as everything else in this
project (see [Version policy](#version-policy) above): pin an exact version if a shape change landing
under you would be a problem. That covers `Machine.Gate`, `Machine.GateInput`,
`Machine.GateVerdict`, `PatchGuard`, the `Ruling`/`Staged` types and all four of `Reply`'s public
names (`since`, `splice`, `Marker` and `Since`) too, on exactly the same terms and with no extra promise attached to them for
being lifted out of the shipped pipeline. What IS promised for as long as those names exist is what
they mean, not their shape: a gate timeout stays an infra fault and never becomes a `GateVerdict` case,
the node never publishes outward, and it reads your `config.conf` per tick rather than at the moment
your graph is built.

**A full worked graph, compiled and walked by this repo's own suite.**
[`test/ReviewFixLoopExample.scala`](test/ReviewFixLoopExample.scala) is a complete consumer
`loop.scala`: its own edge types, a patch seam that inspects before it applies, and a bounded
`REVIEW -> FIX` cycle that fans out one fixer dispatch per reviewer finding and stops after three
rounds on a needs-human PR. Its `Fix` node takes an input extending `RequiresReviewInput`, so the
macro above proves at compile time that no path reaches the fixer without a cold review first, which
is why a red gate there travels into the review prompt rather than straight into the fixer. It lives
under `test/` rather than under `docs/` on purpose: it is compiled by `scala-cli test .` and driven
end to end by [`test/ReviewFixLoopExampleSpec.scala`](test/ReviewFixLoopExampleSpec.scala) against a
`TestWorld`, so it is a build failure the day it stops matching the API, instead of an example that
quietly rots. Its own header comment lists the three things it carries for this repository that your
copy does not.

### Testing your own loop

The same tag publishes a second artifact, `in.rcard::litter-box-testkit`, which is exactly what this
repository tests itself with: scripted in-memory handlers for every capability, plus the interaction
recorder every scenario asserts against. No Docker, no network, no credentials, and no `claude`
binary; the whole of this project's own suite runs on it, which is why CI needs nothing installed but
`scala-cli`.

```scala
//> using test.dep in.rcard::litter-box-testkit:<version>
```

**`test.dep`, never `dep`.** The testkit's `TestWorld` lives inside the library's own package on
purpose, so its `agents` really is an `AgentDispatch` and `world.agents.review(...)` really does mint
an `AgentDispatch.Judged`, out of a scripted fake with no reviewer behind it. That value clears
`Guard.RequiresReview` at both gates, because it is not an imitation of a trust token, it is one. On a
test classpath that is the entire point, running against a fake world is what you asked for. On your
main compile classpath it is a review you forged for yourself.

Starting the loop with the testkit reachable is refused. `lb` and `LitterBox.run` both check, on the
loop subcommand only, whether the testkit is loadable in the JVM about to run; if it is, startup logs
a `FATAL` naming this rule and exits 1, before any node runs. `init`, `eject`, `watch`, `tail` and
`--help` are untouched, so the commands you need in order to fix the declaration keep working. Read
that check for what it is: it catches `dep` written where `test.dep` was meant, and nothing more. A
renamed or shaded copy of the testkit passes it, and so does your own production code calling
`TestWorld` directly instead of starting the loop. `src/Caps.scala`'s `AgentDispatch` scaladoc states
the residual in full.

**The version pairing.** The testkit and the library are released together from one tag and pin to
each other exactly. Unlike `LitterBox.graph`, the testkit hands you the capability traits themselves,
so a testkit compiled against one library version has no compatibility story at all against another,
and under the `0.x` policy above a trait gaining one method is an ordinary release. Declare both at
the same version, or neither.

A worked example: your own two-node graph, one scripted dispatch, asserting on the outcome AND on the
call sequence.

```scala
//> using dep in.rcard::litter-box:<version>
//> using test.dep in.rcard::litter-box-testkit:<version>
import in.rcard.litterbox.*
import in.rcard.litterbox.Caps.given   // the individual capabilities, derived from the ambient Caps

val First: Node[Unit, Unit] = Node(
  name = "First", cost = Cost.OneDispatch, timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[AgentDispatch].worker(Role.IMPL, "first.md", "first.patch", "first.log", None)
    NodeOutcome.Done(())
)
val Second: Node[Unit, Unit] = Node(
  name = "Second", cost = Cost.NoDispatch, timeout = Timeout.Unbounded,
  probe = _ => None,
  run = _ =>
    summon[Caps].logger.log("Second ran")
    NodeOutcome.Done(())
)

// in your test: a scenario body, whatever framework you use
def myLoopRunsBothNodes(): Unit =
  val world = new TestWorld
  world.implScript = Script.WorkerScript.Produces("1\t0\tsrc/Slice.scala") // numstat DSL

  val exit = world.runGraph(
    LitterBox.graph(
      name = "my-loop",
      plan = Plan(
        entry = First,
        edges = List(
          Edge.To(First, Second, _ => Some(())),
          Edge.Exit(Second, _ => Some(LoopExit.Success))
        )
      ),
      dispatchBudget = _ => 1,
      startInput = _ => ()
    )
  )

  assert(exit == LoopExit.Success)
  assert(world.callCount("dispatch IMPL") == 1) // the recorded call sequence
  assert(world.logged("Second ran"))
```

`First` and `Second` are named in the `Plan` literal through plain `val` references, and that is the
actual constraint: `LitterBox.graph` reads the SOURCE of the `Plan` at its own call site, so every
element written there has to be a stable path, a top level `val`, an `object` member, or an unqualified
`val` member of the enclosing `class`, never a node built inline at its point of use in the `Plan` and
never a helper `def` CALL written straight into it, `entry = myNode("First")`: neither is a runtime
object this walk, or `Runner`'s own walk of the `Plan` at runtime, could ever name a second time, and
`Runner`'s own walk needs to. The same helper is fine when its result is bound to a `val` first and the
`Plan` names the `val`. [Write your own loop](#write-your-own-loop) above has the
full list of what that check can and cannot read.

**One node on its own.** `runNode` is the unit-test sibling of `runGraph`: it steps exactly one `Node`,
with no `Plan` and no `LitterBox.graph` call wrapped around it, so the answer you read
back is about that node and nothing else.

```scala
def myNodeParksOnAnEmptyBudget(): Unit =
  val world = new TestWorld

  // Right(NodeRun(outcome, remainingDispatches)); Left(exit) if the node raised a fault
  val ran = world.runNode(First, (), dispatchBudget = 1)

  assert(ran == Right(NodeRun(NodeOutcome.Done(()), 0))) // dispatched once, budget spent
  assert(world.callCount("dispatch IMPL") == 1)          // the same recorder buffers as runGraph

  val starved = world.runNode(First, (), dispatchBudget = 0)
  assert(starved == Right(NodeRun(NodeOutcome.Stopped(LoopExit.Parked), 0)))
```

`dispatchBudget` is the same number, meaning the same thing, that `LitterBox.graph` takes, and it
defaults to `1`. The `Either` is what a fault does: a node calling `Fault.raise` abandons the run
rather than returning, and that lands as `Left(LoopExit.InfraFault)` with the fault line in `logLines`
and the rc-50 notification in `notifications`. `cfg` is there too, `world.runNode(n, i, cfg = Config(dryRun = true))`,
for a node that reads `Config` off the ambient `Caps`. There is no `iteration` parameter: an iteration
number is something a whole tick has, and one node stepped alone is not a tick.

A dispatch records the model it asked for as the last field of its call string, `model=` and empty
when the role has none, so a scenario passing `cfg = Config(models = AgentModels(fix = Some(ClaudeModel.Haiku)))`
can assert that a fixer really asked for the cheap model while the reviewer asked for the strong one.
The recorded field is the model id the container would have seen, `model=claude-haiku-4-5`, not the
name the config file spells.

Every capability is a `var` or a scripted list on the `TestWorld`: `implScript`/`fixScripts` for the
worker, `reviewScripts` for the reviewer, `files` for what a dispatch wrote, plus `cleanTree`,
`applySucceeds`, `fetchSucceeds` and friends for the git and GitHub answers. Everything it observed
lands in `calls` (`callCount`), `logLines` (`logged`), `notifications`, `commitMessages`,
`pushedBranches` and `files`. Patch contents use a tiny numstat DSL, one `added<TAB>deleted<TAB>path`
line per file, so a scenario can make a patch touch a protected path without producing a real diff.

The supported surface is `TestWorld` (its scripting fields, its recorder buffers, `runGraph`,
`runLoop` and `runNode`), `NodeRun`, the `Script` object the scripting fields take their values from
(`Script.WorkerScript`, `Script.ReviewScript`, `newFilePatch`, `approveReview`), `FakeClock` and
`buildCaps`. Everything else the artifact happens to expose because it lives in the library's package,
`Machine.runOnce` included, is internal and moves without notice.

`withFaulting` is in the jar and is listed here only to say what it is not: it takes a
`Faulting ?=> T`, and `Faulting` is `private[litterbox]`, so a consumer can call `withFaulting { ... }`
and can never write a body that actually faults, having no way to name the type it would summon. It is
there for this repository's own specs, which drive `Runner.step` directly. `runNode` is what you want
instead: it establishes that same boundary internally and reports what crossed it as the `Either` above.

Two rough edges worth knowing before you copy a default: `Script.newFilePatch` hardcodes
`src/main/scala/Slice.scala`, and `TestWorld`'s GitHub defaults centre on issue 999. Both are this
repository's own conventions rather than anything meaningful to yours; script your own values rather
than asserting against those.

Neither `runGraph` nor `runNode` ever hands you a `Runner.Ledger`, deliberately, and you cannot
construct one either: its constructor is `private[litterbox]`. The runner owns the dispatch counter and
the timeout clock, so a budget of one behaves under test exactly as it does in production, and a
budget assertion you write means something. `runNode` mints a ledger for you from the `dispatchBudget`
you named and reports what survived as the plain `Int` in `NodeRun`, which is all a test needs and
nothing a node could spend from.

### Quickstart

From a fresh install, in the repo you want to run the loop against:

```bash
litter-box init
```

This writes `.litter-box/` and prints warnings and next steps naming exactly what it could not
answer for you; see the file table under [Getting started](#getting-started) for what each file is
and [Configuration](#configuration) for `config.conf`. Fill in the TODOs it printed (a real
`gate.fast` command, a credential in `.litter-box/.env`, the build tool layer in
`.litter-box/Dockerfile`), then take it for a first spin without touching a real issue:

```bash
litter-box run --dry-run
```

`run` is a plain alias for the bare `litter-box` invocation; either spelling starts the loop. Drop
`--dry-run` once you trust what it would do, and see [Running it](#running-it) for what the flag
actually skips.

## Getting started

The contributor path: building a binary from a checkout of this repo, for anyone working on
litter-box itself or who wants one ahead of the next tagged release. If you just want to run
litter-box against your own project, [Install](#install) above is faster.

```bash
scala-cli --power package . -o lb --assembly
```

Then, from the repo you want to run the loop against:

```bash
java -jar /path/to/lb init
```

`init` detects your GitHub remote (via `gh`), whether `build.sbt` is present, and your JDK version,
then writes seven files under `.litter-box/`:

| File | Purpose |
|---|---|
| `config.conf` | the loop's only mandatory config — see [Configuration](#configuration) below |
| `Dockerfile` | `FROM ghcr.io/rcardin/litter-box-base` plus a `TODO` for your JDK and build tool layer (see [The sandbox image](#the-sandbox-image)) |
| `allowlist` | egress hosts the sandbox proxy permits (see [The egress allowlist](#the-egress-allowlist)) |
| `prompts/conventions.md` | the one file you own — spliced into every prompt as `{{CONVENTIONS}}` |
| `.env.example` | the credential the sandboxed worker needs, and any other variable from [Running it](#running-it); meant to be copied to `.env`, never committed |
| `.gitignore` | ignores `logs/` and `.env` inside `.litter-box/` |
| `loop.scala` | names which pipeline this run walks, through the public `LitterBox`/`LoopGraph` API; `LitterBox.shipped` by default, or your own graph built with `LitterBox.graph` (see [Write your own loop](#write-your-own-loop)) |

It refuses to overwrite an existing `.litter-box/` unless you pass `--force`, and the check happens
before the first file is written, so a refused `init` never leaves a half scaffold.

`Dockerfile` and `config.conf`'s `gate.fast` always carry a `TODO`, whatever was detected, and
`gate.fast` is always written as `"false"`, a command that exists everywhere and always fails, so
iteration one goes honestly red instead of running something nobody confirmed. There are no
build-tool presets: what `init` detected is written into those TODOs as evidence for you to act on,
because seeing a `build.sbt` names the tool and never the command, and reading `java -version` names
what the host builds under and never what the container should carry. See
[Why the middle is a TODO](docs/base-image.md#why-the-middle-is-a-todo-and-not-a-preset) for the two
defects that taught us this.

`init` also prints up to three warnings (what it found or failed to find for your build tool, no
remote found, a JDK other than 21) and three next steps, none of which it can do on your behalf:

1. **Fill in `.litter-box/prompts/conventions.md`.** It is the highest-value file here: everything
   true only of your project — layout, test tiers, lint rules, "anything that has bitten you" — is
   spliced into every worker, fixer and reviewer prompt as `{{CONVENTIONS}}`. The prompt skeletons
   themselves, the protocol that keeps the loop honest, ship inside the litter-box artifact, not in
   your repo.
2. **Provide a credential.** `cp .litter-box/.env.example .litter-box/.env` and fill in
   `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY`. The loop reads that file at startup and passes
   what it finds to the sandboxed worker, fixer and reviewer. Exporting the variable instead works
   just as well; the file takes any other variable from [Running it](#running-it) too, and which one
   wins is the layering in [Configuration](#configuration).
3. **Create the four labels** the state machine drives on:
   ```bash
   gh label create ready && gh label create in-progress && gh label create blocked && gh label create parked
   ```

### Overriding a prompt skeleton

The four prompt skeletons — `iterate-prompt.md`, `fix-prompt.md`, `review-prompt.md`,
`grill-issue-prompt.md` — ship inside the jar. A repo that genuinely needs to change one, not just
its conventions, ejects it:

```bash
java -jar /path/to/lb eject iterate-prompt.md
```

This copies the built-in skeleton to `.litter-box/prompts/iterate-prompt.md`, which then wins over
the built-in for every later run. `.litter-box/prompts/**` sits inside `.litter-box/**`, which the
protected-path floor always covers, so a worker under harness cannot rewrite the prompt that
constrains it. Pass `--force` to overwrite one you already ejected. `fix-prompt.md` now splices in
third-party comments left on the issue while a run is in flight, so the fixer can act on steering it
would otherwise never see; a `fix-prompt.md` ejected before this change has no slot for them and
silently drops them until you re-eject it.

### The sandbox image

`.litter-box/Dockerfile` builds `FROM ${BASE_IMAGE}` — a build-tool-free image carrying temurin 21, a
pinned Claude CLI and a non-root user, with no build tool and no credentials baked in. Your
Dockerfile installs the JDK and build tool this project needs and nothing else: it needs no
`ENTRYPOINT`, because all three runners override it and run `gate.fast` (or the agent entrypoint)
through `bash -c`. `init` scaffolds that file with the install layer left as a `TODO`, which is the
whole of what litter-box claims to know about your build.

**A normal run never pulls that base image.** `build-image.sh` builds it locally from
`resources/sandbox/base.Dockerfile` on every run and passes the local tag as `--build-arg
BASE_IMAGE`, so the scaffolded `ARG BASE_IMAGE=ghcr.io/rcardin/litter-box-base:…` default only
applies when you run `docker build` against that Dockerfile by hand. The cost of that choice is a
local Claude-CLI install; what it buys is a sandbox that doesn't depend on pulling a prebuilt base
from ghcr or any registry credentials. `ghcr.io/rcardin/litter-box-base:0.1.0` is already
published, from the `v0.1.0` tag; a normal run still never pulls it, for the reason above, and
each further tag publishes the next version alongside it. See
[docs/base-image.md](docs/base-image.md) for the full contract the image guarantees.

## Configuration

One HOCON file at the repo root, `.litter-box/config.conf`. It is mandatory: with no config the loop
exits `50` (infra fault) and names `litter-box init`, rather than guessing and acting on the wrong
labels. Anything omitted falls back to the reference schema in `src/Settings.scala`; every knob
loop.sh took from an env var (`GATE_CMD`, `REPAIR_BUDGET`, `ITER_TIMEOUT`, ...) still overrides its
config key for a single run. The full precedence, `.litter-box/.env` and its two qualifications
included, is stated once in the `Settings` object's scaladoc in `src/Settings.scala`, so that this
README and `ARCHITECTURE.md` cannot drift from the code that applies it.

```hocon
instance-name = "litter-box"          # namespaces the Docker image/network/proxy/cache names
conventions   = ".litter-box/prompts/conventions.md"  # spliced into the worker, fixer and reviewer prompts as {{CONVENTIONS}}
stop-file     = "STOP.md"
log-dir       = ".litter-box/logs"

gate {
  fast      = "scala-cli test ."            # runs INSIDE the sandbox image, so read against its PATH;
                                            # `init` scaffolds `false` plus a TODO, never a guess
  sandboxed = true                          # false runs it on the host instead, with everything your shell has
  timeout   = 900
}
agent.model { impl = null, fix = null, review = null }   # haiku | sonnet | opus | fable; unset = whatever
                                                        # the `claude` CLI itself defaults to
issues.labels { ready = "ready", active = "in-progress", blocked = "blocked", parked = "parked" }
issues.park-on-exhaustion = true          # false opens a needs-human PR instead, the earlier contract
protect  = [".litter-box/**", ".github/**", "CONTEXT.md"]
budgets  { repair = 2, max-patch-bytes = 1000000 }
timeouts { iter = 1800, ci-wait = 900, ci-appear = 300, ci-appear-interval = 10, implement-slack = 300 }
```

`gate.sandboxed` defaults to `true`, so a `config.conf` written before the key existed inherits the
container without ever asking for it, and a `gate.fast` written for the host stops running on the
host the moment the binary is upgraded. A config that leaves the key unsaid therefore gets a
`WARNING` at startup naming both ways to answer it; writing `sandboxed = true` is as good an answer
as `sandboxed = false` and silences it just the same.

`agent.model` picks the model each of the three model touched dispatches asks for: `impl` for the
worker, `fix` for the fixer, `review` for the cold reviewer. Each is independently optional, and each
takes an environment override for a single run, `IMPL_MODEL`, `FIX_MODEL` and `REVIEW_MODEL`, on the
same precedence as every other key. UNSET means no model is passed at all: the loop adds no
`ANTHROPIC_MODEL` to the container, so whatever your `.litter-box/Dockerfile` sets, or the `claude`
CLI's own default, is what runs. There is no default model, deliberately, since shipping one would
move every repo's spend and every repo's answers at once.

The value is one of a closed set of names, `haiku`, `sonnet`, `opus`, `fable`, one per Claude family,
and anything else STOPS THE RUN with rc 50 before a single issue is read, naming the key and listing
the names you may write. That is the enum's whole point: an unrecognised model has no safe reading,
because the only alternative to failing is to dispatch on the CLI's default, and on `review` that is
a downgrade of the adversarial gate nothing in the loop could ever report. Each name dispatches on a
pinned full model id (`opus` sends `claude-opus-5`), never a floating family alias, so the same commit
of your repo asks for the same model next month as it does today; a new family release moves that id
in a litter-box release, with a release note, rather than under a running repo. Names are bare and
provider free by design: they stay the spelling you already type, and a second vendor's models would
join the same flat namespace rather than arrive as `claude:opus`.

The knob is PER ROLE, not per node. A consumer graph can dispatch a fixer from as many nodes as it
likes, and every one of them asks for `agent.model.fix`; a node cannot name a model of its own. That
is a decision, not a gap: the model is a property of what a dispatch IS, and `.litter-box/config.conf`
sits inside the `protect` floor, so an agent working under the harness cannot rewrite the models its
own next round runs on, which it could if the choice lived in graph code.

`agent.model.review` is the dangerous one, in the same way `gate.sandboxed` is. The cold reviewer's
independence is the property the whole loop is built around, and pointing that key at a weak model
weakens the adversarial gate silently: nothing in the loop can detect it, judge it or refuse it. The
same goes for writing `REVIEW_MODEL` into `.litter-box/.env`, which is untracked and permanent, so it
would apply to every future run with nothing in the repository recording that it does. The closed set
of names catches a typo there; it cannot catch a deliberate `haiku`.

`instance-name` earns its place even though litter-box never runs two instances at once:
`start-proxy.sh` does `docker rm -f "$PROXY_NAME"` at startup, before any issue label is
read, so with machine-global names a mistaken second launch kills the running instance's proxy
mid-iteration and no label discipline can prevent it.

## The pipeline

Fixed, not pluggable:

```
PICK → IMPLEMENT → GATE → REPAIR → REVIEW → PR → CI → MERGE
```

One issue per iteration. `PICK` resumes an `in-progress` issue if there is one, and if that issue
is ALSO `parked` with an accepted human reply it resumes straight into a FIX round rather than an
ordinary IMPL. With no issue in progress it resumes the oldest `parked` issue with an accepted
human reply instead, so a run a human already steered finishes before anything new starts; else it
takes the oldest `ready` one. Deterministic, no LLM involved.

`parked` now survives a whole iteration rather than flipping to `active` at pick time: an infra
fault partway through a resumed iteration leaves the issue both `in-progress` and `parked`, exactly
the state the next tick's pick already knows how to read, so a genuinely accepted reply is never
silently dropped by a fault the loop could not have prevented. Several situations follow directly
from that:

- If `gh issue list --label parked` itself fails to read, the whole tick ends `InfraFault` (rc 50):
  nothing is mutated, nothing is dispatched, and the next tick re-decides from scratch once the read
  (hopefully) succeeds. This holds whether or not an issue is already in flight; an earlier version
  of this rule tried to treat an in-flight issue as decidable regardless and degrade instead of
  faulting, which turned out to be unsound (it could fabricate a parked-resume narrative over an
  issue that was never parked at all, or strand the very label it was trying to protect on a repo
  that has not created it yet). The known cost is that a repo which has never created the `parked`
  label faults every crash-resume tick that reaches this read; the fix for that is to create the
  label (or, longer term, to tell "label missing" apart from "read failed" at this call site, which
  `gh` does not do today).
- If the pick cannot tell whether an in-progress issue's own reply is usable (an unreadable
  comments list, or the harness's own GitHub identity cannot be verified), the iteration ends
  `Parked` (rc 60) having mutated nothing at all, exactly like an ordinary parked issue with no
  reply yet. This is expected to be transient: the next tick re-reads the same `gh` calls, and a
  persistently failing read shows up as a repeated log line rather than a silent hang, with the
  same credential/access fix that the rest of the loop already depends on. Because this return
  happens before the ready queue (and every other parked issue) is ever read, a PERSISTENTLY
  failing read on this one issue starves the whole queue behind it; an operator who sees the same
  log line repeat tick after tick can escape it without a code change by removing `in-progress` from
  that one issue by hand (`gh issue edit <#> --remove-label in-progress`), which lets the pick fall
  through to everything else while that issue keeps `parked` and waits for its own read to be fixed
  separately.
- If an in-progress issue's reply IS accepted but the self-repair budget is exhausted
  (`REPAIR_BUDGET=0`, or already spent for that issue), the loop does not sit on the issue forever:
  it drops `in-progress` (keeping `parked`, since the reply really is still waiting) and tries the
  rest of the queue the same tick, UNLESS `DRY_RUN=1`, in which case it does not mutate that label
  either and only reports what it would have picked. If nothing else is available the tick ends
  `Parked` having made that one label edit; if the release edit itself fails, the tick ends `Parked`
  immediately instead of picking a second issue, so the loop never runs two issues in flight at
  once. An operator seeing repeated `Parked` exits with a human reply already posted should read
  that as "raise `REPAIR_BUDGET`", not as "the loop is still waiting on a reply"; the per-tick log
  line names which of the two is actually true.

## The safety spine

This is the product. Everything else is plumbing.

- **The worker never picks its own work.** The issue comes from a label query, not from the model.
- **Protected-path patch guard.** A patch touching any glob in the config's `protect` list is
  rejected unapplied. A consumer `protect` list can only widen the protection, never narrow it: the
  reference entries are unioned in as a floor, so the list always covers `.litter-box/**`, i.e. the
  config file that defines the list. The loop cannot be talked into loosening its own guard, editing
  its own CI, or rewriting the conventions it is judged against.
- **Test-tamper check.** The diff is measured against `origin/main` with `git apply --numstat` and
  the result is handed to the reviewer, which catches the classic failure mode: deleting a failing
  test to go green.
- **Cold independent reviewer.** A separate `claude -p` with none of the worker's context. It sees
  the diff, the acceptance criteria, the conventions and the tamper report, and must emit a
  `VERDICT:` sentinel. **No sentinel is treated as REQUEST_CHANGES**, never as approval.
- **Bounded self-repair.** A shared budget (default 2) per issue, spent by a RED gate *or* a
  `REQUEST_CHANGES`. Exhausting it on that generic failure parks the issue by default
  (`issues.park-on-exhaustion`) rather than looping forever; a guard rejection or an empty fix
  still terminates straight to `needs-human` regardless of the knob.
- **Only vouched-for accounts can resume a parked issue.** Every marker comment the harness itself
  posts (the initial park comment, and the one it posts the moment a resumed reply is actually spent
  on a fresh attempt) bounds the resume probe the same way: only a reply after the newest one, from
  an `OWNER`, `MEMBER` or `COLLABORATOR` association, counts. A comment from any other account is
  logged and ignored, so a public issue thread cannot be used to force unbounded park/resume
  dispatch cycles.
- **Infra faults are not code failures.** A Docker outage, a timed-out worker or a failed merge
  exits `50` with the budget untouched and the issue left `in-progress`, so the next tick resumes it.
  A crashed sandbox can never burn repair budget or trigger a FIX.
- **`STOP.md` is a manual kill switch.** The loop reads it and never writes it.
- **The sandbox carries no credentials.** Non-root user, egress only through an allowlisting proxy.

## Exit codes

Each iteration ends in one of eight states. The driver maps them to a process exit code:

| State | rc | Process | Meaning |
|---|---|---|---|
| Success | 0 | *continues* | Merged, or PR opened → `needs-review` |
| ManualStop | 10 | 0 | `STOP.md` present |
| Idle | 11 | 0 | No `ready`, `in-progress` or `parked` issue |
| DryRun | 20 | 0 | `DRY_RUN=1` stop point, before any mutation |
| NothingMade | 30 | 1 | Empty patch — nothing staged |
| NeedsHuman | 40 | *continues* | Guard rejection, empty fix, CI red, or budget spent with `issues.park-on-exhaustion` false. PR left open for audit |
| InfraFault | 50 | 50 | Infra problem. Issue stays `in-progress` |
| Parked | 60 | 60 | Budget spent on a generic gate/review failure with `issues.park-on-exhaustion` true (the default). No PR; issue labelled `parked` instead of `needs-human`. A later tick with an accepted human reply on the issue resumes it with a FIX |

`Success` and `NeedsHuman` are the only two that let the driver advance to the next iteration;
every other state exits the process immediately. The loop runs at most `MAX_ITERS` iterations.

## Running it

```bash
scala-cli test .            # the test suite: no Docker, no gh, no credentials
scala-cli run . -- --help   # usage: init, eject, watch, tail, --dry-run (same binary as `litter-box`)
scala-cli run .             # the loop itself
```

Environment variables still configure the loop — a flag beats the matching variable where both
exist. See [Getting started](#getting-started) for `init`, `eject`, `--dry-run` and `--help`, and
[Watching a run](#watching-a-run) for `watch` and `tail`.

| Variable | Default | Purpose |
|---|---|---|
| `MAX_ITERS` | `1` | Iterations before the driver stops |
| `DRY_RUN` | `0` | `1` renders the worker prompt, then stops before any mutation |
| `REPAIR_BUDGET` | `2` | Fix attempts per issue |
| `MAX_PATCH_BYTES` | `1000000` | Oversized-patch guard |
| `GATE_CMD` | `false` | The gate (overrides `gate.fast`); the default fails on purpose, because litter-box never guesses your build command. Exporting it also forces `gate.sandboxed` to false — the gate runs on the host with everything your shell has, not in the container — and skips the whole Docker preflight that would have built that container; setting it in `.litter-box/.env` only changes the command |
| `GATE_TIMEOUT` | `900` | Gate timeout (seconds) |
| `ITER_TIMEOUT` | `1800` | Worker dispatch timeout |
| `IMPLEMENT_SLACK` | `300` | Added on top of `ITER_TIMEOUT` for the Implement node's own timeout; raise it on a host with no `timeout`/`gtimeout` binary, where the worker runs unbounded and this is the only backstop left |
| `CI_WAIT_TIMEOUT` / `CI_APPEAR_TIMEOUT` / `CI_APPEAR_INTERVAL` | `900` / `300` / `10` | CI polling |
| `NTFY_TOPIC` | — | ntfy.sh topic for notifications |
| `IMPL_MODEL` | unset | Model for the worker dispatch, for this run only; overrides `agent.model.impl` |
| `FIX_MODEL` | unset | Model for the fixer dispatch, for this run only; overrides `agent.model.fix` |
| `REVIEW_MODEL` | unset | Model for the cold reviewer, for this run only; overrides `agent.model.review`. A weak value here silently weakens the adversarial gate, and nothing in the loop can detect, judge or refuse it |

Each of the three must be one of `haiku`, `sonnet`, `opus` or `fable`; any other spelling stops the
run with exit code 50 before a single issue is read, naming the key and listing the names you may
write. That check applies whether the value came from `.litter-box/config.conf` or from one of these
three variables. See [Configuration](#configuration) above for what `agent.model` picks and why a
default model was deliberately left out.

`IMPL_CMD`, `FIX_CMD`, `REVIEW_CMD`, `NOTIFY_CMD`, `CI_WAIT_CMD`, `CI_APPEAR_CMD` and `MERGE_CMD`
are test seams: each replaces one subprocess so the loop can be driven without Docker or GitHub.

Preflight requires `gh` and `claude` on `PATH` (but not `jq`, which only `watch` and `tail` need and
which the loop never calls), plus whatever tool the first word of your gate command names (that
probe is skipped when the gate runs sandboxed, because the tool lives in the image rather than on
the host), and either `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY` for the sandboxed worker,
exported or written in `.litter-box/.env`. That file is
not credentials-only: any variable in the table above can live in it, and it reaches the credential
check, the config layering and the seams by the same door an export does. Two things it cannot do,
both of them deliberate:

- **It cannot skip the sandbox preflight.** A `GATE_CMD` there sets the gate command and stops
  there; only an exported `GATE_CMD` is an operator saying "no sandbox for this run", because the
  file is permanent and untracked and would otherwise switch the preflight off for every future run,
  silently.
- **An empty export does not shadow it.** `FOO=` exported is an absent `FOO` everywhere else in the
  loop, so it loses to the file's value rather than blanking it — which is what makes a sourced
  `.env.example` or a CI `env:` entry built from a missing secret harmless.

For which layer wins in general, see [Configuration](#configuration).

`watch` and `tail` (below) need `jq` and nothing else: no credential, no Docker, no `gh`. It is
checked when you run them rather than at loop startup, so a missing `jq` never stops a run.

### Watching a run

```bash
litter-box watch            # pinned phase banner over the log the current phase is writing
litter-box tail             # follow the newest worker log, one readable line per stream-json event
litter-box watch <status.jsonl>   # or point either at a specific file
litter-box tail <logfile>
```

Both are passive: they read `status.jsonl` and the log files the run already writes, never write to
the repo, never call `gh`, and the loop cannot tell whether anything is attached. Attach, kill and
reattach at any point in a run. Run them from anywhere inside the repo you want to watch — the repo
is found the same way the loop finds it, with `git rev-parse --show-toplevel`, and a relative path
you pass is resolved against the directory you typed it in.

`watch` reads your repo's `log-dir` out of `.litter-box/config.conf`, so a repo that moved its logs
gets a watcher that follows.

### Issue labels

`ready` → `in-progress` → `needs-review` or `needs-human`. `blocked` issues carry a
`Blocked-by: #N` line and are flipped to `ready` when their dependency closes. `class-1` marks an
issue as eligible for auto-merge once CI is green.

## Layout

```
src/           the loop: Machine (state machine), Live (handlers), Caps, Domain, Main, Init, Cli, Prompts
test/          the suite, plus golden/ — the frozen log-line contract
resources/     shipped inside the artifact: prompts/ (built-in skeletons), scaffold/ (init's
               templates), sandbox/ (the Docker sandbox: base image, gate, agent and reviewer
               runners, egress proxy), observe/ (watch.sh, tail-claude.sh and their lib/)
docs/          reference docs, e.g. base-image.md
sandbox/test/  shell tests of resources/sandbox (needs Docker) and resources/observe, run manually
```

Prompt skeletons no longer live in a consumer repo's `prompts/` directory — they ship inside the
artifact under `resources/prompts/`, with `.litter-box/prompts/` as the per-repo override written by
`litter-box eject` (see [Getting started](#getting-started)).

The sandbox scripts ship the same way, for the same reason: they are protocol, not configuration, so
a consumer carrying a copy would carry one that rots the moment litter-box updates. On each run they
are unpacked to `~/.cache/litter-box/sandbox/<digest>`, keyed by the contents so an upgrade lands in
a new directory on its own. A consumer owns exactly two files of the sandbox — `.litter-box/Dockerfile`
(what the gate image is built from) and `.litter-box/allowlist` (what it may talk to).

So do the observability scripts, under `~/.cache/litter-box/observe/<digest>`, and there the reason
is sharper still: `watch.sh` PARSES `status.jsonl`, so a scaffolded copy would silently misread a
renamed field in every repo that ever ran `init`. Nobody types a content digest, so they get a front
door instead — see [Watching a run](#watching-a-run).

`Machine` is a pure decision function over a `using` clause of capability traits (`Caps.scala`);
`Live.scala` holds every real side effect. That is what lets the whole suite run in memory.

### The egress allowlist

`.litter-box/allowlist` is one host per line, matched against the CONNECT hostname; a line starting
with `#` is a comment. `init` seeds it with the hosts a JVM build resolves artifacts from plus
`api.anthropic.com`, and whatever is not named there is refused by the proxy with `403 Filtered`.
The file replaces the copy that ships in the artifact rather than extending it, so an edit can
narrow egress as well as widen it, and nothing at run time enforces that it stays a superset of the
shipped list: add the hosts your build needs, keep the seeded ones, and expect a missing one to
surface as a resolution failure inside the gate rather than as a network timeout.

The list is baked into the proxy image rather than read at run time, deliberately: the copy in the
image is what the proxy enforces from preflight to teardown, so the fence stays fixed for the whole
of a run and a worker editing `.litter-box/allowlist` cannot move the fence it is running behind.
An edit lands at the next image build instead, and the loop needs nothing from you to get there:
preflight runs `build-image.sh` immediately before `start-proxy.sh`, and that build bakes whatever
the file currently says into the proxy image. `start-proxy.sh` is what proves it took. It recreates
the container from the current image and reads back the list in force, so the run proceeds only
once the running proxy demonstrably enforces your file. A proxy found enforcing anything else, the
case a `start-proxy.sh` invoked on its own has to cover, gets one rebuild under it; if the two
still disagree after that, the run stops instead of gating against a fence nobody wrote.

### The log contract

The operator log stream is parsed by `watch.sh`, so its wording is asserted behaviour, not
decoration. `LogParitySpec` freezes it whole against the golden files in `test/golden`. To change a
log line deliberately:

```bash
UPDATE_GOLDEN=1 scala-cli test .
git diff test/golden          # read this. it IS the contract change.
```

## Build

Scala 3.8.3 on JDK 21 LTS, built with scala-cli. Deliberately **not** sbt: the threat model
distrusts agent-authored build files, so the loop never couples to the build of the project it is
working on. That rule holds for publishing too, which is why releases go to Maven Central through
`scala-cli publish`, configured by flags on the release workflow's own `publish` job, rather than through
sbt-ci-release and a `build.sbt` that would restate this project's dependencies and layout a second
time; [docs/maven-central-setup.md](docs/maven-central-setup.md) has the full reasoning and the
operator steps.

## License

MIT. See [LICENSE](LICENSE).
