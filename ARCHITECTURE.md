## Architecture

### The pure machine over capabilities

The central split, and the reason the whole suite runs in memory:

- `src/Caps.scala` — capability traits (`GitHub`, `Git`, `HarnessFs`, `GateRunner`, `AgentDispatch`,
  `Notify`, `Log`, `StatusLog`, `Clock`), passed as `using` context parameters. It also holds
  `HostGateRunner`, which is not a capability of its own but a case class wrapping a `GateRunner`
  (pointedly not a subtype of one, so it can never win `using GateRunner` resolution): same
  mechanics on the host side of the sandbox boundary, a second type purely so `Main.gateRunners`
  has to wire each tier by name from its own `LiveGateRunner` rather than reuse one instance (the
  why is in the `HostGateRunner` scaladoc, issue #11).
- `src/Machine.scala` — `Machine.iterate` / `runOnce`: pure decision logic. Touches the world through
  nothing but the capabilities. No filesystem, no subprocess, no clock.
- `src/Live.scala` — every real side effect (`LiveGit`, `LiveGitHub`, `LiveGateRunner`,
  `LiveAgentDispatch`, `LiveProc`, ...). Handlers take dependencies as constructor params.
- `test/Recorder.scala` — `TestWorld`, scripted in-memory handlers for every capability plus an
  interaction recorder. Scenarios assert on both the outcome and the call sequence.

Adding behaviour that needs the outside world means adding a capability method in `Caps.scala`, a
decision in `Machine.scala`, an implementation in `Live.scala`, and a script in `Recorder.scala`.

Infra faults short-circuit through `boundary.Label[LoopExit]` (aliased `Faulting` in `Machine`).
That is a type-level guarantee, not a convention: no code after a fault can run, so no fault path can
burn repair budget or dispatch a FIX.

### Domain

`src/Domain.scala` holds the closed types: `LoopExit` (the eight terminal states and their process
exit codes 0/10/11/20/30/40/50/60 — the rc contract shared with `watch.sh`, never change a meaning),
`StageResult`, `GateResult`, `Verdict`, `FailureKind` (whose `text` strings appear verbatim in logs,
commits and PR notes), `InfraFault`, `Role`, `Template`, `Config`.

### Configuration layering

Four layers, and the `Settings` object's own scaladoc (`src/Settings.scala`) is the one place that
states which of them beats which, plus the two qualifications on it. Change the rule there and
nowhere else; everything here is about the parts, not the order.

`src/Settings.scala` holds the reference schema as HOCON text, parses `.litter-box/config.conf` onto
it (`loadFile`) and reads `.litter-box/.env` (`loadDotEnv`); `Main.layerDotEnv` combines that file
with the process environment and `Main.parseEnv` layers the result over the file's values. A missing
config file is a `Left`, never silent defaults — the loop exits 50 and names `litter-box init`.

`.litter-box/.env` is plain `KEY=value` lines, holds any variable the loop reads rather than a
credential only, and is OPTIONAL, unlike `config.conf`: exporting the variables is the other equally
supported way. A JVM cannot mutate its own environment, so the entries the ambient environment does
not already carry are stamped onto every child through `LiveProc.exportEnv` — the sandbox scripts
read the credential off their own environment, never off the loop.

`protect` is a floor, not a list: a consumer's entries are **unioned** with the reference floor, so
`.litter-box/**` is always covered and the loop cannot be talked into loosening its own guard.

### What ships in the artifact vs what a consumer owns

Prompt skeletons, sandbox scripts and the observability scripts are all **protocol, not
configuration**, so they live under `resources/` and travel inside the jar
(`//> using resourceDir ./resources`):

- `src/Prompts.scala` — resolves `Template` skeletons from the classpath, with
  `.litter-box/prompts/<name>` overriding per file (written by `litter-box eject`).
- `src/Shipped.scala` — one implementation of "get a tree of files out of the jar and onto disk
  intact": materialises `resources/<tree>/**` to `~/.cache/litter-box/<tree>/<digest>`, keyed by
  content digest so an upgrade lands in a new directory with no cache-busting step. Each tree is a
  subclass naming its directory and its explicit `ShippedFiles` manifest; `test/ShippedSpec.scala`
  runs the whole spec against every tree in `Trees` and fails if a `resources/<tree>/` directory
  gains a file that is not in its manifest.
- `src/Sandbox.scala` — the Docker sandbox tree, run by the loop.
- `src/Observe.scala` — `watch.sh`, `tail-claude.sh` and their `lib/`, run by a human. Fronted by
  `litter-box watch` / `litter-box tail` (`ObserveTool` in `Cli.scala`, `Main.runObserve`), because
  nobody types a content digest. They ship rather than scaffold because `watch.sh` parses the
  `status.jsonl` schema `LiveStatusLog` writes, so a consumer-side copy would rot silently.
- `src/Init.scala` — `litter-box init` scaffolds six files from `resources/scaffold/` into
  `.litter-box/`. A consumer owns only `Dockerfile`, `allowlist`, `config.conf` and
  `prompts/conventions.md`.

### The log contract

The operator log stream is parsed by `watch.sh`, so its wording is asserted behaviour, not decoration.
`test/LogParitySpec.scala` freezes the whole stream per scenario against `test/golden/*.log`. To
change a line deliberately: `UPDATE_GOLDEN=1 scala-cli test .`, then read `git diff test/golden` — that
diff **is** the contract change.

`status.jsonl` is the machine-readable sibling, emitted via `StatusLog`.