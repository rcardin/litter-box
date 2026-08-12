# Publishing to Maven Central

The `publish` job in `.github/workflows/release.yml` pushes `in.rcard::litter-box` to Maven Central
on every `v*` tag. That coordinate is not decoration: `litter-box init` scaffolds a
`.litter-box/loop.scala` whose first line is `//> using dep in.rcard::litter-box:<version>`, so a
release whose library never reached Central hands every new consumer a launcher that dies at
dependency resolution before compiling a line.

The namespace, the account and the four secrets the job authenticates with are manual, one time
steps a workflow cannot do for itself. They are done for `rcardin/litter-box` itself; this document
is what you follow to stand the same thing up in a fork.

## Why `scala-cli publish` and not sbt-ci-release

The sibling `yaes` project publishes with sbt-ci-release, and every fact that plugin needs is a fact
this job needs too, under the same four secret names. The difference is only the vehicle, and the
reason is stated at the top of `project.scala`: this repository has no sbt build and must not grow
one, because the threat model distrusts agent-authored build files and the loop is not allowed to
couple to the build of the project it works on. An sbt build added purely to publish would restate
the scala version, the dependency list and a non standard source layout (`src/`, `test/`,
`resources/`) in a second file that nothing keeps in step with `project.scala`, and the first
divergence would ship rather than fail.

Everything sbt-ci-release contributes is a flag here instead. The publishing metadata Central
requires (organization, name, license, url, vcs, description, developer), the target repository, the
version computation, the credentials and the signing key are all arguments to the single
`scala-cli publish` invocation in that job.

That is worth one note, because `project.scala` is where a reader would expect to find at least the
metadata, as `//> using publish.*` directives. Those directives are still marked experimental by
scala-cli, and an experimental directive fails any invocation that does not pass `--power`.
Directives are read on every command, not only on `publish`, so a single `//> using publish.name` in
`project.scala` makes plain `scala-cli test .` fail with `directive is experimental`, taking the
`build` job, `ci.yml` and the command CLAUDE.md documents down with it. The flags cost nothing
anywhere else, so the configuration lives entirely in the job that uses it.

## 1. Claim the `in.rcard` namespace

Sign in at <https://central.sonatype.com> and verify the `in.rcard` namespace. Verification of a
domain-shaped namespace is a DNS TXT record on `rcard.in`; Sonatype's UI tells you the exact token
to publish. A namespace stays verified once done, so this is genuinely a one time step.

If you are working in a fork under a namespace you do not own, the `--organization` flag in the
`publish` job and `LitterBox.Coordinate` in `src/LitterBox.scala` both have to change, and
`InitSpec` asserts the scaffold against the constant rather than a literal, so the scaffold follows
automatically.

## 2. Generate a Central Portal user token

Account → Generate User Token. This yields a username and a password that are *not* your login
credentials. Store them as the repository secrets `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`.

These are Central Portal tokens, not the old OSSRH ones. OSSRH reached end of life on 30 June 2025
and `oss.sonatype.org` no longer accepts deployments; a token minted before the migration will fail
authentication. The job passes `--publish-repository central`, which since scala-cli
1.8.4 resolves to the Portal's OSSRH Staging API at `https://ossrh-staging-api.central.sonatype.com`.
The aliases `central-legacy` and `central-s01` still name the dead hosts, so neither is what this
project wants.

## 3. Create a PGP key and export it

Central rejects unsigned artifacts. Create a key, publish the public half to a keyserver so Central
can verify the signature, and export the private half for CI:

```bash
gpg --full-generate-key                       # RSA 4096, no expiry is simplest
gpg --list-secret-keys --keyid-format=long    # note the long key id
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID> | base64 | tr -d '\n'
```

Store that last value as the secret `PGP_SECRET`, and the key's passphrase as `PGP_PASSPHRASE`.

The base64 wrapper is sbt-ci-release's convention, kept here so the secrets are interchangeable
between the two vehicles and so a multi line armored key survives being pasted into a secrets box.
The workflow accepts either encoding: a value beginning with `-----BEGIN` is passed through
untouched, anything else is base64 decoded first. `PGP_PASSPHRASE` is optional, and a key generated
without a passphrase works; the job only adds `--secret-key-password` when the secret is non empty,
because passing an empty passphrase fails such a key rather than using it.

## 4. Cut a tag

Bump `LitterBox.Version` in `src/LitterBox.scala`, then push `v<that version>`. The `build` job fails
the release outright if the tag and the constant disagree, before anything is packaged, so the
binary, the ghcr image, the GitHub release, the Homebrew formula and the Central artifact all
describe one version by construction.

## What to do when the publish job fails

Read this before re-running the job.

`image` and `formula` are safely retryable: ghcr and the tap both accept being written twice.
**Maven Central is not.** A released version is immutable there, and a second attempt at the same
version is rejected. The failure modes divide cleanly:

- **Failed before the upload** (missing secret, bad credentials, signing error, compile failure).
  Nothing reached Central. Fix the cause and re-run the job.
- **Failed after the deployment was validated.** The deployment is sitting in the Portal, and a
  re-run will be rejected as a duplicate. Go to <https://central.sonatype.com/publishing/deployments>,
  and either publish or drop the pending deployment by hand. Dropping it frees the version for a
  genuine re-run.
- **Already published.** The version is permanent. Bump `LitterBox.Version`, delete nothing, and cut
  a new tag.

`release` and `formula` both wait on `publish`, so a Central failure stops the GitHub release and
the Homebrew formula too. That is deliberate, and it is the same rule `image` follows: everything cut
from one tag moves together, or the tag ships artifacts that disagree about what version they are. The
cost is that a Central hiccup blocks a binary that was otherwise fine; re-running the workflow after
clearing the pending deployment is the recovery, not a hand-uploaded release asset.

## Checking it worked

```bash
scala-cli -e 'println("ok")' --dep in.rcard::litter-box:<version>
```

Central sync to `repo1.maven.org` is not instant. The Portal shows the deployment as published
first; resolution through the public mirror follows, typically within minutes.
