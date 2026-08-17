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

Read out of `project.scala` rather than typed, for the same reason the workflows do it: a `3.8.3`
written here is a second statement of a fact that file owns, and this copy is the one nothing checks.
Note the workflows go further and run `publish local` on the testkit rather than `compile`, since only
that builds the doc jar, the sources jar and the pom; `compile` is enough for the edit-and-check loop
this command exists for.

Two things follow. Everything in that file, `TestWorld`, `Script`, `FakeClock`, `buildCaps` and
`withFaulting`, may depend on `src/` and on the Scala library, and on nothing else. And it is all
consumer-facing surface now, so a rename there is a breaking change for a node author, not a local
edit. README's Testkit section is where the artifact says which of it is SUPPORTED surface, which is a
narrower list than what the jar happens to contain.

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