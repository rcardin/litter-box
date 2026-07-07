# Autonomous loop harness — v2

Implements step **v2** of `docs/autonomous-loop-harness.md`
(design: `docs/superpowers/specs/2026-07-01-harness-v2-testcontainers-split-design.md`),
building on v1 (`docs/superpowers/specs/2026-07-01-harness-v1-reviewer-design.md`):

> `v2: + Testcontainers gate split`

This revision also hardens v2 on top of the same design: an infra-fault terminal (rc `50`), a
Docker preflight, protected-path enforcement in bash, and a fatal stale-base guard — see
**Infra faults vs code failures** below. None of this changes the v2 state machine; it closes
gaps where infra problems or prompt violations were previously indistinguishable from code
failures.

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

**v3 (GitHub Actions CI `build` check + branch protection on `main`) has shipped**, outside this
script — `ci.yml` is merged and `main` is protected on the `build` check. **Still not
implemented:** a convention-lint gate step, rebase-on-main-and-rerun. **For class-2/3, the
loop still stops at PR — a human merges** (class-1 is auto-merged as of v4, below). Budget
exhaustion (fast-RED, IT-RED, or `REQUEST_CHANGES`) opens a PR too (audit trail) but flips
`needs-human` — as does a protected-path violation (see **Infra faults vs code failures**
below), which skips the gate and the reviewer entirely.

## v4: auto-merge (class-1 only)

A class-1 issue that ends in reviewer APPROVE is merged unattended:

1. The loop opens the PR as before, then waits for the required `build` check
   (`gh pr checks --watch`, bounded by `CI_WAIT_TIMEOUT`, default 900 s).
   Hitting the bound is an infra fault (rc 50): the loop exits for inspection,
   the PR stays open, the issue keeps `in-progress`.
2. CI red after green local gates → the issue flips to `needs-human` and the loop
   moves on. There is no self-repair against the independent check (the v3
   hands-off rule): local gates were green, so red CI means environment drift or
   a real independence catch — a human looks either way.
3. CI green → `gh pr merge --squash --delete-branch`, then the loop verifies the
   PR state is actually MERGED (an unverified merge is rc 50). The PR body's
   `Closes #N` closes the issue.

Class-2/3 SUCCESS keeps the stop-at-PR terminal: `needs-review`, human merges.
Auto-merge for the harder classes is earned by class-1 track record, not assumed.

### blocked → ready

After a verified merge the loop scans open `blocked` issues for the sentinel line

    Blocked-by: #N

and flips `blocked` → `ready` when every referenced issue is closed (the
just-merged one counts immediately). Issues without the sentinel are never
touched. File dependent slices with the sentinel or flip them by hand.

### Notifications

Set `NTFY_TOPIC` (an https://ntfy.sh topic) to get a push on: every
`needs-human` terminal, every infra-fault exit (rc 50), and every auto-merge.
Unset = log-only. `NOTIFY_CMD` overrides the channel entirely (test seam,
eval'd with `$msg` in scope). Notify failures never change loop behavior.

### Branch protection

`strict: true` is set on the required `build` check: a PR must be up to date
with main before merging. The loop is serial and always branches off fresh
origin/main, so this only ever bites out-of-band PRs.

## Pieces

| File | Role |
|---|---|
| `build.sbt` | Defines the `It` custom config (`config("it") extend Test` + `inConfig(It)(Defaults.testSettings)`, forked + serial + `-oDF`). `sbt test` = `src/test`; `sbt It/test` = `src/it`. |
| `src/it/scala/.../PostgresJdbcEventStoreSpec.scala` | The one Testcontainers IT (real PG + Flyway), moved out of `src/test` so it no longer runs in the fast gate. |
| `loop.sh` | Bash state machine. Docker preflight at startup, picks one issue via `gh`, dispatches fresh `claude -p` tasks, enforces the protected-path guard, runs the fast gate + IT gate + repair loop + reviewer, opens a PR, stops. Timeouts and other infra faults exit `50` without spending repair budget. The model never chooses the task. |
| `iterate-prompt.md` | The narrow worker prompt (unchanged from v0). `{{ISSUE}}` is spliced with the issue body. |
| `fix-prompt.md` | The fix-iteration prompt. Splices `{{ISSUE}}` + `{{FAILURE}}` (gate-log tail **or** reviewer reasons + tamper). Same hard rules as the worker (no test weakening, `-Werror`, no `gh`/branch ops). |
| `review-prompt.md` | The cold reviewer prompt. Splices `{{ISSUE}}`, `{{CONVENTIONS}}` (`CONTEXT.md`), `{{TAMPER}}`, `{{DIFF}}`. Adversarial. Last line MUST be `VERDICT: APPROVE` or `VERDICT: REQUEST_CHANGES`. |
| `test/statemachine-test.sh` | Drives the whole state machine in a throwaway sandbox (fake `gh`, stubbed gate + dispatches) — no real GitHub, no Opus tokens. Scenarios A–I plus a DRY_RUN check (APPROVE, REQUEST_CHANGES, fast-RED, IT-RED, budget exhaustion, idle-no-latch, protected-path, empty-review, gate-timeout), 68 assertions total. |
| `logs/` | Per-pass `claude -p` logs, gate logs, rendered prompts, tamper + review + diff artefacts. Git-ignored. |

## Control flow (what `loop.sh` does)

0. Startup: unless `IT_GATE_CMD` is overridden (test seam), `docker info` must succeed or the
   whole loop `die`s before touching any issue — an unreachable Docker daemon must never
   surface mid-run as a false IT-RED.
1. Guard: `STOP.md` present (a **manual** kill-switch — create it by hand to halt the loop) or
   `MAX_ITERS` hit → exit.
2. Pick US (deterministic): resume an `in-progress` issue, else oldest `ready`. None → log idle,
   exit `11`. This is transient (a US parked in human review), not terminal: the loop writes **no**
   sentinel, so the next tick resumes on its own once an issue goes `ready`.
3. Require a clean tree. `git fetch origin main` and branching `us-<n>` off `origin/main` are
   both **fatal** on failure (`die`) — every diff, tamper report, gate run and PR downstream is
   measured against `origin/main`, so a stale local base is never silently tolerated. Flip issue
   `ready`→`in-progress`.
4. Dispatch the fresh **IMPL** `claude -p` (v0 worker prompt). A `124` timeout → infra fault,
   exit `50` (a half-finished worker must never reach the gates). Otherwise, nothing produced →
   leave in-progress, no PR (exit `30`).
5. **Repair loop** (shared budget of 2), each pass:
   - **Protected-path guard** (bash, not the prompt — the worker/fixer runs with permissions
     skipped): if the staged diff vs `origin/main` touches `harness/`, `docs/`, `.github/`,
     `PROMPT.md`, `CONTEXT.md` or `STOP.md`, terminate immediately as `FAIL(needs-human)`, gate
     `SKIPPED`, **no FIX dispatched** (the fixer is the same agent class that just broke the
     rule) — a PR is still opened, marked do-not-merge, for the audit trail.
   - Run the **FAST gate** (`sbt compile -Werror` + `sbt test`, `src/test` only, no Docker). A
     `124` timeout → infra fault, exit `50`, no budget spent.
     - **RED** → budget 0? terminate `FAIL(needs-human)`. Else spend one, dispatch **FIX**
       (fast-gate-log tail spliced; a FIX timeout is also infra fault, exit `50`), re-loop.
       **This short-circuits the IT gate** — no Docker.
     - **GREEN** → run the **IT gate** (`sbt It/test`, `src/it` only, real PG). A `124` timeout
       → infra fault, exit `50`.
       - **IT-RED** → budget 0? terminate `FAIL(needs-human)`. Else spend one, dispatch **FIX**
         (IT-gate-log tail spliced), re-loop.
       - **IT-GREEN** → run the **tamper check** (`src/test` + `src/it`), then dispatch the
         cold **REVIEWER**. A `124` timeout, or an empty/whitespace-only review, is an infra
         fault (crashed/timed-out reviewer) → exit `50`, no budget spent. Grep the `VERDICT:`
         sentinel on a non-empty review (missing → `REQUEST_CHANGES`, fail-safe — this is model
         misbehavior, real signal, not an infra fault):
         - `APPROVE` → terminate `SUCCESS`.
         - `REQUEST_CHANGES` → budget 0? terminate `FAIL(needs-human)`. Else spend one,
           dispatch **FIX** (review reasons + tamper spliced), re-loop.
6. **Terminal:** commit, push, `gh pr create`. `SUCCESS` on a **class-1** issue hands off to
   **v4 auto-merge** (below) instead of flipping a label — the merge or a CI-red `needs-human`
   decides its fate. `SUCCESS` on **class-2/3** records the APPROVE + review and flips
   `needs-review`; a human merges. `FAIL` (budget exhaustion or protected-path) records the
   last failure and flips `needs-human` regardless of class. An exit-`50` infra fault instead
   commits nothing and opens no PR — the issue simply stays `in-progress` for the next tick or
   for manual inspection.

Every `claude -p` (IMPL, FIX, REVIEW) is a **fresh context**: the failure reason or the diff
it needs is spliced into its prompt, never remembered.

## Infra faults vs code failures

Not every non-zero outcome is a code failure. `loop.sh` distinguishes **infra faults**, which
must never cost repair budget or bury the real signal under `needs-human`, from actual code or
review failures:

- **rc `50` — infra-fault terminal.** A gate timeout (rc `124`), a worker or reviewer
  `claude -p` timeout (rc `124`), or an empty/whitespace-only reviewer output all exit the
  *whole loop* with `50`. The issue **keeps its `in-progress` label** (resumable next tick), **no
  repair-budget unit is spent**, **no FIX is dispatched**, and **no PR is opened**. A non-empty
  review that is simply missing the `VERDICT:` sentinel is treated differently — that is model
  misbehavior, real signal — and still fail-safes to `REQUEST_CHANGES`, spending budget as usual.
- **Docker preflight.** At startup, if `IT_GATE_CMD` is still the real `sbt It/test` (the test
  seam not overridden), `docker info` must succeed or the loop dies immediately: "docker
  unreachable (colima running?) — IT gate would fail for infra reasons". Skipped when the seam
  is overridden.
- **Protected-path enforcement.** Enforced in bash after every `git add -A` in the repair loop,
  not just in the prompt, because the worker/fixer runs with `--dangerously-skip-permissions`.
  A staged diff vs `origin/main` touching `harness/`, `docs/`, `.github/`, `PROMPT.md`,
  `CONTEXT.md` or `STOP.md` terminates the US as `FAIL(needs-human)` immediately: gate
  `SKIPPED`, no FIX dispatched, PR still opened (do-not-merge, audit trail only).
  `harness/logs/` is gitignored so the harness's own log writes never trip this.
- **Fatal stale-base guard.** `git fetch origin main` failing, or being unable to branch off
  `origin/main`, is fatal (`die`) rather than a silent fallback to a stale local base.

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
`class-1|2|3`. `needs-review` = reviewer APPROVE on a **class-2/3** issue, human merges
(class-1 APPROVE skips this label — see **v4: auto-merge** above). `needs-human` = budget
exhausted (fast-RED, IT-RED, or `REQUEST_CHANGES`), a protected-path violation, or CI red
after a class-1 auto-merge attempt; human takes over. An rc-`50` infra fault touches no label
— the issue stays `in-progress` for the next tick or manual inspection. Created in the repo
already.
