# Autonomous loop harness — v2

Implements step **v2** of `docs/autonomous-loop-harness.md`
(design: `docs/superpowers/specs/2026-07-01-harness-v2-testcontainers-split-design.md`),
building on v1 (`docs/superpowers/specs/2026-07-01-harness-v1-reviewer-design.md`):

> `v2: + Testcontainers gate split`

**v1** gave the reviewer stack (all unchanged in v2):

- **Cold independent reviewer** — a separate, fresh `claude -p` that never shared context
  with the worker. It sees only the diff, the issue acceptance criteria, `CONTEXT.md`, and
  the tamper report, and emits a `VERDICT:` sentinel bash greps for.
- **Test-tamper check** — bash diffs the test tree against `origin/main` and surfaces the
  numstat (deleted / net-negative test files) to the reviewer. Bash does **not** block; the
  reviewer is the judgment. This catches the classic Ralph failure: deleting a failing test
  to go green. **v2 extends the diff to `src/it`** so gutting the IT to pass its tier is caught.
- **Bounded self-repair** — a shared budget of **2** fix iterations per US, spent by *any* of
  a fast-RED gate, an IT-RED gate, or a reviewer `REQUEST_CHANGES`. A pathological US spends at
  most 2 fixes total before it terminates.

**v2** splits the one gate into **two tiers by source directory** (not by tag):

- `src/test/scala` = in-memory unit/acceptance tests. **FAST gate** (`sbt compile test`) runs
  only these — **no Docker**.
- `src/it/scala` = Testcontainers integration tests (real Postgres + Flyway). **IT gate**
  (`sbt It/test`) runs only these. The one IT today is `PostgresJdbcEventStoreSpec`.
- The tier boundary is a custom sbt config in `build.sbt`: `lazy val It = config("it") extend
  Test` + `inConfig(It)(Defaults.testSettings)` (sbt's built-in `IntegrationTest` was
  deprecated in 1.9). `extend Test` gives `It` the full Test classpath, so no dependency
  rewiring.
- **IT fires after fast-GREEN and before the reviewer**, so the reviewer only ever judges a
  unit+IT-green diff. **fast-RED short-circuits the IT gate** — Docker is never paid for an
  iteration whose unit tests already fail. IT-RED draws from the same shared budget of 2.

**Still NOT in v2:** GitHub Actions / branch protection (v3), auto-merge (v4), convention-lint
gate step, rebase-on-main-and-rerun. **v2 still stops at PR — a human merges.** Budget
exhaustion (fast-RED, IT-RED, or `REQUEST_CHANGES`) opens a PR too (audit trail) but flips
`needs-human`.

## Pieces

| File | Role |
|---|---|
| `build.sbt` | Defines the `It` custom config (`config("it") extend Test` + `inConfig(It)(Defaults.testSettings)`, forked + serial + `-oDF`). `sbt test` = `src/test`; `sbt It/test` = `src/it`. |
| `src/it/scala/.../PostgresJdbcEventStoreSpec.scala` | The one Testcontainers IT (real PG + Flyway), moved out of `src/test` so it no longer runs in the fast gate. |
| `loop.sh` | Bash state machine. Picks one issue via `gh`, dispatches fresh `claude -p` tasks, runs the fast gate + IT gate + repair loop + reviewer, opens a PR, stops. The model never chooses the task. |
| `iterate-prompt.md` | The narrow worker prompt (unchanged from v0). `{{ISSUE}}` is spliced with the issue body. |
| `fix-prompt.md` | The fix-iteration prompt. Splices `{{ISSUE}}` + `{{FAILURE}}` (gate-log tail **or** reviewer reasons + tamper). Same hard rules as the worker (no test weakening, `-Werror`, no `gh`/branch ops). |
| `review-prompt.md` | The cold reviewer prompt. Splices `{{ISSUE}}`, `{{CONVENTIONS}}` (`CONTEXT.md`), `{{TAMPER}}`, `{{DIFF}}`. Adversarial. Last line MUST be `VERDICT: APPROVE` or `VERDICT: REQUEST_CHANGES`. |
| `test/statemachine-test.sh` | Drives the whole state machine in a throwaway sandbox (fake `gh`, stubbed gate + dispatches) — no real GitHub, no Opus tokens. Verifies the spec's done-criteria. |
| `logs/` | Per-pass `claude -p` logs, gate logs, rendered prompts, tamper + review + diff artefacts. Git-ignored. |

## Control flow (what `loop.sh` does)

1. Guard: `STOP.md` present or `MAX_ITERS` hit → exit.
2. Pick US (deterministic): resume an `in-progress` issue, else oldest `ready`. None → write
   `STOP.md`, exit.
3. Require a clean tree; branch `us-<n>` off `origin/main`; flip issue `ready`→`in-progress`.
4. Dispatch the fresh **IMPL** `claude -p` (v0 worker prompt). Nothing produced → leave
   in-progress, no PR.
5. **Repair loop** (shared budget of 2):
   - Run the **FAST gate** (`sbt compile -Werror` + `sbt test`, `src/test` only, no Docker).
     - **RED** → budget 0? terminate `FAIL(needs-human)`. Else spend one, dispatch **FIX**
       (fast-gate-log tail spliced), re-loop. **This short-circuits the IT gate** — no Docker.
     - **GREEN** → run the **IT gate** (`sbt It/test`, `src/it` only, real PG).
       - **IT-RED** → budget 0? terminate `FAIL(needs-human)`. Else spend one, dispatch **FIX**
         (IT-gate-log tail spliced), re-loop.
       - **IT-GREEN** → run the **tamper check** (`src/test` + `src/it`), then dispatch the
         cold **REVIEWER**. Grep the `VERDICT:` sentinel (missing → `REQUEST_CHANGES`, fail-safe):
         - `APPROVE` → terminate `SUCCESS`.
         - `REQUEST_CHANGES` → budget 0? terminate `FAIL(needs-human)`. Else spend one,
           dispatch **FIX** (review reasons + tamper spliced), re-loop.
6. **Terminal (still stop-at-PR):** commit, push, `gh pr create`. `SUCCESS` records the
   APPROVE + review and flips `needs-review`. `FAIL` records the last failure + review and
   flips `needs-human`. A human merges either way.

Every `claude -p` (IMPL, FIX, REVIEW) is a **fresh context**: the failure reason or the diff
it needs is spliced into its prompt, never remembered.

## Environment the gate needs

`loop.sh` exports these itself (override via env if your paths differ):

- **JDK 25** (`.sdkmanrc` pins `java=25.0.2-open`). yaes 0.20.0 binds JDK 25's
  `StructuredTaskScope` API; under a newer default JDK (e.g. 26) **every** test aborts with
  `NoSuchMethodError` — a false RED that masks the real signal. `JAVA_HOME_PINNED` overrides.
- **colima docker socket** (`DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`,
  `TESTCONTAINERS_RYUK_DISABLED`) so the Testcontainers IT finds Docker. **colima must be
  running** (`colima start`) for the **IT gate**. The **fast gate** (`sbt test`) needs no
  Docker after the v2 split — only the IT gate (`sbt It/test`) does.

## Run it

The loop burns Opus tokens and opens a real PR. Run it yourself when ready:

```bash
harness/loop.sh                 # one US (default MAX_ITERS=1)
DRY_RUN=1 harness/loop.sh       # inspect plumbing: render the worker prompt, no mutation
harness/test/statemachine-test.sh   # exercise the state machine offline (no gh, no tokens)
```

Env: `MAX_ITERS` (default 1), `ITER_TIMEOUT` s (default 1800), `GATE_TIMEOUT` s (default 900),
`IT_GATE_TIMEOUT` s (default 1200 — container startup), `REPAIR_BUDGET` (default 2), `DRY_RUN=1`.

**Test seams** (default to the real thing; used by `statemachine-test.sh` to force outcomes):
`GATE_CMD` (fast tier), `IT_GATE_CMD` (IT tier), `IMPL_CMD`, `FIX_CMD`, `REVIEW_CMD`. A stub
ignores the prompt and simulates the gate / agent — e.g. `IT_GATE_CMD=false` forces IT-RED,
`REVIEW_CMD='echo "VERDICT: REQUEST_CHANGES"'`. Note: the gate seams run word-split (not shell
`eval`), so a **stateful** gate stub must be a script on `PATH`, not an inline compound.

## Watching a live run

The IMPL and FIX dispatches run `--output-format stream-json --verbose`, emitting one JSONL
event per turn *as they work* into `logs/*.claude.log`. Follow the newest live:

```bash
harness/tail-claude.sh              # renders 🗣 prose / 🔧 tool calls / ↳ results per event
```

The reviewer dispatch is plain text (its stdout **is** the verdict-bearing review, captured to
`logs/issue-<n>-review.md`). Bash reads only each dispatch's exit code and the one `VERDICT:`
grep — never the mid-flight stream — so the raw outcome stays the signal.

## Labels the state machine queries

`ready` · `blocked` · `in-progress` · `planned` · `needs-review` · `needs-human` +
`class-1|2|3`. `needs-review` = reviewer APPROVE, human merges. `needs-human` = budget
exhausted (fast-RED, IT-RED, or `REQUEST_CHANGES`), human takes over. Created in the repo already.
