#!/usr/bin/env bash
# Parity oracle: drives whichever loop implementation HARNESS_IMPL selects through the v1 state
# machine in a throwaway sandbox — scala (default, via harness/loop.sh) or bash (harness/loop-bash.sh).
# Stubs gh (fake bin on PATH), the gate (GATE_CMD), and the three claude dispatches
# (IMPL_CMD/FIX_CMD/REVIEW_CMD). No real GitHub, no Opus tokens.
set -euo pipefail

# The harness/ dir under test defaults to the one this script lives in (harness/test/..).
SRC_HARNESS="${SRC_HARNESS:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

# Which implementation of the loop this run scores (the Scala rewrite's parity oracle, slice 3):
#   scala (default) -> scala-cli run harness/scala, which harness/loop.sh now execs
#   bash            -> harness/loop-bash.sh, the retiring reference implementation
# Every scenario, stub and assertion below is shared: the two implementations are scored on the
# same 142 checks, which is the whole point. Slice 4 made the Scala port the default because it is
# now what `harness/loop.sh` runs. `bash` stays selectable, and `loop-bash.sh` stays on disk, only
# until the first real US merges under the Scala loop; then both this switch and the whole suite
# retire (ScenarioSpec is the canonical test — see issue #47).
HARNESS_IMPL="${HARNESS_IMPL:-scala}"
case "$HARNESS_IMPL" in
  bash|scala) ;;
  *) echo "HARNESS_IMPL must be 'bash' or 'scala' (got '$HARNESS_IMPL')" >&2; exit 2 ;;
esac

# The command that runs ONE loop process against the sandbox at $WORK (cwd is always $WORK).
#
# bash runs the COPY inside the sandbox, because loop-bash.sh derives the repo root it mutates from
# its own $BASH_SOURCE. The Scala entry derives that root from the cwd instead (Main.resolveRepoRoot
# walks up for harness/loop.sh, which the sandbox copy carries), so its sources can stay where they
# are — and must, or scala-cli would compile them again from scratch in each of the 22 sandboxes.
# That is also why scala mode invokes scala-cli directly rather than the `$WORK/harness/loop.sh`
# shim, which would cd into the sandbox and recompile: same program, same env, same exit code.
# Same sandbox under test either way; only the location of the code doing the driving differs.
harness_entry() {
  if [[ "$HARNESS_IMPL" == "scala" ]]; then
    scala-cli run "$SRC_HARNESS/scala"
  else
    "$WORK/harness/loop-bash.sh"
  fi
}

pass=0; fail=0
check() { # check DESC EXPECTED ACTUAL
  if [[ "$2" == "$3" ]]; then printf '  ok   %s\n' "$1"; pass=$((pass+1));
  else printf '  FAIL %s (want=%q got=%q)\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}
checkc() { # checkc DESC NEEDLE FILE
  if grep -q "$2" "$3" 2>/dev/null; then printf '  ok   %s\n' "$1"; pass=$((pass+1));
  else printf '  FAIL %s (missing %q in %s)\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}

# The phase sequence with consecutive duplicates collapsed: a phase that emits start+ok
# shows once. Asserting on this, not on raw events, keeps the expectation readable.
phase_seq() { # phase_seq STATUS_FILE
  grep -o '"phase":"[A-Z_]*"' "$1" 2>/dev/null \
    | sed 's/^"phase":"//; s/"$//' \
    | awk 'NR==1 || $0!=prev { printf "%s ", $0 } { prev=$0 }'
}

# phase_seq is state-blind: a phase that emitted "red" where "ok" was expected still shows
# up as the same bare token, so a wrong outcome at the right position goes undetected. This
# variant keeps the state, collapsing only exact consecutive phase:state duplicates, so a
# flipped outcome (e.g. FAST_GATE:ok where FAST_GATE:red was required) changes the string.
phase_state_seq() { # phase_state_seq STATUS_FILE
  grep -o '"phase":"[A-Z_]*","state":"[a-z]*"' "$1" 2>/dev/null \
    | sed -E 's/"phase":"([A-Z_]*)","state":"([a-z]*)"/\1:\2/' \
    | awk 'NR==1 || $0!=prev { printf "%s ", $0 } { prev=$0 }'
}

# v6 slice 2: the worker/fixer stub contract is "produce a patch at $PATCH_OUT", not "edit the
# tree". This helper writes a new-file unified diff, the shape every stub here needs (none of the
# fixtures touch a file that already exists in the base — the loop resets to a pristine base and
# git-applies the cumulative patch, so a new-file patch always applies). Exported so loop.sh's
# eval'd stubs (which run in the loop's process, a child of this shell) can call it.
newfile_patch() { # newfile_patch PATH CONTENT   (writes to $PATCH_OUT)
  local p="$1" content="$2" n
  n=$(printf '%s\n' "$content" | wc -l | tr -d ' ')
  {
    printf 'diff --git a/%s b/%s\n' "$p" "$p"
    printf 'new file mode 100644\n--- /dev/null\n+++ b/%s\n' "$p"
    printf '@@ -0,0 +1,%s @@\n' "$n"
    printf '%s\n' "$content" | sed 's/^/+/'
  } > "$PATCH_OUT"
}
export -f newfile_patch

setup_sandbox() {
  SB="$(mktemp -d)"
  BARE="$SB/origin.git"; WORK="$SB/work"; FAKEBIN="$SB/bin"
  GH_CALLS="$SB/gh-calls.log"; : >"$GH_CALLS"
  mkdir -p "$FAKEBIN"

  git init --quiet --bare "$BARE"
  git init --quiet "$WORK"
  cd "$WORK"
  git config user.email t@t; git config user.name t
  git config commit.gpgsign false
  mkdir -p src/main/scala src/test/scala src/it/scala
  echo 'object Base'                 > src/main/scala/Base.scala
  printf 'class BaseTest {\n  // baseline assertion line 1\n  // line 2\n}\n' > src/test/scala/BaseTest.scala
  printf 'class BaseIT {\n  // baseline IT assertion line 1\n  // line 2\n}\n' > src/it/scala/BaseIT.scala
  # copy the harness under test into the sandbox repo
  cp -R "$SRC_HARNESS" "$WORK/harness"
  rm -rf "$WORK/harness/logs"; mkdir -p "$WORK/harness/logs"
  printf '# CONTEXT\nConventions: onion layout, use-case error enum.\n' > CONTEXT.md
  git add -A >/dev/null; git commit --quiet -m init
  git remote add origin "$BARE"
  git push --quiet -u origin main >/dev/null 2>&1 || git push --quiet -u origin master:main >/dev/null 2>&1
  git branch --quiet -M main 2>/dev/null || true
  git push --quiet -u origin main >/dev/null 2>&1

  # fake gh
  cat > "$FAKEBIN/gh" <<GHEOF
#!/usr/bin/env bash
echo "gh \$*" >> "$GH_CALLS"
case "\$1 \$2" in
  "issue list")
    if [[ "\$*" == *"--label in-progress"* ]]; then echo "";
    elif [[ "\$*" == *"--label ready"* ]]; then echo "999";
    elif [[ "\$*" == *"--label blocked"* ]]; then
      for b in \${STUB_BLOCKED_ISSUES:-}; do echo "\$b"; done
    fi ;;
  "issue view")
    if [[ "\$*" == *"--json title,body"* ]]; then
      printf '# US-999 sample\n\nAC1: implement the slice.\nAC2: cover it with a test.\n'
    elif [[ "\$*" == *"--json labels"* ]]; then
      echo "\${STUB_ISSUE_LABELS:-ready}"
    elif [[ "\$*" == *"--json body"* ]]; then
      if [[ "\$3" == "555" ]]; then printf 'Blocked-by: #999\n';
      elif [[ "\$3" == "666" ]]; then printf 'Blocked-by: #999\nBlocked-by: #777\n'; fi
    elif [[ "\$*" == *"--json state"* ]]; then
      echo "\${STUB_DEP_STATE:-CLOSED}"
    fi ;;
  "issue edit") : ;;
  "pr create") echo "https://github.com/test/test/pull/123" ;;
  "pr checks") exit "\${STUB_CI_RC:-0}" ;;
  "pr merge") : ;;
  "pr view")
    if [[ "\$*" == *"statusCheckRollup"* ]]; then echo "\${STUB_CHECK_COUNT:-1}";
    else echo "\${STUB_PR_STATE:-MERGED}"; fi ;;
  "pr comment") : ;;
  *) : ;;
esac
GHEOF
  chmod +x "$FAKEBIN/gh"
  export PATH="$FAKEBIN:$PATH"
}

teardown() { cd /; rm -rf "$SB"; }

run_loop() { # run_loop -> sets RC; env vars for seams passed by caller
  cd "$WORK"
  RC=0
  MAX_ITERS=1 harness_entry >"$SB/loop.out" 2>&1 || RC=$?
}

echo "== Scenario DRY: DRY_RUN renders worker prompt, no mutation =="
setup_sandbox
RC=0; cd "$WORK"
# GATE_CMD overridden so the sandbox preflight (image build + proxy + containerized worker/fixer)
# is skipped: the suite must not depend on a live docker daemon or an API key on the machine
# running it. Overriding GATE_CMD is what the loop keys the whole sandbox preflight off.
DRY_RUN=1 MAX_ITERS=1 GATE_CMD=true harness_entry >"$SB/loop.out" 2>&1 || RC=$?
check "exit code 0 (dry run)" 0 "$RC"
checkc "worker prompt rendered" "AC1: implement the slice" "$WORK/harness/logs/issue-999.prompt.txt"
check "no gh issue edit (no label mutation)" "0" "$(grep -c 'issue edit' "$GH_CALLS" || true; )"
check "no gh pr create" "" "$(grep 'pr create' "$GH_CALLS" || true)"
teardown

echo "== Scenario A: APPROVE happy path -> needs-review, exit 0 =="
setup_sandbox
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
run_loop
check "exit code 0 (APPROVE)" 0 "$RC"
checkc "one fast-gate pass only" "pass1.gate.log" <(ls "$WORK/harness/logs")
check "no local IT gate ran (CI is the sole IT judge now)" "" "$(ls "$WORK/harness/logs" | grep 'it-gate.log' || true)"
check "no pass2 gate (no repair)" "" "$(ls "$WORK/harness/logs" | grep pass2 || true)"
checkc "issue -> needs-review" "issue edit 999 --add-label needs-review" "$GH_CALLS"
checkc "PR created" "pr create" "$GH_CALLS"
check "logfile fields are repo-relative, never absolute" "" \
  "$(grep -o '"logfile":"/[^"]*"' "$WORK/harness/logs/status.jsonl" || true)"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario B: forced REQUEST_CHANGES -> exactly one fix, re-gate, re-review APPROVE =="
setup_sandbox
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='newfile_patch src/main/scala/Slice.scala "object SliceFixed"'
# REVIEW: first call REQUEST_CHANGES, second APPROVE (counter file in sandbox)
export REVIEW_CMD='c="$PWD/harness/logs/.revcount"; n=$(cat "$c" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$c"; if [ "$n" -eq 1 ]; then echo "VERDICT: REQUEST_CHANGES"; else echo "VERDICT: APPROVE"; fi'
run_loop
check "exit code 0 (eventual APPROVE)" 0 "$RC"
check "exactly one FIX dispatch" "1" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
checkc "two fast-gate passes (re-gate)" "pass2.gate.log" <(ls "$WORK/harness/logs")
check "no third pass" "" "$(ls "$WORK/harness/logs" | grep pass3 || true)"
checkc "issue -> needs-review" "issue edit 999 --add-label needs-review" "$GH_CALLS"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario C: forced fast-gate-RED, shared budget 2 -> needs-human + PR =="
setup_sandbox
export GATE_CMD=false
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='newfile_patch src/main/scala/Slice.scala "object Slice$RANDOM"'
export REVIEW_CMD='echo "VERDICT: APPROVE"'   # never reached (fast gate always RED)
NOTIFY_LOG="$SB/notify-c.log"; : >"$NOTIFY_LOG"
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
# Driver treats needs-human (iterate rc=40) as a handled outcome and continues the loop, so
# the SCRIPT exits 0; the FAIL path is proven by needs-human + PR + 2 fixes below.
check "script exits 0 (needs-human handled, not aborted)" 0 "$RC"
checkc "driver logged budget-exhausted terminal" "needs-human" "$SB/loop.out"
check "exactly two FIX dispatches (budget 2)" "2" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
checkc "three fast-gate passes (2 fixes + final RED)" "pass3.gate.log" <(ls "$WORK/harness/logs")
check "no local IT gate ran (CI is the sole IT judge now)" "" "$(ls "$WORK/harness/logs" | grep 'it-gate.log' || true)"
check "no fourth pass" "" "$(ls "$WORK/harness/logs" | grep pass4 || true)"
check "reviewer never ran (RED never renders a review prompt)" "" "$(ls "$WORK/harness/logs" | grep 'review.prompt.txt' || true)"
checkc "issue -> needs-human" "issue edit 999 --add-label needs-human" "$GH_CALLS"
checkc "PR still opened (audit trail)" "pr create" "$GH_CALLS"
checkc "notify fired on needs-human" "needs-human" "$NOTIFY_LOG"
check  "notify fired exactly once"   "1" "$(wc -l < "$NOTIFY_LOG" | tr -d ' ')"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

# v6 slice 3: the worker/fixer runs inside a sandbox container; a dispatch that exceeds
# ITER_TIMEOUT kills the container and returns 124. dispatch_worker maps that 124 to an infra
# fault that spends NO repair budget (rc 50). The stub path forwards a 124 exit from the seam
# command, so `IMPL_CMD='exit 124'` deterministically simulates the container-dispatch timeout
# without a real container. (The actual container kill is covered by the sandbox infra-fault
# test; here we prove the state-machine transition.)
echo "== Scenario D: worker container dispatch timeout (rc 124) -> exit 50, no budget spent =="
setup_sandbox
NOTIFY_LOG_D="$SB/notify-d.log"; : >"$NOTIFY_LOG_D"
export GATE_CMD=true
export IMPL_CMD='exit 124'                             # simulates the container-dispatch timeout
export FIX_CMD='false'                                 # must never run (no budget spent)
export REVIEW_CMD='echo "VERDICT: APPROVE"'            # must never run
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG_D"
run_loop
check "exit code 50 (infra fault)" 50 "$RC"
checkc "dispatch timeout logged as infra fault" "half-finished worker must not reach the gates" "$SB/loop.out"
check "zero FIX dispatches (no budget spent)" "0" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
check "gates never ran (a timed-out worker never reaches them)" "" "$(ls "$WORK/harness/logs" | grep 'gate.log' || true)"
check "no PR created" "" "$(grep 'pr create' "$GH_CALLS" || true)"
check "no needs-human label" "" "$(grep 'needs-human' "$GH_CALLS" || true)"
checkc "issue marked in-progress" "issue edit 999 --add-label in-progress" "$GH_CALLS"
check "in-progress never removed (resumable next tick)" "0" "$(grep -c 'remove-label in-progress' "$GH_CALLS" || true)"
check "phase sequence stops at the timed-out IMPL (no gate, no review)" \
  "PICK IMPL DONE " \
  "$(phase_seq "$WORK/harness/logs/status.jsonl")"
checkc "IMPL phase emitted red with a timeout detail" 'IMPL","state":"red"' "$WORK/harness/logs/status.jsonl"
checkc "notify fired on infra fault" "infra fault" "$NOTIFY_LOG_D"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario E: fixer container dispatch timeout (rc 124) -> exit 50, no PR, in-progress kept =="
setup_sandbox
NOTIFY_LOG_E="$SB/notify-e.log"; : >"$NOTIFY_LOG_E"
export GATE_CMD=false                                  # forces a FIX dispatch
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='exit 124'                              # the FIX dispatch times out
export REVIEW_CMD='echo "VERDICT: APPROVE"'            # must never run
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG_E"
run_loop
check "exit code 50 (infra fault)" 50 "$RC"
checkc "FIX timeout logged as infra fault" "FIX worker timed out" "$SB/loop.out"
check "exactly one FIX dispatch attempted, then halted" "1" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
check "no PR created" "" "$(grep 'pr create' "$GH_CALLS" || true)"
check "no needs-human label" "" "$(grep 'needs-human' "$GH_CALLS" || true)"
check "in-progress never removed (resumable next tick)" "0" "$(grep -c 'remove-label in-progress' "$GH_CALLS" || true)"
checkc "notify fired on infra fault" "infra fault" "$NOTIFY_LOG_E"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario F: no ready issue idles WITHOUT latching -> exit 0, no STOP.md, resumes next tick =="
setup_sandbox
# GATE_CMD=true skips the sandbox preflight (no docker/API key needed) for both runs — the idle
# path never reaches a gate, and the resume run stubs every dispatch.
export GATE_CMD=true
# Override the gh stub so BOTH in-progress and ready lists come back empty (fully idle).
cat > "$FAKEBIN/gh" <<GHEOF
#!/usr/bin/env bash
echo "gh \$*" >> "$GH_CALLS"
case "\$1 \$2" in
  "issue list") echo "" ;;
  *) : ;;
esac
GHEOF
chmod +x "$FAKEBIN/gh"
run_loop
check "exit code 0 (idle, no work)" 0 "$RC"
checkc "logged idle (not STOP)" "idle, exiting" "$SB/loop.out"
check "no STOP.md written (idle must not latch)" "" "$(ls "$WORK/STOP.md" 2>/dev/null || true)"
check "no issue edit (nothing started)" "0" "$(grep -c 'issue edit' "$GH_CALLS" || true)"
# Now a US goes ready: the very next tick must resume on its own (no manual cleanup needed).
cat > "$FAKEBIN/gh" <<GHEOF
#!/usr/bin/env bash
echo "gh \$*" >> "$GH_CALLS"
case "\$1 \$2" in
  "issue list")
    if [[ "\$*" == *"--label in-progress"* ]]; then echo "";
    elif [[ "\$*" == *"--label ready"* ]]; then echo "999"; fi ;;
  "issue view") printf '# US-999 sample\n\nAC1: implement the slice.\n' ;;
  "pr create") echo "https://github.com/test/test/pull/321" ;;
  *) : ;;
esac
GHEOF
chmod +x "$FAKEBIN/gh"
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='true'
export REVIEW_CMD='printf "VERDICT: APPROVE\n"'
run_loop
check "resumes next tick without manual cleanup (exit 0)" 0 "$RC"
checkc "picked the newly-ready issue" "issue edit 999 --add-label in-progress" "$GH_CALLS"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario G: protected-path patch -> needs-human + PR, no FIX, reviewer never runs =="
setup_sandbox
export GATE_CMD=true
# The worker's PATCH touches a protected file (harness/). The guard reads the patch's file list,
# so any harness/ path (other than gitignored harness/logs) trips it — the rejected patch is
# never applied to the tree.
export IMPL_CMD='newfile_patch harness/evil.txt "pwned"'
export FIX_CMD='false'                                 # must never run (fixer = violating agent class)
export REVIEW_CMD='echo "VERDICT: APPROVE"'            # must never run
run_loop
check "script exits 0 (needs-human handled, not aborted)" 0 "$RC"
checkc "driver output mentions protected-path" "protected-path" "$SB/loop.out"
check "no FIX dispatch" "0" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
check "reviewer never ran" "" "$(ls "$WORK/harness/logs" | grep 'review.prompt.txt' || true)"
check "gates never ran (violation short-circuits the gates)" "" "$(ls "$WORK/harness/logs" | grep 'gate.log' || true)"
check "rejected patch NOT applied (no harness/evil.txt in the tree)" "" "$(ls "$WORK/harness/evil.txt" 2>/dev/null || true)"
checkc "issue -> needs-human" "issue edit 999 --add-label needs-human" "$GH_CALLS"
checkc "PR still opened (audit trail)" "pr create" "$GH_CALLS"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario H: empty reviewer output = infra fault -> exit 50, issue stays in-progress =="
setup_sandbox
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='false'                                 # must never run (no budget spent)
export REVIEW_CMD='true'                               # empty review file: crashed/timed-out reviewer
run_loop
check "exit code 50 (infra fault)" 50 "$RC"
checkc "driver logged infra fault" "infra fault — exiting for inspection" "$SB/loop.out"
check "no FIX dispatch (no budget spent)" "0" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
check "no needs-human label" "" "$(grep 'needs-human' "$GH_CALLS" || true)"
check "no PR created" "" "$(grep 'pr create' "$GH_CALLS" || true)"
checkc "issue marked in-progress" "issue edit 999 --add-label in-progress" "$GH_CALLS"
check "in-progress never removed (resumable next tick)" "0" "$(grep -c 'remove-label in-progress' "$GH_CALLS" || true)"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario I: gate timeout (rc 124) = infra fault -> exit 50, no budget spent =="
setup_sandbox
# run_gate execs the cmd word-split (no shell eval), so the 124-stub is a script on PATH.
cat > "$FAKEBIN/gate-timeout" <<'GTEOF'
#!/usr/bin/env bash
exit 124
GTEOF
chmod +x "$FAKEBIN/gate-timeout"
export GATE_CMD=gate-timeout
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='false'                                 # must never run (no budget spent)
export REVIEW_CMD='echo "VERDICT: APPROVE"'            # must never run
NOTIFY_LOG_I="$SB/notify-i.log"; : >"$NOTIFY_LOG_I"
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG_I"
run_loop
check "exit code 50 (infra fault)" 50 "$RC"
checkc "gate-timeout logged as infra fault" "infra fault, not a code failure" "$SB/loop.out"
check "zero FIX dispatches (no budget spent)" "0" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
check "no PR created" "" "$(grep 'pr create' "$GH_CALLS" || true)"
check "no needs-human label" "" "$(grep 'needs-human' "$GH_CALLS" || true)"
check "no local IT gate ran (CI is the sole IT judge now)" "" "$(ls "$WORK/harness/logs" | grep 'it-gate.log' || true)"
checkc "notify fired on infra fault" "infra fault" "$NOTIFY_LOG_I"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario J: class-1 SUCCESS + CI green -> auto-merge, notify, exit 0 =="
setup_sandbox
NOTIFY_LOG="$SB/notify-j.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export STUB_BLOCKED_ISSUES="555 666"
export STUB_DEP_STATE=OPEN
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
check  "exit code 0 (merged)" 0 "$RC"
checkc "CI wait ran (default gh pr checks through stub)" "pr checks" "$GH_CALLS"
checkc "merge invoked (default gh pr merge through stub)" "pr merge 123 --squash --delete-branch" "$GH_CALLS"
checkc "merge verified (pr view state)" "pr view 123" "$GH_CALLS"
check  "issue NOT flipped to needs-review (auto-merge path)" "" "$(grep 'add-label needs-review' "$GH_CALLS" || true)"
checkc "in-progress label removed after merge" "issue edit 999 --remove-label in-progress" "$GH_CALLS"
checkc "notify says auto-merged" "auto-merged" "$NOTIFY_LOG"
checkc "blocked dependent flipped to ready (all deps closed)" "issue edit 555 --add-label ready --remove-label blocked" "$GH_CALLS"
check  "dependent with an open dep NOT flipped" "" "$(grep 'issue edit 666 --add-label ready' "$GH_CALLS" || true)"
check "phase sequence covers the full auto-merge path" \
  "PICK IMPL FAST_GATE REVIEW PR CI_WAIT MERGE DONE " \
  "$(phase_seq "$WORK/harness/logs/status.jsonl")"
unset STUB_ISSUE_LABELS STUB_BLOCKED_ISSUES STUB_DEP_STATE GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario K: class-1 SUCCESS + CI RED -> needs-human, NO merge, no self-repair =="
setup_sandbox
NOTIFY_LOG="$SB/notify-k.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export STUB_CI_RC=1
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='false'   # must never run: no self-repair against the independent check
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
check  "script exits 0 (CI-red handled, not aborted)" 0 "$RC"
check  "NO merge attempted" "" "$(grep 'pr merge' "$GH_CALLS" || true)"
checkc "PR comment explains CI red" "pr comment" "$GH_CALLS"
checkc "issue -> needs-human" "issue edit 999 --add-label needs-human" "$GH_CALLS"
check  "no FIX dispatched (never self-repair against CI)" "0" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
checkc "notify says CI RED" "CI RED" "$NOTIFY_LOG"
unset STUB_ISSUE_LABELS STUB_CI_RC GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario L: class-1 SUCCESS + CI wait timeout -> rc 50, issue stays in-progress =="
setup_sandbox
NOTIFY_LOG="$SB/notify-l.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export STUB_CI_RC=124
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
check  "exit code 50 (infra fault)" 50 "$RC"
check  "NO merge attempted" "" "$(grep 'pr merge' "$GH_CALLS" || true)"
check  "issue NOT flipped (stays in-progress for resume)" "" "$(grep -E 'issue edit 999 --(add-label needs-|remove-label in-progress)' "$GH_CALLS" || true)"
checkc "notify fired (infra fault)" "infra fault" "$NOTIFY_LOG"
unset STUB_ISSUE_LABELS STUB_CI_RC GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario M: class-2 SUCCESS -> stop-at-PR path unchanged, no CI wait, no merge =="
setup_sandbox
export STUB_ISSUE_LABELS="ready class-2"
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
run_loop
check  "exit code 0" 0 "$RC"
checkc "issue -> needs-review (human merges)" "issue edit 999 --add-label needs-review" "$GH_CALLS"
check  "no CI wait" "" "$(grep 'pr checks' "$GH_CALLS" || true)"
check  "no merge" "" "$(grep 'pr merge' "$GH_CALLS" || true)"
unset STUB_ISSUE_LABELS GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario N: merge not verified (pr view != MERGED) -> rc 50 =="
setup_sandbox
NOTIFY_LOG="$SB/notify-n.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export STUB_PR_STATE=OPEN
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
check  "exit code 50 (unverified merge = infra fault)" 50 "$RC"
checkc "merge WAS attempted" "pr merge" "$GH_CALLS"
check  "in-progress NOT removed (unverified)" "" "$(grep 'issue edit 999 --remove-label in-progress' "$GH_CALLS" || true)"
checkc "notify fired (infra fault)" "infra fault" "$NOTIFY_LOG"
unset STUB_ISSUE_LABELS STUB_PR_STATE GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario O: merge command fails -> rc 50, in-progress kept =="
setup_sandbox
NOTIFY_LOG="$SB/notify-o.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export MERGE_CMD=false
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
check  "exit code 50 (merge command failed = infra fault)" 50 "$RC"
check  "no merge-verify pr view (verify not reached)" "" "$(grep 'pr view 123 --json state' "$GH_CALLS" || true)"
check  "in-progress NOT removed" "" "$(grep 'issue edit 999 --remove-label in-progress' "$GH_CALLS" || true)"
checkc "notify fired (infra fault)" "infra fault" "$NOTIFY_LOG"
unset STUB_ISSUE_LABELS MERGE_CMD GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

# A push races the workflow scheduler: for the first few seconds the PR has zero checks and
# `gh pr checks` exits nonzero with "no checks reported". That is not a red build. Regression
# for the false needs-human burn on PR #28 (issue #26).
echo "== Scenario P: CI check registers late -> loop waits, then merges =="
setup_sandbox
NOTIFY_LOG="$SB/notify-p.log"; : >"$NOTIFY_LOG"
COUNTER="$SB/appear.count"; printf '0\n' >"$COUNTER"
export STUB_ISSUE_LABELS="ready class-1"
# rollup is empty on the first two polls, then the check registers
export CI_APPEAR_CMD='n=$(cat '"$COUNTER"'); printf "%s\n" $((n+1)) > '"$COUNTER"'; [ "$n" -ge 2 ] && echo 1 || echo 0'
export CI_APPEAR_INTERVAL=1
export CI_APPEAR_TIMEOUT=30
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
check  "exit code 0 (waited for the check, then merged)" 0 "$RC"
check  "polled until the check registered" "3" "$(cat "$COUNTER")"
checkc "CI wait ran only after the check appeared" "pr checks" "$GH_CALLS"
checkc "merge invoked" "pr merge 123 --squash --delete-branch" "$GH_CALLS"
check  "NOT flipped to needs-human (empty rollup is not a red build)" "" "$(grep 'needs-human' "$GH_CALLS" || true)"
check  "no CI-red PR comment" "" "$(grep 'pr comment' "$GH_CALLS" || true)"
unset STUB_ISSUE_LABELS CI_APPEAR_CMD CI_APPEAR_INTERVAL CI_APPEAR_TIMEOUT GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario Q: CI check never registers -> rc 50 infra fault, NOT needs-human =="
setup_sandbox
NOTIFY_LOG="$SB/notify-q.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export CI_APPEAR_CMD='echo 0'   # rollup stays empty forever
export CI_APPEAR_INTERVAL=1
export CI_APPEAR_TIMEOUT=3
export GATE_CMD=true
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
check  "exit code 50 (never registered = infra fault, not a code failure)" 50 "$RC"
check  "CI wait never ran (nothing to watch)" "" "$(grep 'pr checks' "$GH_CALLS" || true)"
check  "NO merge attempted" "" "$(grep 'pr merge' "$GH_CALLS" || true)"
check  "NOT flipped to needs-human" "" "$(grep 'needs-human' "$GH_CALLS" || true)"
check  "issue stays in-progress for resume" "" "$(grep 'issue edit 999 --remove-label in-progress' "$GH_CALLS" || true)"
checkc "notify fired (infra fault)" "infra fault" "$NOTIFY_LOG"
unset STUB_ISSUE_LABELS CI_APPEAR_CMD CI_APPEAR_INTERVAL CI_APPEAR_TIMEOUT GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

# v6 slice 2: the patch guard is the choke point every agent change crosses. R/S/T exercise the
# three new rejection modes — a protected CI-workflow path, a patch that will not apply, and an
# oversized patch. All three are computed from the extracted patch, before it can be merged.
echo "== Scenario R: patch touching a CI workflow -> needs-human + PR, guard rejects, not applied =="
setup_sandbox
export GATE_CMD=true
# The guard reads the patch's file list; a .github/ path is a protected CI-workflow class.
export IMPL_CMD='newfile_patch .github/workflows/evil.yml "on: push"'
export FIX_CMD='false'                                 # must never run
export REVIEW_CMD='echo "VERDICT: APPROVE"'            # must never run
run_loop
check "script exits 0 (needs-human handled)" 0 "$RC"
checkc "driver output mentions protected-path" "protected-path" "$SB/loop.out"
check "no FIX dispatch" "0" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
check "reviewer never ran" "" "$(ls "$WORK/harness/logs" | grep 'review.prompt.txt' || true)"
check "gates never ran (guard rejection short-circuits the gates)" "" "$(ls "$WORK/harness/logs" | grep 'gate.log' || true)"
check "CI workflow NOT applied (no .github/workflows/evil.yml in the tree)" "" "$(ls "$WORK/.github/workflows/evil.yml" 2>/dev/null || true)"
checkc "issue -> needs-human" "issue edit 999 --add-label needs-human" "$GH_CALLS"
checkc "PR still opened (audit trail)" "pr create" "$GH_CALLS"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario S: patch that fails to apply -> rc 50 infra fault, no budget spent =="
setup_sandbox
NOTIFY_LOG_S="$SB/notify-s.log"; : >"$NOTIFY_LOG_S"
export GATE_CMD=true
# A 'new file' patch for a path that already exists in the base cannot apply (git apply: already
# exists in index). An apply conflict is an infra fault, not a gate failure — no budget spent.
export IMPL_CMD='newfile_patch src/main/scala/Base.scala "object Dup"'
export FIX_CMD='false'                                 # must never run (no budget spent)
export REVIEW_CMD='echo "VERDICT: APPROVE"'            # must never run
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG_S"
run_loop
check "exit code 50 (apply conflict = infra fault)" 50 "$RC"
checkc "driver logged infra fault" "infra fault — exiting for inspection" "$SB/loop.out"
check "zero FIX dispatches (no budget spent)" "0" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
check "no PR created" "" "$(grep 'pr create' "$GH_CALLS" || true)"
check "no needs-human label" "" "$(grep 'needs-human' "$GH_CALLS" || true)"
check "gates never ran (apply precedes the gates)" "" "$(ls "$WORK/harness/logs" | grep 'gate.log' || true)"
checkc "issue marked in-progress" "issue edit 999 --add-label in-progress" "$GH_CALLS"
check "in-progress never removed (resumable next tick)" "0" "$(grep -c 'remove-label in-progress' "$GH_CALLS" || true)"
checkc "notify fired on infra fault" "infra fault" "$NOTIFY_LOG_S"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario T: oversized patch -> needs-human + PR, guard rejects, not applied =="
setup_sandbox
export GATE_CMD=true
export MAX_PATCH_BYTES=10                              # any real patch exceeds this tiny cap
export IMPL_CMD='newfile_patch src/main/scala/Slice.scala "object Slice"'
export FIX_CMD='false'                                 # must never run
export REVIEW_CMD='echo "VERDICT: APPROVE"'            # must never run
run_loop
check "script exits 0 (needs-human handled)" 0 "$RC"
checkc "driver output mentions oversized-patch" "oversized-patch" "$SB/loop.out"
check "no FIX dispatch" "0" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
check "reviewer never ran" "" "$(ls "$WORK/harness/logs" | grep 'review.prompt.txt' || true)"
check "gates never ran (guard rejection short-circuits the gates)" "" "$(ls "$WORK/harness/logs" | grep 'gate.log' || true)"
check "oversized patch NOT applied (no Slice.scala in the tree)" "" "$(ls "$WORK/src/main/scala/Slice.scala" 2>/dev/null || true)"
checkc "issue -> needs-human" "issue edit 999 --add-label needs-human" "$GH_CALLS"
checkc "PR still opened (audit trail)" "pr create" "$GH_CALLS"
unset GATE_CMD MAX_PATCH_BYTES IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo
echo "==== $pass passed, $fail failed ===="
[[ "$fail" -eq 0 ]]
