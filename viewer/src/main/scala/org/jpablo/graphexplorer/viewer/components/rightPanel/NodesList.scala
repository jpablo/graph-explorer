package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.widgets.{Join, LabeledCheckbox, Search}
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.widgets.smallInput

// Enum for sort columns
enum SortColumn:
  case Id, Label

// Enum for sort direction
enum SortDirection:
  case Ascending, Descending

def NodesList(
    state:          ViewerState,
    onlyActiveVar:  Var[Boolean]
): ReactiveHtmlElement[dom.HTMLDivElement] =
  val filterVar = Var("")
  val sortColumnVar = Var(SortColumn.Id)
  val sortDirectionVar = Var(SortDirection.Ascending)
  val filteredGraph = filteredDiagramEvent(state, onlyActiveVar.signal, filterVar.signal)

  // Helper function to toggle sort direction or set a new sort column
  def handleSortClick(column: SortColumn) = Observer[dom.MouseEvent] { _ =>
    if sortColumnVar.now() == column then
      // Toggle direction if same column
      sortDirectionVar.update {
        case SortDirection.Ascending => SortDirection.Descending
        case SortDirection.Descending => SortDirection.Ascending
      }
    else
      // Set new column and default to ascending
      sortColumnVar.set(column)
      sortDirectionVar.set(SortDirection.Ascending)
  }

  div(
    form(
      idAttr := "nodes-panel-controls",
      Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveVar)),
      div(
        cls := "flex gap-2",
        Search(
          placeholder := "filter",
          controlled(value <-- filterVar, onInput.mapToValue --> filterVar)
        ).smallInput,
        button(
          cls := "btn btn-xs",
          title := "Select filtered nodes",
          "Select",
          onClick.preventDefault(_.sample(filteredGraph)) --> { graph =>
            state.diagramSelection.set(graph.nodesSet.map(_.id))
          }
        )
      )
    ),
    div(
      idAttr := "nodes-panel-contents",
      table(
        cls := "table table-xs table-pin-rows",
        thead(
          tr(
            th(
              cls := "cursor-pointer select-none",
              cls("text-primary") <-- sortColumnVar.signal.map(_ == SortColumn.Id),
              "Id ",
              span(
                cls := "inline-block",
                cls <-- sortColumnVar.signal.combineWith(sortDirectionVar.signal).map { (column, direction) =>
                  if column == SortColumn.Id then
                    direction match
                      case SortDirection.Ascending => "after:content-['↑']"
                      case SortDirection.Descending => "after:content-['↓']"
                  else ""
                }
              ),
              onClick --> handleSortClick(SortColumn.Id)
            ), 
            th(
              cls := "cursor-pointer select-none",
              cls("text-primary") <-- sortColumnVar.signal.map(_ == SortColumn.Label),
              "Label ",
              span(
                cls := "inline-block",
                cls <-- sortColumnVar.signal.combineWith(sortDirectionVar.signal).map { (column, direction) =>
                  if column == SortColumn.Label then
                    direction match
                      case SortDirection.Ascending => "after:content-['↑']"
                      case SortDirection.Descending => "after:content-['↓']"
                  else ""
                }
              ),
              onClick --> handleSortClick(SortColumn.Label)
            )
          )
        ),
        tbody(
          children <--
            filteredGraph
              .combineWith(sortColumnVar.signal, sortDirectionVar.signal)
              .map: (graph, sortColumn, sortDirection) =>
                val nodes = graph.nodesSet.toList
                val sortedNodes = sortColumn match
                  case SortColumn.Id =>
                    val sorted = nodes.sortBy(node => node.id.toString.toLowerCase)
                    if sortDirection == SortDirection.Descending then sorted.reverse else sorted
                  case SortColumn.Label =>
                    val sorted = nodes.sortBy(node => node.label.toString.toLowerCase)
                    if sortDirection == SortDirection.Descending then sorted.reverse else sorted

                sortedNodes.map: node =>
                  tr(
                    cls := "whitespace-nowrap hover cursor-pointer",
                    cls("font-bold") <-- state.isNodeVisible(node.id),
                    cls("bg-base-200") <-- state.isSelected(node.id),
                    td(cls := "truncate", node.id.toString),
                    td(cls := "truncate", node.label.toString),
                    onMouseDown.preventDefault --> Observer.empty,
                    onClick.preventDefault.map(_.shiftKey) --> state.diagramSelection.handleClickOnNode(node.id),
                    onDblClick
                      .preventDefault
                      .stopPropagation(_.sample(state.isNodeVisible(node.id))) --> { visible =>
                      if visible then
                        state.hideNodes(Set(node.id))
                      else
                        state.showNodes(Set(node.id))
                    }
                  )
        )
      )
    )
  )

private def filteredDiagramEvent(
    state:          ViewerState,
    onlyActive:     Signal[Boolean],
    filterByNodeId: Signal[String]
): Signal[ViewerGraph] = state
  .fullGraph
  .combineWith(onlyActive, filterByNodeId, state.hiddenNodesS)
  .map: (fullGraph, onlyActive, filter, hiddenNodes) =>
    fullGraph
      .orElse(filter.isBlank, _.filterByNodeId(filter))
      .orElse(!onlyActive, _.removeNodes(hiddenNodes))
