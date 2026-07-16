#!/usr/bin/env bash
# The cold reviewer dispatch, containerized (v6 slice 4, issue #37). Moves the LAST model-touched
# execution off the host: the reviewer now runs `claude -p` INSIDE the sandbox image with ZERO
# mounts — no repository, no output volume, no host config, no `.git`. Everything it judges (the
# issue, the conventions, the tamper report, the full diff) is already spliced into the prompt by
# loop.sh's render_template, so the container needs nothing but the prompt (an env var) and the
# dedicated credential (CLAUDE_CODE_OAUTH_TOKEN preferred, ANTHROPIC_API_KEY as fallback — see
# sandbox_credential_env in lib.sh). Its STDOUT is the verdict, streamed back verbatim to
# this script's stdout, which dispatch_review captures to the review file and greps exactly as
# before. Independence is now enforced by CONSTRUCTION twice over: there is no tree in the
# container to touch, and the mutating tools are still denied as defense-in-depth.
#
#   REVIEW_PROMPT=... run-reviewer.sh   (or, back-compat, run-reviewer.sh PROMPT)
#
# REVIEW_PROMPT  the fully-rendered reviewer prompt (diff + instructions already spliced in),
#                delivered via ENV so the large multi-line diff never rides in argv (ARG_MAX /
#                process-listing leak). $1 is still honoured as a back-compat fallback.
#
# stdout   the reviewer's text output — the VERDICT: sentinel on its last line — the product
# stderr   this script's diagnostics + the container's stderr (never the verdict)
#
# Exit codes mirror run-agent.sh / run-fast-gate.sh so dispatch_review needs no new logic:
#   0    the container ran; stdout holds the reviewer's output (possibly empty = crashed reviewer,
#        which loop.sh already treats as an infra fault — see dispatch_review's empty-review guard)
#   124  timeout (container killed by the trap) or any infra fault (missing image, dead proxy,
#        unreachable Docker, no credential) — dispatch_review maps 124 to an rc-50 terminal that
#        spends NO repair budget.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

# Tool-deny flags stay on the invocation as defense-in-depth: independence is already enforced by
# the zero-mount container (there is no working tree to touch), but the reviewer is still handed no
# mutating tools. Read stays allowed for extra context — identical to the pre-slice-4 host call.
REVIEWER_DENY=(--disallowed-tools Edit Write MultiEdit NotebookEdit Bash)

# Any failure here is an INFRA FAULT (exit 124), never a review verdict — same convention as
# run-agent.sh / run-fast-gate.sh, and gtimeout wraps this script so a real timeout arrives as a
# signal below.
infra_fault() { echo "[run-reviewer] INFRA FAULT: $*" >&2; exit 124; }

# Prompt from ENV (preferred, keeps the multi-line diff out of argv), $1 as back-compat fallback.
# A missing prompt is an infra fault (124), not a bare shell error — so it maps to dispatch_review's
# rc-50 no-budget terminal like every other infra fault, and only AFTER infra_fault() is defined.
PROMPT="${REVIEW_PROMPT:-${1:-}}"
[[ -n "$PROMPT" ]] || infra_fault "no reviewer prompt (set REVIEW_PROMPT env or pass it as argv \$1)"

sandbox_credential_env || infra_fault "neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY set (the sandboxed reviewer has no other way to authenticate)"
sandbox_preflight   # docker reachable + image present + proxy running (shared, see lib.sh)

# Only a waitfile is needed on the host — the reviewer container mounts nothing, so there is no
# workdir/input/output tmpdir to stage. Rooted under $HOME for the same colima reason the other
# runners document (macOS's real TMPDIR is invisible to the Docker daemon under colima).
FES_SANDBOX_TMP_ROOT="${FES_SANDBOX_TMP_ROOT:-$HOME/.cache/fes-harness-sandbox}"
mkdir -p "$FES_SANDBOX_TMP_ROOT"
cname="fes-reviewer-$$-$(date +%s)"
waitfile="$(mktemp "$FES_SANDBOX_TMP_ROOT/reviewer-wait-XXXXXX")"

# EXIT covers every normal path; TERM/INT cover gtimeout and Ctrl-C. gtimeout kills the docker
# CLIENT process group, never the container in the VM, so an untrapped timeout would orphan a full
# claude container — the traps kill it explicitly and exit 124. Same pattern as run-agent.sh.
cleanup() {
  docker rm -f "$cname" >/dev/null 2>&1 || true
  kill "${logs_pid:-}" "${wait_pid:-}" >/dev/null 2>&1 || true
  rm -f "$waitfile"
}
on_signal() {
  echo "[run-reviewer] signal received — killing reviewer container $cname" >&2
  docker kill "$cname" >/dev/null 2>&1 || true
  exit 124
}
trap cleanup EXIT
trap on_signal TERM INT

# --- the reviewer container -------------------------------------------------------------------
# ZERO mounts (no -v at all): the prompt and the dedicated credential are the only non-proxy env. Same
# non-root gate user + cap-drop + no-new-privileges as every other role, and it joins
# fes-sandbox-net only, so it reaches the network solely through the proxy sidecar
# (api.anthropic.com is on the allowlist). Detached (gtimeout can't reach into the VM — see
# on_signal) and NOT --rm (AutoRemove races docker wait for the exit code). The default `claude -p`
# output format is TEXT — the final assistant message, whose last line is the VERDICT sentinel — so
# the container's stdout is the verdict verbatim. The deny flags are passed as positional args so
# the (possibly large, multi-line) prompt travels as an env var, never as an argv entry.
proxy_url="http://$PROXY_NAME:$PROXY_PORT"
docker run -d --name "$cname" \
  --network "$NETWORK" \
  --user gate \
  --cap-drop=ALL \
  --security-opt=no-new-privileges \
  -e HOME=/home/gate \
  "${CREDENTIAL_ENV[@]}" \
  -e HTTP_PROXY="$proxy_url" -e HTTPS_PROXY="$proxy_url" \
  -e http_proxy="$proxy_url" -e https_proxy="$proxy_url" \
  -e NO_PROXY="" -e no_proxy="" \
  -e REVIEW_PROMPT="$PROMPT" \
  --entrypoint /bin/bash \
  "$IMAGE" \
  -c 'claude -p "$REVIEW_PROMPT" --dangerously-skip-permissions "$@"' _ "${REVIEWER_DENY[@]}" \
  >/dev/null \
  || infra_fault "docker run failed to start the reviewer container"

# Stream the container's stdout to THIS script's stdout and its stderr to stderr (docker logs keeps
# the two streams split for a non-TTY container). dispatch_review then redirects stdout to the
# review file and stderr to review_file.stderr, so the verdict grep and the empty-review infra-
# fault guard see ONLY the reviewer's stdout — never this script's diagnostics.
docker logs -f "$cname" &
logs_pid=$!

# Exit code via a BACKGROUND `docker wait` whose pid the interruptible `wait` builtin blocks on, so
# the TERM/INT traps fire promptly on a timeout.
docker wait "$cname" >"$waitfile" &
wait_pid=$!
wait "$wait_pid" || true

# Let the log streamer flush the tail of the output (container has already exited here).
wait "$logs_pid" 2>/dev/null || true

# Validate the docker-wait output for its side-effect only (a genuine infra check that docker wait
# produced usable output), then discard it: unlike run-agent.sh the reviewer never branches on the
# container's exit code — see the exit 0 rationale below.
read_wait_rc "$waitfile" >/dev/null   # infra-fault on empty/garbage, else discard (shared, see lib.sh)

# A nonzero claude exit is NOT an infra fault here: it leaves empty stdout, which loop.sh's
# dispatch_review already treats as a crashed/timed-out reviewer (rc 50, no budget spent) — exactly
# like the old host `claude -p ... || rc=$?`, which never treated a nonzero claude rc as fatal
# (only a timeout, or empty output, ended the loop). So exit 0 regardless of the container rc.
exit 0
