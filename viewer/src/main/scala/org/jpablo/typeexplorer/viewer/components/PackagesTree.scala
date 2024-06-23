package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.components.state.ViewerState
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.Namespace
import org.scalajs.dom
import org.scalajs.dom.{HTMLAnchorElement, HTMLLIElement, HTMLUListElement}

def Nodes(
    viewerState: ViewerState,
    graph:       Signal[ViewerGraph]
): ReactiveHtmlElement[HTMLUListElement] =
  val lis = graph.map: g =>
    g.namespaces.toList.map: s =>
      NodeRow(viewerState, s)
  ul(children <-- lis)


def NodeRow(tabState: ViewerState, ns: Namespace) =
  val isActive = tabState.activeSymbols.signal.map(_.contains(ns.symbol))
  li(
    a(
      idAttr := ns.symbol.toString,
      cls    := "font-['JetBrains_Mono'] rounded-box cursor-pointer",
      cls("active") <-- isActive,
      div(
        cls := "truncate",
        ns.displayName
      ),
      onClick.preventDefault.stopPropagation --> { _ =>
        tabState.activeSymbols.toggle(ns.symbol)
        tabState.canvasSelection.toggle(ns.symbol)
      }
    )
  )


