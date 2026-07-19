// Harness Scala rewrite — slice 1 (issue #44).
// Standalone scala-cli project; NOT part of the sbt build (deliberate: the threat model
// distrusts agent-authored build.sbt, so the harness never couples to it).
//> using scala 3.8.3
//> using jvm temurin:25
//> using dep "in.rcard.yaes::yaes-core:0.20.0"
//> using test.dep "org.scalatest::scalatest:3.2.20"
//> using options -deprecation -feature -unchecked
