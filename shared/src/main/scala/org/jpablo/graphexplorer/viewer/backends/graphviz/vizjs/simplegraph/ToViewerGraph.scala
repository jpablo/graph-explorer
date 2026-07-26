package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{
  Area,
  ArrowHead,
  ArrowSize,
  ArrowTail,
  BgColor,
  Class,
  Cluster,
  ClusterLabelLoc,
  Color,
  ColorScheme,
  Constraint,
  Dir,
  FillColor,
  FixedSize,
  FontColor,
  FontName,
  FontSize,
  ForceLabels,
  GraphType,
  GvId,
  Head,
  HeadPort,
  Height,
  Id,
  Image,
  ImagePath,
  ImagePos,
  Label,
  LabelDistance,
  LabelFloat,
  LabelFontColor,
  LabelFontName,
  LabelJust,
  Layer,
  Layout,
  Len,
  LHeight,
  Lp,
  LWidth,
  Margin,
  Name,
  NoJustify,
  Normalize,
  NodeSep,
  Orientation,
  Overlap,
  Pad,
  PenColor,
  PenWidth,
  Peripheries,
  Pos,
  Rank,
  Rankdir,
  RankSep,
  Ratio,
  Rects,
  Regular,
  RootGraphLabelLoc,
  SameHead,
  SameTail,
  Shape,
  ShowBoxes,
  Sides,
  Splines,
  Start,
  Style,
  Tail,
  TailClip,
  TailLp,
  TailPort,
  TailTarget,
  TailTooltip,
  TailURL,
  Target,
  Tooltip,
  URL,
  Vertices,
  Width
}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, VizViewerGraphElements}

import scala.collection.immutable.VectorMap
import scala.collection.mutable

def toViewerGraph(simpleGraph: SimpleGraph): ViewerGraph =
  // simpleGraph has no defaults
  // There are two variants of ViewerGraph:
  // 1. 1:1 mapping from SimpleGraph to ViewerGraph
  // The meaning of empty/missing attributes is the same as in SimpleGraph: (set defaults)
  // 2. After expanding and extracting defaults, we need to switch
  // to the meaning of regular attributes in ViewerGraph: a missing attribute means "not set",
  // and it bubbles up to the graph level.
  ViewerGraph(
    elements = toViewerGraphElements(simpleGraph).expandAndExtractDefaultAttributes,
    id = simpleGraph.name,
    tpe = GraphType.fromBoolean(simpleGraph.directed)
  )

/** Converts a given SimpleGraph instance into an equivalent VizViewerGraphElements instance with a 1:1 mapping. This method serves as a
  * base for further processing and should not be used standalone.
  *
  * @param simpleGraph
  *   The input graph of type SimpleGraph to be converted into VizViewerGraphElements.
  * @return
  *   A VizViewerGraphElements instance derived from the input SimpleGraph, containing 1:1 mappings.
  */
def toViewerGraphElements(simpleGraph: SimpleGraph): VizViewerGraphElements =
  import org.jpablo.graphexplorer.viewer.models.*

  // Helper function to sanitize labels by removing leading newlines
  // TODO: This is actually supported by that we just need to find a way to do it in this code base.
  // without getting exceptions
  def sanitizeSingleLabel(label: String): String =
    label.replaceAll("^(\\\\n)+", "")

  // One appender per converter: adds `attrId -> value` (when present) unless the
  // attribute is excluded. Collapses the per-attribute copy lines below to
  // `add(Pos.attrId, node.pos)`. Append ORDER is preserved and significant:
  // Attributes.fromOrdered keeps insertion order, which DOT serialization relies on.
  def attrAppender(
      attrs:   mutable.ListBuffer[(AttributeId, AttrValue)],
      exclude: Set[AttributeId]
  ): (AttributeId, Option[String]) => Unit =
    (attrId, valueOpt) =>
      valueOpt.foreach(v => if !exclude.contains(attrId) then attrs += attrId -> AttrValue(v))

  // Attributes outside the SimpleGraph schema (custom mermaid_* metadata, ...),
  // appended after the named fields in alphabetical order for determinism.
  def appendExtras(add: (AttributeId, Option[String]) => Unit, extras: Map[String, String]): Unit =
    extras.toVector.sortBy(_._1).foreach((k, v) => add(AttributeId(k), Some(v)))

  // Attribute converters for case classes
  def toAttributesFromNode(node: SimpleGraphNode, exclude: Set[AttributeId]): Attributes =
    val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()
    val add   = attrAppender(attrs, exclude)

    add(GvId.attrId, Some(node._gvid.toString))
    add(Name.attrId, Some(node.name))
    // Skip \N labels as they are the default (node name)
    add(Label.attrId, Some(node.label).filter(_ != "\\N").map(sanitizeSingleLabel))

    add(Pos.attrId, node.pos)
    add(Height.attrId, node.height)
    add(Width.attrId, node.width)
    add(Shape.attrId, node.shape)
    add(FontName.attrId, node.fontname)
    add(FontSize.attrId, node.fontsize)
    add(FontColor.attrId, node.fontcolor)
    add(Color.attrId, node.color)
    add(FillColor.attrId, node.fillcolor)
    add(Style.attrId, node.style)
    add(PenWidth.attrId, node.penwidth)
    add(Rects.attrId, node.rects)
    add(Sides.attrId, node.sides)
    add(Peripheries.attrId, node.peripheries)
    add(FixedSize.attrId, node.fixedsize)
    add(Regular.attrId, node.regular)
    add(Orientation.attrId, node.orientation)
    add(URL.attrId, node.URL)
    add(Area.attrId, node.area)
    add(Class.attrId, node.`class`)
    add(ColorScheme.attrId, node.colorscheme)
    add(Target.attrId, node.target)
    add(Tooltip.attrId, node.tooltip)
    add(Vertices.attrId, node.vertices)
    add(Image.attrId, node.image)
    add(ImagePath.attrId, node.imagepath)
    add(ImagePos.attrId, node.imagepos)
    add(Margin.attrId, node.margin)
    add(NoJustify.attrId, node.nojustify)
    appendExtras(add, node.extraAttrs)

    Attributes.fromOrdered(attrs)

  def toAttributesFromCluster(cluster: SimpleGraphCluster, exclude: Set[AttributeId]): Attributes =
    val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()
    val add   = attrAppender(attrs, exclude)

    add(GvId.attrId, Some(cluster._gvid.toString))
    add(Name.attrId, Some(cluster.name))
    // Skip \N labels as they are the default
    add(Label.attrId, cluster.label.filter(_ != "\\N").map(sanitizeSingleLabel))

    add(FontName.attrId, cluster.fontname)
    add(FontSize.attrId, cluster.fontsize)
    add(FontColor.attrId, cluster.fontcolor)
    add(Color.attrId, cluster.color)
    add(PenColor.attrId, cluster.pencolor)
    add(PenWidth.attrId, cluster.penwidth)
    add(BgColor.attrId, cluster.bgcolor)
    add(FillColor.attrId, cluster.fillcolor)
    add(Style.attrId, cluster.style)
    add(LabelJust.attrId, cluster.labeljust)
    add(ClusterLabelLoc.attrId, cluster.labelloc)
    add(LHeight.attrId, cluster.lheight)
    add(Lp.attrId, cluster.lp)
    add(LWidth.attrId, cluster.lwidth)
    // layout and rankdir are graph-only attributes: never emitted on clusters
    add(Normalize.attrId, cluster.normalize)
    add(Start.attrId, cluster.start)
    add(Overlap.attrId, cluster.overlap)
    add(Cluster.attrId, cluster.cluster)
    add(Splines.attrId, cluster.splines)
    add(Target.attrId, cluster.target)
    add(Tooltip.attrId, cluster.tooltip)
    add(URL.attrId, cluster.URL)
    add(Class.attrId, cluster.`class`)
    add(ColorScheme.attrId, cluster.colorscheme)
    // Add rank attribute for clusters/subgraphs
    add(Rank.attrId, cluster.rank)
    appendExtras(add, cluster.extraAttrs)

    Attributes.fromOrdered(attrs)

  def toAttributesFromGraph(simpleGraph: SimpleGraph): Attributes =
    val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()
    val add   = attrAppender(attrs, Set.empty)

    // name/directed/strict/bb/_subgraph_cnt are intentionally not emitted (handled specially)
    add(FontName.attrId, simpleGraph.fontname)
    add(FontSize.attrId, simpleGraph.fontsize)
    add(Label.attrId, simpleGraph.label.map(sanitizeSingleLabel))
    add(RootGraphLabelLoc.attrId, simpleGraph.labelloc)
    add(Lp.attrId, simpleGraph.lp)
    add(LHeight.attrId, simpleGraph.lheight)
    add(LWidth.attrId, simpleGraph.lwidth)
    add(Rankdir.attrId, simpleGraph.rankdir)
    add(Layout.attrId, simpleGraph.layout)
    add(BgColor.attrId, simpleGraph.bgcolor)
    add(NodeSep.attrId, simpleGraph.nodesep)
    add(Pad.attrId, simpleGraph.pad)
    add(RankSep.attrId, simpleGraph.ranksep)
    add(Ratio.attrId, simpleGraph.ratio)
    add(Splines.attrId, simpleGraph.splines)
    add(Overlap.attrId, simpleGraph.overlap)
    add(Normalize.attrId, simpleGraph.normalize)
    add(Start.attrId, simpleGraph.start)
    appendExtras(add, simpleGraph.extraAttrs)

    Attributes.fromOrdered(attrs)

  def toAttributesFromEdge(edge: SimpleGraphEdge, exclude: Set[AttributeId]): Attributes =
    val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()
    val add   = attrAppender(attrs, exclude)

    add(GvId.attrId, Some(edge._gvid.toString))
    add(Tail.attrId, Some(edge.tail.toString))
    add(Head.attrId, Some(edge.head.toString))

    add(Pos.attrId, edge.pos)
    add(Id.attrId, edge.id)
    // Skip \N labels as they are the default
    add(Label.attrId, edge.label.filter(_ != "\\N").map(sanitizeSingleLabel))
    add(FontName.attrId, edge.fontname)
    add(FontSize.attrId, edge.fontsize)
    add(Color.attrId, edge.color)
    add(PenWidth.attrId, edge.penwidth)
    add(Style.attrId, edge.style)
    add(Lp.attrId, edge.lp)
    add(Len.attrId, edge.len)
    add(Constraint.attrId, edge.constraint)
    add(ForceLabels.attrId, edge.forcelabels)
    add(HeadPort.attrId, edge.headport)
    add(TailPort.attrId, edge.tailport)
    add(ArrowHead.attrId, edge.arrowhead)
    add(ArrowTail.attrId, edge.arrowtail)
    add(ArrowSize.attrId, edge.arrowsize)
    add(Dir.attrId, edge.dir)
    add(Class.attrId, edge.`class`)
    add(ColorScheme.attrId, edge.colorscheme)
    add(Layer.attrId, edge.layer)
    add(NoJustify.attrId, edge.nojustify)
    add(SameHead.attrId, edge.samehead)
    add(SameTail.attrId, edge.sametail)
    add(ShowBoxes.attrId, edge.showboxes)
    add(TailLp.attrId, edge.tail_lp)
    add(TailClip.attrId, edge.tailclip)
    add(Target.attrId, edge.target)
    add(Tooltip.attrId, edge.tooltip)
    add(LabelDistance.attrId, edge.labeldistance)
    add(LabelFloat.attrId, edge.labelfloat)
    add(LabelFontColor.attrId, edge.labelfontcolor)
    add(LabelFontName.attrId, edge.labelfontname)
    add(TailTarget.attrId, edge.tailtarget)
    add(TailTooltip.attrId, edge.tailtooltip)
    add(TailURL.attrId, edge.tailURL)
    appendExtras(add, edge.extraAttrs)

    Attributes.fromOrdered(attrs)

  // gvid -> node name, for resolving edge endpoints
  val gvidToNodeId = mutable.Map[Int, String]()

  // Separate nodes and clusters from objects array
  val rawNodesBuilder = VectorMap.newBuilder[NodeId, Attributes]
  val rawClusters     = mutable.Map[GroupId, (Attributes, List[Int], List[Int], Boolean)]()

  simpleGraph.objects.foreach { objectsList =>
    objectsList.foreach {
      case SimpleGraphObject.Node(node) =>
        val nodeId = NodeId(node.name)
        gvidToNodeId(node._gvid) = node.name
        // `name` becomes the NodeId; keeping it as an attribute too would duplicate it
        val attrs = toAttributesFromNode(node, Set(Name.attrId))
        rawNodesBuilder += nodeId -> attrs
      case SimpleGraphObject.Cluster(cluster) =>
        val (groupId, wasCluster) = GroupId.fromDot(cluster.name)
        // `name` becomes the GroupId (the cluster's member lists are read separately,
        // from the SimpleGraph fields — they never reach the attribute stream)
        val attrs                 = toAttributesFromCluster(cluster, Set(Name.attrId))
        val nodeGvids             = cluster.nodes.getOrElse(List.empty)
        val edgeGvids             = cluster.edges.getOrElse(List.empty)
        rawClusters(groupId) = (attrs, nodeGvids, edgeGvids, wasCluster)
    }
  }
  val rawNodes = rawNodesBuilder.result()

  // Build group memberships from cluster data
  val membershipsBuilder = mutable.Map[GroupMemberId, GroupId]()
  val groupsBuilder      = mutable.Map[GroupId, ViewerGroup]()

  // Build groups first
  rawClusters.foreach { case (groupId, (attrs, nodeGvids, edgeGvids, wasCluster)) =>
    // Ensure cluster attribute is set appropriately:
    // - If already present, preserve it
    // - If missing and was originally a cluster, set to "true"
    // - If missing and was not originally a cluster, set to "false"
    val updatedAttrs = if attrs.values.contains(Cluster.attrId) then {
      attrs
    } else {
      val defaultValue  = if wasCluster then "true" else "false"
      val updatedValues = attrs.values + (Cluster.attrId -> AttrValue(defaultValue))
      Attributes(updatedValues)
    }
    groupsBuilder(groupId) = ViewerGroup.group(groupId, updatedAttrs)
  }

  // Cluster indexes, built once: by GroupId (the FIRST cluster in `objects` wins,
  // mirroring the collectFirst these loops previously ran per item) and by _gvid.
  val objectClusters: List[SimpleGraphCluster] =
    simpleGraph.objects.getOrElse(Nil).collect { case SimpleGraphObject.Cluster(c) => c }
  val clusterFirstByGroupId: Map[GroupId, SimpleGraphCluster] =
    objectClusters.distinctBy(c => GroupId.fromDot(c.name)._1).map(c => GroupId.fromDot(c.name)._1 -> c).toMap
  val clusterByGvid: Map[Int, SimpleGraphCluster] =
    objectClusters.distinctBy(_._gvid).map(c => c._gvid -> c).toMap

  // For each item (node or edge) gvid, find the most specific (innermost) cluster that
  // lists it — dot_json lists an item in EVERY enclosing subgraph's array, so a cluster
  // only owns the items its direct sub-clusters don't claim. Shared by the node and
  // edge ownership passes below.
  def innermostOwners(
      ownGvids:        ((Attributes, List[Int], List[Int], Boolean)) => List[Int],
      subClusterGvids: SimpleGraphCluster => List[Int]
  ): mutable.Map[Int, GroupId] =
    val owners = mutable.Map[Int, GroupId]()
    rawClusters.foreach { case (groupId, clusterData) =>
      // This cluster's direct sub-clusters' item sets, resolved once per cluster
      val subItemSets: List[Set[Int]] =
        clusterFirstByGroupId.get(groupId).toList
          .flatMap(_.subgraphs.getOrElse(Nil))
          .flatMap(clusterByGvid.get)
          .map(sub => subClusterGvids(sub).toSet)
      ownGvids(clusterData).foreach { gvid =>
        if !subItemSets.exists(_.contains(gvid)) then owners(gvid) = groupId
      }
    }
    owners

  // For each node, find the most specific (innermost) cluster it belongs to
  val nodeToCluster = innermostOwners(_._2, _.nodes.getOrElse(Nil))

  // Build final memberships
  nodeToCluster.foreach { case (gvid, groupId) =>
    gvidToNodeId.get(gvid).foreach { nodeName =>
      membershipsBuilder(NodeId(nodeName)) = groupId
    }
  }

  // Handle group-to-group memberships (nested subgraphs)
  objectClusters.foreach { cluster =>
    val parentGroupId = GroupId.fromDot(cluster.name)._1
    cluster.subgraphs.foreach { subgraphGvids =>
      subgraphGvids.foreach { subgraphGvid =>
        // Find the subgraph cluster by its _gvid
        clusterByGvid.get(subgraphGvid).foreach { subCluster =>
          membershipsBuilder(GroupId.fromDot(subCluster.name)._1) = parentGroupId
        }
      }
    }
  }

  // For each EDGE, find the most specific (innermost) declaring subgraph
  val edgeToCluster = innermostOwners(_._3, _.edges.getOrElse(Nil))

  // Convert edges to arrows, keeping each edge's _gvid alongside the Arrow it produced
  // so ownership can be joined by gvid below without re-deriving ArrowIds.
  val edgeArrowPairs: List[(Int, Arrow)] =
    simpleGraph.edges.getOrElse(Nil).map { edge =>
      val sourceName = gvidToNodeId.getOrElse(edge.tail, edge.tail.toString)
      val targetName = gvidToNodeId.getOrElse(edge.head, edge.head.toString)
      // endpoints and ports are promoted to Arrow fields below, not kept as attributes
      val attrs = toAttributesFromEdge(edge, Set(Tail.attrId, Head.attrId, TailPort.attrId, HeadPort.attrId))

      edge._gvid -> Arrow(
        source = NodeId(sourceName),
        target = NodeId(targetName),
        seq = edge._gvid,
        attributes = attrs,
        sourcePort = edge.tailport,
        targetPort = edge.headport
      )
    }

  val arrows: Map[ArrowId, Arrow] = edgeArrowPairs.map((_, a) => a.id -> a).toMap

  // Arrow ownership: join each built Arrow with its innermost declaring subgraph by
  // the edge's _gvid — no throwaway Arrow reconstruction needed for the ids to match.
  val arrowMembershipsMap: Map[ArrowId, GroupId] =
    edgeArrowPairs.flatMap((gvid, a) => edgeToCluster.get(gvid).map(a.id -> _)).toMap

  // Ensure all arrow endpoints are in nodes, preserving the original order from rawNodes
  val arrowEndpoints    = arrows.values.flatMap(_.endpoints).filterNot(rawNodes.contains)
  val finalNodesBuilder = VectorMap.newBuilder[NodeId, ViewerNode]

  // First add nodes in their original order from rawNodes
  rawNodes.foreach { case (nodeId, attrs) =>
    finalNodesBuilder += nodeId -> ViewerNode.nodeNoDefaults(nodeId, attrs)
  }

  // Then add any missing nodes from arrow endpoints
  arrowEndpoints.foreach { nodeId =>
    finalNodesBuilder += nodeId -> ViewerNode.nodeNoDefaults(nodeId, Attributes.empty)
  }

  val finalNodes = finalNodesBuilder.result()

  // Extract graph attributes
  // No exclusions needed: name/directed/objects/edges are SimpleGraph structure, and
  // toAttributesFromGraph never emits them in the first place.
  val graphAttrs = toAttributesFromGraph(simpleGraph)

  VizViewerGraphElements(
    nodes = finalNodes,
    arrows = arrows,
    memberships = VectorMap.from(membershipsBuilder),
    arrowMemberships = arrowMembershipsMap,
    groups = groupsBuilder.toMap,
    graphAttributes = graphAttrs
  )
