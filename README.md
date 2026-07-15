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

> **v6 slice 3 removed the LOCAL IT gate.** The `src/it` tier is now judged **only by GitHub
> Actions** (the required `build` check runs `sbt It/test` on the PR). The loop runs the FAST
> gate as its sole local gate; an IT failure surfaces as **CI-red** on the class-1 auto-merge
> path (→ needs-human, no local self-repair), exactly the existing CI-red route. The paragraphs
> above describe the historical v2 two-tier loop; see **Containerized worker/fixer (v6 slice 3)**
> below for the current shape. The tamper diff still covers `src/it`.

**v3 (GitHub Actions CI `build` check + branch protection on `main`) has shipped**, outside this
script — `ci.yml` is merged and `main` is protected on the `build` check. **Still not
implemented:** a convention-lint gate step, rebase-on-main-and-rerun. **For class-2/3, the
loop still stops at PR — a human merges** (class-1 is auto-merged as of v4, below). Budget
exhaustion (fast-RED, IT-RED, or `REQUEST_CHANGES`) opens a PR too (audit trail) but flips
`needs-human` — as does a protected-path violation (see **Infra faults vs code failures**
below), which skips the gate and the reviewer entirely.

## v4: auto-merge (class-1 only)

A class-1 issue that ends in reviewer APPROVE is merged unattended:

1. The loop opens the PR as before, then waits for the required `build` check to
   **register** — a push races the workflow scheduler, so a fresh PR reports zero
   checks for a few seconds, and `gh pr checks` exits nonzero there exactly as it
   does for a failed check. The loop polls `statusCheckRollup` until it is non-empty
   (bounded by `CI_APPEAR_TIMEOUT`, default 300 s) before letting `gh pr checks
   --watch` judge the result (bounded by `CI_WAIT_TIMEOUT`, default 900 s).
   Hitting either bound is an infra fault (rc 50): the loop exits for inspection,
   the PR stays open, the issue keeps `in-progress`. A check that never registers is
   a scheduler problem, never a code failure — it must not reach `needs-human`.
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
| `src/it/scala/.../PostgresJdbcEventStoreSpec.scala` | The one Testcontainers IT (real PG + Flyway). Judged by **CI** (`sbt It/test` in `ci.yml`), never by a local gate — the loop has no local IT gate as of v6 slice 3. |
| `loop.sh` | Bash state machine. Sandbox preflight at startup (v6: build image, start proxy sidecar; requires `ANTHROPIC_API_KEY`), picks one issue via `gh`, dispatches fresh `claude -p` tasks **inside the sandbox** (worker/fixer, v6 slice 3), enforces the protected-path patch guard, runs the FAST gate + repair loop + reviewer, opens a PR, stops. Timeouts and other infra faults exit `50` without spending repair budget. The model never chooses the task. |
| `iterate-prompt.md` | The narrow worker prompt (unchanged from v0). `{{ISSUE}}` is spliced with the issue body. |
| `fix-prompt.md` | The fix-iteration prompt. Splices `{{ISSUE}}` + `{{FAILURE}}` (gate-log tail **or** reviewer reasons + tamper). Same hard rules as the worker (no test weakening, `-Werror`, no `gh`/branch ops). |
| `review-prompt.md` | The cold reviewer prompt. Splices `{{ISSUE}}`, `{{CONVENTIONS}}` (`CONTEXT.md`), `{{TAMPER}}`, `{{DIFF}}`. Adversarial. Last line MUST be `VERDICT: APPROVE` or `VERDICT: REQUEST_CHANGES`. |
| `sandbox/` | The containerized sandbox — image `Dockerfile`, proxy sidecar (`proxy/`), `build-image.sh`, `start-proxy.sh` / `stop-proxy.sh`, the `GATE_CMD` default `run-fast-gate.sh` (v6 slice 1), and the worker/fixer dispatch `run-agent.sh` + `agent-entrypoint.sh` (v6 slice 3). See **Containerized FAST gate** and **Containerized worker/fixer** below. |
| `test/statemachine-test.sh` | Drives the whole state machine in a throwaway sandbox (fake `gh`, stubbed gate + dispatches) — no real GitHub, no Opus tokens, no Docker (`GATE_CMD` and the `IMPL/FIX/REVIEW` seams are always stubbed). Scenarios A–T plus a DRY_RUN check (APPROVE, REQUEST_CHANGES, fast-RED, **container-dispatch timeout for worker + fixer**, budget exhaustion, idle-no-latch, protected-path, empty-review, gate-timeout, class-1 auto-merge + CI red/timeout/late-registering-check/unverified-merge/merge-failure, class-2 stop-at-PR, plus patch-guard CI-workflow rejection / apply-conflict / oversized-patch), 142 assertions total. Stubs produce patch files at `$PATCH_OUT`; a stub that exits `124` simulates a container-dispatch timeout. |
| `sandbox/test/` | Manual/local Docker-dependent checks for the sandbox itself (image smoke test, proxy allowlist test, FAST-gate and worker/fixer infra-fault checks, coursier-cache speed check) — not part of `sbt test` or `statemachine-test.sh`. Run `sandbox/test/run-all.sh`. |
| `logs/` | Per-pass `claude -p` logs, gate logs, rendered prompts, tamper + review + diff artefacts. Git-ignored. |

## Control flow (what `loop.sh` does)

0. Startup: unless `GATE_CMD` is overridden (test seam), the loop builds the sandbox image,
   starts the proxy sidecar, and requires `ANTHROPIC_API_KEY` (the dedicated key handed to the
   containerized worker/fixer) — any failure `die`s before touching an issue. There is no longer
   a loop-level Docker preflight: `run-fast-gate.sh` and `run-agent.sh` each re-check Docker /
   image / proxy at dispatch time and exit `124` (infra fault) if anything is stale.
1. Guard: `STOP.md` present (a **manual** kill-switch — create it by hand to halt the loop) or
   `MAX_ITERS` hit → exit.
2. Pick US (deterministic): resume an `in-progress` issue, else oldest `ready`. None → log idle,
   exit `11`. This is transient (a US parked in human review), not terminal: the loop writes **no**
   sentinel, so the next tick resumes on its own once an issue goes `ready`.
3. Require a clean tree. `git fetch origin main` and branching `us-<n>` off `origin/main` are
   both **fatal** on failure (`die`) — every diff, tamper report, gate run and PR downstream is
   measured against `origin/main`, so a stale local base is never silently tolerated. Flip issue
   `ready`→`in-progress`.
4. Dispatch the fresh **IMPL** worker **inside the sandbox container** (v6 slice 3) **across the
   patch seam** (v6 slice 2, below): `run-agent.sh` clones `origin/main` read-only, runs `claude
   -p` with a dedicated `ANTHROPIC_API_KEY` reaching the network only through the proxy, and
   returns the cumulative patch, which the harness resets, inspects and `git apply`s. A `124`
   timeout → the container is **killed** and it exits infra fault `50` (a half-finished worker
   must never reach the gates). A patch that will not apply → infra fault, exit `50`, no budget
   spent. Nothing produced (empty patch) → leave in-progress, no PR (exit `30`). A **patch-guard
   rejection** (protected path or oversized) terminates as `FAIL(needs-human)` without entering
   the repair loop.
5. **Repair loop** (shared budget of 2), each pass:
   - Run the **FAST gate** (`sbt compile -Werror` + `sbt test`, `src/test` only, containerized).
     A `124` timeout → infra fault, exit `50`, no budget spent.
     - **RED** → budget 0? terminate `FAIL(needs-human)`. Else spend one, dispatch **FIX** (in
       the sandbox; fast-gate-log tail spliced; a FIX timeout is also infra fault, exit `50`),
       re-loop.
     - **GREEN** → run the **tamper check** (`src/test` + `src/it`), then dispatch the cold
       **REVIEWER** (on the host — it authors no code; all mutating tools are denied). A `124`
       timeout, or an empty/whitespace-only review, is an infra fault (crashed/timed-out
       reviewer) → exit `50`, no budget spent. Grep the `VERDICT:` sentinel on a non-empty review
       (missing → `REQUEST_CHANGES`, fail-safe — model misbehavior, real signal, not an infra
       fault):
       - `APPROVE` → terminate `SUCCESS`.
       - `REQUEST_CHANGES` → budget 0? terminate `FAIL(needs-human)`. Else spend one, dispatch
         **FIX** (review reasons + tamper spliced), re-loop.

   The `src/it` integration tier has **no local gate**: it is judged by CI on the PR. On a
   class-1 SUCCESS the auto-merge step waits for the required `build` check, and an **IT failure
   there is CI-red → `needs-human`, no local self-repair** (the existing CI-red route).
6. **Terminal:** commit, push, `gh pr create`. `SUCCESS` on a **class-1** issue hands off to
   **v4 auto-merge** (below) instead of flipping a label — the merge or a CI-red `needs-human`
   decides its fate. `SUCCESS` on **class-2/3** records the APPROVE + review and flips
   `needs-review`; a human merges. `FAIL` (budget exhaustion, or a patch-guard rejection —
protected-path or oversized) records the
   last failure and flips `needs-human` regardless of class. An exit-`50` infra fault instead
   commits nothing and opens no PR — the issue simply stays `in-progress` for the next tick or
   for manual inspection.

Every `claude -p` (IMPL, FIX, REVIEW) is a **fresh context**: the failure reason or the diff
it needs is spliced into its prompt, never remembered.

## Infra faults vs code failures

Not every non-zero outcome is a code failure. `loop.sh` distinguishes **infra faults**, which
must never cost repair budget or bury the real signal under `needs-human`, from actual code or
review failures:

- **rc `50` — infra-fault terminal.** A gate timeout (rc `124`), a **worker/fixer container
  dispatch timeout** (rc `124` — the container is killed, no orphan) or reviewer timeout, a
  missing sandbox image / dead proxy / unreachable Docker / missing API key at dispatch time, a
  **patch that fails to `git apply`** (v6 slice 2), or an empty/whitespace-only reviewer output
  all exit the *whole loop* with `50`. The issue **keeps its `in-progress` label** (resumable
  next tick), **no repair-budget unit is spent**, **no FIX is dispatched**, and **no PR is
  opened**. A non-empty review that is simply missing the `VERDICT:` sentinel is treated
  differently — that is model misbehavior, real signal — and still fail-safes to
  `REQUEST_CHANGES`, spending budget as usual.
- **Sandbox preflight (v6).** At startup, when `GATE_CMD` is not overridden, the loop requires
  `ANTHROPIC_API_KEY`, then runs `sandbox/build-image.sh` and `sandbox/start-proxy.sh` (both
  die-on-failure), plus a `trap ... EXIT` that always runs `sandbox/stop-proxy.sh` when the loop
  exits (normal exit or any `die`). There is **no loop-level `docker info` preflight** any more
  (v6 slice 3 deleted it together with the local IT gate). At dispatch *time*,
  `sandbox/run-fast-gate.sh` (the FAST gate) and `sandbox/run-agent.sh` (the worker/fixer) each
  re-check Docker reachability, image presence and proxy liveness and exit `124` — the loop's
  rc-124-is-infra-fault convention — on any failure, so a sidecar that dies mid-run is never
  mistaken for a code failure. See **Containerized FAST gate** and **Containerized worker/fixer**
  below.
- **Patch seam (v6 slice 2).** Every agent dispatch (IMPL and each FIX) crosses an
  inspect-then-apply boundary rather than being committed straight from the tree the agent
  edited. As of v6 slice 3 the agent runs **in the sandbox**: `run-agent.sh` returns the
  cumulative diff vs `origin/main` as a patch file, which `stage_patch()` inspects (protected
  path, size cap) then `git apply --index`es onto a pristine base. The worker/fixer **stub
  contract** matches: a stub writes `$PATCH_OUT` instead of running a container. A patch that
  will not apply is an **infra fault** (exit `50`, no budget spent), never a gate failure.
- **Protected-path / oversized patch guard.** Enforced in bash, reading the file list straight
  out of the **patch** (`git apply --numstat`), because the worker/fixer runs with
  `--dangerously-skip-permissions`. A patch touching `harness/` (harness code), `.github/` (CI
  workflows), `docs/`, `CONTEXT.md` (the constitution), `PROMPT.md` or `STOP.md`, **or** a patch
  over `MAX_PATCH_BYTES` (default 1 MB), terminates the US as `FAIL(needs-human)` immediately:
  gate `SKIPPED`, no FIX dispatched, the rejected patch **never applied**, PR still opened with a
  rejection marker (do-not-merge, audit trail only). `harness/logs/` is gitignored so the
  harness's own log writes never enter a patch.
- **Fatal stale-base guard.** `git fetch origin main` failing, or being unable to branch off
  `origin/main`, is fatal (`die`) rather than a silent fallback to a stale local base.

## Patch seam (v6 slice 2)

Agent output no longer reaches the branch straight from the tree the agent edited. This is a
**prefactor**: the worker still runs on the host, but its changes already cross an
inspect-then-apply boundary, so a later slice can swap the host tree edit for a container that
hands back a patch the same way.

Every IMPL and FIX dispatch funnels through `stage_patch()`:

1. **Produce a patch.** The dispatch's output is a patch file at `$PATCH_OUT` — the seam
   boundary. A real worker/fixer edits the host tree and bash snapshots the cumulative diff vs
   `origin/main`; a test **stub** writes `$PATCH_OUT` itself (the slice-2 stub contract) so the
   state machine is driven with no real agent.
2. **Reset to a pristine base.** `git reset --hard origin/main` + `git clean -fd` — the tree the
   agent edited is never trusted or committed directly. (`harness/logs/` is gitignored, so the
   extracted patch file survives the clean.)
3. **Inspect, then apply.** Size cap (`MAX_PATCH_BYTES`, default 1 MB) and the protected-path
   guard both read the **patch** (`git apply --numstat`), not the tree. A rejection routes to
   `needs-human` (rejected patch never applied; a marker is committed for the audit PR). A clean
   patch is applied with `git apply --index` on the branch; a patch that will not apply is an
   **infra fault** (rc `50`, no repair budget spent), not a gate failure.

The **test-tamper numstat check** is reworked to read the same applied patch, so its verdicts
are unchanged but now derive from the one artifact the guard and `git apply` also see. See the
patch-guard / apply-conflict / oversized scenarios (R/S/T) in `test/statemachine-test.sh`.

## Containerized FAST gate (v6 slice 1)

The FAST gate (`GATE_CMD`) no longer runs `sbt compile test` on the host. `build.sbt` is
agent-authored, so a hostile or merely buggy worker/fixer iteration could make a *host* gate
lie. v6 reframes the threat model (issue #33/#34): the sandbox protects the host, GH Actions CI
protects `main`, and a local gate is a productivity signal only — so the FAST gate now runs
inside a locked-down container. Slice 1 containerized **only** the FAST gate; v6 slice 3 then
moved the **worker and fixer** into the same image (see **Containerized worker/fixer** below) and
deleted the local IT gate. Only the reviewer still runs on the host — it authors no code (all
mutating tools denied).

- **The image** (`sandbox/Dockerfile`, tag `fes-harness-sandbox:v6`) — `eclipse-temurin:25-jdk`
  (matches the host's `.sdkmanrc` JDK 25 pin) plus sbt 1.12.9 (matches
  `project/build.properties`, installed from the official release tarball), `git`, and the
  Claude Code CLI (installed via its native installer, currently reporting the same
  `2.1.207` the host runs — the CLI does not need to authenticate in this slice, only to
  report `claude --version`). Runs as a non-root `gate` user with `--cap-drop=ALL
  --security-opt=no-new-privileges` at `docker run` time.
- **The egress proxy sidecar** (`sandbox/proxy/`, container `fes-sandbox-proxy`) — a tiny
  `alpine` + `tinyproxy` image on a Docker `--internal` network (`fes-sandbox-net`, no route to
  the outside world) that the proxy *also* joins the default `bridge` network for its own real
  egress. Gate containers join `fes-sandbox-net` only, so the proxy is the only host they can
  reach at all. The hostname allowlist lives in `sandbox/proxy/allowlist` (one hostname per
  line — `api.anthropic.com`, `repo1.maven.org`, `repo.maven.apache.org`,
  `repo.scala-sbt.org`, `oss.sonatype.org`), enforced by tinyproxy's `Filter` +
  `FilterDefaultDeny yes` on both plain HTTP and HTTPS `CONNECT` (no MITM — the hostname in the
  `CONNECT` line is enough to filter on). **To extend it**: edit `sandbox/proxy/allowlist`,
  bump the `# version:` comment at the top of `sandbox/proxy/tinyproxy.conf`, then
  `sandbox/build-image.sh && sandbox/stop-proxy.sh && sandbox/start-proxy.sh` (or just restart
  `loop.sh`, which does both). The sidecar starts as part of `loop.sh`'s startup preflight and
  stops via a `trap ... EXIT`, so it never outlives the loop.
- **The coursier cache volume** (`fes-sandbox-coursier-cache`, a Docker named volume) — mounted
  only at `/home/gate/.cache/coursier` inside gate containers. It self-populates on first use
  and measurably speeds up subsequent gate runs (observed on this repo: ~27s cold, ~19s warm).
  Nothing on the host — no `sbt` config, no env var — references this volume, so by
  construction it can never be mounted by a host `sbt` process.
- **The read-only clone** — rather than a live bind mount of the real working tree,
  `run-fast-gate.sh` runs `git write-tree` (loop.sh already does `git add -A` immediately
  before every gate call, so the index reflects the worker's current output) then `git
  archive` into a throwaway `mktemp` directory with **no `.git` at all**. Only that disposable
  copy is bind-mounted read-write into the container (needed for `target/`); the real
  repository, its history, and its hooks never enter the container.
- **No credentials, ever.** The gate container gets exactly two env vars
  (`JAVA_TOOL_OPTIONS` pointing coursier/sbt's JVM at the proxy via
  `-Dhttp(s).proxyHost/-Port` — a JVM does not read `HTTP_PROXY`/`HTTPS_PROXY` on its own).
  No `ANTHROPIC_API_KEY`, no `GH_TOKEN`, no `gh` config mount — the `gh` binary is not even
  installed in the image, so there is nothing for a compromised gate container to reach for.
- **Infra faults, not code failures.** `run-fast-gate.sh` reuses the loop's existing
  rc-124-is-infra-fault convention for the three ways this slice can break: Docker
  unreachable, the image missing, or the proxy sidecar dead — see **Infra faults vs code
  failures** above.
- **The seam is untouched.** `GATE_CMD` is still a single overridable command string
  (`GATE_OVERRIDDEN`); `run_gate()` and every rc-handling branch in `iterate()` needed zero
  changes.

## Containerized worker/fixer (v6 slice 3)

The worker and fixer no longer run `claude -p` on the host — the last place agent-authored code
executed outside a sandbox. `dispatch_worker()`'s real path calls `sandbox/run-agent.sh`, which:

- **Clones `origin/main` read-only** via `git archive` into a throwaway `$HOME/.cache/...`
  directory (no host `.git` ever enters the container — stronger than a `:ro` bind mount). The
  in-container `agent-entrypoint.sh` commits that tree as the base (`HEAD == origin/main`), then
  overlays the **prior cumulative patch** (`$CURRENT_PATCH`, empty on the initial IMPL) so a FIX
  builds on prior work. The agent's diff is therefore cumulative-vs-`origin/main`, exactly what
  `stage_patch()` applies onto a pristine base.
- **Passes a dedicated, spend-capped `ANTHROPIC_API_KEY`** as a container env var — the only
  credential in the container. No `GH_TOKEN`, no host `gh`/claude config, no host keychain. The
  container joins `fes-sandbox-net` only, so it reaches the network **only through the proxy**
  (`api.anthropic.com` is on the allowlist); `HTTP(S)_PROXY` + JVM proxy props are set so both
  `claude`'s fetch and any `sbt` the agent runs go through it. Same non-root `gate` user,
  `--cap-drop=ALL`, `--security-opt=no-new-privileges`, and coursier cache volume as the gate.
- **Leaves its result as a patch** on an output volume (`/output/agent.patch`), copied back to
  the harness's `$patch_out`. Only that patch crosses the boundary; the host tree is never
  written by the agent.
- **Is detached and awaited under `ITER_TIMEOUT`.** `gtimeout` kills only the docker *client*, so
  on expiry `run-agent.sh`'s signal trap **kills the container** and exits `124` (infra fault) —
  a client-side timeout alone would orphan a full claude container in the VM. Every infra fault
  (timeout, missing image, dead proxy, unreachable Docker, missing API key, a prior patch that
  will not apply) exits `124`, which `dispatch_worker()` maps to an rc-`50` terminal that spends
  no repair budget.

The state-machine test drives this seam with stubs (a stub writing `$PATCH_OUT`, or exiting
`124` to simulate the dispatch timeout); `sandbox/test/agent-infra-fault-test.sh` covers the
real `run-agent.sh` infra-fault exit codes without a live daemon.

## Environment the loop needs

`loop.sh` pins/exports these itself (override via env if your paths differ):

- **JDK 25** (`.sdkmanrc` pins `java=25.0.2-open`). yaes 0.20.0 binds JDK 25's
  `StructuredTaskScope` API; under a newer default JDK (e.g. 26) **every** test aborts with
  `NoSuchMethodError` — a false RED that masks the real signal. `JAVA_HOME_PINNED` overrides.
  (The sandbox image pins the same JDK 25 in its own image, independent of the host.)
- **`ANTHROPIC_API_KEY`** — the dedicated, spend-capped key handed to the containerized
  worker/fixer. Required at startup (the loop `die`s without it); nothing else from the host
  environment enters the agent container.
- **Docker via colima** — the loop no longer exports `DOCKER_HOST` or any `TESTCONTAINERS_*`
  var (v6 slice 3 deleted that plumbing along with the local IT gate). The sandbox containers
  reach the daemon through the docker CLI's active context (colima's socket on this setup), so
  **colima must be running** (`colima start`). Note: under colima only `$HOME` is mounted into
  the VM, so `run-fast-gate.sh` / `run-agent.sh` root their throwaway clones under
  `$HOME/.cache/fes-harness-sandbox` rather than the system `TMPDIR` — a plain `/tmp` (or
  macOS's real `TMPDIR`, `/var/folders/...`) is invisible to the Docker daemon there and would
  silently bind-mount an empty directory into the container.

## Run it

The loop burns Opus tokens and opens a real PR. Run it yourself when ready:

```bash
harness/loop.sh                 # one US (default MAX_ITERS=1)
DRY_RUN=1 harness/loop.sh       # inspect plumbing: render the worker prompt, no mutation
harness/test/statemachine-test.sh   # exercise the state machine offline (no gh, no tokens)
```

Env: `MAX_ITERS` (default 1), `ITER_TIMEOUT` s (default 1800), `GATE_TIMEOUT` s (default 900),
`REPAIR_BUDGET` (default 2), `DRY_RUN=1`. `ANTHROPIC_API_KEY` is required (handed to the
containerized worker/fixer).

**Test seams** (default to the real thing; used by `statemachine-test.sh` to force outcomes):
`GATE_CMD` (FAST tier), `IMPL_CMD`, `FIX_CMD`, `REVIEW_CMD`. A stub ignores the prompt and
simulates the gate / agent — e.g. `GATE_CMD=false` forces fast-RED, `IMPL_CMD='exit 124'`
simulates a container-dispatch timeout, `REVIEW_CMD='echo "VERDICT: REQUEST_CHANGES"'`. Note:
the gate seam runs word-split (not shell `eval`), so a **stateful** gate stub must be a script on
`PATH`, not an inline compound. Overriding `GATE_CMD` also skips the sandbox preflight, so the
suite needs neither Docker nor an API key.

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

## Observability

The loop writes one JSON event per phase transition to `harness/logs/status.jsonl`
(gitignored). It is a pure append: the loop's behaviour, exit codes and merge decisions do not
depend on it, and nothing reads it back.

Watch a run from a second terminal:

```bash
harness/watch.sh
```

A four-line banner pins the current phase, gate pass and remaining repair budget to the top of
the terminal, and the pane below follows whichever log the current phase is writing: agent
dispatches rendered as prose, tool calls and tool results; gates and the CI wait as raw output.
The pane switches on its own when the loop moves from the worker to a gate to the reviewer.

The watcher is passive. Attach it, kill it, reattach it, run without it: the loop cannot tell.

Banner states:

| Banner                          | Meaning                                                     |
| ------------------------------- | ----------------------------------------------------------- |
| `▶ IT 4m12s`                    | that phase is running, and has been for four minutes         |
| `✗ fast` with `↺ fix 2`         | the fast gate went red twice and the repair budget is spent  |
| `STALE (loop died in IT)`       | the loop's pid is gone and it never wrote a terminal event   |
| `DONE rc=0` / `rc=40` / `rc=50` | clean terminal: merged or needs-review / needs-human / infra fault |

Liveness is a `kill -0` on the pid carried by each event, not a heartbeat. The IT gate blocks
for up to twenty minutes waiting on a Testcontainers Postgres, so a heartbeat would need a
child process inside the gate, and that child would outlive a `SIGKILL`ed loop and lie. The
terminal `DONE` event is written by the driver's exit-code dispatch rather than by a
`trap EXIT`, precisely so that a killed loop leaves none and the pid check catches it.

To follow a single agent dispatch without the banner, `harness/tail-claude.sh` still works and
takes an explicit log file.

### The reviewer pane

`dispatch_review` runs `claude -p` *without* `--output-format stream-json`, because the
reviewer's stdout **is** the product: its last line carries the `VERDICT:` sentinel the loop
greps for. The review pane therefore stays empty until the reviewer finishes, then shows the
finished markdown. Streaming it live would mean restructuring the dispatch the verdict depends
on, which is a control-plane change for a cosmetic gain.
