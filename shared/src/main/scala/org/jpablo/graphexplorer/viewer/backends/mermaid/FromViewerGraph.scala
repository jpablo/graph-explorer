package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Rankdir, Shape, Style}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.immutable.VectorMap

private val MermaidClassDefPrefix             = "mermaid_classDef_"
private val MermaidClassDefTextPrefix         = "mermaid_classDefText_"
private val MermaidClassAttr                  = AttributeId("mermaid_class")
private val MermaidDefaultLinkStyleAttr       = AttributeId("mermaid_linkStyle_default")
private val MermaidDefaultLinkInterpolateAttr = AttributeId("mermaid_linkInterpolate_default")

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

  // Serialize subgraphs (groups, excluding root)
  val subgraphNodes = scala.collection.mutable.Set[NodeId]()
  graph.groups.toVector.sortBy(_._1.value).foreach { case (groupId, group) =>
    if groupId != rootGroupId then
      val title = group.label.toString match
        case s if s.nonEmpty => s" [$s]"
        case _               => ""
      lines.append(s"  subgraph ${groupId.value}$title\n")

      // Find nodes in this subgraph
      val nodesInGroup = graph.memberships.collect {
        case (nodeId: NodeId, gId) if gId == groupId => nodeId
      }

      // Serialize nodes in this subgraph
      nodesInGroup.toVector.sortBy(_.value).foreach { nodeId =>
        graph.nodes.get(nodeId).foreach { node =>
          val nodeLine = serializeNode(nodeId, node)
          lines.append(s"    $nodeLine\n")
          subgraphNodes += nodeId
        }
      }

      lines.append("  end\n")
  }

  // Serialize nodes not in any subgraph
  graph.nodes.foreach { case (nodeId, node) =>
    if !subgraphNodes.contains(nodeId) then
      val shouldEmitNode = shouldEmitStandaloneNode(nodeId, node, connectedNodeIds)
      if shouldEmitNode then
        val nodeLine = serializeNode(nodeId, node)
        lines.append(s"  $nodeLine\n")
  }

  // Serialize edges
  sortedArrows(graph.arrows.values).foreach { arrow =>
    val edgeLine = serializeEdge(arrow)
    lines.append(s"  $edgeLine\n")
  }

  // Emit inline style directives for nodes with CSS in their Style attribute
  graph.nodes.foreach { case (nodeId, node) =>
    node.attributes.values.get(Style.attrId).map(_.toString).filter(_.contains(":")).foreach { css =>
      lines.append(s"  style ${nodeId.value} $css\n")
    }
  }

  lines.toString

/** Serialize a node with its shape and label. */
private def serializeNode(nodeId: NodeId, node: ViewerNode): String =
  val labelOpt = node.attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty)
  val shapeOpt = node.attributes.values.get(Shape.attrId).map(_.toString)
  val classOpt = node.attributes.values.get(MermaidClassAttr).map(_.toString).filter(_.nonEmpty)

  val label                       = labelOpt.getOrElse(nodeId.value)
  val (openBracket, closeBracket) = shapeOpt.map(dotShapeToMermaid).getOrElse(("[", "]"))
  val classSuffix                 = classOpt.map(c => s":::$c").getOrElse("")

  if shapeOpt.isEmpty && label == nodeId.value then
    s"${nodeId.value}$classSuffix"
  else
    // Escape label for Mermaid (quotes need special handling)
    val escapedLabel = escapeMermaidLabel(label)
    s"${nodeId.value}$openBracket$escapedLabel$closeBracket$classSuffix"

/** Serialize an edge with its style and label. */
private def serializeEdge(arrow: Arrow): String =
  val styleOpt  = arrow.attributes.values.get(Style.attrId).map(_.toString)
  val arrowType = dotStyleToMermaidArrow(styleOpt)

  val labelOpt  = arrow.attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty)
  val labelPart = labelOpt.map(l => s"|${escapeMermaidLabel(l)}|").getOrElse("")

  s"${arrow.source.value} $arrowType$labelPart ${arrow.target.value}"

/** Maps DOT shape names back to Mermaid bracket syntax.
  *
  * This is the inverse of `mermaidShapeToDot` in ToViewerGraph.scala.
  */
private def dotShapeToMermaid(dotShape: String): (String, String) =
  dotShape.toLowerCase match
    case "box" | "rect" | "rectangle" => ("[", "]")
    case "diamond"                    => ("{", "}")
    case "circle"                     => ("((", "))")
    case "ellipse"                    => ("([", "])")
    case "cylinder"                   => ("[(", ")]")
    case "hexagon"                    => ("{{", "}}")
    case "parallelogram"              => ("[/", "/]")
    case "trapezium" | "trapezoid"    => ("[/", "\\]")
    case "invtrapezium"               => ("[\\", "/]")
    case "doublecircle"               => ("(((", ")))")
    case "stadium"                    => ("([", "])")
    case _                            => ("[", "]") // Default to rectangle

/** Maps DOT edge styles to Mermaid arrow syntax. */
private def dotStyleToMermaidArrow(styleOpt: Option[String]): String =
  styleOpt match
    case Some("dashed") => "-.->"
    case Some("bold")   => "==>"
    case Some("dotted") => "-.->"
    case _              => "-->"

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

private def mergeMermaidCssBodies(styleBodyOpt: Option[String], extraBodyOpt: Option[String]): Option[String] =
  val base   = styleBodyOpt.map(MermaidStyleDeclarations.parse).getOrElse(VectorMap.empty)
  val extra  = extraBodyOpt.map(MermaidStyleDeclarations.parse).getOrElse(VectorMap.empty)
  val merged = base ++ extra
  if merged.isEmpty then None
  else Some(merged.map { case (key, value) => s"$key:$value" }.mkString(","))

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
