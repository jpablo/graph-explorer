package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.command.{LiveViewCommand, LiveViewSelection}
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.models.{ElementId, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

/** A desktop command names its diagram (plan §2).
  *
  * `gx select <file>` and `gx fit <file>` send the canonical path of the file.
  * The page compares it with the diagram on the screen. When the screen shows
  * a different diagram, the page refuses with `VIEW_REJECTED`, and the message
  * names what is on the screen. Before, the page did not read the path, and a
  * command for one file changed the selection of a different diagram.
  *
  * `what-is-selected` answers with the diagram too, so `gx selection` can say
  * which file the selection is in.
  */
class LiveViewTargetSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "LiveViewTargetSpec"

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit =
    LiveViewCommands.reset()
    DesktopDocumentRegistry.reset()

  override def afterEach(context: AfterEach): Unit =
    LiveViewCommands.reset()
    DesktopDocumentRegistry.reset()

  private val a: Set[ElementId] = Set(NodeId("a"))

  private def selected(state: ViewerState): Set[ElementId] = state.selection.now().ids.toSet[ElementId]

  /** The answer of the page to `what-is-selected`, decoded as `gx` decodes it. */
  private def selectionAnswer(): Option[LiveViewSelection] =
    LiveViewCommands.run(LiveViewCommand.WhatIsSelected).toOption.flatMap(v => LiveViewSelection.decode(v).toOption)

  private def refusal(outcome: Either[LiveViewCommands.Refusal, ujson.Value]): Option[(String, String)] =
    outcome.left.toOption.map(r => (r.code, r.message))

  /** A viewer of a file, attached to the live view tier. */
  private def onFile(path: String)(body: ViewerState => Unit) =
    withGraphvizAsync: graphviz =>
      val open  = DesktopDocumentRegistry.record(path, "rev-1", "digraph { a; b }")
      val state = ViewerState(ViewTarget.File(open.id), graphviz)
      LiveViewCommands.attach(state)
      afterMicrotasks(body(state))

  test("select for the file on the screen selects"):
    onFile("/w/arch.dot"): state =>
      val outcome = LiveViewCommands.run(LiveViewCommand.Select("/w/arch.dot", a))
      check(outcome.isRight, s"a select for the file on the screen was refused: $outcome")
      same(selected(state), a, "the select did not reach the viewer")

  test("select for a different file is refused, names the file on the screen, and selects nothing"):
    onFile("/w/shown.dot"): state =>
      val outcome = LiveViewCommands.run(LiveViewCommand.Select("/w/other.dot", a))
      same(refusal(outcome).map(_._1), Some("VIEW_REJECTED"), s"a select for another file was not refused: $outcome")
      val message = refusal(outcome).map(_._2).getOrElse("")
      check(message.contains("/w/shown.dot"), s"the refusal does not name the file on the screen: $message")
      check(message.contains("/w/other.dot"), s"the refusal does not name the file of the command: $message")
      same(selected(state), Set.empty[ElementId], "the select changed the diagram on the screen")

  test("fit for a different file is refused, and fit for the file on the screen is accepted"):
    onFile("/w/shown.dot"): _ =>
      same(
        refusal(LiveViewCommands.run(LiveViewCommand.ResetView("/w/other.dot"))).map(_._1),
        Some("VIEW_REJECTED"),
        "a fit for another file was not refused"
      )
      val outcome = LiveViewCommands.run(LiveViewCommand.ResetView("/w/shown.dot"))
      check(outcome.isRight, s"a fit for the file on the screen was refused: $outcome")

  test("the page compares the canonical forms of the two paths"):
    // One file, two spellings of a Windows path. The library key has one
    // spelling for each file, and the comparison uses it.
    onFile("""C:\w\arch.dot"""): state =>
      val outcome = LiveViewCommands.run(LiveViewCommand.Select("C:/w/arch.dot", a))
      check(outcome.isRight, s"two spellings of one path were not the same file: $outcome")
      same(selected(state), a, "the select did not reach the viewer")

  test("select is refused when a browser diagram is on the screen, and the message names it"):
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.diagram("groups"), graphviz)
      LiveViewCommands.attach(state)
      afterMicrotasks:
        state.storedName.set("Groups")
        val outcome = LiveViewCommands.run(LiveViewCommand.Select("/w/arch.dot", a))
        same(refusal(outcome).map(_._1), Some("VIEW_REJECTED"), s"a select at a browser diagram was not refused: $outcome")
        val message = refusal(outcome).map(_._2).getOrElse("")
        check(message.contains("the browser diagram \"Groups\""), s"the refusal does not name the browser diagram: $message")
        same(selected(state), Set.empty[ElementId], "the select changed the browser diagram")

  test("select is refused when an example is on the screen, and the message says so"):
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.Example("logo", "Logo"), graphviz, initialSource = Some("digraph { a }"))
      LiveViewCommands.attach(state)
      afterMicrotasks:
        val outcome = LiveViewCommands.run(LiveViewCommand.Select("/w/arch.dot", a))
        same(refusal(outcome).map(_._1), Some("VIEW_REJECTED"), s"a select at an example was not refused: $outcome")
        val message = refusal(outcome).map(_._2).getOrElse("")
        check(message.contains("an example (\"Logo\")"), s"the refusal does not say that an example is on the screen: $message")

  test("a command with nothing on the screen is still NO_LIVE_VIEW"):
    same(
      refusal(LiveViewCommands.run(LiveViewCommand.Select("/w/arch.dot", a))).map(_._1),
      Some("NO_LIVE_VIEW"),
      "a select with no diagram on the screen"
    )

  test("what is selected answers with the path of the file on the screen and the refs"):
    onFile("/w/arch.dot"): state =>
      state.selection.set1(a)
      same(selectionAnswer(), Some(LiveViewSelection(Some("/w/arch.dot"), "/w/arch.dot", Vector("node:a"))), "the answer of the page")

  test("what is selected says when the screen shows no file"):
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.Example("logo", "Logo"), graphviz, initialSource = Some("digraph { a }"))
      LiveViewCommands.attach(state)
      afterMicrotasks:
        same(selectionAnswer(), Some(LiveViewSelection(None, "an example (\"Logo\")", Vector.empty)), "the answer of the page")
