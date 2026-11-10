package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{ArrowHead, ArrowTail, CornerStyle, GraphType, Label, Rankdir, Shape, Style}
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
  val referencedNodeIds: Set[String] =
    mermaidGraph.edges.iterator.flatMap(e => Iterator(e.start, e.end)).toSet ++
      mermaidGraph.subgraphs.iterator.flatMap(_.nodes).toSet

  val subgraphIds: Set[String] = mermaidGraph.subgraphs.iterator.map(_.id).toSet

  // Convert vertices to nodes. mermaid.js also lists subgraph ids in its vertices
  // dictionary — those are groups, not nodes, and materializing them here would emit
  // a stray standalone node statement on the next serialization.
  val nodes: VectorMap[NodeId, ViewerNode] =
    mermaidGraph.vertices.iterator.foldLeft(VectorMap.empty[NodeId, ViewerNode]) { case (acc, (rawKey, vertex)) =>
      val vertexId = Option(vertex.id).filter(_.nonEmpty)
      val canonicalId =
        if referencedNodeIds.contains(rawKey) then rawKey
        else vertexId.filter(referencedNodeIds.contains).getOrElse(rawKey)
      val nodeId      = NodeId(canonicalId)
      val attrs       = vertexToAttributes(vertex.copy(id = canonicalId))
      if subgraphIds.contains(canonicalId) || acc.contains(nodeId) then acc
      else acc.updated(nodeId, ViewerNode.nodeNoDefaults(nodeId, attrs))
    }

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

  // `style <subgraphId> ...` directives land in the VERTICES dictionary (mermaid.js
  // gives subgraphs a vertex entry); harvest them for the group instead of dropping
  // them along with the phantom vertex.
  val subgraphVertexStyles: Map[String, List[String]] =
    mermaidGraph.vertices.iterator.flatMap { case (rawKey, v) =>
      val sid =
        if subgraphIds.contains(rawKey) then Some(rawKey)
        else if subgraphIds.contains(v.id) then Some(v.id)
        else None
      sid.filter(_ => v.styles.nonEmpty).map(_ -> v.styles)
    }.toMap

  // Convert subgraphs to groups
  val groups: Map[GroupId, ViewerGroup] =
    mermaidGraph.subgraphs.map { subgraph =>
      val groupId = GroupId(subgraph.id)
      val attrs   = subgraphToAttributes(subgraph, subgraphVertexStyles.getOrElse(subgraph.id, Nil))
      groupId -> ViewerGroup.group(groupId, attrs)
    }.toMap

  // Build memberships from subgraphs. Mermaid lists a NESTED subgraph's id in the
  // parent's member list, so an id naming another subgraph becomes a group->group
  // membership; treating it as a node would both lose the nesting and fabricate a
  // phantom node membership.
  val memberships: VectorMap[GroupMemberId, GroupId] =
    VectorMap.from(
      mermaidGraph.subgraphs.flatMap { subgraph =>
        val groupId = GroupId(subgraph.id)
        subgraph.nodes.map { memberId =>
          val member: GroupMemberId =
            if subgraphIds.contains(memberId) then GroupId(memberId) else NodeId(memberId)
          member -> groupId
        }
      }
    )

  // Ensure all edge endpoints exist in nodes
  val missingNodes = arrows.values.flatMap(_.endpoints).filterNot(nodes.contains)
  val allNodes = nodes ++ missingNodes.map(nid => nid -> ViewerNode.nodeNoDefaults(nid, Attributes.empty))

  // Build graph attributes from direction, title, and classDefs
  val graphAttrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()
  mermaidGraph.direction.foreach(d => graphAttrs += Rankdir.attrId -> AttrValue(d))
  mermaidGraph.title.foreach(t => graphAttrs += Label.attrId -> AttrValue(t))
  mermaidGraph.classDefs.foreach { case (name, classDef) =>
    if classDef.styles.nonEmpty then
      graphAttrs += AttributeId(MermaidClassDefPrefix + name) -> AttrValue(classDef.styles.mkString(","))
    if classDef.textStyles.nonEmpty then
      graphAttrs += AttributeId(MermaidClassDefTextPrefix + name) -> AttrValue(classDef.textStyles.mkString(","))
  }
  if mermaidGraph.defaultEdgeStyle.nonEmpty then
    graphAttrs += MermaidDefaultLinkStyleAttr -> AttrValue(mermaidGraph.defaultEdgeStyle.mkString(","))
  mermaidGraph.defaultEdgeInterpolate.foreach(v =>
    graphAttrs += MermaidDefaultLinkInterpolateAttr -> AttrValue(v)
  )

  ViewerGraphElements(
    nodes = allNodes,
    arrows = arrows,
    memberships = memberships,
    groups = groups,
    graphAttributes = Attributes.fromOrdered(graphAttrs)
  )

/** Converts a Mermaid state diagram to a ViewerGraph.
  *
  * A state is a node, a transition is an arrow, and a composite state is a
  * group. A start or end state (`[*]`) is a node too, with its kind in
  * `MermaidStateTypeAttr`. The graph keeps the kind of diagram in
  * `MermaidDiagramAttr`, so that the printer writes a state diagram back.
  */
def stateDiagramToViewerGraph(diagram: MermaidStateGraph): ViewerGraph =
  ViewerGraph(
    elements = stateDiagramToViewerGraphElements(diagram),
    id = "mermaid",
    tpe = GraphType.digraph
  )

def stateDiagramToViewerGraphElements(diagram: MermaidStateGraph): ViewerGraphElements =
  val compositeIds = diagram.composites.iterator.map(_.id).toSet

  // A composite state is a group, not a node. The flowchart conversion does the
  // same with a subgraph.
  val nodes: VectorMap[NodeId, ViewerNode] =
    diagram.states.iterator.filterNot(s => compositeIds.contains(s.id)).foldLeft(VectorMap.empty[NodeId, ViewerNode]) { (acc, state) =>
      val nodeId = NodeId(state.id)
      if acc.contains(nodeId) then acc
      else acc.updated(nodeId, ViewerNode.nodeNoDefaults(nodeId, stateToAttributes(state)))
    }

  val numbered = diagram.numberedTransitions
  val arrows: Map[ArrowId, Arrow] =
    numbered.map { (transition, seq) =>
      val arrow = Arrow(
        source = NodeId(transition.start),
        target = NodeId(transition.end),
        seq = seq,
        attributes = transitionToAttributes(transition)
      )
      arrow.id -> arrow
    }.toMap

  val groups: Map[GroupId, ViewerGroup] =
    diagram.composites.map { composite =>
      val groupId = GroupId(composite.id)
      groupId -> ViewerGroup.group(groupId, compositeToAttributes(composite))
    }.toMap

  val memberships: VectorMap[GroupMemberId, GroupId] =
    VectorMap.from(
      diagram.states.iterator.collect {
        case s if !compositeIds.contains(s.id) && s.parent.exists(compositeIds.contains) =>
          (NodeId(s.id): GroupMemberId) -> GroupId(s.parent.get)
      } ++
        diagram.composites.iterator.collect {
          case c if c.parent.exists(compositeIds.contains) => (GroupId(c.id): GroupMemberId) -> GroupId(c.parent.get)
        }
    )

  // A transition to a composite state ends at a node with the id of the group,
  // as an edge to a subgraph does in a flowchart.
  val missingNodes =
    numbered.iterator.flatMap((t, _) => Iterator(NodeId(t.start), NodeId(t.end))).filterNot(nodes.contains).distinct
  val allNodes = nodes ++ missingNodes.map(nid => nid -> ViewerNode.nodeNoDefaults(nid, Attributes.empty))

  val graphAttrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()
  graphAttrs += MermaidDiagramAttr -> AttrValue(StateDiagramHeader)
  diagram.direction.foreach(d => graphAttrs += Rankdir.attrId -> AttrValue(d))
  diagram.title.foreach(t => graphAttrs += Label.attrId -> AttrValue(t))
  diagram.classDefs.foreach { (name, styles) =>
    if styles.nonEmpty then graphAttrs += AttributeId(MermaidClassDefPrefix + name) -> AttrValue(styles.mkString(","))
  }

  ViewerGraphElements(
    nodes = allNodes,
    arrows = arrows,
    memberships = memberships,
    groups = groups,
    graphAttributes = Attributes.fromOrdered(graphAttrs)
  )

/** Converts a state of a state diagram to Attributes. */
private def stateToAttributes(state: MermaidState): Attributes =
  val attrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()
  state.label.foreach(l => attrs += Label.attrId -> AttrValue(MermaidLabelText.toStored(l)))
  state.kind.keyword.foreach(k => attrs += MermaidStateTypeAttr -> AttrValue(k))
  // A description cannot contain a line break, so one line holds one description.
  if state.descriptions.nonEmpty then attrs += MermaidStateDescriptionsAttr -> AttrValue(state.descriptions.mkString("\n"))
  if state.styles.nonEmpty then attrs += Style.attrId -> AttrValue(state.styles.mkString(","))
  if state.classes.nonEmpty then attrs += MermaidClassAttr -> AttrValue(state.classes.mkString(" "))
  Attributes.fromOrdered(attrs)

/** Converts a transition to Attributes. The DOM id (`edge<N>`) keeps the order
  * of the source, so that the printer writes the transitions in the same order.
  */
private def transitionToAttributes(transition: MermaidTransition): Attributes =
  val attrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()
  transition.label.foreach(l => attrs += Label.attrId -> AttrValue(MermaidLabelText.toStored(l)))
  transition.domId.foreach(d => attrs += MermaidDomIdAttr -> AttrValue(d))
  Attributes.fromOrdered(attrs)

/** Converts a composite state to Attributes. */
private def compositeToAttributes(composite: MermaidCompositeState): Attributes =
  val attrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()
  composite.label.foreach(l => attrs += Label.attrId -> AttrValue(MermaidLabelText.toStored(l)))
  if composite.styles.nonEmpty then attrs += Style.attrId -> AttrValue(composite.styles.mkString(","))
  if composite.classes.nonEmpty then attrs += MermaidClassAttr -> AttrValue(composite.classes.mkString(" "))
  Attributes.fromOrdered(attrs)

/** Converts a MermaidVertex to Attributes. */
private def vertexToAttributes(vertex: MermaidVertex): Attributes =
  val attrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()

  // Label (use text if different from id), converted to the stored (DOT-escaped) form
  if vertex.text != vertex.id then attrs += Label.attrId -> AttrValue(MermaidLabelText.toStored(vertex.text))

  // gx-shape-* marker classes carry DOT shapes that have no Mermaid bracket form
  // (record, house, ...); they win over the collapsed-to-rectangle bracket shape.
  val (shapeMarkers, cssClasses) = vertex.classes.partition(_.startsWith(ShapeClassPrefix))
  shapeMarkers.headOption.map(_.stripPrefix(ShapeClassPrefix)) match
    case Some(dotShape) =>
      attrs += Shape.attrId -> AttrValue(dotShape)
    case None =>
      vertex.shape.foreach { shape =>
        attrs += Shape.attrId -> AttrValue(mermaidShapeToDot(shape))
        // Mermaid's round brackets are DOT's box with rounded corners
        if shape == "round" || shape == "rounded" then
          attrs += CornerStyle.attrId -> AttrValue("rounded")
      }

  // Store Mermaid-specific data as custom attributes
  vertex.domId.foreach(v => attrs += MermaidDomIdAttr -> AttrValue(v))

  // Combine styles into a single style attribute if present
  if vertex.styles.nonEmpty then attrs += Style.attrId -> AttrValue(vertex.styles.mkString(","))

  // Store classes as a custom attribute
  if cssClasses.nonEmpty then attrs += MermaidClassAttr -> AttrValue(cssClasses.mkString(" "))

  Attributes.fromOrdered(attrs)

/** Converts a MermaidEdge to Attributes. */
private def edgeToAttributes(edge: MermaidEdge): Attributes =
  val attrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()

  // Label, converted to the stored (DOT-escaped) form
  edge.text.filter(_.nonEmpty).foreach(v => attrs += Label.attrId -> AttrValue(MermaidLabelText.toStored(v)))

  // Map Mermaid stroke to DOT style
  edge.stroke.foreach {
    case "dotted" => attrs += Style.attrId -> AttrValue("dashed")
    case "thick"  => attrs += Style.attrId -> AttrValue("bold")
    case _        => // "normal" or unknown - no style needed
  }

  // Arrow markers: map mermaid link forms onto the DOT head/tail model the app renders
  // with (dir=both — see ViewerGraph.defaultEdgeTheme, so ArrowHead/ArrowTail alone
  // control the drawn markers). double_* = arrows at both ends; arrow_open = none.
  edge.edgeType match
    case Some(t) if t.startsWith("double_") => attrs += ArrowTail.attrId -> AttrValue("normal")
    case Some("arrow_open")                 => attrs += ArrowHead.attrId -> AttrValue("none")
    case _                                  => ()

  // Store edge type as custom attribute
  edge.edgeType.foreach(v => attrs += MermaidEdgeTypeAttr -> AttrValue(v))
  if edge.styles.nonEmpty then attrs += MermaidEdgeStyleAttr -> AttrValue(edge.styles.mkString(","))
  edge.interpolate.foreach(v => attrs += MermaidEdgeInterpolateAttr -> AttrValue(v))

  Attributes.fromOrdered(attrs)

/** Converts a MermaidSubgraph (plus any harvested style directives) to Attributes. */
private def subgraphToAttributes(subgraph: MermaidSubgraph, styles: List[String]): Attributes =
  val attrs = scala.collection.mutable.ListBuffer[(AttributeId, AttrValue)]()

  subgraph.title.foreach(v => attrs += Label.attrId -> AttrValue(MermaidLabelText.toStored(v)))
  if styles.nonEmpty then attrs += Style.attrId -> AttrValue(styles.mkString(","))
  if subgraph.classes.nonEmpty then attrs += MermaidClassAttr -> AttrValue(subgraph.classes.mkString(" "))

  Attributes.fromOrdered(attrs)

/** Maps Mermaid shape names to DOT shape names. */
private def mermaidShapeToDot(mermaidShape: String): String =
  mermaidShape.toLowerCase match
    case "rect" | "rectangle"      => "box"
    case "round" | "rounded"       => "box" // with rounded corners style
    // Mermaid stadium brackets `([ ])` are also what a DOT `ellipse` serializes to
    // (dotShapeToMermaid), so map stadium back to `ellipse` (a valid graphviz shape)
    // to keep ellipse/stadium stable across a round-trip instead of collapsing to box.
    case "stadium"                 => "ellipse"
    // `[[ ]]` is what a DOT `folder` serializes to (dotShapeToMermaid), so folder is
    // the canonical DOT shape for subroutine — keeping folder (the collapsed-group
    // figure) stable across a round-trip instead of collapsing to box.
    case "subroutine"              => "folder"
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
