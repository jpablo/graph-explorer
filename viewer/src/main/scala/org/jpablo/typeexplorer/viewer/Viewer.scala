package org.jpablo.typeexplorer.viewer

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.examples.Example1
import org.jpablo.typeexplorer.viewer.graph.InheritanceGraph
import org.jpablo.typeexplorer.viewer.models.GraphSymbol
import org.jpablo.typeexplorer.viewer.plantUML.state.{CanvasSelectionOps, Path, ProjectId}
import org.jpablo.typeexplorer.viewer.plantUML.{CanvasContainer, InheritanceSvgDiagram}
import org.scalajs.dom

object Viewer:

  def main(args: Array[String]): Unit =
    println("hello world")
    render(dom.document.querySelector("#app"), createApp(ProjectId("project-0")))


  val graph = Signal.fromValue(Example1.diagram)
  val diagram = Signal.fromValue(InheritanceSvgDiagram.empty)

  private def createApp(projectId: ProjectId): ReactiveHtmlElement[dom.HTMLDivElement] =
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

