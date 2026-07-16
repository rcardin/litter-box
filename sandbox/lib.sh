#!/usr/bin/env bash
# Shared constants for the v6-slice-1 FAST-gate sandbox (issue #34). Sourced, never executed
# directly — no side effects, just variable assignments and the shared log() helper.
IMAGE="fes-harness-sandbox:v6"
PROXY_IMAGE="fes-harness-sandbox-proxy:v6"
NETWORK="fes-sandbox-net"
PROXY_NAME="fes-sandbox-proxy"
PROXY_PORT="8888"
COURSIER_VOLUME="fes-sandbox-coursier-cache"
SANDBOX_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[sandbox] %s\n' "$*" >&2; }

# --- shared preflight + wait-rc guard (extracted from the three runners) -----------------------
# CONTRACT: both helpers below call `infra_fault "<msg>"` on failure. infra_fault is deliberately
# NOT defined here — each runner defines it with its own role tag ([run-agent] / [run-fast-gate] /
# [run-reviewer]) and the exit-124 convention, so the emitted diagnostics stay byte-identical to
# the pre-extraction inline code. Every caller therefore MUST define infra_fault BEFORE invoking
# these helpers (all three already do).

# The claude credential, shared by the two model-touching runners (run-agent.sh, run-reviewer.sh;
# run-fast-gate.sh runs no model and needs none). Two credentials are accepted, OAuth preferred:
#   CLAUDE_CODE_OAUTH_TOKEN  a subscription OAuth token (`claude setup-token`) — bills the
#                            subscription, no extra spend
#   ANTHROPIC_API_KEY        a dedicated, spend-capped console API key
# Exactly ONE is passed into the container: passing both would let a stale/invalid
# ANTHROPIC_API_KEY (e.g. an OAuth token exported under the wrong name — the exact 401 that
# motivated this) shadow a valid OAuth token inside claude. Sets CREDENTIAL_ENV to the docker -e
# args for the chosen credential; returns 1 if neither is set (callers infra-fault with their
# own role tag).
sandbox_credential_env() {
  if [[ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]]; then
    CREDENTIAL_ENV=(-e CLAUDE_CODE_OAUTH_TOKEN="$CLAUDE_CODE_OAUTH_TOKEN")
  elif [[ -n "${ANTHROPIC_API_KEY:-}" ]]; then
    CREDENTIAL_ENV=(-e ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY")
  else
    return 1
  fi
}

# The proxy-stack preflight, identical in all three runners: Docker reachable, sandbox image
# present, proxy sidecar running. On any failure the caller's infra_fault emits its tagged message
# and exits 124. (The per-runner credential check is intentionally NOT here — it is absent in
# run-fast-gate.sh — so it stays inline in the runners that need it, before this call.)
sandbox_preflight() {
  docker info >/dev/null 2>&1 || infra_fault "docker unreachable"
  docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || infra_fault "image $IMAGE missing (run harness/sandbox/build-image.sh)"
  [[ "$(docker inspect -f '{{.State.Running}}' "$PROXY_NAME" 2>/dev/null || true)" == "true" ]] \
    || infra_fault "proxy $PROXY_NAME not running (run harness/sandbox/start-proxy.sh)"
}

# Read the docker-wait exit code from $1 and validate it is a bare integer, else infra-fault.
# Empty/garbage docker-wait output = daemon hiccup: infra fault, never a silent 0. Prints the
# validated rc on stdout so callers can `rc="$(read_wait_rc "$waitfile")"`; callers that only need
# the guard (not the value) invoke it as `read_wait_rc "$waitfile" >/dev/null`.
read_wait_rc() {
  local waitfile="$1" rc
  rc="$(cat "$waitfile" 2>/dev/null || true)"
  [[ "$rc" =~ ^[0-9]+$ ]] || infra_fault "docker wait returned no usable exit code (got '${rc:-}')"
  printf '%s\n' "$rc"
}
