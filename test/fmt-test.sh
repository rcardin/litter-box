#!/usr/bin/env bash
# Characterises harness/lib/claude-fmt.jq against a fixed stream-json fixture.
set -euo pipefail

SRC_HARNESS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILTER="$SRC_HARNESS/lib/claude-fmt.jq"

pass=0; fail=0
check() { # check DESC EXPECTED ACTUAL
  if [[ "$2" == "$3" ]]; then printf '  ok   %s\n' "$1"; pass=$((pass+1));
  else printf '  FAIL %s (want=%q got=%q)\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}

SB="$(mktemp -d)"
trap 'rm -rf "$SB"' EXIT

cat > "$SB/fixture.jsonl" <<'EOF'
{"type":"system","subtype":"init"}
{"type":"assistant","message":{"content":[{"type":"text","text":"Reading   the  spec"}]}}
{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/a/b.scala"}}]}}
{"type":"user","message":{"content":[{"type":"tool_result","content":[{"text":"object Base"}]}]}}
{"type":"result","subtype":"success","num_turns":7,"duration_ms":1234}
EOF

# A real *.claude.log is the runner's stdout+stderr combined, so plain diagnostics sit between
# the events. Every one of them must be skipped without killing the stream.
cat > "$SB/noisy.jsonl" <<'EOF'
[sandbox] ANTHROPIC_API_KEY holds an OAuth token (sk-ant-oat...) — passing it as CLAUDE_CODE_OAUTH_TOKEN
{"type":"system","subtype":"init"}
{"type":"assistant","message":{"content":[{"type":"text","text":"Reading   the  spec"}]}}
{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/a/b.scala"}}]}}
{"type":"user","message":{"content":[{"type":"tool_result","content":[{"text":"object Base"}]}]}}
{"type":"result","subtype":"success","num_turns":7,"duration_ms":1234}
EOF

echo "== claude-fmt.jq renders one readable line per event =="
out="$(jq -R -r -f "$FILTER" < "$SB/fixture.jsonl")"
check "system marker"    "── system/init"                  "$(echo "$out" | sed -n 1p)"
check "assistant prose"  "🗣  Reading the spec"             "$(echo "$out" | sed -n 2p)"
check "tool call"        '🔧 Read {"file_path":"/a/b.scala"}' "$(echo "$out" | sed -n 3p)"
check "tool result"      "   ↳ object Base"                 "$(echo "$out" | sed -n 4p)"
check "result marker"    "── result/success  turns=7  1234ms" "$(echo "$out" | sed -n 5p)"

echo
echo "== non-JSON lines are skipped, not fatal =="
noisy="$(jq -R -r -f "$FILTER" < "$SB/noisy.jsonl")"
check "noisy == clean"   "$out"                              "$noisy"

echo
echo "==== $pass passed, $fail failed ===="
[[ "$fail" -eq 0 ]]
