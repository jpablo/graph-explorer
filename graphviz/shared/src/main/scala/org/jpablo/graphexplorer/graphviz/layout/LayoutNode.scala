package org.jpablo.graphexplorer.graphviz.layout

/** A node in the layout pipeline's working graph: either a real (DOT-
  * declared) node, or a synthetic virtual / slack node introduced by the
  * `dot` algorithm to bridge long edges and constrain the x-coordinate
  * auxiliary graph.
  *
  *  - [[Real]] — a node from `RGraph.nodes`; `id` is the DOT name.
  *  - [[Virtual]] — a chain node from `class2` long-edge splitting;
  *    [[Order.order]] produces one per intermediate rank for an edge whose
  *    rank span > 1. Identified historically by the `__v{edge}_{rank}`
  *    name convention.
  *  - [[Slack]] — a synthetic source node in [[XCoord]]'s auxiliary
  *    network-simplex graph (`make_edge_pairs`'s slack source). Identified
  *    historically by the `__s{segment}` name convention.
  *
  * The `name` extension reproduces the historical String key exactly —
  * [[NetworkSimplex]]'s node identifiers are raw Strings (`NSEdge(tail:
  * String, head: String, …)`), so the typed layers convert at that boundary.
  */
enum LayoutNode derives CanEqual:
  case Real(id: String)
  case Virtual(edgeIdx: Int, rank: Int)
  case Slack(segIdx: Int)
  /** Cluster bounding-box slacknodes `ln`/`rn` (position.c `make_lrvn`):
    * the aux-graph variables holding a cluster's left/right border x.
    * `idx` indexes [[Cluster.clusters]]; -1 is the root graph's pair
    * (created by `contain_subclust`). */
  case ClusterLn(idx: Int)
  case ClusterRn(idx: Int)

  /** The historical String name used as a key in maps shared with
    * [[NetworkSimplex]] and the rest of the layout pipeline. Byte-identical
    * to the prior `s"__v${idx}_${r}"` / `s"__s${i}"` formats. */
  def name: String = this match
    case Real(id)         => id
    case Virtual(e, r)    => s"__v${e}_$r"
    case Slack(i)         => s"__s$i"
    case ClusterLn(i)     => s"__cln$i"
    case ClusterRn(i)     => s"__crn$i"

object LayoutNode:
  /** Recover a [[LayoutNode]] from its historical String name. Real-node
    * IDs are accepted verbatim (any string that does not match the
    * synthetic prefixes is treated as Real). */
  def fromName(s: String): LayoutNode =
    if s.startsWith("__v") then
      // __v{edge}_{rank}
      val rest = s.drop(3)
      val sep  = rest.indexOf('_')
      if sep > 0 then
        (rest.substring(0, sep).toIntOption, rest.substring(sep + 1).toIntOption) match
          case (Some(e), Some(r)) => Virtual(e, r)
          case _                  => Real(s)
      else Real(s)
    else if s.startsWith("__cln") then
      s.drop(5).toIntOption.map(ClusterLn.apply).getOrElse(Real(s))
    else if s.startsWith("__crn") then
      s.drop(5).toIntOption.map(ClusterRn.apply).getOrElse(Real(s))
    else if s.startsWith("__s") then
      s.drop(3).toIntOption.map(Slack.apply).getOrElse(Real(s))
    else Real(s)

  /** Cheap prefix check, equivalent to the historical
    * `id.startsWith("__v")`. Kept for Spline.scala, which converts
    * Order/XCoord's [[LayoutNode]] output back to String keys at the
    * consumption boundary (its 50+ internal lookup sites stay
    * String-typed — the kernel/boundary principle). */
  inline def isVirtualName(s: String): Boolean = s.startsWith("__v")
