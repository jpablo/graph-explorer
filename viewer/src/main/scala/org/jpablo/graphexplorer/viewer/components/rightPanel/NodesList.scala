package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.state.{HiddenElements, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.{Join, LabeledCheckboxFormControl, Search}
import org.jpablo.graphexplorer.viewer.widgets.smallInput
import org.jpablo.graphexplorer.viewer.domUtils.open

def NodesList(state: ViewerState): Div =
  val onlyActiveVar = Var(false)
  val filterVar     = Var("")
  val expandOverrideV = Var[Option[Boolean]](Some(true)) // expanded by default
  val expandedGroupsV = Var(Set.empty[GroupId])
  val knownGroupsV    = Var(Set.empty[GroupId])

  def nodeMatches(graph: ViewerGraph, nodeId: NodeId, filter: String): Boolean =
    val f = filter.trim.toLowerCase
    if f.isEmpty then true
    else
      val idStr     = nodeId.value.toLowerCase
      val labelStr  = graph.getNode(nodeId).map(_.label.toString.toLowerCase).getOrElse("")
      idStr.contains(f) || labelStr.contains(f)

  def nodeVisible(nodeId: NodeId, onlyActive: Boolean, hidden: HiddenElements): Boolean =
    !onlyActive || !(nodeId in hidden)

  def groupChildren(graph: ViewerGraph, groupId: GroupId): Set[GroupMemberId] =
    graph.getDirectChildren(Set(groupId))

  def groupLabel(graph: ViewerGraph, groupId: GroupId): String =
    graph.groups
      .get(groupId)
      .map(g => if g.label.toString.nonEmpty then g.label.toString else groupId.value)
      .getOrElse(groupId.value)

  def nodeLabel(graph: ViewerGraph, nodeId: NodeId): String =
    graph.getNode(nodeId).map { n =>
      val lbl = n.label.toString
      if lbl.nonEmpty then lbl else nodeId.toString
    }.getOrElse(nodeId.toString)

  def descendantNodeIds(graph: ViewerGraph, groupId: GroupId): Set[NodeId] =
    graph.getAllChildren(Set(groupId)).collect { case id: GroupMemberId if id.isNodeId => NodeId(id.value) }

  def renderNode(
      graph:      ViewerGraph,
      nodeId:     NodeId,
      onlyActive: Boolean,
      filter:     String,
      hidden:     HiddenElements
  ): Option[LI] =
    if nodeMatches(graph, nodeId, filter) && nodeVisible(nodeId, onlyActive, hidden) then
      Some(
        li(
          a(
            cls := "hover cursor-pointer truncate",
            cls("font-bold") <-- state.isElementVisible(nodeId),
            cls("bg-base-200") <-- state.selection.contains(nodeId),
            title := nodeId.toString,
            nodeLabel(graph, nodeId),
            onMouseDown.preventDefault --> Observer.empty,
            onClick.map(_.shiftKey) --> state.selection.updateSelectionStatus(nodeId),
            onDblClick
              .preventDefault
              .stopPropagation(_.sample(state.isElementVisible(nodeId))) --> { visible =>
              if visible then state.hideNodes(Set(nodeId)) else state.showNodes(Set(nodeId))
            }
          )
        )
      )
    else None

  def renderGroup(
      graph:      ViewerGraph,
      groupId:    GroupId,
      onlyActive: Boolean,
      filter:     String,
      hidden:     HiddenElements
  ): Option[LI] =
    if groupId == ViewerGraphElements.defaultRootId then None
    else
      val childrenMembers = groupChildren(graph, groupId)

      // Render child nodes and groups applying filters
      val renderedChildren: Seq[LI] =
        childrenMembers.toSeq
          .flatMap:
            case gid: GroupId => renderGroup(graph, gid, onlyActive, filter, hidden)
            case nid: NodeId  => renderNode(graph, nid, onlyActive, filter, hidden)

      if renderedChildren.isEmpty then None
      else
        val groupBoldSignal =
          state.fullGraph.combineWithFn(state.hiddenElements.signal) { (g, h) =>
            val desc = descendantNodeIds(g, groupId)
            desc.exists(n => !(n in h))
          }

        Some(
          li(
            detailsTag(
              open <-- expandOverrideV.signal.combineWith(expandedGroupsV.signal).map {
                case (Some(value), _) => value
                case (None, set)      => set.contains(groupId)
              },
              summaryTag(
                cls := "hover cursor-pointer truncate",
                cls("bg-base-200") <-- state.selection.contains(groupId),
                cls("font-bold")   <-- groupBoldSignal,
                title := groupId.toString,
                groupLabel(graph, groupId),
                onMouseDown.preventDefault --> Observer.empty,
                onClick.preventDefault --> { e =>
                  state.selection.updateSelectionStatus(groupId)(e.shiftKey)
                  expandOverrideV.set(None)
                  expandedGroupsV.update(set => if set.contains(groupId) then set - groupId else set + groupId)
                },
                onDblClick.stopPropagation(_.sample(state.fullGraph.combineWith(state.hiddenElements.signal))) --> {
                  case (g, hiddenNodes) =>
                    val nodes = descendantNodeIds(g, groupId)
                    if nodes.nonEmpty then
                      val hiddenCount = nodes.count(n => n in hiddenNodes)
                      if hiddenCount < nodes.size then state.hideNodes(nodes) else state.showNodes(nodes)
                }
              ),
              ul(
                // Nested children
                renderedChildren
              )
            )
          )
        )

  def renderRootTree(
      graph:      ViewerGraph,
      onlyActive: Boolean,
      filter:     String,
      hidden:     HiddenElements
  ): Seq[LI] =
    val rootChildren = graph.getRootChildren
    // We sort groups and nodes independently by label/ID for a stable order
    val (groups, nodes) = rootChildren.partition(_.isGroupId)

    val renderedGroups =
      groups.toSeq
        .collect { case id if id.isGroupId => GroupId(id.value) }
        .flatMap(gid => renderGroup(graph, gid, onlyActive, filter, hidden))

    val renderedNodes =
      nodes.toSeq
        .collect { case id if id.isNodeId => NodeId(id.value) }
        .flatMap(nid => renderNode(graph, nid, onlyActive, filter, hidden))

    renderedGroups ++ renderedNodes

  div(
    idAttr := "nodes-list",
    // Track known groups (initial + updates) and default new ones to expanded
    state.fullGraph --> { g =>
      val currentGroups = g.groupIds - ViewerGraphElements.defaultRootId
      val prev          = knownGroupsV.now()
      val newlyAdded    = currentGroups -- prev
      knownGroupsV.set(currentGroups)
      expandedGroupsV.update(set => (set intersect currentGroups) ++ newlyAdded)
    },
    form(
      idAttr := "right-panel-controls",
      Join(LabeledCheckboxFormControl(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveVar)),
      div(
        cls := "flex gap-2",
        Search(
          placeholder := "filter",
          controlled(value <-- filterVar, onInput.mapToValue --> filterVar)
        ).smallInput,
        button(
          cls   := "btn btn-xs",
          title := "Expand all groups",
          "Expand",
          onClick.preventDefault.mapTo(()) --> { _ => expandOverrideV.set(Some(true)) }
        ),
        button(
          cls   := "btn btn-xs",
          title := "Collapse all groups",
          "Collapse",
          onClick.preventDefault.mapTo(()) --> { _ => expandOverrideV.set(Some(false)) }
        ),
        button(
          cls   := "btn btn-xs",
          title := "Select filtered nodes",
          "Select",
          onClick.preventDefault(_.sample(state.fullGraph.combineWith(
            onlyActiveVar.signal,
            filterVar.signal,
            state.hiddenElements.signal
          ))) --> { case (fullGraph, onlyActive, filter, hiddenNodes) =>
            val toSelect = fullGraph.nodeIds.filter { nid =>
              nodeMatches(fullGraph, nid, filter) && nodeVisible(nid, onlyActive, hiddenNodes)
            }
            state.selection.set1(toSelect)
          }
        )
      )
    ),
    div(
      idAttr := "right-panel-contents",
      ul(
        cls := "menu menu-xs menu-compact",
        children <-- state.fullGraph.combineWithFn(
          onlyActiveVar.signal,
          filterVar.signal,
          state.hiddenElements.signal
        ) { (graph, onlyActive, filter, hidden) =>
          renderRootTree(graph, onlyActive, filter, hidden)
        }
      )
    )
  )

// Backward compatible helper retained (unused here), kept for potential reuse
private def filteredDiagramEvent(
    state:          ViewerState,
    onlyActive:     Signal[Boolean],
    filterByNodeId: Signal[String]
): Signal[ViewerGraph] = state
  .fullGraph
  .combineWithFn(onlyActive, filterByNodeId, state.hiddenElements.signal): (fullGraph, onlyActive, filter, hiddenNodes) =>
    fullGraph
      .orElse(filter.isBlank, _.filterByNodeId(filter))
      .orElse(!onlyActive, _.removeElements(hiddenNodes))
