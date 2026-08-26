package in.rcard.litterbox

/** The render half of the reply protocol: untrusted comment entries in, one block of prompt text
  * out, safe to splice into a slot a worker will read.
  *
  * Why this is a TIER 1 file (`docs/adr/0001-framework-tier-is-kit-only.md` carries the tier rule
  * itself, and this object is the fourth file the rule now names). Two call sites inside the shipped
  * graph each wrote the same three step composition out by hand, and only one of them carried the
  * comment saying why the three steps run in that order. The order IS the protection, so a
  * composition restated at each call site is a security decision with as many implementations as it
  * has callers, and a consumer authoring their own graph had none of them: any node of theirs that
  * wanted to put a human's words in front of a worker had to invent the pipeline again. The same
  * argument that moved the patch guard here moves this.
  *
  * Everything the composition is made of is private on purpose. A caller that can reach the
  * individual steps can order them wrongly, and a spec that drives the individual steps can watch
  * each one behave perfectly while the composition around them is wrong, which is exactly the state
  * this repository was in. One public entry point means there is one order, and it is the one this
  * file states.
  *
  * What deliberately stays OUT. The sentinel a caller renders when the comment read FAILED is the
  * caller's to write: this function is handed the entries that were read, so it has no way to tell a
  * thread with nothing in it from a thread nobody could see, and inventing an answer for a question
  * it was not asked is how the two sentinels drift into meaning the same thing.
  */
object Reply:

  /** The rendered block for `entries`, oldest first, each one neutralised and capped, joined by the
    * separator this file owns.
    *
    * The three steps run in THIS order and the order is the whole point (issue #28 review findings 1
    * and 2, round 3). Escaping the entry grammar first is what lets the escape read a forged entry
    * boundary whole: capping first can cut such a boundary in half and change whether it still
    * parses as one, and an escape that runs after the cap cannot see the text the cap already
    * dropped. Defusing a forged fence tag sits between them for the same reason, since the tag it
    * looks for is a shape a cut can destroy just as easily.
    *
    * An empty list answers with the harness's own sentinel rather than an empty string, so a slot
    * that had nothing to hold says so in words a worker can read instead of collapsing into a blank
    * line that reads as a rendering bug.
    */
  def splice(entries: List[String]): String =
    if entries.isEmpty then NoComments
    else
      val share = shareChars(entries.size)
      entries.map(e => truncateEntry(defuseFenceCloser(escapeEntryGrammar(e)), share)).mkString(Separator)

  /** What a worker sees in place of comment text when the thread genuinely holds none. Distinct from
    * anything a caller renders for a read that failed: "there is nothing here" and "I could not look"
    * are different facts, and a worker that cannot tell them apart cannot weigh what it is missing.
    */
  private val NoComments = "[harness: no comments]"

  /** The boundary between two rendered entries. Owned here, next to the escape that neutralises a
    * forged copy of it, because a separator stated at a call site is a separator the escape does not
    * know about.
    */
  private val Separator = "\n\n---\n\n"

  /** Comment text is free form and, unlike the patch path (`cfg.maxPatchBytes`), unbounded. A
    * constant here rather than a config key, same as every other cap the harness sets on itself:
    * this is the harness defending itself, not a knob a consumer tunes. Spent as a PER ENTRY share,
    * never as one cap on the whole joined string: capping the join let whichever entry landed on the
    * wrong side of the cutoff, regardless of who wrote it, evict another commenter's text entirely
    * (issue #28 review finding 2, round 3; a round 2 fix that kept the newest text instead of the
    * oldest moved which entry was vulnerable without closing the hole).
    */
  private val MaxCommentsChars = 20000

  /** The floor any single entry keeps, even when there are enough entries that an even split of
    * [[MaxCommentsChars]] would round down to something unreadable. A floor, not a hard total cap: a
    * thread with hundreds of entries can still exceed [[MaxCommentsChars]] overall, an acceptable
    * trade against a share so thin no entry says anything legible.
    */
  private val MinCommentShareChars = 500

  private def shareChars(entryCount: Int): Int =
    math.max(MaxCommentsChars / math.max(entryCount, 1), MinCommentShareChars)

  /** Capping PER ENTRY (issue #28 review finding 2, round 3) means no single commenter's text,
    * regardless of its position in the thread or its own length, can push another commenter's text
    * out of the window: each entry's fate depends only on its own length, never on where it sits or
    * how big its neighbours are. The notice travels with the cut text so an operator reading the
    * rendered block can tell a comment that ended from a comment the harness stopped quoting.
    */
  private def truncateEntry(entry: String, share: Int): String =
    if entry.length <= share then entry
    else entry.take(share) + s"\n\n[comment truncated by the harness at $share characters]"

  /** The `<untrusted-comments>` fence in the fix prompt skeleton is plain text, not a markup the
    * model parses structurally. A comment body starting with the literal `</untrusted-comments>`
    * string reads as the END of the untrusted section, landing whatever the commenter wrote after it
    * as unmarked, seemingly authoritative text at top level; a comment body containing the literal
    * `<untrusted-comments>` OPENING string forges a second fence boundary inside the data. Encoding
    * either tag's angle brackets as HTML entities keeps the forgery readable as data while making it
    * impossible for spliced text to reproduce either of the fence's own strings. The genuine fence
    * that ships in the skeleton is untouched: it never passes through here, only the comment DATA
    * does.
    *
    * `[\s\p{Z}]*` widens the whitespace class to every Unicode separator, not just the ASCII ones
    * Java's `\s` matches (a non breaking space is a real world bypass otherwise), and `[^>]*` after
    * the tag name tolerates any junk up to the closing `>` rather than requiring the tag to be
    * exactly whitespace padded. The capturing group around the optional `/` is what tells the
    * replacement which of the two strings to emit, so one pattern defuses both tags without
    * duplicating the whitespace and junk tolerance twice.
    */
  private def defuseFenceCloser(s: String): String =
    FenceTag.replaceAllIn(
      s,
      m => if m.group(1) == "/" then "&lt;/untrusted-comments&gt;" else "&lt;untrusted-comments&gt;"
    )

  private val FenceTag = "(?i)<[\\s\\p{Z}]*(/?)[\\s\\p{Z}]*untrusted-comments[^>]*>".r

  /** Neutralises, within a comment BODY only, never the trusted author prefix an entry opens with,
    * any line that could be mistaken for the grammar this file uses to join entries: the separator,
    * or a line shaped like another entry's own prefix. Without this, an attacker's own comment body
    * can embed a separator followed by a prefix naming someone else as plain text, and once joined
    * with the real entries the rendered block is byte identical to a genuine entry from that person,
    * indistinguishable from it (issue #28 review finding 1, round 3). Neutralising by prefixing the
    * offending line with a visible marker, rather than deleting it, keeps the text readable to an
    * operator while denying it the exact shape the harness uses to attribute text to an account.
    */
  private def escapeEntryGrammar(entry: String): String =
    parseEntry(entry) match
      case None                       => entry
      case Some((login, assoc, body)) =>
        val escapedBody = body.linesIterator
          .map { line =>
            if line == "---" || AuthorPrefixLine.matches(line) then
              s"[comment text, not a real entry boundary] $line"
            else line
          }
          .mkString("\n")
        s"@$login ($assoc):\n$escapedBody"

  /** A full line matching the author prefix grammar, used only to spot a FORGED copy of it inside a
    * comment body; the genuine prefix an entry opens with never runs through this check.
    */
  private val AuthorPrefixLine = "^@\\S+ \\([A-Z_]+\\):$".r

  private val AuthorPrefix = "^@(\\S+) \\(([A-Z_]+)\\):\\n".r

  /** Every comment entry arrives as an author prefix followed by a body; this splits one back into
    * its three pieces, or `None` if the entry does not have that shape at all. A deliberate second
    * copy of a parse the application tier also holds: the tier rule forbids reaching for that one,
    * and a shared parse would be a seam across which the two questions, "who wrote this" and "what
    * of this is safe to quote", could no longer move independently.
    */
  private def parseEntry(entry: String): Option[(String, String, String)] =
    AuthorPrefix.findFirstMatchIn(entry).map(m => (m.group(1), m.group(2), entry.substring(m.end)))
