#!/usr/bin/env bash
#
# Live-follow the JSONL log a stream-json `claude -p` iteration writes (loop.sh dispatch).
# Passive observability only — reads nothing back into the loop, just renders for a human.
#
# Usage:  harness/tail-claude.sh [logfile]
#         (no arg → follows the newest harness/logs/*.claude.log)
#
# Each line of the log is one stream-json event; this collapses them to one readable line:
#   🗣  assistant prose      🔧 tool call + truncated input      ↳ tool result (truncated)
#   ── system/result markers
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LOG="${1:-$(ls -t "$SCRIPT_DIR"/logs/*.claude.log 2>/dev/null | head -1 || true)}"
[[ -n "$LOG" ]] || { echo "no *.claude.log under $SCRIPT_DIR/logs — has a run started?" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq not found" >&2; exit 1; }

echo "tailing $LOG  (Ctrl-C to stop)" >&2

FILTER="$SCRIPT_DIR/lib/claude-fmt.jq"
[[ -f "$FILTER" ]] || { echo "missing filter: $FILTER" >&2; exit 1; }

# -n +1 so we render the whole log so far, then follow.
tail -n +1 -f "$LOG" | jq -R -r --unbuffered -f "$FILTER" || true
