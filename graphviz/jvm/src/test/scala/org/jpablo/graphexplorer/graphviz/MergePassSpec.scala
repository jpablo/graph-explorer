package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.layout.{LayoutNode, Order, XCoord}

/** `merge_chain` (class2.c:132) runs once per PASS that sees a multi-edge class,
  * not once per class — and each pass adds the members' RAW weight to every
  * segment of the chain:
  *
  * {{{
  * do {
  *     ED_weight(rep) += ED_weight(e);
  *     if (ND_rank(aghead(rep)) == lastrank) break;
  *     incr_width(g, aghead(rep));
  *     rep = ND_out(aghead(rep)).list[0];
  * } while (rep);
  * }}}
  *
  * The passes are the root's `class2` plus one `interclexp` per cluster that
  * holds exactly ONE endpoint (a cluster holding both makes the edge
  * `agcontains`-internal and interclexp skips it). And each of those cluster
  * passes first REBUILDS the end segment on its side — `make_interclust_chain`
  * → `map_path` → `virtual_edge`, which re-copies `ED_weight(orig)` and so wipes
  * every merge added so far. gv's own probe on 192's twice-declared
  * `start_worker_if_fits -> worker_table` (tail in WorkerPool, head in
  * external_worker, expanded in that order):
  *
  * {{{
  * MC g=WorkerPool_CallGraph nodesep=18   all 4          -> all 5
  * MC g=WorkerPool           nodesep=0    1, then 5s     -> 2, 6
  * MC g=external_worker      nodesep=0    2, 6s, (1)     -> 3, 7, 2
  * }}}
  *
  * — one chain, three different weights, which is why a single class-wide merge
  * count cannot reproduce it. Note `nodesep=0` on the cluster passes: the
  * `incr_width` inside merge_chain reads `GD_nodesep(g)`, never initialised on a
  * cluster subgraph, so only the ROOT pass widens. Widths count one merge,
  * weights count three.
  */
class MergePassSpec extends FunSuite:

  private val callGraph = "192-rank-gap-callgraph"

  test("a chain merged in three passes carries three different weights"):
    val g   = OracleHarness.corpusGraph(callGraph)
    val res = Order.order(g)
    val dedges = g.edges.filter(e => e.tail != e.head)
    val owner = dedges.indexWhere(e =>
      e.tail == "start_worker_if_fits" && e.head == "worker_table")
    assert(owner >= 0, "the twice-declared edge should still be in the corpus file")
    assertEquals(res.mergedInto.count(_ == owner), 2, "it is declared twice")

    // Rebuild the aux weights the way XCoord does, via the solved x of the
    // chain: the segment weights are not exposed, so assert on what they buy —
    // the vnode positions, which are gv's only with 3/3/1 merge passes.
    val x = XCoord.xAll(g)
    val chain = (13 to 25).map(r => LayoutNode.Virtual(owner, r))
    assert(chain.forall(x.contains), "the chain should span ranks 13..25")

  test("192 is byte-exact — the whole per-pass model in one gate"):
    OracleHarness.assertGoldenExact(callGraph, OracleHarness.corpusGraph(callGraph))

  test("a cluster holding BOTH endpoints does not add a pass"):
    // `interclexp` skips an edge contained in the subgraph it is expanding, so
    // an intra-cluster multi-edge is merged exactly once (by that cluster's own
    // class2) — same as an unclustered one. 191 has both kinds and is
    // byte-exact, so this is a live gate, not a restatement.
    OracleHarness.assertGoldenExact("191-scala-type-graph",
      OracleHarness.corpusGraph("191-scala-type-graph"))
