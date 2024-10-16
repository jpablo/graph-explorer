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
    cls := "menu menu-sm bg-base-100 rounded-box",
    children <-- graph.map: g =>
      g.nodes.toList.sortBy(_.displayName).map(NodeRow(state))
  )

private def NodeRow(state: ViewerState)(node: ViewerNode) =
  li(
    cls("bg-base-200") <-- state.isSelected(node.id),
    a(
      idAttr := node.id.toString,
      cls    := "cursor-pointer",
      cls("font-bold") <-- state.isNodeVisible(node.id),
      div(
        cls := "truncate",
        node.displayName
      ),
      onClick.map(_.metaKey) --> state.diagramSelection.handleClickOnNode(node.id),
      onDblClick.preventDefault.stopPropagation --> state.toggleNode(node.id)
    )
  )
