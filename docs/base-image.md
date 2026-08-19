# `ghcr.io/rcardin/litter-box-base`

The part of the litter-box gate sandbox that is not about any one project. Built from
`resources/sandbox/base.Dockerfile`, published on tag by the `image` job in
`.github/workflows/release.yml`, which also builds the binary and the homebrew formula from that
same tag (issue #6).

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

## What it inherits from `eclipse-temurin`

The `ENTRYPOINT` and `CMD` above are inherited and never reached, because all three runners
override them. The environment is inherited too, and nothing overrides it, so every variable the
base sets is live inside the gate, the worker and the reviewer container.
`FROM eclipse-temurin:21-jdk` (`resources/sandbox/base.Dockerfile:9`) sets six:

| Variable | Value |
|---|---|
| `PATH` | prefixed with `/opt/java/openjdk/bin` |
| `JAVA_HOME` | `/opt/java/openjdk` |
| `LANG` | `en_US.UTF-8` |
| `LANGUAGE` | `en_US:en` |
| `LC_ALL` | `en_US.UTF-8` |
| `JAVA_VERSION` | the temurin build string, shaped like `jdk-21.0.11+10` |

That table is a snapshot of one temurin patch release, and the base moves under it every time
upstream cuts a new one. Nothing in the gate checks this page against the image, so when the exact
set matters, read it off the image:

```bash
docker image inspect eclipse-temurin:21-jdk --format '{{json .Config.Env}}'
```

**`JAVA_VERSION` is the name that collides.** An `ENV` inherited from the base wins over an `ARG`
of the same name declared in the project layer, so the obvious way to parameterise a JDK install in
`.litter-box/Dockerfile` does not do what it reads as:

```dockerfile
# WRONG: JAVA_VERSION is already an ENV in the base, so this ARG never wins
ARG JAVA_VERSION=17.0.13+11
RUN curl -fsSL "https://example.com/OpenJDK21U-jdk-${JAVA_VERSION}.tar.gz" -o /tmp/jdk.tar.gz
```

The interpolation quietly uses the temurin build string instead, the fetch asks for a URL carrying
something shaped like `jdk-21.0.11+10`, and the server answers `404`. The failure names no cause:
the URL is well formed, the `ARG` default is right there in the file, and the version in the URL
that failed appears nowhere in the project layer. If you arrived here from that `404`, this is it.

The fix is to pick any name the base does not already define. `PROJECT_JDK_VERSION` collides with
nothing in the table above:

```dockerfile
ARG PROJECT_JDK_VERSION=17.0.13+11
RUN curl -fsSL "https://example.com/OpenJDK21U-jdk-${PROJECT_JDK_VERSION}.tar.gz" -o /tmp/jdk.tar.gz
```

`JAVA_VERSION` is the only inherited name a project layer is realistically going to reach for as a
build argument. `JAVA_HOME` and `PATH` are inherited as well, but a layer that redefines either is
replacing the JDK the sandbox runs on rather than parameterising an install, and the three locale
variables are not names anyone passes to `--build-arg`. The rule covers all six anyway: check the
table, or the `docker image inspect` line, before choosing an `ARG` name.

## Using it

```dockerfile
ARG BASE_IMAGE=ghcr.io/rcardin/litter-box-base:0.1.1
FROM ${BASE_IMAGE}

USER root
# TODO: install this project's JDK and build tool, pinned to exact versions
USER gate
WORKDIR /workspace
```

The tag above matches this binary's own version (`LitterBox.Version`), and becomes pullable once
that version is released, not before. Until the matching tag is cut, whatever `image` last
published under an earlier tag stays live under `latest`; check the
[ghcr package page](https://github.com/rcardin/litter-box/pkgs/container/litter-box-base) for the
tags actually published right now rather than trusting a snapshot written here. This does not
block a normal run either way: as the next paragraph explains, `build-image.sh` overrides
`BASE_IMAGE` with a locally built tag, so `ghcr.io` is never consulted on that path regardless of
which tags exist there yet.

`litter-box init` writes exactly this file to `.litter-box/Dockerfile` (skeleton written, middle
left to you), and `build-image.sh` builds the gate image from it, that file, with no fallback. The
build tool you install here is what `gate.fast` in `.litter-box/config.conf` is read against, so the
two have to name the same thing.

The `ARG` default above is not the path a loop run takes. `build-image.sh:19` builds this base image
locally from `resources/sandbox/base.Dockerfile` first, then passes that local tag as
`--build-arg BASE_IMAGE` when it builds the gate image (`build-image.sh:60`). So `ghcr.io` is never
consulted during a run — every consumer builds and pays for the Claude-CLI install locally, and the
sandbox needs no registry credentials. (It may still pull public base layers like `eclipse-temurin`
if they aren't already cached.) The `ghcr.io` default is what a hand-run `docker build -f .litter-box/Dockerfile` gets, and only that.

## Where the egress allowlist applies

The allowlist (`.litter-box/allowlist`, or the shipped `resources/sandbox/proxy/allowlist` when a
repo has none) is enforced at `docker run` time only, against the three containers the runners
start. It is not enforced during `docker build`.

At run time the fence is the proxy sidecar plus an internal network. `start-proxy.sh:28-29` creates
`litter-box-net` with `--internal`, so the network has no route out at all, and starts tinyproxy on
it as the only reachable peer. `run-fast-gate.sh:100`, `run-agent.sh:90` and `run-reviewer.sh:92`
then join that network and no other, point `HTTP_PROXY`, `HTTPS_PROXY`, `http_proxy` and
`https_proxy` at the sidecar, and clear `NO_PROXY` and `no_proxy` so nothing in the image can carve
an exception. The gate and the worker also set `JAVA_TOOL_OPTIONS` with the matching JVM proxy
properties, for a build tool that reads those and not the environment. So every host the gate, the
worker or the reviewer touches while it runs needs an allowlist entry, or tinyproxy refuses it with
`403 Filtered`.

At build time none of that is in play. The `docker build` invocations pass no proxy variables and
no network flag: `build-image.sh:19` for the base image, `build-image.sh:60` for the gate image,
and `build_proxy_image` in `lib.sh:118` for the proxy image itself. A `RUN curl ...` in the project
layer of `.litter-box/Dockerfile` therefore reaches whatever the Docker daemon can reach, and needs
no allowlist entry. The reverse is worth saying too, but scoped to the phase it reasons about: an
entry added because a build step succeeded against that host bought the build nothing, since the
build would have reached it with or without the entry. That is not a reason to remove the entry,
because the same host is often also touched at run time; a dependency mirror such as
`repo1.maven.org` is a case in point, since `gate.fast` resolves against it while it runs, inside
the proxied network, and would be refused with `403 Filtered` without an entry. An allowlist entry
earns its place only when something touches that host at run time.

This is a documented shape, not a sanctioned route out. Two facts contain it. The first is that
`.litter-box/Dockerfile` is operator owned and not agent owned: the scaffolded protect floor is
`[".litter-box/**", ".github/**"]` (`resources/scaffold/config.conf:54`) and a consumer can only
widen it, so a worker patch that adds a `RUN` line to the image the loop builds is rejected before
it is ever applied. The second is that the absence of proxy settings is not a promise of an open
network. A daemon behind a corporate proxy, a BuildKit configuration that pins the build network,
or a rootless daemon with egress rules of its own will constrain a build regardless. The claim here
is narrower and exact: litter-box itself neither routes nor filters build traffic, so the allowlist
is not what decides whether a build step reaches a host.

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
