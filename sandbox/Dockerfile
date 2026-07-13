# fes-harness-sandbox — pinned toolchain for the containerized FAST gate (issue #34).
# JDK 25 matches the host pin (.sdkmanrc: java=25.0.2-open); sbt matches project/build.properties.
# This image carries NO credentials of any kind (no ANTHROPIC_API_KEY, no gh token) — see
# harness/sandbox/run-fast-gate.sh for what's actually passed into a running container.
FROM eclipse-temurin:25-jdk

ARG SBT_VERSION=1.12.9
ARG CLAUDE_INSTALL_URL=https://claude.ai/install.sh
# Pinned so image rebuilds are reproducible; the installer verifies the downloaded binary's
# checksum against its release manifest. Bump deliberately, together with IMAGE tag changes.
ARG CLAUDE_VERSION=2.1.207

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git ca-certificates gnupg \
    && rm -rf /var/lib/apt/lists/*

# sbt: official release tarball, pinned to the version in project/build.properties.
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
      -o /tmp/sbt.tgz \
    && tar -xzf /tmp/sbt.tgz -C /usr/local \
    && rm /tmp/sbt.tgz \
    && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

# Claude Code CLI: the native installer downloads a single self-contained (Bun-compiled)
# binary and runs `<binary> install`, which places it under $HOME/.local/share/claude/versions
# and symlinks $HOME/.local/bin/claude. Run it under a throwaway HOME (root's install would
# not be reachable by the non-root `gate` user below), then lift the single binary out to
# /usr/local/bin so every user can exec it. It does not need to authenticate to report
# `claude --version` — this slice never runs it agentically inside the sandbox.
RUN mkdir -p /opt/claude-install \
    && HOME=/opt/claude-install bash -c "curl -fsSL '$CLAUDE_INSTALL_URL' | bash -s '$CLAUDE_VERSION'" \
    && claude_bin="$(find /opt/claude-install -maxdepth 6 -type f -perm -u+x -name 'claude' | head -1)" \
    && if [ -z "$claude_bin" ]; then \
         claude_bin="$(find /opt/claude-install/.local/share/claude/versions -maxdepth 1 -type f | head -1)"; \
       fi \
    && test -n "$claude_bin" \
    && install -m 0755 "$claude_bin" /usr/local/bin/claude \
    && rm -rf /opt/claude-install

# Non-root user; gate containers never run as root (cheap defense-in-depth, see run-fast-gate.sh).
RUN useradd -m -u 10001 -s /bin/bash gate \
    && mkdir -p /home/gate/.cache/coursier \
    && chown -R gate:gate /home/gate

USER gate
WORKDIR /workspace
ENTRYPOINT ["sbt"]
