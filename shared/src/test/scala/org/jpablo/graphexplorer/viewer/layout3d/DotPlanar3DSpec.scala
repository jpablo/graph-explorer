package org.jpablo.graphexplorer.viewer.layout3d

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{toViewerGraph, SimpleGraph}
import org.jpablo.graphexplorer.viewer.models.NodeId
import upickle.default.read

class DotPlanar3DSpec extends FunSuite:

  private def n(s: String) = NodeId(s)

  /** DOT text → ViewerGraph via the real engine (structure only), the same
    * shape the viewer holds when it asks for hints.
    */
  private def viewerGraphOf(dot: String) =
    val res = Graphviz.renderFormats(dot, Seq("dot_json"))
    assertEquals(res.status, "success", res.errors.mkString("; "))
    toViewerGraph(read[SimpleGraph](res.output("dot_json")))

  private def layoutGraphOf(dot: String): LayoutGraph =
    val g     = viewerGraphOf(dot)
    val hints = PlanarHints.fromViewerGraph(g)
    assert(hints.isDefined, "engine should produce hints")
    LayoutGraph(
      g.nodes.keys.toVector,
      g.arrows.values.map(a => (a.source, a.target)).toVector,
      hints = hints
    )

  private val chain = "digraph G { a -> b; b -> c; }"

  /** Synthetic hints: chords a→b and c→d form an X; e→f is far away.
    * Declared before any test registration — -Wsafe-init rejects a test
    * lambda capturing `this` while later fields are still null.
    */
  private val crossGraph =
    val nodes = Vector("a", "b", "c", "d", "e", "f").map(n)
    val edges = Vector((n("a"), n("b")), (n("c"), n("d")), (n("e"), n("f")))
    val pos = Map(
      n("a") -> (0.0, 0.0),
      n("b") -> (10.0, 10.0),
      n("c") -> (0.0, 10.0),
      n("d") -> (10.0, 0.0),
      n("e") -> (100.0, 0.0),
      n("f") -> (110.0, 0.0)
    )
    val paths = edges.map: (s, t) =>
      Vector.tabulate(PlanarHints.SamplesPerEdge): j =>
        val tt     = j.toDouble / (PlanarHints.SamplesPerEdge - 1)
        val (a, b) = (pos(s), pos(t))
        (a._1 + (b._1 - a._1) * tt, a._2 + (b._2 - a._2) * tt)
    LayoutGraph(nodes, edges, hints = Some(PlanarHints(pos, paths)))

  // ---- hints -------------------------------------------------------------

  test("hints carry a position for every node and a fixed-size path per edge"):
    val lg = layoutGraphOf(chain)
    val h  = lg.hints.get
    assertEquals(h.positions.keySet, Set(n("a"), n("b"), n("c")))
    assertEquals(h.edgePaths.size, 2)
    h.edgePaths.foreach(p => assertEquals(p.size, PlanarHints.SamplesPerEdge))

  test("hints are deterministic"):
    assertEquals(layoutGraphOf(chain).hints, layoutGraphOf(chain).hints)

  // ---- targets -----------------------------------------------------------

  test("targets are the dot drawing: in-plane, centered, rank order preserved"):
    val lg = layoutGraphOf(chain)
    val t  = DotPlanar3D.targets(lg)
    t.values.foreach(p => assertEquals(p.z, 0.0))
    // dot ranks top-down and json0 is y-up: a above b above c
    assert(t(n("a")).y > t(n("b")).y)
    assert(t(n("b")).y > t(n("c")).y)
    // centered on the origin
    val ys = t.values.map(_.y)
    assert(math.abs(ys.max + ys.min) < 1e-6)

  test("without hints the layout degrades to the layered targets"):
    val lg = LayoutGraph(Vector(n("a"), n("b")), Vector((n("a"), n("b"))))
    assertEquals(DotPlanar3D.targets(lg), LayeredLayout3D.targets(lg))
    assertEquals(DotPlanar3D.offsets(lg), Vector.empty[Vector[Vec3]])

  // ---- crossing levels ---------------------------------------------------

  test("crossing edges get different levels; non-crossing edges stay flat"):
    val levels = DotPlanar3D.crossingLevels(crossGraph, crossGraph.hints.get)
    assert(levels(0) != levels(1), s"crossing pair shares level: $levels")
    assertEquals(levels(2), 0)
    // conservative: the first of the pair keeps the plane
    assertEquals(levels(0), 0)
    assertEquals(levels(1), 1)

  test("edges sharing an endpoint never count as crossing"):
    val nodes = Vector("a", "b", "c").map(n)
    val edges = Vector((n("a"), n("b")), (n("a"), n("c")))
    val pos   = Map(n("a") -> (0.0, 0.0), n("b") -> (10.0, 10.0), n("c") -> (10.0, -10.0))
    val paths = Vector.fill(2)(Vector((0.0, 0.0), (10.0, 10.0)))
    val lg    = LayoutGraph(nodes, edges, hints = Some(PlanarHints(pos, paths)))
    assertEquals(DotPlanar3D.crossingLevels(lg, lg.hints.get), Vector(0, 0))

  // ---- offsets and the Depth knob ----------------------------------------

  test("depth 0 keeps every edge exactly in the plane"):
    val flat = DotPlanar3D.offsets(crossGraph, DotPlanar3D.Params(depth = 0.0))
    flat.flatten.foreach(off => assertEquals(off.z, 0.0))

  test("a lifted edge bows by level × depth, zero at its endpoints"):
    val depth = 0.5
    val off   = DotPlanar3D.offsets(crossGraph, DotPlanar3D.Params(depth = depth))
    val bowed = off(1) // the +1 level edge of the crossing pair
    assertEquals(bowed.head.z, 0.0)
    assertEquals(bowed.last.z, 0.0)
    val apex = bowed.map(_.z).max
    assert(math.abs(apex - depth) < 1e-9, s"apex $apex, expected $depth")
    // the in-plane edge stays flat at any depth
    off(0).foreach(o => assertEquals(o.z, 0.0))

  test("offsets reproduce dot's spline at the targets (chord + offset = path)"):
    val lg  = layoutGraphOf(chain)
    val st  = DotPlanar3D.initial(lg)
    val t   = st.targets
    val h   = lg.hints.get
    val off = st.edgeOffsets
    // reconstruct edge 0's world path and compare against the scaled hint path
    val (s, e)   = lg.edges(0)
    val (a, b)   = (t(s), t(e))
    val nPts     = off(0).size
    val xs       = h.positions.valuesIterator.map(_._1).toVector
    val ys       = h.positions.valuesIterator.map(_._2).toVector
    val (cx, cy) = ((xs.min + xs.max) / 2, (ys.min + ys.max) / 2)
    for j <- 0 until nPts do
      val tt    = j.toDouble / (nPts - 1)
      val world = a + (b - a) * tt + off(0)(j)
      val exp   = h.edgePaths(0)(j)
      assert(math.abs(world.x - (exp._1 - cx) / 72.0) < 1e-9)
      assert(math.abs(world.y - (exp._2 - cy) / 72.0) < 1e-9)

  // ---- lifecycle ---------------------------------------------------------

  test("initial is settled; sync from a foreign state morphs to the same place"):
    val lg    = layoutGraphOf(chain)
    val fresh = DotPlanar3D.initial(lg)
    assert(fresh.done)

    val foreign = ForceLayout3D.initial(lg)
    val adopted = DotPlanar3D.sync(foreign, lg)
    assert(!adopted.done) // it has somewhere to go
    val settled = Layout3D.run(DotPlanar3D, lg).positions
    var s       = adopted
    var guard   = 0
    while !s.done && guard < 2000 do { s = DotPlanar3D.step(s); guard += 1 }
    assertEquals(s.positions, fresh.positions)

  test("sync is identity on the same graph and algorithm"):
    val lg = layoutGraphOf(chain)
    val st = DotPlanar3D.initial(lg)
    assert(DotPlanar3D.sync(st, lg) eq st)

  test("reheat with a new depth changes bows without moving nodes"):
    val lg  = layoutGraphOf("digraph G { a -> d; b -> c; a -> c; b -> d; }")
    val st  = DotPlanar3D.initial(lg, DotPlanar3D.Params(depth = 0.2))
    val re  = DotPlanar3D.reheat(st, DotPlanar3D.Params(depth = 0.8))
    assertEquals(re.positions, st.positions)
    assert(re.done, "no node distance appeared")
