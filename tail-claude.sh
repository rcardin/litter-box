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

# -n +1 so we render the whole log so far, then follow. `?` guards keep a malformed or
# unexpected event from aborting the stream. Truncate long strings so one event = one line.
tail -n +1 -f "$LOG" | jq -r --unbuffered '
  def clip($n): if (.|length) > $n then .[:$n] + "…" else . end;
  def flat: (gsub("\\s+";" ") // .);
  if .type == "system" then
    "── system/\(.subtype // "?")"
  elif .type == "assistant" then
    ( .message.content[]? |
      if .type == "text"     then "🗣  " + (.text     | flat | clip(200))
      elif .type == "tool_use" then "🔧 \(.name) " + ((.input | tostring) | flat | clip(160))
      else empty end )
  elif .type == "user" then
    ( .message.content[]? |
      if .type == "tool_result" then
        "   ↳ " + (((.content | if type=="array" then (map(.text? // "") | join(" ")) else tostring end)) | flat | clip(160))
      else empty end )
  elif .type == "result" then
    "── result/\(.subtype // "?")  turns=\(.num_turns // "?")  \(.duration_ms // "?")ms"
  else empty end
' || true
