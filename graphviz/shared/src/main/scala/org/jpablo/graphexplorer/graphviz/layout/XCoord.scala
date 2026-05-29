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

  /** Core solve: ordering + x (points) for all placed nodes, left edge at 0. */
  private def xSolve(g: RGraph): (Order.Result, Map[LayoutNode, Pt]) =
    val res  = Order.order(g)
    val byId = g.nodes.iterator.map(n => n.id -> n).toMap

    def half(n: LayoutNode): Double = n match
      case _: LayoutNode.Virtual => VirtualHalf
      case _: LayoutNode.Slack   => VirtualHalf // never queried; defensive
      case LayoutNode.Real(id)   =>
        byId.get(id).flatMap(rn => NodeSize.layoutSize(rn, g))
          .map(_.halfWidthPt.value).getOrElse(1.0)

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

    val auxNodes = mutable.LinkedHashSet.empty[LayoutNode]
    res.order.toList.sortBy(_._1).foreach { case (_, ids) => ids.foreach(auxNodes += _) }

    val edges = mutable.ArrayBuffer.empty[NetworkSimplex.NSEdge]

    // make_LR_constraints: separation within each rank
    res.order.foreach { case (_, ids) =>
      ids.sliding(2).foreach {
        case Seq(u, v) =>
          val sep = math.ceil(half(u) + half(v) + NodeSep).toInt
          edges += NetworkSimplex.NSEdge(u.name, v.name, sep, 0)
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
    res.segments.zipWithIndex.foreach { case ((t, h), i) =>
      val sn: LayoutNode = LayoutNode.Slack(i)
      auxNodes += sn
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
    }

    val xr = NetworkSimplex.solve(auxNodes.toSeq.map(_.name), edges.toSeq, balance = NSBalance.LeftRight)
    // NS returns String-keyed ranks; map back to LayoutNode via the
    // historical name-form parse (Order produces the same Virtual/Slack
    // identities so the round-trip is exact).
    val xrByNode: Map[LayoutNode, Int] = auxNodes.iterator.map(n => n -> xr(n.name)).toMap

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
