package org.jpablo.typeexplorer.viewer

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import com.softwaremill.quicklens.*
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
    val appElem = createApp(graph, buildSvgDiagram(viz))
    render(dom.document.querySelector("#app"), appElem)

  private def createApp(
      graph:     Val[ViewerGraph],
      renderDot: String => Signal[SvgDiagram]
  ): ReactiveHtmlElement[dom.HTMLDivElement] =
    given Owner = unsafeWindowOwner
    val id = ProjectId("project-0")
    val allSymbols = graph.now().symbols.map(_ -> None).toMap
    val project = Project(id).modify(_.page.activeSymbols).setTo(allSymbols)
    val appState = AppState(Var(PersistedAppState(project, "")), graph)
    val viewerState = ViewerState(appState.activeProject.pageV, appState.fullGraph, renderDot)
    TopLevel(appState, viewerState, graph, viewerState.svgDiagram)

  private def buildSvgDiagram(viz: GraphViz)(s: String): Signal[SvgDiagram] =
    Signal
      .fromFuture(viz.render(s).map(SvgDiagram.apply))
      .map(_.getOrElse(SvgDiagram.empty))

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
