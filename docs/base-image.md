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
# install your build tool, pinned to an exact version
USER gate
WORKDIR /workspace
```

`litter-box init` writes exactly this file, filled in, to `.litter-box/Dockerfile`, and
`build-image.sh` builds the gate image from it — that file, with no fallback. The build tool you
install here is what `gate.fast` in `.litter-box/config.conf` is read against, so the two have to
name the same thing.

## Adding a preset

`init` scaffolds an sbt Dockerfile because sbt is the only build tool a litter-box loop has
actually been run against end to end. A Gradle or Maven preset is a PR: add a case to
`Init.BuildTool`, its install block in `Init.dockerfile`, its gate command in `Init.configConf`, and
a detection rule in `Init.detect`. Adding a case is a claim that you ran the
loop with it, so please say so in the PR.

## Bumping the Claude CLI

`CLAUDE_VERSION` is an explicit `ARG` in `resources/sandbox/base.Dockerfile`. Bump it in a commit of its own,
together with the image tag, so a broken CLI release is one revert.
