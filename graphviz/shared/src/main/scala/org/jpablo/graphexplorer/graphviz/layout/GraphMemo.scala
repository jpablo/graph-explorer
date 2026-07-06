package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.RGraph

/** Size-1 identity cache for the pure, expensive per-graph layout stages.
  *
  * `Graphviz.renderFormats` emits dot_json/json0/svg — and each internally
  * calls `Output.bbox` + `Spline` — on the **same** `RGraph` instance, so the
  * NS/mincross/spline stages were recomputed ~7×. Keying a last-result cache
  * by reference (`eq`) collapses that to 1× with no signature changes.
  *
  * Correctness under concurrency: these functions are pure, and the compute is
  * inside `synchronized`, so concurrent access to *different* graphs merely
  * misses (recomputes) — it can never return a torn/wrong result. On Scala.js
  * (single-threaded) `synchronized` is a no-op. Only one graph's layout is
  * retained (size 1), so memory stays bounded.
  */
private[layout] final class GraphMemo[V]:
  private var key:   RGraph = null
  private var value: V      = null.asInstanceOf[V]
  def apply(g: RGraph)(compute: => V): V = synchronized {
    if g eq key then value
    else
      val v = compute
      key = g; value = v
      v
  }
