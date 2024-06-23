package org.jpablo.typeexplorer.viewer

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.backends.graphviz.GraphvizInheritance.toDot
import org.jpablo.typeexplorer.viewer.components.state.*
import org.jpablo.typeexplorer.viewer.components.{SvgDiagram, TopLevel}
import org.jpablo.typeexplorer.viewer.examples.Example1
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.scalajs.dom
import org.scalajs.dom.SVGSVGElement

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Promise

object Viewer:

  def main(args: Array[String]): Unit =
    val graph = Signal.fromValue(Example1.diagram)
    val viz = new GraphViz
    for svgElem <- viz.render(toDot("", graph.now())) do
      val appElem = createApp(svgElem, graph)
      render(dom.document.querySelector("#app"), appElem)

  private def createApp(
      svgElem: SVGSVGElement,
      graph:   Val[ViewerGraph]
  ): ReactiveHtmlElement[dom.HTMLDivElement] =
    given Owner = unsafeWindowOwner
    val id = ProjectId("project-0")
    val svgDiagramV = Signal.fromValue(SvgDiagram(svgElem))
    val appState = AppState(Var(PersistedAppState(Project(id), "")), graph)
    val viewerState = ViewerState(appState.activeProject.pageV, appState.fullGraph, svgDiagramV)
    TopLevel(appState, viewerState, graph, svgDiagramV)

  private def setupErrorHandling()(using Owner): EventBus[String] =
    val errors = new EventBus[String]
    AirstreamError.registerUnhandledErrorCallback: ex =>
      errors.emit(ex.getMessage)
    windowEvents(_.onError).foreach: e =>
      errors.emit(e.message)
    errors

class GraphViz:
  private val instance: Future[js.Dynamic] =
    js.Dynamic.global.Viz
      .instance()
      .asInstanceOf[js.Promise[js.Dynamic]]
      .toFuture

  def render(g: String): Future[SVGSVGElement] =
    instance.map(_.renderSVGElement(g).asInstanceOf[SVGSVGElement])
