---
name: capability-wiring
description: Use when behaviour needs something from the outside world in litter-box (a new subprocess, a new gh call, a new file read, a new clock read). Wires it end to end across Caps.scala, Machine.scala, Live.scala and test/Recorder.scala, with tests and goldens. Do NOT use for pure decision changes that touch no capability.
tools: Read, Edit, Write, Grep, Glob, Bash
---

You add or change one capability in litter-box. The four file dance is not optional and not
reorderable.

## The dance

1. `src/Caps.scala`: the capability trait method. Smallest signature that can express the need.
   Scaladoc says WHY this seam exists, never what the method does.
2. `src/Machine.scala`: the decision that calls it. `Machine` stays pure: no filesystem, no
   subprocess, no clock, nothing but capability calls. If your change makes `Machine` import
   `java.io`, `os`, or `System.currentTimeMillis`, you got the seam wrong. Go back to step 1.
3. `src/Live.scala`: the real implementation. Copy the shape of `LiveGateRunner`: dependencies as
   constructor parameters, exactly one seam for the thing a test must fake.
4. `test/Recorder.scala`: the scripted in memory handler plus its interaction recording. Every
   capability method must be scriptable and every call recordable, or `ScenarioSpec` cannot assert
   on the call sequence.

Then `src/Main.scala` wiring, if the handler needs constructing. Note `HostGateRunner` is a wrapper
case class, deliberately not a `GateRunner` subtype, so host side tiers must be wired by name.

## Rules that fail the build or the review if broken

- Everything under `test/` stays Docker free, network free, credential free. CI runs
  `scala-cli test .` with nothing else installed. Docker dependent checks are shell scripts under
  `sandbox/test/`, run by hand, never wired into the gate.
- Tests drive real behaviour through the seam. They do not assert on a mock. Template pair:
  `LiveGateRunner` plus `test/LiveProcSpec.scala`.
- One runtime dependency (`com.typesafe:config`), one test dependency (scalatest). Adding a
  dependency is a design decision that needs the user's word, not a convenience you take.
- Never `@nowarn` or any other suppression. Fix the cause.
- No `build.sbt`. Ever. The threat model distrusts agent authored build files; the build is
  scala-cli.
- Prose contains no dash characters.

## Infra faults

Faults short circuit through `boundary.Label[LoopExit]` (`Faulting` in `src/Kit.scala`), and only
via `Machine.infraFault`. Never `boundary.break` with a bare `LoopExit`, and never invent a second
fault path with its own wording or ordering: the log line, then the notify, then the abandon, in
that order, is the contract.

## New config knob

If the capability needs configuration, add it to the reference HOCON schema in `src/Settings.scala`.
Do not restate the layering order anywhere: the `Settings` object scaladoc is the one place that
states which layer beats which. `protect` is a floor that gets unioned, never a list a consumer can
shrink.

## New shipped file

A file added under `resources/<tree>/` must also be added to that tree's `ShippedFiles` manifest in
`src/Shipped.scala`, else `test/ShippedSpec.scala` fails. A file that a consumer owns instead goes
through `src/Init.scala` scaffolding.

## Finish

Run `scala-cli test .`. If the operator log stream changed, run `UPDATE_GOLDEN=1 scala-cli test .`
and then read `git diff test/golden` and quote it back: that diff IS the contract change, and
`watch.sh` parses those lines. If you did not intend the wording change, revert it rather than
accept the golden.

Report: files touched, the capability signature, the golden diff if any, the test command output.
