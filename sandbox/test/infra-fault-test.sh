#!/usr/bin/env bash
# AC7: run-fast-gate.sh's gate-time preflight exits 124 (infra-fault code, no repair budget
# spent) when Docker is unreachable. Deterministic and daemon-free: a bogus DOCKER_HOST makes
# the very first `docker info` probe fail.
set -euo pipefail
# The sandbox scripts ship in the artifact and live under resources/ (#9); these tests run
# them straight out of the source tree rather than out of an extraction cache.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../resources/sandbox" && pwd)"
source "$SCRIPT_DIR/lib.sh"

fail=0

# Run the gate against an unreachable daemon; capture the exit code without `set -e` aborting.
rc=0
DOCKER_HOST="tcp://127.0.0.1:1" "$SCRIPT_DIR/run-fast-gate.sh" >/dev/null 2>&1 || rc=$?

if [[ "$rc" == "124" ]]; then
  echo "  ok   unreachable Docker at gate time -> rc 124 (infra fault, no repair budget)"
else
  echo "  FAIL expected rc 124 on unreachable Docker, got: $rc"; fail=1
fi

exit "$fail"
