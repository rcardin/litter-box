#!/usr/bin/env bash
#
# v0 Ralph loop — probe build of the autonomous loop harness.
# Scope (intentionally thin, see docs/autonomous-loop-harness.md "Build sequence"):
#   bash state machine + compile/test gate + STOP-AT-PR.
# NOT in v0: independent reviewer (v1), Testcontainers gate (v2), GitHub Actions /
#            branch protection (v3), auto-merge (v4), bounded self-repair, tamper check,
#            convention lint, rebase-and-rerun.
#
# The loop never lets the model choose what to work on: bash resolves all state with `gh`
# queries and dispatches ONE narrow `claude -p` task per iteration with a fresh context.
# It stops at an open PR labelled `needs-review`; a human is the gate in v0.
#
# Usage:   harness/loop.sh
# Env:     MAX_ITERS      hard iteration cap                 (default 1 — v0 probe = one US)
#          ITER_TIMEOUT   per-iteration claude -p timeout, s (default 1800)
#          DRY_RUN        1 = stop before invoking claude -p and before any push/PR
set -euo pipefail

# --- locate repo root (script lives in harness/) ---------------------------------------
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

MAX_ITERS="${MAX_ITERS:-1}"
ITER_TIMEOUT="${ITER_TIMEOUT:-1800}"   # per-iteration claude -p budget, seconds
GATE_TIMEOUT="${GATE_TIMEOUT:-900}"    # per-gate sbt compile+test budget, seconds
DRY_RUN="${DRY_RUN:-0}"
PROMPT_TEMPLATE="$SCRIPT_DIR/iterate-prompt.md"
LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"

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
[[ -f "$PROMPT_TEMPLATE" ]] || die "missing prompt template: $PROMPT_TEMPLATE"

# --- the gate: compile under -Werror, then full test suite -----------------------------
# Returns 0 = green, non-zero = red. -Werror is already in build.sbt scalacOptions.
# NOTE: v0 runs the FULL suite incl. the Testcontainers IT (needs Docker, set above). The
# design wants v0 in-memory-only with the IT split out at v2; that split is deferred — for a
# single human-watched probe with a verified GREEN baseline, running the IT once is harmless.
run_gate() {
  local logfile="$1"
  log "gate: sbt compile + test (timeout ${GATE_TIMEOUT}s) -> $logfile"
  local g="sbt -batch -no-colors compile test"
  [[ -n "$TIMEOUT_BIN" ]] && g="$TIMEOUT_BIN $GATE_TIMEOUT $g"
  if $g >"$logfile" 2>&1; then
    return 0
  fi
  return 1
}

# --- one iteration ---------------------------------------------------------------------
iterate() {
  local n="$1"

  # Guards --------------------------------------------------------------------
  [[ -f "$REPO_ROOT/STOP.md" ]] && { log "STOP.md present — exiting"; return 10; }

  # Pick US (deterministic, no LLM): resume an in-progress one, else next ready.
  local issue
  issue="$(gh issue list --state open --label in-progress --json number --jq '.[0].number')"
  if [[ -z "$issue" ]]; then
    issue="$(gh issue list --state open --label ready --json number,createdAt \
              --jq 'sort_by(.createdAt) | .[0].number')"
  fi
  if [[ -z "$issue" ]]; then
    log "no in-progress or ready issue — writing STOP.md"
    printf '# STOP\n\nNo `ready` issues left for the loop at %s.\n' "$(date -u +%FT%TZ)" \
      >"$REPO_ROOT/STOP.md"
    return 10
  fi
  log "iteration $n -> issue #$issue"

  # Render the narrow prompt with the issue body injected (read-only). Body goes via a file
  # because it is multiline (awk -v mangles embedded newlines); splice it at {{ISSUE}}.
  local body_file prompt
  body_file="$LOG_DIR/issue-${issue}.body.md"
  gh issue view "$issue" --json title,body \
    --jq '"# " + (.title) + "\n\n" + .body' >"$body_file"
  prompt="$(awk -v f="$body_file" '
    /\{\{ISSUE\}\}/ { while ((getline line < f) > 0) print line; close(f); next }
    { print }
  ' "$PROMPT_TEMPLATE")"

  # Dry run stops here — before ANY git/label mutation, so it is truly read-only.
  if [[ "$DRY_RUN" == "1" ]]; then
    printf '%s\n' "$prompt" >"$LOG_DIR/issue-${issue}.prompt.txt"
    log "DRY_RUN=1 — rendered prompt for #$issue -> $LOG_DIR/issue-${issue}.prompt.txt; no mutation; stopping"
    return 20
  fi

  # Require a clean tree on a fresh branch off main. Serial loop: one US at a time.
  [[ -z "$(git status --porcelain)" ]] || die "working tree not clean — refusing to start"
  git fetch --quiet origin main || true
  local branch="us-${issue}"
  if git show-ref --verify --quiet "refs/heads/$branch"; then
    log "branch $branch exists — checking it out"
    git checkout --quiet "$branch"
  else
    git checkout --quiet -b "$branch" origin/main 2>/dev/null \
      || git checkout --quiet -b "$branch"
  fi

  # Mark in-progress so a crashed run resumes the same US next tick.
  gh issue edit "$issue" --add-label in-progress --remove-label ready >/dev/null

  # Dispatch ONE fresh claude -p task. No self-repair in v0 — observe the raw outcome.
  # stream-json (+ --verbose, required in print mode) emits one JSONL event per turn as the
  # agent works, instead of a single blob at exit — so a human can `tail -f` it live. Bash
  # only reads the exit code, never the stream (the raw outcome stays the signal). Follow
  # nicely with: harness/tail-claude.sh
  local claude_log="$LOG_DIR/issue-${issue}-iter${n}.claude.log"
  log "dispatching claude -p (timeout ${ITER_TIMEOUT}s) -> $claude_log  [tail: harness/tail-claude.sh]"
  local rc=0
  local claude_flags=(-p "$prompt" --output-format stream-json --verbose --dangerously-skip-permissions)
  if [[ -n "$TIMEOUT_BIN" ]]; then
    "$TIMEOUT_BIN" "$ITER_TIMEOUT" claude "${claude_flags[@]}" >"$claude_log" 2>&1 || rc=$?
  else
    claude "${claude_flags[@]}" >"$claude_log" 2>&1 || rc=$?
  fi
  [[ "$rc" == "124" ]] && log "WARNING: claude -p hit the ${ITER_TIMEOUT}s timeout"
  log "claude -p exited rc=$rc"

  # Gate.
  local gate_log="$LOG_DIR/issue-${issue}-iter${n}.gate.log"
  local gate_status
  if run_gate "$gate_log"; then
    gate_status="GREEN"
    log "gate GREEN"
  else
    gate_status="RED"
    log "gate RED (see $gate_log)"
  fi

  # Stop-at-PR: commit whatever the iteration produced, push, open a PR for a human.
  # v0 opens a PR even on a RED gate — the human is the reviewer/merge gate.
  if [[ -z "$(git status --porcelain)" ]]; then
    log "no changes produced by the iteration — leaving issue in-progress, not opening a PR"
    return 30
  fi
  git add -A
  git commit --quiet -m "feat(US-${issue}): autonomous iteration — gate ${gate_status}

Refs #${issue}. Loop iteration ${n}. Gate: ${gate_status}.
This commit was produced by an unattended claude -p iteration (harness v0).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"

  git push --quiet -u origin "$branch"
  local pr_body
  pr_body="$(printf 'Autonomous harness (v0) iteration %s for #%s.\n\n**Gate: %s** — see harness/logs.\n\nv0 stops at PR: no reviewer, no CI, no auto-merge. A human reviews and merges.\n\nCloses #%s' \
              "$n" "$issue" "$gate_status" "$issue")"
  gh pr create --base main --head "$branch" \
    --title "US-${issue}: autonomous iteration (gate ${gate_status})" \
    --body "$pr_body" >/dev/null
  gh issue edit "$issue" --add-label needs-review --remove-label in-progress >/dev/null
  log "PR opened for #$issue (gate ${gate_status}); issue -> needs-review"
  return 0
}

# --- driver ----------------------------------------------------------------------------
log "v0 loop start (MAX_ITERS=$MAX_ITERS, ITER_TIMEOUT=${ITER_TIMEOUT}s, DRY_RUN=$DRY_RUN)"
for ((i = 1; i <= MAX_ITERS; i++)); do
  rc=0
  iterate "$i" || rc=$?
  case "$rc" in
    0)  log "iteration $i done (PR opened)";;
    10) log "loop exhausted/stopped — exiting"; exit 0;;
    20) log "dry run reached its stop point — exiting"; exit 0;;
    30) log "iteration $i produced nothing — exiting for inspection"; exit 1;;
    *)  log "iteration $i failed rc=$rc — exiting for inspection"; exit "$rc";;
  esac
done
log "hit MAX_ITERS=$MAX_ITERS — exiting"
