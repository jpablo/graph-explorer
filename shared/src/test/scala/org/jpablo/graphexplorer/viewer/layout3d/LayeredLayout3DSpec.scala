package org.jpablo.graphexplorer.viewer.layout3d

import org.jpablo.graphexplorer.viewer.models.NodeId

class LayeredLayout3DSpec extends munit.FunSuite:

  private val params = LayeredLayout3D.defaultParams

  private def graph(nodes: String*)(edges: (String, String)*): LayoutGraph =
    LayoutGraph(
      nodes.toVector.map(NodeId(_)),
      edges.toVector.map((s, t) => (NodeId(s), NodeId(t)))
    )

  private def settle(state0: LayoutState3D): LayoutState3D =
    var state = state0
    var steps = 0
    while !state.done && steps < 2000 do
      state = LayeredLayout3D.step(state)
      steps += 1
    state

  test("a path stacks one node per rank, top-down, at exact plane heights"):
    val s   = LayeredLayout3D.initial(graph("a", "b", "c")(("a", "b"), ("b", "c")))
    val gap = params.layerGap * params.k
    assertEqualsDouble(s.positions(NodeId("a")).y, gap, 1e-9)
    assertEqualsDouble(s.positions(NodeId("b")).y, 0.0, 1e-9)
    assertEqualsDouble(s.positions(NodeId("c")).y, -gap, 1e-9)

  test("same-rank nodes share a plane and keep their separation"):
    val s = LayeredLayout3D.initial(
      graph("a", "b", "c", "d")(("a", "b"), ("a", "c"), ("b", "d"), ("c", "d"))
    )
    val b = s.positions(NodeId("b"))
    val c = s.positions(NodeId("c"))
    assertEqualsDouble(b.y, c.y, 1e-9)
    val planarDistance = math.hypot(b.x - c.x, b.z - c.z)
    assert(planarDistance >= params.k * params.minSepFactor - 1e-6)

  test("a cycle terminates: the back edge breaks and ranks stay distinct"):
    val s  = LayeredLayout3D.initial(graph("a", "b", "c")(("a", "b"), ("b", "c"), ("c", "a")))
    val ys = Vector("a", "b", "c").map(n => s.positions(NodeId(n)).y)
    assertEquals(ys.distinct.size, 3)
    assert(ys(0) > ys(1) && ys(1) > ys(2))

  test("the same graph always produces the same layout"):
    val g = graph("a", "b", "c", "d", "e")(("a", "b"), ("a", "c"), ("c", "d"), ("b", "d"), ("d", "e"))
    assertEquals(LayeredLayout3D.initial(g).positions, LayeredLayout3D.initial(g).positions)

  test("adopting a force layout morphs to exactly the layered targets"):
    val g       = graph("a", "b", "c", "d")(("a", "b"), ("b", "c"), ("b", "d"))
    val force   = ForceLayout3D.run(g)
    val adopted = LayeredLayout3D.sync(force, g)
    assertEquals(adopted.algoId, LayeredLayout3D.id)
    assert(!adopted.done) // a real morph, not a teleport
    val settled = settle(adopted)
    assert(settled.done)
    assertEquals(settled.positions, LayeredLayout3D.initial(g).positions)

  test("sync with an unchanged topology is identity"):
    val g = graph("a", "b")(("a", "b"))
    val s = LayeredLayout3D.initial(g)
    assert(LayeredLayout3D.sync(s, graph("a", "b")(("a", "b"))) eq s)

  test("a self-loop neither crashes nor ranks its node apart"):
    val s = LayeredLayout3D.initial(graph("a", "b")(("a", "a"), ("a", "b")))
    assert(s.positions.values.forall(_.isFinite))
    assert(s.positions(NodeId("a")).y > s.positions(NodeId("b")).y)

  test("changing the layer-gap knob re-targets a settled layout to the new planes"):
    val g       = graph("a", "b")(("a", "b"))
    val settled = LayeredLayout3D.initial(g)
    val wider   = LayeredLayout3D.withKnobs(Map("layerGap" -> 3.0))
    assertEquals(wider.id, LayeredLayout3D.id)
    val reheated = wider.reheat(settled)
    assert(!reheated.done)
    val s = {
      var st = reheated
      var i  = 0
      while !st.done && i < 2000 do { st = wider.step(st); i += 1 }
      st
    }
    assertEqualsDouble(s.positions(NodeId("a")).y - s.positions(NodeId("b")).y, 3.0, 1e-9)

  test("a pinned node resists the tween, then returns on release"):
    val g    = graph("a", "b")(("a", "b"))
    val s    = LayeredLayout3D.initial(g)
    val held = Vec3(3, 3, 3)
    val dragged = s.copy(
      positions = s.positions.updated(NodeId("a"), held),
      pinned = Set(NodeId("a")),
      temperature = (held - s.targets(NodeId("a"))).length
    )
    val stillHeld = LayeredLayout3D.step(LayeredLayout3D.step(dragged))
    assertEquals(stillHeld.positions(NodeId("a")), held)
    val released = settle(stillHeld.copy(pinned = Set.empty))
    assertEquals(released.positions(NodeId("a")), s.targets(NodeId("a")))

  test("a new node tweens in from sync without disturbing survivors' targets"):
    val g1 = graph("a", "b")(("a", "b"))
    val g2 = graph("a", "b", "c")(("a", "b"), ("b", "c"))
    val s2 = settle(LayeredLayout3D.sync(LayeredLayout3D.initial(g1), g2))
    assertEquals(s2.positions, LayeredLayout3D.initial(g2).positions)
