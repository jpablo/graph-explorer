package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.RGraph
import org.jpablo.graphexplorer.graphviz.units.Length.Pt
import scala.collection.mutable

/** Phase 3b of the `dot` pipeline: cross-axis (X) coordinate assignment.
  *
  * Ports `create_aux_edges` + `set_xcoords` (lib/dotgen/position.c, gv 13.0.1):
  * build an auxiliary graph whose network-simplex ranks ARE the x positions.
  *
  *  - `make_LR_constraints`: per rank, consecutive nodes u→v get a 0-weight
  *    aux edge with minlen `rw(u)+lw(v)+nodesep` (order + separation).
  *  - `make_edge_pairs`: every layout segment t→h gets a slack node `sn` with
  *    edges `sn→t`, `sn→h` (minlen 1, weight = ω·edgeweight). Minimising
  *    `Σ weight·Δrank` then straightens edges; ω = 1 (real–real), 2
  *    (real–virtual), 8 (virtual–virtual).
  *
  * Virtual node half-width = `virtual_node` base (1) + `incr_width`
  * (nodesep/2) = 10pt (class2.c). NS `balance=2` (LR) handles slack centring.
  */
object XCoord:

  // Memoized (per-graph, size-1): renderFormats hits xSolve ~7× on one graph.
  // Third element: solved cluster [ln, rn] border x per Cluster.clusters idx.
  private val solveMemo = GraphMemo[(Order.Result, Map[LayoutNode, Pt], Vector[(Double, Double)])]()
  private def xSolve(g: RGraph): (Order.Result, Map[LayoutNode, Pt], Vector[(Double, Double)]) =
    solveMemo(g)(xSolveImpl(g))

  /** gv `decompose` (decomp.c): rebuild the node list in DFS order from
    * declaration-order seeds, following out-edges before in-edges over the
    * real+virtual layout graph. This is the `GD_nlist` order the aux-graph
    * network simplex iterates — the NS is order-sensitive (feasible_tree,
    * LR_balance), so reproducing it is what makes x-coords byte-exact.
    * `search_component` pushes vec order flat_in/flat_out/in/out (each
    * reversed); with a LIFO stack the out-neighbours pop first, in forward
    * order. */
  private def decomposeOrder(g: RGraph, res: Order.Result): Vector[LayoutNode] =
    val nodes  = res.order.values.flatten.toVector
    val out    = mutable.LinkedHashMap.from(nodes.map(_ -> mutable.ArrayBuffer.empty[LayoutNode]))
    val in     = mutable.LinkedHashMap.from(nodes.map(_ -> mutable.ArrayBuffer.empty[LayoutNode]))
    res.segments.foreach { case (t, h) => out(t) += h; in(h) += t }
    val done   = mutable.Set.empty[LayoutNode]
    val result = mutable.ArrayBuffer.empty[LayoutNode]
    def visit(seed: LayoutNode): Unit =
      val stk = mutable.Stack(seed)
      while stk.nonEmpty do
        val n = stk.pop()
        if !done(n) then
          done += n; result += n
          in(n).reverseIterator.foreach(w => if !done(w) then stk.push(w))
          out(n).reverseIterator.foreach(w => if !done(w) then stk.push(w))
    g.nodes.foreach { n => val s: LayoutNode = LayoutNode.Real(n.id); if !done(s) then visit(s) }
    nodes.foreach(n => if !done(n) then { done += n; result += n }) // isolated
    result.toVector

  /** Core solve: ordering + x (points) for all placed nodes, left edge at 0. */
  private def xSolveImpl(g: RGraph): (Order.Result, Map[LayoutNode, Pt], Vector[(Double, Double)]) =
    val res  = Order.order(g)
    val byId = g.nodes.iterator.map(n => n.id -> n).toMap

    // GD_nodesep (attr-driven, default 18pt) + a plain virtual node's half
    // width (`incr_width` = 1 + nodesep/2, class2.c plain_vnode).
    val NodeSep     = Coord.nodeSepPt(g)
    val VirtualHalf = Coord.virtualHalfPt(g)

    // gv quirk (ported deliberately): intra-cluster chain vnodes are created
    // by class2(subg) in expand_cluster, and incr_width reads GD_nodesep(subg)
    // — never initialized on cluster subgraphs (0) — so they keep
    // virtual_node's lw=rw=1, unlike root-graph chain vnodes (1 + nodesep/2).
    val intraVEdge: Set[Int] =
      val clustOfName = Cluster.clustOf(g)
      val ends = g.edges.filter(e => e.tail != e.head).map(e => (e.tail, e.head))
      ends.indices.filter { i =>
        (clustOfName.get(ends(i)._1), clustOfName.get(ends(i)._2)) match
          case (Some(a), Some(b)) => a == b
          case _                  => false
      }.toSet

    def half(n: LayoutNode): Double = n match
      case LayoutNode.Virtual(d, _) if intraVEdge(d) => 1.0
      case _: LayoutNode.Virtual => VirtualHalf
      case _: LayoutNode.Slack   => VirtualHalf // never queried; defensive
      case _: LayoutNode.ClusterLn | _: LayoutNode.ClusterRn => 1.0 // virtual_node lw=rw=1
      case LayoutNode.Real(id)   =>
        byId.get(id).flatMap(rn => NodeSize.layoutSize(rn, g))
          .map(_.halfWidthPt.value).getOrElse(1.0)

    // Asymmetric widths (class2.c label_vnode): a label vnode has lw=nodesep,
    // rw=labelWidth; every other node is symmetric (lw=rw=half) so this is
    // identical to `half` unless a label vnode is involved.
    val labelW = Coord.labelVnodeWidths(g)
    // make_LR_constraints (position.c:251): a self-looped node's ND_rw is
    // inflated by Σ selfRightSpace BEFORE the aux solve (the original rw is
    // parked in ND_mval) — the loop + its label reserve space on the right,
    // pushing everything after it in the rank (fsm's LR_4/LR_7/LR_8).
    val selfRw: Map[String, Double] =
      g.edges.filter(e => e.tail == e.head).groupBy(_.tail).view
        .mapValues(_.map(Coord.selfRightSpace(_, g)).sum).toMap
    def lw(n: LayoutNode): Double = n match
      case v: LayoutNode.Virtual if labelW.contains(v.name) => NodeSep
      case _                                                => half(n)
    def rw(n: LayoutNode): Double = n match
      case v: LayoutNode.Virtual if labelW.contains(v.name) => labelW(v.name)
      case LayoutNode.Real(id) if selfRw.contains(id)       => half(n) + selfRw(id)
      case _                                                => half(n)

    // virtual_weight() (mincross.c): aux edge-pair weight = ω·edgeweight
    // where ω = NSClass.weight(class(tail), class(head)). See [[NSClass]]
    // for the table and case definitions.
    val deg = mutable.HashMap.empty[String, Int].withDefaultValue(0)
    g.edges.foreach { e =>
      if e.tail != e.head then { deg(e.tail) = deg(e.tail) + 1; deg(e.head) = deg(e.head) + 1 }
    }
    def cls(n: LayoutNode): NSClass = n match
      case _: LayoutNode.Virtual => NSClass.Virtual
      case _: LayoutNode.Slack   => NSClass.Virtual // never queried; defensive
      case _: LayoutNode.ClusterLn | _: LayoutNode.ClusterRn => NSClass.Virtual // defensive
      case LayoutNode.Real(id)   =>
        if deg(id) <= 1 then NSClass.Singleton else NSClass.Ordinary

    val edges = mutable.ArrayBuffer.empty[NetworkSimplex.NSEdge]

    // Initial aux-graph ranks: gv's make_LR_constraints left-packs each rank
    // (`ND_rank(v) = last + width`) and make_edge_pairs seats each slack below
    // its endpoints. These are already feasible, so gv skips init_rank — and
    // that seed determines which feasible_tree is built ⇒ the NS is only
    // byte-exact if seeded with the SAME ranks.
    val initRank = mutable.Map.empty[String, Int]

    // make_LR_constraints: separation within each rank, ranks in order
    // (GD_minrank..GD_maxrank). When the graph has edge labels, gv
    // (position.c:226) shrinks the separation on ODD ranks — where the
    // label/chain vnodes live — from nodesep(18) to 5. `sep[i & 1]`.
    val hasEL = Rank.hasEdgeLabel(g)
    // Flat (same-rank) edges between order-adjacent nodes widen their pair's
    // separation (make_LR_constraints flat_out): the aux-adjacency edge's
    // minlen is bumped to `max(ED_minlen(e)·nodesep + width, width + nodesep +
    // ROUND(ED_dist))`, where `width = rw(left)+lw(right)`, `ED_dist` is the
    // widest flat-edge label, and the flat edge's own `ED_minlen` is doubled
    // when the graph has edge labels. Keyed by the ordered (left,right) names.
    val posInRank: Map[String, (Int, Int)] =
      res.order.iterator.flatMap { (r, ids) => ids.zipWithIndex.map { (n, p) => n.name -> (r, p) } }.toMap
    val flatBump: Map[(String, String), Double] =
      g.edges.iterator.filter(e => e.tail != e.head).flatMap { e =>
        for
          (rt, pt) <- posInRank.get(e.tail)
          (rh, ph) <- posInRank.get(e.head)
          if rt == rh && math.abs(pt - ph) == 1
        yield
          val (l, r) = if pt < ph then (e.tail, e.head) else (e.head, e.tail)
          val lblW   = if e.attrs.get("label").exists(_.nonEmpty) then Coord.edgeLabelDim(e, g)._1 else 0.0
          (l, r) -> lblW
      }.toVector.groupBy(_._1).view.mapValues(_.map(_._2).max).toMap
    // make_LR_constraints flat_out: bump the pair's aux-adjacency minlen to
    // `max(ED_minlen·nodesep + width, width + nodesep + ROUND(ED_dist))`, the
    // flat edge's ED_minlen being doubled when the graph has edge labels.
    def flatMinlenOf(u: LayoutNode, v: LayoutNode, base: Int): Int =
      flatBump.get((u.name, v.name)) match
        case Some(lblW) =>
          val width = rw(u) + lw(v)
          val fm    = if hasEL then 2 else 1
          val m0    = math.max(fm * NodeSep + width, width + NodeSep + math.round(lblW).toDouble)
          math.max(base, math.round(m0).toInt)
        case None => base
    res.order.toList.sortBy(_._1).foreach { case (rank, ids) =>
      val nodesep = if hasEL && (rank & 1) == 1 then 5.0 else NodeSep
      ids.headOption.foreach(h => initRank(h.name) = 0)
      var last = 0
      ids.sliding(2).foreach {
        case Seq(u, v) =>
          // The aux EDGE minlen is `ROUND(width)` (make_aux_edge → ED_minlen),
          // but the SEED rank gv left-packs is `ND_rank(v) = (int)(last +
          // width)` — the running int rank plus the RAW width, TRUNCATED
          // (position.c:262). The flat_out bump raises the EDGE minlen only
          // (the NS re-solves from the seed, which may go infeasible → init_rank).
          val width  = rw(u) + lw(v) + nodesep
          val minlen = flatMinlenOf(u, v, math.round(width).toInt)
          edges += NetworkSimplex.NSEdge(u.name, v.name, minlen, 0)
          last = (last + width).toInt
          initRank(v.name) = last
        case _ => ()
      }
    }

    // make_edge_pairs: per-segment slack node, ω-weighted straightening.
    // Port offset (position.c `make_edge_pairs`): m0 = (int)(headport.p.x −
    // tailport.p.x); minlens become (max(m0,0)+1, max(−m0,0)+1) so the slack
    // node pulls the segment straight *through the ports*, not node centres.
    // Portless edges ⇒ both ports x=0 ⇒ m0=0 ⇒ (1,1): byte-identical.
    val realEdges = g.edges.filter(e => e.tail != e.head)
    def portX(nodeId: String, port: Option[org.jpablo.graphexplorer.graphviz.dotlang.Port]): Double =
      // ED_*_port.p is stored in the CANONICAL frame (cwrotatepf, shapes.c) —
      // for LR a record field's vertical offset becomes the canonical x.
      byId.get(nodeId).map(n => PortAnchor.canonical(n, g, port)._1).getOrElse(0.0)
    // gv make_edge_pairs iterates GD_nlist (decompose order); per node, per
    // out-segment (ND_save_out order = segment index), it creates a slack node
    // and two straightening edges. This order — NOT segment order — is what the
    // NS sees, so reproduce it exactly. Slack nodes are collected in creation
    // order, then prepended (reversed) to head the NS node list (GD_nlist).
    val decomp  = decomposeOrder(g, res)
    val outSegs = mutable.LinkedHashMap.empty[LayoutNode, mutable.ArrayBuffer[Int]]
    res.segments.iterator.zipWithIndex.foreach { case ((t, _), i) =>
      outSegs.getOrElseUpdate(t, mutable.ArrayBuffer.empty) += i
    }
    val slackNodes = mutable.ArrayBuffer.empty[LayoutNode]
    decomp.foreach { node =>
      outSegs.getOrElse(node, mutable.ArrayBuffer.empty).foreach { i =>
      val (t, h) = res.segments(i)
      val sn: LayoutNode = LayoutNode.Slack(i)
      slackNodes += sn
      val owner = res.segOwner.lift(i).getOrElse(-1)
      val owned = if owner >= 0 && owner < realEdges.length then Some(realEdges(owner)) else None
      // make_edge_pairs slack weight = ω-class × the **edge `weight`**
      // (ED_weight, default 1; the whole virtual chain inherits it). Was
      // a documented M5+ deferral; default-1 ⇒ 01/06/07 unchanged.
      val wt = owned.flatMap(_.attrs.get("weight")).flatMap(_.toDoubleOption)
        .map(w => math.max(1, math.round(w).toInt)).getOrElse(1)
      val w  = NSClass.weight(cls(t), cls(h)) * wt
      val (m0, m1) =
        owned match
          case Some(re) =>
            // Endpoint match requires comparing the *real* node id, not a
            // synthetic virtual carrying the chain through the same rank.
            // Segments run in WORKING orientation: an acyclic-reversed dedge's
            // first segment tails the original HEAD (whose port gv swapped
            // onto ED_tail_port at reversal) — match either end and use that
            // end's own port, or the reversed chains drop their ports (ds's
            // rigid −7 subtree: node7:f1→node1:f0, node11:f2→node1:f0).
            val tpx = t match
              case LayoutNode.Real(id) if id == re.tail => portX(re.tail, re.tailPort)
              case LayoutNode.Real(id) if id == re.head => portX(re.head, re.headPort)
              case _ => 0.0
            val hpx = h match
              case LayoutNode.Real(id) if id == re.head => portX(re.head, re.headPort)
              case LayoutNode.Real(id) if id == re.tail => portX(re.tail, re.tailPort)
              case _ => 0.0
            val m   = (hpx - tpx).toInt // C `int` truncation toward zero
            if m > 0 then (m, 0) else (0, -m)
          case None => (0, 0)
      edges += NetworkSimplex.NSEdge(sn.name, t.name, m0 + 1, w)
      edges += NetworkSimplex.NSEdge(sn.name, h.name, m1 + 1, w)
      // ND_rank(sn) = MIN(rank(tail) − m0 − 1, rank(head) − m1 − 1)
      initRank(sn.name) = math.min(initRank(t.name) - (m0 + 1), initRank(h.name) - (m1 + 1))
      }
    }

    // ── pos_clusters (position.c:491): cluster containment/separation ──────
    // Each cluster gets `ln`/`rn` border slacknodes (make_lrvn); containment,
    // sibling separation, label min-width and box compaction all become
    // ordinary aux edges (margin = CL_OFFSET = 8) in the SAME solve. Runs
    // after make_edge_pairs, exactly as create_aux_edges orders it.
    val cluInfos = Cluster.clusters(g)
    val clSlacks = mutable.ArrayBuffer.empty[LayoutNode] // fast_node creation order
    if cluInfos.nonEmpty then
      val ClOffset = 8.0
      val flip     = Rank.flip(g)
      def ln(i: Int) = LayoutNode.ClusterLn(i)
      def rn(i: Int) = LayoutNode.ClusterRn(i)
      val lrvnMade = mutable.Set.empty[Int]
      // GD_rank(cluster): the cluster's slice of the global order (real
      // members + chain vnodes of INTRA-cluster edges — gv mark_clusters puts
      // those vnodes in the cluster, so contain_nodes holds them inside the
      // borders and the box reserves their width; e.g. groups.dot's reversed
      // a3->a0 chain), preserving mincross order.
      def inClust(ci: Int, n: LayoutNode): Boolean = n match
        case LayoutNode.Real(id) => cluInfos(ci).members(id)
        case LayoutNode.Virtual(d, _) =>
          realEdges.lift(d).exists(re =>
            cluInfos(ci).members(re.tail) && cluInfos(ci).members(re.head))
        case _ => false
      def slice(ci: Int, r: Int): Vector[LayoutNode] =
        res.order.getOrElse(r, Vector.empty).filter(inClust(ci, _)).toVector
      def gRow(r: Int): Vector[LayoutNode] = res.order.getOrElse(r, Vector.empty).toVector
      // make_lrvn: create the border pair; a TB cluster label forces the box
      // at least as wide as the padded label (ln→rn aux edge, weight 0).
      def makeLrvn(ci: Int): Unit =
        if !lrvnMade(ci) then
          lrvnMade += ci
          clSlacks += ln(ci); clSlacks += rn(ci)
          if ci >= 0 && cluInfos(ci).hasLabel && !flip then
            edges += NetworkSimplex.NSEdge(ln(ci).name, rn(ci).name,
              math.round(cluInfos(ci).borderTopX).toInt, 0)
      // contain_nodes: per rank, first/last member held inside the borders.
      def containNodes(ci: Int): Unit =
        makeLrvn(ci)
        var r = cluInfos(ci).minRank
        while r <= cluInfos(ci).maxRank do
          val row = slice(ci, r)
          if row.nonEmpty then
            edges += NetworkSimplex.NSEdge(ln(ci).name, row.head.name,
              math.round(lw(row.head) + ClOffset).toInt, 0)
            edges += NetworkSimplex.NSEdge(row.last.name, rn(ci).name,
              math.round(rw(row.last) + ClOffset).toInt, 0)
          r += 1
      // contain_clustnodes: containment + the weight-128 compaction edge
      // (the label edge, if present, is re-weighted instead — find_fast_edge).
      def containClustnodes(ci: Int): Unit =
        if ci >= 0 then
          containNodes(ci)
          val ei = edges.indexWhere(e => e.tail == ln(ci).name && e.head == rn(ci).name)
          if ei >= 0 then edges(ei) = edges(ei).copy(weight = edges(ei).weight + 128)
          else edges += NetworkSimplex.NSEdge(ln(ci).name, rn(ci).name, 1, 128)
        Cluster.childrenOf(g, ci).foreach(containClustnodes)
      // vnode_not_related_to: a virtual whose original edge has no endpoint
      // in the cluster (its chain merely passes alongside).
      def vnodeNotRelated(ci: Int, n: LayoutNode): Boolean = n match
        case LayoutNode.Virtual(d, _) =>
          realEdges.lift(d).forall(re =>
            !cluInfos(ci).nodeIds.contains(re.tail) && !cluInfos(ci).nodeIds.contains(re.head))
        case _ => false
      def isNormal(n: LayoutNode): Boolean = n match
        case _: LayoutNode.Real => true
        case _                  => false
      // keepout_othernodes: nearest unrelated node left/right of the cluster
      // on each rank is pushed CL_OFFSET clear of the border.
      def keepout(ci: Int): Unit =
        if ci >= 0 then
          var r = cluInfos(ci).minRank
          while r <= cluInfos(ci).maxRank do
            val row = slice(ci, r)
            if row.nonEmpty then
              val grow = gRow(r)
              val pos  = grow.indexOf(row.head)
              var i    = pos - 1
              var stop = false
              while !stop && i >= 0 do
                val u = grow(i)
                if isNormal(u) || vnodeNotRelated(ci, u) then
                  edges += NetworkSimplex.NSEdge(u.name, ln(ci).name,
                    math.round(ClOffset + rw(u)).toInt, 0)
                  stop = true
                i -= 1
              var j = pos + row.length
              stop = false
              while !stop && j < grow.length do
                val u = grow(j)
                if isNormal(u) || vnodeNotRelated(ci, u) then
                  edges += NetworkSimplex.NSEdge(rn(ci).name, u.name,
                    math.round(ClOffset + lw(u)).toInt, 0)
                  stop = true
                j += 1
            r += 1
        Cluster.childrenOf(g, ci).foreach(keepout)
      // contain_subclust: a subcluster's borders sit ≥ margin inside its
      // parent's (side label borders are 0 in TB — labels live on top).
      def containSubclust(ci: Int): Unit =
        makeLrvn(ci)
        Cluster.childrenOf(g, ci).foreach { cc =>
          makeLrvn(cc)
          edges += NetworkSimplex.NSEdge(ln(ci).name, ln(cc).name, math.round(ClOffset).toInt, 0)
          edges += NetworkSimplex.NSEdge(rn(cc).name, rn(ci).name, math.round(ClOffset).toInt, 0)
          containSubclust(cc)
        }
      // separate_subclust: rank-overlapping sibling boxes stay margin apart,
      // ordered by their first nodes on the first shared rank.
      def separateSubclust(ci: Int): Unit =
        val kids = Cluster.childrenOf(g, ci)
        kids.foreach(makeLrvn)
        for ii <- kids.indices; jj <- ii + 1 until kids.length do
          var low  = kids(ii)
          var high = kids(jj)
          if cluInfos(low).minRank > cluInfos(high).minRank then
            val t = low; low = high; high = t
          if cluInfos(low).maxRank >= cluInfos(high).minRank then
            val r    = cluInfos(high).minRank
            val grow = gRow(r)
            val lo   = slice(low, r)
            val hi   = slice(high, r)
            if lo.nonEmpty && hi.nonEmpty then
              val (l, rr) =
                if grow.indexOf(lo.head) < grow.indexOf(hi.head) then (low, high) else (high, low)
              edges += NetworkSimplex.NSEdge(rn(l).name, ln(rr).name, math.round(ClOffset).toInt, 0)
        kids.foreach(separateSubclust)
      containClustnodes(-1)
      keepout(-1)
      containSubclust(-1)
      separateSubclust(-1)

    // The NS node order MUST be gv's GD_nlist: cluster ln/rn (created last ⇒
    // prepended nearest the head, in reverse creation order), then the
    // make_edge_pairs slacks (reversed), then the decompose-DFS'd real+virtual
    // nodes. The order-sensitive NS reproduces gv's x-solve only fed this order.
    val nodeOrder: Vector[LayoutNode] =
      clSlacks.reverseIterator.toVector ++ slackNodes.reverseIterator.toVector ++ decomp
    val xr = NetworkSimplex.solve(nodeOrder.map(_.name), edges.toSeq, balance = NSBalance.LeftRight, initRanks = initRank)
    // NS returns String-keyed ranks; map back to LayoutNode via the
    // historical name-form parse (Order produces the same Virtual/Slack
    // identities so the round-trip is exact).
    val xrByNode: Map[LayoutNode, Int] = nodeOrder.iterator.map(n => n -> xr(n.name)).toMap

    // NO normalization: gv's canonical x coords ARE the raw aux-solve ranks
    // (integers, possibly negative) — the drawing reaches the origin only at
    // postproc, via GD_bb (our writers' dx = −bb.LL / DrawTransform Offset).
    // The old shift here (leftmost real's `x − lw`) was FRACTIONAL (node
    // half-widths like 33.033), and `maximal_bbox`'s round() is not
    // translation-invariant — every spline-phase rounding landed on a
    // different lattice than gv's (fsm's cascading 0.033pt drifts).
    val placed: Vector[LayoutNode] = res.order.values.flatten.toVector
    val shift  = 0.0
    val allX = placed.iterator.map(id => id -> Pt(xrByNode(id).toDouble - shift)).toMap
    val clB  = cluInfos.indices.map { i =>
      (xrByNode(LayoutNode.ClusterLn(i)).toDouble - shift,
       xrByNode(LayoutNode.ClusterRn(i)).toDouble - shift)
    }.toVector
    (res, allX, clB)

  /** x (points) for real **and** virtual placed nodes, plus the ordering. */
  def solveAll(g: RGraph): (Order.Result, Map[LayoutNode, Pt]) =
    val (res, allX, _) = xSolve(g)
    (res, allX)

  /** Solved cluster border x `[ln, rn]` per [[Cluster.clusters]] index. */
  def clusterXBounds(g: RGraph): Vector[(Double, Double)] = xSolve(g)._3

  /** x (points) for real nodes only. Memoized: the solve itself is cached,
    * but this projection re-hashed all N entries on each of its ~5-6 calls
    * per render (bbox ×2-3, json0, Svg, DrawTransform). */
  private val xCoordsMemo = GraphMemo[Map[String, Pt]]()
  def xCoords(g: RGraph): Map[String, Pt] =
    xCoordsMemo(g) {
      val (_, allX, _) = xSolve(g)
      g.nodes.iterator.map(n => n.id -> allX(LayoutNode.Real(n.id))).toMap
    }

end XCoord
