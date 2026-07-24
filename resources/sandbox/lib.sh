#!/usr/bin/env bash
# Shared constants for the FAST-gate sandbox. Sourced, never executed directly — no side effects,
# just variable assignments and the shared log() helper.
#
# These Docker identifiers are global to the machine, and start-proxy.sh does `docker rm -f` on
# PROXY_NAME at startup, BEFORE any issue label is consulted: two litter-box instances sharing
# them would kill each other's proxy mid-iteration, and no amount of label discipline could stop
# it. So they are namespaced by the `instance-name` config key, which the loop exports as
# LITTER_BOX_INSTANCE onto every child it forks (Settings.InstanceEnvVar / LiveProc.exportEnv).
#
# This is the ONLY place the names are derived. The fallback keeps the scripts runnable by hand
# (build-image.sh, start-proxy.sh, sandbox/test/*) without the loop having exported anything, and
# equals the reference config's own `instance-name`.
INSTANCE_NAME="${LITTER_BOX_INSTANCE:-litter-box}"
IMAGE="${INSTANCE_NAME}-sandbox:v6"
# The build-tool-free base layer (base.Dockerfile). Built locally by build-image.sh and consumed by
# the consumer's .litter-box/Dockerfile through its BASE_IMAGE ARG, so a checkout never needs the
# registry. NOT namespaced by INSTANCE_NAME: it carries nothing instance-specific, so two instances
# sharing it is correct and saves a multi-minute Claude CLI install per instance.
BASE_IMAGE="litter-box-base:v1"
PROXY_IMAGE="${INSTANCE_NAME}-sandbox-proxy:v6"
NETWORK="${INSTANCE_NAME}-net"
PROXY_NAME="${INSTANCE_NAME}-proxy"
PROXY_PORT="8888"
COURSIER_VOLUME="${INSTANCE_NAME}-coursier-cache"
SANDBOX_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[sandbox] %s\n' "$*" >&2; }

# The repo the loop is working on, which is NOT derivable from SANDBOX_DIR any more (#9). These
# scripts ship inside the litter-box artifact and run from an extraction cache
# (~/.cache/litter-box/sandbox/<digest>, see Sandbox.scala), so `$SANDBOX_DIR/..` names a directory
# with no relationship to the consumer's repo. Two ways in, in order:
#
#   LITTER_BOX_REPO_ROOT  exported onto every child by the loop (Settings.childEnv), the only path
#                         that is correct when the sandbox lives in the cache
#   git rev-parse         for a hand-run script (sandbox/test/*, a manual build-image.sh), where
#                         the operator's cwd IS the repo they mean
#
# Neither answering is fatal HERE — lib.sh is sourced by scripts that do not all need a repo root —
# so REPO_ROOT is left empty and the scripts that do need one say so themselves.
REPO_ROOT="${LITTER_BOX_REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || true)}"

# --- the egress allowlist: one derivation, two readers ----------------------------------------
# `litter-box init` writes .litter-box/allowlist into the consumer's repo, so that is the file an
# operator edits and it wins. The shipped proxy/allowlist is the fallback for a repo that has never
# been scaffolded.
#
# The answer lives HERE rather than inline in build-image.sh, where it used to, because two scripts
# need it and they must never disagree (issue #14): build-image.sh bakes that file into the proxy
# image, and start-proxy.sh checks the list a running proxy is really enforcing against it. Two
# independent derivations of "the allowlist" is exactly how the image and the file could drift with
# nothing noticing.
effective_allowlist() {
  local scaffolded="$REPO_ROOT/.litter-box/allowlist"
  if [[ -n "$REPO_ROOT" && -f "$scaffolded" ]]; then
    printf '%s\n' "$scaffolded"
  else
    printf '%s\n' "$SANDBOX_DIR/proxy/allowlist"
  fi
}

# The allowlist the RUNNING proxy is enforcing, on stdout. Empty when there is no such container or
# its copy cannot be read, which compares unequal to any real allowlist and so reads as a mismatch:
# the safe direction, since a mismatch only ever costs a rebuild.
proxy_allowlist_in_force() {
  docker exec "$PROXY_NAME" cat /etc/tinyproxy/allowlist 2>/dev/null || true
}

# Staged build contexts, removed by the EXIT trap every script that builds an image installs. One
# trap for all of them: a second `trap ... EXIT` REPLACES the first rather than adding to it, so a
# per-context trap would leak every context but the last one registered.
SANDBOX_STAGED_CTXS=()
sandbox_cleanup_staged_ctxs() {
  [[ ${#SANDBOX_STAGED_CTXS[@]} -gt 0 ]] && rm -rf "${SANDBOX_STAGED_CTXS[@]}"
  return 0
}

# Builds the proxy image from whatever `effective_allowlist` names. Shared by build-image.sh, which
# builds all three images, and by start-proxy.sh, which rebuilds this one alone when it catches the
# running proxy enforcing something else.
#
# The list is COPYed into the image (proxy/Dockerfile) and not bind-mounted at run time, which is
# what makes a rebuild necessary at all. Deliberate: a mount would read the fence out of a file the
# sandboxed worker can reach in the repo it is editing, so a worker could widen its own egress. The
# image is the one copy nothing inside the sandbox can write.
#
# mktemp -d, not an in-place copy over the shipped proxy/allowlist: the sandbox tree is a
# content-addressed cache directory (Sandbox.scala), so writing into it would make its contents
# disagree with the digest that names it.
build_proxy_image() {
  local allowlist ctx
  allowlist="$(effective_allowlist)"
  ctx="$SANDBOX_DIR/proxy"
  if [[ "$allowlist" != "$ctx/allowlist" ]]; then
    ctx="$(mktemp -d)"
    SANDBOX_STAGED_CTXS+=("$ctx")
    cp "$SANDBOX_DIR/proxy/Dockerfile" "$SANDBOX_DIR/proxy/tinyproxy.conf" "$ctx/"
    cp "$allowlist" "$ctx/allowlist"
    log "using allowlist $allowlist"
  fi
  log "building $PROXY_IMAGE ..."
  docker build -t "$PROXY_IMAGE" -f "$ctx/Dockerfile" "$ctx"
}

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
  elif [[ "${ANTHROPIC_API_KEY:-}" == sk-ant-oat* ]]; then
    # An OAuth token exported under the API-key name. Passing it as ANTHROPIC_API_KEY is a
    # guaranteed 401 ("Invalid API key"), which is what motivated the check above; the prefix
    # says unambiguously what the value IS, so pass it under the name it belongs to.
    log "ANTHROPIC_API_KEY holds an OAuth token (sk-ant-oat...) — passing it as CLAUDE_CODE_OAUTH_TOKEN; export it under that name to silence this"
    CREDENTIAL_ENV=(-e CLAUDE_CODE_OAUTH_TOKEN="$ANTHROPIC_API_KEY")
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
