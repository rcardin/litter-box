#!/usr/bin/env bash
#
# v1 Ralph loop — independent reviewer + tamper check + bounded self-repair.
# Implements step v1 of docs/autonomous-loop-harness.md and
# docs/superpowers/specs/2026-07-01-harness-v1-reviewer-design.md.
#
# Adds over v0:
#   - bounded self-repair: a shared budget of 2 fix iterations per US, spent by EITHER a
#     RED gate OR a reviewer REQUEST_CHANGES.
#   - test-tamper check: diff the test tree vs origin/main and surface it to the reviewer.
#   - cold independent reviewer: a separate, fresh `claude -p` that sees only the diff, the
#     acceptance criteria, CONTEXT.md, and the tamper report, and emits a VERDICT sentinel.
#   - `needs-human` terminal: budget exhaustion (RED or REQUEST_CHANGES) still opens a PR for
#     the audit trail but flips the issue to needs-human instead of needs-review.
#
# Still NOT in v1 (see the spec's "deliberately excludes"): Testcontainers gate split (v2),
# GitHub Actions / branch protection (v3), auto-merge (v4), convention-lint gate step,
# rebase-on-main-and-rerun. v1 STILL STOPS AT PR — a human merges.
#
# The loop never lets the model choose what to work on: bash resolves all state with `gh`
# queries and dispatches narrow, fresh `claude -p` tasks. Every dispatch is fresh context.
#
# Usage:   harness/loop.sh
# Env:     MAX_ITERS      hard cap on US count               (default 1)
#          ITER_TIMEOUT   per-dispatch claude -p timeout, s  (default 1800)
#          GATE_TIMEOUT   per-gate sbt budget, s             (default 900)
#          DRY_RUN        1 = stop before invoking claude -p and before any push/PR
#          REPAIR_BUDGET  shared fix budget per US           (default 2)
#          -- test seams (default to the real thing; overridden by the state-machine test) --
#          GATE_CMD       the gate command                   (default sbt compile test)
#          IMPL_CMD       stub for the worker dispatch       (default: real claude -p)
#          FIX_CMD        stub for the fix dispatch          (default: real claude -p)
#          REVIEW_CMD     stub for the reviewer dispatch     (default: real claude -p)
set -euo pipefail

# --- locate repo root (script lives in harness/) ---------------------------------------
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

MAX_ITERS="${MAX_ITERS:-1}"
ITER_TIMEOUT="${ITER_TIMEOUT:-1800}"   # per-dispatch claude -p budget, seconds
GATE_TIMEOUT="${GATE_TIMEOUT:-900}"    # per-gate sbt compile+test budget, seconds
DRY_RUN="${DRY_RUN:-0}"
REPAIR_BUDGET="${REPAIR_BUDGET:-2}"    # shared across gate-RED and REQUEST_CHANGES per US
ITERATE_PROMPT="$SCRIPT_DIR/iterate-prompt.md"
FIX_PROMPT="$SCRIPT_DIR/fix-prompt.md"
REVIEW_PROMPT="$SCRIPT_DIR/review-prompt.md"
CONVENTIONS="$REPO_ROOT/CONTEXT.md"
LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"

# Test seams: default empty -> real `claude -p`; the state-machine test overrides them with
# deterministic stubs so it can force RED / REQUEST_CHANGES / budget exhaustion for free.
GATE_CMD="${GATE_CMD:-sbt -batch -no-colors compile test}"
IMPL_CMD="${IMPL_CMD:-}"
FIX_CMD="${FIX_CMD:-}"
REVIEW_CMD="${REVIEW_CMD:-}"

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

# --- the gate: compile under -Werror, then full test suite -----------------------------
# Returns 0 = green, non-zero = red. -Werror is already in build.sbt scalacOptions.
# NOTE: v1 still runs the FULL suite incl. the Testcontainers IT (needs Docker, set above);
# the in-memory/IT split is deferred to v2 (see spec). GATE_CMD is a test seam.
run_gate() {
  local logfile="$1"
  log "gate: $GATE_CMD (timeout ${GATE_TIMEOUT}s) -> $logfile"
  local g="$GATE_CMD"
  [[ -n "$TIMEOUT_BIN" ]] && g="$TIMEOUT_BIN $GATE_TIMEOUT $g"
  if $g >"$logfile" 2>&1; then
    return 0
  fi
  return 1
}

# --- claude dispatch (worker / fixer) --------------------------------------------------
# Writes a stream-json JSONL log (one event per turn) so harness/tail-claude.sh can follow it
# live; bash reads only the exit code. Honours the IMPL_CMD / FIX_CMD seams (a stub command
# that ignores the prompt and simulates the agent).
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
  [[ "$rc" == "124" ]] && log "WARNING: $role claude -p hit the ${ITER_TIMEOUT}s timeout"
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
  [[ "$rc" == "124" ]] && log "WARNING: REVIEWER claude -p hit the ${ITER_TIMEOUT}s timeout"
  log "REVIEWER claude -p exited rc=$rc"
  return 0
}

# --- test-tamper check -----------------------------------------------------------------
# Diff the (staged) test tree against origin/main. All tests live under src/test (verified).
# Bash does NOT block on tamper — it surfaces the raw numstat plus a one-line summary to the
# reviewer, who is the judgment. numstat rows are: added<TAB>deleted<TAB>path ('-' = binary).
tamper_report() {
  local out="$1" raw touched=0 net_del=0 add del path
  raw="$(git diff --cached --numstat origin/main -- src/test || true)"
  if [[ -n "$raw" ]]; then
    while IFS=$'\t' read -r add del path; do
      [[ -z "$path" ]] && continue
      touched=$((touched + 1))
      [[ "$add" == "-" ]] && continue                # binary — no line counts
      (( del > add )) && net_del=$((net_del + 1))    # net deletions on this test file
    done <<< "$raw"
  fi
  {
    printf '# Test-tamper report (git diff --numstat origin/main -- src/test)\n\n'
    printf '**Summary: %s test file(s) touched, %s with net deletions.**\n\n' "$touched" "$net_del"
    printf 'Raw numstat (added  deleted  path; a deleted file shows all lines as deletions):\n\n'
    if [[ -n "$raw" ]]; then
      printf '```\n%s\n```\n' "$raw"
    else
      printf '(no test files changed vs origin/main)\n'
    fi
  } >"$out"
}

# --- one US, start to terminal ---------------------------------------------------------
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

  # Render the worker prompt with the issue body injected (read-only).
  local body_file worker_prompt_file
  body_file="$LOG_DIR/issue-${issue}.body.md"
  gh issue view "$issue" --json title,body \
    --jq '"# " + (.title) + "\n\n" + .body' >"$body_file"
  worker_prompt_file="$LOG_DIR/issue-${issue}.prompt.txt"
  render_template "$ITERATE_PROMPT" "$worker_prompt_file" ISSUE "$body_file"

  # Dry run stops here — before ANY git/label mutation, so it is truly read-only.
  if [[ "$DRY_RUN" == "1" ]]; then
    log "DRY_RUN=1 — rendered worker prompt for #$issue -> $worker_prompt_file; no mutation; stopping"
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

  # Initial worker dispatch (fresh context). ----------------------------------
  local worker_prompt; worker_prompt="$(cat "$worker_prompt_file")"
  dispatch_worker IMPL "$worker_prompt" "$LOG_DIR/issue-${issue}-iter${n}.claude.log"

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

    local gate_log="$LOG_DIR/issue-${issue}-pass${pass}.gate.log"
    if ! run_gate "$gate_log"; then
      gate_status="RED"; failure_kind="gate-RED"
      log "gate RED (pass $pass, see $gate_log)"
      if (( budget == 0 )); then outcome="FAIL"; break; fi
      budget=$((budget - 1))
      log "self-repair: budget now $budget — dispatching FIX for gate-RED"
      local fail_file="$LOG_DIR/issue-${issue}-pass${pass}.failure.md"
      {
        printf '## Gate failure — `%s` (compile under -Werror, then tests)\n\n' "$GATE_CMD"
        printf 'Tail of the gate log:\n\n```\n'
        tail -n 200 "$gate_log"
        printf '\n```\n'
      } >"$fail_file"
      local fix_prompt_file="$LOG_DIR/issue-${issue}-pass${pass}.fix.prompt.txt"
      render_template "$FIX_PROMPT" "$fix_prompt_file" ISSUE "$body_file" FAILURE "$fail_file"
      dispatch_worker FIX "$(cat "$fix_prompt_file")" "$LOG_DIR/issue-${issue}-pass${pass}.fix.claude.log"
      continue
    fi

    gate_status="GREEN"
    log "gate GREEN (pass $pass) — running tamper check + cold reviewer"

    # Tamper check feeds the reviewer (bash surfaces, does not block).
    local tamper_file="$LOG_DIR/issue-${issue}-tamper.md"
    tamper_report "$tamper_file"

    # Full diff for the reviewer (staged, so new files are included).
    local diff_file="$LOG_DIR/issue-${issue}-diff.patch"
    git diff --cached origin/main >"$diff_file"

    local review_prompt_file="$LOG_DIR/issue-${issue}-pass${pass}.review.prompt.txt"
    render_template "$REVIEW_PROMPT" "$review_prompt_file" \
      ISSUE "$body_file" CONVENTIONS "$CONVENTIONS" TAMPER "$tamper_file" DIFF "$diff_file"
    dispatch_review "$(cat "$review_prompt_file")" "$review_file"
    reviewed=1

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
    dispatch_worker FIX "$(cat "$fix_prompt_file")" "$LOG_DIR/issue-${issue}-pass${pass}.fix.claude.log"
    continue
  done

  # --- terminal: commit, push, PR (SUCCESS -> needs-review, FAIL -> needs-human) --------
  git add -A
  if git diff --cached --quiet HEAD; then
    log "nothing staged at terminal — unexpected; leaving in-progress"
    return 30
  fi

  local label commit_tag pr_note
  if [[ "$outcome" == "SUCCESS" ]]; then
    label="needs-review"
    commit_tag="reviewer APPROVE, gate ${gate_status}"
    pr_note="**Reviewer: APPROVE** · gate ${gate_status}. The cold independent reviewer approved; a human still merges (v1 stops at PR)."
  else
    label="needs-human"
    commit_tag="self-repair budget exhausted (${failure_kind}), gate ${gate_status}"
    pr_note="**Needs human** — self-repair budget of ${REPAIR_BUDGET} exhausted on ${failure_kind} (last gate ${gate_status}). Opened for the audit trail; do NOT merge without review."
  fi

  git commit --quiet -m "feat(US-${issue}): autonomous iteration — ${commit_tag}

Refs #${issue}. Loop iteration ${n}, ${pass} gate pass(es). Outcome: ${outcome}.
This commit was produced by an unattended claude -p iteration (harness v1).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"

  git push --quiet -u origin "$branch"

  local pr_body_file; pr_body_file="$LOG_DIR/issue-${issue}.pr-body.md"
  {
    printf 'Autonomous harness (v1) iteration %s for #%s.\n\n' "$n" "$issue"
    printf '%s\n\n' "$pr_note"
    if (( reviewed )); then
      printf '<details><summary>Independent reviewer output</summary>\n\n```\n'
      cat "$review_file"
      printf '\n```\n\n</details>\n\n'
    fi
    printf 'v1 stops at PR: no CI, no auto-merge. A human reviews and merges.\n\n'
    printf 'Closes #%s\n' "$issue"
  } >"$pr_body_file"

  gh pr create --base main --head "$branch" \
    --title "US-${issue}: autonomous iteration (${outcome}, gate ${gate_status})" \
    --body-file "$pr_body_file" >/dev/null
  gh issue edit "$issue" --add-label "$label" --remove-label in-progress >/dev/null
  log "PR opened for #$issue (outcome ${outcome}); issue -> ${label}"
  [[ "$outcome" == "SUCCESS" ]] && return 0 || return 40
}

# --- driver ----------------------------------------------------------------------------
log "v1 loop start (MAX_ITERS=$MAX_ITERS, ITER_TIMEOUT=${ITER_TIMEOUT}s, REPAIR_BUDGET=$REPAIR_BUDGET, DRY_RUN=$DRY_RUN)"
for ((i = 1; i <= MAX_ITERS; i++)); do
  rc=0
  iterate "$i" || rc=$?
  case "$rc" in
    0)  log "iteration $i done (APPROVE, PR opened -> needs-review)";;
    40) log "iteration $i done (budget exhausted, PR opened -> needs-human)";;
    10) log "loop exhausted/stopped — exiting"; exit 0;;
    20) log "dry run reached its stop point — exiting"; exit 0;;
    30) log "iteration $i produced nothing — exiting for inspection"; exit 1;;
    *)  log "iteration $i failed rc=$rc — exiting for inspection"; exit "$rc";;
  esac
done
log "hit MAX_ITERS=$MAX_ITERS — exiting"
