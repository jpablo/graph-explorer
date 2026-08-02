package org.jpablo.graphexplorer.viewer.layout3d

import org.jpablo.graphexplorer.viewer.models.NodeId

class ForceLayout3DSpec extends munit.FunSuite:

  private def graph(nodes: String*)(edges: (String, String)*): LayoutGraph =
    LayoutGraph(
      nodes.toVector.map(NodeId(_)),
      edges.toVector.map((s, t) => (NodeId(s), NodeId(t)))
    )

  private def dist(state: LayoutState3D, a: String, b: String): Double =
    (state.positions(NodeId(a)) - state.positions(NodeId(b))).length

  test("the same graph always produces the same layout"):
    val g  = graph("a", "b", "c", "d")(("a", "b"), ("b", "c"), ("c", "d"), ("d", "a"))
    val s1 = ForceLayout3D.run(g)
    val s2 = ForceLayout3D.run(g)
    assertEquals(s1.positions, s2.positions)

  test("initial placement spreads nodes: no two coincide"):
    val g     = graph((1 to 50).map(i => s"n$i")*)()
    val s     = ForceLayout3D.initial(g)
    val posns = s.positions.values.toVector
    for
      i <- posns.indices
      j <- posns.indices
      if i < j
    do assert((posns(i) - posns(j)).length > 1e-6)

  test("converges to done with finite positions"):
    val g = graph("a", "b", "c", "d", "e")(("a", "b"), ("a", "c"), ("a", "d"), ("a", "e"))
    val s = ForceLayout3D.run(g)
    assert(s.done)
    assert(s.positions.values.forall(_.isFinite))

  test("a path graph lays out in path order"):
    val s = ForceLayout3D.run(graph("a", "b", "c")(("a", "b"), ("b", "c")))
    assert(dist(s, "a", "b") < dist(s, "a", "c"))
    assert(dist(s, "b", "c") < dist(s, "a", "c"))

  test("connected nodes end up closer than an isolated one"):
    val s = ForceLayout3D.run(
      graph("a", "b", "c", "loner")(("a", "b"), ("b", "c"), ("c", "a"))
    )
    val within  = Vector(dist(s, "a", "b"), dist(s, "b", "c"), dist(s, "c", "a"))
    val toLoner = Vector(dist(s, "loner", "a"), dist(s, "loner", "b"), dist(s, "loner", "c"))
    assert(within.max < toLoner.min)

  test("a self-loop neither crashes nor collapses the node"):
    val s = ForceLayout3D.run(graph("a", "b")(("a", "a"), ("a", "b")))
    assert(s.done)
    assert(s.positions.values.forall(_.isFinite))

  test("sync with an unchanged topology is identity"):
    val g = graph("a", "b")(("a", "b"))
    val s = ForceLayout3D.run(g)
    // A structurally equal (but not eq) graph, as a fresh visibleGraph emission produces:
    val same = graph("a", "b")(("a", "b"))
    assert(ForceLayout3D.sync(s, same) eq s)

  test("sync keeps surviving positions exactly and seeds a new node near its neighbor"):
    val params = ForceLayout3D.defaultParams
    val g      = graph("a", "b")(("a", "b"))
    val s      = ForceLayout3D.run(g, params)
    val grown  = graph("a", "b", "c")(("a", "b"), ("b", "c"))
    val synced = ForceLayout3D.sync(s, grown, params)
    assertEquals(synced.positions(NodeId("a")), s.positions(NodeId("a")))
    assertEquals(synced.positions(NodeId("b")), s.positions(NodeId("b")))
    // Placed at its neighbor's barycenter plus a k/2 nudge:
    assertEqualsDouble(dist(synced, "b", "c"), params.k * 0.5, 1e-9)
    // And the simulation is reheated so the change animates:
    assert(!synced.done)

  test("sync drops removed nodes"):
    val s      = ForceLayout3D.run(graph("a", "b", "c")(("a", "b"), ("b", "c")))
    val synced = ForceLayout3D.sync(s, graph("a", "b")(("a", "b")))
    assertEquals(synced.positions.keySet, Set(NodeId("a"), NodeId("b")))
