#!/usr/bin/env bash
# AC (issue #37): the egress fence is verified from EVERY container role. For each of the four
# roles — worker, fixer, gate, reviewer — a request to an allowlisted host succeeds and a request
# to a non-allowlisted host is refused by the proxy. Supersedes the single-shot
# proxy-allowlist-test.sh: same refusal signature, now asserted once per role in the exact network
# posture that role runs in.
#
# The two egress mechanisms in play (all roles join fes-sandbox-net only, so the proxy is the sole
# reachable host either way):
#   - env    : HTTP(S)_PROXY set in the container env — how run-agent.sh (worker/fixer) and
#              run-reviewer.sh (reviewer) point `claude`'s fetch at the proxy. `curl` reads the
#              same env, so a bare `curl` faithfully exercises that path.
#   - curl-x : proxy given explicitly via `curl -x` — the faithful curl analog of run-fast-gate.sh,
#              whose sbt/coursier JVM reaches the proxy via -Dhttp(s).proxyHost props (a JVM prop
#              curl cannot read, so -x stands in for it).
#
# Empirically verified refusal signature (tinyproxy 1.11.2, alpine:3.20, FilterDefaultDeny Yes): a
# filtered CONNECT gets "HTTP/1.1 403 Filtered", which curl reports as a failed tunnel — curl exit
# code 7, http_code 000. Requires the proxy sidecar already running (start-proxy.sh) and $NETWORK.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/lib.sh"

# The reviewer/worker/fixer's actual critical allowlist entry — the host that must be reachable for
# `claude` to work at all. Asserting on it (rather than a maven mirror) makes the test validate the
# entry the roles genuinely depend on. No credentials needed: we only assert the proxy TUNNEL opens
# (curl exit 0), not a 2xx — an allowlisted host may legitimately answer 401/404/405, which still
# proves the fence let the CONNECT through.
ALLOWED_HOST="api.anthropic.com"  # on sandbox/proxy/allowlist — the roles' critical egress target
BLOCKED_HOST="example.com"        # deliberately not on the allowlist
fail=0

[[ "$(docker inspect -f '{{.State.Running}}' "$PROXY_NAME" 2>/dev/null || true)" == "true" ]] \
  || { echo "  FAIL proxy $PROXY_NAME is not running -- run harness/sandbox/start-proxy.sh first" >&2; exit 1; }

echo "== egress policy test: every container role reaches allowlisted hosts, is refused elsewhere ==" >&2

proxy_url="http://$PROXY_NAME:$PROXY_PORT"

# run_curl MECHANISM HOST -> prints "<http_code> <curl_exit_code>". Uses the sandbox image itself
# (it ships curl), so egress is exercised from the exact image every role runs in.
run_curl() {
  local mech="$1" host="$2" code rc=0
  if [[ "$mech" == "env" ]]; then
    code="$(docker run --rm --network "$NETWORK" \
      -e HTTP_PROXY="$proxy_url" -e HTTPS_PROXY="$proxy_url" \
      -e http_proxy="$proxy_url" -e https_proxy="$proxy_url" \
      --entrypoint curl "$IMAGE" \
      -s -o /dev/null -w '%{http_code}' --max-time 15 "https://$host/" 2>/dev/null)" || rc=$?
  else # curl-x
    code="$(docker run --rm --network "$NETWORK" --entrypoint curl "$IMAGE" \
      -x "$proxy_url" -s -o /dev/null -w '%{http_code}' --max-time 15 "https://$host/" 2>/dev/null)" || rc=$?
  fi
  printf '%s %s' "${code:-000}" "$rc"
}

# check_role ROLE MECHANISM
check_role() {
  local role="$1" mech="$2" allowed_code allowed_rc blocked_code blocked_rc
  read -r allowed_code allowed_rc <<<"$(run_curl "$mech" "$ALLOWED_HOST")"
  read -r blocked_code blocked_rc <<<"$(run_curl "$mech" "$BLOCKED_HOST")"

  # Success = the proxy opened the tunnel (curl exit 0). We do NOT require 2xx: an allowlisted host
  # may answer 401/404/405 without credentials, which still proves egress was permitted. The refused
  # case below (curl exit 7, http 000) is what distinguishes a fenced host from an allowed one.
  if [[ "$allowed_rc" == "0" ]]; then
    echo "  ok   $role reaches allowlisted $ALLOWED_HOST (http=$allowed_code, via $mech)"
  else
    echo "  FAIL $role could not reach $ALLOWED_HOST: http=$allowed_code curl_rc=$allowed_rc (via $mech)"; fail=1
  fi

  # Refusal must match the PROXY's signature exactly (curl exit 7 = failed CONNECT tunnel, http
  # 000 = no response line): a bare non-zero rc would also pass on a DNS failure (rc 6), a timeout
  # (rc 28), or a dead proxy mid-run — none of which prove the allowlist FILTER is what refused the
  # host, so the test could go green while the fence is broken. Assert the documented signature.
  if [[ "$blocked_rc" == "7" && "$blocked_code" == "000" ]]; then
    echo "  ok   $role refused for non-allowlisted $BLOCKED_HOST (curl_rc=$blocked_rc, http=$blocked_code, via $mech)"
  else
    echo "  FAIL $role $BLOCKED_HOST refusal did not match proxy-filter signature (want curl_rc=7 http=000, got curl_rc=$blocked_rc http=$blocked_code, via $mech)"; fail=1
  fi
}

# Each of issue #37's four container roles, in the exact network posture its runner uses.
# worker and fixer both run through run-agent.sh with identical image/config/network, so they
# exercise the SAME env-proxy path — fixer is asserted separately to cover the named AC role, not
# because it adds a distinct egress mechanism. reviewer shares that env path via run-reviewer.sh;
# gate uses JVM -Dhttp(s).proxyHost props, which curl -x faithfully stands in for.
roles=(
  "worker   env"
  "fixer    env"
  "reviewer env"
  "gate     curl-x"
)
for entry in "${roles[@]}"; do
  # shellcheck disable=SC2086  # word-split intentional: "role mechanism" -> two args
  check_role $entry
done

exit "$fail"
