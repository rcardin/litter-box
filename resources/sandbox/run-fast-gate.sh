#!/usr/bin/env bash
# The sandboxed FAST gate: runs the repo's own gate command inside the no-credentials container
# instead of on the host. The loop invokes this when `gate.sandboxed` is true (Settings/Config),
# passing `gate.fast` through as a single argument.
#
# Usage: run-fast-gate.sh "<gate command>"
#
# The command arrives as ONE argument and is handed to `bash -c` inside the container, so it keeps
# the shell semantics an operator writing a command string in config expects. Before #9 the command
# was not a parameter at all: this script appended `-batch -no-colors compile test` to the image's
# ENTRYPOINT, which meant the sandbox was still an sbt sandbox one layer below the ENTRYPOINT hole
# that #4 opened to remove exactly that coupling. A Gradle consumer got `gradle -batch -no-colors
# compile test`. The image's ENTRYPOINT is now overridden here and ignored, the same way
# run-agent.sh has always overridden it.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

# --- preflight: any failure here is an INFRA FAULT, exit 124 (never a code failure) -------
# loop.sh's run_gate() already treats a gate exit code of 124 as an infra fault (the pre-
# existing "gate timed out" convention) that returns rc 50 from iterate() without spending
# repair budget. Reusing it means zero changes to the state-machine dispatch logic.
infra_fault() { echo "[run-fast-gate] INFRA FAULT: $*" >&2; exit 124; }

sandbox_preflight   # docker reachable + image present + proxy running (shared, see lib.sh)

# Checked AFTER the preflight, not before: an operator running this by hand with no arguments is
# far more likely to be missing Docker than to be missing the argument, and the preflight's
# diagnostic is the more useful one to reach first.
GATE_CMD="${1:-}"
[[ -n "${GATE_CMD//[[:space:]]/}" ]] || infra_fault "no gate command given (usage: run-fast-gate.sh \"<command>\")"

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
LITTER_BOX_SANDBOX_TMP_ROOT="${LITTER_BOX_SANDBOX_TMP_ROOT:-$HOME/.cache/litter-box-sandbox}"
mkdir -p "$LITTER_BOX_SANDBOX_TMP_ROOT"
tmpdir="$(mktemp -d "$LITTER_BOX_SANDBOX_TMP_ROOT/gate-XXXXXX")"

# Unique per-invocation container name: loop.sh wraps GATE_CMD in gtimeout, and gtimeout kills
# the docker CLIENT process group, never the container itself (PRD #33 design note). So the
# container must be run DETACHED under a name we can kill/remove from signal traps — a
# foreground `docker run --rm` would keep running inside the VM after a timeout, leaking a
# full sbt container per timed-out gate pass.
cname="litter-box-gate-$$-$(date +%s)"
waitfile="$(mktemp "$LITTER_BOX_SANDBOX_TMP_ROOT/wait-XXXXXX")"

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

# --- gate container: no credentials, no host env, proxy addressed two ways ----------------
# No -e ANTHROPIC_API_KEY, no GH_TOKEN, no gh config mount, no host env passthrough beyond the
# proxy plumbing below. The proxy container's name is only known at `docker run` time, so none of
# this can be baked into the image.
#
# BOTH forms are set, because "the build tool" is no longer assumed to be a JVM one (#9):
#   JAVA_TOOL_OPTIONS   a JVM ignores HTTP_PROXY/HTTPS_PROXY and reads system properties instead;
#                       this is what sbt/coursier need
#   HTTP(S)_PROXY       what everything else honours — a native launcher (scala-cli, which is a
#                       Bun/Graal binary before it is ever a JVM), curl, npm, cargo, go
# Setting only the first was the sbt assumption surviving one layer below the ENTRYPOINT hole #4
# opened: a non-JVM gate got a container with no route out at all and failed on DNS. Neither form
# widens egress — both address the same allowlisting proxy, and the network has no other exit.
# NO_PROXY is cleared so nothing in the image's own environment can carve an exception.
#
# Detached (see cname comment above), and NOT --rm: AutoRemove races `docker wait` for the
# exit code, so the container is removed explicitly in cleanup() instead.
proxy_url="http://$PROXY_NAME:$PROXY_PORT"
docker run -d --name "$cname" \
  --network "$NETWORK" \
  --user gate \
  --cap-drop=ALL \
  --security-opt=no-new-privileges \
  -e JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=$PROXY_NAME -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=$PROXY_NAME -Dhttps.proxyPort=$PROXY_PORT" \
  -e HTTP_PROXY="$proxy_url" -e HTTPS_PROXY="$proxy_url" \
  -e http_proxy="$proxy_url" -e https_proxy="$proxy_url" \
  -e NO_PROXY="" -e no_proxy="" \
  -v "$tmpdir:/workspace" \
  -v "$COURSIER_VOLUME:/home/gate/.cache/coursier" \
  -w /workspace \
  --entrypoint /bin/bash \
  "$IMAGE" \
  -c "$GATE_CMD" >/dev/null \
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

# Empty/garbage docker-wait output = daemon hiccup: infra fault, never a silent 0.
rc="$(read_wait_rc "$waitfile")"   # validated bare integer, else infra-fault (shared, see lib.sh)

exit "$rc"
