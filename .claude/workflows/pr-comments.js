export const meta = {
  name: 'pr-comments',
  description: 'Read the open review comments on a GitHub PR, verify each one, fix the ones that hold, verify the fix, then reply and resolve the thread',
  whenToUse: 'Run when a PR has unresolved review comments and you want each one adjudicated, fixed test first, independently verified, pushed, and answered in its own thread.',
  phases: [
    { title: 'Collect', detail: 'Read the unresolved review threads on the PR' },
    { title: 'Adjudicate', detail: 'Opus decides whether each comment holds', model: 'opus' },
    { title: 'Fix', detail: 'Sonnet fixes the comments that hold, test first', model: 'sonnet' },
    { title: 'Verify', detail: 'Opus verifies each fix against the comment', model: 'opus' },
    { title: 'Push', detail: 'Commit the fixes and push to the PR branch' },
    { title: 'Answer', detail: 'Reply in each thread and resolve the ones that were addressed' },
  ],
}

// ---------- input ----------

const input = typeof args === 'string' || typeof args === 'number' ? { pr: args } : (args || {})
const PR = String(input.pr ?? '').trim() // empty means: the PR of the current branch
const TEST_CMD = input.testCommand || 'scala-cli test .'
const MAX_THREADS = Number(input.maxThreads ?? 8)
const INCLUDE_OUTDATED = Boolean(input.includeOutdated ?? false)

const PR_REF = PR ? `PR #${PR}` : 'the PR of the current branch'
const PR_ARG = PR || '' // gh defaults to the current branch when the number is omitted

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

const THREADS_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['owner', 'repo', 'number', 'headBranch', 'baseBranch', 'threads'],
  properties: {
    owner: { type: 'string' },
    repo: { type: 'string' },
    number: { type: 'integer' },
    headBranch: { type: 'string' },
    baseBranch: { type: 'string' },
    threads: {
      type: 'array',
      description: 'One entry per UNRESOLVED review thread, oldest first',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['threadId', 'replyToCommentId', 'author', 'path', 'line', 'body'],
        properties: {
          threadId: { type: 'string', description: 'GraphQL node id of the review thread, needed by resolveReviewThread' },
          replyToCommentId: { type: 'integer', description: 'databaseId of the FIRST comment in the thread, needed by the replies REST endpoint' },
          author: { type: 'string' },
          path: { type: 'string' },
          line: { type: 'integer', description: 'line the thread is anchored to, 0 when GitHub reports null' },
          isOutdated: { type: 'boolean' },
          body: { type: 'string', description: 'Full text of the first comment, verbatim' },
          laterComments: { type: 'string', description: 'The rest of the thread, author and body, concatenated. Empty when the thread has one comment.' },
          diffHunk: { type: 'string', description: 'The diff hunk the comment is anchored to' },
        },
      },
    },
  },
}

const VERDICT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['threadId', 'holds', 'reasoning', 'confidence'],
  properties: {
    threadId: { type: 'string' },
    holds: { type: 'boolean', description: 'true when the comment names a real defect or a real improvement that this repository should make' },
    reasoning: { type: 'string', description: 'Why it holds or why it does not, citing file:line evidence you actually read' },
    evidence: { type: 'array', items: { type: 'string' }, description: 'file:line citations backing the reasoning' },
    failureScenario: { type: 'string', description: 'When it holds: concrete inputs or state that produce the wrong result. Empty when the comment is a pure conventions or readability point.' },
    suggestedFix: { type: 'string', description: 'When it holds: the smallest change that answers the comment' },
    confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
  },
}

const FIX_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['threadId', 'applied', 'what', 'gatePassed'],
  properties: {
    threadId: { type: 'string' },
    applied: { type: 'boolean' },
    what: { type: 'string', description: 'What you changed, or why no change was needed' },
    testFirst: { type: 'boolean', description: 'true when a test was written that failed before the change and passes after' },
    testsTouched: { type: 'array', items: { type: 'string' } },
    filesChanged: { type: 'array', items: { type: 'string' } },
    gatePassed: { type: 'boolean' },
    gateOutputTail: { type: 'string', description: 'Last decisive lines of the gate output, verbatim' },
  },
}

const FIXES_SCHEMA = {
  // The StructuredOutput tool rejects a non object root schema, so the batch of fixes is wrapped.
  type: 'object',
  additionalProperties: false,
  required: ['fixes'],
  properties: {
    fixes: { type: 'array', items: FIX_SCHEMA, description: 'One entry per threadId you were given' },
  },
}

const VERIFY_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['threadId', 'addressed', 'assessment'],
  properties: {
    threadId: { type: 'string' },
    addressed: { type: 'boolean', description: 'true only when the diff actually answers the reviewer comment and the gate is green' },
    assessment: { type: 'string', description: 'What the diff does and whether it answers the comment' },
    problems: { type: 'array', items: { type: 'string' }, description: 'Defects introduced by the fix, or ways it falls short. Empty when it is sound.' },
    gateObserved: { type: 'boolean', description: 'true only when YOU ran the gate and saw it green' },
  },
}

const PUSH_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['committed', 'pushed', 'commitSha'],
  properties: {
    committed: { type: 'boolean' },
    pushed: { type: 'boolean' },
    commitSha: { type: 'string', description: 'Empty when nothing was committed' },
    notes: { type: 'string' },
  },
}

const ANSWER_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['threadId', 'replied', 'resolved'],
  properties: {
    threadId: { type: 'string' },
    replied: { type: 'boolean' },
    resolved: { type: 'boolean' },
    replyBody: { type: 'string', description: 'The reply you actually posted' },
    error: { type: 'string' },
  },
}

// ---------- 1. collect the open threads ----------

phase('Collect')
log(`reading the unresolved review threads on ${PR_REF}`)

const collected = await agent(
  `Read the open review comments on ${PR_REF} in the repository in the current working directory.

Steps:
1. Identify the PR: gh pr view ${PR_ARG} --json number,headRefName,baseRefName,url,title
   and the repository: gh repo view --json owner,name
2. Fetch every review thread with GraphQL. reviewThreads is the only place the resolved flag and
   the thread node id live, so the REST comments endpoint is not enough:

   gh api graphql -f query='
     query($owner:String!, $repo:String!, $pr:Int!) {
       repository(owner:$owner, name:$repo) {
         pullRequest(number:$pr) {
           reviewThreads(first: 100) {
             nodes {
               id
               isResolved
               isOutdated
               path
               line
               comments(first: 50) {
                 nodes { databaseId author { login } body diffHunk }
               }
             }
           }
         }
       }
     }' -F owner=OWNER -F repo=REPO -F pr=NUMBER

3. Keep ONLY threads where isResolved is false.${INCLUDE_OUTDATED ? '' : ' Drop threads where isOutdated is true, they point at code that no longer exists.'}
4. For each kept thread report: the thread node id, the databaseId of the FIRST comment in the
   thread, its author login, path, line (use 0 when null), the first comment body verbatim, the
   remaining comments concatenated as "author: body", and the diff hunk.

Do not edit any file. Do not post anything. Do not resolve anything. This step only reads.
If there are no unresolved threads, return an empty threads array, that is a valid outcome.`,
  { label: `collect:${PR_REF}`, phase: 'Collect', model: 'sonnet', effort: 'medium', schema: THREADS_SCHEMA },
)

if (!collected) throw new Error('could not read the PR review threads')

const allThreads = collected.threads || []
const threads = allThreads.slice(0, MAX_THREADS)
if (allThreads.length > threads.length) {
  log(`capped at ${threads.length} of ${allThreads.length} unresolved threads, the rest are untouched and still open`)
}

if (!threads.length) {
  log('no unresolved review threads, nothing to do')
  return { pr: collected.number, owner: collected.owner, repo: collected.repo, threads: 0, results: [] }
}

log(`${threads.length} unresolved thread${threads.length === 1 ? '' : 's'} on PR #${collected.number} (${collected.headBranch})`)

const byId = new Map(threads.map(t => [t.threadId, t]))
const describe = t => `Thread ${t.threadId}
Author: ${t.author}
Location: ${t.path}:${t.line || '?'}${t.isOutdated ? ' (outdated)' : ''}
Comment:
${t.body}
${t.laterComments ? `\nLater in the thread:\n${t.laterComments}` : ''}
${t.diffHunk ? `\nDiff hunk:\n${t.diffHunk}` : ''}`

// ---------- 2. adjudicate every comment, in parallel, read only ----------

phase('Adjudicate')

const verdicts = (await parallel(threads.map(t => () =>
  agent(
    `You are a cold adjudicator on PR #${collected.number}. A reviewer left the comment below.
Decide whether it HOLDS against the code as it stands right now. You are not the author and you
owe the reviewer nothing: a comment based on a misreading of the code does not hold.

${describe(t)}

${HOUSE_RULES}

Method:
- Read ${t.path} and everything it depends on. Read the PR diff: git diff ${collected.baseBranch}...HEAD
- Check the claim against what the code actually does today, not against what the diff looked like
  when the comment was written. The comment may already be fixed, in which case it does not hold.
- A comment holds when it names a real defect, a real threat model or conventions violation, or a
  real missing test. A comment does not hold when it is wrong, already addressed, out of the scope
  of this PR, or a pure style preference that changes no behaviour and no stated convention.
- When it holds, give the concrete failure scenario and the smallest fix that answers it.

Do NOT edit any file. Do NOT post anything to GitHub. Report the verdict only.
Return holds false with your reasoning when it does not hold. That is a respectable outcome.`,
    { label: `adjudicate:${t.path.split('/').pop()}:${t.line || 0}`, phase: 'Adjudicate', model: 'opus', effort: 'high', schema: VERDICT_SCHEMA },
  )
))).filter(Boolean)

const verdictById = new Map(verdicts.map(v => [v.threadId, v]))
const holding = verdicts.filter(v => v.holds && byId.has(v.threadId))
log(`adjudicated: ${holding.length} of ${verdicts.length} comments hold`)

// ---------- 3. fix the ones that hold, one agent per file so no two agents edit the same file ----------

const fixById = new Map()

if (holding.length) {
  phase('Fix')
  const byFile = new Map()
  for (const v of holding) {
    const file = byId.get(v.threadId).path || 'unknown'
    if (!byFile.has(file)) byFile.set(file, [])
    byFile.get(file).push(v)
  }
  log(`dispatching ${byFile.size} fixer${byFile.size === 1 ? '' : 's'} over ${holding.length} comment${holding.length === 1 ? '' : 's'}`)

  const fixes = (await parallel([...byFile.entries()].map(([file, group]) => () =>
    agent(
      `You are a fixer on PR #${collected.number}, branch ${collected.headBranch}. Answer the reviewer
comments below in ${file}. Nothing else.

${HOUSE_RULES}

${group.map(v => `--- thread ${v.threadId} ---
${describe(byId.get(v.threadId))}

Adjudicator verdict: HOLDS.
Reasoning: ${v.reasoning}
Failure scenario: ${v.failureScenario || 'not applicable, this is a conventions or readability point'}
Suggested fix: ${v.suggestedFix || 'not given, work out the smallest change yourself'}`).join('\n\n')}

Rules:
- Work test first wherever the comment is about behaviour: write or tighten a test that FAILS on the
  current code, run ${TEST_CMD} and SEE it fail, then make it pass, then refactor with the suite green.
  Set testFirst true only when you actually observed the red. For a pure conventions, naming or
  documentation comment there is nothing to red green, set testFirst false and say so in "what".
- Touch ${file} and its tests. Do not refactor anything the comments do not name.
- Fix the comments in the order given, one at a time.
- Run ${TEST_CMD} before you finish and report the real result. Never claim a green you did not see.
- Do NOT commit, do NOT push, do NOT post anything to GitHub. Leave the work in the working tree.

Return one entry per threadId you were given.`,
      { label: `fix:${file.split('/').pop()}`, phase: 'Fix', model: 'sonnet', effort: 'high', schema: FIXES_SCHEMA },
    )
  ))).filter(Boolean).flatMap(r => r.fixes || [])

  for (const f of fixes) if (f && f.threadId) fixById.set(f.threadId, f)
  log(`fixed: ${fixes.filter(f => f && f.applied).length} of ${holding.length}`)
}

// ---------- 4. verify each fix independently ----------

const verifyById = new Map()
const applied = holding.filter(v => (fixById.get(v.threadId) || {}).applied)

if (applied.length) {
  phase('Verify')
  const verifications = (await parallel(applied.map(v => () => {
    const t = byId.get(v.threadId)
    const f = fixById.get(v.threadId)
    return agent(
      `You are an independent verifier on PR #${collected.number}. You did not write the fix and you
do not trust it. Judge whether the working tree now ANSWERS the reviewer comment below.

${describe(t)}

Adjudicator said it holds because: ${v.reasoning}
The fixer claims: ${f.what}
Files it says it changed: ${(f.filesChanged || []).join(', ') || 'none reported'}
Tests it says it touched: ${(f.testsTouched || []).join(', ') || 'none reported'}

${HOUSE_RULES}

Method:
- Read the current state of the code, not the fixer's summary: git status --porcelain && git diff
- Ask whether the change actually removes the problem the reviewer named, for the inputs the code
  will really see. A change that renames the symptom is not a fix.
- Judge the test: would it still pass with the fix reverted? If yes, the coverage is fake, that is
  a problem. Skip this check only when the comment was a pure conventions or documentation point.
- Check the fix introduced no new defect and broke no existing behaviour.
- Run ${TEST_CMD} yourself. Set gateObserved true only when you ran it and saw it green.

Do NOT edit any file. Do NOT post anything to GitHub. Report the judgement only.
Set addressed true only when the comment is genuinely answered and the gate is green.`,
      { label: `verify:${t.path.split('/').pop()}:${t.line || 0}`, phase: 'Verify', model: 'opus', effort: 'high', schema: VERIFY_SCHEMA },
    )
  }))).filter(Boolean)

  for (const v of verifications) if (v && v.threadId) verifyById.set(v.threadId, v)
  log(`verified: ${verifications.filter(v => v.addressed).length} of ${applied.length} fixes answer their comment`)
}

// ---------- 5. commit and push, so the replies can cite a real sha ----------

// Every applied fix is committed, not only the verified ones. A fix the verifier called short is
// still a change in the working tree, and leaving it uncommitted would strand it on the machine
// while its thread gets a reply saying work was done.
const toCommit = holding.filter(v => (fixById.get(v.threadId) || {}).applied)
let shipped = { committed: false, pushed: false, commitSha: '', notes: 'no fix was applied, there was nothing to commit' }

if (toCommit.length) {
  phase('Push')
  const shortfalls = toCommit.filter(v => !(verifyById.get(v.threadId) || {}).addressed)
  shipped = await agent(
    `Commit and push the review comment fixes on branch ${collected.headBranch} of PR #${collected.number}.
This is the step that gets the work off this machine and onto the PR, it is not optional.

${HOUSE_RULES}

1. Run ${TEST_CMD}. If it is RED, stop: do not commit, do not push. Return committed false, pushed
   false, empty commitSha, and put the decisive failing output in notes.
2. Stage the work and commit with a Conventional Commit message. Subject line under 72 chars, body
   says WHY in normal English prose with no dash characters, and ends with the trailer:
   Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
   The body summarises the review comments answered:
${toCommit.map(v => `   - ${byId.get(v.threadId).path}: ${(fixById.get(v.threadId) || {}).what || v.suggestedFix}`).join('\n')}
${shortfalls.length ? `   Under a "Not fully answered" heading in the body, name these, an independent verifier judged them incomplete:\n${shortfalls.map(v => `   - ${byId.get(v.threadId).path}: ${((verifyById.get(v.threadId) || {}).problems || []).join('; ') || 'the fix was never verified'}`).join('\n')}` : ''}
3. git push origin ${collected.headBranch}
4. Confirm the push landed: git status -sb should report no ahead count, and
   git rev-parse HEAD must equal git rev-parse origin/${collected.headBranch}.
   Report pushed true only when you observed that. If the push was rejected because the remote
   moved, pull with rebase and push again, then check the same way.

Report the real commit sha. Never invent one. If the working tree is clean because the fixers
changed nothing, return committed false with that in notes.`,
    { label: `push:${collected.headBranch}`, phase: 'Push', model: 'opus', effort: 'medium', schema: PUSH_SCHEMA },
  ) || { committed: false, pushed: false, commitSha: '', notes: 'the push agent died' }
  log(`push: ${shipped.pushed ? `pushed ${shipped.commitSha}` : `NOT pushed, ${shipped.notes || 'unknown reason'}`}`)
}

// ---------- 6. reply in every thread, resolve only the ones actually answered ----------

phase('Answer')

const shaLine = shipped.pushed && shipped.commitSha ? `The fix is in commit ${shipped.commitSha} on this branch.` : ''

const answers = (await parallel(threads.map(t => () => {
  const v = verdictById.get(t.threadId)
  const f = fixById.get(t.threadId)
  const chk = verifyById.get(t.threadId)
  const resolved = Boolean(chk && chk.addressed)

  const brief = !v
    ? `The adjudicator died before reaching this comment, so nothing was decided and nothing was changed.
Reply saying plainly that this comment was not processed and is left open. Do NOT resolve the thread.`
    : !v.holds
    ? `Verdict: the comment does NOT hold, and nothing was changed.
Reasoning to convey: ${v.reasoning}
Evidence: ${(v.evidence || []).join(', ') || 'none cited'}
Reply respectfully explaining why the code is correct as it stands, citing the evidence, and invite
the reviewer to push back if they still disagree. Do NOT resolve the thread, the reviewer decides.`
    : resolved
    ? `Verdict: the comment holds, it was fixed and independently verified.
What changed: ${f.what}
Tests touched: ${(f.testsTouched || []).join(', ') || 'none'}
Verifier assessment: ${chk.assessment}
${shaLine}
Reply describing what changed and how it was tested, then RESOLVE the thread.`
    : `Verdict: the comment holds, but it is NOT fully answered.
${f ? `Fixer said: ${f.what} (applied ${f.applied}, gate ${f.gatePassed ? 'green' : 'RED'})` : 'No fix was produced.'}
${chk ? `Verifier said: ${chk.assessment}. Problems: ${(chk.problems || []).join('; ') || 'none listed'}` : 'The fix was never verified.'}
Reply saying plainly what was attempted and what is still open. Do NOT claim it is fixed.
Do NOT resolve the thread.`

  return agent(
    `Answer one review thread on PR #${collected.number} of ${collected.owner}/${collected.repo}.

${describe(t)}

${brief}

Write the reply for human reviewers, in normal English prose with no dash characters. Short, two to
five sentences, factual, no marketing, no apology theatre. Do not restate the whole comment back.

Post it as a reply IN THIS THREAD, not as a new top level comment:
  gh api repos/${collected.owner}/${collected.repo}/pulls/${collected.number}/comments/${t.replyToCommentId}/replies -f body='...'

${resolved ? `Then resolve the thread:
  gh api graphql -f query='mutation($id:ID!){ resolveReviewThread(input:{threadId:$id}){ thread { isResolved } } }' -f id='${t.threadId}'
Report resolved true only when the mutation actually returned isResolved true.` : 'Do NOT resolve this thread. Report resolved false.'}

Do NOT edit any file. Do NOT commit or push. Report exactly what you posted, and put any API error
verbatim in the error field. Never report replied true for a call that failed.`,
    { label: `answer:${t.path.split('/').pop()}:${t.line || 0}`, phase: 'Answer', model: 'sonnet', effort: 'medium', schema: ANSWER_SCHEMA },
  )
}))).filter(Boolean)

const answerById = new Map(answers.map(a => [a.threadId, a]))
log(`answered: ${answers.filter(a => a.replied).length} replied, ${answers.filter(a => a.resolved).length} resolved`)

// ---------- report ----------

return {
  pr: collected.number,
  repo: `${collected.owner}/${collected.repo}`,
  branch: collected.headBranch,
  unresolvedThreadsFound: allThreads.length,
  threadsProcessed: threads.length,
  skippedForCap: Math.max(0, allThreads.length - threads.length),
  commit: shipped.pushed ? shipped.commitSha : null,
  pushNotes: shipped.pushed ? '' : shipped.notes,
  results: threads.map(t => {
    const v = verdictById.get(t.threadId)
    const f = fixById.get(t.threadId)
    const chk = verifyById.get(t.threadId)
    const a = answerById.get(t.threadId)
    return {
      thread: t.threadId,
      where: `${t.path}:${t.line || '?'}`,
      author: t.author,
      comment: t.body.slice(0, 200),
      holds: v ? v.holds : null,
      reasoning: v ? v.reasoning : 'not adjudicated',
      fixed: f ? f.applied : false,
      whatChanged: f ? f.what : null,
      testFirst: f ? Boolean(f.testFirst) : false,
      verified: chk ? chk.addressed : null,
      verifierProblems: chk ? (chk.problems || []) : [],
      replied: a ? a.replied : false,
      resolved: a ? a.resolved : false,
      error: a ? a.error : 'no answer agent result',
    }
  }),
}
