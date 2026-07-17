package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.{RGraph, RSubgraph}
import scala.collection.mutable

/** M8: cluster structure for the layout pipeline (gv 13.0.1 semantics).
  *
  * The engine ranks globally (gv's `newrank=true` path — the default
  * cluster-recursive ranker is not ported; it is *broken* for cross-cluster
  * `rank=same`, see 03-subgraph-cluster). Downstream of ranking, clusters are
  * pure geometry: Y label bands ([[Coord]]), X border constraints
  * ([[XCoord]] `pos_clusters`), spline clipping ([[Spline]] `cl_bound`) and
  * box/label emission ([[org.jpablo.graphexplorer.graphviz.output]]).
  *
  * This object models `mkClusters` + `mark_lowclusters` (cluster.c):
  *  - [[clusters]] — every `cluster*` subgraph, **preorder** (`GD_clust`
  *    recursion order; clusters nested in plain subgraphs still count);
  *  - membership — the *innermost* cluster of every layout node.
  *    `mark_lowcluster_basic` recurses into subclusters before marking, so
  *    the deepest (then earliest-declared) cluster wins. Virtual chain nodes
  *    belong to the innermost cluster *declaring* their original edge.
  */
object Cluster:

  private val Gap         = 4.0  // const.h GAP
  private val DefFontSize = 14.0 // DEFAULT_FONTSIZE

  /** One cluster's structural + label data.
    *
    * @param parent   index (into [[clusters]]) of the innermost enclosing
    *                 cluster, -1 = root.
    * @param nodeIds  transitive real-node members (declaration order).
    * @param members  layout-node *names* inside: real ids + `__v` chain names.
    * @param minRank / maxRank — rank band (over real members).
    * @param lwidthPt / lheightPt — label box (0 if no label).
    */
  final case class CInfo(
      name:      String,
      label:     String,
      parent:    Int,
      nodeIds:   Vector[String],
      members:   Set[String],
      minRank:   Int,
      maxRank:   Int,
      lwidthPt:  Double,
      lheightPt: Double
  ) derives CanEqual:
    def hasLabel: Boolean = label.nonEmpty
    /** `GD_border[TOP]` (input.c:870): label `dimen` + PAD (x += 4·GAP,
      * y += 2·GAP). Cluster labels default to `labelloc=t` ⇒ TOP border. */
    def borderTopX: Double = if hasLabel then lwidthPt + 4 * Gap else 0.0
    def borderTopY: Double = if hasLabel then lheightPt + 2 * Gap else 0.0
    /** Cluster-label centre `lp` given the cluster's [[BB]]
      * (`place_graph_label`, cluster labelloc=t default): box-centre x,
      * `UR.y − (lheight + YPAD)/2`. The single home for the formula both
      * writers (json0 `lp`, svg `<text>`) read. */
    def labelLp(bb: BB): (Double, Double) =
      ((bb.llx + bb.urx) / 2.0, bb.ury - (lheightPt + 2 * Gap) / 2.0)

  /** Cluster bounding box in final coordinates (assembled from the X solve
    * and the Y machinery — `GD_bb` after `dot_compute_bb`/translation). */
  final case class BB(llx: Double, lly: Double, urx: Double, ury: Double) derives CanEqual

  private val memo = GraphMemo[(Vector[CInfo], Map[String, Int])]()
  private def structure(g: RGraph): (Vector[CInfo], Map[String, Int]) = memo(g)(compute(g))

  /** All clusters, preorder (gv `GD_clust` emit/constraint order). */
  def clusters(g: RGraph): Vector[CInfo] = structure(g)._1

  /** Innermost-cluster index per layout-node *name* (real ids and `__v`
    * chain names). Absent ⇒ the node belongs to the root graph. */
  def clustOf(g: RGraph): Map[String, Int] = structure(g)._2

  private def compute(g: RGraph): (Vector[CInfo], Map[String, Int]) =
    // Flatten the cluster tree: preorder, descending through non-cluster
    // subgraphs (a cluster inside a plain `{}` hangs off the same parent).
    val flat = Vector.newBuilder[(RSubgraph, Int)] // (cluster, parent idx)
    var count = 0
    def walk(s: RSubgraph, parentCl: Int): Unit =
      val here =
        if s.isCluster then
          val idx = count
          flat += ((s, parentCl)); count += 1
          idx
        else parentCl
      s.children.foreach(walk(_, here))
    g.subgraphs.foreach(walk(_, -1))
    val cls = flat.result()
    if cls.isEmpty then return (Vector.empty, Map.empty)

    val ranks = Rank.assign(g)

    // Transitive node/edge membership per cluster (all descendant levels).
    def transNodes(s: RSubgraph): Vector[String] =
      (s.nodeIds ++ s.children.flatMap(transNodes)).distinct
    def transEdges(s: RSubgraph): Vector[Int] =
      (s.edgeIdxs ++ s.children.flatMap(transEdges)).distinct

    // mark_lowclusters: postorder (children before parent, siblings in
    // order); first mark wins ⇒ innermost cluster.
    val depth  = mutable.Map.empty[Int, Int]
    cls.zipWithIndex.foreach { case ((_, p), i) => depth(i) = if p < 0 then 0 else depth(p) + 1 }
    val postorder = cls.indices.sortBy(i => -depth(i)) // deepest first; stable ⇒ sibling order kept
    val clustOfB  = mutable.Map.empty[String, Int]

    // Real-edge index (tail≠head position) — the `__v{d}_{r}` naming key.
    val realEdgeIdx: Map[Int, Int] = // g.edges idx → dedge idx
      g.edges.iterator.zipWithIndex.filter { case (e, _) => e.tail != e.head }
        .map(_._2).zipWithIndex.toMap

    postorder.foreach { ci =>
      val (s, _) = cls(ci)
      transNodes(s).foreach(id => if !clustOfB.contains(id) then clustOfB(id) = ci)
      // vnodes of edges declared in this cluster (ED_to_virt chain marking)
      transEdges(s).foreach { eIx =>
        val e = g.edges(eIx)
        for
          d  <- realEdgeIdx.get(eIx)
          rt <- ranks.get(e.tail)
          rh <- ranks.get(e.head)
        do
          var r = math.min(rt, rh) + 1
          while r < math.max(rt, rh) do
            val nm = LayoutNode.Virtual(d, r).name
            if !clustOfB.contains(nm) then clustOfB(nm) = ci
            r += 1
      }
    }

    val infos = cls.zipWithIndex.map { case ((s, parent), ci) =>
      val nIds  = transNodes(s)
      val rs    = nIds.flatMap(ranks.get)
      val (lw, lh) =
        if s.label.nonEmpty then
          (NodeSize.labelWidthPt(s.label, DefFontSize, "Times", g.name.getOrElse("")),
           NodeSize.labelHeightPt(s.label, DefFontSize, g.name.getOrElse("")))
        else (0.0, 0.0)
      CInfo(s.id, s.label, parent, nIds,
        clustOfB.iterator.collect { case (nm, c) if c == ci => nm }.toSet,
        if rs.isEmpty then 0 else rs.min,
        if rs.isEmpty then 0 else rs.max,
        lw, lh)
    }
    (infos, clustOfB.toMap)

  /** Direct cluster children (indices) of cluster `ci` (-1 = root). */
  def childrenOf(g: RGraph, ci: Int): Vector[Int] =
    clusters(g).zipWithIndex.collect { case (c, i) if c.parent == ci => i }.toVector

  /** Final cluster bounding boxes, one per [[clusters]] entry:
    * x from the aux-graph `ln`/`rn` solve, y = rank centre ± cluster
    * `ht1`/`ht2` (position.c `dot_compute_bb`, non-root branch). */
  def bbs(g: RGraph): Vector[BB] =
    val cs = clusters(g)
    if cs.isEmpty then return Vector.empty
    val xb       = XCoord.clusterXBounds(g)
    val yi       = Coord.yInfo(g)
    val (_, yOf) = Coord.rankY(g)
    cs.zipWithIndex.map { (c, i) =>
      BB(xb(i)._1, yOf(c.maxRank).value - yi.clHt1(i),
         xb(i)._2, yOf(c.minRank).value + yi.clHt2(i))
    }

  /** A cluster's CANONICAL box mapped into the final frame (translate_bb's
    * corner mapping through the rankdir transform: LR/BT rotate, so the
    * (llx,ury)/(urx,lly) corner pair becomes the new LL/UR; RL and TB map
    * the LL/UR corners directly). `tf` = the writer's full transform
    * (rotation + translation) — identity+offset for TB. */
  def finalBB(g: RGraph, bb: BB, tf: (Double, Double) => (Double, Double)): BB =
    val (ll, ur) = Rank.rankdir(g) match
      case RankDir.LR | RankDir.BT => (tf(bb.llx, bb.ury), tf(bb.urx, bb.lly))
      case _                       => (tf(bb.llx, bb.lly), tf(bb.urx, bb.ury))
    BB(ll._1, ll._2, ur._1, ur._2)

end Cluster
