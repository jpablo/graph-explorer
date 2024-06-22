package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.components.state.{AppState, InheritanceTabState}
import org.jpablo.typeexplorer.viewer.graph.InheritanceGraph
import org.jpablo.typeexplorer.viewer.widgets.Drawer
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(
    appState:  AppState,
    pageId:    String,
    fullGraph: Signal[InheritanceGraph]
): ReactiveHtmlElement[HTMLDivElement] =
  val zoomValue = Var(1.0)
  val fitDiagram = EventBus[Unit]()
  val tabState = InheritanceTabState(appState, pageId, ???)
  div(
    cls := "te-parent-2 tab-content bg-base-100 border-base-300 rounded-box",
    Drawer(
      id        = s"drawer-tab-$pageId",
      drawerEnd = false,
      content = _.amend(
        CanvasContainer(tabState.inheritanceSvgDiagram, tabState.canvasSelection, zoomValue, fitDiagram.events),
        Toolbar(fullGraph, tabState, zoomValue, fitDiagram /*, appState.appConfigDialogOpenV*/ ),
        SelectionSidebar(appState, tabState)
      ),
      sidebar = _.amend(
        div(
          cls := "p-4 w-96 bg-base-100 text-base-content h-full",
          PackagesTreeComponent(appState, tabState)
        )
      )
    ).amend(
      cls := "te-parent-1"
    )
  )
