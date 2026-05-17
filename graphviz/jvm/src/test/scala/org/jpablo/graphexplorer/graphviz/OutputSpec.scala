package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.Output

/** M7 increment-1 exit gate: `dot_json` / `json0` strings match the captured
  * viz-js goldens. Tolerance-aware structural diff (PORT.md §2.1): graph
  * attrs / `_gvid` / `tail` / `head` / `label` / arrow-prefix exact; geometry
  * (`bb`, node `pos`/`width`/`height`, edge spline `pos`) within ε. A whole-
  * drawing horizontal X-mirror is layout-equivalent and allowed (cf.
  * XCoordSpec/SplineSpec — 06's X comes out mirrored). Scope: label-free TB.
  */
class OutputSpec extends FunSuite:

  private val Eps = 2.0 // points (~the §2.1 geometry tolerance)

  private val corpus = List("01-minimal", "06-undirected", "07-cross")

  private def graph(name: String) =
    AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get)

  // _gvid → node name, from an objects array.
  private def nameByGvid(o: ujson.Value): Map[Int, String] =
    o("objects").arr.iterator.map(n => n("_gvid").num.toInt -> n("name").str).toMap

  private def edgeKey(e: ujson.Value, nm: Map[Int, String]): (String, String) =
    (nm(e("tail").num.toInt), nm(e("head").num.toInt))

  private def num(s: String): Double = s.toDouble

  private def bbNums(v: ujson.Value): Vector[Double] =
    v.str.split("[ ,]").iterator.map(_.toDouble).toVector

  /** Parse a json0 edge `pos`: optional `e,`/`s,` prefixes + control points. */
  private def parsePos(s: String): (Option[(Double, Double)], Option[(Double, Double)], Vector[(Double, Double)]) =
    var ep: Option[(Double, Double)] = None
    var sp: Option[(Double, Double)] = None
    val pts = Vector.newBuilder[(Double, Double)]
    s.split("\\s+").foreach { tok =>
      val f = tok.split(",")
      if f.length == 3 && f(0) == "e" then ep = Some((f(1).toDouble, f(2).toDouble))
      else if f.length == 3 && f(0) == "s" then sp = Some((f(1).toDouble, f(2).toDouble))
      else if f.length == 2 then pts += ((f(0).toDouble, f(1).toDouble))
    }
    (ep, sp, pts.result())

  private def near(a: (Double, Double), b: (Double, Double), e: Double): Boolean =
    math.abs(a._1 - b._1) <= e && math.abs(a._2 - b._2) <= e

  private def hausdorff(a: Vector[(Double, Double)], b: Vector[(Double, Double)]): Double =
    def dir(p: Vector[(Double, Double)], q: Vector[(Double, Double)]) =
      p.iterator.map(x => q.iterator.map(y => math.hypot(x._1 - y._1, x._2 - y._2)).min).max
    math.max(dir(a, b), dir(b, a))

  // ── dot_json: pure structure ────────────────────────────────────────────
  corpus.foreach { name =>
    test(s"$name: dot_json matches the golden (structure exact, bb ±ε)"):
      val ours = ujson.read(Output.dotJson(graph(name)))
      val gold = ujson.read(OracleHarness.golden(name, "dot_json"))
      assertEquals(ours("name").str, gold("name").str)
      assertEquals(ours("directed").bool, gold("directed").bool)
      assertEquals(ours("strict").bool, gold("strict").bool)
      assertEquals(ours("_subgraph_cnt").num, gold("_subgraph_cnt").num)
      bbNums(ours("bb")).zip(bbNums(gold("bb"))).foreach { case (o, g) =>
        assert(math.abs(o - g) <= Eps, s"$name dot_json bb $o vs $g")
      }
      val og = nameByGvid(ours); val gg = nameByGvid(gold)
      assertEquals(og.values.toSet, gg.values.toSet, s"$name objects")
      // label by node name
      def labels(v: ujson.Value) =
        v("objects").arr.iterator.map(n => n("name").str -> n("label").str).toMap
      assertEquals(labels(ours), labels(gold), s"$name labels")
      def eset(v: ujson.Value, nm: Map[Int, String]) =
        v("edges").arr.iterator.map(e => edgeKey(e, nm)).toSet
      assertEquals(eset(ours, og), eset(gold, gg), s"$name edge set")
  }

  // ── json0: structure + geometry (mirror-aware) ──────────────────────────
  corpus.foreach { name =>
    test(s"$name: json0 matches the golden (structure exact, geometry ±ε, mirror allowed)"):
      val ours = ujson.read(Output.json0(graph(name)))
      val gold = ujson.read(OracleHarness.golden(name, "json0"))
      assertEquals(ours("name").str, gold("name").str)
      assertEquals(ours("directed").bool, gold("directed").bool)
      assertEquals(ours("strict").bool, gold("strict").bool)

      val gNodes = gold("objects").arr.iterator.map(n => n("name").str -> n).toMap
      val oNodes = ours("objects").arr.iterator.map(n => n("name").str -> n).toMap
      assertEquals(oNodes.keySet, gNodes.keySet, s"$name nodes")
      val gx = gNodes.values.map(n => num(n("pos").str.split(",")(0))).toVector
      val W  = gx.max + gx.min // whole-drawing mirror axis (cf. XCoordSpec)

      def nodeDev(mirror: Boolean): Double =
        oNodes.iterator.map { case (id, on) =>
          val gp = gNodes(id)("pos").str.split(","); val op = on("pos").str.split(",")
          val ox = if mirror then W - num(op(0)) else num(op(0))
          math.hypot(ox - num(gp(0)), num(op(1)) - num(gp(1)))
        }.max
      val mir = nodeDev(true) < nodeDev(false)
      assert(math.min(nodeDev(false), nodeDev(true)) <= Eps, s"$name node pos dev")

      // width/height exact (numeric); label exact
      oNodes.foreach { case (id, on) =>
        val gn = gNodes(id)
        assertEquals(on("label").str, gn("label").str, s"$name $id label")
        assert(math.abs(num(on("width").str) - num(gn("width").str)) < 1e-6, s"$name $id width")
        assert(math.abs(num(on("height").str) - num(gn("height").str)) < 1e-6, s"$name $id height")
      }

      val ong = nameByGvid(ours); val gng = nameByGvid(gold)
      val oE  = ours("edges").arr.iterator.map(e => edgeKey(e, ong) -> e).toMap
      val gE  = gold("edges").arr.iterator.map(e => edgeKey(e, gng) -> e).toMap
      assertEquals(oE.keySet, gE.keySet, s"$name edge set")
      gE.foreach { case (k, ge) =>
        val (oep, osp, opts0) = parsePos(oE(k)("pos").str)
        val (gep, gsp, gpts)  = parsePos(ge("pos").str)
        assertEquals(oep.isDefined, gep.isDefined, s"$name $k e,prefix")
        assertEquals(osp.isDefined, gsp.isDefined, s"$name $k s,prefix")
        def mx(p: (Double, Double)) = if mir then (W - p._1, p._2) else p
        val dev = hausdorff(opts0.map(mx), gpts)
        assert(dev <= Eps, s"$name $k spline dev=$dev")
        for o <- oep; gg <- gep do assert(near(mx(o), gg, Eps), s"$name $k ep ${mx(o)} vs $gg")
      }
  }

  // M6 ports: `node:field:compass` is threaded through the model and emitted
  // as dot_json `tailport`/`headport`. 04's two struct1→struct2 edges are
  // distinguished only by their ports — gated as a set vs the golden.
  test("04-ports-compass: dot_json edge ports match the golden"):
    def portSet(v: ujson.Value): Set[(String, String, String, String)] =
      val nm = nameByGvid(v)
      v("edges").arr.iterator.map { e =>
        (nm(e("tail").num.toInt), nm(e("head").num.toInt),
         e.obj.get("tailport").map(_.str).getOrElse(""),
         e.obj.get("headport").map(_.str).getOrElse(""))
      }.toSet
    val ours = ujson.read(Output.dotJson(graph("04-ports-compass")))
    val gold = ujson.read(OracleHarness.golden("04-ports-compass", "dot_json"))
    assertEquals(portSet(ours), portSet(gold))
    assertEquals(
      portSet(ours),
      Set(("struct1", "struct2", "f0", "a"), ("struct1", "struct2", "f2:s", "b:n"))
    )

end OutputSpec
