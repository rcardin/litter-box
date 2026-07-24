## Test seams

`IMPL_CMD`, `FIX_CMD`, `REVIEW_CMD`, `NOTIFY_CMD`, `CI_WAIT_CMD`, `CI_APPEAR_CMD` and `MERGE_CMD` each
replace one subprocess, so the loop can be driven end to end without Docker or GitHub. `GATE_CMD`
overriding `gate.fast` also skips the entire Docker preflight.