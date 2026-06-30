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
- Write tests for every acceptance criterion. Tests are in-memory only — use the existing
  in-memory fixtures / stubs. Do NOT write Testcontainers tests in this iteration.
- The project compiles under `-Werror`. Warnings are build failures. Keep it clean.
- Do not weaken, delete, or `@nowarn`-silence existing tests to make the build pass.
- Do not edit files under `harness/`, `docs/`, `PROMPT.md`, or `CONTEXT.md`.

## Definition of done for this iteration

- The acceptance criteria in the issue are implemented.
- `sbt -Werror compile` is clean and `sbt test` is green locally.
- Every acceptance criterion maps to at least one test.

When you believe you are done, stop. The harness will run the compile + test gate, open a
PR, and hand it to a human. You do not report success — the gate does.
