## Conventions

Full text in `.litter-box/prompts/conventions.md` (this repo runs the loop on itself, so that file is
both real config and a worked example). The load-bearing ones:

- Everything under `test/` must stay Docker-free, network-free and credential-free. Docker-dependent
  tests are shell scripts under `sandbox/test/`, run by hand, never wired into the gate.
- One runtime dependency (`com.typesafe:config`) and one test dependency (scalatest). Adding a
  dependency is a design decision, not a convenience.
- Never use `@nowarn` or any other suppression to get past a warning. Fix the cause.
- No code in `src/Kit.scala`, `src/KitMacro.scala` or `src/PatchGuard.scala` may name anything
  declared outside those three files plus `src/Domain.scala` and `src/Caps.scala`. Comments and
  string literals are exempt.
  `docs/adr/0001-framework-tier-is-kit-only.md` has the reasoning, `test/KitBoundarySpec.scala`
  holds it, and the reason it needs writing down is that every fault site that broke it arrived for
  a good local reason and compiled clean.
- Scaladoc explains WHY a decision was made, never what the code does.
- Prose contains no dash characters.
- The template to copy for a new handler: `LiveGateRunner` in `src/Live.scala` plus its tests in
  `test/LiveProcSpec.scala` — dependencies as constructor params, one seam for the thing that must be
  faked, tests driving real behaviour through the seam rather than asserting on a mock.