package harness

import scala.collection.mutable

/** Scripted in-memory handlers for every capability, plus an interaction recorder.
  *
  * Scenarios assert on BOTH the outcome (LoopExit, labels flipped, PR opened, budget left)
  * and the interaction sequence (`calls`): no FIX after an infra fault, no merge without
  * verification, marker staged on a guard rejection.
  *
  * Patch contents use a tiny numstat DSL: each line `added<TAB>deleted<TAB>path` (or `-` for
  * binary). FakeGit's applyNumstat returns exactly the well-formed lines — so garbage content
  * yields an empty numstat (the bash fail-open) — and applyIndex succeeds only when the whole
  * patch is well-formed and `applySucceeds` is not scripted false.
  */
object Script:
  /** What a scripted worker/fixer dispatch does. */
  enum WorkerScript:
    /** Writes `content` (numstat DSL or garbage) to patchOut. */
    case Produces(content: String)
    /** Writes nothing: the agent produced no diff. */
    case Empty
    /** Simulates the container-dispatch timeout (rc 124). */
    case TimedOut

  /** What a scripted reviewer dispatch does. */
  enum ReviewScript:
    /** Writes `output` to the review file. */
    case Says(output: String)
    /** Simulates the reviewer dispatch timeout (rc 124). */
    case TimedOut

  val newFilePatch: String  = "1\t0\tsrc/main/scala/Slice.scala"
  val approveReview: String = "checked AC1/AC2, tests present.\nVERDICT: APPROVE"

final class TestWorld:
  import Script.*

  // ---- recorder --------------------------------------------------------------------------
  val calls: mutable.ArrayBuffer[String]         = mutable.ArrayBuffer.empty
  val notifications: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val events: mutable.ArrayBuffer[StatusEvent]   = mutable.ArrayBuffer.empty
  val files: mutable.Map[String, String]         = mutable.Map.empty

  def record(c: String): Unit = calls += c
  def called(needle: String): Boolean = calls.exists(_.contains(needle))
  def callCount(needle: String): Int  = calls.count(_.contains(needle))

  /** Phase sequence with consecutive duplicates collapsed (the bash suite's phase_seq). */
  def phaseSeq: List[String] =
    events.map(_.phase).foldLeft(List.empty[String]) { (acc, p) =>
      if acc.lastOption.contains(p) then acc else acc :+ p
    }

  /** phase:state sequence, collapsing only exact consecutive duplicates (phase_state_seq). */
  def phaseStateSeq: List[String] =
    events.map(e => s"${e.phase}:${e.state}").foldLeft(List.empty[String]) { (acc, p) =>
      if acc.lastOption.contains(p) then acc else acc :+ p
    }

  // ---- script knobs (defaults = the happy APPROVE path on issue 999) ----------------------
  var stopFile: Boolean                = false
  var inProgress: Option[Int]          = None
  var ready: Option[Int]               = Some(999)
  var titleBody: String                = "# US-999 sample\n\nAC1: implement the slice.\nAC2: cover it with a test."
  var labels: List[String]             = List("ready")
  var implScript: WorkerScript         = WorkerScript.Produces(newFilePatch)
  var fixScripts: List[WorkerScript]   = Nil
  var reviewScripts: List[ReviewScript] = List(ReviewScript.Says(approveReview))
  var gateResults: List[GateResult]    = Nil // empty => Green forever
  var ciWaitResult: GateResult         = GateResult.Green
  var rollupCounts: List[Int]          = List(1) // last value repeats
  var prUrl: String                    = "https://github.com/test/test/pull/123"
  var prStateAnswer: String            = "MERGED"
  var mergeSucceeds: Boolean           = true
  var applySucceeds: Boolean           = true
  var cleanTree: Boolean               = true
  var fetchSucceeds: Boolean           = true
  var blockedIssues: List[Int]         = Nil
  var issueBodies: Map[Int, String]    = Map.empty
  var issueStates: Map[Int, String]    = Map.empty // default CLOSED
  var templates: Map[Template, String] = Map(
    Template.Iterate -> "You are the worker. Fresh context.\n{{ISSUE}}\nProduce a patch.",
    Template.Fix     -> "You are the fixer.\n{{ISSUE}}\n{{FAILURE}}\nProduce a patch.",
    Template.Review  -> "Cold review.\n{{ISSUE}}\n{{CONVENTIONS}}\n{{TAMPER}}\n{{DIFF}}\nEmit a VERDICT."
  )
  var conventionsText: String = "# CONTEXT\nConventions: onion layout, use-case error enum."

  // ---- derived state the fakes maintain ---------------------------------------------------
  var appliedPatches: List[String] = Nil
  var staged: Boolean              = false
  var commitMessages: List[String] = Nil
  var pushedBranches: List[String] = Nil
  var prBodies: List[String]       = Nil
  var sleeps: List[Int]            = Nil

  private def isNumstatLine(l: String): Boolean =
    NumstatRow.parse(l).exists { row =>
      (row.added.matches("\\d+") || row.added == "-") && (row.deleted.matches("\\d+") || row.deleted == "-")
    }

  // ---- capability instances ---------------------------------------------------------------
  val github: GitHub = new GitHub:
    def inProgressIssue(): Option[Int] =
      record("gh issue list --label in-progress"); inProgress
    def oldestReadyIssue(): Option[Int] =
      record("gh issue list --label ready"); ready
    def issueTitleAndBody(issue: Int): String =
      record(s"gh issue view $issue --json title,body"); titleBody
    def issueBody(issue: Int): String =
      record(s"gh issue view $issue --json body"); issueBodies.getOrElse(issue, "")
    def issueLabels(issue: Int): List[String] =
      record(s"gh issue view $issue --json labels"); labels
    def issueState(issue: Int): String =
      record(s"gh issue view $issue --json state"); issueStates.getOrElse(issue, "CLOSED")
    def editLabels(issue: Int, add: List[String], remove: List[String]): Boolean =
      val a = add.map(l => s" --add-label $l").mkString
      val r = remove.map(l => s" --remove-label $l").mkString
      record(s"gh issue edit $issue$a$r"); true
    def openBlockedIssues(): List[Int] =
      record("gh issue list --label blocked"); blockedIssues
    def createPr(branch: String, title: String, body: String): String =
      record(s"gh pr create --head $branch --title $title"); prBodies = prBodies :+ body; prUrl
    def prComment(pr: Int, body: String): Unit =
      record(s"gh pr comment $pr")
    def prState(pr: Int): String =
      record(s"gh pr view $pr --json state"); prStateAnswer
    def checksRollupCount(pr: Int): Option[Int] =
      record(s"gh pr view $pr --json statusCheckRollup")
      rollupCounts match
        case Nil      => Some(1)
        case h :: Nil => Some(h)
        case h :: t   => rollupCounts = t; Some(h)
    def merge(pr: Int): Boolean =
      record(s"gh pr merge $pr --squash --delete-branch"); mergeSucceeds

  val git: Git = new Git:
    def statusClean(): Boolean = { record("git status --porcelain"); cleanTree }
    def fetchOriginMain(): Boolean = { record("git fetch origin main"); fetchSucceeds }
    def checkoutBranch(branch: String): Boolean = { record(s"git checkout $branch"); true }
    def resetHardCleanToOriginMain(): Unit =
      record("git reset --hard origin/main && git clean -fd"); staged = false
    def applyNumstat(patch: String): String =
      record(s"git apply --numstat $patch")
      files.getOrElse(patch, "").linesIterator.filter(isNumstatLine).mkString("\n")
    def applyIndex(patch: String): Boolean =
      record(s"git apply --index $patch")
      val content = files.getOrElse(patch, "")
      val ok = applySucceeds && content.linesIterator.nonEmpty &&
        content.linesIterator.forall(isNumstatLine)
      if ok then
        appliedPatches = appliedPatches :+ patch
        staged = true
      ok
    def add(path: String): Unit = { record(s"git add $path"); staged = true }
    def addAll(): Unit = record("git add -A")
    def diffCachedOriginMain(): String =
      record("git diff --cached origin/main")
      appliedPatches.lastOption.map(files.getOrElse(_, "")).getOrElse("")
    def anythingStaged(): Boolean = { record("git diff --cached --quiet HEAD"); staged }
    def commit(message: String): Unit =
      record("git commit"); commitMessages = commitMessages :+ message
    def push(branch: String): Unit =
      record(s"git push -u origin $branch"); pushedBranches = pushedBranches :+ branch

  val agents: AgentDispatch = new AgentDispatch:
    def worker(role: Role, promptFile: String, patchOut: String, currentPatch: Option[String]): DispatchOutcome =
      record(s"dispatch $role promptFile=$promptFile patchOut=$patchOut currentPatch=${currentPatch.getOrElse("")}")
      val script = role match
        case Role.IMPL => implScript
        case Role.FIX =>
          fixScripts match
            case Nil    => WorkerScript.Empty
            case h :: t => fixScripts = t; h
      script match
        case WorkerScript.Produces(content) =>
          files(patchOut) = content; DispatchOutcome.Done
        case WorkerScript.Empty    => DispatchOutcome.Done
        case WorkerScript.TimedOut => DispatchOutcome.TimedOut
    def review(prompt: String, reviewFile: String): DispatchOutcome =
      record(s"dispatch REVIEW reviewFile=$reviewFile")
      reviewScripts match
        case Nil => DispatchOutcome.Done
        case h :: t =>
          reviewScripts = t
          h match
            case ReviewScript.Says(out) => files(reviewFile) = out; DispatchOutcome.Done
            case ReviewScript.TimedOut  => DispatchOutcome.TimedOut

  val gates: GateRunner = new GateRunner:
    def run(label: String, cmd: String, timeoutSec: Int, logFile: String): GateResult =
      record(s"gate $label cmd=$cmd log=$logFile")
      if label == "CI-WAIT" then ciWaitResult
      else
        gateResults match
          case Nil    => GateResult.Green
          case h :: t => gateResults = t; h

  val status: StatusLog = new StatusLog:
    def append(event: StatusEvent): Unit = events += event

  val notifier: Notify = new Notify:
    def notify(msg: String): Unit = { record(s"notify $msg"); notifications += msg }

  val fs: HarnessFs = new HarnessFs:
    def stopRequested(): Boolean = stopFile
    def readTemplate(template: Template): String = templates(template)
    def conventions(): String = conventionsText
    def write(path: String, content: String): Unit = files(path) = content
    def read(path: String): String = files.getOrElse(path, "")
    def sizeBytes(path: String): Long = files.get(path).map(_.length.toLong).getOrElse(0L)

  val clock: Clock = new Clock:
    def sleepSeconds(s: Int): Unit = sleeps = sleeps :+ s
