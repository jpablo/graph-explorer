package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
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
    AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get)

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

end ClusterSpec
