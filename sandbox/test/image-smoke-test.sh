#!/usr/bin/env bash
# AC1: the built gate image reports the expected sbt and claude versions from inside the
# container. Also spot-checks the non-root `gate` user and the absence of `gh` (AC5 — no
# access to the host gh token by construction, since the binary isn't even in the image).
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"

fail=0

echo "== image smoke test: sbt + claude versions inside $IMAGE ==" >&2
sbt_out="$(docker run --rm --entrypoint sbt "$IMAGE" -no-colors --version 2>&1)"
claude_out="$(docker run --rm --entrypoint claude "$IMAGE" --version 2>&1)"
user_out="$(docker run --rm --entrypoint whoami "$IMAGE" 2>&1)"
gh_out="$(docker run --rm --entrypoint sh "$IMAGE" -c 'command -v gh || echo NONE' 2>&1)"

if echo "$sbt_out" | grep -q "1.12.9"; then
  echo "  ok   sbt reports 1.12.9"
else
  echo "  FAIL sbt version: $sbt_out"; fail=1
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
