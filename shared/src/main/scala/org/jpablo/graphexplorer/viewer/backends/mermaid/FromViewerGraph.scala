package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.*
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{ArrowHead, ArrowTail, BoldStyle, BorderStyle, Color, CornerStyle, Dir, FillColor, FontColor, FontName, FontSize, InvisibleStyle, Label, PenColor, PenWidth, Rankdir, Shape, Style}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.immutable.VectorMap

private val MermaidNodeCssPreferredKeyOrder = Vector("fill", "stroke", "stroke-width", "color", "font-family", "font-size")

/** Converts a ViewerGraph back to Mermaid text.
  *
  * A graph from a state diagram becomes a state diagram again (see
  * `viewerGraphToMermaidStateText`). Each other graph becomes a flowchart.
  */
def viewerGraphToMermaidText(graph: ViewerGraph): String =
  if isMermaidStateDiagram(graph) then viewerGraphToMermaidStateText(graph)
  else viewerGraphToMermaidFlowchartText(graph)

/** Converts a ViewerGraph back to Mermaid flowchart text.
  *
  * This is the inverse of `toViewerGraph` in ToViewerGraph.scala.
  */
private def viewerGraphToMermaidFlowchartText(graph: ViewerGraph): String =
  val lines = StringBuilder()

  // Get title from graph attributes (Label attribute)
  val titleOpt = graph.elements.graphAttributes.values
    .get(Label.attrId)
    .map(_.toString)
    .filter(_.nonEmpty)

  // Add front matter with title if present
  titleOpt.foreach { title =>
    lines.append("---\n")
    lines.append(s"title: ${MermaidSourceScan.titleToYaml(title)}\n")
    lines.append("---\n")
  }

  // Get direction from graph attributes, default to TB (top-to-bottom)
  val direction = graph.elements.graphAttributes.getAs(Rankdir).toString
  lines.append(s"flowchart $direction\n")

  emitClassDefLines(lines, graph.elements.graphAttributes)
  emitDefaultLinkStyleLine(lines, graph.elements.graphAttributes)

  // Get the root group ID to filter it out
  val rootGroupId = GroupId(ViewerGraphElements.defaultRootId.value)

  val connectedNodeIds = graph.arrows.values.flatMap(_.endpoints).toSet

  // One Mermaid id for each node and each group, before the first id is written
  val ids = MermaidIds(graph, rootGroupId, connectedNodeIds)

  // Shared indexes: one deterministic group ordering for the three group passes,
  // and one membership pass instead of a scan per group.
  val sortedGroups = graph.groups.toVector.sortBy(_._1.value)
  val nodesByGroup: Map[GroupId, List[NodeId]] =
    graph.memberships.toList
      .collect { case (nodeId: NodeId, gId) => (gId, nodeId) }
      .groupMap(_._1)(_._2)

  // Serialize subgraphs (groups, excluding root). Child groups are emitted INSIDE
  // their parent's block — Mermaid supports nested subgraphs, and emitting them flat
  // would silently drop the group-in-group memberships on the next parse.
  val childGroupsByParent: Map[GroupId, Vector[GroupId]] =
    graph.memberships.toVector
      .collect {
        case (childId: GroupId, parentId) if parentId != rootGroupId && graph.groups.contains(parentId) =>
          (parentId, childId)
      }
      .groupMap(_._1)(_._2)
  val nestedGroupIds: Set[GroupId] = childGroupsByParent.values.flatten.toSet

  val subgraphNodes = scala.collection.mutable.Set[NodeId]()
  val emittedGroups = scala.collection.mutable.Set[GroupId]() // guards against membership cycles

  def emitSubgraph(groupId: GroupId, group: ViewerGroup, depth: Int): Unit =
    if emittedGroups.add(groupId) then
      val indent = "  " * depth
      // A group with a new id shows its old id, as Mermaid shows the id of a subgraph that has no title
      val storedTitle = Some(group.label.toString)
        .filter(_.nonEmpty)
        .orElse(Option.when(ids.renamed(groupId))(storedFormOf(groupId.value)))
      val title = storedTitle.map(MermaidLabelText.fromStored).filter(_.nonEmpty) match
        case Some(s) => s" [${escapeMermaidLabel(s)}]"
        case None    => ""
      lines.append(s"${indent}subgraph ${ids.group(groupId)}$title\n")

      // Member nodes first, then child subgraphs, each in deterministic id order
      nodesByGroup.getOrElse(groupId, Nil).toVector.sortBy(_.value).foreach { nodeId =>
        graph.nodes.get(nodeId).foreach { node =>
          lines.append(s"$indent  ${serializeNode(nodeId, node, ids)}\n")
          subgraphNodes += nodeId
        }
      }
      childGroupsByParent.getOrElse(groupId, Vector.empty).sortBy(_.value).foreach { childId =>
        graph.groups.get(childId).foreach(child => emitSubgraph(childId, child, depth + 1))
      }

      lines.append(s"${indent}end\n")

  sortedGroups.foreach { case (groupId, group) =>
    if groupId != rootGroupId && !nestedGroupIds.contains(groupId) then
      emitSubgraph(groupId, group, depth = 1)
  }
  // A membership cycle (malformed input) leaves its groups unreachable from any
  // top-level emission — emit them at top level rather than dropping them.
  sortedGroups.foreach { case (groupId, group) =>
    if groupId != rootGroupId && !emittedGroups.contains(groupId) then
      emitSubgraph(groupId, group, depth = 1)
  }

  // Serialize nodes not in any subgraph
  graph.nodes.foreach { case (nodeId, node) =>
    if !subgraphNodes.contains(nodeId) then
      val shouldEmitNode = shouldEmitStandaloneNode(nodeId, node, connectedNodeIds, ids)
      if shouldEmitNode then
        val nodeLine = serializeNode(nodeId, node, ids)
        lines.append(s"  $nodeLine\n")
  }

  // Serialize edges and per-edge style directives (`linkStyle` indexes are by edge declaration order).
  val orderedArrows = sortedArrows(graph.arrows.values)
  orderedArrows.foreach { arrow =>
    val edgeLine = serializeEdge(arrow, ids)
    lines.append(s"  $edgeLine\n")
  }
  orderedArrows.zipWithIndex.foreach { case (arrow, edgeIndex) =>
    edgeStyleDirectiveCss(arrow).foreach { css =>
      lines.append(s"  linkStyle $edgeIndex $css\n")
    }
  }

  // Emit inline style directives for nodes, merging CSS style text and normalized node attrs.
  graph.nodes.foreach { case (nodeId, node) =>
    nodeStyleDirectiveCss(node).foreach { css =>
      lines.append(s"  style ${ids.node(nodeId)} $css\n")
    }
  }

  // Preserve DOT shapes with no Mermaid form through the round trip via a reserved
  // marker class (see MermaidAttrKeys.ShapeClassPrefix). Without this, e.g. a record
  // node came back as `shape=box` after one round trip and Split/Transpose Record
  // became permanently unavailable.
  graph.nodes.foreach { case (nodeId, node) =>
    node.attributes.values
      .get(Shape.attrId)
      .map(_.toString)
      .filter(s => mermaidMappedShape(s).isEmpty)
      .foreach(s => lines.append(s"  class ${ids.node(nodeId)} $ShapeClassPrefix$s\n"))
  }
  sortedGroups.foreach { case (groupId, group) =>
    if groupId != rootGroupId then
      groupStyleDirectiveCss(group).foreach { css =>
        lines.append(s"  style ${ids.group(groupId)} $css\n")
      }
  }
  emitGroupClassLines(lines, sortedGroups, rootGroupId, ids)

  lines.toString

/** Serialize a node with its shape and label. */
private def serializeNode(nodeId: NodeId, node: ViewerNode, ids: MermaidIds): String =
  val labelOpt = node.attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty)
  val shapeOpt = node.attributes.values.get(Shape.attrId).map(_.toString)
  val classOpt = node.attributes.values.get(MermaidClassAttr).map(_.toString).filter(_.nonEmpty)
  val rounded  = node.attributes.values.get(CornerStyle.attrId).exists(_.toString == "rounded")

  val safeId = ids.node(nodeId)
  // With no label, the canvas shows the id. Thus a node with a new id gets its old id as its label.
  val label = labelOpt.getOrElse(storedFormOf(nodeId.value))
  val (openBracket, closeBracket) = shapeOpt match
    case Some(s) =>
      mermaidMappedShape(s) match
        // A rounded box is Mermaid's round-bracket form
        case Some(("[", "]")) if rounded => ("(", ")")
        case Some(brackets)              => brackets
        // Unmapped DOT shape: rectangle here, the real shape rides a gx-shape class
        case None                        => ("[", "]")
    case None => if rounded then ("(", ")") else ("[", "]")
  val classSuffix = classOpt.map(c => s":::$c").getOrElse("")

  // Bare form only when the id is already Mermaid-safe and there is no shape/label/rounding to show.
  if shapeOpt.isEmpty && !rounded && label == nodeId.value && safeId == nodeId.value then
    s"$safeId$classSuffix"
  else
    // Escape label for Mermaid (quotes need special handling); stored line breaks
    // become <br/> (which also triggers quoting below)
    val escapedLabel = escapeMermaidLabel(MermaidLabelText.fromStored(label))
    s"$safeId$openBracket$escapedLabel$closeBracket$classSuffix"

/** The stored (DOT-escaped) form of an id, for a label. An id with no `\` and no line break is its own stored form,
  * thus this function calls `TextUtils.escape` only for the other ids.
  */
private def storedFormOf(id: String): String =
  if id.exists(c => c == '\\' || c == '\n') then TextUtils.escape(id) else id

/** Serialize an edge with its style and label. */
private def serializeEdge(arrow: Arrow, ids: MermaidIds): String =
  val (startArrow, endArrow) = effectiveEdgeMarkers(arrow.attributes)

  // Mermaid links can carry an end arrow, arrows at BOTH ends, or none — but no
  // tail-only form. A tail-only edge (the result of "Reverse Arrows Head/Tail Style"
  // under the app's dir=both theme) is rendered with SWAPPED endpoints and a forward
  // arrow: visually identical, though the flip becomes structural after the next
  // parse round-trip (pinned as LossyAs in FeatureParitySpec).
  val (src, dst, start, end) =
    if startArrow && !endArrow then (arrow.target, arrow.source, false, true)
    else (arrow.source, arrow.target, startArrow, endArrow)

  val arrowType = mermaidLinkToken(edgeLineStyle(arrow.attributes), start, end)

  val labelOpt  = arrow.attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty)
  val labelPart = labelOpt.map(l => s"|${escapeMermaidLabel(MermaidLabelText.fromStored(l))}|").getOrElse("")

  s"${ids.node(src)} $arrowType$labelPart ${ids.node(dst)}"

/** Which ends of the edge actually DRAW a marker. The app's edge theme renders with
  * dir=both (see ViewerGraph.defaultEdgeTheme), so the drawn markers are controlled by
  * the ArrowHead (default: an arrow) and ArrowTail (default: none) shapes; an explicit
  * `dir` attribute further masks them, matching Graphviz semantics.
  *
  * Public: DiagramSelectionOps.reverseArrowsStyle uses the same resolution to decide
  * when the swap must become an endpoint reversal in Mermaid mode.
  */
def effectiveEdgeMarkers(attrs: Attributes): (start: Boolean, end: Boolean) =
  val values    = attrs.values
  val headDrawn = values.get(ArrowHead.attrId).map(_.toString).forall(_ != "none")
  val tailDrawn = values.get(ArrowTail.attrId).map(_.toString).exists(_ != "none")
  values.get(Dir.attrId).map(_.toString) match
    case Some("forward") => (start = false, end = headDrawn)
    case Some("back")    => (start = tailDrawn, end = false)
    case Some("none")    => (start = false, end = false)
    case _               => (start = tailDrawn, end = headDrawn) // both (theme default) / unset

/** Derive the DOT line-style keyword (dashed/dotted/bold) for an edge from either a
  * collapsed `style` attribute (Mermaid-sourced) or the expanded style sub-attributes
  * BorderStyle/BoldStyle. DOT import expands `style` into these sub-attributes and
  * removes `style`, so reading `style` alone would silently drop dashed/dotted/bold
  * edges and emit plain solid arrows. Note: Mermaid cannot distinguish dashed from
  * dotted, so both collapse to `-.->`. */
private def edgeLineStyle(attrs: Attributes): Option[String] =
  val values = attrs.values
  values
    .get(Style.attrId).map(_.toString).filterNot(_.contains(":"))
    .orElse(Option.when(values.get(BoldStyle.attrId).exists(_.toString == "true"))("bold"))
    .orElse(values.get(BorderStyle.attrId).map(_.toString))

/** The Mermaid id of each node and each group of one graph.
  *
  * The writer builds this map once, before it writes the first line. Each place that writes an id (node definitions,
  * arrows, subgraphs, `style` lines and `class` lines) reads it, thus these places always agree.
  *
  * An id that Mermaid can use and that no other element has keeps its id. Thus the usual graph does not change. The map
  * gives a new id to an id that:
  *   - has a character that is not safe in a Mermaid id, or has `--` (Mermaid reads `--` as the start of a link);
  *   - is a flowchart keyword that breaks the Mermaid parse (see [[MermaidKeywordIds]]);
  *   - is the id of another element. Mermaid has one set of ids for nodes and for subgraphs, and DOT has two.
  *
  * One node is not another element: a stand-in for a group (see `standsForGroup` in `MermaidIds.apply`). This node
  * is not in `nodeIds`, and [[node]] gives it the id of its group.
  */
private final class MermaidIds private (nodeIds: Map[NodeId, String], groupIds: Map[GroupId, String]):
  def node(id: NodeId): String   = nodeIds.get(id).orElse(groupIds.get(GroupId(id.value))).getOrElse(id.value)
  def group(id: GroupId): String = groupIds.getOrElse(id, id.value)

  /** True when the node has a new id of its own. Then the writer must write the old id as its label. */
  def renamed(id: NodeId): Boolean = nodeIds.get(id).exists(_ != id.value)

  /** True when the group has a new id. Then the writer must write the old id as its title. */
  def renamed(id: GroupId): Boolean = groupIds.get(id).exists(_ != id.value)

private object MermaidIds:

  private val UnsafeIdChar = "[^A-Za-z0-9_-]".r
  private val HyphenRun    = "-{2,}".r

  /** `connectedNodeIds` holds the end nodes of each arrow. An end that is not in `graph.nodes` gets an id too. */
  def apply(graph: ViewerGraph, rootGroupId: GroupId, connectedNodeIds: Set[NodeId]): MermaidIds =
    val groups       = graph.groups.keys.filter(_ != rootGroupId).toVector.sortBy(_.value)
    val danglingEnds = connectedNodeIds.filterNot(graph.nodes.contains).toVector.sortBy(_.value)
    val nodes        = graph.nodes.keys.toVector ++ danglingEnds

    val groupIdValues = groups.map(_.value).toSet

    /** True when the node is a stand-in for a group: a node with no attributes and no group that has the id of a
      * group. The Mermaid reader (`toViewerGraph`) makes this node for an arrow to a subgraph, and in Mermaid the id
      * names the subgraph. Thus this node keeps the id of its group.
      */
    def standsForGroup(id: NodeId): Boolean =
      groupIdValues.contains(id.value) &&
        graph.nodes.get(id).forall(_.attributes.values.isEmpty) &&
        !graph.memberships.contains(id)
    val ownNodes = nodes.filterNot(standsForGroup)

    val taken    = scala.collection.mutable.Set[String]()
    val nodeIds  = scala.collection.mutable.Map[NodeId, String]()
    val groupIds = scala.collection.mutable.Map[GroupId, String]()

    // First the ids that do not change, nodes before groups. Thus a new id cannot take an id that stays.
    ownNodes.foreach(id => if MermaidKeywordIds.usable(id.value, subgraph = false) && taken.add(id.value) then nodeIds(id) = id.value)
    groups.foreach(id => if MermaidKeywordIds.usable(id.value, subgraph = true) && taken.add(id.value) then groupIds(id) = id.value)

    // Then a new id for each other element
    def newId(id: String, subgraph: Boolean): String =
      val next = freshId(id, subgraph, taken.contains)
      taken += next
      next
    ownNodes.foreach(id => if !nodeIds.contains(id) then nodeIds(id) = newId(id.value, subgraph = false))
    groups.foreach(id => if !groupIds.contains(id) then groupIds(id) = newId(id.value, subgraph = true))

    new MermaidIds(nodeIds.toMap, groupIds.toMap)

  /** A new Mermaid id for `id`, made of ASCII characters, that `taken` does not have.
    *
    * The steps below give a base that Mermaid can use: it has only safe characters, no `--`, and no keyword at its
    * start. A `_2` or `_3` at its end does not change that.
    */
  private def freshId(id: String, subgraph: Boolean, taken: String => Boolean): String =
    val chars    = UnsafeIdChar.replaceAllIn(id, "_")
    val noLinks  = HyphenRun.replaceAllIn(chars, m => "_" * m.matched.length)
    val nonEmpty = if noLinks.isEmpty then "id" else noLinks
    // `end` becomes `end_`, and `end-x` becomes `end_x`: a word character after the keyword stops the match
    val base = MermaidKeywordIds.keywordAt(nonEmpty, subgraph) match
      case Some(keyword) => keyword + "_" + nonEmpty.drop(keyword.length).stripPrefix("-")
      case None          => nonEmpty
    Iterator.from(1)
      .map(n => if n == 1 then base else s"${base}_$n")
      .find(candidate => !taken(candidate))
      .get

/** The flowchart keywords that break the Mermaid parse when they are an id.
  *
  * The list comes from the lexer of Mermaid 11.12 (`flowDiagram`), and a check of each id with real Mermaid confirmed
  * it (MermaidWriterRealMermaidSpec). The match is case-sensitive: `End`, `Style` and `Class` are good ids.
  */
private object MermaidKeywordIds:

  /** The lexer matches these words with `\b` after them. Thus the word breaks the parse alone, and also before a
    * character that is not `[A-Za-z0-9_]`, for example `end-x` or `endé`. `end_x` and `end1` are good ids.
    */
  private val WordKeywords = Set(
    "end", "subgraph", "graph", "flowchart", "style", "class", "classDef", "linkStyle", "interpolate",
    "_self", "_blank", "_parent", "_top"
  )

  /** The lexer matches these words with white space after them. Thus only the whole word breaks the parse. */
  private val SpaceKeywords = Set("click", "call", "href")

  /** Mermaid reads this word as a keyword only after `subgraph`. A node can have the id `default`. */
  private val SubgraphKeywords = Set("default")

  /** The word keywords after `subgraph`. */
  private val SubgraphWordKeywords = WordKeywords ++ SubgraphKeywords

  private def isAsciiWordChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'

  /** The keyword at the start of `id` that breaks the parse, if one is there. */
  def keywordAt(id: String, subgraph: Boolean): Option[String] =
    val words = if subgraph then SubgraphWordKeywords else WordKeywords
    words
      .find(word => id.startsWith(word) && id.lift(word.length).forall(c => !isAsciiWordChar(c)))
      .orElse(SpaceKeywords.find(_ == id))

  /** True when Mermaid reads `id` as this one id, after `subgraph` when `subgraph` is true. */
  def usable(id: String, subgraph: Boolean): Boolean =
    id.nonEmpty &&
      id.forall(MermaidSourceScan.isIdentifierChar) &&
      !id.contains("--") &&
      keywordAt(id, subgraph).isEmpty

/** Maps DOT shape names to Mermaid bracket syntax (paired with `mermaidShapeToDot`),
  * or None when Mermaid has no form for the shape (record, house, ...): the caller
  * then renders a rectangle and preserves the real shape via a `gx-shape-*` class.
  *
  * Not a perfect inverse: Mermaid has fewer shapes than DOT, so several DOT shapes
  * collapse onto the same brackets (e.g. `ellipse` and `stadium` both use `([ ])`).
  * `mermaidShapeToDot` resolves each bracket to one canonical DOT shape so the
  * common shapes stay stable across a round-trip.
  */
private def mermaidMappedShape(dotShape: String): Option[(String, String)] =
  dotShape.toLowerCase match
    case "box" | "rect" | "rectangle" => Some(("[", "]"))
    // The collapsed-group proxy's shape (CollapseOps.proxyAttributes). Mermaid has no
    // folder; the subroutine's double-struck rectangle is its "more inside" figure, so
    // a collapsed box stays visually distinct from a plain node in Mermaid mode too.
    case "folder"                     => Some(("[[", "]]"))
    case "diamond"                    => Some(("{", "}"))
    case "circle"                     => Some(("((", "))"))
    case "ellipse"                    => Some(("([", "])"))
    case "cylinder"                   => Some(("[(", ")]"))
    case "hexagon"                    => Some(("{{", "}}"))
    case "parallelogram"              => Some(("[/", "/]"))
    case "trapezium" | "trapezoid"    => Some(("[/", "\\]"))
    case "invtrapezium"               => Some(("[\\", "/]"))
    case "doublecircle"               => Some(("(((", ")))"))
    case "stadium"                    => Some(("([", "])"))
    case _                            => None

/** Maps DOT edge styles + drawn markers to Mermaid link syntax. Mermaid's arrow syntax
  * cannot distinguish dashed from dotted, so both map to the dotted family
  * (round-tripping as dashed); finer distinctions would need a `linkStyle
  * stroke-dasharray` directive. `start && !end` never reaches here (serializeEdge
  * swaps such edges into the `!start && end` form).
  */
private def mermaidLinkToken(styleOpt: Option[String], start: Boolean, end: Boolean): String =
  val family = styleOpt match
    case Some("dashed") | Some("dotted") => (fwd = "-.->", both = "<-.->", open = "-.-")
    case Some("bold")                    => (fwd = "==>", both = "<==>", open = "===")
    case _                               => (fwd = "-->", both = "<-->", open = "---")
  (start, end) match
    case (true, true)   => family.both
    case (false, false) => family.open
    case _              => family.fwd

private def shouldEmitStandaloneNode(nodeId: NodeId, node: ViewerNode, connectedNodeIds: Set[NodeId], ids: MermaidIds): Boolean =
  val labelOpt         = node.attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty)
  val shapeOpt         = node.attributes.values.get(Shape.attrId).map(_.toString)
  val hasExplicitLabel = labelOpt.exists(_ != nodeId.value)
  val hasShape         = shapeOpt.nonEmpty
  val hasClass         = node.attributes.values.contains(MermaidClassAttr)
  val hasCssStyle      = node.attributes.values.get(Style.attrId).exists(_.toString.contains(":"))

  // A node with a new id needs a line of its own, because only that line can carry its old id as its label
  val renamed = ids.renamed(nodeId)

  !connectedNodeIds.contains(nodeId) || hasExplicitLabel || hasShape || hasClass || hasCssStyle || renamed

private def emitClassDefLines(lines: StringBuilder, graphAttributes: Attributes): Unit =
  collectClassDefBodies(graphAttributes).foreach { case (className, body) =>
    lines.append(s"  classDef $className $body\n")
  }

private[mermaid] def collectClassDefBodies(graphAttributes: Attributes): Vector[(String, String)] =
  val styleByClass = graphAttributes.values.collect {
    case (attrId, value) if attrId.value.startsWith(MermaidClassDefPrefix) =>
      attrId.value.stripPrefix(MermaidClassDefPrefix) -> value.toString
  }
  val textByClass = graphAttributes.values.collect {
    case (attrId, value) if attrId.value.startsWith(MermaidClassDefTextPrefix) =>
      attrId.value.stripPrefix(MermaidClassDefTextPrefix) -> value.toString
  }

  val allClassNames = (styleByClass.keySet ++ textByClass.keySet).toVector.sorted
  allClassNames.flatMap { className =>
    val merged = mergeMermaidCssBodies(styleByClass.get(className), textByClass.get(className))
    merged.map(className -> _)
  }

private def emitDefaultLinkStyleLine(lines: StringBuilder, graphAttributes: Attributes): Unit =
  val defaultStyleOpt = graphAttributes.values.get(MermaidDefaultLinkStyleAttr).map(_.toString).filter(_.nonEmpty)
  val defaultInterpolateOpt = graphAttributes.values
    .get(MermaidDefaultLinkInterpolateAttr)
    .map(_.toString)
    .filter(_.nonEmpty)
    .map(value => s"interpolate:$value")

  mergeMermaidCssBodies(defaultStyleOpt, defaultInterpolateOpt).foreach { body =>
    lines.append(s"  linkStyle default $body\n")
  }

private def emitGroupClassLines(
    lines:        StringBuilder,
    sortedGroups: Vector[(GroupId, ViewerGroup)],
    rootGroupId:  GroupId,
    ids:          MermaidIds
): Unit =
  sortedGroups.foreach { case (groupId, group) =>
    if groupId != rootGroupId then
      val classNames = group.attributes.values
        .get(MermaidClassAttr)
        .map(_.toString)
        .toVector
        .flatMap(parseMermaidClassNames)
      if classNames.nonEmpty then
        lines.append(s"  class ${ids.group(groupId)} ${classNames.mkString(",")}\n")
  }

private def mergeMermaidCssBodies(styleBodyOpt: Option[String], extraBodyOpt: Option[String]): Option[String] =
  val base   = styleBodyOpt.map(MermaidStyleDeclarations.parse).getOrElse(VectorMap.empty)
  val extra  = extraBodyOpt.map(MermaidStyleDeclarations.parse).getOrElse(VectorMap.empty)
  val merged = base ++ extra
  if merged.isEmpty then None
  else Some(formatMermaidCssBody(merged))

// --- css directive helpers -----------------------------------------------------------
// The DOT-attribute -> css-declaration mapping lives once here, shared by the
// node/edge/group style directives (it is the inverse of the css -> DOT derivation in
// graph.AttributesOps).

private def cssValue(attrs: Map[AttributeId, AttrValue], attrId: AttributeId): Option[String] =
  attrs.get(attrId).map(_.toString).filter(_.nonEmpty)

private def cssPx(attrs: Map[AttributeId, AttrValue], attrId: AttributeId): Option[String] =
  attrs.get(attrId).map(v => s"${v.toString}px").filter(_.nonEmpty)

/** Parse an attribute holding css declarations (only when it looks like css, i.e. contains ':'). */
private def cssDeclarationsOf(attrs: Map[AttributeId, AttrValue], attrId: AttributeId): VectorMap[String, String] =
  attrs
    .get(attrId)
    .map(_.toString)
    .filter(_.contains(":"))
    .map(MermaidStyleDeclarations.parse)
    .getOrElse(VectorMap.empty)

/** Layer per-attribute overrides (in order) onto base css declarations, drop blank values, format. */
private def mergeCssOverrides(
    base:      VectorMap[String, String],
    overrides: Seq[(String, Option[String])]
): Option[String] =
  val merged = overrides
    .foldLeft(base) { case (acc, (key, valueOpt)) =>
      acc.updatedWith(key)(existing => valueOpt.orElse(existing))
    }
    .filter((_, value) => value.trim.nonEmpty)
  if merged.isEmpty then None
  else Some(formatMermaidCssBody(merged))

private[mermaid] def nodeStyleDirectiveCss(node: ViewerNode): Option[String] =
  val attrs = node.attributes.values
  mergeCssOverrides(
    cssDeclarationsOf(attrs, Style.attrId),
    Seq(
      "fill"         -> cssValue(attrs, FillColor.attrId),
      "stroke"       -> cssValue(attrs, Color.attrId),
      "stroke-width" -> cssPx(attrs, PenWidth.attrId),
      "color"        -> cssValue(attrs, FontColor.attrId),
      "font-family"  -> cssValue(attrs, FontName.attrId),
      "font-size"    -> cssPx(attrs, FontSize.attrId),
      // Border style / invisibility ride on css (Mermaid has no attribute for them);
      // the distinct dash patterns keep dashed vs dotted recoverable
      "stroke-dasharray" -> attrs.get(BorderStyle.attrId).map(_.toString).collect {
        case "dashed" => "6 4"
        case "dotted" => "2 2"
      },
      "opacity" -> attrs.get(InvisibleStyle.attrId).map(_.toString).collect { case "true" => "0" }
    )
  )

private def edgeStyleDirectiveCss(arrow: Arrow): Option[String] =
  val attrs = arrow.attributes.values
  val interpolateDeclaration = attrs
    .get(MermaidEdgeInterpolateAttr)
    .map(_.toString)
    .filter(_.nonEmpty)
    .map(v => VectorMap("interpolate" -> v))
    .getOrElse(VectorMap.empty)

  mergeCssOverrides(
    cssDeclarationsOf(attrs, MermaidEdgeStyleAttr) ++ cssDeclarationsOf(attrs, Style.attrId) ++ interpolateDeclaration,
    Seq(
      "stroke"       -> cssValue(attrs, Color.attrId),
      "stroke-width" -> cssPx(attrs, PenWidth.attrId),
      "color"        -> cssValue(attrs, FontColor.attrId),
      "font-family"  -> cssValue(attrs, FontName.attrId),
      "font-size"    -> cssPx(attrs, FontSize.attrId)
    )
  )

private[mermaid] def groupStyleDirectiveCss(group: ViewerGroup): Option[String] =
  val attrs       = group.attributes.values
  val borderColor = attrs.get(PenColor.attrId).orElse(attrs.get(Color.attrId)).map(_.toString).filter(_.nonEmpty)
  mergeCssOverrides(
    cssDeclarationsOf(attrs, Style.attrId),
    Seq(
      "fill"         -> cssValue(attrs, FillColor.attrId),
      "stroke"       -> borderColor,
      "stroke-width" -> cssPx(attrs, PenWidth.attrId),
      "color"        -> cssValue(attrs, FontColor.attrId),
      "font-family"  -> cssValue(attrs, FontName.attrId),
      "font-size"    -> cssPx(attrs, FontSize.attrId)
    )
  )

private def formatMermaidCssBody(declarations: VectorMap[String, String]): String =
  val preferred = MermaidNodeCssPreferredKeyOrder.flatMap { key =>
    declarations.get(key).map(value => key -> value)
  }
  val preferredKeys = MermaidNodeCssPreferredKeyOrder.toSet
  val rest = declarations.iterator
    .filterNot((key, _) => preferredKeys.contains(key))
    .toVector
    .sortBy(_._1)

  (preferred ++ rest).map { case (key, value) => s"$key:$value" }.mkString(",")

private def sortedArrows(arrows: Iterable[Arrow]): Vector[Arrow] =
  arrows.toVector.sortBy(arrow => (arrow.source.value, arrow.target.value, arrow.seq, arrow.id.value))

/** Escape special characters in Mermaid labels. */
private def escapeMermaidLabel(label: String): String =
  // Mermaid uses double quotes for labels with special characters
  if label.contains("\"") || label.contains("[") || label.contains("]") ||
    label.contains("{") || label.contains("}") || label.contains("(") ||
    label.contains(")") || label.contains("|") || label.contains("<") ||
    label.contains(">")
  then
    // Wrap in quotes and escape internal quotes
    "\"" + MermaidSourceScan.encodeQuotes(label) + "\""
  else label
