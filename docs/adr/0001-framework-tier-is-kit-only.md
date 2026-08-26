# 1. The framework tier is the kit only

## Status

Accepted, 23 August 2026.

## Context

`src/Kit.scala` and `src/KitMacro.scala` are the framework. A consumer who authors their own loop
writes `Node`s and one `Plan`, hands them to `LitterBox.graph`, and compiles against that kit; the
macro reads their literal at compile time and the `Runner` walks their graph at run time. Whatever
the kit names is therefore, transitively, part of the surface they depend on, whether or not anyone
meant to publish it.

The kit called `Machine.infraFault` at five sites: inside `Fault.raise`, twice inside
`Plan.workflowOf` (an ill declared plan, a walk that dead ends), inside `Runner.step` (a node
overrunning its declared timeout) and inside `Runner.run` (an invalid `Shape`). `Machine` is not a
lower layer the kit rests on. It is the shipped pipeline, one graph among the many the kit exists to
run, and by the kit's own framing merely its first consumer. Every one of those five references
pointed the dependency arrow backwards, from the framework into one of its inhabitants.

`Fault` is the sharpest instance. That type exists for exactly one reason, stated in its own
scaladoc: a node holding the raw `boundary.Label[LoopExit]` could `break` with whatever `LoopExit`
it liked, `Success` included, skipping the fault line, the rc 50 notify and the runner's own
accounting, so `Fault` offers one operation and that operation IS the loop's single fault body. Its
entire implementation was a one line delegate into the application. The type whose whole claim is
that this loop has one fault body did not contain that body, and the guarantee it advertised was
held somewhere it could not see.

`Runner.step` used both channels inside one method. It built a `Fault` at the top, raised through
that value twice (a `Trust.Reviewed` node returning an unjudged value, a node returning
`Stopped(LoopExit.InfraFault)` directly), then reached past it to call `Machine.infraFault` directly
on the timeout overrun with the same `fault` still in scope. Two spellings of one operation, four
lines apart, is what an unstated rule looks like from the inside.

Nothing here was a compiler error waiting to happen. `Kit` and `Machine` are members of the same
package, so all five references are legal Scala and always were. Nor did the edge arrive through
carelessness: it accumulated one good local reason at a time, across three issues (#32 gave the kit
its `Fault` and its timeout check, #38 gave `Runner.run` its startup validation, #67 gave
`Plan.workflowOf` its derivation), each time because the author needed a fault and the only fault
body in the codebase was in `Machine`. An arrow like that can only be reversed by a decision, and
kept reversed by a check.

## Decision

Three tiers, and a tier is a property of a FILE:

- **Tier 0, the domain**: `src/Domain.scala`, `src/Caps.scala`. The closed types and the capability
  traits.
- **Tier 1, the kit**: `src/Kit.scala`, `src/KitMacro.scala`, `src/PatchGuard.scala`,
  `src/Reply.scala`. The framework a consumer compiles against. `PatchGuard.scala` and
  `Reply.scala` joined the list after this ADR was accepted, each under its own amendment below.
- **Tier 2, the application**: `src/Machine.scala`, `src/LitterBox.scala`, `src/Main.scala`,
  `src/Live.scala`. The shipped graph, the front door, the wiring that runs a graph and the real
  side effects behind the capabilities.

One rule, and it runs one way: **no code in Tier 1 may name anything declared outside Tier 0 union
Tier 1.** Tier 2 is unconstrained and names both tiers below it freely. There is no rule pointing
the other way because there is no problem in that direction: an application depending on its
framework is the arrangement, not the defect.

Tier is carried by the file rather than by a package, a marker trait or a naming convention.
`private[litterbox]` is load bearing across this whole codebase (`LoopGraph`'s every member,
`Runner.Ledger`'s constructor, `Fault.label`), so a package per tier would have to widen exactly the
access those decisions closed, trading a real boundary for a nominal one. A file, by contrast, needs
no compiler cooperation to identify, which matters because the rule is enforced by reading source
text.

The rule binds CODE. It deliberately does not bind comments or string literals. Naming a construct
in order to explain it, or in order to diagnose it, creates no dependency; only a reference the
compiler resolves does. The exemption is part of the decision, not a limitation of how the check
happens to be written.

## Consequences

**The fault body moved down, and nothing about a fault moved.** `Fault.raise` now contains the three
statements every fault in this loop runs, and `Machine.infraFault` is a delegate into it: the
application tier keeps its local convenience over the channel instead of owning a second
implementation of it. The kit's four other sites raise through a `Fault` they already hold or build.
No log line, no notify and no exit code changed, which is what the unchanged goldens under
`test/golden/` and `test/ScenarioSpec.scala`'s verbatim notify assertions say.

**`LoopGraph` and `LitterBox.graph` are the consumer's front door and they are Tier 2 on purpose.**
This is the counterintuitive part and the one a future reader is most likely to try to fix.
`src/LitterBox.scala` holds framework shaped things (the `LoopGraph` trait, the `graph` factory that
builds its second inhabitant, `run`) in the same file as one application instance (`shipped`, whose
code names `Machine` nine times). Splitting the file was considered and rejected. `graph` can only
return a `new LoopGraph` because it lives in the same file the `sealed` trait does, which is the
whole mechanism letting a consumer author a graph without the trait's surface being widened by one
member; and a split would not even produce a Tier 1 clean `LoopGraph`, since `begin`'s own abstract
signature names `Machine.Cursor`, as does the inhabitant `graph` returns. The file level cut states
the true position honestly: this is the coordinate a consumer depends on, it is not framework, and
the kit holds nothing of it.

**`src/KitMacro.scala` names `LitterBox.graph` in six compile error strings, and that is not a
violation.** Those messages report on a specific call the consumer wrote, and a diagnostic that
cannot say which call failed is worth less than the coupling it avoids. Parameterising the name
would build a seam across which nothing varies, since exactly one entry point exists. If a second
one ever arrives, someone edits six strings, and that day is cheaper than every day of carrying the
seam before it.

**`src/Kit.scala` still opens with seventy three comment lines narrating the shipped pipeline's own
edit history.** Under the rule that prose is exempt this costs nothing mechanically, but it does
mean the framework's first page reads as the story of one of its consumers. Known, deliberately
left, and not spec enforced: it is a writing job, not a dependency.

**`test/KitBoundarySpec.scala` is what stops the edge coming back.** A spec is needed because the
compiler has no opinion here: same package, legal references, and a reviewer who sees one new fault
site sees a reasonable line of code, exactly as every site that arrived before it was. So the check
reads the source text, strips comments and string literals into spaces (line numbers survive, so a
failure names the line), and derives its denylist from the tree rather than listing forbidden names
by hand. Derivation is the part that matters: a hand written list fails open in precisely the way
the original edge arrived, so a top level declaration added anywhere outside the two tiers is denied
to the kit the moment it is declared. A kit local identifier that innocently collides with such a
name is a finding about the vocabulary to be resolved, never an exclusion to be added.

**The tier model is not total over `src/`.** Seven files carry no tier LABEL: `Cli.scala`,
`Init.scala`, `Observe.scala`, `Prompts.scala`, `Sandbox.scala`, `Settings.scala` and
`Shipped.scala`. They parse arguments, scaffold, resolve templates and unpack resource trees. The
rule still reaches every one of them, and so does the check: the rule is phrased as a complement, and
`KitBoundarySpec`'s denylist is derived from every `.scala` file under `src` that is not one of the
four, so the kit may no more name `Settings` than it may name `Machine`. What these seven do not need
is a NAME for their position, since nothing reads one. Tiers exist here to make one arrow
enforceable, not to give every file a label.

## Amendment, 24 August 2026: `src/PatchGuard.scala` joins tier 1

The patch guard, the decision about what an agent patch may be allowed to reach the index, was a set
of `private` helpers inside `src/Machine.scala`: `stagePatch`, `touchesProtected`, `numstatPaths` and
`writeRejectMarker`, with the glob matcher itself a `private[litterbox]` member of `src/Settings.scala`.
By the framing this ADR already sets out, that put the loop's own threat model inside one inhabitant
of the kit rather than in the framework every inhabitant compiles against.

The consequence was not hypothetical. `test/ReviewFixLoopExample.scala`, the one worked consumer graph
this repository ships and the file a consumer copies, restated the guard by hand, and restated it
WEAKER: prefix matching on a `**` suffix where the real guard runs a JDK glob, so a `protect` entry
whose star is not at the end silently stopped protecting anything. Its own comment said so. A security
decision reachable only through one graph's private helper is a security decision every other graph
reimplements, and reimplements worse.

`src/PatchGuard.scala` is therefore a tier 1 file, and the rule binds it unchanged: it names `Domain`,
`Caps` and the kit, and nothing else. Two things follow from the rule rather than from preference. The
glob matcher MOVED out of `Settings` rather than being called from tier 1, since `Settings` is on the
denylist `test/KitBoundarySpec.scala` derives; `Settings.protectWithFloor` stayed, because deciding
which patterns are in the list is config layering while deciding what a pattern MEANS is guard
mechanics. And the agent dispatch did NOT move in: what a dispatch costs is the node's `Cost` and the
runner's own metering, so a guard that dispatched would be spending a budget it cannot see.

The tier rule itself does not change, and neither does its direction. What changed is that tier 1 now
has three files, `test/KitBoundarySpec.scala` scans all three, and a fourth would be added the same
way: by deciding it is framework, not by discovering that something in tier 2 was already being
imported from tier 1.

## Amendment, 25 August 2026: `src/Reply.scala` joins tier 1

The render half of the reply protocol, turning untrusted comment entries into one block of prompt
text, was written out by hand at two call sites inside `src/Machine.scala`: `runFixRound`, filling the
`{{COMMENTS}}` slot of a FIX prompt, and `askHumanProbeResult`, building the text `AskHuman` hands to
the next node. Both spelled the same three steps in the same order, escape the entry grammar, defuse a
forged fence tag, then cap per entry, joined by the same separator. Only one of them carried the
comment explaining why that order is what it is, and no test in the suite could tell one order from
the other.

The order IS the protection. Capping first can cut a forged entry boundary in half and change whether
it still parses as one, and an escape that runs after the cap cannot see the text the cap already
dropped. A protection carried by the ORDER of a composition, and restated wherever the composition is
needed, has as many implementations as it has callers and no way to check that they agree. It took
three review rounds to arrive at that order; the second call site copied it, and the next caller was
free to copy it wrong.

A consumer authoring their own graph had none of the implementations. Any node of theirs that puts a
human's words in front of a worker faces exactly this question, and the only two answers in the
codebase were private to one graph. That is the same argument that moved the patch guard, and it is
answered the same way.

`src/Reply.scala` is therefore a tier 1 file, added the way this ADR's previous amendment says a
fourth one would be: by deciding it is framework, not by discovering that something in tier 2 was
already being imported from tier 1. It exposes one function, the render half, pure over a list of
strings, taking no capability and no config. Everything the composition is made of is private to it,
the two character budget constants included, because a caller that can reach the steps can order them
wrongly and a spec that drives the steps can watch each one behave perfectly while the composition
around them is wrong, which is precisely the state this repository was in.

Two things follow from the rule rather than from preference. The file carries its OWN copy of the
entry parse rather than calling `Machine.parseEntry`, which is `private[litterbox]` and so would
compile cleanly from the same package while being exactly the reference `test/KitBoundarySpec.scala`
exists to catch. And the sentinel a caller renders when the comment read FAILED stayed in
`runFixRound`: the render half is handed the entries that were read, so it cannot tell a thread with
nothing in it from a thread nobody could see, and an answer to a question it was not asked is how two
sentinels drift into meaning the same thing.

What the extraction was allowed to change is nothing. The golden files under `test/golden/` are the
proof, and they are untouched by the diff. What it added is one test the suite did not have: a forged
boundary placed to straddle the cap, so that capping first and escaping first render visibly different
text, and the assertion fails if the two steps are ever swapped back.

The tier rule itself does not change, and neither does its direction. Tier 1 now has four files,
`test/KitBoundarySpec.scala` scans all four, and a fifth would be added the same way.
