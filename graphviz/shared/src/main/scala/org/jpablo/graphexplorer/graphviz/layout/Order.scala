package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.RGraph
import scala.collection.mutable

/** Phase 2 of the `dot` pipeline: within-rank ordering / crossing
  * minimisation. Ports the core of `lib/dotgen/mincross.c` (gv 13.0.1):
  * long-edge virtual-node chains (`class2`), BFS initial order
  * (`build_ranks`, pass 0), the weighted-median `reorder` and `transpose`
  * adjacent-swap refinement, iterated to `MaxIter` keeping the best.
  *
  * Scoped to what the corpus exercises (PORT.md §5.2): no flat-edge handling,
  * no cluster recursion, no ports, and the pass-0/pass-1 init *alternation*
  * is simplified to pass-0 only. Gate is crossing-count parity with the
  * oracle (the objective mincross optimises), not the exact permutation —
  * left/right mirroring is an accepted documented deviation.
  */
object Order:

  private val MaxIter     = 24    // mincross.c
  private val MinQuit     = 8
  private val Convergence = 0.995

  final case class Result(
      rank:      Map[String, Int],
      /** rank index → left-to-right node ids (real + `__v…` virtual). */
      order:     Map[Int, Vector[String]],
      crossings: Long,
      /** unit-span layout edges incl. virtual chains: (tail, head). */
      segments:  Vector[(String, String)],
      /** per-segment originating directed-edge index (into `Rank.ranked`'s
        * `dedges`, == `g.edges` minus self-loops). Lets XCoord recover each
        * segment's port x-offset (`make_edge_pairs` `ED_*_port.p.x`). */
      segOwner:  Vector[Int]
  ) derives CanEqual:
    def isVirtual(id: String): Boolean = id.startsWith("__v")
    def realOrder: Map[Int, Vector[String]] =
      order.view.mapValues(_.filterNot(isVirtual)).toMap

  def order(g: RGraph): Result =
    val (rank0, dedges) = Rank.ranked(g)

    // ── class2: replace long edges with unit-span virtual-node chains ──────
    val rankOf = mutable.HashMap.from(rank0)
    val out    = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[String]]
    val in     = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[String]]
    def node(id: String): Unit =
      out.getOrElseUpdate(id, mutable.ArrayBuffer.empty)
      in.getOrElseUpdate(id, mutable.ArrayBuffer.empty)
    g.nodes.foreach(n => node(n.id))
    val segs   = mutable.ArrayBuffer.empty[(String, String)]
    val segOwn = mutable.ArrayBuffer.empty[Int]
    var curOwner = -1
    def connect(t: String, h: String): Unit =
      out(t) += h
      in(h) += t
      segs += ((t, h))
      segOwn += curOwner

    dedges.zipWithIndex.foreach { case (e, idx) =>
      curOwner = idx
      val rt = rankOf(e.tail)
      val rh = rankOf(e.head)
      if rh - rt <= 1 then
        if rh != rt then connect(e.tail, e.head) else () // span 1 (skip flat span 0)
      else
        var prev = e.tail
        var r    = rt + 1
        while r < rh do
          val v = s"__v${idx}_$r"
          node(v)
          rankOf(v) = r
          connect(prev, v)
          prev = v
          r += 1
        connect(prev, e.head)
    }

    val allNodes: Vector[String] = // real (declaration order) then virtual
      g.nodes.map(_.id) ++ rankOf.keysIterator.filter(_.startsWith("__v")).toVector.sorted

    val minR  = if rankOf.isEmpty then 0 else rankOf.values.min
    val maxR  = if rankOf.isEmpty then 0 else rankOf.values.max
    val ranks = mutable.HashMap.from((minR to maxR).map(r => r -> mutable.ArrayBuffer.empty[String]))
    val pos   = mutable.HashMap.empty[String, Int]

    // ── build_ranks (pass 0): BFS from in-edge-free nodes, follow out ──────
    val mark = mutable.Set.empty[String]
    val q    = mutable.Queue.empty[String]
    def install(n: String): Unit =
      val rb = ranks(rankOf(n))
      pos(n) = rb.length
      rb += n
    allNodes.foreach { root =>
      if in(root).isEmpty && !mark(root) then
        mark += root
        q.enqueue(root)
        while q.nonEmpty do
          val n0 = q.dequeue()
          install(n0)
          out(n0).foreach(h => if !mark(h) then { mark += h; q.enqueue(h) })
    }
    // any nodes unreached (e.g. isolated with only in-edges) — append stably
    allNodes.foreach(n => if !mark(n) then { mark += n; install(n) })

    // ── crossing counting ────────────────────────────────────────────────
    def bilayer(r: Int): Long =
      val segs = mutable.ArrayBuffer.empty[(Int, Int)] // (pos upper, pos lower)
      ranks.getOrElse(r, mutable.ArrayBuffer.empty).foreach { u =>
        out(u).foreach(w => segs += ((pos(u), pos(w))))
      }
      var c = 0L
      var i = 0
      while i < segs.length do
        var j = i + 1
        while j < segs.length do
          val (ui, wi) = segs(i)
          val (uj, wj) = segs(j)
          if (ui - uj) * (wi - wj) < 0 then c += 1
          j += 1
        i += 1
      c
    def ncross: Long = (minR until maxR).iterator.map(bilayer).sum

    def exchange(r: Int, i: Int, j: Int): Unit =
      val rb = ranks(r)
      val a  = rb(i); val b = rb(j)
      rb(i) = b; rb(j) = a
      pos(a) = j; pos(b) = i

    // ── weighted-median values + reorder (mincross.c medians/reorder) ─────
    val mval = mutable.HashMap.empty[String, Double]
    def medians(r0: Int, r1: Int): Unit =
      val rb = ranks.getOrElse(r0, mutable.ArrayBuffer.empty)
      rb.foreach { n =>
        val nbrs = (if r1 > r0 then out(n) else in(n)).map(pos).sorted
        mval(n) = nbrs.length match
          case 0 => -1.0
          case 1 => nbrs(0).toDouble
          case 2 => (nbrs(0) + nbrs(1)) / 2.0
          case j =>
            val rm = j / 2
            if j % 2 == 1 then nbrs(rm).toDouble
            else
              val lm    = rm - 1
              val rspan = nbrs(j - 1) - nbrs(rm)
              val lspan = nbrs(lm) - nbrs(0)
              if lspan == rspan then (nbrs(lm) + nbrs(rm)) / 2.0
              else (nbrs(lm).toDouble * rspan + nbrs(rm).toDouble * lspan) / (lspan + rspan)
      }

    def reorder(r: Int, reverse: Boolean): Unit =
      val rb = ranks(r)
      var ep = rb.length
      var nelt = rb.length - 1
      while nelt >= 0 do
        var lp = 0
        while lp < ep do
          while lp < ep && mval.getOrElse(rb(lp), -1.0) < 0 do lp += 1
          if lp < ep then
            var rp = lp + 1
            while rp < ep && mval.getOrElse(rb(rp), -1.0) < 0 do rp += 1
            if rp < ep then
              val p1 = mval.getOrElse(rb(lp), -1.0)
              val p2 = mval.getOrElse(rb(rp), -1.0)
              if p1 > p2 || (p1 >= p2 && reverse) then exchange(r, lp, rp)
              lp = rp
            else lp = ep
          else lp = ep
        if !reverse then ep -= 1
        nelt -= 1

    // ── transpose (adjacent swaps) ───────────────────────────────────────
    def crossPair(av: Iterable[String], aw: Iterable[String]): Int =
      var c = 0
      av.foreach(x => aw.foreach(y => if pos(x) > pos(y) then c += 1))
      c
    def transposeStep(reverse: Boolean): Long =
      var delta = 0L
      (minR to maxR).foreach { r =>
        val rb = ranks(r)
        var i = 0
        while i < rb.length - 1 do
          val v = rb(i); val w = rb(i + 1)
          val c0 = crossPair(in(v), in(w)) + crossPair(out(v), out(w))
          val c1 = crossPair(in(w), in(v)) + crossPair(out(w), out(v))
          if c1 < c0 || (c0 > 0 && reverse && c1 == c0) then
            exchange(r, i, i + 1)
            delta += c0 - c1
          i += 1
      }
      delta
    def transpose(reverse: Boolean): Unit =
      while transposeStep(reverse) >= 1 do ()

    def mincrossStep(pass: Int): Unit =
      val reverse = pass % 4 < 2
      val (first, last, dir) =
        if pass % 2 == 0 then (minR + 1, maxR, 1) else (maxR - 1, minR, -1)
      var r = first
      while r != last + dir do
        medians(r, r - dir)
        reorder(r, reverse)
        r += dir
      transpose(!reverse)

    // ── main loop (mincross.c) ───────────────────────────────────────────
    def snapshot(): Map[Int, Vector[String]] =
      ranks.iterator.map { case (r, b) => r -> b.toVector }.toMap
    def restore(s: Map[Int, Vector[String]]): Unit =
      s.foreach { case (r, v) =>
        val rb = ranks(r); rb.clear(); rb ++= v
        v.iterator.zipWithIndex.foreach { case (n, i) => pos(n) = i }
      }

    var best      = snapshot()
    var bestCross = ncross
    if bestCross > 0 then
      var trying = 0
      var iter   = 0
      var cur    = bestCross
      while iter < MaxIter && trying < MinQuit && cur != 0 do
        trying += 1
        mincrossStep(iter)
        cur = ncross
        if cur <= bestCross then
          best = snapshot()
          if cur < Convergence * bestCross then trying = 0
          bestCross = cur
        iter += 1
      if cur > bestCross then restore(best)
      if bestCross > 0 then
        transpose(false)
        val c = ncross
        if c <= bestCross then { best = snapshot(); bestCross = c }
        else restore(best)

    restore(best)
    Result(rank0, snapshot(), bestCross, segs.toVector, segOwn.toVector)

end Order
