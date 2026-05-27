package org.jpablo.graphexplorer.graphviz.layout

/** `mincross.c` node-class for `virtual_weight()` — the slack-node weight
  * scales with the (tail-class, head-class) pair:
  *
  * | tail \ head | Ordinary | Singleton | Virtual |
  * |---|---|---|---|
  * | Ordinary    | 1 | 1 | 1 |
  * | Singleton   | 1 | 2 | 2 |
  * | Virtual     | 1 | 2 | 4 |
  *
  *  - [[Ordinary]]  — real node with ≥ 2 incident real edges.
  *  - [[Singleton]] — real node with ≤ 1 incident real edge.
  *  - [[Virtual]]   — synthetic virtual node introduced by long-edge
  *                    splitting.
  */
enum NSClass(val ord: Int) derives CanEqual:
  case Ordinary  extends NSClass(0)
  case Singleton extends NSClass(1)
  case Virtual   extends NSClass(2)

object NSClass:
  /** Static omega table indexed `[tail.ord][head.ord]`. */
  val omega: Array[Array[Int]] = Array(
    Array(1, 1, 1),  // tail = Ordinary
    Array(1, 2, 2),  // tail = Singleton
    Array(1, 2, 4)   // tail = Virtual
  )
  inline def weight(tail: NSClass, head: NSClass): Int = omega(tail.ord)(head.ord)
