package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.VisibilityRules.Direction
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.immutable.VectorMap

/** The contraction rule, both directions, on the shapes where it matters:
  * a diamond (shared node must survive one-sided contraction) and a chain
  * (recursive vs single-layer).
  */
class VisibilityRulesSpec extends FunSuite:

  private def graph(edges: (String, String)*): ViewerGraph =
    val nodeIds = edges.flatMap((s, t) => Seq(s, t)).distinct
    ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap.from(nodeIds.map(id => NodeId(id) -> ViewerNode.nodeWithDefaults(NodeId(id)))),
        arrows = edges.map((s, t) => Arrow(NodeId(s), NodeId(t))).map(a => a.id -> a).toMap
      )
    )

  private def ids(ss: String*): Set[NodeId] = ss.map(NodeId(_)).toSet

  //          a          d
  //         / \        /
  //        b   c ---- +     (diamond-ish: c has parents a and d)
  private def diamond = graph("a" -> "b", "a" -> "c", "d" -> "c")

  test("hiding a's successors keeps c — it is still reachable from d"):
    val (arrows, nodes) = VisibilityRules.contract(diamond, ElementIds(), ids("a"), Direction.Successors, recursive = true)
    assertEquals(nodes, ids("b"), "b loses its only parent; c keeps d")
    assertEquals(arrows.size, 2, "both of a's outgoing arrows hide")

  test("hiding c's predecessors keeps a — it still points at b"):
    val (arrows, nodes) = VisibilityRules.contract(diamond, ElementIds(), ids("c"), Direction.Predecessors, recursive = true)
    assertEquals(nodes, ids("d"), "d pointed only at c; a survives via b")
    assertEquals(arrows.size, 2, "both incoming arrows of c hide")

  private def chain = graph("a" -> "b", "b" -> "c", "c" -> "d")

  test("recursive successor contraction takes the whole tail; a layer takes one"):
    val (_, rec) = VisibilityRules.contract(chain, ElementIds(), ids("a"), Direction.Successors, recursive = true)
    assertEquals(rec, ids("b", "c", "d"))
    val (_, layer) = VisibilityRules.contract(chain, ElementIds(), ids("a"), Direction.Successors, recursive = false)
    assertEquals(layer, ids("b"))

  test("recursive predecessor contraction mirrors it from the other end"):
    val (_, rec) = VisibilityRules.contract(chain, ElementIds(), ids("d"), Direction.Predecessors, recursive = true)
    assertEquals(rec, ids("a", "b", "c"))
    val (_, layer) = VisibilityRules.contract(chain, ElementIds(), ids("d"), Direction.Predecessors, recursive = false)
    assertEquals(layer, ids("c"))

  test("contraction respects an existing hidden set"):
    // b already hidden: a's contraction sees only a→c (invisible arrows to
    // hidden nodes are not re-hidden, and c still falls to d's absence).
    val hidden = ElementIds.from(NodeId("b"))
    val g      = graph("a" -> "b", "a" -> "c")
    val (arrows, nodes) = VisibilityRules.contract(g, hidden, ids("a"), Direction.Successors, recursive = true)
    assertEquals(nodes, ids("c"))
    assertEquals(arrows.size, 1)

  test("concealedDirect: an expandable node knows its hidden neighbors"):
    val hidden = ElementIds(ids("b", "c").toSet[ElementId])
    assertEquals(VisibilityRules.concealedDirect(diamond, hidden, NodeId("a"), Direction.Successors), ids("b", "c"))
    assertEquals(VisibilityRules.concealedDirect(diamond, hidden, NodeId("d"), Direction.Successors), ids("c"))
    assertEquals(VisibilityRules.concealedDirect(diamond, hidden, NodeId("d"), Direction.Predecessors), Set.empty[NodeId])

  test("concealedDirect: a hidden ARROW alone still marks the node expandable"):
    val a      = diamond.arrows.values.find(x => x.source == NodeId("a") && x.target == NodeId("b")).get
    val hidden = ElementIds.from(a.id)
    assertEquals(VisibilityRules.concealedDirect(diamond, hidden, NodeId("a"), Direction.Successors), ids("b"))

  test("concealedCounts: only expandable nodes appear, with per-side counts"):
    val hidden = ElementIds(ids("b", "d").toSet[ElementId])
    val counts = VisibilityRules.concealedCounts(diamond, hidden)
    assertEquals(counts.get(NodeId("a")), Some((1, 0)), "a conceals b (successor side)")
    assertEquals(counts.get(NodeId("c")), Some((0, 1)), "c conceals d (predecessor side)")
    assert(!counts.contains(NodeId("b")), "hidden nodes carry no badge")

  test("a self-loop neither hides its own node nor counts as concealed"):
    val g = graph("a" -> "a", "a" -> "b")
    val (_, nodes) = VisibilityRules.contract(g, ElementIds(), ids("a"), Direction.Successors, recursive = true)
    assertEquals(nodes, ids("b"))
    val counts = VisibilityRules.concealedCounts(g, ElementIds(ids("b").toSet[ElementId]))
    assertEquals(counts.get(NodeId("a")), Some((1, 0)))

  // ── groups ────────────────────────────────────────────────────────────────
  // A group is as visible as its members: the Elements panel kept listing
  // clusters whose every member had been hidden, bright and clickable, standing
  // for nothing on the canvas.

  private def cluster = GroupId("cluster_0")
  private def empty   = GroupId("cluster_empty")

  /** a→b with both in `cluster`, plus a member-less cluster and a loose node. */
  private def grouped: ViewerGraph =
    ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap.from(
          Seq("a", "b", "loose").map(id => NodeId(id) -> ViewerNode.nodeWithDefaults(NodeId(id)))
        ),
        arrows      = Map.from(Seq(Arrow(NodeId("a"), NodeId("b"))).map(x => x.id -> x)),
        groups      = Map(cluster -> ViewerGroup.group(cluster), empty -> ViewerGroup.group(empty)),
        memberships = Map(NodeId("a") -> cluster, NodeId("b") -> cluster)
      )
    )

  test("a group is visible while ANY member is"):
    assert(VisibilityRules.groupVisible(grouped, cluster, ElementIds()))
    val oneHidden = ElementIds(ids("a").toSet[ElementId])
    assert(VisibilityRules.groupVisible(grouped, cluster, oneHidden), "b still carries it")

  test("a group whose every member is hidden is hidden too"):
    val allHidden = ElementIds(ids("a", "b").toSet[ElementId])
    assert(!VisibilityRules.groupVisible(grouped, cluster, allHidden))

  test("hiding an unrelated node leaves the group alone"):
    val elsewhere = ElementIds(ids("loose").toSet[ElementId])
    assert(VisibilityRules.groupVisible(grouped, cluster, elsewhere))

  test("a member-less group answers for itself"):
    assert(VisibilityRules.groupVisible(grouped, empty, ElementIds()), "nothing hides it")
    assert(
      !VisibilityRules.groupVisible(grouped, empty, ElementIds.from(empty)),
      "…but it can be hidden directly — with no members, no one else can vouch for it"
    )

  test("memberNodes reaches every depth, and stops at the group's edge"):
    assertEquals(VisibilityRules.memberNodes(grouped, cluster), ids("a", "b"))
    assertEquals(VisibilityRules.memberNodes(grouped, empty), Set.empty[NodeId])
