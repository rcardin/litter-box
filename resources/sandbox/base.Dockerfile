# litter-box-base — the part of the gate sandbox that is not about any one project.
#
# Carries a JDK, the pinned Claude CLI, and a non-root user to run as. Carries NO build tool, NO
# ENTRYPOINT and NO credentials of any kind. Those three absences are the contract: a consumer's
# own Dockerfile does `FROM` this image, installs whatever it builds with, and sets its own
# ENTRYPOINT. See docs/base-image.md.
#
# JDK 21 LTS matches the loop's own `//> using jvm temurin:21`.
FROM eclipse-temurin:21-jdk

ARG CLAUDE_INSTALL_URL=https://claude.ai/install.sh
# Pinned so image rebuilds are reproducible; the installer verifies the downloaded binary's
# checksum against its release manifest. Bump deliberately, together with the image tag.
ARG CLAUDE_VERSION=2.1.207

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git ca-certificates gnupg \
    && rm -rf /var/lib/apt/lists/*

# Claude Code CLI: the native installer downloads a single self-contained (Bun-compiled)
# binary and runs `<binary> install`, which places it under $HOME/.local/share/claude/versions
# and symlinks $HOME/.local/bin/claude. Run it under a throwaway HOME (root's install would
# not be reachable by the non-root `gate` user below), then lift the single binary out to
# /usr/local/bin so every user can exec it.
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
# uid 10001 is part of the contract: consumer Dockerfiles chown into /home/gate.
RUN useradd -m -u 10001 -s /bin/bash gate \
    && mkdir -p /home/gate/.cache/coursier \
    && chown -R gate:gate /home/gate

USER gate
WORKDIR /workspace
