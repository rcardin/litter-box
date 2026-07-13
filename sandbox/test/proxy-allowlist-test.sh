#!/usr/bin/env bash
# AC3: a request from a sandbox container to a non-allowlisted host is refused by the proxy;
# a request to an allowlisted host succeeds. Requires the proxy sidecar already running
# (harness/sandbox/start-proxy.sh) and $NETWORK already created.
#
# Empirically verified refusal signature (tinyproxy 1.11.2, alpine:3.20, FilterDefaultDeny
# Yes): a filtered CONNECT gets "HTTP/1.1 403 Filtered" from tinyproxy, which curl reports as
# an unsuccessful CONNECT tunnel -- curl exit code 7 ("Failed to connect"), http_code 000 (no
# response from the actual target was ever received, since the tunnel itself was refused).
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"

fail=0

[[ "$(docker inspect -f '{{.State.Running}}' "$PROXY_NAME" 2>/dev/null || true)" == "true" ]] \
  || { echo "  FAIL proxy $PROXY_NAME is not running -- run harness/sandbox/start-proxy.sh first" >&2; exit 1; }

echo "== proxy allowlist test: allowed host succeeds, blocked host refused ==" >&2

run_curl() { # run_curl HOST -> prints "<http_code> <curl_exit_code>"
  # Uses the sandbox image itself (it ships curl): the test then exercises egress from the
  # exact image the gate runs in, and avoids pulling a drifting third-party :latest tag.
  local host="$1" code rc=0
  code="$(docker run --rm --network "$NETWORK" --entrypoint curl "$IMAGE" \
    -x "http://$PROXY_NAME:$PROXY_PORT" -s -o /dev/null -w '%{http_code}' \
    --max-time 15 "https://$host/" 2>/dev/null)" || rc=$?
  printf '%s %s' "${code:-000}" "$rc"
}

read -r allowed_code allowed_rc <<<"$(run_curl repo1.maven.org)"
read -r blocked_code blocked_rc <<<"$(run_curl example.com)"

if [[ "$allowed_rc" == "0" && "$allowed_code" == 2* ]]; then
  echo "  ok   allowed host repo1.maven.org reachable (http=$allowed_code)"
else
  echo "  FAIL allowed host repo1.maven.org: http=$allowed_code curl_rc=$allowed_rc"; fail=1
fi

if [[ "$blocked_rc" != "0" ]]; then
  echo "  ok   blocked host example.com refused by the proxy (curl_rc=$blocked_rc, http=$blocked_code)"
else
  echo "  FAIL blocked host example.com unexpectedly reachable (http=$blocked_code curl_rc=$blocked_rc)"; fail=1
fi

exit "$fail"
