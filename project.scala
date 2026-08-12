// litter-box: the autonomous coding loop. Standalone scala-cli project; NOT an sbt build
// (deliberate: the threat model distrusts agent-authored build.sbt, so the loop never couples
// to one). JDK 21 LTS — the old JDK 25 pin existed only for a dropped dependency's StructuredTaskScope.
//> using scala 3.8.3
//> using jvm temurin:21
//> using dep "com.typesafe:config:1.4.9"
//> using test.dep "org.scalatest::scalatest:3.2.20"
//> using options -deprecation -feature -unchecked

// The prompt skeletons ship in the artifact, not in the consumer repo: a consumer who carries a
// copy of the protocol carries a copy that silently rots when the tool updates. `Prompts.resolve`
// reads them from here and lets `.litter-box/prompts/` override per file.
//> using resourceDir ./resources

// Publishing to Maven Central (issue #41). A scaffolded `.litter-box/loop.scala` opens with
// `//> using dep in.rcard::litter-box:<version>`, so the library has to be resolvable by anyone who
// runs `litter-box init`, which means Central and nowhere else: GitHub Packages would make every
// scaffolded launcher require a credential to resolve, and JitPack would force the coordinate under
// `com.github.rcardin`, contradicting `LitterBox.Coordinate`.
//
// Deliberately `scala-cli publish` and not sbt-ci-release, the way the sibling `yaes` project does
// it. The reason is the same one at the top of this file: this repository has no sbt build and must
// not grow one, and an sbt build added purely to publish would restate the scala version, the
// dependency list and a non standard source layout in a second file that nothing keeps in step with
// this one. Everything sbt-ci-release contributes there is a CLI flag here instead, and the CI
// secrets are the same four either way.
//> using publish.organization in.rcard
//> using publish.name litter-box
//> using publish.license MIT
//> using publish.url https://github.com/rcardin/litter-box
//> using publish.vcs github:rcardin/litter-box
//> using publish.description "A distrustful autonomous coding loop: one labelled issue at a time, implemented in a sandbox, gated, independently reviewed, and merged only if CI agrees."
//> using publish.developer "rcardin|Riccardo Cardin|https://github.com/rcardin"

// `central` is an alias, not a URL, and since scala-cli 1.8.4 it resolves to the Sonatype Central
// Portal's OSSRH Staging API (`https://ossrh-staging-api.central.sonatype.com`), not to the
// `oss.sonatype.org` host that reached end of life on 30 June 2025. `central-legacy` and
// `central-s01` still name the dead hosts; neither is what this project wants.
//> using publish.repository central

// The version is READ FROM THE TAG, never written here. `.github/workflows/release.yml`'s `build`
// job already fails a tag whose name disagrees with `LitterBox.Version`, so `git:tag` and that
// constant cannot describe different versions of the same release; pinning `publish.version` here
// as well would be a third statement of the one fact, and the first one to go stale.
//> using publish.computeVersion git:tag
