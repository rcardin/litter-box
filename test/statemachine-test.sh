#!/usr/bin/env bash
# Drives harness/loop.sh through its v1 state machine in a throwaway sandbox.
# Stubs gh (fake bin on PATH), the gate (GATE_CMD), and the three claude dispatches
# (IMPL_CMD/FIX_CMD/REVIEW_CMD). No real GitHub, no Opus tokens.
set -euo pipefail

# The harness/ dir under test defaults to the one this script lives in (harness/test/..).
SRC_HARNESS="${SRC_HARNESS:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

pass=0; fail=0
check() { # check DESC EXPECTED ACTUAL
  if [[ "$2" == "$3" ]]; then printf '  ok   %s\n' "$1"; pass=$((pass+1));
  else printf '  FAIL %s (want=%q got=%q)\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}
checkc() { # checkc DESC NEEDLE FILE
  if grep -q "$2" "$3" 2>/dev/null; then printf '  ok   %s\n' "$1"; pass=$((pass+1));
  else printf '  FAIL %s (missing %q in %s)\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}

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
  "pr view") echo "\${STUB_PR_STATE:-MERGED}" ;;
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
  MAX_ITERS=1 "$WORK/harness/loop.sh" >"$SB/loop.out" 2>&1 || RC=$?
}

echo "== Scenario DRY: DRY_RUN renders worker prompt, no mutation =="
setup_sandbox
RC=0; cd "$WORK"
# IT_GATE_CMD overridden so the docker preflight is skipped: the suite must not depend on
# a live docker daemon on the machine running it.
DRY_RUN=1 MAX_ITERS=1 IT_GATE_CMD=true "$WORK/harness/loop.sh" >"$SB/loop.out" 2>&1 || RC=$?
check "exit code 0 (dry run)" 0 "$RC"
checkc "worker prompt rendered" "AC1: implement the slice" "$WORK/harness/logs/issue-999.prompt.txt"
check "no gh issue edit (no label mutation)" "0" "$(grep -c 'issue edit' "$GH_CALLS" || true; )"
check "no gh pr create" "" "$(grep 'pr create' "$GH_CALLS" || true)"
teardown

echo "== Scenario A: APPROVE happy path -> needs-review, exit 0 =="
setup_sandbox
export GATE_CMD=true
export IT_GATE_CMD=true
export IMPL_CMD='mkdir -p src/main/scala && printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
run_loop
check "exit code 0 (APPROVE)" 0 "$RC"
checkc "one fast-gate pass only" "pass1.gate.log" <(ls "$WORK/harness/logs")
checkc "IT gate ran after fast-GREEN (pass1)" "pass1.it-gate.log" <(ls "$WORK/harness/logs")
check "no pass2 gate (no repair)" "" "$(ls "$WORK/harness/logs" | grep pass2 || true)"
checkc "issue -> needs-review" "issue edit 999 --add-label needs-review" "$GH_CALLS"
checkc "PR created" "pr create" "$GH_CALLS"
unset GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario B: forced REQUEST_CHANGES -> exactly one fix, re-gate, re-review APPROVE =="
setup_sandbox
export GATE_CMD=true
export IT_GATE_CMD=true
export IMPL_CMD='printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='printf "object SliceFixed\n" > src/main/scala/Slice.scala'
# REVIEW: first call REQUEST_CHANGES, second APPROVE (counter file in sandbox)
export REVIEW_CMD='c="$PWD/harness/logs/.revcount"; n=$(cat "$c" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$c"; if [ "$n" -eq 1 ]; then echo "VERDICT: REQUEST_CHANGES"; else echo "VERDICT: APPROVE"; fi'
run_loop
check "exit code 0 (eventual APPROVE)" 0 "$RC"
check "exactly one FIX dispatch" "1" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
checkc "two fast-gate passes (re-gate)" "pass2.gate.log" <(ls "$WORK/harness/logs")
checkc "two IT-gate passes (re-gate)" "pass2.it-gate.log" <(ls "$WORK/harness/logs")
check "no third pass" "" "$(ls "$WORK/harness/logs" | grep pass3 || true)"
checkc "issue -> needs-review" "issue edit 999 --add-label needs-review" "$GH_CALLS"
unset GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario C: forced fast-gate-RED, shared budget 2 -> needs-human + PR, IT never runs =="
setup_sandbox
export GATE_CMD=false
export IT_GATE_CMD=false   # must never be reached: fast-RED short-circuits the IT gate
export IMPL_CMD='printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='printf "// touch %s\n" "$RANDOM" >> src/main/scala/Slice.scala'
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
check "IT gate NEVER ran (fast-RED short-circuits, no Docker paid)" "" "$(ls "$WORK/harness/logs" | grep 'it-gate.log' || true)"
check "no fourth pass" "" "$(ls "$WORK/harness/logs" | grep pass4 || true)"
check "reviewer never ran (RED never renders a review prompt)" "" "$(ls "$WORK/harness/logs" | grep 'review.prompt.txt' || true)"
checkc "issue -> needs-human" "issue edit 999 --add-label needs-human" "$GH_CALLS"
checkc "PR still opened (audit trail)" "pr create" "$GH_CALLS"
checkc "notify fired on needs-human" "needs-human" "$NOTIFY_LOG"
check  "notify fired exactly once"   "1" "$(wc -l < "$NOTIFY_LOG" | tr -d ' ')"
unset GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario D: forced IT-gate-RED -> exactly one fix from shared budget, re-gate green =="
setup_sandbox
export GATE_CMD=true                                   # fast tier always green
export IMPL_CMD='printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='printf "object SliceFixed\n" > src/main/scala/Slice.scala'
# IT: first call RED, second GREEN. run_gate execs the cmd word-split (no shell eval), so the
# stateful stub is a script on PATH, not an inline compound. Counter under the loop cwd (WORK).
cat > "$FAKEBIN/it-flaky" <<'ITEOF'
#!/usr/bin/env bash
c="harness/logs/.itcount"
n=$(cat "$c" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$c"
[ "$n" -ge 2 ]
ITEOF
chmod +x "$FAKEBIN/it-flaky"
export IT_GATE_CMD=it-flaky
export REVIEW_CMD='echo "VERDICT: APPROVE"'
run_loop
check "exit code 0 (IT green after one fix, then APPROVE)" 0 "$RC"
check "exactly one FIX dispatch (from shared budget)" "1" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
checkc "two fast-gate passes (re-gate after IT-RED)" "pass2.gate.log" <(ls "$WORK/harness/logs")
checkc "two IT-gate passes (RED then GREEN)" "pass2.it-gate.log" <(ls "$WORK/harness/logs")
check "reviewer ran only after IT-GREEN (no pass1 review)" "" "$(ls "$WORK/harness/logs" | grep 'pass1.review.prompt.txt' || true)"
checkc "reviewer ran on pass2 (unit+IT green)" "pass2.review.prompt.txt" <(ls "$WORK/harness/logs")
checkc "issue -> needs-review" "issue edit 999 --add-label needs-review" "$GH_CALLS"
unset GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario E: IT-gate-RED exhausts shared budget 2 -> needs-human + PR, reviewer never runs =="
setup_sandbox
export GATE_CMD=true                                   # fast tier always green
export IT_GATE_CMD=false                               # IT tier always RED
export IMPL_CMD='printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='printf "// touch %s\n" "$RANDOM" >> src/main/scala/Slice.scala'
export REVIEW_CMD='echo "VERDICT: APPROVE"'            # never reached (IT always RED)
run_loop
check "script exits 0 (needs-human handled)" 0 "$RC"
checkc "driver logged budget-exhausted terminal" "needs-human" "$SB/loop.out"
checkc "failure kind is IT-gate-RED" "IT-gate-RED" "$SB/loop.out"
check "exactly two FIX dispatches (budget 2)" "2" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
checkc "three fast-gate passes (all GREEN)" "pass3.gate.log" <(ls "$WORK/harness/logs")
checkc "three IT-gate passes (2 fixes + final RED)" "pass3.it-gate.log" <(ls "$WORK/harness/logs")
check "no fourth pass" "" "$(ls "$WORK/harness/logs" | grep pass4 || true)"
check "reviewer never ran (IT-RED never renders a review prompt)" "" "$(ls "$WORK/harness/logs" | grep 'review.prompt.txt' || true)"
checkc "issue -> needs-human" "issue edit 999 --add-label needs-human" "$GH_CALLS"
checkc "PR still opened (audit trail)" "pr create" "$GH_CALLS"
unset GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario F: no ready issue idles WITHOUT latching -> exit 0, no STOP.md, resumes next tick =="
setup_sandbox
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
export GATE_CMD=true IT_GATE_CMD=true
export IMPL_CMD='printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='true'
export REVIEW_CMD='printf "VERDICT: APPROVE\n"'
run_loop
check "resumes next tick without manual cleanup (exit 0)" 0 "$RC"
checkc "picked the newly-ready issue" "issue edit 999 --add-label in-progress" "$GH_CALLS"
unset GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario G: protected-path violation -> needs-human + PR, no FIX, reviewer never runs =="
setup_sandbox
export GATE_CMD=true
export IT_GATE_CMD=true
# The worker touches a protected file (harness/) AND a normal src file. harness/logs is the
# only gitignored part of harness/, so any other harness file trips the check.
export IMPL_CMD='printf "x" >> harness/fix-prompt.md && printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='false'                                 # must never run (fixer = violating agent class)
export REVIEW_CMD='echo "VERDICT: APPROVE"'            # must never run
run_loop
check "script exits 0 (needs-human handled, not aborted)" 0 "$RC"
checkc "driver output mentions protected-path" "protected-path" "$SB/loop.out"
check "no FIX dispatch" "0" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
check "reviewer never ran" "" "$(ls "$WORK/harness/logs" | grep 'review.prompt.txt' || true)"
check "gates never ran (violation short-circuits the gates)" "" "$(ls "$WORK/harness/logs" | grep 'gate.log' || true)"
checkc "issue -> needs-human" "issue edit 999 --add-label needs-human" "$GH_CALLS"
checkc "PR still opened (audit trail)" "pr create" "$GH_CALLS"
unset GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario H: empty reviewer output = infra fault -> exit 50, issue stays in-progress =="
setup_sandbox
export GATE_CMD=true
export IT_GATE_CMD=true
export IMPL_CMD='printf "object Slice\n" > src/main/scala/Slice.scala'
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
unset GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario I: gate timeout (rc 124) = infra fault -> exit 50, no budget spent =="
setup_sandbox
# run_gate execs the cmd word-split (no shell eval), so the 124-stub is a script on PATH,
# same trick as the it-flaky stub in Scenario D.
cat > "$FAKEBIN/gate-timeout" <<'GTEOF'
#!/usr/bin/env bash
exit 124
GTEOF
chmod +x "$FAKEBIN/gate-timeout"
export GATE_CMD=gate-timeout
export IT_GATE_CMD=false                               # must never be reached
export IMPL_CMD='printf "object Slice\n" > src/main/scala/Slice.scala'
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
check "IT gate never ran" "" "$(ls "$WORK/harness/logs" | grep 'it-gate.log' || true)"
checkc "notify fired on infra fault" "infra fault" "$NOTIFY_LOG_I"
unset GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario J: class-1 SUCCESS + CI green -> auto-merge, notify, exit 0 =="
setup_sandbox
NOTIFY_LOG="$SB/notify-j.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export STUB_BLOCKED_ISSUES="555 666"
export STUB_DEP_STATE=OPEN
export GATE_CMD=true
export IT_GATE_CMD=true
export IMPL_CMD='mkdir -p src/main/scala && printf "object Slice\n" > src/main/scala/Slice.scala'
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
unset STUB_ISSUE_LABELS STUB_BLOCKED_ISSUES STUB_DEP_STATE GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario K: class-1 SUCCESS + CI RED -> needs-human, NO merge, no self-repair =="
setup_sandbox
NOTIFY_LOG="$SB/notify-k.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export STUB_CI_RC=1
export GATE_CMD=true
export IT_GATE_CMD=true
export IMPL_CMD='mkdir -p src/main/scala && printf "object Slice\n" > src/main/scala/Slice.scala'
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
unset STUB_ISSUE_LABELS STUB_CI_RC GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario L: class-1 SUCCESS + CI wait timeout -> rc 50, issue stays in-progress =="
setup_sandbox
NOTIFY_LOG="$SB/notify-l.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export STUB_CI_RC=124
export GATE_CMD=true
export IT_GATE_CMD=true
export IMPL_CMD='mkdir -p src/main/scala && printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
check  "exit code 50 (infra fault)" 50 "$RC"
check  "NO merge attempted" "" "$(grep 'pr merge' "$GH_CALLS" || true)"
check  "issue NOT flipped (stays in-progress for resume)" "" "$(grep -E 'issue edit 999 --(add-label needs-|remove-label in-progress)' "$GH_CALLS" || true)"
checkc "notify fired (infra fault)" "infra fault" "$NOTIFY_LOG"
unset STUB_ISSUE_LABELS STUB_CI_RC GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario M: class-2 SUCCESS -> stop-at-PR path unchanged, no CI wait, no merge =="
setup_sandbox
export STUB_ISSUE_LABELS="ready class-2"
export GATE_CMD=true
export IT_GATE_CMD=true
export IMPL_CMD='mkdir -p src/main/scala && printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
run_loop
check  "exit code 0" 0 "$RC"
checkc "issue -> needs-review (human merges)" "issue edit 999 --add-label needs-review" "$GH_CALLS"
check  "no CI wait" "" "$(grep 'pr checks' "$GH_CALLS" || true)"
check  "no merge" "" "$(grep 'pr merge' "$GH_CALLS" || true)"
unset STUB_ISSUE_LABELS GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario N: merge not verified (pr view != MERGED) -> rc 50 =="
setup_sandbox
NOTIFY_LOG="$SB/notify-n.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export STUB_PR_STATE=OPEN
export GATE_CMD=true
export IT_GATE_CMD=true
export IMPL_CMD='mkdir -p src/main/scala && printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
check  "exit code 50 (unverified merge = infra fault)" 50 "$RC"
checkc "merge WAS attempted" "pr merge" "$GH_CALLS"
check  "in-progress NOT removed (unverified)" "" "$(grep 'issue edit 999 --remove-label in-progress' "$GH_CALLS" || true)"
checkc "notify fired (infra fault)" "infra fault" "$NOTIFY_LOG"
unset STUB_ISSUE_LABELS STUB_PR_STATE GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo "== Scenario O: merge command fails -> rc 50, in-progress kept =="
setup_sandbox
NOTIFY_LOG="$SB/notify-o.log"; : >"$NOTIFY_LOG"
export STUB_ISSUE_LABELS="ready class-1"
export MERGE_CMD=false
export GATE_CMD=true
export IT_GATE_CMD=true
export IMPL_CMD='mkdir -p src/main/scala && printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
export NOTIFY_CMD='printf "%s\n" "$msg" >> '"$NOTIFY_LOG"
run_loop
check  "exit code 50 (merge command failed = infra fault)" 50 "$RC"
check  "no pr view (verify not reached)" "" "$(grep 'pr view' "$GH_CALLS" || true)"
check  "in-progress NOT removed" "" "$(grep 'issue edit 999 --remove-label in-progress' "$GH_CALLS" || true)"
checkc "notify fired (infra fault)" "infra fault" "$NOTIFY_LOG"
unset STUB_ISSUE_LABELS MERGE_CMD GATE_CMD IT_GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD NOTIFY_CMD
teardown

echo
echo "==== $pass passed, $fail failed ===="
[[ "$fail" -eq 0 ]]
