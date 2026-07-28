package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeNoDefaults

import scala.annotation.tailrec

/** Collapsing a group: the group and everything inside it render as ONE box,
  * and the arrows that crossed its border now start or end at that box.
  *
  * This is a VIEW transform, like hiding — it runs on the way from the full
  * graph to the visible one and never touches the source text, so expanding
  * restores the original exactly. (Contrast `combineIntoRecord`, which edits
  * the graph.)
  *
  * The box is a NODE, not an empty cluster: a cluster with no members does not
  * render at all in Graphviz, and the canonical "edge clipped at the cluster
  * border" idiom (`compound` + `lhead`/`ltail`) is not implemented by the
  * pure-Scala dot engine that draws every `dot` diagram here. The proxy node
  * keeps the GROUP's id string so the state layer can map a click on it back
  * to the group — see `ViewerState.resolveCollapsed`, which is what makes the
  * attributes toolbar edit the real group instead of minting a phantom node.
  */
trait CollapseOps:
  this: ViewerGraph =>

  /** The groups that actually collapse: a group nested inside another collapsed
    * group is already swallowed by it, so only the OUTERMOST ones do any work.
    * Ids that no longer name a group (deleted since) drop out.
    */
  def effectiveCollapsed(collapsed: Set[GroupId]): Set[GroupId] =
    @tailrec
    def hasCollapsedAncestor(g: GroupId): Boolean =
      membership(g) match
        case None         => false
        case Some(parent) => (parent in collapsed) || hasCollapsedAncestor(parent)
    collapsed.filter(g => (g in groups) && !hasCollapsedAncestor(g))

  /** `collapsed` groups become single boxes. */
  def collapseGroups(collapsed: Set[GroupId]): ViewerGraph =
    val outermost = effectiveCollapsed(collapsed)
    if outermost.isEmpty then this
    else
      // node → the proxy that now stands for it. Built per collapsed group so
      // nested members all land on the same outermost box.
      val proxyOf: Map[NodeId, NodeId] =
        outermost.flatMap { g =>
          val proxy = CollapseOps.proxyIdFor(g)
          getAllChildren(Set(g)).collect { case n: NodeId => n -> proxy }
        }.toMap

      val swallowedGroups: Set[GroupId] =
        outermost.flatMap(g => getAllChildren(Set(g)).collect { case gg: GroupId => gg }) ++ outermost

      // ── the proxy nodes ────────────────────────────────────────────────────
      val proxies: Seq[(NodeId, ViewerNode)] =
        outermost.toSeq
          .sortBy(_.value) // deterministic emission order
          .map: g =>
            val proxyId = CollapseOps.proxyIdFor(g)
            proxyId -> nodeNoDefaults(proxyId, CollapseOps.proxyAttributes(groups.get(g), g))

      val remainingNodes = nodes.filterNot((id, _) => proxyOf.contains(id))
      // The proxy sits where its group sat: inside the group's parent, if any.
      val proxyMemberships: Map[GroupMemberId, GroupId] =
        outermost.flatMap(g => membership(g).map(CollapseOps.proxyIdFor(g) -> _)).toMap

      // ── the arrows ─────────────────────────────────────────────────────────
      // Sorted so the winner of a merge is stable across renders (Map iteration
      // order is not); the survivor keeps its own attributes, and the arrows it
      // stands for are untouched in the full graph.
      val rewritten =
        arrows.toVector
          .sortBy((id, _) => id.value)
          .flatMap: (_, a) =>
            val s = proxyOf.getOrElse(a.source, a.source)
            val t = proxyOf.getOrElse(a.target, a.target)
            // wholly inside one collapsed group ⇒ it has no border to cross
            if s == t && (a.source in proxyOf) && (a.target in proxyOf) then None
            else if s == a.source && t == a.target then Some(a)
            else
              // A port names a field of the node that is no longer drawn; the
              // proxy has no such field, so the rewritten end loses its port.
              Some(
                a.copy(
                  source = s,
                  target = t,
                  sourcePort = if s == a.source then a.sourcePort else None,
                  targetPort = if t == a.target then a.targetPort else None
                )
              )
          // "merge into one": one arrow per (source, target, ports) — several
          // members pointing at the same outside node read as a single edge
          // from the box.
          .distinctBy(a => (a.source, a.target, a.sourcePort, a.targetPort))

      val remainingArrows = rewritten.map(a => a.id -> a).toMap

      // Memberships of surviving elements, minus everything swallowed.
      val remainingMemberships =
        memberships.filterNot: (memberId, groupId) =>
          (groupId in swallowedGroups) ||
            memberId.asGroupId.exists(_ in swallowedGroups) ||
            memberId.asNodeId.exists(proxyOf.contains)

      modifyElements.using(
        _.copy(
          nodes = remainingNodes ++ proxies,
          arrows = remainingArrows,
          groups = groups.filterNot((id, _) => id in swallowedGroups),
          memberships = remainingMemberships ++ proxyMemberships,
          // An arrow's declaring subgraph may have just been collapsed away.
          arrowMemberships = elements.arrowMemberships.filterNot: (arrowId, groupId) =>
            (groupId in swallowedGroups) || !remainingArrows.contains(arrowId)
        )
      )

end CollapseOps

object CollapseOps:
  /** The proxy node's id is the group's id string: one identity, two spellings,
    * so a click on the box resolves back to the group it stands for. */
  def proxyIdFor(g: GroupId): NodeId = NodeId(g.value)

  /** Is `id` the proxy for one of `collapsed`? */
  def collapsedGroupFor(id: ElementId, collapsed: Set[GroupId]): Option[GroupId] =
    id match
      case n: NodeId => Some(GroupId(n.value)).filter(collapsed.contains)
      case _         => None

  /** The box wears the group's clothes — its label, fill and border — so it
    * still reads as a group rather than as a new node, plus `shape=folder`,
    * which is the affordance: a folded container you can open.
    */
  def proxyAttributes(source: Option[ViewerGroup], g: GroupId): Attributes =
    val groupAttrs = source.map(_.attributes).getOrElse(Attributes.empty)
    // AttrValue is `String | AttrEq`; toString is the rendered spelling of both.
    def carry(from: AttributeId, to: AttributeId): Option[(String, String)] =
      groupAttrs.values.get(from).map(v => to.value -> v.toString).filter(_._2.nonEmpty)

    val label = groupAttrs.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty).getOrElse(g.value)
    // A cluster's background is `bgcolor`; a node's is `fillcolor`. Either one
    // the user set on the group should paint the box.
    val fill = carry(BgColor.attrId, FillColor.attrId).orElse(carry(FillColor.attrId, FillColor.attrId))

    // `filled` is carried as the EXPANDED sub-attribute, not a raw `style`
    // string: the graph's internal form is post-expandStyleAttributes, and
    // ViewerGraphElements asserts that a fillcolor without an effective
    // FillStyle is invalid — a raw `style=filled` does not satisfy it.
    Attributes.of(
      (Seq(
        Label.attrId.value     -> label,
        Shape.attrId.value     -> Shape.folder.toString,
        FillStyle.attrId.value -> true.toString
      ) ++ fill.toSeq ++ carry(Color.attrId, Color.attrId).toSeq)*
    )
