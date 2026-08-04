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
    * make this fall back to returning `shapeExpr` completely unchanged, neither one an error: the
    * expression is not that literal shape at all (a variable, a function call, `Machine.shippedShape`,
    * anything indirect), or it is that shape but this walk cannot fully make sense of one of its
    * pieces (an entry or transition list built some way other than a plain `List(...)` literal, say).
    * `checkedShape`'s own doc states why silently degrading, rather than aborting, is the only choice
    * that keeps this function safe to sprinkle onto a `Shape` a future author is not yet ready to
    * write as a full literal: `Runner.validate` remains the backstop for exactly that graph.
    *
    * When the literal IS fully readable, this runs the SAME BFS `Runner.validate` runs (from `entry`,
    * over `transitions`, a state keyed on `(node, reviewed so far)` so no diamond or cycle is ever
    * expanded twice, `Runner.validate`'s own doc, `Kit.scala`, has the shared reasoning for why BFS
    * and why that particular key). What identity and trust actually read has to be stated precisely,
    * correcting an earlier, too strong version of this paragraph (issue #39 review round 3, M5):
    * identity is NOT read off the `Node.name` string the way `Runner.validate` reads it, it is read
    * off a STABLE PATH, a package, a module, or an immutable `val`, chained through every prefix, or
    * off a literal `name` argument written directly at an inline `Node.apply` call (`identifyRef`'s
    * own doc below has the full reasoning). That key can SPLIT what `Runner.validate` merges, two
    * distinct `val`s that happen to carry the same `name` string at runtime are one node to
    * `Runner.validate`, two here, never the other way, so the one direction this can be wrong in is a
    * violation missed here that `Runner.validate` still catches, never a violation invented here that
    * `Runner.validate` would not raise. `trust`, unlike identity, genuinely is read the same way
    * `Node.apply` itself derives it, by summoning `TrustOf[O]` and reading which given answered
    * (`nodeFacts`'s own doc below has the mechanism), declining the whole shape rather than guessing
    * whenever a third given, neither `TrustOf.judged` nor `LowPriorityTrustOf.plain`, answers instead.
    * Two respects still remain in which this walk is NOT `Runner.validate`'s, both by design rather
    * than by oversight: `guard` is read structurally, off the
    * reference's own INPUT type extending `RequiresReviewInput`, never off the hand written `guard`
    * field `Runner.validate` reads (`checkedShape`'s own doc, `Kit.scala`, has the one way that can
    * diverge from a genuine `Runner.validate` outcome); and three DECLARATION level checks
    * `Runner.validate` also runs have no AST level analogue here at all (`violations`'s own doc,
    * below, names each one and why).
    */
  def checkShapeImpl(shapeExpr: Expr[Shape])(using Quotes): Expr[Shape] =
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

    /** The element terms of a literal `List(a, b, c)` call, or `None` for anything else (a `Nil`, a
      * variable, a `:+`/`++` chain, a DIFFERENT varargs method entirely, ...). `List(...)`'s own single
      * argument, once past the varargs apply itself, is a `Repeated` node holding exactly the elements
      * written at that call site; that `Repeated` shape alone is not enough to trust, though, since any
      * varargs method call shares it (issue #39 review round 3, M1, confirmed by writing a consumer
      * helper of the identical shape and inspecting what the compiler actually calls it: `def
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
      * names the real module correctly.
      */
    val listModuleClass = Symbol.requiredModule("scala.collection.immutable.List").moduleClass

    def literalListElements(t: Term): Option[List[Term]] =
      unwrap(t) match
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
        case _ => None

    /** The two positional arguments of a case class companion's `apply` call, matched by comparing the
      * called method's own OWNER against `moduleClass`, not by name or arity alone: a differently
      * named two argument function call must not be mistaken for `Shape(...)` or `Transition(...)`.
      */
    def companionApplyArgs(t: Term, moduleClass: Symbol): Option[List[Term]] =
      unwrap(t) match
        case Apply(fun, args) if fun.symbol.exists && fun.symbol.owner == moduleClass =>
          Some(args.map(a => stripNamed(unwrap(a))))
        case _ => None

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
      * edges on, `display` is what an error message shows a human, and `trust`/`guard` are read off
      * the reference's own static type (`nodeFacts` below), never off a value.
      */
    final case class NodeRef(key: String, display: String, trust: Trust, guard: Guard)

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

    /** The full stable path key of `t`, `None` unless EVERY prefix, all the way down to the root, is a
      * stable path link (`isStablePathLink` above), keyed on the WHOLE chain, receiver included, never
      * only the last segment (issue #39 review round 3, B1: keying a `Select` by its own member symbol
      * alone throws the receiver away, so two distinct receivers of the same instance member, `a.node`
      * and `b.node` for two different `a`/`b`, merge into one key even though each receiver's `node`
      * genuinely builds a different `Node`, confirmed by writing exactly that and finding the old rule
      * merged them). Chaining through `stablePathKey` on the qualifier, rather than reading `t.symbol`
      * alone, is what makes `a.node` and `b.node` differ here: the member symbol `node` is identical
      * for both, only the receiver distinguishes them.
      */
    def stablePathKey(t: Term): Option[String] =
      t match
        case Ident(_) =>
          if isStablePathLink(t.symbol) then Some(t.symbol.fullName) else None
        case Select(qualifier, name) =>
          if isStablePathLink(t.symbol) then
            stablePathKey(qualifier).map(prefix => s"$prefix.$name")
          else None
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
      * A stable path (`Pick`, `Machine.OpenPr`, `a.node` for a stable `a`, ...) is keyed on the WHOLE
      * chain (`stablePathKey` above): the same path written twice in one shape is the same value, hence
      * the same `Node`, hence, trivially, the same `name`, without this macro ever having to read that
      * field.
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
      */
    def identifyRef(t: Term): Option[(String, String)] =
      t match
        case Ident(_) | Select(_, _) =>
          stablePathKey(t).map(key => (key, t.symbol.name))
        case Apply(_, _) =>
          val (fn, argss) = uncurry(t)
          if fn.symbol.exists && fn.symbol.owner == nodeModuleClass then
            literalNameArg(argss.headOption.getOrElse(Nil)).map(n => (n, n))
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
            yield NodeRef(key, display, trust, guard)
        case _ => None

    /** Parses one `Transition(from, to)` element into its two node references, or `None` if either
      * side is not itself a recognisable node reference (`nodeFacts` above).
      */
    def parseTransition(t: Term): Option[(NodeRef, NodeRef)] =
      companionApplyArgs(t, transitionModuleClass) match
        case Some(List(fromTerm, toTerm)) =>
          for
            from <- nodeFacts(fromTerm)
            to   <- nodeFacts(toTerm)
          yield (from, to)
        case _ => None

    /** The full parse: `shapeExpr` has to be a literal `Shape(entry = List(...), transitions =
      * List(...))` call, every entry element a recognisable node reference, and every transitions
      * element a recognisable `Transition(...)` of two recognisable node references, for this to
      * return anything at all. One unrecognised piece anywhere is enough to fall back for the WHOLE
      * shape, deliberately: a partial reading could clear a graph whose unreadable half is exactly
      * where the real violation lives, which would be worse than not checking at all.
      */
    def parseShape: Option[(List[NodeRef], List[(NodeRef, NodeRef)])] =
      for
        args                          <- companionApplyArgs(shapeExpr.asTerm, shapeModuleClass)
        // `companionApplyArgs` matches ANY call whose callee is owned by `Shape`'s own module class,
        // not specifically `Shape.apply`: Scala synthesises `Shape.unapply` on that exact same module
        // class for a case class shaped like this one, a plain, non `Option` wrapped extractor taking
        // ONE argument, the scrutinee, not the two `entry`/`transitions` this walk needs (issue #39
        // review, BLOCKER 1). Matching `args` here as a generator, not a plain `val` pattern, is what
        // turns that arity mismatch into a graceful `None`, the same fallback every other unreadable
        // piece in this function already gets, rather than a `MatchError` thrown straight out of the
        // macro and into the compiler's own error stream.
        (entryTerm, transitionsTerm) <- args match
                                           case List(e, t) => Some((e, t))
                                           case _          => None
        entryElems        <- literalListElements(entryTerm)
        entryNodes        <- entryElems.foldRight(Option(List.empty[NodeRef])) { (e, acc) =>
                                for tail <- acc; n <- nodeFacts(e) yield n :: tail
                              }
        transitionElems   <- literalListElements(transitionsTerm)
        transitions       <- transitionElems.foldRight(Option(List.empty[(NodeRef, NodeRef)])) { (e, acc) =>
                                for tail <- acc; edge <- parseTransition(e) yield edge :: tail
                              }
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
      case None => shapeExpr
      case Some((entry, transitions)) =>
        val vs = violations(entry, transitions)
        if vs.isEmpty then shapeExpr
        else
          report.errorAndAbort(
            "litter-box: this Workflow graph is rejected at compile time (issue #39, RFC #26 " +
              "decision 16), before Runner.validate would even see it at startup: " +
              vs.mkString("; ")
          )
