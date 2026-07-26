package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{GraphType, GvId, Label}
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

  // Helper to detect if a string contains HTML-like content
  def isHtmlLabel(value: String): Boolean = {
    value.contains("<") && value.contains(">") &&
    (value.contains("<table") || value.contains("<b>") || value.contains("<i>") ||
      value.contains("<font") || value.contains("<br") || value.contains("<hr") ||
      value.contains("<td") || value.contains("<tr") || value.contains("</"))
  }

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

  // Declaration-order sort key: elements sort by their _gvid; missing _gvid sorts last.
  def gvidOrder(attrs: ViewerAttributes): Double =
    attrs.values.get(GvId.attrId).map(_.toString.toDouble).getOrElse(Double.MaxValue)

  // Helper to collect attributes from ViewerNode directly
  def collectNodeAttributes(
      node:        ViewerNode,
      excludeKeys: Set[String] = Set.empty
  ): List[(String, String)] = {
    val attrs          = ListBuffer[(String, String)]()
    val internalAttrs  = if (omitInternal) Set("id") else Set.empty[String]
    val allExcludeKeys = excludeKeys ++ internalAttrs + GvId.attrId.value // Always exclude _gvid from output

    // Add id attribute unless excluded by omitInternal setting
    if (!allExcludeKeys.contains("id"))
      attrs += "id" -> s"node:${node.id.value}"

    // Process attributes directly from ViewerNode
    node.attributes.values.foreach { case (attrId, attrValue) =>
      val key   = attrId.value
      val value = attrValue.toString
      if (!allExcludeKeys.contains(key) && key != "id") {
        attrs += key -> value
      }
    }

    attrs.toList
  }

  // Helper to collect attributes from ViewerGroup directly
  def collectClusterAttributes(group: ViewerGroup, excludeKeys: Set[String] = Set.empty): List[(String, String)] = {
    val attrs          = ListBuffer[(String, String)]()
    val internalAttrs  = if (omitInternal) Set("id") else Set.empty[String]
    val allExcludeKeys = excludeKeys ++ internalAttrs + GvId.attrId.value // Always exclude _gvid from output

    // Add id attribute unless excluded by omitInternal setting
    if (!allExcludeKeys.contains("id"))
      attrs += "id" -> s"group:${group.id.value}"

    // Function to check if an attribute is a default value that shouldn't be included
    def isDefaultValue(key: String, value: String): Boolean = {
      import org.jpablo.graphexplorer.viewer.models.ViewerGroup.defaultGroupAttributes
      defaultGroupAttributes.values.get(AttributeId(key)).exists(_.toString == value)
    }

    // Process attributes directly from ViewerGroup
    group.attributes.values.foreach { case (attrId, attrValue) =>
      val key   = attrId.value
      val value = attrValue.toString
      if (!allExcludeKeys.contains(key) && key != "id") {
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

  // Helper to collect attributes from Arrow directly
  def collectEdgeAttributes(arrow: Arrow, excludeKeys: Set[String] = Set.empty): List[(String, String)] = {
    val attrs          = ListBuffer[(String, String)]()
    val internalAttrs  = if (omitInternal) Set("id") else Set.empty[String]
    val allExcludeKeys = excludeKeys ++ internalAttrs + GvId.attrId.value // Always exclude _gvid from output

    // Add id attribute for arrows
    if (!allExcludeKeys.contains("id"))
      attrs += "id" -> s"arrow:${arrow.source.value}->${arrow.target.value}/${arrow.seq}"

    // Process arrow attributes directly
    arrow.attributes.values.foreach { case (attrId, attrValue) =>
      val key   = attrId.value
      val value = attrValue.toString
      if (!allExcludeKeys.contains(key) && key != "id") {
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
  emitAttrStatement("graph", collectGraphAttributes(elements.graphAttributes, Set("name", "directed")))
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
    val tailPort = arrow.sourcePort.map(p => ":" + quoted(p)).getOrElse("")
    val headPort = arrow.targetPort.map(p => ":" + quoted(p)).getOrElse("")

    val edgeAttrs = collectEdgeAttributes(arrow, Set("tail", "head"))

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

  // Helper to process clusters recursively with proper nesting (using ViewerGraphElements directly)
  def processCluster(groupId: GroupId, level: Int, processedGroups: scala.collection.mutable.Set[GroupId]): Unit = {
    if (processedGroups.contains(groupId)) return
    processedGroups += groupId

    val group = elements.groups(groupId)

    // Graphviz only draws a subgraph as a cluster when its name starts with "cluster",
    // and GroupId.fromDot strips that prefix on import (storing cluster="true"). Restore
    // the prefix from the reliable `cluster` attribute — not from the id's spelling, which
    // dropped cluster semantics for ids with uppercase/underscores or > 10 chars.
    val isCluster    = group.attributes.values.get(AttributeId("cluster")).exists(_.toString == "true")
    val subgraphName =
      if (isCluster && !group.id.value.startsWith("cluster")) s"cluster_${group.id.value}"
      else group.id.value
    lines += s"""${padding(level)}subgraph ${quoted(subgraphName)} {"""

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
