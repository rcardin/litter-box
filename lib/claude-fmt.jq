# Collapse one `claude -p --output-format stream-json` event into one readable line.
#   🗣  assistant prose      🔧 tool call + truncated input      ↳ tool result (truncated)
#   ── system/result markers
# `?` guards keep a malformed or unexpected event from aborting the stream.
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
