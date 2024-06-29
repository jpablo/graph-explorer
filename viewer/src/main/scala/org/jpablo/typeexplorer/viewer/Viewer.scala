package org.jpablo.typeexplorer.viewer

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import com.softwaremill.quicklens.*
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.typeexplorer.viewer.components.state.*
import org.jpablo.typeexplorer.viewer.components.{SvgDiagram, TopLevel}
import org.jpablo.typeexplorer.viewer.examples.Example1
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.utils.CSVToArray
import org.scalajs.dom

object Viewer:

  def main(args: Array[String]): Unit =
//    Signal.fromValue(Example1.graph).
    val appElem =
      createApp(
//        graph = Signal.fromValue(Example1.graph),
        renderDot = (new Graphviz).renderDot
      )
    render(dom.document.querySelector("#app"), appElem)

  private def createApp(
      renderDot: String => Signal[SvgDiagram]
  ): ReactiveHtmlElement[dom.HTMLDivElement] =
    given Owner = unsafeWindowOwner
    val id = ProjectId("project-0")
    val replaceText: Var[String] = Var("")
    val graph: Signal[ViewerGraph] =
      replaceText.signal
        .map(CSVToArray(_))
        .map(ViewerGraph.from)
//    val allSymbols = graph.now().nodeIds.map(_ -> None).toMap
    val project = Project(id)
    val appState = AppState(Var(PersistedAppState(project, "")), graph)
    val viewerState = ViewerState(appState.activeProject.pageV, appState.fullGraph, renderDot)
    TopLevel(appState, viewerState, replaceText, viewerState.svgDiagram)

  private def setupErrorHandling()(using Owner): EventBus[String] =
    val errors = new EventBus[String]
    AirstreamError.registerUnhandledErrorCallback: ex =>
      errors.emit(ex.getMessage)
    windowEvents(_.onError).foreach: e =>
      errors.emit(e.message)
    errors
