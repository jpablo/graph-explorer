package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.components.nodes.NodesPanel
import org.jpablo.typeexplorer.viewer.state.ViewerState
import org.jpablo.typeexplorer.viewer.widgets.{Drawer, SimpleDialog}
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(
    state:     ViewerState,
): ReactiveHtmlElement[HTMLDivElement] =
  val zoomValue = Var(1.0)
  val fitDiagram = EventBus[Unit]()
  val replaceTextOpen = Var(false)
  div(
    cls := "bg-base-100 border-base-300 rounded-box",
    Drawer(
      id        = s"drawer-id",
      drawerEnd = false,
      content = _.amend(
        CanvasContainer(state.svgDiagram, state.canvasSelection, zoomValue, fitDiagram.events),
        Toolbar(state.fullGraph, state, zoomValue, fitDiagram, replaceTextOpen),
        SelectionSidebar(state)
      ),
      sidebar = _.amend(
        div(
          cls := "p-4 w-96 bg-base-100 text-base-content h-full",
          NodesPanel(state)
        )
      )
    ).amend(
      cls := "te-parent-1"
    ),
    ReplaceGraphDialog(state.source, replaceTextOpen)
  )

def ReplaceGraphDialog(text: Var[String], open: Var[Boolean]) =
  SimpleDialog(
    open,
    textArea(
      cls         := "textarea textarea-bordered whitespace-nowrap w-full",
      placeholder := "Replace graph",
      controlled(
        value <-- text,
        onInput.mapToValue --> text
      )
    )
  )
