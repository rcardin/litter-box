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
# v2 splits the single gate into two tiers by source directory (src/test = in-memory,
# Docker-free; src/it = Testcontainers real-PG):
#   - FAST gate (GATE_CMD, `sbt compile test`) runs only src/test — no Docker on every iter.
#   - IT gate (IT_GATE_CMD, `sbt It/test`) runs only src/it and fires AFTER fast-GREEN and
#     BEFORE the reviewer, so the reviewer only ever judges a unit+IT-green diff.
#   - fast-RED short-circuits the IT gate (Docker never paid for a unit-failing iter).
#   - the repair budget stays SHARED and stays 2: fast-RED, IT-RED, and REQUEST_CHANGES all
#     draw from the same pool. The tamper diff now also covers src/it.
#
# v3 (GitHub Actions CI `build` check + branch protection on main) shipped OUTSIDE this
# script, followed by a hardening pass: infra-fault terminal rc 50 (timeouts and empty
# reviewer output exit for inspection WITHOUT spending the repair budget), a docker
# preflight before the real IT gate, protected-path enforcement in bash (harness/, docs/,
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
# Still NOT here (deferred): convention-lint gate step, auto-merge for class-2/3, cost cap
# in dollars (MAX_ITERS is the ceiling), container isolation.
#
# The loop never lets the model choose what to work on: bash resolves all state with `gh`
# queries and dispatches narrow, fresh `claude -p` tasks. Every dispatch is fresh context.
#
# Usage:   harness/loop.sh
# Env:     MAX_ITERS      hard cap on US count               (default 1)
#          ITER_TIMEOUT   per-dispatch claude -p timeout, s  (default 1800)
#          GATE_TIMEOUT   per-fast-gate sbt budget, s        (default 900)
#          IT_GATE_TIMEOUT per-IT-gate sbt budget, s         (default 1200 — container startup)
#          DRY_RUN        1 = stop before invoking claude -p and before any push/PR
#          REPAIR_BUDGET  shared fix budget per US           (default 2)
#          -- test seams (default to the real thing; overridden by the state-machine test) --
#          GATE_CMD       the fast (src/test) gate command   (default sbt compile test)
#          IT_GATE_CMD    the IT (src/it) gate command       (default sbt It/test)
#          IMPL_CMD       stub for the worker dispatch       (default: real claude -p)
#          FIX_CMD        stub for the fix dispatch          (default: real claude -p)
#          REVIEW_CMD     stub for the reviewer dispatch     (default: real claude -p)
#          NTFY_TOPIC     ntfy.sh topic for push notifications (unset = log-only)
#          NOTIFY_CMD     test seam: eval'd with $msg in scope, replaces the ntfy call
#          CI_WAIT_TIMEOUT  bound on the required-CI wait, s   (default 900)
#          CI_WAIT_CMD    test seam for the CI wait            (default: gh pr checks --watch)
#          MERGE_CMD      test seam for the merge              (default: gh pr merge --squash)
set -euo pipefail

# --- locate repo root (script lives in harness/) ---------------------------------------
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

MAX_ITERS="${MAX_ITERS:-1}"
ITER_TIMEOUT="${ITER_TIMEOUT:-1800}"   # per-dispatch claude -p budget, seconds
GATE_TIMEOUT="${GATE_TIMEOUT:-900}"    # per-fast-gate sbt compile+test budget, seconds
IT_GATE_TIMEOUT="${IT_GATE_TIMEOUT:-1200}"  # per-IT-gate budget, seconds (Docker container startup)
DRY_RUN="${DRY_RUN:-0}"
REPAIR_BUDGET="${REPAIR_BUDGET:-2}"    # shared across gate-RED and REQUEST_CHANGES per US
CI_WAIT_TIMEOUT="${CI_WAIT_TIMEOUT:-900}"   # bound on waiting for the required CI check, seconds
ITERATE_PROMPT="$SCRIPT_DIR/iterate-prompt.md"
FIX_PROMPT="$SCRIPT_DIR/fix-prompt.md"
REVIEW_PROMPT="$SCRIPT_DIR/review-prompt.md"
CONVENTIONS="$REPO_ROOT/CONTEXT.md"
LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"

# Test seams: default empty -> real `claude -p`; the state-machine test overrides them with
# deterministic stubs so it can force RED / REQUEST_CHANGES / budget exhaustion for free.
# Capture whether the IT gate seam was overridden BEFORE applying the default: the docker
# preflight below only applies to the real `sbt It/test` gate, never to test stubs.
IT_GATE_OVERRIDDEN=0
if [[ -n "${IT_GATE_CMD:-}" ]]; then IT_GATE_OVERRIDDEN=1; fi
GATE_CMD="${GATE_CMD:-sbt -batch -no-colors compile test}"
IT_GATE_CMD="${IT_GATE_CMD:-sbt -batch -no-colors It/test}"
IMPL_CMD="${IMPL_CMD:-}"
FIX_CMD="${FIX_CMD:-}"
REVIEW_CMD="${REVIEW_CMD:-}"
CI_WAIT_CMD="${CI_WAIT_CMD:-}"   # test seam; default (empty) -> gh pr checks <pr> --watch --fail-fast
MERGE_CMD="${MERGE_CMD:-}"       # test seam; default (empty) -> gh pr merge <pr> --squash --delete-branch

log() { printf '[loop %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "FATAL: $*"; exit 1; }

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
# Testcontainers under colima needs the docker socket pointed explicitly (see memory note).
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.colima/default/docker.sock}"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-/var/run/docker.sock}"
export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"

command -v gh    >/dev/null || die "gh not found"
command -v sbt   >/dev/null || die "sbt not found"
command -v claude >/dev/null || die "claude not found"
# Docker preflight: the REAL IT gate (Testcontainers) needs a reachable docker daemon
# (colima). Failing it mid-run would look like a RED gate and burn repair budget on an
# infra problem, so refuse to start instead. Skipped when the seam is overridden (tests).
if [[ "$IT_GATE_OVERRIDDEN" == "0" ]]; then
  docker info >/dev/null 2>&1 || die "docker unreachable (colima running?) — IT gate would fail for infra reasons"
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
# Parametrised over (label, command, timeout, logfile) so both tiers share the timeout-
# wrapping logic: the FAST tier (GATE_CMD, src/test — Docker-free after the v2 split) and
# the IT tier (IT_GATE_CMD, src/it — real PG via Testcontainers). Both are test seams.
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
# Real path: returns 124 when claude -p hits the timeout (infra fault — the caller must not
# let a half-finished worker fall through to the gates), 0 otherwise. Stub overrides keep
# the always-return-0 behavior: they simulate the agent, their rc is meaningless.
dispatch_worker() {
  local role="$1" prompt="$2" logf="$3" override="" rc=0
  case "$role" in
    IMPL) override="$IMPL_CMD";;
    FIX)  override="$FIX_CMD";;
  esac
  log "dispatching $role claude -p -> $logf"
  if [[ -n "$override" ]]; then
    ( eval "$override" ) >"$logf" 2>&1 || rc=$?
    log "$role stub exited rc=$rc"
    return 0
  fi
  local args=(-p "$prompt" --dangerously-skip-permissions --output-format stream-json --verbose)
  if [[ -n "$TIMEOUT_BIN" ]]; then
    "$TIMEOUT_BIN" "$ITER_TIMEOUT" claude "${args[@]}" >"$logf" 2>&1 || rc=$?
  else
    claude "${args[@]}" >"$logf" 2>&1 || rc=$?
  fi
  if [[ "$rc" == "124" ]]; then
    log "WARNING: $role claude -p hit the ${ITER_TIMEOUT}s timeout"
    return 124
  fi
  log "$role claude -p exited rc=$rc"
  return 0
}

# --- cold reviewer dispatch ------------------------------------------------------------
# Unlike the worker, the reviewer's STDOUT is the product: `claude -p` default output is
# "text" (verified) — the final assistant message, whose last line is the VERDICT sentinel.
# We capture stdout to the review file and stderr separately. Honours the REVIEW_CMD seam.
#
# Independence is enforced by CONSTRUCTION, not just by the prompt: the reviewer needs no
# tools (the diff, AC, CONTEXT.md and tamper report are all spliced into its prompt), so all
# mutating tools are denied. It cannot touch the working tree it is judging. Read stays
# allowed for extra context. This is the "cold independent reviewer" the gate stack needs.
REVIEWER_DENY=(--disallowed-tools Edit Write MultiEdit NotebookEdit Bash)
dispatch_review() {
  local prompt="$1" review_file="$2" rc=0
  log "dispatching REVIEWER claude -p (cold, no mutating tools) -> $review_file"
  if [[ -n "$REVIEW_CMD" ]]; then
    ( eval "$REVIEW_CMD" ) >"$review_file" 2>"$review_file.stderr" || rc=$?
    log "REVIEWER stub exited rc=$rc"
    return 0
  fi
  if [[ -n "$TIMEOUT_BIN" ]]; then
    "$TIMEOUT_BIN" "$ITER_TIMEOUT" \
      claude -p "$prompt" --dangerously-skip-permissions "${REVIEWER_DENY[@]}" >"$review_file" 2>"$review_file.stderr" || rc=$?
  else
    claude -p "$prompt" --dangerously-skip-permissions "${REVIEWER_DENY[@]}" >"$review_file" 2>"$review_file.stderr" || rc=$?
  fi
  if [[ "$rc" == "124" ]]; then
    log "WARNING: REVIEWER claude -p hit the ${ITER_TIMEOUT}s timeout"
    return 124
  fi
  log "REVIEWER claude -p exited rc=$rc"
  return 0
}

# --- test-tamper check -----------------------------------------------------------------
# Diff the (staged) test tree against origin/main. All tests live under src/test (in-memory)
# or src/it (Testcontainers IT); v2 covers both so a repair iter cannot gut the IT to go green
# without the reviewer seeing it. Bash does NOT block on tamper — it surfaces the raw numstat
# plus a one-line summary to the reviewer, who is the judgment. numstat rows are:
# added<TAB>deleted<TAB>path ('-' = binary).
tamper_report() {
  local out="$1" raw touched=0 net_del=0 add del path
  raw="$(git diff --cached --numstat origin/main -- src/test src/it || true)"
  if [[ -n "$raw" ]]; then
    while IFS=$'\t' read -r add del path; do
      [[ -z "$path" ]] && continue
      touched=$((touched + 1))
      [[ "$add" == "-" ]] && continue                # binary — no line counts
      (( del > add )) && net_del=$((net_del + 1))    # net deletions on this test file
    done <<< "$raw"
  fi
  {
    printf '# Test-tamper report (git diff --numstat origin/main -- src/test src/it)\n\n'
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
auto_merge() {
  local issue="$1" branch="$2" pr_num="$3"
  local ci_cmd="${CI_WAIT_CMD:-gh pr checks $pr_num --watch --fail-fast}"
  local ci_log="$LOG_DIR/issue-${issue}.ci-wait.log"
  local ci_rc=0
  run_gate "CI-WAIT" "$ci_cmd" "$CI_WAIT_TIMEOUT" "$ci_log" || ci_rc=$?
  if (( ci_rc == 124 )); then
    log "CI wait hit the ${CI_WAIT_TIMEOUT}s bound — infra fault; PR open, issue stays in-progress"
    return 50
  fi
  if (( ci_rc != 0 )); then
    log "CI RED on PR #$pr_num after local gates green — needs-human, no merge, no self-repair"
    gh pr comment "$pr_num" --body "CI red after local gates were green. The loop never self-repairs against the independent check (v3 hands-off rule) — a human must look." >/dev/null 2>&1 || true
    gh issue edit "$issue" --add-label needs-human --remove-label in-progress >/dev/null 2>&1 \
      || log "WARNING: could not flip #$issue to needs-human (flip by hand)"
    notify "harness: #${issue} CI RED -> needs-human (PR #${pr_num})"
    return 40
  fi
  local merge_cmd="${MERGE_CMD:-gh pr merge $pr_num --squash --delete-branch}"
  log "CI green — merging PR #$pr_num"
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
  gh issue edit "$issue" --remove-label in-progress >/dev/null 2>&1 || true
  flip_blocked "$issue"
  git fetch --quiet origin main || log "post-merge fetch failed (next iteration re-fetches anyway)"
  notify "harness: #${issue} auto-merged (PR #${pr_num}, CI green, reviewer APPROVE)"
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

  # Initial worker dispatch (fresh context). ----------------------------------
  local worker_prompt impl_rc=0; worker_prompt="$(cat "$worker_prompt_file")"
  dispatch_worker IMPL "$worker_prompt" "$LOG_DIR/issue-${issue}-iter${n}.claude.log" || impl_rc=$?
  if (( impl_rc == 124 )); then
    log "IMPL worker timed out — infra fault; a half-finished worker must not reach the gates"
    return 50
  fi

  # Nothing produced at all → same v0 semantics: leave in-progress, no PR.
  git add -A
  if git diff --cached --quiet origin/main; then
    log "no changes produced by the iteration — leaving issue in-progress, not opening a PR"
    return 30
  fi

  # --- bounded self-repair loop --------------------------------------------------------
  # budget is shared: each RED or REQUEST_CHANGES that is not the last one spends one unit.
  local budget="$REPAIR_BUDGET" pass=0
  local outcome="" gate_status="" verdict="" failure_kind=""
  local review_file="$LOG_DIR/issue-${issue}-review.md"
  : >"$review_file"                                   # empty until the first review
  local reviewed=0

  while :; do
    pass=$((pass + 1))
    git add -A                                        # stage so new files show in diff/gate/tamper

    # --- protected-path enforcement (bash, not the prompt) -----------------------------
    # Worker prompts forbid touching the harness, docs and control files, but the worker
    # runs with permissions skipped, so bash enforces it. No FIX dispatch: the fixer is the
    # same agent class that violated the rule. Straight to the FAIL terminal (PR opened for
    # the audit trail, label needs-human). harness/logs/ is gitignored, so the harness's
    # own log writes never trip this.
    if ! git diff --cached --quiet origin/main -- harness/ docs/ .github/ PROMPT.md CONTEXT.md STOP.md; then
      log "protected-path violation: staged diff touches harness/, docs/, .github/, PROMPT.md, CONTEXT.md or STOP.md — no FIX dispatched"
      outcome="FAIL"; failure_kind="protected-path"; gate_status="SKIPPED"
      break
    fi

    # --- FAST tier: src/test only (no Docker after the v2 split) -----------------------
    local gate_log="$LOG_DIR/issue-${issue}-pass${pass}.gate.log"
    local gate_rc=0
    run_gate "FAST" "$GATE_CMD" "$GATE_TIMEOUT" "$gate_log" || gate_rc=$?
    if (( gate_rc == 124 )); then
      log "WARNING: FAST gate hit the ${GATE_TIMEOUT}s timeout — infra fault, not a code failure"
      return 50
    fi
    if (( gate_rc != 0 )); then
      gate_status="RED"; failure_kind="gate-RED"
      log "FAST gate RED (pass $pass, see $gate_log)"
      if (( budget == 0 )); then outcome="FAIL"; break; fi
      budget=$((budget - 1))
      log "self-repair: budget now $budget — dispatching FIX for gate-RED (IT gate short-circuited)"
      local fail_file="$LOG_DIR/issue-${issue}-pass${pass}.failure.md"
      {
        printf '## Fast-gate failure — `%s` (compile under -Werror, then in-memory tests)\n\n' "$GATE_CMD"
        printf 'Tail of the fast-gate log:\n\n```\n'
        tail -n 200 "$gate_log"
        printf '\n```\n'
      } >"$fail_file"
      local fix_prompt_file="$LOG_DIR/issue-${issue}-pass${pass}.fix.prompt.txt"
      render_template "$FIX_PROMPT" "$fix_prompt_file" ISSUE "$body_file" FAILURE "$fail_file"
      local fix_rc=0
      dispatch_worker FIX "$(cat "$fix_prompt_file")" "$LOG_DIR/issue-${issue}-pass${pass}.fix.claude.log" || fix_rc=$?
      if (( fix_rc == 124 )); then
        log "FIX worker timed out — infra fault; exiting without spending further budget"
        return 50
      fi
      continue                                        # short-circuits the IT gate — no Docker paid
    fi
    log "FAST gate GREEN (pass $pass) — running IT gate (real PG)"

    # --- IT tier: src/it only (Testcontainers real PG). Only reached on fast-GREEN. ----
    local it_gate_log="$LOG_DIR/issue-${issue}-pass${pass}.it-gate.log"
    local it_gate_rc=0
    run_gate "IT" "$IT_GATE_CMD" "$IT_GATE_TIMEOUT" "$it_gate_log" || it_gate_rc=$?
    if (( it_gate_rc == 124 )); then
      log "WARNING: IT gate hit the ${IT_GATE_TIMEOUT}s timeout — infra fault, not a code failure"
      return 50
    fi
    if (( it_gate_rc != 0 )); then
      gate_status="IT-RED"; failure_kind="IT-gate-RED"
      log "IT gate RED (pass $pass, see $it_gate_log)"
      if (( budget == 0 )); then outcome="FAIL"; break; fi
      budget=$((budget - 1))
      log "self-repair: budget now $budget — dispatching FIX for IT-gate-RED"
      local fail_file="$LOG_DIR/issue-${issue}-pass${pass}.failure.md"
      {
        printf '## IT-gate failure — `%s` (Testcontainers real-Postgres integration tests)\n\n' "$IT_GATE_CMD"
        printf 'The in-memory fast tier passed; the real-PG integration tier (src/it) failed.\n\n'
        printf 'Tail of the IT-gate log:\n\n```\n'
        tail -n 200 "$it_gate_log"
        printf '\n```\n'
      } >"$fail_file"
      local fix_prompt_file="$LOG_DIR/issue-${issue}-pass${pass}.fix.prompt.txt"
      render_template "$FIX_PROMPT" "$fix_prompt_file" ISSUE "$body_file" FAILURE "$fail_file"
      local fix_rc=0
      dispatch_worker FIX "$(cat "$fix_prompt_file")" "$LOG_DIR/issue-${issue}-pass${pass}.fix.claude.log" || fix_rc=$?
      if (( fix_rc == 124 )); then
        log "FIX worker timed out — infra fault; exiting without spending further budget"
        return 50
      fi
      continue
    fi

    gate_status="GREEN"
    log "FAST + IT gates GREEN (pass $pass) — running tamper check + cold reviewer"

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
    dispatch_review "$(cat "$review_prompt_file")" "$review_file" || review_rc=$?
    if (( review_rc == 124 )); then
      log "REVIEWER timed out — infra fault; exiting without spending budget"
      return 50
    fi
    reviewed=1

    # An empty (or whitespace-only) review is a crashed/timed-out reviewer, not a verdict:
    # infra fault. Distinct from a non-empty review missing the sentinel (model misbehavior,
    # handled fail-safe below).
    if ! grep -q '[^[:space:]]' "$review_file"; then
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

    if [[ "$verdict" == "APPROVE" ]]; then
      outcome="SUCCESS"; break
    fi

    # REQUEST_CHANGES — spend from the same shared budget.
    failure_kind="REQUEST_CHANGES"
    if (( budget == 0 )); then outcome="FAIL"; break; fi
    budget=$((budget - 1))
    log "self-repair: budget now $budget — dispatching FIX for REQUEST_CHANGES"
    local fail_file="$LOG_DIR/issue-${issue}-pass${pass}.failure.md"
    {
      printf '## The independent reviewer requested changes\n\n'
      cat "$review_file"
      printf '\n\n'
      cat "$tamper_file"
    } >"$fail_file"
    local fix_prompt_file="$LOG_DIR/issue-${issue}-pass${pass}.fix.prompt.txt"
    render_template "$FIX_PROMPT" "$fix_prompt_file" ISSUE "$body_file" FAILURE "$fail_file"
    local fix_rc=0
    dispatch_worker FIX "$(cat "$fix_prompt_file")" "$LOG_DIR/issue-${issue}-pass${pass}.fix.claude.log" || fix_rc=$?
    if (( fix_rc == 124 )); then
      log "FIX worker timed out — infra fault; exiting without spending further budget"
      return 50
    fi
    continue
  done

  # --- terminal: commit, push, PR (SUCCESS -> needs-review, FAIL -> needs-human) --------
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
    pr_note="**Reviewer: APPROVE** · gate ${gate_status} (in-memory + real-PG IT tiers both green). Not class-1, so not auto-merged: a human reviews and merges."
  elif [[ "$failure_kind" == "protected-path" ]]; then
    label="needs-human"
    commit_tag="protected-path violation, gate ${gate_status}"
    pr_note="**Needs human** — the agent modified protected files (harness/, docs/, .github/, PROMPT.md, CONTEXT.md or STOP.md). Opened for the audit trail ONLY; this diff must NOT be merged."
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
