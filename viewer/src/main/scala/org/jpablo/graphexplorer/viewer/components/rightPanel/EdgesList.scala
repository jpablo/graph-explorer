package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.models.Arrow
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.widgets.{Join, LabeledCheckbox, Search}
import org.jpablo.graphexplorer.viewer.widgets.smallInput
import com.raquo.airstream.state.Var

// Enum for sort columns
enum EdgeSortColumn derives CanEqual:
  case Label, Source, Target

// Enum for sort direction
enum EdgeSortDirection derives CanEqual:
  case Ascending, Descending

def EdgesList(
    state: ViewerState,
    onlyActiveEdges: Var[Boolean],
): ReactiveHtmlElement[dom.HTMLDivElement] =
  val filterEdgesByNodeId = Var("")
  val sortColumnVar = Var(EdgeSortColumn.Label)
  val sortDirectionVar = Var(EdgeSortDirection.Ascending)

  // Helper function to toggle sort direction or set a new sort column
  def handleSortClick(column: EdgeSortColumn) = Observer[dom.MouseEvent] { _ =>
    if sortColumnVar.now() == column then
      // Toggle direction if same column
      sortDirectionVar.update {
        case EdgeSortDirection.Ascending => EdgeSortDirection.Descending
        case EdgeSortDirection.Descending => EdgeSortDirection.Ascending
      }
    else
      // Set new column and default to ascending
      sortColumnVar.set(column)
      sortDirectionVar.set(EdgeSortDirection.Ascending)
  }

  def arrowEndpoints(arrow: Arrow): (String, String) =
    val Seq(sourceNode, targetNode) = state.getNodeById(Seq(arrow.source, arrow.target))
    val sl = sourceNode.label.toString
    val tl = targetNode.label.toString
    (if sl.isBlank then arrow.source.toString else sl, if tl.isBlank then arrow.target.toString else tl)

  div(
    form(
      idAttr := "edges-panel-controls",
      Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveEdges)),
      div(
        cls := "flex gap-2",
        Search(
          placeholder := "filter",
          controlled(value <-- filterEdgesByNodeId, onInput.mapToValue --> filterEdgesByNodeId)
        ).smallInput,
        button(
          cls := "btn btn-xs",
          title := "Select filtered edges",
          "Select",
          onClick.preventDefault(_.sample(state.fullGraph.combineWith(onlyActiveEdges, filterEdgesByNodeId.signal, state.hiddenNodesS))) --> { case (fullGraph, onlyActive, str, hiddenNodes) =>
            val filteredEdges = fullGraph
              .orElse(!onlyActive, _.removeElements(hiddenNodes))
              .filterArrowsBy(a => a.source.toString.contains(str) || a.target.toString.contains(str))
              .toList
              .map(_.id)
              .toSet
            state.diagramSelection.set(filteredEdges)
          }
        )
      )
    ),
    div(
      idAttr := "edges-panel-contents",
      table(
        cls := "table table-xs table-pin-rows",
        thead(
          tr(
            th(
              cls := "cursor-pointer select-none",
              cls("text-primary") <-- sortColumnVar.signal.map(_ == EdgeSortColumn.Label),
              "Label ",
              span(
                cls := "inline-block",
                cls <-- sortColumnVar.signal.combineWith(sortDirectionVar.signal).map { (column, direction) =>
                  if column == EdgeSortColumn.Label then
                    direction match
                      case EdgeSortDirection.Ascending => "after:content-['↑']"
                      case EdgeSortDirection.Descending => "after:content-['↓']"
                  else ""
                }
              ),
              onClick --> handleSortClick(EdgeSortColumn.Label)
            ),
            th(
              cls := "cursor-pointer select-none",
              cls("text-primary") <-- sortColumnVar.signal.map(_ == EdgeSortColumn.Source),
              "Source ",
              span(
                cls := "inline-block",
                cls <-- sortColumnVar.signal.combineWith(sortDirectionVar.signal).map { (column, direction) =>
                  if column == EdgeSortColumn.Source then
                    direction match
                      case EdgeSortDirection.Ascending => "after:content-['↑']"
                      case EdgeSortDirection.Descending => "after:content-['↓']"
                  else ""
                }
              ),
              onClick --> handleSortClick(EdgeSortColumn.Source)
            ),
            th(""),
            th(
              cls := "cursor-pointer select-none",
              cls("text-primary") <-- sortColumnVar.signal.map(_ == EdgeSortColumn.Target),
              "Target ",
              span(
                cls := "inline-block",
                cls <-- sortColumnVar.signal.combineWith(sortDirectionVar.signal).map { (column, direction) =>
                  if column == EdgeSortColumn.Target then
                    direction match
                      case EdgeSortDirection.Ascending => "after:content-['↑']"
                      case EdgeSortDirection.Descending => "after:content-['↓']"
                  else ""
                }
              ),
              onClick --> handleSortClick(EdgeSortColumn.Target)
            )
          )
        ),
        tbody(
          children <--
            state
              .fullGraph
              .combineWith(onlyActiveEdges, filterEdgesByNodeId.signal, state.hiddenNodesS, sortColumnVar.signal, sortDirectionVar.signal)
              .map: (fullGraph, onlyActive, str, hiddenNodes, sortColumn, sortDirection) =>
                val filteredEdges = fullGraph
                  .orElse(!onlyActive, _.removeElements(hiddenNodes))
                  .filterArrowsBy(a => a.source.toString.contains(str) || a.target.toString.contains(str))
                  .toList

                // Pre-calculate endpoints for sorting
                val edgesWithEndpoints = filteredEdges.map(arrow => (arrow, arrowEndpoints(arrow)))

                val sortedEdges = sortColumn match
                  case EdgeSortColumn.Label =>
                    val sorted = edgesWithEndpoints.sortBy(_._1.label.toString.toLowerCase)
                    if sortDirection == EdgeSortDirection.Descending then sorted.reverse else sorted
                  case EdgeSortColumn.Source =>
                    val sorted = edgesWithEndpoints.sortBy(_._2._1.toLowerCase)
                    if sortDirection == EdgeSortDirection.Descending then sorted.reverse else sorted
                  case EdgeSortColumn.Target =>
                    val sorted = edgesWithEndpoints.sortBy(_._2._2.toLowerCase)
                    if sortDirection == EdgeSortDirection.Descending then sorted.reverse else sorted

                sortedEdges.map: (arrow, labels) =>
                  val (sourceLabel, targetLabel) = labels
                  tr(
                    cls := "whitespace-nowrap hover cursor-pointer",
                    cls("font-bold") <-- state.isEdgeVisible(arrow.id),
                    cls("bg-base-200") <-- state.isSelected(arrow.id),
                    td(cls := "truncate", arrow.label.toString),
                    td(cls := "truncate", cls("selected") <-- state.isSelected(arrow.source), sourceLabel),
                    td("→"),
                    td(cls := "truncate", cls("selected") <-- state.isSelected(arrow.target), targetLabel),
                    onMouseDown.preventDefault --> Observer.empty,
                    onClick.map(_.shiftKey) --> state.diagramSelection.handleClickOnArrow(arrow),
                    onDblClick
                      .preventDefault
                      .stopPropagation(_.sample(state.isEdgeVisible(arrow.id))) --> { visible =>
                      if visible then
                        state.hideNodes(arrow.nodeIds)
                      else
                        state.showNodes(arrow.nodeIds)
                    }
                  )
        )
      )
    )
  )
