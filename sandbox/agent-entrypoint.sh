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

# The agent. The dedicated ANTHROPIC_API_KEY and the proxy env come from the container env
# (run-agent.sh) — no host keychain, no host claude config, no gh token. stdout is the stream-
# json log the harness tails, so it must NOT be redirected. A claude failure is not fatal here:
# it leaves an empty patch, which the host reads as "no diff" (EMPTY), exactly like a host claude
# that produced nothing.
claude -p "$(cat "$INPUT/prompt.txt")" \
  --dangerously-skip-permissions --output-format stream-json --verbose || true

# Extract the agent's work as the cumulative patch. --no-renames keeps the host protected-path
# guard rename-proof: a rename records as delete+add, so the concrete destination path appears
# literally and harness/* etc. cannot be slipped past by renaming into a protected dir.
git add -A >/dev/null 2>&1 || { echo "[agent-entrypoint] staging the agent's work failed" >&2; exit 3; }
git diff --cached --no-renames HEAD > "$OUTPUT/agent.patch" 2>/dev/null \
  || { echo "[agent-entrypoint] writing the agent patch to /output failed" >&2; exit 3; }
