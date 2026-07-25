# The gate container for this repository.
#
# What `litter-box init` writes is the CONTRACT SKELETON and nothing else: the handful of lines
# litter-box can guarantee keep the sandbox sound. The project-specific layer (your JDK, your build
# tool) is left as a TODO for you to confirm, because detecting is not verifying. Seeing a build
# file tells you the build tool, not the invocation, and reading `java -version` tells you what the
# HOST builds under, not what this container should carry. `init` says what it saw in the TODO
# below and stops there, rather than scaffolding a guess that fails on iteration one.
#
# FROM the litter-box base image, which carries a JDK, the pinned Claude CLI and a non-root `gate`
# user, and NO credentials of any kind. It deliberately carries no build tool: that is this file's
# job, and it is the only part of the sandbox that knows what this project is built with. See
# https://github.com/rcardin/litter-box/blob/main/docs/base-image.md for the contract the base
# image guarantees.
#
# No ENTRYPOINT. The three runners (gate, agent, reviewer) all override it, because the command to
# run is a property of the RUN — `gate.fast` from .litter-box/config.conf, or the agent entrypoint
# script — not of the image. An ENTRYPOINT here would be dead weight at best and a build tool
# hardcoded in two places at worst.
#
# BASE_IMAGE is overridden to a locally built tag when litter-box builds this file, so a run never
# needs the registry; the default below is what a hand-run `docker build` gets.
ARG BASE_IMAGE=ghcr.io/rcardin/litter-box-base:0.1.0
FROM ${BASE_IMAGE}

# root for the install below only. The image MUST end back on `gate`: the fast gate runs as that
# user, and a container whose gate is root is not the sandbox you installed litter-box for.
USER root
{{PROJECT_LAYER}}
USER gate
WORKDIR /workspace
