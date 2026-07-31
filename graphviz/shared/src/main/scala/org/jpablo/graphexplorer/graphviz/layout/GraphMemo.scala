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
  * retained (size 1) — and since every `renderFormats` call parses a fresh
  * `RGraph` (no cross-call hit is possible), the facade calls [[GraphMemo.clearAll]]
  * when it finishes so the last graph's layout doesn't stay pinned for the
  * life of a long-lived (browser) session.
  */
private[graphviz] final class GraphMemo[V]:
  private var key:   RGraph = null
  private var value: V      = null.asInstanceOf[V]
  // Registered LAST, not first: `register` publishes `this` to a module-level
  // buffer whose only consumer (`clearAll`) writes both fields, so handing it
  // over before they exist is a leak of a half-built object — harmless today
  // (the instances are singletons built at class-init, and Scala.js is
  // single-threaded) but exactly what -Wsafe-init is for.
  def apply(g: RGraph)(compute: => V): V = synchronized {
    if g eq key then value
    else
      val v = compute
      key = g; value = v
      v
  }
  private[layout] def clear(): Unit = synchronized {
    key = null; value = null.asInstanceOf[V]
  }
  GraphMemo.register(this)

private[graphviz] object GraphMemo:
  // The instances are a fixed set of module-level singletons (one per layout
  // stage), registered once at class-init time.
  private val instances = scala.collection.mutable.ArrayBuffer.empty[GraphMemo[?]]
  private def register(m: GraphMemo[?]): Unit = synchronized { instances += m }
  /** Drop every stage's retained graph + result (see class doc). */
  def clearAll(): Unit = synchronized { instances.foreach(_.clear()) }
