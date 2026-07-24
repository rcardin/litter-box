# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

litter-box is a distrustful autonomous coding loop. It picks one labelled GitHub issue, dispatches a
fresh `claude -p` worker inside a network-restricted Docker sandbox, gates the result, has a cold
independent reviewer judge the diff, opens a PR, and lets CI decide. The pipeline is fixed, not
pluggable: `PICK → IMPLEMENT → GATE → REPAIR → REVIEW → PR → CI → MERGE`.

Scala 3.8.3 on temurin 21, built with **scala-cli, not sbt**. Deliberate: the threat model distrusts
agent-authored build files, so the loop never couples to the build of the project it works on. Do not
add a `build.sbt`.

## Commands

```bash
scala-cli test .                              # the whole suite: no Docker, no gh, no credentials
scala-cli test . --test-only 'in.rcard.litterbox.CliSpec'   # one suite (glob over FQCN)
scala-cli run . -- --help                     # usage: init, eject, --dry-run
scala-cli run .                               # the loop itself
scala-cli --power package . -o lb --assembly  # build the `lb` fat jar

UPDATE_GOLDEN=1 scala-cli test .              # rewrite the log-contract goldens (see below)
bash sandbox/test/run-all.sh                  # Docker-dependent shell tests, manual only
```

CI (`.github/workflows/ci.yml`) runs exactly `scala-cli test .` with nothing else installed.

## Architecture
Architecture is described in `ARCHITECTURE.md`.

## Conventions
Conventions are described in `CONVENTIONS.md`. The load-bearing ones:

## Testing
Testing is described in `TEST.md`. The load-bearing ones: