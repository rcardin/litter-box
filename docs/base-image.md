# `ghcr.io/rcardin/litter-box-base`

The part of the litter-box gate sandbox that is not about any one project. Built from
`sandbox/base.Dockerfile`, published on tag by `.github/workflows/base-image.yml`.

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
- **No `ENTRYPOINT`.** The consumer sets it to their build tool.
- **No credentials.** No API key, no OAuth token, no `gh` token, no registry login. Credentials
  reach a running container as environment variables at `docker run` time and are never baked in.
  See `sandbox/lib.sh:sandbox_credential_env`.

## Using it

```dockerfile
ARG BASE_IMAGE=ghcr.io/rcardin/litter-box-base:0.1.0
FROM ${BASE_IMAGE}

USER root
# install your build tool, pinned to an exact version
USER gate
WORKDIR /workspace
ENTRYPOINT ["your-build-tool"]
```

`litter-box init` writes exactly this file, filled in, to `.litter-box/Dockerfile`.

## Adding a preset

`init` scaffolds an sbt Dockerfile because sbt is the only build tool a litter-box loop has
actually been run against end to end. A Gradle or Maven preset is a PR: add a case to
`Init.BuildTool`, its install block and entrypoint in `Init.dockerfile`, its gate command in
`Init.configConf`, and a detection rule in `Init.detect`. Adding a case is a claim that you ran the
loop with it, so please say so in the PR.

## Bumping the Claude CLI

`CLAUDE_VERSION` is an explicit `ARG` in `sandbox/base.Dockerfile`. Bump it in a commit of its own,
together with the image tag, so a broken CLI release is one revert.
