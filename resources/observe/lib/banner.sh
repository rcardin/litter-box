#!/usr/bin/env bash
#
# Pure banner renderer for logs/status.jsonl.
#
#   render_banner <status_file> <alive:0|1> [now_epoch]  ->  exactly 4 lines on stdout
#
# No ANSI, no terminal queries, no side effects. Everything the banner decides (which run
# is current, which phase is running, staleness, badge counts, the terminal outcome) is
# decided here, which is why this is the feature's only test seam. watch.sh does terminal
# management and nothing else.
#
# Malformed lines are dropped (fromjson? // empty), so a torn append degrades to a dropped
# frame rather than a crash. Lines that parse as valid JSON but are neither an event object
# nor a stage declaration (a bare scalar, or a shapeless {} missing the fields either shape
# needs) are dropped too, by the shape guard in the same filter stage.
#
# Stage identity (issue #40): this file used to hardcode which phase strings exist, what
# each one's chip label is, which of the two chip rows it belongs to, and which phase starts
# an iteration or ends a run. None of that lives here any more. The run itself declares it,
# once per TICK (issue #40 review, MAJOR 1), as a "kind":"stages" line in status.jsonl
# (`LiveStatusLog.declare`), and every piece of rendering below reads that declaration instead
# of a literal. A graph that never writes one still renders four lines, with empty chip rows:
# this file has nothing of its own left to fall back to, on purpose, so a wrong or missing
# declaration is the loop's bug to fix, never a stale copy this script would otherwise be
# trusted to agree with.

# jq program. $alive and $now are injected; the input is the slurped array of valid lines,
# a mix of event objects (has phase/state) and one declaration per TICK (kind ==
# "stages") that render_banner's first jq pass already let through.
# shellcheck disable=SC2016
_BANNER_JQ='
def sym($s):
  if   $s=="ok"    then "✓"
  elif $s=="red"   then "✗"
  elif $s=="skip"  then "–"
  elif $s=="start" then "▶"
  else "·" end;

def elapsed($secs):
  (if $secs < 0 then 0 else $secs end) as $t
  | if $t < 60 then "\($t)s"
    else "\(($t / 60) | floor)m\($t % 60)s" end;

([ .[] | select(has("phase") and has("state")) ]) as $allEvents
| ([ .[] | select(.kind == "stages") ])            as $allDecls
|
if ($allEvents | length) == 0 then
  "no run yet", "", "", "(waiting for the first phase event)"
else
  $allEvents[-1]                                          as $last
  | [ $allEvents[] | select(.run == $last.run) ]           as $ev
  # The newest declaration on file for the CURRENT run. A run declares once per TICK, not once
  # per run (`Machine.runOnce` writes one ahead of every tick'\''s own first status event, issue
  # #40 review MAJOR 1), precisely so a declaration is always somewhere inside the `tail -n 5000`
  # window below, no matter how long the run has been going; on a healthy long run this array can
  # hold many declarations for the same $last.run, all of them identical. The trailing `| last` is
  # therefore LOAD BEARING, not defensive: it is what picks the newest one out of that pile
  # instead of an arbitrary one, and it is also what keeps a torn or doubled write from breaking
  # anything, the same defensive stance gets applied everywhere else in this script. Do not delete it as
  # dead code because a run "only declares once". No declaration at all for this run
  # (an older status.jsonl from before issue #40, or a graph that never calls declare)
  # leaves $decl null and every field below falls back to its empty, whole run default.
  | ([ $allDecls[] | select(.run == $last.run) ] | last)   as $decl
  # `// []` alone only substitutes on `null`/`false`: a declaration whose own `stages` field is
  # present but not an array (a hand-edited or corrupted line, still valid JSON) would otherwise
  # pass a non-array straight through to every `$stageList[]` iteration below, which jq aborts on
  # (a nonzero exit), leaving `render_banner` short of its own four-line contract and `watch.sh`
  # dead under `set -euo pipefail`. The explicit `type == "array"` check is what actually guards
  # that, degrading a malformed `stages` field to the same empty list a genuinely absent
  # declaration already falls back to, never a crash. The `map(select(...))` after it guards the
  # element shape the same way: an array element that is not an object at all (`["PICK"]`,
  # standing in for a stage list of bare phase strings) makes `.row`/`.chip` below index a string
  # instead of an object, and an object whose `phase`, `chip` or `row` is present but the wrong
  # type (a missing or numeric `phase`, e.g.) makes `$st[$s.phase]` in `chip()` index an object
  # with `null` or a number, or the string concatenation in `chip()`/`badgeSuffix()` abort, the
  # same way. Dropping any element that is not an object with a string `phase`, a string `chip`
  # and a numeric `row` degrades one malformed stage to an empty stage list entry, never a crash
  # (issue #40 review round 2, MAJOR 1; phase guard added review round 3, BLOCKER). `$anchor` and
  # `$terminal` get the same treatment for the same reason: a
  # non-string `terminal` (`7`, say) reaches `$st[$terminal]` below, indexing an object with a
  # number, which jq also aborts on; reducing anything but a string to `null` folds a malformed
  # `anchor`/`terminal` into the same "absent" fallback the rest of this program already handles.
  | (($decl.stages // [])
     | if type == "array" then . else [] end
     | map(select(type == "object" and (.phase | type) == "string" and (.chip | type) == "string" and (.row | type) == "number")))
      as $stageList
  | ($decl.anchor   // null | if type == "string" then . else null end) as $anchor
  | ($decl.terminal // null | if type == "string" then . else null end) as $terminal

  # A run is stamped once per loop PROCESS, so with MAX_ITERS>1 the same run carries every
  # iteration end to end: the terminal event of iteration N is immediately followed by the
  # anchor event of iteration N+1. The writer emits the terminal event meaning "this
  # iteration ended"; the reader must read that same event as "the run is over", so the phase
  # map is scoped to events at or after the LAST occurrence of the declared anchor phase, the
  # start of the iteration currently under way. That scoping fixes two things at once: a
  # terminal event from a finished earlier iteration can no longer be mistaken for the run
  # being over, and chips from that finished iteration can no longer paint the row of the
  # current one. No declared anchor, or an anchor phase that never appears, falls back to the
  # whole run.
  | (if $anchor != null
     then ([ range(0; ($ev|length)) | select($ev[.].phase == $anchor) ] | last)
     else null end)                                        as $anchorIdx
  | (if $anchorIdx != null then $ev[$anchorIdx:] else $ev end) as $cev
  | (reduce $cev[] as $e ({}; .[$e.phase] = $e))            as $st
  | (if $terminal != null then $st[$terminal] else null end) as $done
  | ([ $cev[] | select(.state == "start") ] | last)        as $lastStart
  | (if $lastStart != null and $st[$lastStart.phase].state == "start"
     then $lastStart else null end)                        as $cur

  # The declared CHIP label for a raw phase string, falling back to the phase itself. Used only
  # by the STALE line below, whose phase comes off a live event and so may legitimately name
  # something the declaration never mapped (an unmapped phase such as PARK, or a run with no
  # declaration at all), or something the declaration DID map, but only as a badge, never a
  # chip. Badge stages (`.badge == true`, e.g. FIX) are deliberately excluded from this lookup:
  # a badge has no chip label of its own on the banner (`badgeSuffix` below renders it as a
  # counted suffix, `chip` is never called for it), so resolving one here would print a label
  # the banner itself never shows anywhere else, and would silently diverge from the HEAD
  # rendering STALE has always used for a badge phase, the raw phase string verbatim (e.g.
  # "STALE (loop died in FIX)", never "STALE (loop died in fix)").
  | def chip_name($p): (first($stageList[] | select(.phase == $p and ((.badge // false) | not)) | .chip)) // $p;

  # One declared chip stage rendered as a chip: symbol for its latest known state, its own
  # declared label, plus elapsed time when it is the one currently running.
  def chip($s):
      ($st[$s.phase].state // "none") as $ss
      | sym($ss) + " " + $s.chip
        + (if $cur != null and $cur.phase == $s.phase
           then " " + elapsed($now - $cur.ts)
           else "" end);

  # One declared badge stage rendered as a counted suffix ("  ↺ fix 2"), never as a chip.
  # Counted from how many times this phase started within the current iteration, empty when
  # it never started at all so a badge that never fired adds nothing to the row.
  def badgeSuffix($s):
      ([ $cev[] | select(.phase == $s.phase and .state == "start") ] | length) as $n
      | if $n > 0 then "  ↺ " + $s.chip + " " + ($n|tostring) else "" end;

  # One banner chip row: every declared chip stage for that row, in declared order, followed
  # by every declared badge stage for that row, in declared order, as suffixes. `watch.sh`
  # only ever prints two chip rows (BANNER_ROWS=4 total), so only rows 1 and 2 are ever asked
  # for; a stage declared on any other row simply never renders, degrading quietly rather
  # than crashing.
  def row($r):
      ( ([ $stageList[] | select(.row == $r and ((.badge // false) | not)) ] | map(chip(.)) | join("  "))
        + ([ $stageList[] | select(.row == $r and (.badge // false)) ] | map(badgeSuffix(.)) | join("")) );

    ( "US-\($last.issue) · iter \($last.iter) · pass \($last.pass) · budget \($last.budget)" ),

    row(1),

    row(2),

    ( if $done != null then
        # `$done.phase`, not the literal "DONE" (issue #40 review MAJOR 4): `$done` is the
        # declared TERMINAL phase'\''s own latest event, and its `phase` field is that phase'\''s own raw
        # name, whatever the declaration says it is. A graph declaring `terminal: "SHIP"` prints
        # "SHIP ...", exactly the same way the shipped graph'\''s own `terminal: "DONE"` still prints
        # "DONE ..." (`$done.phase` is literally "DONE" there), so the shipped rendering stays
        # byte identical while a consumer'\''s own terminal phase is no longer hardcoded past.
        (($done.phase) + " " + (($done.detail // "") | gsub("[\r\n]+"; " "))) as $doneLine
        # issue #28: rc=60 is the one detail worth a plain-English gloss; every other rc renders
        # byte-identically to before, since sandbox/test/watch-test.sh pins them.
        | if $done.detail == "rc=60" then $doneLine + " (parked, waiting on a human)" else $doneLine end
      elif $alive == 0 then
        "STALE (loop died in " + chip_name((($cur // $last).phase)) + ")"
      else
        "RUNNING (pid \($last.pid))"
      end )
end
'

# render_banner STATUS_FILE ALIVE [NOW]
render_banner() {
  local file="$1" alive="${2:-1}" now="${3:-}"
  [[ -n "$now" ]] || now="$(date +%s)"

  if [[ ! -f "$file" ]]; then
    printf 'no run yet\n\n\n(waiting for %s)\n' "$file"
    return 0
  fi

  # tail bounds the read: logs/status.jsonl is append-only and never truncated, so
  # an unbounded read grows without limit across months of runs. A `run` is stamped once per
  # loop PROCESS, so it spans a whole loop process across all its iterations and issues, not
  # a single iteration; 5000 lines is roughly 500 gate passes (about 900 KB) and still cheap
  # to re-read once per second, so it comfortably covers one long-lived run.
  # The first jq drops malformed lines and anything that is neither shape the renderer reads:
  # an event object needs "run", "phase" and "state"; a declaration needs "run" and
  # "kind":"stages". The second slurps the survivors and renders.
  tail -n 5000 "$file" \
    | jq -R 'fromjson? // empty
        | select(type == "object" and has("run"))
        | select((has("phase") and has("state")) or .kind == "stages")' \
    | jq -s -r --argjson alive "$alive" --argjson now "$now" "$_BANNER_JQ"
}
