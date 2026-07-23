#!/usr/bin/env bash
# Idempotent build of all three sandbox images (base + gate + proxy). Always runs `docker build`,
# which is a fast no-op when the layer cache is unchanged — see lib.sh for tags.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

[[ -n "$REPO_ROOT" ]] || {
  log "cannot locate the repo to build for: set LITTER_BOX_REPO_ROOT or run from inside the repo"
  exit 1
}

log "building $BASE_IMAGE ..."
docker build -t "$BASE_IMAGE" -f "$SANDBOX_DIR/base.Dockerfile" "$SANDBOX_DIR"

# The gate image, which is the ONE part of the sandbox that knows what the repo is built with.
#
# It comes from .litter-box/Dockerfile — the file `litter-box init` writes and the consumer owns —
# with no fallback anywhere in the shipped tree. Before #9 this built the litter-box repo's own
# `sandbox/Dockerfile`, which hardcoded sbt: every consumer, whatever they build with, got an sbt
# container, and the `.litter-box/Dockerfile` that `init` had written for them was read by nothing.
# Deleting the fallback rather than preferring the consumer's file is deliberate — a fallback here
# can only ever be one project's build tool imposed on another's, and a missing Dockerfile is a
# repo that was never `init`ed, which is a thing to say out loud rather than paper over.
#
# BASE_IMAGE is overridden to the locally built tag, so a build never needs the registry even
# though the scaffolded Dockerfile's own default points at ghcr.io.
GATE_DOCKERFILE="$REPO_ROOT/.litter-box/Dockerfile"
[[ -f "$GATE_DOCKERFILE" ]] || {
  log "missing $GATE_DOCKERFILE — run \`litter-box init\` to scaffold it"
  exit 1
}

log "building $IMAGE from .litter-box/Dockerfile ..."
docker build --build-arg "BASE_IMAGE=$BASE_IMAGE" -t "$IMAGE" \
  -f "$GATE_DOCKERFILE" "$REPO_ROOT/.litter-box"

# The egress allowlist. `litter-box init` writes .litter-box/allowlist into the repo, so that is
# the file an operator edits and it wins. The shipped proxy/allowlist is the fallback for a repo
# that has never been scaffolded.
#
# The list is COPYed into the image (proxy/Dockerfile), not read at run time, so overriding it
# means building from a staged context rather than pointing a flag at a path. mktemp -d, not an
# in-place copy over the shipped proxy/allowlist: the sandbox tree is a content-addressed cache
# directory (Sandbox.scala), so writing into it would make its contents disagree with the digest
# that names it.
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
