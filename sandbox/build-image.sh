#!/usr/bin/env bash
# Idempotent build of both sandbox images (gate + proxy). Always runs `docker build`, which
# is a fast no-op when the layer cache is unchanged — see harness/sandbox/lib.sh for tags.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

log "building $IMAGE ..."
docker build -t "$IMAGE" -f "$SANDBOX_DIR/Dockerfile" "$SANDBOX_DIR"

log "building $PROXY_IMAGE ..."
docker build -t "$PROXY_IMAGE" -f "$SANDBOX_DIR/proxy/Dockerfile" "$SANDBOX_DIR/proxy"

log "images built: $IMAGE, $PROXY_IMAGE"
