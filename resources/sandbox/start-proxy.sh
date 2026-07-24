#!/usr/bin/env bash
# Idempotent start of the egress-allowlisting proxy sidecar (issue #34). Creates the internal
# network if missing, removes any stale container from a previous crashed run, starts the
# proxy joined to both litter-box-net (where gate containers live) and the default bridge
# (its real egress), and waits for tinyproxy to actually be listening before returning.
#
# It also owns the guarantee that the fence in force IS the fence the operator wrote (issue #14).
# The allowlist is COPYed into the proxy image, so it is read at image build time and a running
# container goes on enforcing the copy its image was built from: an operator who edited
# .litter-box/allowlist and re-ran saw the identical `403 Filtered`, with nothing in the file, the
# logs or the README saying the edit had not been applied. What actually applies an edit is the
# image build the preflight runs just before this script (build-image.sh, unconditionally); what
# this script owns is that the edit is demonstrably in force by the time the loop proceeds. Two
# things below, both unconditional rather than advisory: the container is always recreated from the
# current image, never reused, and the list it reports back is compared against the effective one
# rather than assumed equal to it.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

# The rebuild below stages a build context; lib.sh owns the array and this is the one trap that
# empties it.
trap sandbox_cleanup_staged_ctxs EXIT

# `--internal`: no route to the outside world. Gate containers join ONLY this network, so the
# proxy is the only reachable peer. Egress isolation lives here, not in a firewall inside the
# gate container.
docker network inspect "$NETWORK" >/dev/null 2>&1 \
  || docker network create --internal "$NETWORK" >/dev/null

# Drops any container from a previous run and starts a fresh one from the CURRENT proxy image,
# which is what makes an image rebuild sufficient for an allowlist edit to take effect. Returns
# only once tinyproxy is listening; exits the script on any failure that a retry cannot fix.
start_proxy_container() {
  # Remove any stale container from a previous crashed run before starting fresh.
  docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true

  docker run -d --name "$PROXY_NAME" \
    --network "$NETWORK" \
    "$PROXY_IMAGE" >/dev/null

  # Also join the default bridge network for real egress (litter-box-net alone has none).
  # A silently missing bridge attachment would leave the proxy "up" but egress-less, turning
  # infra faults into fake code REDs downstream, so verify the attachment and fail hard if
  # the connect failed for any reason other than "already connected".
  docker network connect bridge "$PROXY_NAME" >/dev/null 2>&1 || true
  if ! docker inspect -f '{{range $k, $_ := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' \
      "$PROXY_NAME" | grep -qx bridge; then
    log "fatal: proxy $PROXY_NAME is not attached to the bridge network (no egress)"
    exit 1
  fi

  # Readiness: no `nc`/`netcat` in the alpine+tinyproxy image, and tinyproxy writes no pidfile
  # by default in our config, so grep the container's own stdout log for tinyproxy's own
  # "main loop" notice (verified empirically: `NOTICE ... Starting main loop. Accepting
  # connections.`) rather than depending on a tool that may not be installed.
  for _ in $(seq 1 20); do
    if docker logs "$PROXY_NAME" 2>&1 | grep -q "Accepting connections"; then
      return 0
    fi
    sleep 0.5
  done
  log "proxy $PROXY_NAME did not come up in time"
  docker logs "$PROXY_NAME" 2>&1 | tail -20 >&2 || true
  exit 1
}

allowlist="$(effective_allowlist)"
start_proxy_container

# The fence in force against the fence the operator wrote. On the loop's own path this cannot
# differ, since build-image.sh has just rebuilt the proxy image from the effective list, so the
# branch below is not the mechanism by which an edit lands: it is the backstop for a start-proxy.sh
# invoked on its own, and the assertion that refuses to leave a proxy running behind a list nobody
# wrote. The container is fresh by construction here, so a list that DIFFERS can only mean the
# IMAGE is stale: rebuild it and start again. Exactly one retry, not a loop: if the two still
# disagree after a rebuild then something is wrong that another rebuild cannot fix, and starting
# anyway would leave the operator debugging a 403 the file they edited says cannot happen, which is
# the failure #14 is about. The first mismatch costs a rebuild, the second one aborts the run.
#
# A list that could not be READ (rc 2) is never rebuilt away: rebuilding on a docker fault would
# spend minutes and then abort under a message blaming an allowlist that is very likely correct, so
# both checks stop right there and name docker.
rc=0
proxy_enforces "$allowlist" || rc=$?
if (( rc == 2 )); then
  log "fatal: cannot read the allowlist in force from proxy $PROXY_NAME (docker exec failed)"
  exit 1
elif (( rc != 0 )); then
  log "proxy $PROXY_NAME enforces an allowlist that differs from $allowlist, rebuilding $PROXY_IMAGE"
  build_proxy_image
  start_proxy_container
  rc=0
  proxy_enforces "$allowlist" || rc=$?
  if (( rc == 2 )); then
    log "fatal: cannot read the allowlist in force from proxy $PROXY_NAME after rebuilding $PROXY_IMAGE (docker exec failed)"
    exit 1
  elif (( rc != 0 )); then
    log "fatal: proxy $PROXY_NAME still does not enforce $allowlist after rebuilding $PROXY_IMAGE"
    exit 1
  fi
fi

log "proxy $PROXY_NAME up on $NETWORK:$PROXY_PORT enforcing $allowlist"
exit 0
