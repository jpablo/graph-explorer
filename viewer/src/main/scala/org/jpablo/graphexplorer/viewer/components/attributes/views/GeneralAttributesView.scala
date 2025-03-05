package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.components.attributes.EdgesAttributesView
import org.jpablo.graphexplorer.viewer.components.attributes.views.{GraphAttributesView, NodesAttributesView, RootGraphAttributesView}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom.HTMLDivElement


def GeneralAttributesView(state: ViewerState) =
  val tabIndex = Var(0)
  def tabVisible(i: Int) = tabIndex.signal.map(_ == i)

  val tabsData: List[(String, ReactiveHtmlElement[HTMLDivElement])] =
    List(
      "Nodes"  -> NodesAttributesView("DiagramAttributesView", state, state.rootTargetAttributesUpdates(AttributeTarget.node), selection = false),
      "Arrows" -> EdgesAttributesView(state, state.rootTargetAttributesUpdates(AttributeTarget.edge), selection = false),
      "Groups" -> GraphAttributesView(state, state.rootTargetAttributesUpdates(AttributeTarget.graph), selection = false)
    )
  div(
    div(cls := "divider", div(cls := "divider-content", h2(cls := "text-lg font-semibold", "Diagram Options"))),
    RootGraphAttributesView(state),
    div(cls := "divider", div(cls := "divider-content", h2(cls := "text-lg font-semibold", "Defaults"))),
    div(
      cls := "flex justify-center",
      div(
        role := "tablist",
        cls  := "tabs tabs-boxed tabs-xs w-[300px]",
        for (tabName, i) <- tabsData.map(_._1).zipWithIndex
          yield a(
            role := "tab",
            cls  := "tab flex-1",
            tabName,
            cls("tab-active") <-- tabVisible(i),
            onClick --> tabIndex.set(i)
          )
      )
    ),
    div(
      idAttr := "diagram-attributes-content",
      for (view, i) <- tabsData.map(_._2).zipWithIndex yield view.amend(cls("hidden") <-- tabVisible(i).not)
    )
  )
