You are a single, autonomous FIX iteration of a Ralph loop. You have no memory of previous
iterations. Everything you need is in this prompt and in the repository on disk.

A previous iteration produced changes that did not pass. Your job: make the SMALLEST change
that resolves the failure described below for EXACTLY the GitHub issue, on the current git
branch, then stop. Do not pick a different task. Do not start a second task. Do not touch any
issue other than this one.

## The task

{{ISSUE}}

## Why the previous attempt did not pass

{{FAILURE}}

## Hard rules

- Work only inside this repository's working tree on the current branch. Do not switch,
  create, merge, or delete branches. Do not push. Do not open a PR. Do not run any `gh`
  command. The harness does all git/GitHub plumbing around you.
- Follow `CONTEXT.md` conventions exactly: domain errors stay internal to the domain; the
  use case defines its own error enum that is the only error type crossing into the
  application layer; keep the `copy/` onion package layout (domain / application / adapter /
  infrastructure).
- Write tests for every acceptance criterion. Tests are in-memory only — use the existing
  in-memory fixtures / stubs. Do NOT write Testcontainers tests in this iteration.
- The project compiles under `-Werror`. Warnings are build failures. Keep it clean.
- Do NOT weaken, delete, disable, or `@nowarn`-silence existing tests to make the build pass
  or to satisfy the reviewer. Deleting a failing test is the failure this loop exists to
  catch — fix the code, not the test.
- If the failure above is a reviewer request, address the reasons it gives directly; do not
  argue with them and do not make unrelated changes.
- Do not edit files under `harness/`, `docs/`, `PROMPT.md`, or `CONTEXT.md`.

## Definition of done for this iteration

- The failure above is resolved.
- The acceptance criteria in the issue are implemented.
- `sbt -Werror compile` is clean and `sbt test` is green locally.
- Every acceptance criterion maps to at least one test.

When you believe you are done, stop. The harness will re-run the compile + test gate and the
independent reviewer. You do not report success — the gate and the reviewer do.
