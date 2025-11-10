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

  // Attribute converters for case classes
  def toAttributesFromNode(node: SimpleGraphNode, exclude: Set[String] = Set.empty): Attributes =
    val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()

    if (!exclude.contains("_gvid")) attrs += AttributeId("_gvid") -> AttrValue(node._gvid.toString)
    if (!exclude.contains("name")) attrs += AttributeId("name")   -> AttrValue(node.name)
    // Skip \N labels as they are the default (node name)
    if (!exclude.contains(Label.attrId.value) && node.label != "\\N") attrs += Label.attrId -> AttrValue(sanitizeSingleLabel(node.label))

    node.pos.foreach(v => if (!exclude.contains(Pos.attrId.value)) attrs += Pos.attrId -> AttrValue(v))
    node.height.foreach(v => if (!exclude.contains(Height.attrId.value)) attrs += Height.attrId -> AttrValue(v))
    node.width.foreach(v => if (!exclude.contains(Width.attrId.value)) attrs += Width.attrId -> AttrValue(v))
    node.shape.foreach(v => if (!exclude.contains(Shape.attrId.value)) attrs += Shape.attrId -> AttrValue(v))
    node.fontname.foreach(v => if (!exclude.contains(FontName.attrId.value)) attrs += FontName.attrId -> AttrValue(v))
    node.fontsize.foreach(v => if (!exclude.contains(FontSize.attrId.value)) attrs += FontSize.attrId -> AttrValue(v))
    node.fontcolor.foreach(v => if (!exclude.contains(FontColor.attrId.value)) attrs += FontColor.attrId -> AttrValue(v))
    node.color.foreach(v => if (!exclude.contains(Color.attrId.value)) attrs += Color.attrId -> AttrValue(v))
    node.fillcolor.foreach(v => if (!exclude.contains(FillColor.attrId.value)) attrs += FillColor.attrId -> AttrValue(v))
    node.style.foreach(v => if (!exclude.contains(Style.attrId.value)) attrs += Style.attrId -> AttrValue(v))
    node.penwidth.foreach(v => if (!exclude.contains(PenWidth.attrId.value)) attrs += PenWidth.attrId -> AttrValue(v))
    node.rects.foreach(v => if (!exclude.contains(Rects.attrId.value)) attrs += Rects.attrId -> AttrValue(v))
    node.sides.foreach(v => if (!exclude.contains(Sides.attrId.value)) attrs += Sides.attrId -> AttrValue(v))
    node.peripheries.foreach(v => if (!exclude.contains(Peripheries.attrId.value)) attrs += Peripheries.attrId -> AttrValue(v))
    node.fixedsize.foreach(v => if (!exclude.contains(FixedSize.attrId.value)) attrs += FixedSize.attrId -> AttrValue(v))
    node.regular.foreach(v => if (!exclude.contains(Regular.attrId.value)) attrs += Regular.attrId -> AttrValue(v))
    node.orientation.foreach(v => if (!exclude.contains(Orientation.attrId.value)) attrs += Orientation.attrId -> AttrValue(v))
    node.URL.foreach(v => if (!exclude.contains(URL.attrId.value)) attrs += URL.attrId -> AttrValue(v))
    node.area.foreach(v => if (!exclude.contains(Area.attrId.value)) attrs += Area.attrId -> AttrValue(v))
    node.`class`.foreach(v => if (!exclude.contains(Class.attrId.value)) attrs += Class.attrId -> AttrValue(v))
    node.colorscheme.foreach(v => if (!exclude.contains(ColorScheme.attrId.value)) attrs += ColorScheme.attrId -> AttrValue(v))
    node.target.foreach(v => if (!exclude.contains(Target.attrId.value)) attrs += Target.attrId -> AttrValue(v))
    node.tooltip.foreach(v => if (!exclude.contains(Tooltip.attrId.value)) attrs += Tooltip.attrId -> AttrValue(v))
    node.vertices.foreach(v => if (!exclude.contains(Vertices.attrId.value)) attrs += Vertices.attrId -> AttrValue(v))
    node.image.foreach(v => if (!exclude.contains(Image.attrId.value)) attrs += Image.attrId -> AttrValue(v))
    node.imagepath.foreach(v => if (!exclude.contains(ImagePath.attrId.value)) attrs += ImagePath.attrId -> AttrValue(v))
    node.imagepos.foreach(v => if (!exclude.contains(ImagePos.attrId.value)) attrs += ImagePos.attrId -> AttrValue(v))
    node.margin.foreach(v => if (!exclude.contains("margin")) attrs += AttributeId("margin") -> AttrValue(v))
    node.nojustify.foreach(v => if (!exclude.contains("nojustify")) attrs += AttributeId("nojustify") -> AttrValue(v))

    Attributes(VectorMap.from(attrs.toSeq))

  def toAttributesFromCluster(cluster: SimpleGraphCluster, exclude: Set[String] = Set.empty): Attributes =
    val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()

    // Define attributes that are only valid at the graph level, not in subgraphs
    val graphOnlyAttributes = Set(
      "rankdir",
      "layout",
      "nodesep",
      "ranksep",
      "pad",
      "ratio",
      "dpi",
      "fontpath",
      "landscape",
      "size",
      "rotate",
      "center",
      "pagedir",
      "viewport",
      "outputorder",
      "compound",
      "remincross",
      "searchsize",
      "showboxes",
      "imagepath",
      "concentrate"
    )

    if (!exclude.contains("_gvid")) attrs += AttributeId("_gvid") -> AttrValue(cluster._gvid.toString)
    if (!exclude.contains("name")) attrs += AttributeId("name")   -> AttrValue(cluster.name)
    // Skip \N labels as they are the default
    cluster.label.foreach(v =>
      if (!exclude.contains(Label.attrId.value) && v != "\\N") attrs += Label.attrId -> AttrValue(sanitizeSingleLabel(v))
    )
//      if (!exclude.contains("bb")) attrs += AttributeId("bb") -> AttrValue(cluster.bb)

    cluster.fontname.foreach(v => if (!exclude.contains(FontName.attrId.value)) attrs += FontName.attrId -> AttrValue(v))
    cluster.fontsize.foreach(v => if (!exclude.contains(FontSize.attrId.value)) attrs += FontSize.attrId -> AttrValue(v))
    cluster.fontcolor.foreach(v => if (!exclude.contains(FontColor.attrId.value)) attrs += FontColor.attrId -> AttrValue(v))
    cluster.color.foreach(v => if (!exclude.contains(Color.attrId.value)) attrs += Color.attrId -> AttrValue(v))
    cluster.pencolor.foreach(v => if (!exclude.contains(PenColor.attrId.value)) attrs += PenColor.attrId -> AttrValue(v))
    cluster.penwidth.foreach(v => if (!exclude.contains(PenWidth.attrId.value)) attrs += PenWidth.attrId -> AttrValue(v))
    cluster.bgcolor.foreach(v => if (!exclude.contains(BgColor.attrId.value)) attrs += BgColor.attrId -> AttrValue(v))
    cluster.fillcolor.foreach(v => if (!exclude.contains(FillColor.attrId.value)) attrs += FillColor.attrId -> AttrValue(v))
    cluster.style.foreach(v => if (!exclude.contains(Style.attrId.value)) attrs += Style.attrId -> AttrValue(v))
    cluster.labeljust.foreach(v => if (!exclude.contains(LabelJust.attrId.value)) attrs += LabelJust.attrId -> AttrValue(v))
    cluster.labelloc.foreach(v => if (!exclude.contains(ClusterLabelLoc.attrId.value)) attrs += ClusterLabelLoc.attrId -> AttrValue(v))
    cluster.lheight.foreach(v => if (!exclude.contains(LHeight.attrId.value)) attrs += LHeight.attrId -> AttrValue(v))
    cluster.lp.foreach(v => if (!exclude.contains(Lp.attrId.value)) attrs += Lp.attrId -> AttrValue(v))
    cluster.lwidth.foreach(v => if (!exclude.contains(LWidth.attrId.value)) attrs += LWidth.attrId -> AttrValue(v))
    // Skip layout - it's a graph-only attribute
    cluster.layout.foreach(v =>
      if (!exclude.contains(Layout.attrId.value) && !graphOnlyAttributes.contains(Layout.attrId.value)) attrs += Layout.attrId -> AttrValue(v)
    )
    cluster.normalize.foreach(v => if (!exclude.contains(Normalize.attrId.value)) attrs += Normalize.attrId -> AttrValue(v))
    cluster.start.foreach(v => if (!exclude.contains(Start.attrId.value)) attrs += Start.attrId -> AttrValue(v))
    cluster.overlap.foreach(v => if (!exclude.contains(Overlap.attrId.value)) attrs += Overlap.attrId -> AttrValue(v))
    cluster.cluster.foreach(v => if (!exclude.contains(Cluster.attrId.value)) attrs += Cluster.attrId -> AttrValue(v))
    // Skip rankdir - it's a graph-only attribute
    cluster.rankdir.foreach(v =>
      if (!exclude.contains(Rankdir.attrId.value) && !graphOnlyAttributes.contains(Rankdir.attrId.value)) attrs += Rankdir.attrId -> AttrValue(v)
    )
    cluster.splines.foreach(v => if (!exclude.contains(Splines.attrId.value)) attrs += Splines.attrId -> AttrValue(v))
    cluster.target.foreach(v => if (!exclude.contains(Target.attrId.value)) attrs += Target.attrId -> AttrValue(v))
    cluster.tooltip.foreach(v => if (!exclude.contains(Tooltip.attrId.value)) attrs += Tooltip.attrId -> AttrValue(v))
    cluster.URL.foreach(v => if (!exclude.contains(URL.attrId.value)) attrs += URL.attrId -> AttrValue(v))
    cluster.`class`.foreach(v => if (!exclude.contains(Class.attrId.value)) attrs += Class.attrId -> AttrValue(v))
    cluster.colorscheme.foreach(v => if (!exclude.contains(ColorScheme.attrId.value)) attrs += ColorScheme.attrId -> AttrValue(v))
    // Add rank attribute for clusters/subgraphs
    cluster.rank.foreach(v => if (!exclude.contains(Rank.attrId.value)) attrs += Rank.attrId -> AttrValue(v))

    Attributes(VectorMap.from(attrs.toSeq))

  def toAttributesFromGraph(simpleGraph: SimpleGraph, exclude: Set[String] = Set.empty): Attributes =
    val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()

//    if (!exclude.contains("name")) attrs += AttributeId("name")         -> AttrValue(simpleGraph.name)
//    if (!exclude.contains("directed")) attrs += AttributeId("directed") -> AttrValue(simpleGraph.directed.toString)
//      if (!exclude.contains("strict")) attrs += AttributeId("strict") -> AttrValue(graph.strict.toString)
//      if (!exclude.contains("bb")) attrs += AttributeId("bb") -> AttrValue(graph.bb)
//      if (!exclude.contains("_subgraph_cnt")) attrs += AttributeId("_subgraph_cnt") -> AttrValue(graph._subgraph_cnt.toString)

    simpleGraph.fontname.foreach(v => if (!exclude.contains(FontName.attrId.value)) attrs += FontName.attrId -> AttrValue(v))
    simpleGraph.fontsize.foreach(v => if (!exclude.contains(FontSize.attrId.value)) attrs += FontSize.attrId -> AttrValue(v))
    simpleGraph.label.foreach(v => if (!exclude.contains(Label.attrId.value)) attrs += Label.attrId -> AttrValue(sanitizeSingleLabel(v)))
    simpleGraph.labelloc.foreach(v => if (!exclude.contains(RootGraphLabelLoc.attrId.value)) attrs += RootGraphLabelLoc.attrId -> AttrValue(v))
    simpleGraph.lp.foreach(v => if (!exclude.contains(Lp.attrId.value)) attrs += Lp.attrId -> AttrValue(v))
    simpleGraph.lheight.foreach(v => if (!exclude.contains(LHeight.attrId.value)) attrs += LHeight.attrId -> AttrValue(v))
    simpleGraph.lwidth.foreach(v => if (!exclude.contains(LWidth.attrId.value)) attrs += LWidth.attrId -> AttrValue(v))
    simpleGraph.rankdir.foreach(v => if (!exclude.contains(Rankdir.attrId.value)) attrs += Rankdir.attrId -> AttrValue(v))
    simpleGraph.layout.foreach(v => if (!exclude.contains(Layout.attrId.value)) attrs += Layout.attrId -> AttrValue(v))
    simpleGraph.bgcolor.foreach(v => if (!exclude.contains(BgColor.attrId.value)) attrs += BgColor.attrId -> AttrValue(v))
    simpleGraph.nodesep.foreach(v => if (!exclude.contains(NodeSep.attrId.value)) attrs += NodeSep.attrId -> AttrValue(v))
    simpleGraph.pad.foreach(v => if (!exclude.contains(Pad.attrId.value)) attrs += Pad.attrId -> AttrValue(v))
    simpleGraph.ranksep.foreach(v => if (!exclude.contains(RankSep.attrId.value)) attrs += RankSep.attrId -> AttrValue(v))
    simpleGraph.ratio.foreach(v => if (!exclude.contains(Ratio.attrId.value)) attrs += Ratio.attrId -> AttrValue(v))
    simpleGraph.splines.foreach(v => if (!exclude.contains(Splines.attrId.value)) attrs += Splines.attrId -> AttrValue(v))
    simpleGraph.overlap.foreach(v => if (!exclude.contains(Overlap.attrId.value)) attrs += Overlap.attrId -> AttrValue(v))
    simpleGraph.normalize.foreach(v => if (!exclude.contains(Normalize.attrId.value)) attrs += Normalize.attrId -> AttrValue(v))
    simpleGraph.start.foreach(v => if (!exclude.contains(Start.attrId.value)) attrs += Start.attrId -> AttrValue(v))

    Attributes(VectorMap.from(attrs.toSeq))

  def toAttributesFromEdge(edge: SimpleGraphEdge, exclude: Set[String] = Set.empty): Attributes =
    val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()

    if (!exclude.contains("_gvid")) attrs += AttributeId("_gvid") -> AttrValue(edge._gvid.toString)
    if (!exclude.contains("tail")) attrs += AttributeId("tail")   -> AttrValue(edge.tail.toString)
    if (!exclude.contains("head")) attrs += AttributeId("head")   -> AttrValue(edge.head.toString)

    edge.pos.foreach(v => if (!exclude.contains(Pos.attrId.value)) attrs += Pos.attrId -> AttrValue(v))
    edge.id.foreach(v => if (!exclude.contains(Id.attrId.value)) attrs += Id.attrId -> AttrValue(v))
    // Skip \N labels as they are the default
    edge.label.foreach(v =>
      if (!exclude.contains(Label.attrId.value) && v != "\\N") attrs += Label.attrId -> AttrValue(sanitizeSingleLabel(v))
    )
    edge.fontname.foreach(v => if (!exclude.contains(FontName.attrId.value)) attrs += FontName.attrId -> AttrValue(v))
    edge.fontsize.foreach(v => if (!exclude.contains(FontSize.attrId.value)) attrs += FontSize.attrId -> AttrValue(v))
    edge.color.foreach(v => if (!exclude.contains(Color.attrId.value)) attrs += Color.attrId -> AttrValue(v))
    edge.penwidth.foreach(v => if (!exclude.contains(PenWidth.attrId.value)) attrs += PenWidth.attrId -> AttrValue(v))
    edge.style.foreach(v => if (!exclude.contains(Style.attrId.value)) attrs += Style.attrId -> AttrValue(v))
    edge.lp.foreach(v => if (!exclude.contains(Lp.attrId.value)) attrs += Lp.attrId -> AttrValue(v))
    edge.len.foreach(v => if (!exclude.contains(Len.attrId.value)) attrs += Len.attrId -> AttrValue(v))
    edge.constraint.foreach(v => if (!exclude.contains(Constraint.attrId.value)) attrs += Constraint.attrId -> AttrValue(v))
    edge.forcelabels.foreach(v => if (!exclude.contains(ForceLabels.attrId.value)) attrs += ForceLabels.attrId -> AttrValue(v))
    edge.headport.foreach(v => if (!exclude.contains(HeadPort.attrId.value)) attrs += HeadPort.attrId -> AttrValue(v))
    edge.tailport.foreach(v => if (!exclude.contains(TailPort.attrId.value)) attrs += TailPort.attrId -> AttrValue(v))
    edge.arrowhead.foreach(v => if (!exclude.contains(ArrowHead.attrId.value)) attrs += ArrowHead.attrId -> AttrValue(v))
    edge.arrowtail.foreach(v => if (!exclude.contains(ArrowTail.attrId.value)) attrs += ArrowTail.attrId -> AttrValue(v))
    edge.arrowsize.foreach(v => if (!exclude.contains(ArrowSize.attrId.value)) attrs += ArrowSize.attrId -> AttrValue(v))
    edge.dir.foreach(v => if (!exclude.contains(Dir.attrId.value)) attrs += Dir.attrId -> AttrValue(v))
    edge.`class`.foreach(v => if (!exclude.contains(Class.attrId.value)) attrs += Class.attrId -> AttrValue(v))
    edge.colorscheme.foreach(v => if (!exclude.contains(ColorScheme.attrId.value)) attrs += ColorScheme.attrId -> AttrValue(v))
    edge.layer.foreach(v => if (!exclude.contains(Layer.attrId.value)) attrs += Layer.attrId -> AttrValue(v))
    edge.nojustify.foreach(v => if (!exclude.contains("nojustify")) attrs += AttributeId("nojustify") -> AttrValue(v))
    edge.samehead.foreach(v => if (!exclude.contains(SameHead.attrId.value)) attrs += SameHead.attrId -> AttrValue(v))
    edge.sametail.foreach(v => if (!exclude.contains(SameTail.attrId.value)) attrs += SameTail.attrId -> AttrValue(v))
    edge.showboxes.foreach(v => if (!exclude.contains(ShowBoxes.attrId.value)) attrs += ShowBoxes.attrId -> AttrValue(v))
    edge.tail_lp.foreach(v => if (!exclude.contains(TailLp.attrId.value)) attrs += TailLp.attrId -> AttrValue(v))
    edge.tailclip.foreach(v => if (!exclude.contains(TailClip.attrId.value)) attrs += TailClip.attrId -> AttrValue(v))
    edge.target.foreach(v => if (!exclude.contains(Target.attrId.value)) attrs += Target.attrId -> AttrValue(v))
    edge.tooltip.foreach(v => if (!exclude.contains(Tooltip.attrId.value)) attrs += Tooltip.attrId -> AttrValue(v))
    edge.labeldistance.foreach(v => if (!exclude.contains(LabelDistance.attrId.value)) attrs += LabelDistance.attrId -> AttrValue(v))
    edge.labelfloat.foreach(v => if (!exclude.contains(LabelFloat.attrId.value)) attrs += LabelFloat.attrId -> AttrValue(v))
    edge.labelfontcolor.foreach(v => if (!exclude.contains(LabelFontColor.attrId.value)) attrs += LabelFontColor.attrId -> AttrValue(v))
    edge.labelfontname.foreach(v => if (!exclude.contains(LabelFontName.attrId.value)) attrs += LabelFontName.attrId -> AttrValue(v))
    edge.tailtarget.foreach(v => if (!exclude.contains(TailTarget.attrId.value)) attrs += TailTarget.attrId -> AttrValue(v))
    edge.tailtooltip.foreach(v => if (!exclude.contains(TailTooltip.attrId.value)) attrs += TailTooltip.attrId -> AttrValue(v))
    edge.tailURL.foreach(v => if (!exclude.contains(TailURL.attrId.value)) attrs += TailURL.attrId -> AttrValue(v))

    Attributes(VectorMap.from(attrs.toSeq))

  // Create node ID to gvid mapping for edge resolution
  val nodeIdToGvid = mutable.Map[String, Double]()
  val gvidToNodeId = mutable.Map[Double, String]()

  // Separate nodes and clusters from objects array
  val rawNodesBuilder = VectorMap.newBuilder[NodeId, Attributes]
  val rawClusters     = mutable.Map[GroupId, (Attributes, List[Int], Boolean)]()

  simpleGraph.objects.foreach { objectsList =>
    objectsList.foreach {
      case SimpleGraphObject.Node(node) =>
        val nodeId = NodeId(node.name)
        nodeIdToGvid(node.name) = node._gvid
        gvidToNodeId(node._gvid) = node.name
        val attrs = toAttributesFromNode(node, Set("name"))
        rawNodesBuilder += nodeId -> attrs
      case SimpleGraphObject.Cluster(cluster) =>
        val (groupId, wasCluster) = GroupId.fromDot(cluster.name)
        val attrs                 = toAttributesFromCluster(cluster, Set("name", "nodes", "edges", "subgraphs"))
        val nodeGvids             = cluster.nodes.getOrElse(List.empty)
        rawClusters(groupId) = (attrs, nodeGvids, wasCluster)
    }
  }
  val rawNodes = rawNodesBuilder.result()

  // Build group memberships from cluster data
  val membershipsBuilder = mutable.Map[GroupMemberId, GroupId]()
  val groupsBuilder      = mutable.Map[GroupId, ViewerGroup]()

  // Build groups first
  rawClusters.foreach { case (groupId, (attrs, nodeGvids, wasCluster)) =>
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

  // For each node, find the most specific (innermost) cluster it belongs to
  val nodeToCluster = mutable.Map[Double, GroupId]()

  rawClusters.foreach { case (groupId, (attrs, nodeGvids, wasCluster)) =>
    nodeGvids.foreach { gvid =>
      // Check if this node is also contained in any sub-clusters of this cluster
      val cluster = simpleGraph.objects.flatMap(_.collectFirst {
        case SimpleGraphObject.Cluster(c) if GroupId.fromDot(c.name)._1 == groupId => c
      })

      val isInSubCluster = cluster.exists { c =>
        c.subgraphs.exists { subgraphs =>
          subgraphs.exists { subgraphGvid =>
            simpleGraph.objects.exists(_.exists {
              case SimpleGraphObject.Cluster(subCluster) =>
                subCluster._gvid == subgraphGvid && subCluster.nodes.getOrElse(List.empty).contains(gvid)
              case _ => false
            })
          }
        }
      }

      // Only assign node to this cluster if it's not in any sub-cluster
      if (!isInSubCluster) {
        nodeToCluster(gvid) = groupId
      }
    }
  }

  // Build final memberships
  nodeToCluster.foreach { case (gvid, groupId) =>
    gvidToNodeId.get(gvid).foreach { nodeName =>
      membershipsBuilder(NodeId(nodeName)) = groupId
    }
  }

  // Handle group-to-group memberships (nested subgraphs)
  simpleGraph.objects.foreach { objectsList =>
    objectsList.foreach {
      case SimpleGraphObject.Cluster(cluster) =>
        val parentGroupId = GroupId.fromDot(cluster.name)._1
        // Check for subgraphs (nested groups)
        cluster.subgraphs.foreach { subgraphGvids =>
          subgraphGvids.foreach { subgraphGvid =>
            // Find the subgraph cluster by its _gvid
            objectsList.collectFirst {
              case SimpleGraphObject.Cluster(subCluster) if subCluster._gvid == subgraphGvid =>
                val childGroupId = GroupId.fromDot(subCluster.name)._1
                membershipsBuilder(childGroupId) = parentGroupId
            }
          }
        }
      case _ => // Ignore nodes
    }
  }

  // Convert edges to arrows
  val arrows: Map[ArrowId, Arrow] = simpleGraph.edges match {
    case Some(edgeArray) =>
      edgeArray.map { edge =>
        val sourceName = gvidToNodeId.getOrElse(edge.tail, edge.tail.toString)
        val targetName = gvidToNodeId.getOrElse(edge.head, edge.head.toString)
        val seq        = edge._gvid
        val attrs      = toAttributesFromEdge(edge, Set("tail", "head", "headport", "tailport"))

        val arrow = Arrow(
          source = NodeId(sourceName),
          target = NodeId(targetName),
          seq = seq,
          attributes = attrs,
          sourcePort = edge.tailport,
          targetPort = edge.headport
        )
        arrow.id -> arrow
      }.toMap
    case None =>
      Map.empty[ArrowId, Arrow]
  }

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
  val graphAttrs = toAttributesFromGraph(simpleGraph, Set("name", "directed", "objects", "edges"))

  VizViewerGraphElements(
    nodes = finalNodes,
    arrows = arrows,
    memberships = VectorMap.from(membershipsBuilder),
    groups = groupsBuilder.toMap,
    graphAttributes = graphAttrs
  )
