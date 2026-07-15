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
  // Geometry (pad + labelloc) lives in Coord — the layout computes it once,
  // exactly as gv stores GD_label; the writers only read it.
  private def rootLabelText(g: RGraph): Option[String] = g.rootAttrs.get("label").filter(_.nonEmpty)
  private def labelFontSize(g: RGraph): Double =
    g.rootAttrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(DefFontSize)
  private def labelTop(g: RGraph): Boolean = Coord.graphLabelTop(g)
  private def labelHtPt(g: RGraph, lbl: String): Double =
    NodeSize.labelHeightPt(lbl, labelFontSize(g), g.name.getOrElse(""))
  private def gLabelPad(g: RGraph): Double = Coord.graphLabelPad(g)
  /** C `printf("%.2f")` — the `lwidth`/`lheight`/`lp`-dimension format. */
  private def f2(x: Double): String = BigDecimal(x).setScale(2, BigDecimal.RoundingMode.HALF_UP).toString

  /** Layout-independent bits shared by both formats. */
  private final case class Doc(
      name:       String,
      directed:   Boolean,
      strict:     Boolean,
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
        nodeGvids: Vector[Int], edgeGvids: Vector[Int], subgraphGvids: Vector[Int],
        emitLabel: Boolean, attrs: Map[String, String] = Map.empty)

  private val docMemo = org.jpablo.graphexplorer.graphviz.layout.GraphMemo[Doc]()
  private def doc(g: RGraph): Doc = docMemo(g)(docImpl(g))
  private def docImpl(g: RGraph): Doc =
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
    // (declaration order), its out-edges as `agfstout` returns them — ordered by
    // HEAD node id (declaration order), then AGSEQ for parallel edges — NOT edge
    // declaration order. (Coincides with AGSEQ unless a node's out-edges name
    // their heads out of node order, e.g. 95's `top->a0;top->b0;top->a1;…`.)
    // `idx` = position in `g.edges` (= AGSEQ = the Spline key) so parallel/port
    // multi-edges map to their own spline. The top-level array is then
    // AGSEQ-sorted (gv qsort by seq in write_edges); `_gvid` keeps this value.
    // O(E) per-tail grouping (groupBy keeps declaration order within a group)
    // instead of scanning all edges once per node.
    val edgesByTail = g.edges.zipWithIndex.groupBy(_._1.tail)
    var k = 0
    val edgesByK =
      g.nodes.iterator.flatMap { n =>
        edgesByTail.getOrElse(n.id, Vector.empty)
          .sortBy { case (e, ix) => (nodeIdx.getOrElse(e.head, Int.MaxValue), ix) }
          .map { case (e, ix) =>
            val r = Doc.E(k, nodeGvid(e.tail), nodeGvid(e.head), e.tailPortStr, e.headPortStr, ix)
            k += 1; r
          }
      }.toVector
    val edgeGvidByIdx = edgesByK.iterator.map(e => e.idx -> e.gv).toMap
    val edges         = edgesByK.sortBy(_.idx)

    // Membership is purely additive (cgraph containment). NOTE: gv's DEFAULT
    // ranking *deletes* rank-constrained nodes from clusters ("%s was already
    // in a rankset, deleted from cluster" — the very corruption that breaks
    // 03-subgraph-cluster). Our engine implements the global-ranking
    // (`newrank`) semantics, where no eviction happens — matching the
    // gv-13.0.1-with-newrank oracle (03b golden: cluster_0 keeps a0).
    // Within a subgraph, nodes/edges list in global id (gvid / AGSEQ)
    // order, matching `agfstnode(sg)` / the `qsort`.
    // A subgraph contains its own nodes AND all its descendant subgraphs'
    // (cgraph: a nested cluster's nodes are members of the parent too) — so
    // cluster_0 rolls up the nested cluster_1's a,b. Emitted in gvid order.
    def transitiveNodeIds(s: RSubgraph): Set[String] =
      s.nodeIds.toSet ++ s.children.flatMap(transitiveNodeIds)
    def memberNodes(s: RSubgraph): Vector[String] =
      val declared = transitiveNodeIds(s)
      g.nodes.iterator.map(_.id).filter(declared).toVector
    val labelDeclared = g.graphAttrKeys.contains("label")
    val subgraphs = flat.zipWithIndex.map { case (s, gv) =>
      val mem      = memberNodes(s)
      val memSet   = mem.toSet
      // A CLUSTER owns every edge with BOTH endpoints inside it (cgraph
      // containment adds them during cluster processing) — membership, NOT the
      // declaration site: 95's `a0->a1->a2` is declared at root yet belongs to
      // cluster_0; 03b's cross-cluster `a2->b1` is excluded (b1 ∉ cluster_0). A
      // PLAIN subgraph (`{rank=same;…}`) gets only its directly-declared edges
      // (90's `a->b` declared at root stays OUT of its rank=same block).
      val edgeGids =
        // gv lists a subgraph's edges in EDGE DECLARATION order (the cgraph
        // edge sequence), NOT sorted by gvid — gvids are assigned by the
        // writer's output order, so the array can look scrambled (163-groups:
        // cluster_0 = [0,4,3,1]).
        if s.isCluster then
          g.edges.iterator.zipWithIndex
            .collect { case (e, ix) if memSet(e.tail) && memSet(e.head) => edgeGvidByIdx(ix) }
            .toVector
        else s.edgeIdxs.filter(ix => memSet(g.edges(ix).tail)).sorted.map(edgeGvidByIdx)
      // child subgraph gvids (preorder ⇒ each child's gvid is its index in flat).
      val childGvids = s.children.map(c => flat.indexWhere(_ eq c)).filter(_ >= 0)
      Doc.SG(gv, s.id, s.label, s.rank, mem.map(nodeGvid), edgeGids, childGvids, labelDeclared,
        s.attrs)
    }

    // Root graph attributes — gv `write_attrs`: every declared graph-attr key
    // (agnxtattr order), the root's value (default "" if only set in a
    // subgraph), skipping empty values *except* `label` (which always prints).
    val rootAttrs = attrPairs(g.graphAttrKeys.iterator.map(k => k -> g.rootAttrs.getOrElse(k, "")).toMap)

    Doc(g.name.getOrElse("%1"), g.directed, g.strict, rootAttrs,
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

  // ── update_bb_bz (emit.c): grow a bb by a spline's TIGHT bezier bbox ───────
  // dot grows GD_bb per installed spline (the graph box is the node/cluster/
  // label union with each edge's adaptively-subdivided curve extent), so an
  // edge that escapes the node span lifts the drawing. Regular-edge splines
  // stay inside their rank span ⇒ a no-op for them; a non-adjacent flat edge
  // arches above its rank, and THIS is what raises the graph height. The naive
  // control-hull bbox would overshoot (control points sit above the curve), so
  // gv subdivides until each segment is within `HW`=2pt of its chord, then
  // expands by the now-near-flat control points — recovering the true peak.
  private val HW2 = 4.0 // (HW = 2pt)²
  private def ptToLine2(a: Spline.XY, b: Spline.XY, p: Spline.XY): Double =
    val dx = b.x - a.x; val dy = b.y - a.y
    var a2 = (p.y - a.y) * dx - (p.x - a.x) * dy
    a2 *= a2
    if a2 < 1e-6 then 0.0 else a2 / (dx * dx + dy * dy)
  private def flatEnough(cp: Array[Spline.XY]): Boolean =
    ptToLine2(cp(0), cp(3), cp(1)) < HW2 && ptToLine2(cp(0), cp(3), cp(2)) < HW2
  /** de Casteljau split at t = 0.5 (`Bezier`, utils.c) → (left, right) quads. */
  private def bezierSplit(cp: Array[Spline.XY]): (Array[Spline.XY], Array[Spline.XY]) =
    def mid(a: Spline.XY, b: Spline.XY) = Spline.XY((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
    val m01 = mid(cp(0), cp(1)); val m12 = mid(cp(1), cp(2)); val m23 = mid(cp(2), cp(3))
    val mA = mid(m01, m12); val mB = mid(m12, m23); val c = mid(mA, mB)
    (Array(cp(0), m01, mA, c), Array(c, mB, m23, cp(3)))
  /** `bb` = mutable `[minX, minY, maxX, maxY]`, grown by one bezier segment. */
  private def updateBBbz(bb: Array[Double], cp: Array[Spline.XY]): Unit =
    val outside = cp.exists(p => p.x > bb(2) || p.x < bb(0) || p.y > bb(3) || p.y < bb(1))
    if outside then
      if flatEnough(cp) then
        cp.foreach { p =>
          if p.x > bb(2) then bb(2) = p.x else if p.x < bb(0) then bb(0) = p.x
          if p.y > bb(3) then bb(3) = p.y else if p.y < bb(1) then bb(1) = p.y
        }
      else
        val (l, r) = bezierSplit(cp)
        updateBBbz(bb, l); updateBBbz(bb, r)
  /** Grow `[minX,minY,maxX,maxY]` by every routed edge spline (update_bb_bz on
    * each 4-point segment). Splines are in layout coords, matching gv's grow
    * during routing (before the rankdir transform). */
  private def growBySplines(g: RGraph, minX: Double, minY: Double, maxX: Double, maxY: Double)
      : (Double, Double, Double, Double) =
    val spls = Spline.splinesEx(g)
    if spls.isEmpty then (minX, minY, maxX, maxY)
    else
      val bb = Array(minX, minY, maxX, maxY)
      spls.valuesIterator.foreach { es =>
        val pts = es.pts
        var i = 0
        while i + 3 < pts.length do
          updateBBbz(bb, Array(pts(i), pts(i + 1), pts(i + 2), pts(i + 3)))
          i += 3
      }
      (bb(0), bb(1), bb(2), bb(3))

  private val bboxMemo = org.jpablo.graphexplorer.graphviz.layout.GraphMemo[(Pt, Pt, Pt, Pt)]()
  private[output] def bbox(g: RGraph): (Pt, Pt, Pt, Pt) = bboxMemo(g)(bboxImpl(g))
  private def bboxImpl(g: RGraph): (Pt, Pt, Pt, Pt) =
    val (_, yOf) = Coord.rankY(g)
    val ranks    = org.jpablo.graphexplorer.graphviz.layout.Rank.assign(g)
    var minX = Double.MaxValue; var maxX = Double.MinValue
    var minY = Double.MaxValue; var maxY = Double.MinValue
    val xs = XCoord.xCoords(g)
    // selfRightSpace: no-port self-edges reserve SELF_EDGE_SIZE on the
    // right (the port/label-bearing cases are deferred — no corpus).
    // One O(E) count map instead of an O(E) scan per node.
    val selfLoops: Map[String, Int] =
      g.edges.filter(e => e.tail == e.head && e.tailPort.isEmpty && e.headPort.isEmpty)
        .groupBy(_.tail).view.mapValues(_.size).toMap
    g.nodes.foreach { n =>
      for xPt <- xs.get(n.id); sz <- NodeSize.nodeSize(n, g) do
        val x  = xPt.value
        val hw = sz.halfWidthPt.value; val hh = sz.halfHeightPt.value
        val selfW = selfLoops.getOrElse(n.id, 0) * SelfEdgeSize
        val y  = yOf(ranks(n.id)).value
        minX = math.min(minX, x - hw); maxX = math.max(maxX, x + hw + selfW)
        minY = math.min(minY, y - hh); maxY = math.max(maxY, y + hh)
    }
    // Clusters (dot_compute_bb root): the bb also spans every top-level
    // cluster box + CL_OFFSET margin in x; in y the root's cluster-inflated
    // GD_ht1/GD_ht2 set the bottom/top (label bands included).
    val cls = org.jpablo.graphexplorer.graphviz.layout.Cluster.clusters(g)
    if cls.nonEmpty then
      val yi   = Coord.yInfo(g)
      val cbbs = org.jpablo.graphexplorer.graphviz.layout.Cluster.bbs(g)
      org.jpablo.graphexplorer.graphviz.layout.Cluster.childrenOf(g, -1).foreach { i =>
        minX = math.min(minX, cbbs(i).llx - 8.0); maxX = math.max(maxX, cbbs(i).urx + 8.0)
      }
      if ranks.nonEmpty then
        val maxR = ranks.values.max; val minR = ranks.values.min
        minY = math.min(minY, yOf(maxR).value - yi.rootHt1)
        maxY = math.max(maxY, yOf(minR).value + yi.rootHt2)
    // Grow by each edge spline's tight curve extent (dot's per-spline
    // update_bb_bz). A no-op for node-contained regular edges; a non-adjacent
    // flat-edge arch rises above its rank and lifts the graph height here.
    val (gx0, gy0, gx1, gy1) = growBySplines(g, minX, minY, maxX, maxY)
    minX = gx0; minY = gy0; maxX = gx1; maxY = gy1
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
    // Empty drawing (zero nodes/clusters/splines — e.g. `digraph {}` or the
    // viewer's defaults-only serialization): nothing touched the extreme
    // seeds, and MinValue−MaxValue overflows to −Infinity downstream (a
    // NumberFormatException in the BigDecimal formatters). gv emits
    // bb="0 0 0 0" here (translate_drawing of an empty drawing).
    if minX > maxX || minY > maxY then (Pt(0.0), Pt(0.0), Pt(0.0), Pt(0.0))
    else (Pt(snap(minX)), Pt(snap(minY)), Pt(snap(maxX)), Pt(snap(maxY)))

  /** Node-extent bbox in the FINAL (rotated) frame: each node is drawn at its
    * true size around the `map_point`-transformed centre (rotating the swapped
    * layout size back to the true size). Used for flipped graphs; the offset in
    * `tf` already lands the min corner at ~0. selfW/graph-label reservation is
    * a deferral (no flipped corpus exercises them). */
  private[output] def finalBBox(g: RGraph, tf: (Double, Double) => (Double, Double)): (Double, Double, Double, Double) =
    val (_, yOf) = Coord.rankY(g)
    val ranks    = org.jpablo.graphexplorer.graphviz.layout.Rank.assign(g)
    val xs       = XCoord.xCoords(g)
    var minX = Double.MaxValue; var maxX = Double.MinValue
    var minY = Double.MaxValue; var maxY = Double.MinValue
    g.nodes.foreach { n =>
      for xp <- xs.get(n.id); sz <- NodeSize.nodeSize(n, g) do
        val (cxv, cyv) = tf(xp.value, yOf(ranks(n.id)).value)
        val hw = sz.halfWidthPt.value; val hh = sz.halfHeightPt.value
        minX = math.min(minX, cxv - hw); maxX = math.max(maxX, cxv + hw)
        minY = math.min(minY, cyv - hh); maxY = math.max(maxY, cyv + hh)
    }
    def snap(v: Double): Double = { val r = math.rint(v); if math.abs(v - r) < 1e-6 then r else v }
    if minX > maxX || minY > maxY then (0.0, 0.0, 0.0, 0.0) // empty drawing, see bbox
    else (snap(minX), snap(minY), snap(maxX), snap(maxY))

  /** One subgraph object block (4-space indented, no trailing comma), shared
    * by both writers. gv field order: name, attrs (alphabetical), _gvid,
    * [nodes], [edges]. `attrs` come from [[sgAttrs]] (+ json0's cluster
    * layout attrs), already filtered + sorted. */
  private def sgBlockJson(sg: Doc.SG, attrs: Vector[(String, String)]): String =
    val fields = Vector.newBuilder[String]
    fields += s"""      "name": "${esc(sg.name)}""""
    attrs.foreach((k, v) => fields += s"""      "${esc(k)}": "${esc(v)}"""")
    fields += s"""      "_gvid": ${sg.gvid}"""
    if sg.subgraphGvids.nonEmpty then
      fields += s"""      "subgraphs": [\n        ${sg.subgraphGvids.mkString(",")}\n      ]"""
    if sg.nodeGvids.nonEmpty then
      fields += s"""      "nodes": [\n        ${sg.nodeGvids.mkString(",")}\n      ]"""
    if sg.edgeGvids.nonEmpty then
      fields += s"""      "edges": [\n        ${sg.edgeGvids.mkString(",")}\n      ]"""
    "    {\n" + fields.result().mkString(",\n") + "\n    }"

  /** A subgraph's attribute stream (gv `write_attrs`): every declared
    * graph-attr key, with the subgraph's own value where it has one (label /
    * rank in our model) else the root declaration's default (cgraph: setting
    * a graph attr on the root re-declares its default, so subgraphs echo the
    * root's value — 03b's `newrank` appears in every cluster object). Empty
    * values are skipped except an (always-printed) declared `label`. */
  private def sgAttrs(g: RGraph, sg: Doc.SG): Vector[(String, String)] =
    g.graphAttrKeys.iterator.map { k =>
      val own = k match
        case "label" => sg.label
        case "rank"  => sg.rank.getOrElse("")
        case _       => sg.attrs.getOrElse(k, "") // the subgraph's own value (incl. inherited scope)
      k -> (if own.nonEmpty then own else g.rootAttrs.getOrElse(k, ""))
    }.toVector
      .filter { case (k, v) => v.nonEmpty || (k == "label" && sg.emitLabel) }
      .sortBy(_._1)

  def dotJson(g: RGraph): String =
    val d = doc(g)
    // dot_json `bb` is the **integer** box (space-sep) — gv's `-Tjson`
    // structural dump ROUNDs each GD_bb corner (ROUND macro = round-half-away-
    // from-zero); json0 keeps the exact float. (Earlier floor/ceil only ever
    // matched because no corpus max had a fractional in (0, 0.5) until the
    // polygon shapes — triangle 61.291 → 61, not ceil's 62.)
    val (lx, ly, ux, uy) =
      if org.jpablo.graphexplorer.graphviz.layout.DrawTransform.rotated(g) then
        finalBBox(g, org.jpablo.graphexplorer.graphviz.layout.DrawTransform.of(g))
      else { val (a, b, c, dd) = bbox(g); (a.value, b.value, c.value, dd.value) }
    // translate_drawing: shift the full bb to the origin (a no-op unless a
    // spline overhangs the node/cluster box — see json0).
    val (blx, bly, bux, buy) =
      (gvRound(0.0), gvRound(0.0), gvRound(ux - lx), gvRound(uy - ly))
    val sb = new StringBuilder
    sb ++= "{\n"
    sb ++= s"""  "name": "${esc(d.name)}",\n"""
    sb ++= s"""  "directed": ${d.directed},\n"""
    sb ++= s"""  "strict": ${d.strict},\n"""
    sb ++= s"""  "bb": "${g5(blx)} ${g5(bly)} ${g5(bux)} ${g5(buy)}",\n"""
    d.rootAttrs.foreach { case (k, v) => sb ++= s"""  "${esc(k)}": "${esc(v)}",\n""" }
    sb ++= s"""  "_subgraph_cnt": ${d.sgCnt}""" // comma deferred: gv omits "objects" when empty
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
    val objBlocks = d.subgraphs.map(sg => sgBlockJson(sg, sgAttrs(g, sg))) ++
      d.nodes.map((id, gv) => nodeBlock(id, gv))
    // gv omits the "objects" array entirely for an empty graph (and an empty
    // objects set implies no edges — edges auto-declare their endpoints).
    if objBlocks.nonEmpty then
      sb ++= ",\n  \"objects\": [\n"
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
    // map_point (postproc.c): rotate canonical coords into the drawing frame.
    val tf0 = org.jpablo.graphexplorer.graphviz.layout.DrawTransform.of(g)
    val (lx, ly, ux, uy) =
      if org.jpablo.graphexplorer.graphviz.layout.DrawTransform.rotated(g) then finalBBox(g, tf0)
      else { val (a, b, c, dd) = bbox(g); (a.value, b.value, c.value, dd.value) }
    // translate_drawing (postproc.c): the FULL bb (incl. spline overhang) lands
    // at the origin. A no-op wherever the layout already starts at 0 (every file
    // whose splines stay within the node/cluster box) — non-trivial only when a
    // spline overhangs a cluster (95). Compose into `tf` so every coord shifts.
    val dx = -lx; val dy = -ly
    val tf: (Double, Double) => (Double, Double) = (x, y) => { val (a, b) = tf0(x, y); (a + dx, b + dy) }
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
    sb ++= s"""  "bb": "${g5(lx + dx)},${g5(ly + dy)},${g5(ux + dx)},${g5(uy + dy)}",\n"""
    // root graph attrs + the label geometry (lp/lwidth/lheight), merged
    // alphabetically into the write_attrs stream (do_graph_label).
    val rootKv = scala.collection.mutable.LinkedHashMap.empty[String, String]
    d.rootAttrs.foreach { case (k, v) => rootKv(k) = s""""${esc(v)}"""" }
    rootLabelText(g).foreach { lbl =>
      val lhPt = labelHtPt(g, lbl)
      val lwIn = NodeSize.labelWidthPt(lbl, labelFontSize(g), g.rootAttrs.getOrElse("fontname", "Times"), g.name.getOrElse("")) / 72.0
      rootKv("lheight") = s""""${f2(lhPt / 72.0)}""""
      rootKv("lp")      = s""""${g5((lx + ux) / 2.0 + dx)},${g5((if labelTop(g) then uy - Gap - lhPt / 2.0 else Gap + lhPt / 2.0) + dy)}""""
      rootKv("lwidth")  = s""""${f2(lwIn)}""""
    }
    rootKv.toVector.sortBy(_._1).foreach { case (k, v) => sb ++= s"""  "${esc(k)}": $v,\n""" }
    sb ++= s"""  "_subgraph_cnt": ${d.sgCnt}""" // comma deferred: gv omits "objects" when empty
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
      val (tpx, tpy) = tf(px, py)
      kv("pos") = s""""${g5(tpx)},${g5(tpy)}""""
      // record nodes emit `rects` — every leaf field box in absolute coords
      // (node centre + node-local box, y-up), space-joined in field order.
      NodeSize.recordLayout(n, g).foreach { root =>
        def leaves(f: org.jpablo.graphexplorer.graphviz.layout.RecordLabel.Field): Vector[org.jpablo.graphexplorer.graphviz.layout.RecordLabel.Field] =
          if f.isLeaf then Vector(f) else f.flds.flatMap(leaves)
        val rects = leaves(root).map { f =>
          val (llx, lly) = tf(px + f.llx, py + f.lly)
          val (urx, ury) = tf(px + f.urx, py + f.ury)
          s"${g5(llx)},${g5(lly)},${g5(urx)},${g5(ury)}"
        }.mkString(" ")
        kv("rects") = s""""$rects""""
      }
      val fields = Vector.newBuilder[String]
      fields += s"""      "_gvid": $gv"""
      fields += s"""      "name": "${esc(id)}""""
      kv.toVector.sortBy(_._1).foreach((k, v) => fields += s"""      "${esc(k)}": $v""")
      "    {\n" + fields.result().mkString(",\n") + "\n    }"
    // json0 cluster objects add the layout attrs (bb + label geometry),
    // merged alphabetically into the same write_attrs stream. lp = box
    // centre x, UR.y − border[TOP].y/2 (place_graph_label, labelloc=t).
    val cluLayoutAttrs: Map[String, Vector[(String, String)]] =
      val cls = org.jpablo.graphexplorer.graphviz.layout.Cluster.clusters(g)
      if cls.isEmpty then Map.empty
      else
        val cbbs = org.jpablo.graphexplorer.graphviz.layout.Cluster.bbs(g)
        cls.zipWithIndex.map { (c, i) =>
          val bb   = cbbs(i)
          val base = Vector("bb" -> s"${g5(bb.llx + dx)},${g5(bb.lly + dy)},${g5(bb.urx + dx)},${g5(bb.ury + dy)}")
          val lbl  =
            if c.hasLabel then
              val (lpx, lpy) = c.labelLp(bb)
              Vector(
                "lheight" -> f2(c.lheightPt / 72.0),
                "lp"      -> s"${g5(lpx + dx)},${g5(lpy + dy)}",
                "lwidth"  -> f2(c.lwidthPt / 72.0))
            else Vector.empty
          c.name -> (base ++ lbl)
        }.toMap
    val objBlocks = d.subgraphs.map { sg =>
      val attrs = (sgAttrs(g, sg) ++ cluLayoutAttrs.getOrElse(sg.name, Vector.empty)).sortBy(_._1)
      sgBlockJson(sg, attrs)
    } ++ d.nodes.map((id, gv) => nodeBlock(id, gv))
    // gv omits the "objects" array entirely for an empty graph (see dotJson);
    // the "edges" array is likewise omitted for an edgeless graph.
    if objBlocks.nonEmpty then
      sb ++= ",\n  \"objects\": [\n"
      sb ++= objBlocks.mkString(",\n")
      sb ++= (if d.edges.nonEmpty then "\n  ],\n" else "\n  ]\n")
    else sb ++= "\n"
    def edgeBlock(e: Doc.E): String =
      var a = g.edges(e.idx).attrs.toMap
      e.tp.foreach(p => a += "tailport" -> p)
      e.hp.foreach(p => a += "headport" -> p)
      if edgeLabels then a = a.updatedWith("label")(v => Some(v.getOrElse("")))
      val kv = scala.collection.mutable.LinkedHashMap.empty[String, String]
      attrPairs(a).foreach((k, v) => kv(k) = s""""${esc(v)}"""")
      lps.get(e.idx).foreach { p => val (lpx, lpy) = tf(p.x, p.y); kv("lp") = s""""${g5(lpx)},${g5(lpy)}"""" }
      spl.get(e.idx).foreach { es =>
        def m(p: Spline.XY): (Double, Double) = tf(p.x, p.y)
        val pre = es.ep.map(p => { val (x, y) = m(p); s"e,${g5(x)},${g5(y)} " }).getOrElse("") +
                  es.sp.map(p => { val (x, y) = m(p); s"s,${g5(x)},${g5(y)} " }).getOrElse("")
        val pts = es.pts.map(p => { val (x, y) = m(p); s"${g5(x)},${g5(y)}" }).mkString(" ")
        kv("pos") = s""""$pre$pts""""
      }
      val fields = Vector.newBuilder[String]
      fields += s"""      "_gvid": ${e.gv}"""
      fields += s"""      "tail": ${e.t}"""
      fields += s"""      "head": ${e.h}"""
      kv.toVector.sortBy(_._1).foreach((k, v) => fields += s"""      "${esc(k)}": $v""")
      "    {\n" + fields.result().mkString(",\n") + "\n    }"
    if d.edges.nonEmpty then
      sb ++= "  \"edges\": [\n"
      sb ++= d.edges.map(edgeBlock).mkString(",\n")
      sb ++= "\n  ]\n"
    sb ++= "}\n"
    sb.toString

end Output
