package org.jpablo.graphexplorer.graphviz.layout

import scala.collection.mutable

/** Network simplex — a **faithful 1:1 transcription** of `lib/common/ns.c`
  * (gv 13.0.1): `init_graph`/`init_rank`/`feasible_tree` (subtree-merge with a
  * size min-heap)/the `leave_edge`→`enter_edge`→`update` pivot loop with
  * incremental cut values (`treeupdate`) and low/lim (`dfs_range`)/`TB_balance`/
  * `LR_balance`.
  *
  * The point of transcribing rather than re-deriving: byte-exact output of the
  * order-sensitive parts (which spanning tree, the `Tree_edge` list order that
  * `LR_balance` walks, the tie-breaks) falls out for free, because this runs the
  * same mechanics as gv — not just the same math.
  *
  * The abstract graph maps onto gv's structures directly: nodes are indices
  * `0..N-1` in the given `nodes` order (= `GD_nlist`); each node's `ND_out`/
  * `ND_in` are the edge indices with that tail/head in the given `edges` order.
  * The caller (Rank / XCoord) is responsible for presenting nodes and edges in
  * gv's order — this kernel then reproduces gv's ranks exactly.
  *
  * Minimises `Σ weight·(rank[head] − rank[tail])` s.t.
  * `rank[head] − rank[tail] ≥ minlen`. Kernel shared by rank assignment (M2)
  * and x-coordinate assignment (M4x: `set_xcoords`).
  */
object NetworkSimplex:

  final case class NSEdge(tail: String, head: String, minlen: Int, weight: Int) derives CanEqual

  private val SearchSize = 30 // ns.c SEARCHSIZE

  /** @param initRanks optional per-node starting ranks (gv seeds the aux graph
    *   with `make_LR_constraints`/`make_edge_pairs` ranks; if they are already
    *   feasible, `init_rank` is skipped — this is what the aux-graph x-solve
    *   does, and it changes which `feasible_tree` is built). Empty ⇒ start at 0
    *   (then `init_rank` runs, the rank-assignment path). */
  def solve(nodes: Seq[String], edges: Seq[NSEdge], balance: NSBalance = NSBalance.None,
            initRanks: collection.Map[String, Int] = Map.empty,
            /** TB_balance's `Tree_node` input order (GD_nlist = the ranking
              * graph's decompose order). When given, the balance pass sorts
              * THIS sequence with musl-qsort's exact (tie-unstable)
              * permutation — the oracle's libc — instead of a stable rank
              * sort of `nodes` order. Empty ⇒ legacy stable sort. */
            tbOrder: Seq[String] = Seq.empty): Map[String, Int] =
    if nodes.isEmpty then return Map.empty
    val nodeList = nodes.toVector
    val N        = nodeList.length
    val idx      = nodeList.iterator.zipWithIndex.toMap
    val es       = edges.toVector
    val E        = es.length

    // ── edge arrays (ED_*) ────────────────────────────────────────────────
    val etail     = Array.tabulate(E)(i => idx(es(i).tail))
    val ehead     = Array.tabulate(E)(i => idx(es(i).head))
    val eminlen   = Array.tabulate(E)(i => es(i).minlen)
    val eweight   = Array.tabulate(E)(i => es(i).weight)
    val cutvalue  = Array.fill(E)(0)   // ED_cutvalue
    val treeIndex = Array.fill(E)(-1)  // ED_tree_index (-1 ⇒ not a tree edge)

    // ── ND_out / ND_in (edge indices per node, in input `edges` order) ─────
    val outB = Array.fill(N)(mutable.ArrayBuffer.empty[Int])
    val inB  = Array.fill(N)(mutable.ArrayBuffer.empty[Int])
    var i0 = 0
    while i0 < E do { outB(etail(i0)) += i0; inB(ehead(i0)) += i0; i0 += 1 }
    val outE = outB.map(_.toArray)
    val inE  = inB.map(_.toArray)

    // ── node arrays (ND_*) ────────────────────────────────────────────────
    // Seed ND_rank from the caller (aux-graph packing) or 0. init_graph's
    // feasibility check below decides whether init_rank overwrites this.
    val rank    = Array.tabulate(N)(i => initRanks.getOrElse(nodeList(i), 0)) // ND_rank
    val prio    = Array.fill(N)(0)  // ND_priority
    val low     = Array.fill(N)(0)  // ND_low
    val lim     = Array.fill(N)(0)  // ND_lim
    val par     = Array.fill(N)(-1) // ND_par (parent tree-edge index; -1 = none)
    val treeOut = Array.fill(N)(mutable.ArrayBuffer.empty[Int]) // ND_tree_out
    val treeIn  = Array.fill(N)(mutable.ArrayBuffer.empty[Int]) // ND_tree_in

    inline def slack(e: Int): Int = rank(ehead(e)) - rank(etail(e)) - eminlen(e)
    inline def isTree(e: Int): Boolean = treeIndex(e) >= 0
    inline def seq(l: Int, x: Int, h: Int): Boolean = l <= x && x <= h

    val treeEdge = mutable.ArrayBuffer.empty[Int] // ctx.Tree_edge.list (ORDERED)

    // add_tree_edge (ns.c:58): append to the ordered Tree_edge list + the
    // per-node tree adjacency (tail's tree_out, head's tree_in).
    def addTreeEdge(e: Int): Unit =
      treeIndex(e) = treeEdge.length
      treeEdge += e
      treeOut(etail(e)) += e
      treeIn(ehead(e)) += e

    // exchange_tree_edges (ns.c:120): f takes e's slot in the list; e is removed
    // from its endpoints' tree adjacency by SWAP-remove (last element fills the
    // hole — this reorders tree_out/tree_in exactly as gv does), f appended.
    def exchangeTreeEdges(e: Int, f: Int): Unit =
      treeIndex(f) = treeIndex(e)
      treeEdge(treeIndex(e)) = f
      treeIndex(e) = -1
      val to = treeOut(etail(e)); val jo = to.indexOf(e); to(jo) = to(to.length - 1); to.remove(to.length - 1)
      val ti = treeIn(ehead(e));  val ji = ti.indexOf(e); ti(ji) = ti(ti.length - 1); ti.remove(ti.length - 1)
      treeOut(etail(f)) += f
      treeIn(ehead(f)) += f

    // ── init_graph (ns.c:913): ND_priority = in-degree; feasibility check ──
    var feasible = true
    var v0 = 0
    while v0 < N do
      prio(v0) = inE(v0).length
      var k = 0
      while k < inE(v0).length do
        val e = inE(v0)(k)
        if rank(ehead(e)) - rank(etail(e)) < eminlen(e) then feasible = false
        k += 1
      v0 += 1

    // ── init_rank (ns.c:155): longest-path via a priority (in-degree) queue ─
    if !feasible then
      val q = mutable.Queue.empty[Int]
      var v = 0
      while v < N do { if prio(v) == 0 then q.enqueue(v); v += 1 }
      while q.nonEmpty do
        val u = q.dequeue()
        rank(u) = 0
        var k = 0
        while k < inE(u).length do { val e = inE(u)(k); rank(u) = math.max(rank(u), rank(etail(e)) + eminlen(e)); k += 1 }
        k = 0
        while k < outE(u).length do
          val e = outE(u)(k); prio(ehead(e)) -= 1
          if prio(ehead(e)) <= 0 then q.enqueue(ehead(e))
          k += 1

    // ── feasible_tree: subtree union-find + size min-heap (ns.c:305–643) ────
    val nodeSubtree = Array.fill(N)(-1)                 // ND_subtree (subtree id)
    val stRep  = mutable.ArrayBuffer.empty[Int]         // subtree.rep (a node)
    val stSize = mutable.ArrayBuffer.empty[Int]         // subtree.size
    val stHeap = mutable.ArrayBuffer.empty[Int]         // heap_index (-1 = off heap)
    val stPar  = mutable.ArrayBuffer.empty[Int]         // union-find parent (subtree id)
    def newSubtree(rep: Int): Int =
      stRep += rep; stSize += 0; stHeap += -1; stPar += stRep.length - 1; stRep.length - 1
    inline def onHeap(t: Int): Boolean = stHeap(t) != -1
    def stFind(node: Int): Int =
      var s = nodeSubtree(node); while stPar(s) != s do s = stPar(s); s
    def stFindRoot(t0: Int): Int = { var r = t0; while stPar(r) != r do r = stPar(r); r }
    def stUnion(s0: Int, s1: Int): Int =
      val r0 = stFindRoot(s0); val r1 = stFindRoot(s1)
      if r0 == r1 then r0
      else
        val r = if !onHeap(r1) then r0 else if !onHeap(r0) then r1
                else if stSize(r1) < stSize(r0) then r0 else r1
        stPar(r0) = r; stPar(r1) = r; stSize(r) = stSize(r0) + stSize(r1); r

    // tight_subtree_search (ns.c:329): iterative DFS growing a tight subtree
    // over slack-0 non-tree edges (in-edges before out-edges), add_tree_edge on
    // each; returns the node count. `inI`/`outI` advance exactly as gv's for-
    // loops (kept unincremented on a match so the edge is re-examined on return).
    final class Frame(val v: Int, var inI: Int, var outI: Int, var rv: Int)
    def tightSubtreeSearch(root: Int, st: Int): Int =
      var rv = 1
      nodeSubtree(root) = st
      val todo = mutable.Stack(new Frame(root, 0, 0, 1))
      while todo.nonEmpty do
        val top = todo.top
        var updated = false
        var brk = false
        while !brk && top.inI < inE(top.v).length do
          val e = inE(top.v)(top.inI)
          if isTree(e) then top.inI += 1
          else if nodeSubtree(etail(e)) == -1 && slack(e) == 0 then
            addTreeEdge(e); nodeSubtree(etail(e)) = st
            todo.push(new Frame(etail(e), 0, 0, 1)); updated = true; brk = true
          else top.inI += 1
        if !updated then
          brk = false
          while !brk && top.outI < outE(top.v).length do
            val e = outE(top.v)(top.outI)
            if isTree(e) then top.outI += 1
            else if nodeSubtree(ehead(e)) == -1 && slack(e) == 0 then
              addTreeEdge(e); nodeSubtree(ehead(e)) = st
              todo.push(new Frame(ehead(e), 0, 0, 1)); updated = true; brk = true
            else top.outI += 1
          if !updated then
            val last = todo.pop()
            if todo.isEmpty then rv = last.rv else todo.top.rv += last.rv
      rv

    // inter_tree_edge_search (ns.c:452): tightest non-tree edge from `tree` to a
    // DIFFERENT subtree; search forward through tree edges (out then in),
    // recursing, short-circuiting on a slack-0 find.
    def interTreeEdgeSearch(v: Int, from: Int, best0: Int): Int =
      var best = best0
      val ts   = stFind(v)
      if best >= 0 && slack(best) == 0 then return best
      var k = 0
      while k < outE(v).length do
        val e = outE(v)(k)
        if isTree(e) then { if ehead(e) != from then best = interTreeEdgeSearch(ehead(e), v, best) }
        else if stFind(ehead(e)) != ts then { if best < 0 || slack(e) < slack(best) then best = e }
        k += 1
      k = 0
      while k < inE(v).length do
        val e = inE(v)(k)
        if isTree(e) then { if etail(e) != from then best = interTreeEdgeSearch(etail(e), v, best) }
        else if stFind(etail(e)) != ts then { if best < 0 || slack(e) < slack(best) then best = e }
        k += 1
      best

    // tree_adjust (ns.c:541): shift ranks of a whole tree by delta.
    def treeAdjust(v: Int, from: Int, delta: Int): Unit =
      rank(v) += delta
      var k = 0
      while k < treeIn(v).length do { val e = treeIn(v)(k); if etail(e) != from then treeAdjust(etail(e), v, delta); k += 1 }
      k = 0
      while k < treeOut(v).length do { val e = treeOut(v)(k); if ehead(e) != from then treeAdjust(ehead(e), v, delta); k += 1 }

    // merge_trees (ns.c:560): move the off-heap tree onto the other by SLACK(e),
    // add e as a tree edge, union the subtrees.
    def mergeTrees(e: Int): Int =
      val t0 = stFind(etail(e)); val t1 = stFind(ehead(e))
      if !onHeap(t0) then { val d = slack(e); if d != 0 then treeAdjust(stRep(t0), -1, d) }
      else { val d = -slack(e); if d != 0 then treeAdjust(stRep(t1), -1, d) }
      addTreeEdge(e)
      stUnion(t0, t1)

    // size min-heap over subtrees (ns.c:417–538)
    val heapElt = mutable.ArrayBuffer.empty[Int]
    def heapify(i0: Int): Unit =
      var i = i0
      var go = true
      while go && i < heapElt.length do
        val l = 2 * (i + 1) - 1; val r = 2 * (i + 1)
        var smallest = i
        if l < heapElt.length && stSize(heapElt(l)) < stSize(heapElt(smallest)) then smallest = l
        if r < heapElt.length && stSize(heapElt(r)) < stSize(heapElt(smallest)) then smallest = r
        if smallest != i then
          val t = heapElt(i); heapElt(i) = heapElt(smallest); heapElt(smallest) = t
          stHeap(heapElt(i)) = i; stHeap(heapElt(smallest)) = smallest; i = smallest
        else go = false
    def buildHeap(): Unit =
      var i = 0; while i < heapElt.length do { stHeap(heapElt(i)) = i; i += 1 }
      i = heapElt.length / 2 - 1
      while i >= 0 do { heapify(i); i -= 1 }
    def extractMin(): Int =
      val rv = heapElt(0)
      stHeap(rv) = -1
      heapElt(0) = heapElt(heapElt.length - 1); stHeap(heapElt(0)) = 0
      heapElt.remove(heapElt.length - 1)
      heapify(0)
      rv

    // find all tight subtrees (ns.c:608), then merge smallest-first (ns.c:621).
    var n0 = 0
    while n0 < N do
      if nodeSubtree(n0) == -1 then
        val st = newSubtree(n0)
        stSize(st) = tightSubtreeSearch(n0, st)
        heapElt += st
      n0 += 1
    buildHeap()
    while heapElt.length > 1 do
      val tree0 = extractMin()
      val ee    = interTreeEdgeSearch(stRep(tree0), -1, -1)
      if ee < 0 then heapElt.clear() // disconnected — stop (matches gv error break)
      else { val tree1 = mergeTrees(ee); heapify(stHeap(tree1)) }

    // ── init_cutvalues (ns.c:294): dfs_range_init then dfs_cutval ───────────
    // dfs_range_init (ns.c:1174, iterative): assign par/low/lim over the tree.
    def dfsRangeInit(root: Int): Unit =
      par(root) = -1; low(root) = 1
      val todo = mutable.Stack((root, -1, 1, Array(0, 0))) // (v, parEdge, lim, [outI,inI])
      var limMax = 0
      while todo.nonEmpty do
        val (v, pe, curLim, it) = todo.top
        var pushed = false
        while !pushed && it(0) < treeOut(v).length do
          val e = treeOut(v)(it(0)); it(0) += 1
          if e != pe then
            val n = ehead(e); par(n) = e; low(n) = curLim
            todo.push((n, e, curLim, Array(0, 0))); pushed = true
        if !pushed then
          while !pushed && it(1) < treeIn(v).length do
            val e = treeIn(v)(it(1)); it(1) += 1
            if e != pe then
              val n = etail(e); par(n) = e; low(n) = curLim
              todo.push((n, e, curLim, Array(0, 0))); pushed = true
          if !pushed then
            lim(v) = curLim; limMax = curLim
            todo.pop()
            if todo.nonEmpty then
              val (pv, ppe, _, pit) = todo.top
              todo.pop(); todo.push((pv, ppe, limMax + 1, pit))

    // x_val / x_cutval / dfs_cutval (ns.c:1104/1075/1142)
    def xVal(e: Int, v: Int, dir: Int): Int =
      val other = if etail(e) == v then ehead(e) else etail(e)
      var rv = 0; var f = 0
      if !seq(low(v), lim(other), lim(v)) then { f = 1; rv = eweight(e) }
      else { rv = (if isTree(e) then cutvalue(e) else 0) - eweight(e) }
      var d = if dir > 0 then (if ehead(e) == v then 1 else -1) else (if etail(e) == v then 1 else -1)
      if f != 0 then d = -d
      if d < 0 then -rv else rv
    def xCutval(f: Int): Unit =
      val (v, dir) = if par(etail(f)) == f then (etail(f), 1) else (ehead(f), -1)
      var sum = 0
      var k = 0
      while k < outE(v).length do { sum += xVal(outE(v)(k), v, dir); k += 1 }
      k = 0
      while k < inE(v).length do { sum += xVal(inE(v)(k), v, dir); k += 1 }
      cutvalue(f) = sum
    def dfsCutval(v: Int, pe: Int): Unit =
      var k = 0
      while k < treeOut(v).length do { val e = treeOut(v)(k); if e != pe then dfsCutval(ehead(e), e); k += 1 }
      k = 0
      while k < treeIn(v).length do { val e = treeIn(v)(k); if e != pe then dfsCutval(etail(e), e); k += 1 }
      if pe >= 0 then xCutval(pe)

    if treeEdge.length == N - 1 then
      dfsRangeInit(0)
      dfsCutval(0, -1)

    // ── leave_edge (ns.c:190): round-robin scan for a negative cut value ────
    var sI = 0
    def leaveEdge(): Int =
      var rv = -1; var cnt = 0; val j = sI
      while sI < treeEdge.length do
        val f = treeEdge(sI)
        if cutvalue(f) < 0 then
          if rv < 0 || cutvalue(rv) > cutvalue(f) then rv = f
          cnt += 1; if cnt >= SearchSize then return rv
        sI += 1
      if j > 0 then
        sI = 0
        while sI < j do
          val f = treeEdge(sI)
          if cutvalue(f) < 0 then
            if rv < 0 || cutvalue(rv) > cutvalue(f) then rv = f
            cnt += 1; if cnt >= SearchSize then return rv
          sI += 1
      rv

    // ── enter_edge (ns.c:270) + dfs_enter_out/inedge (ns.c:226/248) ─────────
    var enterE = -1; var enterSlack = 0; var LowC = 0; var LimC = 0
    def dfsEnterOut(v: Int): Unit =
      var k = 0
      while k < outE(v).length do
        val e = outE(v)(k)
        if !isTree(e) then
          if !seq(LowC, lim(ehead(e)), LimC) then
            val s = slack(e)
            if s < enterSlack || enterE < 0 then { enterE = e; enterSlack = s }
        else if lim(ehead(e)) < lim(v) then dfsEnterOut(ehead(e))
        k += 1
      k = 0
      while k < treeIn(v).length && enterSlack > 0 do
        val e = treeIn(v)(k); if lim(etail(e)) < lim(v) then dfsEnterOut(etail(e)); k += 1
    def dfsEnterIn(v: Int): Unit =
      var k = 0
      while k < inE(v).length do
        val e = inE(v)(k)
        if !isTree(e) then
          if !seq(LowC, lim(etail(e)), LimC) then
            val s = slack(e)
            if s < enterSlack || enterE < 0 then { enterE = e; enterSlack = s }
        else if lim(etail(e)) < lim(v) then dfsEnterIn(etail(e))
        k += 1
      k = 0
      while k < treeOut(v).length && enterSlack > 0 do
        val e = treeOut(v)(k); if lim(ehead(e)) < lim(v) then dfsEnterIn(ehead(e)); k += 1
    def enterEdge(e: Int): Int =
      val (v, outsearch) = if lim(etail(e)) < lim(ehead(e)) then (etail(e), false) else (ehead(e), true)
      enterE = -1; enterSlack = Int.MaxValue; LowC = low(v); LimC = lim(v)
      if outsearch then dfsEnterOut(v) else dfsEnterIn(v)
      enterE

    // ── update (ns.c:686): pivot e (leaving) ↔ f (entering) ─────────────────
    def rerank(v: Int, delta: Int): Unit =
      rank(v) -= delta
      var k = 0
      while k < treeOut(v).length do { val e = treeOut(v)(k); if e != par(v) then rerank(ehead(e), delta); k += 1 }
      k = 0
      while k < treeIn(v).length do { val e = treeIn(v)(k); if e != par(v) then rerank(etail(e), delta); k += 1 }
    def treeUpdate(v0: Int, w: Int, cv: Int, dir: Int): Int =
      var v = v0
      while !seq(low(v), lim(w), lim(v)) do
        val e = par(v)
        val d = if v == etail(e) then dir else 1 - dir
        if d != 0 then cutvalue(e) += cv else cutvalue(e) -= cv
        v = if lim(etail(e)) > lim(ehead(e)) then etail(e) else ehead(e)
      v
    def invalidatePath(lca: Int, to0: Int): Unit =
      var to = to0
      var go = true
      while go do
        if low(to) == -1 then go = false
        else
          low(to) = -1
          val e = par(to)
          if e < 0 then go = false
          else if lim(to) >= lim(lca) then go = false
          else to = if lim(etail(e)) > lim(ehead(e)) then etail(e) else ehead(e)
    def dfsRange(v0: Int, par0: Int, low0: Int): Unit =
      if par(v0) == par0 && low(v0) == low0 then return
      par(v0) = par0; low(v0) = low0
      val todo = mutable.Stack((v0, par0, low0, Array(0, 0)))
      var limMax = 0
      while todo.nonEmpty do
        val (v, pe, curLim, it) = todo.top
        var processed = false
        while !processed && it(0) < treeOut(v).length do
          val e = treeOut(v)(it(0)); it(0) += 1
          if e != pe then
            val n = ehead(e)
            if par(n) == e && low(n) == curLim then
              todo.pop(); todo.push((v, pe, lim(n) + 1, it))
            else { par(n) = e; low(n) = curLim; todo.push((n, e, curLim, Array(0, 0))) }
            processed = true
        if !processed then
          while !processed && it(1) < treeIn(v).length do
            val e = treeIn(v)(it(1)); it(1) += 1
            if e != pe then
              val n = etail(e)
              if par(n) == e && low(n) == curLim then
                todo.pop(); todo.push((v, pe, lim(n) + 1, it))
              else { par(n) = e; low(n) = curLim; todo.push((n, e, curLim, Array(0, 0))) }
              processed = true
          if !processed then
            lim(v) = curLim; limMax = curLim
            todo.pop()
            if todo.nonEmpty then
              val (pv, ppe, _, pit) = todo.top
              todo.pop(); todo.push((pv, ppe, limMax + 1, pit))
    def update(e: Int, f: Int): Unit =
      val delta = slack(f)
      if delta > 0 then
        val s1 = treeIn(etail(e)).length + treeOut(etail(e)).length
        if s1 == 1 then rerank(etail(e), delta)
        else
          val s2 = treeIn(ehead(e)).length + treeOut(ehead(e)).length
          if s2 == 1 then rerank(ehead(e), -delta)
          else if lim(etail(e)) < lim(ehead(e)) then rerank(etail(e), delta)
          else rerank(ehead(e), -delta)
      val cv  = cutvalue(e)
      val lca = treeUpdate(etail(f), ehead(f), cv, 1)
      treeUpdate(ehead(f), etail(f), cv, 0)
      val lcaLow = low(lca)
      invalidatePath(lca, ehead(f)); invalidatePath(lca, etail(f))
      cutvalue(f) = -cv; cutvalue(e) = 0
      exchangeTreeEdges(e, f)
      dfsRange(lca, par(lca), lcaLow)

    // ── pivot loop (ns.c:1020) ─────────────────────────────────────────────
    if treeEdge.length == N - 1 then
      val maxiter = 4 * N * (E + 1) + 1000
      var iter = 0
      var e = leaveEdge()
      while e >= 0 && iter < maxiter do
        val f = enterEdge(e)
        if f < 0 then e = -1
        else { update(e, f); iter += 1; e = leaveEdge() }

    // ── scan_and_normalize (ns.c:730) ──────────────────────────────────────
    def scanAndNormalize(): Int =
      var mn = Int.MaxValue; var mx = Int.MinValue
      var v = 0
      while v < N do { mn = math.min(mn, rank(v)); mx = math.max(mx, rank(v)); v += 1 }
      v = 0; while v < N do { rank(v) -= mn; v += 1 }
      mx - mn

    // ── LR_balance (ns.c:768): centre zero-cut-value tree edges by δ/2 ──────
    def lrBalance(): Unit =
      var i = 0
      while i < treeEdge.length do
        val e = treeEdge(i)
        if cutvalue(e) == 0 then
          val f = enterEdge(e)
          if f >= 0 then
            val delta = slack(f)
            if delta > 1 then
              if lim(etail(e)) < lim(ehead(e)) then rerank(etail(e), delta / 2)
              else rerank(ehead(e), -(delta / 2))
        i += 1

    // ── TB_balance (ns.c:835): scan_and_normalize, then move balanced
    //    (in-weight == out-weight) nodes to the least-populated feasible rank.
    //    `min`/`max` extreme-pin (`TBbalance`) is not modelled (no corpus). ──
    def tbBalance(): Unit =
      val maxRank = scanAndNormalize()
      val nrank   = Array.fill(maxRank + 1)(0)
      // gv fills Tree_node from GD_nlist and qsorts by rank ONLY
      // (increasingrankcmpf) — the equal-rank permutation is the libc's.
      // With `tbOrder` (the decompose-order nlist) we replay the ORACLE's
      // musl qsort exactly; otherwise the legacy stable sort (equivalent
      // wherever ties don't matter — the whole pre-sbt corpus).
      val order: IndexedSeq[Int] =
        if tbOrder.nonEmpty then
          val arr = tbOrder.iterator.flatMap(idx.get).toArray
          MuslSort.sort(arr, (x, y) => Integer.compare(rank(x), rank(y)))
          arr.toIndexedSeq
        else (0 until N).sortBy(rank)
      order.foreach(n => nrank(rank(n)) += 1)
      order.foreach { n =>
        var inw = 0; var outw = 0; var lo = 0; var hi = maxRank
        var k = 0
        while k < inE(n).length do { val e = inE(n)(k); inw += eweight(e); lo = math.max(lo, rank(etail(e)) + eminlen(e)); k += 1 }
        k = 0
        while k < outE(n).length do { val e = outE(n)(k); outw += eweight(e); hi = math.min(hi, rank(ehead(e)) - eminlen(e)); k += 1 }
        if lo < 0 then lo = 0
        if inw == outw then
          var choice = lo
          var r = lo + 1
          while r <= hi do { if nrank(r) < nrank(choice) then choice = r; r += 1 }
          nrank(rank(n)) -= 1; nrank(choice) += 1
          rank(n) = choice
      }

    // dispatch (ns.c:1039): TB balances + normalizes; LR centres, no normalize;
    // None just normalizes to min 0.
    balance match
      case NSBalance.TopBottom => tbBalance()
      case NSBalance.LeftRight => lrBalance()
      case NSBalance.None      => scanAndNormalize()

    nodeList.iterator.zipWithIndex.map((name, i) => name -> rank(i)).toMap

end NetworkSimplex
