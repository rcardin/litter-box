#!/usr/bin/env bash
#
# v2 Ralph loop — v1 (reviewer + tamper + self-repair) + Testcontainers tier split.
# Implements step v2 of docs/autonomous-loop-harness.md and
# docs/superpowers/specs/2026-07-01-harness-v2-testcontainers-split-design.md.
# Builds on v1 (docs/superpowers/specs/2026-07-01-harness-v1-reviewer-design.md).
#
# v1 gave us:
#   - bounded self-repair: a shared budget of 2 fix iterations per US, spent by EITHER a
#     RED gate OR a reviewer REQUEST_CHANGES.
#   - test-tamper check: diff the test tree vs origin/main and surface it to the reviewer.
#   - cold independent reviewer: a separate, fresh `claude -p` that sees only the diff, the
#     acceptance criteria, CONTEXT.md, and the tamper report, and emits a VERDICT sentinel.
#   - `needs-human` terminal: budget exhaustion (RED or REQUEST_CHANGES) still opens a PR for
#     the audit trail but flips the issue to needs-human instead of needs-review.
#
# v2 split the single gate into two tiers by source directory (src/test = in-memory; src/it =
# Testcontainers real-PG). v6 slice 3 REMOVED the local IT tier (see below): the loop now runs
# only the FAST gate (GATE_CMD, `sbt compile test`, src/test), and the real-PG src/it tier is
# judged by GitHub Actions on the PR. The repair budget stays SHARED and stays 2: fast-RED and
# REQUEST_CHANGES draw from the same pool. The tamper diff still covers src/it (an agent must
# not gut an IT to dodge the CI judge).
#
# v3 (GitHub Actions CI `build` check + branch protection on main) shipped OUTSIDE this
# script, followed by a hardening pass: infra-fault terminal rc 50 (timeouts and empty
# reviewer output exit for inspection WITHOUT spending the repair budget), protected-path
# enforcement in bash (harness/, docs/,
# .github/, PROMPT.md, CONTEXT.md, STOP.md), and a fatal stale-base guard.
#
# v4 (this revision) adds the auto-merge terminal, class-1 only: on reviewer APPROVE the
# loop waits for the required CI check (bounded by CI_WAIT_TIMEOUT; timeout = infra fault
# rc 50), merges with `gh pr merge --squash --delete-branch`, and VERIFIES the PR state is
# MERGED (unverified = rc 50). CI red after green local gates flips the issue to
# needs-human WITHOUT self-repair — the loop never repairs against the independent check.
# Class-2/3 SUCCESS still stops at PR for a human merge. A verified merge also flips any
# open `blocked` issue whose `Blocked-by: #N` body references are now all closed, and
# fires the notify seam (NTFY_TOPIC / NOTIFY_CMD; also fired on every needs-human terminal
# and every rc 50 exit).
#
# v6 slice 1 containerized the FAST gate: GATE_CMD defaults to harness/sandbox/run-fast-gate.sh,
# which snapshots the staged index via `git write-tree` + `git archive` into a throwaway dir (no
# live bind mount, no .git in the container), then runs `sbt compile test` inside a pinned, non-
# root, no-credentials image on an isolated Docker network (fes-sandbox-net) that can reach nothing
# but an egress-allowlisting proxy sidecar (fes-sandbox-proxy — versioned allowlist at
# harness/sandbox/proxy/allowlist). A self-populating named volume caches coursier downloads across
# runs, mounted only inside sandbox containers, never by any host sbt process. The gate seam
# contract is a single overridable command string reusing the rc-124-is-infra-fault convention for
# every infra problem. See harness/README.md "Containerized FAST gate".
#
# v6 slice 2 reworked the code path so agent output crosses an inspect-then-apply boundary instead
# of being committed straight from the tree the agent edited. After every worker/fixer dispatch,
# stage_patch() takes the cumulative diff against origin/main as a size-capped patch file, resets
# the tree to a pristine base, guards the patch (protected-path guard reads the PATCH and spells out
# CI workflows/.github, harness code/harness, and the constitution CONTEXT.md; plus a byte-size cap
# MAX_PATCH_BYTES), and applies it with `git apply --index`. A patch that will not apply is an infra
# fault (rc 50, no budget), NOT a gate failure; a guard rejection routes to needs-human. The worker/
# fixer STUB contract: a stub PRODUCES a patch at $PATCH_OUT — the patch-extraction point is the seam.
#
# v6 slice 3 (this revision) moves the agent itself into the sandbox and removes the last host
# execution of agent-authored code. dispatch_worker()'s real path now calls
# harness/sandbox/run-agent.sh instead of a host `claude -p`: the container clones origin/main
# (git archive, read-only), overlays the prior cumulative patch, runs claude with a DEDICATED
# spend-capped ANTHROPIC_API_KEY reaching the network only through the proxy, and leaves the
# cumulative patch on an output volume. Containers are detached and awaited under ITER_TIMEOUT; on
# expiry run-agent.sh kills the container (a client-side timeout alone would orphan it) and exits
# 124 (infra fault, no repair budget). In the same slice the LOCAL IT gate, the Docker preflight,
# and the colima socket env plumbing are deleted: GitHub Actions (already a required check on
# protected main, and it runs `sbt It/test`) becomes the sole Testcontainers judge, and an IT-red
# PR follows the existing CI-red path (comment, needs-human, halt — no local self-repair).
#
# Still NOT here (deferred): convention-lint gate step, auto-merge for class-2/3, cost cap in
# dollars (MAX_ITERS is the ceiling), and containerizing the reviewer (planned as #37; it runs on
# the host but authors no code — all mutating tools are denied).
#
# The loop never lets the model choose what to work on: bash resolves all state with `gh`
# queries and dispatches narrow, fresh `claude -p` tasks. Every dispatch is fresh context.
#
# Usage:   harness/loop.sh
# Env:     MAX_ITERS      hard cap on US count               (default 1)
#          ITER_TIMEOUT   per-dispatch agent timeout, s      (default 1800)
#          GATE_TIMEOUT   per-fast-gate sbt budget, s        (default 900)
#          DRY_RUN        1 = stop before invoking claude -p and before any push/PR
#          REPAIR_BUDGET  shared fix budget per US           (default 2)
#          MAX_PATCH_BYTES size cap on an extracted patch, B  (default 1000000 — v6 slice 2)
#          -- test seams (default to the real thing; overridden by the state-machine test) --
#          GATE_CMD       the fast (src/test) gate command   (default: harness/sandbox/run-fast-gate.sh,
#                                                              a containerized `sbt compile test` — v6 slice 1)
#          IMPL_CMD       stub for the worker dispatch       (default: real claude -p)
#          FIX_CMD        stub for the fix dispatch          (default: real claude -p)
#          REVIEW_CMD     stub for the reviewer dispatch     (default: real claude -p)
#          NTFY_TOPIC     ntfy.sh topic for push notifications (unset = log-only)
#          NOTIFY_CMD     test seam: eval'd with $msg in scope, replaces the ntfy call
#          CI_WAIT_TIMEOUT  bound on the required-CI wait, s   (default 900)
#          CI_WAIT_CMD    test seam for the CI wait            (default: gh pr checks --watch)
#          CI_APPEAR_TIMEOUT  bound on a check REGISTERING, s  (default 300)
#          CI_APPEAR_INTERVAL poll period while it registers,s (default 10)
#          CI_APPEAR_CMD  test seam: prints the rollup size    (default: gh pr view --json ...)
#          MERGE_CMD      test seam for the merge              (default: gh pr merge --squash)
set -euo pipefail

# --- locate repo root (script lives in harness/) ---------------------------------------
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

MAX_ITERS="${MAX_ITERS:-1}"
ITER_TIMEOUT="${ITER_TIMEOUT:-1800}"   # per-dispatch agent budget, seconds (container killed on expiry)
GATE_TIMEOUT="${GATE_TIMEOUT:-900}"    # per-fast-gate sbt compile+test budget, seconds
DRY_RUN="${DRY_RUN:-0}"
REPAIR_BUDGET="${REPAIR_BUDGET:-2}"    # shared across gate-RED and REQUEST_CHANGES per US
MAX_PATCH_BYTES="${MAX_PATCH_BYTES:-1000000}"  # size cap (bytes) on an extracted agent patch (v6 slice 2)
CI_WAIT_TIMEOUT="${CI_WAIT_TIMEOUT:-900}"   # bound on waiting for the required CI check, seconds
CI_APPEAR_TIMEOUT="${CI_APPEAR_TIMEOUT:-300}"  # bound on waiting for a check to REGISTER, seconds
CI_APPEAR_INTERVAL="${CI_APPEAR_INTERVAL:-10}" # poll period while waiting for it to register
ITERATE_PROMPT="$SCRIPT_DIR/iterate-prompt.md"
FIX_PROMPT="$SCRIPT_DIR/fix-prompt.md"
REVIEW_PROMPT="$SCRIPT_DIR/review-prompt.md"
CONVENTIONS="$REPO_ROOT/CONTEXT.md"
LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"

# Test seams: default empty -> real `claude -p`; the state-machine test overrides them with
# deterministic stubs so it can force RED / REQUEST_CHANGES / budget exhaustion for free.
# Capture whether the FAST gate seam was overridden BEFORE applying its default: the sandbox
# preflight below (image build + proxy) only applies to the real containerized gate and agent,
# never to test stubs. The state-machine test overrides GATE_CMD together with IMPL/FIX, so a
# skipped preflight always coincides with a stubbed worker/fixer — no container is ever needed.
GATE_OVERRIDDEN=0
if [[ -n "${GATE_CMD:-}" ]]; then GATE_OVERRIDDEN=1; fi
# v6 slice 1: the FAST gate runs in a container (harness/sandbox/run-fast-gate.sh) instead of on
# the host. Exit code contract is unchanged (0 green, 124 infra fault, anything else red).
GATE_CMD="${GATE_CMD:-$SCRIPT_DIR/sandbox/run-fast-gate.sh}"
IMPL_CMD="${IMPL_CMD:-}"
FIX_CMD="${FIX_CMD:-}"
REVIEW_CMD="${REVIEW_CMD:-}"
CI_WAIT_CMD="${CI_WAIT_CMD:-}"   # test seam; default (empty) -> gh pr checks <pr> --watch --fail-fast
CI_APPEAR_CMD="${CI_APPEAR_CMD:-}"  # test seam; default (empty) -> gh pr view <pr> --json statusCheckRollup
MERGE_CMD="${MERGE_CMD:-}"       # test seam; default (empty) -> gh pr merge <pr> --squash --delete-branch

log() { printf '[loop %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "FATAL: $*"; exit 1; }

# --- phase events (observability, passive) -----------------------------------------------
# One JSON line per state transition, appended to $STATUS_FILE, read by harness/watch.sh.
# This is a PURE APPEND: no branch here, no caller reads a return value, no exit code moves.
# A wrong phase() call produces a wrong banner, never a wrong merge.
#
# The four CUR_* globals are the event's context. iterate() keeps them current; phase() only
# reads them, so a terminal DONE emitted from the driver still carries the right issue.
STATUS_FILE="$LOG_DIR/status.jsonl"
RUN_ID="$(date +%s)"      # stamped once per process; the watcher renders only the newest run
CUR_ISSUE=""; CUR_ITER=0; CUR_PASS=0; CUR_BUDGET=0

# phase PHASE STATE [LOGFILE] [DETAIL]
# Each event is one printf, well under PIPE_BUF, appended with >>. An O_APPEND write below
# PIPE_BUF is atomic, so a reader never sees a torn line.
phase() {
  local ph="$1" st="$2" lf="${3:-}" detail="${4:-}"
  # logfile is a contract with the watcher: repo-relative, never absolute. Call sites pass
  # $LOG_DIR/... which expands absolute (LOG_DIR is $SCRIPT_DIR/logs, and SCRIPT_DIR is a
  # pwd). Normalize here, the single choke point, so no call site has to remember. An empty
  # lf stays empty; a path not under $REPO_ROOT/ passes through unchanged.
  lf="${lf#$REPO_ROOT/}"
  # Never model-controlled: only loop-internal values and the already-grepped verdict token
  # reach `detail`. Strip anything that could break out of the JSON string anyway.
  detail="${detail//\\/}"; detail="${detail//\"/}"; detail="${detail//$'\n'/ }"
  printf '{"ts":%s,"pid":%s,"run":"%s","iter":%s,"issue":"%s","phase":"%s","state":"%s","pass":%s,"budget":%s,"logfile":"%s","detail":"%s"}\n' \
    "$(date +%s)" "$$" "$RUN_ID" "$CUR_ITER" "$CUR_ISSUE" "$ph" "$st" "$CUR_PASS" "$CUR_BUDGET" "$lf" "$detail" \
    >>"$STATUS_FILE" 2>/dev/null || true
}

# `timeout` is gnu coreutils on the Mac via `gtimeout`; fall back gracefully.
TIMEOUT_BIN="$(command -v timeout || command -v gtimeout || true)"

# --- environment the whole loop needs ---------------------------------------------------
# Both `claude -p` (it may invoke sbt itself) and the gate inherit these. Without them the
# build goes RED for infra reasons (wrong JDK, no Docker socket) and masks the real signal.
#
# yaes 0.20.0 binds JDK 25's StructuredTaskScope API; the machine default may be newer
# (NoSuchMethodError at runtime). Pin JDK 25. See .sdkmanrc (java=25.0.2-open).
JAVA_HOME_PINNED="${JAVA_HOME_PINNED:-$HOME/.sdkman/candidates/java/25.0.2-open}"
if [[ -x "$JAVA_HOME_PINNED/bin/java" ]]; then
  export JAVA_HOME="$JAVA_HOME_PINNED"
  export PATH="$JAVA_HOME/bin:$PATH"
else
  log "WARNING: pinned JDK not at $JAVA_HOME_PINNED — gate runs under default java (may abort)"
fi
# v6 slice 3: the local IT gate is gone (GitHub Actions is the sole Testcontainers judge), so the
# colima socket / Testcontainers env plumbing that only served the host IT gate is deleted. The
# sandbox containers reach the daemon through the docker CLI's active context (colima's socket on
# this setup), and run-fast-gate.sh / run-agent.sh each do their own gate-time `docker info` infra
# check, so no loop-level Docker preflight is needed.

command -v gh    >/dev/null || die "gh not found"
command -v sbt   >/dev/null || die "sbt not found"
command -v claude >/dev/null || die "claude not found"
# Sandbox preflight (v6): build the image, start the proxy sidecar, and always stop it on loop
# exit — normal exit or any die(). It covers BOTH the containerized FAST gate AND the now-
# containerized worker/fixer (same image, same proxy). A dead/missing sidecar or a stale image
# would surface as a false gate-RED / infra fault; run-fast-gate.sh and run-agent.sh re-check at
# dispatch time and exit 124 (infra fault, no budget spent) if anything has gone stale mid-run.
# Skipped when GATE_CMD is overridden — the state-machine test overrides it together with the
# IMPL/FIX seams, so a skipped preflight always coincides with stubbed, container-free dispatches.
if [[ "$GATE_OVERRIDDEN" == "0" ]]; then
  [[ -n "${ANTHROPIC_API_KEY:-}" ]] \
    || die "ANTHROPIC_API_KEY not set — the sandboxed worker/fixer has no other way to authenticate"
  "$SCRIPT_DIR/sandbox/build-image.sh" || die "sandbox image build failed"
  "$SCRIPT_DIR/sandbox/start-proxy.sh" || die "sandbox proxy failed to start"
  trap '"$SCRIPT_DIR/sandbox/stop-proxy.sh" >/dev/null 2>&1 || true' EXIT
fi
for f in "$ITERATE_PROMPT" "$FIX_PROMPT" "$REVIEW_PROMPT"; do
  [[ -f "$f" ]] || die "missing prompt template: $f"
done
[[ -f "$CONVENTIONS" ]] || die "missing conventions file: $CONVENTIONS"

# --- template rendering ----------------------------------------------------------------
# render_template TEMPLATE OUT KEY1 FILE1 [KEY2 FILE2 ...]
# Replaces each line containing the literal `{{KEY}}` with the contents of FILE. Splicing
# from files (not -v) preserves embedded newlines. Prints nothing; writes OUT.
render_template() {
  local template="$1" out="$2"; shift 2
  cp "$template" "$out"
  while (( $# >= 2 )); do
    local key="$1" cf="$2"; shift 2
    local tmp; tmp="$(mktemp)"
    awk -v k="{{$key}}" -v f="$cf" '
      index($0, k) { while ((getline line < f) > 0) print line; close(f); next }
      { print }
    ' "$out" >"$tmp"
    mv "$tmp" "$out"
  done
}

# --- the gate: run a tier command, capture its log -------------------------------------
# Returns the ACTUAL command exit code: 0 = green, 124 = timeout (an infra fault — callers
# must treat it as terminal, not RED, and must NOT spend repair budget on it), any other
# non-zero = red. -Werror is already in build.sbt scalacOptions.
# Parametrised over (label, command, timeout, logfile). v6 slice 3 removed the local IT tier
# (CI is now the sole Testcontainers judge), so this drives only the FAST tier (GATE_CMD,
# containerized `sbt compile test`) and the CI-wait. GATE_CMD is a test seam.
run_gate() {
  local label="$1" cmd="$2" tmo="$3" logfile="$4"
  log "$label gate: $cmd (timeout ${tmo}s) -> $logfile"
  local g="$cmd"
  [[ -n "$TIMEOUT_BIN" ]] && g="$TIMEOUT_BIN $tmo $g"
  local rc=0
  $g >"$logfile" 2>&1 || rc=$?
  return $rc
}

# --- claude dispatch (worker / fixer) --------------------------------------------------
# Writes a stream-json JSONL log (one event per turn) so harness/tail-claude.sh can follow it
# live; bash reads only the exit code. Honours the IMPL_CMD / FIX_CMD seams (a stub command
# that ignores the prompt and simulates the agent).
# Real path (v6 slice 3): the agent runs INSIDE the sandbox container. run-agent.sh clones
# origin/main read-only, overlays the prior cumulative patch, runs claude with a dedicated
# spend-capped ANTHROPIC_API_KEY reaching the network only through the proxy, and writes the
# cumulative-vs-origin/main patch to $patch_out. It returns 124 on timeout (it kills the
# container — gtimeout alone would orphan it) or any infra fault, 0 otherwise; the caller must
# not let a half-finished worker fall through to the gates. Stub overrides simulate the agent by
# writing $PATCH_OUT themselves; a stub that exits 124 simulates the container-dispatch timeout.
#
# $CURRENT_PATCH is the prior cumulative work seeding the container's tree: empty on the initial
# IMPL, and the last applied patch on a FIX (stage_patch resets the tree only AFTER this returns,
# so CURRENT_PATCH still points at the prior pass here).
dispatch_worker() {
  local role="$1" prompt="$2" logf="$3" patch_out="$4" override="" rc=0
  case "$role" in
    IMPL) override="$IMPL_CMD";;
    FIX)  override="$FIX_CMD";;
  esac
  log "dispatching $role agent -> $logf (patch -> $patch_out)"
  if [[ -n "$override" ]]; then
    ( export PATCH_OUT="$patch_out"; eval "$override" ) >"$logf" 2>&1 || rc=$?
    log "$role stub exited rc=$rc"
    (( rc == 124 )) && return 124        # a stub can simulate the container-dispatch timeout
    return 0
  fi
  # The container reads the prompt from a file (it can be large; a mount is cleaner than an arg).
  local prompt_file; prompt_file="$(mktemp)"
  printf '%s' "$prompt" > "$prompt_file"
  local runner=("$SCRIPT_DIR/sandbox/run-agent.sh" "$prompt_file" "$patch_out" "${CURRENT_PATCH:-}")
  if [[ -n "$TIMEOUT_BIN" ]]; then
    "$TIMEOUT_BIN" "$ITER_TIMEOUT" "${runner[@]}" >"$logf" 2>&1 || rc=$?
  else
    "${runner[@]}" >"$logf" 2>&1 || rc=$?
  fi
  rm -f "$prompt_file"
  if [[ "$rc" == "124" ]]; then
    log "WARNING: $role sandbox dispatch failed rc=124 (${ITER_TIMEOUT}s timeout or infra fault: missing image/proxy/Docker/API key/prior-patch)"
    return 124
  fi
  log "$role sandbox dispatch exited rc=$rc (patch written by the container)"
  return 0
}

# --- cold reviewer dispatch ------------------------------------------------------------
# Containerized (v6 slice 4): the reviewer runs `claude -p` inside the sandbox image with ZERO
# mounts via sandbox/run-reviewer.sh — the last model-touched execution to leave the host. Unlike
# the worker, the reviewer's STDOUT is the product: `claude -p` default output is "text" (verified)
# — the final assistant message, whose last line is the VERDICT sentinel. run-reviewer.sh streams
# the container's stdout/stderr split, and we capture stdout to the review file and stderr
# separately. Honours the REVIEW_CMD seam (the state-machine tests never touch a container).
#
# Independence is enforced by CONSTRUCTION twice over: the container mounts no repository at all
# (nothing to touch — the diff, AC, CONTEXT.md and tamper report are all spliced into the prompt),
# and the mutating tools are still denied as defense-in-depth (run-reviewer.sh holds REVIEWER_DENY).
# Read stays allowed for extra context. This is the "cold independent reviewer" the gate stack needs.
dispatch_review() {
  local prompt="$1" review_file="$2" rc=0
  log "dispatching REVIEWER in the sandbox (cold, zero mounts, no mutating tools) -> $review_file"
  if [[ -n "$REVIEW_CMD" ]]; then
    ( eval "$REVIEW_CMD" ) >"$review_file" 2>"$review_file.stderr" || rc=$?
    log "REVIEWER stub exited rc=$rc"
    return 0
  fi
  # Deliver the (large, multi-line) prompt via ENV, never argv — same reason dispatch_worker writes
  # it to a file: keeps the full diff out of argv (ARG_MAX / process-listing leak). The env prefix
  # scopes REVIEW_PROMPT to this one command and passes cleanly through gtimeout into run-reviewer.sh.
  local runner=("$SCRIPT_DIR/sandbox/run-reviewer.sh")
  if [[ -n "$TIMEOUT_BIN" ]]; then
    REVIEW_PROMPT="$prompt" "$TIMEOUT_BIN" "$ITER_TIMEOUT" "${runner[@]}" >"$review_file" 2>"$review_file.stderr" || rc=$?
  else
    REVIEW_PROMPT="$prompt" "${runner[@]}" >"$review_file" 2>"$review_file.stderr" || rc=$?
  fi
  if [[ "$rc" == "124" ]]; then
    log "WARNING: REVIEWER sandbox dispatch failed rc=124 (${ITER_TIMEOUT}s timeout or infra fault: missing image/proxy/Docker/API key)"
    return 124
  fi
  log "REVIEWER sandbox dispatch exited rc=$rc"
  return 0
}

# --- test-tamper check -----------------------------------------------------------------
# Numstat the test files in the APPLIED PATCH (v6 slice 2: computed from $CURRENT_PATCH via
# `git apply --numstat`, not from the staged tree — same verdicts, now derived from the same
# artifact the guard and git-apply see). All tests live under src/test (in-memory) or src/it
# (Testcontainers IT); v2 covers both so a repair iter cannot gut the IT to go green without
# the reviewer seeing it. Bash does NOT block on tamper — it surfaces the raw numstat plus a
# one-line summary to the reviewer, who is the judgment. numstat rows are:
# added<TAB>deleted<TAB>path ('-' = binary).
tamper_report() {
  local out="$1" raw touched=0 net_del=0 add del path
  raw="$(git apply --numstat "$CURRENT_PATCH" 2>/dev/null \
          | awk -F'\t' '$3 ~ /^src\/(test|it)\// { print }' || true)"
  if [[ -n "$raw" ]]; then
    while IFS=$'\t' read -r add del path; do
      [[ -z "$path" ]] && continue
      touched=$((touched + 1))
      [[ "$add" == "-" ]] && continue                # binary — no line counts
      (( del > add )) && net_del=$((net_del + 1))    # net deletions on this test file
    done <<< "$raw"
  fi
  {
    printf '# Test-tamper report (git apply --numstat on the applied patch, filtered to src/test, src/it)\n\n'
    printf '**Summary: %s test file(s) touched, %s with net deletions.**\n\n' "$touched" "$net_del"
    printf 'Raw numstat (added  deleted  path; a deleted file shows all lines as deletions):\n\n'
    if [[ -n "$raw" ]]; then
      printf '```\n%s\n```\n' "$raw"
    else
      printf '(no test files changed vs origin/main)\n'
    fi
  } >"$out"
}

# --- notify seam -------------------------------------------------------------------------
# Fires on exactly three events: any needs-human terminal, any rc=50 infra-fault exit, and
# each successful auto-merge (v4). NOTIFY_CMD (test seam) is eval'd with $msg in scope;
# otherwise NTFY_TOPIC posts to ntfy.sh; otherwise log-only. A dead notification channel
# must never change loop behavior: every failure is swallowed.
notify() {
  local msg="$1"
  if [[ -n "${NOTIFY_CMD:-}" ]]; then
    ( eval "$NOTIFY_CMD" ) || log "notify failed (ignored)"
  elif [[ -n "${NTFY_TOPIC:-}" ]]; then
    curl -s --max-time 10 -d "$msg" "https://ntfy.sh/${NTFY_TOPIC}" >/dev/null 2>&1 || log "notify failed (ignored)"
  else
    log "notify (no channel configured): $msg"
  fi
}

# --- blocked -> ready auto-flip ----------------------------------------------------------
# After a verified merge, a dependency just closed. Flip every open `blocked` issue whose
# body's `Blocked-by: #N` references are ALL closed. The just-merged issue counts as closed
# even if GitHub's async issue-close lags the merge. Issues without the machine-readable
# sentinel are left alone (human-managed). Runs only on the merge path — the only moment a
# dependency can newly close. bash 3.2: while-read, no mapfile.
flip_blocked() {
  local merged_issue="$1" b refs r state all_closed
  { gh issue list --state open --label blocked --json number --jq '.[].number' 2>/dev/null || true; } \
  | while read -r b; do
      [[ -z "$b" ]] && continue
      refs="$(gh issue view "$b" --json body --jq .body 2>/dev/null \
              | grep -oE 'Blocked-by: #[0-9]+' | grep -oE '[0-9]+' || true)"
      [[ -z "$refs" ]] && continue
      all_closed=1
      for r in $refs; do
        [[ "$r" == "$merged_issue" ]] && continue
        state="$(gh issue view "$r" --json state --jq .state 2>/dev/null || echo UNKNOWN)"
        [[ "$state" == "CLOSED" ]] || { all_closed=0; break; }
      done
      if (( all_closed )); then
        log "dependency #$merged_issue closed — flipping #$b blocked -> ready"
        gh issue edit "$b" --add-label ready --remove-label blocked >/dev/null 2>&1 \
          || log "WARNING: could not flip #$b blocked -> ready (flip by hand)"
      fi
    done
  return 0
}

# --- v4 auto-merge (class-1 only) --------------------------------------------------------
# Called only on outcome=SUCCESS for a class-1 issue, after the PR exists. Waits for the
# required CI check bounded by CI_WAIT_TIMEOUT, then merges and VERIFIES the merge.
# Returns: 0 merged (issue auto-closes via the PR's "Closes #N"), 40 CI red -> needs-human
# (the loop never self-repairs against the independent check), 50 infra fault (timeout,
# merge failure, or unverified merge — exit for inspection, issue stays in-progress).

# `gh pr checks` exits nonzero for two different worlds: a check ran and FAILED, and no check
# has registered yet ("no checks reported on the '<branch>' branch"). A push races the workflow
# scheduler, so a fresh PR routinely reports zero checks for a few seconds — reading that as red
# burns the issue to needs-human against a build that then goes green (PR #28 / issue #26).
# Discriminate on data, not on the exit code: block until the rollup is non-empty, and only then
# let `gh pr checks --watch` judge the result. A check that never registers is a scheduler/infra
# problem, not a code failure, so the caller maps the timeout to rc 50 (resumable), never rc 40.
# Returns: 0 once >=1 check is registered, 1 on timeout.
wait_for_checks() {
  local pr_num="$1"
  local cmd="${CI_APPEAR_CMD:-gh pr view $pr_num --json statusCheckRollup --jq '.statusCheckRollup | length'}"
  local waited=0 n=""
  while (( waited < CI_APPEAR_TIMEOUT )); do
    n="$(eval "$cmd" 2>/dev/null || true)"
    if [[ "$n" =~ ^[0-9]+$ ]] && (( n > 0 )); then
      log "CI check registered on PR #$pr_num after ${waited}s"
      return 0
    fi
    sleep "$CI_APPEAR_INTERVAL"
    waited=$(( waited + CI_APPEAR_INTERVAL ))
  done
  return 1
}

auto_merge() {
  local issue="$1" branch="$2" pr_num="$3"
  local ci_cmd="${CI_WAIT_CMD:-gh pr checks $pr_num --watch --fail-fast}"
  local ci_log="$LOG_DIR/issue-${issue}.ci-wait.log"
  local ci_rc=0
  phase CI_WAIT start "$ci_log"
  if ! wait_for_checks "$pr_num"; then
    log "no CI check registered on PR #$pr_num within ${CI_APPEAR_TIMEOUT}s — infra fault; PR open, issue stays in-progress"
    return 50
  fi
  run_gate "CI-WAIT" "$ci_cmd" "$CI_WAIT_TIMEOUT" "$ci_log" || ci_rc=$?
  if (( ci_rc == 124 )); then
    log "CI wait hit the ${CI_WAIT_TIMEOUT}s bound — infra fault; PR open, issue stays in-progress"
    return 50
  fi
  if (( ci_rc != 0 )); then
    phase CI_WAIT red "$ci_log"
    log "CI RED on PR #$pr_num after local gates green — needs-human, no merge, no self-repair"
    gh pr comment "$pr_num" --body "CI red after local gates were green. The loop never self-repairs against the independent check (v3 hands-off rule) — a human must look." >/dev/null 2>&1 || true
    gh issue edit "$issue" --add-label needs-human --remove-label in-progress >/dev/null 2>&1 \
      || log "WARNING: could not flip #$issue to needs-human (flip by hand)"
    notify "harness: #${issue} CI RED -> needs-human (PR #${pr_num})"
    return 40
  fi
  phase CI_WAIT ok "$ci_log"
  local merge_cmd="${MERGE_CMD:-gh pr merge $pr_num --squash --delete-branch}"
  log "CI green — merging PR #$pr_num"
  phase MERGE start ""
  local merge_rc=0
  $merge_cmd >>"$ci_log" 2>&1 || merge_rc=$?
  if (( merge_rc != 0 )); then
    log "merge command failed rc=$merge_rc — infra fault"
    return 50
  fi
  local state
  state="$(gh pr view "$pr_num" --json state --jq .state 2>/dev/null || true)"
  if [[ "$state" != "MERGED" ]]; then
    log "merge NOT verified (PR state '${state:-unknown}') — infra fault"
    return 50
  fi
  phase MERGE ok "" "pr=$pr_num"
  gh issue edit "$issue" --remove-label in-progress >/dev/null 2>&1 || true
  flip_blocked "$issue"
  git fetch --quiet origin main || log "post-merge fetch failed (next iteration re-fetches anyway)"
  notify "harness: #${issue} auto-merged (PR #${pr_num}, CI green, reviewer APPROVE)"
  return 0
}

# --- protected-path patch guard (v6 slice 2) -------------------------------------------
# Reads the file list straight out of the PATCH (git apply --numstat), so it guards what the
# agent PRODUCED, independent of the tree. Spells out the three classes the sandbox must never
# let an agent rewrite — CI workflow files (.github/), harness code (harness/), and the
# constitution (CONTEXT.md) — and keeps docs/, PROMPT.md and STOP.md protected as the original
# tree guard did. Returns 0 if the patch touches a protected path, 1 if it is clean. Takes the
# pre-computed numstat text (stage_patch runs git apply --numstat once and feeds it here).
patch_touches_protected() { # patch_touches_protected NUMSTAT
  local numstat="$1" p
  while IFS=$'\t' read -r _ _ p; do
    [[ -z "$p" ]] && continue
    case "$p" in
      .github/*|harness/*|docs/*|CONTEXT.md|PROMPT.md|STOP.md) return 0 ;;
    esac
  done <<< "$numstat"
  return 1
}

# On a guard rejection the tree is left pristine — a hostile or oversized patch is NEVER
# applied. Stage a small tracked marker so the terminal still has a diff to open the audit PR
# with; the routing (needs-human + PR) matches the original protected-path guard. The marker,
# not the rejected change, is what lands on the throwaway branch.
write_reject_marker() { # write_reject_marker REASON NUMSTAT
  local reason="$1" numstat="$2"
  {
    printf '# Patch rejected by the harness guard\n\n'
    printf '%s\n\n' "$reason"
    printf 'This branch is opened for the audit trail ONLY and must NOT be merged. The rejected\n'
    printf 'patch was never applied to the tree. Numstat of the rejected patch (added deleted path):\n\n```\n'
    printf '%s\n' "$numstat" | head -100
    printf '\n```\n'
  } > "$REPO_ROOT/PATCH-REJECTED.md"
  git add "$REPO_ROOT/PATCH-REJECTED.md"
}

# --- the patch seam (v6 slice 2) -------------------------------------------------------
# Every agent dispatch (worker or fixer) crosses this boundary: produce a patch, reset the tree
# to a pristine base, inspect the patch, then git-apply it. The tree the agent edited is NEVER
# committed directly. Sets STAGE_RESULT and, on OK, CURRENT_PATCH (the artifact the tamper check
# and reviewer read).
#   OK         patch inspected + applied+staged; the change is live in the tree
#   EMPTY      the agent produced no diff
#   TIMEOUT    the dispatch hit ITER_TIMEOUT (infra fault)
#   APPLY_FAIL git apply refused the patch (infra fault — NOT a gate failure, NO budget spent)
#   PROTECTED  patch touches a protected path (reject; marker staged for the audit PR)
#   OVERSIZE   patch exceeds MAX_PATCH_BYTES (reject; marker staged for the audit PR)
STAGE_RESULT=""; CURRENT_PATCH=""
stage_patch() { # stage_patch ROLE PROMPT LOGF PATCH_FILE
  local role="$1" prompt="$2" logf="$3" patch="$4" rc=0
  dispatch_worker "$role" "$prompt" "$logf" "$patch" || rc=$?
  if (( rc == 124 )); then STAGE_RESULT=TIMEOUT; return 0; fi
  # Reset to the pristine base: the agent's working tree is data to inspect, never trusted. The
  # patch file lives under harness/logs (gitignored), so clean -fd never removes it.
  git reset -q --hard origin/main
  git clean -qfd
  if [[ ! -s "$patch" ]]; then STAGE_RESULT=EMPTY; return 0; fi
  # --- inspect, THEN apply (PRD: the host inspects the patch before it touches the tree) ------
  # Compute the numstat ONCE here and feed it to both the guard and the reject marker (avoids
  # re-running git apply --numstat 2-3 times per patch). Fail-open is DELIBERATE and backstopped:
  # if git apply --numstat yields nothing (an unparseable patch) the guard sees an empty file list
  # and returns clean, but the real git apply --index below then fails, so STAGE_RESULT=APPLY_FAIL
  # (infra fault, no repair budget spent). A malformed patch therefore never reaches the gates.
  local numstat; numstat="$(git apply --numstat "$patch" 2>/dev/null)"
  local bytes; bytes="$(wc -c < "$patch" | tr -d ' ')"
  if (( bytes > MAX_PATCH_BYTES )); then
    log "patch guard: ${bytes}B exceeds the ${MAX_PATCH_BYTES}B cap — rejecting oversized patch (not applied)"
    write_reject_marker "Oversized patch: ${bytes} bytes exceeds the ${MAX_PATCH_BYTES}-byte cap." "$numstat"
    STAGE_RESULT=OVERSIZE; return 0
  fi
  if patch_touches_protected "$numstat"; then
    log "patch guard: patch touches a protected path (.github/, harness/, docs/, CONTEXT.md, PROMPT.md or STOP.md) — rejecting (not applied)"
    write_reject_marker "Patch touches a protected path (CI workflow, harness code, docs, or a control/constitution file)." "$numstat"
    STAGE_RESULT=PROTECTED; return 0
  fi
  # Inspection passed — apply on the fresh branch. A patch that will not apply is an infra fault,
  # not a gate failure: exit for inspection WITHOUT spending repair budget.
  if ! git apply --index "$patch" >"$patch.apply.err" 2>&1; then
    log "git apply refused the patch (see $patch.apply.err) — infra fault, no budget spent"
    STAGE_RESULT=APPLY_FAIL; return 0
  fi
  CURRENT_PATCH="$patch"
  STAGE_RESULT=OK; return 0
}

# --- fixer dispatch across the patch seam ----------------------------------------------
# Renders the FIX prompt, stages the fixer's patch, and emits the FIX phase event with the state
# stage_patch reached. Reads iterate()'s $issue and $body_file via bash dynamic scope, exactly as
# the inline gate/review blocks already do. The caller branches on STAGE_RESULT.
dispatch_fix() { # dispatch_fix PASS FAILFILE
  local pass="$1" fail_file="$2"
  local fix_prompt_file="$LOG_DIR/issue-${issue}-pass${pass}.fix.prompt.txt"
  render_template "$FIX_PROMPT" "$fix_prompt_file" ISSUE "$body_file" FAILURE "$fail_file"
  local fix_log="$LOG_DIR/issue-${issue}-pass${pass}.fix.claude.log"
  local fix_patch="$LOG_DIR/issue-${issue}-pass${pass}.fix.patch"
  phase FIX start "$fix_log"
  stage_patch FIX "$(cat "$fix_prompt_file")" "$fix_log" "$fix_patch"
  case "$STAGE_RESULT" in
    TIMEOUT)    phase FIX red "$fix_log" "timeout" ;;
    APPLY_FAIL) phase FIX red "$fix_log" "patch apply conflict" ;;
    PROTECTED)  phase FIX red "$fix_log" "protected-path" ;;
    OVERSIZE)   phase FIX red "$fix_log" "oversized patch" ;;
    EMPTY)      phase FIX red "$fix_log" "empty fix" ;;
    *)          phase FIX ok  "$fix_log" ;;
  esac
}

# Maps the STAGE_RESULT of a fixer dispatch onto the self-repair loop's control flow. Sets
# iterate()'s outcome/failure_kind locals through bash dynamic scope, exactly as the inline
# gate/review blocks already do (see the comment above dispatch_fix). The three copy-pasted
# post-dispatch switches used to inline this; centralising them keeps the control flow single-sourced.
# Returns: 50 = infra fault, caller must `return 50`; 2 = terminal FAIL, caller must `break`;
# 0 = the fix applied, caller continues the loop.
handle_fix_result() {
  case "$STAGE_RESULT" in
    TIMEOUT)    log "FIX worker timed out (infra fault); exiting without spending further budget"; return 50 ;;
    APPLY_FAIL) log "FIX patch did not apply (infra fault, no budget spent)"; return 50 ;;
    PROTECTED)  outcome="FAIL"; failure_kind="protected-path"; return 2 ;;
    OVERSIZE)   outcome="FAIL"; failure_kind="oversized-patch"; return 2 ;;
    EMPTY)      log "FIX produced no diff (the fixer reverted all prior work); routing to needs-human"; outcome="FAIL"; failure_kind="empty-fix"; return 2 ;;
  esac
  return 0
}

# --- one US, start to terminal ---------------------------------------------------------
iterate() {
  local n="$1"

  # Guards --------------------------------------------------------------------
  # STOP.md is a *manual* kill-switch only: create it by hand to halt the loop.
  # The loop never writes it itself — "no ready issue" is a transient idle state
  # (a US parked in human review), not a terminal one, so it must not latch.
  [[ -f "$REPO_ROOT/STOP.md" ]] && { log "STOP.md present (manual kill-switch) — exiting"; return 10; }

  # Pick US (deterministic, no LLM): resume an in-progress one, else next ready.
  local issue
  issue="$(gh issue list --state open --label in-progress --json number --jq '.[0].number')"
  if [[ -z "$issue" ]]; then
    issue="$(gh issue list --state open --label ready --json number,createdAt \
              --jq 'sort_by(.createdAt) | .[0].number')"
  fi
  if [[ -z "$issue" ]]; then
    log "no in-progress or ready issue — idle, exiting (next tick resumes when one goes ready)"
    return 11
  fi
  log "iteration $n -> issue #$issue"
  CUR_ITER="$n"; CUR_ISSUE="$issue"; CUR_PASS=0; CUR_BUDGET="$REPAIR_BUDGET"
  phase PICK ok "" "issue=$issue"

  # Render the worker prompt with the issue body injected (read-only).
  local body_file worker_prompt_file
  body_file="$LOG_DIR/issue-${issue}.body.md"
  gh issue view "$issue" --json title,body \
    --jq '"# " + (.title) + "\n\n" + .body' >"$body_file"
  worker_prompt_file="$LOG_DIR/issue-${issue}.prompt.txt"
  render_template "$ITERATE_PROMPT" "$worker_prompt_file" ISSUE "$body_file"

  # v4: auto-merge is earned by class-1 only. Detect the class once, at pick time.
  local issue_labels is_class1=0
  issue_labels="$(gh issue view "$issue" --json labels --jq '[.labels[].name] | join(" ")')"
  [[ " $issue_labels " == *" class-1 "* ]] && is_class1=1

  # Dry run stops here — before ANY git/label mutation, so it is truly read-only.
  if [[ "$DRY_RUN" == "1" ]]; then
    log "DRY_RUN=1 — rendered worker prompt for #$issue -> $worker_prompt_file; no mutation; stopping"
    return 20
  fi

  # Require a clean tree on a fresh branch off main. Serial loop: one US at a time.
  [[ -z "$(git status --porcelain)" ]] || die "working tree not clean — refusing to start"
  # Stale-base guard: everything downstream (diff, tamper, gates, PR) is measured against
  # origin/main. A fetch failure or a branch off local HEAD would silently measure against
  # a stale base, so both are fatal — no fallback.
  git fetch --quiet origin main || die "cannot fetch origin/main — refusing to run against a stale base"
  local branch="us-${issue}"
  if git show-ref --verify --quiet "refs/heads/$branch"; then
    log "branch $branch exists — checking it out"
    git checkout --quiet "$branch"
  else
    git checkout --quiet -b "$branch" origin/main || die "cannot branch off origin/main"
  fi

  # Mark in-progress so a crashed run resumes the same US next tick.
  gh issue edit "$issue" --add-label in-progress --remove-label ready >/dev/null

  # --- bounded self-repair state -------------------------------------------------------
  # budget is shared: each RED or REQUEST_CHANGES that is not the last one spends one unit. The
  # state is declared BEFORE the initial dispatch because a patch-guard rejection on the very
  # first worker patch sets outcome/failure_kind and skips the loop straight to the terminal.
  local budget="$REPAIR_BUDGET" pass=0
  CUR_BUDGET="$budget"
  local outcome="" gate_status="" verdict="" failure_kind=""
  local review_file="$LOG_DIR/issue-${issue}-review.md"
  : >"$review_file"                                   # empty until the first review
  local reviewed=0

  # Initial worker dispatch (fresh context), crossing the patch seam. -----------
  # stage_patch resets the tree and applies the worker's patch under inspection; the tree the
  # worker edited is never committed directly.
  local worker_prompt; worker_prompt="$(cat "$worker_prompt_file")"
  local impl_log="$LOG_DIR/issue-${issue}-iter${n}.claude.log"
  local impl_patch="$LOG_DIR/issue-${issue}-iter${n}.impl.patch"
  phase IMPL start "$impl_log"
  stage_patch IMPL "$worker_prompt" "$impl_log" "$impl_patch"
  case "$STAGE_RESULT" in
    TIMEOUT)
      phase IMPL red "$impl_log" "timeout"
      log "IMPL worker timed out — infra fault; a half-finished worker must not reach the gates"
      return 50 ;;
    APPLY_FAIL)
      phase IMPL red "$impl_log" "patch apply conflict"
      log "IMPL patch did not apply — infra fault, no budget spent"
      return 50 ;;
    EMPTY)
      phase IMPL ok "$impl_log" "no diff"
      log "no changes produced by the iteration — leaving issue in-progress, not opening a PR"
      return 30 ;;
    PROTECTED)
      phase IMPL red "$impl_log" "protected-path"
      log "patch guard rejected the initial worker patch (protected-path) — routing to needs-human"
      outcome="FAIL"; failure_kind="protected-path"; gate_status="SKIPPED" ;;
    OVERSIZE)
      phase IMPL red "$impl_log" "oversized patch"
      log "patch guard rejected the initial worker patch (oversized-patch) — routing to needs-human"
      outcome="FAIL"; failure_kind="oversized-patch"; gate_status="SKIPPED" ;;
    *)
      phase IMPL ok "$impl_log" ;;
  esac

  # --- bounded self-repair loop --------------------------------------------------------
  # Skipped entirely if the initial patch was already rejected (outcome set above): the guard
  # rejection routes straight to the needs-human terminal, exactly as the old tree guard did.
  while [[ -z "$outcome" ]]; do
    pass=$((pass + 1))
    git add -A                                        # stage so new files show in diff/gate/tamper

    # --- FAST tier: src/test only, containerized (v6 slice 1) --------------------------
    # v6 slice 3: this is the ONLY local gate. Integration tests (src/it, real Postgres via
    # Testcontainers) are judged by GitHub Actions on the PR — the required `build` check runs
    # `sbt It/test`, so an IT failure surfaces as CI-red on the auto-merge path (needs-human,
    # no local self-repair), never as a local gate here.
    CUR_PASS="$pass"
    local gate_log="$LOG_DIR/issue-${issue}-pass${pass}.gate.log"
    local gate_rc=0
    phase FAST_GATE start "$gate_log"
    run_gate "FAST" "$GATE_CMD" "$GATE_TIMEOUT" "$gate_log" || gate_rc=$?
    if (( gate_rc == 124 )); then
      log "WARNING: FAST gate hit the ${GATE_TIMEOUT}s timeout — infra fault, not a code failure"
      return 50
    fi
    if (( gate_rc != 0 )); then
      gate_status="RED"; failure_kind="gate-RED"
      phase FAST_GATE red "$gate_log"
      log "FAST gate RED (pass $pass, see $gate_log)"
      if (( budget == 0 )); then outcome="FAIL"; break; fi
      budget=$((budget - 1)); CUR_BUDGET="$budget"
      log "self-repair: budget now $budget — dispatching FIX for gate-RED"
      local fail_file="$LOG_DIR/issue-${issue}-pass${pass}.failure.md"
      {
        printf '## Fast-gate failure — `%s` (compile under -Werror, then in-memory tests)\n\n' "$GATE_CMD"
        printf 'Tail of the fast-gate log:\n\n```\n'
        tail -n 200 "$gate_log"
        printf '\n```\n'
      } >"$fail_file"
      dispatch_fix "$pass" "$fail_file"
      handle_fix_result; local hf=$?
      (( hf == 50 )) && return 50
      (( hf == 2 ))  && break
      continue
    fi

    gate_status="GREEN"
    log "FAST gate GREEN (pass $pass) — running tamper check + cold reviewer"
    phase FAST_GATE ok "$gate_log"

    # Tamper check feeds the reviewer (bash surfaces, does not block).
    local tamper_file="$LOG_DIR/issue-${issue}-tamper.md"
    tamper_report "$tamper_file"

    # Full diff for the reviewer (staged, so new files are included).
    local diff_file="$LOG_DIR/issue-${issue}-diff.patch"
    git diff --cached origin/main >"$diff_file"

    local review_prompt_file="$LOG_DIR/issue-${issue}-pass${pass}.review.prompt.txt"
    render_template "$REVIEW_PROMPT" "$review_prompt_file" \
      ISSUE "$body_file" CONVENTIONS "$CONVENTIONS" TAMPER "$tamper_file" DIFF "$diff_file"
    local review_rc=0
    phase REVIEW start "$review_file"
    dispatch_review "$(cat "$review_prompt_file")" "$review_file" || review_rc=$?
    if (( review_rc == 124 )); then
      phase REVIEW red "$review_file" "timeout"
      log "REVIEWER timed out — infra fault; exiting without spending budget"
      return 50
    fi
    reviewed=1

    # An empty (or whitespace-only) review is a crashed/timed-out reviewer, not a verdict:
    # infra fault. Distinct from a non-empty review missing the sentinel (model misbehavior,
    # handled fail-safe below).
    if ! grep -q '[^[:space:]]' "$review_file"; then
      phase REVIEW red "$review_file" "empty review"
      log "reviewer produced no output — infra fault (crashed or timed-out reviewer)"
      return 50
    fi

    # Grep, not parse. Missing sentinel → REQUEST_CHANGES (fail safe, never auto-approve).
    verdict="$(grep -oE 'VERDICT: (APPROVE|REQUEST_CHANGES)' "$review_file" | tail -1 | awk '{print $2}')"
    if [[ -z "$verdict" ]]; then
      verdict="REQUEST_CHANGES"
      log "reviewer emitted no VERDICT sentinel — fail-safe REQUEST_CHANGES"
    fi
    log "reviewer verdict: $verdict (pass $pass)"
    phase REVIEW ok "$review_file" "verdict=$verdict"

    if [[ "$verdict" == "APPROVE" ]]; then
      outcome="SUCCESS"; break
    fi

    # REQUEST_CHANGES — spend from the same shared budget.
    failure_kind="REQUEST_CHANGES"
    if (( budget == 0 )); then outcome="FAIL"; break; fi
    budget=$((budget - 1)); CUR_BUDGET="$budget"
    log "self-repair: budget now $budget — dispatching FIX for REQUEST_CHANGES"
    local fail_file="$LOG_DIR/issue-${issue}-pass${pass}.failure.md"
    {
      printf '## The independent reviewer requested changes\n\n'
      cat "$review_file"
      printf '\n\n'
      cat "$tamper_file"
    } >"$fail_file"
    dispatch_fix "$pass" "$fail_file"
    handle_fix_result; local hf=$?
    (( hf == 50 )) && return 50
    (( hf == 2 ))  && break
    continue
  done

  # --- terminal: commit, push, PR (SUCCESS -> needs-review, FAIL -> needs-human) --------
  # A fixer that produced no diff (failure_kind=empty-fix) left the tree pristine: stage_patch
  # reset to origin/main before it saw the empty patch, so the "nothing staged" guard below would
  # otherwise fire first and mask the routing. Stage a small tracked marker so the needs-human audit
  # PR still opens, mirroring the patch-guard reject marker. In the cumulative-patch model an empty
  # fix reverts all prior work, so this branch legitimately holds only the marker.
  if [[ "$failure_kind" == "empty-fix" ]]; then
    {
      printf '# Fixer produced no diff\n\n'
      printf 'The self-repair fixer returned an empty patch. In the cumulative-patch model that\n'
      printf 'reverts all prior work on this branch, so the loop routed the issue to human review\n'
      printf 'instead of re-gating an empty tree. Opened for the audit trail ONLY; do NOT merge.\n'
    } > "$REPO_ROOT/FIX-EMPTY.md"
    git add "$REPO_ROOT/FIX-EMPTY.md"
  fi
  git add -A
  if git diff --cached --quiet HEAD; then
    log "nothing staged at terminal — unexpected; leaving in-progress"
    return 30
  fi

  local label commit_tag pr_note
  if [[ "$outcome" == "SUCCESS" && "$is_class1" == "1" ]]; then
    label=""   # no flip: the auto-merge path owns the issue's fate
    commit_tag="reviewer APPROVE, gate ${gate_status}"
    pr_note="**Reviewer: APPROVE** · gate ${gate_status} · class-1 — v4 auto-merge candidate: the loop merges after the required CI check goes green."
  elif [[ "$outcome" == "SUCCESS" ]]; then
    label="needs-review"
    commit_tag="reviewer APPROVE, gate ${gate_status}"
    pr_note="**Reviewer: APPROVE** · gate ${gate_status} (containerized in-memory FAST tier green; the real-PG IT tier is judged by CI on this PR). Not class-1, so not auto-merged: a human reviews and merges."
  elif [[ "$failure_kind" == "protected-path" || "$failure_kind" == "oversized-patch" ]]; then
    label="needs-human"
    commit_tag="patch guard rejection (${failure_kind}), gate ${gate_status}"
    pr_note="**Needs human** — the patch guard rejected the agent's patch (${failure_kind}: a CI workflow / harness / docs / control-or-constitution file, or a patch over the size cap). The rejected change was NOT applied; this branch holds only a rejection marker and must NOT be merged."
  elif [[ "$failure_kind" == "empty-fix" ]]; then
    label="needs-human"
    commit_tag="fixer produced no diff (empty-fix), gate ${gate_status}"
    pr_note="**Needs human**: the self-repair fixer produced no diff. In the cumulative-patch model that reverts all prior work, so this branch holds only an audit marker (the prior implementation is NOT on it). Opened for the audit trail; do NOT merge."
  else
    label="needs-human"
    commit_tag="self-repair budget exhausted (${failure_kind}), gate ${gate_status}"
    pr_note="**Needs human** — self-repair budget of ${REPAIR_BUDGET} exhausted on ${failure_kind} (last gate ${gate_status}). Opened for the audit trail; do NOT merge without review."
  fi

  if [[ "$label" == "needs-human" ]]; then
    notify "harness: #${issue} needs-human (${failure_kind:-?}, gate ${gate_status})"
  fi

  git commit --quiet -m "feat(US-${issue}): autonomous iteration — ${commit_tag}

Refs #${issue}. Loop iteration ${n}, ${pass} gate pass(es). Outcome: ${outcome}.
This commit was produced by an unattended claude -p iteration (harness v2).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"

  git push --quiet -u origin "$branch"

  local pr_body_file; pr_body_file="$LOG_DIR/issue-${issue}.pr-body.md"
  {
    printf 'Autonomous harness (v2) iteration %s for #%s.\n\n' "$n" "$issue"
    printf '%s\n\n' "$pr_note"
    if (( reviewed )); then
      printf '<details><summary>Independent reviewer output</summary>\n\n```\n'
      cat "$review_file"
      printf '\n```\n\n</details>\n\n'
    fi
    if [[ "$outcome" == "SUCCESS" && "$is_class1" == "1" ]]; then
      printf 'v4 auto-merge: class-1 + reviewer APPROVE — the loop merges once the required CI check is green.\n\n'
    else
      printf 'Not auto-merged (v4 merges class-1 + APPROVE only): a human reviews and merges.\n\n'
    fi
    printf 'Closes #%s\n' "$issue"
  } >"$pr_body_file"

  local pr_url pr_num
  pr_url="$(gh pr create --base main --head "$branch" \
    --title "US-${issue}: autonomous iteration (${outcome}, gate ${gate_status})" \
    --body-file "$pr_body_file")"
  pr_num="${pr_url##*/}"
  if [[ -z "$pr_num" ]]; then
    log "could not determine PR number from gh pr create output — infra fault"
    return 50
  fi
  log "PR #${pr_num} opened for #$issue (outcome ${outcome})"
  phase PR ok "" "pr=$pr_num outcome=$outcome"

  if [[ "$outcome" == "SUCCESS" && "$is_class1" == "1" ]]; then
    local am_rc=0
    auto_merge "$issue" "$branch" "$pr_num" || am_rc=$?
    return "$am_rc"
  fi

  gh issue edit "$issue" --add-label "$label" --remove-label in-progress >/dev/null
  log "issue #$issue -> ${label}"
  [[ "$outcome" == "SUCCESS" ]] && return 0 || return 40
}

# --- driver ----------------------------------------------------------------------------
log "v2 loop start (MAX_ITERS=$MAX_ITERS, ITER_TIMEOUT=${ITER_TIMEOUT}s, REPAIR_BUDGET=$REPAIR_BUDGET, DRY_RUN=$DRY_RUN)"
for ((i = 1; i <= MAX_ITERS; i++)); do
  rc=0
  iterate "$i" || rc=$?
  phase DONE end "" "rc=$rc"
  case "$rc" in
    0)  log "iteration $i done (SUCCESS — auto-merged, or PR -> needs-review)";;
    40) log "iteration $i done (FAIL terminal -> needs-human, PR open for audit)";;
    10) log "manual STOP.md — exiting"; exit 0;;
    11) log "no actionable issue — idle, exiting"; exit 0;;
    20) log "dry run reached its stop point — exiting"; exit 0;;
    30) log "iteration $i produced nothing — exiting for inspection"; exit 1;;
    50) log "infra fault — exiting for inspection (issue stays in-progress)"
        notify "harness: infra fault — loop exited rc=50 for inspection (issue stays in-progress)"
        exit 50;;
    *)  log "iteration $i failed rc=$rc — exiting for inspection"; exit "$rc";;
  esac
done
log "hit MAX_ITERS=$MAX_ITERS — exiting"
