#!/usr/bin/env bash
# Publishes the testkit `in.rcard::litter-box-testkit` (`LitterBox.TestkitCoordinate`, issue #42):
# `test/Recorder.scala` compiled ALONE against the library, carrying the metadata Maven Central
# demands.
#
#   scripts/publish-testkit.sh local   <version> [extra scala-cli flags...]
#   scripts/publish-testkit.sh central <version> [extra scala-cli flags...]
#
# WHY this is a script rather than the command written where it runs. Three places need it:
# `.github/workflows/ci.yml`'s `testkit` job, which stages it into `~/.ivy2/local` on every PR;
# `.github/workflows/release.yml`'s pre flight, which does the same off a tag before anything
# irreversible happens; and that same job's upload to Central. Spelled out three times, adding one
# flag was a three file edit, and the two blocks inside release.yml were near identical fifteen flag
# walls differing in a handful of tokens, which is the exact shape in which a copy paste swaps a
# `--name` with nothing noticing. Central is immutable at a version, so the path this protects is the
# one path in this pipeline that cannot be retried, and it is worth one indirection for the flag set
# to be stated once and run identically by all three.
#
# What genuinely differs between the three callers, and is therefore a parameter: where the artifact
# lands, and at which version. Everything else is the artifact's identity and must not vary.
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <local|central> <version> [extra scala-cli flags...]" >&2
  exit 2
fi
mode="$1"
# Both the testkit's own version AND the library version it is pinned to, from one argument on
# purpose. The testkit exposes the capability traits themselves, so a testkit built against one
# library version says nothing about any other; the pairing rule in `LitterBox.TestkitCoordinate`'s
# scaladoc is that the two are released together, and a single parameter is that rule made
# unbreakable rather than restated.
version="$2"
# An EMPTY version reaches here as two arguments, so the count check above waves it through, and
# `set -u` never fires on a set but empty variable. It is the same hole the `:?` guards on the
# project.scala captures below close: scala-cli does not REJECT `--project-version ""`, it quietly
# substitutes a version of its own, and on the Central path that is an artifact at a coordinate
# nobody chose and nobody can take back. Callers now pass a shell variable rather than a literal
# (release.yml's `$VERSION`), which is exactly the shape that can arrive empty.
: "${version:?empty version argument; refusing to publish at a version scala-cli would pick}"
shift 2

case "$mode" in
  local)
    # `publish local` writes to `~/.ivy2/local`, which scala-cli resolves by default. That is what
    # lets the testkit be built against a library version that is not on Central yet, and what makes
    # this a full rehearsal of the Central publish with no network and no credentials.
    publish=(publish local)
    ;;
  central)
    publish=(publish)
    # Pushed into the positional parameters rather than into a second array so that the invocation
    # below splices these and the caller's own extra flags with a single `"$@"`: an empty array
    # expanded under `set -u` is an error on bash 3.2, whereas `"$@"` never is.
    #
    # Credentials are `env:` references resolved by scala-cli out of the environment this script
    # inherits, so no secret is ever a process argument here.
    #
    # The one flag a caller adds is `--secret-key-password`, which exists only when the optional
    # passphrase secret is non empty: release.yml decides that once, for the library publish in the
    # same step and for this call, rather than deciding it identically in two places.
    set -- --publish-repository central \
      --user env:SONATYPE_USERNAME \
      --password env:SONATYPE_PASSWORD \
      --secret-key env:PGP_SECRET_ARMORED \
      "$@"
    ;;
  *)
    echo "$0: unknown mode '$mode', expected 'local' or 'central'" >&2
    exit 2
    ;;
esac

# Both callers run from their checkout, and a human running this by hand should not have to remember
# to. `test/Recorder.scala` and `project.scala` below are read relative to this.
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$repo_root"

# `--scala`/`--jvm` are read out of project.scala rather than written here, because an explicit FILE
# input (`test/Recorder.scala`) makes that file's own directory the project root, so the root
# project.scala is not read at all. That is the property the testkit publish wants (no `resourceDir`,
# so the testkit jar carries none of the library's resources, and no library sources), and its cost is
# that the two directives have to be handed over on the command line. Handed over by `sed`, never
# retyped: a hardcoded `3.8.3` here is a second statement of a fact project.scala already owns, and it
# would go stale silently, the testkit compiling under the old scala version against a library built
# with the new one.
#
# `:?` on both captures, and this is not belt and braces. `set -u` fires on an UNSET variable, never
# on a set but empty one, so a `project.scala` whose directive line is reformatted or removed leaves
# `--scala ""`, which scala-cli ACCEPTS, silently substituting its own default (reproduced during
# issue #42's review: `--scala ""` published a pom depending on scala3-library_3 3.8.4 while
# project.scala pinned 3.8.3, with no warning). The result is a testkit whose TASTy no consumer on the
# library's own scala version can read, at a coordinate nobody can take back.
scala_version="$(sed -n 's|^//> using scala \(.*\)$|\1|p' project.scala)"
jvm_version="$(sed -n 's|^//> using jvm \(.*\)$|\1|p' project.scala)"
: "${scala_version:?no //> using scala directive found in project.scala}"
: "${jvm_version:?no //> using jvm directive found in project.scala}"

# `--power` because `publish` is itself an experimental scala-cli command, and because the metadata
# flags are the experimental publish settings in their command line form. project.scala says at
# length why they are flags here and not `//> using publish.*` directives there.
scala-cli --power "${publish[@]}" test/Recorder.scala \
  --scala "$scala_version" \
  --jvm "$jvm_version" \
  --organization in.rcard \
  --name litter-box-testkit \
  --project-version "$version" \
  --dep "in.rcard::litter-box:$version" \
  --license MIT \
  --url https://github.com/rcardin/litter-box \
  --vcs github:rcardin/litter-box \
  --description "Testkit for litter-box: scripted in-memory handlers for every capability, plus the interaction recorder, so a node author tests their own graph with no Docker, no network and no credentials." \
  --developer "rcardin|Riccardo Cardin|https://github.com/rcardin" \
  "$@"
