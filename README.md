# litter-box

A distrustful autonomous coding loop for JVM projects.

It picks one labelled GitHub issue, dispatches a fresh `claude -p` worker inside a
network-restricted Docker sandbox, gates the result, has a **cold independent reviewer** judge the
diff, opens a PR, and lets CI decide. The model never picks its own task and never reports its own
success.

Extracted from the `harness/` directory of
[functional-event-sourcing-with-yaes](https://github.com/rcardin/functional-event-sourcing-with-yaes)
and being generalized into an installable tool. See [#1](https://github.com/rcardin/litter-box/issues/1)
for the design record.

## Status

`litter-box init` and `litter-box eject` exist and work — see [Getting started](#getting-started).
What is still missing is a published binary: there is no `brew install litter-box` yet, so for now
you build one from a checkout. Tracked as [#6](https://github.com/rcardin/litter-box/issues/6).

## Getting started

There is no published binary yet, so build one from a checkout of this repo:

```bash
scala-cli --power package . -o lb --assembly
```

Then, from the repo you want to run the loop against:

```bash
java -jar /path/to/lb init
```

`init` detects your GitHub remote (via `gh`), whether `build.sbt` is present, and your JDK version,
then writes six files under `.litter-box/`:

| File | Purpose |
|---|---|
| `config.conf` | the loop's only mandatory config — see [Configuration](#configuration) below |
| `Dockerfile` | `FROM ghcr.io/rcardin/litter-box-base` plus your build tool layer — see [The sandbox image](#the-sandbox-image) |
| `allowlist` | egress hosts the sandbox proxy permits (see [The egress allowlist](#the-egress-allowlist)) |
| `prompts/conventions.md` | the one file you own — spliced into every prompt as `{{CONVENTIONS}}` |
| `.env.example` | the credential the sandboxed worker needs, and any other variable from [Running it](#running-it); meant to be copied to `.env`, never committed |
| `.gitignore` | ignores `logs/` and `.env` inside `.litter-box/` |

It refuses to overwrite an existing `.litter-box/` unless you pass `--force`, and the check happens
before the first file is written, so a refused `init` never leaves a half scaffold.

If no `build.sbt` is found, both `Dockerfile` and `config.conf`'s `gate.fast` carry a `TODO` and
`gate.fast` is written as `"false"` rather than a guessed preset — litter-box will not run a build
tool nobody has confirmed. Sbt is the only preset today; adding another (Gradle, Maven, ...) is a
PR — see [Adding a preset](docs/base-image.md#adding-a-preset).

`init` also prints up to three warnings (no remote found, no build tool detected, a JDK other than
21) and three next steps, none of which it can do on your behalf:

1. **Fill in `.litter-box/prompts/conventions.md`.** It is the highest-value file here: everything
   true only of your project — layout, test tiers, lint rules, "anything that has bitten you" — is
   spliced into every worker, fixer and reviewer prompt as `{{CONVENTIONS}}`. The prompt skeletons
   themselves, the protocol that keeps the loop honest, ship inside the litter-box artifact, not in
   your repo.
2. **Provide a credential.** `cp .litter-box/.env.example .litter-box/.env` and fill in
   `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY`. The loop reads that file at startup and passes
   what it finds to the sandboxed worker, fixer and reviewer. Exporting the variable instead works
   just as well; the file takes any other variable from [Running it](#running-it) too, and which one
   wins is the layering in [Configuration](#configuration).
3. **Create the three labels** the state machine drives on:
   ```bash
   gh label create ready && gh label create in-progress && gh label create blocked
   ```

### Overriding a prompt skeleton

The four prompt skeletons — `iterate-prompt.md`, `fix-prompt.md`, `review-prompt.md`,
`grill-issue-prompt.md` — ship inside the jar. A repo that genuinely needs to change one, not just
its conventions, ejects it:

```bash
java -jar /path/to/lb eject iterate-prompt.md
```

This copies the built-in skeleton to `.litter-box/prompts/iterate-prompt.md`, which then wins over
the built-in for every later run. `.litter-box/prompts/**` sits inside `.litter-box/**`, which the
protected-path floor always covers, so a worker under harness cannot rewrite the prompt that
constrains it. Pass `--force` to overwrite one you already ejected.

### The sandbox image

`.litter-box/Dockerfile` builds `FROM ghcr.io/rcardin/litter-box-base` — a build-tool-free image
carrying temurin 21, a pinned Claude CLI and a non-root user, with no build tool and no credentials
baked in. Your Dockerfile installs the build tool and nothing else: it needs no `ENTRYPOINT`, because
all three runners override it and run `gate.fast` (or the agent entrypoint) through `bash -c`. **Nothing has been published to ghcr yet** — the first publish happens when a
tag is cut. See [docs/base-image.md](docs/base-image.md) for the full contract the image guarantees
and how to add a build-tool preset beyond sbt.

## Configuration

One HOCON file at the repo root, `.litter-box/config.conf`. It is mandatory: with no config the loop
exits `50` (infra fault) and names `litter-box init`, rather than guessing and acting on the wrong
labels. Anything omitted falls back to the reference schema in `src/Settings.scala`; every knob
loop.sh took from an env var (`GATE_CMD`, `REPAIR_BUDGET`, `ITER_TIMEOUT`, ...) still overrides its
config key for a single run. The full precedence, `.litter-box/.env` and its two qualifications
included, is stated once in the `Settings` object's scaladoc in `src/Settings.scala`, so that this
README and `ARCHITECTURE.md` cannot drift from the code that applies it.

```hocon
instance-name = "litter-box"          # namespaces the Docker image/network/proxy/cache names
conventions   = ".litter-box/prompts/conventions.md"  # spliced into the worker, fixer and reviewer prompts as {{CONVENTIONS}}
stop-file     = "STOP.md"
log-dir       = ".litter-box/logs"

gate {
  fast      = "sbt -Werror compile test"   # runs INSIDE the sandbox image, so read against its PATH
  sandboxed = true                          # false runs it on the host instead, with everything your shell has
  timeout   = 900
}
issues.labels { ready = "ready", active = "in-progress", blocked = "blocked" }
protect  = [".litter-box/**", ".github/**", "CONTEXT.md"]
budgets  { repair = 2, max-patch-bytes = 1000000 }
timeouts { iter = 1800, ci-wait = 900, ci-appear = 300, ci-appear-interval = 10 }
```

`instance-name` earns its place even though litter-box never runs two instances at once:
`start-proxy.sh` does `docker rm -f "$PROXY_NAME"` at startup, before any issue label is
read, so with machine-global names a mistaken second launch kills the running instance's proxy
mid-iteration and no label discipline can prevent it.

## The pipeline

Fixed, not pluggable:

```
PICK → IMPLEMENT → GATE → REPAIR → REVIEW → PR → CI → MERGE
```

One issue per iteration. `PICK` resumes an `in-progress` issue if there is one, else takes the
oldest `ready` one — deterministic, no LLM involved.

## The safety spine

This is the product. Everything else is plumbing.

- **The worker never picks its own work.** The issue comes from a label query, not from the model.
- **Protected-path patch guard.** A patch touching any glob in the config's `protect` list is
  rejected unapplied. A consumer `protect` list can only widen the protection, never narrow it: the
  reference entries are unioned in as a floor, so the list always covers `.litter-box/**`, i.e. the
  config file that defines the list. The loop cannot be talked into loosening its own guard, editing
  its own CI, or rewriting the conventions it is judged against.
- **Test-tamper check.** The diff is measured against `origin/main` with `git apply --numstat` and
  the result is handed to the reviewer, which catches the classic failure mode: deleting a failing
  test to go green.
- **Cold independent reviewer.** A separate `claude -p` with none of the worker's context. It sees
  the diff, the acceptance criteria, the conventions and the tamper report, and must emit a
  `VERDICT:` sentinel. **No sentinel is treated as REQUEST_CHANGES**, never as approval.
- **Bounded self-repair.** A shared budget (default 2) per issue, spent by a RED gate *or* a
  `REQUEST_CHANGES`. A pathological issue terminates instead of looping.
- **Infra faults are not code failures.** A Docker outage, a timed-out worker or a failed merge
  exits `50` with the budget untouched and the issue left `in-progress`, so the next tick resumes it.
  A crashed sandbox can never burn repair budget or trigger a FIX.
- **`STOP.md` is a manual kill switch.** The loop reads it and never writes it.
- **The sandbox carries no credentials.** Non-root user, egress only through an allowlisting proxy.

## Exit codes

Each iteration ends in one of seven states. The driver maps them to a process exit code:

| State | rc | Process | Meaning |
|---|---|---|---|
| Success | 0 | *continues* | Merged, or PR opened → `needs-review` |
| ManualStop | 10 | 0 | `STOP.md` present |
| Idle | 11 | 0 | No `ready` or `in-progress` issue |
| DryRun | 20 | 0 | `DRY_RUN=1` stop point, before any mutation |
| NothingMade | 30 | 1 | Empty patch — nothing staged |
| NeedsHuman | 40 | *continues* | Budget spent, guard rejection, or CI red. PR left open for audit |
| InfraFault | 50 | 50 | Infra problem. Issue stays `in-progress` |

`Success` and `NeedsHuman` are the only two that let the driver advance to the next iteration;
every other state exits the process immediately. The loop runs at most `MAX_ITERS` iterations.

## Running it

```bash
scala-cli test .            # the test suite: no Docker, no gh, no credentials
scala-cli run . -- --help   # usage: init, eject, --dry-run (same binary as `litter-box`)
scala-cli run .             # the loop itself
```

Environment variables still configure the loop — a flag beats the matching variable where both
exist. See [Getting started](#getting-started) for `init`, `eject`, `--dry-run` and `--help`.

| Variable | Default | Purpose |
|---|---|---|
| `MAX_ITERS` | `1` | Iterations before the driver stops |
| `DRY_RUN` | `0` | `1` renders the worker prompt, then stops before any mutation |
| `REPAIR_BUDGET` | `2` | Fix attempts per issue |
| `MAX_PATCH_BYTES` | `1000000` | Oversized-patch guard |
| `GATE_CMD` | `sbt -Werror compile test` | The gate (overrides `gate.fast`). Exporting it also skips the whole Docker preflight; setting it in `.litter-box/.env` only changes the command |
| `GATE_TIMEOUT` | `900` | Gate timeout (seconds) |
| `ITER_TIMEOUT` | `1800` | Worker dispatch timeout |
| `CI_WAIT_TIMEOUT` / `CI_APPEAR_TIMEOUT` / `CI_APPEAR_INTERVAL` | `900` / `300` / `10` | CI polling |
| `NTFY_TOPIC` | — | ntfy.sh topic for notifications |

`IMPL_CMD`, `FIX_CMD`, `REVIEW_CMD`, `NOTIFY_CMD`, `CI_WAIT_CMD`, `CI_APPEAR_CMD` and `MERGE_CMD`
are test seams: each replaces one subprocess so the loop can be driven without Docker or GitHub.

Preflight requires `gh`, `sbt` and `claude` on `PATH`, and either `CLAUDE_CODE_OAUTH_TOKEN` or
`ANTHROPIC_API_KEY` for the sandboxed worker, exported or written in `.litter-box/.env`. That file is
not credentials-only: any variable in the table above can live in it, and it reaches the credential
check, the config layering and the seams by the same door an export does. Two things it cannot do,
both of them deliberate:

- **It cannot skip the sandbox preflight.** A `GATE_CMD` there sets the gate command and stops
  there; only an exported `GATE_CMD` is an operator saying "no sandbox for this run", because the
  file is permanent and untracked and would otherwise switch the preflight off for every future run,
  silently.
- **An empty export does not shadow it.** `FOO=` exported is an absent `FOO` everywhere else in the
  loop, so it loses to the file's value rather than blanking it — which is what makes a sourced
  `.env.example` or a CI `env:` entry built from a missing secret harmless.

For which layer wins in general, see [Configuration](#configuration).

### Issue labels

`ready` → `in-progress` → `needs-review` or `needs-human`. `blocked` issues carry a
`Blocked-by: #N` line and are flipped to `ready` when their dependency closes. `class-1` marks an
issue as eligible for auto-merge once CI is green.

## Layout

```
src/           the loop: Machine (state machine), Live (handlers), Caps, Domain, Main, Init, Cli, Prompts
test/          the suite, plus golden/ — the frozen log-line contract
resources/     shipped inside the artifact: prompts/ (built-in skeletons), scaffold/ (init's
               templates), sandbox/ (the Docker sandbox: base image, gate, agent and reviewer
               runners, egress proxy)
docs/          reference docs, e.g. base-image.md
sandbox/test/  Docker-dependent shell tests of resources/sandbox, run manually
lib/           shell helpers for the watch UI
watch.sh       live run monitor, reads the log stream and status.jsonl
```

Prompt skeletons no longer live in a consumer repo's `prompts/` directory — they ship inside the
artifact under `resources/prompts/`, with `.litter-box/prompts/` as the per-repo override written by
`litter-box eject` (see [Getting started](#getting-started)).

The sandbox scripts ship the same way, for the same reason: they are protocol, not configuration, so
a consumer carrying a copy would carry one that rots the moment litter-box updates. On each run they
are unpacked to `~/.cache/litter-box/sandbox/<digest>`, keyed by the contents so an upgrade lands in
a new directory on its own. A consumer owns exactly two files of the sandbox — `.litter-box/Dockerfile`
(what the gate image is built from) and `.litter-box/allowlist` (what it may talk to).

`Machine` is a pure decision function over a `using` clause of capability traits (`Caps.scala`);
`Live.scala` holds every real side effect. That is what lets the whole suite run in memory.

### The egress allowlist

`.litter-box/allowlist` is one host per line, matched against the CONNECT hostname; a line starting
with `#` is a comment. `init` seeds it with the hosts a JVM build resolves artifacts from plus
`api.anthropic.com`, and whatever is not named there is refused by the proxy with `403 Filtered`.
The file wins over the copy that ships in the artifact, so it can only be widened by editing it,
never bypassed: add the hosts your build needs, and expect a missing one to surface as a resolution
failure inside the gate rather than as a network timeout.

The list is baked into the proxy image rather than read at run time, deliberately: a worker editing
its repo can reach `.litter-box/allowlist`, and a fence read from a file the sandbox can write is
not a fence. Applying an edit is therefore `start-proxy.sh`'s job, and it needs nothing from you:
every run recreates the proxy container from the current image, and a container found enforcing an
allowlist other than yours has the image rebuilt under it before the loop is allowed to proceed. If
the two still disagree after that rebuild, the run stops there instead of gating against a fence
nobody wrote.

### The log contract

The operator log stream is parsed by `watch.sh`, so its wording is asserted behaviour, not
decoration. `LogParitySpec` freezes it whole against the golden files in `test/golden`. To change a
log line deliberately:

```bash
UPDATE_GOLDEN=1 scala-cli test .
git diff test/golden          # read this. it IS the contract change.
```

## Build

Scala 3.8.3 on JDK 21 LTS, built with scala-cli. Deliberately **not** sbt: the threat model
distrusts agent-authored build files, so the loop never couples to the build of the project it is
working on.

## License

MIT. See [LICENSE](LICENSE).
