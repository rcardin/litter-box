## Conventions

Full text in `.litter-box/prompts/conventions.md` (this repo runs the loop on itself, so that file is
both real config and a worked example). The load-bearing ones:

- Everything under `test/` must stay Docker-free, network-free and credential-free. Docker-dependent
  tests are shell scripts under `sandbox/test/`, run by hand, never wired into the gate.
- One runtime dependency (`com.typesafe:config`) and one test dependency (scalatest). Adding a
  dependency is a design decision, not a convenience.
- Never use `@nowarn` or any other suppression to get past a warning. Fix the cause.
- Scaladoc explains WHY a decision was made, never what the code does.
- Prose contains no dash characters.
- The template to copy for a new handler: `LiveGateRunner` in `src/Live.scala` plus its tests in
  `test/LiveProcSpec.scala` — dependencies as constructor params, one seam for the thing that must be
  faked, tests driving real behaviour through the seam rather than asserting on a mock.