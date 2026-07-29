package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{ArrowHead, ArrowTail, BoldStyle, BorderStyle, Color, CornerStyle, Dir, FillColor, FontColor, FontName, FontSize, InvisibleStyle, Label, PenColor, PenWidth, Rankdir, Shape, Style}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.immutable.VectorMap

private val MermaidNodeCssPreferredKeyOrder = Vector("fill", "stroke", "stroke-width", "color", "font-family", "font-size")
private val MermaidUnsafeIdChar             = "[^A-Za-z0-9_-]".r

/** Converts a ViewerGraph back to Mermaid flowchart text.
  *
  * This is the inverse of `toViewerGraph` in ToViewerGraph.scala.
  */
def viewerGraphToMermaidText(graph: ViewerGraph): String =
  val lines = StringBuilder()

  // Get title from graph attributes (Label attribute)
  val titleOpt = graph.elements.graphAttributes.values
    .get(Label.attrId)
    .map(_.toString)
    .filter(_.nonEmpty)

  // Add front matter with title if present
  titleOpt.foreach { title =>
    lines.append("---\n")
    lines.append(s"title: $title\n")
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
      val title = MermaidLabelText.fromStored(group.label.toString) match
        case s if s.nonEmpty => s" [$s]"
        case _               => ""
      lines.append(s"${indent}subgraph ${mermaidId(groupId.value)}$title\n")

      // Member nodes first, then child subgraphs, each in deterministic id order
      nodesByGroup.getOrElse(groupId, Nil).toVector.sortBy(_.value).foreach { nodeId =>
        graph.nodes.get(nodeId).foreach { node =>
          lines.append(s"$indent  ${serializeNode(nodeId, node)}\n")
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
      val shouldEmitNode = shouldEmitStandaloneNode(nodeId, node, connectedNodeIds)
      if shouldEmitNode then
        val nodeLine = serializeNode(nodeId, node)
        lines.append(s"  $nodeLine\n")
  }

  // Serialize edges and per-edge style directives (`linkStyle` indexes are by edge declaration order).
  val orderedArrows = sortedArrows(graph.arrows.values)
  orderedArrows.foreach { arrow =>
    val edgeLine = serializeEdge(arrow)
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
      lines.append(s"  style ${mermaidId(nodeId.value)} $css\n")
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
      .foreach(s => lines.append(s"  class ${mermaidId(nodeId.value)} $ShapeClassPrefix$s\n"))
  }
  sortedGroups.foreach { case (groupId, group) =>
    if groupId != rootGroupId then
      groupStyleDirectiveCss(group).foreach { css =>
        lines.append(s"  style ${mermaidId(groupId.value)} $css\n")
      }
  }
  emitGroupClassLines(lines, sortedGroups, rootGroupId)

  lines.toString

/** Serialize a node with its shape and label. */
private def serializeNode(nodeId: NodeId, node: ViewerNode): String =
  val labelOpt = node.attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty)
  val shapeOpt = node.attributes.values.get(Shape.attrId).map(_.toString)
  val classOpt = node.attributes.values.get(MermaidClassAttr).map(_.toString).filter(_.nonEmpty)
  val rounded  = node.attributes.values.get(CornerStyle.attrId).exists(_.toString == "rounded")

  val safeId = mermaidId(nodeId.value)
  val label  = labelOpt.getOrElse(nodeId.value)
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

/** Serialize an edge with its style and label. */
private def serializeEdge(arrow: Arrow): String =
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

  s"${mermaidId(src.value)} $arrowType$labelPart ${mermaidId(dst.value)}"

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

/** Map a node/subgraph id to a Mermaid-safe identifier, applied consistently at every id
  * emission site (node defs, edges, subgraphs, style/class directives) so references still
  * resolve. Ids with whitespace or metacharacters (possible after DOT import of quoted ids)
  * would otherwise produce invalid/mis-parsed Mermaid. Already-safe ids pass through unchanged. */
private def mermaidId(id: String): String =
  if id.forall(MermaidSourceScan.isIdentifierChar) then id
  else MermaidUnsafeIdChar.replaceAllIn(id, "_")

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

private def shouldEmitStandaloneNode(nodeId: NodeId, node: ViewerNode, connectedNodeIds: Set[NodeId]): Boolean =
  val labelOpt         = node.attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty)
  val shapeOpt         = node.attributes.values.get(Shape.attrId).map(_.toString)
  val hasExplicitLabel = labelOpt.exists(_ != nodeId.value)
  val hasShape         = shapeOpt.nonEmpty
  val hasClass         = node.attributes.values.contains(MermaidClassAttr)
  val hasCssStyle      = node.attributes.values.get(Style.attrId).exists(_.toString.contains(":"))

  !connectedNodeIds.contains(nodeId) || hasExplicitLabel || hasShape || hasClass || hasCssStyle

private def emitClassDefLines(lines: StringBuilder, graphAttributes: Attributes): Unit =
  collectClassDefBodies(graphAttributes).foreach { case (className, body) =>
    lines.append(s"  classDef $className $body\n")
  }

private def collectClassDefBodies(graphAttributes: Attributes): Vector[(String, String)] =
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
    rootGroupId:  GroupId
): Unit =
  sortedGroups.foreach { case (groupId, group) =>
    if groupId != rootGroupId then
      val classNames = group.attributes.values
        .get(MermaidClassAttr)
        .map(_.toString)
        .toVector
        .flatMap(parseMermaidClassNames)
      if classNames.nonEmpty then
        lines.append(s"  class ${mermaidId(groupId.value)} ${classNames.mkString(",")}\n")
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

private def nodeStyleDirectiveCss(node: ViewerNode): Option[String] =
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

private def groupStyleDirectiveCss(group: ViewerGroup): Option[String] =
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
    "\"" + label.replace("\"", "#quot;") + "\""
  else label
