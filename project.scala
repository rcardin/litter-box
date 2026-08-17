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

// Publishing to Maven Central (issue #41) is configured ENTIRELY in the `publish` job of
// `.github/workflows/release.yml` and, for the testkit, in the `scripts/publish-testkit.sh` that job
// calls, as command line flags, and deliberately NOT here as the `//> using publish.*` directives
// that would be the obvious home for it.
//
// Every one of those directives is still marked experimental by scala-cli, and an experimental
// directive is rejected by any invocation that does not pass `--power`. That is not a publish-time
// restriction: directives are read on EVERY command, so `//> using publish.organization` sitting in
// this file makes plain `scala-cli test .` fail outright, which is the exact command CLAUDE.md
// documents, README.md's Build section names and `.github/workflows/ci.yml` runs with nothing else
// installed. A build file that breaks the project's own test command in order to describe a release
// that happens on tags only is the wrong trade; the flags cost nothing anywhere else.
//
// So the metadata (organization, name, license, url, vcs, description, developer), the repository
// and the version computation all live in that job, and the testkit's copy of them in the one script
// its three callers share. Nothing about them is duplicated here, and nothing here has to be kept in
// step with them.
//
// Why `scala-cli publish` at all, rather than sbt-ci-release the way the sibling `yaes` project does
// it: the same reason stated at the top of this file. This repository has no sbt build and must not
// grow one, and an sbt build added purely to publish would restate the scala version, the dependency
// list and a non standard source layout in a second file that nothing keeps in step with this one.
// The CI secrets are the same four either way.
