# The gate container for this repository.
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

USER root
{{BUILD_TOOL}}
USER gate
WORKDIR /workspace
