package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.RGraph
import scala.collection.mutable

/** Phase 2 of the `dot` pipeline: within-rank ordering / crossing
  * minimisation. Faithful transcription of `lib/dotgen/mincross.c` (gv 13.0.1):
  * long-edge virtual-node chains (`class2`), the `decompose`-order (`GD_nlist`)
  * BFS initial orders (`build_ranks` pass 0 = in-free seeds/out-edges, pass 1 =
  * out-free seeds/in-edges), the `mincross` pass-0/1/2 driver (`save_best`/
  * `restore_best`), the weighted-median `reorder`, and `transpose` adjacent-swap
  * refinement.
  *
  * Deferred (⬜, PORT.md §5.2): **flat edges** (`flat_reorder`/`flat_breakcycles`
  * for same-rank adjacencies — this is what leaves 06's `b`/`c` order a mirror
  * of gv's), cluster recursion (`mincross_clust`), and ports. Without flat
  * edges the label-free corpus is gated crossing-count/mirror-aware, not
  * strictly byte-exact, for graphs that have a same-rank edge.
  */
object Order:

  private val MaxIter     = 24    // mincross.c
  private val MinQuit     = 8
  private val Convergence = 0.995

  final case class Result(
      rank:      Map[String, Int],
      /** rank index → left-to-right node ids (real + virtual chain). */
      order:     Map[Int, Vector[LayoutNode]],
      crossings: Long,
      /** unit-span layout edges incl. virtual chains: (tail, head). */
      segments:  Vector[(LayoutNode, LayoutNode)],
      /** per-segment originating directed-edge index (into `Rank.ranked`'s
        * `dedges`, == `g.edges` minus self-loops). Lets XCoord recover each
        * segment's port x-offset (`make_edge_pairs` `ED_*_port.p.x`). */
      segOwner:  Vector[Int]
  ) derives CanEqual:
    def isVirtual(n: LayoutNode): Boolean = n match
      case _: LayoutNode.Virtual => true
      case _                     => false
    /** rank index → real-node ids (Strings), preserving left-to-right order. */
    def realOrder: Map[Int, Vector[String]] =
      order.view.mapValues(_.collect { case LayoutNode.Real(id) => id }).toMap

  private val orderMemo = GraphMemo[Result]()
  def order(g: RGraph): Result = orderMemo(g)(orderImpl(g))
  private def orderImpl(g: RGraph): Result =
    val (rank0, dedges) = Rank.ranked(g)

    // ── class2: replace long edges with unit-span virtual-node chains ──────
    val rankOf = mutable.HashMap.from(
      rank0.iterator.map((s, r) => (LayoutNode.Real(s): LayoutNode) -> r)
    )
    val out    = mutable.LinkedHashMap.empty[LayoutNode, mutable.ArrayBuffer[LayoutNode]]
    val in     = mutable.LinkedHashMap.empty[LayoutNode, mutable.ArrayBuffer[LayoutNode]]
    def node(id: LayoutNode): Unit =
      out.getOrElseUpdate(id, mutable.ArrayBuffer.empty)
      in.getOrElseUpdate(id, mutable.ArrayBuffer.empty)
    g.nodes.foreach(n => node(LayoutNode.Real(n.id)))
    val segs   = mutable.ArrayBuffer.empty[(LayoutNode, LayoutNode)]
    val segOwn = mutable.ArrayBuffer.empty[Int]
    var curOwner = -1
    def connect(t: LayoutNode, h: LayoutNode): Unit =
      out(t) += h
      in(h) += t
      segs += ((t, h))
      segOwn += curOwner

    dedges.zipWithIndex.foreach { case (e, idx) =>
      curOwner = idx
      val tail = LayoutNode.Real(e.tail)
      val head = LayoutNode.Real(e.head)
      val rt = rankOf(tail)
      val rh = rankOf(head)
      if rh - rt <= 1 then
        if rh != rt then connect(tail, head) else () // span 1 (skip flat span 0)
      else
        var prev: LayoutNode = tail
        var r    = rt + 1
        while r < rh do
          val v = LayoutNode.Virtual(idx, r)
          node(v)
          rankOf(v) = r
          connect(prev, v)
          prev = v
          r += 1
        connect(prev, head)
    }

    // gv `build_ranks` iterates GD_nlist for its BFS seeds — the `decompose`
    // (decomp.c) DFS order over the class2 real+virtual graph: from declaration-
    // order real seeds, following out-edges before in-edges. This seed order
    // (not declaration order) is what makes the initial ordering match gv (and,
    // e.g., not a left-right mirror).
    val gdNlist: Vector[LayoutNode] =
      val done = mutable.Set.empty[LayoutNode]
      val res  = mutable.ArrayBuffer.empty[LayoutNode]
      def visit(seed: LayoutNode): Unit =
        val stk = mutable.Stack(seed)
        while stk.nonEmpty do
          val n = stk.pop()
          if !done(n) then
            done += n; res += n
            in(n).reverseIterator.foreach(w => if !done(w) then stk.push(w))
            out(n).reverseIterator.foreach(w => if !done(w) then stk.push(w))
      g.nodes.foreach { nn => val s: LayoutNode = LayoutNode.Real(nn.id); if !done(s) then visit(s) }
      (g.nodes.map(n => LayoutNode.Real(n.id): LayoutNode) ++
        rankOf.keysIterator.collect { case v: LayoutNode.Virtual => v }.toVector.sortBy(_.name))
        .foreach(n => if !done(n) then { done += n; res += n }) // stable append for unreached
      res.toVector

    val minR  = if rankOf.isEmpty then 0 else rankOf.values.min
    val maxR  = if rankOf.isEmpty then 0 else rankOf.values.max
    val ranks = mutable.HashMap.from((minR to maxR).map(r => r -> mutable.ArrayBuffer.empty[LayoutNode]))
    val pos   = mutable.HashMap.empty[LayoutNode, Int]
    val mark  = mutable.Set.empty[LayoutNode]
    def install(n: LayoutNode): Unit =
      val rb = ranks(rankOf(n)); pos(n) = rb.length; rb += n

    // build_ranks(pass) (mincross.c:1273): BFS installing each rank left-to-
    // right. pass 0 seeds from in-edge-free nodes and follows out-edges; pass 1
    // seeds from out-edge-free nodes and follows in-edges (`enqueue_neighbors`)
    // — the two initial orderings the driver picks the better of. Seeds are
    // iterated in GD_nlist order. Then, for a flipped graph (rankdir LR/RL),
    // EVERY rank is reversed (mincross.c:1334) — the LR order-axis mirror.
    def buildRanks(pass: Int): Unit =
      ranks.valuesIterator.foreach(_.clear()); pos.clear(); mark.clear()
      val q = mutable.Queue.empty[LayoutNode]
      gdNlist.foreach { root =>
        val rootFree = if pass == 0 then in(root).isEmpty else out(root).isEmpty
        if rootFree && !mark(root) then
          mark += root; q.enqueue(root)
          while q.nonEmpty do
            val n0 = q.dequeue(); install(n0)
            (if pass == 0 then out(n0) else in(n0)).foreach(w => if !mark(w) then { mark += w; q.enqueue(w) })
      }
      gdNlist.foreach(n => if !mark(n) then { mark += n; install(n) }) // unreached
      if Rank.flip(g) then
        ranks.valuesIterator.foreach { rb =>
          val n = rb.length; var i = 0
          while i < n / 2 do { val t = rb(i); rb(i) = rb(n - 1 - i); rb(n - 1 - i) = t; i += 1 }
          rb.iterator.zipWithIndex.foreach { case (nd, idx) => pos(nd) = idx }
        }

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
    val mval = mutable.HashMap.empty[LayoutNode, Double]
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
    def crossPair(av: Iterable[LayoutNode], aw: Iterable[LayoutNode]): Int =
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

    // ── mincross driver (mincross.c:745) ─────────────────────────────────
    // Passes 0 and 1 each rebuild the whole order from a different initial BFS
    // (in-free vs out-free seeds) + ≤min(4,MaxIter) refinement iters; pass 2
    // refines the best of those for up to MaxIter. `save_best`/`restore_best`
    // keep the min-crossing order seen; a final `transpose(false)` polishes it.
    def snapshot(): Map[Int, Vector[LayoutNode]] =
      ranks.iterator.map { case (r, b) => r -> b.toVector }.toMap
    def restore(s: Map[Int, Vector[LayoutNode]]): Unit =
      s.foreach { case (r, v) =>
        val rb = ranks(r); rb.clear(); rb ++= v
        v.iterator.zipWithIndex.foreach { case (n, i) => pos(n) = i }
      }

    var best      = Map.empty[Int, Vector[LayoutNode]]
    var bestCross = Long.MaxValue
    var cur       = Long.MaxValue
    var pass      = 0
    var stop      = false
    while pass <= 2 && !stop do
      val maxthispass = if pass <= 1 then math.min(4, MaxIter) else MaxIter
      if pass <= 1 then
        buildRanks(pass)
        // flat_breakcycles(pass 0) + flat_reorder: no-ops without flat edges.
        cur = ncross
        if cur <= bestCross then { best = snapshot(); bestCross = cur }
      else
        if cur > bestCross then restore(best)
        cur = bestCross
      var trying = 0; var iter = 0; var brk = false
      while iter < maxthispass && !brk do
        if trying >= MinQuit then brk = true
        else
          trying += 1
          if cur == 0 then brk = true
          else
            mincrossStep(iter)
            cur = ncross
            if cur <= bestCross then
              best = snapshot()
              if cur < Convergence * bestCross then trying = 0
              bestCross = cur
            iter += 1
      if cur == 0 then stop = true
      pass += 1
    if cur > bestCross then restore(best)
    if bestCross > 0 then { transpose(false); bestCross = ncross }
    Result(rank0, snapshot(), bestCross, segs.toVector, segOwn.toVector)

end Order
