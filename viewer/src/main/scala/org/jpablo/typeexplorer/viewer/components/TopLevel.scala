package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.components.state.{AppState, ViewerState}
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.widgets.Drawer
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(
    appState:    AppState,
    viewerState: ViewerState,
    fullGraph:   Signal[ViewerGraph],
    svgDiagram:  Signal[SvgDiagram]
): ReactiveHtmlElement[HTMLDivElement] =
  val zoomValue = Var(1.0)
  val fitDiagram = EventBus[Unit]()
  div(
    cls := "bg-base-100 border-base-300 rounded-box",
    Drawer(
      id        = s"drawer-id",
      drawerEnd = false,
      content = _.amend(
        CanvasContainer(viewerState.svgDiagram, viewerState.canvasSelection, zoomValue, fitDiagram.events),
        Toolbar(fullGraph, viewerState, zoomValue, fitDiagram /*, appState.appConfigDialogOpenV*/ ),
        SelectionSidebar(appState, viewerState)
      ),
      sidebar = _.amend(
        div(
          cls := "p-4 w-96 bg-base-100 text-base-content h-full",
          PackagesTreeComponent(appState, viewerState)
        )
      )
    ).amend(
      cls := "te-parent-1"
    )
  )
