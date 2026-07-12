package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.{Attrs, RGraph, RSubgraph}
import scala.collection.mutable

/** Phase 1 of the `dot` pipeline: cycle breaking + rank assignment.
  *
  * Ports `lib/dotgen/acyclic.c` (DFS back-edge reversal); ranks are then
  * assigned by the true [[NetworkSimplex]] kernel with TB balance (`balance=1`),
  * matching `rank.c`/`ns.c` (gv 13.0.1). (The earlier longest-path stand-in
  * and its M2 deferral are retired — see PORT.md §5.2.)
  */
object Rank:

  /** A directed edge in the working (post-acyclic) graph. */
  final case class DEdge(tail: String, head: String, minlen: Int) derives CanEqual

  private def minlenOf(attrs: Attrs): Int =
    attrs.get("minlen").flatMap(_.toIntOption).getOrElse(1)

  /** `GD_has_labels(g) & EDGE_LABEL`: any edge carries a non-empty `label`.
    * Triggers `edgelabel_ranks` rank-doubling (rank.c). */
  def hasEdgeLabel(g: RGraph): Boolean =
    g.edges.exists(e => e.tail != e.head && e.attrs.get("label").exists(_.nonEmpty))

  /** `GD_rankdir`: const.h RANKDIR_TB=0/LR=1/BT=2/RL=3. */
  def rankdir(g: RGraph): RankDir = RankDir.fromAttr(g.rootAttrs.get("rankdir"))

  /** `GD_flip`: the rank axis is horizontal (LR/RL) ⇒ node w/h are swapped
    * for layout (`gv_nodesize(n, flip)`). */
  def flip(g: RGraph): Boolean = rankdir(g).isFlipped

  /** Reverse back edges via DFS, exactly as Graphviz `acyclic`/`dfs`:
    * roots visited in node-declaration order; an out-edge to a node currently
    * on the DFS stack is reversed (and not recursed into).
    */
  private def acyclic(g: RGraph, minlenScale: Int): Vector[DEdge] =
    val edges  = g.edges.filter(e => e.tail != e.head) // self-loops don't rank
    val outIdx = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Int]]
    g.nodes.foreach(n => outIdx(n.id) = mutable.ArrayBuffer.empty)
    edges.zipWithIndex.foreach { case (e, i) =>
      outIdx.getOrElseUpdate(e.tail, mutable.ArrayBuffer.empty) += i
    }
    val reversed = mutable.Set.empty[Int]
    val mark     = mutable.Set.empty[String]
    val onStack  = mutable.Set.empty[String]

    def dfs(n: String): Unit =
      if mark(n) then return
      mark += n
      onStack += n
      outIdx.getOrElse(n, mutable.ArrayBuffer.empty).foreach { i =>
        val w = edges(i).head
        if onStack(w) then reversed += i // back edge → reverse, don't recurse
        else if !mark(w) then dfs(w)
      }
      onStack -= n

    g.nodes.foreach(n => dfs(n.id))

    edges.zipWithIndex.map { case (e, i) =>
      val ml = minlenOf(e.attrs) * minlenScale // edgelabel_ranks: ED_minlen*=2
      if reversed(i) then DEdge(e.head, e.tail, ml) else DEdge(e.tail, e.head, ml)
    }

  /** `collapse_sets` (class1.c) + `GD_minset`/`GD_maxset` (rank.c
    * `build_ranksets`): union-find leaders for every `rank=same/min/max/
    * source/sink` subgraph (members merged to one representative so the rank
    * solve pins them to a single rank), plus the extreme sets — all
    * `min`/`source` members collapse into one `minset`, all `max`/`sink`
    * into one `maxset`. `slenX`/`slenY` are 1 for `source`/`sink` (strict
    * extreme, `minmax_edges` slen) else 0. Identity when no rank constraints
    * exist ⇒ additive (unconstrained corpus unchanged). */
  final case class RankSets(
      leader:  String => String,
      minset:  Option[String], // leader of the min/source union
      maxset:  Option[String], // leader of the max/sink union
      slenX:   Int,            // source ⇒ 1 (strict), min ⇒ 0
      slenY:   Int             // sink ⇒ 1, max ⇒ 0
  )

  private def rankConstraints(g: RGraph): RankSets =
    val parent = mutable.Map.empty[String, String]
    def find(x: String): String =
      val p = parent.getOrElse(x, x)
      if p == x then x else { val r = find(p); parent(x) = r; r }
    def union(a: String, b: String): Unit =
      val (ra, rb) = (find(a), find(b))
      if ra != rb then parent(ra) = rb
    val nodeSet  = g.nodes.iterator.map(_.id).toSet
    val minMembers = mutable.ArrayBuffer.empty[String] // min + source
    val maxMembers = mutable.ArrayBuffer.empty[String] // max + sink
    var slenX = 0; var slenY = 0
    def walk(subs: Vector[RSubgraph]): Unit = subs.foreach { s =>
      s.rank.foreach { rt =>
        val members = s.nodeIds.filter(nodeSet)
        members.reduceLeftOption { (a, b) => union(a, b); b } // collapse this set
        rt match
          case "min"                => minMembers ++= members
          case "source"             => minMembers ++= members; slenX = 1
          case "max"                => maxMembers ++= members
          case "sink"               => maxMembers ++= members; slenY = 1
          case _                    => () // same
      }
      walk(s.children)
    }
    walk(g.subgraphs)
    // Fuse all min/source members into one set, all max/sink into one.
    minMembers.reduceLeftOption { (a, b) => union(a, b); b }
    maxMembers.reduceLeftOption { (a, b) => union(a, b); b }
    RankSets(find, minMembers.headOption.map(find), maxMembers.headOption.map(find), slenX, slenY)

  /** Post-acyclic directed edges plus normalised ranks (min rank = 0),
    * computed by the network-simplex kernel with TB balance. Acyclic +
    * the returned working edges stay on the *original* nodes (Order builds its
    * virtual chains from them); the `rank=same` collapse applies only to the
    * NS solve, whose leader ranks are then expanded back to every member.
    */
  private val rankedMemo = GraphMemo[(Map[String, Int], Vector[DEdge])]()
  def ranked(g: RGraph): (Map[String, Int], Vector[DEdge]) = rankedMemo(g)(rankedImpl(g))
  private def rankedImpl(g: RGraph): (Map[String, Int], Vector[DEdge]) =
    val wedges0 = acyclic(g, if hasEdgeLabel(g) then 2 else 1)
    val rs      = rankConstraints(g)
    val leader  = rs.leader
    // minmax_edges1 (rank.c): reverse every in-edge of minset (so it becomes a
    // source) and out-edge of maxset (so a sink). Applied to the WORKING edges
    // so Order/Spline route through the reversed direction while the arrow
    // stays at the original head (g.edges is the arrow authority via segOwner)
    // — exactly the acyclic-reversal contract. Identity without min/max sets.
    val wedges =
      if rs.minset.isEmpty && rs.maxset.isEmpty then wedges0
      else wedges0.map { e =>
        if rs.minset.contains(leader(e.head)) then DEdge(e.head, e.tail, e.minlen)
        else if rs.maxset.contains(leader(e.tail)) then DEdge(e.head, e.tail, e.minlen)
        else e
      }
    // collapse endpoints to leaders for ranking; drop now-intra-set edges
    var nse = wedges.iterator.flatMap { e =>
      val (t, h) = (leader(e.tail), leader(e.head))
      if t == h then None else Some(NetworkSimplex.NSEdge(t, h, e.minlen, 1))
    }.toVector
    val leaderNodes = g.nodes.iterator.map(n => leader(n.id)).distinct.toVector
    // minmax_edges2 (rank.c): for every leader with no out-edge add `n→maxset`
    // (minlen slenY), and no in-edge add `minset→n` (minlen slenX), weight 0.
    if rs.minset.isDefined || rs.maxset.isDefined then
      val hasOut = nse.iterator.map(_.tail).toSet
      val hasIn  = nse.iterator.map(_.head).toSet
      val extra  = mutable.ArrayBuffer.empty[NetworkSimplex.NSEdge]
      leaderNodes.foreach { n =>
        rs.maxset.foreach(mx => if !hasOut(n) && n != mx then extra += NetworkSimplex.NSEdge(n, mx, rs.slenY, 0))
        rs.minset.foreach(mn => if !hasIn(n) && n != mn then extra += NetworkSimplex.NSEdge(mn, n, rs.slenX, 0))
      }
      nse = nse ++ extra.toVector
    val leaderRanks = NetworkSimplex.solve(leaderNodes, nse, balance = NSBalance.TopBottom)
    val ranks = g.nodes.iterator.map(n => n.id -> leaderRanks(leader(n.id))).toMap
    (ranks, wedges)

  /** Normalised integer ranks (min rank = 0) for every node. */
  def assign(g: RGraph): Map[String, Int] = ranked(g)._1

end Rank
