package org.jpablo.graphexplorer.viewer.components.nodes

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.ViewerNode
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.{HTMLAnchorElement, HTMLLIElement, HTMLUListElement}
import com.raquo.laminar.api.features.unitArrows

def NodesList(
    state: ViewerState,
    graph: Signal[ViewerGraph]
): ReactiveHtmlElement[HTMLUListElement] =
  ul(
    cls := "menu menu-sm bg-base-200 rounded-box",
    children <-- graph.map: g =>
      g.nodes.toList.sortBy(_.displayName).map(NodeRow(state))
  )

private def NodeRow(state: ViewerState)(node: ViewerNode) =
  li(
    a(
      idAttr := node.id.toString,
      cls    := "cursor-pointer",
      cls("font-bold") <-- state.isVisible(node.id),
      div(
        cls := "truncate",
        node.displayName
      ),
      onClick.preventDefault.stopPropagation --> state.toggleNode(node.id)
    )
  )
