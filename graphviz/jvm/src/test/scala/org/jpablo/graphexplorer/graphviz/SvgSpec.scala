package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.Svg

/** M7 increment-2 exit gate: the `svg` string is well-formed and visually
  * close to the captured viz-js golden. Tolerance-aware structural diff
  * (PORT.md §2.1): svg dimensions / viewBox / per-node & per-edge titles /
  * text content exact; geometry (transform, ellipse, text pos, path,
  * arrowhead) within ε. Whole-drawing X-mirror allowed (06 — layout-
  * equivalent, cf. XCoordSpec/SplineSpec/OutputSpec). Scope: label-free TB.
  */
class SvgSpec extends FunSuite:

  private val Eps = 3.0 // pt — visual closeness (incl. M5-deferred arrow miter)
  private val corpus = List("01-minimal", "06-undirected", "07-cross")

  private def ours(name: String): String =
    Svg.svg(AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get))

  private val NodeRe = """(?s)<g id="node\d+" class="node">\s*<title>(.*?)</title>\s*<ellipse[^>]*cx="([-\d.]+)" cy="([-\d.]+)" rx="([-\d.]+)" ry="([-\d.]+)"[^>]*/>\s*<text[^>]*x="([-\d.]+)" y="([-\d.]+)"[^>]*>(.*?)</text>""".r
  private val EdgeRe = """(?s)<g id="edge\d+" class="edge">\s*<title>(.*?)</title>\s*<path[^>]*d="([^"]*)"/>(?:\s*<polygon[^>]*points="([^"]*)"/>)?""".r
  private val SvgRe  = """<svg width="(\d+)pt" height="(\d+)pt"\s+viewBox="([^"]*)"""".r
  private val TrRe   = """translate\(([-\d.]+) ([-\d.]+)\)""".r

  private final case class Node(cx: Double, cy: Double, rx: Double, ry: Double, tx: Double, ty: Double, lbl: String)
  private final case class Edge(d: Vector[(Double, Double)], arrow: Option[Vector[(Double, Double)]])

  private def nodes(svg: String): Map[String, Node] =
    NodeRe.findAllMatchIn(svg).map { m =>
      m.group(1) -> Node(m.group(2).toDouble, m.group(3).toDouble, m.group(4).toDouble,
        m.group(5).toDouble, m.group(6).toDouble, m.group(7).toDouble, m.group(8))
    }.toMap

  private def pts(s: String): Vector[(Double, Double)] =
    s.replace("M", " ").replace("C", " ").replace("L", " ").trim
      .split("\\s+").filter(_.nonEmpty)
      .map { t => val a = t.split(","); (a(0).toDouble, a(1).toDouble) }.toVector

  private def edges(svg: String): Map[String, Edge] =
    EdgeRe.findAllMatchIn(svg).map { m =>
      m.group(1) -> Edge(pts(m.group(2)), Option(m.group(3)).map(pts))
    }.toMap

  private def hausdorff(a: Vector[(Double, Double)], b: Vector[(Double, Double)]): Double =
    def dir(p: Vector[(Double, Double)], q: Vector[(Double, Double)]) =
      p.iterator.map(x => q.iterator.map(y => math.hypot(x._1 - y._1, x._2 - y._2)).min).max
    math.max(dir(a, b), dir(b, a))

  corpus.foreach { name =>
    test(s"$name: svg well-formed + visually close to the golden (mirror allowed)"):
      val o = ours(name)
      val gld = OracleHarness.golden(name, "svg")
      assert(o.startsWith("<?xml "), "xml decl")
      assert(o.contains("</svg>"), "closed svg")

      val om = SvgRe.findFirstMatchIn(o).getOrElse(fail("our <svg> header"))
      val gm = SvgRe.findFirstMatchIn(gld).getOrElse(fail("golden <svg> header"))
      assertEquals(om.group(1), gm.group(1), s"$name svg width")
      assertEquals(om.group(2), gm.group(2), s"$name svg height")
      assertEquals(om.group(3), gm.group(3), s"$name viewBox")

      val ot = TrRe.findFirstMatchIn(o).get; val gt = TrRe.findFirstMatchIn(gld).get
      assert(math.abs(ot.group(1).toDouble - gt.group(1).toDouble) <= Eps, s"$name translate x")
      assert(math.abs(ot.group(2).toDouble - gt.group(2).toDouble) <= Eps, s"$name translate y")

      val gn = nodes(gld); val on = nodes(o)
      assertEquals(on.keySet, gn.keySet, s"$name node titles")
      val gxs = gn.values.map(_.cx).toVector
      val W   = gxs.max + gxs.min // whole-drawing mirror axis

      def nodeDev(mir: Boolean): Double =
        on.iterator.map { case (k, n) =>
          val g = gn(k)
          val cx = if mir then W - n.cx else n.cx
          val tx = if mir then W - n.tx else n.tx
          math.max(
            math.max(math.hypot(cx - g.cx, n.cy - g.cy), math.abs(n.rx - g.rx) + math.abs(n.ry - g.ry)),
            math.hypot(tx - g.tx, n.ty - g.ty))
        }.max
      val mir = nodeDev(true) < nodeDev(false)
      assert(math.min(nodeDev(false), nodeDev(true)) <= Eps, s"$name node geometry dev")
      on.foreach { case (k, n) => assertEquals(n.lbl, gn(k).lbl, s"$name $k label") }

      def mx(p: (Double, Double)) = if mir then (W - p._1, p._2) else p
      val ge = edges(gld); val oe = edges(o)
      assertEquals(oe.keySet, ge.keySet, s"$name edge titles")
      ge.foreach { case (k, g) =>
        val e = oe(k)
        assert(hausdorff(e.d.map(mx), g.d) <= Eps, s"$name $k path dev=${hausdorff(e.d.map(mx), g.d)}")
        assertEquals(e.arrow.isDefined, g.arrow.isDefined, s"$name $k arrowhead presence")
        for ea <- e.arrow; ga <- g.arrow do
          assert(hausdorff(ea.map(mx), ga) <= Eps, s"$name $k arrowhead dev=${hausdorff(ea.map(mx), ga)}")
      }
  }

end SvgSpec
