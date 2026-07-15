#!/usr/bin/env bash
# The worker/fixer dispatch, containerized (v6 slice 3, issue #36). Replaces the host `claude -p`
# invocation: loop.sh's dispatch_worker() calls this instead. The agent runs inside the sandbox
# image with a dedicated, spend-capped ANTHROPIC_API_KEY, reaches the network ONLY through the
# proxy sidecar, works on a throwaway origin/main clone (never the host tree), and leaves its
# result as a patch on the output volume — the seam the prefactor slice (#35) built.
#
#   run-agent.sh PROMPT_FILE PATCH_OUT [PRIOR_PATCH]
#
# PROMPT_FILE  the rendered worker/fixer prompt (read into the container as /input/prompt.txt)
# PATCH_OUT    where the cumulative-vs-origin/main patch is written on the host
# PRIOR_PATCH  the prior cumulative patch to seed the tree with (empty on the initial IMPL)
#
# Exit codes mirror run-fast-gate.sh so dispatch_worker needs no new dispatch logic:
#   0    the container ran; PATCH_OUT holds the agent's patch (possibly empty = no diff)
#   124  timeout (container killed) or any infra fault (missing image, dead proxy, unreachable
#        Docker, no API key, prior patch failed to apply) — dispatch_worker maps 124 to an
#        infra-fault exit that spends NO repair budget.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

PROMPT_FILE="${1:?run-agent.sh: PROMPT_FILE required}"
PATCH_OUT="${2:?run-agent.sh: PATCH_OUT required}"
PRIOR_PATCH="${3:-}"

# Any failure here is an INFRA FAULT (exit 124), never a code failure — same convention as
# run-fast-gate.sh, and gtimeout wraps this script so a real timeout arrives as a signal below.
infra_fault() { echo "[run-agent] INFRA FAULT: $*" >&2; exit 124; }

[[ -n "${ANTHROPIC_API_KEY:-}" ]] || infra_fault "ANTHROPIC_API_KEY not set (the sandboxed agent has no other way to authenticate)"
docker info >/dev/null 2>&1 || infra_fault "docker unreachable"
docker image inspect "$IMAGE" >/dev/null 2>&1 \
  || infra_fault "image $IMAGE missing (run harness/sandbox/build-image.sh)"
[[ "$(docker inspect -f '{{.State.Running}}' "$PROXY_NAME" 2>/dev/null || true)" == "true" ]] \
  || infra_fault "proxy $PROXY_NAME not running (run harness/sandbox/start-proxy.sh)"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || infra_fault "not inside a git working tree"

# The tmpdirs must live under $HOME: under colima only $HOME is mounted into the VM, so a plain
# mktemp -d (macOS /var/folders/...) would silently bind-mount an EMPTY dir. Same constraint the
# FAST gate documents in run-fast-gate.sh.
FES_SANDBOX_TMP_ROOT="${FES_SANDBOX_TMP_ROOT:-$HOME/.cache/fes-harness-sandbox}"
mkdir -p "$FES_SANDBOX_TMP_ROOT"
workdir="$(mktemp -d "$FES_SANDBOX_TMP_ROOT/agent-work-XXXXXX")"   # the origin/main clone (rw)
inpdir="$(mktemp -d "$FES_SANDBOX_TMP_ROOT/agent-inp-XXXXXX")"     # prompt + prior patch + entrypoint (ro)
outdir="$(mktemp -d "$FES_SANDBOX_TMP_ROOT/agent-out-XXXXXX")"     # the returned patch (rw)
cname="fes-agent-$$-$(date +%s)"
waitfile="$(mktemp "$FES_SANDBOX_TMP_ROOT/agent-wait-XXXXXX")"

# EXIT covers every normal path; TERM/INT cover gtimeout and Ctrl-C. gtimeout kills the docker
# CLIENT process group, never the container in the VM, so an untrapped timeout would orphan a
# full claude container — the traps kill it explicitly and exit 124. Same pattern as the gate.
cleanup() {
  docker rm -f "$cname" >/dev/null 2>&1 || true
  kill "${logs_pid:-}" "${wait_pid:-}" >/dev/null 2>&1 || true
  rm -rf "$workdir" "$inpdir" "$outdir" "$waitfile"
}
on_signal() {
  echo "[run-agent] signal received — killing agent container $cname" >&2
  docker kill "$cname" >/dev/null 2>&1 || true
  exit 124
}
trap cleanup EXIT
trap on_signal TERM INT

# --- prepare the container's inputs -----------------------------------------------------------
# Workspace = the pristine origin/main tree via git archive (no host .git ever enters the
# container — stronger isolation than a :ro bind mount of the live tree). The agent-entrypoint
# turns this into a git repo whose HEAD == origin/main, then overlays PRIOR_PATCH.
git archive origin/main | tar -x -C "$workdir" || infra_fault "git archive origin/main failed"
cp "$PROMPT_FILE" "$inpdir/prompt.txt" || infra_fault "could not stage the prompt"
cp "$SCRIPT_DIR/agent-entrypoint.sh" "$inpdir/agent-entrypoint.sh" || infra_fault "could not stage the entrypoint"
if [[ -n "$PRIOR_PATCH" && -s "$PRIOR_PATCH" ]]; then
  cp "$PRIOR_PATCH" "$inpdir/prior.patch" || infra_fault "could not stage the prior patch"
else
  : > "$inpdir/prior.patch"
fi

# --- the agent container ----------------------------------------------------------------------
# Detached (gtimeout can't reach into the VM to stop it — see on_signal) and NOT --rm (AutoRemove
# races docker wait for the exit code). No gh token, no host claude config, no host env beyond the
# dedicated API key and the proxy plumbing. --user gate + cap-drop + no-new-privileges match the
# gate container. The proxy is set both as HTTP(S)_PROXY (claude's fetch) and as JVM system
# properties (in case the agent runs sbt to self-check).
proxy_url="http://$PROXY_NAME:$PROXY_PORT"
docker run -d --name "$cname" \
  --network "$NETWORK" \
  --user gate \
  --cap-drop=ALL \
  --security-opt=no-new-privileges \
  -e HOME=/home/gate \
  -e ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
  -e HTTP_PROXY="$proxy_url" -e HTTPS_PROXY="$proxy_url" \
  -e http_proxy="$proxy_url" -e https_proxy="$proxy_url" \
  -e NO_PROXY="" -e no_proxy="" \
  -e JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=$PROXY_NAME -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=$PROXY_NAME -Dhttps.proxyPort=$PROXY_PORT" \
  -v "$workdir:/workspace" \
  -v "$inpdir:/input:ro" \
  -v "$outdir:/output" \
  -v "$COURSIER_VOLUME:/home/gate/.cache/coursier" \
  -w /workspace \
  --entrypoint /bin/bash \
  "$IMAGE" \
  /input/agent-entrypoint.sh \
  >/dev/null \
  || infra_fault "docker run failed to start the agent container"

# Stream the container's stdout (claude's stream-json) into this wrapper's stdout so
# dispatch_worker's log captures it exactly as the old host `claude -p` did.
docker logs -f "$cname" 2>&1 &
logs_pid=$!

# Exit code via a BACKGROUND `docker wait` whose pid the interruptible `wait` builtin blocks on,
# so the TERM/INT traps fire promptly on a timeout.
docker wait "$cname" >"$waitfile" &
wait_pid=$!
wait "$wait_pid" || true

# Let the log streamer flush the tail of the output (container has already exited here).
wait "$logs_pid" 2>/dev/null || true

rc="$(cat "$waitfile" 2>/dev/null || true)"
[[ "$rc" =~ ^[0-9]+$ ]] || infra_fault "docker wait returned no usable exit code (got '${rc:-}')"

# The entrypoint's only nonzero exit is 3 = an infra fault (base-repo setup, prior patch would not
# apply, or staging failed). Any other container exit (including a claude failure) leaves a
# possibly-empty patch, which the host reads as EMPTY — same as a host agent that produced nothing.
(( rc == 3 )) && infra_fault "container setup failed inside the sandbox (base repo, prior patch, or staging)"

# Hand the patch back to the host. A missing patch (claude produced nothing) becomes an empty
# file so the host's stage_patch sees EMPTY rather than a stale artifact.
if [[ -f "$outdir/agent.patch" ]]; then
  cp "$outdir/agent.patch" "$PATCH_OUT" || infra_fault "could not copy the agent patch back to the host"
else
  : > "$PATCH_OUT" || infra_fault "could not write the empty patch to $PATCH_OUT"
fi
exit 0
