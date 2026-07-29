package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.*

/** Collapsing a group: one box, arrows re-pointed at it, nothing lost.
  *
  * The invariant behind every case here is that collapse is a VIEW: the graph
  * it runs on is never modified, so expanding is just "don't apply it".
  */
class CollapseOpsSpec extends FunSuite:

  /** a → b → c, with b and c inside group `g`, plus an outside arrow c → d. */
  private def sample: ViewerGraph =
    val g = GroupId("g")
    ViewerGraph(
      ViewerGraphElements(
        nodes = scala.collection.immutable.VectorMap(
          NodeId("a") -> ViewerNode.nodeWithDefaults(NodeId("a")),
          NodeId("b") -> ViewerNode.nodeWithDefaults(NodeId("b")),
          NodeId("c") -> ViewerNode.nodeWithDefaults(NodeId("c")),
          NodeId("d") -> ViewerNode.nodeWithDefaults(NodeId("d"))
        ),
        arrows = Seq(
          Arrow(NodeId("a"), NodeId("b")),
          Arrow(NodeId("b"), NodeId("c")),
          Arrow(NodeId("c"), NodeId("d"))
        ).map(a => a.id -> a).toMap,
        groups = Map(g -> ViewerGroup.group(g, Attributes.of(Label -> "Backend"))),
        memberships = Map(NodeId("b") -> g, NodeId("c") -> g)
      )
    )

  private val g = GroupId("g")
  private val proxy = CollapseOps.proxyIdFor(g)

  test("the group and its members become one box"):
    val out = sample.collapseGroups(Set(g))
    assertEquals(out.groups.keySet, Set.empty[GroupId], "the cluster is gone")
    assert(!out.nodeIds.contains(NodeId("b")), "members are gone")
    assert(!out.nodeIds.contains(NodeId("c")))
    assert(out.nodeIds.contains(proxy), "the proxy stands in for them")
    assertEquals(out.nodeIds, Set(NodeId("a"), NodeId("d"), proxy))

  test("crossing arrows re-point at the box; internal ones disappear"):
    val out = sample.collapseGroups(Set(g))
    val ends = out.arrows.values.map(a => (a.source.value, a.target.value)).toSet
    assertEquals(ends, Set(("a", "g"), ("g", "d")), "a→box and box→d; b→c is internal")

  test("several members pointing at the same node merge into one arrow"):
    // b→d AND c→d: from outside the box they are the same edge.
    val withTwo = sample.modifyElements.using: e =>
      val extra = Arrow(NodeId("b"), NodeId("d"))
      e.copy(arrows = e.arrows + (extra.id -> extra))
    val out = withTwo.collapseGroups(Set(g))
    assertEquals(out.arrows.values.count(a => a.source == proxy && a.target == NodeId("d")), 1)

  test("the box wears the group's label, and folder marks it collapsed"):
    val out  = sample.collapseGroups(Set(g))
    val node = out.nodes(proxy)
    assertEquals(node.attributes.values(Label.attrId).toString, "Backend")
    assertEquals(node.attributes.values(Shape.attrId).toString, Shape.folder.toString)

  test("an unlabelled group falls back to its id, never an empty box"):
    val bare = sample.modifyElements.using(e => e.copy(groups = Map(g -> ViewerGroup.group(g))))
    val out  = bare.collapseGroups(Set(g))
    assertEquals(out.nodes(proxy).attributes.values(Label.attrId).toString, "g")

  test("the source graph is untouched — collapse is a view"):
    val before = sample
    val after  = before.collapseGroups(Set(g))
    assertEquals(before.nodeIds, Set(NodeId("a"), NodeId("b"), NodeId("c"), NodeId("d")))
    assertEquals(before.groups.keySet, Set(g))
    assert(after.nodeIds != before.nodeIds, "…and the view really did change")

  test("collapsing nothing changes nothing"):
    assertEquals(sample.collapseGroups(Set.empty).nodeIds, sample.nodeIds)
    // an id that no longer names a group is ignored, not crashed on
    assertEquals(sample.collapseGroups(Set(GroupId("ghost"))).nodeIds, sample.nodeIds)

  test("collapsedMemberCounts: every swallowed node counts, keyed by the proxy"):
    assertEquals(sample.collapsedMemberCounts(Set(g)), Map(proxy -> 2))
    assertEquals(sample.collapsedMemberCounts(Set(GroupId("ghost"))), Map.empty[NodeId, Int])

  test("collapsedMemberCounts: a nested collapsed group is swallowed by the outer box"):
    val h = GroupId("h")
    val nested = sample.modifyElements.using: e =>
      e.copy(
        groups = e.groups + (h -> ViewerGroup.group(h)),
        memberships = (e.memberships - NodeId("c")) + (NodeId("c") -> h) + (h -> g)
      )
    assertEquals(nested.collapsedMemberCounts(Set(g, h)), Map(proxy -> 2), "only the outermost box wears a badge")
    assertEquals(nested.collapsedMemberCounts(Set(h)), Map(CollapseOps.proxyIdFor(h) -> 1))

  test("collapsedMemberCounts: an empty group still gets an entry — the box exists"):
    val e = GroupId("empty")
    val withEmpty = sample.modifyElements.using(el => el.copy(groups = el.groups + (e -> ViewerGroup.group(e))))
    assertEquals(withEmpty.collapsedMemberCounts(Set(e)), Map(CollapseOps.proxyIdFor(e) -> 0))

  // ── collapsedView: the neighbor machinery's picture ──────────────────────

  test("collapse then hide an external neighbor — the box is expandable"):
    // g = {b, c}; hide d (outside). The box must advertise ONE concealed
    // successor — this is exactly the badge that used to be computed on the
    // full graph, where the proxy does not exist, and therefore never showed.
    val view = sample.collapsedView(Set(g), ElementIds.from(NodeId("d")))
    val counts = VisibilityRules.concealedCounts(view.graph, view.hidden)
    assertEquals(counts.get(proxy), Some((1, 0)))
    // and contraction FROM the box hides the crossing arrow's ORIGINAL id
    val (arrows, nodes) =
      VisibilityRules.contract(view.graph, ElementIds(), Set(proxy), VisibilityRules.Direction.Successors, recursive = false)
    assertEquals(nodes, Set(NodeId("d")))
    val cToD = sample.arrows.values.find(a => a.source == NodeId("c") && a.target == NodeId("d")).get
    assertEquals(view.originalArrows(arrows), Set(cToD.id))

  test("collapsedView: a rewritten arrow is hidden only when ALL its originals are"):
    // b→d and c→d both cross the border and merge into one box arrow.
    val extra = Arrow(NodeId("b"), NodeId("d"))
    val withTwo = sample.modifyElements.using(e => e.copy(arrows = e.arrows + (extra.id -> extra)))
    val cToD = withTwo.arrows.values.find(a => a.source == NodeId("c") && a.target == NodeId("d")).get
    val boxArrowId = withTwo
      .collapseGroups(Set(g))
      .arrows.values.find(a => a.source == proxy && a.target == NodeId("d")).get.id

    // one of the two hidden: the box arrow still stands (the other original shows)
    val half = withTwo.collapsedView(Set(g), ElementIds.from(extra.id))
    assert(!half.hidden.classify.arrows.contains(boxArrowId), "one visible original keeps the box arrow visible")
    // both hidden: the box arrow is concealed, and it stands for BOTH originals
    val full = withTwo.collapsedView(Set(g), ElementIds(Set[ElementId](extra.id, cToD.id)))
    assert(full.hidden.classify.arrows.contains(boxArrowId))
    assertEquals(full.originalArrows(Set(boxArrowId)), Set(extra.id, cToD.id))

  test("collapsedView: a group hidden as a GroupId re-spells as its proxy"):
    val view = sample.collapsedView(Set(g), ElementIds.from(g))
    assert(view.hidden.nodeIds.contains(proxy), "the box counts as hidden")

  test("an inner collapsed group is swallowed by the outer one"):
    val outer = GroupId("outer")
    val nested = sample.modifyElements.using: e =>
      e.copy(
        groups = e.groups + (outer -> ViewerGroup.group(outer, Attributes.of(Label -> "Outer"))),
        memberships = e.memberships + (g -> outer) + (NodeId("a") -> outer)
      )
    // both collapsed: only the OUTER box survives, and it holds everything
    assertEquals(nested.effectiveCollapsed(Set(outer, g)), Set(outer), "inner is redundant")
    val out = nested.collapseGroups(Set(outer, g))
    assertEquals(out.nodeIds, Set(NodeId("d"), CollapseOps.proxyIdFor(outer)))
    val ends = out.arrows.values.map(a => (a.source.value, a.target.value)).toSet
    assertEquals(ends, Set(("outer", "d")))

  test("a port on a rewritten end is dropped — the box has no such field"):
    val ported = sample.modifyElements.using: e =>
      val a = Arrow(NodeId("a"), NodeId("c"), targetPort = Some("f1"))
      e.copy(arrows = e.arrows + (a.id -> a))
    val out = ported.collapseGroups(Set(g))
    val toBox = out.arrows.values.filter(_.target == proxy)
    assert(toBox.nonEmpty)
    assert(toBox.forall(_.targetPort.isEmpty), "a field of a hidden member cannot be an endpoint")

  test("the box inherits its group's place in a parent group"):
    val outer = GroupId("outer")
    val nested = sample.modifyElements.using: e =>
      e.copy(
        groups = e.groups + (outer -> ViewerGroup.group(outer)),
        memberships = e.memberships + (g -> outer)
      )
    val out = nested.collapseGroups(Set(g))
    assertEquals(out.membership(proxy), Some(outer), "collapsing g must not move it out of outer")
    assert(out.groups.contains(outer), "the parent group survives")

  test("toVisibleGraph composes hiding and collapsing"):
    val hidden = ElementIds.from(NodeId("a"))
    val out    = sample.toVisibleGraph(hidden, Set(g))
    assert(!out.nodeIds.contains(NodeId("a")), "hidden stays hidden")
    assert(out.nodeIds.contains(proxy), "and the group still collapses")
    val ends = out.arrows.values.map(a => (a.source.value, a.target.value)).toSet
    assertEquals(ends, Set(("g", "d")), "a→b went with a; box→d remains")
