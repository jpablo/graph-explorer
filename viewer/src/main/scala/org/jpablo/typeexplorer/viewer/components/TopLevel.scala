package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import com.raquo.laminar.nodes.ReactiveHtmlElement.Base
import org.jpablo.typeexplorer.viewer.components.state.{AppState, InheritanceTabState}
import org.jpablo.typeexplorer.viewer.domUtils.*
import org.jpablo.typeexplorer.viewer.widgets.Drawer
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(appState: AppState, pageId: String): Seq[Base] =
  val zoomValue = Var(1.0)
  val fitDiagram = EventBus[Unit]()
  val tabState = InheritanceTabState(appState, pageId, ???)
  Seq(
    input(
      role := "tab",
      tpe  := "radio",
      checked <-- appState.activeProject.getActivePageId.map(pageId == _),
      cls       := "tab",
      name      := "tabs_area",
      ariaLabel := s"Inheritance",
      onClick --> appState.setActivePage(pageId)
    ),
    div(
      cls  := "te-parent-2 tab-content bg-base-100 border-base-300 rounded-box",
      Drawer(
        id        = s"drawer-tab-$pageId",
        drawerEnd = false,
        content = _.amend(
          CanvasContainer(tabState.inheritanceSvgDiagram, tabState.canvasSelection, zoomValue, fitDiagram.events),
          Toolbar(appState.fullGraph, tabState, zoomValue, fitDiagram/*, appState.appConfigDialogOpenV*/),
          SelectionSidebar(appState, tabState)
        ),
        sidebar = _.amend(
          div(
            cls := "p-4 w-96 bg-base-100 text-base-content h-full",
            PackagesTreeComponent(appState, tabState),
          )
        )
      ).amend(
        cls := "te-parent-1"
      )
    )
  )
