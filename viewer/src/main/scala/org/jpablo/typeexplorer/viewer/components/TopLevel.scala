package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.typeexplorer.viewer.components.nodes.NodesPanel
import org.jpablo.typeexplorer.viewer.state.ViewerState
import org.jpablo.typeexplorer.viewer.widgets.SimpleDialog
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(state: ViewerState): ReactiveHtmlElement[HTMLDivElement] =
  val zoomValue = Var(1.0)
  val fitDiagram = EventBus[Unit]()
  val replaceTextOpen = Var(false)
  val drawerOpen = Var(true)
  div(
    cls    := "bg-base-100 border-base-300 rounded-box flex h-screen",
    idAttr := "top-level",
    drawerOpen.signal.childWhenTrue(NodesPanel(state)),
    CanvasContainer(state.svgDiagram, state.diagramSelection, zoomValue, fitDiagram.events),
    Toolbar(state, zoomValue, fitDiagram, drawerOpen, replaceTextOpen),
    SelectionSidebar(state),
    ReplaceGraphDialog(state.source, replaceTextOpen)
  )

def ReplaceGraphDialog(text: Var[String], open: Var[Boolean]) =
  SimpleDialog(
    open,
    textArea(
      cls         := "textarea textarea-bordered whitespace-nowrap w-full",
      placeholder := "Replace graph",
      controlled(value <-- text, onInput.mapToValue --> text)
    )
  )
