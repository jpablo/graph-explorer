package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{GraphType, Label, Shape, Style}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.immutable.VectorMap

/** Converts a MermaidGraph to a ViewerGraph.
  *
  * Since Mermaid has a simpler attribute model than DOT, this conversion maps Mermaid concepts to their closest DOT
  * equivalents.
  */
def toViewerGraph(mermaidGraph: MermaidGraph): ViewerGraph =
  ViewerGraph(
    elements = toViewerGraphElements(mermaidGraph),
    id = "mermaid",
    tpe = GraphType.digraph // Mermaid flowcharts are always directed
  )

/** Converts a MermaidGraph to ViewerGraphElements.
  */
def toViewerGraphElements(mermaidGraph: MermaidGraph): ViewerGraphElements =
  // Convert vertices to nodes
  val nodes: VectorMap[NodeId, ViewerNode] =
    VectorMap.from(
      mermaidGraph.vertices.map { case (id, vertex) =>
        val nodeId = NodeId(id)
        val attrs  = vertexToAttributes(vertex)
        nodeId -> ViewerNode.nodeNoDefaults(nodeId, attrs)
      }
    )

  // Convert edges to arrows
  // Mermaid assigns edge DOM ids using a per-(source,target) sequence counter.
  val edgeCounts = scala.collection.mutable.Map[(String, String), Int]().withDefaultValue(0)

  val arrows: Map[ArrowId, Arrow] =
    mermaidGraph.edges.map { edge =>
      val sourceId = NodeId(edge.start)
      val targetId = NodeId(edge.end)

      val key = (edge.start, edge.end)
      edgeCounts(key) += 1
      val seq = edgeCounts(key)

      val attrs = edgeToAttributes(edge)
      val arrow = Arrow(
        source = sourceId,
        target = targetId,
        seq = seq,
        attributes = attrs
      )
      arrow.id -> arrow
    }.toMap

  // Convert subgraphs to groups
  val groups: Map[GroupId, ViewerGroup] =
    mermaidGraph.subgraphs.map { subgraph =>
      val groupId = GroupId(subgraph.id)
      val attrs   = subgraphToAttributes(subgraph)
      groupId -> ViewerGroup.group(groupId, attrs)
    }.toMap

  // Build memberships from subgraphs
  val memberships: VectorMap[GroupMemberId, GroupId] =
    VectorMap.from(
      mermaidGraph.subgraphs.flatMap { subgraph =>
        val groupId = GroupId(subgraph.id)
        subgraph.nodes.map { nodeIdStr =>
          NodeId(nodeIdStr) -> groupId
        }
      }
    )

  // Ensure all edge endpoints exist in nodes
  val missingNodes = arrows.values.flatMap(_.endpoints).filterNot(nodes.contains)
  val allNodes = nodes ++ missingNodes.map(nid => nid -> ViewerNode.nodeNoDefaults(nid, Attributes.empty))

  ViewerGraphElements(
    nodes = allNodes,
    arrows = arrows,
    memberships = memberships,
    groups = groups,
    graphAttributes = Attributes.empty
  )

/** Converts a MermaidVertex to Attributes. */
private def vertexToAttributes(vertex: MermaidVertex): Attributes =
  val attrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()

  // Label (use text if different from id)
  if vertex.text != vertex.id then attrs += Label.attrId -> AttrValue(vertex.text)

  // Shape mapping from Mermaid to DOT
  vertex.shape.foreach { shape =>
    val dotShape = mermaidShapeToDot(shape)
    attrs += Shape.attrId -> AttrValue(dotShape)
  }

  // Store Mermaid-specific data as custom attributes
  vertex.domId.foreach(v => attrs += AttributeId("mermaid_domId") -> AttrValue(v))

  // Combine styles into a single style attribute if present
  if vertex.styles.nonEmpty then attrs += Style.attrId -> AttrValue(vertex.styles.mkString(","))

  // Store classes as a custom attribute
  if vertex.classes.nonEmpty then attrs += AttributeId("class") -> AttrValue(vertex.classes.mkString(" "))

  Attributes(VectorMap.from(attrs.toSeq))

/** Converts a MermaidEdge to Attributes. */
private def edgeToAttributes(edge: MermaidEdge): Attributes =
  val attrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()

  // Label
  edge.text.filter(_.nonEmpty).foreach(v => attrs += Label.attrId -> AttrValue(v))

  // Map Mermaid stroke to DOT style
  edge.stroke.foreach {
    case "dotted" => attrs += Style.attrId -> AttrValue("dashed")
    case "thick"  => attrs += Style.attrId -> AttrValue("bold")
    case _        => // "normal" or unknown - no style needed
  }

  // Store edge type as custom attribute
  edge.edgeType.foreach(v => attrs += AttributeId("mermaid_edgeType") -> AttrValue(v))

  Attributes(VectorMap.from(attrs.toSeq))

/** Converts a MermaidSubgraph to Attributes. */
private def subgraphToAttributes(subgraph: MermaidSubgraph): Attributes =
  val attrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()

  subgraph.title.foreach(v => attrs += Label.attrId -> AttrValue(v))

  Attributes(VectorMap.from(attrs.toSeq))

/** Maps Mermaid shape names to DOT shape names. */
private def mermaidShapeToDot(mermaidShape: String): String =
  mermaidShape.toLowerCase match
    case "rect" | "rectangle"      => "box"
    case "round" | "rounded"       => "box" // with rounded corners style
    case "stadium"                 => "box"
    case "subroutine"              => "box"
    case "cylinder" | "database"   => "cylinder"
    case "circle"                  => "circle"
    case "ellipse"                 => "ellipse"
    case "diamond" | "rhombus"     => "diamond"
    case "hexagon"                 => "hexagon"
    case "parallelogram"           => "parallelogram"
    case "parallelogram-alt"       => "parallelogram"
    case "trapezoid"               => "trapezium"
    case "trapezoid-alt"           => "invtrapezium"
    case "double-circle" | "doublecircle" => "doublecircle"
    case other                     => "box" // Default to box
