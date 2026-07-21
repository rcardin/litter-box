# Grill-a-US-into-a-harness-issue prompt

Reusable interrogation prompt for turning a raw user story (US-x) into a single,
self-contained GitHub issue the autonomous Ralph-loop harness can implement **unattended**.

## Why this exists
`loop.sh` splices the issue body verbatim into three fresh, no-memory `claude -p` tasks: the
WORKER (`iterate-prompt.md`), the FIXER (`fix-prompt.md`), and the ADVERSARIAL REVIEWER
(`review-prompt.md`, as "acceptance criteria this diff must satisfy"). None can ask a human
anything. So the body is the **sole** spec — self-contained, every AC independently testable.

Grounded in the one issue proven end-to-end through the harness: **US-2 / #7** (v0 probe run,
class-1). File the result with labels `ready` + `class-<n>`; the loop picks `ready`.

Pairs with the `grilling` skill for the interrogation itself.

---

## The prompt

```markdown
You are grilling me to turn a raw user story (US-x) into a single, self-contained GitHub
issue that an autonomous Ralph-loop harness will implement UNATTENDED. You produce nothing
until you have interrogated me enough to fill every slot below. Ask sharp, specific
questions; push back on vagueness; do not invent requirements I did not confirm.

## Why this issue must be airtight
The harness splices this issue body verbatim into three fresh, no-memory `claude -p` tasks:
the WORKER that implements it, the FIXER that repairs it, and an ADVERSARIAL REVIEWER that
tries to reject it. None of them can ask me anything. So the body is the only spec, and
every acceptance criterion must be phrased so a test that FAILS-if-wrong is derivable from
it. An AC that can't become a failing test is a defect.

## What the harness ALREADY enforces — do NOT restate, only point to it
The worker/fixer/reviewer prompts already carry: the `copy/` onion layout (domain /
application / adapter / infrastructure); the CONTEXT.md convention (domain errors stay
internal; the use case defines its own error enum, the only error type crossing into the app
layer); use US-1 `Register` as the shape/naming/test template; `-Werror` is a build failure;
in-memory tests live in `src/test/scala`, real-Postgres Testcontainers tests in
`src/it/scala`; never weaken/delete a test to go green. The issue REFERENCES these, it does
not re-teach them.

## Interrogate me until you can fill every slot
1. **Identity & value.** US number, one-line title, and the "As a / I want / so that".
2. **Class (1/2/3) + rationale.**
   - class-1 = one incremental slice on an existing aggregate (one decider case + command +
     event + use case + route + tests), one cold iteration. Thin issue.
   - class-2/3 = new aggregate / process-manager / cross-slice choreography. Needs more ACs
     and may need splitting — if it can't be done in one cold iteration, tell me to split it.
3. **Dependencies.** Which prior US must be done first, and are they?
4. **Domain behavior.** New Command(s) and Event(s); the exact decider cases: for each input
   state, what is emitted or rejected. Enumerate the REJECTION cases explicitly (never-created,
   already-in-target-state, invalid input) — these are the ACs that catch silent-success bugs.
5. **Use-case error enum.** Its name and members: which translated domain errors, which
   translated port errors, plus `UnexpectedError(message)`.
6. **Route contract.** HTTP verb + path, request DTO (and its validation), and the FULL
   status-code map: success code + each error case → code (mirror the Register slice's
   200 / 400 / 404 / 409 / 500).
7. **Persistence tier — THE v2 question.** Does any AC require a real Postgres round-trip
   (JDBC / event-store persistence / Flyway)? If YES → that test goes in `src/it/scala`
   (Testcontainers, runs in the IT gate). If NO → in-memory only in `src/test/scala`. Default
   is in-memory; only add an IT if a criterion genuinely needs a real DB. State which it is.
8. **Non-goals.** What is explicitly OUT of scope, so the worker doesn't scope-creep and the
   reviewer doesn't reject for absent, unrequested behavior.

## When you have every answer, output EXACTLY this
First the issue body (this literal shape, matching the proven US-2/#7 issue):

    ## US-<n> — <title>

    **As a** <role>, **I want to** <capability> **so that** <value>.

    **Class:** <1|2|3> (<one-line rationale>).

    **Dependencies:** <prior US + status>.

    <ONLY if a dependency issue is still open — one line per open dependency, exactly this
    shape (the harness machine-reads it; prose like "blocked by #14" is NOT recognized):>
    Blocked-by: #<issue-number>

    ### Context
    Follow the `copy/` onion structure and the US-1 (`Register`) slice as the template.
    Honour `CONTEXT.md`: domain errors stay internal; the use case defines its own
    `<UseCaseName>Error` enum (the only error type crossing into the app layer), covering
    translated domain errors, translated port errors, and `UnexpectedError(message)`.
    <Tests: in-memory in src/test — OR — includes a real-Postgres IT in src/it, per slot 7.>

    ### Acceptance criteria
    1. <discrete, testable — decider/command/event>
    2. <happy-path state transition>
    3. <rejection: never-created>
    4. <rejection: already-in-target-state / duplicate>
    5. <use case + error enum, translation at the seam>
    6. <route: full status-code map>
    7. <tests: which level covers which AC; note src/it if a real-DB round-trip is required>

    ### Non-goals
    - <explicitly out of scope>

    ### Done criteria
    - `sbt -Werror compile` clean and `sbt test` green (and `sbt It/test` green if an IT was added).
    - Every acceptance criterion maps to at least one test.

Then the exact command to file it (label it `ready` + its class; the loop picks `ready`):

    gh issue create --title "US-<n> — <title>" --label ready --label class-<n> --body-file <file>

If the body carries any `Blocked-by: #<issue-number>` line (an open dependency), file with
`--label blocked` INSTEAD of `--label ready`: the harness flips `blocked` -> `ready`
automatically once every referenced issue is closed.

## Self-check before you emit
- Is every AC independently testable (a wrong impl makes a concrete test fail)?
- Are ALL rejection cases enumerated, not just the happy path?
- Is the persistence tier (slot 7) stated, so the test lands in the right gate?
- If any dependency (slot 3) is still open: does the body carry the exact `Blocked-by: #<n>`
  sentinel line(s), and is the filing command labeled `blocked` instead of `ready`?
- Could an adversarial reviewer, seeing ONLY this body + the diff, decide APPROVE vs
  REQUEST_CHANGES without guessing? If not, tighten it.
```

---

## Notes
- **Labels are load-bearing.** File as `ready` + `class-<n>`. The loop queries `ready`;
  `class-<n>` drives triage. Do NOT file as `needs-review`/`needs-human` (terminal labels).
- **Class-2/3 earn this prompt's keep.** #7 was class-1 (trivially airtight). Patron
  aggregate / borrow process-manager / return choreography will resist one-iteration scope —
  the grill must force a split when a story can't finish in a single cold pass.
- If the story depends on another issue (a prefactor slice, an earlier US), label it
  `blocked` instead of `ready` and put one line per dependency in the body, exactly:

      Blocked-by: #<issue-number>

  The harness machine-reads this sentinel: when every referenced issue is closed, the
  loop flips the label `blocked` -> `ready` automatically. Prose like "blocked by #14"
  is NOT recognized.
