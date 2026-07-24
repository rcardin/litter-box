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

# Staged build contexts, removed on any exit. The array and its cleanup live in lib.sh because
# start-proxy.sh stages one too (issue #14); the reason there is exactly one trap is documented
# with them.
trap sandbox_cleanup_staged_ctxs EXIT

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

# The context is a STAGED COPY of .litter-box, not .litter-box itself, because that directory is
# where the credential lives: `init` writes .env.example and tells the operator to copy it to
# .env, which holds the token the sandboxed worker authenticates with. `docker build` streams its
# whole context to the daemon, so pointing it at .litter-box would hand the daemon the token on
# every build and let any `COPY . .` a consumer adds bake it into the image — in the one image
# whose stated contract (docs/base-image.md) is that it carries no credentials.
#
# A copy rather than a context of exactly one file: the consumer owns this Dockerfile, and a
# `COPY settings.xml /home/gate/.m2/` next to their build tool layer is a reasonable thing for
# them to write. Everything in .litter-box stays reachable EXCEPT the two things that must not be
# in an image — .env* (the credential and its template) and logs/ (run output, potentially large).
gate_ctx="$(mktemp -d)"
SANDBOX_STAGED_CTXS+=("$gate_ctx")
(
  cd "$REPO_ROOT/.litter-box"
  find . -mindepth 1 -maxdepth 1 \
    ! -name '.env' ! -name '.env.*' ! -name 'logs' \
    -exec cp -R {} "$gate_ctx/" \;
)

log "building $IMAGE from .litter-box/Dockerfile ..."
docker build --build-arg "BASE_IMAGE=$BASE_IMAGE" -t "$IMAGE" \
  -f "$GATE_DOCKERFILE" "$gate_ctx"

# The egress allowlist, staged and baked by `build_proxy_image` (lib.sh). It moved there so that
# start-proxy.sh can rebuild this one image on its own when it finds the running proxy enforcing a
# different list (issue #14). Which file that is, and why it is baked rather than mounted, is
# documented with it.
build_proxy_image

log "images built: $BASE_IMAGE, $IMAGE, $PROXY_IMAGE"
