package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings

import upickle.default.*

/** Represents a single node (or vertex) in the graph.
  */
case class SimpleGraphNode(
    _gvid:  Int,
    name:   String,
    label:  String,
    pos:    Option[String] = None,
    height: Option[String] = None,
    width:  Option[String] = None,
    shape:  Option[String] = None,
    // Optional styling and attribute properties
    fontname:  Option[String] = None,
    fontsize:  Option[String] = None,
    fontcolor: Option[String] = None,
    color:     Option[String] = None,
    fillcolor: Option[String] = None,
    style:     Option[String] = None,
    penwidth:  Option[String] = None,
    // Optional geometric or record-specific properties
    rects:       Option[String] = None,
    sides:       Option[String] = None,
    peripheries: Option[String] = None,
    fixedsize:   Option[String] = None, // 'true' | 'false' | 'shape'
    regular:     Option[String] = None, // 'true' | 'false'
    orientation: Option[String] = None,
    // Optional application-specific properties
    URL:  Option[String] = None,
    area: Option[String] = None,
    // Additional missing attributes
    `class`:     Option[String] = None,
    colorscheme: Option[String] = None,
    target:      Option[String] = None,
    tooltip:     Option[String] = None,
    vertices:    Option[String] = None,
    image:       Option[String] = None,
    imagepath:   Option[String] = None,
    imagepos:    Option[String] = None,
    margin:      Option[String] = None,
    nojustify:   Option[String] = None // 'true' | 'false'
) derives ReadWriter:

  /** Returns the unique identifier for this node, which is a string representation of its _gvid.
    */
  def id: String = s"node:$name"

/** Represents a logical grouping of nodes and edges, often rendered as a bounding box.
  */
case class SimpleGraphCluster(
    _gvid: Int,
    name:  String,
//    bb:        String,
    nodes:     Option[List[Int]] = None,
    label:     String,
    edges:     Option[List[Int]] = None,
    subgraphs: Option[List[Int]] = None,
    // Optional styling and layout properties
    fontname:  Option[String] = None,
    fontsize:  Option[String] = None,
    fontcolor: Option[String] = None,
    color:     Option[String] = None,
    pencolor:  Option[String] = None,
    penwidth:  Option[String] = None,
    bgcolor:   Option[String] = None,
    fillcolor: Option[String] = None,
    style:     Option[String] = None,
    labeljust: Option[String] = None, // 'c' | 'l' | 'r'
    labelloc:  Option[String] = None, // 't' | 'b' | 'c'
    lheight:   Option[String] = None,
    lp:        Option[String] = None,
    lwidth:    Option[String] = None,
    layout:    Option[String] = None,
    normalize: Option[String] = None, // string | number
    start:     Option[String] = None, // string | number
    overlap:   Option[String] = None,
    cluster:   Option[String] = None, // 'true'
    rankdir:   Option[String] = None,
    splines:   Option[String] = None,
    // Additional missing attributes
    target:      Option[String] = None,
    tooltip:     Option[String] = None,
    URL:         Option[String] = None,
    `class`:     Option[String] = None,
    colorscheme: Option[String] = None
) derives ReadWriter

/** Represents a connection (or edge) between two nodes in the graph.
  */
case class SimpleGraphEdge(
    _gvid: Int,
    tail:  Int,
    head:  Int,
    pos:   Option[String] = None,
    // Optional styling and attribute properties
    id:       Option[String] = None,
    label:    Option[String] = None,
    fontname: Option[String] = None,
    fontsize: Option[String] = None,
    color:    Option[String] = None,
    penwidth: Option[String] = None,
    style:    Option[String] = None,
    // Optional layout and positioning properties
    lp:          Option[String] = None,
    len:         Option[String] = None,
    constraint:  Option[String] = None, // 'true' | 'false'
    forcelabels: Option[String] = None, // 'true' | 'false'
    // Arrow and port related properties
    headport:  Option[String] = None,
    tailport:  Option[String] = None,
    arrowhead: Option[String] = None,
    arrowtail: Option[String] = None,
    arrowsize: Option[String] = None,
    dir:       Option[String] = None, // 'both' | 'forward' | 'back' | 'none'
    // Additional missing attributes
    `class`:        Option[String] = None,
    colorscheme:    Option[String] = None,
    layer:          Option[String] = None,
    nojustify:      Option[String] = None, // 'true' | 'false'
    samehead:       Option[String] = None,
    sametail:       Option[String] = None,
    showboxes:      Option[String] = None, // 'true' | 'false'
    tail_lp:        Option[String] = None,
    tailclip:       Option[String] = None, // 'true' | 'false'
    target:         Option[String] = None,
    tooltip:        Option[String] = None,
    labeldistance:  Option[String] = None,
    labelfloat:     Option[String] = None, // 'true' | 'false'
    labelfontcolor: Option[String] = None,
    labelfontname:  Option[String] = None,
    tailtarget:     Option[String] = None,
    tailtooltip:    Option[String] = None,
    tailURL:        Option[String] = None
) derives ReadWriter

/** A discriminated union type for any object that can appear in the 'objects' array. This represents either a SimpleGraphNode or
  * SimpleGraphCluster.
  */
enum SimpleGraphObject:
  case Node(node: SimpleGraphNode)
  case Cluster(cluster: SimpleGraphCluster)

object SimpleGraphObject:
  def fromNode(node: SimpleGraphNode): SimpleGraphObject = SimpleGraphObject.Node(node)

  def fromCluster(cluster: SimpleGraphCluster): SimpleGraphObject = SimpleGraphObject.Cluster(cluster)

  given ReadWriter[SimpleGraphObject] =
    readwriter[ujson.Value].bimap[SimpleGraphObject](
      {
        case Node(node)       => writeJs(node)
        case Cluster(cluster) => writeJs(cluster)
      },
      { jsValue =>
        // Distinguish between node and cluster based on the presence of specific fields
        // Clusters have a 'nodes' field, while nodes don't. Same for 'cluster' field.
        if jsValue.obj.contains("nodes") || jsValue.obj.contains("cluster") then
          Cluster(read[SimpleGraphCluster](jsValue))
        else
          Node(read[SimpleGraphNode](jsValue))
      }
    )

/** The root object of the graph data structure, encompassing all elements of the graph.
  *
  * Note that this representation doesn't allow default attributes (node[...] and edge[...]) to be set directly in the graph.
  */
case class SimpleGraph(
    name:     String,
    directed: Boolean = true,
//    strict:        Boolean = false,
//    bb:            String = "",
//    _subgraph_cnt: Int = 0.0,
    objects: Option[List[SimpleGraphObject]] = None,
    edges:   Option[List[SimpleGraphEdge]] = None,
    // Optional root graph properties
    fontname:  Option[String] = None,
    fontsize:  Option[String] = None,
    label:     Option[String] = None,
    labelloc:  Option[String] = None, // 't' | 'b' | 'c'
    lp:        Option[String] = None,
    lheight:   Option[String] = None,
    lwidth:    Option[String] = None,
    rankdir:   Option[String] = None,
    layout:    Option[String] = None,
    bgcolor:   Option[String] = None,
    nodesep:   Option[String] = None,
    pad:       Option[String] = None,
    ranksep:   Option[String] = None,
    ratio:     Option[String] = None,
    splines:   Option[String] = None,
    overlap:   Option[String] = None,
    normalize: Option[String] = None, // string | number
    start:     Option[String] = None, // string | number
    // Additional missing attributes
    beautify:           Option[String] = None, // 'true' | 'false'
    Damping:            Option[String] = None,
    defaultdist:        Option[String] = None,
    dim:                Option[String] = None,
    dimen:              Option[String] = None,
    diredgeconstraints: Option[String] = None, // 'true' | 'false' | 'hier'
    dpi:                Option[String] = None,
    epsilon:            Option[String] = None,
    esep:               Option[String] = None,
    fontnames:          Option[String] = None,
    fontpath:           Option[String] = None,
    K:                  Option[String] = None,
    label_scheme:       Option[String] = None, // 'true' | 'false'
    labeljust:          Option[String] = None, // 'c' | 'l' | 'r'
    landscape:          Option[String] = None, // 'true' | 'false'
    layerlistsep:       Option[String] = None,
    layers:             Option[String] = None,
    layerselect:        Option[String] = None,
    layersep:           Option[String] = None,
    nojustify:          Option[String] = None, // 'true' | 'false'
    notranslate:        Option[String] = None, // 'true' | 'false'
    target:             Option[String] = None,
    TBbalance:          Option[String] = None,
    tooltip:            Option[String] = None,
    truecolor:          Option[String] = None, // 'true' | 'false'
    URL:                Option[String] = None
) derives ReadWriter
