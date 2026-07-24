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

`src/Domain.scala` holds the closed types: `LoopExit` (the seven terminal states and their process
exit codes 0/10/11/20/30/40/50 — the rc contract shared with `watch.sh`, never change a meaning),
`StageResult`, `GateResult`, `Verdict`, `FailureKind` (whose `text` strings appear verbatim in logs,
commits and PR notes), `InfraFault`, `Role`, `Template`, `Config`.

### Configuration layering

**env var > `.litter-box/.env` > `.litter-box/config.conf` > `Settings.Reference`.**
`src/Settings.scala` holds the reference schema as HOCON text; `Main.parseEnv` layers env vars on
top. A missing config file is a `Left`, never silent defaults — the loop exits 50 and names
`litter-box init`.

`.litter-box/.env` (`Settings.loadDotEnv`, `Main.layerDotEnv`) is the credential file `init`
scaffolds an example of, read as plain `KEY=value` lines. It is OPTIONAL, unlike `config.conf`:
exporting the variable is the other equally supported way, and an exported variable wins on
conflict. A JVM cannot mutate its own environment, so the entries the ambient environment does not
already carry are stamped onto every child through `LiveProc.exportEnv` — the sandbox scripts read
the credential off their own environment, never off the loop.

`protect` is a floor, not a list: a consumer's entries are **unioned** with the reference floor, so
`.litter-box/**` is always covered and the loop cannot be talked into loosening its own guard.

### What ships in the artifact vs what a consumer owns

Both prompt skeletons and sandbox scripts are **protocol, not configuration**, so they live under
`resources/` and travel inside the jar (`//> using resourceDir ./resources`):

- `src/Prompts.scala` — resolves `Template` skeletons from the classpath, with
  `.litter-box/prompts/<name>` overriding per file (written by `litter-box eject`).
- `src/Sandbox.scala` — materialises `resources/sandbox/**` to `~/.cache/litter-box/sandbox/<digest>`,
  keyed by content digest so an upgrade lands in a new directory with no cache-busting step.
  `Sandbox.ShippedFiles` is an explicit list; `SandboxSpec` fails if `resources/sandbox/` gains a file
  that is not in it.
- `src/Init.scala` — `litter-box init` scaffolds six files from `resources/scaffold/` into
  `.litter-box/`. A consumer owns only `Dockerfile`, `allowlist`, `config.conf` and
  `prompts/conventions.md`.

### The log contract

The operator log stream is parsed by `watch.sh`, so its wording is asserted behaviour, not decoration.
`test/LogParitySpec.scala` freezes the whole stream per scenario against `test/golden/*.log`. To
change a line deliberately: `UPDATE_GOLDEN=1 scala-cli test .`, then read `git diff test/golden` — that
diff **is** the contract change.

`status.jsonl` is the machine-readable sibling, emitted via `StatusLog`.