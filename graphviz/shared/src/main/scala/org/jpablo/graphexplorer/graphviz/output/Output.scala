package org.jpablo.graphexplorer.graphviz.output

import org.jpablo.graphexplorer.graphviz.model.RGraph
import org.jpablo.graphexplorer.graphviz.layout.{Coord, NodeSize, Spline, XCoord}
import org.jpablo.graphexplorer.graphviz.units.Length.Pt

/** Phase 5 of the `dot` pipeline: output writers (increment 1 — `dot_json`
  * and `json0`).
  *
  * Hand-rolled JSON string builders (no serialization dependency — shared
  * sources stay platform-neutral, PORT.md §3). The functional contract
  * (PORT.md §1) is to match `@viz-js/viz`'s output **strings** closely
  * enough that the viewer's `read[SimpleGraph]` / `getEdgePos` consume them
  * identically; gated structurally against the captured goldens.
  *
  *  - `dot_json`: structure only — `name`/`directed`/`strict`/`bb` (space-
  *    separated)/`_subgraph_cnt`/`objects`(`_gvid`,`name`,`label`)/`edges`
  *    (`_gvid`,`tail`,`head`).
  *  - `json0`: adds the computed layout — node `pos`/`width`/`height`, edge
  *    `pos` spline string (`e,EX,EY ` prefix iff a head arrow is drawn),
  *    `bb` comma-separated.
  *
  * Scope: label-free TB (01/06/07); records/clusters/edge-labels are their
  * own tracked deferrals (PORT.md §5).
  */
object Output:

  /** Graphviz number format ≈ C `printf("%.5g")`: 5 significant digits,
    * trailing zeros and a trailing point trimmed, no exponent in our range. */
  private[output] def g5(x0: Double): String =
    val x = if x0 == 0.0 then 0.0 else x0 // normalise -0.0
    if x == 0.0 then "0"
    else
      val neg = x < 0
      val a   = math.abs(x)
      val exp = math.floor(math.log10(a)).toInt
      val dec = math.max(0, 4 - exp) // 5 sig figs
      val bd  = BigDecimal(a).setScale(dec, BigDecimal.RoundingMode.HALF_UP)
      var s   = bd.bigDecimal.toPlainString
      if s.contains('.') then
        s = s.reverse.dropWhile(_ == '0').dropWhile(_ == '.').reverse
      if neg && s != "0" then "-" + s else s

  private def esc(s: String): String =
    val b = new StringBuilder
    s.foreach {
      case '"'  => b ++= "\\\""
      case '\\' => b ++= "\\\\"
      case '/'  => b ++= "\\/" // gv `stoj` escapes forward slash (json.c)
      case '\n' => b ++= "\\n"
      case '\r' => b ++= "\\r"
      case '\t' => b ++= "\\t"
      case c    => b += c
    }
    b.toString

  /** Object attributes as gv `write_attrs` emits them: **alphabetical** by
    * name, skipping empty values *except* `label` (which always prints). */
  private def attrPairs(attrs: Map[String, String]): Vector[(String, String)] =
    attrs.toVector.filter { case (k, v) => v.nonEmpty || k == "label" }.sortBy(_._1)

  private val Gap         = 4.0  // const.h GAP (YPAD = 2*GAP)
  private val DefFontSize = 14.0

  // ── root graph label (`do_graph_label`) ─────────────────────────────────
  private def rootLabelText(g: RGraph): Option[String] = g.rootAttrs.get("label").filter(_.nonEmpty)
  private def labelFontSize(g: RGraph): Double =
    g.rootAttrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(DefFontSize)
  private def labelTop(g: RGraph): Boolean = g.rootAttrs.get("labelloc").exists(_.startsWith("t"))
  private def labelHtPt(g: RGraph, lbl: String): Double =
    NodeSize.labelHeightPt(lbl, labelFontSize(g), g.name.getOrElse(""))
  /** Reserved rank-axis space = label box + YPAD (2*GAP); 0 with no label. */
  private def gLabelPad(g: RGraph): Double =
    rootLabelText(g).map(l => labelHtPt(g, l) + 2.0 * Gap).getOrElse(0.0)
  /** C `printf("%.2f")` — the `lwidth`/`lheight`/`lp`-dimension format. */
  private def f2(x: Double): String = BigDecimal(x).setScale(2, BigDecimal.RoundingMode.HALF_UP).toString

  /** Layout-independent bits shared by both formats. */
  private final case class Doc(
      name:       String,
      directed:   Boolean,
      strict:     Boolean,
      hasCluster: Boolean,                      // any cluster ⇒ viz-js bails on layout
      rootAttrs:  Vector[(String, String)],     // root graph attrs, write_attrs-filtered
      sgCnt:      Int,                           // _subgraph_cnt
      subgraphs:  Vector[Doc.SG],                // preorder, gvid = 0..sgCnt-1
      nodes:      Vector[(String, Int)],         // id → _gvid (offset by sgCnt)
      edges:      Vector[Doc.E]                  // top-level array, AGSEQ order
  )
  private object Doc:
    // _gvid, tailGvid, headGvid, tailport, headport, g.edges index
    final case class E(gv: Int, t: Int, h: Int, tp: Option[String], hp: Option[String], idx: Int)
    // a subgraph object: gvid, resolved name, label, rank, member-node gvids,
    // member-edge gvids (both already ownership-filtered + ordered). emitLabel
    // = whether `label` is a declared graph attr (write_attrs prints empty
    // `label` only then — so 03's cluster-declared label reaches %7, but 11's
    // rank-only subgraph omits it).
    final case class SG(gvid: Int, name: String, label: String, rank: Option[String],
        nodeGvids: Vector[Int], edgeGvids: Vector[Int], emitLabel: Boolean)

  private def doc(g: RGraph): Doc =
    import org.jpablo.graphexplorer.graphviz.model.RSubgraph
    // Preorder DFS over the subgraph tree = cgraph `label_subgs` order: each
    // subgraph gets `_gvid` 0..sgCnt-1; real-node `_gvid` is then offset by
    // sgCnt (write_graph: `ND_gid = sgcnt + ncnt++`).
    val sgFlat = Vector.newBuilder[RSubgraph]
    def preorder(s: RSubgraph): Unit = { sgFlat += s; s.children.foreach(preorder) }
    g.subgraphs.foreach(preorder)
    val flat  = sgFlat.result()
    val sgCnt = flat.size

    val nodeIdx  = g.nodes.iterator.map(_.id).zipWithIndex.toMap
    def nodeGvid(id: String): Int = sgCnt + nodeIdx(id)
    val nodes = g.nodes.map(n => n.id -> nodeGvid(n.id))

    // Edge _gvid = cgraph node-traversal order (write_graph): for each node
    // (declaration order), its out-edges in declaration order. `idx` = position
    // in `g.edges` (= AGSEQ = the Spline key) so parallel/port multi-edges map
    // to their own spline. The top-level array is then AGSEQ-sorted (gv qsort
    // by seq in write_edges), while `_gvid` keeps the node-traversal value.
    var k = 0
    val edgesByK =
      g.nodes.iterator.flatMap { n =>
        g.edges.iterator.zipWithIndex.filter { case (e, _) => e.tail == n.id }.map { case (e, ix) =>
          val r = Doc.E(k, nodeGvid(e.tail), nodeGvid(e.head), e.tailPortStr, e.headPortStr, ix)
          k += 1; r
        }
      }.toVector
    val edgeGvidByIdx = edgesByK.iterator.map(e => e.idx -> e.gv).toMap
    val edges         = edgesByK.sortBy(_.idx)

    // Ownership rule (PORT.md §5.2, oracle-derived): a node that belongs to a
    // `rank`-constraint subgraph is dropped from every *cluster* node list;
    // plain-subgraph membership stays additive. `write_edges(sg)` walks only
    // surviving member nodes, so an evicted node's out-edge leaves the cluster
    // too. Within a subgraph, nodes/edges list in global id (gvid / AGSEQ)
    // order, matching `agfstnode(sg)` / the `qsort`.
    val rankConstrained: Set[String] =
      flat.iterator.filter(_.rank.isDefined).flatMap(_.nodeIds).toSet
    def memberNodes(s: RSubgraph): Vector[String] =
      val declared = s.nodeIds.toSet
      val kept     = if s.isCluster then declared -- rankConstrained else declared
      g.nodes.iterator.map(_.id).filter(kept).toVector
    val labelDeclared = g.graphAttrKeys.contains("label")
    val subgraphs = flat.zipWithIndex.map { case (s, gv) =>
      val mem      = memberNodes(s)
      val memSet   = mem.toSet
      val edgeGids = s.edgeIdxs.filter(ix => memSet(g.edges(ix).tail)).sorted.map(edgeGvidByIdx)
      Doc.SG(gv, s.id, s.label, s.rank, mem.map(nodeGvid), edgeGids, labelDeclared)
    }

    // Root graph attributes — gv `write_attrs`: every declared graph-attr key
    // (agnxtattr order), the root's value (default "" if only set in a
    // subgraph), skipping empty values *except* `label` (which always prints).
    val rootAttrs = attrPairs(g.graphAttrKeys.iterator.map(k => k -> g.rootAttrs.getOrElse(k, "")).toMap)
    val hasCluster = flat.exists(_.isCluster)

    Doc(g.name.getOrElse("%1"), g.directed, g.strict, hasCluster, rootAttrs,
      sgCnt, subgraphs, nodes, edges)

  private val SelfEdgeSize = 18.0 // const.h SELF_EDGE_SIZE

  /** Graph bounding box — faithful `position.c` `dot_compute_bb` (root):
    * the **node-extent** box only (NORMAL nodes ± `ND_lw`/`ND_rw`/rank
    * half-heights), **no spline extent and no floor/ceil** (Graphviz keeps
    * the exact float; the splines are channel-bounded so they don't extend
    * the bb). One exception is faithfully modelled: `make_LR_constraints`
    * enlarges `ND_rw` by `selfRightSpace` (`SELF_EDGE_SIZE`=18 per no-port
    * self-edge, + label width) and `dot_compute_bb` then sees it — so a
    * self-looped node's right extent (and the bb) grows by 18 per loop.
    * Drives `bb` for all three formats — shared with `Svg`. */
  /** gv `ROUND` macro: round half **away from zero** (not Java's half-up). */
  private[output] def gvRound(x: Double): Double =
    if x >= 0 then math.floor(x + 0.5) else math.ceil(x - 0.5)

  private[output] def bbox(g: RGraph): (Pt, Pt, Pt, Pt) =
    val (_, yOf) = Coord.rankY(g)
    val ranks    = org.jpablo.graphexplorer.graphviz.layout.Rank.assign(g)
    var minX = Double.MaxValue; var maxX = Double.MinValue
    var minY = Double.MaxValue; var maxY = Double.MinValue
    val xs = XCoord.xCoords(g)
    g.nodes.foreach { n =>
      for xPt <- xs.get(n.id); sz <- NodeSize.nodeSize(n, g) do
        val x  = xPt.value
        val hw = sz.halfWidthPt.value; val hh = sz.halfHeightPt.value
        // selfRightSpace: no-port self-edges reserve SELF_EDGE_SIZE on the
        // right (the port/label-bearing cases are deferred — no corpus).
        val selfW = g.edges.count(e => e.tail == n.id && e.head == n.id &&
          e.tailPort.isEmpty && e.headPort.isEmpty) * SelfEdgeSize
        val y  = yOf(ranks(n.id)).value
        minX = math.min(minX, x - hw); maxX = math.max(maxX, x + hw + selfW)
        minY = math.min(minY, y - hh); maxY = math.max(maxY, y + hh)
    }
    // root graph label reserves space on its labelloc side (Coord already
    // shifted the nodes for a bottom label ⇒ extend the bbox to reclaim it).
    val pad = gLabelPad(g)
    if pad > 0 then { if labelTop(g) then maxY += pad else minY -= pad }
    // Snap sub-epsilon FP noise to the nearest integer. gv's node coordinates
    // come out as clean values; a polygon size derived through sqrt/trig
    // (poly_init) carries ~1e-13 noise, which is harmless EXCEPT when it
    // straddles the integer boundary that dot_json floor/ceils or the svg
    // canvas ceils — there it becomes a full ±1pt error (e.g. house maxY
    // 36.0000001 → ceil 37). Genuine fractionals (triangle 49.6) stay put.
    def snap(v: Double): Double =
      val r = math.rint(v)
      if math.abs(v - r) < 1e-6 then r else v
    (Pt(snap(minX)), Pt(snap(minY)), Pt(snap(maxX)), Pt(snap(maxY)))

  /** One subgraph object block (4-space indented, no trailing comma), shared
    * by both writers. gv field order: name, label, [rank], _gvid, [nodes],
    * [edges]. json0's cluster-label geometry (bb/lheight/lwidth/lp) is a
    * tracked deferral (PORT.md §5.4) — not emitted here yet. */
  private def sgBlockJson(sg: Doc.SG): String =
    val fields = Vector.newBuilder[String]
    fields += s"""      "name": "${esc(sg.name)}""""
    if sg.emitLabel then fields += s"""      "label": "${esc(sg.label)}""""
    sg.rank.foreach(r => fields += s"""      "rank": "${esc(r)}"""")
    fields += s"""      "_gvid": ${sg.gvid}"""
    if sg.nodeGvids.nonEmpty then
      fields += s"""      "nodes": [\n        ${sg.nodeGvids.mkString(",")}\n      ]"""
    if sg.edgeGvids.nonEmpty then
      fields += s"""      "edges": [\n        ${sg.edgeGvids.mkString(",")}\n      ]"""
    "    {\n" + fields.result().mkString(",\n") + "\n    }"

  def dotJson(g: RGraph): String =
    val d = doc(g)
    // dot_json `bb` is the **integer** box (space-sep) — gv's `-Tjson`
    // structural dump ROUNDs each GD_bb corner (ROUND macro = round-half-away-
    // from-zero); json0 keeps the exact float. (Earlier floor/ceil only ever
    // matched because no corpus max had a fractional in (0, 0.5) until the
    // polygon shapes — triangle 61.291 → 61, not ceil's 62.)
    val (lxPt, lyPt, uxPt, uyPt) = bbox(g)
    val (lx, ly, ux, uy) = (lxPt.value, lyPt.value, uxPt.value, uyPt.value)
    val (blx, bly, bux, buy) =
      (gvRound(lx), gvRound(ly), gvRound(ux), gvRound(uy))
    val sb = new StringBuilder
    sb ++= "{\n"
    sb ++= s"""  "name": "${esc(d.name)}",\n"""
    sb ++= s"""  "directed": ${d.directed},\n"""
    sb ++= s"""  "strict": ${d.strict},\n"""
    // Clustered graphs: this viz-js leaves them unlaid-out ⇒ sentinel bb.
    val bbStr = if d.hasCluster then "0 0 0 0" else s"${g5(blx)} ${g5(bly)} ${g5(bux)} ${g5(buy)}"
    sb ++= s"""  "bb": "$bbStr",\n"""
    d.rootAttrs.foreach { case (k, v) => sb ++= s"""  "${esc(k)}": "${esc(v)}",\n""" }
    sb ++= s"""  "_subgraph_cnt": ${d.sgCnt},\n"""
    val byId = g.nodes.iterator.map(n => n.id -> n).toMap
    // gv auto-declares node `label` (default \N) always, edge `label` (default
    // "") only when some edge uses it ⇒ every edge then prints `label` too.
    val edgeLabels = g.edges.exists(_.attrs.toMap.contains("label"))
    // objects = subgraph objects (preorder) FIRST, then real nodes (offset gvid)
    def nodeBlock(id: String, gv: Int): String =
      val a = byId(id).attrs.toMap.updatedWith("label")(v => Some(v.getOrElse("\\N")))
      val fields = Vector.newBuilder[String]
      fields += s"""      "_gvid": $gv"""
      fields += s"""      "name": "${esc(id)}""""
      attrPairs(a).foreach((k, v) => fields += s"""      "${esc(k)}": "${esc(v)}"""")
      "    {\n" + fields.result().mkString(",\n") + "\n    }"
    val objBlocks = d.subgraphs.map(sgBlockJson) ++ d.nodes.map((id, gv) => nodeBlock(id, gv))
    sb ++= "  \"objects\": [\n"
    sb ++= objBlocks.mkString(",\n")
    sb ++= "\n  ]"
    def edgeBlock(e: Doc.E): String =
      var a = g.edges(e.idx).attrs.toMap
      e.tp.foreach(p => a += "tailport" -> p) // ports are just edge attributes
      e.hp.foreach(p => a += "headport" -> p)
      if edgeLabels then a = a.updatedWith("label")(v => Some(v.getOrElse("")))
      val fields = Vector.newBuilder[String]
      fields += s"""      "_gvid": ${e.gv}"""
      fields += s"""      "tail": ${e.t}"""
      fields += s"""      "head": ${e.h}"""
      attrPairs(a).foreach((k, v) => fields += s"""      "${esc(k)}": "${esc(v)}"""")
      "    {\n" + fields.result().mkString(",\n") + "\n    }"
    // gv omits the "edges" array entirely when the graph has no edges.
    if d.edges.nonEmpty then
      sb ++= ",\n  \"edges\": [\n"
      sb ++= d.edges.map(edgeBlock).mkString(",\n")
      sb ++= "\n  ]"
    sb ++= "\n}\n"
    sb.toString

  def json0(g: RGraph): String =
    val d = doc(g)
    val (lxPt, lyPt, uxPt, uyPt) = bbox(g)
    val (lx, ly, ux, uy) = (lxPt.value, lyPt.value, uxPt.value, uyPt.value)
    val byId  = g.nodes.iterator.map(n => n.id -> n).toMap
    val xs    = XCoord.xCoords(g)
    val (_, yOf) = Coord.rankY(g)
    val ranks = org.jpablo.graphexplorer.graphviz.layout.Rank.assign(g)
    val spl   = Spline.splinesEx(g)
    val lps   = Spline.labelPositions(g)
    val sb = new StringBuilder
    sb ++= "{\n"
    sb ++= s"""  "name": "${esc(d.name)}",\n"""
    sb ++= s"""  "directed": ${d.directed},\n"""
    sb ++= s"""  "strict": ${d.strict},\n"""
    val bbStr = if d.hasCluster then "0,0,0,0" else s"${g5(lx)},${g5(ly)},${g5(ux)},${g5(uy)}"
    sb ++= s"""  "bb": "$bbStr",\n"""
    // root graph attrs + the label geometry (lp/lwidth/lheight), merged
    // alphabetically into the write_attrs stream (do_graph_label).
    val rootKv = scala.collection.mutable.LinkedHashMap.empty[String, String]
    d.rootAttrs.foreach { case (k, v) => rootKv(k) = s""""${esc(v)}"""" }
    rootLabelText(g).foreach { lbl =>
      val lhPt = labelHtPt(g, lbl)
      val lwIn = NodeSize.labelWidthPt(lbl, labelFontSize(g), g.rootAttrs.getOrElse("fontname", "Times"), g.name.getOrElse("")) / 72.0
      rootKv("lheight") = s""""${f2(lhPt / 72.0)}""""
      rootKv("lp")      = s""""${g5((lx + ux) / 2.0)},${g5(if labelTop(g) then uy - Gap - lhPt / 2.0 else Gap + lhPt / 2.0)}""""
      rootKv("lwidth")  = s""""${f2(lwIn)}""""
    }
    rootKv.toVector.sortBy(_._1).foreach { case (k, v) => sb ++= s"""  "${esc(k)}": $v,\n""" }
    sb ++= s"""  "_subgraph_cnt": ${d.sgCnt},\n"""
    val edgeLabels = g.edges.exists(_.attrs.toMap.contains("label"))
    // json0 = dot_json attrs with the layout keys (height/pos/width for nodes,
    // pos for edges) merged into the same alphabetical `write_attrs` stream.
    def nodeBlock(id: String, gv: Int): String =
      val n  = byId(id)
      val sz = NodeSize.nodeSize(n, g)
      val px = xs.get(id).fold(0.0)(_.value)
      val py = yOf(ranks(id)).value
      val a  = n.attrs.toMap.updatedWith("label")(v => Some(v.getOrElse("\\N")))
      val kv = scala.collection.mutable.LinkedHashMap.empty[String, String]
      attrPairs(a).foreach((k, v) => kv(k) = s""""${esc(v)}"""")
      sz.foreach { s => kv("height") = s""""${g5(s.height.value)}""""; kv("width") = s""""${g5(s.width.value)}"""" }
      kv("pos") = s""""${g5(px)},${g5(py)}""""
      val fields = Vector.newBuilder[String]
      fields += s"""      "_gvid": $gv"""
      fields += s"""      "name": "${esc(id)}""""
      kv.toVector.sortBy(_._1).foreach((k, v) => fields += s"""      "${esc(k)}": $v""")
      "    {\n" + fields.result().mkString(",\n") + "\n    }"
    val objBlocks = d.subgraphs.map(sgBlockJson) ++ d.nodes.map((id, gv) => nodeBlock(id, gv))
    sb ++= "  \"objects\": [\n"
    sb ++= objBlocks.mkString(",\n")
    sb ++= "\n  ],\n"
    def edgeBlock(e: Doc.E): String =
      var a = g.edges(e.idx).attrs.toMap
      e.tp.foreach(p => a += "tailport" -> p)
      e.hp.foreach(p => a += "headport" -> p)
      if edgeLabels then a = a.updatedWith("label")(v => Some(v.getOrElse("")))
      val kv = scala.collection.mutable.LinkedHashMap.empty[String, String]
      attrPairs(a).foreach((k, v) => kv(k) = s""""${esc(v)}"""")
      lps.get(e.idx).foreach(p => kv("lp") = s""""${g5(p.x)},${g5(p.y)}"""")
      spl.get(e.idx).foreach { es =>
        val pre = es.ep.map(p => s"e,${g5(p.x)},${g5(p.y)} ").getOrElse("") +
                  es.sp.map(p => s"s,${g5(p.x)},${g5(p.y)} ").getOrElse("")
        val pts = es.pts.map(p => s"${g5(p.x)},${g5(p.y)}").mkString(" ")
        kv("pos") = s""""$pre$pts""""
      }
      val fields = Vector.newBuilder[String]
      fields += s"""      "_gvid": ${e.gv}"""
      fields += s"""      "tail": ${e.t}"""
      fields += s"""      "head": ${e.h}"""
      kv.toVector.sortBy(_._1).foreach((k, v) => fields += s"""      "${esc(k)}": $v""")
      "    {\n" + fields.result().mkString(",\n") + "\n    }"
    sb ++= "  \"edges\": [\n"
    sb ++= d.edges.map(edgeBlock).mkString(",\n")
    sb ++= "\n  ]\n}\n"
    sb.toString

end Output
