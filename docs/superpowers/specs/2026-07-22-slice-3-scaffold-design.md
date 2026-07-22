# Slice 3: scaffold — `init`, layered prompts, base image

Design for [issue #4](https://github.com/rcardin/litter-box/issues/4). Blocked-by #3 (closed).

## Problem

litter-box still assumes it is the repo it works on. Three couplings say so:

1. **No way in.** A consumer repo has no `.litter-box/config.conf` and no way to get one. Slice 2
   made the loop read config from a file; nothing writes that file.
2. **Prompts are project-specific.** `prompts/iterate-prompt.md` names `CONTEXT.md`'s onion layout,
   the US-1 `Register` slice, Testcontainers, `-Werror` and `@nowarn`. A consumer inherits another
   project's conventions as protocol.
3. **The image ships sbt.** `sandbox/Dockerfile:48` is `ENTRYPOINT ["sbt"]`, so the gate container
   is an sbt container. A Gradle consumer has to fork the Dockerfile.

This slice makes litter-box usable by someone who did not write it.

## Non-goals

- **Sandbox shell scripts stay repo-root-relative.** `Main` resolves `sandbox/build-image.sh` under
  the consumer root; after this slice it still does. Shipping executable scripts out of a jar needs
  runtime extraction and exec bits, which is a fourth subsystem. Filed as a follow-up.
- **No ghcr push from this branch.** The workflow is written; the push happens on a tag.
- **No Gradle/Maven preset.** The image contract is documented so one can be contributed. We never
  scaffold a preset we have not run a loop against.

## 1. CLI

`Main` gains a dispatcher. New `src/Cli.scala` holds a pure parser so the arg table is testable
without a process.

```scala
enum Command:
  case Loop(dryRun: Boolean)
  case Init(force: Boolean)
  case Eject(what: String)
  case Help

object Cli:
  def parse(args: List[String]): Either[String, Command]
  val Usage: String
```

| Invocation | Command |
|---|---|
| (none) | `Loop(dryRun = false)` |
| `--dry-run` | `Loop(dryRun = true)` |
| `init` / `init --force` | `Init(force)` |
| `eject prompts/iterate-prompt.md` | `Eject(what)` |
| `--help`, `-h`, `help` | `Help` |
| anything else | `Left(msg)` |

`@main def litterBoxLoop(args: String*)` parses first. A `Left` prints the message and the usage
and exits 1, the same code `die` uses for a broken invocation.

`Init` and `Eject` return before every preflight: no `gh`/`sbt`/`claude` PATH probe, no config
load, no Docker. `init` on a repo with no config is the whole point, so it cannot require one.

`Loop`'s only change: `dryRun = flags.dryRun || env("DRY_RUN") == "1"`. The env var keeps working
because operators and `sandbox/test/*` already use it; the flag can only turn dry-run on, never
off, so a flag can never quietly disarm an operator's `DRY_RUN=1`.

## 2. Layered prompts

### Storage

The four skeletons move to `resources/prompts/`, added to `project.scala` as
`//> using resourceDir ./resources`. They ship in the artifact, not in the consumer repo.

`Template` (Domain.scala) gains a fourth case, `GrillIssue extends Template("grill-issue-prompt.md")`.
Nothing dispatches it yet; it gets a resource and an eject path because a consumer overriding three
of four prompts and silently failing on the fourth is a worse outcome than an unused enum case.

### Resolution

New `src/Prompts.scala`:

```scala
object Prompts:
  val EjectDir = ".litter-box/prompts"
  def resolve(root: Path, t: Template): String     // disk override, else classpath resource
  def builtIn(t: Template): String                 // classpath resource only
  def eject(root: Path, what: String, force: Boolean): Either[String, Path]
```

`resolve` checks `root/.litter-box/prompts/<fileName>`; if it is a regular file, that wins.
Otherwise the classpath resource `/prompts/<fileName>`. A missing resource is a broken build, not a
user error, so it throws.

`LiveHarnessFs.readTemplate` delegates to `Prompts.resolve`. `Machine` is untouched: it already
asks the handler for a template and does not know where one comes from.

`Main`'s preflight loop over `Template.values` currently `die`s on a missing
`prompts/<file>.md`. It becomes a no-op check against `Prompts.resolve` (which cannot fail for a
built-in), so a consumer repo with no `prompts/` directory starts.

### Slots

The issue names five — `{{ISSUE}}`, `{{PROTECTED}}`, `{{GATE}}`, `{{CONVENTIONS}}`, `{{TAMPER}}` —
but two more already exist in the render sites and must be carried: `{{FAILURE}}` (fix) and
`{{DIFF}}` (review). Seven total.

`Machine.renderTemplate` replaces only the keys it is given and leaves any other `{{KEY}}` line
verbatim, which means an unsupplied slot ships to the model as literal braces. All three render
sites therefore pass every slot the template they render can contain:

| Site | Template | Slots passed |
|---|---|---|
| `Machine.scala:178` | Iterate | PROTECTED, GATE, CONVENTIONS, ISSUE |
| `Machine.scala:241` | Fix | PROTECTED, GATE, CONVENTIONS, ISSUE, FAILURE |
| `Machine.scala:310` | Review | PROTECTED, GATE, CONVENTIONS, ISSUE, TAMPER, DIFF |

`PROTECTED` renders `cfg.protect` as a bulleted list; `GATE` is `cfg.gateCmd`. Both come off
`Config`, so an operator's `GATE_CMD` override reaches the prompt the same way it reaches the gate.

**Splice order matters and changes.** `renderTemplate` folds left, so a slot spliced early has its
injected text scanned by every later pass. Today `ISSUE` goes first, which means an issue body
containing the literal `{{GATE}}` would have that line replaced by the gate command. The order
above puts the config-derived, trusted slots (PROTECTED, GATE, CONVENTIONS) ahead of every slot
carrying text the harness did not write (ISSUE from GitHub, FAILURE and TAMPER and DIFF derived
from agent output), so injected content is never rescanned for slots. Not a privilege escalation —
every value involved is already visible to the agent — but it stops a prompt from being reshaped by
its own inputs.

A test asserts no `{{` survives any render. That is the guard against a skeleton edit adding a slot
nobody splices.

### Content split

The real work. Each skeleton splits three ways.

*Protocol, verbatim in the skeleton:* one iteration / no memory; implement EXACTLY the issue below;
no branch switch, no push, no PR, no `gh`; do not weaken, delete or silence existing tests; a test
per acceptance criterion; "you do not report success — the gate, the reviewer, and CI do";
`VERDICT: APPROVE` / `VERDICT: REQUEST_CHANGES`; the `{{TAMPER}}` splice.

*Templated:* the protected-paths sentence (shape is protocol, list is `{{PROTECTED}}`); the gate
command in the done criteria (`{{GATE}}`); the fast-tier-vs-CI-tier concept stays protocol, the
concrete paths go to conventions.

*Project, moves to `conventions.md`:* the onion layout and domain-error rule; "use the existing US-1
`Register` slice as your template"; Testcontainers/Postgres; `src/it` vs `src/test`; `-Werror`;
`@nowarn` (a Scala-only spelling of a tamper dodge).

A consumer who mangles the `VERDICT:` contract or the no-`gh` rule breaks the machine silently,
with no error — which is exactly why those lines live in the skeleton and not in the file the
consumer edits.

### Eject

`litter-box eject prompts/iterate-prompt.md` writes the built-in to
`.litter-box/prompts/iterate-prompt.md`. Accepts the four names with or without the `prompts/`
prefix. Refuses to overwrite an existing file without `--force`. Ejected files win over built-ins,
which is `resolve`'s order.

## 3. `init`

New `src/Init.scala`, split pure/IO the way `Settings` is:

```scala
final case class Detected(
    hasGitHubRemote: Boolean,
    remote: Option[String],
    buildTool: Option[BuildTool],   // Sbt today; None means "we did not recognise it"
    jdk: String
)

object Init:
  def plan(d: Detected): List[(String, String)]   // relPath -> content, pure and total
  def warnings(d: Detected): List[String]
```

### Detection

| Fact | How |
|---|---|
| repo root | `git rev-parse --show-toplevel` (reuse `Main.resolveRepoRoot`) |
| GitHub remote | `gh repo view --json nameWithOwner` |
| sbt | `build.sbt` is a regular file at the root |
| JDK | `java -version` major, defaulted to 21 when unreadable |

None of these is fatal. A missing `gh` remote is a warning in the next-steps output, because a
repo can gain a remote after scaffolding.

### Files written

All under `.litter-box/`:

| File | Content |
|---|---|
| `config.conf` | the schema with detected values, commented against `Settings.Reference` |
| `Dockerfile` | `FROM ghcr.io/rcardin/litter-box-base:<pin>` + build tool + `ENTRYPOINT` |
| `allowlist` | the egress allowlist, seeded from `sandbox/proxy/allowlist` |
| `prompts/conventions.md` | a skeleton the consumer fills in, with prompts for what belongs |
| `.env.example` | `CLAUDE_CODE_OAUTH_TOKEN=` / `ANTHROPIC_API_KEY=`, no values |
| `.gitignore` | `logs/`, `.env` |

The scaffolded `config.conf` sets `conventions = ".litter-box/prompts/conventions.md"`.
`Settings.Reference` keeps `CONTEXT.md` as its default, so a slice-2 repo that already has one is
unaffected. One key, one file: there is no second thing named conventions.

### `--force` and atomicity

An existing `.litter-box/` without `--force` is an error, rc 1, **and nothing is written**. The
directory check happens before the first write, so a refused `init` cannot leave a half-scaffold.

### Non-sbt repos

Everything is still written. The Dockerfile carries `# TODO: install your build tool` where the
sbt install would be, `gate.fast` is a commented-out TODO in `config.conf`, and stderr says out
loud that the build tool was not detected. rc stays 0: the files are correct, the operator has one
line to fill in. We never scaffold a preset we have not run a loop against.

### Output

Next steps, printed on success: copy `.env.example` to `.env` and fill it; create the three labels
(`gh label create ready|in-progress|blocked`); fill in `prompts/conventions.md`; plus any warning
from `Init.warnings`.

## 4. Base image

### The split

`sandbox/base.Dockerfile` (new) carries temurin 21, `curl`/`git`/`ca-certificates`/`gnupg`, the
pinned Claude CLI (`ARG CLAUDE_VERSION`, bumped deliberately), and the non-root `gate` user with
its coursier cache dir. **Zero credentials.** No build tool. No `ENTRYPOINT`.

`sandbox/Dockerfile` shrinks to `ARG BASE_IMAGE` + the sbt install + `ENTRYPOINT ["sbt"]`.

### Chicken-and-egg

`BASE_IMAGE` defaults to a locally built tag (`${INSTANCE_NAME}-base:v1`), and `build-image.sh`
builds `base.Dockerfile` before `Dockerfile`. Local development never depends on ghcr existing,
and there is one source of truth for the base layer. The *scaffolded consumer* Dockerfile points at
`ghcr.io/rcardin/litter-box-base:<pin>` instead, because a consumer has no `sandbox/` directory.

### Allowlist

`init` writes `.litter-box/allowlist`, so something has to read it. The file is `COPY`ed into the
proxy image at build time (`sandbox/proxy/Dockerfile:5`), not read at startup, so the override
belongs in `build-image.sh`: when `.litter-box/allowlist` exists, the proxy image is built from a
staged context carrying it instead of `sandbox/proxy/allowlist`.

### Publishing

`.github/workflows/base-image.yml` builds and pushes `ghcr.io/rcardin/litter-box-base` on a tag
push, with the Claude CLI version as an explicit `ARG`. This branch does not push anything.

`docs/base-image.md` documents the contract the image guarantees — JDK, the `gate` user and its
uid, `WORKDIR`, what is absent (build tool, entrypoint, credentials) — so a Gradle or Maven preset
is a PR someone can send.

## 5. Testing

New specs: `test/CliSpec.scala`, `test/InitSpec.scala`, `test/PromptsSpec.scala`.

From the issue:

- `init` against a fixture repo produces a `.litter-box/` that `Settings.loadFile` accepts and
  `Settings.parse` reads without throwing.
- `init` twice without `--force` fails and changes nothing (byte-compare the tree before/after).
- A non-sbt fixture gets the TODO Dockerfile and the warning, not a silent sbt preset.
- Prompt splicing: all five slots substituted; an ejected file overrides the built-in.
- The skeleton still contains the `VERDICT:` contract after splicing with an empty
  `conventions.md`.

Added:

- `Cli.parse` table, including the unknown-argument `Left`.
- No `{{` survives any of the three render sites.
- The scaffolded `config.conf` agrees with `Settings.Reference` on every key it sets — the same
  pin `SettingsSpec` already applies to `Config()`'s case-class defaults.
- `eject` refuses to clobber without `--force`.

The suite stays Docker-free and credential-free, so CI is unchanged.

## Done

- `scala-cli test .` green.
- `litter-box init` on a clean sbt repo, then `scala-cli run . -- --dry-run`, reaches the dry-run
  stop without touching git or labels. That is `LoopExit.DryRun` (rc 20) internally; the *process*
  exits 0, because `driverAction` maps `DryRun` to `Exit(0)`. The issue's "rc 20" is the loop code,
  not the shell's `$?`.
- Base image workflow present and the Dockerfile split verified by a local build.
