package org.jpablo.typeexplorer.viewer

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.backends.graphviz.GraphvizInheritance.toDot
import org.jpablo.typeexplorer.viewer.examples.Example1
import org.jpablo.typeexplorer.viewer.graph.InheritanceGraph
import org.jpablo.typeexplorer.viewer.models.GraphSymbol
import org.jpablo.typeexplorer.viewer.components.state.{CanvasSelectionOps, Path, ProjectId}
import org.jpablo.typeexplorer.viewer.components.{CanvasContainer, InheritanceSvgDiagram}
import org.scalajs.dom
import org.scalajs.dom.SVGSVGElement

import scala.scalajs.js

object Viewer:

//  val graph: Val[InheritanceGraph] = Signal.fromValue(Example1.diagram)

  def main(args: Array[String]): Unit =
    println("hello world")
    val g = toDot("", Example1.diagram)
    val vizInstance = js.Dynamic.global.Viz.instance().asInstanceOf[js.Promise[js.Dynamic]]
    vizInstance.`then`: viz =>
      val svgElem = InheritanceSvgDiagram(viz.renderSVGElement(g).asInstanceOf[SVGSVGElement])
      val diagram = Signal.fromValue(svgElem)
      val appElem = createApp(ProjectId("project-0"), diagram)
      render(dom.document.querySelector("#app"), appElem)

  private def createApp(
      projectId: ProjectId,
      diagram:   Val[InheritanceSvgDiagram]
  ): ReactiveHtmlElement[dom.HTMLDivElement] =
    given Owner = unsafeWindowOwner
    val zoomValue = Var(1.0)
    val fitDiagram = EventBus[Unit]()
    val canvasSelectionV = Var(Set.empty[GraphSymbol])
    val canvasSelection = CanvasSelectionOps(canvasSelectionV)
    CanvasContainer(diagram, canvasSelection, zoomValue, fitDiagram.events)

  private def setupErrorHandling()(using Owner): EventBus[String] =
    val errors = new EventBus[String]
    AirstreamError.registerUnhandledErrorCallback: ex =>
      errors.emit(ex.getMessage)
    windowEvents(_.onError).foreach: e =>
      errors.emit(e.message)
    errors
