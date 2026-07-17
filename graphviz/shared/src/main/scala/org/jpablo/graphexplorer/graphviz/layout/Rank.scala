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
    // rank_set_class reads agget(subg, "rank") — which resolves through
    // cgraph DEFAULTS: `graph [rank=same]` declared at ROOT level makes
    // every subgraph a rank set by inheritance (the 167 grid). The root
    // itself is never collapsed (collapse_sets only walks SUBgraphs).
    val defaultRank = g.rootAttrs.get("rank").filter(_.nonEmpty)
    def walk(subs: Vector[RSubgraph]): Unit = subs.foreach { s =>
      s.rank.orElse(defaultRank).foreach { rt =>
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

  private def mapbool(v: Option[String]): Boolean = v.exists { s =>
    s.toLowerCase match
      case "true" | "yes" => true
      case "false" | "no" => false
      case other          => other.toIntOption.exists(_ > 0)
  }

  /** dot_rank (rank.c:449): `newrank` ⇒ dot2 (global ranking — our native
    * path); otherwise clusters rank RECURSIVELY (dot1: interior solve +
    * collapse to a leader + interclust1 slack constraints at the root).
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
    if tops.nonEmpty && !mapbool(g.rootAttrs.get("newrank")) && !crossClusterRankSet then
      rankedDot1(g, tops)
    else rankedGlobal(g)

  // ── dot1_rank (rank.c:429): recursive cluster ranking ─────────────────────
  // collapse_cluster: each cluster's interior is ranked with its OWN solve
  // (nested clusters first), then collapses to a leader (the LAST rank-0
  // fast node in list order); class1 turns every inter-cluster edge into a
  // SLACK virtual node with two aux edges (interclust1, class1.c:31):
  // v→tail (minlen t_len, weight CL_BACK·w) + v→head (h_len, w), offset =
  // minlen + t_local − h_local — a SOFT constraint, absent from acyclic.
  // rank1 runs UNBALANCED when clusters exist; expand_ranksets adds each
  // member's local offset to its leader's root rank.
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
    if roots.size <= 1 then NetworkSimplex.solve(nodes, edges, balance = balance, tbOrder = tbOrder)
    else
      val out = Map.newBuilder[String, Int]
      roots.foreach { r =>
        val ns = nodes.filter(compOf(_) == r)
        val nsSet = ns.toSet
        val es = edges.filter(e => nsSet(e.tail))
        out ++= NetworkSimplex.solve(ns, es, balance = balance, tbOrder = tbOrder.filter(nsSet))
      }
      out.result()

  private def rankedDot1(g: RGraph, tops: Vector[RSubgraph]): (Map[String, Int], Vector[DEdge]) =
    val minlenScale = if hasEdgeLabel(g) then 2 else 1
    val realEdges   = g.edges.filter(e => e.tail != e.head)
    val nodeSeq     = g.nodes.iterator.map(_.id).zipWithIndex.toMap

    // dotgen acyclic scoped to an edge subset: DFS seeds in `order`,
    // out-edges by (head seq, idx); returns the reversed edge indices.
    def acyclicScoped(order: Vector[String], edgeIdxs: Vector[Int]): Set[Int] =
      val outIdx = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Int]]
      order.foreach(n => outIdx(n) = mutable.ArrayBuffer.empty)
      edgeIdxs.foreach(i => outIdx.getOrElseUpdate(realEdges(i).tail, mutable.ArrayBuffer.empty) += i)
      outIdx.values.foreach { buf =>
        val sorted = buf.sortBy(i => (nodeSeq.getOrElse(realEdges(i).head, Int.MaxValue), i))
        buf.clear(); buf ++= sorted
      }
      val reversed = mutable.Set.empty[Int]
      val mark = mutable.Set.empty[String]; val onStack = mutable.Set.empty[String]
      def dfs(n: String): Unit =
        if !mark(n) then
          mark += n; onStack += n
          outIdx.getOrElse(n, mutable.ArrayBuffer.empty).foreach { i =>
            val w = realEdges(i).head
            if onStack(w) then reversed += i
            else if !mark(w) then dfs(w)
          }
          onStack -= n
      order.foreach(dfs)
      reversed.toSet

    def allNodes(s: RSubgraph): Vector[String] =
      s.nodeIds ++ s.children.flatMap(allNodes)
    def subClusters(s: RSubgraph): Vector[RSubgraph] =
      s.children.flatMap(c => if c.isCluster then Vector(c) else subClusters(c))

    val interiorRev = mutable.Set.empty[Int] // intra-cluster acyclic reversals
    val slackWasUsed = mutable.Set.empty[Int] // edges turned into slack pairs somewhere

    /** dot1_rank(subg): (leader, member → local rank). */
    def solveCluster(cl: RSubgraph): (String, Map[String, Int]) =
      val nested       = subClusters(cl)
      val nestedSolved = nested.map(solveCluster)
      val nestedLeader = mutable.HashMap.empty[String, String]
      val nestedLocal  = mutable.HashMap.empty[String, Int]
      nested.zip(nestedSolved).foreach { case (nc, (ld, loc)) =>
        allNodes(nc).foreach(m => nestedLeader(m) = ld)
        loc.foreach((m, r) => nestedLocal(m) = r)
      }
      val memberVec = allNodes(cl).distinct
      val memberSet = memberVec.toSet
      val inNested  = nestedLeader.keySet
      def lead(n: String): String = nestedLeader.getOrElse(n, n)
      // fast universe (nlist): un-nested members + nested leaders, decl order
      val fastNodes = memberVec.filter(n => lead(n) == n)
      val induced = realEdges.indices.filter { i =>
        val e = realEdges(i)
        memberSet(e.tail) && memberSet(e.head) && constrained(e.attrs)
      }.toVector
      val plain = induced.filter { i =>
        val e = realEdges(i); !inNested(e.tail) && !inNested(e.head)
      }
      val rev = acyclicScoped(fastNodes, plain)
      interiorRev ++= rev
      // NS edges: plain (class1 merge) + nested-crossing (interclust1 slack)
      val byPair = mutable.LinkedHashMap.empty[(String, String), NetworkSimplex.NSEdge]
      val extra  = mutable.ArrayBuffer.empty[NetworkSimplex.NSEdge]
      val slackNames = mutable.ArrayBuffer.empty[String]
      induced.foreach { i =>
        val e  = realEdges(i)
        val lt = lead(e.tail); val lh = lead(e.head)
        if lt != lh then
          val ml = minlenOf(e.attrs) * minlenScale
          if inNested(e.tail) || inNested(e.head) then
            slackWasUsed += i
            val off = ml + nestedLocal.getOrElse(e.tail, 0) - nestedLocal.getOrElse(e.head, 0)
            val (tLen, hLen) = if off > 0 then (0, off) else (-off, 0)
            val v = s"%slack:${cl.id}:${slackNames.length}"
            slackNames += v
            val w = weightOf(e.attrs)
            extra += NetworkSimplex.NSEdge(v, lt, tLen, 10 * w) // CL_BACK
            extra += NetworkSimplex.NSEdge(v, lh, hLen, w)
          else
            val (t, h) = if rev(i) then (lh, lt) else (lt, lh)
            // class1 merge: ED_weight starts at the edge's `weight` ATTR
            // and SUMS across merged duplicates (pprof's weight=94 edges).
            val w = weightOf(e.attrs)
            byPair.get((t, h)) match
              case Some(r) => byPair((t, h)) =
                NetworkSimplex.NSEdge(t, h, math.max(r.minlen, ml), r.weight + w)
              case None => byPair((t, h)) = NetworkSimplex.NSEdge(t, h, ml, w)
      }
      val nse = byPair.values.toVector ++ extra
      // rank1: TB balance only when this level has NO nested clusters.
      val (bal, tbo) =
        if nested.isEmpty then
          (NSBalance.TopBottom, decomposeOrderOf(fastNodes, plain, rev))
        else (NSBalance.None, Vector.empty[String])
      val solved = solvePerComponent(fastNodes ++ slackNames, nse, balance = bal, tbOrder = tbo)
      val local = memberVec.iterator.map { m =>
        m -> (solved.getOrElse(lead(m), 0) + nestedLocal.getOrElse(m, 0))
      }.toMap
      // cluster_leader: the LAST fast NORMAL node in nlist order with rank 0
      val leader = fastNodes.filter(n => solved.getOrElse(n, 0) == 0).lastOption.getOrElse(memberVec.head)
      (leader, local)

    // TB_balance Tree_node order for a scoped solve (decompose over the
    // class1 fast graph — same construction as the global path's tbOrder).
    def decomposeOrderOf(fastNodes: Vector[String], plain: Vector[Int], rev: Set[Int]): Vector[String] =
      val outAdjR = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[String]]
      val inAdjR  = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[String]]
      fastNodes.foreach { n => outAdjR(n) = mutable.ArrayBuffer.empty; inAdjR(n) = mutable.ArrayBuffer.empty }
      val seen   = mutable.Set.empty[(String, String)]
      val byTail = plain.groupBy(i => realEdges(i).tail)
      fastNodes.foreach { n =>
        byTail.getOrElse(n, Vector.empty)
          .sortBy(i => (nodeSeq.getOrElse(realEdges(i).head, Int.MaxValue), i))
          .foreach { i =>
            val e = realEdges(i)
            if !seen((e.tail, e.head)) then
              seen += ((e.tail, e.head))
              outAdjR(e.tail) += e.head
              inAdjR(e.head) += e.tail
          }
      }
      val done = mutable.Set.empty[String]
      val res  = mutable.ArrayBuffer.empty[String]
      def visit(seed: String): Unit =
        val stk = mutable.Stack(seed)
        while stk.nonEmpty do
          val n = stk.pop()
          if !done(n) then
            done += n; res += n
            inAdjR(n).reverseIterator.foreach(w => if !done(w) then stk.push(w))
            outAdjR(n).reverseIterator.foreach(w => if !done(w) then stk.push(w))
      fastNodes.foreach(s => if !done(s) then visit(s))
      res.toVector

    val topSolved = tops.map(solveCluster)
    val leaderOf  = mutable.HashMap.empty[String, String]
    val localOf   = mutable.HashMap.empty[String, Int]
    tops.zip(topSolved).foreach { case (cl, (ld, loc)) =>
      allNodes(cl).foreach(m => leaderOf(m) = ld)
      loc.foreach((m, r) => localOf(m) = r)
    }
    val clustered = leaderOf.keySet
    def lead(n: String): String = leaderOf.getOrElse(n, n)

    // root acyclic: only edges with NEITHER endpoint clustered (intra edges
    // were handled by the interior solves; inter-cluster edges are slack).
    val rootIdxs  = realEdges.indices.filter(i => constrained(realEdges(i).attrs)).toVector
    val plainRoot = rootIdxs.filter { i =>
      val e = realEdges(i); !clustered(e.tail) && !clustered(e.head)
    }
    val rootRev = acyclicScoped(g.nodes.map(_.id), plainRoot)

    val byPair = mutable.LinkedHashMap.empty[(String, String), NetworkSimplex.NSEdge]
    val extra  = mutable.ArrayBuffer.empty[NetworkSimplex.NSEdge]
    val slackNames = mutable.ArrayBuffer.empty[String]
    rootIdxs.foreach { i =>
      val e  = realEdges(i)
      val lt = lead(e.tail); val lh = lead(e.head)
      if lt != lh then
        val ml = minlenOf(e.attrs) * minlenScale
        if clustered(e.tail) || clustered(e.head) then
          slackWasUsed += i
          val off = ml + localOf.getOrElse(e.tail, 0) - localOf.getOrElse(e.head, 0)
          val (tLen, hLen) = if off > 0 then (0, off) else (-off, 0)
          val v = s"%slack:$$root:${slackNames.length}"
          slackNames += v
          val w = weightOf(e.attrs)
          extra += NetworkSimplex.NSEdge(v, lt, tLen, 10 * w) // CL_BACK * weight
          extra += NetworkSimplex.NSEdge(v, lh, hLen, w)
        else
          val (t, h) = if rootRev(i) then (lh, lt) else (lt, lh)
          // class1 merge: ED_weight = weight ATTR, summed across duplicates.
          val w = weightOf(e.attrs)
          byPair.get((t, h)) match
            case Some(r) => byPair((t, h)) =
              NetworkSimplex.NSEdge(t, h, math.max(r.minlen, ml), r.weight + w)
            case None => byPair((t, h)) = NetworkSimplex.NSEdge(t, h, ml, w)
    }
    val rootNodes = g.nodes.iterator.map(n => lead(n.id)).distinct.toVector ++ slackNames
    // rank1 with clusters present: NO TB balance (rank.c:382); per-component.
    val solved = solvePerComponent(rootNodes, byPair.values.toVector ++ extra.toVector,
      balance = NSBalance.None)
    val ranks = g.nodes.iterator.map { n =>
      n.id -> (solved.getOrElse(lead(n.id), 0) + localOf.getOrElse(n.id, 0))
    }.toMap

    // working orientations for Order/Spline: plain edges follow their acyclic
    // pass; slack (cluster-crossing) edges follow the FINAL rank comparison
    // (class2's backward-edge handling); flat stays declared.
    val wedges = realEdges.zipWithIndex.map { (e, i) =>
      val ml = minlenOf(e.attrs) * minlenScale
      if slackWasUsed(i) then
        if ranks(e.tail) > ranks(e.head) then DEdge(e.head, e.tail, ml) else DEdge(e.tail, e.head, ml)
      else if interiorRev(i) || rootRev(i) then DEdge(e.head, e.tail, ml)
      else DEdge(e.tail, e.head, ml)
    }
    (ranks, wedges)

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
    val fTail = mutable.ArrayBuffer.empty[String]
    val fHead = mutable.ArrayBuffer.empty[String]
    val fMinlen = mutable.ArrayBuffer.empty[Int]
    val fWeight = mutable.ArrayBuffer.empty[Int]
    val fAlive = mutable.ArrayBuffer.empty[Boolean]
    val fOut = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Int]]
    val fIn  = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Int]]
    val fastByPair = mutable.HashMap.empty[(String, String), Int]
    def fastMake(t: String, h: String, ml: Int, w: Int): Unit =
      fastByPair.get((t, h)) match
        case Some(i) => // merge_oneway: minlen max, weight sum, position kept
          fMinlen(i) = math.max(fMinlen(i), ml); fWeight(i) += w
        case None =>
          val i = fTail.length
          fTail += t; fHead += h; fMinlen += ml; fWeight += w; fAlive += true
          fOut.getOrElseUpdate(t, mutable.ArrayBuffer.empty) += i
          fIn.getOrElseUpdate(h, mutable.ArrayBuffer.empty) += i
          fastByPair((t, h)) = i
    // zapinlist (fastgr.c): swap-remove — the hole is filled with the LAST
    // member. delete_fast_edge = zap from tail-out + head-in.
    def zap(buf: mutable.ArrayBuffer[Int], e: Int): Unit =
      val j = buf.indexOf(e)
      if j >= 0 then { buf(j) = buf(buf.length - 1); buf.remove(buf.length - 1) }
    // reverse_edge (acyclic.c:20): delete the fast edge; merge into the
    // opposite fast edge when one exists, else create it (appended at the
    // END of the new endpoints' lists AND of the global creation order).
    def fastReverse(e: Int): Unit =
      fAlive(e) = false
      zap(fOut.getOrElseUpdate(fTail(e), mutable.ArrayBuffer.empty), e)
      zap(fIn.getOrElseUpdate(fHead(e), mutable.ArrayBuffer.empty), e)
      fastByPair.remove((fTail(e), fHead(e)))
      fastMake(fHead(e), fTail(e), fMinlen(e), fWeight(e))
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
              fastMake(t, h, minlenOf(e.attrs) * minlenScaleG, weightOf(e.attrs))
          }
      }
      // minmax_edges (rank.c:316): make maxset a sink / minset a source by
      // reverse_edge'ing every fast out-/in-edge (list[0] until empty).
      rs.maxset.foreach { mx =>
        val out = fOut.getOrElseUpdate(mx, mutable.ArrayBuffer.empty)
        while out.nonEmpty do fastReverse(out(0))
      }
      rs.minset.foreach { mn =>
        val in = fIn.getOrElseUpdate(mn, mutable.ArrayBuffer.empty)
        while in.nonEmpty do fastReverse(in(0))
      }
    }
    val leaderNodes = g.nodes.iterator.map(n => leader(n.id)).distinct.toVector
    // TB_balance's Tree_node order = the ranking graph's GD_nlist = the
    // decompose (decomp.c) DFS over the class1 fast graph: declaration-order
    // seeds, OUT-edges before IN-edges, adjacency in class1's creation order
    // (per tail in decl order, out-edges by (head seq, idx), first occurrence
    // of each merged (t,h) pair — ORIGINAL orientation; acyclic runs later).
    val tbOrder: Vector[String] =
      val nodeSeqR = g.nodes.iterator.map(_.id).zipWithIndex.toMap
      val outAdjR  = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[String]]
      val inAdjR   = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[String]]
      leaderNodes.foreach { n => outAdjR(n) = mutable.ArrayBuffer.empty; inAdjR(n) = mutable.ArrayBuffer.empty }
      val seen   = mutable.Set.empty[(String, String)]
      val byTail = g.edges.iterator.zipWithIndex.filter((e, _) => constrained(e.attrs))
        .toVector.groupBy(_._1.tail)
      g.nodes.foreach { n =>
        byTail.getOrElse(n.id, Vector.empty)
          .sortBy((e, i) => (nodeSeqR.getOrElse(e.head, Int.MaxValue), i))
          .foreach { (e, _) =>
            val lt = leader(e.tail); val lh = leader(e.head)
            if lt != lh && !seen((lt, lh)) then
              seen += ((lt, lh))
              outAdjR(lt) += lh
              inAdjR(lh) += lt
          }
      }
      val done2 = mutable.Set.empty[String]
      val res   = mutable.ArrayBuffer.empty[String]
      def visit(seed: String): Unit =
        val stk = mutable.Stack(seed)
        while stk.nonEmpty do
          val n = stk.pop()
          if !done2(n) then
            done2 += n; res += n
            inAdjR(n).reverseIterator.foreach(w => if !done2(w) then stk.push(w))
            outAdjR(n).reverseIterator.foreach(w => if !done2(w) then stk.push(w))
      leaderNodes.foreach(s => if !done2(s) then visit(s))
      res.toVector
    // acyclic (acyclic.c:32) ON THE FAST GRAPH, per component in nlist
    // (decompose) order: an out-edge to an on-stack head is reverse_edge'd
    // in place; gv's `i--` after the swap-remove re-examines the same slot
    // (now holding the swapped-in LAST edge).
    locally {
      val mark = mutable.Set.empty[String]
      val onStack = mutable.Set.empty[String]
      def dfs(n: String): Unit =
        if !mark(n) then
          mark += n; onStack += n
          val out = fOut.getOrElseUpdate(n, mutable.ArrayBuffer.empty)
          var i = 0
          while i < out.length do
            val e = out(i)
            val w = fHead(e)
            if onStack(w) then fastReverse(e) // slot i re-examined next pass
            else
              if !mark(w) then dfs(w)
              i += 1
          onStack -= n
      tbOrder.foreach(dfs)
      leaderNodes.foreach(dfs) // any leader outside the fast graph (isolated)
    }
    var nse = fTail.indices.iterator.filter(fAlive)
      .map(i => NetworkSimplex.NSEdge(fTail(i), fHead(i), fMinlen(i), fWeight(i)))
      .toVector
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
