export const meta = {
  name: 'issue-to-pr',
  description: 'Take a GitHub issue, analyze it, research the code, implement it TDD, adversarially review and fix in rounds, then commit, push and open the PR',
  whenToUse: 'Run when you want one labelled GitHub issue driven end to end to an open PR: analysis, optional code research, TDD implementation, up to 3 adversarial review and fix rounds, then branch, commit, push and PR.',
  phases: [
    { title: 'Analyze', detail: 'Opus reads the issue and states the contract', model: 'opus' },
    { title: 'Research', detail: 'Sonnet agents answer the open code questions', model: 'sonnet' },
    { title: 'Implement', detail: 'Opus implements test first, red green refactor', model: 'opus' },
    { title: 'Review', detail: 'Opus adversarially reviews the diff', model: 'opus' },
    { title: 'Fix', detail: 'One Sonnet agent per finding', model: 'sonnet' },
    { title: 'Ship', detail: 'Commit, push, open the PR' },
  ],
}

// ---------- input ----------

const input = typeof args === 'string' ? { issue: args } : (args || {})
const ISSUE = String(input.issue ?? '').trim()
if (!ISSUE) throw new Error('issue-to-pr needs an issue: pass args "42" or {issue: "42", ...}')

const TEST_CMD = input.testCommand || 'scala-cli test .'
const BASE = input.base || 'main'
const BRANCH = input.branch || `issue-${ISSUE.replace(/[^A-Za-z0-9._-]/g, '-')}`
const MAX_ROUNDS = Number(input.maxRounds ?? 3)
const MAX_FIXES_PER_ROUND = Number(input.maxFixesPerRound ?? 6)

const HOUSE_RULES = `
House rules for this repository, non negotiable:
- Read CLAUDE.md, CONVENTIONS.md, ARCHITECTURE.md and TEST.md before touching code, and obey them.
- Build and test with scala-cli, never sbt. Never add a build.sbt.
- The gate is: ${TEST_CMD}
- Everything under test/ stays Docker free, network free and credential free.
- Never use @nowarn or any other warning suppression. Fix the cause.
- Scaladoc explains WHY, never what the code does.
- Prose contains no dash characters.
`

// ---------- schemas ----------

const ANALYSIS_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['title', 'problem', 'acceptanceCriteria', 'outOfScope', 'researchQuestions', 'risks'],
  properties: {
    title: { type: 'string' },
    problem: { type: 'string', description: 'What the issue actually asks for, in your own words' },
    acceptanceCriteria: { type: 'array', items: { type: 'string' }, description: 'Observable, testable conditions' },
    outOfScope: { type: 'array', items: { type: 'string' } },
    researchQuestions: {
      type: 'array',
      description: 'Open questions about THIS codebase that must be answered before implementing. Empty if the issue is fully self contained.',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['question', 'why'],
        properties: { question: { type: 'string' }, why: { type: 'string' } },
      },
    },
    risks: { type: 'array', items: { type: 'string' } },
  },
}

const RESEARCH_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['question', 'answer', 'evidence'],
  properties: {
    question: { type: 'string' },
    answer: { type: 'string' },
    evidence: {
      type: 'array',
      description: 'file:line citations backing the answer',
      items: { type: 'string' },
    },
  },
}

const IMPL_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['summary', 'filesChanged', 'testsAdded', 'gatePassed', 'gateOutputTail', 'openConcerns'],
  properties: {
    summary: { type: 'string' },
    filesChanged: { type: 'array', items: { type: 'string' } },
    testsAdded: { type: 'array', items: { type: 'string' }, description: 'Test names or suite:test pairs written first, before the code' },
    gatePassed: { type: 'boolean' },
    gateOutputTail: { type: 'string', description: 'Last decisive lines of the gate output, verbatim' },
    openConcerns: { type: 'array', items: { type: 'string' } },
  },
}

const REVIEW_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['verdict', 'findings'],
  properties: {
    verdict: { type: 'string', enum: ['clean', 'changes-required'] },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['id', 'file', 'line', 'severity', 'category', 'problem', 'fix'],
        properties: {
          id: { type: 'string', description: 'Short stable slug, e.g. gate-swallows-exit-code' },
          file: { type: 'string' },
          line: { type: 'integer' },
          severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
          category: { type: 'string', description: 'correctness | threat-model | conventions | test-coverage | simplification' },
          problem: { type: 'string', description: 'One sentence naming the defect' },
          failureScenario: { type: 'string', description: 'Concrete inputs or state that produce the wrong result' },
          fix: { type: 'string', description: 'The smallest change that removes the defect' },
        },
      },
    },
  },
}

const FIX_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['id', 'applied', 'what', 'gatePassed'],
  properties: {
    id: { type: 'string' },
    applied: { type: 'boolean' },
    what: { type: 'string', description: 'What you changed, or why no change was needed' },
    gatePassed: { type: 'boolean' },
  },
}

const FIXES_SCHEMA = {
  // The StructuredOutput tool rejects a non object root schema, so the batch of fixes is wrapped.
  type: 'object',
  additionalProperties: false,
  required: ['fixes'],
  properties: {
    fixes: { type: 'array', items: FIX_SCHEMA, description: 'One entry per finding id you were given' },
  },
}

const SHIP_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['branch', 'commitSha', 'pushed', 'prUrl'],
  properties: {
    branch: { type: 'string' },
    commitSha: { type: 'string' },
    pushed: { type: 'boolean' },
    prUrl: { type: 'string' },
    notes: { type: 'string' },
  },
}

// ---------- 1. analyze ----------

phase('Analyze')
log(`issue ${ISSUE}: reading and analyzing`)

const analysis = await agent(
  `You are the analyst for GitHub issue ${ISSUE} in the repository in the current working directory.

Read the issue with: gh issue view ${ISSUE} --json number,title,body,labels,comments
Also read its comments, they often carry the real constraints.

${HOUSE_RULES}

Do NOT write any code and do NOT edit any file. Your whole job is to turn the issue into a
contract the implementer can be held to:
- what the issue actually asks for, stripped of narrative
- acceptance criteria that are observable and testable, one per line
- what is explicitly out of scope
- the open questions about THIS codebase whose answers change the implementation. Only list a
  question if you genuinely cannot answer it from the issue text alone. If the issue is self
  contained, return an empty researchQuestions array.
- risks: where this change could break the threat model or existing behaviour.`,
  { label: `analyze:issue-${ISSUE}`, phase: 'Analyze', model: 'opus', effort: 'high', schema: ANALYSIS_SCHEMA },
)

if (!analysis) throw new Error('analysis failed, nothing to implement')
log(`analysis: ${analysis.acceptanceCriteria.length} acceptance criteria, ${analysis.researchQuestions.length} research questions`)

// ---------- 2. research (only when the analyst asked for it) ----------

let research = []
if (analysis.researchQuestions.length) {
  phase('Research')
  const questions = analysis.researchQuestions.slice(0, 6)
  if (analysis.researchQuestions.length > questions.length) {
    log(`capped research at ${questions.length} of ${analysis.researchQuestions.length} questions`)
  }
  research = (await parallel(questions.map((q, i) => () =>
    agent(
      `Read only code research in the repository in the current working directory.

Question: ${q.question}
Why it matters: ${q.why}

Context, the issue being implemented:
${analysis.problem}

Answer the question from the code itself. Cite every claim as file:line. Do not edit any file,
do not propose a design, do not implement anything. If the answer is "no such thing exists",
say that and cite where it would have been.`,
      { label: `research:${i + 1}`, phase: 'Research', model: 'sonnet', effort: 'medium', schema: RESEARCH_SCHEMA },
    )
  ))).filter(Boolean)
  log(`research: ${research.length} questions answered`)
}

const researchBrief = research.length
  ? research.map(r => `Q: ${r.question}\nA: ${r.answer}\nEvidence: ${(r.evidence || []).join(', ')}`).join('\n\n')
  : 'No research was needed, the issue is self contained.'

const contract = `
ISSUE ${ISSUE}: ${analysis.title}

PROBLEM
${analysis.problem}

ACCEPTANCE CRITERIA
${analysis.acceptanceCriteria.map(c => `- ${c}`).join('\n')}

OUT OF SCOPE
${analysis.outOfScope.map(c => `- ${c}`).join('\n') || '- nothing stated'}

RISKS
${analysis.risks.map(c => `- ${c}`).join('\n') || '- none stated'}

RESEARCH FINDINGS
${researchBrief}
`

// ---------- 3. implement, test first ----------

phase('Implement')

const impl = await agent(
  `You are the implementer for GitHub issue ${ISSUE}.

${contract}

${HOUSE_RULES}

Work on a branch. First run: git checkout ${BASE} && git pull --ff-only && git checkout -b ${BRANCH}
If the branch already exists, check it out and continue on it.

Implement strictly test first, red green refactor, one acceptance criterion at a time:
1. Write ONE failing test that pins the next acceptance criterion. Run ${TEST_CMD} and SEE it fail.
   A test that passes before the code exists is not a test, delete it and write a real one.
2. Write the smallest code that makes it pass. Run ${TEST_CMD} and see it pass.
3. Refactor with the suite green.
4. Next criterion.

Do not commit, do not push, do not open a PR. Leave the work in the working tree on ${BRANCH}.
Finish with the whole suite green: run ${TEST_CMD} one last time and report the real result.
If the gate is red, report gatePassed false with the decisive output, do not claim success.`,
  { label: `implement:issue-${ISSUE}`, phase: 'Implement', model: 'opus', effort: 'high', schema: IMPL_SCHEMA },
)

if (!impl) throw new Error('implementation agent died, nothing to review')
log(`implement: ${impl.filesChanged.length} files, gate ${impl.gatePassed ? 'green' : 'RED'}`)

// ---------- 4 and 5. adversarial review, then one fixer per finding, up to MAX_ROUNDS ----------

const rounds = []
let lastReview = null
let converged = false

for (let round = 1; round <= MAX_ROUNDS; round++) {
  phase('Review')
  const priorRounds = rounds.length
    ? `\nPrevious rounds already fixed:\n${rounds.flatMap(r => r.fixes.map(f => `- ${f.id}: ${f.what}`)).join('\n')}\nDo not re report those unless the fix is wrong or incomplete.`
    : ''

  const review = await agent(
    `You are a COLD, ADVERSARIAL reviewer. You did not write this code and you do not trust it.

${contract}

Review the diff of branch ${BRANCH} against ${BASE}:
  git diff ${BASE}...HEAD
plus any uncommitted work:
  git status --porcelain && git diff

${HOUSE_RULES}
${priorRounds}

Judge against, in this order:
1. Correctness. Does it do what the acceptance criteria say, for the inputs it will actually see?
   Hunt for the wrong result, not the ugly line. Every finding needs a concrete failure scenario.
2. This repository's threat model and conventions.
3. Test quality. Do the tests fail when the code is wrong? Would they still pass with the
   implementation gutted? Missing coverage of a stated acceptance criterion is a finding.
4. Simplification. Only when the simpler form is plainly correct.

Run ${TEST_CMD} yourself. A claim of green that you did not observe is a blocker finding.

Report findings only, do not edit any file. Findings must be real defects, ranked most severe
first. Style opinions that change no behaviour are not findings. If the diff is genuinely sound,
return verdict "clean" with an empty findings array. Returning an empty array is a valid and
respectable outcome, do not invent work.`,
    { label: `review:round-${round}`, phase: 'Review', model: 'opus', effort: 'high', schema: REVIEW_SCHEMA },
  )

  lastReview = review
  if (!review) { log(`round ${round}: reviewer died, stopping the loop`); break }

  const findings = (review.findings || [])
  if (!findings.length || review.verdict === 'clean') {
    converged = true
    log(`round ${round}: review clean, leaving the loop`)
    rounds.push({ round, findings: [], fixes: [] })
    break
  }

  const todo = findings.slice(0, MAX_FIXES_PER_ROUND)
  if (findings.length > todo.length) {
    log(`round ${round}: ${findings.length} findings, fixing the top ${todo.length} this round, the rest carry to the next round`)
  } else {
    log(`round ${round}: ${todo.length} findings, dispatching one fixer each`)
  }

  // Findings in the same file are fixed by one agent, sequentially, so two fixers never
  // edit the same file at the same time. Distinct files run concurrently.
  phase('Fix')
  const byFile = new Map()
  for (const f of todo) {
    const key = f.file || 'unknown'
    if (!byFile.has(key)) byFile.set(key, [])
    byFile.get(key).push(f)
  }

  const fixes = (await parallel([...byFile.entries()].map(([file, group]) => () =>
    agent(
      `You are a fixer. Apply the review findings below to branch ${BRANCH}. Nothing else.

${contract}

${HOUSE_RULES}

Findings in ${file}, fix them in order:
${group.map(f => `
[${f.id}] ${f.severity} / ${f.category} at ${f.file}:${f.line}
Problem: ${f.problem}
Failure scenario: ${f.failureScenario || 'not given'}
Suggested fix: ${f.fix}`).join('\n')}

Rules:
- Touch ${file} and its tests. Do not refactor anything the findings do not name.
- For a correctness finding, first add or tighten a test that FAILS on the current code, then fix it.
- If a finding is wrong, say so in "what", set applied false, and change nothing. Do not fix
  something you cannot reproduce.
- Run ${TEST_CMD} before you finish and report the real result.
- Do not commit, do not push.

Return one entry per finding id you were given.`,
      { label: `fix:${file.split('/').pop()}`, phase: 'Fix', model: 'sonnet', effort: 'high', schema: FIXES_SCHEMA },
    )
  ))).filter(Boolean).flatMap(r => r.fixes || [])

  const applied = fixes.filter(f => f && f.applied).length
  log(`round ${round}: ${applied} of ${todo.length} findings fixed`)
  rounds.push({ round, findings: todo, fixes })

  if (round === MAX_ROUNDS) log(`hit the ${MAX_ROUNDS} round cap, shipping with whatever the last review left open`)
}

// Findings the last review raised that no fixer applied a change for.
const unresolved = converged
  ? []
  : ((lastReview && lastReview.findings) || []).filter(f => !rounds.some(r => r.fixes.some(x => x && x.id === f.id && x.applied)))

// Fixes made in the final round. No review ever saw them, so they are unverified, and the loop
// leaving without a clean verdict must never be reported to the shipper as a clean verdict.
const lastRound = rounds[rounds.length - 1]
const unverifiedFixes = converged ? [] : ((lastRound && lastRound.fixes) || []).filter(f => f && f.applied)

const reviewStatus = converged
  ? `The review loop converged: round ${lastRound ? lastRound.round : '?'} returned verdict clean with no findings. Say so in the PR body.`
  : `The review loop did NOT converge. It hit the ${MAX_ROUNDS} round cap with the last review still raising findings.
Do not claim the code was reviewed clean, in the PR body or anywhere else. Under a "Review status" heading in the PR body, state plainly that the loop hit the round cap and that the fixes from the last round were never re reviewed.
${unresolved.length ? `Under a "Known open findings" heading, list these, they are still unfixed:\n${unresolved.map(f => `- ${f.severity}: ${f.problem} (${f.file}:${f.line})`).join('\n')}` : 'No finding was left unfixed.'}
${unverifiedFixes.length ? `Under an "Unverified fixes" heading, list these, they were applied in the last round and no reviewer has judged them:\n${unverifiedFixes.map(f => `- ${f.id}: ${f.what}`).join('\n')}` : ''}`

// ---------- 7. commit, push, PR ----------

phase('Ship')

const ship = await agent(
  `Ship the work on branch ${BRANCH} for GitHub issue ${ISSUE}.

${contract}

${HOUSE_RULES}

1. Run ${TEST_CMD}. If it is RED, stop: do not commit, do not push, do not open a PR. Return
   pushed false, empty prUrl, and put the decisive failing output in notes.
2. Stage the work and commit with a Conventional Commit message. Subject line under 72 chars,
   body says WHY, and ends with the trailer:
   Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
   Reference the issue in the body as "Closes #${ISSUE}".
3. git push -u origin ${BRANCH}
4. Open the PR with gh pr create --base ${BASE} --head ${BRANCH}. The body is written for human
   reviewers, in normal English prose with no dash characters: what changed, why, how it was
   tested, and "Closes #${ISSUE}". End the body with:
   🤖 Generated with [Claude Code](https://claude.com/claude-code)

${reviewStatus}

Report the real commit sha and the real PR url. Never invent either.`,
  { label: `ship:issue-${ISSUE}`, phase: 'Ship', model: 'opus', effort: 'medium', schema: SHIP_SCHEMA },
)

return {
  issue: ISSUE,
  branch: BRANCH,
  analysis,
  research,
  implementation: impl,
  reviewRounds: rounds.map(r => ({ round: r.round, findings: r.findings.length, fixed: r.fixes.filter(f => f && f.applied).length })),
  finalVerdict: converged ? 'clean' : 'changes-required-at-round-cap',
  converged,
  unresolvedFindings: unresolved,
  unverifiedFixes,
  ship,
}
