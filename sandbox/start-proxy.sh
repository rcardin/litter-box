#!/usr/bin/env bash
# Idempotent start of the egress-allowlisting proxy sidecar (issue #34). Creates the internal
# network if missing, removes any stale container from a previous crashed run, starts the
# proxy joined to both fes-sandbox-net (where gate containers live) and the default bridge
# (its real egress), and waits for tinyproxy to actually be listening before returning.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

# `--internal`: no route to the outside world. Gate containers join ONLY this network, so the
# proxy is the only reachable peer — egress isolation lives here, not in a firewall inside the
# gate container.
docker network inspect "$NETWORK" >/dev/null 2>&1 \
  || docker network create --internal "$NETWORK" >/dev/null

# Remove any stale container from a previous crashed run before starting fresh.
docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true

docker run -d --name "$PROXY_NAME" \
  --network "$NETWORK" \
  "$PROXY_IMAGE" >/dev/null

# Also join the default bridge network for real egress (fes-sandbox-net alone has none).
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
    log "proxy $PROXY_NAME up on $NETWORK:$PROXY_PORT"
    exit 0
  fi
  sleep 0.5
done
log "proxy $PROXY_NAME did not come up in time"
docker logs "$PROXY_NAME" 2>&1 | tail -20 >&2 || true
exit 1
