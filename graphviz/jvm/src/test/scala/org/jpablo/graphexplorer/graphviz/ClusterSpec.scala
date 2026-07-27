package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** M8 clusters — subgraph-tree model + the cluster geometry subsystem.
  *
  * **The oracle story (don't-port-the-bug rule).** Graphviz's DEFAULT ranking
  * is *broken* on 03's cross-cluster `{rank=same; a0; b0}`: 13.0.1 (CLI and
  * viz-js alike) silently emits a degenerate 0×0 layout; 12.2.1 hard-errors
  * (`install_in_rank`) after warning "a0 was already in a rankset, deleted
  * from cluster". The input is NOT contradictory — its constraint system has
  * a unique minimum-edge-length solution — and gv itself ships the fix as
  * `newrank=true` (global ranking; rank constraints may span clusters).
  *
  * Our engine ranks globally *by construction* (= newrank semantics), so the
  * correct oracle is **gv 13.0.1 with `newrank=true`**: corpus file
  * `03b-subgraph-cluster-newrank` (gated fully byte-exact in
  * [[CorpusByteExactSpec]]). 03-verbatim must produce the SAME drawing — its
  * outputs differ from 03b's goldens only by the `newrank` attribute echoes
  * (dot_json/json0); the svg is byte-identical. That derived gate lives here.
  *
  * Consequence: gv-default's "eviction" of rank-constrained nodes from their
  * clusters (the 12.2.1 warning — the first stage of the corruption) does NOT
  * happen under newrank; the 03b golden keeps `a0` in `cluster_0`. Membership
  * stays purely additive.
  */
class ClusterSpec extends FunSuite:

  private def graph(name: String) =
    OracleHarness.corpusGraph(name)

  /** Drop the `newrank` attribute echo lines from a 03b golden — everything
    * else must match 03-verbatim byte-for-byte. */
  private def stripNewrank(s: String): String =
    s.linesIterator.filterNot(_.contains(""""newrank": "true"""")).mkString("\n") + "\n"

  test("03 verbatim: svg is byte-identical to the newrank oracle (03b golden)"):
    val g = graph("03-subgraph-cluster")
    assertEquals(Svg.svg(g), OracleHarness.golden("03b-subgraph-cluster-newrank", "svg"))

  test("03 verbatim: json0 equals the newrank oracle modulo the attribute echo"):
    val g = graph("03-subgraph-cluster")
    assertEquals(Output.json0(g),
      stripNewrank(OracleHarness.golden("03b-subgraph-cluster-newrank", "json0")))

  test("03 verbatim: dot_json equals the newrank oracle modulo the attribute echo"):
    val g = graph("03-subgraph-cluster")
    assertEquals(Output.dotJson(g),
      stripNewrank(OracleHarness.golden("03b-subgraph-cluster-newrank", "dot_json")))

  test("03: subgraph-tree model captures names, cluster-ness, rank & the %7 anon id"):
    val g   = graph("03-subgraph-cluster")
    val sgs = g.subgraphs
    assertEquals(sgs.map(_.id), Vector("cluster_0", "cluster_1", "%7"))
    assertEquals(sgs.map(_.isCluster), Vector(true, true, false))
    assertEquals(sgs.map(_.label), Vector("group A", "group B", ""))
    assertEquals(sgs.map(_.rank), Vector(None, None, Some("same")))

  test("03: rank=same does NOT evict a0/b0 from their clusters (newrank semantics)"):
    val g  = graph("03-subgraph-cluster")
    val dj = ujson.read(Output.dotJson(g))
    def nodesOf(name: String): Set[Int] =
      dj("objects").arr.find(_.obj.get("name").exists(_.str == name))
        .flatMap(_.obj.get("nodes")).map(_.arr.iterator.map(_.num.toInt).toSet).getOrElse(Set.empty)
    val gvidOf = dj("objects").arr.iterator
      .flatMap(o => o.obj.get("name").map(_.str -> o("_gvid").num.toInt)).toMap
    assert(nodesOf("cluster_0").contains(gvidOf("a0")), "a0 stays in cluster_0")
    assert(nodesOf("cluster_1").contains(gvidOf("b0")), "b0 stays in cluster_1")
    assertEquals(nodesOf("%7"), Set(gvidOf("a0"), gvidOf("b0")))

  test("non-clustered corpus keeps _subgraph_cnt=0 (additive: no regression)"):
    List("01-minimal", "04-ports-compass", "06-undirected", "07-cross").foreach { name =>
      val dj = ujson.read(Output.dotJson(graph(name)))
      assertEquals(dj("_subgraph_cnt").num.toInt, 0, s"$name must stay flat")
    }

  // ── cluster labels: own font, labeljust, and the FLIPPED (rankdir=LR)
  // placement (191-scala-type-graph, 2026-07-27) ─────────────────────────
  //
  // 191 is still a corpus deferral for an unrelated ranking reason, so the
  // byte-exact gate cannot cover it. These assertions pin the parts that ARE
  // byte-exact — and they are POSITION-INDEPENDENT (label box size, and the
  // label's offset from its own cluster box), so they keep holding once the
  // ranking lands and the boxes move.

  private def json0Objects(name: String): Map[String, ujson.Value] =
    ujson.read(Output.json0(graph(name)))("objects").arr.iterator
      .flatMap(o => o.obj.get("name").map(_.str -> o)).toMap

  private def goldenJson0Objects(name: String): Map[String, ujson.Value] =
    ujson.read(OracleHarness.golden(name, "json0"))("objects").arr.iterator
      .flatMap(o => o.obj.get("name").map(_.str -> o)).toMap

  test("191: cluster label boxes are measured in the CLUSTER's own font"):
    // Every cluster declares fontsize=11/fontname=Helvetica; measuring with
    // the 14pt Times defaults made every box 21.2pt (11*LINESPACING + 2*GAP)
    // too short and the emitted label 3pt too large.
    val ours = json0Objects("191-scala-type-graph")
    val gold = goldenJson0Objects("191-scala-type-graph")
    val clusters = gold.filter((_, o) => o.obj.contains("lwidth")).keys.toVector.sorted
    assert(clusters.length == 10, s"expected 10 labelled clusters, got ${clusters.length}")
    clusters.foreach { c =>
      assertEquals(ours(c)("lwidth").str, gold(c)("lwidth").str, s"$c lwidth")
      assertEquals(ours(c)("lheight").str, gold(c)("lheight").str, s"$c lheight")
    }

  test("191: labeljust=l under rankdir=LR places lp via place_flip_graph_label"):
    // The label sits at a fixed offset INSIDE its own box: x from the LEFT
    // edge (labeljust=l), y down from the TOP (labelloc=t, the cluster
    // default) — computed in canonical coords, then rotated with the drawing.
    val ours = json0Objects("191-scala-type-graph")
    val gold = goldenJson0Objects("191-scala-type-graph")
    def offset(o: ujson.Value): (Double, Double) =
      val bb = o("bb").str.split(",").map(_.toDouble)
      val lp = o("lp").str.split(",").map(_.toDouble)
      (lp(0) - bb(0), bb(3) - lp(1))
    gold.filter((_, o) => o.obj.contains("lp")).keys.toVector.sorted.foreach { c =>
      val (ox, oy) = offset(ours(c))
      val (gx, gy) = offset(gold(c))
      assertEqualsDouble(ox, gx, 0.005, s"$c label x-offset from its LL corner")
      assertEqualsDouble(oy, gy, 0.005, s"$c label y-offset from its UR corner")
    }

  test("191: style=\"filled,rounded\" clusters draw round_corners' bezier path"):
    // A rounded cluster is a <path> of 8 cubics (25 control points) starting
    // on the BOTTOM edge — emit_clusters' AF walks from the LL corner, unlike
    // a node's, which starts top-right. The paint goes through colxlate too
    // (hex lowercased), and the label carries the cluster's own font-size.
    val svg = Svg.svg(graph("191-scala-type-graph"))
    val blocks = """(?s)<g id="[^"]*" class="cluster">.*?</g>""".r.findAllIn(svg).toVector
    assertEquals(blocks.length, 10)
    blocks.foreach { b =>
      val d = """ d="([^"]+)"""".r.findFirstMatchIn(b).map(_.group(1)).getOrElse("")
      assertEquals("""-?[\d.]+,-?[\d.]+""".r.findAllIn(d).size, 25, s"25 bezier points:\n$b")
      assert(b.contains("""fill="#f1f3f4" stroke="#5f6368""""), s"lowercased paint:\n$b")
      assert(b.contains("""font-size="11.00""""), s"the cluster's own font-size:\n$b")
    }

  test("191: cluster blocks come out in gv's within-rank order"):
    // The collapsed mincross pass (skeleton graph) decides which cluster sits
    // where inside a rank. Position-independent gate: on every rank, the
    // sequence of clusters — ordered by their members' cross-rank coordinate —
    // must match the golden's. Guards the two collapsed-pass fixes: building
    // the skeleton adjacency in class2 EMISSION order, and reorder's `###`
    // sawclust rule. (Absolute coordinates still differ, hence the deferral.)
    val name = "191-scala-type-graph"
    val g    = OracleHarness.corpusGraph(name)
    val clOf = org.jpablo.graphexplorer.graphviz.layout.Cluster.clusters(g).zipWithIndex
      .map((c, i) => i -> c.name).toMap
    val member = org.jpablo.graphexplorer.graphviz.layout.Cluster.clustOf(g)
    def blocksPerRank(json0: String): Map[Double, Vector[String]] =
      ujson.read(json0)("objects").arr.iterator
        .flatMap(o => o.obj.get("pos").map(p => o("name").str -> p.str))
        .flatMap { (id, pos) =>
          val Array(x, y) = pos.split(",").map(_.toDouble)
          member.get(id).map(ci => (x, y, clOf(ci)))
        }
        .toVector
        .groupBy(_._1)                                  // rankdir=LR ⇒ x is the rank
        .view.mapValues(_.sortBy(_._2).map(_._3).foldLeft(Vector.empty[String]) {
          (acc, c) => if acc.lastOption.contains(c) then acc else acc :+ c
        }).toMap
    assertEquals(
      blocksPerRank(Output.json0(g)),
      blocksPerRank(OracleHarness.golden(name, "json0")))

  test("191: EVERY rank's within-rank order matches gv (mincross gate)"):
    // The clustered mincross reproduces gv end to end — the collapsed pass,
    // all ten cluster refines, and ReMincross — so the observable output, the
    // left-to-right order of the real nodes on every rank, is now identical.
    // (191 is still a corpus deferral: the X COORDINATES differ, which is a
    // later phase. This gate is what keeps the ordering work from rotting.)
    val name = "191-scala-type-graph"
    def orderPerRank(json0: String): Map[Double, Vector[String]] =
      ujson.read(json0)("objects").arr.iterator
        .flatMap(o => o.obj.get("pos").map(p => o("name").str -> p.str))
        .map { (id, pos) => val Array(x, y) = pos.split(",").map(_.toDouble); (x, y, id) }
        .toVector.groupBy(_._1).view.mapValues(_.sortBy(_._2).map(_._3)).toMap
    val ours = orderPerRank(Output.json0(graph(name)))
    val gold = orderPerRank(OracleHarness.golden(name, "json0"))
    assertEquals(ours.keySet, gold.keySet, "the rank set itself must match")
    assertEquals(gold.size, 6)
    gold.keys.toVector.sorted.foreach(x => assertEquals(ours(x), gold(x), s"rank x=$x"))

end ClusterSpec
