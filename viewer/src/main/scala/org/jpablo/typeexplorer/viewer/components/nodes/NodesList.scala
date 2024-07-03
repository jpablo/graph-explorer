package org.jpablo.typeexplorer.viewer.components.nodes

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.ViewerNode
import org.jpablo.typeexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.{HTMLAnchorElement, HTMLLIElement, HTMLUListElement}

def NodesList(
    state: ViewerState,
    graph:       Signal[ViewerGraph]
): ReactiveHtmlElement[HTMLUListElement] =
  val lis =
    graph.map:
      _.nodes.toList.sortBy(_.displayName).map: s =>
        NodeRow(state, s)
  ul(
    cls := "menu menu-sm bg-base-200 rounded-box",
    children <-- lis
  )


private def NodeRow(state: ViewerState, ns: ViewerNode) =
  val isActive = state.activeSymbols.signal.map(_.contains(ns.id))
  li(
    a(
      idAttr := ns.id.toString,
      cls    := "font-['JetBrains_Mono'] cursor-pointer",
      cls("font-bold") <-- isActive,
      div(
        cls := "truncate",
        ns.displayName
      ),
      onClick.preventDefault.stopPropagation --> { _ =>
        state.activeSymbols.toggle(ns.id)
        state.canvasSelection.toggle(ns.id)
      }
    )
  )


