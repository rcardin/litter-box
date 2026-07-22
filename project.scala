// litter-box: the autonomous coding loop. Standalone scala-cli project; NOT an sbt build
// (deliberate: the threat model distrusts agent-authored build.sbt, so the loop never couples
// to one). JDK 21 LTS — the old JDK 25 pin existed only for a dropped dependency's StructuredTaskScope.
//> using scala 3.8.3
//> using jvm temurin:21
//> using dep "com.typesafe:config:1.4.9"
//> using test.dep "org.scalatest::scalatest:3.2.20"
//> using options -deprecation -feature -unchecked
