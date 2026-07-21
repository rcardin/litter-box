#!/usr/bin/env bash
#
# Ralph loop entry point — a thin shim over the Scala state machine in harness/scala.
#
# The loop itself is `harness/scala` (Domain/Machine/Caps/Live/Main), ported from the 944-line
# bash machine in slices #44-#47; see
# docs/superpowers/specs/2026-07-19-harness-scala-rewrite-design.md. This file exists for three
# reasons and holds no logic of its own:
#
#   1. Invocation compatibility: `harness/loop.sh` is the documented entry point, is what
#      operators and every doc reference type, and is what harness/watch.sh describes.
#   2. Repo-root marker: Main.resolveRepoRoot walks up from the cwd looking for `harness/loop.sh`,
#      the same invariant bash derived from its own $BASH_SOURCE. This file IS that marker.
#   3. Run-from-anywhere: bash got that free from $BASH_SOURCE; the cd below restores it, so
#      the harness works from any subdirectory exactly as it always did.
#
# Env interface and exit codes are unchanged; every variable below is read by harness/scala's
# Main.parseEnv, not here. Caller arguments are forwarded past `--` so scala-cli never claims
# them; the loop consumes none, by design (it never lets the model or the operator choose what to
# work on; all state is resolved via `gh`).
#
# Usage:   harness/loop.sh
# Env:     MAX_ITERS      hard cap on US count               (default 1)
#          ITER_TIMEOUT   per-dispatch agent timeout, s      (default 1800)
#          GATE_TIMEOUT   per-fast-gate sbt budget, s        (default 900)
#          DRY_RUN        1 = stop before invoking claude -p and before any push/PR
#          REPAIR_BUDGET  shared fix budget per US           (default 2)
#          MAX_PATCH_BYTES size cap on an extracted patch, B (default 1000000)
#          JAVA_HOME_PINNED  JDK 25 home stamped onto every child process
#                            (default $HOME/.sdkman/candidates/java/25.0.2-open)
#          CLAUDE_CODE_OAUTH_TOKEN (preferred) or ANTHROPIC_API_KEY: the dedicated, spend-capped
#                            Claude credential handed to the containerized worker/fixer. At least
#                            one is REQUIRED at startup; without either the loop dies.
#          -- test seams (default to the real thing; overridden by the state-machine test) --
#          GATE_CMD       the fast (src/test) gate command   (default: harness/sandbox/run-fast-gate.sh,
#                                                             a containerized `sbt compile test`)
#          IMPL_CMD       stub for the worker dispatch       (default: real sandboxed claude -p)
#          FIX_CMD        stub for the fix dispatch          (default: real sandboxed claude -p)
#          REVIEW_CMD     stub for the reviewer dispatch     (default: real sandboxed claude -p)
#          NTFY_TOPIC     ntfy.sh topic for push notifications (unset = log-only)
#          NOTIFY_CMD     test seam: replaces the ntfy call
#          CI_WAIT_TIMEOUT    bound on the required-CI wait, s  (default 900)
#          CI_WAIT_CMD    test seam for the CI wait            (default: gh pr checks --watch)
#          CI_APPEAR_TIMEOUT  bound on a check REGISTERING, s   (default 300)
#          CI_APPEAR_INTERVAL poll period while it registers, s (default 10)
#          CI_APPEAR_CMD  test seam: prints the rollup size     (default: gh pr view --json ...)
#          MERGE_CMD      test seam for the merge              (default: gh pr merge --squash)
#
# Exit:    0 success (auto-merged, or PR -> needs-review) | 1 nothing staged / fatal preflight
#          50 infra fault (no repair budget spent, issue stays in-progress)
#          A manual STOP.md, an idle tick and a DRY_RUN stop all exit 0.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

command -v scala-cli >/dev/null || {
  printf '[loop] FATAL: scala-cli not found — the harness loop is a Scala program (harness/scala)\n' >&2
  exit 1
}

# The JDK the loop runs under is pinned by harness/scala/project.scala (`//> using jvm temurin:25`),
# which scala-cli provisions itself; JAVA_HOME_PINNED is about the CHILDREN the loop forks and is
# read inside Main. Nothing to pin here.
#
# `exec` so the loop is this process: signals, exit code and the terminal all belong to it directly,
# with no bash wrapper left to swallow them.
exec scala-cli run "$SCRIPT_DIR/scala" -- "$@"
