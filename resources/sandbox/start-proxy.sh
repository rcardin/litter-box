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
# logs or the README saying the edit had not been applied. Two things below close that, and both
# are unconditional rather than advisory: the container is always recreated from the current image,
# never reused, and a container whose baked list disagrees with the effective one gets the image
# rebuilt under it instead of a silent start.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

# The rebuild below stages a build context; lib.sh owns the array and this is the one trap that
# empties it.
trap sandbox_cleanup_staged_ctxs EXIT

# `--internal`: no route to the outside world. Gate containers join ONLY this network, so the
# proxy is the only reachable peer — egress isolation lives here, not in a firewall inside the
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
  # infra faults into fake code REDs downstream — so verify the attachment and fail hard if
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

# The fence in force against the fence the operator wrote. The container is fresh by construction
# here, so a mismatch can only mean the IMAGE is stale: rebuild it and start again. Exactly one
# retry, not a loop: if the two still disagree after a rebuild then something is wrong that
# another rebuild cannot fix, and starting anyway would leave the operator debugging a 403 the file
# they edited says cannot happen, which is the failure #14 is about.
if [[ "$(proxy_allowlist_in_force)" != "$(cat "$allowlist")" ]]; then
  log "proxy $PROXY_NAME enforces an allowlist that differs from $allowlist, rebuilding $PROXY_IMAGE"
  build_proxy_image
  start_proxy_container
  if [[ "$(proxy_allowlist_in_force)" != "$(cat "$allowlist")" ]]; then
    log "fatal: proxy $PROXY_NAME still does not enforce $allowlist after rebuilding $PROXY_IMAGE"
    exit 1
  fi
fi

log "proxy $PROXY_NAME up on $NETWORK:$PROXY_PORT enforcing $allowlist"
exit 0
