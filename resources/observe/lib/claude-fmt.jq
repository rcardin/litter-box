# Collapse one `claude -p --output-format stream-json` event into one readable line.
#   🗣  assistant prose      🔧 tool call + truncated input      ↳ tool result (truncated)
#   ── system/result markers
# `?` guards keep a malformed or unexpected event from aborting the stream.
#
# INPUT IS RAW LINES (`jq -R`), not parsed JSON: a *.claude.log is NOT pure JSONL. The agent
# dispatch redirects the runner's stdout+stderr into one file (`cmd >"$logf" 2>&1`), so plain
# diagnostics — e.g. the sandbox's `[sandbox] ANTHROPIC_API_KEY holds an OAuth token …` — are
# interleaved with the events. Under `jq -f` those aborted the whole stream on a parse error and
# the watch pane stayed empty for the entire iteration; `fromjson?` drops them line by line.
def clip($n): if (.|length) > $n then .[:$n] + "…" else . end;
def flat: (gsub("\\s+";" ") // .);
def render:
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
else empty end;

(fromjson? // empty) | render
