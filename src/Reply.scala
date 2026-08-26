package in.rcard.litterbox

/** The reply protocol, both halves. [[since]] decides which entries on an issue thread count as a
  * human answering; [[splice]] turns entries into one block of prompt text safe to put in a slot a
  * worker will read. Four public names, [[since]], [[splice]], [[Marker]] and [[Since]], and
  * everything either half is made of is private.
  *
  * Why this is a TIER 1 file (`docs/adr/0001-framework-tier-is-kit-only.md` carries the tier rule
  * itself, and this object is the fourth file the rule names). Two call sites inside the shipped
  * graph each wrote the render composition out by hand, and only one of them carried the comment
  * saying why the three steps run in that order. The order IS the protection, so a composition
  * restated at each call site is a security decision with as many implementations as it has callers,
  * and a consumer authoring their own graph had none of them: any node of theirs that wanted to put
  * a human's words in front of a worker had to invent the pipeline again. The selection half was the
  * same shape one layer up, seven names widened out of the shipped graph so tests could drive them
  * one at a time, with the real decision living in how callers gated and composed them. The same
  * argument that moved the patch guard here moves both.
  *
  * Everything either composition is made of is private on purpose. A caller that can reach the
  * individual steps can order them wrongly, and a spec that drives the individual steps can watch
  * each one behave perfectly while the composition around them is wrong, which is exactly the state
  * this repository was in. Two public entry points mean there is one order and one answer to "does
  * this comment count", and they are the ones this file states.
  *
  * The two halves share ONE private entry parse. The tier rule already forbids reaching for the
  * application tier's copy, and a second copy inside this file would let the question "who wrote
  * this" and the question "what of this is safe to quote" answer differently about the same text,
  * which is the drift the shared parse exists to prevent. A future edit that specialises the parse
  * for one of the two questions silently changes the other, so specialise by adding a step, never by
  * narrowing the parse.
  *
  * What deliberately stays OUT. The sentinel a caller renders when the comment read FAILED is the
  * caller's to write: [[splice]] is handed the entries that were read, so it has no way to tell a
  * thread with nothing in it from a thread nobody could see, and inventing an answer for a question
  * it was not asked is how the two sentinels drift into meaning the same thing. So do the marker
  * token and the comment bodies the shipped graph posts, which are that graph's own voice on an
  * issue thread and travel in as arguments, and the two outcomes that need a capability or config to
  * reach, an unreadable read and an exhausted repair budget.
  */
object Reply:

  /** The CALLER's claim about a park marker this module cannot verify for itself, and the only
    * thing that decides what an ABSENT marker means. On a thread that DOES contain a marker both
    * cases answer identically, so the claim never changes which entries count; it only answers the
    * question the module has no way to ask the world.
    *
    * Why an argument rather than a comment at a call site. The selection half used to hand back the
    * WHOLE thread whenever it found no marker, a fallback that is safe only for a caller holding
    * outside evidence that a marker was posted at some point. Two callers knew that and each proved
    * it in prose beside its own call, one of them by hand writing a pre check the other did not
    * need, and a third caller had nothing to read at all. An unstated precondition that every caller
    * has to rediscover is a defect waiting for the next caller; stated as a closed type it is a
    * question the compiler makes them answer.
    *
    * What counts as evidence for [[Proven]]: a fact about the world, read outside this module, that
    * could only be true if a marker comment had already been posted on this thread. Today the only
    * such evidence in this repository is the shipped graph's `parked` LABEL on the issue, which the
    * pick phase confirms before it ever asks this question, because the one place that applies that
    * label also posts the marker. A consumer's own graph has evidence only if it can name a fact of
    * that shape; "the issue looks parked to me" is not one. When in doubt the honest claim is
    * [[Required]], whose worst case is answering no reply on a thread that has one, against
    * [[Proven]]'s worst case of reading an issue's ordinary unrelated discussion as an answer to a
    * question nobody asked.
    */
  enum Marker:

    /** No marker found means no reply. The claim for a caller with no evidence a marker was ever
      * posted, including every caller reached the first time an issue is parked at all.
      */
    case Required

    /** No marker found means every entry on the thread counts, the behaviour the pick phase has
      * always had. The claim for a caller holding outside evidence that a marker was posted and
      * has since been edited away, deleted, or never matched because a human applied the label by
      * hand.
      */
    case Proven

  /** What one selection answers: the entries that count as the reply, the entries that do not, and
    * the logins behind the accepted ones.
    *
    * Three fields rather than a list, because both marker reading callers finish the same
    * intermediate the same way and one of them needs the rejected entries too, for the operator log
    * line that explains why an issue stayed parked. Handing back an intermediate each caller
    * completes itself is how the answer to "does this comment count" grows a second implementation.
    *
    * [[accepted]] and [[ignored]] together are exactly the post boundary entries, each in thread
    * order, so a caller can tell "nothing was posted" from "something was posted and did not count"
    * by looking at [[ignored]] alone. Emptiness carries every distinction this module makes: the
    * cases that need a capability or config to reach, an unreadable read and an exhausted budget,
    * belong to callers that have those and are deliberately absent here.
    */
  final case class Since(accepted: List[String], ignored: List[String], authors: List[String])

  /** The reply on `comments` (oldest first, as `Caps.GitHub.issueComments` returns them) given
    * `claim` about `marker` posted by `viewer`.
    *
    * The boundary is the LAST marker entry, not the first: a thread can be parked, replied to,
    * resumed and parked again, and only the newest boundary bounds a reply that has not been spent
    * yet. Everything at or before it is closed off for good.
    *
    * `marker` and `viewer` travel in as arguments rather than being read from the shipped graph's
    * own constants, which this module may not name at all: the answer belongs to whichever question
    * the caller poses, and a module that reached for one graph's token would answer a different
    * question than the one it was asked.
    */
  def since(marker: String, viewer: String, comments: List[String], claim: Marker): Since =
    comments.lastIndexWhere(isMarkerEntry(marker, viewer, _)) match
      case -1  =>
        claim match
          case Marker.Required => Since(Nil, Nil, Nil)
          case Marker.Proven   => select(comments)
      case idx => select(comments.drop(idx + 1))

  /** Splits the post boundary entries into the two halves [[Since]] carries and names the accepted
    * authors, deduplicated in first seen order: the logins reach a prompt the shipped graph writes,
    * so the order they are named in is observable text, not an implementation detail free to move.
    */
  private def select(entries: List[String]): Since =
    val (accepted, ignored) = entries.partition(entryCountsAsReply)
    Since(accepted, ignored, accepted.flatMap(authorLogin).distinct)

  /** Whether `entry` is the harness's own marker comment: an ANCHORED match of `marker` at the
    * start of the body (not `contains`: GitHub's Quote reply button copies the quoted comment's
    * body verbatim, marker included, into the new comment, so an unanchored `contains` lets a
    * human's Quote reply of the marker match itself and silence the resume probe forever, issue #28
    * review finding 4) FROM THE VIEWER'S OWN LOGIN.
    *
    * Login, not association. An earlier round required an accepted association here instead, which
    * stopped a forged marker from an unvouched account but broke under a bot or GitHub App token:
    * such a token's `authorAssociation` reads `NONE` even on the harness's own comment, so the
    * genuine marker would never match, the selection would fall into the no marker arm, and the
    * loop would treat an issue's entire comment history as a reply forever (issue #28 review
    * finding 3, round 3). Login is provenance the harness actually controls: only the account it is
    * authenticated as can ever satisfy this check, whatever association GitHub reports for it. A
    * quoted marker is prefixed with `> ` by Quote reply and so never starts the body.
    */
  private def isMarkerEntry(marker: String, viewer: String, entry: String): Boolean =
    parseEntry(entry).exists((login, _, body) => login == viewer && body.startsWith(marker))

  /** The associations a resume decision trusts. Any GitHub account can comment on a public issue;
    * without this filter a drive by `NONE` association comment on a parked issue would resume it,
    * burning a dispatch, a repair budget and a reviewer dispatch at no cost to the commenter,
    * repeatable forever (issue #28 review finding 5). `OWNER`, `MEMBER` and `COLLABORATOR` are the
    * three associations GitHub grants to people the repository itself has given some standing to;
    * every other one (`NONE`, `CONTRIBUTOR`, `FIRST_TIME_CONTRIBUTOR`, `FIRST_TIMER`, ...) is a
    * public commenter the repository has not vouched for.
    */
  private val AcceptedAssociations: Set[String] = Set("OWNER", "MEMBER", "COLLABORATOR")

  /** Whether `entry` counts as a human reply that may resume a parked issue: from an accepted
    * association AND not blank once the author prefix is stripped (issue #28 review finding 9: a
    * whitespace only reply must not burn a dispatch either). An entry that does not even parse is
    * conservatively not a reply, since a probe about to make a trust decision off an association
    * must not assume a shape it can cheaply check.
    */
  private def entryCountsAsReply(entry: String): Boolean =
    parseEntry(entry).exists((_, assoc, body) => AcceptedAssociations(assoc) && !body.isBlank)

  /** The `@login` an entry's author prefix names. Only a login, never an entry's body, leaves the
    * selection half this way: comment TEXT reaches a worker exclusively through [[splice]], inside
    * the fence a caller builds for it.
    */
  private def authorLogin(entry: String): Option[String] =
    parseEntry(entry).map(_._1)

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
    * its three pieces, or `None` if the entry does not have that shape at all. The ONE copy in this
    * repository, serving both halves: the application tier no longer holds one, and this file may
    * not name anything there anyway. An entry that does not parse is never accepted and never
    * escaped, which is the conservative answer to both questions at once.
    */
  private def parseEntry(entry: String): Option[(String, String, String)] =
    AuthorPrefix.findFirstMatchIn(entry).map(m => (m.group(1), m.group(2), entry.substring(m.end)))
