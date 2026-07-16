#!/usr/bin/env bash
# AC4: run-agent.sh maps every infra fault to exit 124 (the infra-fault code
# loop.sh's dispatch_worker turns into an rc-50 exit that spends NO repair budget). Deterministic
# and daemon-free: a missing API key fails before any docker call, and a bogus DOCKER_HOST makes
# the `docker info` probe fail. This proves the "kill the container / no orphan, exit infra-fault"
# contract's exit code without needing a real container (the timeout kill itself is a trap in
# run-agent.sh over the same detached-container + docker-wait pattern the FAST gate already ships).
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"

fail=0
prompt="$(mktemp)"; printf 'do nothing' > "$prompt"
patch_out="$(mktemp)"
trap 'rm -f "$prompt" "$patch_out"' EXIT

# 1. No credential at all (neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY) -> infra fault
# before any docker call, and the message names BOTH accepted credentials.
rc=0; msg=""
msg="$( ( unset ANTHROPIC_API_KEY CLAUDE_CODE_OAUTH_TOKEN; "$SCRIPT_DIR/run-agent.sh" "$prompt" "$patch_out" ) 2>&1 >/dev/null )" || rc=$?
if [[ "$rc" == "124" && "$msg" == *"CLAUDE_CODE_OAUTH_TOKEN"* && "$msg" == *"ANTHROPIC_API_KEY"* ]]; then
  echo "  ok   no credential -> rc 124 naming both CLAUDE_CODE_OAUTH_TOKEN and ANTHROPIC_API_KEY"
else
  echo "  FAIL expected rc 124 naming both credentials, got: rc=$rc msg=$msg"; fail=1
fi

# 2. API key present (no OAuth token) but Docker unreachable -> credential accepted, infra fault
# at the docker info probe.
rc=0
( unset CLAUDE_CODE_OAUTH_TOKEN
  ANTHROPIC_API_KEY=dummy DOCKER_HOST="tcp://127.0.0.1:1" \
    "$SCRIPT_DIR/run-agent.sh" "$prompt" "$patch_out" ) >/dev/null 2>&1 || rc=$?
if [[ "$rc" == "124" ]]; then
  echo "  ok   unreachable Docker at dispatch time -> rc 124 (infra fault, no repair budget)"
else
  echo "  FAIL expected rc 124 on unreachable Docker, got: $rc"; fail=1
fi

# 3. OAuth token ALONE (no ANTHROPIC_API_KEY) is a valid credential: the dispatch must get past
# the credential check and fault later, at the docker probe — never on the credential itself.
rc=0; msg=""
msg="$( ( unset ANTHROPIC_API_KEY
  CLAUDE_CODE_OAUTH_TOKEN=dummy DOCKER_HOST="tcp://127.0.0.1:1" \
    "$SCRIPT_DIR/run-agent.sh" "$prompt" "$patch_out" ) 2>&1 >/dev/null )" || rc=$?
if [[ "$rc" == "124" && "$msg" == *"docker unreachable"* ]]; then
  echo "  ok   CLAUDE_CODE_OAUTH_TOKEN alone accepted (faulted at docker probe, not on credential)"
else
  echo "  FAIL OAuth-only credential not accepted: rc=$rc msg=$msg"; fail=1
fi

exit "$fail"
