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

  // Graph bbox (canonical dot_compute_bb box + translate_bb final frame)
  // lives in layout.GraphBB — gv computes it in layout; writers only read.

  /** gv `ROUND` macro: round half **away from zero** (not Java's half-up). */
  private[output] def gvRound(x: Double): Double =
    if x >= 0 then math.floor(x + 0.5) else math.ceil(x - 0.5)

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
      // write_subgs (!top): one indent before the FIRST entry only; the
      // ",\n" separator leaves every later gvid at column 0.
      fields += s"""      "subgraphs": [\n        ${sg.subgraphGvids.mkString(",\n")}\n      ]"""
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
      // A LOCALLY DECLARED value shadows the root default even when empty
      // (cgraph agxget: `label=""` on the subgraph echoes "", not the root's).
      val (own, declared) = k match
        // agxget: a subgraph's label = its LOCAL value if set, else the
        // root declaration's default — git's anonymous subgraphs echo the
        // root's "Basic git concepts…". The merged attrs carry the
        // inherited value; a locally-declared empty label still prints "".
        case "label" => (sg.attrs.getOrElse("label", sg.label), sg.emitLabel)
        case "rank"  => (sg.rank.getOrElse(""), sg.rank.isDefined)
        case _       => (sg.attrs.getOrElse(k, ""), sg.attrs.contains(k))
      k -> (if declared || own.nonEmpty then own else g.rootAttrs.getOrElse(k, ""))
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
        org.jpablo.graphexplorer.graphviz.layout.GraphBB.finalBBox(g)
      else { val (a, b, c, dd) = org.jpablo.graphexplorer.graphviz.layout.GraphBB.bbox(g); (a.value, b.value, c.value, dd.value) }
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
      if org.jpablo.graphexplorer.graphviz.layout.DrawTransform.rotated(g) then org.jpablo.graphexplorer.graphviz.layout.GraphBB.finalBBox(g)
      else { val (a, b, c, dd) = org.jpablo.graphexplorer.graphviz.layout.GraphBB.bbox(g); (a.value, b.value, c.value, dd.value) }
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
      // placed external label (addXLabels) — json0-only `xlp`
      org.jpablo.graphexplorer.graphviz.layout.XLabels.place(g).nodes.get(id).foreach { p =>
        val (x, y) = tf(p.cx, p.cy); kv("xlp") = s""""${g5(x)},${g5(y)}""""
      }
      // record nodes emit `rects` — every leaf field box in absolute coords
      // (node centre + node-local box, y-up), space-joined in field order.
      // The FIELD boxes are already in the drawn (final) orientation —
      // recordLayout is flip-aware — so only the node CENTRE goes through
      // the rankdir transform; pushing the local offsets through `tf` too
      // double-rotated them (LR rects came out with llx > urx). Same shape
      // as Svg's record path (transformed centre + local box).
      // output.c:312: ONLY the shape named exactly "record" echoes rects —
      // Mrecord does NOT (psg's Mrecord+html nodes carry no rects attr).
      if n.attrs.get("shape").map(_.toLowerCase).contains("record") then
        NodeSize.recordLayout(n, g).foreach { root =>
          def leaves(f: org.jpablo.graphexplorer.graphviz.layout.RecordLabel.Field): Vector[org.jpablo.graphexplorer.graphviz.layout.RecordLabel.Field] =
            if f.isLeaf then Vector(f) else f.flds.flatMap(leaves)
          val rects = leaves(root).map { f =>
            s"${g5(tpx + f.llx)},${g5(tpy + f.lly)},${g5(tpx + f.urx)},${g5(tpy + f.ury)}"
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
          // translate_bb: the CANONICAL cluster box maps corner-wise through
          // the rankdir transform (identity+offset for TB).
          val bb   = org.jpablo.graphexplorer.graphviz.layout.Cluster.finalBB(g, cbbs(i), tf)
          val base = Vector("bb" -> s"${g5(bb.llx)},${g5(bb.lly)},${g5(bb.urx)},${g5(bb.ury)}")
          val lbl  =
            if c.hasLabel then
              val (lpx, lpy) = c.labelLp(bb)
              Vector(
                "lheight" -> f2(c.lheightPt / 72.0),
                "lp"      -> s"${g5(lpx)},${g5(lpy)}",
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
      org.jpablo.graphexplorer.graphviz.layout.XLabels.place(g).edges.get(e.idx).foreach { p =>
        val (x, y) = tf(p.cx, p.cy); kv("xlp") = s""""${g5(x)},${g5(y)}""""
      }
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
