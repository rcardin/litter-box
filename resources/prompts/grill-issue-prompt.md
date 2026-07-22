# Grill-a-story-into-a-harness-issue prompt

Reusable interrogation prompt for turning a raw user story into a single, self-contained GitHub
issue the autonomous Ralph-loop harness can implement **unattended**.

## Why this exists
The harness splices the issue body verbatim into three fresh, no-memory agent tasks: the WORKER
(`iterate-prompt.md`), the FIXER (`fix-prompt.md`), and the ADVERSARIAL REVIEWER
(`review-prompt.md`, as "the acceptance criteria this diff must satisfy"). None can ask a human
anything. So the body is the **sole** spec — self-contained, every acceptance criterion
independently testable.

File the result with the `ready` label your litter-box config names; the loop picks up `ready`
issues.

Pairs with the `grilling` skill for the interrogation itself.

---

## The prompt

```markdown
You are grilling me to turn a raw user story into a single, self-contained GitHub issue that an
autonomous Ralph-loop harness will implement UNATTENDED. You produce nothing until you have
interrogated me enough to fill every slot below. Ask sharp, specific questions; push back on
vagueness; do not invent requirements I did not confirm.

## Why this issue must be airtight
The harness splices this issue body verbatim into three fresh, no-memory agent tasks: the WORKER
that implements it, the FIXER that repairs it, and an ADVERSARIAL REVIEWER that tries to reject
it. None of them can ask me anything. So the body is the only spec, and every acceptance
criterion must be phrased so a test that FAILS-if-wrong is derivable from it. An acceptance
criterion that cannot become a failing test is a defect.

## What the harness ALREADY enforces — do NOT restate, only point to it
The worker, fixer and reviewer prompts already carry the protocol: one iteration with no memory;
no branch switching, pushing, PR opening or `gh` commands; a test for every acceptance criterion;
never weaken, delete or silence a test to go green; the fast tier gates each iteration while the
slow tier is left to CI. They also carry this project's conventions in full, reproduced below.
The issue REFERENCES all of that, it does not re-teach it.

## This project's conventions

{{CONVENTIONS}}

## Interrogate me until you can fill every slot
1. **Identity and value.** A story identifier, a one-line title, and the "As a / I want / so
   that".
2. **Scope.** Can this be finished by ONE cold iteration with no memory? If it cannot, say so and
   tell me to split it. An issue too big for a single pass is the most common cause of a loop
   that churns.
3. **Dependencies.** Which prior story must be done first, and is it done?
4. **Behaviour.** The exact inputs and the exact outcome for each. Enumerate the REJECTION and
   error cases explicitly, not just the happy path — those are the criteria that catch
   silent-success bugs.
5. **Contract at the boundary.** Whatever this project exposes to a caller: the signature, the
   input validation, and the FULL map of outcome to response, success and failure alike.
6. **Test tier.** Does any acceptance criterion need external infrastructure — a database, a
   container, the network, a credential? If YES it belongs in the slow tier that CI runs. If NO
   it belongs in the fast tier that gates every iteration. The conventions above say which
   directory is which. Default to the fast tier and justify anything else.
7. **Non-goals.** What is explicitly OUT of scope, so the worker does not scope-creep and the
   reviewer does not reject for absent, unrequested behaviour.

## When you have every answer, output EXACTLY this
First the issue body, in this literal shape:

    ## <identifier> — <title>

    **As a** <role>, **I want to** <capability> **so that** <value>.

    **Scope:** <one-line rationale that this fits one cold iteration>.

    **Dependencies:** <prior story + status>.

    <ONLY if a dependency issue is still open — one line per open dependency, exactly this
    shape (the harness machine-reads it; prose like "blocked by #14" is NOT recognized):>
    Blocked-by: #<issue-number>

    ### Context
    <The one existing feature, by path, whose shape this work should copy, plus any convention
    from above that this issue specifically leans on.>
    <Which test tier covers this work, per slot 6.>

    ### Acceptance criteria
    1. <discrete, testable>
    2. <happy path>
    3. <rejection case>
    4. <error case>
    5. <the boundary contract: full outcome map>
    6. <tests: which criterion is covered at which tier>

    ### Non-goals
    - <explicitly out of scope>

    ### Done criteria
    - The fast gate is green.
    - Every acceptance criterion maps to at least one test.

Then the exact command to file it, labelled so the loop will pick it up:

    gh issue create --title "<identifier> — <title>" --label ready --body-file <file>

If the body carries any `Blocked-by: #<issue-number>` line (an open dependency), file with
`--label blocked` INSTEAD of `--label ready`: the harness flips `blocked` -> `ready`
automatically once every referenced issue is closed.

## Self-check before you emit
- Is every acceptance criterion independently testable (a wrong implementation makes a concrete
  test fail)?
- Are ALL rejection and error cases enumerated, not just the happy path?
- Is the test tier (slot 6) stated, so the test lands in the right gate?
- If any dependency (slot 3) is still open: does the body carry the exact `Blocked-by: #<n>`
  sentinel line(s), and is the filing command labelled `blocked` instead of `ready`?
- Could an adversarial reviewer, seeing ONLY this body and the diff, decide APPROVE vs
  REQUEST_CHANGES without guessing? If not, tighten it.
```

---

## Notes
- **Labels are load-bearing.** File as the `ready` label your config names. Do not file as a
  terminal label such as `needs-review` or `needs-human`: the loop will never pick those up.
- **Big stories earn this prompt's keep.** A story that cannot finish in a single cold pass will
  resist every attempt to make it airtight — the grill must force a split rather than paper over
  the size.
- If the story depends on another issue, label it `blocked` instead of `ready` and put one line
  per dependency in the body, exactly:

      Blocked-by: #<issue-number>

  The harness machine-reads this sentinel: when every referenced issue is closed, the
  loop flips the label `blocked` -> `ready` automatically. Prose like "blocked by #14"
  is NOT recognized.
