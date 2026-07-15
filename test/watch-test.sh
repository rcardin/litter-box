#!/usr/bin/env bash
# Fixture tests for render_banner (harness/lib/banner.sh).
# Pure function: (status.jsonl, alive, now) -> 4 lines. No terminal, no loop, no gh.
set -euo pipefail

SRC_HARNESS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../lib/banner.sh
. "$SRC_HARNESS/lib/banner.sh"

pass=0; fail=0
check() { # check DESC EXPECTED ACTUAL
  if [[ "$2" == "$3" ]]; then printf '  ok   %s\n' "$1"; pass=$((pass+1));
  else printf '  FAIL %s\n       want=%q\n        got=%q\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}
line() { echo "$1" | sed -n "${2}p"; }   # line "$out" 2 -> second line

SB="$(mktemp -d)"
trap 'rm -rf "$SB"' EXIT

ev() { # ev TS PHASE STATE PASS BUDGET [LOGFILE] [DETAIL] [ISSUE] [RUN] [ITER]
  printf '{"ts":%s,"pid":4711,"run":"%s","iter":%s,"issue":"%s","phase":"%s","state":"%s","pass":%s,"budget":%s,"logfile":"%s","detail":"%s"}\n' \
    "$1" "${9:-100}" "${10:-1}" "${8:-5}" "$2" "$3" "$4" "$5" "${6:-}" "${7:-}"
}

# v6 slice 3 removed the local IT gate, so the banner's fixed chip row is PICK/IMPL/FAST_GATE
# only — no IT_GATE chip. Integration tests are judged by CI (surfaced on the PR / CI_WAIT chip).
echo "== Fixture A: fast gate running, loop alive -> running banner with elapsed =="
F="$SB/a.jsonl"
{ ev 100 PICK      ok    0 2
  ev 101 IMPL      start 0 2 harness/logs/issue-5-iter1.claude.log
  ev 500 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log
  ev 748 FAST_GATE start 1 2 harness/logs/issue-5-pass1.gate.log
} > "$F"
out="$(render_banner "$F" 1 1000)"
check "A line count is 4"  "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "A header"  "US-5 · iter 1 · pass 1 · budget 2"    "$(line "$out" 1)"
check "A chips 1" "✓ pick  ✓ impl  ▶ fast 4m12s"         "$(line "$out" 2)"
check "A chips 2" "· rev  · pr  · ci  · merge"           "$(line "$out" 3)"
check "A status"  "RUNNING (pid 4711)"                   "$(line "$out" 4)"

echo "== Fixture B: fast gate RED, budget exhausted -> red chip + fix badge =="
F="$SB/b.jsonl"
{ ev 100 PICK      ok    0 2
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log
  ev 200 FAST_GATE start 1 2 harness/logs/issue-5-pass1.gate.log
  ev 260 FAST_GATE red   1 2 harness/logs/issue-5-pass1.gate.log
  ev 261 FIX       start 1 1 harness/logs/issue-5-pass1.fix.claude.log
  ev 400 FIX       ok    1 1 harness/logs/issue-5-pass1.fix.claude.log
  ev 401 FAST_GATE start 2 1 harness/logs/issue-5-pass2.gate.log
  ev 460 FAST_GATE red   2 1 harness/logs/issue-5-pass2.gate.log
  ev 461 FIX       start 2 0 harness/logs/issue-5-pass2.fix.claude.log
  ev 600 FIX       ok    2 0 harness/logs/issue-5-pass2.fix.claude.log
  ev 601 FAST_GATE start 3 0 harness/logs/issue-5-pass3.gate.log
  ev 660 FAST_GATE red   3 0 harness/logs/issue-5-pass3.gate.log
} > "$F"
out="$(render_banner "$F" 1 1000)"
check "B header"  "US-5 · iter 1 · pass 3 · budget 0"  "$(line "$out" 1)"
check "B chips 1" "✓ pick  ✓ impl  ✗ fast  ↺ fix 2"    "$(line "$out" 2)"
check "B status"  "RUNNING (pid 4711)"                 "$(line "$out" 4)"

echo "== Fixture C: pid dead, no terminal event -> stale, names the phase =="
F="$SB/c.jsonl"
{ ev 100 PICK      ok    0 2
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log
  ev 748 FAST_GATE start 1 2 harness/logs/issue-5-pass1.gate.log
} > "$F"
out="$(render_banner "$F" 0 1000)"
check "C status is stale, names phase" "STALE (loop died in fast)" "$(line "$out" 4)"
check "C line count is 4" "4" "$(echo "$out" | wc -l | tr -d ' ')"

echo "== Fixture D: terminal event rc=50 -> infra-fault banner, wins over liveness =="
F="$SB/d.jsonl"
{ ev 100 PICK      ok    0 2
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log
  ev 500 FAST_GATE ok    1 2 harness/logs/issue-5-pass1.gate.log
  ev 900 DONE      end   1 2 "" "rc=50"
} > "$F"
out="$(render_banner "$F" 0 1000)"
check "D status"  "DONE rc=50"                          "$(line "$out" 4)"
check "D chips 1" "✓ pick  ✓ impl  ✓ fast"              "$(line "$out" 2)"

echo "== Fixture E: two runs in one file -> only the newest renders =="
F="$SB/e.jsonl"
{ ev 100 PICK ok 0 2 "" "" 5 100
  ev 110 DONE end 0 2 "" "rc=0" 5 100
  ev 900 PICK ok 0 2 "" "" 7 900
} > "$F"
out="$(render_banner "$F" 1 1000)"
check "E header shows the newest run's issue" "US-7 · iter 1 · pass 0 · budget 2" "$(line "$out" 1)"
check "E old run's DONE does not leak"        "RUNNING (pid 4711)"                "$(line "$out" 4)"

echo "== Fixture F: torn line mid-file -> skipped, banner still renders =="
F="$SB/f.jsonl"
{ ev 100 PICK ok 0 2
  printf '{"ts":123,"pha\n'
  ev 748 FAST_GATE start 1 2 harness/logs/issue-5-pass1.gate.log
} > "$F"
out="$(render_banner "$F" 1 1000)"
check "F line count is still 4" "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "F renders the valid tail" "✓ pick  · impl  ▶ fast 4m12s" "$(line "$out" 2)"

echo "== Fixture G: empty file -> placeholder, still 4 lines =="
F="$SB/g.jsonl"; : > "$F"
out="$(render_banner "$F" 1 1000)"
check "G line count is 4" "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "G says no run yet" "no run yet" "$(line "$out" 1)"

echo "== Fixture H: valid JSON but non-event line (bare scalar) is dropped, not crashed =="
F="$SB/h.jsonl"
{ ev 100 PICK  ok    0 2
  ev 101 IMPL  start 0 2 harness/logs/issue-5-iter1.claude.log
  echo 'true'
} > "$F"
set +e
out="$(render_banner "$F" 1 1000)"
rc=$?
set -e
check "H exits 0"                 "0" "$rc"
check "H line count is 4"         "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "H renders the valid tail"  "✓ pick  ▶ impl 14m59s  · fast" "$(line "$out" 2)"

echo "== Fixture I: detail with embedded newline is sanitized to one line =="
F="$SB/i.jsonl"
{ ev 100 PICK      ok  0 2
  ev 101 IMPL      ok  0 2 harness/logs/issue-5-iter1.claude.log
  ev 500 FAST_GATE ok  1 2 harness/logs/issue-5-pass1.gate.log
  ev 900 DONE      end 1 2 "" "rc=1\nfatal: something broke"
} > "$F"
out="$(render_banner "$F" 0 1000)"
check "I line count is 4"              "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "I status has no embedded newline" "DONE rc=1 fatal: something broke" "$(line "$out" 4)"

echo "== Fixture J: 500+ line run -> tail bound must not truncate early PICK/IMPL chips =="
F="$SB/j.jsonl"
{ ev 100 PICK ok 0 2
  ev 101 IMPL ok 0 2 harness/logs/issue-5-iter1.claude.log
  ts=200
  i=1
  while [[ $i -le 250 ]]; do
    ev "$ts" FIX start 1 2 harness/logs/issue-5-pass1.fix.claude.log
    ts=$((ts+1))
    ev "$ts" FIX ok 1 2 harness/logs/issue-5-pass1.fix.claude.log
    ts=$((ts+1))
    i=$((i+1))
  done
  ev 900 FAST_GATE red 1 2 harness/logs/issue-5-pass1.gate.log
} > "$F"
check "J fixture has more than 500 lines" "1" "$(( $(wc -l < "$F") > 500 ))"
out="$(render_banner "$F" 1 1000)"
check "J header"  "US-5 · iter 1 · pass 1 · budget 2"                    "$(line "$out" 1)"
check "J chips 1" "✓ pick  ✓ impl  ✗ fast  ↺ fix 250"                    "$(line "$out" 2)"

echo "== Fixture K: same run, later iteration in flight -> the earlier iteration's DONE must not leak =="
F="$SB/k.jsonl"
{ ev 100 PICK      ok    0 2 ""                                   ""              5 200 1
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log ""             5 200 1
  ev 200 FAST_GATE ok    1 2 harness/logs/issue-5-pass1.gate.log   ""             5 200 1
  ev 400 REVIEW    ok    1 2 ""                                   "verdict=APPROVE" 5 200 1
  ev 450 PR        ok    0 2 ""                                   "pr=123"        5 200 1
  ev 500 DONE      end   0 2 ""                                   "rc=0"          5 200 1
  ev 600 PICK      ok    0 2 ""                                   ""              6 200 2
  ev 601 IMPL      start 0 2 harness/logs/issue-6-iter2.claude.log ""             6 200 2
} > "$F"
out="$(render_banner "$F" 1 1000)"
check "K header shows the in-flight issue, not the finished one" "US-6 · iter 2 · pass 0 · budget 2" "$(line "$out" 1)"
check "K chips scope to the in-flight issue only (no leaked gate ticks)" \
  "✓ pick  ▶ impl 6m39s  · fast" "$(line "$out" 2)"
check "K status is RUNNING, not the earlier iteration's DONE" "RUNNING (pid 4711)" "$(line "$out" 4)"

echo "== Fixture L: terminal DONE as the very last event, loop still alive -> DONE beats liveness =="
F="$SB/l.jsonl"
{ ev 100 PICK      ok    0 2 ""                                   ""              5 300
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log ""             5 300
  ev 200 FAST_GATE ok    1 2 harness/logs/issue-5-pass1.gate.log   ""             5 300
  ev 400 REVIEW    ok    1 2 ""                                   "verdict=APPROVE" 5 300
  ev 450 PR        ok    0 2 ""                                   "pr=123"        5 300
  ev 500 DONE      end   0 2 ""                                   "rc=0"          5 300
} > "$F"
out="$(render_banner "$F" 1 1000)"
check "L status is DONE even though the pid is alive (terminal beats liveness, not just staleness)" \
  "DONE rc=0" "$(line "$out" 4)"

echo
echo "==== $pass passed, $fail failed ===="
[[ "$fail" -eq 0 ]]
