package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.command.SourceCommand
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.models.{ElementId, NodeId}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}

import scala.concurrent.ExecutionContext.Implicits.global

/** A canvas edit in Mermaid mode must keep the title of the diagram.
  *
  * The title of a Mermaid flowchart is in the YAML frontmatter (`title: ...`). A canvas edit writes the whole graph
  * again (`InternalPhases.applyGraphEdit`), and the new text replaces the old text. Thus the graph must keep the
  * title. The graph comes from real Mermaid (`MermaidBackend.textToGraph`).
  */
class MermaidCanvasEditTitleSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "MermaidCanvasEditTitleSpec"

  override def munitFixtures = List(mockStorageFixture())

  private val source =
    """---
      |title: Release plan
      |---
      |flowchart LR
      |  api[API] --> db[DB]""".stripMargin

  test("a canvas edit in Mermaid mode keeps the frontmatter title"):
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.diagram("local"), graphviz, initialSource = Some(source))
      def parsed =
        state.loadStatus.now() == DiagramLoadStatus.Ready && state.phases.fullGraphV.now().nodeIds.contains(NodeId("api"))
      Waits.settle(parsed).map: _ =>
        check(parsed, s"precondition: the Mermaid parse did not end. The status is ${state.loadStatus.now()}")
        same(state.formatSelection.now(), DiagramFormat.Mermaid, "precondition: the document is Mermaid")
        same(state.sourceText.now(), source, "precondition: the viewer shows the document")
        // The toolbar color control does this.
        state.runSourceCommand(SourceCommand.SetAttribute(Set[ElementId](NodeId("api")), "color", "red"))
      .flatMap: _ =>
        afterMicrotasks:
          val text = state.sourceText.now()
          check(text != source, s"precondition: the canvas edit did not change the text:\n$text")
          check(text.contains("stroke:red"), s"precondition: the text does not have the edit:\n$text")
          same(MermaidBackend().extractTitle(text), Some("Release plan"), s"the canvas edit dropped the title:\n$text")
