#!/usr/bin/env bash
# AC1: the built gate image reports the expected build-tool and claude versions from inside the
# container. Also spot-checks the non-root `gate` user and the absence of `gh` (AC5 — no
# access to the host gh token by construction, since the binary isn't even in the image).
#
# The build tool checked here is THIS repo's, from .litter-box/Dockerfile — since #9 that is the
# only file the gate image is built from, so a consumer running this test would change the two
# scala-cli lines below and nothing else.
set -euo pipefail
# The sandbox scripts ship in the artifact and live under resources/ (#9); these tests run
# them straight out of the source tree rather than out of an extraction cache.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../resources/sandbox" && pwd)"
source "$SCRIPT_DIR/lib.sh"

fail=0

echo "== image smoke test: scala-cli + claude versions inside $IMAGE ==" >&2
build_tool_out="$(docker run --rm --entrypoint scala-cli "$IMAGE" version --cli-version 2>&1)"
claude_out="$(docker run --rm --entrypoint claude "$IMAGE" --version 2>&1)"
user_out="$(docker run --rm --entrypoint whoami "$IMAGE" 2>&1)"
gh_out="$(docker run --rm --entrypoint sh "$IMAGE" -c 'command -v gh || echo NONE' 2>&1)"

if echo "$build_tool_out" | grep -q "1.15.0"; then
  echo "  ok   scala-cli reports 1.15.0"
else
  echo "  FAIL scala-cli version: $build_tool_out"; fail=1
fi

if echo "$claude_out" | grep -qE '[0-9]+\.[0-9]+\.[0-9]+'; then
  echo "  ok   claude reports a version ($claude_out)"
else
  echo "  FAIL claude version: $claude_out"; fail=1
fi

if [[ "$user_out" == "gate" ]]; then
  echo "  ok   container runs as non-root user 'gate'"
else
  echo "  FAIL expected user 'gate', got: $user_out"; fail=1
fi

if [[ "$gh_out" == "NONE" ]]; then
  echo "  ok   gh CLI is not present in the image (no host gh-token access is possible)"
else
  echo "  FAIL expected no gh in image, found: $gh_out"; fail=1
fi

exit "$fail"
