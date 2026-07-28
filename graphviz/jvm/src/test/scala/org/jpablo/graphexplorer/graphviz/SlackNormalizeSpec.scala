package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.layout.{Order, Rank}

/** `scan_and_normalize` (ns.c:730) takes its minimum over the NORMAL nodes only
  * and then shifts EVERY node by it:
  *
  * {{{
  * for (n = GD_nlist(G); n; n = ND_next(n))
  *     if (ND_node_type(n) == NORMAL) Minrank = MIN(Minrank, ND_rank(n));
  * for (n = GD_nlist(G); n; n = ND_next(n)) ND_rank(n) -= Minrank;
  * }}}
  *
  * So an `interclust1` SLACKNODE that the solve placed above the real nodes
  * lands at a NEGATIVE rank instead of dragging them down — gv's own probe shows
  * 192's root component finishing with `real min 0, slack min -8`.
  *
  * We minimised over every node, slack included, which shifted 192's whole
  * connected body down by 8 while its isolated component stayed at 0. Two
  * symptoms:
  *
  *   - the body and the isolated nodes no longer shared rank 0, so the drawing
  *     stacked the legend below the graph instead of beside it, and came out a
  *     rank taller than gv's;
  *   - ranks 1..7 ended up occupied by nothing at all, and the first `rows(r)`
  *     on such a rank threw `NoSuchElementException: key not found: 1` — the
  *     render failed outright with a message naming nothing.
  *
  * The gap is gone now that the offset is, so nothing in the corpus exercises an
  * empty rank any more. `Order.orderClustered` still allocates the full
  * minrank..maxrank span (gv's `allocate_ranks` does, and every mincross loop
  * indexes `rows(r)` directly) — that is defence, not something these tests can
  * still reach through a real graph.
  */
class SlackNormalizeSpec extends FunSuite:

  private val callGraph = "192-rank-gap-callgraph"

  test("the graph that crashed renders"):
    val res = Graphviz.renderFormats(OracleHarness.corpusSource(callGraph), Seq("svg"))
    assertEquals(res.errors, Vector.empty)
    assertEquals(res.status, "success")

  test("a slack node does not drag the real nodes down"):
    // gv, probed after rank1: run=0, _start=2, db_table=24, span 0..32.
    val ranks = Rank.ranked(OracleHarness.corpusGraph(callGraph))._1
    assertEquals(ranks("run"), 0, "the connected body must start at rank 0, as in gv")
    assertEquals(ranks("_start"), 2)
    assertEquals(ranks("db_table"), 24)
    assertEquals((ranks.values.min, ranks.values.max), (0, 32), "gv reports minrank=0 maxrank=32")

  test("every component starts at rank 0, so they share ranks instead of stacking"):
    // The isolated nodes (a legend cluster and two unreferenced tables) belong to
    // their own components; gv puts all of them on rank 0 alongside `run`.
    val ranks = Rank.ranked(OracleHarness.corpusGraph(callGraph))._1
    Vector("legend_public", "legend_private", "legend_static", "legend_module",
           "legend_dead", "misc_table", "async_table").foreach { n =>
      assertEquals(ranks(n), ranks("run"), s"$n should share the body's top rank")
    }

  test("the rank span is fully allocated, empty ranks included"):
    // gv's allocate_ranks contract, and what the mincross loops rely on.
    val res  = Order.order(OracleHarness.corpusGraph(callGraph))
    val span = res.order.keys.min to res.order.keys.max
    span.foreach(r => assert(res.order.contains(r), s"rank $r has no row"))

  test("191's rank numbering is gv's, with no constant offset"):
    // The same bug shifted 191 by +2 throughout — harmless for the drawing (it
    // was byte-exact anyway) but it meant every rank comparison against gv's
    // probes needed a fudge. gv's own dump of 191 spans r0..r10.
    val res = Order.order(OracleHarness.corpusGraph("191-scala-type-graph"))
    assertEquals((res.order.keys.min, res.order.keys.max), (0, 10))
