package org.jpablo.graphexplorer.graphviz.layout

import scala.collection.mutable

/** Network simplex (Gansner-Koutsofios-North-Vo §2.3, ported from
  * `lib/common/ns.c`, gv 13.0.1).
  *
  * Minimises `Σ weight·(rank[head] − rank[tail])` subject to
  * `rank[head] − rank[tail] ≥ minlen`, returning normalised integer ranks.
  *
  * This is the kernel `dot` reuses for BOTH rank assignment (M2) and
  * x-coordinate assignment (M4x: `set_xcoords` = ranks of an auxiliary
  * graph). Cut values are recomputed per pivot rather than maintained via
  * the C's low/lim DFS — correctness over speed; the graphs here are tiny.
  */
object NetworkSimplex:

  final case class NSEdge(tail: String, head: String, minlen: Int, weight: Int) derives CanEqual

  /** @param balance see [[NSBalance]] — TopBottom spreads loose nodes,
    *                LeftRight centres within slack (used by x-coords). */
  def solve(nodes: Seq[String], edges: Seq[NSEdge], balance: NSBalance = NSBalance.None): Map[String, Int] =
    if nodes.isEmpty then return Map.empty
    val nodeList = nodes.toVector
    val es       = edges.toVector

    val outIdx = mutable.LinkedHashMap.from(nodeList.map(_ -> mutable.ArrayBuffer.empty[Int]))
    val inIdx  = mutable.LinkedHashMap.from(nodeList.map(_ -> mutable.ArrayBuffer.empty[Int]))
    es.zipWithIndex.foreach { case (e, i) =>
      outIdx.getOrElseUpdate(e.tail, mutable.ArrayBuffer.empty) += i
      inIdx.getOrElseUpdate(e.head, mutable.ArrayBuffer.empty) += i
    }

    val rank = mutable.HashMap.from(nodeList.map(_ -> 0))

    // ── init_rank: longest path via Kahn (feasible) ──────────────────────
    val indeg = mutable.HashMap.from(nodeList.map(n => n -> inIdx(n).size))
    val q     = mutable.Queue.from(nodeList.filter(indeg(_) == 0))
    var seen  = 0
    while q.nonEmpty do
      val v = q.dequeue()
      seen += 1
      inIdx(v).foreach { i =>
        val e = es(i)
        if rank(e.tail) + e.minlen > rank(v) then rank(v) = rank(e.tail) + e.minlen
      }
      outIdx(v).foreach { i =>
        val h = es(i).head
        indeg(h) -= 1
        if indeg(h) == 0 then q.enqueue(h)
      }
    require(seen == nodeList.size, "NetworkSimplex: input graph has a cycle")

    def slack(i: Int): Int = (rank(es(i).head) - rank(es(i).tail)) - es(i).minlen

    // ── feasible tight spanning tree ─────────────────────────────────────
    val treeEdges = mutable.Set.empty[Int]

    /** Nodes reachable from `root` via current tree edges (undirected). */
    def componentOf(root: String, tree: collection.Set[Int]): mutable.Set[String] =
      val comp = mutable.Set(root)
      val stk  = mutable.Stack(root)
      while stk.nonEmpty do
        val v = stk.pop()
        tree.foreach { i =>
          val e = es(i)
          val w = if e.tail == v then Some(e.head) else if e.head == v then Some(e.tail) else None
          w.foreach(x => if !comp(x) then { comp += x; stk.push(x) })
        }
      comp

    /** Grow `treeEdges` to a tight spanning tree, shifting ranks as needed. */
    def feasibleTree(): Unit =
      var guard = 0
      while guard < nodeList.size * 4 do
        guard += 1
        // tight subtree from node 0 over slack-0 edges
        treeEdges.clear()
        val inTree = mutable.Set(nodeList.head)
        var grew   = true
        while grew do
          grew = false
          es.indices.foreach { i =>
            if !treeEdges(i) && slack(i) == 0 then
              val e = es(i)
              val tIn = inTree(e.tail)
              val hIn = inTree(e.head)
              if tIn ^ hIn then
                treeEdges += i
                inTree += e.tail; inTree += e.head
                grew = true
          }
        if inTree.size >= nodeList.size then return
        // find min-slack non-tree edge with exactly one endpoint in tree
        var best = -1
        es.indices.foreach { i =>
          val e = es(i)
          if inTree(e.tail) ^ inTree(e.head) then
            if best < 0 || slack(i) < slack(best) then best = i
        }
        if best < 0 then return // disconnected — leave as forest
        val e     = es(best)
        val delta = if inTree(e.head) then -slack(best) else slack(best)
        inTree.foreach(v => rank(v) = rank(v) + delta)
      end while

    feasibleTree()

    // ── cut values + primal pivots ───────────────────────────────────────
    def tailSide(leaving: Int): mutable.Set[String] =
      componentOf(es(leaving).tail, treeEdges - leaving)

    def cutValue(te: Int): Int =
      val tSide = tailSide(te)
      var cv    = 0
      es.foreach { e =>
        val tl = tSide(e.tail)
        val hd = tSide(e.head)
        if tl && !hd then cv += e.weight
        else if !tl && hd then cv -= e.weight
      }
      cv

    def propagateTight(): Unit =
      val r0 = nodeList.head
      rank(r0) = 0
      val vis = mutable.Set(r0)
      val stk = mutable.Stack(r0)
      while stk.nonEmpty do
        val v = stk.pop()
        treeEdges.foreach { i =>
          val e = es(i)
          if e.tail == v && !vis(e.head) then
            rank(e.head) = rank(v) + e.minlen; vis += e.head; stk.push(e.head)
          else if e.head == v && !vis(e.tail) then
            rank(e.tail) = rank(v) - e.minlen; vis += e.tail; stk.push(e.tail)
        }
      // any nodes not in this tree component keep their init rank

    val maxIter = nodeList.size * es.size + 100
    var iter    = 0
    var pivoting = treeEdges.size == nodeList.size - 1
    while pivoting && iter < maxIter do
      iter += 1
      var leaving = -1
      treeEdges.foreach { te =>
        val cv = cutValue(te)
        if cv < 0 && (leaving < 0 || cv < cutValue(leaving)) then leaving = te
      }
      if leaving < 0 then pivoting = false
      else
        val tSide    = tailSide(leaving)
        var entering = -1
        es.indices.foreach { i =>
          if !treeEdges(i) then
            val e = es(i)
            if !tSide(e.tail) && tSide(e.head) then // head→tail side (opposite of leaving)
              if entering < 0 || slack(i) < slack(entering) then entering = i
        }
        if entering < 0 then pivoting = false
        else
          treeEdges -= leaving
          treeEdges += entering
          propagateTight()

    // ── LR balance (ns.c LR_balance): centre within zero-cutvalue slack ──
    // For each tight tree edge with cutvalue 0, the swap with its entering
    // edge is free; split the slack evenly by shifting e's tail component.
    if balance == NSBalance.LeftRight then
      treeEdges.toVector.sorted.foreach { te =>
        if cutValue(te) == 0 then
          val tSide = tailSide(te)
          var entering = -1
          es.indices.foreach { i =>
            if !treeEdges(i) then
              val e = es(i)
              if !tSide(e.tail) && tSide(e.head) then
                if entering < 0 || slack(i) < slack(entering) then entering = i
          }
          if entering >= 0 then
            val d = slack(entering)
            if d > 1 then
              val sh = d / 2
              tSide.foreach(v => rank(v) = rank(v) - sh)
              if es.indices.exists(i => slack(i) < 0) then
                tSide.foreach(v => rank(v) = rank(v) + sh) // revert if infeasible
      }

    // ── normalize ────────────────────────────────────────────────────────
    val minR = rank.values.min
    nodeList.foreach(v => rank(v) = rank(v) - minR)

    if balance == NSBalance.TopBottom then tbBalance(nodeList, es, rank, outIdx, inIdx)

    nodeList.iterator.map(v => v -> rank(v)).toMap

  /** TB_balance: a node whose in-weight == out-weight and that has a feasible
    * rank window wider than one is moved to the least-populated rank in that
    * window (matches `ns.c` TB_balance for unweighted/balanced nodes).
    */
  private def tbBalance(
      nodes:  Vector[String],
      es:     Vector[NSEdge],
      rank:   mutable.HashMap[String, Int],
      outIdx: mutable.LinkedHashMap[String, mutable.ArrayBuffer[Int]],
      inIdx:  mutable.LinkedHashMap[String, mutable.ArrayBuffer[Int]]
  ): Unit =
    val maxR  = if rank.isEmpty then 0 else rank.values.max
    val count = mutable.HashMap.from((0 to maxR).map(_ -> 0))
    nodes.foreach(v => count(rank(v)) = count.getOrElse(rank(v), 0) + 1)
    nodes.foreach { v =>
      val inW  = inIdx(v).map(es(_).weight).sum
      val outW = outIdx(v).map(es(_).weight).sum
      if inW == outW then
        val low  = inIdx(v).map(i => rank(es(i).tail) + es(i).minlen).maxOption.getOrElse(rank(v))
        val high = outIdx(v).map(i => rank(es(i).head) - es(i).minlen).minOption.getOrElse(rank(v))
        if low < high then
          var bestR = rank(v)
          var r     = low
          while r <= high do
            if count.getOrElse(r, 0) < count.getOrElse(bestR, 0) then bestR = r
            r += 1
          if bestR != rank(v) then
            count(rank(v)) = count(rank(v)) - 1
            count(bestR)   = count.getOrElse(bestR, 0) + 1
            rank(v) = bestR
    }

end NetworkSimplex
