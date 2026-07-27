# `ghcr.io/rcardin/litter-box-base`

The part of the litter-box gate sandbox that is not about any one project. Built from
`resources/sandbox/base.Dockerfile`, published on tag by `.github/workflows/base-image.yml`.

## What it guarantees

| | |
|---|---|
| JDK | temurin 21 (LTS), on `PATH` |
| Claude CLI | pinned, at `/usr/local/bin/claude`, executable by every user |
| User | non-root `gate`, uid 10001, home `/home/gate`, owner of `/home/gate/.cache/coursier` |
| `WORKDIR` | `/workspace` |
| `USER` | `gate` |
| Base | `eclipse-temurin:21-jdk` (Debian) with `curl`, `git`, `ca-certificates`, `gnupg` |

## What it deliberately does not have

- **No build tool.** Not sbt, not Gradle, not Maven, not npm. That is the consumer's layer.
- **No `ENTRYPOINT` of its own**, and none is wanted from the consumer either. All three runners
  override it: the gate runs `gate.fast` through `bash -c`, the worker and reviewer run their own
  entrypoint script. What the image inherits from `eclipse-temurin`
  (`ENTRYPOINT ["/__cacert_entrypoint.sh"]`, `CMD ["jshell"]`) is therefore never reached.
  This was not always true — until #9 the gate ran the image's `ENTRYPOINT` with sbt's own flags
  appended, so the "no build tool" promise held one layer up and was broken one layer down.
- **No credentials.** No API key, no OAuth token, no `gh` token, no registry login. Credentials
  reach a running container as environment variables at `docker run` time and are never baked in.
  See `resources/sandbox/lib.sh:sandbox_credential_env`.

## Using it

```dockerfile
ARG BASE_IMAGE=ghcr.io/rcardin/litter-box-base:0.1.0
FROM ${BASE_IMAGE}

USER root
# TODO: install this project's JDK and build tool, pinned to exact versions
USER gate
WORKDIR /workspace
```

`litter-box init` writes exactly this file to `.litter-box/Dockerfile` (skeleton written, middle
left to you), and `build-image.sh` builds the gate image from it, that file, with no fallback. The
build tool you install here is what `gate.fast` in `.litter-box/config.conf` is read against, so the
two have to name the same thing.

The `ARG` default above is not the path a loop run takes. `build-image.sh:19` builds this base image
locally from `resources/sandbox/base.Dockerfile` first, then passes that local tag as
`--build-arg BASE_IMAGE` when it builds the gate image (`build-image.sh:60`). So the registry is
never consulted during a run — every consumer builds and pays for the Claude-CLI install locally, and
the sandbox needs no registry credentials or network at all. The `ghcr.io` default is what a hand-run
`docker build -f .litter-box/Dockerfile` gets, and only that.

## Why the middle is a TODO and not a preset

There are no build-tool presets, for any build tool, and there is no extension point to add one
(#13). `init` detects your build tool and your JDK, and it writes what it found into the TODO above
as evidence. It does not act on it:

- Seeing a `build.sbt` tells you the tool, not the invocation. `init` used to scaffold
  `sbt -Werror compile test`, which sbt rejects outright: `-Werror` is a scalac flag, sbt parses
  bare arguments as commands, and it answers `Not a valid command: -`. Every sbt repo ever
  scaffolded got a gate that could not run.
- Reading `java -version` tells you what the HOST builds under, not what the container should carry.
  A project on `-release:25` over this temurin 21 base fails as late as the tier allows: image
  built, proxy up, sources compiled, then `25 is not a valid choice for -java-output-version`.

Both are the same mistake, so the fix is one rule rather than a better guess: `init` scaffolds the
lines that keep the sandbox sound and marks everything project-specific as unanswered.

## Bumping the Claude CLI

`CLAUDE_VERSION` is an explicit `ARG` in `resources/sandbox/base.Dockerfile`. Bump it in a commit of its own,
together with the image tag, so a broken CLI release is one revert.
