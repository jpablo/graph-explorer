package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typingsNative

import scala.scalajs.js

/** Represents a single node (or vertex) in the graph.
  */
@js.native
trait SimpleGraphNode extends js.Object:
  val _gvid: Int                 = js.native
  val name: String               = js.native
  val label: String              = js.native
  val pos: js.UndefOr[String]    = js.native
  val height: js.UndefOr[String] = js.native
  val width: js.UndefOr[String]  = js.native
  val shape: js.UndefOr[String]  = js.native

  // Optional styling and attribute properties
  val fontname: js.UndefOr[String]  = js.native
  val fontsize: js.UndefOr[String]  = js.native
  val fontcolor: js.UndefOr[String] = js.native
  val color: js.UndefOr[String]     = js.native
  val fillcolor: js.UndefOr[String] = js.native
  val style: js.UndefOr[String]     = js.native
  val penwidth: js.UndefOr[String]  = js.native

  // Optional geometric or record-specific properties
  val rects: js.UndefOr[String]       = js.native
  val sides: js.UndefOr[String]       = js.native
  val peripheries: js.UndefOr[String] = js.native
  val fixedsize: js.UndefOr[String]   = js.native // 'true' | 'false' | 'shape'
  val regular: js.UndefOr[String]     = js.native // 'true' | 'false'
  val orientation: js.UndefOr[String] = js.native

  // Optional application-specific properties
  val URL: js.UndefOr[String]  = js.native
  val area: js.UndefOr[String] = js.native

  // Additional missing attributes
  val `class`: js.UndefOr[String]     = js.native
  val colorscheme: js.UndefOr[String] = js.native
  val target: js.UndefOr[String]      = js.native
  val tooltip: js.UndefOr[String]     = js.native
  val vertices: js.UndefOr[String]    = js.native
  val image: js.UndefOr[String]       = js.native
  val imagepath: js.UndefOr[String]   = js.native
  val imagepos: js.UndefOr[String]    = js.native
  val margin: js.UndefOr[String]      = js.native
  val nojustify: js.UndefOr[String]   = js.native // 'true' | 'false'

object SimpleGraphNode:
  def apply(
      _gvid:       Int,
      name:        String,
      label:       String = "\\N",
      pos:         js.UndefOr[String] = js.undefined,
      height:      js.UndefOr[String] = js.undefined,
      width:       js.UndefOr[String] = js.undefined,
      shape:       js.UndefOr[String] = js.undefined,
      fontname:    js.UndefOr[String] = js.undefined,
      fontsize:    js.UndefOr[String] = js.undefined,
      fontcolor:   js.UndefOr[String] = js.undefined,
      color:       js.UndefOr[String] = js.undefined,
      fillcolor:   js.UndefOr[String] = js.undefined,
      style:       js.UndefOr[String] = js.undefined,
      penwidth:    js.UndefOr[String] = js.undefined,
      rects:       js.UndefOr[String] = js.undefined,
      sides:       js.UndefOr[String] = js.undefined,
      peripheries: js.UndefOr[String] = js.undefined,
      fixedsize:   js.UndefOr[String] = js.undefined,
      regular:     js.UndefOr[String] = js.undefined,
      orientation: js.UndefOr[String] = js.undefined,
      URL:         js.UndefOr[String] = js.undefined,
      area:        js.UndefOr[String] = js.undefined,
      `class`:     js.UndefOr[String] = js.undefined,
      colorscheme: js.UndefOr[String] = js.undefined,
      target:      js.UndefOr[String] = js.undefined,
      tooltip:     js.UndefOr[String] = js.undefined,
      vertices:    js.UndefOr[String] = js.undefined,
      image:       js.UndefOr[String] = js.undefined,
      imagepath:   js.UndefOr[String] = js.undefined,
      imagepos:    js.UndefOr[String] = js.undefined,
      margin:      js.UndefOr[String] = js.undefined,
      nojustify:   js.UndefOr[String] = js.undefined
  ): SimpleGraphNode =
    val obj = js.Dynamic.literal(
      _gvid = _gvid,
      name = name,
      pos = pos,
      height = height,
      width = width,
      label = label
    )
    shape.foreach(obj.updateDynamic("shape")(_))
    fontname.foreach(obj.updateDynamic("fontname")(_))
    fontsize.foreach(obj.updateDynamic("fontsize")(_))
    fontcolor.foreach(obj.updateDynamic("fontcolor")(_))
    color.foreach(obj.updateDynamic("color")(_))
    fillcolor.foreach(obj.updateDynamic("fillcolor")(_))
    style.foreach(obj.updateDynamic("style")(_))
    penwidth.foreach(obj.updateDynamic("penwidth")(_))
    rects.foreach(obj.updateDynamic("rects")(_))
    sides.foreach(obj.updateDynamic("sides")(_))
    peripheries.foreach(obj.updateDynamic("peripheries")(_))
    fixedsize.foreach(obj.updateDynamic("fixedsize")(_))
    regular.foreach(obj.updateDynamic("regular")(_))
    orientation.foreach(obj.updateDynamic("orientation")(_))
    URL.foreach(obj.updateDynamic("URL")(_))
    area.foreach(obj.updateDynamic("area")(_))
    `class`.foreach(obj.updateDynamic("class")(_))
    colorscheme.foreach(obj.updateDynamic("colorscheme")(_))
    target.foreach(obj.updateDynamic("target")(_))
    tooltip.foreach(obj.updateDynamic("tooltip")(_))
    vertices.foreach(obj.updateDynamic("vertices")(_))
    image.foreach(obj.updateDynamic("image")(_))
    imagepath.foreach(obj.updateDynamic("imagepath")(_))
    imagepos.foreach(obj.updateDynamic("imagepos")(_))
    margin.foreach(obj.updateDynamic("margin")(_))
    nojustify.foreach(obj.updateDynamic("nojustify")(_))
    obj.asInstanceOf[SimpleGraphNode]

/** Represents a logical grouping of nodes and edges, often rendered as a bounding box.
  */
@js.native
trait SimpleGraphCluster extends js.Object:
  val _gvid: Double                           = js.native
  val name: String                            = js.native
  val bb: String                              = js.native
  val nodes: js.Array[Double]                 = js.native
  val edges: js.UndefOr[js.Array[Double]]     = js.native
  val subgraphs: js.UndefOr[js.Array[Double]] = js.native
  val label: String                           = js.native

  // Optional styling and layout properties
  val fontname: js.UndefOr[String]  = js.native
  val color: js.UndefOr[String]     = js.native
  val bgcolor: js.UndefOr[String]   = js.native
  val style: js.UndefOr[String]     = js.native
  val labeljust: js.UndefOr[String] = js.native // 'c' | 'l' | 'r'
  val labelloc: js.UndefOr[String]  = js.native // 't' | 'b' | 'c'
  val lheight: js.UndefOr[String]   = js.native
  val lp: js.UndefOr[String]        = js.native
  val lwidth: js.UndefOr[String]    = js.native
  val layout: js.UndefOr[String]    = js.native
  val normalize: js.UndefOr[String] = js.native // string | number
  val start: js.UndefOr[String]     = js.native // string | number
  val overlap: js.UndefOr[String]   = js.native
  val cluster: js.UndefOr[String]   = js.native // 'true'
  val rankdir: js.UndefOr[String]   = js.native
  val splines: js.UndefOr[String]   = js.native

  // Additional missing attributes
  val target: js.UndefOr[String]      = js.native
  val tooltip: js.UndefOr[String]     = js.native
  val URL: js.UndefOr[String]         = js.native
  val `class`: js.UndefOr[String]     = js.native
  val colorscheme: js.UndefOr[String] = js.native

object SimpleGraphCluster:
  def apply(
      _gvid:       Double,
      name:        String,
      bb:          String,
      nodes:       js.Array[Double],
      label:       String,
      edges:       js.UndefOr[js.Array[Double]] = js.undefined,
      subgraphs:   js.UndefOr[js.Array[Double]] = js.undefined,
      fontname:    js.UndefOr[String] = js.undefined,
      color:       js.UndefOr[String] = js.undefined,
      bgcolor:     js.UndefOr[String] = js.undefined,
      style:       js.UndefOr[String] = js.undefined,
      labeljust:   js.UndefOr[String] = js.undefined,
      labelloc:    js.UndefOr[String] = js.undefined,
      lheight:     js.UndefOr[String] = js.undefined,
      lp:          js.UndefOr[String] = js.undefined,
      lwidth:      js.UndefOr[String] = js.undefined,
      layout:      js.UndefOr[String] = js.undefined,
      normalize:   js.UndefOr[String] = js.undefined,
      start:       js.UndefOr[String] = js.undefined,
      overlap:     js.UndefOr[String] = js.undefined,
      cluster:     js.UndefOr[String] = js.undefined,
      rankdir:     js.UndefOr[String] = js.undefined,
      splines:     js.UndefOr[String] = js.undefined,
      target:      js.UndefOr[String] = js.undefined,
      tooltip:     js.UndefOr[String] = js.undefined,
      URL:         js.UndefOr[String] = js.undefined,
      `class`:     js.UndefOr[String] = js.undefined,
      colorscheme: js.UndefOr[String] = js.undefined
  ): SimpleGraphCluster =
    val obj = js.Dynamic.literal(
      _gvid = _gvid,
      name = name,
      bb = bb,
      nodes = nodes,
      label = label
    )
    edges.foreach(obj.updateDynamic("edges")(_))
    subgraphs.foreach(obj.updateDynamic("subgraphs")(_))
    fontname.foreach(obj.updateDynamic("fontname")(_))
    color.foreach(obj.updateDynamic("color")(_))
    bgcolor.foreach(obj.updateDynamic("bgcolor")(_))
    style.foreach(obj.updateDynamic("style")(_))
    labeljust.foreach(obj.updateDynamic("labeljust")(_))
    labelloc.foreach(obj.updateDynamic("labelloc")(_))
    lheight.foreach(obj.updateDynamic("lheight")(_))
    lp.foreach(obj.updateDynamic("lp")(_))
    lwidth.foreach(obj.updateDynamic("lwidth")(_))
    layout.foreach(obj.updateDynamic("layout")(_))
    normalize.foreach(obj.updateDynamic("normalize")(_))
    start.foreach(obj.updateDynamic("start")(_))
    overlap.foreach(obj.updateDynamic("overlap")(_))
    cluster.foreach(obj.updateDynamic("cluster")(_))
    rankdir.foreach(obj.updateDynamic("rankdir")(_))
    splines.foreach(obj.updateDynamic("splines")(_))
    target.foreach(obj.updateDynamic("target")(_))
    tooltip.foreach(obj.updateDynamic("tooltip")(_))
    URL.foreach(obj.updateDynamic("URL")(_))
    `class`.foreach(obj.updateDynamic("class")(_))
    colorscheme.foreach(obj.updateDynamic("colorscheme")(_))
    obj.asInstanceOf[SimpleGraphCluster]

/** Represents a connection (or edge) between two nodes in the graph.
  */
@js.native
trait SimpleGraphEdge extends js.Object:
  val _gvid: Int              = js.native
  val tail: Int               = js.native
  val head: Int               = js.native
  val pos: js.UndefOr[String] = js.native

  // Optional styling and attribute properties
  val id: js.UndefOr[String]       = js.native
  val label: js.UndefOr[String]    = js.native
  val fontname: js.UndefOr[String] = js.native
  val fontsize: js.UndefOr[String] = js.native
  val color: js.UndefOr[String]    = js.native
  val penwidth: js.UndefOr[String] = js.native
  val style: js.UndefOr[String]    = js.native

  // Optional layout and positioning properties
  val lp: js.UndefOr[String]          = js.native
  val len: js.UndefOr[String]         = js.native
  val constraint: js.UndefOr[String]  = js.native // 'true' | 'false'
  val forcelabels: js.UndefOr[String] = js.native // 'true' | 'false'

  // Arrow and port related properties
  val headport: js.UndefOr[String]  = js.native
  val tailport: js.UndefOr[String]  = js.native
  val arrowhead: js.UndefOr[String] = js.native
  val arrowtail: js.UndefOr[String] = js.native
  val arrowsize: js.UndefOr[String] = js.native
  val dir: js.UndefOr[String]       = js.native // 'both' | 'forward' | 'back' | 'none'

  // Additional missing attributes
  val `class`: js.UndefOr[String]        = js.native
  val colorscheme: js.UndefOr[String]    = js.native
  val layer: js.UndefOr[String]          = js.native
  val nojustify: js.UndefOr[String]      = js.native // 'true' | 'false'
  val samehead: js.UndefOr[String]       = js.native
  val sametail: js.UndefOr[String]       = js.native
  val showboxes: js.UndefOr[String]      = js.native // 'true' | 'false'
  val tail_lp: js.UndefOr[String]        = js.native
  val tailclip: js.UndefOr[String]       = js.native // 'true' | 'false'
  val target: js.UndefOr[String]         = js.native
  val tooltip: js.UndefOr[String]        = js.native
  val labeldistance: js.UndefOr[String]  = js.native
  val labelfloat: js.UndefOr[String]     = js.native // 'true' | 'false'
  val labelfontcolor: js.UndefOr[String] = js.native
  val labelfontname: js.UndefOr[String]  = js.native
  val tailtarget: js.UndefOr[String]     = js.native
  val tailtooltip: js.UndefOr[String]    = js.native
  val tailURL: js.UndefOr[String]        = js.native

object SimpleGraphEdge:
  def apply(
      _gvid:          Int,
      tail:           Int,
      head:           Int,
      pos:            js.UndefOr[String] = js.undefined,
      id:             js.UndefOr[String] = js.undefined,
      label:          js.UndefOr[String] = js.undefined,
      fontname:       js.UndefOr[String] = js.undefined,
      fontsize:       js.UndefOr[String] = js.undefined,
      color:          js.UndefOr[String] = js.undefined,
      penwidth:       js.UndefOr[String] = js.undefined,
      style:          js.UndefOr[String] = js.undefined,
      lp:             js.UndefOr[String] = js.undefined,
      len:            js.UndefOr[String] = js.undefined,
      constraint:     js.UndefOr[String] = js.undefined,
      forcelabels:    js.UndefOr[String] = js.undefined,
      headport:       js.UndefOr[String] = js.undefined,
      tailport:       js.UndefOr[String] = js.undefined,
      arrowhead:      js.UndefOr[String] = js.undefined,
      arrowtail:      js.UndefOr[String] = js.undefined,
      arrowsize:      js.UndefOr[String] = js.undefined,
      dir:            js.UndefOr[String] = js.undefined,
      `class`:        js.UndefOr[String] = js.undefined,
      colorscheme:    js.UndefOr[String] = js.undefined,
      layer:          js.UndefOr[String] = js.undefined,
      nojustify:      js.UndefOr[String] = js.undefined,
      samehead:       js.UndefOr[String] = js.undefined,
      sametail:       js.UndefOr[String] = js.undefined,
      showboxes:      js.UndefOr[String] = js.undefined,
      tail_lp:        js.UndefOr[String] = js.undefined,
      tailclip:       js.UndefOr[String] = js.undefined,
      target:         js.UndefOr[String] = js.undefined,
      tooltip:        js.UndefOr[String] = js.undefined,
      labeldistance:  js.UndefOr[String] = js.undefined,
      labelfloat:     js.UndefOr[String] = js.undefined,
      labelfontcolor: js.UndefOr[String] = js.undefined,
      labelfontname:  js.UndefOr[String] = js.undefined,
      tailtarget:     js.UndefOr[String] = js.undefined,
      tailtooltip:    js.UndefOr[String] = js.undefined,
      tailURL:        js.UndefOr[String] = js.undefined
  ): SimpleGraphEdge =
    val obj = js.Dynamic.literal(
      _gvid = _gvid,
      tail = tail,
      head = head
    )
    pos.foreach(obj.updateDynamic("pos")(_))
    id.foreach(obj.updateDynamic("id")(_))
    label.foreach(obj.updateDynamic("label")(_))
    fontname.foreach(obj.updateDynamic("fontname")(_))
    fontsize.foreach(obj.updateDynamic("fontsize")(_))
    color.foreach(obj.updateDynamic("color")(_))
    penwidth.foreach(obj.updateDynamic("penwidth")(_))
    style.foreach(obj.updateDynamic("style")(_))
    lp.foreach(obj.updateDynamic("lp")(_))
    len.foreach(obj.updateDynamic("len")(_))
    constraint.foreach(obj.updateDynamic("constraint")(_))
    forcelabels.foreach(obj.updateDynamic("forcelabels")(_))
    headport.foreach(obj.updateDynamic("headport")(_))
    tailport.foreach(obj.updateDynamic("tailport")(_))
    arrowhead.foreach(obj.updateDynamic("arrowhead")(_))
    arrowtail.foreach(obj.updateDynamic("arrowtail")(_))
    arrowsize.foreach(obj.updateDynamic("arrowsize")(_))
    dir.foreach(obj.updateDynamic("dir")(_))
    `class`.foreach(obj.updateDynamic("class")(_))
    colorscheme.foreach(obj.updateDynamic("colorscheme")(_))
    layer.foreach(obj.updateDynamic("layer")(_))
    nojustify.foreach(obj.updateDynamic("nojustify")(_))
    samehead.foreach(obj.updateDynamic("samehead")(_))
    sametail.foreach(obj.updateDynamic("sametail")(_))
    showboxes.foreach(obj.updateDynamic("showboxes")(_))
    tail_lp.foreach(obj.updateDynamic("tail_lp")(_))
    tailclip.foreach(obj.updateDynamic("tailclip")(_))
    target.foreach(obj.updateDynamic("target")(_))
    tooltip.foreach(obj.updateDynamic("tooltip")(_))
    labeldistance.foreach(obj.updateDynamic("labeldistance")(_))
    labelfloat.foreach(obj.updateDynamic("labelfloat")(_))
    labelfontcolor.foreach(obj.updateDynamic("labelfontcolor")(_))
    labelfontname.foreach(obj.updateDynamic("labelfontname")(_))
    tailtarget.foreach(obj.updateDynamic("tailtarget")(_))
    tailtooltip.foreach(obj.updateDynamic("tailtooltip")(_))
    tailURL.foreach(obj.updateDynamic("tailURL")(_))
    obj.asInstanceOf[SimpleGraphEdge]

/** The root object of the graph data structure, encompassing all elements of the graph.
  */
@js.native
trait SimpleGraph extends js.Object:
  val name: String                                     = js.native
  val directed: Boolean                                = js.native
  val strict: Boolean                                  = js.native
  val bb: String                                       = js.native
  val _subgraph_cnt: Double                            = js.native
  val objects: js.UndefOr[js.Array[SimpleGraphObject]] = js.native
  val edges: js.UndefOr[js.Array[SimpleGraphEdge]]     = js.native

  // Optional root graph properties
  val fontname: js.UndefOr[String]  = js.native
  val fontsize: js.UndefOr[String]  = js.native
  val label: js.UndefOr[String]     = js.native
  val labelloc: js.UndefOr[String]  = js.native // 't' | 'b' | 'c'
  val lp: js.UndefOr[String]        = js.native
  val lheight: js.UndefOr[String]   = js.native
  val lwidth: js.UndefOr[String]    = js.native
  val rankdir: js.UndefOr[String]   = js.native
  val layout: js.UndefOr[String]    = js.native
  val bgcolor: js.UndefOr[String]   = js.native
  val nodesep: js.UndefOr[String]   = js.native
  val pad: js.UndefOr[String]       = js.native
  val ranksep: js.UndefOr[String]   = js.native
  val ratio: js.UndefOr[String]     = js.native
  val splines: js.UndefOr[String]   = js.native
  val overlap: js.UndefOr[String]   = js.native
  val normalize: js.UndefOr[String] = js.native // string | number
  val start: js.UndefOr[String]     = js.native // string | number

  // Additional missing attributes
  val beautify: js.UndefOr[String]           = js.native // 'true' | 'false'
  val Damping: js.UndefOr[String]            = js.native
  val defaultdist: js.UndefOr[String]        = js.native
  val dim: js.UndefOr[String]                = js.native
  val dimen: js.UndefOr[String]              = js.native
  val diredgeconstraints: js.UndefOr[String] = js.native // 'true' | 'false' | 'hier'
  val dpi: js.UndefOr[String]                = js.native
  val epsilon: js.UndefOr[String]            = js.native
  val esep: js.UndefOr[String]               = js.native
  val fontnames: js.UndefOr[String]          = js.native
  val fontpath: js.UndefOr[String]           = js.native
  val K: js.UndefOr[String]                  = js.native
  val label_scheme: js.UndefOr[String]       = js.native // 'true' | 'false'
  val labeljust: js.UndefOr[String]          = js.native // 'c' | 'l' | 'r'
  val landscape: js.UndefOr[String]          = js.native // 'true' | 'false'
  val layerlistsep: js.UndefOr[String]       = js.native
  val layers: js.UndefOr[String]             = js.native
  val layerselect: js.UndefOr[String]        = js.native
  val layersep: js.UndefOr[String]           = js.native
  val nojustify: js.UndefOr[String]          = js.native // 'true' | 'false'
  val notranslate: js.UndefOr[String]        = js.native // 'true' | 'false'
  val target: js.UndefOr[String]             = js.native
  val TBbalance: js.UndefOr[String]          = js.native
  val tooltip: js.UndefOr[String]            = js.native
  val truecolor: js.UndefOr[String]          = js.native // 'true' | 'false'
  val URL: js.UndefOr[String]                = js.native

object SimpleGraph:
  def apply(
      name:               String,
      directed:           Boolean = true,
      strict:             Boolean = false,
      bb:                 String = "0,0,0,0",
      _subgraph_cnt:      Double = 0.0,
      objects:            js.Array[SimpleGraphObject] = js.Array(),
      edges:              js.Array[SimpleGraphEdge] = js.Array(),
      fontname:           js.UndefOr[String] = js.undefined,
      fontsize:           js.UndefOr[String] = js.undefined,
      label:              js.UndefOr[String] = js.undefined,
      labelloc:           js.UndefOr[String] = js.undefined,
      lp:                 js.UndefOr[String] = js.undefined,
      lheight:            js.UndefOr[String] = js.undefined,
      lwidth:             js.UndefOr[String] = js.undefined,
      rankdir:            js.UndefOr[String] = js.undefined,
      layout:             js.UndefOr[String] = js.undefined,
      bgcolor:            js.UndefOr[String] = js.undefined,
      nodesep:            js.UndefOr[String] = js.undefined,
      pad:                js.UndefOr[String] = js.undefined,
      ranksep:            js.UndefOr[String] = js.undefined,
      ratio:              js.UndefOr[String] = js.undefined,
      splines:            js.UndefOr[String] = js.undefined,
      overlap:            js.UndefOr[String] = js.undefined,
      normalize:          js.UndefOr[String] = js.undefined,
      start:              js.UndefOr[String] = js.undefined,
      beautify:           js.UndefOr[String] = js.undefined,
      Damping:            js.UndefOr[String] = js.undefined,
      defaultdist:        js.UndefOr[String] = js.undefined,
      dim:                js.UndefOr[String] = js.undefined,
      dimen:              js.UndefOr[String] = js.undefined,
      diredgeconstraints: js.UndefOr[String] = js.undefined,
      dpi:                js.UndefOr[String] = js.undefined,
      epsilon:            js.UndefOr[String] = js.undefined,
      esep:               js.UndefOr[String] = js.undefined,
      fontnames:          js.UndefOr[String] = js.undefined,
      fontpath:           js.UndefOr[String] = js.undefined,
      K:                  js.UndefOr[String] = js.undefined,
      label_scheme:       js.UndefOr[String] = js.undefined,
      labeljust:          js.UndefOr[String] = js.undefined,
      landscape:          js.UndefOr[String] = js.undefined,
      layerlistsep:       js.UndefOr[String] = js.undefined,
      layers:             js.UndefOr[String] = js.undefined,
      layerselect:        js.UndefOr[String] = js.undefined,
      layersep:           js.UndefOr[String] = js.undefined,
      nojustify:          js.UndefOr[String] = js.undefined,
      notranslate:        js.UndefOr[String] = js.undefined,
      target:             js.UndefOr[String] = js.undefined,
      TBbalance:          js.UndefOr[String] = js.undefined,
      tooltip:            js.UndefOr[String] = js.undefined,
      truecolor:          js.UndefOr[String] = js.undefined,
      URL:                js.UndefOr[String] = js.undefined
  ): SimpleGraph =
    val obj = js.Dynamic.literal(
      name = name,
      directed = directed,
      strict = strict,
      bb = bb,
      _subgraph_cnt = _subgraph_cnt,
      objects = objects,
      edges = edges
    )
    fontname.foreach(obj.updateDynamic("fontname")(_))
    fontsize.foreach(obj.updateDynamic("fontsize")(_))
    label.foreach(obj.updateDynamic("label")(_))
    labelloc.foreach(obj.updateDynamic("labelloc")(_))
    lp.foreach(obj.updateDynamic("lp")(_))
    lheight.foreach(obj.updateDynamic("lheight")(_))
    lwidth.foreach(obj.updateDynamic("lwidth")(_))
    rankdir.foreach(obj.updateDynamic("rankdir")(_))
    layout.foreach(obj.updateDynamic("layout")(_))
    bgcolor.foreach(obj.updateDynamic("bgcolor")(_))
    nodesep.foreach(obj.updateDynamic("nodesep")(_))
    pad.foreach(obj.updateDynamic("pad")(_))
    ranksep.foreach(obj.updateDynamic("ranksep")(_))
    ratio.foreach(obj.updateDynamic("ratio")(_))
    splines.foreach(obj.updateDynamic("splines")(_))
    overlap.foreach(obj.updateDynamic("overlap")(_))
    normalize.foreach(obj.updateDynamic("normalize")(_))
    start.foreach(obj.updateDynamic("start")(_))
    beautify.foreach(obj.updateDynamic("beautify")(_))
    Damping.foreach(obj.updateDynamic("Damping")(_))
    defaultdist.foreach(obj.updateDynamic("defaultdist")(_))
    dim.foreach(obj.updateDynamic("dim")(_))
    dimen.foreach(obj.updateDynamic("dimen")(_))
    diredgeconstraints.foreach(obj.updateDynamic("diredgeconstraints")(_))
    dpi.foreach(obj.updateDynamic("dpi")(_))
    epsilon.foreach(obj.updateDynamic("epsilon")(_))
    esep.foreach(obj.updateDynamic("esep")(_))
    fontnames.foreach(obj.updateDynamic("fontnames")(_))
    fontpath.foreach(obj.updateDynamic("fontpath")(_))
    K.foreach(obj.updateDynamic("K")(_))
    label_scheme.foreach(obj.updateDynamic("label_scheme")(_))
    labeljust.foreach(obj.updateDynamic("labeljust")(_))
    landscape.foreach(obj.updateDynamic("landscape")(_))
    layerlistsep.foreach(obj.updateDynamic("layerlistsep")(_))
    layers.foreach(obj.updateDynamic("layers")(_))
    layerselect.foreach(obj.updateDynamic("layerselect")(_))
    layersep.foreach(obj.updateDynamic("layersep")(_))
    nojustify.foreach(obj.updateDynamic("nojustify")(_))
    notranslate.foreach(obj.updateDynamic("notranslate")(_))
    target.foreach(obj.updateDynamic("target")(_))
    TBbalance.foreach(obj.updateDynamic("TBbalance")(_))
    tooltip.foreach(obj.updateDynamic("tooltip")(_))
    truecolor.foreach(obj.updateDynamic("truecolor")(_))
    URL.foreach(obj.updateDynamic("URL")(_))
    obj.asInstanceOf[SimpleGraph]

/** A discriminated union type for any object that can appear in the 'objects' array. This represents either a SimpleGraphNode or
  * SimpleGraphCluster.
  */
type SimpleGraphObject = SimpleGraphNode | SimpleGraphCluster
