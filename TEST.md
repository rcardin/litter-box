## Test seams

`IMPL_CMD`, `FIX_CMD`, `REVIEW_CMD`, `NOTIFY_CMD`, `CI_WAIT_CMD`, `CI_APPEAR_CMD` and `MERGE_CMD` each
replace one subprocess, so the loop can be driven end to end without Docker or GitHub. `GATE_CMD`
overriding `gate.fast` also skips the entire Docker preflight.

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