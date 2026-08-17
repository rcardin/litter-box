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
    * by `strict` (issue #43 review, added alongside `checkedShapeStrict`, `Kit.scala`): called
    * leniently, from `checkedShape`, this falls back to returning `shapeExpr` completely unchanged,
    * neither case an error, `checkedShape`'s own doc states why silently degrading, rather than
    * aborting, is the only choice that keeps THAT function safe to sprinkle onto a `Shape` a future
    * author is not yet ready to write as a full literal, `Runner.validate` remains the backstop for
    * exactly that graph. Called strictly, from `checkedShapeStrict`, `LitterBox.graph`'s own only
    * caller, the identical situation instead aborts compilation: `checkedShapeStrict`'s own doc has the
    * reason a silent fallback is not safe for that one entry point specifically. The two "independent
    * things" named above are NOT collapsed into one strict error: they are distinguished by
    * `ParseFailure` (below `parseShape`'s own definition), whose own doc has the reasoning for raising
    * two differently worded messages instead of one. The reachable table of what this walk can and
    * cannot read is spelled out at `stablePathKey`, `literalListElements` and `companionApplyArgs`
    * below, each at the point that recognises the relevant form.
    *
    * When the literal IS fully readable, this runs the SAME BFS `Runner.validate` runs (from `entry`,
    * over `transitions`, a state keyed on `(node, reviewed so far)` so no diamond or cycle is ever
    * expanded twice, `Runner.validate`'s own doc, `Kit.scala`, has the shared reasoning for why BFS
    * and why that particular key). Identity is NOT read off the `Node.name` string the way
    * `Runner.validate` reads it: it is read off a STABLE PATH, a package, a module, or an immutable
    * `val`, chained through every prefix, or off a literal `name` argument written directly at an
    * inline `Node.apply` call (`identifyRef`'s own doc below has the full reasoning), through ONE
    * canonical function, `stablePathKey`'s own `canonical` (that function's own doc has the mechanism
    * and the reason a single scheme replaced three incompatible ones this walk used to compute by
    * hand). `trust`, unlike identity, is read the same way `Node.apply` itself derives it, by summoning
    * `TrustOf[O]` and reading which given answered (`nodeFacts`'s own doc below has the mechanism),
    * declining the whole shape rather than guessing whenever neither `TrustOf.judged` nor
    * `LowPriorityTrustOf.plain` answers. `guard` is read the same OR `Node.apply` itself computes, off
    * the reference's own input type extending `RequiresReviewInput` and off an explicit
    * `guard = Guard.RequiresReview` argument (`nodeFacts`'s and `explicitRequiresReviewGuard`'s own
    * docs below have the mechanism and the reason both halves are needed), with one asymmetry worth
    * knowing before trusting what this walk reports: the marker half reads a STATIC TYPE, so it fires
    * for every reference form this walk can key at all, while the argument half reads SOURCE TEXT that
    * only an inline `Node.apply` written at the shape's own call site still carries, so an explicit
    * `guard` on a node built elsewhere and referenced by a stable path is invisible here and left to
    * `Runner.validate` alone (`explicitRequiresReviewGuard`'s own doc below has that scope in full).
    *
    * One respect this walk is NOT `Runner.validate`'s, by design rather than by oversight: three
    * DECLARATION level checks `Runner.validate` also runs have no AST level analogue here at all
    * (`violations`'s own doc, below, names each one and why). What keeps a key COLLISION honest, as
    * opposed to an alias this walk cannot even see, is `checkReconciled` (below `parseShape`'s own
    * definition), not `Runner.validate`: two `NodeRef`s sharing one canonical key while disagreeing on
    * `(trust, guard)` is a hard compile error naming both, raised BEFORE the BFS below ever runs
    * (`checkReconciled`'s own doc has the full reasoning, including the residual alias this check
    * cannot catch).
    */
  /** The implementation behind `markerRequiresReview` (`Kit.scala`), which `Node.apply`'s own `inline`
    * body reads to decide the marker half of a node's `guard`. One line, and every line of that
    * function's own doc is about why it has to be this line and not a given: `TypeRepr.of[I] <:<
    * TypeRepr.of[RequiresReviewInput]` is the compiler's own subtype relation over the type argument
    * the caller already wrote down, so there is no implicit to shadow, no priority to accident into
    * and no parameter for a call site to answer with (issue #26 PR review closed a suppression that
    * the previous `using GuardOf[I]` shape allowed).
    *
    * Deliberately the SAME expression `nodeFacts` below runs on a node reference's own captured `i`,
    * and deliberately in the same file: `Node.apply`'s runtime `guard` field and this macro's compile
    * time structural read are one rule with one implementation, so no future edit can move one without
    * the other being right there to move with it.
    */
  def markerRequiresReviewImpl[I: Type](using Quotes): Expr[Boolean] =
    import quotes.reflect.*
    Expr(TypeRepr.of[I] <:< TypeRepr.of[RequiresReviewInput])

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
      * a zero element list): `LitterBox.graph(..., shape = Shape(entry = List(only), transitions =
      * Nil), ...)`, a fully literal declaration of a single node graph with no transitions, an entirely
      * ordinary shape for a consumer to write, would otherwise be refused purely because `Nil` was not
      * recognised. `Nil` is exactly as literal as `List()`, always the same value, so recognising it
      * costs none of the caution the rest of this function reserves for varargs calls that only LOOK
      * like `List(...)`.
      *
      * This function is shared by BOTH `checkedShape` and `checkedShapeStrict` (`strict`'s own only two
      * values thread through to the same `literalListElements`, `Kit.scala` has both entries), so
      * recognising `Nil` (and, below, `List.empty`) is NOT a strict-only change: a `Shape` whose only
      * unreadable piece is one of those now gets FULLY PARSED under lenient `checkedShape` too, not only
      * silently returned unchanged, which means it can newly fail to compile there if what this walk can
      * now read turns out to have a genuine review-reachability violation. The direction is safe, MORE
      * checking, never less, but "safe direction" and "unchanged" are different claims, and
      * `checkedShape` is a published, public `inline def` (shipped in `0.2.0`) with no stability promise
      * attached to it beyond the blanket `0.x` one `README.md`'s version policy section already states,
      * so a caller of `checkedShape` itself, not only of `checkedShapeStrict`, can have working code
      * newly fail to compile after upgrading past this fix. `Kit.scala`'s own doc on `checkedShape` and
      * `checkedShapeStrict`, and `LitterBox.scala`'s own doc on `LitterBox.graph`, all state this
      * plainly; `test/GraphMacroSpec.scala` pins the new behaviour so it reads as a decision made with
      * eyes open, not an accident later discovered. The same applies, for the identical reason (a shared
      * function, so no widening here is strict-only), to every other form this file widened alongside
      * `Nil`: `List.empty`/`List.empty[...]` recognition (just below), a `This`-prefixed stable path
      * (`stablePathKey`), out-of-order named arguments (`companionApplyArgs`), and a local `val`-bound
      * `Transition` (`parseTransition`).
      *
      * `None` for anything else (a variable, a `:+`/`++` chain, a DIFFERENT varargs method entirely,
      * ...): `List(...)`'s own single argument, once past the varargs apply itself, is a `Repeated` node
      * holding exactly the elements written at that call site; that `Repeated` shape alone is not enough
      * to trust, though, since any varargs method call shares it, confirmed by writing a consumer helper
      * of the identical shape and inspecting what the compiler actually calls it: `def firstOnly(ns:
      * Node[?, ?]*): List[Node[?, ?]] = ns.toList.take(1)` used as `entry = firstOnly(Safe, Danger)`
      * produced the SAME `Apply(fun, List(Repeated(List(Safe, Danger), _)))` shape a real
      * `List(Safe, Danger)` does, so a version of this function that trusted the shape alone read
      * `Danger` as a genuine `entry` element even though `firstOnly` itself drops it. The callee
      * therefore has to be confirmed as `List.apply` itself, not merely inferred from the tree shape it
      * happens to share.
      *
      * TRAP for a future editor: `qualifier.tpe.typeSymbol`, not `qualifier.symbol`, is what that
      * confirmation has to compare against `List`'s own module class, since unqualified `List` in
      * ordinary code resolves through `scala`'s own package object alias `val List = ...`, whose TERM
      * symbol is that alias `val`, never `List`'s own module symbol, while its TYPE still names the real
      * module correctly. The identical alias trap is why `Nil`, below, is also matched on its widened
      * TYPE's symbol, never on the identifier's own term symbol.
      *
      * `List.empty`/`List.empty[Transition]` also read as `Nil`: an ordinary consumer idiom for "no
      * transitions" this walk used to fall back on, the same class of gap the `Nil` case above closed
      * for the literal `Nil` spelling. `List.empty[A]`'s own definition (`def empty[A]: List[A] = Nil`)
      * takes only a TYPE argument, never a value one, so its call tree is a bare
      * `TypeApply(Select(qualifier, "empty"), _)`, with no enclosing `Apply` for a value argument list
      * at all, confirmed by printing the typed tree of exactly this call and inspecting its shape rather
      * than assumed; the `Apply` case above therefore can never match it, and this needs its own case.
      * `qualifier.tpe.typeSymbol == listModuleClass`, the identical alias-safe comparison the
      * `List(...)` case above already uses, is what keeps a differently named zero-argument-type method
      * called `empty` on some other module from being misread as this one.
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
      * review): `Shape(transitions = List(...), entry = List(...))`, named
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
      * itself and comparing WHICH given answered against this symbol, issue #39 review).
      */
    val judgedSymbol = Symbol.requiredMethod("in.rcard.litterbox.TrustOf.judged")

    /** The exact given `LowPriorityTrustOf.plain` resolves to for every `O` that is not
      * `AgentDispatch.Judged`-shaped (`nodeFacts` below has the reason a summoned given that is
      * neither this nor `judgedSymbol` above must decline rather than be read as this one by default).
      *
      * TRAP for a future editor: looked up through `TrustOf`'s own path, `in.rcard.litterbox.
      * TrustOf.plain`, not through `LowPriorityTrustOf` directly, even though `plain` is declared on
      * that parent trait: `Symbol.requiredMethod` against the trait's OWN path is unreliable this
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
      * of a key collision it finds, not only the one `parseShape`'s own fold happened to visit last.
      */
    final case class NodeRef(key: String, display: String, trust: Trust, guard: Guard, pos: Position)

    /** Peels a curried call (`mk("x")("y")`; `Node.apply`'s own call shape too, `(mainArgs)(using
      * t: TrustOf[O])` is one more curried layer past the explicit arguments) down to the innermost
      * function term and the FULL list of argument lists, outermost call last. A one layer walk,
      * `case Apply(fun, _) => ...`, an earlier version of this function did, sees only the OUTERMOST
      * layer, so a curried call with genuine arguments in an inner layer, and `Node.apply`'s own
      * always present trailing `using` layer, both read as if they carried none (issue #39 review,
      * `mk("x")("y")` used to key and display as bare `mk`).
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
      * excluded on purpose (issue #39 review): its own body can build a fresh value on
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
      * file is built from, replacing three keying schemes that each used to encode the identical symbol
      * chain as a different string (issue #43 review). `Symbol.fullName` puts a trailing `$` on every
      * MODULE CLASS segment of the chain, a fact about how the module VALUE's own class is named on the
      * JVM, never a fact about the source a consumer actually wrote: `ZZH.Start` for a member of
      * `object ZZH` reports as `...ZZH$.Start`, a top-level `val` in a package reports with the package
      * object's own synthetic class carrying `$package$`, and neither `$` survives into the
      * concatenation the general `Select` case below used to build by hand, `stablePathKey(qualifier) +
      * "." + name`, which never went through `fullName` at all.
      *
      * TRAP this fix closes: the SAME `Node` value, `ZZH.Start`, written bare through a wildcard import
      * (`Ident`, keyed on the raw `t.symbol.fullName`, `$` included) and written qualified
      * (`Select(Ident(ZZH), Start)`, keyed on the concatenation, no `$`), used to produce two DIFFERENT
      * keys. Because `violations`' own BFS below keys `entry` and `byFrom` on this string, two keys for
      * one node SPLIT it: an edge whose `from` was keyed one way never links to an `entry` element keyed
      * the other way, so a marker-only-guarded node past that broken edge is never dequeued, never
      * checked, and this macro reports nothing wrong, silently. Reproduced three ways: a wildcard
      * import, a package-qualified top-level `val`, and an `object` member spelled bare in one place and
      * qualified in another, each a real compiled file reaching an unreviewed `RequiresReviewInput` node
      * with `LitterBox.graph` reporting nothing and `Runner.validate` on the identical shape also
      * returning `List()`. Stripping the trailing `$` off EVERY dot-separated segment, not only the last
      * one (a module nested inside another module carries `$` on more than one), is the whole fix: both
      * spellings above now canonicalise to the identical string, because both ARE, underneath,
      * `t.symbol` on the same member, and reading the key straight off that shared symbol, rather than
      * reconstructing a string from whichever AST shape the reference happened to take, is what makes
      * the reconstruction itself stop being a second, independently drifting source of truth.
      */
    def canonical(sym: Symbol): String =
      sym.fullName.split('.').map(_.stripSuffix("$")).mkString(".")

    /** The full stable path key of `t`, `None` unless EVERY prefix, all the way down to the root, is a
      * stable path link (`isStablePathLink` above). ONE canonical function, `canonical` above, computes
      * every key, replacing the three incompatible schemes `Ident`, `Select(This(_), _)` and the general
      * `Select` case each used to compute by hand (`canonical`'s own doc has the reproduced bug that
      * motivated the collapse). Two DIFFERENT REASONS a reference can be a stable path stay two
      * different key NAMESPACES below, deliberately not folded further, because only one of them is
      * sound to read straight off `t.symbol`:
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
      *     same singleton-qualified member collapse to one key.
      *
      *     `canonical(t.symbol)` alone is NOT, however, guaranteed to tell a `class` apart from its own
      *     companion `object` of the identical simple name, precisely because canonicalisation strips
      *     the one segment, the module class's trailing `$`, that used to be the only textual difference
      *     between them: `class ZZX`'s own member `A` and `object ZZX`'s own member `A` both canonicalise
      *     to `....ZZX.A`. This is NOT a regression this branch introduces silently, it is the residual
      *     `checkReconciled` below exists to catch loudly: two `NodeRef`s sharing this one key with
      *     genuinely different `trust`/`guard` (the class's `A` extending `RequiresReviewInput`, the
      *     object's not) is a hard compile error naming both, rather than a silent pick between them by
      *     whichever the visited set dequeues first.
      *
      *   - The qualifier is an ordinary INSTANCE, a stable but non-singleton value (`val a: SomeClass`,
      *     an `a.node` reference where `a` could, for all this macro can prove, be one of several
      *     distinct `SomeClass` values at once). This branch DECLINES ENTIRELY, `None`, never a key:
      *     every instance-qualified receiver, regardless of what it widens to, is `UnreadablePiece`, a
      *     hard error under `checkedShapeStrict` naming the unreadable term, a silent lenient fallback
      *     under `checkedShape`.
      *
      *     TRAP for a future editor: this looks over-cautious, and two narrower rules were tried and
      *     rejected before landing here, both found UNSOUND rather than merely incomplete (issue #39 and
      *     #43 review). Keying `canonical(t.symbol)` alone names only the DECLARATION, throwing the
      *     receiver away and merging `a.node`/`b.node` for two genuinely different `a`/`b` into one key.
      *     Keying on `s"instance:$prefix#$name"` by recursing `stablePathKey(qualifier)` fixed that but
      *     opened a narrower hole: a class member reached through a SECOND `val` the SAME class declares
      *     and initialises to `this` (`val self0: ZZC = this`, then `self0.Start` alongside a bare
      *     `Start` elsewhere) keyed DIFFERENTLY from the bare reference even though they are the
      *     identical `Node` at runtime, a silent SPLIT the macro has no dataflow analysis to rule out. A
      *     follow-up patch narrowing that one case, declining only a receiver whose WIDENED type exactly
      *     matched the class enclosing the macro's own splice site, was defeated three independent ways
      *     (aliasing to a supertype of that class, moving the call into a nested `object`, aliasing
      *     something that is not a class at all), none of them involving `this` at all; each reached the
      *     same instance-qualifier branch and was silently KEYED, splitting the aliased node from its
      *     bare spelling elsewhere in the same shape, seven real files compiling clean through a
      *     violating graph confirmed each one against the working tree. The root cause both narrow
      *     patches shared: this macro has no dataflow analysis at all, so "the receiver's widened type
      *     happens to equal some class" was always a proxy for "this receiver might alias `this`", and no
      *     FURTHER proxy is going to fare any better, since the actual fact needed, whether two distinct
      *     instance qualifiers name the same runtime value or two genuinely different ones, is exactly
      *     the fact a tree-only macro cannot observe (`checkShapeImpl`'s own doc above has the general
      *     limit). So this branch stops trying to distinguish provably-same from provably-different
      *     instance qualifiers and declines ALL of them, uniformly: the macro cannot tell `a.node`/
      *     `b.node` on two genuinely different instances (the idiom `GraphMacroSpec` pins, still
      *     compiling under `checkedShape`'s own lenient fallback, `Runner.validate` the backstop for it
      *     now) apart from `n2a.Start`/`n2b.Start` on one instance aliased twice, so keying an instance
      *     path AT ALL is guessing, and this file's own stated philosophy, restated at `checkShapeImpl`'s
      *     own doc above, is "canonicalise, or decline loudly rather than guess": there is no honest
      *     THIRD option between those two for a fact this macro cannot observe. A consumer whose shape
      *     genuinely needs an instance-qualified node reference has `checkedShape`'s own lenient fallback
      *     with `Runner.validate` as a backstop, or restructures the reference onto a top-level `val`, an
      *     `object` member, or an inline `Node(name = ..., ...)` call, every one of which this walk reads
      *     directly.
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
      * decline, which `parseShape`'s own fold turns into a fallback for the WHOLE shape. A reference is
      * trusted only when it is a STABLE PATH (`stablePathKey` above, a package, a module, or an
      * immutable `val`, chained through every prefix) or an inline `Node.apply` call carrying a literal
      * `name`; every other shape, including every method call that is not `Node.apply` itself,
      * regardless of how many arguments it carries, falls back rather than guesses.
      *
      * TRAP for a future editor: trusting a call with NO arguments in any curried layer, on the theory
      * that a zero argument call is fully determined by which method it calls, was tried and is unsound
      * (issue #39 review): that theory is false for a STATEFUL zero argument factory (`stage(): Node`
      * built from a mutable counter, confirmed to compile clean on a graph `Runner.validate` rejects),
      * and the same unconditional trust applied to a bare `Ident`/`Select` regardless of whether the
      * symbol behind it was a `val` or a `def` would let a paramless `def stage: Node` with the
      * identical stateful body through unfixed too. Falling back for every method call that is not
      * `Node.apply` costs real detections `Machine.shippedShape`'s own doc already accounts for (its
      * own node references are exactly this shape), never a false rejection: `Runner.validate` remains
      * the backstop for anything this declines.
      *
      * A stable path (`Pick`, `Machine.OpenPr`, a `val` member of a class read bare inside a method, ...)
      * is keyed on the WHOLE chain (`stablePathKey` above): the same path written twice in one shape is
      * the same value, hence the same `Node`, hence, trivially, the same `name`, without this macro ever
      * having to read that field. `a.node` for an ordinary instance `a` is NOT an example of this
      * (`stablePathKey`'s own doc has the reasoning for declining every instance-qualified receiver
      * rather than keying it): `stablePathKey(t)` returns `None` for it, so this function's own
      * `Ident(_) | Select(_, _)` case below declines it too, rather than reaching this "keyed on the
      * whole chain" case at all.
      *
      * A call whose own callee, after peeling every curried layer (`uncurry` above), resolves into
      * `Node`'s own companion IS `Node.apply` itself, written inline; here the `name` string really is
      * sitting right there in the AST, in the call's own first argument list, so this reads it
      * directly (`literalNameArg` above) and keys on THAT string, the same fact `Runner.validate`
      * would compute from the constructed value. Two inline constructions naming the same literal
      * `"Pick"` are therefore the same node here too, matching `Runner.validate`: keying an inline
      * construction by its own source position instead, an earlier version of this function did, made
      * every occurrence of one a DIFFERENT node, so `byFrom` below never linked the edges a real graph
      * naming the same node twice actually has, and the walk missed every violation past `entry`. When
      * `name` was written some other way, this returns `None` rather than inventing a key: guessing
      * which two inline constructions "probably" share a name is exactly the confident-but-wrong
      * reading this whole function exists to avoid.
      *
      * The key returned for an inline construction is `s"inline:$n"`, never the bare literal `n`:
      * `stablePathKey`'s own namespace above (a plain dotted path, built from a SYMBOL,
      * `canonical(t.symbol)`) can never, by construction, equal a bare source string typed by a
      * consumer as a `name` argument, but relying on that being true by accident, rather than by a
      * namespace prefix that makes it true by CONSTRUCTION, is exactly the kind of coincidence this
      * file's own `List`/`Nil` alias-trap paragraphs elsewhere (`literalListElements`'s own doc above)
      * already warn against trusting. `inline:` costs nothing an inline construction ever needed: two
      * inline `Node("Pick", ...)` calls still key identically to each other (`inline:Pick` both times),
      * only now provably unable to collide with a stable path spelled `Pick` too.
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

    /** `Guard`'s own module class, the identical alias-safe anchor `listModuleClass`/`nilModuleClass`
      * above already use, so a candidate term can be confirmed to name the `RequiresReview` case by
      * comparing its OWNER symbol against this, rather than by assuming whether a parameterless enum
      * case compiles to a method or a field.
      */
    val guardModuleClass = Symbol.requiredModule("in.rcard.litterbox.Guard").moduleClass

    /** Whether `t`, once any `NamedArg` wrapper (`stripNamed` above) and every wrapper `unwrap` peels
      * off is gone, names the literal `Guard.RequiresReview` case: owner `guardModuleClass` above, name
      * `"RequiresReview"`. `explicitRequiresReviewGuard` below is the only caller; a variable, a
      * computed value, or the sibling case `Guard.Open` all decline here rather than guess, the same
      * "canonicalise, or decline loudly" philosophy every other recognition function in this file
      * already holds itself to. `stripNamed` runs on both sides of `unwrap` (`unwrap` before, in case a
      * `NamedArg` sits inside a `Typed` adaptation; `unwrap` after, in case the value a `NamedArg`
      * carries is itself wrapped), mirroring the order `companionApplyArgs` above already uses for the
      * identical two wrappers.
      */
    def isRequiresReviewLiteral(t: Term): Boolean =
      val sym = unwrap(stripNamed(unwrap(t))).symbol
      sym.exists && sym.owner == guardModuleClass && sym.name == "RequiresReview"

    /** Whether an inline `Node.apply` call `t` passes `guard = Guard.RequiresReview` explicitly,
      * alongside the structural `RequiresReviewInput` read below (issue #43 review): `Guard`'s own
      * scaladoc (`Kit.scala`) states plainly that declaring the guard "is the node author's own job",
      * so the hand written, documented form has to earn a compile time check of its own, not only the
      * marker-derived one.
      *
      * INLINE is the operative word, and it is the whole scope of this function: an explicit `guard`
      * argument is source text, never a fact on a type, so the only reference form that still carries it
      * by the time this walk sees it is a `Node.apply` call written straight into the shape. A node
      * declared as `val Guarded = Node(..., guard = Guard.RequiresReview)` and then used as `Guarded`
      * presents this function with the REFERENCE, whose tree says nothing whatever about the argument
      * list on the far side of the binding, so this answers `false` and the marker test in `nodeFacts`
      * below is the only half that runs for it. On an input type that carries no `RequiresReviewInput`
      * marker, that node reads `Guard.Open` at compile time and is caught by `Runner.validate` alone
      * (`RequiresReviewInput`'s own doc, `Kit.scala`, states this asymmetry as the published scope of
      * issue #43 review round 5, and `test/GraphValidationSpec.scala`'s own round 5 tests write every
      * node inline for exactly this reason). Reading through the binding is not impossible in principle,
      * `parseTransition` below does exactly that for a `val`-bound `Transition`, but its own doc records
      * how narrow that mechanism actually is: `Symbol.tree` yields a populated `rhs` for a LOCAL `val`
      * declared in the same block and nothing at all for a `class`/`object` MEMBER, which is the form
      * almost every real node is declared in, so the reach bought would be small, the second, differently
      * limited source of one fact would be real, and `Runner.validate` already covers the whole of it.
      *
      * Reads every element of the call's own first, non `using`, argument list, named or positional,
      * looking for one that names the literal `Guard.RequiresReview` case, rather than trying to prove
      * WHICH parameter position or name it bound to.
      *
      * TRAP for a future editor: an earlier version of this function keyed on `NamedArg("guard", ...)`
      * plus a fixed sixth POSITIONAL slot guarded by `mainArgs.exists(_.isInstanceOf[NamedArg])`. That
      * guard silently always answered `true`: `quotes.reflect.NamedArg` is an ABSTRACT type member of
      * the `Quotes` instance this macro runs under, so an `isInstanceOf` test against it is unchecked
      * and erases to the type's own upper bound, `Term`, which every element of an argument list
      * already satisfies trivially, confirmed by compiling that exact snippet and reading the
      * compiler's own warning at the call site, "the type test for ... NamedArg cannot be checked at
      * runtime because it refers to an abstract type member". So the positional branch that old version
      * relied on could never fire, and a purely positional `guard = Guard.RequiresReview` argument
      * compiled clean under `checkedShape` with no violation ever reported. `Node.apply` has exactly one
      * parameter of type `Guard`, `guard` itself (`name`/`cost`/`timeout` are `String`/`Cost`/`Timeout`,
      * `probe`/`run` are function types, and the trailing `using` clause carries `TrustOf[O]` alone,
      * not a `Guard`), so ANY main argument that names the literal
      * `Guard.RequiresReview` case, wherever it sits and however it was written, can only ever be that
      * one parameter: no position or name test is needed to be sure which parameter answered, which is
      * what this version reads instead of relying on the unchecked `isInstanceOf`.
      *
      * Declines (returns `false`) rather than guesses whenever the call itself is not recognisably
      * `Node.apply` (`fn.symbol.owner == nodeModuleClass`, the same test `identifyRef` above already
      * runs) or the main argument list was rearranged out of declared order (the synthetic-`val`
      * rewrite `companionApplyArgs`'s own doc names for `Shape`/`Transition`): `uncurry` sees only the
      * REWRITTEN call in that case, a `Block` wrapping the synthetic bindings rather than the plain
      * `Apply` this function reads, so `fn.symbol.owner == nodeModuleClass` itself already fails and
      * `nodeFacts` below falls back to the structural marker test alone for that reference, the same
      * safe, decline-rather-than-guess direction every other unrecognised shape in this file already
      * takes; `identifyRef` above independently declines the SAME reference for the SAME reason, so a
      * `guard` written this way costs a reference its key too, not only this one fact.
      */
    def explicitRequiresReviewGuard(t: Term): Boolean =
      val (fn, argss) = uncurry(t)
      fn.symbol.exists && fn.symbol.owner == nodeModuleClass &&
        argss.headOption.getOrElse(Nil).exists(isRequiresReviewLiteral)

    /** Whether `t` is a call whose callee resolves into `Node`'s own companion, the same test
      * `identifyRef` and `explicitRequiresReviewGuard` above both already run to decide they are
      * looking at `Node.apply` itself rather than at some other factory. Factored out only because
      * `unwrapRef` below has to ask it about a tree neither of those two would otherwise reach.
      */
    def isNodeApplyCall(t: Term): Boolean =
      val (fn, _) = uncurry(t)
      fn.symbol.exists && fn.symbol.owner == nodeModuleClass

    /** `unwrap` (top of this file) for a NODE REFERENCE specifically, and the difference is the whole
      * reason it exists: `Node.apply` is an `inline def` (`Kit.scala`, issue #26 PR review, which is
      * what took the marker fact out of an overridable `using` clause and into a macro read of `I`), so
      * an inline `Node(name = "Pick", ...)` construction written straight into a `Shape` literal reaches
      * this macro already EXPANDED, as `Inlined(Some(originalCall), bindings, expansion)`. Neither half
      * of that tree is what the rest of this file wants: `unwrap` peels an `Inlined` with no bindings
      * down to the EXPANSION, which is `Node.make(...)` over synthetic proxy vals, where the literal
      * `name` argument and any literal `guard = Guard.RequiresReview` argument have both stopped being
      * literals sitting in an argument list. The `call` an `Inlined` carries alongside it is the
      * original, fully applied `Node.apply[I, O](...)` tree, written exactly as the author wrote it, so
      * that is what this returns instead, and `identifyRef`/`explicitRequiresReviewGuard` go on reading
      * the same source text they always read.
      *
      * Only for a call this file can confirm IS `Node.apply` (`isNodeApplyCall` above). Any other
      * inline expansion, a consumer's own `inline def myNode(...): Node[I, O]` helper, say, keeps the
      * pre-existing `unwrap` behaviour of peeling to the expansion, so nothing this walk used to read
      * one way starts being read another way because of a change that was only ever about `Node.apply`.
      */
    def unwrapRef(t: Term): Term = t match
      // `Inlined.call` is typed `Option[Tree]`, so the `: Term` narrowing is a real type test, not the
      // erased, always-true one `explicitRequiresReviewGuard`'s own TRAP paragraph above warns about:
      // that one was an `isInstanceOf` against an abstract type member, which erases to its bound;
      // this is a PATTERN, so it goes through the `TypeTest[Tree, Term]` the reflection API itself
      // provides and genuinely narrows.
      case Inlined(Some(call: Term), _, _) if isNodeApplyCall(call) => call
      case Inlined(_, Nil, body)                                    => unwrapRef(body)
      case Typed(body, _)                                           => unwrapRef(body)
      case Block(Nil, body)                                         => unwrapRef(body)
      case _                                                        => t

    /** Reads `trust`/`guard` off a node reference's own static type `Node[I, O]`, and, for `guard`
      * alone, off the reference's own SOURCE TEXT too, never off a value (`checkShapeImpl`'s own doc
      * has the reason no value exists yet to read). `I` extending `RequiresReviewInput` is one half of
      * `guard` (`RequiresReviewInput`'s own doc, `Kit.scala`, has the reason a node author's own hand
      * written argument cannot be the only half read, and `markerRequiresReview`'s own doc has the
      * identical subtype test `Node.apply` itself runs on the real value): a subtype test against the
      * reference's own concrete `I` reads it without needing a runtime value. The other half, `explicitRequiresReviewGuard` above, reads any
      * main argument that names the literal `Guard.RequiresReview` case, named or positional, so a node
      * author who wrote the documented, hand declared form (`Guard`'s own scaladoc, `Kit.scala`, calls
      * declaring it "the node author's own job") gets a real compile time check too, not only the marker
      * one. The two halves combine with an OR, matching the identical combination `Node.apply`
      * (`Kit.scala`) performs on the real constructed value.
      *
      * They do NOT have the same reach, and that asymmetry is deliberate rather than an oversight
      * waiting to be tidied up. The marker half reads the reference's own static type, which a stable
      * path carries exactly as an inline construction does, so it answers for every form `identifyRef`
      * can key at all. The argument half has only source to read, and a stable path's source is the
      * reference, not the construction, so it answers only for an inline `Node.apply` written at the
      * shape's own call site (`unwrapRef` below recovers that call from its own expansion; it recovers
      * a CALL, never the right hand side of some `val` the reference names). A node built as `val
      * Guarded = Node(..., guard = Guard.RequiresReview)` on an unmarked input type therefore still
      * reads `Guard.Open` here, and `Runner.validate` remains its only check, the same lenient
      * direction every other shape this file cannot read already takes.
      *
      * TRAP for a future editor: `trust` is read by SUMMONING `TrustOf[o]` right here, inside the
      * macro, and asking WHICH given answered, `TrustOf.judged` or `LowPriorityTrustOf.plain` (issue
      * #39 review) — never by pattern matching `O` against a type directly. This is the identical
      * search `Node.apply`'s own `using t: TrustOf[O]` runs when the real `Node` is built, so there is
      * no second, independently re-derived rule here that could ever disagree with it. An earlier
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
      * again; it also makes a separate `O =:= Nothing` special case unnecessary, since a subtyping
      * match would need one to tell `Nothing` apart from a genuine `Judged` shape, and `TrustOf[Nothing]`
      * was never going to resolve to `judged` either, for the identical invariance reason.
      *
      * A summoned given that is neither `judgedSymbol` nor `plainSymbol` is UNREADABLE, and this
      * declines the WHOLE shape rather than guess (issue #39 review): a hand written given can reuse
      * `judged`'s own `trust` answer through an unchecked cast (`Kit.scala`'s own doc on `Node.apply`
      * names the exact forgery, `TrustOf.judged.asInstanceOf[TrustOf[MyType]]`, and states plainly that
      * it compiles today), so `Node.apply` itself would stamp `Trust.Reviewed` on the real `Node`
      * whenever THAT given wins resolution, while defaulting anything unrecognised to `Trust.Plain`
      * here would stamp the opposite. Declining here, rather than guessing `Plain`, is the only reading
      * that can never disagree with what `Node.apply` actually stamps.
      *
      * One fact has to be ruled out before either fact below is trusted: a WIDENED reference's captured
      * `i`/`o` are the wildcard's own abstract bounds, never the concrete types the value was built
      * with. Handled inline below, immediately before the fact it would otherwise corrupt.
      */
    def nodeFacts(t: Term): Option[NodeRef] =
      val real = unwrapRef(t)
      real.tpe.widen.dealias.asType match
        case '[Node[i, o]] =>
          // A WIDENED reference (`val OpenPr: Node[?, ?] = Node[PrInput, Unit](...)`) still matches
          // this very pattern, but `i`/`o` bind to the wildcard's OWN synthetic bounds, never to the
          // concrete types the value was actually built with (issue #39 review, confirmed by inspecting
          // what a widened reference's captured type parameters actually are: an abstract
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
            // `guard` is `Guard.RequiresReview` when EITHER half says so (issue #43 review;
            // `explicitRequiresReviewGuard`'s own doc above has the reading this second half
            // performs): the marker on `I` alone used to be the only half read here, which is what let a
            // node declared `guard = Guard.RequiresReview` by hand, `Guard`'s own scaladoc calls that the
            // "node author's own job" (`Kit.scala`), walk through this compile time check as though it
            // were unguarded whenever its input type happened not to extend `RequiresReviewInput` too.
            // `Node.apply` (`Kit.scala`) already combines the identical two facts the identical way at
            // runtime, so reading only one of them here was a genuine gap this walk had, not a difference
            // in what the two checks were ever meant to agree on.
            val guard =
              if TypeRepr.of[i] <:< TypeRepr.of[RequiresReviewInput] || explicitRequiresReviewGuard(real)
              then Guard.RequiresReview
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
      * #43 review), the way `Runner.validate` never has to, since it reads a genuine `Transition` VALUE
      * rather than a piece of source. Unlike `nodeFacts`, which reads `trust`/`guard` off a reference's
      * STATIC TYPE and therefore never needs to look inside a `val` binding at all, `Transition`'s own
      * case class carries no type parameters encoding `from`/`to` (`Transition(from: Node[?, ?], to:
      * Node[?, ?])`, `Kit.scala`), so there is no fact on `t1`'s own type this walk could read either
      * side off; the only place `from`/`to` exist at all, before a real `Transition` value is ever
      * constructed, is inside `t1`'s own initialiser. `stablePathKey(t)` is reused, rather than a
      * separate stability test written here, so this never trusts a `var`-bound or otherwise unstable
      * `t1`, the identical guard `isStablePathLink` gives everywhere else in this file. `Symbol.tree` is
      * wrapped in a `Try`, and its RESULT, not merely whether it throws, decides whether this resolves:
      * a `t1` bound by a plain local `val` inside the SAME method or block that also builds the literal
      * `Shape` resolves cleanly, `vd.rhs` fully populated with `t1`'s own `Transition(A, B)` call; a
      * `t1` bound as a MEMBER of an enclosing `class`/`object`, initialised in that type's own primary
      * constructor rather than carried on the member `ValDef` itself, does not, `vd.rhs` comes back
      * `None` on both the member's own symbol tree and a hand-written scan of the owning class's body
      * for exactly this reason. This walk therefore
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
      * rather than a single `None` (issue #43 review). The strict caller (`checkShapeImpl`'s own tail
      * match) needs to tell them apart to raise three DIFFERENTLY worded errors: `NotALiteral` is
      * `shapeExpr` itself not recognisably a `Shape(...)` call at all (a `val`, a function result,
      * `Machine.shippedShape`, ...); `UnreadablePiece` is a narrower failure, `shapeExpr` genuinely IS a
      * `Shape(...)` literal, written directly at the call site, but one TERM somewhere inside it (a node
      * reference, a `Transition`, a list) is not one of the forms this walk can read; `KeyConflict` is
      * narrower still, every piece of `shapeExpr` WAS readable, but two of the `NodeRef`s this walk
      * built from it canonicalise to the identical `key` (`stablePathKey`'s own doc above has the two
      * ways that can happen, a `class`/`object` companion pair or two inline constructions sharing one
      * literal `name`) while disagreeing on `(trust, guard)`, so there is no honest way to decide which
      * of the two facts `violations` below should trust (`checkReconciled`'s own doc has the full
      * reasoning). Conflating the first two under `checkedShapeStrict` was found unsound: a consumer who
      * wrote a fully literal `Shape` reaching only ordinary idioms this walk simply had not been taught
      * yet (a class member `val` referenced bare, `List.empty`, out-of-order named arguments) received
      * the SAME "write a literal here" message a consumer who wrote no literal at all would get, advice
      * the first consumer could not act on because they had already followed it; letting the visited set
      * of a BFS silently pick a winner between two conflicting `NodeRef`s sharing one key is the
      * identical class of mistake one level down, so `KeyConflict` gets its own wording for the same
      * reason. The lenient caller (`checkedShape`) does not need any of these three distinguished, and
      * does not get them: every case falls back to `shapeExpr` unchanged there, exactly as a single
      * `None` always did, since `checkedShape`'s own doc (`Kit.scala`) already commits to silently
      * degrading on ANY unreadable shape for its own callers, regardless of why, key conflicts included.
      */
    enum ParseFailure:
      case NotALiteral
      case UnreadablePiece(term: Term)
      case KeyConflict(key: String, first: NodeRef, second: NodeRef)

    /** Checks every `NodeRef` `parseShape` below has collected so far, `entry` and both sides of every
      * `Transition`, for two references sharing one `key` (`stablePathKey`/`identifyRef` above) while
      * disagreeing on `(trust, guard)` (issue #43 review). Canonicalising the key (`canonical`,
      * `stablePathKey`'s own doc) closes the SPLIT failures: the same node, spelled two ways, now keys
      * identically, so `violations`' own BFS links the edge it used to miss. Canonicalisation does NOT,
      * on its own, close the MERGE failures: a `class`/`object` companion pair sharing one simple name
      * once `$` is stripped, or two inline `Node.apply` calls sharing one literal `name` — two genuinely
      * DIFFERENT nodes can still canonicalise to the identical key, because a key is, deliberately, a
      * STRING derived from a symbol or a literal, never a proof of runtime identity this macro has no
      * constructed value to check against. Left unchecked, `violations`' own BFS would run over
      * whichever `NodeRef` the visited set's underlying `Map` happens to keep for a shared key, silently
      * picking a winner between two conflicting facts and dropping the other, exactly the "confident but
      * wrong" reading this whole file exists to avoid (`parseShape`'s own doc has the identical principle
      * for a single unreadable term; this is its restatement for two READABLE terms that turn out to
      * disagree). So this runs BEFORE `violations`, over the raw `NodeRef` list, and hard-declines the
      * whole shape the instant it finds one conflict, rather than letting the BFS run at all and produce
      * an answer that could, by construction, disagree with what a `Runner.validate` running against the
      * REAL, single, already-merged-or-not-by-the-JVM `Node` values would find. `first`/`second` are
      * kept, in the order `groupBy` happened to collect them, purely so the caller can name and point at
      * BOTH disagreeing references, not only whichever one this fold visited last; which of the two
      * prints first is not a fact this function promises, only that both print.
      *
      * A shared key with IDENTICAL `(trust, guard)` on every reference sharing it, by contrast, is not
      * flagged here at all: whether that is a genuine companion collision that happens not to matter
      * (both sides equally open or equally guarded) or two independent nodes that coincidentally agree
      * on both facts, `violations` reads nothing else off a `NodeRef`, so nothing this function could
      * report would ever change what it finds either way, and reporting it anyway would be noise a
      * consumer could not act on.
      *
      * A SECOND alias family exists beside the one this function closes, named here rather than left for
      * a reader to rediscover, because canonicalisation plus this reconciliation pass together close
      * every SPLIT and every disagreeing MERGE, but neither one, nor any decline rule expressible over a
      * single `Shape` literal, closes this one: one runtime `Node` VALUE bound to TWO different
      * top-level `val`s, `val n19Start = zzmk("Start")` and, separately, `val n19Same = n19Start`, the
      * second `val`'s own initialiser reading straight from the first rather than constructing anything
      * new. `canonical` keys each `val` on its OWN symbol, `n19Start` and `n19Same` are two different
      * symbols, so this walk produces two DIFFERENT keys for what is, at runtime, one single `Node`, and
      * neither key ever collides with the other the way a misspelled SAME symbol does, so
      * canonicalisation has nothing to canonicalise here, and neither key is ever shared by two
      * disagreeing `NodeRef`s, so `checkReconciled` above has nothing to catch either: an edge whose
      * `from` was written through `n19Same` never links to an `entry` element written through
      * `n19Start`, silently, the identical orphaned-edge mechanism as the SPLIT case above had, just no
      * longer reachable by fixing the KEY function, since the two keys here are each, individually,
      * perfectly legitimate. Recovering the fact that `n19Same`'s own initialiser is `n19Start` would
      * need this walk to read a MEMBER `val`'s own right-hand side back out through `Symbol.tree`, the
      * exact operation `parseTransition`'s own doc above already records returns `vd.rhs == None` for a
      * member, not a local; a version of this file that read every member `val`'s initialiser to chase
      * this kind of alias would also have to re-run the identical stability and cycle reasoning
      * `parseTransition` already pays for a bound `Transition`, for every ordinary node reference in a
      * shape, a scope well past what this macro's per-call-site tree walk is built to do, and would
      * still fail the moment the alias crossed a FILE boundary this same compilation unit does not see,
      * `n19Same` defined in a different module than `n19Start`. So this is left standing, uniformly,
      * alongside the def-built-node residue `checkShapeImpl`'s own doc above already names: this macro
      * cannot tell one `Node` bound under two stable paths apart from two genuinely different `Node`s
      * that happen to canonicalise differently, which is exactly what it is, two different symbols, so
      * there is nothing dishonest about the silence, only a real limit of a walk that reasons over
      * SYMBOLS, never over the VALUES those symbols happen to alias. What makes this residue SURVIVABLE
      * rather than a silent hole (`Node.apply`'s own doc, `Kit.scala`, `markerRequiresReview`'s own doc alongside
      * `TrustOf`, `Kit.scala`): `Runner.validate` now derives the identical `Guard.RequiresReview` this
      * walk derives, off the SAME `RequiresReviewInput` marker, on the constructed `Node` value itself,
      * not merely off a hand-written field a node author could forget to set to match; so a graph this
      * walk cannot fully verify, because one of its nodes is reachable through an alias this walk cannot
      * chase, is still checked by `Runner.validate` at startup, against the real, single, already-
      * resolved `Node` values, where `n19Start` and `n19Same` are not two facts to reconcile at all,
      * only one value read twice.
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
      * function (issue #43 review): a single walk here, rather than a
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
        // review). Matching `args` here as a generator, not a plain `val` pattern, is what
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
        // inside `transitions` (both conflicting refs are `Transition` endpoints, neither is an `entry`
        // element) so restricting this to `entry` would
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
      * Only the BFS half, stated plainly rather than left to be inferred (issue #39 review):
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
            // `nodeFacts` above reads TWO independent sources for this fact (issue #43 review): the
            // INPUT type's own `RequiresReviewInput` marker, and a literal `guard =
            // Guard.RequiresReview` argument read straight off the reference's own source text. Naming
            // only the marker here, the way an earlier version of this message did (issue #39 review,
            // when the marker was still the only source), would be wrong advice for a node
            // guarded purely by the hand written argument: telling it to "stop extending
            // RequiresReviewInput" names a marker it never carried in the first place. The message below
            // therefore names both possible causes rather than assuming which one fired.
            found +=
              s"path ${pathHere.mkString(" -> ")} reaches '${node.display}', a node that requires " +
                s"review (its own input type extends RequiresReviewInput, or its guard argument was " +
                s"written explicitly as Guard.RequiresReview, or both), with no reviewed node anywhere " +
                s"before it on that path. Put a node whose output type is AgentDispatch.Judged[_] " +
                s"somewhere before '${node.display}' on this path, or stop declaring it " +
                s"Guard.RequiresReview, by removing RequiresReviewInput from its input type and/or its " +
                s"explicit guard argument, whichever applies, if it genuinely never needs a review."
          val reviewedHere = reviewed || node.trust == Trust.Reviewed
          byFrom.getOrElse(node.key, Nil).foreach(child => queue.enqueue(Item(child, pathHere, reviewedHere)))

      found.toList

    parseShape match
      case Left(ParseFailure.NotALiteral) =>
        // The lenient path (`strict = false`, `checkedShape`'s own caller): a `shapeExpr` this walk
        // cannot read as a literal is not this function's business to reject, `Runner.validate`
        // remains the backstop (`checkedShape`'s own doc, `Kit.scala`, has the reasoning). The strict
        // path (`strict = true`, `checkedShapeStrict`'s own only caller, `LitterBox.graph`): the
        // identical situation is a hard error instead (issue #43 review). `Node.apply` (`Kit.scala`,
        // `markerRequiresReview`'s own doc) derives `Guard.RequiresReview` on the real `Node` whenever a node's own
        // input type extends `RequiresReviewInput`, even if its `guard` argument was left at the
        // default `Guard.Open`, so `Runner.validate` catches that case too; erroring here remains
        // correct regardless (`checkedShapeStrict`'s own doc, `Kit.scala`, has the two reasons that
        // survive: compile time beats startup on timing alone, and two alias residuals exist, named in
        // `stablePathKey`'s and `checkReconciled`'s own docs above, that no widening of THIS walk
        // closes, for which `Runner.validate` remains the only check that ever runs at all). Naming
        // what to do instead, rather than merely refusing, is what `report.errorAndAbort` buys over a
        // bare fallback here. This is `ParseFailure.NotALiteral` specifically, never `UnreadablePiece`:
        // `shapeExpr` itself was not recognisable as a `Shape(...)` call at all, so "write the Shape
        // literal inline" is advice this consumer genuinely has not yet followed, unlike the
        // `UnreadablePiece` case below.
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
        // The SECOND, narrower strict failure, deliberately given DIFFERENT wording from `NotALiteral`
        // above so the two can never be confused for one another by a reader or by a test asserting on
        // the message: `shapeExpr` WAS recognised as a literal `Shape(...)` call written directly at
        // this call site, but this one TERM inside it, `bad`, is not a form this walk can read (an
        // entry/transitions list built some way other than `List(...)`/`List.empty`/`Nil`, a node or
        // `Transition` reference that is neither a stable path nor an inline literal construction, ...).
        // Falling back leniently here, the way the lenient `checkedShape` path still does, is explicitly
        // ruled out (issue #43 review): that would reopen for every marker-only-guarded node this walk
        // happens not to recognise the exact hole `NotALiteral` above closes, so this raises just as
        // loud, only with different words and anchored at `bad`'s own source position
        // (`report.errorAndAbort`'s two-argument overload), rather than at the whole `shapeExpr`
        // `NotALiteral` points at, so the compiler underlines the exact unread term rather than the
        // whole call. The forms actually named below are precisely the ones `stablePathKey`,
        // `literalListElements` and `identifyRef` accept, kept in sync with them by hand since a macro
        // error string cannot introspect its own sibling functions; a form accepted there and not named
        // here is a documentation bug, not a runtime one, since the acceptance itself is what actually
        // governs whether this branch is ever reached. A node built by a `def`, including
        // `Machine.shippedShape`'s own config-parameterised `A(cfg)` idiom (`LitterBox.graph`'s own
        // scaladoc has the fuller reasoning for why that idiom is not, and cannot be made, expressible
        // through this factory), is the one residue this fix leaves on purpose: this macro reasons about
        // TREES, never about a running value (`checkShapeImpl`'s own doc above), so a node a `def` call
        // would build is not a fact this walk could ever read no matter how far
        // `stablePathKey`/`literalListElements` are widened. `export`ed member forwarders are named
        // explicitly, separately from the def-built-node residue above, rather than left for a consumer
        // to lump in with it (issue #43 review): `object B: export A.*` makes `B.Foo` look, at the call
        // site, exactly like the "an object member" form this message already lists as readable, but
        // Scala compiles an `export` into a synthetic FORWARDER `def`, never a `val`, so
        // `isStablePathLink` rightly declines it and this message would otherwise point the consumer
        // straight back at the form they already, apparently, used. An instance-qualified receiver
        // (`a.node` for an ordinary, non-singleton `a`) is named explicitly too, rather than left for a
        // consumer to lump in with the def-built-node residue above: `stablePathKey`'s own doc has the
        // reasoning for why this walk stopped keying every instance-qualified receiver it can prove is
        // STABLE and started declining all of them uniformly, regardless of whether two of them provably
        // alias the same value or provably do not, since this macro cannot observe which is true and
        // keying either answer risks disagreeing with the runtime.
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
        // The THIRD strict failure, given its own wording for the identical reason `NotALiteral` and
        // `UnreadablePiece` above already have separate ones:
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
