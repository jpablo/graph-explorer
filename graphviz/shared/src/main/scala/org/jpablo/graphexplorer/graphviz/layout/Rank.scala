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

  /** `collapse_sets` (class1.c): union-find leaders for the `rank=same/min/
    * max/source/sink` subgraphs — every member is merged to one representative
    * so the rank solve pins them to a single rank. Identity when no rank
    * constraints exist ⇒ additive (unconstrained corpus unchanged).
    *
    * NOTE: the `min`/`source` (⇒ global min rank) and `max`/`sink` (⇒ global
    * max) *extreme* constraints are not yet added — only the same-rank merge,
    * which is the whole of `rank=same`. No corpus exercises min/max/source/
    * sink positioning; tracked in PORT.md §5.2. */
  private def rankConstraintLeader(g: RGraph): String => String =
    val parent = mutable.Map.empty[String, String]
    def find(x: String): String =
      val p = parent.getOrElse(x, x)
      if p == x then x else { val r = find(p); parent(x) = r; r }
    def union(a: String, b: String): Unit =
      val (ra, rb) = (find(a), find(b))
      if ra != rb then parent(ra) = rb
    val nodeSet = g.nodes.iterator.map(_.id).toSet
    def walk(subs: Vector[RSubgraph]): Unit = subs.foreach { s =>
      if s.rank.isDefined then
        s.nodeIds.filter(nodeSet).reduceLeftOption { (a, b) => union(a, b); b }
      walk(s.children)
    }
    walk(g.subgraphs)
    (x: String) => find(x)

  /** Post-acyclic directed edges plus normalised ranks (min rank = 0),
    * computed by the network-simplex kernel with TB balance. Acyclic +
    * the returned working edges stay on the *original* nodes (Order builds its
    * virtual chains from them); the `rank=same` collapse applies only to the
    * NS solve, whose leader ranks are then expanded back to every member.
    */
  private val rankedMemo = GraphMemo[(Map[String, Int], Vector[DEdge])]()
  def ranked(g: RGraph): (Map[String, Int], Vector[DEdge]) = rankedMemo(g)(rankedImpl(g))
  private def rankedImpl(g: RGraph): (Map[String, Int], Vector[DEdge]) =
    val wedges = acyclic(g, if hasEdgeLabel(g) then 2 else 1)
    val leader = rankConstraintLeader(g)
    // collapse endpoints to leaders for ranking; drop now-intra-set edges
    val nse = wedges.iterator.flatMap { e =>
      val (t, h) = (leader(e.tail), leader(e.head))
      if t == h then None else Some(NetworkSimplex.NSEdge(t, h, e.minlen, 1))
    }.toVector
    val leaderNodes = g.nodes.iterator.map(n => leader(n.id)).distinct.toVector
    val leaderRanks = NetworkSimplex.solve(leaderNodes, nse, balance = NSBalance.TopBottom)
    val ranks = g.nodes.iterator.map(n => n.id -> leaderRanks(leader(n.id))).toMap
    (ranks, wedges)

  /** Normalised integer ranks (min rank = 0) for every node. */
  def assign(g: RGraph): Map[String, Int] = ranked(g)._1

end Rank
