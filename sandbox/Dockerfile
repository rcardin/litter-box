# The gate container for litter-box's own repo: the base image plus sbt.
#
# The build tool lives HERE and nowhere else. Before slice 3 this file ended in
# `ENTRYPOINT ["sbt"]`, which made "the litter-box sandbox" and "an sbt container" the same thing
# and forced a Gradle consumer to fork the whole Dockerfile.
#
# BASE_IMAGE defaults to the tag build-image.sh builds locally from base.Dockerfile, so a checkout
# can build the whole stack with no registry involved. A consumer's scaffolded Dockerfile points at
# ghcr.io/rcardin/litter-box-base instead, because a consumer has no sandbox/ directory.
ARG BASE_IMAGE=litter-box-base:v1
FROM ${BASE_IMAGE}

ARG SBT_VERSION=1.12.9

USER root
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
      -o /tmp/sbt.tgz \
    && tar -xzf /tmp/sbt.tgz -C /usr/local \
    && rm /tmp/sbt.tgz \
    && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

USER gate
WORKDIR /workspace
ENTRYPOINT ["sbt"]
