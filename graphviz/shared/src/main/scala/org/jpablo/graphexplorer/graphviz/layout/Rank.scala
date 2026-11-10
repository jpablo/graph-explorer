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
  /** `constraint=false` (mapbool) — the edge exists for routing/mincross but
    * is ABSENT from the ranking graph (class1.c skips it): no acyclic
    * traversal or reversal, no rank constraint. Default true. */
  private[layout] def constrained(attrs: org.jpablo.graphexplorer.graphviz.model.Attrs): Boolean =
    attrs.get("constraint") match
      case Some(v) =>
        v.toLowerCase match
          case "false" | "no" => false
          case other          => other.toIntOption.forall(_ > 0)
      case None => true

  private def acyclic(g: RGraph, minlenScale: Int): Vector[DEdge] =
    val edges  = g.edges.filter(e => e.tail != e.head) // self-loops don't rank
    val outIdx = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Int]]
    g.nodes.foreach(n => outIdx(n.id) = mutable.ArrayBuffer.empty)
    edges.zipWithIndex.foreach { case (e, i) =>
      if constrained(e.attrs) then
        outIdx.getOrElseUpdate(e.tail, mutable.ArrayBuffer.empty) += i
    }
    // agfstout order (cgraph agedgeseqcmpf): a node's out-edges iterate by
    // (HEAD-node declaration seq, edge seq) — NOT edge-declaration order.
    // Coincides with declaration order unless heads are named out of node
    // order (fsm/logo examples); a different DFS order breaks different
    // back edges ⇒ different ranks.
    val nodeSeq: Map[String, Int] = g.nodes.iterator.map(_.id).zipWithIndex.toMap
    outIdx.values.foreach { buf =>
      val sorted = buf.sortBy(i => (nodeSeq.getOrElse(edges(i).head, Int.MaxValue), i))
      buf.clear(); buf ++= sorted
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
    * exist ⇒ additive (unconstrained corpus unchanged).
    *
    * Only [[rankedGlobal]] (newrank, and the 03 exception) uses this walk.
    * It goes into each subgraph, also into a set or a cluster, as dot2's
    * compile_samerank does. dot1 graphs use the collapse_sets walk of
    * [[rankedDot1]]. */
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
    val minMembers = mutable.ArrayBuffer.empty[String] // min + source
    val maxMembers = mutable.ArrayBuffer.empty[String] // max + sink
    var slenX = 0; var slenY = 0
    // rank_set_class reads agget(subg, "rank") — which resolves through
    // cgraph DEFAULTS: `graph [rank=same]` declared at ROOT level makes
    // every subgraph a rank set by inheritance (the 167 grid). The root
    // itself is never collapsed (collapse_sets only walks SUBgraphs).
    val defaultRank = g.rootAttrs.get("rank").filter(_.nonEmpty)
    def walk(subs: Vector[RSubgraph]): Unit = subs.foreach { s =>
      s.rank.orElse(defaultRank).foreach { rt =>
        // union_all walks agfstnode(subg): the GLOBAL node order (AGSEQ),
        // not the body order.
        val own     = s.nodeIds.toSet
        val members = g.nodes.iterator.map(_.id).filter(own).toVector
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

  private def mapbool(v: Option[String]): Boolean = v.exists { s =>
    s.toLowerCase match
      case "true" | "yes" => true
      case "false" | "no" => false
      case other          => other.toIntOption.exists(_ > 0)
  }

  /** dot_rank (rank.c:449): `newrank` ⇒ dot2, which the port gives as one
    * global level ([[rankedGlobal]]); otherwise dot1_rank ([[rankedDot1]]),
    * for a graph with or without clusters. Clusters rank RECURSIVELY
    * (interior solve + collapse to a leader + interclust1 slack edges).
    *
    * EXCEPTION (don't-port-the-bug, 03): a rank set SPANNING cluster
    * boundaries corrupts gv's dot1 (13.0.1 emits a 0×0 sentinel drawing) —
    * for those inputs we keep the correct global (newrank) semantics,
    * byte-gated against the 03b newrank oracle in ClusterSpec. */
  private def rankedImpl(g: RGraph): (Map[String, Int], Vector[DEdge]) =
    def topClusters(subs: Vector[RSubgraph]): Vector[RSubgraph] =
      subs.flatMap(s => if s.isCluster then Vector(s) else topClusters(s.children))
    val tops = topClusters(g.subgraphs)
    def crossClusterRankSet: Boolean =
      val clOf = mutable.HashMap.empty[String, Int]
      def mark(s: RSubgraph, ci: Int): Unit =
        s.nodeIds.foreach(n => clOf(n) = ci); s.children.foreach(mark(_, ci))
      tops.zipWithIndex.foreach((c, i) => mark(c, i))
      val defaultRank = g.rootAttrs.get("rank").filter(_.nonEmpty)
      def walk(subs: Vector[RSubgraph]): Boolean = subs.exists { s =>
        (s.rank.orElse(defaultRank).isDefined && !s.isCluster &&
          s.nodeIds.map(clOf.get).distinct.length > 1) || walk(s.children)
      }
      walk(g.subgraphs)
    if mapbool(g.rootAttrs.get("newrank")) || crossClusterRankSet then rankedGlobal(g)
    else rankedDot1(g)

  // ED_weight = late_int(weight, default 1, FLOOR 0) — atoi truncates
  // fractions. Shared by the dot1 interclust weights and the global
  // ranking edges (TB_balance's inweight==outweight test reads it).
  private def weightOf(a: Attrs): Int =
    a.get("weight").flatMap(_.toDoubleOption).map(w => math.max(0, w.toInt)).getOrElse(1)

  /** rank1 (rank.c:373): the NS runs per CONNECTED COMPONENT (gv
    * `decompose(g,0)` + the comp loop). Feeding a disconnected graph to one
    * solve leaves everything beyond the feasible tree's component at its
    * init_rank floor (pprof: an isolated cluster leader + the call tree —
    * N16 stuck at its minimum feasible rank). Each component normalizes to
    * rank 0 independently, exactly like gv's per-comp `rank()` calls. */
  /** A synthetic slack node from `interclust1` — never a NORMAL node, so
    * `scan_and_normalize` must not anchor on it (see [[NetworkSimplex.solve]]'s
    * `isNormal`). */
  private def isSlackNode(n: String): Boolean = n.startsWith("%slack:")

  private def solvePerComponent(
      nodes: Vector[String], edges: Vector[NetworkSimplex.NSEdge],
      balance: NSBalance, tbOrder: Vector[String] = Vector.empty
  ): Map[String, Int] =
    val parent = mutable.HashMap.empty[String, String]
    def find(x: String): String =
      val p = parent.getOrElse(x, x)
      if p == x then x else { val r = find(p); parent(x) = r; r }
    def union(a: String, b: String): Unit =
      val (ra, rb) = (find(a), find(b))
      if ra != rb then parent(ra) = rb
    edges.foreach(e => union(e.tail, e.head))
    val compOf = nodes.iterator.map(n => n -> find(n)).toMap
    // components in first-appearance order over the node list
    val roots = mutable.LinkedHashSet.empty[String]
    nodes.foreach(n => roots += compOf(n))
    if roots.size <= 1 then
      NetworkSimplex.solve(nodes, edges, balance = balance, tbOrder = tbOrder,
        isNormal = n => !isSlackNode(n))
    else
      val out = Map.newBuilder[String, Int]
      roots.foreach { r =>
        val ns = nodes.filter(compOf(_) == r)
        val nsSet = ns.toSet
        val es = edges.filter(e => nsSet(e.tail))
        out ++= NetworkSimplex.solve(ns, es, balance = balance, tbOrder = tbOrder.filter(nsSet),
          isNormal = n => !isSlackNode(n))
      }
      out.result()

  /** The class1 fast graph of one ranking level (fastgr.c). It has one fast
    * edge for each (tail, head) pair: `make` is `virtual_edge`, or
    * `merge_oneway` when the pair has an edge (minlen max, weight sum, the
    * edge keeps its position). The out and in lists keep the gv order, also
    * after `reverse`. [[rankedGlobal]] and each level of [[rankedDot1]] use
    * it. */
  private final class FastGraph:
    val tail   = mutable.ArrayBuffer.empty[String]
    val head   = mutable.ArrayBuffer.empty[String]
    val minlen = mutable.ArrayBuffer.empty[Int]
    val weight = mutable.ArrayBuffer.empty[Int]
    val alive  = mutable.ArrayBuffer.empty[Boolean]
    private val outL   = mutable.HashMap.empty[String, mutable.ArrayBuffer[Int]]
    private val inL    = mutable.HashMap.empty[String, mutable.ArrayBuffer[Int]]
    private val byPair = mutable.HashMap.empty[(String, String), Int]

    def out(n: String): mutable.ArrayBuffer[Int] =
      outL.getOrElseUpdate(n, mutable.ArrayBuffer.empty)
    def in(n: String): mutable.ArrayBuffer[Int] =
      inL.getOrElseUpdate(n, mutable.ArrayBuffer.empty)
    def has(t: String, h: String): Boolean = byPair.contains((t, h))

    def make(t: String, h: String, ml: Int, w: Int): Unit =
      byPair.get((t, h)) match
        case Some(i) => minlen(i) = math.max(minlen(i), ml); weight(i) += w
        case None    => add(t, h, ml, w)

    /** `virtual_edge` with no merge: the edge is always new. */
    def add(t: String, h: String, ml: Int, w: Int): Unit =
      val i = tail.length
      tail += t; head += h; minlen += ml; weight += w; alive += true
      out(t) += i; in(h) += i
      byPair((t, h)) = i

    // zapinlist (fastgr.c): swap-remove. The LAST member fills the hole.
    private def zap(buf: mutable.ArrayBuffer[Int], e: Int): Unit =
      val j = buf.indexOf(e)
      if j >= 0 then { buf(j) = buf(buf.length - 1); buf.remove(buf.length - 1) }

    /** reverse_edge (acyclic.c:20): delete the fast edge, then merge it into
      * the opposite fast edge, or make that edge. A new edge goes to the END
      * of the lists of its ends and of the creation order. */
    def reverse(e: Int): Unit =
      alive(e) = false
      zap(out(tail(e)), e)
      zap(in(head(e)), e)
      byPair.remove((tail(e), head(e)))
      make(head(e), tail(e), minlen(e), weight(e))

    /** minmax_edges (rank.c:316): reverse each fast out-edge of maxset (it
      * becomes a sink) and each fast in-edge of minset (it becomes a
      * source), always `list[0]` until the list is empty. */
    def minmax(minset: Option[String], maxset: Option[String]): Unit =
      maxset.foreach { mx => val o = out(mx); while o.nonEmpty do reverse(o(0)) }
      minset.foreach { mn => val i = in(mn); while i.nonEmpty do reverse(i(0)) }

    /** decompose(g, 0) (decomp.c): the connected components, each in the
      * order of `search_component`. The seeds come in the given order. The
      * walk takes the OUT-edges before the IN-edges, each list in its own
      * order. */
    def decompose(seeds: Iterable[String]): Vector[Vector[String]] =
      val done  = mutable.Set.empty[String]
      val comps = Vector.newBuilder[Vector[String]]
      seeds.foreach { s =>
        if !done(s) then
          val comp = Vector.newBuilder[String]
          val stk  = mutable.Stack(s)
          while stk.nonEmpty do
            val n = stk.pop()
            if !done(n) then
              done += n; comp += n
              in(n).reverseIterator.foreach(e => if !done(tail(e)) then stk.push(tail(e)))
              out(n).reverseIterator.foreach(e => if !done(head(e)) then stk.push(head(e)))
          comps += comp.result()
      }
      comps.result()

    /** acyclic (acyclic.c:32) on the fast graph, with DFS seeds in `order`.
      * An out-edge to a head on the stack is reversed in place. The swap
      * puts the LAST edge in that slot, and gv's `i--` examines the slot
      * again. */
    def acyclic(order: Iterable[String]): Unit =
      val mark    = mutable.Set.empty[String]
      val onStack = mutable.Set.empty[String]
      def dfs(n: String): Unit =
        if !mark(n) then
          mark += n; onStack += n
          val o = out(n)
          var i = 0
          while i < o.length do
            val e = o(i)
            val w = head(e)
            if onStack(w) then reverse(e) // examine slot i again
            else
              if !mark(w) then dfs(w)
              i += 1
          onStack -= n
      order.foreach(dfs)

    /** minmax_edges2 (rank.c): for each root `n` in `roots` order, an edge
      * `n → maxset` (minlen slenY) when n has no out-edge, and an edge
      * `minset → n` (minlen slenX) when n has no in-edge. Both have weight
      * 0. The test reads the lists as they are at that time, so an edge
      * that an earlier node added counts. True when it adds an edge. */
    def minmax2(roots: Iterable[String], minset: Option[String], maxset: Option[String],
                slenX: Int, slenY: Int): Boolean =
      var added = false
      if minset.isDefined || maxset.isDefined then
        roots.foreach { n =>
          maxset.foreach { mx =>
            if out(n).isEmpty && n != mx then { add(n, mx, slenY, 0); added = true }
          }
          minset.foreach { mn =>
            if in(n).isEmpty && n != mn then { add(mn, n, slenX, 0); added = true }
          }
        }
      added

    /** The live fast edges in creation order: the NS builds each node's
      * ND_out and ND_in from this order. */
    def nsEdges: Vector[NetworkSimplex.NSEdge] =
      tail.indices.iterator.filter(alive)
        .map(i => NetworkSimplex.NSEdge(tail(i), head(i), minlen(i), weight(i)))
        .toVector
  end FastGraph

  /** dot1_rank (rank.c:429) for the root and, through collapse_cluster, for
    * each cluster. Each level L does:
    *
    *   1. collapse_sets(L): walk the subgraphs of L in order. A cluster
    *      (is_cluster comes first) gets its own dot1_rank, then its members
    *      union to its leader. A rank set (`agget(S, "rank")` is same, min,
    *      max, source or sink) unions its members to its first member (in
    *      cgraph AGSEQ, with the nodes of nested subgraphs). min and source
    *      sets union into the minset of L, max and sink sets into the maxset
    *      of L. The walk goes into a plain subgraph, but not into a set or a
    *      cluster.
    *   2. class1(L): for each edge of L, t and h are the UF roots of its
    *      ends. An edge with t == h is skipped (it is flat, or it is inside
    *      a child cluster). An edge with an end in a child cluster becomes a
    *      SLACK node v with two aux edges (interclust1, class1.c:31):
    *      v → t (minlen t_len, weight CL_BACK·w) and v → h (h_len, w), with
    *      offset = minlen + t_local − h_local. The aux edges go to the UF
    *      ROOTS, so the slack edge of a set member goes to the set root.
    *      Each other edge merges into the fast edge of (t, h).
    *   3. minmax_edges, decompose, acyclic on the fast graph in decompose
    *      order, minmax_edges2 (decompose again when it adds an edge).
    *   4. rank1: the NS for each connected component. TB balance only when
    *      L has no child cluster (rank.c:382).
    *   5. expand_ranksets: each member's rank is its root's rank plus its
    *      local rank in its child cluster.
    *   6. cluster_leader: the LAST normal node with rank 0 in GD_nlist(L).
    *      GD_nlist is the list of the last component, and only UF roots are
    *      in it.
    *
    * The working edges stay on the original nodes (Order makes its virtual
    * chains from them). The lowest level that has both ends of an edge sets
    * the orientation of the edge. */
  private def rankedDot1(g: RGraph): (Map[String, Int], Vector[DEdge]) =
    val minlenScale = if hasEdgeLabel(g) then 2 else 1
    val realEdges   = g.edges.filter(e => e.tail != e.head)
    val nodeSeq     = g.nodes.iterator.map(_.id).zipWithIndex.toMap
    // agfstout order (cgraph agedgeseqcmpf): the out-edges of a node by
    // (head seq, edge seq).
    val outEdges: Map[String, Vector[Int]] =
      realEdges.indices.toVector.groupBy(i => realEdges(i).tail).map { (t, is) =>
        t -> is.sortBy(i => (nodeSeq.getOrElse(realEdges(i).head, Int.MaxValue), i))
      }

    // The transitive nodes of a subgraph, in the GLOBAL node order (=
    // AGSEQ), as cgraph `agfstnode(subg)` gives them, not in body order.
    // This order is the dot1 nlist: it sets the decompose seeds, the
    // acyclic order and the cluster leader.
    def transSet(s: RSubgraph): Set[String] =
      s.nodeIds.toSet ++ s.children.flatMap(transSet)
    def allNodes(s: RSubgraph): Vector[String] =
      val set = transSet(s)
      g.nodes.iterator.map(_.id).filter(set).toVector

    // rank_set_class (rank.c): maptoken of agget(S, "rank"). The value comes
    // through the defaults of the scope: a `rank` of the root or of a
    // parent subgraph goes to each subgraph that is made after it
    // (RSubgraph.attrs). An unknown value is not a set.
    def rankKind(s: RSubgraph): Option[String] =
      s.attrs.get("rank").filter(Set("same", "min", "source", "max", "sink"))

    // collapse_sets(L): the child clusters and the rank sets of a level.
    def collapseSets(subs: Vector[RSubgraph]): (Vector[RSubgraph], Vector[(RSubgraph, String)]) =
      val clusters = Vector.newBuilder[RSubgraph]
      val sets     = Vector.newBuilder[(RSubgraph, String)]
      def walk(ss: Vector[RSubgraph]): Unit = ss.foreach { s =>
        if s.isCluster then clusters += s
        else rankKind(s) match
          case Some(k) => sets += ((s, k))
          case None    => walk(s.children)
      }
      walk(subs)
      (clusters.result(), sets.result())

    // The orientation of each edge for Order/Spline. The level that ranks
    // the edge sets it: Reversed (acyclic or minmax_edges reversed the
    // fast edge) or Slack (the edge goes in or out of a child cluster).
    // Other edges keep the declared direction.
    val Reversed = 1; val Slack = 2
    val orient = mutable.HashMap.empty[Int, Int]

    /** dot1_rank(L): (the cluster leader, member → local rank). */
    def rankLevel(
        levelId: String, nodes: Vector[String], subs: Vector[RSubgraph]
    ): (String, Map[String, Int]) =
      val inLevel = nodes.toSet
      val (clusters, sets) = collapseSets(subs)
      // collapse_cluster returns at once for a cluster with no nodes.
      val kids = clusters.filter(c => transSet(c).exists(inLevel))
      val childLeader = mutable.HashMap.empty[String, String]
      val childLocal  = mutable.HashMap.empty[String, Int]
      kids.foreach { c =>
        val (ld, loc) = rankLevel(c.id, allNodes(c), c.children)
        allNodes(c).foreach(m => childLeader(m) = ld)
        childLocal ++= loc
      }

      // collapse_rankset: UF_union(u, v) keeps the root of u, so the root of
      // a set is its first member, and the minset (maxset) root is the first
      // member of the first min (max) set. A member of a child cluster stays
      // in the cluster: a set that crosses a cluster boundary is the 03 case
      // (see rankedImpl).
      val parent = mutable.HashMap.empty[String, String]
      def find(x: String): String =
        val p = parent.getOrElse(x, x)
        if p == x then x else { val r = find(p); parent(x) = r; r }
      def union(u: String, v: String): Unit =
        val (ru, rv) = (find(u), find(v))
        if ru != rv then parent(rv) = ru
      var minset = Option.empty[String]; var maxset = Option.empty[String]
      var slenX  = 0; var slenY = 0 // source ⇒ 1, sink ⇒ 1 (strict extreme)
      sets.foreach { (s, kind) =>
        val members = allNodes(s).filter(n => inLevel(n) && !childLeader.contains(n))
        members.headOption.foreach { u =>
          members.iterator.drop(1).foreach(union(u, _))
          kind match
            case "min" | "source" => minset = Some(minset.fold(u) { m => union(m, u); m })
            case "max" | "sink"   => maxset = Some(maxset.fold(u) { m => union(m, u); m })
            case _                => ()
          if kind == "source" then slenX = 1
          if kind == "sink" then slenY = 1
        }
      }
      // mark_clusters: UF_setname makes the child leader the root.
      def lead(n: String): String = childLeader.getOrElse(n, find(n))

      // class1 (class1.c): the tails in agfstnode(L) order, the out-edges in
      // agfstout order. The aux edges of a slack node are fast edges too.
      val fg        = FastGraph()
      val slack     = mutable.ArrayBuffer.empty[String]
      val pairOf    = mutable.ArrayBuffer.empty[(Int, String, String)]
      nodes.foreach { n =>
        outEdges.getOrElse(n, Vector.empty).foreach { i =>
          val e = realEdges(i)
          if inLevel(e.head) && constrained(e.attrs) then
            val t = lead(e.tail); val h = lead(e.head)
            if t != h then
              val ml = minlenOf(e.attrs) * minlenScale
              // class1 merge: ED_weight starts at the `weight` ATTR and the
              // merge adds the weights of duplicates (pprof's weight=94).
              val w  = weightOf(e.attrs)
              if childLeader.contains(e.tail) || childLeader.contains(e.head) then
                orient(i) = Slack
                val off = ml + childLocal.getOrElse(e.tail, 0) - childLocal.getOrElse(e.head, 0)
                val (tLen, hLen) = if off > 0 then (0, off) else (-off, 0)
                val v = s"%slack:$levelId:${slack.length}"
                slack += v
                fg.make(v, t, tLen, 10 * w) // CL_BACK
                fg.make(v, h, hLen, w)
              else
                fg.make(t, h, ml, w)
                pairOf += ((i, t, h))
        }
      }

      val mn = minset.map(find); val mx = maxset.map(find)
      fg.minmax(mn, mx)
      // decompose(L, 0): the seeds are the UF roots in agfstnode(L) order.
      // acyclic walks GD_nlist AFTER decompose put it in component DFS
      // order. The seed order decides which back edge of a cycle is
      // reversed: in 191 the SignatureLayoutType ⇄ FieldSpecType ⇄
      // SignatureLayoutCompanion triangle, gv enters at the Companion and
      // reverses FST→SLC.
      val roots = nodes.filter(n => lead(n) == n)
      var comps = fg.decompose(roots)
      fg.acyclic(comps.flatten)
      pairOf.foreach((i, t, h) => if !fg.has(t, h) then orient(i) = Reversed)
      if fg.minmax2(roots, mn, mx, slenX, slenY) then comps = fg.decompose(roots)

      val balanced = kids.isEmpty
      val solved = solvePerComponent(roots ++ slack, fg.nsEdges,
        balance = if balanced then NSBalance.TopBottom else NSBalance.None,
        tbOrder = if balanced then comps.flatten else Vector.empty)
      val local = nodes.iterator.map { m =>
        m -> (solved.getOrElse(lead(m), 0) + childLocal.getOrElse(m, 0))
      }.toMap
      // An empty level is only the root of an empty graph: it has no leader.
      val leader = comps.lastOption.getOrElse(roots)
        .filter(n => !isSlackNode(n) && solved.getOrElse(n, 0) == 0)
        .lastOption.orElse(nodes.headOption).getOrElse("")
      (leader, local)

    val (_, ranks) = rankLevel("$root", g.nodes.map(_.id), g.subgraphs)

    // Slack (cluster-crossing) edges follow the FINAL rank comparison
    // (class2's backward-edge handling). Flat edges keep the declared
    // direction.
    val wedges = realEdges.zipWithIndex.map { (e, i) =>
      val ml = minlenOf(e.attrs) * minlenScale
      orient.get(i) match
        case Some(Slack) if ranks(e.tail) > ranks(e.head) => DEdge(e.head, e.tail, ml)
        case Some(Reversed)                               => DEdge(e.head, e.tail, ml)
        case _                                            => DEdge(e.tail, e.head, ml)
    }
    (ranks, wedges)

  /** The global ranking for `newrank` graphs and for the 03 exception (see
    * [[rankedImpl]]): one level, with clusters as plain subgraphs. It keeps
    * the older order rules (decompose before minmax_edges, a minmax_edges2
    * test on the edges before the additions), because the 03b newrank gate
    * is byte-exact with them. */
  private def rankedGlobal(g: RGraph): (Map[String, Int], Vector[DEdge]) =
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
    // ── the class1 LEADER fast graph (dot1_rank order: collapse_sets →
    // class1 → minmax_edges → decompose → acyclic → rank1) ────────────────
    // The ranking graph carries ONE fast edge per LEADER (t,h) pair
    // (`find_fast_edge` + `merge_oneway`: minlen max, weight sum; first
    // occurrence keeps its creation position). Creation order = class1's
    // scan: per TAIL node in declaration order, out-edges by cgraph's
    // (head seq, idx). CRUCIALLY, gv's `acyclic` then runs ON THIS COLLAPSED
    // graph — rank=same unions can create leader-level cycles that don't
    // exist between nodes (sdh: `vc3TTP->xp->au3CTP` chains vs
    // `au3CTP_4_1->xp_4_1` make au3CTP⇄xp a 2-cycle), and a node-level
    // acyclic pass never sees them, feeding the NS a cyclic input whose
    // init_rank never dequeues downstream nodes.
    val realEdges = g.edges.filter(e => e.tail != e.head)
    val minlenScaleG = if hasEdgeLabel(g) then 2 else 1
    // ED_weight = the edge's `weight` attr (late_int: default 1, FLOOR 0,
    // atoi truncation). It reaches TB_balance's inweight==outweight test —
    // lion_share's weight=2 marriage edges keep spouses' children OFF the
    // balance path; a hardcoded 1 let 018 drift to the emptier marr rank.
    val fg = FastGraph()
    locally {
      val nodeSeqC = g.nodes.iterator.map(_.id).zipWithIndex.toMap
      val eByTail = realEdges.indices.groupBy(i => realEdges(i).tail)
      g.nodes.foreach { n =>
        eByTail.getOrElse(n.id, Seq.empty)
          .sortBy(i => (nodeSeqC.getOrElse(realEdges(i).head, Int.MaxValue), i))
          .foreach { i =>
            val e = realEdges(i)
            val (t, h) = (leader(e.tail), leader(e.head))
            if t != h && constrained(e.attrs) then
              fg.make(t, h, minlenOf(e.attrs) * minlenScaleG, weightOf(e.attrs))
          }
      }
    }
    val leaderNodes = g.nodes.iterator.map(n => leader(n.id)).distinct.toVector
    // TB_balance's Tree_node order = the ranking graph's GD_nlist = the
    // decompose (decomp.c) DFS over the class1 fast graph: declaration-order
    // seeds, OUT-edges before IN-edges, adjacency in class1's creation order
    // (per tail in decl order, out-edges by (head seq, idx), first occurrence
    // of each merged (t,h) pair — ORIGINAL orientation: this path takes it
    // before minmax_edges, and acyclic runs later).
    val tbOrder: Vector[String] = fg.decompose(leaderNodes).flatten
    // minmax_edges (rank.c:316): make maxset a sink / minset a source.
    fg.minmax(rs.minset, rs.maxset)
    // acyclic (acyclic.c:32) ON THE FAST GRAPH, per component in nlist
    // (decompose) order. The leaders follow for any leader outside the
    // fast graph (isolated).
    fg.acyclic(tbOrder ++ leaderNodes)
    var nse = fg.nsEdges
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
    // rank1 (rank.c:373) ALWAYS decomposes and solves per connected
    // component — a single whole-graph solve on a disconnected input dies
    // in feasible_tree (incomplete spanning tree ⇒ the pivot loop is
    // silently SKIPPED) and ships init_rank longest-path ranks
    // (profile.gv: the stray moncontrol→profil pair cost the main
    // component its two improving pivots).
    val leaderRanks = solvePerComponent(leaderNodes, nse, balance = NSBalance.TopBottom,
      tbOrder = tbOrder)
    val ranks = g.nodes.iterator.map(n => n.id -> leaderRanks(leader(n.id))).toMap
    // class2 orientation contract: a working edge whose head ranks ABOVE its
    // tail (possible only via the LEADER-level acyclic — the real edge is
    // never reversed, sdh's au3CTP_4_1→xp_4_1) is flipped so downstream
    // chains run downward; the arrow stays at the original head (g.edges is
    // the authority via segOwner). Flat (equal-rank) edges keep orientation.
    val wedgesAligned = wedges.map { e =>
      if ranks.getOrElse(e.tail, 0) > ranks.getOrElse(e.head, 0)
      then DEdge(e.head, e.tail, e.minlen)
      else e
    }
    (ranks, wedgesAligned)

  /** Normalised integer ranks (min rank = 0) for every node. */
  def assign(g: RGraph): Map[String, Int] = ranked(g)._1

end Rank
