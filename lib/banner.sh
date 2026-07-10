#!/usr/bin/env bash
#
# Pure banner renderer for harness/logs/status.jsonl.
#
#   render_banner <status_file> <alive:0|1> [now_epoch]  ->  exactly 4 lines on stdout
#
# No ANSI, no terminal queries, no side effects. Everything the banner decides (which run
# is current, which phase is running, staleness, the fix badge, the terminal outcome) is
# decided here, which is why this is the feature's only test seam. watch.sh does terminal
# management and nothing else.
#
# Malformed lines are dropped (fromjson? // empty), so a torn append degrades to a dropped
# frame rather than a crash. Lines that parse as valid JSON but are not event objects (a
# bare scalar, or a shapeless {} missing the fields the renderer indexes) are dropped too,
# by the shape guard in the same filter stage.

# jq program. $alive and $now are injected; the input is the slurped array of valid events.
# The phase-label helper is `chip_name`, NOT `label`: `label` is a reserved jq keyword
# (`label $out | ...`) and defining it is a compile error.
# shellcheck disable=SC2016
_BANNER_JQ='
def chip_name($p):
  if   $p=="PICK"      then "pick"
  elif $p=="IMPL"      then "impl"
  elif $p=="FAST_GATE" then "fast"
  elif $p=="IT_GATE"   then "IT"
  elif $p=="REVIEW"    then "rev"
  elif $p=="PR"        then "pr"
  elif $p=="CI_WAIT"   then "ci"
  elif $p=="MERGE"     then "merge"
  else $p end;

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

if length == 0 then
  "no run yet", "", "", "(waiting for the first phase event)"
else
  .[-1]                                                  as $last
  | [ .[] | select(.run == $last.run) ]                  as $ev
  # A run is stamped once per loop PROCESS, so with MAX_ITERS>1 the same run carries every
  # iteration end to end: iteration N DONE is immediately followed by iteration N+1 PICK.
  # The writer DONE means "this iteration ended"; the reader DONE must mean "the run is
  # over", so the phase map is scoped to events at or after the LAST PICK, the start of the
  # in-flight iteration. That scoping fixes two things at once: a DONE from a finished
  # earlier iteration can no longer be mistaken for the run being over, and chips from that
  # finished iteration (its FAST_GATE/IT_GATE ticks etc) can no longer paint the in-flight
  # iteration row. If a run somehow has no PICK, fall back to using the whole run.
  | ([ range(0; ($ev|length)) | select($ev[.].phase == "PICK") ] | last) as $pickIdx
  | (if $pickIdx != null then $ev[$pickIdx:] else $ev end) as $cev
  | (reduce $cev[] as $e ({}; .[$e.phase] = $e))          as $st
  | ([ $cev[] | select(.phase == "FIX" and .state == "start") ] | length) as $fixes
  | ($st["DONE"])                                        as $done
  | ([ $cev[] | select(.state == "start") ] | last)      as $lastStart
  | (if $lastStart != null and $st[$lastStart.phase].state == "start"
     then $lastStart else null end)                      as $cur

  | def chip($p):
      ($st[$p].state // "none") as $s
      | sym($s) + " " + chip_name($p)
        + (if $cur != null and $cur.phase == $p
           then " " + elapsed($now - $cur.ts)
           else "" end);

    ( "US-\($last.issue) · iter \($last.iter) · pass \($last.pass) · budget \($last.budget)" ),

    ( ([ "PICK", "IMPL", "FAST_GATE", "IT_GATE" ] | map(chip(.)) | join("  "))
      + (if $fixes > 0 then "  ↺ fix \($fixes)" else "" end) ),

    ( [ "REVIEW", "PR", "CI_WAIT", "MERGE" ] | map(chip(.)) | join("  ") ),

    ( if $done != null then
        "DONE " + (($done.detail // "") | gsub("[\r\n]+"; " "))
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

  # tail bounds the read: harness/logs/status.jsonl is append-only and never truncated, so
  # an unbounded read grows without limit across months of runs. A `run` is stamped once per
  # loop PROCESS, so it spans a whole loop process across all its iterations and issues, not
  # a single iteration; 5000 lines is roughly 500 gate passes (about 900 KB) and still cheap
  # to re-read once per second, so it comfortably covers one long-lived run.
  # The first jq drops malformed lines and non-event JSON (valid syntax but the wrong shape:
  # not an object, or missing a field the renderer indexes); the second slurps the survivors
  # and renders.
  tail -n 5000 "$file" \
    | jq -R 'fromjson? // empty | select(type == "object" and has("run") and has("phase") and has("state"))' \
    | jq -s -r --argjson alive "$alive" --argjson now "$now" "$_BANNER_JQ"
}
