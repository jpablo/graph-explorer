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
      case '\n' => b ++= "\\n"
      case '\r' => b ++= "\\r"
      case '\t' => b ++= "\\t"
      case c    => b += c
    }
    b.toString

  private def nodeLabel(n: org.jpablo.graphexplorer.graphviz.model.RNode): String =
    n.attrs.toMap.getOrElse("label", "\\N") // Graphviz default node label

  /** Layout-independent bits shared by both formats. */
  private final case class Doc(
      name:     String,
      directed: Boolean,
      strict:   Boolean,
      nodes:    Vector[(String, Int)],         // id → _gvid (declaration order)
      edges:    Vector[Doc.E]
  )
  private object Doc:
    // _gvid, tailGvid, headGvid, tailport, headport, g.edges index
    final case class E(gv: Int, t: Int, h: Int, tp: Option[String], hp: Option[String], idx: Int)

  private def doc(g: RGraph): Doc =
    val gvid  = g.nodes.iterator.map(_.id).zipWithIndex.toMap
    val nodes = g.nodes.map(n => n.id -> gvid(n.id))
    // Edge _gvid = cgraph creation order: for each node (declaration order),
    // its out-edges in declaration order. `idx` = position in `g.edges` (the
    // Spline key) so parallel/port multi-edges map to their own spline.
    var k = 0
    val edges =
      g.nodes.iterator.flatMap { n =>
        g.edges.iterator.zipWithIndex.filter { case (e, _) => e.tail == n.id }.map { case (e, ix) =>
          val r = Doc.E(k, gvid(e.tail), gvid(e.head), e.tailPortStr, e.headPortStr, ix)
          k += 1; r
        }
      }.toVector
    Doc(g.name.getOrElse("%1"), g.directed, g.strict, nodes, edges)

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
    (Pt(minX), Pt(minY), Pt(maxX), Pt(maxY))

  def dotJson(g: RGraph): String =
    val d = doc(g)
    // dot_json `bb` is the **integer** box (space-sep) — gv's `-Tjson`
    // structural dump floor/ceils GD_bb; json0 keeps the exact float.
    val (lxPt, lyPt, uxPt, uyPt) = bbox(g)
    val (lx, ly, ux, uy) = (lxPt.value, lyPt.value, uxPt.value, uyPt.value)
    val (blx, bly, bux, buy) =
      (math.floor(lx), math.floor(ly), math.ceil(ux), math.ceil(uy))
    val sb = new StringBuilder
    sb ++= "{\n"
    sb ++= s"""  "name": "${esc(d.name)}",\n"""
    sb ++= s"""  "directed": ${d.directed},\n"""
    sb ++= s"""  "strict": ${d.strict},\n"""
    sb ++= s"""  "bb": "${g5(blx)} ${g5(bly)} ${g5(bux)} ${g5(buy)}",\n"""
    sb ++= s"""  "_subgraph_cnt": 0,\n"""
    sb ++= "  \"objects\": [\n"
    val byId = g.nodes.iterator.map(n => n.id -> n).toMap
    d.nodes.zipWithIndex.foreach { case ((id, gv), i) =>
      sb ++= "    {\n"
      sb ++= s"""      "_gvid": $gv,\n"""
      sb ++= s"""      "name": "${esc(id)}",\n"""
      sb ++= s"""      "label": "${esc(nodeLabel(byId(id)))}"\n"""
      sb ++= (if i == d.nodes.length - 1 then "    }\n" else "    },\n")
    }
    sb ++= "  ],\n"
    sb ++= "  \"edges\": [\n"
    d.edges.zipWithIndex.foreach { case (e, i) =>
      sb ++= "    {\n"
      sb ++= s"""      "_gvid": ${e.gv},\n"""
      sb ++= s"""      "tail": ${e.t},\n"""
      sb ++= s"""      "head": ${e.h}"""
      e.hp.foreach(p => sb ++= s""",\n      "headport": "${esc(p)}"""")
      e.tp.foreach(p => sb ++= s""",\n      "tailport": "${esc(p)}"""")
      sb ++= "\n"
      sb ++= (if i == d.edges.length - 1 then "    }\n" else "    },\n")
    }
    sb ++= "  ]\n}\n"
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
    val sb = new StringBuilder
    sb ++= "{\n"
    sb ++= s"""  "name": "${esc(d.name)}",\n"""
    sb ++= s"""  "directed": ${d.directed},\n"""
    sb ++= s"""  "strict": ${d.strict},\n"""
    sb ++= s"""  "bb": "${g5(lx)},${g5(ly)},${g5(ux)},${g5(uy)}",\n"""
    sb ++= s"""  "_subgraph_cnt": 0,\n"""
    sb ++= "  \"objects\": [\n"
    d.nodes.zipWithIndex.foreach { case ((id, gv), i) =>
      val n  = byId(id)
      val sz = NodeSize.nodeSize(n, g)
      val px = xs.get(id).fold(0.0)(_.value)
      val py = yOf(ranks(id)).value
      sb ++= "    {\n"
      sb ++= s"""      "_gvid": $gv,\n"""
      sb ++= s"""      "name": "${esc(id)}",\n"""
      sz.foreach(s => sb ++= s"""      "height": "${g5(s.height.value)}",\n""")
      sb ++= s"""      "label": "${esc(nodeLabel(n))}",\n"""
      sb ++= s"""      "pos": "${g5(px)},${g5(py)}",\n"""
      sz.foreach(s => sb ++= s"""      "width": "${g5(s.width.value)}"\n""")
      sb ++= (if i == d.nodes.length - 1 then "    }\n" else "    },\n")
    }
    sb ++= "  ],\n"
    sb ++= "  \"edges\": [\n"
    d.edges.zipWithIndex.foreach { case (e, i) =>
      val gv = e.gv; val t = e.t; val h = e.h
      sb ++= "    {\n"
      sb ++= s"""      "_gvid": $gv,\n"""
      sb ++= s"""      "tail": $t,\n"""
      sb ++= s"""      "head": $h"""
      e.hp.foreach(p => sb ++= s""",\n      "headport": "${esc(p)}"""")
      e.tp.foreach(p => sb ++= s""",\n      "tailport": "${esc(p)}"""")
      spl.get(e.idx).foreach { es =>
        val pre = es.ep.map(p => s"e,${g5(p.x)},${g5(p.y)} ").getOrElse("") +
                  es.sp.map(p => s"s,${g5(p.x)},${g5(p.y)} ").getOrElse("")
        val pts = es.pts.map(p => s"${g5(p.x)},${g5(p.y)}").mkString(" ")
        sb ++= s""",\n      "pos": "$pre$pts"\n"""
      }
      if spl.get(e.idx).isEmpty then sb ++= "\n"
      sb ++= (if i == d.edges.length - 1 then "    }\n" else "    },\n")
    }
    sb ++= "  ]\n}\n"
    sb.toString

end Output
