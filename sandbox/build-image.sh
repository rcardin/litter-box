#!/usr/bin/env bash
# Idempotent build of all three sandbox images (base + gate + proxy). Always runs `docker build`,
# which is a fast no-op when the layer cache is unchanged — see harness/sandbox/lib.sh for tags.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

log "building $BASE_IMAGE ..."
docker build -t "$BASE_IMAGE" -f "$SANDBOX_DIR/base.Dockerfile" "$SANDBOX_DIR"

log "building $IMAGE ..."
docker build --build-arg "BASE_IMAGE=$BASE_IMAGE" -t "$IMAGE" -f "$SANDBOX_DIR/Dockerfile" "$SANDBOX_DIR"

# The egress allowlist. `litter-box init` writes .litter-box/allowlist into the repo, so that is
# the file an operator edits and it wins. sandbox/proxy/allowlist is the fallback for a checkout
# that has never been scaffolded.
#
# The list is COPYed into the image (proxy/Dockerfile), not read at run time, so overriding it
# means building from a staged context rather than pointing a flag at a path. mktemp -d, not an
# in-place copy over sandbox/proxy/allowlist: that would leave a dirty tracked file behind after
# every build, and the loop refuses to start on an unclean tree.
REPO_ROOT="$(cd -- "$SANDBOX_DIR/.." && pwd)"
proxy_ctx="$SANDBOX_DIR/proxy"
if [[ -f "$REPO_ROOT/.litter-box/allowlist" ]]; then
  proxy_ctx="$(mktemp -d)"
  trap 'rm -rf "$proxy_ctx"' EXIT
  cp "$SANDBOX_DIR/proxy/Dockerfile" "$SANDBOX_DIR/proxy/tinyproxy.conf" "$proxy_ctx/"
  cp "$REPO_ROOT/.litter-box/allowlist" "$proxy_ctx/allowlist"
  log "using allowlist $REPO_ROOT/.litter-box/allowlist"
fi

log "building $PROXY_IMAGE ..."
docker build -t "$PROXY_IMAGE" -f "$proxy_ctx/Dockerfile" "$proxy_ctx"

log "images built: $BASE_IMAGE, $IMAGE, $PROXY_IMAGE"
