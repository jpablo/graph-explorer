package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.components.state.ViewerState
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.ViewerNode
import org.scalajs.dom
import org.scalajs.dom.{HTMLAnchorElement, HTMLLIElement, HTMLUListElement}

def NodesList(
    viewerState: ViewerState,
    graph:       Signal[ViewerGraph]
): ReactiveHtmlElement[HTMLUListElement] =
  val lis =
    graph.map:
      _.nodes.toList.sortBy(_.displayName).map: s =>
        NodeRow(viewerState, s)
  ul(
    cls := "menu menu-sm bg-base-200 rounded-box",
    children <-- lis
  )


def NodeRow(tabState: ViewerState, ns: ViewerNode) =
  val isActive = tabState.activeSymbols.signal.map(_.contains(ns.nodeId))
  li(
    a(
      idAttr := ns.nodeId.toString,
      cls    := "font-['JetBrains_Mono'] cursor-pointer",
      cls("font-bold") <-- isActive,
      div(
        cls := "truncate",
        ns.displayName
      ),
      onClick.preventDefault.stopPropagation --> { _ =>
        tabState.activeSymbols.toggle(ns.nodeId)
        tabState.canvasSelection.toggle(ns.nodeId)
      }
    )
  )


