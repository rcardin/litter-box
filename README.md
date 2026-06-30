# Autonomous loop harness — v0 (probe)

Implements step **v0** of `docs/autonomous-loop-harness.md`:

> `bash loop + compile/test gate + stop-at-PR -> run on US-2 (class-1), watch it break`

Deliberately thin. **Not** in v0: independent reviewer (v1), Testcontainers gate (v2),
GitHub Actions / branch protection (v3), auto-merge (v4), bounded self-repair, tamper check,
convention lint, rebase-and-rerun.

## Pieces

| File | Role |
|---|---|
| `loop.sh` | Bash state machine. Picks one issue via `gh`, dispatches one fresh `claude -p`, runs the gate, opens a PR, stops. The model never chooses the task. |
| `iterate-prompt.md` | The narrow per-iteration prompt. `{{ISSUE}}` is spliced with the issue body at dispatch. |
| `logs/` | Per-iteration `claude -p` log, gate log, rendered issue body. Git-ignored. |

## One iteration (what `loop.sh` does)

1. Guard: `STOP.md` present or `MAX_ITERS` hit → exit.
2. Pick US (deterministic): resume an `in-progress` issue, else oldest `ready`. None → write
   `STOP.md`, exit.
3. Require a clean tree; branch `us-<n>` off `origin/main`; flip issue `ready`→`in-progress`.
4. Dispatch one fresh `claude -p --dangerously-skip-permissions` with the narrow prompt
   (under a `timeout`). **No self-repair** — the raw outcome is the signal.
5. Gate: `sbt compile` (`-Werror` from `build.sbt`) + `sbt test`.
6. Stop-at-PR: commit, push, `gh pr create`, flip issue → `needs-review`. A PR opens even on
   a **RED** gate — in v0 the human is the reviewer/merge gate.

## Environment the gate needs

`loop.sh` exports these itself (override via env if your paths differ):

- **JDK 25** (`.sdkmanrc` pins `java=25.0.2-open`). yaes 0.20.0 binds JDK 25's
  `StructuredTaskScope` API; under a newer default JDK (e.g. 26) **every** test aborts with
  `NoSuchMethodError` — a false RED that masks the real signal. `JAVA_HOME_PINNED` overrides.
- **colima docker socket** (`DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`,
  `TESTCONTAINERS_RYUK_DISABLED`) so the Testcontainers IT finds Docker. **colima must be
  running** (`colima start`).

Verified GREEN baseline on clean `main` with these set: 9 suites / 49 tests, 0 aborted.
A RED gate during the probe therefore means the agent's slice, not infra.

> v0 deviation: the design wants v0 in-memory-only with the Testcontainers IT split out at
> v2. That split is deferred — for one human-watched probe with a green baseline, running the
> IT once is harmless. Revisit when building v2.

## Run it

The probe burns Opus tokens and opens a real PR. Run it yourself when ready:

```bash
# one US-2 iteration (default MAX_ITERS=1)
harness/loop.sh

# inspect plumbing without invoking claude / pushing:
DRY_RUN=1 harness/loop.sh
```

Env: `MAX_ITERS` (default 1), `ITER_TIMEOUT` seconds (default 1800), `DRY_RUN=1`.

## Labels the state machine queries

`ready` · `blocked` · `in-progress` · `planned` · `needs-review` · `needs-human` +
`class-1|2|3`. Created in the repo already.
