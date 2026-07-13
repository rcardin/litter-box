#!/usr/bin/env bash
# Shared constants for the v6-slice-1 FAST-gate sandbox (issue #34). Sourced, never executed
# directly — no side effects, just variable assignments and the shared log() helper.
IMAGE="fes-harness-sandbox:v6"
PROXY_IMAGE="fes-harness-sandbox-proxy:v6"
NETWORK="fes-sandbox-net"
PROXY_NAME="fes-sandbox-proxy"
PROXY_PORT="8888"
COURSIER_VOLUME="fes-sandbox-coursier-cache"
SANDBOX_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[sandbox] %s\n' "$*" >&2; }
