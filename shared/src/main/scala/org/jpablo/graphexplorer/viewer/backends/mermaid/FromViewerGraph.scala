package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Rankdir, Shape, Style}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*

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

  // Get the root group ID to filter it out
  val rootGroupId = GroupId(ViewerGraphElements.defaultRootId.value)

  val connectedNodeIds = graph.arrows.values.flatMap(_.endpoints).toSet

  // Serialize subgraphs (groups, excluding root)
  val subgraphNodes = scala.collection.mutable.Set[NodeId]()
  graph.groups.foreach { case (groupId, group) =>
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
      nodesInGroup.foreach { nodeId =>
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
  graph.arrows.foreach { case (_, arrow) =>
    val edgeLine = serializeEdge(arrow)
    lines.append(s"  $edgeLine\n")
  }

  lines.toString

/** Serialize a node with its shape and label. */
private def serializeNode(nodeId: NodeId, node: ViewerNode): String =
  val labelOpt = node.attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty)
  val shapeOpt = node.attributes.values.get(Shape.attrId).map(_.toString)

  val label = labelOpt.getOrElse(nodeId.value)
  val (openBracket, closeBracket) = shapeOpt.map(dotShapeToMermaid).getOrElse(("[", "]"))

  if shapeOpt.isEmpty && label == nodeId.value then
    nodeId.value
  else
    // Escape label for Mermaid (quotes need special handling)
    val escapedLabel = escapeMermaidLabel(label)
    s"${nodeId.value}$openBracket$escapedLabel$closeBracket"

/** Serialize an edge with its style and label. */
private def serializeEdge(arrow: Arrow): String =
  val styleOpt = arrow.attributes.values.get(Style.attrId).map(_.toString)
  val arrowType = dotStyleToMermaidArrow(styleOpt)

  val labelOpt = arrow.attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty)
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
  val labelOpt = node.attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty)
  val shapeOpt = node.attributes.values.get(Shape.attrId).map(_.toString)
  val hasExplicitLabel = labelOpt.exists(_ != nodeId.value)
  val hasShape = shapeOpt.nonEmpty

  !connectedNodeIds.contains(nodeId) || hasExplicitLabel || hasShape

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
