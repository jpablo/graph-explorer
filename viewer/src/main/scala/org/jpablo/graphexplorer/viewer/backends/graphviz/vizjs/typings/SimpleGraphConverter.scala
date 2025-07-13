package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings

import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.{ArrowPosition, ArrowPositionParser}

import scala.collection.mutable
import scala.collection.immutable.VectorMap
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.{Attributes as ViewerAttributes, *}
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.defaultGroupAttributes
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue

object SimpleGraphConverter:

  def viewerGraphElementsToDotString(elements: ViewerGraphElements): String =
    graphToDotString(fromViewerGraphElements(elements))

  def toViewerGraphElements(graph: SimpleGraph): ViewerGraphElements =
    import org.jpablo.graphexplorer.viewer.models.{Attributes as ViewerAttributes, *}

    // Attribute converters for case classes
    def toAttributesFromNode(node: SimpleGraphNode, exclude: Set[String] = Set.empty): ViewerAttributes =
      val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()
      
      if (!exclude.contains("_gvid")) attrs += AttributeId("_gvid") -> AttrValue(node._gvid.toString)
      if (!exclude.contains("name")) attrs += AttributeId("name") -> AttrValue(node.name)
      if (!exclude.contains("label")) attrs += AttributeId("label") -> AttrValue(node.label)
      
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
      
      ViewerAttributes(VectorMap.from(attrs.toSeq))
    
    def toAttributesFromCluster(cluster: SimpleGraphCluster, exclude: Set[String] = Set.empty): ViewerAttributes =
      val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()
      
      if (!exclude.contains("_gvid")) attrs += AttributeId("_gvid") -> AttrValue(cluster._gvid.toString)
      if (!exclude.contains("name")) attrs += AttributeId("name") -> AttrValue(cluster.name)
      if (!exclude.contains("label")) attrs += AttributeId("label") -> AttrValue(cluster.label)
//      if (!exclude.contains("bb")) attrs += AttributeId("bb") -> AttrValue(cluster.bb)
      
      cluster.fontname.foreach(v => if (!exclude.contains("fontname")) attrs += AttributeId("fontname") -> AttrValue(v))
      cluster.color.foreach(v => if (!exclude.contains("color")) attrs += AttributeId("color") -> AttrValue(v))
      cluster.bgcolor.foreach(v => if (!exclude.contains("bgcolor")) attrs += AttributeId("bgcolor") -> AttrValue(v))
      cluster.style.foreach(v => if (!exclude.contains("style")) attrs += AttributeId("style") -> AttrValue(v))
      cluster.labeljust.foreach(v => if (!exclude.contains("labeljust")) attrs += AttributeId("labeljust") -> AttrValue(v))
      cluster.labelloc.foreach(v => if (!exclude.contains("labelloc")) attrs += AttributeId("labelloc") -> AttrValue(v))
      cluster.lheight.foreach(v => if (!exclude.contains("lheight")) attrs += AttributeId("lheight") -> AttrValue(v))
      cluster.lp.foreach(v => if (!exclude.contains("lp")) attrs += AttributeId("lp") -> AttrValue(v))
      cluster.lwidth.foreach(v => if (!exclude.contains("lwidth")) attrs += AttributeId("lwidth") -> AttrValue(v))
      cluster.layout.foreach(v => if (!exclude.contains("layout")) attrs += AttributeId("layout") -> AttrValue(v))
      cluster.normalize.foreach(v => if (!exclude.contains("normalize")) attrs += AttributeId("normalize") -> AttrValue(v))
      cluster.start.foreach(v => if (!exclude.contains("start")) attrs += AttributeId("start") -> AttrValue(v))
      cluster.overlap.foreach(v => if (!exclude.contains("overlap")) attrs += AttributeId("overlap") -> AttrValue(v))
      cluster.cluster.foreach(v => if (!exclude.contains("cluster")) attrs += AttributeId("cluster") -> AttrValue(v))
      cluster.rankdir.foreach(v => if (!exclude.contains("rankdir")) attrs += AttributeId("rankdir") -> AttrValue(v))
      cluster.splines.foreach(v => if (!exclude.contains("splines")) attrs += AttributeId("splines") -> AttrValue(v))
      cluster.target.foreach(v => if (!exclude.contains("target")) attrs += AttributeId("target") -> AttrValue(v))
      cluster.tooltip.foreach(v => if (!exclude.contains("tooltip")) attrs += AttributeId("tooltip") -> AttrValue(v))
      cluster.URL.foreach(v => if (!exclude.contains("URL")) attrs += AttributeId("URL") -> AttrValue(v))
      cluster.`class`.foreach(v => if (!exclude.contains("class")) attrs += AttributeId("class") -> AttrValue(v))
      cluster.colorscheme.foreach(v => if (!exclude.contains("colorscheme")) attrs += AttributeId("colorscheme") -> AttrValue(v))
      
      ViewerAttributes(VectorMap.from(attrs.toSeq))

    def toAttributesFromGraph(graph: SimpleGraph, exclude: Set[String] = Set.empty): ViewerAttributes =
      val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()
      
      if (!exclude.contains("name")) attrs += AttributeId("name") -> AttrValue(graph.name)
      if (!exclude.contains("directed")) attrs += AttributeId("directed") -> AttrValue(graph.directed.toString)
//      if (!exclude.contains("strict")) attrs += AttributeId("strict") -> AttrValue(graph.strict.toString)
//      if (!exclude.contains("bb")) attrs += AttributeId("bb") -> AttrValue(graph.bb)
//      if (!exclude.contains("_subgraph_cnt")) attrs += AttributeId("_subgraph_cnt") -> AttrValue(graph._subgraph_cnt.toString)
      
      graph.fontname.foreach(v => if (!exclude.contains("fontname")) attrs += AttributeId("fontname") -> AttrValue(v))
      graph.fontsize.foreach(v => if (!exclude.contains("fontsize")) attrs += AttributeId("fontsize") -> AttrValue(v))
      graph.label.foreach(v => if (!exclude.contains("label")) attrs += AttributeId("label") -> AttrValue(v))
      graph.rankdir.foreach(v => if (!exclude.contains("rankdir")) attrs += AttributeId("rankdir") -> AttrValue(v))
      graph.bgcolor.foreach(v => if (!exclude.contains("bgcolor")) attrs += AttributeId("bgcolor") -> AttrValue(v))
      graph.splines.foreach(v => if (!exclude.contains("splines")) attrs += AttributeId("splines") -> AttrValue(v))
      
      ViewerAttributes(VectorMap.from(attrs.toSeq))

    def toAttributesFromEdge(edge: SimpleGraphEdge, exclude: Set[String] = Set.empty): ViewerAttributes =
      val attrs = mutable.ListBuffer[(AttributeId, AttrValue)]()
      
      if (!exclude.contains("_gvid")) attrs += AttributeId("_gvid") -> AttrValue(edge._gvid.toString)
      if (!exclude.contains("tail")) attrs += AttributeId("tail") -> AttrValue(edge.tail.toString)
      if (!exclude.contains("head")) attrs += AttributeId("head") -> AttrValue(edge.head.toString)
      
      edge.pos.foreach(v => if (!exclude.contains("pos")) attrs += AttributeId("pos") -> AttrValue(v))
      edge.id.foreach(v => if (!exclude.contains("id")) attrs += AttributeId("id") -> AttrValue(v))
      edge.label.foreach(v => if (!exclude.contains("label")) attrs += AttributeId("label") -> AttrValue(v))
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
      
      ViewerAttributes(VectorMap.from(attrs.toSeq))

    // Create node ID to gvid mapping for edge resolution
    val nodeIdToGvid = mutable.Map[String, Double]()
    val gvidToNodeId = mutable.Map[Double, String]()

    // Separate nodes and clusters from objects array
    val rawNodes    = mutable.Map[NodeId, ViewerAttributes]()
    val rawClusters = mutable.Map[GroupId, (ViewerAttributes, List[Double])]()

    graph.objects.foreach { objectsList =>
      objectsList.foreach {
        case SimpleGraphObject.Node(node) =>
          val nodeId = NodeId(node.name)
          nodeIdToGvid(node.name) = node._gvid
          gvidToNodeId(node._gvid) = node.name
          val attrs = toAttributesFromNode(node, Set("name"))
          rawNodes(nodeId) = attrs
        case SimpleGraphObject.Cluster(cluster) =>
          val groupId   = GroupId(cluster.name)
          val attrs     = toAttributesFromCluster(cluster, Set("name", "nodes", "edges", "subgraphs"))
          val nodeGvids = cluster.nodes
          rawClusters(groupId) = (attrs, nodeGvids)
      }
    }

    // Build group memberships from cluster data
    val membershipsBuilder = mutable.Map[GroupMemberId, GroupId]()
    val groupsBuilder      = mutable.Map[GroupId, ViewerGroup]()

    // Build groups first
    rawClusters.foreach { case (groupId, (attrs, nodeGvids)) =>
      groupsBuilder(groupId) = ViewerGroup.group(groupId, attrs)
    }

    // For each node, find the most specific (innermost) cluster it belongs to
    val nodeToCluster = mutable.Map[Double, GroupId]()

    rawClusters.foreach { case (groupId, (attrs, nodeGvids)) =>
      nodeGvids.foreach { gvid =>
        // Check if this node is also contained in any sub-clusters of this cluster
        val cluster = graph.objects.flatMap(_.collectFirst {
          case SimpleGraphObject.Cluster(c) if c.name == groupId.value => c
        })

        val isInSubCluster = cluster.exists { c =>
          c.subgraphs.exists { subgraphs =>
            subgraphs.exists { subgraphGvid =>
              graph.objects.exists(_.exists {
                case SimpleGraphObject.Cluster(subCluster) =>
                  subCluster._gvid == subgraphGvid && subCluster.nodes.contains(gvid)
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
    graph.objects.foreach { objectsList =>
      objectsList.foreach {
        case SimpleGraphObject.Cluster(cluster) =>
          val parentGroupId = GroupId(cluster.name)
          // Check for subgraphs (nested groups)
          cluster.subgraphs.foreach { subgraphGvids =>
            subgraphGvids.foreach { subgraphGvid =>
              // Find the subgraph cluster by its _gvid
              objectsList.collectFirst {
                case SimpleGraphObject.Cluster(subCluster) if subCluster._gvid == subgraphGvid =>
                  val childGroupId = GroupId(subCluster.name)
                  membershipsBuilder(childGroupId) = parentGroupId
              }
            }
          }
        case _ => // Ignore nodes
      }
    }

    // Convert edges to arrows
    val arrows: Map[ArrowId, Arrow] = graph.edges match {
      case Some(edgeArray) =>
        edgeArray.map { edge =>
          val sourceName = gvidToNodeId.getOrElse(edge.tail, edge.tail.toString)
          val targetName = gvidToNodeId.getOrElse(edge.head, edge.head.toString)
          val seq        = edge._gvid
          val arrowId    = ArrowId(s"$sourceName->$targetName/$seq")
          val attrs      = toAttributesFromEdge(edge, Set("tail", "head", "headport", "tailport"))

          arrowId -> Arrow(
            source = NodeId(sourceName),
            target = NodeId(targetName),
            seq = seq,
            attributes = attrs,
            sourcePort = edge.tailport,
            targetPort = edge.headport
          )
        }.toMap
      case None =>
        Map.empty[ArrowId, Arrow]
    }

    // Ensure all arrow endpoints are in nodes
    val allNodeIds = rawNodes.keys ++ arrows.values.flatMap(_.endpoints)
    val finalNodes = VectorMap.from(
      allNodeIds.map { nodeId =>
        val attrs = rawNodes.getOrElse(nodeId, ViewerAttributes.empty)
        nodeId -> ViewerNode.nodeNoDefaults(nodeId, attrs)
      }.toSeq
    )

    // Extract graph attributes
    val graphAttrs = toAttributesFromGraph(graph, Set("name", "objects", "edges"))

    ViewerGraphElements(
      nodes = finalNodes,
      arrows = arrows,
      memberships = VectorMap.from(membershipsBuilder),
      groups = groupsBuilder.toMap,
      graphAttributes = graphAttrs,
      defaultNodeAttributes = ViewerAttributes.empty,  // As requested - don't fill defaults
      defaultArrowAttributes = ViewerAttributes.empty, // As requested - don't fill defaults
      defaultGroupAttributes = ViewerAttributes.empty  // As requested - don't fill defaults
    )

  /**
   * Converts a `ViewerGraphElements` object into a `SimpleGraph` representation.
   *
   * Note: Discards default attributes!!
   * @param elements The input graph elements including nodes, edges, and clusters as provided by the viewer.
   * @return A new `SimpleGraph` instance that represents the given elements in a simplified format.
   */
  def fromViewerGraphElements(elements: ViewerGraphElements): SimpleGraph =

    // Helper to convert ViewerAttributes to SimpleGraphNode
    def attributesToNode(nodeId: NodeId, attrs: ViewerAttributes, defaultGvid: Int): SimpleGraphNode =
      def getAttr(key: String): Option[String] = 
        attrs.values.get(AttributeId(key)).map(_.toString)
      
      // Preserve original _gvid if present in attributes
      val gvid = getAttr("_gvid").map(_.toInt).getOrElse(defaultGvid)
      
      SimpleGraphNode(
        _gvid = gvid,
        name = nodeId.value,
        label = getAttr("label").getOrElse(nodeId.value),
        pos = getAttr("pos"),
        height = getAttr("height"),
        width = getAttr("width"),
        shape = getAttr("shape"),
        fontname = getAttr("fontname"),
        fontsize = getAttr("fontsize"),
        fontcolor = getAttr("fontcolor"),
        color = getAttr("color"),
        fillcolor = getAttr("fillcolor"),
        style = getAttr("style"),
        penwidth = getAttr("penwidth"),
        rects = getAttr("rects"),
        sides = getAttr("sides"),
        peripheries = getAttr("peripheries"),
        fixedsize = getAttr("fixedsize"),
        regular = getAttr("regular"),
        orientation = getAttr("orientation"),
        URL = getAttr("URL"),
        area = getAttr("area"),
        `class` = getAttr("class"),
        colorscheme = getAttr("colorscheme"),
        target = getAttr("target"),
        tooltip = getAttr("tooltip"),
        vertices = getAttr("vertices"),
        image = getAttr("image"),
        imagepath = getAttr("imagepath"),
        imagepos = getAttr("imagepos"),
        margin = getAttr("margin"),
        nojustify = getAttr("nojustify")
      )

    // Helper to convert ViewerAttributes to SimpleGraphCluster
    def attributesToCluster(groupId: GroupId, attrs: ViewerAttributes, defaultGvid: Double, nodeGvids: List[Double], edgeGvids: Option[List[Double]] = None, subgraphGvids: Option[List[Double]] = None): SimpleGraphCluster =
      def getAttr(key: String): Option[String] = 
        attrs.values.get(AttributeId(key)).map(_.toString)
      
      // Check if an attribute is a default value that shouldn't be included
      def isDefaultValue(key: String, value: String): Boolean = {
        defaultGroupAttributes.values.get(AttributeId(key)).exists(_.toString == value)
      }
      
      // Get attribute only if it's not a default value
      def getAttrNonDefault(key: String): Option[String] = {
        getAttr(key).filterNot(value => isDefaultValue(key, value))
      }
      
      // Preserve original _gvid if present in attributes
      val gvid = getAttr("_gvid").map(_.toDouble).getOrElse(defaultGvid)
      
      SimpleGraphCluster(
        _gvid = gvid,
        name = groupId.value,
//        bb = getAttr("bb").getOrElse("0,0,100,100"),
        nodes = nodeGvids,
        label = getAttrNonDefault("label").getOrElse(groupId.value),
        edges = edgeGvids,
        subgraphs = subgraphGvids,
        fontname = getAttr("fontname"),
        color = getAttr("color"),
        bgcolor = getAttr("bgcolor"),
        style = getAttr("style"),
        labeljust = getAttrNonDefault("labeljust"),
        labelloc = getAttrNonDefault("labelloc"),
        lheight = getAttr("lheight"),
        lp = getAttr("lp"),
        lwidth = getAttr("lwidth"),
        layout = getAttr("layout"),
        normalize = getAttr("normalize"),
        start = getAttr("start"),
        overlap = getAttr("overlap"),
        cluster = getAttr("cluster"), // Always preserve cluster attribute if present
        rankdir = getAttr("rankdir"),
        splines = getAttr("splines"),
        target = getAttr("target"),
        tooltip = getAttr("tooltip"),
        URL = getAttr("URL"),
        `class` = getAttr("class"),
        colorscheme = getAttr("colorscheme")
      )

    // Helper to convert ViewerAttributes to SimpleGraphEdge
    def attributesToEdge(arrow: Arrow, defaultGvid: Int, tailGvid: Int, headGvid: Int): SimpleGraphEdge =
      def getAttr(key: String): Option[String] = 
        arrow.attributes.values.get(AttributeId(key)).map(_.toString)
      
      // Preserve original _gvid if present in attributes
      val gvid = getAttr("_gvid").map(_.toInt).getOrElse(defaultGvid)
      
      SimpleGraphEdge(
        _gvid = gvid,
        tail = tailGvid,
        head = headGvid,
        pos = getAttr("pos"),
        id = getAttr("id").orElse(Some(arrow.id.toSvg)),
        label = getAttr("label"),
        fontname = getAttr("fontname"),
        fontsize = getAttr("fontsize"),
        color = getAttr("color"),
        penwidth = getAttr("penwidth"),
        style = getAttr("style"),
        lp = getAttr("lp"),
        len = getAttr("len"),
        constraint = getAttr("constraint"),
        forcelabels = getAttr("forcelabels"),
        headport = arrow.targetPort,
        tailport = arrow.sourcePort,
        arrowhead = getAttr("arrowhead"),
        arrowtail = getAttr("arrowtail"),
        arrowsize = getAttr("arrowsize"),
        dir = getAttr("dir"),
        `class` = getAttr("class"),
        colorscheme = getAttr("colorscheme"),
        layer = getAttr("layer"),
        nojustify = getAttr("nojustify"),
        samehead = getAttr("samehead"),
        sametail = getAttr("sametail"),
        showboxes = getAttr("showboxes"),
        tail_lp = getAttr("tail_lp"),
        tailclip = getAttr("tailclip"),
        target = getAttr("target"),
        tooltip = getAttr("tooltip"),
        labeldistance = getAttr("labeldistance"),
        labelfloat = getAttr("labelfloat"),
        labelfontcolor = getAttr("labelfontcolor"),
        labelfontname = getAttr("labelfontname"),
        tailtarget = getAttr("tailtarget"),
        tailtooltip = getAttr("tailtooltip"),
        tailURL = getAttr("tailURL")
      )

    // Helper to convert ViewerAttributes to SimpleGraph
    def attributesToGraph(attrs: ViewerAttributes, 
                         name: String, 
                         objects: Option[List[SimpleGraphObject]], 
                         edges: Option[List[SimpleGraphEdge]],
                         subgraphCnt: Double): SimpleGraph =
      def getAttr(key: String): Option[String] = 
        attrs.values.get(AttributeId(key)).map(_.toString)
      
      SimpleGraph(
        name = name,
        directed = getAttr("directed").map(_.toBoolean).getOrElse(true),
//        strict = getAttr("strict").map(_.toBoolean).getOrElse(false),
//        bb = getAttr("bb").getOrElse("0,0,100,100"),
//        _subgraph_cnt = subgraphCnt,
        objects = objects,
        edges = edges,
        fontname = getAttr("fontname"),
        fontsize = getAttr("fontsize"),
        label = getAttr("label"),
        labelloc = getAttr("labelloc"),
        lp = getAttr("lp"),
        lheight = getAttr("lheight"),
        lwidth = getAttr("lwidth"),
        rankdir = getAttr("rankdir"),
        layout = getAttr("layout"),
        bgcolor = getAttr("bgcolor"),
        nodesep = getAttr("nodesep"),
        pad = getAttr("pad"),
        ranksep = getAttr("ranksep"),
        ratio = getAttr("ratio"),
        splines = getAttr("splines"),
        overlap = getAttr("overlap"),
        normalize = getAttr("normalize"),
        start = getAttr("start"),
        beautify = getAttr("beautify"),
        Damping = getAttr("Damping"),
        defaultdist = getAttr("defaultdist"),
        dim = getAttr("dim"),
        dimen = getAttr("dimen"),
        diredgeconstraints = getAttr("diredgeconstraints"),
        dpi = getAttr("dpi"),
        epsilon = getAttr("epsilon"),
        esep = getAttr("esep"),
        fontnames = getAttr("fontnames"),
        fontpath = getAttr("fontpath"),
        K = getAttr("K"),
        label_scheme = getAttr("label_scheme"),
        labeljust = getAttr("labeljust"),
        landscape = getAttr("landscape"),
        layerlistsep = getAttr("layerlistsep"),
        layers = getAttr("layers"),
        layerselect = getAttr("layerselect"),
        layersep = getAttr("layersep"),
        nojustify = getAttr("nojustify"),
        notranslate = getAttr("notranslate"),
        target = getAttr("target"),
        TBbalance = getAttr("TBbalance"),
        tooltip = getAttr("tooltip"),
        truecolor = getAttr("truecolor"),
        URL = getAttr("URL")
      )

    // Build reverse membership map (group -> members)
    val groupMemberships = mutable.Map[GroupId, mutable.Set[NodeId]]()
    val groupToSubgroups = mutable.Map[GroupId, mutable.Set[GroupId]]()
    
    elements.memberships.foreach {
      case (nodeId: NodeId, groupId) =>
        groupMemberships.getOrElseUpdate(groupId, mutable.Set.empty) += nodeId
      case (childGroupId: GroupId, parentGroupId) =>
        groupToSubgroups.getOrElseUpdate(parentGroupId, mutable.Set.empty) += childGroupId
    }

    // Create a mapping from NodeId to its preserved gvid or a new one
    val nodeIdToGvid = mutable.Map[NodeId, Int]()
    
    // Check if any nodes have explicit gvids
    val hasExplicitGvids = elements.nodes.exists { case (_, node) =>
      node.attributes.values.contains(AttributeId("_gvid"))
    }
    
    // If no explicit gvids, start from 0; otherwise start high to avoid conflicts
    var nextNodeGvid = if (hasExplicitGvids) 1000 else 0
    
    // Assign gvids in the order of nodes
    elements.nodes.foreach { case (nodeId, node) =>
      val gvid = node.attributes.values.get(AttributeId("_gvid")).map(_.toString.toInt).getOrElse {
        val id = nextNodeGvid
        nextNodeGvid += 1
        id
      }
      nodeIdToGvid(nodeId) = gvid
    }

    // Create node objects preserving original gvids
    val nodeObjects = elements.nodes.map { case (nodeId, node) =>
      val gvid = nodeIdToGvid(nodeId)
      val nodeWithDefaults = attributesToNode(nodeId, node.attributes, gvid)
      // Apply defaults for required fields if missing
      nodeWithDefaults.copy(
        pos = nodeWithDefaults.pos.orElse(Some("0,0")),
        height = nodeWithDefaults.height.orElse(Some("0.5")),
        width = nodeWithDefaults.width.orElse(Some("0.75"))
      )
    }.toList

    // Create cluster objects, preserving original gvids
    // Check if any clusters have explicit gvids
    val hasExplicitClusterGvids = elements.groups.exists { case (_, group) =>
      group.attributes.values.contains(AttributeId("_gvid"))
    }
    
    // If no explicit gvids, start from 0; otherwise start high to avoid conflicts
    var nextClusterGvid = if (hasExplicitClusterGvids) 1000.0 else 0.0
    
    // Build a map of all direct node memberships (including through nested groups)
    val allNodeMemberships = mutable.Map[GroupId, mutable.Set[Double]]()
    
    def collectAllNodeGvids(groupId: GroupId): Set[Double] = {
      val directNodes = groupMemberships.getOrElse(groupId, mutable.Set.empty)
        .map(nodeId => nodeIdToGvid(nodeId).toDouble).toSet
      
      val nestedNodes = groupToSubgroups.getOrElse(groupId, mutable.Set.empty)
        .flatMap(childGroupId => collectAllNodeGvids(childGroupId)).toSet
      
      directNodes ++ nestedNodes
    }
    
    // Populate all node memberships for each group
    elements.groups.keys.foreach { groupId =>
      allNodeMemberships(groupId) = mutable.Set.from(collectAllNodeGvids(groupId))
    }
    
    val clusterObjects = elements.groups.map { case (groupId, group) =>
      // Get all node gvids for this cluster (including nested)
      val memberGvids = allNodeMemberships.getOrElse(groupId, mutable.Set.empty).toList.sorted
      
      val subgraphGvids = groupToSubgroups.get(groupId).map { subgroups =>
        subgroups.toList.map { subgroupId =>
          elements.groups(subgroupId).attributes.values
            .get(AttributeId("_gvid"))
            .map(_.toString.toDouble)
            .getOrElse {
              val id = nextClusterGvid
              nextClusterGvid += 1
              id
            }
        }.sorted
      }
      
      // Find edge gvids for this cluster 
      // Reconstruct edge references based on which edges connect nodes in this cluster
      val nodeGvidSet = memberGvids.toSet
      val clusterEdges = elements.arrows.values.collect {
        case arrow if nodeGvidSet.contains(nodeIdToGvid(arrow.source).toDouble) && 
                     nodeGvidSet.contains(nodeIdToGvid(arrow.target).toDouble) =>
          arrow.attributes.values.get(AttributeId("_gvid"))
            .map(_.toString.toDouble)
            .getOrElse(arrow.seq.toDouble)
      }.toList.sorted
      
      val edgeGvids = if (clusterEdges.nonEmpty) Some(clusterEdges) else None
      
      val defaultGvid = group.attributes.values.get(AttributeId("_gvid"))
        .map(_.toString.toDouble)
        .getOrElse {
          val id = nextClusterGvid
          nextClusterGvid += 1
          id
        }
      
      attributesToCluster(
        groupId, 
        group.attributes, 
        defaultGvid,
        memberGvids,
        edgeGvids,
        subgraphGvids
      )
    }.toList

    // Sort objects: clusters first (by gvid), then nodes (by gvid)
    val sortedClusters = clusterObjects.sortBy(_._gvid)
    val sortedNodes = nodeObjects.sortBy(_._gvid)
    
    // Combine into objects array with clusters first
    val allObjects: List[SimpleGraphObject] = 
      sortedClusters.map(SimpleGraphObject.Cluster.apply) ++ 
      sortedNodes.map(SimpleGraphObject.Node.apply)

    // Create edge objects
    // Check if any edges have explicit gvids
    val hasExplicitEdgeGvids = elements.arrows.exists { case (_, arrow) =>
      arrow.attributes.values.contains(AttributeId("_gvid"))
    }
    
    // If no explicit gvids, start from 0; otherwise use seq as default to avoid conflicts
    var edgeCounter = 0
    
    val edgeObjects = elements.arrows.values.map { arrow =>
      // Use preserved gvids for nodes
      val tailGvid = nodeIdToGvid(arrow.source)
      val headGvid = nodeIdToGvid(arrow.target)
      
      // Use explicit gvid if present, otherwise use counter starting from 0
      val defaultGvid = if (hasExplicitEdgeGvids) arrow.seq else {
        val gvid = edgeCounter
        edgeCounter += 1
        gvid
      }
      
      attributesToEdge(arrow, defaultGvid, tailGvid, headGvid)
    }.toList.sortBy(-_._gvid) // Sort by descending gvid to match original order

    // Create main graph object
    attributesToGraph(
      elements.graphAttributes,
      "G",
      if (allObjects.nonEmpty) Some(allObjects) else None,
      if (edgeObjects.nonEmpty) Some(edgeObjects) else None,
      elements.groups.size.toDouble
    )

  def graphToDotString(graph: SimpleGraph): String =
    val lines = mutable.ListBuffer[String]()

    // Helper to detect if a string contains HTML-like content
    def isHtmlLabel(value: String): Boolean = 
      value.contains("<") && value.contains(">") && 
      (value.contains("<table") || value.contains("<b>") || value.contains("<i>") || 
       value.contains("<font") || value.contains("<br") || value.contains("<hr") ||
       value.contains("<td") || value.contains("<tr") || value.contains("</"))

    // Detect if this is a complex graph with nested subgraphs or has HTML labels
    val hasNestedSubgraphs = graph.objects.exists(_.exists {
      case SimpleGraphObject.Cluster(cluster) => cluster.subgraphs.isDefined && cluster.subgraphs.get.nonEmpty
      case _ => false
    })
    
    // Check if any node has HTML labels with multi-line content
    val hasComplexHtmlLabels = graph.objects.exists(_.exists {
      case SimpleGraphObject.Node(node) => isHtmlLabel(node.label) && node.label.trim.split("\n").length > 1
      case _ => false
    })

    // Helper function for padding - use 4 spaces for complex graphs or graphs with multi-line HTML labels, 2 for simple
    def padding(level: Int): String = if (hasNestedSubgraphs || hasComplexHtmlLabels) "    " * level else "  " * level
    
    // Helper to format a single attribute value
    def formatValue(value: String): String = s""""$value""""
    
    // Helper to format a label value - HTML labels use <> notation, others use quotes
    def formatLabelValue(value: String): String = 
      if (isHtmlLabel(value)) {
        // Remove leading/trailing whitespace and format HTML labels
        val trimmed = value.trim
        s"<$trimmed>"
      }
      else s""""$value""""

    // Helper to collect attributes from case class
    def collectNodeAttributes(node: SimpleGraphNode, excludeKeys: Set[String] = Set.empty): List[(String, String)] =
      val attrs = mutable.ListBuffer[(String, String)]()
      if (!excludeKeys.contains("id")) attrs += "id" -> s"node:${node.name}"  // Add the semantic node ID
      if (!excludeKeys.contains("label")) attrs += "label" -> node.label
      node.pos.foreach(v => if (!excludeKeys.contains("pos")) attrs += "pos" -> v)
      node.height.foreach(v => if (!excludeKeys.contains("height")) attrs += "height" -> v)
      node.width.foreach(v => if (!excludeKeys.contains("width")) attrs += "width" -> v)
      node.shape.foreach(v => if (!excludeKeys.contains("shape")) attrs += "shape" -> v)
      node.fontname.foreach(v => if (!excludeKeys.contains("fontname")) attrs += "fontname" -> v)
      node.fontsize.foreach(v => if (!excludeKeys.contains("fontsize")) attrs += "fontsize" -> v)
      node.fontcolor.foreach(v => if (!excludeKeys.contains("fontcolor")) attrs += "fontcolor" -> v)
      node.color.foreach(v => if (!excludeKeys.contains("color")) attrs += "color" -> v)
      node.fillcolor.foreach(v => if (!excludeKeys.contains("fillcolor")) attrs += "fillcolor" -> v)
      node.style.foreach(v => if (!excludeKeys.contains("style")) attrs += "style" -> v)
      node.penwidth.foreach(v => if (!excludeKeys.contains("penwidth")) attrs += "penwidth" -> v)
      node.rects.foreach(v => if (!excludeKeys.contains("rects")) attrs += "rects" -> v)
      node.sides.foreach(v => if (!excludeKeys.contains("sides")) attrs += "sides" -> v)
      node.peripheries.foreach(v => if (!excludeKeys.contains("peripheries")) attrs += "peripheries" -> v)
      node.fixedsize.foreach(v => if (!excludeKeys.contains("fixedsize")) attrs += "fixedsize" -> v)
      node.regular.foreach(v => if (!excludeKeys.contains("regular")) attrs += "regular" -> v)
      node.orientation.foreach(v => if (!excludeKeys.contains("orientation")) attrs += "orientation" -> v)
      node.URL.foreach(v => if (!excludeKeys.contains("URL")) attrs += "URL" -> v)
      node.area.foreach(v => if (!excludeKeys.contains("area")) attrs += "area" -> v)
      node.`class`.foreach(v => if (!excludeKeys.contains("class")) attrs += "class" -> v)
      node.colorscheme.foreach(v => if (!excludeKeys.contains("colorscheme")) attrs += "colorscheme" -> v)
      node.target.foreach(v => if (!excludeKeys.contains("target")) attrs += "target" -> v)
      node.tooltip.foreach(v => if (!excludeKeys.contains("tooltip")) attrs += "tooltip" -> v)
      node.vertices.foreach(v => if (!excludeKeys.contains("vertices")) attrs += "vertices" -> v)
      node.image.foreach(v => if (!excludeKeys.contains("image")) attrs += "image" -> v)
      node.imagepath.foreach(v => if (!excludeKeys.contains("imagepath")) attrs += "imagepath" -> v)
      node.imagepos.foreach(v => if (!excludeKeys.contains("imagepos")) attrs += "imagepos" -> v)
      node.margin.foreach(v => if (!excludeKeys.contains("margin")) attrs += "margin" -> v)
      node.nojustify.foreach(v => if (!excludeKeys.contains("nojustify")) attrs += "nojustify" -> v)
      attrs.toList

    def collectClusterAttributes(cluster: SimpleGraphCluster, excludeKeys: Set[String] = Set.empty): List[(String, String)] =
      val attrs = mutable.ListBuffer[(String, String)]()
      if (!excludeKeys.contains("label")) attrs += "label" -> cluster.label
      cluster.fontname.foreach(v => if (!excludeKeys.contains("fontname")) attrs += "fontname" -> v)
      cluster.color.foreach(v => if (!excludeKeys.contains("color")) attrs += "color" -> v)
      cluster.bgcolor.foreach(v => if (!excludeKeys.contains("bgcolor")) attrs += "bgcolor" -> v)
      cluster.style.foreach(v => if (!excludeKeys.contains("style")) attrs += "style" -> v)
      cluster.labeljust.foreach(v => if (!excludeKeys.contains("labeljust")) attrs += "labeljust" -> v)
      cluster.labelloc.foreach(v => if (!excludeKeys.contains("labelloc")) attrs += "labelloc" -> v)
      cluster.lheight.foreach(v => if (!excludeKeys.contains("lheight")) attrs += "lheight" -> v)
      cluster.lp.foreach(v => if (!excludeKeys.contains("lp")) attrs += "lp" -> v)
      cluster.lwidth.foreach(v => if (!excludeKeys.contains("lwidth")) attrs += "lwidth" -> v)
      cluster.layout.foreach(v => if (!excludeKeys.contains("layout")) attrs += "layout" -> v)
      cluster.normalize.foreach(v => if (!excludeKeys.contains("normalize")) attrs += "normalize" -> v)
      cluster.start.foreach(v => if (!excludeKeys.contains("start")) attrs += "start" -> v)
      cluster.overlap.foreach(v => if (!excludeKeys.contains("overlap")) attrs += "overlap" -> v)
      cluster.cluster.foreach(v => if (!excludeKeys.contains("cluster")) attrs += "cluster" -> v)
      cluster.rankdir.foreach(v => if (!excludeKeys.contains("rankdir")) attrs += "rankdir" -> v)
      cluster.splines.foreach(v => if (!excludeKeys.contains("splines")) attrs += "splines" -> v)
      cluster.target.foreach(v => if (!excludeKeys.contains("target")) attrs += "target" -> v)
      cluster.tooltip.foreach(v => if (!excludeKeys.contains("tooltip")) attrs += "tooltip" -> v)
      cluster.URL.foreach(v => if (!excludeKeys.contains("URL")) attrs += "URL" -> v)
      cluster.`class`.foreach(v => if (!excludeKeys.contains("class")) attrs += "class" -> v)
      cluster.colorscheme.foreach(v => if (!excludeKeys.contains("colorscheme")) attrs += "colorscheme" -> v)
      attrs.toList

    def collectGraphAttributes(graph: SimpleGraph, excludeKeys: Set[String] = Set.empty): List[(String, String)] =
      val attrs = mutable.ListBuffer[(String, String)]()
      graph.fontname.foreach(v => if (!excludeKeys.contains("fontname")) attrs += "fontname" -> v)
      graph.fontsize.foreach(v => if (!excludeKeys.contains("fontsize")) attrs += "fontsize" -> v)
      graph.label.foreach(v => if (!excludeKeys.contains("label")) attrs += "label" -> v)
      graph.labelloc.foreach(v => if (!excludeKeys.contains("labelloc")) attrs += "labelloc" -> v)
      graph.lp.foreach(v => if (!excludeKeys.contains("lp")) attrs += "lp" -> v)
      graph.lheight.foreach(v => if (!excludeKeys.contains("lheight")) attrs += "lheight" -> v)
      graph.lwidth.foreach(v => if (!excludeKeys.contains("lwidth")) attrs += "lwidth" -> v)
      graph.rankdir.foreach(v => if (!excludeKeys.contains("rankdir")) attrs += "rankdir" -> v)
      graph.layout.foreach(v => if (!excludeKeys.contains("layout")) attrs += "layout" -> v)
      graph.bgcolor.foreach(v => if (!excludeKeys.contains("bgcolor")) attrs += "bgcolor" -> v)
      graph.nodesep.foreach(v => if (!excludeKeys.contains("nodesep")) attrs += "nodesep" -> v)
      graph.pad.foreach(v => if (!excludeKeys.contains("pad")) attrs += "pad" -> v)
      graph.ranksep.foreach(v => if (!excludeKeys.contains("ranksep")) attrs += "ranksep" -> v)
      graph.ratio.foreach(v => if (!excludeKeys.contains("ratio")) attrs += "ratio" -> v)
      graph.splines.foreach(v => if (!excludeKeys.contains("splines")) attrs += "splines" -> v)
      graph.overlap.foreach(v => if (!excludeKeys.contains("overlap")) attrs += "overlap" -> v)
      graph.normalize.foreach(v => if (!excludeKeys.contains("normalize")) attrs += "normalize" -> v)
      graph.start.foreach(v => if (!excludeKeys.contains("start")) attrs += "start" -> v)
      graph.beautify.foreach(v => if (!excludeKeys.contains("beautify")) attrs += "beautify" -> v)
      graph.Damping.foreach(v => if (!excludeKeys.contains("Damping")) attrs += "Damping" -> v)
      graph.defaultdist.foreach(v => if (!excludeKeys.contains("defaultdist")) attrs += "defaultdist" -> v)
      graph.dim.foreach(v => if (!excludeKeys.contains("dim")) attrs += "dim" -> v)
      graph.dimen.foreach(v => if (!excludeKeys.contains("dimen")) attrs += "dimen" -> v)
      graph.diredgeconstraints.foreach(v => if (!excludeKeys.contains("diredgeconstraints")) attrs += "diredgeconstraints" -> v)
      graph.dpi.foreach(v => if (!excludeKeys.contains("dpi")) attrs += "dpi" -> v)
      graph.epsilon.foreach(v => if (!excludeKeys.contains("epsilon")) attrs += "epsilon" -> v)
      graph.esep.foreach(v => if (!excludeKeys.contains("esep")) attrs += "esep" -> v)
      graph.fontnames.foreach(v => if (!excludeKeys.contains("fontnames")) attrs += "fontnames" -> v)
      graph.fontpath.foreach(v => if (!excludeKeys.contains("fontpath")) attrs += "fontpath" -> v)
      graph.K.foreach(v => if (!excludeKeys.contains("K")) attrs += "K" -> v)
      graph.label_scheme.foreach(v => if (!excludeKeys.contains("label_scheme")) attrs += "label_scheme" -> v)
      graph.labeljust.foreach(v => if (!excludeKeys.contains("labeljust")) attrs += "labeljust" -> v)
      graph.landscape.foreach(v => if (!excludeKeys.contains("landscape")) attrs += "landscape" -> v)
      graph.layerlistsep.foreach(v => if (!excludeKeys.contains("layerlistsep")) attrs += "layerlistsep" -> v)
      graph.layers.foreach(v => if (!excludeKeys.contains("layers")) attrs += "layers" -> v)
      graph.layerselect.foreach(v => if (!excludeKeys.contains("layerselect")) attrs += "layerselect" -> v)
      graph.layersep.foreach(v => if (!excludeKeys.contains("layersep")) attrs += "layersep" -> v)
      graph.nojustify.foreach(v => if (!excludeKeys.contains("nojustify")) attrs += "nojustify" -> v)
      graph.notranslate.foreach(v => if (!excludeKeys.contains("notranslate")) attrs += "notranslate" -> v)
      graph.target.foreach(v => if (!excludeKeys.contains("target")) attrs += "target" -> v)
      graph.TBbalance.foreach(v => if (!excludeKeys.contains("TBbalance")) attrs += "TBbalance" -> v)
      graph.tooltip.foreach(v => if (!excludeKeys.contains("tooltip")) attrs += "tooltip" -> v)
      graph.truecolor.foreach(v => if (!excludeKeys.contains("truecolor")) attrs += "truecolor" -> v)
      graph.URL.foreach(v => if (!excludeKeys.contains("URL")) attrs += "URL" -> v)
      attrs.toList

    def collectEdgeAttributes(edge: SimpleGraphEdge, excludeKeys: Set[String] = Set.empty): List[(String, String)] =
      val attrs = mutable.ListBuffer[(String, String)]()
      edge.id.foreach(v => if (!excludeKeys.contains("id")) attrs += "id" -> v)
      edge.label.foreach(v => if (!excludeKeys.contains("label")) attrs += "label" -> v)
      edge.fontname.foreach(v => if (!excludeKeys.contains("fontname")) attrs += "fontname" -> v)
      edge.fontsize.foreach(v => if (!excludeKeys.contains("fontsize")) attrs += "fontsize" -> v)
      edge.color.foreach(v => if (!excludeKeys.contains("color")) attrs += "color" -> v)
      edge.penwidth.foreach(v => if (!excludeKeys.contains("penwidth")) attrs += "penwidth" -> v)
      edge.style.foreach(v => if (!excludeKeys.contains("style")) attrs += "style" -> v)
      edge.lp.foreach(v => if (!excludeKeys.contains("lp")) attrs += "lp" -> v)
      edge.len.foreach(v => if (!excludeKeys.contains("len")) attrs += "len" -> v)
      edge.constraint.foreach(v => if (!excludeKeys.contains("constraint")) attrs += "constraint" -> v)
      edge.forcelabels.foreach(v => if (!excludeKeys.contains("forcelabels")) attrs += "forcelabels" -> v)
      edge.arrowhead.foreach(v => if (!excludeKeys.contains("arrowhead")) attrs += "arrowhead" -> v)
      edge.arrowtail.foreach(v => if (!excludeKeys.contains("arrowtail")) attrs += "arrowtail" -> v)
      edge.arrowsize.foreach(v => if (!excludeKeys.contains("arrowsize")) attrs += "arrowsize" -> v)
      edge.dir.foreach(v => if (!excludeKeys.contains("dir")) attrs += "dir" -> v)
      edge.`class`.foreach(v => if (!excludeKeys.contains("class")) attrs += "class" -> v)
      edge.colorscheme.foreach(v => if (!excludeKeys.contains("colorscheme")) attrs += "colorscheme" -> v)
      edge.layer.foreach(v => if (!excludeKeys.contains("layer")) attrs += "layer" -> v)
      edge.nojustify.foreach(v => if (!excludeKeys.contains("nojustify")) attrs += "nojustify" -> v)
      edge.samehead.foreach(v => if (!excludeKeys.contains("samehead")) attrs += "samehead" -> v)
      edge.sametail.foreach(v => if (!excludeKeys.contains("sametail")) attrs += "sametail" -> v)
      edge.showboxes.foreach(v => if (!excludeKeys.contains("showboxes")) attrs += "showboxes" -> v)
      edge.tail_lp.foreach(v => if (!excludeKeys.contains("tail_lp")) attrs += "tail_lp" -> v)
      edge.tailclip.foreach(v => if (!excludeKeys.contains("tailclip")) attrs += "tailclip" -> v)
      edge.target.foreach(v => if (!excludeKeys.contains("target")) attrs += "target" -> v)
      edge.tooltip.foreach(v => if (!excludeKeys.contains("tooltip")) attrs += "tooltip" -> v)
      edge.labeldistance.foreach(v => if (!excludeKeys.contains("labeldistance")) attrs += "labeldistance" -> v)
      edge.labelfloat.foreach(v => if (!excludeKeys.contains("labelfloat")) attrs += "labelfloat" -> v)
      edge.labelfontcolor.foreach(v => if (!excludeKeys.contains("labelfontcolor")) attrs += "labelfontcolor" -> v)
      edge.labelfontname.foreach(v => if (!excludeKeys.contains("labelfontname")) attrs += "labelfontname" -> v)
      edge.tailtarget.foreach(v => if (!excludeKeys.contains("tailtarget")) attrs += "tailtarget" -> v)
      edge.tailtooltip.foreach(v => if (!excludeKeys.contains("tailtooltip")) attrs += "tailtooltip" -> v)
      edge.tailURL.foreach(v => if (!excludeKeys.contains("tailURL")) attrs += "tailURL" -> v)
      attrs.toList

    // Helper to format attribute list
    def formatAttributes(attrs: List[(String, String)]): String =
      if (attrs.isEmpty) ""
      else {
        val formattedAttrs = attrs.map { case (key, value) =>
          if (key == "label") s"$key=${formatLabelValue(value)}"
          else s"$key=${formatValue(value)}"
        }
        s" [${formattedAttrs.mkString(", ")}]"
      }

    // Helper to format attributes in multi-line format for complex cases
    def formatAttributesMultiLine(attrs: List[(String, String)], level: Int, forceMultiLine: Boolean = false): String =
      if (attrs.isEmpty) ""
      else if (forceMultiLine || hasNestedSubgraphs || hasComplexHtmlLabels || attrs.exists { case (k, v) => k == "label" && isHtmlLabel(v) && v.trim.split("\n").length > 1 }) {
        val formattedAttrs = attrs.zipWithIndex.map { case ((key, value), idx) =>
          val isLast = idx == attrs.length - 1
          // Complex graphs with nested subgraphs use comma without space, HTML labels use comma with space
          val comma = if (isLast) "" else if (hasNestedSubgraphs && !hasComplexHtmlLabels) "," else ", "
          
          if (key == "label" && isHtmlLabel(value) && value.trim.split("\n").length > 1) {
            // Special formatting for multi-line HTML labels - each line of HTML on its own line
            val lines = value.trim.split("\n").map(_.trim).filter(_.nonEmpty)
            // Use extra indentation for HTML content
            val htmlPadding = if (hasComplexHtmlLabels) "              " else s"${padding(level + 2)}"
            val htmlLines = lines.map(line => s"$htmlPadding$line").mkString("\n")
            s"${padding(level + 1)}$key=<\n$htmlLines\n${padding(level + 1)}>$comma"
          } else if (key == "label") {
            s"${padding(level + 1)}$key=${formatLabelValue(value)}$comma"
          } else {
            s"${padding(level + 1)}$key=${formatValue(value)}$comma"
          }
        }
        s" [\n${formattedAttrs.mkString("\n")}\n${padding(level)}]"
      } else {
        formatAttributes(attrs)
      }

    // Start graph
    val graphName = graph.name
    val graphType = if (graph.directed) "digraph" else "graph"
    val edgeOp    = if (graph.directed) "->" else "--"

    lines += s"""$graphType "$graphName" {"""

    // Add graph attributes
    val graphAttrs = collectGraphAttributes(graph)
    if (graphAttrs.nonEmpty) {
      lines += s"${padding(1)}graph${formatAttributes(graphAttrs)};"
    }

    // Helper to process clusters recursively with proper nesting
    def processCluster(cluster: SimpleGraphCluster, level: Int, processedClusters: scala.collection.mutable.Set[Double]): Unit = {
      if (processedClusters.contains(cluster._gvid)) return
      processedClusters += cluster._gvid
      
      // For complex graphs, don't add "cluster_" prefix
      val subgraphName = if (hasNestedSubgraphs) cluster.name else s"cluster_${cluster.name}"
      lines += s"""${padding(level)}subgraph "$subgraphName" {"""

      val clusterAttrs = collectClusterAttributes(cluster)
      if (clusterAttrs.nonEmpty) {
        val attrFormatting = if (hasNestedSubgraphs) 
          formatAttributesMultiLine(clusterAttrs, level + 1, false)
        else 
          formatAttributes(clusterAttrs)
        lines += s"${padding(level + 1)}graph$attrFormatting;"
      }

      // Process nested subgraphs first
      cluster.subgraphs.foreach { subgraphGvids =>
        subgraphGvids.foreach { subgraphGvid =>
          graph.objects.foreach { objectsList =>
            objectsList.collectFirst {
              case SimpleGraphObject.Cluster(subCluster) if subCluster._gvid == subgraphGvid =>
                processCluster(subCluster, level + 1, processedClusters)
            }
          }
        }
      }

      // Add nodes that belong only to this cluster (not in any sub-clusters)
      cluster.nodes.foreach { nodeGvid =>
        // Check if this node is in any of the nested subgraphs
        val isInSubCluster = cluster.subgraphs.exists { subgraphGvids =>
          subgraphGvids.exists { subgraphGvid =>
            graph.objects.exists { objectsList =>
              objectsList.exists {
                case SimpleGraphObject.Cluster(subCluster) if subCluster._gvid == subgraphGvid =>
                  subCluster.nodes.contains(nodeGvid)
                case _ => false
              }
            }
          }
        }

        // Only add the node if it's not in a sub-cluster
        if (!isInSubCluster) {
          graph.objects.flatMap(_.collectFirst {
            case SimpleGraphObject.Node(node) if node._gvid == nodeGvid => node
          }).foreach { node =>
            val nodeAttrs = collectNodeAttributes(node)
            val hasMultiLineHtmlLabel = isHtmlLabel(node.label) && node.label.trim.split("\n").length > 1
            val attrFormatting = if (hasNestedSubgraphs || hasMultiLineHtmlLabel || hasComplexHtmlLabels) 
              formatAttributesMultiLine(nodeAttrs, level + 1, hasComplexHtmlLabels)
            else 
              formatAttributes(nodeAttrs)
            lines += s"""${padding(level + 1)}"${node.name}"$attrFormatting;"""
          }
        }
      }

      lines += s"${padding(level)}}"
    }

    // Process top-level clusters (those not referenced in any subgraphs field)
    val processedClusters = scala.collection.mutable.Set[Double]()
    val referencedSubgraphs = graph.objects.map(_.collect {
      case SimpleGraphObject.Cluster(cluster) => cluster.subgraphs.getOrElse(List.empty)
    }.flatten.toSet).getOrElse(Set.empty[Double])

    graph.objects.foreach { objectsList =>
      objectsList.foreach {
        case SimpleGraphObject.Cluster(cluster) =>
          // Only process as top-level if it's not referenced as a subgraph
          if (!referencedSubgraphs.contains(cluster._gvid)) {
            processCluster(cluster, 1, processedClusters)
          }
        case _ => // Skip nodes, we'll process them later
      }
    }

    // Add standalone nodes (not in clusters)
    val clusterNodeGvids = graph.objects.map(_.collect {
      case SimpleGraphObject.Cluster(cluster) => cluster.nodes.toSet
    }.flatten.toSet).getOrElse(Set.empty[Double])

    graph.objects.foreach { objectsList =>
      objectsList.foreach {
        case SimpleGraphObject.Node(node) =>
          if (!clusterNodeGvids.contains(node._gvid.toDouble)) {
            val nodeAttrs = collectNodeAttributes(node)
            val hasMultiLineHtmlLabel = isHtmlLabel(node.label) && node.label.trim.split("\n").length > 1
            val attrFormatting = if (hasNestedSubgraphs || hasMultiLineHtmlLabel || hasComplexHtmlLabels) 
              formatAttributesMultiLine(nodeAttrs, 1, hasComplexHtmlLabels)
            else 
              formatAttributes(nodeAttrs)
            lines += s"""${padding(1)}"${node.name}"$attrFormatting;"""
          }
        case _ => // Skip clusters
      }
    }

    // Add edges
    graph.edges.foreach { edgeArray =>
      edgeArray.foreach { edge =>
        // Find node names by gvid
        val tailName = graph.objects.flatMap(_.collectFirst {
          case SimpleGraphObject.Node(node) if node._gvid == edge.tail => node.name
        }).getOrElse(edge.tail.toString)

        val headName = graph.objects.flatMap(_.collectFirst {
          case SimpleGraphObject.Node(node) if node._gvid == edge.head => node.name
        }).getOrElse(edge.head.toString)

        val edgeAttrs = collectEdgeAttributes(edge)

        val tailPort = edge.tailport.map(p => s""":\"$p\"""").getOrElse("")
        val headPort = edge.headport.map(p => s""":\"$p\"""").getOrElse("")

        lines += s"""${padding(1)}"$tailName"$tailPort $edgeOp "$headName"$headPort${formatAttributes(edgeAttrs)};"""
      }
    }

    lines += "}"
    lines.mkString("\n")

  def getEdgePos(graph: SimpleGraph): Map[String, ArrowPosition] =
    val edgePositions = scala.collection.mutable.Map[String, ArrowPosition]()

    // Create a map from _gvid to name for node lookup
    val nodeMap = graph.objects.map(_.collect {
      case SimpleGraphObject.Node(node) => node._gvid -> node.name
    }.toMap).getOrElse(Map.empty)

    // Collect from main graph edges
    graph.edges.foreach { edgeArray =>
      edgeArray.foreach { edge =>
        edge.pos.foreach { pos =>
          ArrowPositionParser.parse(pos).foreach { arrowPos =>
            // Convert numeric gvids to names
            val tailName = nodeMap.getOrElse(edge.tail, edge.tail.toString)
            val headName = nodeMap.getOrElse(edge.head, edge.head.toString)

            val edgeId = edge.id match {
              case Some(id) =>
                // Try to parse as arrow ID with prefix, fall back to raw ID
                ArrowId.fromSvg(id).map(_.value).getOrElse(id)
              case None => s"$tailName->$headName"
            }
            edgePositions(edgeId) = arrowPos
          }
        }
      }
    }

    edgePositions.toMap
