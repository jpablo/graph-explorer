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

  private val NodeSep    = 18.0 // POINTS(DEFAULT_NODESEP = 0.25in)
  private val VirtualHalf = 1.0 + NodeSep / 2.0 // class2.c plain_vnode

  // Memoized (per-graph, size-1): renderFormats hits xSolve ~7× on one graph.
  private val solveMemo = GraphMemo[(Order.Result, Map[LayoutNode, Pt])]()
  private def xSolve(g: RGraph): (Order.Result, Map[LayoutNode, Pt]) = solveMemo(g)(xSolveImpl(g))

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
  private def xSolveImpl(g: RGraph): (Order.Result, Map[LayoutNode, Pt]) =
    val res  = Order.order(g)
    val byId = g.nodes.iterator.map(n => n.id -> n).toMap

    def half(n: LayoutNode): Double = n match
      case _: LayoutNode.Virtual => VirtualHalf
      case _: LayoutNode.Slack   => VirtualHalf // never queried; defensive
      case LayoutNode.Real(id)   =>
        byId.get(id).flatMap(rn => NodeSize.layoutSize(rn, g))
          .map(_.halfWidthPt.value).getOrElse(1.0)

    // Asymmetric widths (class2.c label_vnode): a label vnode has lw=nodesep,
    // rw=labelWidth; every other node is symmetric (lw=rw=half) so this is
    // identical to `half` unless a label vnode is involved.
    val labelW = Coord.labelVnodeWidths(g)
    def lw(n: LayoutNode): Double = n match
      case v: LayoutNode.Virtual if labelW.contains(v.name) => NodeSep
      case _                                                => half(n)
    def rw(n: LayoutNode): Double = n match
      case v: LayoutNode.Virtual if labelW.contains(v.name) => labelW(v.name)
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
    res.order.toList.sortBy(_._1).foreach { case (rank, ids) =>
      val nodesep = if hasEL && (rank & 1) == 1 then 5.0 else NodeSep
      ids.headOption.foreach(h => initRank(h.name) = 0)
      var last = 0
      ids.sliding(2).foreach {
        case Seq(u, v) =>
          val sep = math.round(rw(u) + lw(v) + nodesep).toInt // ROUND(ND_rw(u)+ND_lw(v)+nodesep)
          edges += NetworkSimplex.NSEdge(u.name, v.name, sep, 0)
          last += sep; initRank(v.name) = last
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
      (for
        p <- port
        n <- byId.get(nodeId)
        a <- PortAnchor.resolve(n, g, p.name.map(_.value).filter(_.nonEmpty), p.compass)
      yield a.x.value).getOrElse(0.0)
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
            val tpx = t match
              case LayoutNode.Real(id) if id == re.tail => portX(re.tail, re.tailPort)
              case _ => 0.0
            val hpx = h match
              case LayoutNode.Real(id) if id == re.head => portX(re.head, re.headPort)
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

    // The NS node order MUST be gv's GD_nlist: slack (reverse of make_edge_pairs
    // creation order — they are prepended) then the decompose-DFS'd real+virtual
    // nodes. The order-sensitive NS reproduces gv's x-solve only fed this order.
    val nodeOrder: Vector[LayoutNode] = slackNodes.reverseIterator.toVector ++ decomp
    val xr = NetworkSimplex.solve(nodeOrder.map(_.name), edges.toSeq, balance = NSBalance.LeftRight, initRanks = initRank)
    // NS returns String-keyed ranks; map back to LayoutNode via the
    // historical name-form parse (Order produces the same Virtual/Slack
    // identities so the round-trip is exact).
    val xrByNode: Map[LayoutNode, Int] = nodeOrder.iterator.map(n => n -> xr(n.name)).toMap

    // shift so the leftmost node's left edge sits at 0 (bbox origin)
    val placed: Vector[LayoutNode] = res.order.values.flatten.toVector
    val shift  = placed.iterator.map(id => xrByNode(id).toDouble - half(id)).minOption.getOrElse(0.0)
    val allX   = placed.iterator.map(id => id -> Pt(xrByNode(id).toDouble - shift)).toMap
    (res, allX)

  /** x (points) for real **and** virtual placed nodes, plus the ordering. */
  def solveAll(g: RGraph): (Order.Result, Map[LayoutNode, Pt]) = xSolve(g)

  /** x (points) for real nodes only. */
  def xCoords(g: RGraph): Map[String, Pt] =
    val (_, allX) = xSolve(g)
    g.nodes.iterator.map(n => n.id -> allX(LayoutNode.Real(n.id))).toMap

end XCoord
