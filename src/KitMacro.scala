package in.rcard.litterbox

import scala.quoted.*

/** The macro implementation behind `checkedShape` (`Kit.scala`, issue #39, RFC #26 decision 16's
  * compile time half). Lives in its own file, apart from `checkedShape`'s own `inline def`, because a
  * macro implementation has to already be compiled bytecode by the time the `inline def` that splices
  * it is expanded; scala-cli's own single compilation run satisfies that through suspend and resume
  * compilation across files, never within one file, so the split here is load bearing, not stylistic.
  *
  * Everything below reasons about TREES and TYPES only, never about a running value: by the time this
  * macro runs, no `Node`, no `Shape`, nothing this file talks about has been constructed yet, only
  * written down as source. That is both the whole point (the check happens before a single node could
  * ever execute, the same guarantee `Runner.validate` gives at runtime, only earlier) and the whole
  * limit (a `Node`'s own runtime `name` field, its `probe`, its `run` body, none of that exists yet
  * either, so nothing here can read it).
  */
private[litterbox] object KitMacro:

  /** Walks `shapeExpr`'s own AST, looking for the literal shape `Shape(entry = List(...), transitions
    * = List(Transition(...), ...))` written directly at the call site. Two independent things can
    * make this fail to recognise the literal: the expression is not that literal shape at all (a
    * variable, a function call, `Machine.shippedShape`, anything indirect), or it is that shape but
    * this walk cannot fully make sense of one of its pieces (an entry or transition list built some
    * way other than a plain `List(...)` literal, say). What happens next on either one is controlled
    * by `strict` (issue #43 review, BLOCKER 1, added alongside `checkedShapeStrict`, `Kit.scala`):
    * called leniently, from `checkedShape`, this falls back to returning `shapeExpr` completely
    * unchanged, neither case an error, `checkedShape`'s own doc states why silently degrading, rather
    * than aborting, is the only choice that keeps THAT function safe to sprinkle onto a `Shape` a
    * future author is not yet ready to write as a full literal, `Runner.validate` remains the backstop
    * for exactly that graph. Called strictly, from `checkedShapeStrict`, `LitterBox.graph`'s own only
    * caller, the identical situation instead aborts compilation: `checkedShapeStrict`'s own doc has the
    * reason a silent fallback is not safe for that one entry point specifically, a `Runner.validate`
    * backstop that would ALSO have to exist for it to be a real one, and does not for a node whose
    * `guard` was left at `Guard.Open` while its input type alone extends `RequiresReviewInput`. The
    * two "independent things" named above are NOT collapsed into one strict error (issue #43 review
    * round 2, BLOCKER B1, correcting the version of this walk an earlier round shipped, which raised
    * the identical "write a literal Shape here" message for both): "not that literal shape at all" and
    * "that shape, but one piece unreadable" are distinguished by `ParseFailure` (below `parseShape`'s
    * own definition) and raise two DIFFERENTLY worded errors, because the advice that fits the first,
    * write a literal `Shape` here, is advice the second consumer has already followed, and telling them
    * to do it again names nothing they could act on. `parseShape`'s own doc has the fuller reasoning,
    * and the reachable table of what this walk can and cannot read, widened by that same review round
    * (a `val` member of a class referenced bare, `List.empty`, out-of-order named arguments, a
    * `val`-bound `Transition`), is spelled out at `stablePathKey`, `literalListElements` and
    * `companionApplyArgs` below, each at the point it was widened.
    *
    * When the literal IS fully readable, this runs the SAME BFS `Runner.validate` runs (from `entry`,
    * over `transitions`, a state keyed on `(node, reviewed so far)` so no diamond or cycle is ever
    * expanded twice, `Runner.validate`'s own doc, `Kit.scala`, has the shared reasoning for why BFS
    * and why that particular key). What identity and trust actually read has to be stated precisely,
    * correcting an earlier, too strong version of this paragraph (issue #39 review round 3, M5):
    * identity is NOT read off the `Node.name` string the way `Runner.validate` reads it, it is read
    * off a STABLE PATH, a package, a module, or an immutable `val`, chained through every prefix, or
    * off a literal `name` argument written directly at an inline `Node.apply` call (`identifyRef`'s
    * own doc below has the full reasoning), through ONE canonical function, `stablePathKey`'s own
    * `canonical`, rather than the three incompatible ones an earlier version of this walk computed by
    * hand (issue #43 review round 3, BLOCKER 1, MAJOR 2, correcting this paragraph's own earlier claim
    * that a key could only ever SPLIT what `Runner.validate` merges, "never the other way", and that
    * "the one direction this can be wrong in is a violation missed here that `Runner.validate` still
    * catches": both halves of that sentence were false. It merges as well as splits: a `class`/`object`
    * companion pair sharing one simple name, or two inline `Node.apply` calls sharing one literal
    * `name`, can canonicalise to one key even when they build two genuinely different `Node`s
    * (`stablePathKey`'s own doc has the mechanism). At the time this paragraph was written,
    * `Runner.validate` was also not a backstop for what this walk misses on a marker-only-guarded node:
    * it read the hand written `guard` field, never the `RequiresReviewInput` marker this walk reads
    * instead, so it found nothing wrong with a shape whose only violation involved a node whose `guard`
    * was left at `Guard.Open`, the reproduced, compiled proof `checkedShapeStrict`'s own doc,
    * `Kit.scala`, gave at length at the time. That specific absence is closed now (issue #43 review
    * round 4, Tier 2, `Kit.scala`'s own doc on `GuardOf`/`Node.apply`): `Node.apply` derives the real
    * `Node`'s own `guard` field from the identical marker this walk reads, whenever the marker is
    * present, so `Runner.validate` genuinely is a backstop for a marker-only-guarded node now, though
    * it remains no backstop at all for the two alias residuals a canonical key still cannot close on its
    * own (`stablePathKey`'s own doc, and the paragraph on `checkReconciled` below, both name them):
    * `Runner.validate` is what catches those, unconditionally, by reading the graph's real values rather
    * than reconstructing an identity from source. What actually keeps a key COLLISION honest, as opposed
    * to an alias this walk cannot even see, is `checkReconciled` (below `parseShape`'s own definition),
    * not `Runner.validate`: two `NodeRef`s sharing one canonical key while disagreeing on `(trust,
    * guard)` is a hard compile error naming both, raised BEFORE the BFS below ever runs, rather than a
    * silent pick between two conflicting facts by whichever the visited set's `Map` happens to keep.
    * `trust`, unlike identity, genuinely is read the same way `Node.apply` itself derives it, by
    * summoning `TrustOf[O]` and reading which given answered (`nodeFacts`'s own doc below has the
    * mechanism), declining the whole shape rather than guessing whenever a third given, neither
    * `TrustOf.judged` nor `LowPriorityTrustOf.plain`, answers instead. Two respects still remain in
    * which this walk is NOT `Runner.validate`'s, both by design rather than by oversight: `guard` is
    * read structurally, off the reference's own INPUT type extending `RequiresReviewInput`, never off
    * the hand written `guard` field `Runner.validate` reads directly; and three DECLARATION level
    * checks `Runner.validate` also runs have no AST level analogue here at all (`violations`'s own doc,
    * below, names each one and why). That first respect used to be able to diverge from a genuine
    * `Runner.validate` outcome in BOTH directions (issue #39 review, MAJOR 2; issue #43 review round 4,
    * Tier 2, `Kit.scala`'s own doc on `GuardOf`/`Node.apply` has the fix): a node whose input type
    * carried this marker while its own `guard` argument was left `Guard.Open` used to read as guarded
    * HERE but not guarded by `Runner.validate`, since `Node.apply` never touched `guard` beyond the
    * hand written default. `Node.apply` now derives `Guard.RequiresReview` from the identical marker
    * this walk reads, whenever it is present, so that direction is closed: the two checks can no longer
    * disagree about a node that carries the marker. The other direction is unaffected and remains, by
    * design, permanently open: `guard = Guard.RequiresReview` written by hand on an input type that
    * does NOT carry the marker reads as `Guard.Open` here (structurally, off a type that does not
    * extend `RequiresReviewInput`) while `Runner.validate` reads the real, stricter field. That is
    * always the SAFE direction, this walk missing a violation `Runner.validate` still catches, never
    * this walk inventing one `Runner.validate` would not; `checkReconciled`'s own doc, above, has the
    * separate, pre-existing residual (a harmless key merge) where that safety property is not this
    * walk's to claim.
    */
  def checkShapeImpl(shapeExpr: Expr[Shape], strict: Boolean)(using Quotes): Expr[Shape] =
    import quotes.reflect.*

    /** Peels the wrapper layers a literal expression accumulates on its way through the typer
      * (`Inlined` around an inlined call, `Typed` around an argument adapted to an expected type,
      * `Block(Nil, _)` around a value with no local statements) without touching anything that
      * actually carries information (`Repeated`, `Apply`, `Ident`, `Select`, ...). Applied everywhere
      * this file inspects a tree's own SHAPE, so a wrapper the typer happened to add is never mistaken
      * for a reason to give up and fall back.
      */
    def unwrap(t: Term): Term = t match
      case Inlined(_, Nil, body) => unwrap(body)
      case Typed(body, _)        => unwrap(body)
      case Block(Nil, body)      => unwrap(body)
      case _                     => t

    /** A named argument (`Shape(entry = ..., transitions = ...)`, `Transition(from = ..., to = ...)`)
      * still carries its value inside a `NamedArg` wrapper after typing; this reaches past it so
      * positional and named calls are read identically below.
      */
    def stripNamed(t: Term): Term = t match
      case NamedArg(_, arg) => arg
      case other             => other

    /** The element terms of a literal `List(a, b, c)` call, or of the literal singleton `Nil` (read as
      * a zero element list, issue #43 review, correcting an earlier version of this function's own
      * doc: `Nil` used to fall back exactly like a variable or a `:+`/`++` chain does): `LitterBox.graph(
      * ..., shape = Shape(entry = List(only), transitions = Nil), ...)`, a fully literal declaration of
      * a single node graph with no transitions, an entirely ordinary shape for a consumer to write,
      * refused to compile under `checkedShapeStrict` purely because `Nil` was not yet recognised. `Nil`
      * is exactly as literal as `List()`, always the same value, so recognising it costs none of the
      * caution the rest of this function reserves for varargs calls that only LOOK like `List(...)`.
      *
      * This function is shared by BOTH `checkedShape` and `checkedShapeStrict` (`strict`'s own only two
      * values thread through to the same `literalListElements`, `Kit.scala` has both entries), so
      * recognising `Nil` here is NOT a strict-only change, a claim three separate doc paragraphs made in
      * capitals at the time this fix shipped and all three turned out to be wrong (issue #43 review
      * round 2, MAJOR M3, `Kit.scala`'s own doc on `checkedShapeStrict`, `LitterBox.scala`'s own doc on
      * `LitterBox.graph`, and an earlier version of this very paragraph): a `Shape` whose only unreadable
      * piece was a `Nil` transitions list used to fall back SILENTLY under `checkedShape` too, returning
      * `shapeExpr` unchanged with no error, and now gets FULLY PARSED there as well, which means it can
      * newly fail to compile under `checkedShape`, `checkedShapeStrict`'s own lenient sibling, if the
      * shape it can now actually read turns out to have a genuine review-reachability violation.
      * Reproduced by compiling `checkedShape(Shape(entry = List(OpenPr), transitions = Nil))` for an
      * `OpenPr` unreachable by any reviewer against both this tree and the tree immediately before this
      * fix: clean before, a compile error naming the violation after. The direction is safe, MORE
      * checking, never less, so nothing this walk used to accept before this fix is rejected now, but
      * "safe direction" and "unchanged" are different claims, and `checkedShape` is a published, public
      * `inline def` (shipped in `0.2.0`) with no stability promise attached to it beyond the blanket `0.x`
      * one `README.md`'s version policy section already states, so a caller of `checkedShape` itself,
      * not only of `checkedShapeStrict`, can have working code newly fail to compile after upgrading past
      * this fix. `Kit.scala`'s own doc on `checkedShape` and `checkedShapeStrict`, and `LitterBox.scala`'s
      * own doc on `LitterBox.graph`, all now say this plainly instead of "UNCHANGED"; `test/GraphMacroSpec
      * .scala` pins the new behaviour so it reads as a decision made with eyes open, not an accident later
      * discovered. The SAME source-breaking widening applies, for the identical reason, to every other
      * widening `checkedShapeStrict`'s own round 2 fix (BLOCKER B1 part 2) made to the functions this
      * file shares between `strict` and lenient: `List.empty`/`List.empty[...]` recognition (this
      * function, just below), a `This`-prefixed stable path (`stablePathKey`), out-of-order named
      * arguments (`companionApplyArgs`), and a local `val`-bound `Transition` (`parseTransition`) are ALL
      * shared, not strict-only, so a `checkedShape` caller whose shape's only unreadable piece was one of
      * those now also gets it fully parsed, and can also newly fail to compile if that parse finds a
      * genuine violation. One corrected paragraph, not four separate caveats, covers all of it: the
      * mechanism, "shared functions, so no widening here is strict-only", is identical in every case.
      * `None` for anything else
      * (a variable, a `:+`/`++` chain, a DIFFERENT varargs method entirely, ...): `List(...)`'s own
      * single argument, once past the varargs apply itself, is a `Repeated` node holding exactly the
      * elements written at that call site; that `Repeated` shape alone is not enough to trust, though,
      * since any varargs method call shares it (issue #39 review round 3, M1, confirmed by writing a
      * consumer helper of the identical shape and inspecting what the compiler actually calls it: `def
      * firstOnly(ns: Node[?, ?]*): List[Node[?, ?]] = ns.toList.take(1)` used as `entry =
      * firstOnly(Safe, Danger)` produced the SAME `Apply(fun, List(Repeated(List(Safe, Danger), _)))`
      * shape a real `List(Safe, Danger)` does, so a version of this function that trusted the shape
      * alone read `Danger` as a genuine `entry` element even though `firstOnly` itself drops it). The
      * callee therefore has to be confirmed as `List.apply` itself, not merely inferred from the tree
      * shape it happens to share: `qualifier.tpe.typeSymbol`, not `qualifier.symbol`, is what that
      * confirmation has to compare against `List`'s own module class, confirmed by writing the exact
      * check and inspecting what each side actually resolves to rather than assumed, since unqualified
      * `List` in ordinary code resolves through `scala`'s own package object alias `val List = ...`,
      * whose TERM symbol is that alias `val`, never `List`'s own module symbol, while its TYPE still
      * names the real module correctly. The identical alias trap is why `Nil`, below, is also matched
      * on its widened TYPE's symbol, never on the identifier's own term symbol.
      *
      * `List.empty`/`List.empty[Transition]` also read as `Nil` (issue #43 review round 2, BLOCKER B1
      * part 2): an ordinary consumer idiom for "no transitions" this walk used to fall back on,
      * confirmed by compiling `Shape(entry = List(only), transitions = List.empty)` as real top-level
      * consumer code and finding it rejected under `checkedShapeStrict` purely because `List.empty` was
      * not yet recognised, the same class of gap the `Nil` case above already closed for the literal
      * `Nil` spelling. `List.empty[A]`'s own definition (`def empty[A]: List[A] = Nil`) takes only a
      * TYPE argument, never a value one, so its call tree is a bare `TypeApply(Select(qualifier,
      * "empty"), _)`, with no enclosing `Apply` for a value argument list at all, confirmed by printing
      * the typed tree of exactly this call and inspecting its shape rather than assumed; the `Apply`
      * case above therefore can never match it, and this needs its own case. `qualifier.tpe.typeSymbol
      * == listModuleClass`, the identical alias-safe comparison the `List(...)` case above already
      * uses, is what keeps a differently named zero-argument-type method called `empty` on some other
      * module from being misread as this one.
      */
    val listModuleClass = Symbol.requiredModule("scala.collection.immutable.List").moduleClass
    val nilModuleClass   = Symbol.requiredModule("scala.collection.immutable.Nil").moduleClass

    def literalListElements(t: Term): Option[List[Term]] =
      val u = unwrap(t)
      if u.tpe.widen.typeSymbol == nilModuleClass then Some(Nil)
      else
        u match
          case Apply(fun, List(argsTerm)) =>
            val calleeQualifier = fun match
              case Select(qualifier, "apply")               => Some(qualifier)
              case TypeApply(Select(qualifier, "apply"), _) => Some(qualifier)
              case _                                        => None
            calleeQualifier.filter(_.tpe.typeSymbol == listModuleClass) match
              case Some(_) =>
                unwrap(argsTerm) match
                  case Repeated(elems, _) => Some(elems)
                  case _                  => None
              case None => None
          case TypeApply(Select(qualifier, "empty"), _) if qualifier.tpe.typeSymbol == listModuleClass =>
            Some(Nil)
          case _ => None

    /** The two positional arguments of a case class companion's `apply` call, matched by comparing the
      * called method's own OWNER against `moduleClass`, not by name or arity alone: a differently
      * named two argument function call must not be mistaken for `Shape(...)` or `Transition(...)`.
      *
      * Also reads straight through the compiler's own OUT-OF-ORDER NAMED ARGUMENT rewrite (issue #43
      * review round 2, BLOCKER B1 part 2): `Shape(transitions = List(...), entry = List(...))`, named
      * arguments written in some order other than `Shape`'s own declared `(entry, transitions)` order,
      * types not as a bare `Apply` but as a `Block` holding one SYNTHETIC `val` per out-of-order
      * argument, each initialised to that argument's own expression in the WRITTEN order (so a
      * left-to-right side effect order is preserved even though the call itself cannot run positionally
      * any more), followed by the real `Shape.apply(entry = entry$1, transitions = transitions$1)` call
      * referencing those temporaries by `Ident`, confirmed by compiling exactly that snippet and
      * inspecting the typed tree rather than assumed: two `ValDef`s, both flagged `Synthetic`, then an
      * `Apply` whose own arguments are already back in DECLARED order, just naming the temporaries
      * instead of the original expressions. Reproduced as a real compile failure before this fix: a
      * `LitterBox.graph` call with `entry`/`transitions` swapped from `Shape`'s own declaration order
      * fell all the way to the "not a literal at all" error, even though every piece of it was written
      * as an inline literal. Recognised here, rather than by loosening `unwrap` to peel EVERY `Block`
      * (which would also swallow a block wrapping a genuine side effect ahead of a literal `Shape`, an
      * indirection this function has never accepted and still should not): the `Synthetic` flag is what
      * tells the compiler's own temp-val rewrite apart from a hand-written block, so only THAT shape is
      * substituted through, by replacing each `Ident` argument of the trailing `Apply` with the bound
      * `ValDef`'s own right-hand side once the ordinary `Apply` match below has found it.
      */
    def companionApplyArgs(t: Term, moduleClass: Symbol): Option[List[Term]] =
      def direct(term: Term): Option[List[Term]] =
        unwrap(term) match
          case Apply(fun, args) if fun.symbol.exists && fun.symbol.owner == moduleClass =>
            Some(args.map(a => stripNamed(unwrap(a))))
          case _ => None
      unwrap(t) match
        case Block(stats, expr)
            if stats.nonEmpty && stats.forall {
              case vd: ValDef => vd.symbol.flags.is(Flags.Synthetic)
              case _          => false
            } =>
          val bindings: Map[Symbol, Term] =
            stats.collect { case vd: ValDef if vd.rhs.isDefined => vd.symbol -> vd.rhs.get }.toMap
          direct(expr).map(_.map { a =>
            unwrap(a) match
              case id @ Ident(_) if bindings.contains(id.symbol) => bindings(id.symbol)
              case other                                         => other
          })
        case _ => direct(t)

    val shapeModuleClass      = Symbol.requiredModule("in.rcard.litterbox.Shape").moduleClass
    val transitionModuleClass = Symbol.requiredModule("in.rcard.litterbox.Transition").moduleClass
    val nodeModuleClass       = Symbol.requiredModule("in.rcard.litterbox.Node").moduleClass

    /** The exact given `Node.apply`'s own `using t: TrustOf[O]` resolves to for a genuinely
      * `AgentDispatch.Judged`-shaped `O` (`nodeFacts` below reads `trust` by summoning `TrustOf[o]`
      * itself and comparing WHICH given answered against this symbol, issue #39 review, B3).
      */
    val judgedSymbol = Symbol.requiredMethod("in.rcard.litterbox.TrustOf.judged")

    /** The exact given `LowPriorityTrustOf.plain` resolves to for every `O` that is not
      * `AgentDispatch.Judged`-shaped (`nodeFacts` below has the reason a summoned given that is
      * neither this nor `judgedSymbol` above must decline rather than be read as this one by default,
      * issue #39 review round 3, M2). Looked up through `TrustOf`'s own path, `in.rcard.litterbox.
      * TrustOf.plain`, not through `LowPriorityTrustOf` directly, even though `plain` is declared on
      * that parent trait: confirmed by instrumenting this exact lookup and inspecting what each path
      * actually resolves to, `Symbol.requiredMethod` against the trait's OWN path is unreliable this
      * early in scala-cli's suspend and resume compilation (`checkShapeImpl`'s own doc has the reason
      * that compilation shape exists at all), landing on an unrelated symbol on the very first macro
      * expansion of a run and only the real one from then on, while the identical lookup through
      * `TrustOf`, the object every OTHER symbol in this file is already anchored to, is stable from the
      * first expansion.
      */
    val plainSymbol = Symbol.requiredMethod("in.rcard.litterbox.TrustOf.plain")

    /** One node as this macro can see it: `key` is what the reachability walk deduplicates and links
      * edges on, `display` is what an error message shows a human, `trust`/`guard` are read off the
      * reference's own static type (`nodeFacts` below), never off a value, and `pos` is the reference's
      * own source position, carried purely so `checkReconciled` below can point a human at BOTH sides
      * of a key collision it finds, not only the one `parseShape`'s own fold happened to visit last
      * (issue #43 review round 3, BLOCKER 1 part 2).
      */
    final case class NodeRef(key: String, display: String, trust: Trust, guard: Guard, pos: Position)

    /** Peels a curried call (`mk("x")("y")`; `Node.apply`'s own call shape too, `(mainArgs)(using
      * t: TrustOf[O])` is one more curried layer past the explicit arguments) down to the innermost
      * function term and the FULL list of argument lists, outermost call last. A one layer walk,
      * `case Apply(fun, _) => ...`, an earlier version of this function did, sees only the OUTERMOST
      * layer, so a curried call with genuine arguments in an inner layer, and `Node.apply`'s own
      * always present trailing `using` layer, both read as if they carried none (issue #39 review,
      * MAJOR 5, `mk("x")("y")` keyed and displayed as bare `mk`).
      */
    def uncurry(t: Term): (Term, List[List[Term]]) =
      t match
        case Apply(inner, args) =>
          val (fn, rest) = uncurry(inner)
          (fn, rest :+ args)
        case _ => (t, Nil)

    /** The literal `name` argument of `Node.apply`'s own first, non `using`, argument list, or `None`
      * if it was written some other way (a variable, string interpolation, a computed value, ...).
      * Reads a `NamedArg("name", ...)` wherever it sits in that list, since a named call can write its
      * arguments in any order, and otherwise falls back to the FIRST positional argument whenever
      * `name` itself was not written as a named argument, regardless of whether OTHER arguments in the
      * same call are named, `name` being `Node.apply`'s own first declared parameter.
      */
    def literalNameArg(args: List[Term]): Option[String] =
      val named = args.collectFirst { case NamedArg("name", Literal(StringConstant(s))) => s }
      named.orElse(args.headOption.collect { case Literal(StringConstant(s)) => s })

    /** Whether `sym` is one safe LINK of a STABLE PATH: a package or a module (an object, itself a
      * singleton, so two references to it are provably the same value, never able to drift) never
      * change what they name, and an immutable `val` (a term that is neither a method nor mutable) is
      * the only kind of ordinary member this walk trusts, because it is the only kind guaranteed to
      * name the SAME value every time it is read within one compilation. A `def`, paramless or not, is
      * excluded on purpose (issue #39 review round 3, B2): its own body can build a fresh value on
      * every call, so two references to the same `def` are not provably the same `Node`, confirmed by
      * writing exactly that, a paramless factory closing over a mutable counter, and finding the old
      * rule merged its calls anyway. A `var` is excluded for the identical reason, it can rebind
      * between two occurrences.
      */
    def isStablePathLink(sym: Symbol): Boolean =
      sym.exists &&
        (sym.flags.is(Flags.Package) ||
          sym.flags.is(Flags.Module) ||
          (sym.isTerm && !sym.flags.is(Flags.Method) && !sym.flags.is(Flags.Mutable)))

    /** Canonicalises a symbol's own `fullName` into the ONE string every SINGLETON-rooted key in this
      * file is now built from (issue #43 review round 3, BLOCKER 1, replacing three keying schemes that
      * each encoded the identical symbol chain as a different string). `Symbol.fullName` puts a
      * trailing `$` on every MODULE CLASS segment of the chain, a fact about how the module VALUE's own
      * class is named on the JVM, never a fact about the source a consumer actually wrote: `ZZH.Start`
      * for a member of `object ZZH` reports as `...ZZH$.Start`, a top-level `val` in a package reports
      * with the package object's own synthetic class carrying `$package$`, and neither `$` survives
      * into the concatenation the general `Select` case below used to build by hand, `stablePathKey
      * (qualifier) + "." + name`, which never went through `fullName` at all. The consequence, before
      * this fix: the SAME `Node` value, `ZZH.Start`, written bare through a wildcard import (`Ident`,
      * keyed on the raw `t.symbol.fullName`, `$` included) and written qualified (`Select(Ident(ZZH),
      * Start)`, keyed on the concatenation, no `$`), produced two DIFFERENT keys, confirmed by
      * instrumenting this exact macro and printing both side by side for exactly that snippet. Because
      * `violations`' own BFS below keys `entry` and `byFrom` on this string, two keys for one node SPLIT
      * it: an edge whose `from` was keyed one way never links to an `entry` element keyed the other way,
      * so a marker-only-guarded node past that broken edge is never dequeued, never checked, and this
      * macro reports nothing wrong, silently (issue #43 review round 3, reproductions (a), (b) and (c):
      * a wildcard import, a package-qualified top-level `val`, and an `object` member spelled bare in
      * one place and qualified in another, each confirmed as a real compiled file reaching an unreviewed
      * `RequiresReviewInput` node with `LitterBox.graph` reporting nothing and `Runner.validate` on the
      * identical shape also returning `List()`, closing off the "`Runner.validate` remains the backstop"
      * claim this file used to make in capitals, corrected at `checkShapeImpl`'s own doc above, MAJOR
      * 2). Stripping the trailing `$` off EVERY dot-separated segment, not only the last one (a module
      * nested inside another module carries `$` on more than one), is the whole fix: both spellings
      * above now canonicalise to the identical string, because both ARE, underneath, `t.symbol` on the
      * same member, and reading the key straight off that shared symbol, rather than reconstructing a
      * string from whichever AST shape the reference happened to take, is what makes the reconstruction
      * itself stop being a second, independently drifting source of truth.
      */
    def canonical(sym: Symbol): String =
      sym.fullName.split('.').map(_.stripSuffix("$")).mkString(".")

    /** The full stable path key of `t`, `None` unless EVERY prefix, all the way down to the root, is a
      * stable path link (`isStablePathLink` above). ONE canonical function now, `canonical` above,
      * replacing the three incompatible schemes `Ident`, `Select(This(_), _)` and the general `Select`
      * case each used to compute by hand (issue #43 review round 3, BLOCKER 1); `canonical`'s own doc
      * has the reproduced key values that motivated the collapse. Two DIFFERENT REASONS a reference can
      * be a stable path stay two different key NAMESPACES below, deliberately not folded further,
      * because only one of them is sound to read straight off `t.symbol`:
      *
      *   - The qualifier is a SINGLETON: a package, a module (an `object`, the same value at every
      *     occurrence within one compilation, never able to drift), or `This`, the macro's own enclosing
      *     instance, exactly one fixed value at any single call site this macro ever expands at
      *     (`isStablePathLink`'s own doc has the reason a `This` receiver is trusted the same way a
      *     module is; a class's own `val A` referenced bare inside one of its methods types as
      *     `Select(This(<the class>), A)`, never `Ident(A)`, confirmed by compiling exactly that and
      *     inspecting the typed tree, not assumed). For all three, `canonical(t.symbol)` alone is
      *     enough, no recursion into the qualifier needed: the compiler resolves `t.symbol` to the SAME
      *     symbol no matter how the reference was spelled (bare via a wildcard import, dotted, fully
      *     package-qualified), so reading the key off that shared symbol, rather than reconstructing a
      *     string from the AST shape the reference happened to take, is what makes every spelling of the
      *     same singleton-qualified member collapse to one key. This branch is what closes (a), (b) and
      *     (c): a wildcard-imported bare reference and a fully qualified one now both resolve to
      *     `canonical(t.symbol)`, the identical string, because both name the exact same symbol.
      *
      *     `canonical(t.symbol)` alone is NOT, however, guaranteed to tell a `class` apart from its own
      *     companion `object` of the identical simple name, precisely because canonicalisation strips
      *     the one segment, the module class's trailing `$`, that used to be the only textual difference
      *     between them: `class ZZX`'s own member `A` and `object ZZX`'s own member `A` both canonicalise
      *     to `....ZZX.A`. This is NOT a regression this branch introduces silently, it is the residual
      *     `checkReconciled` below exists to catch loudly (issue #43 review round 3, reproduction (e)):
      *     two `NodeRef`s sharing this one key with genuinely different `trust`/`guard` (the class's `A`
      *     extending `RequiresReviewInput`, the object's not) is a hard compile error naming both, rather
      *     than a silent pick between them by whichever the visited set dequeues first.
      *
      *   - The qualifier is an ordinary INSTANCE, a stable but non-singleton value (`val a: SomeClass`,
      *     an `a.node` reference where `a` could, for all this macro can prove, be one of several
      *     distinct `SomeClass` values at once). This branch DECLINES ENTIRELY, `None`, never a key
      *     (issue #43 review round 4, deciding between two tiers a round 3 reviewer offered and choosing
      *     the narrower one): every instance-qualified receiver, regardless of what it widens to, is
      *     `UnreadablePiece`, a hard error under `checkedShapeStrict` naming the unreadable term, a silent
      *     lenient fallback under `checkedShape`. This is NOT the first rule tried here, and the two
      *     earlier ones are worth recording, since both were found unsound rather than merely incomplete.
      *     `canonical(t.symbol)` alone, an earlier version of this branch's rejected first attempt, would
      *     name only `SomeClass.node`, the DECLARATION, throwing the receiver away and merging `a.node`/
      *     `b.node` for two genuinely different `a`/`b` into one key (issue #39 review round 3, B1). The
      *     fix that shipped for one review round after that, keying on `s"instance:$prefix#$name"` by
      *     recursing `stablePathKey(qualifier)`, closed B1 (the `a.node`/`b.node` idiom `GraphMacroSpec`
      *     still pins, now declined and falling back rather than genuinely walked, see below) but opened a
      *     narrower, related hole of its own (issue #43 review round 3, BLOCKER 1, reproduction (d)): a
      *     class member reached through a SECOND `val` the SAME class declares and initialises to `this`
      *     (`val self0: ZZC = this` inside `ZZC`'s own body, then `self0.Start` alongside a bare `Start`
      *     elsewhere in the same shape) keys DIFFERENTLY from the bare reference, `instance:...#Start`
      *     against the singleton branch's plain `...ZZC.Start`, even though they are the identical `Node`
      *     at runtime, a silent SPLIT the macro has no dataflow analysis to rule out (`self0` is only
      *     provably STABLE, never provably `this`). The round 3 patch narrowed that one case, declining
      *     only a receiver whose own WIDENED type exactly matched the class enclosing the macro's own
      *     splice site, rather than closing the branch altogether, on the theory that only an alias of
      *     `this` inside the SAME class could ever collide with the singleton branch this way. Round 4
      *     found that theory false with three sidesteps, none involving `this` at all, so the patch had no
      *     purchase on any of them (issue #43 review round 4): ascribing the alias to a SUPERTYPE of the
      *     enclosing class (`val self0: N13Base = this`, so the widened receiver type no longer equals the
      *     splice site's own class exactly) defeated the equality test outright; moving the
      *     `LitterBox.graph` call into a nested `object` moved the splice site's own enclosing class to
      *     that object's synthetic module class, a class the alias was never typed as in the first place;
      *     and aliasing something that is not a class at all (`val n1h: N1H.type = N1H`, an object's own
      *     singleton type, or a plain `val n2b: N2H = n2a` between two ordinary top-level `val`s of the
      *     same class) needed no enclosing-class match whatsoever, since neither alias sits inside any
      *     class the receiver's type could be compared against. Each of the three reaches this exact
      *     branch, an ordinary instance qualifier that is neither `This` nor a module/package, and each
      *     used to be KEYED, silently splitting the aliased node from its bare spelling elsewhere in the
      *     same shape, seven real files compiling clean through a violating graph confirmed this against
      *     the working tree before this fix. The root cause both narrow patches shared: this macro has no
      *     dataflow analysis at all, so "the receiver's widened type happens to equal some class" was
      *     always a proxy for "this receiver might alias `this`", a proxy the three sidesteps each defeat
      *     a different way, and no FOURTH proxy is going to fare any better, since the actual fact needed,
      *     whether two distinct instance qualifiers name the same runtime value or two genuinely different
      *     ones, is exactly the fact a tree-only macro cannot observe (`checkShapeImpl`'s own doc above
      *     has the general limit). So this branch stops trying to distinguish provably-same from
      *     provably-different instance qualifiers and declines ALL of them, uniformly: the macro cannot
      *     tell `a.node`/`b.node` on two genuinely different instances (the idiom `GraphMacroSpec` pins,
      *     still compiling under `checkedShape`'s own lenient fallback, `Runner.validate` the backstop for
      *     it now, same as any other declined shape) apart from `n2a.Start`/`n2b.Start` on one instance
      *     aliased twice, so keying an instance path AT ALL is guessing, and this file's own stated
      *     philosophy, restated at `checkShapeImpl`'s own doc above, is "canonicalise, or decline loudly
      *     rather than guess": there is no honest THIRD option between those two for a fact this macro
      *     cannot observe. A consumer whose shape genuinely needs an instance-qualified node reference has
      *     `checkedShape`'s own lenient fallback with `Runner.validate` as a backstop, or restructures the
      *     reference onto a top-level `val`, an `object` member, or an inline `Node(name = ..., ...)` call,
      *     every one of which this walk reads directly.
      */
    def stablePathKey(t: Term): Option[String] =
      t match
        case Ident(_) =>
          Option.when(isStablePathLink(t.symbol))(canonical(t.symbol))
        case Select(qualifier, name) if !isStablePathLink(t.symbol) => None
        case Select(qualifier, name) =>
          unwrap(qualifier) match
            case This(_) => Some(canonical(t.symbol))
            case q if q.symbol.flags.is(Flags.Module) || q.symbol.flags.is(Flags.Package) =>
              Some(canonical(t.symbol))
            case q => None
        case _ => None

    /** Identifies one node REFERENCE: `key` is what `violations` below deduplicates and links edges
      * on, `display` is what an error message shows a human, and `None` is what makes `nodeFacts`
      * decline, which `parseShape`'s own fold turns into a fallback for the WHOLE shape. Restated once
      * more as ONE rule (issue #39 review round 3, B1 and B2, correcting the previous round's own
      * fix): a reference is trusted only when it is a STABLE PATH (`stablePathKey` above, a package, a
      * module, or an immutable `val`, chained through every prefix) or an inline `Node.apply` call
      * carrying a literal `name`; every other shape, including every method call that is not
      * `Node.apply` itself, regardless of how many arguments it carries, falls back rather than
      * guesses. The previous round's own rule, trusting a call with NO arguments in any curried layer
      * on the theory that a zero argument call is fully determined by which method it calls, is
      * dropped entirely: that theory is false for a STATEFUL zero argument factory (`stage(): Node`
      * built from a mutable counter, confirmed to compile clean under the old rule on a graph
      * `Runner.validate` accepts, and to abort under it too, both reproduced), and the same
      * unconditional trust applied to a bare `Ident`/`Select` regardless of whether the symbol behind
      * it was a `val` or a `def`, so a paramless `def stage: Node` with the identical stateful body
      * went through unfixed even after B1's own repair. Falling back for every method call that is not
      * `Node.apply` costs real detections `Machine.shippedShape`'s own doc already accounts for (its
      * own node references are exactly this shape), never a false rejection: `Runner.validate` remains
      * the backstop for anything this declines.
      *
      * A stable path (`Pick`, `Machine.OpenPr`, a `val` member of a class read bare inside a method, ...)
      * is keyed on the WHOLE chain (`stablePathKey` above): the same path written twice in one shape is
      * the same value, hence the same `Node`, hence, trivially, the same `name`, without this macro ever
      * having to read that field. `a.node` for an ordinary instance `a` is NOT an example of this any
      * more (issue #43 review round 4, `stablePathKey`'s own doc has the reasoning for declining every
      * instance-qualified receiver rather than keying it): `stablePathKey(t)` returns `None` for it, so
      * this function's own `Ident(_) | Select(_, _)` case below declines it too, rather than reaching
      * this "keyed on the whole chain" case at all.
      *
      * A call whose own callee, after peeling every curried layer (`uncurry` above), resolves into
      * `Node`'s own companion IS `Node.apply` itself, written inline; here the `name` string really is
      * sitting right there in the AST, in the call's own first argument list, so this reads it
      * directly (`literalNameArg` above) and keys on THAT string, the same fact `Runner.validate`
      * would compute from the constructed value. Two inline constructions naming the same literal
      * `"Pick"` are therefore the same node here too, matching `Runner.validate` (issue #39 review,
      * B2: keying an inline construction by its own source position instead, an earlier version of
      * this function did, made every occurrence of one a DIFFERENT node, so `byFrom` below never
      * linked the edges a real graph naming the same node twice actually has, and the walk missed
      * every violation past `entry`). When `name` was written some other way, this returns `None`
      * rather than inventing a key: guessing which two inline constructions "probably" share a name is
      * exactly the confident-but-wrong reading this whole function exists to avoid.
      *
      * The key returned for an inline construction is `s"inline:$n"`, never the bare literal `n` (issue
      * #43 review round 3, BLOCKER 1 part 1): `stablePathKey`'s own namespace above (a plain dotted path,
      * built from a SYMBOL, `canonical(t.symbol)`) can never, by construction, equal a bare source string
      * typed by a consumer as a `name` argument, but relying on that being true by accident, rather than
      * by a namespace prefix that makes it true by CONSTRUCTION, is exactly the kind of coincidence this
      * file's own `List`/`Nil` alias-trap paragraphs elsewhere (`literalListElements`'s own doc above)
      * already warn against trusting. A second SYMBOL-built namespace used to sit alongside this one,
      * `instance:...#...`, an instance-qualified receiver chained through its own qualifier; it no longer
      * does, `stablePathKey`'s own doc above has the reason every instance-qualified receiver declines
      * outright instead of keying (issue #43 review round 4), but the `inline:` prefix chosen here never
      * depended on that second namespace existing, only on neither namespace ever being able to coincide
      * with a bare consumer-written string, so this reasoning still holds unchanged. `inline:` costs
      * nothing an
      * inline construction ever needed: two inline `Node("Pick", ...)` calls still key identically to
      * each other (`inline:Pick` both times), the fact B2 above fixed, only now provably unable to
      * collide with a stable path spelled `Pick` too.
      */
    def identifyRef(t: Term): Option[(String, String)] =
      t match
        case Ident(_) | Select(_, _) =>
          stablePathKey(t).map(key => (key, t.symbol.name))
        case Apply(_, _) =>
          val (fn, argss) = uncurry(t)
          if fn.symbol.exists && fn.symbol.owner == nodeModuleClass then
            literalNameArg(argss.headOption.getOrElse(Nil)).map(n => (s"inline:$n", n))
          else None
        case _ => None

    /** Reads `trust`/`guard` off a node reference's own static type `Node[I, O]`, never off a value
      * (`checkShapeImpl`'s own doc has the reason no value exists yet to read). `I` extending
      * `RequiresReviewInput` is what `guard` is read from (`RequiresReviewInput`'s own doc, `Kit.scala`,
      * has the reason `guard` cannot instead be read off `Node.apply`'s own hand written argument, a
      * Scala parameter section ordering rule, not an implicit resolution one): the fact still has to
      * be read SOMEWHERE for the macro's own purposes, and a subtype test against the reference's own
      * concrete `I` is what reads it without needing a runtime value.
      *
      * `trust` is read by SUMMONING `TrustOf[o]` right here, inside the macro, and asking WHICH given
      * answered, `TrustOf.judged` or `LowPriorityTrustOf.plain` (issue #39 review, B3): this is the
      * identical search `Node.apply`'s own `using t: TrustOf[O]` runs when the real `Node` is built,
      * so there is no second, independently re-derived rule here that could ever disagree with it. An earlier
      * version of this function instead pattern matched `O` against the TYPE `AgentDispatch.Judged[a]`
      * directly (`case '[AgentDispatch.Judged[a]] => Trust.Reviewed`), which matches by SUBTYPING, not
      * by the exact unification `given judged[A]: TrustOf[Judged[A]]` requires: `Node[Unit, Null]` and
      * `Node[Unit, AgentDispatch.Judged[Unit] & Serializable]` both conform to `Judged[a]` for some
      * `a` without being `Judged`-shaped at all (`Null` conforms to every reference type; an
      * intersection type is narrower than either side, never equal to one), so that version stamped
      * `Trust.Reviewed` on both, while the real `TrustOf[Null]` and
      * `TrustOf[Judged[Unit] & Serializable]` both resolve to `LowPriorityTrustOf.plain` (given
      * resolution is invariant here, `TrustOf` carries no variance annotation, so only an EXACT
      * `Judged[A]` ever satisfies `judged`'s own result type). Summoning the given directly, rather
      * than re-deriving the rule it follows, is what keeps the two from ever being able to disagree
      * again; it also makes a separate `O =:= Nothing` special case unnecessary, an earlier version of
      * this function needed one only because the subtyping match it used could not otherwise tell
      * `Nothing` apart from a genuine `Judged` shape, and `TrustOf[Nothing]` was never going to resolve
      * to `judged` either, for the identical invariance reason.
      *
      * A summoned given that is neither `judgedSymbol` nor `plainSymbol` is UNREADABLE, and this
      * declines the WHOLE shape rather than guess (issue #39 review round 3, M2, correcting a version
      * of this function that read anything other than `judgedSymbol` as `Trust.Plain`): a hand written
      * given can reuse `judged`'s own `trust` answer through an unchecked cast (`Kit.scala`'s own doc
      * on `Node.apply` names the exact forgery, `TrustOf.judged.asInstanceOf[TrustOf[MyType]]`, and
      * states plainly that it compiles today), so `Node.apply` itself would stamp `Trust.Reviewed` on
      * the real `Node` whenever THAT given wins resolution, while a version of this function that
      * defaulted anything unrecognised to `Trust.Plain` stamped the opposite, confirmed by writing
      * exactly that given and finding the old rule aborted compilation on a graph `Runner.validate`
      * accepts (the real `Node`'s own `trust`, read the SAME way `Node.apply` reads it, genuinely is
      * `Reviewed`). Declining here, rather than guessing `Plain`, is the only reading that can never
      * disagree with what `Node.apply` actually stamps.
      *
      * One fact has to be ruled out before either fact below is trusted, confirmed by writing the
      * exact type and inspecting what the match actually does with it, not merely assumed: a WIDENED
      * reference's captured `i`/`o` are the wildcard's own abstract bounds, never the concrete types
      * the value was built with. Handled inline below, immediately before the fact it would otherwise
      * corrupt.
      */
    def nodeFacts(t: Term): Option[NodeRef] =
      val real = unwrap(t)
      real.tpe.widen.dealias.asType match
        case '[Node[i, o]] =>
          // A WIDENED reference (`val OpenPr: Node[?, ?] = Node[PrInput, Unit](...)`) still matches
          // this very pattern, but `i`/`o` bind to the wildcard's OWN synthetic bounds, never to the
          // concrete types the value was actually built with (issue #39 review, MAJOR 4, confirmed by
          // inspecting what a widened reference's captured type parameters actually are: an abstract
          // type symbol of the wildcard itself, `isTypeParam` true, not the real `PrInput`/`Unit`).
          // Reading `trust`/`guard` off that abstract capture would silently produce `Trust.Plain` and
          // `Guard.Open` regardless of what the node was really declared with, exactly the "confident
          // but wrong" reading `parseShape`'s own doc says is worse than not checking at all; falling
          // back here, before either fact is computed, is what turns that into an honest decline.
          if TypeRepr.of[i].typeSymbol.isTypeParam || TypeRepr.of[o].typeSymbol.isTypeParam then None
          else
            def innermostSymbol(term: Term): Symbol = term match
              case Apply(fn, _)     => innermostSymbol(fn)
              case TypeApply(fn, _) => innermostSymbol(fn)
              case Inlined(_, _, b) => innermostSymbol(b)
              case Block(Nil, b)    => innermostSymbol(b)
              case Typed(b, _)      => innermostSymbol(b)
              case other            => other.symbol
            val trustOpt = Expr.summon[TrustOf[o]] match
              case Some(givenExpr) =>
                innermostSymbol(givenExpr.asTerm) match
                  case sym if sym == judgedSymbol => Some(Trust.Reviewed)
                  case sym if sym == plainSymbol  => Some(Trust.Plain)
                  case _                          => None
              case None => None
            val guard =
              if TypeRepr.of[i] <:< TypeRepr.of[RequiresReviewInput] then Guard.RequiresReview
              else Guard.Open
            for
              trust        <- trustOpt
              (key, display) <- identifyRef(real)
            yield NodeRef(key, display, trust, guard, real.pos)
        case _ => None

    /** Parses one `Transition(from, to)` element into its two node references, or `None` if either
      * side is not itself a recognisable node reference (`nodeFacts` above).
      *
      * Also resolves a `val`-BOUND `Transition`, `val t1 = Transition(A, B)` used later as a list
      * element `List(t1)`, by reading straight through the binding to its own right-hand side (issue
      * #43 review round 2, BLOCKER B1 part 2), the way `Runner.validate` never has to, since it reads a
      * genuine `Transition` VALUE rather than a piece of source. Unlike `nodeFacts`, which reads
      * `trust`/`guard` off a reference's STATIC TYPE and therefore never needs to look inside a `val`
      * binding at all, `Transition`'s own case class carries no type parameters encoding `from`/`to`
      * (`Transition(from: Node[?, ?], to: Node[?, ?])`, `Kit.scala`), so there is no fact on `t1`'s own
      * type this walk could read either side off; the only place `from`/`to` exist at all, before a real
      * `Transition` value is ever constructed, is inside `t1`'s own initialiser. `stablePathKey(t)` is
      * reused, rather than a separate stability test written here, so this never trusts a `var`-bound or
      * otherwise unstable `t1`, the identical guard `isStablePathLink` gives everywhere else in this
      * file. `Symbol.tree` is wrapped in a `Try`, and its RESULT, not merely whether it throws, decides
      * whether this resolves (issue #43 review round 2): confirmed by writing the exact case this macro
      * always actually runs under, a `checkedShapeStrict` splice reached across the file boundary
      * between the consumer's own source and this one (`checkShapeImpl`'s own doc has the reason
      * scala-cli's suspend/resume compilation makes that boundary real, not incidental), a `t1` bound by
      * a plain local `val` inside the SAME method or block that also builds the literal `Shape` resolves
      * cleanly, `vd.rhs` fully populated with `t1`'s own `Transition(A, B)` call; a `t1` bound as a
      * MEMBER of an enclosing `class`/`object`, initialised in that type's own primary constructor
      * rather than carried on the member `ValDef` itself, does not, `vd.rhs` came back `None` on both
      * the member's own symbol tree and a hand-written scan of the owning class's body for exactly this
      * reason, confirmed by writing that scan and inspecting what it actually finds. This walk therefore
      * reads a LOCAL `val`-bound `Transition` (declared in the same block as the `LitterBox.graph` call
      * that uses it) and quietly declines a class-or-object-MEMBER-bound one, the identical fallback
      * every other unreadable piece in this file already gets, `UnreadablePiece`'s own message naming
      * what to write instead, rather than a macro crash or a wrong answer.
      */
    def parseTransition(t: Term): Option[(NodeRef, NodeRef)] =
      def viaCompanion(term: Term): Option[List[Term]] = companionApplyArgs(term, transitionModuleClass)
      val resolvedArgs: Option[List[Term]] =
        viaCompanion(t).orElse {
          val u = unwrap(t)
          if stablePathKey(u).isDefined then
            scala.util.Try(u.symbol.tree).toOption
              .collect { case vd: ValDef => vd.rhs }
              .flatten
              .flatMap(viaCompanion)
          else None
        }
      resolvedArgs match
        case Some(List(fromTerm, toTerm)) =>
          for
            from <- nodeFacts(fromTerm)
            to   <- nodeFacts(toTerm)
          yield (from, to)
        case _ => None

    /** The three ways `parseShape` below can fail to read a full literal graph, kept as DISTINCT cases
      * rather than a single `None` (issue #43 review round 2, BLOCKER B1 part 1, replacing the version
      * of `parseShape` an earlier round shipped that collapsed the first two into one `Option`; issue
      * #43 review round 3, BLOCKER 1 part 2, adding the third). The strict caller (`checkShapeImpl`'s
      * own tail match) needs to tell them apart to raise three DIFFERENTLY worded errors: `NotALiteral`
      * is the case the original BLOCKER 1 message was written for, `shapeExpr` itself is not
      * recognisably a `Shape(...)` call at all (a `val`, a function result, `Machine.shippedShape`,
      * ...); `UnreadablePiece` is a narrower failure, `shapeExpr` genuinely IS a `Shape(...)` literal,
      * written directly at the call site, but one TERM somewhere inside it (a node reference, a
      * `Transition`, a list) is not one of the forms this walk can read; `KeyConflict` is narrower
      * still, every piece of `shapeExpr` WAS readable, but two of the `NodeRef`s this walk built from
      * it canonicalise to the identical `key` (`stablePathKey`'s own doc above has the two ways that can
      * happen, a `class`/`object` companion pair or two inline constructions sharing one literal `name`)
      * while disagreeing on `(trust, guard)`, so there is no honest way to decide which of the two facts
      * `violations` below should trust (`checkReconciled`'s own doc has the full reasoning). Conflating
      * the first two under `checkedShapeStrict` is what BLOCKER B1 (round 2) found: a consumer who wrote
      * a fully literal `Shape` reaching only ordinary idioms this walk simply had not been taught yet (a
      * class member `val` referenced bare, `List.empty`, out-of-order named arguments) received the
      * SAME "write a literal here" message a consumer who wrote no literal at all would get, advice the
      * first consumer could not act on because they had already followed it; letting the visited set of
      * a BFS silently pick a winner between two conflicting `NodeRef`s sharing one key is the identical
      * class of mistake one level down, so `KeyConflict` gets its own wording for the same reason. The
      * lenient caller (`checkedShape`) does not need any of these three distinguished, and does not get
      * them: every case falls back to `shapeExpr` unchanged there, exactly as a single `None` always
      * did, since `checkedShape`'s own doc (`Kit.scala`) already commits to silently degrading on ANY
      * unreadable shape for its own callers, regardless of why, key conflicts included.
      */
    enum ParseFailure:
      case NotALiteral
      case UnreadablePiece(term: Term)
      case KeyConflict(key: String, first: NodeRef, second: NodeRef)

    /** Checks every `NodeRef` `parseShape` below has collected so far, `entry` and both sides of every
      * `Transition`, for two references sharing one `key` (`stablePathKey`/`identifyRef` above) while
      * disagreeing on `(trust, guard)` (issue #43 review round 3, BLOCKER 1 part 2). Canonicalising the
      * key (`canonical`, `stablePathKey`'s own doc) closes the SPLIT failures, (a) through (c): the same
      * node, spelled two ways, now keys identically, so `violations`' own BFS links the edge it used to
      * miss. Canonicalisation does NOT, on its own, close the MERGE failures, (e), a `class`/`object`
      * companion pair sharing one simple name once `$` is stripped, and (f), two inline `Node.apply`
      * calls sharing one literal `name`: two genuinely DIFFERENT nodes can still canonicalise to the
      * identical key, because a key is, deliberately, a STRING derived from a symbol or a literal, never
      * a proof of runtime identity this macro has no constructed value to check against. Left unchecked,
      * `violations`' own BFS would run over whichever `NodeRef` the visited set's underlying `Map`
      * happens to keep for a shared key, silently picking a winner between two conflicting facts and
      * dropping the other, exactly the "confident but wrong" reading this whole file exists to avoid
      * (`parseShape`'s own doc has the identical principle for a single unreadable term; this is its
      * restatement for two READABLE terms that turn out to disagree). So this runs BEFORE `violations`,
      * over the raw `NodeRef` list, and hard-declines the whole shape the instant it finds one conflict,
      * rather than letting the BFS run at all and produce an answer that could, by construction, disagree
      * with what a `Runner.validate` running against the REAL, single, already-merged-or-not-by-the-JVM
      * `Node` values would find. `first`/`second` are kept, in the order `groupBy` happened to collect
      * them, purely so the caller can name and point at BOTH disagreeing references, not only whichever
      * one this fold visited last; which of the two prints first is not a fact this function promises,
      * only that both print.
      *
      * A shared key with IDENTICAL `(trust, guard)` on every reference sharing it, by contrast, is not
      * flagged here at all: whether that is a genuine companion collision that happens not to matter
      * (both sides equally open or equally guarded) or two independent nodes that coincidentally agree
      * on both facts, `violations` reads nothing else off a `NodeRef`, so nothing this function could
      * report would ever change what it finds either way, and reporting it anyway would be noise a
      * consumer could not act on.
      *
      * A SECOND alias family exists beside the one this function closes, named here rather than left for
      * a reader to rediscover, because canonicalisation plus this reconciliation pass together close every
      * SPLIT and every disagreeing MERGE, but neither one, nor any decline rule expressible over a single
      * `Shape` literal, closes this one (issue #43 review round 4): one runtime `Node` VALUE bound to TWO
      * different top-level `val`s, `val n19Start = zzmk("Start")` and, separately, `val n19Same =
      * n19Start`, the second `val`'s own initialiser reading straight from the first rather than
      * constructing anything new. `canonical` keys each `val` on its OWN symbol, `n19Start` and `n19Same`
      * are two different symbols, so this walk produces two DIFFERENT keys for what is, at runtime, one
      * single `Node`, and neither key ever collides with the other the way (a) through (c)'s misspelled
      * SAME symbol did, so canonicalisation has nothing to canonicalise here, and neither key is ever
      * shared by two disagreeing `NodeRef`s, so `checkReconciled` above has nothing to catch either: an
      * edge whose `from` was written through `n19Same` never links to an `entry` element written through
      * `n19Start`, silently, the identical orphaned-edge mechanism (a) through (c) had, just no longer
      * reachable by fixing the KEY function, since the two keys here are each, individually, perfectly
      * legitimate. Recovering the fact that `n19Same`'s own initialiser is `n19Start` would need this walk
      * to read a MEMBER `val`'s own right-hand side back out through `Symbol.tree`, the exact operation
      * `parseTransition`'s own doc above already records returns `vd.rhs == None` for a member, not a
      * local; a version of this file that read every member `val`'s initialiser to chase this kind of
      * alias would also have to re-run the identical stability and cycle reasoning `parseTransition`
      * already pays for a bound `Transition`, for every ordinary node reference in a shape, a scope well
      * past what this macro's per-call-site tree walk is built to do, and would still fail the moment the
      * alias crossed a FILE boundary this same compilation unit does not see, `n19Same` defined in a
      * different module than `n19Start`. So this is left standing, uniformly, alongside the def-built-node
      * residue `checkShapeImpl`'s own doc above already names: this macro cannot tell one `Node` bound
      * under two stable paths apart from two genuinely different `Node`s that happen to canonicalise
      * differently, which is exactly what it is, two different symbols, so there is nothing dishonest
      * about the silence, only a real limit of a walk that reasons over SYMBOLS, never over the VALUES
      * those symbols happen to alias. What makes this residue SURVIVABLE rather than a silent hole,
      * unlike before issue #43 review round 4's Tier 2 (`Node.apply`'s own doc, `Kit.scala`, `GuardOf`'s
      * own doc alongside `TrustOf`, `Kit.scala`): `Runner.validate` now derives the identical `Guard.
      * RequiresReview` this walk derives, off the SAME `RequiresReviewInput` marker, on the constructed
      * `Node` value itself, not merely off a hand-written field a node author could forget to set to
      * match; so a graph this walk cannot fully verify, because one of its nodes is reachable through an
      * alias this walk cannot chase, is still checked by `Runner.validate` at startup, against the real,
      * single, already-resolved `Node` values, where `n19Start` and `n19Same` are not two facts to
      * reconcile at all, only one value read twice.
      */
    def checkReconciled(refs: List[NodeRef]): Either[ParseFailure, Unit] =
      refs
        .groupBy(_.key)
        .values
        .collectFirst {
          case group if group.map(r => (r.trust, r.guard)).distinct.sizeIs > 1 =>
            val first  = group.head
            val second = group.find(r => (r.trust, r.guard) != (first.trust, first.guard)).get
            ParseFailure.KeyConflict(first.key, first, second)
        }
        .toLeft(())

    /** The full parse: `shapeExpr` has to be a literal `Shape(entry = List(...), transitions =
      * List(...))` call, every entry element a recognisable node reference, and every transitions
      * element a recognisable `Transition(...)` of two recognisable node references, for this to
      * return anything at all. One unrecognised piece anywhere is enough to fall back for the WHOLE
      * shape, deliberately: a partial reading could clear a graph whose unreadable half is exactly
      * where the real violation lives, which would be worse than not checking at all.
      *
      * Returns `Either[ParseFailure, ...]`, not `Option`, precisely so the FIRST unreadable term, or
      * the fact that `shapeExpr` was never a `Shape(...)` literal in the first place, survives past this
      * function (issue #43 review round 2, BLOCKER B1 part 1): a single walk here, rather than a
      * successful `Option`-returning walk re-run a second time purely to find out where it would have
      * failed, is what keeps the strict caller's diagnostic from ever being able to disagree with what
      * this parse actually did, since there is only one walk, not two that could drift apart.
      */
    def parseShape: Either[ParseFailure, (List[NodeRef], List[(NodeRef, NodeRef)])] =
      for
        args                          <- companionApplyArgs(shapeExpr.asTerm, shapeModuleClass)
                                            .toRight(ParseFailure.NotALiteral)
        // `companionApplyArgs` matches ANY call whose callee is owned by `Shape`'s own module class,
        // not specifically `Shape.apply`: Scala synthesises `Shape.unapply` on that exact same module
        // class for a case class shaped like this one, a plain, non `Option` wrapped extractor taking
        // ONE argument, the scrutinee, not the two `entry`/`transitions` this walk needs (issue #39
        // review, BLOCKER 1). Matching `args` here as a generator, not a plain `val` pattern, is what
        // turns that arity mismatch into a graceful failure, the same fallback every other unreadable
        // piece in this function already gets, rather than a `MatchError` thrown straight out of the
        // macro and into the compiler's own error stream. Blamed on `shapeExpr` itself, not a narrower
        // term, since there is no `entry`/`transitions` term to point at yet at this point in the walk.
        (entryTerm, transitionsTerm) <- (args match
                                           case List(e, t) => Some((e, t))
                                           case _          => None
                                         ).toRight(ParseFailure.UnreadablePiece(shapeExpr.asTerm))
        entryElems        <- literalListElements(entryTerm)
                               .toRight(ParseFailure.UnreadablePiece(entryTerm))
        entryNodes        <- entryElems.foldRight(
                               Right(List.empty[NodeRef]): Either[ParseFailure, List[NodeRef]]
                             ) { (e, acc) =>
                               for
                                 tail <- acc
                                 n    <- nodeFacts(e).toRight(ParseFailure.UnreadablePiece(e))
                               yield n :: tail
                             }
        transitionElems   <- literalListElements(transitionsTerm)
                               .toRight(ParseFailure.UnreadablePiece(transitionsTerm))
        transitions       <- transitionElems.foldRight(
                               Right(List.empty[(NodeRef, NodeRef)]): Either[ParseFailure, List[(NodeRef, NodeRef)]]
                             ) { (e, acc) =>
                               for
                                 tail <- acc
                                 edge <- parseTransition(e).toRight(ParseFailure.UnreadablePiece(e))
                               yield edge :: tail
                             }
        // Every `NodeRef` this parse has collected, not only `entry`'s: a key conflict can sit entirely
        // inside `transitions` (issue #43 review round 3, reproduction (f), both conflicting refs are
        // `Transition` endpoints, neither is an `entry` element) so restricting this to `entry` would
        // miss exactly the case it exists to catch. Run AFTER both folds above succeed, never
        // interleaved with them: `checkReconciled` needs the FULL set of references to tell a genuine
        // conflict apart from a key that simply has not been seen a second time yet.
        _                 <- checkReconciled(entryNodes ++ transitions.flatMap((from, to) => List(from, to)))
      yield (entryNodes, transitions)

    /** The reachability walk itself, identical in shape to the BFS half of `Runner.validate`
      * (`Kit.scala`'s own doc on that method has the full reasoning for BFS, for the `(name, reviewed)`
      * visited key, and for why the guard test reads the flag carried IN rather than one folded with
      * the current node's own trust); this is that same algorithm, run here over `NodeRef`s instead of
      * `Node`s because no `Node` value exists yet.
      *
      * Only the BFS half, stated plainly rather than left to be inferred (issue #39 review, MAJOR 6):
      * `Runner.validate` also runs three DECLARATION level checks this function does not reproduce,
      * two nodes sharing one `name` that disagree on a fact this walk reads, a node `transitions`
      * names that no `entry` ever reaches, and `transitions` declared with an empty `entry` (that
      * method's own doc has the reasoning for each). None of the three has an AST level analogue worth
      * building here: this macro sees ONE literal `Shape` at ONE call site, never the merged
      * `entry ++ transitions` view across an entire running graph `Runner.validate` reads, so a stale
      * or duplicated name is exactly the kind of fact `Runner.validate` remains the sole check for.
      */
    def violations(entry: List[NodeRef], transitions: List[(NodeRef, NodeRef)]): List[String] =
      val byFrom: Map[String, List[NodeRef]] =
        transitions.groupBy(_._1.key).view.mapValues(_.map(_._2)).toMap

      final case class Item(node: NodeRef, path: List[String], reviewed: Boolean)

      val visited = scala.collection.mutable.Set.empty[(String, Boolean)]
      val found   = scala.collection.mutable.ListBuffer.empty[String]
      val queue   = scala.collection.mutable.Queue.empty[Item]
      entry.foreach(e => queue.enqueue(Item(e, Nil, false)))

      while queue.nonEmpty do
        val Item(node, path, reviewed) = queue.dequeue()
        val key                        = (node.key, reviewed)
        if !visited.contains(key) then
          visited += key
          val pathHere = path :+ node.display
          if node.guard == Guard.RequiresReview && !reviewed then
            // The advice below names the INPUT type's own marker, not `Guard.RequiresReview` (issue
            // #39 review, MINOR 1): this macro never reads that constructor argument at all, only
            // `RequiresReviewInput` on the reference's own static input type (`nodeFacts` above), so a
            // node whose `guard` field genuinely is `Guard.RequiresReview`, and a node whose `guard`
            // field was left `Guard.Open` entirely, read identically here as long as both extend the
            // marker; telling either one to "remove Guard.RequiresReview" is advice the second cannot
            // even follow, since it never wrote that in the first place.
            found +=
              s"path ${pathHere.mkString(" -> ")} reaches '${node.display}', a node whose own input " +
                s"type extends RequiresReviewInput, with no reviewed node anywhere before it on that " +
                s"path. Put a node whose output type is AgentDispatch.Judged[_] somewhere before " +
                s"'${node.display}' on this path, or stop extending RequiresReviewInput on " +
                s"'${node.display}''s own input type if it genuinely never needs a review."
          val reviewedHere = reviewed || node.trust == Trust.Reviewed
          byFrom.getOrElse(node.key, Nil).foreach(child => queue.enqueue(Item(child, pathHere, reviewedHere)))

      found.toList

    parseShape match
      case Left(ParseFailure.NotALiteral) =>
        // The lenient path (`strict = false`, `checkedShape`'s own caller): a `shapeExpr` this walk
        // cannot read as a literal is not this function's business to reject, `Runner.validate`
        // remains the backstop (`checkedShape`'s own doc, `Kit.scala`, has the reasoning). The strict
        // path (`strict = true`, `checkedShapeStrict`'s own only caller, `LitterBox.graph`): the
        // identical situation is a hard error instead (issue #43 review, BLOCKER 1). The reason given
        // below is the CURRENT one, not the original one (issue #43 review round 4, Tier 2, correcting
        // both this comment and the error text itself, which is user-facing and was making the same
        // now-outdated claim): at the time this error was first written, a node whose input type
        // extended `RequiresReviewInput` while its own `guard` argument was left at the default
        // `Guard.Open` (exactly the shape every shipped `OpenPr`/`Merge`-style node takes) was invisible
        // to `Runner.validate`, which read `guard`, never the marker. `Node.apply` (`Kit.scala`, `GuardOf`'s
        // own doc) now derives `Guard.RequiresReview` on the real `Node` whenever the marker is present,
        // so `Runner.validate` catches that exact case too. Erroring here remains correct regardless
        // (`checkedShapeStrict`'s own doc, `Kit.scala`, has the two reasons that survive Tier 2: compile
        // time beats startup on timing alone, and two alias residuals exist, named in `stablePathKey`'s
        // and `checkReconciled`'s own docs above, that no widening of THIS walk closes, for which
        // `Runner.validate` remains the only check that ever runs at all). Naming what to do instead,
        // rather than merely refusing, is what `report.errorAndAbort` buys over a bare fallback here.
        // This is `ParseFailure.NotALiteral` specifically, never `UnreadablePiece` (issue #43 review
        // round 2, BLOCKER B1 part 1): `shapeExpr` itself was not recognisable as a `Shape(...)` call
        // at all, so "write the Shape literal inline" is advice this consumer genuinely has not yet
        // followed, unlike the `UnreadablePiece` case below.
        if strict then
          report.errorAndAbort(
            "litter-box: LitterBox.graph requires a literal Shape(entry = List(...), transitions = " +
              "List(...)) expression written directly at this call site (issue #43 review, BLOCKER " +
              "1), never a val, a function result, or any other indirection: catching a review-" +
              "reachability violation at compile time is strictly earlier than catching the same one " +
              "at Runner.validate's own startup check, on every graph this macro can read, and two " +
              "alias residuals exist that no widening of this check closes (an instance-qualified " +
              "receiver, and one Node value bound under two stable paths), for which Runner.validate " +
              "is the only check that ever runs against your graph's real values at all, so silently " +
              "skipping this check here would silently drop the earliest, and for those two residuals " +
              "the only, chance to catch a mistake in this graph. Write the Shape literal inline in " +
              "this LitterBox.graph call. If a literal genuinely cannot be written here, compose " +
              "Runner.run directly instead of LitterBox.graph, and call Runner.validate on your own " +
              "Shape at startup yourself."
          )
        else shapeExpr
      case Left(ParseFailure.UnreadablePiece(bad)) =>
        // The SECOND, narrower strict failure (issue #43 review round 2, BLOCKER B1 part 1),
        // deliberately given DIFFERENT wording from `NotALiteral` above so the two can never be
        // confused for one another by a reader or by a test asserting on the message: `shapeExpr` WAS
        // recognised as a literal `Shape(...)` call written directly at this call site, but this one
        // TERM inside it, `bad`, is not a form this walk can read (an entry/transitions list built some
        // way other than `List(...)`/`List.empty`/`Nil`, a node or `Transition` reference that is
        // neither a stable path nor an inline literal construction, ...). Falling back leniently here,
        // the way the lenient `checkedShape` path still does, is explicitly ruled out (issue #43 review
        // round 2, decision on BLOCKER B1): that is precisely the hole round 1's own BLOCKER 1 closed,
        // reopened one unread term at a time for every marker-only-guarded node this walk happens not
        // to recognise, so this raises just as loud as `NotALiteral` does, only with different words and
        // anchored at `bad`'s own source position (`report.errorAndAbort`'s two-argument overload),
        // rather than at the whole `shapeExpr` `NotALiteral` points at, so the compiler underlines the
        // exact unread term rather than the whole call. The forms actually named below are precisely
        // the ones `stablePathKey`, `literalListElements` and `identifyRef` accept as of this fix, kept
        // in sync with them by hand since a macro error string cannot introspect its own sibling
        // functions; a form accepted there and not named here is a documentation bug, not a runtime one,
        // since the acceptance itself is what actually governs whether this branch is ever reached. A
        // node built by a `def`, including `Machine.shippedShape`'s own config-parameterised `A(cfg)`
        // idiom (`LitterBox.graph`'s own scaladoc has the fuller reasoning for why that idiom is not,
        // and cannot be made, expressible through this factory), is the one residue this fix leaves on
        // purpose: this macro reasons about TREES, never about a running value (`checkShapeImpl`'s own
        // doc above), so a node a `def` call would build is not a fact this walk could ever read no
        // matter how far `stablePathKey`/`literalListElements` are widened. `export`ed member forwarders
        // are named explicitly, separately from the def-built-node residue above, rather than left for a
        // consumer to lump in with it (issue #43 review round 3, MINOR 3): `object B: export A.*` makes
        // `B.Foo` look, at the call site, exactly like the "an object member" form this message already
        // lists as readable, but Scala compiles an `export` into a synthetic FORWARDER `def`, never a
        // `val`, so `isStablePathLink` rightly declines it and this message would otherwise point the
        // consumer straight back at the form they already, apparently, used. An instance-qualified
        // receiver (`a.node` for an ordinary, non-singleton `a`) is named explicitly too, rather than left
        // for a consumer to lump in with the def-built-node residue above (issue #43 review round 4,
        // Tier 1): `stablePathKey`'s own doc has the reasoning for why this walk stopped keying every
        // instance-qualified receiver it can prove is STABLE and started declining all of them uniformly,
        // regardless of whether two of them provably alias the same value or provably do not, since this
        // macro cannot observe which is true and keying either answer risks disagreeing with the runtime.
        if strict then
          report.errorAndAbort(
            "litter-box: LitterBox.graph could read this Shape(...) literal at the call site, but " +
              s"could not identify or fully parse one piece of it, `${bad.show}`, here (issue #43 " +
              "review round 2, BLOCKER B1). Falling back silently for this piece alone, the way the " +
              "opt-in checkedShape still does for its own callers, would reopen round 1's own BLOCKER " +
              "1 for every node this walk happens not to recognise, so this aborts too, with different " +
              "wording from the \"not a Shape literal at all\" error so the two can never be mistaken " +
              "for each other. Forms this macro CAN read: a node or Transition referred to through a " +
              "stable path (a top level val, an object member, or an unqualified val member of the " +
              "enclosing class); an inline Node(name = \"...\", ...) call carrying a literal name; an " +
              "inline Transition(from, to) call; and a list written as List(...), List.empty, " +
              "List.empty[...], or Nil. A node produced by a def, including a config-parameterised " +
              "idiom such as A(cfg), cannot be identified this way, because this macro never evaluates " +
              "it: give that node's config to its own run/probe body instead, behind a plain top level " +
              "val standing for the node itself. An exported member (`export Source.*`) is a def " +
              "forwarder synthesised by the compiler, never a val, even though it reads like an object " +
              "member at the call site: refer to the original val on Source directly instead. A node " +
              "reached through an instance-qualified receiver (`holder.node` for an ordinary, " +
              "non-singleton `holder`, however many stable aliases stand between it and this call) is " +
              "never readable this way either, deliberately, because this macro cannot tell two distinct " +
              "instances of the same class apart from one instance aliased twice: write the node as a " +
              "top level val, an object member, or an inline Node(name = \"...\", ...) call instead.",
            bad.pos
          )
        else shapeExpr
      case Left(ParseFailure.KeyConflict(key, first, second)) =>
        // The THIRD strict failure (issue #43 review round 3, BLOCKER 1 part 2), given its own wording
        // for the identical reason `NotALiteral` and `UnreadablePiece` above already have separate ones:
        // every piece of `shapeExpr` WAS readable, `bad`-style advice about an unreadable term names
        // nothing this consumer could act on, since nothing here was unreadable. What went wrong is
        // narrower and stranger: two DIFFERENT source references canonicalise to the SAME `key`
        // (`stablePathKey`'s own doc above has the two ways that happens, a `class`/`object` companion
        // pair or two inline constructions sharing one literal `name`) while disagreeing on whether the
        // node they name requires review or carries genuine review trust. `checkReconciled`'s own doc
        // has the reasoning for why this is caught here, hard, rather than left to `violations`' own BFS
        // to silently pick a winner between the two. Anchored at `second`'s own position, with `first`'s
        // own line named in the message text, so a human reading the compiler's underline still learns
        // where BOTH disagreeing references sit, not only the one this walk happened to visit last.
        if strict then
          report.errorAndAbort(
            "litter-box: LitterBox.graph found two references that canonicalise to the same node " +
              s"identity (\"$key\") but disagree on whether that node requires review or carries " +
              s"review trust: '${first.display}' at line ${first.pos.startLine + 1} and " +
              s"'${second.display}' here (issue #43 review round 3, BLOCKER 1 part 2). This is most " +
              "often a class and its own companion object each declaring a member of the identical " +
              "name, or two inline Node(name = \"...\", ...) calls sharing one literal name, where one " +
              "side's input type extends RequiresReviewInput and the other's does not: picking a winner " +
              "silently here could either miss a real review-reachability violation or invent one that " +
              "does not exist, so this refuses instead. Give the two references distinct names, or " +
              "confirm they really are the same node and make their input types agree.",
            second.pos
          )
        else shapeExpr
      case Right((entry, transitions)) =>
        val vs = violations(entry, transitions)
        if vs.isEmpty then shapeExpr
        else
          report.errorAndAbort(
            "litter-box: this Workflow graph is rejected at compile time (issue #39, RFC #26 " +
              "decision 16), before Runner.validate would even see it at startup: " +
              vs.mkString("; ")
          )
