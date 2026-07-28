package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.layout.Order

/** A graph whose ranking leaves a GAP — a rank inside `[minrank, maxrank]` that
  * no node occupies.
  *
  * `dot` ranks each connected component separately and offsets them, so a gap is
  * ordinary: 192's seven isolated nodes (a legend cluster plus two unreferenced
  * tables) take rank 0 while its connected body starts at rank 8, leaving 1..7
  * empty. gv's `allocate_ranks` sizes `GD_rank` over the whole span, so an empty
  * rank still EXISTS with `n == 0`, and every mincross loop — all of which walk
  * minrank..maxrank — simply does nothing for it.
  *
  * Ours held only the ranks that carry nodes, so the first `rows(r)` on a gap
  * threw `NoSuchElementException: key not found: 1`, which reached the user as a
  * bare "key not found: 1" with no hint of a cause.
  *
  * The gate is 192 itself rather than a synthetic: several attempts at a small
  * stand-in (cluster + isolated nodes, with and without edge labels) all ranked
  * without a gap. The shape depends on how a graph's edge-less clusters collapse
  * during dot1's recursive cluster ranking, and inventing a graph that merely
  * looks similar would have gated nothing — as the second test here checks.
  */
class RankGapSpec extends FunSuite:

  private val name = "192-rank-gap-callgraph"

  test("the graph that crashed now renders"):
    val res = Graphviz.renderFormats(OracleHarness.corpusSource(name), Seq("svg"))
    assertEquals(res.errors, Vector.empty, "a rank gap must not fail the render")
    assertEquals(res.status, "success")
    assert(res.output("svg").contains("<svg"), "expected an svg document")

  /** The rank map that matters here is `order` — the rows the mincross loops
    * index — not `rank`, which carries only the REAL nodes. A rank with no real
    * node can still be busy with the virtual chain nodes threading through it;
    * the crash needed a rank empty of BOTH.
    */
  private def rows = Order.order(OracleHarness.corpusGraph(name)).order

  test("its ranking really does gap — otherwise this spec gates nothing"):
    val span  = (rows.keys.min to rows.keys.max)
    val empty = span.filter(r => rows.get(r).forall(_.isEmpty))
    assert(
      empty.nonEmpty,
      s"192 no longer has a rank empty of real AND virtual nodes in ${span.head}..${span.last}, " +
        s"so the crash it was added for is no longer reachable through it — find another case"
    )

  test("every rank in the span is allocated, empty ones included"):
    // gv's allocate_ranks contract, and what the mincross loops rely on: the row
    // exists, it is just empty. Before the fix, `rows(1)` threw here.
    val span = (rows.keys.min to rows.keys.max)
    span.foreach { r =>
      assert(rows.contains(r), s"rank $r has no row — the mincross loops index rows(r) directly")
    }
