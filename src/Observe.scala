package in.rcard.litterbox

/** The observability scripts: the live run monitor and the stream-json log follower.
  *
  * These ship for the same reason the sandbox scripts do (#15), and for one more that is stronger.
  * `watch.sh` PARSES `status.jsonl` — `phase`, `state`, `pass`, `budget`, `logfile`, `pid` — a schema
  * written by `LiveStatusLog`. A scaffolded copy would silently misread a renamed field in every repo
  * that ever ran `init`, with no way to push the fix. And unlike `Dockerfile` or `allowlist` there is
  * nothing project-specific in them: no build tool, no JDK, no domain, so a consumer has no reason to
  * edit them. They fail the configuration test on both halves, which makes them protocol.
  *
  * A SECOND TREE rather than four more entries in [[Sandbox]], deliberately. `resources/sandbox/` is
  * the Docker sandbox: the thing the loop builds images from and runs containers in. Nothing here
  * touches Docker, nothing here is ever run by the loop, and folding the two together would leave the
  * word "sandbox" meaning "whatever happens to ship". They share [[Shipped]], which is where the part
  * that genuinely is the same lives.
  *
  * The tree is extracted whole, `lib/` included, because both scripts resolve their helpers from
  * `$SCRIPT_DIR/lib/` — the extraction directory is what makes that resolution work off an install
  * exactly as it works in a checkout.
  */
object Observe extends Shipped("observe"):

  val ShippedFiles: List[String] = List(
    "watch.sh",
    "tail-claude.sh",
    "lib/banner.sh",
    "lib/claude-fmt.jq"
  )
