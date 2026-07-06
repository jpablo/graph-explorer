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

    // ── cut values via a rooted low/lim tree (ns.c dfs_range + dfs_cutval) ──
    // All tree-edge cut values in ONE O(V+E) postorder pass, reusing children's
    // cut values — replaces the O(V·(V+E))-per-pivot recomputation that hung on
    // dense real graphs (aux graph ~800 nodes for 36-node inputs; PORT.md §6).
    val adj    = mutable.HashMap.from(nodeList.map(_ -> mutable.ArrayBuffer.empty[(Int, String)]))
    val parent = mutable.HashMap.empty[String, Int] // node → tree edge to parent (-1 = root)
    val low    = mutable.HashMap.empty[String, Int]
    val lim    = mutable.HashMap.empty[String, Int] // postorder interval: w ∈ v's subtree ⇔ low(v)≤lim(w)≤lim(v)
    val post   = mutable.ArrayBuffer.empty[String]

    /** Rebuild the undirected tree adjacency from `treeEdges` (O(E)). */
    def rebuildAdj(): Unit =
      adj.valuesIterator.foreach(_.clear())
      treeEdges.foreach { i => val e = es(i); adj(e.tail) += ((i, e.head)); adj(e.head) += ((i, e.tail)) }

    /** dfs_range: root the current `treeEdges`, assign parent + low/lim
      * (iterative — the aux tree can be an ~800-deep chain). */
    def buildRange(): Unit =
      rebuildAdj()
      parent.clear(); low.clear(); lim.clear(); post.clear()
      var counter = 1
      val visited = mutable.Set.empty[String]
      nodeList.foreach { r =>
        if !visited(r) then
          visited += r; low(r) = counter; parent(r) = -1
          val stack = mutable.Stack((r, adj(r).iterator))
          while stack.nonEmpty do
            val (v, it) = stack.top
            if it.hasNext then
              val (ei, w) = it.next()
              if !visited(w) then
                visited += w; low(w) = counter; parent(w) = ei
                stack.push((w, adj(w).iterator))
            else
              lim(v) = counter; counter += 1; post += v; stack.pop()
      }

    /** dfs_cutval: cut value of every tree edge, postorder (children first). */
    def computeCutvals(): mutable.HashMap[Int, Int] =
      val cv = mutable.HashMap.empty[Int, Int]
      post.foreach { v =>
        val f = parent.getOrElse(v, -1)
        if f >= 0 then
          val dir = if es(f).tail == v then 1 else -1
          var sum = 0
          def acc(i: Int): Unit =
            val e     = es(i)
            val other = if e.tail == v then e.head else e.tail
            val inSub = low(v) <= lim(other) && lim(other) <= lim(v)
            var rv    = if !inSub then e.weight else (if treeEdges(i) then cv.getOrElse(i, 0) else 0) - e.weight
            var d     = if dir > 0 then (if e.head == v then 1 else -1) else (if e.tail == v then 1 else -1)
            if !inSub then d = -d
            if d < 0 then rv = -rv
            sum += rv
          outIdx(v).foreach(acc); inIdx(v).foreach(acc)
          cv(f) = sum
      }
      cv

    /** The deeper endpoint of tree edge `te` (whose parent-edge is `te`). */
    def deeperTail(te: Int): (String, Boolean) =
      val v = if parent.getOrElse(es(te).tail, -1) == te then es(te).tail else es(te).head
      (v, es(te).tail == v)

    def propagateTight(): Unit =
      rebuildAdj() // tree changed since the last buildRange — refresh adjacency
      val r0  = nodeList.head
      rank(r0) = 0
      val vis = mutable.Set(r0)
      val stk = mutable.Stack(r0)
      while stk.nonEmpty do
        val v = stk.pop()
        adj(v).foreach { case (i, w) => // O(V+E), not O(V·tree)
          if !vis(w) then
            val e = es(i)
            rank(w) = if e.tail == v then rank(v) + e.minlen else rank(v) - e.minlen
            vis += w; stk.push(w)
        }
      // any nodes not in this tree component keep their init rank

    // `onTail(te)`: is node `w` on the tail-side of tree edge `te`? = deeper
    // node's subtree membership (via low/lim) iff the deeper node is the tail.
    def onTailSide(te: Int): String => Boolean =
      val (v, tailIsSub) = deeperTail(te)
      (w: String) => (low(v) <= lim(w) && lim(w) <= lim(v)) == tailIsSub

    /** min-slack non-tree edge crossing from head-side to tail-side of `te`. */
    def enteringFor(te: Int): Int =
      val onTail = onTailSide(te)
      var entering = -1
      es.indices.foreach { i =>
        if !treeEdges(i) then
          val e = es(i)
          if !onTail(e.tail) && onTail(e.head) then
            if entering < 0 || slack(i) < slack(entering) then entering = i
      }
      entering

    val maxIter  = nodeList.size * es.size + 100
    var iter     = 0
    var pivoting = treeEdges.size == nodeList.size - 1
    while pivoting && iter < maxIter do
      iter += 1
      buildRange()
      val cv = computeCutvals()
      var leaving = -1; var bestCv = 0
      treeEdges.foreach { te =>
        val c = cv.getOrElse(te, 0)
        if c < 0 && (leaving < 0 || c < bestCv) then { leaving = te; bestCv = c }
      }
      if leaving < 0 then pivoting = false
      else
        val entering = enteringFor(leaving)
        if entering < 0 then pivoting = false
        else
          treeEdges -= leaving
          treeEdges += entering
          propagateTight()

    // ── LR balance (ns.c LR_balance): centre within zero-cutvalue slack ──
    // For each tight tree edge with cutvalue 0, the swap with its entering
    // edge is free; split the slack evenly by shifting its tail component.
    if balance == NSBalance.LeftRight then
      buildRange()
      val cv = computeCutvals()
      treeEdges.toVector.sorted.foreach { te =>
        if cv.getOrElse(te, 0) == 0 then
          val entering = enteringFor(te)
          if entering >= 0 then
            val d = slack(entering)
            if d > 1 then
              val sh      = d / 2
              val onTail  = onTailSide(te)
              val tSide   = nodeList.filter(onTail)
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
