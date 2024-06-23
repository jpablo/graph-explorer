package org.jpablo.typeexplorer.viewer

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import com.softwaremill.quicklens.*
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.typeexplorer.viewer.components.state.*
import org.jpablo.typeexplorer.viewer.components.{SvgDiagram, TopLevel}
import org.jpablo.typeexplorer.viewer.examples.Example1
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.scalajs.dom


object Viewer:

  def main(args: Array[String]): Unit =
    val graph = Signal.fromValue(Example1.diagram)
    val viz = new Graphviz
    val appElem = createApp(graph, viz.renderDot)
    render(dom.document.querySelector("#app"), appElem)

  private def createApp(
      graph:     Val[ViewerGraph],
      renderDot: String => Signal[SvgDiagram]
  ): ReactiveHtmlElement[dom.HTMLDivElement] =
    given Owner = unsafeWindowOwner
    val id = ProjectId("project-0")
    val allSymbols = graph.now().nodeIds.map(_ -> None).toMap
    val project = Project(id).modify(_.page.activeSymbols).setTo(allSymbols)
    val appState = AppState(Var(PersistedAppState(project, "")), graph)
    val viewerState = ViewerState(appState.activeProject.pageV, appState.fullGraph, renderDot)
    TopLevel(appState, viewerState, graph, viewerState.svgDiagram)

  private def setupErrorHandling()(using Owner): EventBus[String] =
    val errors = new EventBus[String]
    AirstreamError.registerUnhandledErrorCallback: ex =>
      errors.emit(ex.getMessage)
    windowEvents(_.onError).foreach: e =>
      errors.emit(e.message)
    errors
