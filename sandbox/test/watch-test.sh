#!/usr/bin/env bash
# Fixture tests for render_banner (resources/observe/lib/banner.sh).
# Pure function: (status.jsonl, alive, now) -> 4 lines. No terminal, no loop, no gh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../../resources/observe/lib/banner.sh
. "$REPO_ROOT/resources/observe/lib/banner.sh"

pass=0; fail=0
check() { # check DESC EXPECTED ACTUAL
  if [[ "$2" == "$3" ]]; then printf '  ok   %s\n' "$1"; pass=$((pass+1));
  else printf '  FAIL %s\n       want=%q\n        got=%q\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}
line() { echo "$1" | sed -n "${2}p"; }   # line "$out" 2 -> second line

SB="$(mktemp -d)"
trap 'rm -rf "$SB"' EXIT

ev() { # ev TS PHASE STATE PASS BUDGET [LOGFILE] [DETAIL] [ISSUE] [RUN] [ITER]
  # ISSUE uses `${8-5}` (no colon), UNSET-only, deliberately unlike the other defaults: a caller
  # that wants to represent a real parked-no-reply tick (issue #28, Fixture M) has to be able to
  # pass an explicit empty issue and have it stay empty, which `${8:-5}` (empty-or-unset) would
  # silently override back to the default.
  printf '{"ts":%s,"pid":4711,"run":"%s","iter":%s,"issue":"%s","phase":"%s","state":"%s","pass":%s,"budget":%s,"logfile":"%s","detail":"%s"}\n' \
    "$1" "${9:-100}" "${10:-1}" "${8-5}" "$2" "$3" "$4" "$5" "${6:-}" "${7:-}"
}

# The shipped stage declaration (issue #40), byte for byte what `Machine.shippedStages` carries
# and `LiveStatusLog.declare` would write for a real run: row 1 is PICK/IMPL/FAST_GATE plus FIX
# as a badge, row 2 is REVIEW/PR/CI_WAIT/MERGE, anchor PICK, terminal DONE. A real run writes
# one of these once per TICK, before that tick's own first phase event (`Machine.runOnce`,
# issue #40 review MAJOR 1), so every fixture below that exercises the shipped shape prepends
# exactly one call to this, matching what actually lands on status.jsonl for a single-tick
# fixture. Fixture P is the deliberate exception: it proves the four line degrade a run with no
# declaration at all still gets.
decl() { # decl [RUN] [TS]
  local run="${1:-100}" ts="${2:-0}"
  printf '{"ts":%s,"pid":4711,"run":"%s","kind":"stages","anchor":"PICK","terminal":"DONE","stages":[%s]}\n' \
    "$ts" "$run" \
    '{"phase":"PICK","chip":"pick","row":1,"badge":false},{"phase":"IMPL","chip":"impl","row":1,"badge":false},{"phase":"FAST_GATE","chip":"fast","row":1,"badge":false},{"phase":"FIX","chip":"fix","row":1,"badge":true},{"phase":"REVIEW","chip":"rev","row":2,"badge":false},{"phase":"PR","chip":"pr","row":2,"badge":false},{"phase":"CI_WAIT","chip":"ci","row":2,"badge":false},{"phase":"MERGE","chip":"merge","row":2,"badge":false}'
}

# v6 slice 3 removed the local IT gate, so the banner's fixed chip row is PICK/IMPL/FAST_GATE
# only — no IT_GATE chip. Integration tests are judged by CI (surfaced on the PR / CI_WAIT chip).
echo "== Fixture A: fast gate running, loop alive -> running banner with elapsed =="
F="$SB/a.jsonl"
{ decl
  ev 100 PICK      ok    0 2
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
{ decl
  ev 100 PICK      ok    0 2
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
{ decl
  ev 100 PICK      ok    0 2
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log
  ev 748 FAST_GATE start 1 2 harness/logs/issue-5-pass1.gate.log
} > "$F"
out="$(render_banner "$F" 0 1000)"
check "C status is stale, names phase" "STALE (loop died in fast)" "$(line "$out" 4)"
check "C line count is 4" "4" "$(echo "$out" | wc -l | tr -d ' ')"

echo "== Fixture D: terminal event rc=50 -> infra-fault banner, wins over liveness =="
F="$SB/d.jsonl"
{ decl
  ev 100 PICK      ok    0 2
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log
  ev 500 FAST_GATE ok    1 2 harness/logs/issue-5-pass1.gate.log
  ev 900 DONE      end   1 2 "" "rc=50"
} > "$F"
out="$(render_banner "$F" 0 1000)"
check "D status"  "DONE rc=50"                          "$(line "$out" 4)"
check "D chips 1" "✓ pick  ✓ impl  ✓ fast"              "$(line "$out" 2)"

echo "== Fixture E: two runs in one file -> only the newest renders =="
F="$SB/e.jsonl"
{ decl 100
  ev 100 PICK ok 0 2 "" "" 5 100
  ev 110 DONE end 0 2 "" "rc=0" 5 100
  decl 900
  ev 900 PICK ok 0 2 "" "" 7 900
} > "$F"
out="$(render_banner "$F" 1 1000)"
check "E header shows the newest run's issue" "US-7 · iter 1 · pass 0 · budget 2" "$(line "$out" 1)"
check "E old run's DONE does not leak"        "RUNNING (pid 4711)"                "$(line "$out" 4)"

echo "== Fixture F: torn line mid-file -> skipped, banner still renders =="
F="$SB/f.jsonl"
{ decl
  ev 100 PICK ok 0 2
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
{ decl
  ev 100 PICK  ok    0 2
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
{ decl
  ev 100 PICK      ok  0 2
  ev 101 IMPL      ok  0 2 harness/logs/issue-5-iter1.claude.log
  ev 500 FAST_GATE ok  1 2 harness/logs/issue-5-pass1.gate.log
  ev 900 DONE      end 1 2 "" "rc=1\nfatal: something broke"
} > "$F"
out="$(render_banner "$F" 0 1000)"
check "I line count is 4"              "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "I status has no embedded newline" "DONE rc=1 fatal: something broke" "$(line "$out" 4)"

echo "== Fixture J: 500+ line run -> tail bound must not truncate early PICK/IMPL chips =="
F="$SB/j.jsonl"
{ decl
  ev 100 PICK ok 0 2
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
{ decl 200
  ev 100 PICK      ok    0 2 ""                                   ""              5 200 1
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
{ decl 300
  ev 100 PICK      ok    0 2 ""                                   ""              5 300
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log ""             5 300
  ev 200 FAST_GATE ok    1 2 harness/logs/issue-5-pass1.gate.log   ""             5 300
  ev 400 REVIEW    ok    1 2 ""                                   "verdict=APPROVE" 5 300
  ev 450 PR        ok    0 2 ""                                   "pr=123"        5 300
  ev 500 DONE      end   0 2 ""                                   "rc=0"          5 300
} > "$F"
out="$(render_banner "$F" 1 1000)"
check "L status is DONE even though the pid is alive (terminal beats liveness, not just staleness)" \
  "DONE rc=0" "$(line "$out" 4)"

echo "== Fixture M: terminal event rc=60, no reply on a parked issue -> parked banner, the shape a real parked-no-reply tick actually produces (issue #28) =="
# `Machine.pickAndSetup`'s StoppedEarly(Parked) branch (the parked-with-no-reply path) returns
# BEFORE cur.iter/cur.issue are ever set and before any PICK/IMPL/FAST_GATE event is emitted, so
# the only STATUS EVENT a real tick like this ever writes is the terminal DONE, with iter=0 and
# issue="". The stage declaration (issue #40) still lands ahead of it: `Machine.runOnce` writes
# it before `Pick` ever runs, on EVERY tick, not just the process's first one (issue #40 review,
# MAJOR 1: a declaration written once per process would eventually scroll out of banner.sh's own
# `tail -n 5000` window on a long run, so it is written once per tick instead, keeping one always
# inside that window), regardless of which path that tick takes after `Pick`. The old fixture
# synthesized PICK/IMPL/FAST_GATE events this path never emits; this one matches what actually
# lands in status.jsonl.
F="$SB/m.jsonl"
{ decl 100
  ev 900 DONE end 0 0 "" "rc=60" "" 100 0
} > "$F"
out="$(render_banner "$F" 0 1000)"
check "M header"  "US- · iter 0 · pass 0 · budget 0"        "$(line "$out" 1)"
check "M chips 1" "· pick  · impl  · fast"                  "$(line "$out" 2)"
check "M status"  "DONE rc=60 (parked, waiting on a human)" "$(line "$out" 4)"

echo "== Fixture N: budget-exhaustion park -> the real PICK/IMPL/FAST_GATE/PARK/DONE sequence, PARK renders like any unmapped phase =="
# Unlike Fixture M, a tick that PARKS after exhausting the repair budget runs the ordinary
# PICK/IMPL/FAST_GATE phases first (see the parked-on-exhaustion golden in LogParitySpec) and, since
# issue #28's review, emits one more event when the issue is actually parked: PARK "ok". PARK is not
# one of the seven phases the two fixed chip rows enumerate (PICK/IMPL/FAST_GATE and
# REVIEW/PR/CI_WAIT/MERGE), so it never grows an eighth chip; it just has to not break the banner,
# which this fixture is the regression test for.
F="$SB/n.jsonl"
{ decl
  ev 100 PICK      ok    0 2
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log
  ev 500 FAST_GATE red   3 0 harness/logs/issue-5-pass3.gate.log
  ev 890 PARK       ok    3 0 "" "issue=5"
  ev 900 DONE      end   3 0 "" "rc=60"
} > "$F"
out="$(render_banner "$F" 0 1000)"
check "N status"  "DONE rc=60 (parked, waiting on a human)" "$(line "$out" 4)"
check "N chips 1" "✓ pick  ✓ impl  ✗ fast"                  "$(line "$out" 2)"

echo "== Fixture O: loop dies right after posting PARK, before the terminal DONE -> STALE names the PARK phase, chip_name's catch-all renders it acceptably (issue #28 review nit) =="
F="$SB/o.jsonl"
{ decl
  ev 100 PICK      ok    0 2
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log
  ev 500 FAST_GATE red   3 0 harness/logs/issue-5-pass3.gate.log
  ev 890 PARK       ok    3 0 "" "issue=5"
} > "$F"
out="$(render_banner "$F" 0 1000)"
check "O status names the unmapped PARK phase, not blank or a crash" "STALE (loop died in PARK)" "$(line "$out" 4)"

echo "== Fixture P: no declaration line at all -> four line degrade, empty chip rows, header/status still render (issue #40) =="
# A status.jsonl written before issue #40 (or by a graph that never calls StatusLog.declare) has
# ordinary phase events but no "kind":"stages" line anywhere. The renderer has nothing of its own
# left to fall back to, on purpose: it still prints exactly four lines, with both chip rows
# rendering empty, while the header and status lines (which read fields off the events
# themselves, never off a declaration) keep working exactly as they always did.
F="$SB/p.jsonl"
{ ev 100 PICK ok    0 2
  ev 101 IMPL start 0 2 harness/logs/issue-5-iter1.claude.log
} > "$F"
out="$(render_banner "$F" 1 1000)"
check "P line count is 4"        "4"                                    "$(echo "$out" | wc -l | tr -d ' ')"
check "P header still renders"   "US-5 · iter 1 · pass 0 · budget 2"    "$(line "$out" 1)"
check "P chip row 1 is empty"    ""                                     "$(line "$out" 2)"
check "P chip row 2 is empty"    ""                                     "$(line "$out" 3)"
check "P status still renders"   "RUNNING (pid 4711)"                   "$(line "$out" 4)"

echo "== Fixture Q: a declared stage set with a custom node renders it, its own terminal firing with its own prefix (issue #40) =="
# The acceptance bar for issue #40: a graph that names phases banner.sh has never heard of (here
# BUILD/TEST/DEPLOY, not one of the shipped PICK/IMPL/.../MERGE names) still renders correctly,
# proving the chip label, row placement and elapsed time behaviour all come off the declaration,
# never off a literal in this script. The declared terminal, SHIP, actually fires here (issue #40
# review MAJOR 4): an earlier version of this fixture left SHIP undeclared-but-never-firing, which
# meant the status line always fell through to the ordinary RUNNING/STALE branch and never
# exercised the terminal line at all, dodging the one case where a hardcoded "DONE " prefix would
# have shown through for a graph that declares a DIFFERENT terminal phase name.
F="$SB/q.jsonl"
{ printf '{"ts":0,"pid":4711,"run":"100","kind":"stages","anchor":"BUILD","terminal":"SHIP","stages":[%s]}\n' \
    '{"phase":"BUILD","chip":"build","row":1,"badge":false},{"phase":"TEST","chip":"test","row":1,"badge":false},{"phase":"RETRY","chip":"retry","row":1,"badge":true},{"phase":"DEPLOY","chip":"deploy","row":2,"badge":false}'
  ev 100 BUILD  ok    0 0 "" "" 42
  ev 101 TEST   start 0 0 "" "" 42
  ev 150 TEST   red   0 0 "" "" 42
  ev 151 RETRY  start 0 0 "" "" 42
  ev 160 RETRY  ok    0 0 "" "" 42
  ev 161 TEST   start 0 0 "" "" 42
  ev 900 SHIP   end   0 0 "" "rc=0" 42
} > "$F"
out="$(render_banner "$F" 1 1000)"
check "Q line count is 4"     "4"                                          "$(echo "$out" | wc -l | tr -d ' ')"
check "Q header"              "US-42 · iter 1 · pass 0 · budget 0"         "$(line "$out" 1)"
check "Q chips 1: custom chip labels, a custom badge, elapsed on the running one" \
  "✓ build  ▶ test 13m59s  ↺ retry 1"                                     "$(line "$out" 2)"
check "Q chips 2: a custom row 2 chip" \
  "· deploy"                                                               "$(line "$out" 3)"
check "Q status: the declared terminal SHIP fires, prefixing the line with its own name, never the shipped literal DONE" \
  "SHIP rc=0"                                                              "$(line "$out" 4)"

echo "== Fixture R: loop dies mid repair -> STALE names the raw badge phase, never its lowercase chip label (issue #40 review MAJOR 2) =="
# `chip_name` used to resolve ANY declared phase, badge stages included, so a loop killed while a
# FIX round was in flight rendered "STALE (loop died in fix)" (the badge's own lowercase chip
# label) where HEAD, before issue #40, always rendered the raw phase string "STALE (loop died in
# FIX)". FIX is declared as a badge (row 1, badge:true) in the shipped stage set, never a chip, so
# it must fall through chip_name's lookup exactly like an undeclared phase does (Fixture O's PARK).
F="$SB/r.jsonl"
{ decl
  ev 100 PICK      ok    0 2
  ev 101 IMPL      ok    0 2 harness/logs/issue-5-iter1.claude.log
  ev 200 FAST_GATE red   1 2 harness/logs/issue-5-pass1.gate.log
  ev 201 FIX       start 1 1 harness/logs/issue-5-pass1.fix.claude.log
} > "$F"
out="$(render_banner "$F" 0 1000)"
check "R status names the raw badge phase FIX, not its lowercase chip label fix" \
  "STALE (loop died in FIX)" "$(line "$out" 4)"
check "R line count is 4" "4" "$(echo "$out" | wc -l | tr -d ' ')"

echo "== Fixture S: declaration whose stages field is valid JSON but not an array -> degrades to no declaration, never a crash (issue #40 review MINOR 5) =="
# A declaration line can be well formed JSON while still carrying the wrong SHAPE for "stages" (a
# string here, standing in for any hand-edited or corrupted non-array value): iterating a non-array
# with jq's `[]` aborts the whole jq program (a nonzero exit), which would leave render_banner short
# of its own four-line contract and watch.sh dead under set -euo pipefail. The type guard on
# $stageList degrades this to the same empty-chip-row rendering a genuinely absent declaration
# already gets (Fixture P), rather than a crash.
F="$SB/s.jsonl"
{ printf '{"ts":0,"pid":4711,"run":"100","kind":"stages","anchor":"PICK","terminal":"DONE","stages":"not-an-array"}\n'
  ev 100 PICK  ok    0 2
  ev 101 IMPL  start 0 2 harness/logs/issue-5-iter1.claude.log
} > "$F"
set +e
out="$(render_banner "$F" 1 1000)"
rc=$?
set -e
check "S exits 0 despite the malformed stages field" "0" "$rc"
check "S line count is 4" "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "S header still renders" "US-5 · iter 1 · pass 0 · budget 2" "$(line "$out" 1)"
check "S chip row 1 degrades to empty, not a crash" "" "$(line "$out" 2)"
check "S chip row 2 degrades to empty, not a crash" "" "$(line "$out" 3)"
check "S status still renders" "RUNNING (pid 4711)" "$(line "$out" 4)"

echo "== Fixture T: stages entries that are bare strings, not objects -> degrades to no declaration, never a crash (issue #40 review round 2, MAJOR 1) =="
# stages is an array here, so Fixture S's type guard lets it through; each ELEMENT is a bare
# phase string rather than an object, so .row/.chip below index a string, which jq aborts on
# ("Cannot index string with string \"row\""). The map(select(...)) element guard drops any
# entry that is not an object with a string chip and a numeric row.
F="$SB/t.jsonl"
{ printf '{"ts":0,"pid":4711,"run":"100","kind":"stages","anchor":"PICK","terminal":"DONE","stages":["PICK"]}\n'
  ev 100 PICK  ok    0 2
  ev 101 IMPL  start 0 2 harness/logs/issue-5-iter1.claude.log
} > "$F"
set +e
out="$(render_banner "$F" 1 1000)"
rc=$?
set -e
check "T exits 0 despite a bare-string stages element" "0" "$rc"
check "T line count is 4" "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "T chip row 1 degrades to empty, not a crash" "" "$(line "$out" 2)"

echo "== Fixture U: a stage entry whose chip is a number, not a string -> degrades to no declaration, never a crash (issue #40 review round 2, MAJOR 1) =="
# The entry is an object with a numeric row, so it survives a type == "object" check alone; its
# chip is a number, which chip()'s own string concatenation aborts on
# ('string ("✓ ") and number (7) cannot be added'). The element guard requires chip to be a string.
F="$SB/u.jsonl"
{ printf '{"ts":0,"pid":4711,"run":"100","kind":"stages","anchor":"PICK","terminal":"DONE","stages":[{"phase":"PICK","chip":7,"row":1}]}\n'
  ev 100 PICK  ok    0 2
  ev 101 IMPL  start 0 2 harness/logs/issue-5-iter1.claude.log
} > "$F"
set +e
out="$(render_banner "$F" 1 1000)"
rc=$?
set -e
check "U exits 0 despite a numeric chip" "0" "$rc"
check "U line count is 4" "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "U chip row 1 degrades to empty, not a crash" "" "$(line "$out" 2)"

echo "== Fixture V: a terminal that is a number, not a string -> degrades to no terminal, never a crash (issue #40 review round 2, MAJOR 1) =="
# \$st[\$terminal] indexes the phase map with the declared terminal; a numeric terminal makes that
# an object indexed by a number, which jq aborts on ("Cannot index object with number"). Reducing
# any non-string terminal to null folds it into the same fallback an absent terminal already gets.
F="$SB/v.jsonl"
{ printf '{"ts":0,"pid":4711,"run":"100","kind":"stages","anchor":"PICK","terminal":7,"stages":[{"phase":"PICK","chip":"pick","row":1}]}\n'
  ev 100 PICK  ok    0 2
  ev 101 IMPL  start 0 2 harness/logs/issue-5-iter1.claude.log
} > "$F"
set +e
out="$(render_banner "$F" 1 1000)"
rc=$?
set -e
check "V exits 0 despite a numeric terminal" "0" "$rc"
check "V line count is 4" "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "V status falls back to RUNNING, no terminal to match against" "RUNNING (pid 4711)" "$(line "$out" 4)"

echo "== Fixture W: stage entries with a missing or numeric phase -> degrade to no chip, never a crash (issue #40 review round 3, BLOCKER) =="
# chip() indexes \$st[\$s.phase]; \$st is an object, so a missing phase (jq's shorthand object
# construction leaves it absent, not null) or a numeric phase indexes that object with null or a
# number, which jq aborts on ("Cannot index object with null" / "Cannot index object with
# number"). The element guard requires phase to be a string, same as chip and row.
F="$SB/w.jsonl"
{ printf '{"ts":0,"pid":4711,"run":"100","kind":"stages","anchor":"PICK","terminal":"DONE","stages":[{"phase":"PICK","chip":"pick","row":1},{"chip":"impl","row":1},{"phase":7,"chip":"fast","row":1}]}\n'
  ev 100 PICK  ok    0 2
  ev 101 IMPL  start 0 2 harness/logs/issue-5-iter1.claude.log
} > "$F"
set +e
out="$(render_banner "$F" 1 1000)"
rc=$?
set -e
check "W exits 0 despite a missing and a numeric phase" "0" "$rc"
check "W line count is 4" "4" "$(echo "$out" | wc -l | tr -d ' ')"
check "W chip row 1 keeps the one valid entry, drops the two malformed ones" "✓ pick" "$(line "$out" 2)"

echo
echo "==== $pass passed, $fail failed ===="
[[ "$fail" -eq 0 ]]
