package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Cluster, GraphType, GvId, HeadPort, Id, Label, Name, TailPort}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.{Arrow, GroupId, NodeId, ViewerGroup, ViewerNode, Attributes as ViewerAttributes}

def viewerGraphElementsToText(
    elements:     ViewerGraphElements,
    graphName:    String = "G",
    graphType:    GraphType = GraphType.digraph,
    omitInternal: Boolean = false
): String =
  import org.jpablo.graphexplorer.viewer.models.AttributeId

  import scala.collection.mutable.ListBuffer

  val lines = ListBuffer[String]()

  // The one html-ness predicate (shared with the editing UI — see HtmlLabels)
  def isHtmlLabel(value: String): Boolean =
    org.jpablo.graphexplorer.viewer.formats.dot.HtmlLabels.isHtml(value)

  // Detect if this is a complex graph with nested subgraphs or has HTML labels
  // (a nested subgraph = a membership whose child is itself a group)
  val hasNestedSubgraphs = elements.memberships.exists {
    case (_: GroupId, parentId) => elements.groups.contains(parentId)
    case _                      => false
  }

  // Check if any node has HTML labels with multi-line content
  val hasComplexHtmlLabels = elements.nodes.exists { case (_, node) =>
    node.attributes.values.get(Label.attrId)
      .map(_.toString)
      .exists(label => isHtmlLabel(label) && label.trim.split("\n").length > 1)
  }

  // Helper function for padding - use 4 spaces for complex graphs or graphs with multi-line HTML labels, 2 for simple
  def padding(level: Int): String =
    if (hasNestedSubgraphs || hasComplexHtmlLabels) "    " * level else "  " * level

  // Escape a raw string for embedding inside a DOT double-quoted string.
  // Only the double-quote delimiter is escaped here: backslashes/newlines in
  // label values are already escaped upstream (formats.dot.TextUtils.escape),
  // so escaping them again would double them.
  def escapeDotString(value: String): String = value.replace("\"", "\\\"")

  // The ONE way to emit a DOT double-quoted string — every quoted id, name, port and
  // value goes through here, so no emission site can accidentally skip the escaping.
  def quoted(value: String): String = s""""${escapeDotString(value)}""""


  // Helper to format a label value - HTML labels use <> notation, others use quotes
  def formatLabelValue(value: String): String = {
    if (isHtmlLabel(value)) {
      // Remove leading/trailing whitespace and format HTML labels
      val trimmed = value.trim
      s"<$trimmed>"
    } else quoted(value)
  }

  // The text for the layout gives each element an `id` of its own: `node:a`,
  // `group:g`, or `arrow.toSvg`. The SVG element gets this id, and a click
  // finds the element by it. Thus the layout text does not write the `id`
  // attribute of the source, because that attribute would replace this id.
  // The text for a file (omitInternal) does not write the layout id. It
  // writes the `id` attribute of the source, as each other attribute.
  def layoutId(id: => String): Option[(String, String)] =
    Option.when(!omitInternal)(Id.attrId.value -> id)
  def isReplacedByLayoutId(key: String): Boolean =
    !omitInternal && key == Id.attrId.value

  // Helper to collect attributes from ViewerNode directly
  def collectNodeAttributes(
      node:        ViewerNode,
      excludeKeys: Set[String] = Set.empty
  ): List[(String, String)] = {
    val attrs          = ListBuffer[(String, String)]()
    val allExcludeKeys = excludeKeys + GvId.attrId.value // Always exclude _gvid from output

    attrs ++= layoutId(s"node:${node.id.value}")

    // Process attributes directly from ViewerNode
    node.attributes.values.foreach { case (attrId, attrValue) =>
      val key   = attrId.value
      val value = attrValue.toString
      if (!allExcludeKeys.contains(key) && !isReplacedByLayoutId(key)) {
        attrs += key -> value
      }
    }

    attrs.toList
  }

  // Helper to collect attributes from ViewerGroup directly
  def collectClusterAttributes(group: ViewerGroup, excludeKeys: Set[String] = Set.empty): List[(String, String)] = {
    val attrs          = ListBuffer[(String, String)]()
    // `name` is the source name of the subgraph. It goes after the
    // `subgraph` keyword, and it is not an attribute of the subgraph.
    val allExcludeKeys = excludeKeys + GvId.attrId.value + Name.attrId.value

    attrs ++= layoutId(s"group:${group.id.value}")

    // Function to check if an attribute is a default value that shouldn't be included
    def isDefaultValue(key: String, value: String): Boolean = {
      import org.jpablo.graphexplorer.viewer.models.ViewerGroup.defaultGroupAttributes
      defaultGroupAttributes.values.get(AttributeId(key)).exists(_.toString == value)
    }

    // Process attributes directly from ViewerGroup
    group.attributes.values.foreach { case (attrId, attrValue) =>
      val key   = attrId.value
      val value = attrValue.toString
      if (!allExcludeKeys.contains(key) && !isReplacedByLayoutId(key)) {
        // Special handling for label attribute to prevent inheritance
        if (key == "label" && !excludeKeys.contains("label")) {
          attrs += key -> value
        } else if (key == "cluster") {
          // Always include cluster attribute (like the original SimpleGraphToText)
          attrs += key -> value
        } else if (key != "label" && !isDefaultValue(key, value)) {
          // Only add non-default values
          attrs += key -> value
        }
      }
    }

    attrs.toList
  }

  // Helper to collect attributes from graph-level attributes directly
  def collectGraphAttributes(attrs: ViewerAttributes, excludeKeys: Set[String] = Set.empty): List[(String, String)] = {
    val result = ListBuffer[(String, String)]()
    attrs.values.foreach { case (attrId, attrValue) =>
      val key   = attrId.value
      val value = attrValue.toString
      if (!excludeKeys.contains(key)) {
        result += key -> value
      }
    }
    result.toList
  }

  // An empty label is the default of Graphviz for an arrow and for the root
  // graph. The reader gives each arrow `label=""` when one arrow has a label,
  // and the root graph `label=""` when one cluster has a label. Thus the text
  // for a file (omitInternal) leaves these values out, and a change to one
  // element does not change the text of the others. An empty arrow label
  // stays when the `edge` statement gives a label, because then it overrides
  // that label. The text for the layout keeps each value: Graphviz draws the
  // same diagram.
  val edgeDefaultLabel = elements.defaultArrowAttributes.values.get(Label.attrId).map(_.toString).getOrElse("")
  def isDefaultEmptyLabel(key: String, value: String, default: String): Boolean =
    omitInternal && key == Label.attrId.value && value.isEmpty && default.isEmpty

  // Helper to collect attributes from Arrow directly
  def collectEdgeAttributes(arrow: Arrow, excludeKeys: Set[String] = Set.empty): List[(String, String)] = {
    val attrs          = ListBuffer[(String, String)]()
    val allExcludeKeys = excludeKeys + GvId.attrId.value // Always exclude _gvid from output

    // Add the layout id for arrows.
    //
    // From `arrow.toSvg`, NOT rebuilt from the parts. This id becomes the SVG element's id
    // and so the id a click hands back, which is then looked up in the `arrows` map — where
    // the key is `Arrow.id`. Deriving it a second time here let the two drift: `Arrow.id`
    // includes the ports, this did not, so an edge with a tailport/headport was keyed
    // `A:se->B/0` and advertised itself as `A->B/0`. Selecting it found nothing, so every
    // control in the toolbar read its default, and edits were dropped on the floor —
    // `updateAttributes` filters by the same key. One derivation, one id.
    attrs ++= layoutId(arrow.toSvg)

    // Process arrow attributes directly
    arrow.attributes.values.foreach { case (attrId, attrValue) =>
      val key   = attrId.value
      val value = attrValue.toString
      if (!allExcludeKeys.contains(key) && !isReplacedByLayoutId(key) && !isDefaultEmptyLabel(key, value, edgeDefaultLabel)) {
        attrs += key -> value
      }
    }

    attrs.toList
  }

  // Helper to format attribute list
  def formatAttributes(attrs: List[(String, String)]): String = {
    if (attrs.isEmpty) ""
    else {
      val formattedAttrs = attrs.map { case (key, value) =>
        if (key == "label") s"$key=${formatLabelValue(value)}"
        else s"$key=${quoted(value)}"
      }
      s" [${formattedAttrs.mkString(", ")}]"
    }
  }

  // Helper to format attributes in multi-line format for complex cases
  def formatAttributesMultiLine(attrs: List[(String, String)], level: Int, forceMultiLine: Boolean = false): String = {
    if (attrs.isEmpty) ""
    else if (
      forceMultiLine || hasNestedSubgraphs || hasComplexHtmlLabels || attrs.exists { case (k, v) =>
        k == "label" && isHtmlLabel(v) && v.trim.split("\n").length > 1
      }
    ) {
      val formattedAttrs = attrs.zipWithIndex.map { case ((key, value), idx) =>
        val isLast = idx == attrs.length - 1
        // HTML labels use comma with space, others use comma without space
        val comma = if (isLast) "" else if (hasComplexHtmlLabels) ", " else ","

        if (key == "label" && isHtmlLabel(value) && value.trim.split("\n").length > 1) {
          // Special formatting for multi-line HTML labels - each line of HTML on its own line
          val lines = value.trim.split("\n").map(_.trim).filter(_.nonEmpty)
          // Use extra indentation for HTML content
          val htmlPadding = if (hasComplexHtmlLabels) "              " else s"${padding(level + 2)}"
          val htmlLines   = lines.map(line => s"$htmlPadding$line").mkString("\n")
          s"${padding(level + 1)}$key=<\n$htmlLines\n${padding(level + 1)}>$comma"
        } else if (key == "label") {
          s"${padding(level + 1)}$key=${formatLabelValue(value)}$comma"
        } else {
          s"${padding(level + 1)}$key=${quoted(value)}$comma"
        }
      }
      s" [\n${formattedAttrs.mkString("\n")}\n${padding(level)}]"
    } else {
      formatAttributes(attrs)
    }
  }

  // Start graph
  val graphTypeStr = if graphType.isDirected then "digraph" else "graph"
  val edgeOp    = if graphType.isDirected then "->" else "--"

  lines += s"""$graphTypeStr ${quoted(graphName)} {"""

  // Emit a top-level `graph`/`node`/`edge` attribute statement (multi-line when > 1 attr)
  def emitAttrStatement(keyword: String, attrs: List[(String, String)]): Unit = {
    if (attrs.nonEmpty) {
      val attrFormatting = if (attrs.length > 1)
        formatAttributesMultiLine(attrs, 1, true)
      else
        formatAttributes(attrs)
      lines += s"${padding(1)}$keyword$attrFormatting;"
    }
  }

  // Graph attributes (name/directed are handled specially), then default node/edge attributes
  emitAttrStatement(
    "graph",
    collectGraphAttributes(elements.graphAttributes, Set("name", "directed"))
      .filterNot((key, value) => isDefaultEmptyLabel(key, value, default = ""))
  )
  emitAttrStatement("node", collectGraphAttributes(elements.defaultNodeAttributes))
  emitAttrStatement("edge", collectGraphAttributes(elements.defaultArrowAttributes))

  // One pass over memberships/arrows up front instead of a rescan per cluster.
  val childGroupsByParent: Map[GroupId, List[GroupId]] =
    elements.memberships.toList.collect { case (childGroupId: GroupId, parentId) => (parentId, childGroupId) }.groupMap(_._1)(_._2)
  val nodesByParent: Map[GroupId, List[NodeId]] =
    elements.memberships.toList.collect { case (nodeId: NodeId, parentId) => (parentId, nodeId) }.groupMap(_._1)(_._2)
  val arrowsByOwner: Map[GroupId, List[Arrow]] =
    elements.arrows.values.toList.flatMap(a => elements.arrowMemberships.get(a.id).map(_ -> a)).groupMap(_._1)(_._2)

  // Emit one arrow statement at the given nesting level. Arrows live in the
  // subgraph they were DECLARED in (elements.arrowMemberships) — fdp lays
  // clusters out separately, so re-serializing an intra-cluster edge at top
  // level changes the whole layout ("wrong ownership of arrows").
  val emittedArrows = scala.collection.mutable.Set[org.jpablo.graphexplorer.viewer.models.ArrowId]()
  def emitArrow(arrow: Arrow, level: Int): Unit = {
    // A port has two homes: the structural `sourcePort`/`targetPort`, which is what import
    // fills in and what `Arrow.id` is built from, and the `tailport`/`headport` ATTRIBUTE,
    // which is what the toolbar row edits. Emitting both wrote an edge that contradicted
    // itself -- `"A":"se" -> "B" [tailport="n"]` -- and Graphviz resolves that in favour of
    // the node-reference port, so the drawing kept the stale value the panel had just
    // replaced. The attribute is the newer of the two by construction (import only ever
    // produces the structural one), so it wins and the structural form stands down.
    def portAttr(attrId: org.jpablo.graphexplorer.viewer.models.AttributeId) =
      arrow.attributes.values.get(attrId).map(_.toString)
    val tailPort = portAttr(TailPort.attrId).orElse(arrow.sourcePort).map(p => ":" + quoted(p)).getOrElse("")
    val headPort = portAttr(HeadPort.attrId).orElse(arrow.targetPort).map(p => ":" + quoted(p)).getOrElse("")

    // ...and having emitted it as a port, do not also emit it as an attribute.
    val edgeAttrs = collectEdgeAttributes(arrow, Set("tail", "head", TailPort.attrId.value, HeadPort.attrId.value))

    val attrFormatting = if (hasNestedSubgraphs || hasComplexHtmlLabels || edgeAttrs.length > 1)
      formatAttributesMultiLine(edgeAttrs, level, hasComplexHtmlLabels || edgeAttrs.length > 1)
    else
      formatAttributes(edgeAttrs)

    lines += s"""${padding(level)}${quoted(arrow.source.value)}$tailPort $edgeOp ${quoted(arrow.target.value)}$headPort$attrFormatting;"""
    emittedArrows += arrow.id
  }

  // Emit one node statement at the given nesting level (shared by cluster members and
  // standalone nodes — the formatting decision is identical).
  def emitNode(node: ViewerNode, level: Int): Unit = {
    val nodeAttrs = collectNodeAttributes(node)
    val hasMultiLineHtmlLabel = node.attributes.values.get(Label.attrId)
      .map(_.toString)
      .exists(label => isHtmlLabel(label) && label.trim.split("\n").length > 1)
    val attrFormatting = if (hasNestedSubgraphs || hasMultiLineHtmlLabel || hasComplexHtmlLabels || nodeAttrs.length > 1)
      formatAttributesMultiLine(nodeAttrs, level, hasComplexHtmlLabels || nodeAttrs.length > 1)
    else
      formatAttributes(nodeAttrs)
    lines += s"""${padding(level)}${quoted(node.id.value)}$attrFormatting;"""
  }

  // One name for each subgraph, and no two subgraphs with one name.
  val subgraphNames = dotSubgraphNames(elements.groups.values)

  // Helper to process clusters recursively with proper nesting (using ViewerGraphElements directly)
  def processCluster(groupId: GroupId, level: Int, processedGroups: scala.collection.mutable.Set[GroupId]): Unit = {
    if (processedGroups.contains(groupId)) return
    processedGroups += groupId

    val group = elements.groups(groupId)

    lines += s"""${padding(level)}subgraph ${quoted(subgraphNames(groupId))} {"""

    val clusterAttrs = collectClusterAttributes(group)
    if (clusterAttrs.nonEmpty) {
      // Use multi-line formatting for clusters with multiple attributes or nested subgraphs
      val attrFormatting = if (hasNestedSubgraphs || hasComplexHtmlLabels || clusterAttrs.length > 1)
        formatAttributesMultiLine(clusterAttrs, level + 1, clusterAttrs.length > 1)
      else
        formatAttributes(clusterAttrs)
      lines += s"${padding(level + 1)}graph$attrFormatting;"
    }

    // Process nested subgraphs first (groups that are members of this group).
    // memberships is an unordered Map — sort by the groups' _gvid so nested
    // clusters serialize in DECLARATION order (fdp layouts are order-sensitive;
    // an arbitrary order here swapped G and H in "wrong ownership of arrows").
    childGroupsByParent
      .getOrElse(groupId, Nil)
      .sortBy(childGroupId => gvidOrder(elements.groups(childGroupId).attributes))
      .foreach { childGroupId =>
        processCluster(childGroupId, level + 1, processedGroups)
      }

    // Add nodes that belong to this cluster, sorted by _gvid to preserve original order
    nodesByParent
      .getOrElse(groupId, Nil)
      .sortBy(nodeId => gvidOrder(elements.nodes(nodeId).attributes))
      .foreach(nodeId => emitNode(elements.nodes(nodeId), level + 1))

    // Add arrows DECLARED in this cluster (innermost owner), in seq order
    arrowsByOwner
      .getOrElse(groupId, Nil)
      .sortBy(_.seq)
      .foreach(emitArrow(_, level + 1))

    lines += s"${padding(level)}}"
  }

  // Process top-level clusters (those not referenced as members of any other group)
  val processedGroups = scala.collection.mutable.Set[GroupId]()
  val topLevelGroups = elements.groups.keys.filter { groupId =>
    !elements.memberships.contains(groupId)
  }

  // Process all groups if no explicit top-level found (fallback)
  val groupsToProcess = if (topLevelGroups.nonEmpty) topLevelGroups else elements.groups.keys

  // Sort groups by their _gvid attribute to preserve original order
  val sortedGroupsToProcess = groupsToProcess.toList.sortBy { groupId =>
    gvidOrder(elements.groups(groupId).attributes)
  }

  sortedGroupsToProcess.foreach { groupId =>
    processCluster(groupId, 1, processedGroups)
  }

  // Add standalone nodes (not members of any cluster)
  val clusterNodeIds = nodesByParent.values.flatten.toSet

  elements.nodes.foreach { case (nodeId, node) =>
    if (!clusterNodeIds.contains(nodeId)) {
      emitNode(node, 1)
    }
  }

  // Add remaining top-level arrows (anything not already emitted inside its
  // declaring cluster — includes arrows whose recorded group no longer exists)
  elements.arrows.values.toList.filterNot(a => emittedArrows.contains(a.id)).foreach(emitArrow(_, 1))

  lines += "}"
  lines.mkString("\n")
end viewerGraphElementsToText

/** The name of each group after the `subgraph` keyword in DOT.
  *
  * Graphviz draws a subgraph as a cluster only when its name starts with
  * `cluster`, and `GroupId.fromDot` removes that prefix from the id. Thus the
  * id cannot give the source name back: `clusterB` and `cluster_B` both give
  * the id `B`, and the second of them gets `B_2` (see `GroupId.fromDotNames`).
  * The reader keeps the source name in the `name` attribute. A group gets
  * that name when the name agrees with the group: `GroupId.canBeFromDot`
  * accepts the id, and the name gives the same `cluster` flag. A copy of a
  * group gets a new id, and the `cluster` flag can change. Then the old name
  * belongs to a different group, so the group does not get it. A group with
  * no such name, for example a new group, gets `cluster_<id>` when it is a
  * cluster, and `<id>` when it is not.
  *
  * Graphviz merges two subgraphs that have one name and one parent, and the
  * members of the two then go into one group. Thus each group gets a
  * different name. The source names come first, in the order of `_gvid`. A
  * name that an earlier group has does not go to a second group. A group with
  * no free source name gets its fallback name, with `_2`, `_3`, and so on when
  * a different group has that name.
  *
  * The `cluster` flag comes from the `cluster` attribute, not from the
  * spelling of the id. The spelling lost the cluster flag for ids with
  * capitals or underscores, or with more than 10 characters.
  */
def dotSubgraphNames(groups: Iterable[ViewerGroup]): Map[GroupId, String] =
  def isCluster(group: ViewerGroup): Boolean =
    group.attributes.values.get(Cluster.attrId).exists(_.toString == "true")
  def sourceName(group: ViewerGroup): Option[String] =
    group.attributes.values
      .get(Name.attrId)
      .map(_.toString)
      .filter(name => GroupId.canBeFromDot(name, group.id) && GroupId.fromDot(name)._2 == isCluster(group))
  def fallbackName(group: ViewerGroup): String =
    if isCluster(group) && !group.id.value.startsWith("cluster") then s"cluster_${group.id.value}"
    else group.id.value

  type Names = (Map[GroupId, String], Set[String])
  def give(names: Names, group: ViewerGroup, name: String): Names =
    (names._1 + (group.id -> name), names._2 + name)

  val ordered = groups.toVector.sortBy(g => (gvidOrder(g.attributes), g.id.value))
  val withSource = ordered.foldLeft[Names]((Map.empty, Set.empty)): (names, group) =>
    sourceName(group).filterNot(names._2).fold(names)(give(names, group, _))
  val all = ordered.filterNot(g => withSource._1.contains(g.id)).foldLeft(withSource): (names, group) =>
    val base = fallbackName(group)
    give(names, group, (Iterator(base) ++ Iterator.from(2).map(n => s"${base}_$n")).filterNot(names._2).next())
  all._1

/** The declaration order of an element: its `_gvid`. An element with no
  * `_gvid` comes last.
  */
private def gvidOrder(attrs: ViewerAttributes): Double =
  attrs.values.get(GvId.attrId).map(_.toString.toDouble).getOrElse(Double.MaxValue)
