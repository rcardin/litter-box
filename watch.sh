#!/usr/bin/env bash
#
# Live view of a harness/loop.sh run: a pinned four-line phase banner over a log pane that
# follows whichever file the current phase is writing.
#
# Passive observability only: reads nothing back into the loop, never writes to the repo,
# never invokes gh. Attach, kill and reattach at any point in a run; the loop cannot tell.
#
# Usage:  harness/watch.sh [status.jsonl]
#         (no arg → harness/logs/status.jsonl)
#
# The PARENT is the only writer to the terminal. The child `tail -f` writes into a FIFO and
# the parent prints what it reads, so a banner redraw can never interleave with a log line
# mid-escape-sequence. `read -t 1` is both the pump and the banner tick (bash 3.2 has no
# fractional -t).
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"     # `logfile` fields are repo-relative

# shellcheck source=lib/banner.sh
. "$SCRIPT_DIR/lib/banner.sh"

STATUS="${1:-$SCRIPT_DIR/logs/status.jsonl}"
FILTER="$SCRIPT_DIR/lib/claude-fmt.jq"
command -v jq >/dev/null || { echo "jq not found" >&2; exit 1; }
[[ -f "$FILTER" ]] || { echo "missing filter: $FILTER" >&2; exit 1; }

BANNER_ROWS=4
TTY=0; [[ -t 1 ]] && TTY=1

FIFO="$(mktemp -u)/fifo"
mkdir -p "$(dirname "$FIFO")"
mkfifo "$FIFO"
TAIL_PID=""
CUR_FILE=""
LINES_N=24

stop_tail() {
  [[ -n "$TAIL_PID" ]] || return 0
  pkill -P "$TAIL_PID" 2>/dev/null || true    # the tail and the jq inside the subshell
  kill "$TAIL_PID" 2>/dev/null || true
  wait "$TAIL_PID" 2>/dev/null || true
  TAIL_PID=""
}

cleanup() {
  [[ -n "${CLEANED:-}" ]] && return 0   # idempotent: exit from the signal trap re-enters via EXIT
  CLEANED=1
  stop_tail
  rm -rf "$(dirname "$FIFO")"
  if (( TTY )); then
    printf '\033[r\033[?25h\033[%s;1H\n' "$LINES_N"   # reset scroll region, show cursor
  fi
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT TERM        # signal traps must exit explicitly; one Ctrl-C suffices

# Only *.claude.log is stream-json; gate, CI and reviewer logs are already plain text.
start_tail() { # start_tail FILE
  local f="$1"
  stop_tail
  case "$f" in
    *.claude.log) ( tail -n 40 -f "$f" 2>/dev/null | jq -r --unbuffered -f "$FILTER" >"$FIFO" ) & ;;
    *)            ( tail -n 40 -f "$f" 2>/dev/null >"$FIFO" ) & ;;
  esac
  TAIL_PID=$!
  CUR_FILE="$f"
}

init_screen() {
  (( TTY )) || return 0
  LINES_N="$(tput lines 2>/dev/null || echo 24)"
  # Clear, pin rows 1..BANNER_ROWS, scroll only rows BANNER_ROWS+1..LINES_N.
  printf '\033[2J\033[%s;%sr\033[%s;1H' "$((BANNER_ROWS + 1))" "$LINES_N" "$LINES_N"
}
trap 'init_screen; NEED_REDRAW=1' WINCH

# The last valid event's field, or "". A torn final line yields "".
last_field() { # last_field KEY
  tail -n 1 "$STATUS" 2>/dev/null | jq -R -r --arg k "$1" 'fromjson? // empty | .[$k] // empty' 2>/dev/null || true
}

draw_banner() { # draw_banner NOW
  local now="$1" alive=0 pid out i=1 l
  pid="$(last_field pid)"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && alive=1
  out="$(render_banner "$STATUS" "$alive" "$now")"
  if (( TTY )); then
    printf '\0337'                              # DECSC: save cursor + attrs
    while IFS= read -r l; do
      printf '\033[%s;1H\033[2K%s' "$i" "$l"    # absolute address, clear line, no newline
      i=$((i + 1))
    done <<< "$out"
    printf '\0338'                              # DECRC: restore
  else
    printf '%s\n' "$out"
  fi
}

# Follow the log the current phase points at; respawn the child when it changes.
poll_source() {
  local lf
  lf="$(last_field logfile)"
  [[ -n "$lf" ]] || return 0
  [[ "$lf" != "$CUR_FILE" ]] || return 0
  [[ -f "$lf" ]] || return 0
  start_tail "$lf"
}

[[ -f "$STATUS" ]] || { echo "no status file at $STATUS (has a run started?)" >&2; exit 1; }

init_screen
(( TTY )) && printf '\033[?25l'                 # hide cursor; cleanup restores it

# Open the FIFO read-write so the parent never sees EOF between children.
exec 3<>"$FIFO"

last_tick=0
NEED_REDRAW=0
while :; do
  now="$(date +%s)"
  if (( now != last_tick || NEED_REDRAW )); then
    draw_banner "$now"
    poll_source
    last_tick="$now"; NEED_REDRAW=0
  fi
  # `read -t 1` returns >128 on timeout (and on SIGWINCH). Either way we loop and redraw.
  if IFS= read -r -t 1 -u 3 line; then
    printf '%s\n' "$line"
  fi
done
