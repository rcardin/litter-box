package in.rcard.litterbox

/** The sandbox runtime: where the scripts that build the images and run the containers live.
  *
  * Until slice 3 the answer was `<repo>/sandbox`, because the repo the loop worked on and the repo
  * the loop lived in were the same directory. They are not any more, and `litter-box init` never
  * wrote a `sandbox/` tree, so a scaffolded consumer died at the first preflight script (#9). The
  * fix was to make these files ship, which is what [[Shipped]] is; everything about HOW a shipped
  * tree reaches the disk is stated there, and this object is now only the manifest and the name of
  * the directory it lives in.
  *
  * (`Shipped` also means this file needs no version constant, so #6 gets to decide what a version IS
  * once, for the release, rather than twice.)
  */
object Sandbox extends Shipped("sandbox"):

  /** `sandbox/test/` is deliberately not here and deliberately not under `resources/`: those are
    * Docker-dependent developer tests, and a released binary has no business shipping its own test
    * suite.
    */
  val ShippedFiles: List[String] = List(
    "lib.sh",
    "build-image.sh",
    "start-proxy.sh",
    "stop-proxy.sh",
    "run-agent.sh",
    "run-reviewer.sh",
    "run-fast-gate.sh",
    "agent-entrypoint.sh",
    "base.Dockerfile",
    "proxy/Dockerfile",
    "proxy/tinyproxy.conf",
    "proxy/allowlist"
  )
