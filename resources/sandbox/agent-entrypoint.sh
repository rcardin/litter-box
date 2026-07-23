#!/usr/bin/env bash
# Runs INSIDE the sandbox container (v6 slice 3, issue #36). This is the ONLY agent-authored
# code path left, and it never touches the host: /workspace is a throwaway extraction of the
# host's origin/main tree (git archive, no host .git ever enters the container), and the agent's
# whole product leaves as a single patch on /output.
#
# Steps: turn /workspace into a git repo whose HEAD == origin/main, overlay any prior cumulative
# work, run claude agentically with the dedicated API key, then write the cumulative-vs-
# origin/main patch to /output/agent.patch. Only claude's stream-json reaches stdout (docker
# logs -> the harness's dispatch log); every git command is silenced so it does not pollute the
# log the harness tails.
set -euo pipefail

# The three mount points, overridable ONLY so the git plumbing can be exercised on the host by
# harness/sandbox/test/agent-entrypoint-test.sh. In the container they are the real mounts.
WORKSPACE="${AGENT_WORKSPACE:-/workspace}"
INPUT="${AGENT_INPUT:-/input}"
OUTPUT="${AGENT_OUTPUT:-/output}"

cd "$WORKSPACE"
{
  git init -q -b main
  git config user.email agent@sandbox
  git config user.name  agent
  # WORKSPACE holds the pristine origin/main tree (host: git archive origin/main). Commit it so
  # HEAD == origin/main; the agent's cumulative diff below is measured against exactly this base,
  # the same base the host resets to before git-applying the returned patch.
  git add -A
  git commit -q -m base
} >/dev/null 2>&1 || { echo "[agent-entrypoint] base repo setup failed" >&2; exit 3; }

# Overlay prior cumulative work (empty on the initial IMPL dispatch; the previous pass's patch on
# a FIX) so the agent edits on top of prior work and its diff stays cumulative-vs-origin/main.
# Exit 3 is the entrypoint's single infra-fault code (run-agent.sh maps it to 124), covering
# base-repo setup, a prior patch that will not apply, and staging failures.
if [[ -s "$INPUT/prior.patch" ]]; then
  git apply --index "$INPUT/prior.patch" >/dev/null 2>&1 \
    || { echo "[agent-entrypoint] prior.patch did not apply" >&2; exit 3; }
fi

# The agent. The dedicated credential (CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY) and the
# proxy env come from the container env
# (run-agent.sh) — no host keychain, no host claude config, no gh token. stdout is the stream-
# json log the harness tails, so it must NOT be redirected — tee keeps it flowing while we also
# keep a copy to classify the outcome below. The copy lives OUTSIDE $WORKSPACE: anything written
# in the tree would land in the agent's patch.
stream_log="$(mktemp)"
claude_rc=0
claude -p "$(cat "$INPUT/prompt.txt")" \
  --dangerously-skip-permissions --output-format stream-json --verbose \
  | tee "$stream_log" || claude_rc=$?

# Two claude outcomes are INFRA faults, not agent outcomes, and must be told apart from the
# agent legitimately producing nothing (which is exit 0 with an empty patch):
#
#   1. the final stream-json result line carries a numeric api_error_status (401 invalid key,
#      429, 5xx) — the request never reached the model
#   2. claude exited nonzero without ever emitting a result line — it crashed, was killed, or
#      egress was blocked before the first response
#
# Both used to be swallowed by a bare `|| true` and surfaced to the host as "no changes produced
# by the iteration", which spent a pass and hid the real cause (an OAuth token exported as
# ANTHROPIC_API_KEY 401s in exactly this way). Exit 3 -> run-agent.sh maps it to 124 -> the host
# treats it as an infra fault and spends NO repair budget.
result_line="$(grep '"type":"result"' "$stream_log" | tail -n 1 || true)"
rm -f "$stream_log"
if [[ "$result_line" =~ \"api_error_status\":[0-9] ]]; then
  echo "[agent-entrypoint] claude never reached the model: $result_line" >&2
  exit 3
fi
if (( claude_rc != 0 )) && [[ -z "$result_line" ]]; then
  echo "[agent-entrypoint] claude exited rc=$claude_rc without producing a result — treating as an infra fault, not an empty iteration" >&2
  exit 3
fi

# Extract the agent's work as the cumulative patch. --no-renames keeps the host protected-path
# guard rename-proof: a rename records as delete+add, so the concrete destination path appears
# literally and harness/* etc. cannot be slipped past by renaming into a protected dir.
git add -A >/dev/null 2>&1 || { echo "[agent-entrypoint] staging the agent's work failed" >&2; exit 3; }
git diff --cached --no-renames HEAD > "$OUTPUT/agent.patch" 2>/dev/null \
  || { echo "[agent-entrypoint] writing the agent patch to /output failed" >&2; exit 3; }
