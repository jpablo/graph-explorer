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
    graph: Signal[ViewerGraph]
): ReactiveHtmlElement[HTMLUListElement] =
  val lis =
    graph.map: g =>
      println(s"------- NodesList (${g.nodes.size}): : ----------")
      println(g.nodes.toList.map(_.displayName).sorted.mkString(","))
      println(s"------- Arrows (${g.arrows.size}): ----------")
      println(g.arrows.toList.map(a => s"${a.source} -> ${a.target}").sorted.mkString("\n"))
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
