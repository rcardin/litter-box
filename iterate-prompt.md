You are a single, autonomous iteration of a Ralph loop. You have no memory of previous
iterations. Everything you need is in this prompt and in the repository on disk.

Your job: implement EXACTLY the GitHub issue below, on the current git branch, end to end,
and then stop. Do not pick a different task. Do not start a second task. Do not touch any
issue other than this one.

## The task

{{ISSUE}}

## Hard rules

- Work only inside this repository's working tree on the current branch. Do not switch,
  create, merge, or delete branches. Do not push. Do not open a PR. Do not run any `gh`
  command. The harness does all git/GitHub plumbing around you.
- Follow `CONTEXT.md` conventions exactly: domain errors stay internal to the domain; the
  use case defines its own error enum that is the only error type crossing into the
  application layer; keep the `copy/` onion package layout (domain / application / adapter /
  infrastructure).
- Use the existing US-1 `Register` slice as your template for shape, naming, and tests.
- Write tests for every acceptance criterion. Prefer in-memory unit/acceptance tests — use the
  existing in-memory fixtures / stubs — and put them in `src/test/scala`.
- **Test-placement rule (tier split).** Integration tests that need a real database
  (Testcontainers / a JDBC round-trip against Postgres) live in `src/it/scala`. In-memory unit
  and acceptance tests live in `src/test/scala`. Never put a Docker-dependent test in
  `src/test` — it will not run in the fast gate and will break the tier split. For most user
  stories no `src/it` test is needed; add one only if the acceptance criteria require a real
  Postgres round-trip.
- The project compiles under `-Werror`. Warnings are build failures. Keep it clean.
- Do not weaken, delete, or `@nowarn`-silence existing tests to make the build pass.
- Do not edit files under `harness/`, `docs/`, `PROMPT.md`, or `CONTEXT.md`.

## Definition of done for this iteration

- The acceptance criteria in the issue are implemented.
- `sbt -Werror compile` is clean and `sbt test` (the fast in-memory tier) is green locally.
- If you added any `src/it` test, `sbt It/test` (the real-Postgres tier) is green locally too.
- Every acceptance criterion maps to at least one test.

When you believe you are done, stop. The harness runs the fast (in-memory) gate, then the IT
(real-Postgres) gate, then an independent reviewer, opens a PR, and hands it to a human. You do
not report success — the gates and the reviewer do.
