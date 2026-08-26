# litter-box conventions

## Layout

`src/` holds the loop: `Domain.scala` (types and exit codes), `Machine.scala` (the pure state
machine), `Caps.scala` (the capability interfaces), `Live.scala` (their real implementations),
`Settings.scala` (config), `Cli.scala` (argument parsing), `Init.scala` (the `init` scaffolder),
`Prompts.scala` (template resolution and `eject`), `Main.scala` (wiring and the driver). `test/`
mirrors it.

The rule: `Machine` is pure and depends only on the capabilities in `Caps.scala`. It never touches
the filesystem, a subprocess, or the clock directly. Everything that does lives in `Live.scala`
behind one of those interfaces, so the whole state machine is testable against `test/Recorder.scala`
with no Docker, no `gh` and no credentials.

## The template to copy

`LiveGateRunner` in `src/Live.scala` and its tests in `test/LiveProcSpec.scala`. That pair shows
the shape: a handler taking its dependencies as constructor parameters, a seam for the one thing
that must be faked, and a test that drives real behaviour through the seam rather than asserting on
a mock.

## Test tiers

Everything under `test/` is the fast tier and must stay Docker-free, network-free and
credential-free: CI runs `scala-cli test .` with nothing else installed. Tests that need Docker are
shell scripts under `sandbox/test/`, run by hand, and are never wired into the gate.

## Build and lint rules

Scala 3.8.3 on temurin 21, built by scala-cli, not sbt. This is deliberate: the threat model
distrusts agent-authored build files, so the loop never couples to one. Do not add a `build.sbt`.

One runtime dependency (`com.typesafe:config`) and one test dependency (scalatest). Adding a
dependency is a design decision, not a convenience.

Never use `@nowarn` or any other suppression to get past a warning. Fix the cause.

`src/Kit.scala`, `src/KitMacro.scala`, `src/PatchGuard.scala` and `src/Reply.scala` are the framework
tier. No code in any of them may name anything declared outside those four files plus
`src/Domain.scala` and `src/Caps.scala`; comments and string literals are exempt. Whatever the kit names becomes, transitively, part of the surface a consumer
authoring their own graph depends on, so a kit that reaches into `Machine` publishes the shipped
pipeline's own machinery as framework by accident. If you need a fault from kit code, raise it
through `Fault.raise`, which lives in the kit and IS the loop's one fault body; do not reach for
`Machine.infraFault`, which is private to its own file. `docs/adr/0001-framework-tier-is-kit-only.md`
has the reasoning, including the amendment that added `PatchGuard.scala`, and
`test/KitBoundarySpec.scala` fails the gate on a breach. The patch guard itself is public
(`PatchGuard.stage`, `PatchGuard.rule`, `PatchGuard.touchesProtected`): never restate it, in this
repository or in an example, since a hand written copy of it is how the protect globs silently got
weaker once already.

## Anything that has bitten you

Scaladoc explains WHY a decision was made, never what the code does. The codebase is read mostly by
agents with no memory of the conversation that produced it, so a comment restating the code costs
context and teaches nothing, while a missing reason gets re-litigated every iteration.

Prose contains no dash characters.
