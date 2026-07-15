package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.output.Output

/** M7 increment-1 exit gate: `dot_json` / `json0` strings match the captured
  * viz-js goldens. Tolerance-aware structural diff (PORT.md §2.1): graph
  * attrs / `_gvid` / `tail` / `head` / `label` / arrow-prefix exact; geometry
  * (`bb`, node `pos`/`width`/`height`, edge spline `pos`) within ε. Strict:
  * matched directly to the golden — the build_ranks tail transpose
  * (mincross.c:1349) closes 06's former X-mirror (cf. XCoordSpec/SplineSpec).
  * Scope: label-free TB.
  */
class OutputSpec extends FunSuite:

  private val Eps = 2.0 // points (~the §2.1 geometry tolerance)

  private val corpus = List("01-minimal", "06-undirected", "07-cross")

  private def graph(name: String) =
    OracleHarness.corpusGraph(name)

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
    OracleHarness.hausdorff(a, b)

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

  // ── json0: structure + geometry (strict, no mirror) ─────────────────────
  // With the build_ranks tail transpose transcribed (mincross.c:1349), the
  // whole label-free TB corpus — 06 included — matches the golden X directly;
  // the former layout-equivalent X-mirror allowance is closed (cf. XCoordSpec).
  corpus.foreach { name =>
    test(s"$name: json0 matches the golden (structure exact, geometry ±ε, no mirror)"):
      val ours = ujson.read(Output.json0(graph(name)))
      val gold = ujson.read(OracleHarness.golden(name, "json0"))
      assertEquals(ours("name").str, gold("name").str)
      assertEquals(ours("directed").bool, gold("directed").bool)
      assertEquals(ours("strict").bool, gold("strict").bool)

      val gNodes = gold("objects").arr.iterator.map(n => n("name").str -> n).toMap
      val oNodes = ours("objects").arr.iterator.map(n => n("name").str -> n).toMap
      assertEquals(oNodes.keySet, gNodes.keySet, s"$name nodes")

      def nodeDev: Double =
        oNodes.iterator.map { case (id, on) =>
          val gp = gNodes(id)("pos").str.split(","); val op = on("pos").str.split(",")
          math.hypot(num(op(0)) - num(gp(0)), num(op(1)) - num(gp(1)))
        }.max
      assert(nodeDev <= Eps, s"$name node pos dev")

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
        val dev = hausdorff(opts0, gpts)
        assert(dev <= Eps, s"$name $k spline dev=$dev")
        for o <- oep; gg <- gep do assert(near(o, gg, Eps), s"$name $k ep $o vs $gg")
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

  // M6 ports increment 2: the de-merged, port-box-routed splines surfaced
  // through the public json0 `pos` string (the viewer's `getEdgePos`
  // contract). 04's two parallel struct1→struct2 edges no longer collapse —
  // each carries its own `e,EX,EY` + control points. Keyed by (tailport,
  // headport); arrow-attach exact, spline within ε vs the json0 golden.
  test("04-ports-compass: json0 per-port edge splines match the golden"):
    def edgesByPort(v: ujson.Value): Map[(String, String), ujson.Value] =
      v("edges").arr.iterator.map { e =>
        (e.obj.get("tailport").map(_.str).getOrElse(""),
         e.obj.get("headport").map(_.str).getOrElse("")) -> e
      }.toMap
    val ours0 = ujson.read(Output.json0(graph("04-ports-compass")))
    val gold0 = ujson.read(OracleHarness.golden("04-ports-compass", "json0"))
    val oE = edgesByPort(ours0); val gE = edgesByPort(gold0)
    assertEquals(oE.keySet, gE.keySet,
      "04 json0 must have 2 port-distinguished edges (de-merge)")
    assertEquals(oE.keySet, Set(("f0", "a"), ("f2:s", "b:n")))
    gE.foreach { case (k, ge) =>
      val (oep, _, opts) = parsePos(oE(k)("pos").str)
      val (gep, _, gpts) = parsePos(ge("pos").str)
      assert(oep.isDefined && gep.isDefined, s"04 $k missing e, prefix")
      assert(near(oep.get, gep.get, 0.5), s"04 $k ep ${oep.get} vs ${gep.get}")
      val dev = hausdorff(opts, gpts)
      assert(dev <= Eps, s"04 $k spline dev=$dev (eps=$Eps)")
    }

  // bbox precision (position.c dot_compute_bb): node-extent only, no spline,
  // no floor/ceil. dot_json `bb` is the **integer** box; json0 the **exact
  // float**. Gated byte-exact across the whole corpus incl. 04 — where they
  // genuinely differ (dot_json "0 0 132 124" vs json0 "0,0,131.98,123.6");
  // 01/06/07 are integer ⇒ both equal the golden exactly (was ε-tolerated,
  // now dev 0).
  // 08's bb includes the self-edge `selfRightSpace` (+18) that
  // `make_LR_constraints` adds to `ND_rw` and `dot_compute_bb` sees.
  (corpus :+ "04-ports-compass" :+ "08-selfloop").foreach { name =>
    test(s"$name: bb byte-exact (dot_json int, json0 float)"):
      val odj = ujson.read(Output.dotJson(graph(name)))
      val gdj = ujson.read(OracleHarness.golden(name, "dot_json"))
      assertEquals(odj("bb").str, gdj("bb").str, s"$name dot_json bb")
      val oj0 = ujson.read(Output.json0(graph(name)))
      val gj0 = ujson.read(OracleHarness.golden(name, "json0"))
      assertEquals(oj0("bb").str, gj0("bb").str, s"$name json0 bb")
  }

  // Self-loop end-to-end through the public json0 `pos` (the viewer's
  // `getEdgePos` contract): both edges keyed by (tail,head), `e,EX,EY`
  // exact + spline within ε vs the json0 golden.
  test("08-selfloop: json0 self-loop + edge splines match the golden"):
    def eByTH(v: ujson.Value): Map[(String, String), ujson.Value] =
      val nm = nameByGvid(v)
      v("edges").arr.iterator.map(e => edgeKey(e, nm) -> e).toMap
    val o = ujson.read(Output.json0(graph("08-selfloop")))
    val gld = ujson.read(OracleHarness.golden("08-selfloop", "json0"))
    val oE = eByTH(o); val gE = eByTH(gld)
    assertEquals(oE.keySet, gE.keySet, "08 json0 edge set (incl. self-loop)")
    assertEquals(oE.keySet, Set(("a", "a"), ("a", "b")))
    gE.foreach { case (k, ge) =>
      val (oep, _, opts) = parsePos(oE(k)("pos").str)
      val (gep, _, gpts) = parsePos(ge("pos").str)
      assert(oep.isDefined && gep.isDefined, s"08 $k e, prefix")
      assert(near(oep.get, gep.get, 0.5), s"08 $k ep ${oep.get} vs ${gep.get}")
      assert(hausdorff(opts, gpts) <= 1.0, s"08 $k spline dev=${hausdorff(opts, gpts)}")
    }

end OutputSpec
