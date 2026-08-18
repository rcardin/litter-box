## Test seams

`IMPL_CMD`, `FIX_CMD`, `REVIEW_CMD`, `NOTIFY_CMD`, `CI_WAIT_CMD`, `CI_APPEAR_CMD` and `MERGE_CMD` each
replace one subprocess, so the loop can be driven end to end without Docker or GitHub. `GATE_CMD`
overriding `gate.fast` also skips the entire Docker preflight.

## `test/Recorder.scala` is a published artifact: keep it standalone

Since issue #42 that one file is also `in.rcard::litter-box-testkit`, compiled ALONE against the
published library jar and pushed to Maven Central off every tag. Inside this project it compiles next
to `src/` and next to scalatest, so nothing about `scala-cli test .` notices an `org.scalatest`
import, or a reference to a helper some spec defines, creeping into it. Both would break only the
release publish, on the one path in this pipeline that cannot be retried, since Central is immutable
at a version.

`.github/workflows/ci.yml`'s `testkit` job runs the release's own compile on every PR to catch that.
Run it by hand the same way before touching that file:

```bash
scala_version="$(sed -n 's|^//> using scala \(.*\)$|\1|p' project.scala)"
jvm_version="$(sed -n 's|^//> using jvm \(.*\)$|\1|p' project.scala)"
scala-cli --power publish local . --organization in.rcard --name litter-box --project-version 0.0.0-CI
scala-cli compile test/Recorder.scala --scala "$scala_version" --jvm "$jvm_version" \
  --dep in.rcard::litter-box:0.0.0-CI
```

Read out of `project.scala` rather than typed, for the same reason `scripts/publish-testkit.sh` does
it: a `3.8.3` written here is a second statement of a fact that file owns, and this copy is the one
nothing checks. That script is the single definition of the testkit publish, called by the `testkit`
job and by both halves of `release.yml`'s publish job, so `scripts/publish-testkit.sh local 0.0.0-CI`
after the `publish local .` above is literally what CI runs. It goes further than `compile` and runs
`publish local` on the testkit, since only that builds the doc jar, the sources jar and the pom;
`compile` is enough for the edit-and-check loop the command above exists for.

Two things follow. Everything in that file, `TestWorld`, `NodeRun`, `Script`, `FakeClock`, `buildCaps`
and `withFaulting`, may depend on `src/` and on the Scala library, and on nothing else. And it is all
consumer-facing surface now, so a rename there is a breaking change for a node author, not a local
edit. README's Testkit section is where the artifact says which of it is SUPPORTED surface, which is a
narrower list than what the jar happens to contain.

A third thing follows for `TestWorld.runNode`, the one member of that file that exists BECAUSE it
compiles into `in.rcard.litterbox`. Running a single node means calling `Runner.step`, which takes a
`using Runner.Ledger` whose constructor is `private[litterbox]` (that class's own doc has why it stays
that way), so a consumer cannot make that call from their own package at all and no member of `src/`
was widened to let them. `runNode` makes the call on their behalf from inside the package: it takes a
`dispatchBudget: Int` and reports what survived as the `Int` in `NodeRun`, so a `Ledger` crosses the
artifact boundary in neither direction. Keep it that way. `test/TestkitBoundarySpec.scala` runs nodes
from `package com.example.consumer` and pins `Runner.Ledger(3)` as a compile error from there, so it
fails the day either half stops being true.

## Verifying a macro change: always pass `--server=false`

`src/KitMacro.scala` is compiled and cached by the bloop/BSP daemon `scala-cli` starts by default, and
that cache goes STALE in the dangerous direction for a macro edit (issue #43 review round 4): a macro
change that makes `checkShapeImpl`'s own walk STRICTER, rejecting a shape it used to accept, is invisible
to the daemon until some CONSUMER file that calls the macro also changes, because bloop only re-expands a
macro splice when the file containing that splice is itself recompiled, never merely because the macro's
own implementation changed underneath it. Confirmed directly: with a violating consumer file already
present, the macro intact and bloop running, `scala-cli test` correctly reported one violation; the macro
then deliberately sabotaged (weakened) to accept that same violation, still reported one violation, the
STALE cached result; the macro then RESTORED to its correct, strict behaviour, `scala-cli test` still
reported ZERO violations, because the consumer file had not changed so bloop never re-expanded anything.
The identical source state compiled with `scala-cli test . --server=false`, which skips the daemon
entirely and recompiles from scratch, correctly reported one violation throughout. A clean-looking test
run against a macro change is therefore worthless evidence unless it ran with the daemon off: **pass
`--server=false` on every `scala-cli compile`/`scala-cli test` invocation while verifying a change to
`KitMacro.scala`, `Kit.scala`'s macro-adjacent code (`checkedShape`/`checkedShapeStrict`/`markerRequiresReview`,
and `Node.apply` itself, which is an `inline def` reading that last one), or
anything else a macro reads**, and prefer real, separately compiled top-level consumer files in a scratch
`package com.example.consumer` over `scala.compiletime.testing.typeCheckErrors` snippets when confirming
a fix closes a hole: round 3 and round 4 of that review sequence each found BLOCKERs that a snippet-only
verification pass had missed, because a snippet resolves imports and package paths differently from a
real, separately compiled file.
## The worked consumer example

`test/ReviewFixLoopExample.scala` is the `loop.scala` a consumer repository writes for itself, and
`test/ReviewFixLoopExampleSpec.scala` walks it through a `TestWorld`. It sits under `test/` for the
reason every example should: one nobody compiles rots silently against the API it claims to
demonstrate, and this one is a build failure the day a kit signature moves.

`docs/` was the obvious home and does not work: scala-cli reads every `.scala` file under this
project as a MAIN source, so an example there would be compiled into the published library jar and
into the `lb` assembly, and its `@main def loop` would sit next to the CLI's own entry point. The
`.test.scala` suffix moves a file to the test scope but renames its synthetic package object after
the file, dots and all, which every top level definition in it then warns about, and CONVENTIONS.md
forbids carrying a warning.

Two things separate that file from the copy a consumer writes.

It carries a `package com.example.reviewfix` clause where a consumer's `loop.scala` is a package-less
top level script. The spec that drives it has to import it, and a package this library does not own
is the honest place for it, the same convention `test/ConsumerGraphIdioms.scala` and
`test/ScaffoldedLoopBoundarySpec.scala` already follow.

It carries no dependency directive where a consumer's copy opens with `using dep` naming
`in.rcard::litter-box` at the version they are on. Written here it would be read on every scala-cli
invocation in this repository and make the project depend on a published copy of itself, so the
example compiles against the sources next door instead. That is strictly the stronger check: it is
pinned to the API as it is now, not to the API as it was at the last release.
