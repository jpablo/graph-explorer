package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph

import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.SimpleGraphObject.Node
import upickle.default.*

/** Captures JSON keys that are not part of a case class's schema, so custom
  * attributes (e.g. `mermaid_*`) survive a DOT re-parse instead of being
  * silently dropped by the fixed-field decode. The known-key set is derived
  * from the case class itself (Mirror), so schema and capture cannot drift.
  */
private object ExtraAttrs:
  // json0-only layout keys with no schema field: never user data, never captured
  private val layoutOnlyKeys = Set("bb", "xlp")

  inline def fieldNames[T](using m: scala.deriving.Mirror.ProductOf[T]): Set[String] =
    scala.compiletime.constValueTuple[m.MirroredElemLabels].toList.map(_.toString).toSet

  /** Unknown string-valued keys of `js` (non-strings are structural: arrays, _gvid, ...). */
  def capture(js: ujson.Value, knownKeys: Set[String]): Map[String, String] =
    js.obj.iterator.collect {
      case (k, ujson.Str(v)) if !knownKeys(k) && !layoutOnlyKeys(k) => k -> v
    }.toMap

  /** Inverse of capture: fold extras back into the JSON object as plain keys. */
  def merge(js: ujson.Value, extras: Map[String, String]): ujson.Value =
    js.obj.remove("extraAttrs")
    extras.foreach((k, v) => js.obj(k) = ujson.Str(v))
    js

  /** The whole extras protocol as one ReadWriter: write = base encode + fold extras back
    * as plain keys; read = base decode + capture unknown keys. One definition shared by
    * the four SimpleGraph* companions, so the capture/merge rules cannot drift apart.
    */
  inline def readWriterWithExtras[T](
      get: T => Map[String, String],
      set: (T, Map[String, String]) => T
  )(using scala.deriving.Mirror.ProductOf[T], scala.reflect.ClassTag[T]): ReadWriter[T] =
    val knownKeys           = fieldNames[T]
    val base: ReadWriter[T] = macroRW
    readwriter[ujson.Value].bimap(
      t => merge(writeJs(t)(using base), get(t)),
      js => set(read[T](js)(using base), capture(js, knownKeys))
    )

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
    nojustify:   Option[String] = None, // 'true' | 'false'
    // Any attribute not named above (custom mermaid_* metadata, ...)
    extraAttrs: Map[String, String] = Map.empty
):

  /** Returns the unique identifier for this node, which is a string representation of its _gvid.
    */
  def id: String = s"node:$name"

object SimpleGraphNode:
  given ReadWriter[SimpleGraphNode] =
    ExtraAttrs.readWriterWithExtras(_.extraAttrs, (n, e) => n.copy(extraAttrs = e))

/** Represents a logical grouping of nodes and edges, often rendered as a bounding box.
  */
case class SimpleGraphCluster(
    _gvid: Int,
    name:  String,
//    bb:        String,
    nodes:     Option[List[Int]] = None,
    label:     Option[String] = None,
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
    rank:      Option[String] = None, // Added rank attribute for clusters
    splines:   Option[String] = None,
    // Additional missing attributes
    target:      Option[String] = None,
    tooltip:     Option[String] = None,
    URL:         Option[String] = None,
    `class`:     Option[String] = None,
    colorscheme: Option[String] = None,
    // Any attribute not named above (custom mermaid_* metadata, ...)
    extraAttrs: Map[String, String] = Map.empty
)

object SimpleGraphCluster:
  given ReadWriter[SimpleGraphCluster] =
    ExtraAttrs.readWriterWithExtras(_.extraAttrs, (c, e) => c.copy(extraAttrs = e))

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
    tailURL:        Option[String] = None,
    // Any attribute not named above (custom mermaid_* metadata, ...)
    extraAttrs: Map[String, String] = Map.empty
)

object SimpleGraphEdge:
  given ReadWriter[SimpleGraphEdge] =
    ExtraAttrs.readWriterWithExtras(_.extraAttrs, (ed, e) => ed.copy(extraAttrs = e))

/** A discriminated union type for any object that can appear in the 'objects' array. This represents either a SimpleGraphNode or
  * SimpleGraphCluster.
  */
enum SimpleGraphObject:
  case Node(node: SimpleGraphNode)
  case Cluster(cluster: SimpleGraphCluster)

object SimpleGraphObject:

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
  * This is the structure created by Viz.js when using the "json0" output format.
  *
  * viz.renderFormats(dotText, js.Array("json0"))
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
    URL:                Option[String] = None,
    // Any attribute not named above (custom mermaid_* metadata, ...)
    extraAttrs: Map[String, String] = Map.empty
):
  def nodes: List[SimpleGraphNode] =
    objects match
      case Some(objs) => objs.collect { case Node(n) => n }
      case None       => Nil

object SimpleGraph:
  val minimal = SimpleGraph("G")

  given ReadWriter[SimpleGraph] =
    ExtraAttrs.readWriterWithExtras(_.extraAttrs, (g, e) => g.copy(extraAttrs = e))
