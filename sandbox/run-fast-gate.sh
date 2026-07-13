#!/usr/bin/env bash
# The FAST gate's GATE_CMD default (issue #34): runs `sbt compile test` inside the sandboxed,
# no-credentials container instead of on the host. loop.sh calls this as a single
# word-splittable command — see the GATE_CMD/GATE_OVERRIDDEN seam in harness/loop.sh.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

# --- preflight: any failure here is an INFRA FAULT, exit 124 (never a code failure) -------
# loop.sh's run_gate() already treats a gate exit code of 124 as an infra fault (the pre-
# existing "gate timed out" convention) that returns rc 50 from iterate() without spending
# repair budget. Reusing it means zero changes to the state-machine dispatch logic.
infra_fault() { echo "[run-fast-gate] INFRA FAULT: $*" >&2; exit 124; }

docker info >/dev/null 2>&1 || infra_fault "docker unreachable"
docker image inspect "$IMAGE" >/dev/null 2>&1 \
  || infra_fault "image $IMAGE missing (run harness/sandbox/build-image.sh)"
[[ "$(docker inspect -f '{{.State.Running}}' "$PROXY_NAME" 2>/dev/null || true)" == "true" ]] \
  || infra_fault "proxy $PROXY_NAME not running (run harness/sandbox/start-proxy.sh)"

# --- read-only clone: git write-tree + git archive, no live bind mount, no .git ------------
# loop.sh runs `git add -A` immediately before invoking GATE_CMD in every pass, so the index
# reflects the worker's current output. write-tree turns that staged index into a tree object;
# archive materializes it into a throwaway dir with no .git at all — stronger isolation than a
# :ro bind mount of the real working tree (no host .git/hooks ever enter the container), and
# needs no rsync exclude-list to maintain.
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || infra_fault "not inside a git working tree"

# The tmpdir must live somewhere the Docker daemon can actually see to bind-mount it. Under
# colima (this project's supported setup — see .sdkmanrc / README), only $HOME is mounted into
# the VM by default; macOS's real TMPDIR (/var/folders/...) is NOT, so a plain `mktemp -d`
# silently bind-mounts an EMPTY directory into the container (verified empirically: sbt then
# reports "Neither build.sbt nor a 'project' directory"). Root the tmpdir under $HOME instead.
FES_SANDBOX_TMP_ROOT="${FES_SANDBOX_TMP_ROOT:-$HOME/.cache/fes-harness-sandbox}"
mkdir -p "$FES_SANDBOX_TMP_ROOT"
tmpdir="$(mktemp -d "$FES_SANDBOX_TMP_ROOT/gate-XXXXXX")"

# Unique per-invocation container name: loop.sh wraps GATE_CMD in gtimeout, and gtimeout kills
# the docker CLIENT process group, never the container itself (PRD #33 design note). So the
# container must be run DETACHED under a name we can kill/remove from signal traps — a
# foreground `docker run --rm` would keep running inside the VM after a timeout, leaking a
# full sbt container per timed-out gate pass.
cname="fes-gate-$$-$(date +%s)"
waitfile="$(mktemp "$FES_SANDBOX_TMP_ROOT/wait-XXXXXX")"

# EXIT covers every normal path (and runs after a trap-initiated `exit`); it must be
# idempotent and ignore-errors. TERM/INT covers gtimeout and Ctrl-C: an untrapped fatal
# signal would kill non-interactive bash WITHOUT running its EXIT trap, skipping cleanup —
# so both signals are trapped explicitly, kill the container, and exit 124 (the infra-fault
# convention loop.sh already maps to rc 50 with no repair budget spent).
cleanup() {
  docker rm -f "$cname" >/dev/null 2>&1 || true
  # Removing the container normally ends the background `docker logs -f` / `docker wait`
  # clients on their own, but if the daemon is unreachable the rm fails and they'd outlive
  # this wrapper as orphans — kill them explicitly (pids may be unset on early infra faults).
  kill "${logs_pid:-}" "${wait_pid:-}" >/dev/null 2>&1 || true
  rm -rf "$tmpdir" "$waitfile"
}
on_signal() {
  echo "[run-fast-gate] signal received — killing gate container $cname" >&2
  docker kill "$cname" >/dev/null 2>&1 || true
  exit 124   # triggers the EXIT trap above for the actual removal + tmpdir cleanup
}
trap cleanup EXIT
trap on_signal TERM INT

tree_oid="$(git write-tree)" || infra_fault "git write-tree failed"
git archive "$tree_oid" | tar -x -C "$tmpdir" || infra_fault "git archive/extract failed"

# --- gate container: no credentials, no host env, proxy via JVM system properties ---------
# No -e ANTHROPIC_API_KEY, no GH_TOKEN, no gh config mount, no host env passthrough beyond the
# two JAVA_TOOL_OPTIONS proxy properties below (coursier/sbt run inside a JVM, which does not
# read HTTP_PROXY/HTTPS_PROXY automatically — the proxy container's name is only known at
# `docker run` time, so this can't be baked into the image).
#
# Detached (see cname comment above), and NOT --rm: AutoRemove races `docker wait` for the
# exit code, so the container is removed explicitly in cleanup() instead.
docker run -d --name "$cname" \
  --network "$NETWORK" \
  --user gate \
  --cap-drop=ALL \
  --security-opt=no-new-privileges \
  -e JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=$PROXY_NAME -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=$PROXY_NAME -Dhttps.proxyPort=$PROXY_PORT" \
  -v "$tmpdir:/workspace" \
  -v "$COURSIER_VOLUME:/home/gate/.cache/coursier" \
  -w /workspace \
  "$IMAGE" \
  -batch -no-colors compile test >/dev/null \
  || infra_fault "docker run failed to start the gate container"

# Stream the container's output into this wrapper's stdout so run_gate()'s gate log still
# captures the full sbt output exactly as the old foreground `docker run` did.
docker logs -f "$cname" 2>&1 &
logs_pid=$!

# Exit code via `docker wait`, run as a BACKGROUND job whose pid the bash `wait` builtin
# blocks on: a foreground `docker wait` would defer signal-trap delivery until it returned,
# while the `wait` builtin is interruptible, so the TERM/INT traps above fire promptly.
docker wait "$cname" >"$waitfile" &
wait_pid=$!
wait "$wait_pid" || true

# Let the log streamer flush the tail of the output (container has already exited here).
wait "$logs_pid" 2>/dev/null || true

rc="$(cat "$waitfile" 2>/dev/null || true)"
# Empty/garbage docker-wait output = daemon hiccup: infra fault, never a silent 0.
[[ "$rc" =~ ^[0-9]+$ ]] || infra_fault "docker wait returned no usable exit code (got '${rc:-}')"

exit "$rc"
