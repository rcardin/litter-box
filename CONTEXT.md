# Domain language

The words this codebase uses for its own concepts, and what each one means here. A term earns a place
in this file when the code names it in more than one file, or when a plausible reading of it would be
wrong. Architecture lives in `ARCHITECTURE.md`, decisions in `docs/adr/`; this file is vocabulary only.

`CONTEXT.md` is itself on the reference `protect` floor, so no agent patch can edit it.

## The loop

**Tick** — one full run of `Machine.runOnce`: pick an issue, do the work, reach exactly one `LoopExit`.
A tick is the unit of everything. Nothing is stored between ticks except what GitHub and git already
hold, which is why parking re-derives its state from issue labels and comments rather than latching.

**Graph** — the pipeline a tick walks, as data: nodes and the edges between them. The shipped graph is
the one this binary runs by default; a consumer graph is one a foreign repository authors through
`LitterBox.graph`.

**Node** — one step of a graph, with a declared `Cost`, `Timeout`, `Trust` and `Guard`. The runner
walks nodes; nodes never call each other.

**Plan** — the ONE literal a consumer writes for their own graph: where the walk begins, and every
edge it may take. Both the executed `Workflow` and the validated `Shape` are derived from it.

**Capability** — an effect the pure decision layer is allowed to have on the world (`GitHub`, `Git`,
`HarnessFs`, `GateRunner`, `AgentDispatch`, `Notify`, `Log`, `StatusLog`, `Clock`), passed as a `using`
parameter. `Machine` touches the world through nothing else, which is why the whole suite runs in
memory.

**Dispatch** — one run of an agent in the sandbox. Dispatches are the scarce resource: the runner owns
the counter (`Runner.Ledger`) and charges every real dispatch at the capability, whatever `Cost` the
node declared.

**Infra fault** — something broke that is NOT a verdict on the agent's work (a timeout, a refused
apply, an unreachable graph edge). It short circuits the tick through `Fault.raise`, spends no budget,
leaves the issue in progress, and exits rc 50. Never confuse it with a red gate or a REQUEST_CHANGES:
those are judgments, and they cost budget.

## The patch guard

**Patch guard** — the decision about what an agent patch may be allowed to reach the index, and the
loop's whole threat model in one place: `src/PatchGuard.scala`. The tree an agent edited is data to
inspect, never trusted.

**Patch seam** — `PatchGuard.stage`: reset to the pristine base, rule on the patch, then apply it or
stage a reject marker in its place. It is a seam rather than a step because it is the one place an
untrusted patch either becomes part of the run or does not.

**Ruling** — what the guard CONCLUDED about a patch, before anything was done about it:
`Ruling.Clean`, `Ruling.Oversized`, `Ruling.Protected`. Pure, decidable from the size of the patch and
the paths it names.

**Staged** — what the guard then DID: `Staged.Ok`, `Staged.Empty`, `Staged.ApplyFail`,
`Staged.Protected`, `Staged.Oversize`. Distinct from a ruling because applying can fail on its own.

**Protect list** — the `protect` globs a consumer repository declares, in JDK `glob:` semantics: a
single star stops at a directory separator, a double star crosses it, a bare filename is an exact
match. Matching lives with the guard; the list itself is built by config layering.

**Protect floor** — the reference `protect` entries every consumer list is unioned with at load
(`Settings.protectWithFloor`), so `.litter-box/**` is covered whether or not a repository named it. A
floor, not a list: a consumer's entries can only ever ADD protection. Unioned exactly once, at load,
so nothing downstream has to remember to reapply it.

**Reject marker** — `PATCH-REJECTED.md`, staged in place of a refused patch so the audit PR has a diff
to open with while the rejected change itself never lands on the branch.

**Tamper report** — the numstat of an APPLIED patch, filtered to test paths, written for the cold
reviewer to read. A report, not a guard: it refuses nothing.

## Judgment

**Gate** — the configured build/test command, run in the sandbox. Green or red. A gate TIMEOUT is an
infra fault, never a red.

**Cold reviewer** — a fresh agent session that sees the diff and the issue, and nothing of the session
that produced them. Its verdict is the only thing that can mint a `Judged` value.

**Judged** — a value that provably came from a real review dispatch. The type cannot be constructed
outside the capability that mints it, which is what makes a review guard a guarantee rather than a
convention.

**Repair round** — one FIX dispatch against a failure, bounded by the repair budget. The patch model
is cumulative: a fixer that produces no diff has reverted all prior work, which routes to needs-human
rather than re-gating an empty tree.

**Parked** — the terminal state of a tick that ran out of repair budget on a generic failure: the issue
is labelled and a marker comment is posted, and a human reply resumes it on a later tick. Parking is
about ONE tick, never a stored position.
