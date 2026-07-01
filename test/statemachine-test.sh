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
  mkdir -p src/main/scala src/test/scala
  echo 'object Base'                 > src/main/scala/Base.scala
  printf 'class BaseTest {\n  // baseline assertion line 1\n  // line 2\n}\n' > src/test/scala/BaseTest.scala
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
    elif [[ "\$*" == *"--label ready"* ]]; then echo "999"; fi ;;
  "issue view") printf '# US-999 sample\n\nAC1: implement the slice.\nAC2: cover it with a test.\n' ;;
  "issue edit") : ;;
  "pr create") : ;;
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
DRY_RUN=1 MAX_ITERS=1 "$WORK/harness/loop.sh" >"$SB/loop.out" 2>&1 || RC=$?
check "exit code 0 (dry run)" 0 "$RC"
checkc "worker prompt rendered" "AC1: implement the slice" "$WORK/harness/logs/issue-999.prompt.txt"
check "no gh issue edit (no label mutation)" "0" "$(grep -c 'issue edit' "$GH_CALLS" || true; )"
check "no gh pr create" "" "$(grep 'pr create' "$GH_CALLS" || true)"
teardown

echo "== Scenario A: APPROVE happy path -> needs-review, exit 0 =="
setup_sandbox
export GATE_CMD=true
export IMPL_CMD='mkdir -p src/main/scala && printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='true'
export REVIEW_CMD='printf "checked AC1/AC2, tests present.\nVERDICT: APPROVE\n"'
run_loop
check "exit code 0 (APPROVE)" 0 "$RC"
checkc "one gate pass only" "pass1.gate.log" <(ls "$WORK/harness/logs")
check "no pass2 gate (no repair)" "" "$(ls "$WORK/harness/logs" | grep pass2 || true)"
checkc "issue -> needs-review" "issue edit 999 --add-label needs-review" "$GH_CALLS"
checkc "PR created" "pr create" "$GH_CALLS"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario B: forced REQUEST_CHANGES -> exactly one fix, re-gate, re-review APPROVE =="
setup_sandbox
export GATE_CMD=true
export IMPL_CMD='printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='printf "object SliceFixed\n" > src/main/scala/Slice.scala'
# REVIEW: first call REQUEST_CHANGES, second APPROVE (counter file in sandbox)
export REVIEW_CMD='c="$PWD/harness/logs/.revcount"; n=$(cat "$c" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$c"; if [ "$n" -eq 1 ]; then echo "VERDICT: REQUEST_CHANGES"; else echo "VERDICT: APPROVE"; fi'
run_loop
check "exit code 0 (eventual APPROVE)" 0 "$RC"
check "exactly one FIX dispatch" "1" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
checkc "two gate passes (re-gate)" "pass2.gate.log" <(ls "$WORK/harness/logs")
check "no third pass" "" "$(ls "$WORK/harness/logs" | grep pass3 || true)"
checkc "issue -> needs-review" "issue edit 999 --add-label needs-review" "$GH_CALLS"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo "== Scenario C: forced gate-RED, shared budget 2 -> needs-human + PR, exit 40 =="
setup_sandbox
export GATE_CMD=false
export IMPL_CMD='printf "object Slice\n" > src/main/scala/Slice.scala'
export FIX_CMD='printf "// touch %s\n" "$RANDOM" >> src/main/scala/Slice.scala'
export REVIEW_CMD='echo "VERDICT: APPROVE"'   # never reached (gate always RED)
run_loop
# Driver treats needs-human (iterate rc=40) as a handled outcome and continues the loop, so
# the SCRIPT exits 0; the FAIL path is proven by needs-human + PR + 2 fixes below.
check "script exits 0 (needs-human handled, not aborted)" 0 "$RC"
checkc "driver logged budget-exhausted terminal" "needs-human" "$SB/loop.out"
check "exactly two FIX dispatches (budget 2)" "2" "$(ls "$WORK/harness/logs" | grep -c '\.fix\.claude\.log' || true)"
checkc "three gate passes (2 fixes + final RED)" "pass3.gate.log" <(ls "$WORK/harness/logs")
check "no fourth pass" "" "$(ls "$WORK/harness/logs" | grep pass4 || true)"
check "reviewer never ran (RED never renders a review prompt)" "" "$(ls "$WORK/harness/logs" | grep 'review.prompt.txt' || true)"
checkc "issue -> needs-human" "issue edit 999 --add-label needs-human" "$GH_CALLS"
checkc "PR still opened (audit trail)" "pr create" "$GH_CALLS"
unset GATE_CMD IMPL_CMD FIX_CMD REVIEW_CMD
teardown

echo
echo "==== $pass passed, $fail failed ===="
[[ "$fail" -eq 0 ]]
