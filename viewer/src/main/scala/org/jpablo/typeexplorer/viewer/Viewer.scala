package org.jpablo.typeexplorer.viewer

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.typeexplorer.viewer.components.TopLevel
import org.jpablo.typeexplorer.viewer.examples.Example1
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.state.{AppState, PersistedAppState, Project, ProjectId, ViewerState}
import org.jpablo.typeexplorer.viewer.utils.CSVToArray
import org.scalajs.dom

object Viewer:

  def main(args: Array[String]): Unit =
    val appElem = createApp()
    render(dom.document.querySelector("#app"), appElem)

  private def createApp(): ReactiveHtmlElement[dom.HTMLDivElement] =
    given Owner = unsafeWindowOwner
    val id = ProjectId("project-0")
    val csvString: Var[String] = Var(Example1.graph.toCSV.asString())
    val graph: Signal[ViewerGraph] =
      csvString.signal
        .map(CSVToArray(_))
        .map(ViewerGraph.from)
    val project = Project(id)
    val appState = AppState(Var(PersistedAppState(project, "")), graph)
    val renderDot = (new Graphviz).renderDot
    val viewerState = ViewerState(appState.activeProject.pageV, appState.fullGraph, renderDot)
    TopLevel(appState, viewerState, csvString, viewerState.svgDiagram)

//  private def setupErrorHandling()(using Owner): EventBus[String] =
//    val errors = new EventBus[String]
//    AirstreamError.registerUnhandledErrorCallback: ex =>
//      errors.emit(ex.getMessage)
//    windowEvents(_.onError).foreach: e =>
//      errors.emit(e.message)
//    errors
