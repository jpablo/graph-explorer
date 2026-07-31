package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.{RGraph, REdge}
import scala.collection.mutable

/** Phase 2 of the `dot` pipeline: within-rank ordering / crossing
  * minimisation. Faithful transcription of `lib/dotgen/mincross.c` (gv 13.0.1):
  * long-edge virtual-node chains (`class2`), the `decompose`-order (`GD_nlist`)
  * BFS initial orders (`build_ranks` pass 0 = in-free seeds/out-edges, pass 1 =
  * out-free seeds/in-edges), the `mincross` pass-0/1/2 driver (`save_best`/
  * `restore_best`), the weighted-median `reorder`, and `transpose` adjacent-swap
  * refinement.
  *
  * **Clusters** (`dot_mincross` + `class2.c`/`cluster.c`): [[orderClustered]]
  * collapses each top-level cluster to a skeleton column so the top-level
  * mincross can't interleave clusters, orders each cluster's interior
  * recursively, then expands. Byte-exact for contiguity (94), multi-rank
  * clusters with free crossing edges (95) and nesting (96).
  *
  * Deferred (⬜, PORT.md §5.2): **flat edges** (`flat_reorder`/`flat_breakcycles`
  * for same-rank adjacencies — 06's residual), and the **rank=min/sink** within-
  * rank mirror (81/84: `minmax_edges` reverses the pinned node's edges in
  * `ND_in`-LIFO order, which my working-graph adjacency doesn't reproduce —
  * closing it needs decoupling the build_ranks seed order from `segOwner`).
  */
object Order:

  /** Per-segment port view for port-aware mincross (mincross.c `VAL`,
    * `in_cross`/`out_cross` ties, `local_cross`): each end's CANONICAL-frame
    * port `p.x` plus the MC ordinal (`port.order`, shapes.c:2863). Default =
    * gv's undefined port: p=(0,0) → order = MC_SCALE/2 = 128. */
  final case class SegPorts(tailPX: Double, tailOrd: Int, headPX: Double, headOrd: Int) derives CanEqual
  object SegPorts:
    /** gv's ZEROED `port` struct — what an edge end with no port spec carries
      * into `VAL(node, port) = MC_SCALE*ND_order + port.order`. `.order` is 0
      * there; `MC_SCALE/2` (128) is what `compassPort` assigns to a port that
      * IS specified and resolves to the node centre (shapes.c:2863), which is
      * a different thing. Using 128 for both put every mid-chain segment
      * half a rank-slot off gv whenever it competed with a real port. */
    val default: SegPorts = SegPorts(0.0, 0, 0.0, 0)

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
      segOwner:  Vector[Int],
      /** class2 multi-edge merge (class2.c:207): dedge idx → its class
        * REPRESENTATIVE (identity when unmerged). Merged-away dedges have no
        * chain of their own — the rep's chain carries the summed
        * `ED_weight`/`ED_xpenalty`; splines route the rep once and install
        * copies offset by `Multisep`. */
      mergedInto: Vector[Int],
      /** dedge indices of the labelled NON-adjacent FLAT edges that got a
        * label vnode, in `flat_node` CREATION order. `make_vn_slot` calls
        * `virtual_node` → `fast_node`, which PREPENDS, so `GD_nlist` — and
        * therefore `make_edge_pairs` — sees them at the head REVERSED. */
      flatLabels: Vector[Int],
      /** `GD_nlist` as `dot_position` sees it (the flat-label vnodes excluded —
        * they head the list and [[flatLabels]] carries them). `make_edge_pairs`
        * walks this, and the walk fixes the slack nodes' creation order and so
        * the aux network simplex's node indices. With clusters it is NOT the
        * decompose order: `merge_ranks` PREPENDS each expanded cluster's block. */
      nlist: Vector[LayoutNode]
  ) derives CanEqual:
    /** rank index → real-node ids (Strings), preserving left-to-right order. */
    def realOrder: Map[Int, Vector[String]] =
      order.view.mapValues(_.collect { case LayoutNode.Real(id) => id }).toMap

  /** `GD_nlist` as it stands when `dot_position` runs. `merge_ranks`
    * PREPENDS (`fast_node`) each expanded cluster's nodes in (rank asc,
    * within-rank asc) order, and `expand_cluster` runs clusters in
    * declaration order — so the head of the list is the LAST cluster's
    * block, itself reversed. Only `flat_edges` reads it. */
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

    // gv iterates class2 per NODE (cgraph creation order), and each node's
    // out-edges in the cgraph edge-set order: (HEAD-node seq, edge seq) — NOT
    // edge-declaration order (edge.c `agedgeseqcmpf`: the out-half's key node
    // is the head). This decides ND_out append order and hence build_ranks'
    // BFS enqueue order — with pre-declared nodes (e.g. cluster members before
    // the edges, groups.dot) it differs from edge order and fixes the
    // left-right mirror. Reversed (acyclic) edges are iterated at their
    // ORIGINAL tail, sorted by their ORIGINAL head (class2 walks the cgraph
    // edges and swaps ends by rank), while the chain itself uses the working
    // (post-reversal) direction.
    val nodeSeq: Map[String, Int] = g.nodes.iterator.map(_.id).zipWithIndex.toMap
    val origEnds: Vector[(String, String)] =
      g.edges.filter(e => e.tail != e.head).map(e => (e.tail, e.head))
    val byOrigTail: Map[String, Vector[Int]] =
      dedges.indices.toVector.groupBy(i => origEnds(i)._1)
    // Canonical-frame ports per segment (mincross VAL / cross ties /
    // local_cross): a chain's FIRST segment carries the working tail's port,
    // the LAST the working head's; middles default. Reversed (acyclic)
    // dedges swap ends wholesale, so the working tail port = the ORIGINAL
    // head port (observed in gv: `node1->%0 px=40.80` for a reversed edge).
    val byIdN = g.nodes.iterator.map(n => n.id -> n).toMap
    val realEdges = g.edges.filter(e => e.tail != e.head)
    val segPortsMap = mutable.HashMap.empty[(LayoutNode, LayoutNode), SegPorts]
    def canonOf(nodeId: String, port: Option[org.jpablo.graphexplorer.graphviz.dotlang.Port]): (Double, Int) =
      if port.isEmpty then (0.0, 0) // no port spec ⇒ gv's zeroed struct
      else byIdN.get(nodeId) match
        case Some(n) => val (px, _, ord) = PortAnchor.canonical(n, g, port); (px, ord)
        case None    => (0.0, 0)
    def recordSeg(t: LayoutNode, h: LayoutNode, sp: SegPorts): Unit =
      if sp != SegPorts.default then segPortsMap((t, h)) = sp

    val flatReps = mutable.ArrayBuffer.empty[(LayoutNode, LayoutNode, Int)]
    def emit(idx: Int): Unit =
      val e    = dedges(idx)
      curOwner = idx
      val tail = LayoutNode.Real(e.tail)
      val head = LayoutNode.Real(e.head)
      val rt = rankOf(tail)
      val rh = rankOf(head)
      val re = realEdges(idx)
      val reversed = e.tail != re.tail
      val (wtPort, whPort) = if reversed then (re.headPort, re.tailPort) else (re.tailPort, re.headPort)
      lazy val (wtPX, wtOrd) = canonOf(e.tail, wtPort)
      lazy val (whPX, whOrd) = canonOf(e.head, whPort)
      if rh - rt <= 1 then
        if rh != rt then
          connect(tail, head)
          if wtPort.isDefined || whPort.isDefined then
            recordSeg(tail, head, SegPorts(wtPX, wtOrd, whPX, whOrd))
        else
          // flat edge (class2.c:246 flat_edge): same-rank rep — feeds the
          // mincross flat machinery (flat_breakcycles/flat_reorder/left2right)
          flatReps += ((tail, head, idx))
      else
        var prev: LayoutNode = tail
        var r    = rt + 1
        while r < rh do
          val v = LayoutNode.Virtual(idx, r)
          node(v)
          rankOf(v) = r
          connect(prev, v)
          if r == rt + 1 && wtPort.isDefined then
            recordSeg(tail, v, SegPorts(wtPX, wtOrd, 0.0, 0))
          prev = v
          r += 1
        connect(prev, head)
        if whPort.isDefined then
          recordSeg(prev, head, SegPorts(0.0, 0, whPX, whOrd))
    // class2 multi-edge merge (class2.c:207): CONSECUTIVE out-edges (in the
    // agfstout iteration) with the SAME original endpoints merge — flat
    // parallels via merge_oneway, inter-rank parallels via merge_chain when
    // both are unlabeled with equal ports (ED_weight/ED_xpenalty accumulate
    // on the rep's chain; the merged edge gets NO chain). `prev` stays the
    // rep after a merge, so a 3rd duplicate joins the same class.
    val mergedInto = Array.tabulate(dedges.length)(identity)
    def unlabeled(i: Int): Boolean =
      realEdges(i).attrs.get("label").forall(_.isEmpty)
    def portsEq(i: Int, j: Int): Boolean =
      realEdges(i).tailPort == realEdges(j).tailPort &&
        realEdges(i).headPort == realEdges(j).headPort
    g.nodes.foreach { n =>
      var prev = -1
      byOrigTail.getOrElse(n.id, Vector.empty)
        .sortBy(i => (nodeSeq.getOrElse(origEnds(i)._2, Int.MaxValue), i))
        .foreach { idx =>
          val flat = rankOf(LayoutNode.Real(dedges(idx).tail)) == rankOf(LayoutNode.Real(dedges(idx).head))
          if prev >= 0 && origEnds(idx) == origEnds(prev) &&
             (flat || (unlabeled(idx) && unlabeled(prev) && portsEq(idx, prev))) then
            mergedInto(idx) = mergedInto(prev) // merge_oneway / merge_chain; prev unchanged
          else
            emit(idx)
            prev = idx
        }
    }

    // gv `build_ranks` iterates GD_nlist for its BFS seeds — the `decompose`
    // (decomp.c) DFS order over the class2 real+virtual graph: from declaration-
    // order real seeds. search_component walks FOUR lists per node — ND_out,
    // ND_in, ND_flat_out, ND_flat_in (in that pop order) — flat (same-rank)
    // edges carry the DFS across rank=same siblings (sdh: spiTTP_1_2's flat
    // chain pulls spiTTP_2_1/me_2 in before the next declared seed).
    val flatOutAdj = mutable.HashMap.empty[LayoutNode, mutable.ArrayBuffer[LayoutNode]]
    val flatInAdj  = mutable.HashMap.empty[LayoutNode, mutable.ArrayBuffer[LayoutNode]]
    flatReps.foreach { (t, h, _) =>
      flatOutAdj.getOrElseUpdate(t, mutable.ArrayBuffer.empty) += h
      flatInAdj.getOrElseUpdate(h, mutable.ArrayBuffer.empty) += t
    }
    val gdNlist: Vector[LayoutNode] =
      val done = mutable.Set.empty[LayoutNode]
      val res  = mutable.ArrayBuffer.empty[LayoutNode]
      val emptyAdj = mutable.ArrayBuffer.empty[LayoutNode]
      def visit(seed: LayoutNode): Unit =
        val stk = mutable.Stack(seed)
        while stk.nonEmpty do
          val n = stk.pop()
          if !done(n) then
            done += n; res += n
            flatInAdj.getOrElse(n, emptyAdj).reverseIterator.foreach(w => if !done(w) then stk.push(w))
            flatOutAdj.getOrElse(n, emptyAdj).reverseIterator.foreach(w => if !done(w) then stk.push(w))
            in(n).reverseIterator.foreach(w => if !done(w) then stk.push(w))
            out(n).reverseIterator.foreach(w => if !done(w) then stk.push(w))
      g.nodes.foreach { nn => val s: LayoutNode = LayoutNode.Real(nn.id); if !done(s) then visit(s) }
      (g.nodes.map(n => LayoutNode.Real(n.id): LayoutNode) ++
        rankOf.keysIterator.collect { case v: LayoutNode.Virtual => v }
          .toVector.sortBy(v => (v.edgeIdx, v.rank)))
        .foreach(n => if !done(n) then { done += n; res += n }) // stable append for unreached
      res.toVector

    // merged-class sizes → per-segment ED_xpenalty (merge_chain sums the
    // members' xpenalty=1 on every rep segment).
    val classSize = Array.fill(dedges.length)(0)
    mergedInto.foreach(r => classSize(r) += 1)
    val segXpen: Map[(LayoutNode, LayoutNode), Long] =
      segs.iterator.zip(segOwn).map((s, o) => s -> classSize(o).toLong).toMap

    // flat rep weight = Σ member `weight` attrs (merge_oneway accumulates
    // ED_weight on the rep) — only zero/non-zero gates `constraining`.
    val flatPairs: Vector[(LayoutNode, LayoutNode, Double)] =
      flatReps.iterator.map { (t, h, rep) =>
        val w = mergedInto.indices.iterator.filter(mergedInto(_) == rep)
          .map(j => realEdges(j).attrs.get("weight").flatMap(_.toDoubleOption).getOrElse(1.0)).sum
        (t, h, w)
      }.toVector

    val flip = Rank.flip(g)
    val (order, cross, posNlist) =
      if Cluster.clusters(g).isEmpty then
        // ND_has_port: any edge (incl. self-loops) naming a port on the node.
        val ported: Set[LayoutNode] =
          g.edges.iterator.flatMap { e =>
            (if e.tailPort.isDefined then Iterator(LayoutNode.Real(e.tail): LayoutNode) else Iterator.empty) ++
              (if e.headPort.isDefined then Iterator(LayoutNode.Real(e.head): LayoutNode) else Iterator.empty)
          }.toSet
        val (o, c) = runMincross(out, in, rankOf, gdNlist, flip,
          weight   = (t, h) => segXpen.getOrElse((t, h), 1L),
          segPorts = (t, h) => segPortsMap.getOrElse((t, h), SegPorts.default),
          hasPort  = ported.contains,
          flatPairs = flatPairs)
        // no clusters ⇒ no merge_ranks ⇒ GD_nlist is still the decompose order
        (o, c, gdNlist)
      else
        val portedC: Set[LayoutNode] =
          g.edges.iterator.flatMap { e =>
            (if e.tailPort.isDefined then Iterator(LayoutNode.Real(e.tail): LayoutNode) else Iterator.empty) ++
              (if e.headPort.isDefined then Iterator(LayoutNode.Real(e.head): LayoutNode) else Iterator.empty)
          }.toSet
        orderClustered(g, out, in, rankOf, flip, segs.toVector,
          (t, h) => segXpen.getOrElse((t, h), 1L),
          (t, h) => segPortsMap.getOrElse((t, h), SegPorts.default),
          portedC.contains, flatPairs)
    val (order2, flatCreation) =
      flatLabelSlots(g, order, posNlist, g.edges.filter(e => e.tail != e.head),
        n => out.getOrElse(n, Seq.empty).toSeq)
    Result(rank0, order2, cross, segs.toVector, segOwn.toVector, mergedInto.toVector,
      flatCreation, posNlist)

  /** `flat_edges` + `flat_node` (flat.c), the step `dot_position` runs after
    * `set_ycoords` and before `create_aux_edges`.
    *
    * A labelled flat (same-rank) edge whose endpoints are ADJACENT is handled
    * by widening the pair's separation (`ED_dist`, see [[XCoord]]). A labelled
    * flat edge whose endpoints are NOT adjacent instead gets a real slot: a
    * virtual node carrying the label is spliced into rank `rank(tail) - 1` at
    * the index `flat_limits` picks, and everything to its right shifts over.
    * That vnode is a full participant in the LR chain, so omitting it loses
    * the label's width from the rank — 108pt on 191's widest rank.
    *
    * "Adjacent" is gv's notion, not ours: `checkFlatAdjacent` scans the slots
    * BETWEEN the endpoints and stops only at a NORMAL node or a LABELLED
    * virtual. Plain chain vnodes are transparent, so two real nodes with three
    * chain vnodes between them still count as adjacent. */
  private def flatLabelSlots(
      g:      RGraph,
      order:  Map[Int, Vector[LayoutNode]],
      /** `GD_nlist` at `dot_position` time — the walk order of `flat_edges`,
        * and thus the order competing flat labels claim their slots. */
      nlist:  Vector[LayoutNode],
      dedges: Vector[REdge],
      outOf:  LayoutNode => Seq[LayoutNode]
  ): (Map[Int, Vector[LayoutNode]], Vector[Int]) =
    // Candidate edges: flat (same rank), non-self, labelled.
    val pos: Map[LayoutNode, (Int, Int)] =
      order.iterator.flatMap((r, ids) => ids.zipWithIndex.map((n, p) => n -> (r, p))).toMap
    val labelled = Coord.labelVnodeWidths(g).keySet // chain LABEL vnodes, by name
    val cand = dedges.indices.filter { i =>
      val e = dedges(i)
      e.tail != e.head && e.attrs.get("label").exists(_.nonEmpty) && {
        (pos.get(LayoutNode.Real(e.tail)), pos.get(LayoutNode.Real(e.head))) match
          case (Some((rt, _)), Some((rh, _))) => rt == rh
          case _                              => false
      }
    }
    if cand.isEmpty then return (order, Vector.empty)

    val ranks   = mutable.Map.from(order.view.mapValues(_.toBuffer))
    val created = mutable.ArrayBuffer.empty[Int]
    def ordOf(n: LayoutNode): Int = ranks(pos(n)._1).indexOf(n)

    /** `checkFlatAdjacent` (flat.c:208): only a NORMAL node or a LABELLED
      * virtual blocks; plain chain vnodes are see-through. */
    def adjacent(e: REdge): Boolean =
      val t   = LayoutNode.Real(e.tail); val h = LayoutNode.Real(e.head)
      val row = ranks(pos(t)._1)
      val (lo, hi) = { val a = row.indexOf(t); val b = row.indexOf(h); (math.min(a, b), math.max(a, b)) }
      !(lo + 1 until hi).exists { i =>
        row(i) match
          case _: LayoutNode.Real     => true
          case v: LayoutNode.Virtual  => labelled(v.name)
          case _: LayoutNode.FlatLabel => true // a placed flat label IS labelled
          case _                      => false
      }

    // ── flat_limits (flat.c:101) ──────────────────────────────────────────
    // Four bounds over rank r-1: hard/soft left/right. Each VIRTUAL slot
    // votes: a FLAT-label vnode (no in-edges, two out-edges) by where its own
    // endpoints sit; a forward chain vnode by which side its heads are on.
    val HLB = 0; val HRB = 1; val SLB = 2; val SRB = 3
    def flatLimits(rPrev: Int, lpos: Int, rpos: Int): Int =
      val row = ranks.getOrElse(rPrev, mutable.Buffer.empty[LayoutNode])
      val b   = Array(-1, row.length, -1, row.length)
      def setbounds(v: LayoutNode, ord: Int): Unit = v match
        case f: LayoutNode.FlatLabel =>
          // ND_in.size == 0 ⇒ the two out-edges point at the flat endpoints
          val fe = dedges(f.dedgeIdx)
          val (l0, r0) =
            val a = ordOf(LayoutNode.Real(fe.tail)); val c = ordOf(LayoutNode.Real(fe.head))
            (math.min(a, c), math.max(a, c))
          if r0 <= lpos then { b(SLB) = ord; b(HLB) = ord }
          else if l0 >= rpos then { b(SRB) = ord; b(HRB) = ord }
          else if l0 < lpos && r0 > rpos then () // spans this one: ignore
          else
            if l0 < lpos || (l0 == lpos && r0 < rpos) then b(SLB) = ord
            if r0 > rpos || (r0 == rpos && l0 > lpos) then b(SRB) = ord
        case v: LayoutNode.Virtual =>
          var onleft = false; var onright = false
          outOf(v).foreach { hd =>
            val o = pos.get(hd).map(_._2).getOrElse(-1)
            if o <= lpos then onleft = true else if o >= rpos then onright = true
          }
          if onleft && !onright then b(HLB) = ord + 1
          if onright && !onleft then b(HRB) = ord - 1
        case _ => ()
      var lnode = 0
      var rnode = row.length - 1
      while lnode <= rnode do
        setbounds(row(lnode), lnode)
        if lnode != rnode then setbounds(row(rnode), rnode)
        lnode += 1; rnode -= 1
        if b(HRB) - b(HLB) <= 1 then { lnode = rnode + 1 }
      if b(HLB) <= b(HRB) then (b(HLB) + b(HRB) + 1) / 2
      else (b(SLB) + b(SRB) + 1) / 2

    // gv walks GD_nlist and, per node, that node's ND_flat_out list (class2
    // emit order: out-edges by (head node seq, decl index)).
    val nodeSeq = g.nodes.iterator.map(_.id).zipWithIndex.toMap
    val byTail  = cand.groupBy(i => dedges(i).tail)
    nlist.foreach { ln =>
      val nid = ln match { case LayoutNode.Real(id) => id; case _ => "" }
      byTail.getOrElse(nid, Vector.empty[Int])
        .sortBy(i => (nodeSeq.getOrElse(dedges(i).head, Int.MaxValue), i))
        .foreach { i =>
          val e = dedges(i)
          if !adjacent(e) then
            val r     = pos(LayoutNode.Real(e.tail))._1
            val lpos  = math.min(ordOf(LayoutNode.Real(e.tail)), ordOf(LayoutNode.Real(e.head)))
            val rpos  = math.max(ordOf(LayoutNode.Real(e.tail)), ordOf(LayoutNode.Real(e.head)))
            val place = flatLimits(r - 1, lpos, rpos)
            val row   = ranks.getOrElseUpdate(r - 1, mutable.Buffer.empty[LayoutNode])
            row.insert(math.max(0, math.min(place, row.length)), LayoutNode.FlatLabel(i))
            created += i
        }
    }
    (ranks.view.mapValues(_.toVector).toMap, created.toVector)

  /** Local collapsed-graph node: either a free (root-level) layout node passed
    * through, or a cluster **skeleton** rankleader `Sk(cluster, rank)` standing
    * in for the whole cluster column (gv `build_skeleton`). */
  private enum CNode derives CanEqual:
    case Nd(n: LayoutNode)
    case Sk(clust: Int, rank: Int)

  /** `decompose`-order (decomp.c) over a class2 graph: DFS from declaration-
    * order real `seeds` following in- then out-edges, then a stable append of
    * any unreached nodes (`others`). Shared by the cluster collapse + interiors
    * (the flat graph keeps its own inline copy so its bytes never move). */
  private def decomposeOrder[N](
      seeds: Vector[N], others: Vector[N],
      out: collection.Map[N, mutable.ArrayBuffer[N]],
      in:  collection.Map[N, mutable.ArrayBuffer[N]]
  )(using CanEqual[N, N]): Vector[N] =
    val done = mutable.Set.empty[N]
    val res  = mutable.ArrayBuffer.empty[N]
    def visit(seed: N): Unit =
      val stk = mutable.Stack(seed)
      while stk.nonEmpty do
        val n = stk.pop()
        if !done(n) then
          done += n; res += n
          in(n).reverseIterator.foreach(w => if !done(w) then stk.push(w))
          out(n).reverseIterator.foreach(w => if !done(w) then stk.push(w))
    seeds.foreach(s => if !done(s) then visit(s))
    (seeds ++ others).foreach(n => if !done(n) then { done += n; res += n })
    res.toVector

  /** `dot_mincross` with clusters (mincross.c:381 + class2.c/cluster.c):
    * collapse each top-level cluster to a skeleton column so the top-level
    * mincross physically can't interleave clusters, order the collapsed graph,
    * order each cluster's interior recursively, then expand each skeleton back
    * into its nodes. Scoped to **single-level** clusters (the whole corpus +
    * probes); nested clusters are a documented TODO (would recurse the collapse
    * per level). */
  private def orderClustered(
      g: RGraph,
      nodeOut: collection.Map[LayoutNode, mutable.ArrayBuffer[LayoutNode]],
      nodeIn:  collection.Map[LayoutNode, mutable.ArrayBuffer[LayoutNode]],
      rankOf: collection.Map[LayoutNode, Int],
      flip: Boolean,
      /** every class2 segment in EMISSION order (per tail node in declaration
        * order, per out-edge by (head seq, idx), then rank-ascending within a
        * chain) — the order gv's `class2` creates its virtual edges in. */
      segsInOrder: Vector[(LayoutNode, LayoutNode)],
      /** `ED_xpenalty` per segment, and the port data — the cluster-interior
        * and ReMincross passes count crossings over the whole root graph with
        * gv's `ncross`, so they need the same weights and `local_cross` port
        * term the flat path uses. */
      segWeight: (LayoutNode, LayoutNode) => Long,
      segPorts:  (LayoutNode, LayoutNode) => SegPorts,
      hasPort:   LayoutNode => Boolean,
      /** flat (same-rank) edge reps — a node whose ONLY neighbours share its
        * rank has empty in/out fast lists, so `medians` gives it no median at
        * all and `flat_mval` is the only thing that seats it. */
      flatPairs: Vector[(LayoutNode, LayoutNode, Double)]
  ): (Map[Int, Vector[LayoutNode]], Long, Vector[LayoutNode]) =
    val cinfos   = Cluster.clusters(g)
    // `GD_nlist` as `dot_position` sees it: `merge_ranks` PREPENDS
    // (`fast_node`) each expanded cluster's nodes walking ranks ascending and
    // each rank left-to-right, so every block lands at the head reversed and
    // the last cluster expanded ends up first. `flat_edges` walks this.
    val mergedNlist = mutable.ListBuffer.empty[LayoutNode]
    val clustOf  = Cluster.clustOf(g) // node NAME → innermost cluster idx
    // A chain vnode belongs to a cluster iff its owning edge's ORIGINAL
    // endpoints share that cluster (gv mark_clusters sets ND_clust on the
    // vnodes of intra-cluster edges — e.g. a reversed back edge like
    // groups.dot's a3->a0). They then live in the cluster's interior mincross,
    // NOT as free nodes of the collapsed graph.
    val vOrigEnds: Vector[(String, String)] =
      g.edges.filter(e => e.tail != e.head).map(e => (e.tail, e.head))
    def cOf(n: LayoutNode): Option[Int] = n match
      case LayoutNode.Real(id) => clustOf.get(id)
      case LayoutNode.Virtual(idx, _) =>
        val (t, h) = vOrigEnds(idx)
        (clustOf.get(t), clustOf.get(h)) match
          case (Some(a), Some(b)) if a == b => Some(a)
          case _                            => None
      // The class2 adjacency this walks is keyed by SEGMENT endpoints, so it
      // holds real nodes and chain vnodes and nothing else. The other kinds
      // arrive later or elsewhere: Slack/ClusterLn/ClusterRn are XCoord's aux
      // graph, and a FlatLabel (inserted into a RANK row a few lines above)
      // carries no segments, so it is never a key here. Fail loudly rather
      // than answering None — guessing "no cluster" for a node that reached
      // this by mistake would bend the layout silently, which is the one
      // failure mode a byte-exact port cannot afford.
      case other =>
        sys.error(s"Order.cOf: $other is not a mincross node (class2 holds Real/Virtual only)")
    val allNodes = nodeOut.keysIterator.toVector

    // Each cluster's occupied rank band (over the class2 nodes actually present).
    val clRanks: Map[Int, (Int, Int)] =
      allNodes.groupBy(cOf).collect { case (Some(ci), ns) =>
        val rs = ns.map(rankOf); ci -> (rs.min, rs.max)
      }

    // ── each cluster's interior INSTALL: `expand_cluster` → build_ranks(subg,
    //    0) — a single pass-0 BFS over the intra-cluster segments, roots
    //    scanned in REVERSE nlist order (build_ranks `walkbackwards` for
    //    g != root, "to preserve input node order"). NO local iterations —
    //    the refinement happens afterwards against the GLOBAL order
    //    (`mincross(subg, 2)`), where crossings/medians see the whole graph. ──
    def internalInstall(ci: Int): Map[Int, Vector[LayoutNode]] =
      val members = allNodes.filter(n => cOf(n).contains(ci)).toSet
      val iOut  = mutable.LinkedHashMap.empty[LayoutNode, mutable.ArrayBuffer[LayoutNode]]
      val iIn   = mutable.LinkedHashMap.empty[LayoutNode, mutable.ArrayBuffer[LayoutNode]]
      members.foreach { n =>
        iOut.getOrElseUpdate(n, mutable.ArrayBuffer.empty)
        iIn.getOrElseUpdate(n, mutable.ArrayBuffer.empty)
      }
      allNodes.foreach { t =>
        if members.contains(t) then
          nodeOut(t).foreach { h => if members.contains(h) then { iOut(t) += h; iIn(h) += t } }
      }
      val declared = cinfos(ci).nodeIds.iterator.map(id => LayoutNode.Real(id): LayoutNode)
        .filter(members.contains).toVector
      // interior chain vnodes in class2(subg) creation order: per owning edge
      // (in the per-tail, head-seq emission order the global chains used),
      // min→max rank within a chain.
      val virtuals = members.iterator.collect { case v: LayoutNode.Virtual => v }
        .toVector.sortBy(v => (v.edgeIdx, v.rank)).map(v => v: LayoutNode)
      val nlist = declared ++ virtuals
      val rows  = mutable.HashMap.empty[Int, mutable.ArrayBuffer[LayoutNode]]
      val mark  = mutable.Set.empty[LayoutNode]
      val q     = mutable.Queue.empty[LayoutNode]
      def install(n: LayoutNode): Unit =
        rows.getOrElseUpdate(rankOf(n), mutable.ArrayBuffer.empty) += n
      // build_ranks `walkbackwards`: the cluster nlist is built by fast_node
      // PREPENDS (reverse insertion), and the backward walk restores insertion
      // order — which is exactly this list's forward order (reals in
      // declaration order, then chain vnodes in creation order).
      nlist.foreach { root =>
        if iIn(root).isEmpty && !mark(root) then
          mark += root; q.enqueue(root)
          while q.nonEmpty do
            val n0 = q.dequeue(); install(n0)
            iOut(n0).foreach(w => if !mark(w) then { mark += w; q.enqueue(w) })
      }
      nlist.foreach(n => if !mark(n) then { mark += n; install(n) }) // safety net
      // NO per-rank flip reversal here. build_ranks guards it on `GD_flip(g)`
      // = `GD_rankdir(g) & 1`, and `graph_init` (input.c:586) — the only place
      // rankdir is parsed — runs on the ROOT alone, so a cluster subgraph's
      // rankdir is always 0 and `expand_cluster`'s `build_ranks(subg, 0)`
      // never reverses. Only the root's build_ranks does. Reversing here too
      // un-mirrored each cluster's interior relative to the rank around it.
      rows.iterator.map((r, b) => r -> b.toVector).toMap

    // ── collapse: free nodes pass through, each cluster → a skeleton chain. ──
    def leaderC(n: LayoutNode): CNode = cOf(n) match
      case Some(ci) => CNode.Sk(ci, rankOf(n))
      case None     => CNode.Nd(n)
    val cOut  = mutable.LinkedHashMap.empty[CNode, mutable.ArrayBuffer[CNode]]
    val cIn   = mutable.LinkedHashMap.empty[CNode, mutable.ArrayBuffer[CNode]]
    val cRank = mutable.HashMap.empty[CNode, Int]
    def cInit(x: CNode, r: Int): Unit =
      cOut.getOrElseUpdate(x, mutable.ArrayBuffer.empty)
      cIn.getOrElseUpdate(x, mutable.ArrayBuffer.empty)
      cRank(x) = r
    allNodes.foreach(n => if cOf(n).isEmpty then cInit(CNode.Nd(n), rankOf(n)))
    clRanks.foreach { case (ci, (lo, hi)) =>
      (lo to hi).foreach(r => cInit(CNode.Sk(ci, r), r))
      (lo until hi).foreach { r => // build_skeleton chain (keeps the column together)
        cOut(CNode.Sk(ci, r)) += CNode.Sk(ci, r + 1); cIn(CNode.Sk(ci, r + 1)) += CNode.Sk(ci, r)
      }
    }
    // redirect every class2 segment to its leaders; drop intra-cluster ones
    // (interclrep: `else ignore intra-cluster edges` — the recursion owns them).
    // Walk the segments in gv's class2 EMISSION order, not per-node: gv builds
    // an inter-cluster chain while processing the ORIGINAL edge (interclrep),
    // and `make_chain` appends the first virtual edge to the leader's ND_out
    // right then. Iterating the finished chains node-by-node instead orders a
    // leader's adjacency by which member happens to own each chain's first
    // segment — and since interclrep SWAPS a backward edge so the chain always
    // runs low-rank → high-rank, that member is often the *head*'s cluster.
    // (191: RuntimeContext→PredictType is chain #1 but hangs off PredictType,
    // declared 11th, so it sank below three later chains — one crossing, which
    // steered the whole collapsed search to a different optimum.)
    segsInOrder.foreach { (t, h) =>
      val lt = leaderC(t); val lh = leaderC(h)
      val intra = (lt, lh) match
        case (CNode.Sk(c1, _), CNode.Sk(c2, _)) => c1 == c2
        case _                                  => lt == lh
      if !intra then { cOut(lt) += lh; cIn(lh) += lt }
    }

    // decompose(g, 1) seeds from agfstnode = EVERY node in declaration
    // order, a cluster member standing in as its rankleader at that node's
    // rank (decomp.c: `v = GD_rankleader(subg)[ND_rank(v)]`). Filtering to
    // free nodes made skeleton-only components install LAST (pprof's
    // isolated File cluster belongs at order 0 on rank 0 — comp0).
    val cSeeds = g.nodes.iterator
      .map(n => LayoutNode.Real(n.id): LayoutNode)
      .filter(rankOf.contains)
      .map(leaderC).distinct.toVector
    val cOthers = cOut.keysIterator.filterNot(cSeeds.contains).toVector
    val cSeed   = decomposeOrder(cSeeds, cOthers, cOut, cIn)
    // skeleton chain edges carry ED_xpenalty = CL_CROSS (1000, build_skeleton);
    // a skeleton node stands for its whole rankleader column (install_cluster).
    val skWeight: (CNode, CNode) => Long =
      case (CNode.Sk(c1, _), CNode.Sk(c2, _)) if c1 == c2 => 1000L // CL_CROSS
      case _                                              => 1L
    val skColOf: CNode => Option[Int] =
      case CNode.Sk(ci, _) => Some(ci)
      case _               => None
    val skColNodes: Int => Vector[CNode] = ci =>
      val (lo, hi) = clRanks(ci)
      (lo to hi).map(r => CNode.Sk(ci, r): CNode).toVector
    val (cOrder, cCross) = runMincross(cOut, cIn, cRank, cSeed, flip,
      weight = skWeight, columnOf = skColOf, columnNodes = skColNodes)

    // ── merge2 (mincross.c:1023): the component rank arrays merge into the
    //    root's, but the CLUSTERS STAY COLLAPSED — each is still one rankleader
    //    per rank. gv expands them one at a time inside the mincross_clust loop
    //    below, so while cluster c is refined, c+1.. are still single nodes.
    val rows = mutable.HashMap.from(cOrder.map((r, row) => r -> mutable.ArrayBuffer.from(row)))
    // `allocate_ranks` (mincross.c) sizes GD_rank over the WHOLE span
    // GD_minrank..GD_maxrank, so a rank with no nodes still exists, just with
    // `n == 0`; every mincross loop then walks the span and does nothing for
    // those. Ours only had the ranks that actually carry nodes, so a graph with
    // a GAP crashed on the first `rows(r)` — `key not found: 1`.
    //
    // A gap is ordinary: `dot` ranks each connected component separately and
    // offsets them, so a graph whose isolated nodes land at rank 0 while its
    // connected body starts at rank 8 leaves 1..7 empty. (A call graph with a
    // legend cluster and a couple of unreferenced tables is exactly that shape.)
    if rankOf.nonEmpty then
      (rankOf.valuesIterator.min to rankOf.valuesIterator.max)
        .foreach(r => rows.getOrElseUpdate(r, mutable.ArrayBuffer.empty))
    val gpos = mutable.HashMap.empty[CNode, Int]
    def reindex(): Unit =
      gpos.clear()
      rows.valuesIterator.foreach(rb => rb.iterator.zipWithIndex.foreach((n, i) => gpos(n) = i))
    reindex()
    val gMinR = if rankOf.isEmpty then 0 else rankOf.valuesIterator.min
    val gMaxR = if rankOf.isEmpty then 0 else rankOf.valuesIterator.max

    // ── the root fast graph AS IT STANDS RIGHT NOW ────────────────────────
    // An unexpanded cluster is its skeleton column: one CL_CROSS-weighted
    // chain edge per rank step (`build_skeleton`), and every edge touching it
    // lands on the rankleader — that is `map_interclust_node` (cluster.c:18):
    // "if the node has no cluster, or its cluster is EXPANDED, use the node;
    // else use its cluster's rankleader at that rank". Expanding a cluster
    // swaps its skeleton chain for its own interior segments and rewires the
    // inter-cluster chain ENDPOINTS onto the real nodes
    // (`interclexp`/`make_interclust_chain`/`map_path`), leaving the
    // intermediate chain vnodes alone — which is exactly what re-deriving the
    // adjacency from the class2 segments under `liveNode` reproduces.
    val expanded = mutable.Set.empty[Int]
    val hOut    = mutable.LinkedHashMap.empty[CNode, mutable.ArrayBuffer[CNode]]
    val hIn     = mutable.LinkedHashMap.empty[CNode, mutable.ArrayBuffer[CNode]]
    val hWeight = mutable.HashMap.empty[(CNode, CNode), Long]
    val hPorts  = mutable.HashMap.empty[(CNode, CNode), SegPorts]
    def liveNode(n: LayoutNode): CNode = cOf(n) match
      case Some(ci) if !expanded(ci) => CNode.Sk(ci, rankOf(n))
      case _                         => CNode.Nd(n)
    def rebuildAdjacency(): Unit =
      hOut.clear(); hIn.clear(); hWeight.clear(); hPorts.clear()
      def touch(x: CNode): Unit =
        hOut.getOrElseUpdate(x, mutable.ArrayBuffer.empty)
        hIn.getOrElseUpdate(x, mutable.ArrayBuffer.empty)
      rows.valuesIterator.foreach(_.foreach(touch))
      clRanks.foreach { case (ci, (lo, hi)) =>
        if !expanded(ci) then
          (lo until hi).foreach { r =>
            val a = CNode.Sk(ci, r); val b = CNode.Sk(ci, r + 1)
            touch(a); touch(b)
            hOut(a) += b; hIn(b) += a
            hWeight((a, b)) = 1000L // CL_CROSS (build_skeleton)
          }
      }
      segsInOrder.foreach { (t, h) =>
        val lt = liveNode(t); val lh = liveNode(h)
        // a segment INSIDE a still-collapsed cluster IS the skeleton chain
        val collapsedIntra = (lt, lh) match
          case (CNode.Sk(c1, _), CNode.Sk(c2, _)) => c1 == c2
          case _                                  => false
        if !collapsedIntra && lt != lh then
          touch(lt); touch(lh)
          hOut(lt) += lh; hIn(lh) += lt
          if !hWeight.contains((lt, lh)) then
            hWeight((lt, lh)) = segWeight(t, h)
            val sp = segPorts(t, h)
            if sp != SegPorts.default then hPorts((lt, lh)) = sp
      }
    rebuildAdjacency()
    val emptyRow = mutable.ArrayBuffer.empty[CNode]
    def out(x: CNode): mutable.ArrayBuffer[CNode] = hOut.getOrElse(x, emptyRow)
    def in(x: CNode): mutable.ArrayBuffer[CNode]  = hIn.getOrElse(x, emptyRow)
    def wOf(t: CNode, h: CNode): Long             = hWeight.getOrElse((t, h), 1L)
    def pOf(t: CNode, h: CNode): SegPorts         = hPorts.getOrElse((t, h), SegPorts.default)
    def portedC(x: CNode): Boolean = x match
      case CNode.Nd(n) => hasPort(n)
      case _           => false
    def clustOfC(x: CNode): Option[Int] = x match
      case CNode.Nd(n)     => cOf(n)
      case CNode.Sk(ci, _) => Some(ci)

    // Flat (same-rank) adjacency, in the CNode domain. A node whose only
    // neighbours share its rank has EMPTY in/out fast lists, so `medians`
    // records no median for it and `flat_mval` (mincross.c:1706) is the only
    // thing that seats it — 191's PredictorView/PredictorState reach their
    // cluster only through flat edges to ProgramPredictorsGiven.
    val fInC  = mutable.HashMap.empty[CNode, mutable.ArrayBuffer[CNode]]
    val fOutC = mutable.HashMap.empty[CNode, mutable.ArrayBuffer[CNode]]
    flatPairs.foreach { (t, h, w) =>
      if w != 0.0 then
        val (ct, ch) = (CNode.Nd(t): CNode, CNode.Nd(h): CNode)
        fOutC.getOrElseUpdate(ct, mutable.ArrayBuffer.empty) += ch
        fInC.getOrElseUpdate(ch, mutable.ArrayBuffer.empty) += ct
      }
    /** `flat_mval` — seat an edgeless node beside its flat neighbour: one to
      * the RIGHT of its highest-order flat-in tail, else one to the LEFT of
      * its lowest-order flat-out head. Returns true when it could not (⇒
      * `hasfixed`, which stops `reorder` shrinking its window).
      *
      * `mvalOf` reads 0.0 for a node this pass never touched — gv's ND_mval is
      * calloc'd, and a cluster's own `medians` only walks its OWN slice, so a
      * flat neighbour outside the cluster still carries that zero. */
    def flatMvalOf(mval: mutable.HashMap[CNode, Double], n: CNode): Boolean =
      def mvalOf(x: CNode): Double = mval.getOrElse(x, 0.0)
      // `ordOf` tolerates a flat neighbour that is not installed right now:
      // interclexp wires a flat edge to the REAL node even while that node's
      // cluster is still collapsed, and gv just reads its stale ND_order.
      def ordOf(x: CNode): Int = gpos.getOrElse(x, 0)
      val fin = fInC.getOrElse(n, mutable.ArrayBuffer.empty)
      if fin.nonEmpty then
        var nn = fin(0)
        fin.foreach(t => if ordOf(t) > ordOf(nn) then nn = t)
        if mvalOf(nn) >= 0 then { mval(n) = mvalOf(nn) + 1; false } else true
      else
        val fout = fOutC.getOrElse(n, mutable.ArrayBuffer.empty)
        if fout.nonEmpty then
          var nn = fout(0)
          fout.foreach(h => if ordOf(h) < ordOf(nn) then nn = h)
          if mvalOf(nn) > 0 then { mval(n) = mvalOf(nn) - 1; false } else true
        else true

    /** `expand_cluster` + `merge_ranks` (cluster.c:288): the cluster's
      * rankleaders are replaced IN PLACE by its own `build_ranks(subg, 0)`
      * install, then the surrounding graph is rewired around it. */
    def expandCluster(ci: Int): Unit =
      val interior = internalInstall(ci)
      // merge_ranks: fast_node PREPENDS each node, walking ranks ascending and
      // each rank left-to-right, so this block lands at the head reversed.
      mergedNlist.prependAll(
        (clRanks(ci)._1 to clRanks(ci)._2)
          .flatMap(r => interior.getOrElse(r, Vector.empty)).reverse)
      val (lo, hi) = clRanks(ci)
      (lo to hi).foreach { r =>
        val rb   = rows.getOrElseUpdate(r, mutable.ArrayBuffer.empty)
        val repl = interior.getOrElse(r, Vector.empty).map(n => CNode.Nd(n): CNode)
        val idx  = rb.indexOf(CNode.Sk(ci, r): CNode)
        if idx >= 0 then { rb.remove(idx); rb.insertAll(idx, repl) }
        else rb ++= repl
      }
      expanded += ci
      rebuildAdjacency()
      reindex()


    // ── per-cluster refinement: `mincross_clust` → mincross(subg, 2). The
    //    cluster's rank arrays are SLICES of the root's (contiguous by
    //    construction); medians use GLOBAL neighbor positions and `ncross`
    //    counts over the WHOLE root graph (gv ncross reads the Root globals),
    //    so the unavoidable cross-cluster crossings keep the iterations alive
    //    and reverse-pass tie-swaps can flip equal-median pairs. ──
    // gv `ncross` (mincross.c:1626) over the root graph — the cluster-interior
    // and ReMincross drivers both read this global count. It is WEIGHTED by
    // ED_xpenalty and carries the `local_cross` term for ported nodes;
    // counting raw inversions instead optimises a different objective.
    def localOutG(v: CNode): Long =
      val os = out(v)
      var c = 0L; var i = 0
      while i < os.length do
        var j = i + 1
        while j < os.length do
          val e = os(i); val f = os(j)
          if (gpos(f) - gpos(e)).sign * (pOf(v, f).tailPX - pOf(v, e).tailPX).sign < 0 then
            c += wOf(v, e) * wOf(v, f)
          j += 1
        i += 1
      c
    def localInG(v: CNode): Long =
      val is0 = in(v)
      var c = 0L; var i = 0
      while i < is0.length do
        var j = i + 1
        while j < is0.length do
          val e = is0(i); val f = is0(j)
          if (gpos(f) - gpos(e)).sign * (pOf(f, v).headPX - pOf(e, v).headPX).sign < 0 then
            c += wOf(e, v) * wOf(f, v)
          j += 1
        i += 1
      c
    def ncrossGlobal(): Long =
      var c = 0L
      var r = gMinR
      while r < gMaxR do
        val segs = mutable.ArrayBuffer.empty[(Int, Int, Long)]
        rows.getOrElse(r, mutable.ArrayBuffer.empty).foreach { u =>
          out(u).foreach(w => segs += ((gpos(u), gpos(w), wOf(u, w))))
        }
        var i = 0
        while i < segs.length do
          var j = i + 1
          while j < segs.length do
            val (ui, wi, xi) = segs(i)
            val (uj, wj, xj) = segs(j)
            if (ui - uj) * (wi - wj) < 0 then c += xi * xj
            j += 1
          i += 1
        rows.getOrElse(r, mutable.ArrayBuffer.empty).foreach(v => if portedC(v) then c += localOutG(v))
        rows.getOrElse(r + 1, mutable.ArrayBuffer.empty).foreach(v => if portedC(v) then c += localInG(v))
        r += 1
      c

    def refineCluster(ci: Int): Unit =
      val (cLo, cHi) = clRanks(ci)
      def slice(r: Int): (Int, Int) = // [from, until) of this cluster's members
        val rb   = rows(r)
        var from = 0
        while from < rb.length && !clustOfC(rb(from)).contains(ci) do from += 1
        var until = from
        while until < rb.length && clustOfC(rb(until)).contains(ci) do until += 1
        (from, until)
      def exchange(r: Int, i: Int, j: Int): Unit =
        val rb = rows(r); val a = rb(i); val b = rb(j)
        rb(i) = b; rb(j) = a; gpos(a) = j; gpos(b) = i
      val mval = mutable.HashMap.empty[CNode, Double]
      def medians(r0: Int, r1: Int): Boolean =
        val (from, until) = slice(r0)
        val rb = rows(r0)
        var k = from
        while k < until do
          val n    = rb(k)
          // VAL(node, port) = MC_SCALE*ND_order + port.order (mincross.c:1709)
          // — the same integer median key the root pass uses. Dropping the
          // port term makes two edges into the same node tie when gv orders
          // them, so a rank full of ported HTML nodes never reorders.
          val nbrs =
            (if r1 > r0 then out(n).map(w => 256 * gpos(w) + pOf(n, w).headOrd)
             else in(n).map(x => 256 * gpos(x) + pOf(x, n).tailOrd)).sorted
          mval(n) = nbrs.length match
            case 0 => -1.0
            case 1 => nbrs(0).toDouble
            case 2 => ((nbrs(0) + nbrs(1)) / 2).toDouble // C int division
            case j =>
              val rm = j / 2
              if j % 2 == 1 then nbrs(rm).toDouble
              else
                val lm    = rm - 1
                val rspan = nbrs(j - 1) - nbrs(rm)
                val lspan = nbrs(lm) - nbrs(0)
                if lspan == rspan then ((nbrs(lm) + nbrs(rm)) / 2).toDouble // int div
                else (nbrs(lm).toDouble * rspan + nbrs(rm).toDouble * lspan) / (lspan + rspan)
          k += 1
        // medians' tail (mincross.c:1745): a node with NO fast edges at all is
        // seated from its FLAT neighbours instead, and `hasfixed` reports the
        // ones that could not be — that stops reorder shrinking its window.
        var hasfixed = false
        k = from
        while k < until do
          val n = rb(k)
          if out(n).isEmpty && in(n).isEmpty then hasfixed = flatMvalOf(mval, n) || hasfixed
          k += 1
        hasfixed
      def reorder(r: Int, reverse: Boolean, hasfixed: Boolean): Unit =
        val (from, until) = slice(r)
        val rb = rows(r)
        var ep = until
        var nelt = until - from - 1
        while nelt >= 0 do
          var lp = from
          while lp < ep do
            while lp < ep && mval.getOrElse(rb(lp), -1.0) < 0 do lp += 1
            if lp < ep then
              // NO sawclust here. The `###` rule keys off ND_clust, and
              // `expand_cluster` runs `class2(subg)` → `mark_clusters(subg)`,
              // whose first loop sets `ND_clust(n) = NULL` for every node of
              // subg (it then re-marks only subg's OWN sub-clusters). So while
              // a cluster's interior mincross runs, its nodes carry no cluster
              // — the rule is inert until `mark_lowclusters` re-stamps them
              // just before ReMincross, where it DOES apply.
              var rp = lp + 1
              while rp < ep && mval.getOrElse(rb(rp), -1.0) < 0 do rp += 1
              if rp < ep then
                val p1 = mval.getOrElse(rb(lp), -1.0)
                val p2 = mval.getOrElse(rb(rp), -1.0)
                val doSwap = p1 > p2 || (p1 >= p2 && reverse)
                if doSwap then exchange(r, lp, rp)
                lp = rp
              else lp = ep
            else lp = ep
          if !hasfixed && !reverse then ep -= 1
          nelt -= 1
      // in_cross/out_cross (mincross.c:620): WEIGHTED by ED_xpenalty on both
      // edges, and an equal-order tie breaks on the endpoints' canonical port
      // p.x — two record/HTML ports on one node still cross. Counting raw
      // unweighted inversions makes transpose swap pairs gv leaves alone.
      def crossIn(v: CNode, w: CNode): Long =
        var c = 0L
        in(v).foreach(x => in(w).foreach { y =>
          if gpos(x) > gpos(y) ||
             (gpos(x) == gpos(y) && pOf(x, v).tailPX > pOf(y, w).tailPX) then
            c += wOf(x, v) * wOf(y, w)
        })
        c
      def crossOut(v: CNode, w: CNode): Long =
        var c = 0L
        out(v).foreach(x => out(w).foreach { y =>
          if gpos(x) > gpos(y) ||
             (gpos(x) == gpos(y) && pOf(v, x).headPX > pOf(w, y).headPX) then
            c += wOf(v, x) * wOf(w, y)
        })
        c
      // transpose_step (mincross.c:700) is PER RANK and carries gv's
      // `candidate` gating: a rank clears its own flag on entry and re-arms
      // itself and its two neighbours whenever it swaps. Sweeping every rank
      // on every pass instead visits swaps in a different order, which decides
      // the ties — 191's programs_para ended with ParaCategoryGiven and
      // ProgramPredictorsGiven the wrong way round.
      val candidate = mutable.HashMap.from((cLo to cHi).map(r => r -> true))
      def transposeStep(r: Int, reverse: Boolean): Long =
        var delta = 0L
        candidate(r) = false
        val (from, until) = slice(r)
        val rb = rows(r)
        // out_cross is gated on `GD_rank(g)[r + 1].n > 0`, and for a CLUSTER
        // that array is its own slice — zero past its maxrank, so the
        // cluster's bottom rank contributes in_cross only.
        val hasBelow = r < cHi
        var i = from
        while i < until - 1 do
          val v = rb(i); val w = rb(i + 1)
          val c0 = crossIn(v, w) + (if hasBelow then crossOut(v, w) else 0L)
          val c1 = crossIn(w, v) + (if hasBelow then crossOut(w, v) else 0L)
          if c1 < c0 || (c0 > 0 && reverse && c1 == c0) then
            exchange(r, i, i + 1)
            delta += c0 - c1
            candidate(r) = true
            if r > cLo then candidate(r - 1) = true
            if r < cHi then candidate(r + 1) = true
          i += 1
        delta
      def transpose(reverse: Boolean): Unit =
        (cLo to cHi).foreach(r => candidate(r) = true)
        var delta = 0L
        while {
          delta = 0L
          (cLo to cHi).foreach(r => if candidate(r) then delta += transposeStep(r, reverse))
          delta >= 1
        } do ()
      def mincrossStep(pass: Int): Unit =
        val reverse = pass % 4 < 2
        // mincross_step (mincross.c:1252): the down pass normally starts one
        // rank INSIDE (the topmost rank has nothing above to take medians
        // from) — but a cluster whose minrank is below the root's DOES have a
        // rank above it, so `first--` puts the sweep on the cluster's own top
        // rank. Symmetrically for the up pass at the bottom. Starting one rank
        // in regardless leaves the cluster's boundary ranks unordered.
        val (first, last, dir) =
          if pass % 2 == 0 then ((if cLo > gMinR then cLo else cLo + 1), cHi, 1)
          else ((if cHi < gMaxR then cHi else cHi - 1), cLo, -1)
        var r = first
        while r != last + dir do
          val hasfixed = medians(r, r - dir)
          reorder(r, reverse, hasfixed)
          r += dir
        transpose(!reverse)
      def snapshot(): Map[Int, Vector[CNode]] =
        rows.iterator.map((r, b) => r -> b.toVector).toMap
      def restore(s: Map[Int, Vector[CNode]]): Unit =
        s.foreach { (r, v) =>
          val rb = rows(r); rb.clear(); rb ++= v
          v.iterator.zipWithIndex.foreach((n, i) => gpos(n) = i)
        }
      // mincross(subg, startpass=2) driver
      var cur  = ncrossGlobal()
      var best = cur
      var bst  = snapshot()
      var trying = 0; var iter = 0; var brk = false
      while iter < MaxIter && !brk do
        if trying >= MinQuit then brk = true
        else
          trying += 1
          if cur == 0 then brk = true
          else
            mincrossStep(iter)
            cur = ncrossGlobal()
            if cur <= best then
              bst = snapshot()
              if cur < Convergence * best then trying = 0
              best = cur
            iter += 1
      if cur > best then restore(bst)
      if best > 0 then transpose(false) // driver tail (slice-constrained)

    // mincross_clust, cluster by cluster: expand THIS one (the rest stay
    // collapsed to their rankleaders) and immediately refine it against the
    // graph as it now stands — gv's loop, not expand-everything-then-refine.
    clRanks.keysIterator.toVector.sorted.foreach { ci =>
      expandCluster(ci)
      refineCluster(ci)
    }

    // ── ReMincross (dot_mincross tail, mincross.c:381): after the interior
    //    passes, a FINAL mincross(g, 2) runs over the whole EXPANDED graph.
    //    left2right in this phase (ReMincross=true) pins any adjacent pair
    //    with DIFFERING clusters (free↔cluster or two clusters), so free
    //    nodes weave between cluster columns while boundaries hold. ──
    locally {
      def pinned(v: CNode, w: CNode): Boolean = clustOfC(v) != clustOfC(w)
      def exchange(r: Int, i: Int, j: Int): Unit =
        val rb = rows(r); val a = rb(i); val b = rb(j)
        rb(i) = b; rb(j) = a; gpos(a) = j; gpos(b) = i
      val mval = mutable.HashMap.empty[CNode, Double]
      def medians(r0: Int, r1: Int): Boolean =
        rows.getOrElse(r0, mutable.ArrayBuffer.empty).foreach { n =>
          // VAL(node, port) = MC_SCALE*ND_order + port.order, and C INTEGER
          // division in the even-median cases — the same key the root and
          // cluster-interior passes use.
          val nbrs =
            (if r1 > r0 then out(n).map(w => 256 * gpos(w) + pOf(n, w).headOrd)
             else in(n).map(x => 256 * gpos(x) + pOf(x, n).tailOrd)).sorted
          mval(n) = nbrs.length match
            case 0 => -1.0
            case 1 => nbrs(0).toDouble
            case 2 => ((nbrs(0) + nbrs(1)) / 2).toDouble
            case j =>
              val rm = j / 2
              if j % 2 == 1 then nbrs(rm).toDouble
              else
                val lm    = rm - 1
                val rspan = nbrs(j - 1) - nbrs(rm)
                val lspan = nbrs(lm) - nbrs(0)
                if lspan == rspan then ((nbrs(lm) + nbrs(rm)) / 2).toDouble
                else (nbrs(lm).toDouble * rspan + nbrs(rm).toDouble * lspan) / (lspan + rspan)
        }
        var hasfixed = false
        rows.getOrElse(r0, mutable.ArrayBuffer.empty).foreach { n =>
          if out(n).isEmpty && in(n).isEmpty then hasfixed = flatMvalOf(mval, n) || hasfixed
        }
        hasfixed
      def reorder(r: Int, reverse: Boolean, hasfixed: Boolean): Unit =
        // gv reorder (mincross.c:1473) with the `sawclust ###` rule: in the
        // rp scan, once a CLUSTERED node is passed, later clustered nodes
        // are skipped. Pre-ReMincross only real cluster members are marked;
        // but mark_lowclusters (run right before ReMincross) stamps EVERY
        // node with its lowest cluster OR THE ROOT — so here every node is
        // "clustered", and any fixed (mval<0) node between two comparables
        // blocks their exchange (pprof r14: V–N5(fixed)–N9 stays put).
        val rb = rows(r)
        var ep = rb.length
        var nelt = rb.length - 1
        while nelt >= 0 do
          var lp = 0
          while lp < ep do
            while lp < ep && mval.getOrElse(rb(lp), -1.0) < 0 do lp += 1
            if lp < ep then
              var rp = lp + 1
              var muststay = false; var found = false
              var sawclust = false
              while rp < ep && !muststay && !found do
                if sawclust then rp += 1 // ###: ND_clust set on ALL nodes here
                else if pinned(rb(lp), rb(rp)) then muststay = true
                else if mval.getOrElse(rb(rp), -1.0) >= 0 then found = true
                else { sawclust = true; rp += 1 }
              if rp < ep then
                if !muststay then
                  val p1 = mval.getOrElse(rb(lp), -1.0)
                  val p2 = mval.getOrElse(rb(rp), -1.0)
                  if p1 > p2 || (p1 >= p2 && reverse) then exchange(r, lp, rp)
                lp = rp
              else lp = ep
            else lp = ep
          if !hasfixed && !reverse then ep -= 1
          nelt -= 1
      // same weighted, port-aware in_cross/out_cross as the interior pass
      def crossIn(v: CNode, w: CNode): Long =
        var c = 0L
        in(v).foreach(x => in(w).foreach { y =>
          if gpos(x) > gpos(y) ||
             (gpos(x) == gpos(y) && pOf(x, v).tailPX > pOf(y, w).tailPX) then
            c += wOf(x, v) * wOf(y, w)
        })
        c
      def crossOut(v: CNode, w: CNode): Long =
        var c = 0L
        out(v).foreach(x => out(w).foreach { y =>
          if gpos(x) > gpos(y) ||
             (gpos(x) == gpos(y) && pOf(v, x).headPX > pOf(w, y).headPX) then
            c += wOf(v, x) * wOf(w, y)
        })
        c
      val candidate = mutable.HashMap.from((gMinR to gMaxR).map(r => r -> true))
      def transposeStep(r: Int, reverse: Boolean): Long =
        var delta = 0L
        candidate(r) = false
        val rb = rows.getOrElse(r, mutable.ArrayBuffer.empty)
        var i = 0
        while i < rb.length - 1 do
          val v = rb(i); val w = rb(i + 1)
          if !pinned(v, w) then
            var c0 = 0L; var c1 = 0L
            if r > gMinR then { c0 += crossIn(v, w); c1 += crossIn(w, v) }
            if r < gMaxR && rows.getOrElse(r + 1, mutable.ArrayBuffer.empty).nonEmpty then
              c0 += crossOut(v, w); c1 += crossOut(w, v)
            if c1 < c0 || (c0 > 0 && reverse && c1 == c0) then
              exchange(r, i, i + 1)
              delta += c0 - c1
              candidate(r) = true
              if r > gMinR then candidate(r - 1) = true
              if r < gMaxR then candidate(r + 1) = true
          i += 1
        delta
      def transpose(reverse: Boolean): Unit =
        (gMinR to gMaxR).foreach(r => candidate(r) = true)
        var delta = 0L
        while {
          delta = 0L
          (gMinR to gMaxR).foreach(r => if candidate(r) then delta += transposeStep(r, reverse))
          delta >= 1
        } do ()
      def mincrossStep(pass: Int): Unit =
        val reverse = pass % 4 < 2
        val (first, last, dir) =
          if pass % 2 == 0 then (gMinR + 1, gMaxR, 1) else (gMaxR - 1, gMinR, -1)
        var r = first
        while r != last + dir do
          val hasfixed = medians(r, r - dir)
          reorder(r, reverse, hasfixed)
          r += dir
        transpose(!reverse)
      def snapshot(): Map[Int, Vector[CNode]] =
        rows.iterator.map((r, b) => r -> b.toVector).toMap
      def restore(s: Map[Int, Vector[CNode]]): Unit =
        s.foreach { (r, v) =>
          val rb = rows(r); rb.clear(); rb ++= v
          v.iterator.zipWithIndex.foreach((n, i) => gpos(n) = i)
        }
      var cur  = ncrossGlobal()
      var best = cur
      var bst  = snapshot()
      var trying = 0; var iter = 0; var brk = false
      while iter < MaxIter && !brk do
        if trying >= MinQuit then brk = true
        else
          trying += 1
          if cur == 0 then brk = true
          else
            mincrossStep(iter)
            cur = ncrossGlobal()
            if cur <= best then
              bst = snapshot()
              if cur < Convergence * best then trying = 0
              best = cur
            iter += 1
      if cur > best then restore(bst)
      if best > 0 then transpose(false)
    }

    // every cluster is expanded by now, so every CNode is a plain layout node
    // GD_nlist as `dot_position` finds it: the merge_ranks blocks (prepended,
    // so last-expanded first) followed by whatever `decompose(g, 1)` left —
    // which is the COLLAPSED graph's DFS order, `cSeed`, minus the rankleaders
    // `remove_rankleaders` deletes. `CNode.Nd` is precisely the non-rankleader
    // part: every cluster member collapsed to an `Sk`, so what stays is the
    // root-level real nodes and the inter-cluster chains' vnodes.
    (rows.iterator.map((r, b) => r -> b.toVector.collect { case CNode.Nd(n) => n }).toMap,
      ncrossGlobal(),
      mergedNlist.toVector ++ cSeed.collect { case CNode.Nd(n) => n })

  /** The `mincross` driver proper (mincross.c:745) over a class2 graph given as
    * adjacency maps + a `GD_nlist` seed order. Extracted so both the flat graph
    * ([[orderImpl]]) and — in the cluster path — the collapsed top-level graph
    * and each cluster's recursion can share one solver. Reads `out`/`in`/
    * `rankOf` (never mutates them); returns the per-rank order + crossing count.
    */
  private def runMincross[N](
      out:     collection.Map[N, mutable.ArrayBuffer[N]],
      in:      collection.Map[N, mutable.ArrayBuffer[N]],
      rankOf:  collection.Map[N, Int],
      gdNlist: Vector[N],
      flip:    Boolean,
      /** `ED_xpenalty` per (tail, head) segment — crossings count
        * `weight(e1)*weight(e2)` (mincross.c in_cross/out_cross). The collapsed
        * cluster pass gives skeleton chain edges CL_CROSS=1000
        * (build_skeleton); everything else 1. */
      weight:   (N, N) => Long = (_: N, _: N) => 1L,
      /** `install_cluster` (cluster.c): dequeuing a skeleton node installs the
        * cluster's ENTIRE rankleader column (min→max rank) at once, then
        * enqueues every column node's neighbors in rank order — once per pass
        * (GD_installed guard). `columnOf(n)` = the column id; `columnNodes(id)`
        * = the rank-ordered column. */
      columnOf:    N => Option[Int]  = (_: N) => None,
      columnNodes: Int => Vector[N]  = (_: Int) => Vector.empty,
      /** `g == dot_root(g)`: only the root graph's build_ranks runs the tail
        * transpose (mincross.c:1343) — cluster interiors (mincross_clust,
        * startpass 2) never do. */
      rootGraph:   Boolean = true,
      /** Canonical-frame ports per SEGMENT (u,v) — feeds the median `VAL`,
        * the in/out_cross order-ties and `local_cross`. Parallel segments
        * share one entry (their ports coincide for duplicate DOT edges). */
      segPorts:    (N, N) => SegPorts = (_: N, _: N) => SegPorts.default,
      /** ND_has_port: some edge names a port on this node — gates the
        * `local_cross` term of ncross (rcross, mincross.c:1626). */
      hasPort:     N => Boolean = (_: N) => false,
      /** Flat (same-rank) edge reps `(tail, head, ED_weight)` in class2
        * order — drives flat_breakcycles / flat_reorder / left2right. */
      flatPairs:   Vector[(N, N, Double)] = Vector.empty[(N, N, Double)]
  )(using CanEqual[N, N]): (Map[Int, Vector[N]], Long) =
    // dot_mincross runs the FULL driver PER CONNECTED COMPONENT: decompose
    // splits the fast graph (out/in + FLAT adjacency, decomp.c) and
    // init_mccomp slices the rank arrays, so passes/iterations/save_best —
    // and the LR per-rank flip reversal — are all per component; the final
    // per-rank order is the concatenation in component (decompose) order.
    // Cluster interiors (rootGraph=false, mincross_clust) run unsliced.
    val comps: Vector[Vector[N]] =
      if !rootGraph then Vector(gdNlist)
      else
        val adj = mutable.HashMap.empty[N, mutable.ArrayBuffer[N]]
        def link(a: N, b: N): Unit =
          adj.getOrElseUpdate(a, mutable.ArrayBuffer.empty) += b
          adj.getOrElseUpdate(b, mutable.ArrayBuffer.empty) += a
        out.foreach { (n, ws) => ws.foreach(w => link(n, w)) }
        flatPairs.foreach { (t, h, _) => link(t, h) }
        val compOf = mutable.HashMap.empty[N, Int]
        var ci = 0
        gdNlist.foreach { seed =>
          if !compOf.contains(seed) then
            val stk = mutable.Stack(seed); compOf(seed) = ci
            while stk.nonEmpty do
              val n = stk.pop()
              adj.getOrElse(n, mutable.ArrayBuffer.empty).foreach { w =>
                if !compOf.contains(w) then { compOf(w) = ci; stk.push(w) }
              }
            ci += 1
        }
        (0 until ci).iterator.map(k => gdNlist.filter(n => compOf(n) == k)).toVector

    def runOne(nlist: Vector[N], fps: Vector[(N, N, Double)]): (Map[Int, Vector[N]], Long) =
      val minR  = if rankOf.isEmpty then 0 else rankOf.values.min
      val maxR  = if rankOf.isEmpty then 0 else rankOf.values.max
      val ranks = mutable.HashMap.from((minR to maxR).map(r => r -> mutable.ArrayBuffer.empty[N]))
      val pos   = mutable.HashMap.empty[N, Int]
      val mark  = mutable.Set.empty[N]
      def install(n: N): Unit =
        val rb = ranks(rankOf(n)); pos(n) = rb.length; rb += n

      // ── flat-edge machinery (mincross.c flat_breakcycles/flat_reorder) ────
      // ND_flat_out/ND_flat_in lists (class2 append order) + per-edge weight;
      // flat_rev mutates them (cycle break / non-constraining LR restore).
      val hasFlat = fps.nonEmpty
      val flOut = mutable.HashMap.empty[N, mutable.ArrayBuffer[N]]
      val flIn  = mutable.HashMap.empty[N, mutable.ArrayBuffer[N]]
      val flWt  = mutable.HashMap.empty[(N, N), Double]
      fps.foreach { (t, h, w) =>
        flOut.getOrElseUpdate(t, mutable.ArrayBuffer.empty) += h
        flIn.getOrElseUpdate(h, mutable.ArrayBuffer.empty) += t
        flWt((t, h)) = flWt.getOrElse((t, h), 0.0) + w
      }
      def fOut(n: N): mutable.ArrayBuffer[N] = flOut.getOrElse(n, mutable.ArrayBuffer.empty)
      def fIn(n: N):  mutable.ArrayBuffer[N] = flIn.getOrElse(n, mutable.ArrayBuffer.empty)
      /** fastgr.c flat_rev: delete (t,h); the reversed edge merges into an
        * existing (h,t) (merge_oneway sums weight) or appends as a new one. */
      def flatRev(t: N, h: N): Unit =
        val w = flWt.remove((t, h)).getOrElse(0.0)
        fOut(t) -= h; fIn(h) -= t
        if fOut(h).contains(t) then flWt((h, t)) = flWt.getOrElse((h, t), 0.0) + w
        else
          flOut.getOrElseUpdate(h, mutable.ArrayBuffer.empty) += t
          flIn.getOrElseUpdate(t, mutable.ArrayBuffer.empty) += h
          flWt((h, t)) = w
      // per-rank flat adjacency matrix (GD_rank[r].flat) + flatindex — assigned
      // ONCE (flat_breakcycles, pass 0) and consulted by left2right thereafter.
      val flatIdx = mutable.HashMap.empty[N, Int]
      val flatMx  = mutable.HashMap.empty[Int, mutable.HashSet[(Int, Int)]]
      def left2right(v: N, w: N): Boolean =
        flatMx.get(rankOf(v)) match
          case None    => false
          case Some(m) =>
            val (a, b) = if flip then (w, v) else (v, w)
            (flatIdx.get(a), flatIdx.get(b)) match
              case (Some(ia), Some(ib)) => m.contains((ia, ib))
              case _                    => false
      def flatBreakcycles(): Unit =
        if hasFlat then
          val fmark   = mutable.Set.empty[N]
          val onstack = mutable.Set.empty[N]
          (minR to maxR).foreach { r =>
            val rb  = ranks(r)
            var any = false
            rb.iterator.zipWithIndex.foreach { (v, i) =>
              flatIdx(v) = i
              if fOut(v).nonEmpty then any = true
            }
            if any then
              val m = flatMx.getOrElseUpdate(r, mutable.HashSet.empty)
              def search(v: N): Unit = // flat_search (mincross.c:1148)
                fmark += v; onstack += v
                val lst = fOut(v)
                var i = 0
                while i < lst.length do
                  val h = lst(i)
                  if flWt.getOrElse((v, h), 0.0) == 0.0 then i += 1
                  else if onstack(h) then
                    m += ((flatIdx(h), flatIdx(v)))
                    flatRev(v, h) // removes lst(i) — do not advance
                  else
                    m += ((flatIdx(v), flatIdx(h)))
                    if !fmark(h) then search(h)
                    i += 1
                onstack -= v
              rb.foreach(v => if !fmark(v) then search(v))
          }
      /** flat_reorder (mincross.c:1420): per rank, reverse-topological sort of
        * the constraining flat DAG (postorder DFS from constraint-free seeds,
        * scanned right-to-left for TB), then the reversed result becomes the
        * rank order; non-constraining flat edges pointing right-to-left get
        * flat_rev'd. */
      def flatReorder(): Unit =
        if hasFlat then
          (minR to maxR).foreach { r =>
            val rb = ranks(r)
            if rb.nonEmpty then
              val fmark = mutable.Set.empty[N]
              val temp  = mutable.ArrayBuffer.empty[N]
              def constraining(t: N, h: N): Boolean = flWt.getOrElse((t, h), 0.0) != 0.0
              def post(v: N): Unit = // postorder (mincross.c:1403)
                fmark += v
                fOut(v).foreach(h => if constraining(v, h) && !fmark(h) then post(h))
                temp += v
              val n = rb.length
              var i = 0
              while i < n do
                val v = if flip then rb(i) else rb(n - 1 - i)
                val inCnt  = fIn(v).count(t => constraining(t, v))
                val outCnt = fOut(v).count(h => constraining(v, h))
                if inCnt == 0 && outCnt == 0 then temp += v
                else if !fmark(v) && inCnt == 0 then post(v)
                i += 1
              if temp.nonEmpty && temp.length == rb.length then
                if !flip then
                  var a = 0; var b = temp.length - 1
                  while a < b do { val t = temp(a); temp(a) = temp(b); temp(b) = t; a += 1; b -= 1 }
                rb.indices.foreach { j => rb(j) = temp(j); pos(rb(j)) = j }
                // non-constraining flat edges must be made LR
                var vi = 0
                while vi < rb.length do
                  val v   = rb(vi)
                  val lst = fOut(v)
                  var j = 0
                  while j < lst.length do
                    val h = lst(j)
                    if (!flip && pos(h) < pos(v)) || (flip && pos(h) > pos(v)) then
                      flatRev(v, h) // removes lst(j) — do not advance
                    else j += 1
                  vi += 1
          }

      // build_ranks(pass) (mincross.c:1273): BFS installing each rank left-to-
      // right. pass 0 seeds from in-edge-free nodes and follows out-edges; pass 1
      // seeds from out-edge-free nodes and follows in-edges (`enqueue_neighbors`)
      // — the two initial orderings the driver picks the better of. Seeds are
      // iterated in GD_nlist order. Then, for a flipped graph (rankdir LR/RL),
      // EVERY rank is reversed (mincross.c:1334) — the LR order-axis mirror.
      def buildRanks(pass: Int): Unit =
        ranks.valuesIterator.foreach(_.clear()); pos.clear(); mark.clear()
        val colInstalled = mutable.Set.empty[Int] // GD_installed guard (per pass)
        val q = mutable.Queue.empty[N]
        def enq(n0: N): Unit =
          (if pass == 0 then out(n0) else in(n0)).foreach(w => if !mark(w) then { mark += w; q.enqueue(w) })
        def handle(n0: N): Unit = columnOf(n0) match
          case Some(ci) => // install_cluster: whole column once, then its neighbors
            if !colInstalled(ci) then
              colInstalled += ci
              val col = columnNodes(ci)
              col.foreach(install)
              col.foreach(enq)
          case None =>
            install(n0); enq(n0)
        nlist.foreach { root =>
          val rootFree = if pass == 0 then in(root).isEmpty else out(root).isEmpty
          if rootFree && !mark(root) then
            mark += root; q.enqueue(root)
            while q.nonEmpty do handle(q.dequeue())
        }
        nlist.foreach { n => // unreached safety net (gv components always have roots)
          if !mark(n) then
            mark += n; handle(n)
            while q.nonEmpty do handle(q.dequeue())
        }
        if flip then
          ranks.valuesIterator.foreach { rb =>
            val n = rb.length; var i = 0
            while i < n / 2 do { val t = rb(i); rb(i) = rb(n - 1 - i); rb(n - 1 - i) = t; i += 1 }
            rb.iterator.zipWithIndex.foreach { case (nd, idx) => pos(nd) = idx }
          }

      // ── crossing counting (weighted: ED_xpenalty(e1)*ED_xpenalty(e2)) ─────
      def bilayer(r: Int): Long =
        val segs = mutable.ArrayBuffer.empty[(Int, Int, Long)] // (pos up, pos down, xpenalty)
        ranks.getOrElse(r, mutable.ArrayBuffer.empty).foreach { u =>
          out(u).foreach(w => segs += ((pos(u), pos(w), weight(u, w))))
        }
        var c = 0L
        var i = 0
        while i < segs.length do
          var j = i + 1
          while j < segs.length do
            val (ui, wi, xi) = segs(i)
            val (uj, wj, xj) = segs(j)
            if (ui - uj) * (wi - wj) < 0 then c += xi * xj
            j += 1
          i += 1
        c
      // local_cross (mincross.c:1573): crossings among a SAME node's out (or
      // in) edges caused by PORT ordering — (Δ far-order) · (Δ NEAR-side port
      // p.x) < 0. rcross adds it for every ported node of the rank pair.
      def localOut(v: N): Long =
        val os = out.getOrElse(v, mutable.ArrayBuffer.empty)
        var c = 0L; var i = 0
        while i < os.length do
          var j = i + 1
          while j < os.length do
            val e = os(i); val f = os(j)
            if (pos(f) - pos(e)).sign * (segPorts(v, f).tailPX - segPorts(v, e).tailPX).sign < 0 then
              c += weight(v, e) * weight(v, f)
            j += 1
          i += 1
        c
      def localIn(v: N): Long =
        val is0 = in.getOrElse(v, mutable.ArrayBuffer.empty)
        var c = 0L; var i = 0
        while i < is0.length do
          var j = i + 1
          while j < is0.length do
            val e = is0(i); val f = is0(j)
            if (pos(f) - pos(e)).sign * (segPorts(f, v).headPX - segPorts(e, v).headPX).sign < 0 then
              c += weight(e, v) * weight(f, v)
            j += 1
          i += 1
        c
      def ncross: Long =
        (minR until maxR).iterator.map { r =>
          var c = bilayer(r)
          ranks.getOrElse(r, mutable.ArrayBuffer.empty).foreach(v => if hasPort(v) then c += localOut(v))
          ranks.getOrElse(r + 1, mutable.ArrayBuffer.empty).foreach(v => if hasPort(v) then c += localIn(v))
          c
        }.sum

      def exchange(r: Int, i: Int, j: Int): Unit =
        val rb = ranks(r)
        val a  = rb(i); val b = rb(j)
        rb(i) = b; rb(j) = a
        pos(a) = j; pos(b) = i

      // ── weighted-median values + reorder (mincross.c medians/reorder) ─────
      val mval = mutable.HashMap.empty[N, Double]
      // flat_mval (mincross.c:1706): a CHAIN-ISOLATED node (no in/out chain
      // segments — e.g. LKD's usr_/D0 spacers whose only edge went flat) gets
      // seated next to its flat neighbour: mval = mval(max-order flat-in
      // tail)+1, else mval(min-order flat-out head)−1. Returns true (⇒
      // hasfixed) when it could NOT assign one — reorder then keeps its
      // right-edge window (`ep--` is skipped).
      def flatMval(n: N): Boolean =
        val fin = fIn(n)
        if fin.nonEmpty then
          var nn = fin(0)
          fin.foreach(t => if pos(t) > pos(nn) then nn = t)
          if mval.getOrElse(nn, -1.0) >= 0 then { mval(n) = mval(nn) + 1; false }
          else true
        else
          val fout = fOut(n)
          if fout.nonEmpty then
            var nn = fout(0)
            fout.foreach(h => if pos(h) < pos(nn) then nn = h)
            if mval.getOrElse(nn, -1.0) > 0 then { mval(n) = mval(nn) - 1; false }
            else true
          else true
      def medians(r0: Int, r1: Int): Boolean =
        val rb = ranks.getOrElse(r0, mutable.ArrayBuffer.empty)
        rb.foreach { n =>
          // VAL(node, port) = MC_SCALE·ND_order + port.order (mincross.c:1709):
          // the median list is INTEGER; the down pass keys each out-neighbour
          // with the segment's HEAD port ordinal, the up pass with the TAIL's.
          val nbrs =
            (if r1 > r0 then out(n).map(w => 256 * pos(w) + segPorts(n, w).headOrd)
             else in(n).map(x => 256 * pos(x) + segPorts(x, n).tailOrd)).sorted
          mval(n) = nbrs.length match
            case 0 => -1.0
            case 1 => nbrs(0).toDouble
            case 2 => ((nbrs(0) + nbrs(1)) / 2).toDouble // C int division
            case j =>
              val rm = j / 2
              if j % 2 == 1 then nbrs(rm).toDouble
              else
                val lm    = rm - 1
                val rspan = nbrs(j - 1) - nbrs(rm)
                val lspan = nbrs(lm) - nbrs(0)
                if lspan == rspan then ((nbrs(lm) + nbrs(rm)) / 2).toDouble // int div
                else (nbrs(lm).toDouble * rspan + nbrs(rm).toDouble * lspan) / (lspan + rspan)
        }
        var hasfixed = false
        rb.foreach { n =>
          if out(n).isEmpty && in(n).isEmpty then hasfixed = flatMval(n) || hasfixed
        }
        hasfixed

      def reorder(r: Int, reverse: Boolean, hasfixed: Boolean): Unit =
        val rb = ranks(r)
        var ep = rb.length
        var nelt = rb.length - 1
        while nelt >= 0 do
          var lp = 0
          while lp < ep do
            while lp < ep && mval.getOrElse(rb(lp), -1.0) < 0 do lp += 1
            if lp < ep then
              // find the node that can be compared; a left2right hit on ANY
              // scanned node (incl. the comparable one) pins lp (muststay).
              // gv's `###` sawclust rule (mincross.c:1487): once an UNSEATED
              // (mval < 0) node with ND_clust is passed, every later clustered
              // node is SKIPPED outright — it can neither pin lp nor become
              // the comparison partner. In the collapsed pass ND_clust is set
              // exactly on the cluster skeleton leaders (build_skeleton), so
              // `columnOf` is the predicate; with no clusters it is never set
              // and the rule is inert, matching gv.
              var rp = lp + 1
              var muststay = false
              var found    = false
              var sawclust = false
              while rp < ep && !muststay && !found do
                if sawclust && columnOf(rb(rp)).isDefined then rp += 1
                else if left2right(rb(lp), rb(rp)) then muststay = true
                else if mval.getOrElse(rb(rp), -1.0) >= 0 then found = true
                else
                  if columnOf(rb(rp)).isDefined then sawclust = true
                  rp += 1
              if rp < ep then
                if !muststay then
                  val p1 = mval.getOrElse(rb(lp), -1.0)
                  val p2 = mval.getOrElse(rb(rp), -1.0)
                  if p1 > p2 || (p1 >= p2 && reverse) then exchange(r, lp, rp)
                lp = rp
              else lp = ep
            else lp = ep
          // gv: `if (!hasfixed && !reverse) ep--` — an unseated fixed node
          // keeps the full window so it can still drift right.
          if !hasfixed && !reverse then ep -= 1
          nelt -= 1

      // ── transpose (adjacent swaps; in_cross/out_cross xpenalty products) ──
      def crossIn(v: N, w: N): Long = // in_cross(v, w): v's in-tails right of w's
        var c = 0L
        in(v).foreach(x => in(w).foreach { y =>
          // equal tail ORDER ties break on the tails' canonical port p.x
          // (mincross.c:648): two record ports on one node still cross.
          if pos(x) > pos(y) ||
             (pos(x) == pos(y) && segPorts(x, v).tailPX > segPorts(y, w).tailPX) then
            c += weight(x, v) * weight(y, w)
        })
        c
      def crossOut(v: N, w: N): Long =
        var c = 0L
        out(v).foreach(x => out(w).foreach { y =>
          if pos(x) > pos(y) ||
             (pos(x) == pos(y) && segPorts(v, x).headPX > segPorts(w, y).headPX) then
            c += weight(v, x) * weight(w, y)
        })
        c
      // transpose (mincross.c:728): candidate flags — a sweep visits only
      // flagged ranks; a swap re-flags the rank and its neighbours. The final
      // orders can differ from an all-ranks sweep, so transcribe the flags.
      val candidate = mutable.HashMap.from((minR to maxR).map(r => r -> true))
      def transposeStep(r: Int, reverse: Boolean): Long =
        var delta = 0L
        candidate(r) = false
        val rb = ranks(r)
        var i = 0
        while i < rb.length - 1 do
          val v = rb(i); val w = rb(i + 1)
          // a constraining flat edge v→w pins the pair (left2right, mincross.c:740)
          if !left2right(v, w) then
            var c0 = 0L; var c1 = 0L
            if r > minR then { c0 += crossIn(v, w); c1 += crossIn(w, v) }
            if r < maxR && ranks(r + 1).nonEmpty then { c0 += crossOut(v, w); c1 += crossOut(w, v) }
            val doSwap = c1 < c0 || (c0 > 0 && reverse && c1 == c0)
            if doSwap then
              exchange(r, i, i + 1)
              delta += c0 - c1
              candidate(r) = true
              if r > minR then candidate(r - 1) = true
              if r < maxR then candidate(r + 1) = true
          i += 1
        delta
      def transpose(reverse: Boolean): Unit =
        (minR to maxR).foreach(r => candidate(r) = true)
        var delta = 0L
        while {
          delta = 0L
          (minR to maxR).foreach(r => if candidate(r) then delta += transposeStep(r, reverse))
          delta >= 1
        } do ()

      def mincrossStep(pass: Int): Unit =
        val reverse = pass % 4 < 2
        val (first, last, dir) =
          if pass % 2 == 0 then (minR + 1, maxR, 1) else (maxR - 1, minR, -1)
        var r = first
        while r != last + dir do
          val hasfixed = medians(r, r - dir)
          reorder(r, reverse, hasfixed)
          r += dir
        transpose(!reverse)

      // ── mincross driver (mincross.c:745) ─────────────────────────────────
      // Passes 0 and 1 each rebuild the whole order from a different initial BFS
      // (in-free vs out-free seeds) + ≤min(4,MaxIter) refinement iters; pass 2
      // refines the best of those for up to MaxIter. `save_best`/`restore_best`
      // keep the min-crossing order seen; a final `transpose(false)` polishes it.
      def snapshot(): Map[Int, Vector[N]] =
        ranks.iterator.map { case (r, b) => r -> b.toVector }.toMap
      def restore(s: Map[Int, Vector[N]]): Unit =
        s.foreach { case (r, v) =>
          val rb = ranks(r); rb.clear(); rb ++= v
          v.iterator.zipWithIndex.foreach { case (n, i) => pos(n) = i }
        }

      var best      = Map.empty[Int, Vector[N]]
      var bestCross = Long.MaxValue
      var cur       = Long.MaxValue
      var pass      = 0
      var stop      = false
      while pass <= 2 && !stop do
        val maxthispass = if pass <= 1 then math.min(4, MaxIter) else MaxIter
        if pass <= 1 then
          buildRanks(pass)
          // build_ranks tail (mincross.c:1349): the fresh BFS install can leave
          // crossings that a single transpose pass removes; gv polishes the initial
          // order here — inside build_ranks, before the driver computes cur_cross.
          // Omitting it seeds the driver with a mirror-equivalent order whose later
          // median/transpose passes settle to the opposite tie-break (06's X-mirror).
          // Root graph only (`g == dot_root(g)`); with CL_CROSS weights this is
          // also what floats free vnode chains outside cluster skeleton columns.
          if rootGraph && ncross > 0 then transpose(false)
          // flat_breakcycles (pass 0 only) + flat_reorder (both passes) run
          // AFTER build_ranks' internal transpose (mincross.c:776).
          if pass == 0 then flatBreakcycles()
          flatReorder()
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
      (snapshot(), bestCross)

    if comps.length == 1 then runOne(comps.head, flatPairs)
    else
      var totalCross = 0L
      val finalRanks = mutable.HashMap.empty[Int, Vector[N]]
      comps.foreach { comp =>
        val cset = comp.toSet
        val (ord, c) = runOne(comp, flatPairs.filter(p => cset.contains(p._1)))
        totalCross += c
        ord.foreach { (r, v) =>
          if v.nonEmpty then finalRanks(r) = finalRanks.getOrElse(r, Vector.empty[N]) ++ v
        }
      }
      (finalRanks.toMap.withDefaultValue(Vector.empty[N]), totalCross)

end Order
