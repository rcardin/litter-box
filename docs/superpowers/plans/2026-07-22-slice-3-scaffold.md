# Slice 3: scaffold — `init`, layered prompts, base image — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make litter-box usable by a repo that is not litter-box — a scaffolding command, prompt
skeletons that ship with the tool instead of the consumer, and a gate image with no build tool
baked in.

**Architecture:** Prompt skeletons move from the consumer's `prompts/` to the tool's classpath
resources, with a `.litter-box/prompts/` disk override that `eject` populates. `Main` grows an
argument dispatcher in front of the existing loop, routing to a new `Init` scaffolder or to
`Prompts.eject`. The sandbox Dockerfile splits into a build-tool-free base image and a thin sbt
layer on top.

**Tech Stack:** Scala 3.8.3, scala-cli, typesafe-config, ScalaTest 3.2.20 (`AnyFlatSpec` with
`Matchers`), Docker, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-07-22-slice-3-scaffold-design.md`

## Global Constraints

- **No new dependencies.** The project has exactly one runtime dep (`com.typesafe:config`). The CLI
  parser is hand-rolled. Do not add mainargs, decline, scopt, or anything else.
- **Package is `in.rcard.litterbox`** for every new source and test file.
- **Test suite stays Docker-free, network-free and credential-free.** CI runs `scala-cli test .`
  with nothing else installed. Any test needing Docker belongs under `sandbox/test/` as a shell
  script, run manually.
- **Scaladoc says *why*, not *what*.** Every file in `src/` documents the reasoning behind a
  decision, not a restatement of the code. Match the density in `Settings.scala` and `Live.scala`.
- **Prose contains no dash characters.** Applies to scaladoc, markdown, and prompt text.
- **Never scaffold a preset we have not run a loop against.** sbt is the only known build tool.
  Anything else gets a TODO and a warning, never a guess.
- **The scaffolded image reference is** `ghcr.io/rcardin/litter-box-base:0.1.0`.
- **Full test command:** `scala-cli test .`
  **Single spec:** `scala-cli test . --test-only 'in.rcard.litterbox.CliSpec'`

## File Structure

**Created:**

| Path | Responsibility |
|---|---|
| `src/Cli.scala` | Pure argument parser. `Command` enum plus usage text. No IO. |
| `src/Prompts.scala` | Prompt skeleton lookup (disk override, else classpath) and `eject`. |
| `src/Init.scala` | The scaffolder: pure `plan`/`warnings` plus a thin IO `run`. |
| `resources/prompts/*.md` | The four neutral protocol skeletons, shipped in the artifact. |
| `resources/scaffold/*` | The file templates `init` writes into a consumer's `.litter-box/`. |
| `sandbox/base.Dockerfile` | Build-tool-free base image: JDK, Claude CLI, `gate` user. |
| `.github/workflows/base-image.yml` | Publishes the base image on a tag push. |
| `docs/base-image.md` | The contract the base image guarantees. |
| `test/CliSpec.scala` | Parser table. |
| `test/PromptsSpec.scala` | Resolution order, eject, slot coverage, protocol survival. |
| `test/InitSpec.scala` | Scaffold contents, `--force` refusal, non-sbt path. |

**Modified:**

| Path | Change |
|---|---|
| `project.scala` | Add `//> using resourceDir ./resources`. |
| `src/Domain.scala:91-97` | `Template` gains `GrillIssue`. |
| `src/Live.scala:59-63` | `LiveHarnessFs.readTemplate` delegates to `Prompts.resolve`. |
| `src/Machine.scala:172-180, 238-248, 307-318` | Splice the config-derived slots at all three sites. |
| `src/Main.scala:233-244, 300-305` | Argument dispatch; preflight no longer requires `prompts/`. |
| `sandbox/Dockerfile` | Shrinks to `FROM ${BASE_IMAGE}` plus sbt plus `ENTRYPOINT`. |
| `sandbox/build-image.sh` | Builds the base image before the gate image. |
| `sandbox/build-image.sh` | Builds the base image first; stages `.litter-box/allowlist` into the proxy build context when present. |

**Deleted:** `prompts/` (the four files move to `resources/prompts/`, rewritten).

---

### Task 1: Prompt skeletons move to classpath resources

Move the four prompt files into the artifact and give them a disk override. Content is unchanged in
this task; Task 3 rewrites it. Splitting the move from the rewrite keeps a reviewer able to see
each independently.

**Files:**
- Create: `src/Prompts.scala`
- Create: `resources/prompts/iterate-prompt.md`, `resources/prompts/fix-prompt.md`, `resources/prompts/review-prompt.md`, `resources/prompts/grill-issue-prompt.md` (`git mv` from `prompts/`)
- Modify: `project.scala`, `src/Domain.scala:91-97`, `src/Live.scala:59-63`, `src/Main.scala:300-305`
- Test: `test/PromptsSpec.scala`

**Interfaces:**
- Consumes: `Template` (Domain.scala), `Machine.PromptDir` (about to lose its last reader).
- Produces:
  ```scala
  object Prompts:
    val EjectDir: String = ".litter-box/prompts"
    def builtIn(t: Template): String
    def resolve(root: java.nio.file.Path, t: Template): String
    def parseName(what: String): Either[String, Template]
    def eject(root: java.nio.file.Path, what: String, force: Boolean): Either[String, java.nio.file.Path]
  ```
  `Template` gains `case GrillIssue extends Template("grill-issue-prompt.md")`.

- [ ] **Step 1: Move the prompt files and register the resource directory**

```bash
mkdir -p resources/prompts
git mv prompts/iterate-prompt.md      resources/prompts/iterate-prompt.md
git mv prompts/fix-prompt.md          resources/prompts/fix-prompt.md
git mv prompts/review-prompt.md       resources/prompts/review-prompt.md
git mv prompts/grill-issue-prompt.md  resources/prompts/grill-issue-prompt.md
rmdir prompts
```

Append to `project.scala`, after the `//> using options` line:

```scala
// The prompt skeletons ship in the artifact, not in the consumer repo: a consumer who carries a
// copy of the protocol carries a copy that silently rots when the tool updates. `Prompts.resolve`
// reads them from here and lets `.litter-box/prompts/` override per file.
//> using resourceDir ./resources
```

- [ ] **Step 2: Write the failing test**

Create `test/PromptsSpec.scala`:

```scala
package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Unit tests for the prompt layer.
  *
  * THE RULE THIS SPEC IS WRITTEN TO (GitHub issue #4): the tool owns the protocol and the consumer
  * owns only its conventions. So every assertion below is about which of the two wins where, and
  * about the protocol lines surviving a consumer who supplies nothing at all.
  */
class PromptsSpec extends AnyFlatSpec with Matchers:

  private def tempRoot(): Path = Files.createTempDirectory("prompts-spec")

  private def write(p: Path, s: String): Path =
    Option(p.getParent).foreach(Files.createDirectories(_))
    Files.write(p, s.getBytes(StandardCharsets.UTF_8))

  "builtIn" should "read every template out of the classpath" in:
    Template.values.foreach { t =>
      Prompts.builtIn(t) should not be empty
    }

  "resolve" should "return the built-in when the repo has no override" in:
    val root = tempRoot()
    Prompts.resolve(root, Template.Iterate) shouldBe Prompts.builtIn(Template.Iterate)

  it should "prefer an ejected file over the built-in" in:
    val root = tempRoot()
    write(root.resolve(Prompts.EjectDir).resolve("iterate-prompt.md"), "MINE")
    Prompts.resolve(root, Template.Iterate) shouldBe "MINE"

  it should "override one template without affecting the others" in:
    val root = tempRoot()
    write(root.resolve(Prompts.EjectDir).resolve("fix-prompt.md"), "MINE")
    Prompts.resolve(root, Template.Fix) shouldBe "MINE"
    Prompts.resolve(root, Template.Iterate) shouldBe Prompts.builtIn(Template.Iterate)

  "parseName" should "accept a bare name and a prompts/-prefixed one" in:
    Prompts.parseName("iterate-prompt.md") shouldBe Right(Template.Iterate)
    Prompts.parseName("prompts/iterate-prompt.md") shouldBe Right(Template.Iterate)
    Prompts.parseName("grill-issue-prompt.md") shouldBe Right(Template.GrillIssue)

  it should "reject an unknown name" in:
    Prompts.parseName("nope.md").isLeft shouldBe true

  "eject" should "write the built-in to the override directory" in:
    val root = tempRoot()
    val out  = Prompts.eject(root, "prompts/review-prompt.md", force = false)
    out.isRight shouldBe true
    val written = new String(Files.readAllBytes(out.toOption.get), StandardCharsets.UTF_8)
    written shouldBe Prompts.builtIn(Template.Review)

  it should "refuse to clobber an existing file without force" in:
    val root = tempRoot()
    val dest = root.resolve(Prompts.EjectDir).resolve("review-prompt.md")
    write(dest, "MINE")
    Prompts.eject(root, "review-prompt.md", force = false).isLeft shouldBe true
    new String(Files.readAllBytes(dest), StandardCharsets.UTF_8) shouldBe "MINE"

  it should "overwrite with force" in:
    val root = tempRoot()
    val dest = root.resolve(Prompts.EjectDir).resolve("review-prompt.md")
    write(dest, "MINE")
    Prompts.eject(root, "review-prompt.md", force = true).isRight shouldBe true
    new String(Files.readAllBytes(dest), StandardCharsets.UTF_8) shouldBe Prompts.builtIn(
      Template.Review
    )
```

- [ ] **Step 3: Run test to verify it fails**

Run: `scala-cli test . --test-only 'in.rcard.litterbox.PromptsSpec'`
Expected: FAIL to compile, "Not found: Prompts".

- [ ] **Step 4: Add the `GrillIssue` template case**

In `src/Domain.scala`, extend the `Template` enum (currently lines 91-97):

```scala
enum Template(val fileName: String):
  case Iterate extends Template("iterate-prompt.md")
  case Fix     extends Template("fix-prompt.md")
  case Review  extends Template("review-prompt.md")

  /** Not dispatched by `Machine` today. It exists as a case anyway because `Prompts` enumerates
    * `Template.values` to decide what can be ejected and what ships in the artifact: a fourth
    * skeleton that is not a case is a skeleton a consumer cannot override and the build cannot
    * check, which is a worse failure than an unused case.
    */
  case GrillIssue extends Template("grill-issue-prompt.md")
```

- [ ] **Step 5: Write `src/Prompts.scala`**

```scala
package in.rcard.litterbox

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/** Where a prompt skeleton comes from.
  *
  * Slice 2 read the four templates out of the consumer repo's own `prompts/` directory, which made
  * every consumer the owner of the protocol: the lines that keep the machine honest ("you do not
  * report success", the `VERDICT:` contract, the no-`gh` rule) sat in a file the consumer was
  * invited to edit, and a consumer who mangled one broke the loop silently, with no error. So the
  * skeletons ship in the artifact instead, and the consumer owns exactly one file: the conventions
  * spliced in as `{{CONVENTIONS}}`.
  *
  * A repo that genuinely needs to change a skeleton still can, by ejecting it — but that is now a
  * deliberate act with a command behind it, not the default state of every install.
  */
object Prompts:

  /** Where an ejected skeleton lands, relative to the repo root. Under `.litter-box/` so the
    * existing `protect` floor (`.litter-box/**`, see `Settings.protectFloor`) already covers it:
    * an agent under harness must not be able to rewrite the prompt that constrains it.
    */
  val EjectDir = ".litter-box/prompts"

  /** Classpath prefix. Matches `resources/prompts/` in the source tree via
    * `//> using resourceDir ./resources`.
    */
  private val ResourcePrefix = "/prompts/"

  /** The skeleton as it ships. A missing resource is a broken build, not a user error — the file
    * is in the same repo as this code and `Template` names it — so this throws rather than
    * returning an `Option` that every caller would have to pretend might be empty.
    */
  def builtIn(t: Template): String =
    val res = ResourcePrefix + t.fileName
    Option(getClass.getResourceAsStream(res)) match
      case Some(in) =>
        try new String(in.readAllBytes(), StandardCharsets.UTF_8)
        finally in.close()
      case None =>
        throw IllegalStateException(s"built-in prompt resource missing from the artifact: $res")

  /** The skeleton this repo actually uses: an ejected file if there is one, else the built-in.
    *
    * Disk wins, because that is what ejecting is for. The check is `isRegularFile` rather than
    * `exists` so a directory left at that path falls through to the built-in instead of throwing
    * mid-iteration.
    */
  def resolve(root: Path, t: Template): String =
    val onDisk = root.resolve(EjectDir).resolve(t.fileName)
    if Files.isRegularFile(onDisk) then
      new String(Files.readAllBytes(onDisk), StandardCharsets.UTF_8)
    else builtIn(t)

  /** Maps what an operator typed at `litter-box eject <what>` onto a template.
    *
    * Both spellings are accepted (`iterate-prompt.md` and `prompts/iterate-prompt.md`) because the
    * issue's own example uses the prefixed form while the file on disk after ejecting has none, and
    * an operator who copies either back out of their shell history should not get an error.
    */
  def parseName(what: String): Either[String, Template] =
    val bare = what.stripPrefix("prompts/").stripPrefix("./")
    Template.values.find(_.fileName == bare) match
      case Some(t) => Right(t)
      case None    =>
        Left(
          s"unknown prompt '$what' — expected one of: ${Template.values.map(_.fileName).mkString(", ")}"
        )

  /** Copies a built-in skeleton to `EjectDir` so the repo can edit it.
    *
    * Refuses to clobber without `force`, on the same reasoning as `init`: the file it would
    * overwrite is by definition one somebody hand-edited, and silently replacing it with the stock
    * text would undo that work with no diff to notice.
    */
  def eject(root: Path, what: String, force: Boolean): Either[String, Path] =
    parseName(what).flatMap { t =>
      val dest = root.resolve(EjectDir).resolve(t.fileName)
      if Files.exists(dest) && !force then
        Left(s"$dest already exists — pass --force to overwrite it")
      else
        Files.createDirectories(dest.getParent)
        Files.write(
          dest,
          builtIn(t).getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
        Right(dest)
    }
```

- [ ] **Step 6: Point `LiveHarnessFs` at `Prompts`**

Replace `src/Live.scala:59-63` (the `readTemplate` method and its scaladoc):

```scala
  /** The skeleton for `t`, resolved by `Prompts`: an ejected `.litter-box/prompts/<file>` if the
    * repo has one, else the copy that ships in the artifact. Slice 2 read
    * `prompts/<file>` out of the consumer repo, which made every consumer the owner of the
    * protocol; `Prompts` explains why that moved.
    */
  def readTemplate(t: Template): String =
    Prompts.resolve(root, t)
```

- [ ] **Step 7: Drop the preflight that required a consumer `prompts/` directory**

Replace `src/Main.scala:300-305` (the loop over `Template.values` plus the conventions check) with:

```scala
    // 6c. Conventions file existence (loop.sh:119, 212-215). The prompt-template check that used
    // to sit here is gone: the skeletons ship in the artifact now (`Prompts.builtIn`), so there is
    // no consumer-side file whose absence could be a startup failure. A consumer repo that has
    // never ejected anything has no `prompts/` directory at all, and that is the normal case.
    val conventions = root.resolve(parsed.cfg.conventions)
    if !Files.isRegularFile(conventions) then die(s"missing conventions file: $conventions")
```

- [ ] **Step 8: Run the full suite**

Run: `scala-cli test .`
Expected: PASS. `MainSpec` and `ScenarioSpec` still pass because `Recorder`'s in-memory `HarnessFs`
supplies templates directly and never touched `prompts/`.

If a spec asserts on `Machine.PromptDir`, delete that assertion and the `PromptDir` constant in
`src/Machine.scala:35-39`: nothing reads it once `readTemplate` goes through `Prompts`.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: Ship prompt skeletons in the artifact, overridable per repo

The four templates move from the consumer repo's prompts/ directory into
resources/prompts/, read via the classpath. A .litter-box/prompts/<file>
on disk overrides the built-in, which is what `litter-box eject` will
write. Template gains a GrillIssue case so all four are ejectable.

The startup preflight no longer requires a prompts/ directory in the
consumer repo: there is nothing consumer-side left to miss.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Splice the config-derived slots at every render site

The skeletons are about to stop naming this repo's gate command and protected paths inline, which
means those facts have to arrive as slots. Wire the slots before the content depends on them.

**Files:**
- Modify: `src/Machine.scala:172-180` (Iterate), `src/Machine.scala:238-248` (Fix), `src/Machine.scala:307-318` (Review)
- Test: `test/PromptsSpec.scala` (append)

**Interfaces:**
- Consumes: `Machine.renderTemplate` (`src/Machine.scala:92`), `Config.protect`, `Config.gateCmd`, `HarnessFs.conventions()`.
- Produces: `Machine.protectedList(protect: List[String]): String`.

- [ ] **Step 1: Write the failing test**

Append to `test/PromptsSpec.scala`:

```scala
  "renderTemplate" should "leave an unsupplied slot in the output verbatim" in:
    // The reason every render site below must pass every slot its template can contain: an
    // unsupplied slot is not an error, it is a literal `{{KEY}}` shipped to the model.
    Machine.renderTemplate("a\n{{GATE}}\nb", "ISSUE" -> "x") shouldBe "a\n{{GATE}}\nb"

  it should "not rescan content it has already spliced" in:
    // Splice order: the trusted, config-derived slots go first, so text arriving from GitHub or
    // from agent output can never be reshaped into a different prompt by naming a later slot.
    val out = Machine.renderTemplate(
      "{{GATE}}\n{{ISSUE}}",
      "GATE"  -> "sbt test",
      "ISSUE" -> "{{GATE}}"
    )
    out shouldBe "sbt test\n{{GATE}}"

  "protectedList" should "render one bullet per protect entry" in:
    Machine.protectedList(List(".litter-box/**", "CONTEXT.md")) shouldBe
      "- `.litter-box/**`\n- `CONTEXT.md`"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scala-cli test . --test-only 'in.rcard.litterbox.PromptsSpec'`
Expected: FAIL to compile, "value protectedList is not a member of object Machine".

- [ ] **Step 3: Add `protectedList` to `Machine`**

Insert directly after `renderTemplate` (`src/Machine.scala:100`):

```scala
  /** `{{PROTECTED}}`: the patch guard's list, as markdown bullets for the prompt.
    *
    * The SHAPE of the sentence around it is protocol and stays in the skeleton; the LIST is this
    * repo's and so cannot be. Rendered from `Config.protect` — which `Settings.protectWithFloor`
    * has already unioned with the reference floor — so the prompt names exactly the paths the guard
    * will actually reject, never a stale hand-maintained copy of them.
    */
  private[litterbox] def protectedList(protect: List[String]): String =
    protect.map(p => s"- `$p`").mkString("\n")
```

- [ ] **Step 4: Splice at the Iterate site**

Replace the `fs.write(workerPromptFile, ...)` call at `src/Machine.scala:175-179`:

```scala
    val workerPromptFile = artifact(issue, ".prompt.txt")
    fs.write(
      workerPromptFile,
      renderTemplate(
        fs.readTemplate(Template.Iterate),
        // Config-derived slots FIRST, untrusted content last: `renderTemplate` folds left, so a
        // slot spliced early has its injected text scanned by every later pass. With ISSUE first,
        // an issue body containing the literal {{GATE}} would have that line rewritten by the
        // harness. Nothing here is secret from the agent, but a prompt reshaped by its own inputs
        // is a prompt nobody reviewed.
        "PROTECTED"   -> protectedList(cfg.protect),
        "GATE"        -> cfg.gateCmd,
        "CONVENTIONS" -> fs.conventions(),
        "ISSUE"       -> fs.read(bodyFile)
      )
    )
```

- [ ] **Step 5: Splice at the Fix site**

Replace the `renderTemplate` call at `src/Machine.scala:240-247`:

```scala
      fs.write(
        fixPromptFile,
        renderTemplate(
          fs.readTemplate(Template.Fix),
          "PROTECTED"   -> protectedList(cfg.protect),
          "GATE"        -> cfg.gateCmd,
          "CONVENTIONS" -> fs.conventions(),
          "ISSUE"       -> fs.read(bodyFile),
          "FAILURE"     -> fs.read(failFile)
        )
      )
```

- [ ] **Step 6: Splice at the Review site**

Replace the `renderTemplate` call at `src/Machine.scala:309-317`:

```scala
          fs.write(
            reviewPromptFile,
            renderTemplate(
              fs.readTemplate(Template.Review),
              "PROTECTED"   -> protectedList(cfg.protect),
              "GATE"        -> cfg.gateCmd,
              "CONVENTIONS" -> fs.conventions(),
              "ISSUE"       -> fs.read(bodyFile),
              "TAMPER"      -> fs.read(tamperFile),
              "DIFF"        -> fs.read(diffFile)
            )
          )
```

- [ ] **Step 7: Run the full suite**

Run: `scala-cli test .`
Expected: PASS.

`ScenarioSpec` may assert on rendered prompt bodies. If an assertion breaks because a prompt now
contains the spliced conventions text, update the expected string — do not remove the assertion,
and do not change the splice to make an old expectation pass.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: Splice protected paths, gate command and conventions into every prompt

The skeletons are about to stop naming this repo's gate and protected
paths inline, so both arrive as slots instead. Conventions, previously
spliced only into the reviewer prompt, now reach the worker and fixer too.

Splice order changes: config-derived slots go before any slot carrying
text the harness did not write. renderTemplate folds left, so with ISSUE
spliced first an issue body containing a literal {{GATE}} had that line
rewritten by the harness.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Rewrite the skeletons as neutral protocol

The content task the issue calls the real work. Each skeleton keeps the protocol and sheds
everything specific to the repo litter-box grew up in.

**Files:**
- Modify: `resources/prompts/iterate-prompt.md`, `resources/prompts/fix-prompt.md`, `resources/prompts/review-prompt.md`, `resources/prompts/grill-issue-prompt.md`
- Create: `resources/scaffold/conventions.md`
- Create: `.litter-box/prompts/conventions.md` (this repo's own, carrying what the skeletons shed)
- Modify: `.litter-box/config.conf`
- Test: `test/PromptsSpec.scala` (append)

**Interfaces:**
- Consumes: `Prompts.builtIn`, `Machine.renderTemplate`, `Machine.protectedList` (Tasks 1 and 2).
- Produces: no new Scala API. Produces `resources/scaffold/conventions.md`, which Task 5's
  `Init.plan` writes to `.litter-box/prompts/conventions.md`.

- [ ] **Step 1: Write the failing test**

Append to `test/PromptsSpec.scala`:

```scala
  /** The protocol lines: the ones that keep the machine honest. A consumer who deletes one of
    * these breaks the loop with no error at all — the reviewer stops emitting a parseable verdict,
    * or the agent starts pushing branches — so they live in the skeleton and this test says so.
    */
  "the built-in skeletons" should "keep the verdict contract in the reviewer prompt" in:
    val rendered = Machine.renderTemplate(
      Prompts.builtIn(Template.Review),
      "PROTECTED"   -> Machine.protectedList(List(".litter-box/**")),
      "GATE"        -> "sbt test",
      "CONVENTIONS" -> "", // a consumer who wrote nothing at all
      "ISSUE"       -> "an issue",
      "TAMPER"      -> "no tampering",
      "DIFF"        -> "a diff"
    )
    rendered should include("VERDICT: APPROVE")
    rendered should include("VERDICT: REQUEST_CHANGES")

  it should "keep the no-gh and no-success-report rules in the worker prompt" in:
    val rendered = Machine.renderTemplate(
      Prompts.builtIn(Template.Iterate),
      "PROTECTED"   -> Machine.protectedList(List(".litter-box/**")),
      "GATE"        -> "sbt test",
      "CONVENTIONS" -> "",
      "ISSUE"       -> "an issue"
    )
    rendered should include("Do not run any `gh` command")
    rendered should include("You do not report success")

  it should "substitute every slot, leaving no braces behind" in:
    // Guards the whole slot contract at once: a skeleton edit that adds a slot nobody splices
    // fails here rather than shipping literal braces to the model.
    val cases = List(
      Template.Iterate -> Seq(
        "PROTECTED"   -> "- `x`",
        "GATE"        -> "g",
        "CONVENTIONS" -> "c",
        "ISSUE"       -> "i"
      ),
      Template.Fix -> Seq(
        "PROTECTED"   -> "- `x`",
        "GATE"        -> "g",
        "CONVENTIONS" -> "c",
        "ISSUE"       -> "i",
        "FAILURE"     -> "f"
      ),
      Template.Review -> Seq(
        "PROTECTED"   -> "- `x`",
        "GATE"        -> "g",
        "CONVENTIONS" -> "c",
        "ISSUE"       -> "i",
        "TAMPER"      -> "t",
        "DIFF"        -> "d"
      )
    )
    cases.foreach { case (t, splices) =>
      withClue(s"${t.fileName}: ") {
        Machine.renderTemplate(Prompts.builtIn(t), splices*) should not include "{{"
      }
    }

  it should "name no project-specific convention" in:
    // The whole point of the slice: a consumer inherits the protocol, never another project's
    // domain. Each of these is a real line the pre-slice-3 skeletons carried.
    val banned = List("US-1", "Register", "Testcontainers", "onion", "src/it", "@nowarn", "-Werror")
    Template.values.foreach { t =>
      val text = Prompts.builtIn(t)
      banned.foreach { word =>
        withClue(s"${t.fileName} still mentions '$word': ") {
          text should not include word
        }
      }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scala-cli test . --test-only 'in.rcard.litterbox.PromptsSpec'`
Expected: FAIL. The "no project-specific convention" case fails on `US-1`, `Register`,
`Testcontainers`, `onion`, `src/it`, `@nowarn`, `-Werror`; the slot case fails because the
skeletons contain no `{{PROTECTED}}` or `{{GATE}}` yet.

- [ ] **Step 3: Rewrite `resources/prompts/iterate-prompt.md`**

```markdown
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
- Do not edit any of these protected paths. A patch that touches one is rejected whole, and
  you lose the iteration:

{{PROTECTED}}

- Follow this project's conventions exactly. They are reproduced in full below, and they are
  binding on this iteration in the same way these hard rules are.
- Write tests for every acceptance criterion in the issue.
- Two test tiers exist. The fast tier runs here, on every iteration, and gates your work. The
  slow tier runs in CI after the harness opens a PR. A test that needs external infrastructure
  belongs in the slow tier: put one in the fast tier and it will not run here, which breaks the
  split and hides a failure until CI. The conventions below say which directory is which.
- Do not weaken, delete, or silence existing tests to make the build pass. Suppressing a
  warning or an assertion to get to green is the same failure as deleting the test.

## This project's conventions

{{CONVENTIONS}}

## Definition of done for this iteration

- The acceptance criteria in the issue are implemented.
- The fast gate is green. The harness runs it as: `{{GATE}}`
- Every acceptance criterion maps to at least one test.
- Any slow-tier test you added is self-contained and passes against fresh infrastructure, since
  CI judges it and you cannot.

When you believe you are done, stop. The harness runs the fast gate and an independent reviewer,
opens a PR, and lets CI run the slow tier. You do not report success. The gate, the reviewer, and
CI do.
```

- [ ] **Step 4: Rewrite `resources/prompts/fix-prompt.md`**

Read the current file first (`cat resources/prompts/fix-prompt.md`) and preserve its existing
structure and its `{{FAILURE}}` slot. Apply exactly the same three-way split: keep the protocol,
replace the inline protected-path list with `{{PROTECTED}}`, replace the inline gate command with
`{{GATE}}`, add a `## This project's conventions` section holding `{{CONVENTIONS}}`, and delete
every mention of the onion layout, the `Register` slice, Testcontainers, `src/it`, `-Werror` and
`@nowarn`. The fixer's own protocol lines stay verbatim: it repairs the named failure and nothing
else, it does not start new work, and it does not report success.

- [ ] **Step 5: Rewrite `resources/prompts/review-prompt.md`**

Same treatment, and additionally: the `VERDICT: APPROVE` / `VERDICT: REQUEST_CHANGES` contract and
the `{{TAMPER}}` splice stay verbatim in the skeleton. Do not move either into conventions. The
harness parses the verdict line; a consumer who reworded it would get a reviewer whose output no
longer parses, with no error to tell them.

- [ ] **Step 6: Rewrite `resources/prompts/grill-issue-prompt.md`**

Same treatment. Nothing dispatches this template yet, so it has no slot obligations beyond the ones
it already uses. Strip the project-specific conventions and add `{{CONVENTIONS}}` where they were.

- [ ] **Step 7: Write `resources/scaffold/conventions.md`**

This is the file `init` gives a fresh consumer. It is a prompt for the human, not for the model.

```markdown
# Project conventions

This file is yours. litter-box splices it verbatim into every prompt it sends, as
`{{CONVENTIONS}}`. Everything the agent needs to know that is true of THIS project and not of
software in general goes here. The protocol — one iteration, no pushing, a test per acceptance
criterion, who reports success — is not here: it ships with litter-box and you cannot break it
by editing this file.

Delete the prompts below as you answer them. An empty file is valid and the loop will run; it
will just produce code that matches nothing in particular.

## Layout

Where does code go? Name the directories and what belongs in each. If the project has a layering
rule (what may import what), state it as a rule, not as a description.

## The template to copy

Point at one existing feature, by path, that is the shape you want new work to take. This is the
single highest-value line in this file. An agent copying a real slice of your codebase beats any
amount of prose about style.

## Test tiers

litter-box runs a fast gate every iteration and lets CI run everything else. Say which directory
holds which. Say what a test is not allowed to need in order to live in the fast tier: a database,
a container, the network, a credential.

## Build and lint rules

Anything that turns a warning into a failure, any formatter that must be run, any check that is
not part of the gate command but will fail CI.

## Anything that has bitten you

Rules that exist because something went wrong once. Say why, briefly. An agent that knows the
reason applies the rule to cases you did not list.
```

- [ ] **Step 8: Move this repo's own conventions out of the skeletons**

The lines just deleted from the skeletons are true of litter-box itself and must not be lost.
Create `.litter-box/prompts/conventions.md` with them:

```markdown
# litter-box conventions

## Layout

`src/` holds the loop: `Domain.scala` (types and exit codes), `Machine.scala` (the pure state
machine), `Caps.scala` (the capability interfaces), `Live.scala` (their real implementations),
`Settings.scala` (config), `Main.scala` (wiring and the driver). `test/` mirrors it.

The rule: `Machine` is pure and depends only on the capabilities in `Caps.scala`. It never touches
the filesystem, a subprocess, or the clock directly. Everything that does lives in `Live.scala`
behind one of those interfaces, so the whole state machine is testable against `test/Recorder.scala`
with no Docker, no `gh` and no credentials.

## The template to copy

`LiveGateRunner` in `src/Live.scala` and its tests in `test/LiveProcSpec.scala`. That pair shows
the shape: a handler taking its dependencies as constructor parameters, a seam for the one thing
that must be faked, and a test that drives real behaviour through the seam rather than asserting on
a mock.

## Test tiers

Everything under `test/` is the fast tier and must stay Docker-free, network-free and
credential-free: CI runs `scala-cli test .` with nothing else installed. Tests that need Docker are
shell scripts under `sandbox/test/`, run by hand, and are never wired into the gate.

## Build and lint rules

Scala 3.8.3 on temurin 21, built by scala-cli, not sbt. This is deliberate: the threat model
distrusts agent-authored build files, so the loop never couples to one. Do not add a `build.sbt`.

One runtime dependency (`com.typesafe:config`) and one test dependency (scalatest). Adding a
dependency is a design decision, not a convenience.

Never use `@nowarn` or any other suppression to get past a warning. Fix the cause.

## Anything that has bitten you

Scaladoc explains WHY a decision was made, never what the code does. The codebase is read mostly by
agents with no memory of the conversation that produced it, so a comment restating the code costs
context and teaches nothing, while a missing reason gets re-litigated every iteration.

Prose contains no dash characters.
```

- [ ] **Step 9: Point this repo's config at its new conventions file**

In `.litter-box/config.conf`, change the `conventions` line:

```
conventions   = ".litter-box/prompts/conventions.md"
```

Leave `CONTEXT.md` on disk and leave it in the `protect` list. It is this repo's human-facing
document; the loop just no longer splices it.

- [ ] **Step 10: Run the full suite**

Run: `scala-cli test .`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: Make the prompt skeletons neutral protocol

The four skeletons keep the lines that keep the machine honest (one
iteration, no branch switching or pushing or gh, a test per acceptance
criterion, the VERDICT contract, who reports success) and shed everything
true only of the repo litter-box grew up in: the onion layout, the US-1
Register slice, Testcontainers, src/it, -Werror and @nowarn.

Those move to .litter-box/prompts/conventions.md, which is this repo's own
copy of the file init now scaffolds for a consumer. Protected paths and the
gate command arrive as slots rather than as prose.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: The argument parser

Pure, standalone, testable without a process. `Main` does not change in this task.

**Files:**
- Create: `src/Cli.scala`
- Test: `test/CliSpec.scala`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```scala
  enum Command:
    case Loop(dryRun: Boolean)
    case Init(force: Boolean)
    case Eject(what: String, force: Boolean)
    case Help

  object Cli:
    val Usage: String
    def parse(args: List[String]): Either[String, Command]
  ```

- [ ] **Step 1: Write the failing test**

Create `test/CliSpec.scala`:

```scala
package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit tests for the argument parser.
  *
  * The parser is pure and total so this spec is a table: every accepted invocation, and the shape
  * of the rejection for everything else. `Main` gets no test of its own for dispatch, because
  * dispatch is a three-way match over exactly this enum.
  */
class CliSpec extends AnyFlatSpec with Matchers:

  "parse" should "default to the loop with dry-run off" in:
    Cli.parse(Nil) shouldBe Right(Command.Loop(dryRun = false))

  it should "accept --dry-run" in:
    Cli.parse(List("--dry-run")) shouldBe Right(Command.Loop(dryRun = true))

  it should "accept init with and without --force" in:
    Cli.parse(List("init")) shouldBe Right(Command.Init(force = false))
    Cli.parse(List("init", "--force")) shouldBe Right(Command.Init(force = true))

  it should "accept eject with a prompt name" in:
    Cli.parse(List("eject", "prompts/iterate-prompt.md")) shouldBe
      Right(Command.Eject("prompts/iterate-prompt.md", force = false))
    Cli.parse(List("eject", "fix-prompt.md", "--force")) shouldBe
      Right(Command.Eject("fix-prompt.md", force = true))

  it should "reject eject with no argument" in:
    Cli.parse(List("eject")).isLeft shouldBe true

  it should "accept every spelling of help" in:
    Cli.parse(List("--help")) shouldBe Right(Command.Help)
    Cli.parse(List("-h")) shouldBe Right(Command.Help)
    Cli.parse(List("help")) shouldBe Right(Command.Help)

  it should "reject an unknown subcommand" in:
    Cli.parse(List("frobnicate")).isLeft shouldBe true

  it should "reject an unknown flag on a known subcommand" in:
    Cli.parse(List("init", "--wat")).isLeft shouldBe true

  it should "reject a flag that belongs to another subcommand" in:
    // --dry-run is a loop flag. Accepting it on `init` silently would suggest init has a dry-run
    // mode, which it does not.
    Cli.parse(List("init", "--dry-run")).isLeft shouldBe true

  "the usage text" should "mention every subcommand" in:
    Cli.Usage should include("init")
    Cli.Usage should include("eject")
    Cli.Usage should include("--dry-run")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scala-cli test . --test-only 'in.rcard.litterbox.CliSpec'`
Expected: FAIL to compile, "Not found: Cli".

- [ ] **Step 3: Write `src/Cli.scala`**

```scala
package in.rcard.litterbox

/** What the operator asked for.
  *
  * Kept as a closed enum parsed up front rather than as flags read where they are needed, so
  * `Main`'s dispatch is a total match and an invocation that means nothing fails before the process
  * has touched a repo.
  */
enum Command:
  /** The loop, which is what litter-box does when asked for nothing in particular. */
  case Loop(dryRun: Boolean)
  case Init(force: Boolean)
  case Eject(what: String, force: Boolean)
  case Help

/** Argument parsing, by hand.
  *
  * A parser library would give help text and validation for free, at the cost of a dependency and
  * its transitive tree on the classpath of a process whose whole design is about limiting what runs
  * near an agent. The grammar is four commands and two flags; it does not earn a dependency.
  *
  * Pure and total: every input maps to a `Right(Command)` or a `Left(message)`, and nothing here
  * reads the environment, the filesystem or the clock. That is what lets `CliSpec` be a table
  * rather than a set of process invocations.
  */
object Cli:

  val Usage: String =
    """usage:
      |  litter-box [--dry-run]        run the loop (default)
      |  litter-box init [--force]     scaffold .litter-box/ in this repo
      |  litter-box eject <prompt> [--force]
      |                                copy a built-in prompt to .litter-box/prompts/ to override it
      |  litter-box --help             this message
      |
      |environment variables still work as they always have; a flag beats the matching variable.
      |""".stripMargin

  def parse(args: List[String]): Either[String, Command] = args match
    case Nil                                => Right(Command.Loop(dryRun = false))
    case ("--help" | "-h" | "help") :: Nil  => Right(Command.Help)
    case "init" :: rest                     => flagsOnly(rest, "--force").map(Command.Init.apply)
    case "eject" :: what :: rest if !what.startsWith("-") =>
      flagsOnly(rest, "--force").map(Command.Eject(what, _))
    case "eject" :: _ =>
      Left("eject needs the name of a prompt, e.g. `litter-box eject prompts/iterate-prompt.md`")
    case rest if rest.forall(_.startsWith("-")) =>
      flagsOnly(rest, "--dry-run").map(Command.Loop.apply)
    case other => Left(s"unknown command: ${other.head}")

  /** Accepts a trailing argument list that is either empty or exactly the one flag this subcommand
    * takes. Deliberately narrow: `init --dry-run` is an error rather than a silently ignored flag,
    * because ignoring it would imply `init` has a dry-run mode.
    */
  private def flagsOnly(rest: List[String], flag: String): Either[String, Boolean] = rest match
    case Nil          => Right(false)
    case `flag` :: Nil => Right(true)
    case other        => Left(s"unexpected argument: ${other.head}")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `scala-cli test . --test-only 'in.rcard.litterbox.CliSpec'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add src/Cli.scala test/CliSpec.scala
git commit -m "feat: Add the argument parser

Four commands, two flags, hand-rolled. A parser library would cost a
dependency and its transitive tree on the classpath of a process whose
design is about limiting what runs near an agent; this grammar does not
earn one.

Main does not use it yet.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: The `init` scaffolder

**Files:**
- Create: `src/Init.scala`
- Create: `resources/scaffold/config.conf`, `resources/scaffold/Dockerfile`, `resources/scaffold/allowlist`, `resources/scaffold/env.example`, `resources/scaffold/gitignore`
- Test: `test/InitSpec.scala`

**Interfaces:**
- Consumes: `Machine.renderTemplate` (Task 2), `Settings.ConfigPath`, `Settings.loadFile`, `Settings.parse`, `resources/scaffold/conventions.md` (Task 3).
- Produces:
  ```scala
  object Init:
    val Dir: String = ".litter-box"
    enum BuildTool { case Sbt }
    final case class Detected(buildTool: Option[BuildTool], remote: Option[String], jdk: Option[String])
    def plan(d: Detected): List[(String, String)]   // repo-relative path -> content
    def warnings(d: Detected): List[String]
    def nextSteps(d: Detected): List[String]
    def detect(root: java.nio.file.Path, exec: Seq[String] => LiveProc.Result): Detected
    def run(root: java.nio.file.Path, d: Detected, force: Boolean): Either[String, List[String]]
  ```

- [ ] **Step 1: Write the failing test**

Create `test/InitSpec.scala`:

```scala
package in.rcard.litterbox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Unit tests for the scaffolder.
  *
  * THE RULE THIS SPEC IS WRITTEN TO (GitHub issue #4): what `init` writes must be something the
  * slice-2 config loader accepts, and a repo whose build tool we do not recognise must get a
  * visible TODO rather than a preset we have never run a loop against. So the two load-bearing
  * assertions below are "Settings.loadFile accepts the output" and "a non-sbt repo gets no sbt".
  */
class InitSpec extends AnyFlatSpec with Matchers:

  private def tempRoot(): Path = Files.createTempDirectory("init-spec")

  private def readString(p: Path): String =
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /** Every file under `root`, as repo-relative path strings, sorted. Used to assert that a refused
    * `init` changed literally nothing.
    */
  private def tree(root: Path): List[String] =
    if !Files.exists(root) then Nil
    else
      Files
        .walk(root)
        .iterator
        .asScala
        .filter(Files.isRegularFile(_))
        .map(p => root.relativize(p).toString + ":" + readString(p).hashCode)
        .toList
        .sorted

  private val sbtRepo   = Init.Detected(Some(Init.BuildTool.Sbt), Some("rcardin/x"), Some("21"))
  private val plainRepo = Init.Detected(None, None, Some("21"))

  "plan" should "write every file the slice promises" in:
    val paths = Init.plan(sbtRepo).map(_._1)
    paths should contain allOf (
      ".litter-box/config.conf",
      ".litter-box/Dockerfile",
      ".litter-box/allowlist",
      ".litter-box/prompts/conventions.md",
      ".litter-box/.env.example",
      ".litter-box/.gitignore"
    )

  it should "produce a config the slice-2 loader accepts" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false).isRight shouldBe true
    val loaded = Settings.loadFile(root)
    loaded.isRight shouldBe true
    val cfg = Settings.parse(loaded.toOption.get)
    cfg.conventions shouldBe ".litter-box/prompts/conventions.md"
    cfg.gateCmd should include("sbt")

  it should "point conventions at a file it actually wrote" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val cfg = Settings.parse(Settings.loadFile(root).toOption.get)
    Files.isRegularFile(root.resolve(cfg.conventions)) shouldBe true

  it should "keep .litter-box protected in the scaffolded config" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val cfg = Settings.parse(Settings.loadFile(root).toOption.get)
    cfg.protect should contain(".litter-box/**")

  it should "gitignore the logs and the env file" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val ignored = readString(root.resolve(".litter-box/.gitignore"))
    ignored should include("logs/")
    ignored should include(".env")

  it should "carry no credential value in the env example" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    val env = readString(root.resolve(".litter-box/.env.example"))
    env should include("CLAUDE_CODE_OAUTH_TOKEN=")
    env should include("ANTHROPIC_API_KEY=")
    env should not include "sk-ant"

  "a non-sbt repo" should "get a TODO Dockerfile and no sbt anywhere" in:
    val root = tempRoot()
    Init.run(root, plainRepo, force = false).isRight shouldBe true
    val dockerfile = readString(root.resolve(".litter-box/Dockerfile"))
    dockerfile should include("TODO: install your build tool")
    dockerfile.toLowerCase should not include "sbt"

  it should "not silently inherit the sbt gate from the reference config" in:
    // The trap this test exists for: omitting `gate.fast` from the scaffolded config would make it
    // fall back to Settings.Reference, which is `sbt -Werror compile test`. That is exactly the
    // silent sbt preset the issue forbids.
    val root = tempRoot()
    Init.run(root, plainRepo, force = false)
    val cfg = Settings.parse(Settings.loadFile(root).toOption.get)
    cfg.gateCmd should not include "sbt"

  it should "warn out loud" in:
    Init.warnings(plainRepo).mkString(" ") should include("build tool")

  it should "still succeed" in:
    Init.run(tempRoot(), plainRepo, force = false).isRight shouldBe true

  "a repo with no GitHub remote" should "be warned about it" in:
    Init.warnings(plainRepo).mkString(" ").toLowerCase should include("remote")

  "a second init" should "fail and change nothing without --force" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false).isRight shouldBe true
    Files.write(
      root.resolve(".litter-box/prompts/conventions.md"),
      "MINE".getBytes(StandardCharsets.UTF_8)
    )
    val before = tree(root)
    Init.run(root, sbtRepo, force = false).isLeft shouldBe true
    tree(root) shouldBe before

  it should "overwrite with --force" in:
    val root = tempRoot()
    Init.run(root, sbtRepo, force = false)
    Files.write(
      root.resolve(".litter-box/prompts/conventions.md"),
      "MINE".getBytes(StandardCharsets.UTF_8)
    )
    Init.run(root, sbtRepo, force = true).isRight shouldBe true
    readString(root.resolve(".litter-box/prompts/conventions.md")) should not be "MINE"

  "detect" should "recognise sbt by build.sbt at the root" in:
    val root = tempRoot()
    Files.write(root.resolve("build.sbt"), Array.emptyByteArray)
    val d = Init.detect(root, _ => LiveProc.Result(1, "", ""))
    d.buildTool shouldBe Some(Init.BuildTool.Sbt)

  it should "report no build tool when there is no build.sbt" in:
    val d = Init.detect(tempRoot(), _ => LiveProc.Result(1, "", ""))
    d.buildTool shouldBe None

  it should "read the GitHub remote off gh" in:
    val d = Init.detect(tempRoot(), _ => LiveProc.Result(0, "rcardin/litter-box\n", ""))
    d.remote shouldBe Some("rcardin/litter-box")

  it should "survive gh being absent" in:
    val d = Init.detect(tempRoot(), _ => throw java.io.IOException("no gh"))
    d.remote shouldBe None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scala-cli test . --test-only 'in.rcard.litterbox.InitSpec'`
Expected: FAIL to compile, "Not found: Init".

- [ ] **Step 3: Write the scaffold resources**

`resources/scaffold/config.conf`:

```
# litter-box configuration for this repository. Written by `litter-box init`.
#
# Every key below has a default in `Settings.Reference`; the ones written out are the ones a
# consumer repo genuinely has to decide. Delete a key to take the default, add one to override it.
# An environment variable beats this file, so an operator can change one knob for one run without
# editing a tracked file.

# Namespaces the Docker image, network, proxy container and cache volume, so two litter-box
# instances on one machine cannot tear down each other's proxy mid-iteration.
instance-name = "litter-box"

# Spliced into every prompt as {{CONVENTIONS}}. This is the one file you are expected to write.
conventions   = ".litter-box/prompts/conventions.md"

# Create this file at the repo root to stop the loop after the current iteration.
stop-file     = "STOP.md"
log-dir       = ".litter-box/logs"

gate {
{{GATE}}
  timeout = 900
}

# The three labels the loop reads and writes. Create them with:
#   gh label create ready && gh label create in-progress && gh label create blocked
issues.labels { ready = "ready", active = "in-progress", blocked = "blocked" }

# The patch guard. A patch touching any of these is rejected whole. `.litter-box/**` and
# `.github/**` are a floor litter-box unions in regardless of what this list says: an agent that
# could rewrite this file could widen its own guard, and one that could rewrite the workflows could
# rewrite the CI that judges it. Add your own entries; you cannot remove those.
protect = [".litter-box/**", ".github/**"]

budgets  { repair = 2, max-patch-bytes = 1000000 }
timeouts { iter = 1800, ci-wait = 900, ci-appear = 300, ci-appear-interval = 10 }
```

`resources/scaffold/Dockerfile`:

```
# The gate container for this repository.
#
# FROM the litter-box base image, which carries a JDK, the pinned Claude CLI and a non-root `gate`
# user, and NO credentials of any kind. It deliberately carries no build tool and no ENTRYPOINT:
# that is this file's job, and it is the only part of the sandbox that knows what this project is
# built with. See https://github.com/rcardin/litter-box/blob/main/docs/base-image.md for the
# contract the base image guarantees.
ARG BASE_IMAGE=ghcr.io/rcardin/litter-box-base:0.1.0
FROM ${BASE_IMAGE}

USER root
{{BUILD_TOOL}}
USER gate
WORKDIR /workspace
{{ENTRYPOINT}}
```

`resources/scaffold/allowlist`:

```
api.anthropic.com
```

`resources/scaffold/env.example`:

```
# Copy to .env and fill in ONE of the two. Never commit the result: .litter-box/.gitignore
# already excludes it.
#
# A subscription OAuth token, from `claude setup-token`. Bills the subscription.
CLAUDE_CODE_OAUTH_TOKEN=
# Or a dedicated, spend-capped console API key. Set one or the other, never both: passing both
# lets a stale key shadow a valid token inside claude, which is a 401 that looks like an outage.
ANTHROPIC_API_KEY=
```

`resources/scaffold/gitignore`:

```
logs/
.env
```

- [ ] **Step 4: Write `src/Init.scala`**

```scala
package in.rcard.litterbox

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.util.control.NonFatal

/** `litter-box init` — the way into a repo that is not litter-box.
  *
  * Slice 2 made the loop read everything repo-specific out of `.litter-box/config.conf`; nothing
  * wrote that file, so the only repo that could run the loop was the one whose config was already
  * committed. This is the other half.
  *
  * Split pure/IO on the same line `Settings` is: `plan` and `warnings` are total functions of what
  * was detected and hold every decision worth testing, while `run` is a thin shell that writes what
  * `plan` returned. So `InitSpec` can assert on the content of a scaffold without a filesystem, and
  * on the refusal semantics with one.
  */
object Init:

  val Dir = ".litter-box"

  /** Build tools we have actually run a loop against. Exactly one today, and that is the point:
    * the enum is the list of presets we are willing to scaffold, so adding a case is a deliberate
    * claim that somebody ran the loop end to end with it.
    */
  enum BuildTool:
    case Sbt

  final case class Detected(
      buildTool: Option[BuildTool],
      remote: Option[String],
      jdk: Option[String]
  )

  /** What `init` found. Nothing here is fatal: a repo can gain a remote or a build file after
    * scaffolding, and refusing to write a correct `.litter-box/` because `gh` is not installed
    * would be a worse trade than warning about it.
    *
    * TEST AFFORDANCE, deliberate, and the same shape as `Main.findOnPath`'s `exists` and
    * `Main.resolveRepoRoot`'s `revParse`: `exec` is the subprocess, injected so detection can be
    * exercised against invented results. THE ONLY PRODUCTION CALLER IS `Main`, which passes the
    * real `LiveProc.run`.
    */
  def detect(root: Path, exec: Seq[String] => LiveProc.Result): Detected =
    val buildTool =
      if Files.isRegularFile(root.resolve("build.sbt")) then Some(BuildTool.Sbt) else None

    val remote =
      try
        val r = exec(Seq("gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"))
        Option(r.stdoutTrimmedTrailingNewlines.strip()).filter(_ => r.rc == 0).filter(_.nonEmpty)
      catch case NonFatal(_) => None

    val jdk =
      try
        val r = exec(Seq("java", "-version"))
        // `java -version` writes to stderr, and has done since 1.0. The major version is the first
        // dotted component of the quoted version string.
        "\"(\\d+)".r.findFirstMatchIn(r.stderr + r.stdout).map(_.group(1))
      catch case NonFatal(_) => None

    Detected(buildTool, remote, jdk)

  /** Every file `init` writes, as repo-relative path to content. Total: there is no detection
    * result for which this returns a partial scaffold. A repo whose build tool we do not recognise
    * gets the same six files, with the two build-tool-shaped holes filled by a TODO rather than by
    * a guess.
    */
  def plan(d: Detected): List[(String, String)] =
    List(
      s"$Dir/config.conf"            -> configConf(d),
      s"$Dir/Dockerfile"             -> dockerfile(d),
      s"$Dir/allowlist"              -> resource("allowlist"),
      s"$Dir/prompts/conventions.md" -> resource("conventions.md"),
      s"$Dir/.env.example"           -> resource("env.example"),
      s"$Dir/.gitignore"             -> resource("gitignore")
    )

  /** The gate command, or a loud absence of one.
    *
    * Omitting `gate.fast` from the scaffolded config would NOT be neutral: the key would fall back
    * to `Settings.Reference`, which is `sbt -Werror compile test`, and a Gradle repo would silently
    * acquire an sbt gate. So the non-sbt case writes an explicit `false`, which is a command that
    * exists on every system and always fails. The loop then reports a red gate on iteration one,
    * which is the correct answer to "you have not told me how to build this".
    */
  private def configConf(d: Detected): String =
    val gate = d.buildTool match
      case Some(BuildTool.Sbt) => """  fast    = "sbt -Werror compile test""""
      case None                =>
        """  # TODO: your fast gate command. It must compile and run the in-memory test tier, and
          |  # must NOT need Docker, the network beyond .litter-box/allowlist, or a credential.
          |  # Until you set it, `false` keeps the gate honestly red rather than inheriting a
          |  # build tool nobody confirmed this project uses.
          |  fast    = "false"""".stripMargin
    Machine.renderTemplate(resource("config.conf"), "GATE" -> gate)

  private def dockerfile(d: Detected): String =
    val (install, entrypoint) = d.buildTool match
      case Some(BuildTool.Sbt) =>
        (
          """ARG SBT_VERSION=1.12.9
            |RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
            |      -o /tmp/sbt.tgz \
            |    && tar -xzf /tmp/sbt.tgz -C /usr/local \
            |    && rm /tmp/sbt.tgz \
            |    && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt""".stripMargin,
          """ENTRYPOINT ["sbt"]"""
        )
      case None =>
        (
          """# TODO: install your build tool here, pinned to an exact version so image rebuilds
            |# are reproducible. It must land on PATH for the non-root `gate` user.""".stripMargin,
          """# TODO: ENTRYPOINT ["<your build tool>"]"""
        )
    Machine.renderTemplate(
      resource("Dockerfile"),
      "BUILD_TOOL" -> install,
      "ENTRYPOINT" -> entrypoint
    )

  /** What went unanswered, in the operator's words. Printed to stderr after a successful `init`,
    * because every one of these is a thing that will otherwise fail on iteration one.
    */
  def warnings(d: Detected): List[String] =
    List(
      Option.when(d.buildTool.isEmpty)(
        "no build tool detected (no build.sbt at the repo root). The scaffolded Dockerfile and " +
          "gate.fast both carry a TODO — litter-box will not guess, because a preset nobody has " +
          "run a loop against is worse than no preset."
      ),
      Option.when(d.remote.isEmpty)(
        "no GitHub remote found via `gh`. The loop reads and writes issues, labels and PRs, so it " +
          "needs one before it can run."
      ),
      d.jdk.filterNot(_ == "21").map { v =>
        s"this repo builds under JDK $v, but the litter-box base image ships temurin 21. Either " +
          "add your JDK in .litter-box/Dockerfile or confirm 21 is fine."
      }
    ).flatten

  /** Printed on success. The three things a fresh scaffold cannot do for itself. */
  def nextSteps(d: Detected): List[String] =
    List(
      s"fill in $Dir/prompts/conventions.md — it is spliced into every prompt and is the highest-value file here",
      s"cp $Dir/.env.example $Dir/.env and fill in one credential",
      "create the labels: gh label create ready && gh label create in-progress && gh label create blocked"
    )

  /** Writes the scaffold. Returns the paths written, or the reason nothing was.
    *
    * The existence check covers the WHOLE directory and happens before the first write, so a
    * refused `init` is guaranteed to have changed nothing. Checking per file instead would let a
    * refusal land halfway, which is the state hardest to recover from: neither scaffolded nor
    * untouched, and no way to tell which files are yours.
    */
  def run(root: Path, d: Detected, force: Boolean): Either[String, List[String]] =
    val dir = root.resolve(Dir)
    if Files.exists(dir) && !force then
      Left(s"$dir already exists — pass --force to overwrite it")
    else
      val files = plan(d)
      files.foreach { case (rel, content) =>
        val p = root.resolve(rel)
        Files.createDirectories(p.getParent)
        Files.write(
          p,
          content.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
      }
      Right(files.map(_._1))

  /** A scaffold template out of the artifact. Same reasoning as `Prompts.builtIn`: a missing
    * resource is a broken build, not a user error.
    */
  private def resource(name: String): String =
    val res = s"/scaffold/$name"
    Option(getClass.getResourceAsStream(res)) match
      case Some(in) =>
        try new String(in.readAllBytes(), StandardCharsets.UTF_8)
        finally in.close()
      case None =>
        throw IllegalStateException(s"scaffold resource missing from the artifact: $res")
```

- [ ] **Step 5: Run test to verify it passes**

Run: `scala-cli test . --test-only 'in.rcard.litterbox.InitSpec'`
Expected: PASS.

If "produce a config the slice-2 loader accepts" fails on a HOCON parse error, the `{{GATE}}`
splice produced invalid HOCON — check that the multi-line non-sbt comment block is indented inside
the `gate { }` braces.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: Add litter-box init

Scaffolds .litter-box/ with a config the slice-2 loader accepts, a
Dockerfile FROM the base image, an allowlist, an empty conventions file,
an env example carrying no values, and a gitignore.

Refuses an existing .litter-box/ without --force, and checks before the
first write so a refusal cannot leave a half-scaffold.

A repo with no build.sbt gets a TODO Dockerfile, a gate of `false`, and a
warning. Not an sbt preset: gate.fast is written explicitly rather than
omitted, because omitting it would fall back to the reference default and
silently hand a Gradle repo an sbt gate.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Wire the commands into `Main`

**Files:**
- Modify: `src/Main.scala:233-244`
- Test: `test/MainSpec.scala` (append)

**Interfaces:**
- Consumes: `Cli.parse`, `Cli.Usage`, `Command` (Task 4), `Init.run`/`detect`/`warnings`/`nextSteps` (Task 5), `Prompts.eject` (Task 1), `Main.resolveRepoRoot`.
- Produces: `Main.applyDryRunFlag(flagged: Boolean, env: Map[String, String]): Boolean`.

- [ ] **Step 1: Write the failing test**

Append to `test/MainSpec.scala`:

```scala
  "the --dry-run flag" should "turn dry-run on" in:
    Main.applyDryRunFlag(flagged = true, Map.empty) shouldBe true

  it should "leave DRY_RUN=1 alone when absent" in:
    Main.applyDryRunFlag(flagged = false, Map("DRY_RUN" -> "1")) shouldBe true

  it should "never turn an operator's DRY_RUN=1 off" in:
    // The flag is one-way on purpose. An invocation that could silently disarm a dry run is an
    // invocation that mutates a repo somebody believed was safe.
    Main.applyDryRunFlag(flagged = false, Map("DRY_RUN" -> "1")) shouldBe true

  it should "be off when neither says otherwise" in:
    Main.applyDryRunFlag(flagged = false, Map("DRY_RUN" -> "0")) shouldBe false
    Main.applyDryRunFlag(flagged = false, Map.empty) shouldBe false
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scala-cli test . --test-only 'in.rcard.litterbox.MainSpec'`
Expected: FAIL to compile, "value applyDryRunFlag is not a member of object Main".

- [ ] **Step 3: Add the dry-run fold and the subcommand runners to `Main`**

Insert before `@main def litterBoxLoop` in `src/Main.scala`:

```scala
  /** `--dry-run` ORs with `DRY_RUN=1` rather than replacing it.
    *
    * One-way on purpose: the flag can turn a dry run ON, never off. `DRY_RUN=1` is what an operator
    * exports when they want to be sure nothing mutates, and `sandbox/test/*` sets it the same way;
    * a flag able to clear it would be a way to mutate a repo somebody believed was safe.
    */
  private[litterbox] def applyDryRunFlag(flagged: Boolean, env: Map[String, String]): Boolean =
    flagged || env.getOrElse("DRY_RUN", "0") == "1"

  /** `litter-box init`. Runs before every preflight the loop does: a repo with no config is the
    * whole reason to run this, so requiring one would be circular, and there is no reason to insist
    * on Docker or a credential to write six files.
    */
  private def runInit(force: Boolean): Int =
    val cwd  = Paths.get("").toAbsolutePath
    val root = resolveRepoRoot(() => LiveProc.run(cwd, Seq("git", "rev-parse", "--show-toplevel")))
    root match
      case Left(msg) => LiveLog.log(s"FATAL: $msg"); 1
      case Right(r)  =>
        val detected = Init.detect(r, args => LiveProc.run(r, args))
        Init.run(r, detected, force) match
          case Left(msg)      => LiveLog.log(s"FATAL: $msg"); 1
          case Right(written) =>
            written.foreach(p => LiveLog.log(s"wrote $p"))
            Init.warnings(detected).foreach(w => LiveLog.log(s"WARNING: $w"))
            LiveLog.log("next steps:")
            Init.nextSteps(detected).foreach(s => LiveLog.log(s"  - $s"))
            0
```

And the eject runner:

```scala
  /** `litter-box eject <prompt>`. Same reasoning as `runInit` for skipping preflight. */
  private def runEject(what: String, force: Boolean): Int =
    val cwd  = Paths.get("").toAbsolutePath
    resolveRepoRoot(() => LiveProc.run(cwd, Seq("git", "rev-parse", "--show-toplevel"))) match
      case Left(msg) => LiveLog.log(s"FATAL: $msg"); 1
      case Right(r)  =>
        Prompts.eject(r, what, force) match
          case Left(msg)   => LiveLog.log(s"FATAL: $msg"); 1
          case Right(dest) =>
            LiveLog.log(s"wrote ${r.relativize(dest)} — it now overrides the built-in")
            0
```

- [ ] **Step 4: Turn `litterBoxLoop` into a dispatcher**

Replace the `@main def litterBoxLoop(): Unit =` signature and the first lines of its body
(`src/Main.scala:233-235`):

```scala
  @main def litterBoxLoop(args: String*): Unit =
    Cli.parse(args.toList) match
      case Left(msg) =>
        LiveLog.log(s"FATAL: $msg")
        Console.err.println(Cli.Usage)
        sys.exit(1)
      case Right(Command.Help) =>
        Console.out.println(Cli.Usage)
        sys.exit(0)
      case Right(Command.Init(force))         => sys.exit(runInit(force))
      case Right(Command.Eject(what, force))  => sys.exit(runEject(what, force))
      case Right(Command.Loop(dryRun))        => runLoop(dryRun)

  /** The loop, which is everything this file did before there were subcommands. */
  private def runLoop(dryRunFlag: Boolean): Unit =
    val env = sys.env
```

The rest of the original body follows unchanged, except the one line that builds `cfg` in step 2.
Change the `parseEnv` call site so the flag reaches `Config.dryRun`:

```scala
    val parsed0 = parseEnv(fromFile, env)
    val parsed  = parsed0.copy(cfg =
      parsed0.cfg.copy(dryRun = applyDryRunFlag(dryRunFlag, env))
    )
```

- [ ] **Step 5: Run the full suite**

Run: `scala-cli test .`
Expected: PASS.

- [ ] **Step 6: Verify the CLI end to end by hand**

```bash
scala-cli run . -- --help
```
Expected: the usage text, exit 0.

```bash
scala-cli run . -- frobnicate; echo "rc=$?"
```
Expected: `FATAL: unknown command: frobnicate`, the usage text, `rc=1`.

```bash
cd "$(mktemp -d)" && git init -q . && touch build.sbt \
  && (cd - >/dev/null && scala-cli --power package . -o /tmp/lb --assembly -f) \
  && java -jar /tmp/lb init && find .litter-box -type f | sort
```
Expected: six files under `.litter-box/`, a warning about the missing GitHub remote, and the three
next steps.

```bash
java -jar /tmp/lb init; echo "rc=$?"
```
Expected: `FATAL: ... already exists — pass --force to overwrite it`, `rc=1`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: Dispatch init, eject and help from the command line

litterBoxLoop takes args now. init and eject run before every preflight the
loop does: a repo with no config is the whole reason to run init, so
requiring one would be circular.

--dry-run ORs with DRY_RUN=1 rather than replacing it. The flag can turn a
dry run on, never off; a flag able to clear an operator's DRY_RUN=1 would
be a way to mutate a repo somebody believed was safe.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Split the base image out of the sandbox Dockerfile

**Files:**
- Create: `sandbox/base.Dockerfile`, `.github/workflows/base-image.yml`, `docs/base-image.md`
- Modify: `sandbox/Dockerfile`, `sandbox/build-image.sh`, `sandbox/lib.sh`
- Test: `sandbox/test/image-smoke-test.sh` (manual, Docker-dependent)

**Interfaces:**
- Consumes: `.litter-box/allowlist` written by `Init.plan` (Task 5).
- Produces: `sandbox/lib.sh` gains `BASE_IMAGE`, used by `build-image.sh` and by `sandbox/Dockerfile`'s `ARG`.

- [ ] **Step 1: Write `sandbox/base.Dockerfile`**

Move lines 1-16 and 25-44 of the current `sandbox/Dockerfile` into it, dropping the sbt block
(lines 18-23) and the `ENTRYPOINT` (line 48):

```dockerfile
# litter-box-base — the part of the gate sandbox that is not about any one project.
#
# Carries a JDK, the pinned Claude CLI, and a non-root user to run as. Carries NO build tool, NO
# ENTRYPOINT and NO credentials of any kind. Those three absences are the contract: a consumer's
# own Dockerfile does `FROM` this image, installs whatever it builds with, and sets its own
# ENTRYPOINT. See docs/base-image.md.
#
# JDK 21 LTS matches the loop's own `//> using jvm temurin:21`.
FROM eclipse-temurin:21-jdk

ARG CLAUDE_INSTALL_URL=https://claude.ai/install.sh
# Pinned so image rebuilds are reproducible; the installer verifies the downloaded binary's
# checksum against its release manifest. Bump deliberately, together with the image tag.
ARG CLAUDE_VERSION=2.1.207

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git ca-certificates gnupg \
    && rm -rf /var/lib/apt/lists/*

# Claude Code CLI: the native installer downloads a single self-contained (Bun-compiled)
# binary and runs `<binary> install`, which places it under $HOME/.local/share/claude/versions
# and symlinks $HOME/.local/bin/claude. Run it under a throwaway HOME (root's install would
# not be reachable by the non-root `gate` user below), then lift the single binary out to
# /usr/local/bin so every user can exec it.
RUN mkdir -p /opt/claude-install \
    && HOME=/opt/claude-install bash -c "curl -fsSL '$CLAUDE_INSTALL_URL' | bash -s '$CLAUDE_VERSION'" \
    && claude_bin="$(find /opt/claude-install -maxdepth 6 -type f -perm -u+x -name 'claude' | head -1)" \
    && if [ -z "$claude_bin" ]; then \
         claude_bin="$(find /opt/claude-install/.local/share/claude/versions -maxdepth 1 -type f | head -1)"; \
       fi \
    && test -n "$claude_bin" \
    && install -m 0755 "$claude_bin" /usr/local/bin/claude \
    && rm -rf /opt/claude-install

# Non-root user; gate containers never run as root (cheap defense-in-depth, see run-fast-gate.sh).
# uid 10001 is part of the contract: consumer Dockerfiles chown into /home/gate.
RUN useradd -m -u 10001 -s /bin/bash gate \
    && mkdir -p /home/gate/.cache/coursier \
    && chown -R gate:gate /home/gate

USER gate
WORKDIR /workspace
```

- [ ] **Step 2: Shrink `sandbox/Dockerfile` to the sbt layer**

Replace the whole file:

```dockerfile
# The gate container for litter-box's own repo: the base image plus sbt.
#
# The build tool lives HERE and nowhere else. Before slice 3 this file ended in
# `ENTRYPOINT ["sbt"]`, which made "the litter-box sandbox" and "an sbt container" the same thing
# and forced a Gradle consumer to fork the whole Dockerfile.
#
# BASE_IMAGE defaults to the tag build-image.sh builds locally from base.Dockerfile, so a checkout
# can build the whole stack with no registry involved. A consumer's scaffolded Dockerfile points at
# ghcr.io/rcardin/litter-box-base instead, because a consumer has no sandbox/ directory.
ARG BASE_IMAGE=litter-box-base:v1
FROM ${BASE_IMAGE}

ARG SBT_VERSION=1.12.9

USER root
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
      -o /tmp/sbt.tgz \
    && tar -xzf /tmp/sbt.tgz -C /usr/local \
    && rm /tmp/sbt.tgz \
    && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

USER gate
WORKDIR /workspace
ENTRYPOINT ["sbt"]
```

- [ ] **Step 3: Teach `sandbox/lib.sh` the base tag**

Add after the `IMAGE=` line:

```bash
# The build-tool-free base layer (sandbox/base.Dockerfile). Built locally by build-image.sh and
# consumed by Dockerfile's ARG, so a checkout never needs the registry. NOT namespaced by
# INSTANCE_NAME: it carries nothing instance-specific, so two instances sharing it is correct and
# saves a multi-minute Claude CLI install per instance.
BASE_IMAGE="litter-box-base:v1"
```

- [ ] **Step 4: Build the base image first in `sandbox/build-image.sh`**

Insert before the existing `log "building $IMAGE ..."` block:

```bash
log "building $BASE_IMAGE ..."
docker build -t "$BASE_IMAGE" -f "$SANDBOX_DIR/base.Dockerfile" "$SANDBOX_DIR"
```

And change the gate image build to pass the base through:

```bash
log "building $IMAGE ..."
docker build --build-arg "BASE_IMAGE=$BASE_IMAGE" -t "$IMAGE" -f "$SANDBOX_DIR/Dockerfile" "$SANDBOX_DIR"
```

Update the final log line to `log "images built: $BASE_IMAGE, $IMAGE, $PROXY_IMAGE"`.

- [ ] **Step 5: Let `build-image.sh` prefer the scaffolded allowlist**

`init` writes `.litter-box/allowlist`, so something has to read it. `sandbox/proxy/Dockerfile:5`
`COPY`s the allowlist into the proxy image at BUILD time, so the override belongs here and not in
`start-proxy.sh` — by the time the proxy starts, the list is already baked into the image.

Replace the proxy build block in `sandbox/build-image.sh`:

```bash
# The egress allowlist. `litter-box init` writes .litter-box/allowlist into the repo, so that is
# the file an operator edits and it wins. sandbox/proxy/allowlist is the fallback for a checkout
# that has never been scaffolded.
#
# The list is COPYed into the image (proxy/Dockerfile), not read at run time, so overriding it
# means building from a staged context rather than pointing a flag at a path. mktemp -d, not an
# in-place copy over sandbox/proxy/allowlist: that would leave a dirty tracked file behind after
# every build, and the loop refuses to start on an unclean tree.
REPO_ROOT="$(cd -- "$SANDBOX_DIR/.." && pwd)"
proxy_ctx="$SANDBOX_DIR/proxy"
if [[ -f "$REPO_ROOT/.litter-box/allowlist" ]]; then
  proxy_ctx="$(mktemp -d)"
  trap 'rm -rf "$proxy_ctx"' EXIT
  cp "$SANDBOX_DIR/proxy/Dockerfile" "$SANDBOX_DIR/proxy/tinyproxy.conf" "$proxy_ctx/"
  cp "$REPO_ROOT/.litter-box/allowlist" "$proxy_ctx/allowlist"
  log "using allowlist $REPO_ROOT/.litter-box/allowlist"
fi

log "building $PROXY_IMAGE ..."
docker build -t "$PROXY_IMAGE" -f "$proxy_ctx/Dockerfile" "$proxy_ctx"
```

- [ ] **Step 5b: Verify the allowlist override reaches the image**

```bash
printf 'example.invalid\n' > .litter-box/allowlist
./sandbox/build-image.sh
docker run --rm --entrypoint cat litter-box-sandbox-proxy:v6 /etc/tinyproxy/allowlist
```
Expected: `example.invalid`.

Then restore this repo's real list and confirm the fallback still works:
```bash
rm .litter-box/allowlist
./sandbox/build-image.sh
docker run --rm --entrypoint cat litter-box-sandbox-proxy:v6 /etc/tinyproxy/allowlist
```
Expected: the five hosts from `sandbox/proxy/allowlist`, starting `api.anthropic.com`.

Note: litter-box's own repo deliberately does NOT get a `.litter-box/allowlist`. Its gate needs the
Maven and sbt hosts that `sandbox/proxy/allowlist` already carries, and the scaffold template ships
only `api.anthropic.com`.

- [ ] **Step 6: Write `docs/base-image.md`**

```markdown
# `ghcr.io/rcardin/litter-box-base`

The part of the litter-box gate sandbox that is not about any one project. Built from
`sandbox/base.Dockerfile`, published on tag by `.github/workflows/base-image.yml`.

## What it guarantees

| | |
|---|---|
| JDK | temurin 21 (LTS), on `PATH` |
| Claude CLI | pinned, at `/usr/local/bin/claude`, executable by every user |
| User | non-root `gate`, uid 10001, home `/home/gate`, owner of `/home/gate/.cache/coursier` |
| `WORKDIR` | `/workspace` |
| `USER` | `gate` |
| Base | `eclipse-temurin:21-jdk` (Debian) with `curl`, `git`, `ca-certificates`, `gnupg` |

## What it deliberately does not have

- **No build tool.** Not sbt, not Gradle, not Maven, not npm. That is the consumer's layer.
- **No `ENTRYPOINT`.** The consumer sets it to their build tool.
- **No credentials.** No API key, no OAuth token, no `gh` token, no registry login. Credentials
  reach a running container as environment variables at `docker run` time and are never baked in.
  See `sandbox/lib.sh:sandbox_credential_env`.

## Using it

```dockerfile
ARG BASE_IMAGE=ghcr.io/rcardin/litter-box-base:0.1.0
FROM ${BASE_IMAGE}

USER root
# install your build tool, pinned to an exact version
USER gate
WORKDIR /workspace
ENTRYPOINT ["your-build-tool"]
```

`litter-box init` writes exactly this file, filled in, to `.litter-box/Dockerfile`.

## Adding a preset

`init` scaffolds an sbt Dockerfile because sbt is the only build tool a litter-box loop has
actually been run against end to end. A Gradle or Maven preset is a PR: add a case to
`Init.BuildTool`, its install block and entrypoint in `Init.dockerfile`, its gate command in
`Init.configConf`, and a detection rule in `Init.detect`. Adding a case is a claim that you ran the
loop with it, so please say so in the PR.

## Bumping the Claude CLI

`CLAUDE_VERSION` is an explicit `ARG` in `sandbox/base.Dockerfile`. Bump it in a commit of its own,
together with the image tag, so a broken CLI release is one revert.
```

- [ ] **Step 7: Write `.github/workflows/base-image.yml`**

```yaml
name: base image

# Tag-triggered only. The base image is a published artifact other people's builds depend on, so it
# is never rebuilt by a branch push: the tag IS the version.
on:
  push:
    tags: ["v*"]

jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4

      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - id: meta
        uses: docker/metadata-action@v5
        with:
          images: ghcr.io/rcardin/litter-box-base
          tags: |
            type=semver,pattern={{version}}
            type=semver,pattern={{major}}.{{minor}}

      - uses: docker/build-push-action@v6
        with:
          context: sandbox
          file: sandbox/base.Dockerfile
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
```

- [ ] **Step 8: Verify the split builds**

Run: `./sandbox/build-image.sh`
Expected: three images built, ending `images built: litter-box-base:v1, litter-box-sandbox:v6, litter-box-sandbox-proxy:v6`.

Run: `docker run --rm --entrypoint claude litter-box-base:v1 --version`
Expected: a version string.

Run: `docker run --rm --entrypoint sh litter-box-base:v1 -c 'command -v sbt || echo "no sbt (correct)"'`
Expected: `no sbt (correct)`.

Run: `docker run --rm litter-box-sandbox:v6 --version`
Expected: sbt's version banner, proving the `ENTRYPOINT` survived the split.

- [ ] **Step 9: Run the full suite**

Run: `scala-cli test .`
Expected: PASS. Nothing in this task touches Scala, so a failure here means a previous task
regressed.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: Split the build tool out of the sandbox image

sandbox/base.Dockerfile carries the JDK, the pinned Claude CLI and the
non-root gate user, and nothing else: no build tool, no ENTRYPOINT, no
credentials. sandbox/Dockerfile becomes that image plus sbt.

Before this, ENTRYPOINT [\"sbt\"] made 'the litter-box sandbox' and 'an sbt
container' the same thing, so a Gradle consumer had to fork the Dockerfile.

BASE_IMAGE defaults to a locally built tag so a checkout builds the whole
stack with no registry involved; the workflow publishes the same file to
ghcr on a tag, and a scaffolded consumer Dockerfile points there.

build-image.sh now prefers .litter-box/allowlist, which is the file init
writes and the one an operator edits. It goes there and not in
start-proxy.sh because the list is COPYed into the proxy image at build
time, not read when the proxy starts.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: End-to-end verification and the README

**Files:**
- Modify: `README.md`
- Test: manual, plus the full suite

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Run the full suite**

Run: `scala-cli test .`
Expected: PASS, no failures, no ignored tests.

- [ ] **Step 2: Scaffold a fresh repo and reach the dry-run stop**

This is the issue's Done criterion. It needs `gh` authenticated and a real GitHub repo with one
issue labelled `ready`.

```bash
scala-cli --power package . -o /tmp/lb --assembly -f
cd "$(mktemp -d)"
git init -q . && touch build.sbt && git add -A && git commit -qm init
java -jar /tmp/lb init
```
Expected: six files, the missing-remote warning, three next steps.

Then, in a real sbt repo with a GitHub remote, `.litter-box/prompts/conventions.md` filled in, the
three labels created and one issue labelled `ready`:

```bash
GATE_CMD=true java -jar /tmp/lb --dry-run; echo "rc=$?"
```
Expected: the log reaches `DRY_RUN=1 — rendered worker prompt for #N`, then
`dry run reached its stop point — exiting`, `rc=0`. `GATE_CMD` is set so the sandbox preflight is
skipped (no Docker build, no credential needed) — `Main` step 6b.

Confirm nothing was mutated:
```bash
git status --short && git log --oneline -1
```
Expected: clean tree, the same HEAD as before the run.

Confirm the rendered prompt has no unspliced slots:
```bash
grep -c '{{' .litter-box/logs/*.prompt.txt
```
Expected: `0`.

- [ ] **Step 3: Update the README**

Read `README.md` first. Add a "Getting started" section covering `litter-box init`, what lands in
`.litter-box/`, filling in `conventions.md`, creating the three labels, and `litter-box eject`.
Correct any existing text that says prompts live in the consumer repo's `prompts/` directory or
that the sandbox image ships sbt. Link `docs/base-image.md`.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: Document init, eject and the base image in the README

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 5: Open the PR**

```bash
git push -u origin slice-3-scaffold
gh pr create --title "Slice 3: scaffold — init, layered prompts, base image" --body "$(cat <<'EOF'
Closes #4.

## What

- `litter-box init` scaffolds `.litter-box/` — config, Dockerfile, allowlist, conventions,
  env example, gitignore. Refuses an existing directory without `--force`, checking before the
  first write so a refusal cannot leave a half-scaffold.
- Prompt skeletons ship in the artifact instead of the consumer repo, with a
  `.litter-box/prompts/` override that `litter-box eject` writes. The four skeletons are rewritten
  as neutral protocol; everything true only of this repo moved to
  `.litter-box/prompts/conventions.md`.
- `sandbox/Dockerfile` splits into a build-tool-free base image and a thin sbt layer.
  `.github/workflows/base-image.yml` publishes the base on a tag. **Nothing has been pushed to
  ghcr yet** — the first publish happens when a tag is cut.

## Not in this PR

Sandbox shell scripts are still resolved relative to the consumer repo root. Shipping executable
scripts out of a jar needs runtime extraction and exec bits; filed separately.

## Verification

`scala-cli test .` green. `init` on a fresh sbt repo followed by `--dry-run` reaches the dry-run
stop with a clean tree and no unspliced slots in the rendered prompt.

Spec: `docs/superpowers/specs/2026-07-22-slice-3-scaffold-design.md`
Plan: `docs/superpowers/plans/2026-07-22-slice-3-scaffold.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
