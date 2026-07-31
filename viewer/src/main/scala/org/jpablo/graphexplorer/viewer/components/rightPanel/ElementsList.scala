package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.domUtils.open
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.formats.dot.LabelSummary
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BgColor, FillColor}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.state.{HiddenElements, RightPanelSection, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.{FilterChips, IconButton, IconToggle, Search}

/** Which kind the segmented filter narrows to; `None` in the Var means All. */
private enum ElementKind derives CanEqual:
  case Nodes, Arrows, Groups

/** The unified Elements list — nodes and arrows in ONE scannable outline.
  *
  * Palette-first: this component is the body of both postures. Unpinned it
  * floats as a card by the right rail (RightPanel's floating mode) with the
  * search focused, Enter selecting every match and Escape dismissing; pinned it
  * docks into the panel and stays. Same rows, same actions, either way.
  *
  * Row anatomy (the legibility work): the node's canvas fill as a swatch, a dim
  * id tag when two labels collide, and an EYE control that replaces the old
  * invisible encodings — bold-as-visible becomes dimmed-with-struck-eye, and
  * the secret double-click gains a visible, clickable synonym.
  */
def ElementsList(state: ViewerState): Div =
  val filterVar     = Var("")
  val kindVar       = Var[Option[ElementKind]](None)
  val showHiddenVar = Var(true) // list hidden elements (dimmed) or drop them

  // group tree state (same model NodesList used)
  val expandOverrideV = Var[Option[Boolean]](Some(true))
  val expandedGroupsV = Var(Set.empty[GroupId])
  val knownGroupsV    = Var(Set.empty[GroupId])
  val nodesOpenV      = Var(true)
  val arrowsOpenV     = Var(true)

  // ── model helpers ──────────────────────────────────────────────────────────

  /** The one line a row shows for a node. A structured label is MARKUP — shown
    * raw, every table node read as `<table border="1" cellborder="0" cellsp…`,
    * which is long, mostly boilerplate, and identical to its neighbours.
    */
  def nodeLabel(graph: ViewerGraph, nodeId: NodeId): String =
    graph.getNode(nodeId).map { n =>
      val lbl = LabelSummary.short(n.label.toString, graph.isRecordNode(nodeId))
      if lbl.nonEmpty then lbl else nodeId.toString
    }.getOrElse(nodeId.toString)

  /** Everything the node's label renders, markup gone — what the FILTER reads.
    * Searching the summary would hide matches in the rest of a table (typing
    * "Cook" would stop finding the task whose second row says it); searching
    * the raw markup matches attribute names like `cellborder` in every node.
    */
  def nodeSearchText(graph: ViewerGraph, nodeId: NodeId): String =
    graph.getNode(nodeId).map(n => LabelSummary.full(n.label.toString, graph.isRecordNode(nodeId))).getOrElse("")

  def nodeFill(graph: ViewerGraph, nodeId: NodeId): Option[String] =
    graph.getNode(nodeId).flatMap(_.attributes.values.get(FillColor.attrId)).map(_.toString).filter(_.nonEmpty)

  def groupLabel(graph: ViewerGraph, groupId: GroupId): String =
    graph.groups
      .get(groupId)
      .map: g =>
        val lbl = LabelSummary.short(g.label.toString)
        if lbl.nonEmpty then lbl else groupId.value
      .getOrElse(groupId.value)

  /** A cluster's background is `bgcolor`; `fillcolor` also paints one. Either
    * tints the folder glyph, the way the same rule paints the collapsed box.
    */
  def groupFill(graph: ViewerGraph, groupId: GroupId): Option[String] =
    graph.groups.get(groupId).flatMap { g =>
      val vs = g.attributes.values
      vs.get(BgColor.attrId).orElse(vs.get(FillColor.attrId)).map(_.toString).filter(_.nonEmpty)
    }

  def groupMatches(graph: ViewerGraph, groupId: GroupId, filter: String): Boolean =
    val f = filter.trim.toLowerCase
    f.isEmpty
      || groupId.value.toLowerCase.contains(f)
      || groupLabel(graph, groupId).toLowerCase.contains(f)

  def descendantNodeIds(graph: ViewerGraph, groupId: GroupId): Set[NodeId] =
    graph.getAllChildren(Set(groupId)).collect { case id: GroupMemberId if id.isNodeId => NodeId(id.value) }

  def realGroupIds(graph: ViewerGraph): Set[GroupId] =
    graph.groupIds - ViewerGraphElements.defaultRootId

  /** Labels used by more than one node: those rows carry a dim id tag. */
  def collidingLabels(graph: ViewerGraph): Set[String] =
    graph.nodes.keys
      .groupBy(id => nodeLabel(graph, id))
      .collect { case (lbl, ids) if ids.size > 1 => lbl }
      .toSet

  def nodeMatches(graph: ViewerGraph, nodeId: NodeId, filter: String): Boolean =
    val f = filter.trim.toLowerCase
    f.isEmpty
      || nodeId.value.toLowerCase.contains(f)
      || nodeSearchText(graph, nodeId).toLowerCase.contains(f)

  def arrowMatches(graph: ViewerGraph, arrow: Arrow, filter: String): Boolean =
    val f = filter.trim.toLowerCase
    f.isEmpty
      || LabelSummary.full(arrow.label.toString).toLowerCase.contains(f)
      || nodeSearchText(graph, arrow.source).toLowerCase.contains(f)
      || nodeSearchText(graph, arrow.target).toLowerCase.contains(f)

  def listed(id: ElementId, showHidden: Boolean, hidden: HiddenElements): Boolean =
    showHidden || (id notIn hidden)

  /** The ids the current query matches — the footer count, Enter and “Select
    * shown” all read this one definition, so they can never disagree.
    */
  def shownIds(
      graph:      ViewerGraph,
      kind:       Option[ElementKind],
      filter:     String,
      showHidden: Boolean,
      hidden:     HiddenElements
  ): Set[ElementId] =
    val nodes: Set[ElementId] =
      if kind.contains(ElementKind.Arrows) || kind.contains(ElementKind.Groups) then Set.empty
      else graph.nodeIds.filter(n => nodeMatches(graph, n, filter) && listed(n, showHidden, hidden)).toSet
    val arrows: Set[ElementId] =
      if kind.contains(ElementKind.Nodes) || kind.contains(ElementKind.Groups) then Set.empty
      else
        graph.arrows.values
          .filter(a => arrowMatches(graph, a, filter) && listed(a.id, showHidden, hidden))
          .map(_.id)
          .toSet
    val groups: Set[ElementId] =
      if kind.exists(_ != ElementKind.Groups) then Set.empty
      else realGroupIds(graph).filter(g => groupMatches(graph, g, filter)).toSet
    nodes ++ arrows ++ groups

  val shownSignal =
    state.fullGraph.combineWithFn(
      kindVar.signal,
      filterVar.signal,
      showHiddenVar.signal,
      state.hiddenElements.signal
    )(shownIds)

  def selectShown(): Unit =
    state.selection.set1(
      shownIds(
        state.fullGraphNow(),
        kindVar.now(),
        filterVar.now(),
        showHiddenVar.now(),
        state.hiddenElements.now()
      )
    )

  def closePalette(): Unit =
    if !state.elementsPinned.now() then state.rightPanelActiveSection.set(RightPanelSection.none)

  // ── rows ───────────────────────────────────────────────────────────────────

  def eyeControl(id: ElementId, hide: () => Unit, show: () => Unit) =
    span(
      cls := "el-eye",
      title <-- state.isElementVisible(id).map(v => if v then "Hide" else "Show"),
      i(cls("bi bi-eye") <-- state.isElementVisible(id), cls("bi bi-eye-slash") <-- state.isElementVisible(id).not),
      onClick.stopPropagation(_.sample(state.isElementVisible(id))) --> { visible =>
        if visible then hide() else show()
      }
    )

  def nodeRow(graph: ViewerGraph, nodeId: NodeId, colliding: Set[String], nested: Boolean): Div =
    val label = nodeLabel(graph, nodeId)
    div(
      cls := "el-row",
      cls("el-nested") := nested,
      cls("el-hidden") <-- state.isElementVisible(nodeId).not,
      cls("el-selected") <-- state.selection.contains(nodeId),
      // A node pictogram, not a control: the same glyph slot the arrow rows use.
      // With a fill attribute it becomes that color; without one it stays a
      // quiet outline (a bordered box here read as an unchecked checkbox).
      span(
        cls := "el-swatch",
        nodeFill(graph, nodeId) match
          case Some(c) => i(cls := "bi bi-square-fill", styleAttr := s"color: $c")
          case None    => i(cls := "bi bi-square")
      ),
      // The tooltip carries what the row had to drop: a summarized label keeps
      // the rest of the table reachable on hover, and a label that fits shows
      // the id, as before.
      span(
        cls := "el-label",
        title := {
          val full = nodeSearchText(graph, nodeId)
          if full.nonEmpty && full != label then full else nodeId.toString
        },
        label
      ),
      Option.when(colliding.contains(label) && nodeId.value != label)(span(cls := "el-id", s"#${nodeId.value}")),
      eyeControl(nodeId, () => state.hideNodes(Set(nodeId)), () => state.showNodes(Set(nodeId))),
      onMouseDown.preventDefault --> Observer.empty,
      onClick.map(_.shiftKey) --> state.selection.updateSelectionStatus(nodeId),
      onDblClick.preventDefault.stopPropagation(_.sample(state.isElementVisible(nodeId))) --> { visible =>
        if visible then state.hideNodes(Set(nodeId)) else state.showNodes(Set(nodeId))
      }
    )

  def arrowRow(graph: ViewerGraph, arrow: Arrow): Div =
    val lbl = LabelSummary.short(arrow.label.toString)
    div(
      cls := "el-row el-nested",
      cls("el-hidden") <-- state.isElementVisible(arrow.id).not,
      cls("el-selected") <-- state.selection.contains(arrow.id),
      span(cls := "el-kind", i(cls := "bi bi-arrow-right")),
      span(
        cls   := "el-label",
        title := arrow.id.toString,
        s"${nodeLabel(graph, arrow.source)} → ${nodeLabel(graph, arrow.target)}",
        Option.when(lbl.nonEmpty)(span(cls := "el-id", lbl))
      ),
      eyeControl(
        arrow.id,
        () => state.hiddenElements.update(_ ++ Set(arrow.id)),
        () => state.hiddenElements.update(_ -- Set(arrow.id))
      ),
      onMouseDown.preventDefault --> Observer.empty,
      onClick.map(_.shiftKey) --> state.selection.handleClickOnArrow(arrow)
    )

  /** The group pictogram: the app's own collapse metaphor. A cluster with a
    * bgcolor gets a folder in that color, like the collapsed box would wear it.
    */
  def groupSwatch(graph: ViewerGraph, groupId: GroupId) =
    span(
      cls := "el-swatch",
      groupFill(graph, groupId) match
        case Some(c) => i(cls := "bi bi-folder-fill", styleAttr := s"color: $c")
        case None    => i(cls := "bi bi-folder")
    )

  /** The group's eye works on its members: hide them all, or bring them back.
    * (The old panel had this as an invisible double-click on the group row.)
    */
  def groupEye(graph: ViewerGraph, groupId: GroupId) =
    val anyMemberVisible =
      state.hiddenElements.signal.map { hidden =>
        descendantNodeIds(graph, groupId).exists(n => n notIn hidden)
      }
    span(
      cls := "el-eye",
      title <-- anyMemberVisible.map(v => if v then "Hide members" else "Show members"),
      i(cls("bi bi-eye") <-- anyMemberVisible, cls("bi bi-eye-slash") <-- anyMemberVisible.not),
      onClick.stopPropagation(_.sample(anyMemberVisible)) --> { visible =>
        val members = descendantNodeIds(graph, groupId)
        if visible then state.hideNodes(members) else state.showNodes(members)
      }
    )

  def renderGroup(
      graph:      ViewerGraph,
      groupId:    GroupId,
      colliding:  Set[String],
      filter:     String,
      showHidden: Boolean,
      hidden:     HiddenElements
  ): Option[Div] =
    if groupId == ViewerGraphElements.defaultRootId then None
    else
      val children: Seq[Div] =
        graph.getDirectChildren(Set(groupId)).toSeq
          .flatMap:
            case gid: GroupId => renderGroup(graph, gid, colliding, filter, showHidden, hidden)
            case nid: NodeId =>
              Option.when(nodeMatches(graph, nid, filter) && listed(nid, showHidden, hidden))(
                nodeRow(graph, nid, colliding, nested = true)
              )
      // A group earns its row when members match OR its own name does — a
      // name-only match shows the (childless) folder rather than dropping it.
      if children.isEmpty && !groupMatches(graph, groupId, filter) then None
      else
        Some(
          div(
            detailsTag(
              cls := "el-group",
              open <-- expandOverrideV.signal.combineWith(expandedGroupsV.signal).map {
                case (Some(value), _) => value
                case (None, set)      => set.contains(groupId)
              },
              summaryTag(
                cls := "el-row el-group-head",
                cls("el-selected") <-- state.selection.contains(groupId),
                span(cls := "el-kind el-chevron", i(cls := "bi bi-chevron-down")),
                groupSwatch(graph, groupId),
                span(cls := "el-label", title := groupId.toString, groupLabel(graph, groupId)),
                groupEye(graph, groupId),
                onMouseDown.preventDefault --> Observer.empty,
                onClick.preventDefault --> { e =>
                  state.selection.updateSelectionStatus(groupId)(e.shiftKey)
                  expandOverrideV.set(None)
                  expandedGroupsV.update(set => if set.contains(groupId) then set - groupId else set + groupId)
                }
              ),
              div(children)
            )
          )
        )

  /** The Groups facet: the flat inventory (structure lives in the All tree). */
  def renderGroupsSection(graph: ViewerGraph, filter: String): Seq[Div] =
    realGroupIds(graph).toSeq
      .filter(groupMatches(graph, _, filter))
      .sortBy(g => groupLabel(graph, g).toLowerCase)
      .map { groupId =>
        div(
          cls := "el-row el-nested",
          cls("el-selected") <-- state.selection.contains(groupId),
          groupSwatch(graph, groupId),
          span(cls := "el-label", title := groupId.toString, groupLabel(graph, groupId)),
          span(cls := "el-id", descendantNodeIds(graph, groupId).size.toString),
          groupEye(graph, groupId),
          onMouseDown.preventDefault --> Observer.empty,
          onClick --> { e => state.selection.updateSelectionStatus(groupId)(e.shiftKey) }
        )
      }

  def renderNodesSection(
      graph:      ViewerGraph,
      colliding:  Set[String],
      filter:     String,
      showHidden: Boolean,
      hidden:     HiddenElements
  ): Seq[Div] =
    val (groups, nodes) = graph.getRootChildren.partition(_.isGroupId)
    val renderedGroups = groups.toSeq
      .collect { case id if id.isGroupId => GroupId(id.value) }
      .flatMap(gid => renderGroup(graph, gid, colliding, filter, showHidden, hidden))
    val renderedNodes = nodes.toSeq
      .collect { case id if id.isNodeId => NodeId(id.value) }
      .filter(nid => nodeMatches(graph, nid, filter) && listed(nid, showHidden, hidden))
      .map(nid => nodeRow(graph, nid, colliding, nested = true))
    renderedGroups ++ renderedNodes

  def renderArrowsSection(
      graph:      ViewerGraph,
      filter:     String,
      showHidden: Boolean,
      hidden:     HiddenElements
  ): Seq[Div] =
    graph.arrows.values.toSeq
      .filter(a => arrowMatches(graph, a, filter) && listed(a.id, showHidden, hidden))
      .sortBy(a => (nodeLabel(graph, a.source).toLowerCase, nodeLabel(graph, a.target).toLowerCase))
      .map(arrowRow(graph, _))

  def sectionHead(label: String, count: Signal[Int], openV: Var[Boolean]) =
    div(
      cls := "el-row el-section",
      cls("el-closed") <-- openV.signal.not,
      span(cls := "el-kind el-chevron", i(cls := "bi bi-chevron-down")),
      span(cls := "el-label", label),
      span(cls := "el-id", child.text <-- count.map(_.toString)),
      onClick --> { _ => openV.update(!_) }
    )

  // ── assembly ───────────────────────────────────────────────────────────────

  val searchBox: Input =
    Search(
      placeholder := "Filter elements",
      controlled(value <-- filterVar, onInput.mapToValue --> filterVar),
      onKeyDown --> { e =>
        if e.key == "Enter" then
          e.preventDefault()
          selectShown()
      }
    )

  val summary = state.fullGraph.map(_.summary)

  div(
    idAttr := "elements-list",
    cls    := "h-full max-h-full flex flex-col",
    // Palette posture: opening the section focuses the query — it IS the point.
    // Deferred a tick: at observation time the section's `hidden` class has not
    // been lifted yet, and a hidden input silently refuses focus.
    state.rightPanelActiveSection.signal.changes
      .filter(_ == RightPanelSection.elements) --> { _ =>
      scala.scalajs.js.timers.setTimeout(0) { searchBox.ref.focus() }
    },
    // Escape dismisses the palette (a pinned panel stays; that is what pinning means).
    onKeyDown --> { e => if e.key == "Escape" then closePalette() },
    // Track known groups; new ones default to expanded (same policy NodesList had).
    state.fullGraph --> { g =>
      val currentGroups = g.groupIds - ViewerGraphElements.defaultRootId
      val newlyAdded    = currentGroups -- knownGroupsV.now()
      knownGroupsV.set(currentGroups)
      expandedGroupsV.update(set => (set intersect currentGroups) ++ newlyAdded)
    },
    div(
      cls := "attributes-title flex-none flex items-center justify-between",
      h2("Elements"),
      IconToggle(
        "bi-pin-angle",
        "Pin: keep this list docked in the panel",
        state.elementsPinned
      )
    ),
    div(
      cls := "el-tools flex-none flex items-center gap-1",
      searchBox.amend(cls := "grow"),
      IconButton("bi-chevron-expand", "Expand all groups")(expandOverrideV.set(Some(true))),
      IconButton("bi-chevron-contract", "Collapse all groups")(expandOverrideV.set(Some(false))),
      IconToggle("bi-eye", "List hidden elements (dimmed)", showHiddenVar)
    ),
    div(
      cls := "el-tools flex-none",
      FilterChips(
        groupName  = "elements-kind-filter",
        options    = ElementKind.values.toSeq,
        labelOf    = _.toString,
        selected   = kindVar,
        resetTitle = "All"
      )
    ),
    div(
      cls := "el-contents grow overflow-y-auto",
      children <-- state.fullGraph.combineWithFn(
        kindVar.signal,
        filterVar.signal,
        showHiddenVar.signal,
        state.hiddenElements.signal
      ) { (graph, kind, filter, showHidden, hidden) =>
        val colliding = collidingLabels(graph)
        val nodesSection =
          if kind.exists(_ != ElementKind.Nodes) then Seq.empty
          else
            sectionHead("Nodes", summary.map(_.nodes), nodesOpenV)
              +: Seq(div(cls("hidden") <-- nodesOpenV.signal.not, renderNodesSection(graph, colliding, filter, showHidden, hidden)))
        val arrowsSection =
          if kind.exists(_ != ElementKind.Arrows) then Seq.empty
          else
            sectionHead("Arrows", summary.map(_.arrows), arrowsOpenV)
              +: Seq(div(cls("hidden") <-- arrowsOpenV.signal.not, renderArrowsSection(graph, filter, showHidden, hidden)))
        // Groups live INSIDE the Nodes tree as structure; the facet is the flat
        // inventory view of them.
        val groupsSection =
          if !kind.contains(ElementKind.Groups) then Seq.empty
          else
            // Counted from the same definition the rows use — summary.groups
            // assumes a materialized root group and undercounts without one.
            sectionHead("Groups", state.fullGraph.map(g => realGroupIds(g).size), nodesOpenV)
              +: Seq(div(cls("hidden") <-- nodesOpenV.signal.not, renderGroupsSection(graph, filter)))
        nodesSection ++ arrowsSection ++ groupsSection
      }
    ),
    div(
      cls := "el-footer flex-none flex items-center justify-between",
      span(child.text <-- shownSignal.map(ids => s"${ids.size} shown")),
      a(
        cls := "el-select-shown",
        title := "Select every element the current filter matches (Enter in the filter does the same)",
        "Select shown",
        onClick --> { _ => selectShown() }
      )
    )
  )
