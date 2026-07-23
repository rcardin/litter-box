# The gate container for litter-box's own repo.
#
# The same file every consumer gets from `litter-box init`, filled in for this project: FROM the
# base image (JDK, pinned Claude CLI, non-root `gate` user, no credentials, no build tool), plus
# the one thing that is specific to this repo — what it builds with.
#
# This repo is a scala-cli project, not an sbt one (see project.scala: the threat model distrusts
# an agent-authored build.sbt, so the loop never couples to one). Until #9 the gate image was built
# from `sandbox/Dockerfile`, which installed sbt and set `ENTRYPOINT ["sbt"]` — a leftover from the
# era when this repo did build with sbt, and the reason a scaffolded consumer's own
# `.litter-box/Dockerfile` was written by `init` and then read by nothing.
#
# No ENTRYPOINT: all three runners override it. The gate runs `gate.fast` through `bash -c`, so the
# tool below has to be on PATH for the `gate` user and nothing more.
ARG BASE_IMAGE=ghcr.io/rcardin/litter-box-base:0.1.0
FROM ${BASE_IMAGE}

USER root

# Pinned so image rebuilds are reproducible.
#
# The JVM launcher (scala-cli.jar), NOT the native binary, and that is load-bearing rather than a
# preference: the sandbox has no DNS and reaches the network only through an allowlisting HTTP
# proxy, which run-fast-gate.sh advertises as JVM system properties (`-Dhttps.proxyHost`) via
# JAVA_TOOL_OPTIONS. A GraalVM native image ignores JAVA_TOOL_OPTIONS, and Java's HTTP stack
# ignores HTTP_PROXY, so the native launcher resolves artifacts with no proxy at all and dies on
# `UnknownHostException (repo1.maven.org)`. Under `java -jar` the properties land and coursier
# goes through the proxy. Verified empirically, both ways round.
ARG SCALA_CLI_VERSION=1.15.0
RUN set -eux; \
    curl -fsSL "https://github.com/VirtusLab/scala-cli/releases/download/v${SCALA_CLI_VERSION}/scala-cli.jar" \
      -o /usr/local/lib/scala-cli.jar; \
    printf '%s\n' '#!/bin/sh' 'exec java -jar /usr/local/lib/scala-cli.jar "$@"' \
      > /usr/local/bin/scala-cli; \
    chmod 0755 /usr/local/bin/scala-cli

USER gate
WORKDIR /workspace
