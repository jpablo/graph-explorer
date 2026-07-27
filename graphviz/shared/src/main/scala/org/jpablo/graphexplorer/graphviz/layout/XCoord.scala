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
    // decomp.c search_component walks FOUR lists — ND_out, ND_in,
    // ND_flat_out, ND_flat_in — flat (same-rank) reps carry the DFS across
    // rank=same siblings (class2 emit order; parallels merge to the rep).
    val flatOut = mutable.HashMap.empty[LayoutNode, mutable.ArrayBuffer[LayoutNode]]
    val flatIn  = mutable.HashMap.empty[LayoutNode, mutable.ArrayBuffer[LayoutNode]]
    locally {
      val nodeSeqD = g.nodes.iterator.map(_.id).zipWithIndex.toMap
      val realD    = g.edges.filter(e => e.tail != e.head)
      val byTailD  = realD.indices.groupBy(i => realD(i).tail)
      val seen     = mutable.Set.empty[(String, String)]
      g.nodes.foreach { n =>
        byTailD.getOrElse(n.id, Seq.empty)
          .sortBy(i => (nodeSeqD.getOrElse(realD(i).head, Int.MaxValue), i))
          .foreach { i =>
            val e = realD(i)
            if res.rank.get(e.tail) == res.rank.get(e.head) && res.rank.contains(e.tail)
               && !seen((e.tail, e.head)) then
              seen += ((e.tail, e.head))
              val (t, h) = (LayoutNode.Real(e.tail): LayoutNode, LayoutNode.Real(e.head): LayoutNode)
              flatOut.getOrElseUpdate(t, mutable.ArrayBuffer.empty) += h
              flatIn.getOrElseUpdate(h, mutable.ArrayBuffer.empty) += t
          }
      }
    }
    val done   = mutable.Set.empty[LayoutNode]
    val result = mutable.ArrayBuffer.empty[LayoutNode]
    val emptyA = mutable.ArrayBuffer.empty[LayoutNode]
    def visit(seed: LayoutNode): Unit =
      val stk = mutable.Stack(seed)
      while stk.nonEmpty do
        val n = stk.pop()
        if !done(n) then
          done += n; result += n
          flatIn.getOrElse(n, emptyA).reverseIterator.foreach(w => if !done(w) then stk.push(w))
          flatOut.getOrElse(n, emptyA).reverseIterator.foreach(w => if !done(w) then stk.push(w))
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

    val dedgeVec = g.edges.filter(e => e.tail != e.head)
    // `virtual_edge(vn, …, e)` copies `ED_weight(e)`, and parallel FLAT edges
    // were already merged onto a rep by `merge_oneway` (which accumulates the
    // members' weights there). So the class REP carries the sum and a
    // merged-away member carries only its own — 191's two
    // `ProgramPredictorsGiven→PredictorView` labels are gv's 2 and 1.
    val flatLabelWeight: Int => Int =
      val wOf = (i: Int) => dedgeVec(i).attrs.get("weight")
        .flatMap(_.toDoubleOption).map(w => math.max(0, w.toInt)).getOrElse(1)
      val byPair = dedgeVec.indices.groupBy(i => (dedgeVec(i).tail, dedgeVec(i).head))
      (d: Int) =>
        val cls = byPair((dedgeVec(d).tail, dedgeVec(d).head))
        if cls.min == d then cls.map(wOf).sum else wOf(d)
    def half(n: LayoutNode): Double = n match
      case LayoutNode.FlatLabel(d)  => Coord.flatLabelDim(dedgeVec(d), g)._1
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
    // `ND_weight_class` (class2.c preamble) counts, per node, the edges it
    // touches — incrementing BOTH endpoints per out-edge (so a self-loop adds
    // 2) and saturating at 3. `endpoint_class` then reads <= 1 as SINGLETON.
    //
    // The catch: it is NEVER RESET, so it ACCUMULATES across every class2
    // call — once for the root, then again inside each cluster's own class2
    // during expand_cluster, which re-counts that cluster's INTERNAL edges.
    // And `virtual_weight` reads it at CHAIN-CREATION time. So an
    // intra-cluster edge, whose chain is built by the cluster's class2, sees
    // its endpoints already bumped a second time. 191's `a` is the clean
    // example: 1 after the root's pass (SINGLETON), 2 after core_data's
    // (ORDINARY) — and its chain is built in the latter, so gv weights that
    // segment 1 (table[VIRTUAL][ORDINARY]) where a one-shot degree gives 2.
    val clustOfX = if Cluster.clusters(g).isEmpty then Map.empty[String, Int] else Cluster.clustOf(g)
    val rootWc = mutable.HashMap.empty[String, Int].withDefaultValue(0)
    g.edges.foreach { e =>
      rootWc(e.tail) = rootWc(e.tail) + 1
      rootWc(e.head) = rootWc(e.head) + 1 // a self-loop bumps the same node twice
    }
    val intraWc = mutable.HashMap.empty[(String, Int), Int].withDefaultValue(0)
    g.edges.foreach { e =>
      (clustOfX.get(e.tail), clustOfX.get(e.head)) match
        case (Some(a), Some(b)) if a == b =>
          intraWc((e.tail, a)) = intraWc((e.tail, a)) + 1
          intraWc((e.head, a)) = intraWc((e.head, a)) + 1
        case _ => ()
    }
    /** `ND_weight_class` as it stands when `ci`'s class2 builds a chain —
      * the root's count plus that cluster's own re-count, saturated at 3. */
    def weightClass(id: String, ci: Option[Int]): Int =
      math.min(3, rootWc(id) + ci.map(c => intraWc((id, c))).getOrElse(0))
    def cls(n: LayoutNode, ci: Option[Int]): NSClass = n match
      case _: LayoutNode.FlatLabel => NSClass.Virtual
      case _: LayoutNode.Virtual => NSClass.Virtual
      case _: LayoutNode.Slack   => NSClass.Virtual // never queried; defensive
      case _: LayoutNode.ClusterLn | _: LayoutNode.ClusterRn => NSClass.Virtual // defensive
      case LayoutNode.Real(id)   =>
        if weightClass(id, ci) <= 1 then NSClass.Singleton else NSClass.Ordinary

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
    final case class FlatBump(minlenAttr: Int, dist: Double, weight: Int)
    val flatBump: Map[(String, String), FlatBump] =
      g.edges.iterator.filter(e => e.tail != e.head).flatMap { e =>
        for
          (rt, pt) <- posInRank.get(e.tail)
          (rh, ph) <- posInRank.get(e.head)
          if rt == rh && math.abs(pt - ph) == 1
        yield
          val (l, r) = if pt < ph then (e.tail, e.head) else (e.head, e.tail)
          // ED_dist (flat.c:299) is the label's extent along the CANONICAL
          // x axis: dimen.y under flip (LR/BT rotate labels), dimen.x else.
          val dist =
            if e.attrs.get("label").exists(_.nonEmpty) then
              val (wl, hl) = Coord.edgeLabelDim(e, g)
              if Rank.flip(g) then hl else wl
            else 0.0
          // ED_minlen/ED_weight: late_int defaults 1, floor 0 — a `minlen=0`
          // or `weight=0` attr is legal and layout-visible (git).
          val ml = math.max(0, e.attrs.get("minlen").flatMap(_.toIntOption).getOrElse(1))
          val wt = math.max(0, e.attrs.get("weight").flatMap(_.toDoubleOption).map(_.toInt).getOrElse(1))
          (l, r) -> FlatBump(ml, dist, wt)
      }.toVector.groupBy(_._1).view.mapValues { bs =>
        FlatBump(bs.map(_._2.minlenAttr).max, bs.map(_._2.dist).max, bs.map(_._2.weight).sum)
      }.toMap
    // make_LR_constraints flat_out (position.c:288): a flat edge between
    // order-adjacent nodes bumps the pair's aux-adjacency edge IN PLACE:
    //   int m0 = ED_minlen(e)·nodesep + width;            // C trunc
    //   m0 = MAX(m0, width + nodesep + ROUND(ED_dist(e))) // C trunc again
    //   ED_minlen(e0) = MAX(ED_minlen(e0), m0);
    //   ED_weight(e0) = MAX(ED_weight(e0), ED_weight(e));
    // width = rw(t0)+lw(h0) (NO nodesep); the flat ED_minlen is the edge's
    // minlen ATTR, doubled when the graph has edge labels (rank doubling).
    def flatAdj(u: LayoutNode, v: LayoutNode, base: Int): (Int, Int) =
      flatBump.get((u.name, v.name)) match
        case Some(fb) =>
          val width = rw(u) + lw(v)
          val fm    = fb.minlenAttr * (if hasEL then 2 else 1)
          val m0a   = (fm * NodeSep + width).toInt
          val m0    = math.max(m0a.toDouble, width + NodeSep + math.round(fb.dist).toDouble).toInt
          (math.max(base, m0), fb.weight)
        case None => (base, 0)
    // Flat edges in ND_flat_out order: per WORKING tail, class2 emit order
    // (per tail-node decl order, out-edges by (head seq, idx)), parallels
    // merged onto the rep (minlen max, dist max, weight sum). Used by the
    // NON-ADJACENT branch below; adjacent pairs keep the flatBump path.
    val flatOutByTail: Map[String, Vector[(String, FlatBump)]] =
      val nodeSeqX = g.nodes.iterator.map(_.id).zipWithIndex.toMap
      val realIdx  = g.edges.filter(e => e.tail != e.head)
      val byTailX  = realIdx.indices.groupBy(i => realIdx(i).tail)
      val buf = mutable.LinkedHashMap.empty[(String, String), FlatBump]
      g.nodes.foreach { n =>
        byTailX.getOrElse(n.id, Seq.empty)
          .sortBy(i => (nodeSeqX.getOrElse(realIdx(i).head, Int.MaxValue), i))
          .foreach { i =>
            val e = realIdx(i)
            (posInRank.get(e.tail), posInRank.get(e.head)) match
              case (Some((rt, _)), Some((rh, _))) if rt == rh =>
                val dist =
                  if e.attrs.get("label").exists(_.nonEmpty) then
                    val (wl, hl) = Coord.edgeLabelDim(e, g)
                    if Rank.flip(g) then hl else wl
                  else 0.0
                val ml = math.max(0, e.attrs.get("minlen").flatMap(_.toIntOption).getOrElse(1))
                val wt = math.max(0, e.attrs.get("weight").flatMap(_.toDoubleOption).map(_.toInt).getOrElse(1))
                buf.get((e.tail, e.head)) match
                  case Some(b) => buf((e.tail, e.head)) =
                    FlatBump(math.max(b.minlenAttr, ml), math.max(b.dist, dist), b.weight + wt)
                  case None => buf((e.tail, e.head)) = FlatBump(ml, dist, wt)
              case _ => ()
          }
      }
      buf.toVector.groupBy(_._1._1).view
        .mapValues(_.map((k, b) => (k._2, b))).toMap
    // `canreach` (position.c:188) walks ND_out over the aux graph AS BUILT SO
    // FAR — at this point only make_LR_constraints has run — to refuse a
    // flat-label constraint that would close a cycle. Track that adjacency.
    val auxOut = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
    def addLR(t: String, h: String, ml: Int, w: Int): Unit =
      auxOut.getOrElseUpdate(t, mutable.ArrayBuffer.empty) += h
      edges += NetworkSimplex.NSEdge(t, h, ml, w)
    def canreach(from: String, to: String): Boolean =
      val seen = mutable.Set(from)
      val stk  = mutable.Stack(from)
      var hit  = from == to
      while !hit && stk.nonEmpty do
        auxOut.getOrElse(stk.pop(), mutable.ArrayBuffer.empty).foreach { w =>
          if w == to then hit = true
          else if seen.add(w) then stk.push(w)
        }
      hit
    res.order.toList.sortBy(_._1).foreach { case (rank, ids) =>
      val nodesep = if hasEL && (rank & 1) == 1 then 5.0 else NodeSep
      ids.headOption.foreach(h => initRank(h.name) = 0)
      var last = 0
      var j = 0
      while j < ids.length do
        val u = ids(j)
        if j + 1 < ids.length then
          val v = ids(j + 1)
          // The aux EDGE minlen is `ROUND(width)` (make_aux_edge → ED_minlen),
          // but the SEED rank gv left-packs is `ND_rank(v) = (int)(last +
          // width)` — the running int rank plus the RAW width, TRUNCATED
          // (position.c:262). The flat_out bump raises the EDGE minlen only
          // (the NS re-solves from the seed, which may go infeasible → init_rank).
          val width  = rw(u) + lw(v) + nodesep
          val (minlen, fw) = flatAdj(u, v, math.round(width).toInt)
          addLR(u.name, v.name, minlen, fw)
          last = (last + width).toInt
          initRank(v.name) = last
        // "constraints from labels of flat edges on previous rank"
        // (position.c:308): a flat-edge LABEL vnode (`ND_alg(u)`, set only by
        // `flat_node`) pushes its edge's two endpoints apart AROUND itself —
        // the left one at least `m0 + rw(left) + lw(vn)` before it, the right
        // one at least `m0 + rw(vn) + lw(right)` after, where
        // `m0 = ED_minlen · GD_nodesep / 2` in C INTEGER division and the flat
        // edge's minlen is DOUBLED by the edge-label rank doubling. gv takes
        // the vnode's two out-edges and orders them by their heads' ND_order,
        // so e0 is the LEFT endpoint. Each edge is skipped if it would close a
        // cycle in the aux graph built so far ("these guards are needed
        // because the flat edges work very poorly with cluster layout").
        u match
          case LayoutNode.FlatLabel(d) =>
            val fe = dedgeVec(d)
            val a  = LayoutNode.Real(fe.tail): LayoutNode
            val b  = LayoutNode.Real(fe.head): LayoutNode
            val (l, r) =
              if posInRank(fe.tail)._2 <= posInRank(fe.head)._2 then (a, b) else (b, a)
            val ml0 = math.max(0, fe.attrs.get("minlen").flatMap(_.toIntOption).getOrElse(1)) *
                        (if hasEL then 2 else 1)
            val m0  = (ml0 * NodeSep.toInt) / 2 // C integer division
            val w   = flatLabelWeight(d)
            if !canreach(u.name, l.name) then
              addLR(l.name, u.name, math.round(m0 + rw(l) + lw(u)).toInt, w)
            if !canreach(r.name, u.name) then
              addLR(u.name, r.name, math.round(m0 + rw(u) + lw(r)).toInt, w)
          case _ => ()
        // position flat edge endpoints (position.c:312): u's ND_flat_out
        // edges, t0/h0 ordered by ND_order. Adjacent pairs bump the LR
        // adjacency edge (flatAdj above); an UNLABELED flat edge between
        // NON-neighbors gets its own aux edge
        //   make_aux_edge(t0, h0, ED_minlen·nodesep + width, ED_weight)
        // (labeled non-neighbors are constrained by the label block).
        u match
          case LayoutNode.Real(uid) =>
            flatOutByTail.getOrElse(uid, Vector.empty).foreach { (other, fb) =>
              (posInRank.get(uid), posInRank.get(other)) match
                case (Some((_, pu)), Some((_, po))) =>
                  val (t0, h0, pt0, ph0) =
                    if pu < po then (uid, other, pu, po) else (other, uid, po, pu)
                  if ph0 - pt0 > 1 && fb.dist == 0.0 then
                    val t0n = LayoutNode.Real(t0); val h0n = LayoutNode.Real(h0)
                    val width = rw(t0n) + lw(h0n)
                    val fm    = fb.minlenAttr * (if hasEL then 2 else 1)
                    val m0    = fm * NodeSep + width
                    addLR(t0, h0, math.round(m0).toInt, fb.weight)
                case _ => ()
            }
          case _ => ()
        j += 1
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
    // CLUSTERED graphs: the final GD_nlist is NOT the decompose order —
    // merge_ranks (cluster.c:225) moves each expanded cluster's interior
    // (reals + intra-cluster vnodes) to the root nlist via fast_node
    // PREPENDS, iterating the interior ranks rank-major left-to-right. So
    // the LAST-expanded cluster's block heads the list, each block
    // internally REVERSED (bottom rank right-to-left first); expansion
    // order is preorder over the cluster tree (mincross_clust recursion).
    // Non-cluster leftovers keep the collapsed-graph decompose order.
    // (The block snapshot is the interior order at EXPANSION time; the
    // interior mincross that follows edits ND_order, not the nlist — we
    // use the final order, identical unless interior mincross swaps.)
    val decomp: Vector[LayoutNode] =
      val cs = Cluster.clusters(g)
      if cs.isEmpty then decomposeOrder(g, res)
      else if res.nlist.nonEmpty then
        // `merge_ranks` blocks, then `decompose(g, 1)`'s leftovers — both
        // captured where they are actually built (see Order.orderClustered).
        // The filter is a safety net only; it should never add anything.
        val inN = res.nlist.toSet
        res.nlist ++ decomposeOrder(g, res).filterNot(inN)
      else
        val directMembers: Vector[Set[String]] = cs.indices.toVector.map { ci =>
          val childIds = Cluster.childrenOf(g, ci).flatMap(cc => cs(cc).members)
          cs(ci).members -- childIds
        }
        def vnodeCluster(v: LayoutNode.Virtual): Int =
          // a chain vnode belongs to the innermost cluster containing BOTH
          // endpoints of its originating dedge (its class2 ran there)
          realEdges.lift(v.edgeIdx) match
            case Some(re) =>
              cs.indices.filter(ci => cs(ci).members(re.tail) && cs(ci).members(re.head))
                .minByOption(ci => cs(ci).members.size)
                .getOrElse(-1)
            case None => -1
        def blockOf(ci: Int): Vector[LayoutNode] =
          (cs(ci).minRank to cs(ci).maxRank).iterator.flatMap { r =>
            res.order.getOrElse(r, Vector.empty).iterator.filter {
              case LayoutNode.Real(id)   => directMembers(ci)(id)
              case v: LayoutNode.Virtual => vnodeCluster(v) == ci
              case _                     => false
            }
          }.toVector
        def preorder(ci: Int): Vector[Int] = ci +: Cluster.childrenOf(g, ci).flatMap(preorder)
        val expansion = Cluster.childrenOf(g, -1).flatMap(preorder)
        val blocks    = expansion.reverseIterator.map(ci => blockOf(ci).reverse).toVector
        val inBlocks  = blocks.iterator.flatten.toSet
        blocks.flatten ++ decomposeOrder(g, res).filterNot(inBlocks)
    val outSegs = mutable.LinkedHashMap.empty[LayoutNode, mutable.ArrayBuffer[Int]]
    res.segments.iterator.zipWithIndex.foreach { case ((t, _), i) =>
      outSegs.getOrElseUpdate(t, mutable.ArrayBuffer.empty) += i
    }
    // `ND_out` is ONE list per node, appended to in the order the chains are
    // built — and for a clustered node that is two passes, not one. The
    // cluster's own `class2` (inside `expand_cluster`) builds its INTRA-cluster
    // chains first; only then does `interclexp` → `make_interclust_chain` →
    // `map_path` rebuild the INTER-cluster ones, swapping the rankleader
    // endpoint for the real node with a fresh `virtual_edge` that APPENDS. So
    // a clustered node's inter-cluster out-segments trail all its intra ones —
    // 191's `ProgramType` has gv emitting its three inter-cluster edges
    // (DspyError, PredictorsRep, PredictionO) last where a single pass puts
    // them first. Stable within each group.
    if clustOfX.nonEmpty then
      outSegs.foreach { (node, segs) =>
        val nid = node match { case LayoutNode.Real(id) => id; case _ => "" }
        def ownerOf(i: Int) =
          res.segOwner.lift(i).filter(_ >= 0).flatMap(realEdges.lift)
        // 0 = intra-cluster (the cluster's own class2, first pass)
        // 1 = inter-cluster, this node is the ORIGINAL TAIL
        // 2 = inter-cluster, this node is the ORIGINAL HEAD
        // `interclexp` walks the rankleaders' out-edges before their in-edges,
        // so a rebuilt chain lands in ND_out in that order; a segment that
        // merely runs OUT of this node because `acyclic` reversed its edge is
        // still an in-edge as far as interclexp is concerned.
        def grp(i: Int): Int = ownerOf(i) match
          case Some(re) =>
            (clustOfX.get(re.tail), clustOfX.get(re.head)) match
              case (Some(a), Some(b)) if a == b => 0
              case _                            => if re.tail == nid then 1 else 2
          case None => 0
        val regrouped = segs.toVector.sortBy(grp) // stable
        if regrouped != segs.toVector then { segs.clear(); segs ++= regrouped }
      }
    val slackNodes = mutable.ArrayBuffer.empty[LayoutNode]


    // ── flat-label vnodes come FIRST (flat.c flat_node → make_vn_slot →
    //    virtual_node → fast_node, which PREPENDS to GD_nlist, so the last one
    //    created heads the list). Each has exactly two out-edges, vn→tail then
    //    vn→head, carrying the ports flat_node set:
    //      vn→tail: tail_port.p.x = -lw(vn), head_port.p.x =  rw(flatTail)
    //      vn→head: tail_port.p.x =  rw(vn), head_port.p.x =  lw(flatHead)
    //    so m0 is `rw(flatTail) + lw(vn)` and `lw(flatHead) - rw(vn)`.
    res.flatLabels.reverseIterator.foreach { d =>
      val fe   = dedgeVec(d)
      val vn: LayoutNode = LayoutNode.FlatLabel(d)
      val hv   = half(vn)
      val t    = LayoutNode.Real(fe.tail): LayoutNode
      val h    = LayoutNode.Real(fe.head): LayoutNode
      val fw   = flatLabelWeight(d)
      Vector((t, rw(t) + hv), (h, lw(h) - hv)).foreach { (endp, mRaw) =>
        val sn: LayoutNode = LayoutNode.Slack(slackNodes.length + res.segments.length)
        slackNodes += sn
        val m = mRaw.toInt // C `int` truncation toward zero
        val (m0, m1) = if m > 0 then (m, 0) else (0, -m)
        edges += NetworkSimplex.NSEdge(sn.name, vn.name, m0 + 1, fw)
        edges += NetworkSimplex.NSEdge(sn.name, endp.name, m1 + 1, fw)
        initRank(sn.name) =
          math.min(initRank(vn.name) - (m0 + 1), initRank(endp.name) - (m1 + 1))
      }
    }

    decomp.foreach { node =>
      outSegs.getOrElse(node, mutable.ArrayBuffer.empty).foreach { i =>
      val (t, h) = res.segments(i)
      val sn: LayoutNode = LayoutNode.Slack(i)
      slackNodes += sn
      val owner = res.segOwner.lift(i).getOrElse(-1)
      val owned = if owner >= 0 && owner < realEdges.length then Some(realEdges(owner)) else None
      // make_edge_pairs slack weight = ω-class × the **edge `weight`**
      // (ED_weight = late_int(weight, default 1, FLOOR 0 — `weight=0` is
      // legal and layout-visible, git; atoi ⇒ trunc). A class2-merged
      // multi-edge class sums its members' weights onto the rep chain
      // (merge_chain `ED_weight(rep) += ED_weight(e)`) with NO clamp.
      def weightAttr(d: Int): Int =
        realEdges.lift(d).flatMap(_.attrs.get("weight")).flatMap(_.toDoubleOption)
          .map(w => math.max(0, w.toInt)).getOrElse(1)
      val wt =
        if owner >= 0 then
          res.mergedInto.indices.iterator.filter(res.mergedInto(_) == owner).map(weightAttr).sum
        else 1
      // which class2 built this chain: the owning edge's cluster if it is
      // intra-cluster, else the root's.
      val chainCi = owned.flatMap { re =>
        (clustOfX.get(re.tail), clustOfX.get(re.head)) match
          case (Some(a), Some(b)) if a == b => Some(a)
          case _                            => None
      }
      // `interclexp` REBUILDS an inter-cluster chain's end segments when the
      // cluster expands: make_interclust_chain → map_path swaps the rankleader
      // endpoint for the real node by creating a fresh `virtual_edge(...)` —
      // and cluster.c never calls `virtual_weight` (the symbol does not appear
      // in that file at all). So a replaced END segment keeps the raw
      // ED_weight, i.e. omega 1, while the MIDDLE segments keep the omega
      // make_chain gave them. 191: PredictType>ExampleType is gv {1,4} —
      // ends 1, virtual–virtual middles 4 — where scoring every segment gives
      // the spurious {1,2,4}.
      val interCluster = owned.exists { re =>
        val a = clustOfX.get(re.tail); val b = clustOfX.get(re.head)
        (a.isDefined || b.isDefined) && a != b
      }
      def clusteredReal(n: LayoutNode): Boolean = n match
        case LayoutNode.Real(id) => clustOfX.contains(id)
        case _                   => false
      val rebuiltEnd = interCluster && (clusteredReal(t) || clusteredReal(h))
      val w  = (if rebuiltEnd then 1 else NSClass.weight(cls(t, chainCi), cls(h, chainCi))) * wt
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
            // make_lrvn: `int w = MAX(border[BOTTOM].x, border[TOP].x)` — a C
            // double→int TRUNCATION (not ROUND; make_aux_edge's ROUND then
            // sees an already-integral value). A fractional label width like
            // 431.8 must give 447, not 448 (GP clusters 21/38).
            edges += NetworkSimplex.NSEdge(ln(ci).name, rn(ci).name,
              math.max(cluInfos(ci).borderTopX, cluInfos(ci).borderBottomX).toInt, 0)
      // contain_nodes: per rank, first/last member held inside the borders.
      // The LEFT/RIGHT borders are 0 in TB; under `flip` one of them carries
      // the rotated label box (its PADDED HEIGHT — see CInfo's GD_border
      // block), which is how a flipped cluster reserves label space at all:
      // clust_ht's rank-axis band is skipped for flipped roots.
      def containNodes(ci: Int): Unit =
        makeLrvn(ci)
        val bl = cluInfos(ci).borderLeftX(flip)
        val br = cluInfos(ci).borderRightX(flip)
        var r = cluInfos(ci).minRank
        while r <= cluInfos(ci).maxRank do
          val row = slice(ci, r)
          if row.nonEmpty then
            edges += NetworkSimplex.NSEdge(ln(ci).name, row.head.name,
              math.round(lw(row.head) + ClOffset + bl).toInt, 0)
            edges += NetworkSimplex.NSEdge(row.last.name, rn(ci).name,
              math.round(rw(row.last) + ClOffset + br).toInt, 0)
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
        // gv's test is `ND_clust(u) != ND_clust(v)`, and a flat-edge label
        // vnode is created by `virtual_node` in dot_position — long after
        // `mark_clusters` — so its ND_clust is NULL and it ALWAYS differs.
        // It therefore stops the scan and takes the keepout edge itself.
        case _: LayoutNode.FlatLabel => true
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
        // the PARENT's side borders (the root has none) — 0 in TB, the
        // rotated label box under flip.
        val bl = if ci >= 0 then cluInfos(ci).borderLeftX(flip) else 0.0
        val br = if ci >= 0 then cluInfos(ci).borderRightX(flip) else 0.0
        Cluster.childrenOf(g, ci).foreach { cc =>
          makeLrvn(cc)
          edges += NetworkSimplex.NSEdge(ln(ci).name, ln(cc).name, math.round(ClOffset + bl).toInt, 0)
          edges += NetworkSimplex.NSEdge(rn(cc).name, rn(ci).name, math.round(ClOffset + br).toInt, 0)
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
