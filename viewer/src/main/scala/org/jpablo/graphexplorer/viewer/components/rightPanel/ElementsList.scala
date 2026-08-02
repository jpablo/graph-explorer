package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.domUtils.open
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.formats.dot.LabelSummary
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BgColor, FillColor}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements, VisibilityRules}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.state.{HiddenElements, RecordCellBox, RightPanelSection, SelectedCell, ViewerState}
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
  // Records disclose their cells the same way groups disclose members, but keyed
  // by NodeId — a record is a node, not a group.
  val expandedRecordsV = Var(Set.empty[NodeId])
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
    VisibilityRules.memberNodes(graph, groupId)

  def groupVisible(graph: ViewerGraph, groupId: GroupId): Signal[Boolean] =
    state.hiddenElements.signal.map(VisibilityRules.groupVisible(graph, groupId, _))

  /** [[listed]] one level up: a fully hidden group drops out with its members
    * unless hidden elements are being listed. */
  def groupListed(graph: ViewerGraph, groupId: GroupId, showHidden: Boolean, hidden: HiddenElements): Boolean =
    showHidden || VisibilityRules.groupVisible(graph, groupId, hidden)

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
      else
        realGroupIds(graph)
          .filter(g => groupMatches(graph, g, filter) && groupListed(graph, g, showHidden, hidden))
          .toSet
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
    div(cls := "el-row", cls("el-nested") := nested, nodeRowMods(graph, nodeId, colliding))

  /** A record/table node listed as a PARENT of its rows.
    *
    * Cells were a whole level of structure the inventory did not show: you can
    * select one on the canvas, act on it, navigate by it — and the panel still
    * called the record a leaf, so the only way to reach a row was to find the
    * node in the drawing and click it twice.
    *
    * Only nodes that actually have cells become expandable; everything else
    * stays the plain row it was. `cellBoxes` is gated on `kindOf`, so a diagram
    * of ordinary nodes pays a label sniff per node and nothing more.
    */
  def nodeEntry(graph: ViewerGraph, nodeId: NodeId, colliding: Set[String], nested: Boolean): Div =
    // Fewer than TWO cells is not a structure worth disclosing: `shape=record`
    // is often a graph-wide default, so an ordinary one-line node is a
    // single-cell record, and expanding it would just repeat its own label back
    // as a lone child.
    val boxes = state.recordCells.cellBoxes(nodeId)
    if boxes.sizeIs < 2 then nodeRow(graph, nodeId, colliding, nested)
    else
      div(
        detailsTag(
          cls := "el-group",
          open <-- expandOverrideV.signal.combineWith(expandedRecordsV.signal).map {
            case (Some(value), _) => value
            case (None, set)      => set.contains(nodeId)
          },
          summaryTag(
            cls := "el-row el-group-head",
            cls("el-nested") := nested,
            span(cls := "el-kind el-chevron", i(cls := "bi bi-chevron-down")),
            nodeRowMods(graph, nodeId, colliding),
            // The row's own click already selects the node; this only adds the
            // disclosure, and preventDefault stops <summary>'s native toggle from
            // fighting the explicit one (same shape as the group head).
            onClick.preventDefault --> { _ =>
              expandOverrideV.set(None)
              expandedRecordsV.update(set => if set.contains(nodeId) then set - nodeId else set + nodeId)
            }
          ),
          div(boxes.map(cellRow(nodeId, _)))
        )
      )

  /** One row of a record/table. Selecting it has to claim the NODE as well: a
    * cell selection only survives while its node is the whole selection
    * (RecordCellOps.pruneAgainstSelection), so setting the cell alone would be
    * undone the moment the selection signal fired.
    */
  def cellRow(nodeId: NodeId, box: RecordCellBox): Div =
    val text = state.recordCells.cellDisplayText(SelectedCell(nodeId, box.path))
    val isSelected =
      state.selectedCellV.signal.map(_.exists(c => c.nodeId == nodeId && c.path == box.path))
    div(
      cls := "el-row el-nested el-cell-row",
      cls("el-selected") <-- isSelected,
      span(cls := "el-kind", i(cls := "bi bi-dash-lg")),
      span(cls := "el-label", title := text, if text.nonEmpty then text else "—"),
      box.port.map(p => span(cls := "el-id", s"<$p>")),
      onMouseDown.preventDefault --> Observer.empty,
      onClick.stopPropagation --> { _ =>
        state.selection.set2(nodeId)
        state.recordCells.selectCell(nodeId, box.path)
      }
    )

  def nodeRowMods(graph: ViewerGraph, nodeId: NodeId, colliding: Set[String]): Seq[Modifier[HtmlElement]] =
    val label = nodeLabel(graph, nodeId)
    Seq(
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
  end nodeRowMods

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
    val visible = groupVisible(graph, groupId)
    span(
      cls := "el-eye",
      title <-- visible.map(v => if v then "Hide members" else "Show members"),
      i(cls("bi bi-eye") <-- visible, cls("bi bi-eye-slash") <-- visible.not),
      onClick.stopPropagation(_.sample(visible)) --> { isVisible =>
        val members = descendantNodeIds(graph, groupId)
        if isVisible then state.hideNodes(members) else state.showNodes(members)
      }
    )

  /** Fold state, as a control that is also the marker.
    *
    * A folded group is a single small box on the canvas, which is easy to lose
    * in a large diagram — and its panel row used to be indistinguishable from an
    * unfolded group's, so the list could not answer "which one did I collapse?".
    * Rather than add a separate badge AND a separate button, this one control
    * does both: it follows the eye's rules (quiet, appears on hover) with one
    * exception — a folded group keeps it lit, exactly as a hidden row pins its
    * struck eye. So an unfolded list stays clean and a folded group announces
    * itself.
    *
    * The arrows point the way the click goes: inward to fold, outward to unfold.
    */
  def groupCollapse(groupId: GroupId) =
    val collapsed = state.isGroupCollapsed(groupId)
    span(
      cls                   := "el-collapse",
      cls("el-is-collapsed") <-- collapsed,
      title <-- collapsed.map(c => if c then "Expand group" else "Collapse group"),
      i(
        cls("bi bi-arrows-angle-expand")   <-- collapsed,
        cls("bi bi-arrows-angle-contract") <-- collapsed.not
      ),
      // preventDefault as well as stopPropagation: on the tree's group head this
      // sits inside a <summary>, where a plain click also toggles the <details>.
      onClick.preventDefault.stopPropagation --> { _ => state.toggleGroupCollapsed(groupId) }
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
                nodeEntry(graph, nid, colliding, nested = true)
              )
      // A group with no listed members earns its row when its own name matches
      // the query AND it is itself listed — a name-only match shows the
      // (childless) folder rather than dropping it. The visibility conjunct is
      // what was missing: an empty query matches every name, so a cluster whose
      // members had all been hidden away stayed on the list as a bright, empty
      // folder — present, clickable, and standing for nothing.
      if children.isEmpty
        && !(groupMatches(graph, groupId, filter) && groupListed(graph, groupId, showHidden, hidden))
      then None
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
                // Dimmed + struck eye on the same terms as a node row: a group
                // is as hidden as its members are.
                cls("el-hidden") <-- groupVisible(graph, groupId).not,
                cls("el-selected") <-- state.selection.contains(groupId),
                span(cls := "el-kind el-chevron", i(cls := "bi bi-chevron-down")),
                groupSwatch(graph, groupId),
                span(cls := "el-label", title := groupId.toString, groupLabel(graph, groupId)),
                groupCollapse(groupId),
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
  def renderGroupsSection(graph: ViewerGraph, filter: String, showHidden: Boolean, hidden: HiddenElements): Seq[Div] =
    realGroupIds(graph).toSeq
      .filter(g => groupMatches(graph, g, filter) && groupListed(graph, g, showHidden, hidden))
      .sortBy(g => groupLabel(graph, g).toLowerCase)
      .map { groupId =>
        div(
          cls := "el-row el-nested",
          cls("el-hidden") <-- groupVisible(graph, groupId).not,
          cls("el-selected") <-- state.selection.contains(groupId),
          groupSwatch(graph, groupId),
          span(cls := "el-label", title := groupId.toString, groupLabel(graph, groupId)),
          span(cls := "el-id", descendantNodeIds(graph, groupId).size.toString),
          groupCollapse(groupId),
          groupEye(graph, groupId),
          onMouseDown.preventDefault --> Observer.empty,
          // A folded group is drawn as a proxy node; select the id the canvas uses.
          onClick --> { e => state.selection.updateSelectionStatus(state.renderedId(groupId))(e.shiftKey) }
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
      .map(nid => nodeEntry(graph, nid, colliding, nested = true))
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
      // Scrolls BOTH ways: long names (a fully-qualified type, a table's first
      // cell) used to truncate with no way to read the rest.
      cls := "el-contents grow overflow-y-auto overflow-x-auto",
      div(
      // Sizes to the WIDEST row so every row can be `width: 100%` of it. Without
      // this each row would size to its own content, and a short row would end
      // mid-scroll — its background stopping short and its eye control scrolled
      // out of reach.
      cls := "el-rows",
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
            // The graph's group INVENTORY, like the Nodes/Arrows heads (the
            // rows below may list fewer — hidden ones drop out; the footer is
            // what counts those). realGroupIds, not summary.groups, which
            // assumes a materialized root group and undercounts without one.
            sectionHead("Groups", state.fullGraph.map(g => realGroupIds(g).size), nodesOpenV)
              +: Seq(div(cls("hidden") <-- nodesOpenV.signal.not, renderGroupsSection(graph, filter, showHidden, hidden)))
        nodesSection ++ arrowsSection ++ groupsSection
      }
      )
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
