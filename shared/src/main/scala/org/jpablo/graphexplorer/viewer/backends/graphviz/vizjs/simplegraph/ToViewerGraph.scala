package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType
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
    if (!exclude.contains("label") && node.label != "\\N") attrs += AttributeId("label") -> AttrValue(sanitizeSingleLabel(node.label))

    node.pos.foreach(v => if (!exclude.contains("pos")) attrs += AttributeId("pos") -> AttrValue(v))
    node.height.foreach(v => if (!exclude.contains("height")) attrs += AttributeId("height") -> AttrValue(v))
    node.width.foreach(v => if (!exclude.contains("width")) attrs += AttributeId("width") -> AttrValue(v))
    node.shape.foreach(v => if (!exclude.contains("shape")) attrs += AttributeId("shape") -> AttrValue(v))
    node.fontname.foreach(v => if (!exclude.contains("fontname")) attrs += AttributeId("fontname") -> AttrValue(v))
    node.fontsize.foreach(v => if (!exclude.contains("fontsize")) attrs += AttributeId("fontsize") -> AttrValue(v))
    node.fontcolor.foreach(v => if (!exclude.contains("fontcolor")) attrs += AttributeId("fontcolor") -> AttrValue(v))
    node.color.foreach(v => if (!exclude.contains("color")) attrs += AttributeId("color") -> AttrValue(v))
    node.fillcolor.foreach(v => if (!exclude.contains("fillcolor")) attrs += AttributeId("fillcolor") -> AttrValue(v))
    node.style.foreach(v => if (!exclude.contains("style")) attrs += AttributeId("style") -> AttrValue(v))
    node.penwidth.foreach(v => if (!exclude.contains("penwidth")) attrs += AttributeId("penwidth") -> AttrValue(v))
    node.rects.foreach(v => if (!exclude.contains("rects")) attrs += AttributeId("rects") -> AttrValue(v))
    node.sides.foreach(v => if (!exclude.contains("sides")) attrs += AttributeId("sides") -> AttrValue(v))
    node.peripheries.foreach(v => if (!exclude.contains("peripheries")) attrs += AttributeId("peripheries") -> AttrValue(v))
    node.fixedsize.foreach(v => if (!exclude.contains("fixedsize")) attrs += AttributeId("fixedsize") -> AttrValue(v))
    node.regular.foreach(v => if (!exclude.contains("regular")) attrs += AttributeId("regular") -> AttrValue(v))
    node.orientation.foreach(v => if (!exclude.contains("orientation")) attrs += AttributeId("orientation") -> AttrValue(v))
    node.URL.foreach(v => if (!exclude.contains("URL")) attrs += AttributeId("URL") -> AttrValue(v))
    node.area.foreach(v => if (!exclude.contains("area")) attrs += AttributeId("area") -> AttrValue(v))
    node.`class`.foreach(v => if (!exclude.contains("class")) attrs += AttributeId("class") -> AttrValue(v))
    node.colorscheme.foreach(v => if (!exclude.contains("colorscheme")) attrs += AttributeId("colorscheme") -> AttrValue(v))
    node.target.foreach(v => if (!exclude.contains("target")) attrs += AttributeId("target") -> AttrValue(v))
    node.tooltip.foreach(v => if (!exclude.contains("tooltip")) attrs += AttributeId("tooltip") -> AttrValue(v))
    node.vertices.foreach(v => if (!exclude.contains("vertices")) attrs += AttributeId("vertices") -> AttrValue(v))
    node.image.foreach(v => if (!exclude.contains("image")) attrs += AttributeId("image") -> AttrValue(v))
    node.imagepath.foreach(v => if (!exclude.contains("imagepath")) attrs += AttributeId("imagepath") -> AttrValue(v))
    node.imagepos.foreach(v => if (!exclude.contains("imagepos")) attrs += AttributeId("imagepos") -> AttrValue(v))
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
      if (!exclude.contains("label") && v != "\\N") attrs += AttributeId("label") -> AttrValue(sanitizeSingleLabel(v))
    )
//      if (!exclude.contains("bb")) attrs += AttributeId("bb") -> AttrValue(cluster.bb)

    cluster.fontname.foreach(v => if (!exclude.contains("fontname")) attrs += AttributeId("fontname") -> AttrValue(v))
    cluster.fontsize.foreach(v => if (!exclude.contains("fontsize")) attrs += AttributeId("fontsize") -> AttrValue(v))
    cluster.fontcolor.foreach(v => if (!exclude.contains("fontcolor")) attrs += AttributeId("fontcolor") -> AttrValue(v))
    cluster.color.foreach(v => if (!exclude.contains("color")) attrs += AttributeId("color") -> AttrValue(v))
    cluster.pencolor.foreach(v => if (!exclude.contains("pencolor")) attrs += AttributeId("pencolor") -> AttrValue(v))
    cluster.penwidth.foreach(v => if (!exclude.contains("penwidth")) attrs += AttributeId("penwidth") -> AttrValue(v))
    cluster.bgcolor.foreach(v => if (!exclude.contains("bgcolor")) attrs += AttributeId("bgcolor") -> AttrValue(v))
    cluster.fillcolor.foreach(v => if (!exclude.contains("fillcolor")) attrs += AttributeId("fillcolor") -> AttrValue(v))
    cluster.style.foreach(v => if (!exclude.contains("style")) attrs += AttributeId("style") -> AttrValue(v))
    cluster.labeljust.foreach(v => if (!exclude.contains("labeljust")) attrs += AttributeId("labeljust") -> AttrValue(v))
    cluster.labelloc.foreach(v => if (!exclude.contains("labelloc")) attrs += AttributeId("labelloc") -> AttrValue(v))
    cluster.lheight.foreach(v => if (!exclude.contains("lheight")) attrs += AttributeId("lheight") -> AttrValue(v))
    cluster.lp.foreach(v => if (!exclude.contains("lp")) attrs += AttributeId("lp") -> AttrValue(v))
    cluster.lwidth.foreach(v => if (!exclude.contains("lwidth")) attrs += AttributeId("lwidth") -> AttrValue(v))
    // Skip layout - it's a graph-only attribute
    cluster.layout.foreach(v =>
      if (!exclude.contains("layout") && !graphOnlyAttributes.contains("layout")) attrs += AttributeId("layout") -> AttrValue(v)
    )
    cluster.normalize.foreach(v => if (!exclude.contains("normalize")) attrs += AttributeId("normalize") -> AttrValue(v))
    cluster.start.foreach(v => if (!exclude.contains("start")) attrs += AttributeId("start") -> AttrValue(v))
    cluster.overlap.foreach(v => if (!exclude.contains("overlap")) attrs += AttributeId("overlap") -> AttrValue(v))
    cluster.cluster.foreach(v => if (!exclude.contains("cluster")) attrs += AttributeId("cluster") -> AttrValue(v))
    // Skip rankdir - it's a graph-only attribute
    cluster.rankdir.foreach(v =>
      if (!exclude.contains("rankdir") && !graphOnlyAttributes.contains("rankdir")) attrs += AttributeId("rankdir") -> AttrValue(v)
    )
    cluster.splines.foreach(v => if (!exclude.contains("splines")) attrs += AttributeId("splines") -> AttrValue(v))
    cluster.target.foreach(v => if (!exclude.contains("target")) attrs += AttributeId("target") -> AttrValue(v))
    cluster.tooltip.foreach(v => if (!exclude.contains("tooltip")) attrs += AttributeId("tooltip") -> AttrValue(v))
    cluster.URL.foreach(v => if (!exclude.contains("URL")) attrs += AttributeId("URL") -> AttrValue(v))
    cluster.`class`.foreach(v => if (!exclude.contains("class")) attrs += AttributeId("class") -> AttrValue(v))
    cluster.colorscheme.foreach(v => if (!exclude.contains("colorscheme")) attrs += AttributeId("colorscheme") -> AttrValue(v))
    // Add rank attribute for clusters/subgraphs
    cluster.rank.foreach(v => if (!exclude.contains("rank")) attrs += AttributeId("rank") -> AttrValue(v))

    Attributes(VectorMap.from(attrs.toSeq))

  def toAttributesFromGraph(simpleGraph: SimpleGraph, exclude: Set[String] = Set.empty): Attributes =
    val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()

//    if (!exclude.contains("name")) attrs += AttributeId("name")         -> AttrValue(simpleGraph.name)
//    if (!exclude.contains("directed")) attrs += AttributeId("directed") -> AttrValue(simpleGraph.directed.toString)
//      if (!exclude.contains("strict")) attrs += AttributeId("strict") -> AttrValue(graph.strict.toString)
//      if (!exclude.contains("bb")) attrs += AttributeId("bb") -> AttrValue(graph.bb)
//      if (!exclude.contains("_subgraph_cnt")) attrs += AttributeId("_subgraph_cnt") -> AttrValue(graph._subgraph_cnt.toString)

    simpleGraph.fontname.foreach(v => if (!exclude.contains("fontname")) attrs += AttributeId("fontname") -> AttrValue(v))
    simpleGraph.fontsize.foreach(v => if (!exclude.contains("fontsize")) attrs += AttributeId("fontsize") -> AttrValue(v))
    simpleGraph.label.foreach(v => if (!exclude.contains("label")) attrs += AttributeId("label") -> AttrValue(sanitizeSingleLabel(v)))
    simpleGraph.labelloc.foreach(v => if (!exclude.contains("labelloc")) attrs += AttributeId("labelloc") -> AttrValue(v))
    simpleGraph.lp.foreach(v => if (!exclude.contains("lp")) attrs += AttributeId("lp") -> AttrValue(v))
    simpleGraph.lheight.foreach(v => if (!exclude.contains("lheight")) attrs += AttributeId("lheight") -> AttrValue(v))
    simpleGraph.lwidth.foreach(v => if (!exclude.contains("lwidth")) attrs += AttributeId("lwidth") -> AttrValue(v))
    simpleGraph.rankdir.foreach(v => if (!exclude.contains("rankdir")) attrs += AttributeId("rankdir") -> AttrValue(v))
    simpleGraph.layout.foreach(v => if (!exclude.contains("layout")) attrs += AttributeId("layout") -> AttrValue(v))
    simpleGraph.bgcolor.foreach(v => if (!exclude.contains("bgcolor")) attrs += AttributeId("bgcolor") -> AttrValue(v))
    simpleGraph.nodesep.foreach(v => if (!exclude.contains("nodesep")) attrs += AttributeId("nodesep") -> AttrValue(v))
    simpleGraph.pad.foreach(v => if (!exclude.contains("pad")) attrs += AttributeId("pad") -> AttrValue(v))
    simpleGraph.ranksep.foreach(v => if (!exclude.contains("ranksep")) attrs += AttributeId("ranksep") -> AttrValue(v))
    simpleGraph.ratio.foreach(v => if (!exclude.contains("ratio")) attrs += AttributeId("ratio") -> AttrValue(v))
    simpleGraph.splines.foreach(v => if (!exclude.contains("splines")) attrs += AttributeId("splines") -> AttrValue(v))
    simpleGraph.overlap.foreach(v => if (!exclude.contains("overlap")) attrs += AttributeId("overlap") -> AttrValue(v))
    simpleGraph.normalize.foreach(v => if (!exclude.contains("normalize")) attrs += AttributeId("normalize") -> AttrValue(v))
    simpleGraph.start.foreach(v => if (!exclude.contains("start")) attrs += AttributeId("start") -> AttrValue(v))

    Attributes(VectorMap.from(attrs.toSeq))

  def toAttributesFromEdge(edge: SimpleGraphEdge, exclude: Set[String] = Set.empty): Attributes =
    val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()

    if (!exclude.contains("_gvid")) attrs += AttributeId("_gvid") -> AttrValue(edge._gvid.toString)
    if (!exclude.contains("tail")) attrs += AttributeId("tail")   -> AttrValue(edge.tail.toString)
    if (!exclude.contains("head")) attrs += AttributeId("head")   -> AttrValue(edge.head.toString)

    edge.pos.foreach(v => if (!exclude.contains("pos")) attrs += AttributeId("pos") -> AttrValue(v))
    edge.id.foreach(v => if (!exclude.contains("id")) attrs += AttributeId("id") -> AttrValue(v))
    // Skip \N labels as they are the default
    edge.label.foreach(v =>
      if (!exclude.contains("label") && v != "\\N") attrs += AttributeId("label") -> AttrValue(sanitizeSingleLabel(v))
    )
    edge.fontname.foreach(v => if (!exclude.contains("fontname")) attrs += AttributeId("fontname") -> AttrValue(v))
    edge.fontsize.foreach(v => if (!exclude.contains("fontsize")) attrs += AttributeId("fontsize") -> AttrValue(v))
    edge.color.foreach(v => if (!exclude.contains("color")) attrs += AttributeId("color") -> AttrValue(v))
    edge.penwidth.foreach(v => if (!exclude.contains("penwidth")) attrs += AttributeId("penwidth") -> AttrValue(v))
    edge.style.foreach(v => if (!exclude.contains("style")) attrs += AttributeId("style") -> AttrValue(v))
    edge.lp.foreach(v => if (!exclude.contains("lp")) attrs += AttributeId("lp") -> AttrValue(v))
    edge.len.foreach(v => if (!exclude.contains("len")) attrs += AttributeId("len") -> AttrValue(v))
    edge.constraint.foreach(v => if (!exclude.contains("constraint")) attrs += AttributeId("constraint") -> AttrValue(v))
    edge.forcelabels.foreach(v => if (!exclude.contains("forcelabels")) attrs += AttributeId("forcelabels") -> AttrValue(v))
    edge.headport.foreach(v => if (!exclude.contains("headport")) attrs += AttributeId("headport") -> AttrValue(v))
    edge.tailport.foreach(v => if (!exclude.contains("tailport")) attrs += AttributeId("tailport") -> AttrValue(v))
    edge.arrowhead.foreach(v => if (!exclude.contains("arrowhead")) attrs += AttributeId("arrowhead") -> AttrValue(v))
    edge.arrowtail.foreach(v => if (!exclude.contains("arrowtail")) attrs += AttributeId("arrowtail") -> AttrValue(v))
    edge.arrowsize.foreach(v => if (!exclude.contains("arrowsize")) attrs += AttributeId("arrowsize") -> AttrValue(v))
    edge.dir.foreach(v => if (!exclude.contains("dir")) attrs += AttributeId("dir") -> AttrValue(v))
    edge.`class`.foreach(v => if (!exclude.contains("class")) attrs += AttributeId("class") -> AttrValue(v))
    edge.colorscheme.foreach(v => if (!exclude.contains("colorscheme")) attrs += AttributeId("colorscheme") -> AttrValue(v))
    edge.layer.foreach(v => if (!exclude.contains("layer")) attrs += AttributeId("layer") -> AttrValue(v))
    edge.nojustify.foreach(v => if (!exclude.contains("nojustify")) attrs += AttributeId("nojustify") -> AttrValue(v))
    edge.samehead.foreach(v => if (!exclude.contains("samehead")) attrs += AttributeId("samehead") -> AttrValue(v))
    edge.sametail.foreach(v => if (!exclude.contains("sametail")) attrs += AttributeId("sametail") -> AttrValue(v))
    edge.showboxes.foreach(v => if (!exclude.contains("showboxes")) attrs += AttributeId("showboxes") -> AttrValue(v))
    edge.tail_lp.foreach(v => if (!exclude.contains("tail_lp")) attrs += AttributeId("tail_lp") -> AttrValue(v))
    edge.tailclip.foreach(v => if (!exclude.contains("tailclip")) attrs += AttributeId("tailclip") -> AttrValue(v))
    edge.target.foreach(v => if (!exclude.contains("target")) attrs += AttributeId("target") -> AttrValue(v))
    edge.tooltip.foreach(v => if (!exclude.contains("tooltip")) attrs += AttributeId("tooltip") -> AttrValue(v))
    edge.labeldistance.foreach(v => if (!exclude.contains("labeldistance")) attrs += AttributeId("labeldistance") -> AttrValue(v))
    edge.labelfloat.foreach(v => if (!exclude.contains("labelfloat")) attrs += AttributeId("labelfloat") -> AttrValue(v))
    edge.labelfontcolor.foreach(v => if (!exclude.contains("labelfontcolor")) attrs += AttributeId("labelfontcolor") -> AttrValue(v))
    edge.labelfontname.foreach(v => if (!exclude.contains("labelfontname")) attrs += AttributeId("labelfontname") -> AttrValue(v))
    edge.tailtarget.foreach(v => if (!exclude.contains("tailtarget")) attrs += AttributeId("tailtarget") -> AttrValue(v))
    edge.tailtooltip.foreach(v => if (!exclude.contains("tailtooltip")) attrs += AttributeId("tailtooltip") -> AttrValue(v))
    edge.tailURL.foreach(v => if (!exclude.contains("tailURL")) attrs += AttributeId("tailURL") -> AttrValue(v))

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
    val updatedAttrs = if attrs.values.contains(AttributeId("cluster")) then {
      attrs
    } else {
      val defaultValue  = if wasCluster then "true" else "false"
      val updatedValues = attrs.values + (AttributeId("cluster") -> AttrValue(defaultValue))
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
