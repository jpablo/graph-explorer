package org.jpablo.graphexplorer.viewer.components.nodes

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.ViewerNode
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.{HTMLAnchorElement, HTMLLIElement, HTMLUListElement}

def NodesList(
    state: ViewerState,
    graph: Signal[ViewerGraph]
): ReactiveHtmlElement[HTMLUListElement] =
  val lis =
    graph.map: g =>
      g.nodes.toList
        .sortBy(_.displayName)
        .map: s =>
          NodeRow(state, s)
  ul(
    cls := "menu menu-sm bg-base-200 rounded-box",
    children <-- lis
  )

private def NodeRow(state: ViewerState, node: ViewerNode) =
  val isActive = state.visibleNodes.signal.map(_.contains(node.id))
  li(
    a(
      idAttr := node.id.toString,
      cls    := "cursor-pointer",
      cls("font-bold") <-- isActive,
      div(
        cls := "truncate",
        node.displayName
      ),
      onClick.preventDefault.stopPropagation --> { _ =>
        state.visibleNodes.toggle(node.id)
        state.diagramSelection.toggle(node.id)
      }
    )
  )
