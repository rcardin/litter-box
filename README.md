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

`litter-box init`, `litter-box eject`, `litter-box run`, `litter-box watch` and `litter-box tail`
exist and work; see [Getting started](#getting-started) and [Watching a run](#watching-a-run) for
what each subcommand does. A tagged release publishes a binary, a sandbox base image and a
homebrew formula together, from one `.github/workflows/release.yml`; see
[#6](https://github.com/rcardin/litter-box/issues/6) for the packaging work that made that true.

[Install](#install) is the fastest path to a working binary. Check the
[releases page](https://github.com/rcardin/litter-box/releases) and the
[ghcr package page](https://github.com/rcardin/litter-box/pkgs/container/litter-box-base) for what
is published right now rather than trusting a version number written here. Building from source with
scala-cli stays the contributor path (`scala-cli --power package . -o lb --assembly`, see this
repo's own CLAUDE.md for the full command reference).

### Version policy

litter-box ships `0.x`. No stability promise holds until `1.0`: the CLI grammar, the config schema,
the scaffolded files, the base image contract and the log format can all change in a `0.x` release
without a deprecation cycle. Pin an exact version if a change landing under you would be a problem,
and read a release's notes before bumping past it.

## Install

Every published version is on the [releases page](https://github.com/rcardin/litter-box/releases);
the formula in `rcardin/homebrew-tap` tracks whichever one is newest. See
[docs/homebrew-tap-setup.md](docs/homebrew-tap-setup.md) for how that tap and its push credential
are set up, which matters if you are forking this project rather than installing it.

```bash
brew tap rcardin/tap
brew trust rcardin/tap
brew install litter-box
litter-box init
```

The `trust` line is not optional and not a workaround. Homebrew 6 refuses to load a formula from any
tap outside the official ones until you say so explicitly, failing with `Refusing to load formula
rcardin/tap/litter-box from untrusted tap rcardin/tap`. A formula is Ruby that brew executes on your
machine with your permissions, and a tap is a GitHub repository whose owner can change that Ruby at
any time, so brew is asking you to decide whether you trust this project with that. It is the same
question this project asks about the code its own agents write, which is why the answer belongs to
you and the step is documented here rather than hidden inside an install script. Trusting is
recorded once per machine in `~/.homebrew/trust.json`, or under `$XDG_CONFIG_HOME/homebrew` if that
is set. `brew trust --formula rcardin/tap/litter-box` narrows the decision to this one formula
instead of the whole tap, and is the tighter choice if you want it.

Bare `brew install litter-box`, without the `tap` line first, resolves against `homebrew-core`, the
tap brew searches by default, and litter-box is not in it: `homebrew-core` has its own review bar for
what it accepts, and a `0.x` project that changes its own grammar between releases with no stability
promise (see [Version policy](#version-policy) above) is not a fit for that bar today. `brew tap
rcardin/tap` points brew at `github.com/rcardin/homebrew-tap` instead, so the second command
resolves the name there. The repository is named with the `homebrew-` prefix because that is brew's
own naming convention for a tap; typing `rcardin/tap` (the short form) is what makes brew look for
`rcardin/homebrew-tap` in the first place, so the two spellings are not a typo, they are the same
rule applied at two different points.

The formula depends on `openjdk@21` and installs a single self-executing jar as `litter-box`; see
`.github/formula/litter-box.rb.template` for exactly what it does, and
[.github/workflows/release.yml](.github/workflows/release.yml) for how a tag turns into a published
formula, binary and base image together.

### Quickstart

From a fresh install, in the repo you want to run the loop against:

```bash
litter-box init
```

This writes `.litter-box/` and prints warnings and next steps naming exactly what it could not
answer for you; see the file table under [Getting started](#getting-started) for what each file is
and [Configuration](#configuration) for `config.conf`. Fill in the TODOs it printed (a real
`gate.fast` command, a credential in `.litter-box/.env`, the build tool layer in
`.litter-box/Dockerfile`), then take it for a first spin without touching a real issue:

```bash
litter-box run --dry-run
```

`run` is a plain alias for the bare `litter-box` invocation; either spelling starts the loop. Drop
`--dry-run` once you trust what it would do, and see [Running it](#running-it) for what the flag
actually skips.

## Getting started

The contributor path: building a binary from a checkout of this repo, for anyone working on
litter-box itself or who wants one ahead of the next tagged release. If you just want to run
litter-box against your own project, [Install](#install) above is faster.

```bash
scala-cli --power package . -o lb --assembly
```

Then, from the repo you want to run the loop against:

```bash
java -jar /path/to/lb init
```

`init` detects your GitHub remote (via `gh`), whether `build.sbt` is present, and your JDK version,
then writes seven files under `.litter-box/`:

| File | Purpose |
|---|---|
| `config.conf` | the loop's only mandatory config — see [Configuration](#configuration) below |
| `Dockerfile` | `FROM ghcr.io/rcardin/litter-box-base` plus a `TODO` for your JDK and build tool layer (see [The sandbox image](#the-sandbox-image)) |
| `allowlist` | egress hosts the sandbox proxy permits (see [The egress allowlist](#the-egress-allowlist)) |
| `prompts/conventions.md` | the one file you own — spliced into every prompt as `{{CONVENTIONS}}` |
| `.env.example` | the credential the sandboxed worker needs, and any other variable from [Running it](#running-it); meant to be copied to `.env`, never committed |
| `.gitignore` | ignores `logs/` and `.env` inside `.litter-box/` |
| `loop.scala` | names which pipeline this run walks, through the public `LitterBox`/`LoopGraph` API; editing that pipeline is not yet possible, and running the file is not either, since it names a coordinate this project does not publish until [#41](https://github.com/rcardin/litter-box/issues/41) |

It refuses to overwrite an existing `.litter-box/` unless you pass `--force`, and the check happens
before the first file is written, so a refused `init` never leaves a half scaffold.

`Dockerfile` and `config.conf`'s `gate.fast` always carry a `TODO`, whatever was detected, and
`gate.fast` is always written as `"false"`, a command that exists everywhere and always fails, so
iteration one goes honestly red instead of running something nobody confirmed. There are no
build-tool presets: what `init` detected is written into those TODOs as evidence for you to act on,
because seeing a `build.sbt` names the tool and never the command, and reading `java -version` names
what the host builds under and never what the container should carry. See
[Why the middle is a TODO](docs/base-image.md#why-the-middle-is-a-todo-and-not-a-preset) for the two
defects that taught us this.

`init` also prints up to three warnings (what it found or failed to find for your build tool, no
remote found, a JDK other than 21) and three next steps, none of which it can do on your behalf:

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
3. **Create the four labels** the state machine drives on:
   ```bash
   gh label create ready && gh label create in-progress && gh label create blocked && gh label create parked
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
constrains it. Pass `--force` to overwrite one you already ejected. `fix-prompt.md` now splices in
third-party comments left on the issue while a run is in flight, so the fixer can act on steering it
would otherwise never see; a `fix-prompt.md` ejected before this change has no slot for them and
silently drops them until you re-eject it.

### The sandbox image

`.litter-box/Dockerfile` builds `FROM ${BASE_IMAGE}` — a build-tool-free image carrying temurin 21, a
pinned Claude CLI and a non-root user, with no build tool and no credentials baked in. Your
Dockerfile installs the JDK and build tool this project needs and nothing else: it needs no
`ENTRYPOINT`, because all three runners override it and run `gate.fast` (or the agent entrypoint)
through `bash -c`. `init` scaffolds that file with the install layer left as a `TODO`, which is the
whole of what litter-box claims to know about your build.

**A normal run never pulls that base image.** `build-image.sh` builds it locally from
`resources/sandbox/base.Dockerfile` on every run and passes the local tag as `--build-arg
BASE_IMAGE`, so the scaffolded `ARG BASE_IMAGE=ghcr.io/rcardin/litter-box-base:…` default only
applies when you run `docker build` against that Dockerfile by hand. The cost of that choice is a
local Claude-CLI install; what it buys is a sandbox that doesn't depend on pulling a prebuilt base
from ghcr or any registry credentials. `ghcr.io/rcardin/litter-box-base:0.1.0` is already
published, from the `v0.1.0` tag; a normal run still never pulls it, for the reason above, and
each further tag publishes the next version alongside it. See
[docs/base-image.md](docs/base-image.md) for the full contract the image guarantees.

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
  fast      = "scala-cli test ."            # runs INSIDE the sandbox image, so read against its PATH;
                                            # `init` scaffolds `false` plus a TODO, never a guess
  sandboxed = true                          # false runs it on the host instead, with everything your shell has
  timeout   = 900
}
issues.labels { ready = "ready", active = "in-progress", blocked = "blocked", parked = "parked" }
issues.park-on-exhaustion = true          # false opens a needs-human PR instead, the earlier contract
protect  = [".litter-box/**", ".github/**", "CONTEXT.md"]
budgets  { repair = 2, max-patch-bytes = 1000000 }
timeouts { iter = 1800, ci-wait = 900, ci-appear = 300, ci-appear-interval = 10, implement-slack = 300 }
```

`gate.sandboxed` defaults to `true`, so a `config.conf` written before the key existed inherits the
container without ever asking for it, and a `gate.fast` written for the host stops running on the
host the moment the binary is upgraded. A config that leaves the key unsaid therefore gets a
`WARNING` at startup naming both ways to answer it; writing `sandboxed = true` is as good an answer
as `sandboxed = false` and silences it just the same.

`instance-name` earns its place even though litter-box never runs two instances at once:
`start-proxy.sh` does `docker rm -f "$PROXY_NAME"` at startup, before any issue label is
read, so with machine-global names a mistaken second launch kills the running instance's proxy
mid-iteration and no label discipline can prevent it.

## The pipeline

Fixed, not pluggable:

```
PICK → IMPLEMENT → GATE → REPAIR → REVIEW → PR → CI → MERGE
```

One issue per iteration. `PICK` resumes an `in-progress` issue if there is one, and if that issue
is ALSO `parked` with an accepted human reply it resumes straight into a FIX round rather than an
ordinary IMPL. With no issue in progress it resumes the oldest `parked` issue with an accepted
human reply instead, so a run a human already steered finishes before anything new starts; else it
takes the oldest `ready` one. Deterministic, no LLM involved.

`parked` now survives a whole iteration rather than flipping to `active` at pick time: an infra
fault partway through a resumed iteration leaves the issue both `in-progress` and `parked`, exactly
the state the next tick's pick already knows how to read, so a genuinely accepted reply is never
silently dropped by a fault the loop could not have prevented. Several situations follow directly
from that:

- If `gh issue list --label parked` itself fails to read, the whole tick ends `InfraFault` (rc 50):
  nothing is mutated, nothing is dispatched, and the next tick re-decides from scratch once the read
  (hopefully) succeeds. This holds whether or not an issue is already in flight; an earlier version
  of this rule tried to treat an in-flight issue as decidable regardless and degrade instead of
  faulting, which turned out to be unsound (it could fabricate a parked-resume narrative over an
  issue that was never parked at all, or strand the very label it was trying to protect on a repo
  that has not created it yet). The known cost is that a repo which has never created the `parked`
  label faults every crash-resume tick that reaches this read; the fix for that is to create the
  label (or, longer term, to tell "label missing" apart from "read failed" at this call site, which
  `gh` does not do today).
- If the pick cannot tell whether an in-progress issue's own reply is usable (an unreadable
  comments list, or the harness's own GitHub identity cannot be verified), the iteration ends
  `Parked` (rc 60) having mutated nothing at all, exactly like an ordinary parked issue with no
  reply yet. This is expected to be transient: the next tick re-reads the same `gh` calls, and a
  persistently failing read shows up as a repeated log line rather than a silent hang, with the
  same credential/access fix that the rest of the loop already depends on. Because this return
  happens before the ready queue (and every other parked issue) is ever read, a PERSISTENTLY
  failing read on this one issue starves the whole queue behind it; an operator who sees the same
  log line repeat tick after tick can escape it without a code change by removing `in-progress` from
  that one issue by hand (`gh issue edit <#> --remove-label in-progress`), which lets the pick fall
  through to everything else while that issue keeps `parked` and waits for its own read to be fixed
  separately.
- If an in-progress issue's reply IS accepted but the self-repair budget is exhausted
  (`REPAIR_BUDGET=0`, or already spent for that issue), the loop does not sit on the issue forever:
  it drops `in-progress` (keeping `parked`, since the reply really is still waiting) and tries the
  rest of the queue the same tick, UNLESS `DRY_RUN=1`, in which case it does not mutate that label
  either and only reports what it would have picked. If nothing else is available the tick ends
  `Parked` having made that one label edit; if the release edit itself fails, the tick ends `Parked`
  immediately instead of picking a second issue, so the loop never runs two issues in flight at
  once. An operator seeing repeated `Parked` exits with a human reply already posted should read
  that as "raise `REPAIR_BUDGET`", not as "the loop is still waiting on a reply"; the per-tick log
  line names which of the two is actually true.

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
  `REQUEST_CHANGES`. Exhausting it on that generic failure parks the issue by default
  (`issues.park-on-exhaustion`) rather than looping forever; a guard rejection or an empty fix
  still terminates straight to `needs-human` regardless of the knob.
- **Only vouched-for accounts can resume a parked issue.** Every marker comment the harness itself
  posts (the initial park comment, and the one it posts the moment a resumed reply is actually spent
  on a fresh attempt) bounds the resume probe the same way: only a reply after the newest one, from
  an `OWNER`, `MEMBER` or `COLLABORATOR` association, counts. A comment from any other account is
  logged and ignored, so a public issue thread cannot be used to force unbounded park/resume
  dispatch cycles.
- **Infra faults are not code failures.** A Docker outage, a timed-out worker or a failed merge
  exits `50` with the budget untouched and the issue left `in-progress`, so the next tick resumes it.
  A crashed sandbox can never burn repair budget or trigger a FIX.
- **`STOP.md` is a manual kill switch.** The loop reads it and never writes it.
- **The sandbox carries no credentials.** Non-root user, egress only through an allowlisting proxy.

## Exit codes

Each iteration ends in one of eight states. The driver maps them to a process exit code:

| State | rc | Process | Meaning |
|---|---|---|---|
| Success | 0 | *continues* | Merged, or PR opened → `needs-review` |
| ManualStop | 10 | 0 | `STOP.md` present |
| Idle | 11 | 0 | No `ready`, `in-progress` or `parked` issue |
| DryRun | 20 | 0 | `DRY_RUN=1` stop point, before any mutation |
| NothingMade | 30 | 1 | Empty patch — nothing staged |
| NeedsHuman | 40 | *continues* | Guard rejection, empty fix, CI red, or budget spent with `issues.park-on-exhaustion` false. PR left open for audit |
| InfraFault | 50 | 50 | Infra problem. Issue stays `in-progress` |
| Parked | 60 | 60 | Budget spent on a generic gate/review failure with `issues.park-on-exhaustion` true (the default). No PR; issue labelled `parked` instead of `needs-human`. A later tick with an accepted human reply on the issue resumes it with a FIX |

`Success` and `NeedsHuman` are the only two that let the driver advance to the next iteration;
every other state exits the process immediately. The loop runs at most `MAX_ITERS` iterations.

## Running it

```bash
scala-cli test .            # the test suite: no Docker, no gh, no credentials
scala-cli run . -- --help   # usage: init, eject, watch, tail, --dry-run (same binary as `litter-box`)
scala-cli run .             # the loop itself
```

Environment variables still configure the loop — a flag beats the matching variable where both
exist. See [Getting started](#getting-started) for `init`, `eject`, `--dry-run` and `--help`, and
[Watching a run](#watching-a-run) for `watch` and `tail`.

| Variable | Default | Purpose |
|---|---|---|
| `MAX_ITERS` | `1` | Iterations before the driver stops |
| `DRY_RUN` | `0` | `1` renders the worker prompt, then stops before any mutation |
| `REPAIR_BUDGET` | `2` | Fix attempts per issue |
| `MAX_PATCH_BYTES` | `1000000` | Oversized-patch guard |
| `GATE_CMD` | `false` | The gate (overrides `gate.fast`); the default fails on purpose, because litter-box never guesses your build command. Exporting it also forces `gate.sandboxed` to false — the gate runs on the host with everything your shell has, not in the container — and skips the whole Docker preflight that would have built that container; setting it in `.litter-box/.env` only changes the command |
| `GATE_TIMEOUT` | `900` | Gate timeout (seconds) |
| `ITER_TIMEOUT` | `1800` | Worker dispatch timeout |
| `IMPLEMENT_SLACK` | `300` | Added on top of `ITER_TIMEOUT` for the Implement node's own timeout; raise it on a host with no `timeout`/`gtimeout` binary, where the worker runs unbounded and this is the only backstop left |
| `CI_WAIT_TIMEOUT` / `CI_APPEAR_TIMEOUT` / `CI_APPEAR_INTERVAL` | `900` / `300` / `10` | CI polling |
| `NTFY_TOPIC` | — | ntfy.sh topic for notifications |

`IMPL_CMD`, `FIX_CMD`, `REVIEW_CMD`, `NOTIFY_CMD`, `CI_WAIT_CMD`, `CI_APPEAR_CMD` and `MERGE_CMD`
are test seams: each replaces one subprocess so the loop can be driven without Docker or GitHub.

Preflight requires `gh` and `claude` on `PATH` (but not `jq`, which only `watch` and `tail` need and
which the loop never calls), plus whatever tool the first word of your gate command names (that
probe is skipped when the gate runs sandboxed, because the tool lives in the image rather than on
the host), and either `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY` for the sandboxed worker,
exported or written in `.litter-box/.env`. That file is
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

`watch` and `tail` (below) need `jq` and nothing else: no credential, no Docker, no `gh`. It is
checked when you run them rather than at loop startup, so a missing `jq` never stops a run.

### Watching a run

```bash
litter-box watch            # pinned phase banner over the log the current phase is writing
litter-box tail             # follow the newest worker log, one readable line per stream-json event
litter-box watch <status.jsonl>   # or point either at a specific file
litter-box tail <logfile>
```

Both are passive: they read `status.jsonl` and the log files the run already writes, never write to
the repo, never call `gh`, and the loop cannot tell whether anything is attached. Attach, kill and
reattach at any point in a run. Run them from anywhere inside the repo you want to watch — the repo
is found the same way the loop finds it, with `git rev-parse --show-toplevel`, and a relative path
you pass is resolved against the directory you typed it in.

`watch` reads your repo's `log-dir` out of `.litter-box/config.conf`, so a repo that moved its logs
gets a watcher that follows.

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
               runners, egress proxy), observe/ (watch.sh, tail-claude.sh and their lib/)
docs/          reference docs, e.g. base-image.md
sandbox/test/  shell tests of resources/sandbox (needs Docker) and resources/observe, run manually
```

Prompt skeletons no longer live in a consumer repo's `prompts/` directory — they ship inside the
artifact under `resources/prompts/`, with `.litter-box/prompts/` as the per-repo override written by
`litter-box eject` (see [Getting started](#getting-started)).

The sandbox scripts ship the same way, for the same reason: they are protocol, not configuration, so
a consumer carrying a copy would carry one that rots the moment litter-box updates. On each run they
are unpacked to `~/.cache/litter-box/sandbox/<digest>`, keyed by the contents so an upgrade lands in
a new directory on its own. A consumer owns exactly two files of the sandbox — `.litter-box/Dockerfile`
(what the gate image is built from) and `.litter-box/allowlist` (what it may talk to).

So do the observability scripts, under `~/.cache/litter-box/observe/<digest>`, and there the reason
is sharper still: `watch.sh` PARSES `status.jsonl`, so a scaffolded copy would silently misread a
renamed field in every repo that ever ran `init`. Nobody types a content digest, so they get a front
door instead — see [Watching a run](#watching-a-run).

`Machine` is a pure decision function over a `using` clause of capability traits (`Caps.scala`);
`Live.scala` holds every real side effect. That is what lets the whole suite run in memory.

### The egress allowlist

`.litter-box/allowlist` is one host per line, matched against the CONNECT hostname; a line starting
with `#` is a comment. `init` seeds it with the hosts a JVM build resolves artifacts from plus
`api.anthropic.com`, and whatever is not named there is refused by the proxy with `403 Filtered`.
The file replaces the copy that ships in the artifact rather than extending it, so an edit can
narrow egress as well as widen it, and nothing at run time enforces that it stays a superset of the
shipped list: add the hosts your build needs, keep the seeded ones, and expect a missing one to
surface as a resolution failure inside the gate rather than as a network timeout.

The list is baked into the proxy image rather than read at run time, deliberately: the copy in the
image is what the proxy enforces from preflight to teardown, so the fence stays fixed for the whole
of a run and a worker editing `.litter-box/allowlist` cannot move the fence it is running behind.
An edit lands at the next image build instead, and the loop needs nothing from you to get there:
preflight runs `build-image.sh` immediately before `start-proxy.sh`, and that build bakes whatever
the file currently says into the proxy image. `start-proxy.sh` is what proves it took. It recreates
the container from the current image and reads back the list in force, so the run proceeds only
once the running proxy demonstrably enforces your file. A proxy found enforcing anything else, the
case a `start-proxy.sh` invoked on its own has to cover, gets one rebuild under it; if the two
still disagree after that, the run stops instead of gating against a fence nobody wrote.

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
