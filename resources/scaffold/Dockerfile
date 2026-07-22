# The gate container for this repository.
#
# FROM the litter-box base image, which carries a JDK, the pinned Claude CLI and a non-root `gate`
# user, and NO credentials of any kind. It deliberately carries no build tool and no ENTRYPOINT:
# that is this file's job, and it is the only part of the sandbox that knows what this project is
# built with. See https://github.com/rcardin/litter-box/blob/main/docs/base-image.md for the
# contract the base image guarantees.
ARG BASE_IMAGE=ghcr.io/rcardin/litter-box-base:0.1.0
FROM ${BASE_IMAGE}

USER root
{{BUILD_TOOL}}
USER gate
WORKDIR /workspace
{{ENTRYPOINT}}
