package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.Output

/** M6 clusters — the subgraph-tree model + `dot_json` emission (PORT.md §5.2).
  *
  * viz-js 3.14.0 does **not** lay clusters out (03's `plain`/`json0` carry
  * `pos="0,0"`, `bb="0,0,0,0"`) so 03 is a geometry-free *structural* task:
  * `dot_json` is fully determined and asserted **byte-exact**. The two rules
  * this locks down were derived empirically from the version-matched oracle
  * (probe DOT through viz-js, read the per-subgraph `nodes` arrays), not a
  * source-instrumented gv build:
  *
  *  1. Ownership: a node in a `rank`-constraint subgraph is dropped from any
  *     cluster's node list (`a0`/`b0` list only under `%7`, not their
  *     clusters); a cluster edge whose tail was evicted leaves too
  *     (`cluster_0.edges=[1]` = only `a1→a2`).
  *  2. Anonymous name `%N`: id = `counter*2+1` over the unnamed root + every
  *     edge + anon subgraphs, in parse order — the three cluster edges tick
  *     the counter to 3 before `{rank=same}`, giving `%7`.
  *
  * json0 cluster-label geometry (bb/lheight/lwidth/lp) + svg cluster boxes are
  * tracked deferrals (PORT.md §5.4).
  */
class ClusterSpec extends FunSuite:

  private def graph(name: String) =
    AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get)

  test("03: dot_json is byte-exact vs the golden (subgraph tree + ownership + %N)"):
    val g = graph("03-subgraph-cluster")
    assertEquals(Output.dotJson(g), OracleHarness.golden("03-subgraph-cluster", "dot_json"))

  test("03: subgraph-tree model captures names, cluster-ness, rank & the %7 anon id"):
    val g   = graph("03-subgraph-cluster")
    val sgs = g.subgraphs
    assertEquals(sgs.map(_.id), Vector("cluster_0", "cluster_1", "%7"))
    assertEquals(sgs.map(_.isCluster), Vector(true, true, false))
    assertEquals(sgs.map(_.label), Vector("group A", "group B", ""))
    assertEquals(sgs.map(_.rank), Vector(None, None, Some("same")))

  test("03: rank=same evicts a0/b0 from their clusters (ownership rule)"):
    val g = graph("03-subgraph-cluster")
    // raw declared membership still has a0 in cluster_0 / b0 in cluster_1 …
    assert(g.subgraphs(0).nodeIds.contains("a0"), "cluster_0 declares a0")
    // … but the emitted dot_json lists a0/b0 only under %7.
    val dj = ujson.read(Output.dotJson(g))
    def nodesOf(name: String): Set[Int] =
      dj("objects").arr.find(_.obj.get("name").exists(_.str == name))
        .flatMap(_.obj.get("nodes")).map(_.arr.iterator.map(_.num.toInt).toSet).getOrElse(Set.empty)
    val gvidOf = dj("objects").arr.iterator
      .flatMap(o => o.obj.get("name").map(_.str -> o("_gvid").num.toInt)).toMap
    assert(!nodesOf("cluster_0").contains(gvidOf("a0")), "a0 evicted from cluster_0")
    assert(!nodesOf("cluster_1").contains(gvidOf("b0")), "b0 evicted from cluster_1")
    assertEquals(nodesOf("%7"), Set(gvidOf("a0"), gvidOf("b0")))

  test("non-clustered corpus keeps _subgraph_cnt=0 (additive: no regression)"):
    List("01-minimal", "04-ports-compass", "06-undirected", "07-cross").foreach { name =>
      val dj = ujson.read(Output.dotJson(graph(name)))
      assertEquals(dj("_subgraph_cnt").num.toInt, 0, s"$name must stay flat")
    }

end ClusterSpec
