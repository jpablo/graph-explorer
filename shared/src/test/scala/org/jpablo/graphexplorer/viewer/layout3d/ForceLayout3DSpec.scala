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

  test("a held knob drag (reheat every step) does not make a tight pair vibrate"):
    // The screen-recording scenario: a settled a->b pair, then the edge-length
    // slider is HELD — every input event reheats, so temperature never cools.
    // Around a tight pair the force exceeds the temperature cap on both sides
    // of equilibrium; without oscillation damping every step is a full-cap
    // jump and the pair ping-pongs with constant amplitude (~1.3 world units
    // of per-step jitter) for as long as the slider moves.
    val g      = graph("a", "b")(("a", "b"))
    val params = ForceLayout3D.paramsFrom(Map("k" -> 1.85))
    var s      = ForceLayout3D.run(g)
    s = ForceLayout3D.reheat(s, params)
    val distances =
      (1 to 60).map: _ =>
        s = ForceLayout3D.reheat(ForceLayout3D.step(s, params), params)
        dist(s, "a", "b")
    val tail   = distances.drop(30) // past the legitimate transit to the new equilibrium
    val jitter = tail.zip(tail.tail).map((x, y) => math.abs(x - y)).max
    assert(jitter < 0.08, s"per-step jitter $jitter (pre-fix: ~1.3)")

  test("disconnected components settle adjacent, not exiled"):
    // Two unrelated pairs and a singleton share no attracting edge; without
    // the repulsion cutoff they repel each other to the gravity horizon and
    // the singleton lands on the fringe.
    val s = ForceLayout3D.run(
      graph("a", "b", "c", "d", "s")(("a", "b"), ("c", "d"))
    )
    def mid(x: String, y: String) =
      (s.positions(NodeId(x)) + s.positions(NodeId(y))) * 0.5
    val pairGap = (mid("a", "b") - mid("c", "d")).length
    // intra-component geometry unharmed by the cutoff
    assert(math.abs(dist(s, "a", "b") - 1.0) < 0.4, s"ab ${dist(s, "a", "b")}")
    // components sit near each other and the singleton stays in the neighborhood
    assert(pairGap < 4.0, s"pair centroids $pairGap apart")
    assert(s.positions(NodeId("s")).length < 4.0, s"singleton at ${s.positions(NodeId("s")).length}")

  test("the Spread knob is the packing distance between components"):
    val g = graph("a", "b", "c", "d")(("a", "b"), ("c", "d"))
    def pairGap(s: LayoutState3D) =
      ((s.positions(NodeId("a")) + s.positions(NodeId("b"))) * 0.5 -
        (s.positions(NodeId("c")) + s.positions(NodeId("d"))) * 0.5).length
    val near = pairGap(ForceLayout3D.run(g, ForceLayout3D.paramsFrom(Map("repulsionRange" -> 1.5))))
    val far  = pairGap(ForceLayout3D.run(g, ForceLayout3D.paramsFrom(Map("repulsionRange" -> 5.0))))
    assert(near < far, s"near $near vs far $far")

  test("cohesion pulls cluster members together across the same edge structure"):
    // Two 2-node clusters joined by one edge; the only intra-cluster bond is
    // the cohesion force itself, so its effect is directly measurable.
    val nodes = Vector("a1", "a2", "b1", "b2").map(NodeId(_))
    val edges = Vector((NodeId("a1"), NodeId("b1")))
    val clusters = Vector(Vector(NodeId("a1"), NodeId("a2")), Vector(NodeId("b1"), NodeId("b2")))
    def intra(s: LayoutState3D) = (dist(s, "a1", "a2") + dist(s, "b1", "b2")) / 2
    val without = ForceLayout3D.run(LayoutGraph(nodes, edges))
    val within  = ForceLayout3D.run(LayoutGraph(nodes, edges, clusters))
    assert(intra(within) < intra(without))

  test("a membership change re-adopts (sync is not identity)"):
    val nodes = Vector(NodeId("a"), NodeId("b"))
    val g1    = LayoutGraph(nodes, Vector.empty)
    val g2    = LayoutGraph(nodes, Vector.empty, Vector(Vector(NodeId("a"), NodeId("b"))))
    val s     = ForceLayout3D.run(g1)
    assert(!(ForceLayout3D.sync(s, g2) eq s))

  test("the edge-length knob is the equilibrium distance: k=2 spreads a pair to ~2"):
    val g  = graph("a", "b")(("a", "b"))
    val s1 = ForceLayout3D.run(g, ForceLayout3D.paramsFrom(Map("k" -> 1.0)))
    val s2 = ForceLayout3D.run(g, ForceLayout3D.paramsFrom(Map("k" -> 2.0)))
    // gravity pulls slightly under the pure equilibrium d = k
    assert(math.abs(dist(s1, "a", "b") - 1.0) < 0.25, s"got ${dist(s1, "a", "b")}")
    assert(math.abs(dist(s2, "a", "b") - 2.0) < 0.5, s"got ${dist(s2, "a", "b")}")

  test("withKnobs keeps the id, and reheat rewarms a settled state"):
    val g        = graph("a", "b")(("a", "b"))
    val settled  = ForceLayout3D.run(g)
    val adjusted = ForceLayout3D.withKnobs(Map("k" -> 2.0))
    assertEquals(adjusted.id, ForceLayout3D.id)
    val reheated = adjusted.reheat(settled)
    assert(!reheated.done)

  test("a pinned node holds its position exactly while others keep moving"):
    val g      = graph("a", "b", "c")(("a", "b"), ("b", "c"))
    val s0     = ForceLayout3D.initial(g)
    val held   = Vec3(5, 5, 5)
    val pinned = s0.copy(
      positions = s0.positions.updated(NodeId("a"), held),
      pinned = Set(NodeId("a"))
    )
    var s = pinned
    for _ <- 1 to 20 do s = ForceLayout3D.step(s)
    assertEquals(s.positions(NodeId("a")), held)
    assert(s.positions(NodeId("b")) != pinned.positions(NodeId("b")))

  test("releasing a pin lets the simulation pull the node back"):
    val g    = graph("a", "b")(("a", "b"))
    val s    = ForceLayout3D.run(g)
    val held = Vec3(8, 0, 0)
    val dragged = s.copy(
      positions = s.positions.updated(NodeId("a"), held),
      pinned = Set(NodeId("a")),
      temperature = 0.3
    )
    val released = dragged.copy(pinned = Set.empty)
    var r = released
    var steps = 0
    while !r.done && steps < 2000 do
      r = ForceLayout3D.step(r)
      steps += 1
    val distBefore = (held - s.positions(NodeId("b"))).length
    val distAfter  = (r.positions(NodeId("a")) - r.positions(NodeId("b"))).length
    assert(distAfter < distBefore)
