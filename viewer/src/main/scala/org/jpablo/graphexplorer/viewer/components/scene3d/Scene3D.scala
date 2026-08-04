package org.jpablo.graphexplorer.viewer.components.scene3d

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.backends.threejs as three
import org.jpablo.graphexplorer.viewer.formats.dot.LabelSummary
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.layout3d.{DotPlanar3D, ForceLayout3D, Layout3D, LayoutGraph, LayoutState3D, PlanarHints, Vec3}
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId, ViewerNode}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Button, RangeSlider, ghost, tiny}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.typedarray.Float32Array

/** The 3D canvas: renders `visibleGraph` as a three.js scene, bypassing the
  * whole text → engine → SVG pipeline. Layout is the shared ForceLayout3D
  * simulation, advanced a few steps per animation frame so convergence is
  * visible. Selection bridges by id: a raycast hit writes the same NodeId into
  * the same selection Var the 2D canvas uses, so panels and attribute editing
  * work unchanged.
  *
  * A fresh instance is created every time the 3D toggle turns on and disposed
  * when it turns off — no state survives the mode switch except what lives in
  * ViewerState.
  */
def Scene3D(state: ViewerState): Div =
  val scene = GraphScene3D(state)
  div(
    idAttr := "scene3d-container",
    // relative: the XRButton positions itself absolutely within this box.
    cls    := "relative h-full w-full overflow-hidden",
    onMountUnmountCallback(
      ctx => scene.start(ctx.thisNode.ref),
      _ => scene.dispose()
    ),
    state.visibleGraph --> scene.setGraph,
    state.selection.signal --> scene.setSelection,
    state.layout3D.signal --> scene.setAlgorithm,
    state.nav3DTrackpad.signal --> scene.setNavMode,
    // The toolbar's Auto and Fit speak to the 3D camera exactly as they do to
    // the 2D transform.
    state.autoFit.signal --> scene.setAutoFit,
    state.fitDiagram.events --> (_ => scene.fitNow()),
    state.face3DFront.events --> (_ => scene.faceFront()),
    // Labels bake theme colors into their textures at paint time, so a theme
    // switch must repaint them. setTheme's own observer registered first, so
    // the CSS variables are already the new theme's when this fires.
    state.currentTheme.signal.changes --> (_ => scene.repaintLabels()),
    // Bottom-LEFT: the zoom toolbar centers over the whole middle area, and
    // with the right panel open its tail reaches the canvas's right edge — the
    // left corner is the one spot no floating chrome owns. Only rendered when
    // an immersive session is actually available, so desktops never see it.
    child.maybe <-- scene.vrSupported.signal.map(av => Option.when(av)(vrButton(scene))),
    // Top-right: sliders for the current algorithm's knobs, applied to the
    // RUNNING simulation (each change reheats, so the drawing re-equilibrates
    // live). Rebuilt per algorithm; hidden when there is nothing to tune.
    child.maybe <-- state.layout3D.signal.map: algoId =>
      val algo = Layout3D.byId(algoId).getOrElse(ForceLayout3D)
      Option.when(algo.knobs.nonEmpty)(knobPanel(scene, algo))
  )

private def knobPanel(scene: GraphScene3D, algo: Layout3D) =
  div(
    cls := "floating-toolbar top-2 right-2 flex-col items-stretch gap-1.5 w-60 px-3 py-2",
    algo.knobs.map: knob =>
      div(
        cls := "flex items-center gap-2",
        span(cls := "text-xs w-20 shrink-0 opacity-70", knob.label),
        RangeSlider(
          cls      := "flex-1",
          minAttr  := knob.min.toString,
          maxAttr  := knob.max.toString,
          stepAttr := knob.step.toString,
          controlled(
            value <-- scene.knobValuesV.signal.map(_.getOrElse(knob.id, knob.default).toString),
            onInput.mapToValue --> (v => scene.setKnob(knob.id, v.toDouble))
          )
        ),
        span(
          cls := "text-xs w-9 text-right tabular-nums opacity-70",
          text <-- scene.knobValuesV.signal.map(vs => f"${vs.getOrElse(knob.id, knob.default)}%.2f")
        )
      )
  )

/** App-styled replacement for three's stock XRButton (which is translucent,
  * pins itself to bottom-center over the zoom toolbar, and ignores the theme).
  * Session request must run inside the click's user activation, which it does:
  * toggleVR is called synchronously from the handler.
  */
private def vrButton(scene: GraphScene3D) =
  div(
    cls := "floating-toolbar bottom-2 left-2",
    Button(
      cls := "gap-1.5",
      i(cls := "bi bi-badge-vr"),
      text <-- scene.vrPresenting.signal.map(p => if p then "Exit VR" else "Enter VR"),
      onClick --> scene.toggleVR()
    ).tiny.ghost
  )

final class GraphScene3D(state: ViewerState):

  /** A node's visual, in either presentation: a camera-facing billboard
    * (Sprite) for spatial layouts, or a mesh LYING IN THE DRAWING PLANE for a
    * layout that dictates geometry — a billboard's border swings away from
    * the plane the moment the camera leaves the axis, detaching every edge
    * endpoint; a plane mesh keeps edges glued at any angle, and foreshortens
    * like the sheet of paper the flat drawing is. The closures capture the
    * concrete material so every other code path stays presentation-blind.
    */
  private case class NodeSprite(
      obj:             three.Object3D,
      texture:         three.CanvasTexture,
      label:           Vector[String], // display LINES, stacked in the pill like the 2D box
      planar:          Boolean,
      setMap:          three.CanvasTexture => Unit,
      setTint:         (Int, Double) => Unit, // (color hex, opacity)
      disposeMaterial: () => Unit
  )

  private val StepsPerFrame = 3
  /** World-space height of a node label; ForceLayout3D's ideal edge length is 1. */
  private val NodeHeight   = 0.42
  private val MaxNodeWidth = 3.0
  private val SelectedTint = 0x86b6ff
  private val NormalTint   = 0xffffff
  /** Opacity of nodes outside the selection's neighborhood. */
  private val DimOpacity   = 0.16

  private val renderer   = three.WebGLRenderer(three.WebGLRenderer.params(antialias = true, alpha = true))
  private val scene      = three.Scene()
  private val camera     = three.PerspectiveCamera(50, 1, 0.1, 2000)
  /** Everything the graph draws hangs off this one group, so an XR session can
    * move/scale the whole drawing to a comfortable spot (WebXR's origin is the
    * FLOOR — at identity the graph would sit half underground).
    */
  private val graphRoot  = three.Group()
  private val nodesGroup = three.Group()
  private val raycaster  = three.Raycaster()
  private val pointerNdc = three.Vector2()
  private val tempMatrix = three.Matrix4()
  private val scratchVec = three.Vector3()

  // 3D mode gets its own fixed dark environment, like a DCC viewport, instead
  // of inheriting the document's paper-and-grid: label pills pop against it in
  // any app theme, and the matched fog turns depth into a gentle fade (its
  // distances follow the camera in fitCamera).
  private val EnvBackground = 0x151a21
  scene.background = three.Color().setHex(EnvBackground)
  scene.fog = three.Fog(EnvBackground, 10, 60)

  scene.add(graphRoot)
  graphRoot.add(nodesGroup)

  /** The registry algorithm (knob defaults) and its currently-configured
    * instance; `algo` is what actually steps.
    */
  private var baseAlgo: Layout3D    = ForceLayout3D
  private var algo: Layout3D        = baseAlgo
  private var layout: LayoutState3D = algo.initial(LayoutGraph(Vector.empty, Vector.empty))

  /** Knob values for the CURRENT algorithm (reset to defaults on switch).
    * Session-only on purpose: knobs are for playing, not configuration.
    */
  val knobValuesV: Var[Map[String, Double]] = Var(defaultKnobValues(baseAlgo))

  private def defaultKnobValues(a: Layout3D): Map[String, Double] =
    a.knobs.map(k => k.id -> k.default).toMap

  def setKnob(knobId: String, value: Double): Unit =
    knobValuesV.update(_.updated(knobId, value))
    algo = baseAlgo.withKnobs(knobValuesV.now())
    layout = algo.reheat(layout)
    // Write through even when the reheat left the state settled: a knob can
    // change EDGE geometry alone (the planar layout's Depth moves no node),
    // and the animation loop only writes transforms while stepping — without
    // this, such a knob is visually inert until something else redraws.
    writePositions()
    writeEdgeHighlight() // altitude luminance follows the (possibly re-signed) bows
  private var sprites               = Map.empty[NodeId, NodeSprite]
  private var edges                 = Vector.empty[(NodeId, NodeId)]
  private var selectedNodes         = Set.empty[NodeId]
  private var lastNodeCount         = -1

  // ---------------- trackball camera (no OrbitControls, no poles) ----------------
  // The camera orbits `orbitTarget` carrying its own rolling up-vector: no
  // world direction is privileged, so rotation composes freely and never hits
  // the pole clamp a fixed-up orbit needs. The gizmo camera inherits the same
  // up, so the globe keeps matching even with the horizon tilted.
  private var orbitTarget = Vec3.zero
  private var camUp       = Vec3(0, 1, 0)
  /** Screen-space anchor of an in-progress empty-space drag (drag = orbit). */
  private var mouseOrbitLast: Option[(Double, Double)] = None
  private var resizeObserverOpt: Option[dom.ResizeObserver]   = None

  /** True once the browser reports an immersive-vr-capable device; gates the
    * Enter-VR button so desktops never render it.
    */
  val vrSupported = Var(false)

  /** Mirrors renderer.xr session state for the button label. */
  val vrPresenting = Var(false)

  private var xrSessionOpt: Option[js.Dynamic] = None
  private var brightEdges: Array[Boolean]      = Array.empty

  // One shared cone geometry/material for every arrowhead; meshes per edge.
  private val coneGeometry = three.ConeGeometry(0.05, 0.14, 10)
  /** The cone's height, and its half: positioning the CENTER half a height
    * back along the path tangent puts the TIP exactly on the path's endpoint;
    * stems stop a FULL height back — at the cone's base. */
  private val ConeLen      = 0.14
  private val ConeTipInset = 0.07
  private val coneMaterial = three.MeshBasicMaterial(
    three.MeshBasicMaterial.params(color = 0x5a9df2, transparent = false, opacity = 1.0, depthWrite = true, side = 0)
  )
  private var coneMeshes = Vector.empty[three.Mesh]
  private val coneUp     = three.Vector3().set(0, 1, 0)
  private val coneDir    = three.Vector3()

  // Edge stems are MESHES, not GL lines: `linewidth` on a WebGL line is
  // silently ignored by essentially every platform, so LineSegments could
  // only ever draw a 1px hairline — near-invisible on a HiDPI desktop and
  // worse in a headset. One shared unit cylinder (r=1, h=1), one mesh per
  // edge scaled to (radius, length, radius) and aimed like the cones. The
  // radius is world-space on purpose: an edge is a 3D object that thins
  // with distance, which the constant-px hairline never did.
  private val StemRadius   = 0.011
  private val stemGeometry = three.CylinderGeometry(1, 1, 1, 8)
  // OPAQUE: stems, joints and cones overlap by construction (joint spheres
  // sit on segment ends, stems run into cone bases), and translucent overlaps
  // double-blend — every joint read as a bright ghost dot, and spheres showed
  // through arrowheads. The dim material stays translucent; it exists to
  // recede.
  private val stemMaterial = three.MeshBasicMaterial(
    three.MeshBasicMaterial.params(color = 0x8a92a0, transparent = false, opacity = 1.0, depthWrite = true, side = 0)
  )
  /** Aerial perspective for the depth bows: edges bowing toward the viewer
    * render lighter, away darker. A GLOBAL altitude cue that works even
    * head-on, where the bow itself is invisible.
    */
  private val stemFrontMaterial = three.MeshBasicMaterial(
    three.MeshBasicMaterial.params(color = 0xb8c0cc, transparent = false, opacity = 1.0, depthWrite = true, side = 0)
  )
  private val stemBackMaterial = three.MeshBasicMaterial(
    three.MeshBasicMaterial.params(color = 0x596069, transparent = false, opacity = 1.0, depthWrite = true, side = 0)
  )
  /** The halo: an invisible depth-only casing around each stem. It writes
    * depth but no color, so anything sufficiently BEHIND a stem is clipped in
    * a band around it — at a crossing, the nearer edge visibly CUTS the
    * farther one, the way a map draws a bridge over a road. The casing is
    * pushed away from the camera each frame (frame loop); the offset sets the
    * CUT THRESHOLD: a fragment is clipped only when it sits more than
    * (offset − haloR + stemR) behind the stem's axis. It must comfortably
    * exceed the casing radius — at 0.022 (≈ the 0.024 casing) two SAME-DEPTH
    * edges brushing at a shallow angle fell inside each other's cut band and
    * mutually erased into dashes, with no visible cutter. 0.055 puts the
    * threshold at ~0.04 world: same-plane neighbors never cut, a one-level
    * bow (≥0.2 at mid-span) always does.
    */
  private val HaloRadiusFactor = 2.2
  private val HaloDepthOffset  = 0.055
  private val stemHaloMaterial = three.MeshBasicMaterial(
    three.MeshBasicMaterial.params(
      color = 0x000000, transparent = false, opacity = 1.0, depthWrite = true, side = 0, colorWrite = false)
  )
  private var stemHalos = Vector.empty[Vector[three.Mesh]]
  // The neighborhood dim, formerly per-vertex alpha in the line buffer: now a
  // swap to this shared faint material. depthWrite off — a dimmed stem is
  // context, and must not punch holes into things behind it.
  private val stemDimMaterial = three.MeshBasicMaterial(
    three.MeshBasicMaterial.params(color = 0x8a92a0, transparent = true, opacity = 0.10, depthWrite = false, side = 0)
  )
  /** One CHAIN of segment meshes per edge: a straight stem is a chain of one;
    * a curved edge (a layout that fills edgeOffsets — dot splines, depth
    * bows) gets one segment per sample interval. Chains are sized at rebuild
    * from the current layout state.
    */
  private var stemMeshes = Vector.empty[Vector[three.Mesh]]
  /** Sphere at each interior joint of a curved chain: segments meet at an
    * angle, and exact-length cylinders leave a notch there — the sphere is
    * the round join (2D stroke `lineJoin: round`, in 3D).
    */
  private val jointGeometry = three.SphereGeometry(1, 8, 6)
  private var stemJoints    = Vector.empty[Vector[three.Mesh]]
  /** Unit plane for in-plane node sheets (dictated-geometry layouts). */
  private val planeGeometry = three.PlaneGeometry(1, 1)

  /** Soft, distinguishable hull tints; assigned by cluster order (stable —
    * clusters are sorted by group id).
    */
  private val HullPalette = Vector(0x4a90d9, 0xd9744a, 0x4ad98f, 0xc9b458, 0x9a6ad9, 0x50b8c9)
  private val HullPad     = NodeHeight * 0.7
  private var clusterSets = Vector.empty[Vector[NodeId]]
  private var hullMeshes  = Vector.empty[(three.Mesh, three.MeshBasicMaterial)]

  // ---------------- orientation gizmo: a tiny wired globe ----------------
  // Rendered as a second viewport pass in the corner; its camera copies the
  // orbit direction, so the globe reads as "the world, seen from where you
  // are". Lat/long polylines rather than a wireframe sphere — triangulated
  // wireframes show their diagonal seams.

  private val GizmoSizePx = 96.0
  // Camera distance and fov sized so the FULL axis extent (lines to 1.4 plus
  // the cone tips) fits the frustum with margin: 3.8·tan(23°) ≈ 1.61 of
  // lateral room against ~1.55 of content. At the old 3.2/40° the frustum
  // covered only 1.16 — an axis swinging toward the viewport edge had its
  // arrowhead sliced off by the scissor.
  private val GizmoCamDist = 3.8
  private val gizmoScene   = three.Scene()
  private val gizmoCamera  = three.PerspectiveCamera(46, 1, 0.1, 12)
  // Depth cue: at orbit radius 3.8 the sphere's near face sits ~2.8 away and
  // the far face ~4.8. Fog across that range fades the far hemisphere toward
  // the environment color — without it a wire sphere is a Necker illusion
  // (front and back read as interchangeable).
  gizmoScene.fog = three.Fog(EnvBackground, 3.2, 5.6)
  private val AxisRed   = (1.0, 0.45, 0.45)
  private val AxisGreen = (0.45, 0.95, 0.52)
  private val AxisBlue  = (0.48, 0.72, 1.0)
  private val AxisLen   = 1.4
  private var gizmoAxisTips = Vector.empty[(three.Mesh, three.MeshBasicMaterial)]
  private val gizmoLines    = buildGizmo()

  private def buildGizmo(): (three.BufferGeometry, three.LineBasicMaterial) =
    val pts    = scala.collection.mutable.ArrayBuffer.empty[Float]
    val cols   = scala.collection.mutable.ArrayBuffer.empty[Float]
    val StepsN = 48
    def addSegment(
        a: (Double, Double, Double),
        b: (Double, Double, Double),
        c: (Double, Double, Double),
        alpha: Double
    ): Unit =
      pts ++= Seq(a._1.toFloat, a._2.toFloat, a._3.toFloat, b._1.toFloat, b._2.toFloat, b._3.toFloat)
      cols ++= Seq(
        c._1.toFloat, c._2.toFloat, c._3.toFloat, alpha.toFloat,
        c._1.toFloat, c._2.toFloat, c._3.toFloat, alpha.toFloat
      )
    val wire = (0.55, 0.60, 0.68)
    // meridians every 30° (each circle covers m and m+180°)
    for m <- 0 until 6 do
      val az = m * math.Pi / 6
      for i <- 0 until StepsN do
        val t0 = 2 * math.Pi * i / StepsN
        val t1 = 2 * math.Pi * (i + 1) / StepsN
        addSegment(
          (math.sin(t0) * math.sin(az), math.cos(t0), math.sin(t0) * math.cos(az)),
          (math.sin(t1) * math.sin(az), math.cos(t1), math.sin(t1) * math.cos(az)),
          wire,
          0.55
        )
    // parallels at 0°, ±30°, ±60°
    for lat <- Seq(-60, -30, 0, 30, 60) do
      val y = math.sin(math.toRadians(lat))
      val r = math.cos(math.toRadians(lat))
      for i <- 0 until StepsN do
        val a0 = 2 * math.Pi * i / StepsN
        val a1 = 2 * math.Pi * (i + 1) / StepsN
        addSegment((r * math.sin(a0), y, r * math.cos(a0)), (r * math.sin(a1), y, r * math.cos(a1)), wire, 0.55)
    // axis lines, the r/g/b convention: x red, y green, z blue — full alpha,
    // reaching past the sphere so they always break the silhouette
    addSegment((0, 0, 0), (AxisLen, 0, 0), AxisRed, 1.0)
    addSegment((0, 0, 0), (0, AxisLen, 0), AxisGreen, 1.0)
    addSegment((0, 0, 0), (0, 0, AxisLen), AxisBlue, 1.0)

    val geometry = three.BufferGeometry()
    geometry.setAttribute("position", three.BufferAttribute(Float32Array.from(js.Array(pts.toSeq*)), 3))
    geometry.setAttribute("color", three.BufferAttribute(Float32Array.from(js.Array(cols.toSeq*)), 4))
    val material = three.LineBasicMaterial(
      three.LineBasicMaterial.params(vertexColors = true, transparent = true, opacity = 1.0)
    )
    val lines = three.LineSegments(geometry, material)
    lines.frustumCulled = false
    gizmoScene.add(lines)

    // A 1px line can't get "bigger": visual weight comes from a cone tip on
    // each axis (the shared arrowhead geometry, scaled up and colored).
    gizmoAxisTips = Vector(
      ((AxisLen, 0.0, 0.0), 0xff7373, (1.0, 0.0, 0.0)),
      ((0.0, AxisLen, 0.0), 0x74f284, (0.0, 1.0, 0.0)),
      ((0.0, 0.0, AxisLen), 0x7ab8ff, (0.0, 0.0, 1.0))
    ).map: (tip, color, axis) =>
      val mat = three.MeshBasicMaterial(
        three.MeshBasicMaterial.params(color = color, transparent = true, opacity = 1.0, depthWrite = true, side = 0)
      )
      val cone = three.Mesh(coneGeometry, mat)
      cone.scale.set(1.6, 1.6, 1.6)
      cone.position.set(tip._1, tip._2, tip._3)
      cone.quaternion.setFromUnitVectors(coneUp, scratchVec.set(axis._1, axis._2, axis._3))
      gizmoScene.add(cone)
      (cone, mat)
    (geometry, material)

  /** Second render pass into the corner: the gizmo camera copies the orbit
    * direction at a fixed radius, so the static globe appears exactly as
    * rotated as the world.
    */
  private def renderGizmo(): Unit =
    locally:
      val camP  = Vec3(camera.position.x, camera.position.y, camera.position.z)
      val toCam = camP - orbitTarget
      val dir   = toCam * (1.0 / math.max(1e-9, toCam.length))
      gizmoCamera.position.set(dir.x * GizmoCamDist, dir.y * GizmoCamDist, dir.z * GizmoCamDist)
      // The gizmo must roll with the main camera, or the globe stops matching
      // the moment the horizon tilts (there is no fixed up anymore).
      gizmoCamera.up.set(camUp.x, camUp.y, camUp.z)
      gizmoCamera.lookAt(0, 0, 0)
      val w = renderer.domElement.clientWidth.toDouble
      renderer.autoClear = false
      renderer.setViewport(w - GizmoSizePx - 10, 52, GizmoSizePx, GizmoSizePx)
      renderer.setScissor(w - GizmoSizePx - 10, 52, GizmoSizePx, GizmoSizePx)
      renderer.setScissorTest(true)
      renderer.clearDepth()
      renderer.render(gizmoScene, gizmoCamera)
      renderer.setScissorTest(false)
      renderer.setViewport(0, 0, w, renderer.domElement.clientHeight.toDouble)
      renderer.autoClear = true

  // ---------------- graph -> scene ----------------

  def setGraph(g: ViewerGraph): Unit =
    lastViewerGraph = Some(g)
    val nodeIds = g.nodes.keys.toVector
    edges = g.arrows.values.map(a => (a.source, a.target)).toVector
    clusterSets = visibleClusters(g, nodeIds)
    layout = algo.sync(layout, LayoutGraph(nodeIds, edges, clusterSets, hints = hintsFor(g)))

    val labels: Map[NodeId, Vector[String]] =
      g.nodes.map((id, node) => id -> displayLabel(g, id, node)).toMap
    val (keep, drop) = sprites.partition((id, ns) => labels.get(id).contains(ns.label))
    drop.values.foreach(disposeSprite)
    sprites = labels.foldLeft(keep):
      case (acc, (id, label)) =>
        if acc.contains(id) then acc else acc.updated(id, createSprite(id, label))
    resizeSprites() // surviving sprites adopt the (possibly new) sizing regime

    rebuildLines()
    rebuildHulls()
    applySelection()
    writePositions()
    if nodeIds.size != lastNodeCount then
      lastNodeCount = nodeIds.size
      fitCamera(1.0) // snap: a fresh graph frames correctly from its very first paint
      convergenceFollow = true // and stays framed while the simulation settles

  /** Each visible group's NODE members, ≥ 2 of them, sorted for stable colors
    * and determinism. A folded group has no visible members (its proxy is a
    * plain node) and drops out naturally. Nested groups hull their IMMEDIATE
    * members only — the outer hull does not yet absorb inner groups' nodes.
    */
  private def visibleClusters(g: ViewerGraph, nodeIds: Vector[NodeId]): Vector[Vector[NodeId]] =
    val visible     = nodeIds.toSet
    val memberOrder = nodeIds.zipWithIndex.toMap
    g.elements.memberships.toVector
      .collect:
        case (n: NodeId, gid) if gid != ViewerGraphElements.defaultRootId && visible.contains(n) => (gid, n)
      .groupBy(_._1)
      .toVector
      .sortBy(_._1.value)
      .map((_, pairs) => pairs.map(_._2).sortBy(memberOrder))
      .filter(_.size >= 2)

  def setSelection(sel: ElementIds): Unit =
    selectedNodes = sel.ids.collect { case n: NodeId => n }.toSet
    applySelection()

  /** Repaint every label texture with the current theme's colors; tint,
    * opacity and scale carry over untouched (same text, same font — same
    * aspect).
    */
  def repaintLabels(): Unit =
    sprites = sprites.map: (id, ns) =>
      // Keep the sprite's current aspect (which may be layout-dictated) —
      // a theme repaint changes colors, never geometry.
      val (texture, _) = paintLabel(ns.label, forcedAspect = dictatedSize(id).map((w, h) => w / h))
      ns.setMap(texture)
      ns.texture.dispose()
      id -> ns.copy(texture = texture)

  /** Switch layout algorithms. The new algorithm adopts the CURRENT state —
    * its sync sees a foreign algoId and re-adopts even though the graph is
    * unchanged — so the drawing morphs live from one shape to the other.
    */
  /** Trackpad navigation: two-finger scroll pans, pinch (ctrl/meta-wheel)
    * zooms — the 2D canvas's idiom. Off = mouse idiom: wheel zooms via
    * OrbitControls, drag orbits (drag orbits in both modes).
    */
  private var trackpadNav = true

  def setNavMode(trackpad: Boolean): Unit =
    trackpadNav = trackpad

  /** The dot engine runs only for a layout that declared it wants the flat
    * drawing — it is a full synchronous 2D layout, pure waste for the others.
    */
  private var lastViewerGraph: Option[ViewerGraph] = None

  private def hintsFor(g: ViewerGraph): Option[PlanarHints] =
    if algo.wantsPlanarHints then PlanarHints.fromViewerGraph(g) else None

  def setAlgorithm(algoId: String): Unit =
    val next = Layout3D.byId(algoId).getOrElse(ForceLayout3D)
    if next.id != baseAlgo.id then
      baseAlgo = next
      knobValuesV.set(defaultKnobValues(next))
      algo = next
      // Hints follow the algorithm: same topology, recomputed hints — so a
      // hints-hungry layout gets them on switch, and switching away drops
      // them (LayoutGraph equality then re-adopts, which animates the morph).
      val newGraph = layout.graph.copy(hints = lastViewerGraph.flatMap(hintsFor))
      layout = algo.sync(layout, newGraph)
      resizeSprites() // node geometry may be dictated by the new layout's hints
      // Segment allocation per stem depends on the layout's edge curves.
      rebuildLines()
      applySelection()

  /** What the sprite shows: the label's rendered LINES via LabelSummary — the
    * same interpreter the Elements list uses — so HTML and record labels
    * contribute their content instead of falling back to the id, and a
    * multi-line 2D box (BR-separated spans, record fields) stays multi-line
    * in 3D instead of flattening to one long string. The id remains the
    * fallback for empty labels and DOT's \N default.
    */
  private def displayLabel(g: ViewerGraph, id: NodeId, node: ViewerNode): Vector[String] =
    val raw = node.label.toString
    if raw.isEmpty || raw == "\\N" then Vector(id.value)
    else
      val ls = LabelSummary.lines(raw, isRecord = g.isRecordNode(id), maxLen = 40)
      if ls.isEmpty then Vector(id.value) else ls

  /** The node's world size when the current layout dictates geometry (the
    * hints' dot box, in points): with it, arrows meet the node exactly where
    * the flat drawing clipped them. None = label-derived pill sizing.
    */
  private def dictatedSize(id: NodeId): Option[(Double, Double)] =
    layout.graph.hints
      .flatMap(_.sizes.get(id))
      .map((w, h) => (w * DotPlanar3D.PtToWorld, h * DotPlanar3D.PtToWorld))

  /** Label-derived pill size: one line is NodeHeight tall, each further line
    * adds another — matching the 2D box's habit of growing downward — with
    * the width capped and the whole pill shrinking proportionally under it.
    */
  private def defaultSpriteSize(lines: Vector[String], aspect: Double): (Double, Double) =
    val height = NodeHeight * lines.size.max(1)
    val width  = math.min(height * aspect, MaxNodeWidth)
    (width, width / aspect)

  private def createSprite(id: NodeId, label: Vector[String]): NodeSprite =
    val dictated          = dictatedSize(id)
    val (texture, aspect) = paintLabel(label, forcedAspect = dictated.map((w, h) => w / h))
    val (w, h)            = dictated.getOrElse(defaultSpriteSize(label, aspect))
    dictated match
      case Some(_) =>
        // DoubleSide: the back of the sheet stays visible (mirrored, like
        // holding a printed page against the light) instead of vanishing.
        val material = three.MeshBasicMaterial(
          three.MeshBasicMaterial.params(color = 0xffffff, transparent = true, opacity = 1.0, depthWrite = true, side = 2)
        )
        material.map = texture
        material.needsUpdate = true
        val mesh = three.Mesh(planeGeometry, material)
        mesh.scale.set(w, h, 1)
        // Before the depth-only halos (renderOrder -1): a sheet already drawn
        // keeps its pixels — casings must cut edges, never nodes.
        mesh.renderOrder = -2
        mesh.userData("nodeId") = id.value
        nodesGroup.add(mesh)
        NodeSprite(
          mesh,
          texture,
          label,
          planar = true,
          setMap = t => { material.map = t; material.needsUpdate = true },
          setTint = (c, o) => { material.color.setHex(c); material.opacity = o },
          disposeMaterial = () => material.dispose()
        )
      case None =>
        val material = three.SpriteMaterial(three.SpriteMaterial.params(map = texture, transparent = true))
        val sprite   = three.Sprite(material)
        sprite.scale.set(w, h, 1)
        sprite.userData("nodeId") = id.value
        nodesGroup.add(sprite)
        NodeSprite(
          sprite,
          texture,
          label,
          planar = false,
          setMap = t => material.map = t,
          setTint = (c, o) => { material.color.setHex(c); material.opacity = o },
          disposeMaterial = () => material.dispose()
        )

  /** Re-fit every surviving sprite to the current layout's sizing regime —
    * called when the layout (and so possibly the hints) changed. The scale
    * check makes the no-change case free; a real change repaints the texture
    * at the new aspect so text never stretches.
    */
  private def resizeSprites(): Unit =
    sprites = sprites.map: (id, ns) =>
      val dictated = dictatedSize(id)
      if ns.planar != dictated.isDefined then
        // Presentation flips with the layout (billboard <-> in-plane sheet):
        // rebuild the node visual outright.
        disposeSprite(ns)
        id -> createSprite(id, ns.label)
      else
        val (w, h) = dictated.getOrElse(defaultSpriteSize(ns.label, paintLabelAspect(ns.label)))
        if math.abs(ns.obj.scale.x - w) < 1e-6 && math.abs(ns.obj.scale.y - h) < 1e-6 then id -> ns
        else
          val (texture, _) = paintLabel(ns.label, forcedAspect = Some(w / h))
          ns.setMap(texture)
          ns.texture.dispose()
          ns.obj.scale.set(w, h, 1)
          id -> ns.copy(texture = texture)

  /** The aspect paintLabel would produce unforced, without painting. */
  private def paintLabelAspect(lines: Vector[String]): Double =
    val fontPx  = 64.0
    val padX    = fontPx * 0.55
    val padY    = fontPx * 0.32
    val borderW = 4.0
    val canvas  = dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas]
    val ctx     = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
    ctx.font = s"${fontPx}px ${dom.window.getComputedStyle(dom.document.body).fontFamily}"
    val textWidth = lines.map(ctx.measureText(_).width).maxOption.getOrElse(0.0)
    val w         = math.ceil(textWidth + 2 * padX + 2 * borderW).max(1)
    val h         = math.ceil(fontPx * 1.25 * lines.size.max(1) + 2 * padY + 2 * borderW)
    w / h

  private def disposeSprite(ns: NodeSprite): Unit =
    nodesGroup.remove(ns.obj)
    ns.disposeMaterial()
    ns.texture.dispose()

  /** Draw the label as a rounded pill on a 2D canvas and wrap it in a texture.
    * Colors come from the daisyUI theme variables so 3D follows the app theme.
    * In-scene text (not a DOM overlay) on purpose: DOM overlays do not exist
    * inside an immersive WebXR session, and this canvas is on the VR path.
    */
  private def paintLabel(lines: Vector[String], forcedAspect: Option[Double] = None): (three.CanvasTexture, Double) =
    val fontPx  = 64.0
    val padX    = fontPx * 0.55
    val padY    = fontPx * 0.32
    val borderW = 4.0
    val lineH   = fontPx * 1.25
    val n       = lines.size.max(1)

    val canvas = dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas]
    val ctx    = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
    val font   = s"${fontPx}px ${dom.window.getComputedStyle(dom.document.body).fontFamily}"

    ctx.font = font
    val textWidth = lines.map(ctx.measureText(_).width).maxOption.getOrElse(0.0)
    canvas.height = math.ceil(lineH * n + 2 * padY + 2 * borderW).toInt
    // A forced aspect (a layout that dictates node geometry — the dot box the
    // splines were clipped against) fixes the canvas SHAPE; the text then
    // shrinks to fit rather than stretching with the texture.
    canvas.width = forcedAspect match
      case Some(aspect) => math.ceil(canvas.height * aspect).toInt.max(1)
      case None         => math.ceil(textWidth + 2 * padX + 2 * borderW).toInt.max(1)

    ctx.font = font // canvas state resets when the canvas is resized
    ctx.fillStyle = themeColor("--color-base-100", "#ffffff")
    ctx.strokeStyle = themeColor("--color-base-content", "#333333")
    ctx.lineWidth = borderW
    // A dictated aspect means the shape IS dot's clip box: near-square
    // corners, or edges clipped to the rectangle's corners point at rounded
    // air. Free-form pills keep their soft radius.
    val radius =
      if forcedAspect.isDefined then borderW * 2
      else math.min((canvas.height - borderW) / 4.0, lineH * 0.6)
    ctx.beginPath()
    ctx.asInstanceOf[js.Dynamic].roundRect(
      borderW / 2, borderW / 2, canvas.width - borderW, canvas.height - borderW, radius)
    ctx.fill()
    ctx.stroke()
    ctx.fillStyle = themeColor("--color-base-content", "#333333")
    ctx.textAlign = "center"
    ctx.textBaseline = "middle"
    val availW = canvas.width - 2 * borderW - fontPx * 0.3
    if textWidth > availW && availW > 1 then
      ctx.font = s"${fontPx * availW / textWidth}px ${dom.window.getComputedStyle(dom.document.body).fontFamily}"
    lines.zipWithIndex.foreach: (line, i) =>
      ctx.fillText(line, canvas.width / 2.0, canvas.height / 2.0 + (i - (n - 1) / 2.0) * lineH)

    val texture = three.CanvasTexture(canvas)
    texture.colorSpace = "srgb"
    (texture, canvas.width.toDouble / canvas.height.toDouble)

  private def themeColor(cssVar: String, fallback: String): String =
    val v = dom.window.getComputedStyle(dom.document.documentElement).getPropertyValue(cssVar).trim
    if v.isEmpty then fallback else v

  /** One stem mesh and one cone mesh per edge; geometry and materials are
    * shared, so a rebuild only creates/removes the meshes. Their transforms
    * are (re)written every layout step in writePositions.
    */
  private def rebuildLines(): Unit =
    stemMeshes.flatten.foreach(graphRoot.remove(_))
    stemJoints.flatten.foreach(graphRoot.remove(_))
    stemHalos.flatten.foreach(graphRoot.remove(_))
    coneMeshes.foreach(graphRoot.remove(_))
    stemMeshes = Vector.empty
    stemJoints = Vector.empty
    stemHalos = Vector.empty
    coneMeshes = Vector.empty
    if edges.nonEmpty then
      brightEdges = Array.fill(edges.size)(true)
      stemMeshes = edges.indices.toVector.map: i =>
        val segments = layout.edgeOffsets.lift(i).filter(_.size >= 2).map(_.size - 1).getOrElse(1)
        Vector.fill(segments):
          val stem = three.Mesh(stemGeometry, stemMaterial)
          stem.frustumCulled = false // transforms mutate every frame; skip stale-sphere culling
          graphRoot.add(stem)
          stem
      stemJoints = stemMeshes.map: chain =>
        Vector.fill(math.max(0, chain.size - 1)):
          val joint = three.Mesh(jointGeometry, stemMaterial)
          joint.frustumCulled = false
          graphRoot.add(joint)
          joint
      stemHalos = stemMeshes.map: chain =>
        chain.map: _ =>
          val halo = three.Mesh(stemGeometry, stemHaloMaterial)
          halo.frustumCulled = false
          // Depth-only pass runs before the visible stems, so a nearer casing
          // is already in the depth buffer when a farther stem draws.
          halo.renderOrder = -1
          graphRoot.add(halo)
          halo
      coneMeshes = edges.map: _ =>
        val cone = three.Mesh(coneGeometry, coneMaterial)
        graphRoot.add(cone)
        cone
      writeEdgeHighlight()

  /** Which luminance an edge gets: lighter toward the viewer, darker away —
    * read off the stored bow's sign, so it needs no knowledge of the layout.
    */
  private def altitudeMaterial(i: Int): three.MeshBasicMaterial =
    val apex = layout.edgeOffsets
      .lift(i)
      .filter(_.nonEmpty)
      .map(off => off.maxBy(o => math.abs(o.z)).z)
      .getOrElse(0.0)
    if apex > 1e-6 then stemFrontMaterial
    else if apex < -1e-6 then stemBackMaterial
    else stemMaterial

  /** Applies the current highlight to edges: with a selection, only edges
    * incident to a selected node stay opaque (they ARE the neighborhood) —
    * the rest swap to the faint material. Arrowheads follow their edge by
    * visibility.
    */
  private def writeEdgeHighlight(): Unit =
    for i <- edges.indices do
      val (s, t) = edges(i)
      brightEdges(i) = selectedNodes.isEmpty || selectedNodes.contains(s) || selectedNodes.contains(t)
      if i < stemMeshes.size then
        val material = if brightEdges(i) then altitudeMaterial(i) else stemDimMaterial
        stemMeshes(i).foreach(_.material = material)
        if i < stemJoints.size then stemJoints(i).foreach(_.material = material)

  /** One translucent hull mesh per cluster; the geometry is re-hulled on every
    * position write (QuickHull over tens of points — cheap), the mesh and
    * material persist per graph.
    */
  private def rebuildHulls(): Unit =
    hullMeshes.foreach: (mesh, material) =>
      graphRoot.remove(mesh)
      mesh.geometry.dispose()
      material.dispose()
    hullMeshes = clusterSets.zipWithIndex.map: (members, i) =>
      val material = three.MeshBasicMaterial(
        three.MeshBasicMaterial.params(
          color = HullPalette(i % HullPalette.size),
          transparent = true,
          opacity = 0.16, // reads dimmer against the dark environment than on paper
          depthWrite = false, // a veil, not a wall: nodes and lines behind stay visible
          side = 2            // DoubleSide, so the inside reads when the camera is within
        )
      )
      val mesh = three.Mesh(hullGeometry(members), material)
      graphRoot.add(mesh)
      (mesh, material)

  /** Members' positions padded with a small octahedron each, so 2-member and
    * coplanar clusters (every same-rank pair in the layered layout) still span
    * a volume QuickHull accepts.
    */
  private def hullGeometry(members: Vector[NodeId]): three.ConvexGeometry =
    val pts = js.Array[three.Vector3]()
    for
      m <- members
      p <- layout.positions.get(m)
      (dx, dy, dz) <- Seq(
        (HullPad, 0.0, 0.0), (-HullPad, 0.0, 0.0),
        (0.0, HullPad, 0.0), (0.0, -HullPad, 0.0),
        (0.0, 0.0, HullPad), (0.0, 0.0, -HullPad)
      )
    do pts.push(three.Vector3().set(p.x + dx, p.y + dy, p.z + dz))
    three.ConvexGeometry(pts)

  private def updateHulls(): Unit =
    hullMeshes.zip(clusterSets).foreach:
      case ((mesh, _), members) =>
        val old = mesh.geometry
        mesh.geometry = hullGeometry(members)
        old.dispose()

  private def writePositions(): Unit =
    sprites.foreach: (id, ns) =>
      layout.positions.get(id).foreach(p => ns.obj.position.set(p.x, p.y, p.z))
    for i <- edges.indices do
      val (s, t) = edges(i)
      for sp <- layout.positions.get(s); tp <- layout.positions.get(t) do
        val chain = if i < stemMeshes.size then stemMeshes(i) else Vector.empty
        val path  = curvedPath(i, sp, tp)
        if path.size >= 2 && chain.size == path.size - 1 then
          // Curved edge: segments trace the sampled path. No pill inset —
          // a layout that provides paths has already clipped them at the
          // node borders (dot splines start and end there).
          val lastD   = path.last - path(path.size - 2)
          val lastLen = lastD.length
          val coneOn  = lastLen >= 1e-6 && brightEdges(i)
          // The stem must stop at the cone's BASE: run it to the tip and it
          // pierces the taper near the point, poking out of the head's sides.
          val drawPath =
            if coneOn then
              val dir  = lastD * (1.0 / lastLen)
              val trim = math.min(ConeLen, lastLen * 0.9)
              path.updated(path.size - 1, path.last - dir * trim)
            else path
          for k <- chain.indices do placeStemSegment(chain(k), drawPath(k), drawPath(k + 1))
          // Round joins: a sphere at each interior point closes the notch
          // exact-length segments open on the outside of every bend.
          if i < stemJoints.size then
            val joints = stemJoints(i)
            for k <- joints.indices do
              val p = drawPath(k + 1)
              joints(k).visible = true
              joints(k).position.set(p.x, p.y, p.z)
              joints(k).scale.set(StemRadius, StemRadius, StemRadius)
          // Arrowhead aimed along the final segment; the cone's tip (half its
          // height from center) lands ON the path's endpoint.
          if i < coneMeshes.size then
            val cone = coneMeshes(i)
            if !coneOn then cone.visible = false
            else
              cone.visible = true
              val dir = lastD * (1.0 / lastLen)
              val tip = path.last
              cone.position.set(tip.x - dir.x * ConeTipInset, tip.y - dir.y * ConeTipInset, tip.z - dir.z * ConeTipInset)
              cone.quaternion.setFromUnitVectors(coneUp, coneDir.set(dir.x, dir.y, dir.z))
        else
          val d   = tp - sp
          val len = d.length
          chain.headOption.foreach: stem =>
            if len < 1e-6 then stem.visible = false
            else
              stem.visible = true
              val dir = d * (1.0 / len)
              // Stop short of both pills: the hairline vanished into a label
              // unnoticed, but a stem with real girth crossing INTO the pill
              // reads as a smudge on the text. On the target side, stop at
              // the CONE'S BASE — run to the tip and the stem pokes out of
              // the head's taper.
              val insetSrc = math.min(len * 0.3, NodeHeight * 0.4)
              val insetTgt =
                if brightEdges(i) then math.min(len * 0.5, NodeHeight * 0.55) + ConeTipInset
                else insetSrc
              val a = sp + dir * insetSrc
              val b = tp - dir * insetTgt
              // The unit cylinder is y-aligned and centered: midpoint + length
              // scale + the same aim quaternion the cone uses.
              stem.position.set((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2)
              stem.scale.set(StemRadius, math.max(1e-3, len - insetSrc - insetTgt), StemRadius)
              stem.quaternion.setFromUnitVectors(coneUp, coneDir.set(dir.x, dir.y, dir.z))
          // Allocation raced a state change (chain sized for another layout):
          // hide the rest until the pending rebuild lands.
          chain.drop(1).foreach(_.visible = false)
          if i < stemJoints.size then stemJoints(i).foreach(_.visible = false)
          // Arrowhead: a cone just short of the target pill, aimed along the edge.
          if i < coneMeshes.size then
            val cone = coneMeshes(i)
            if len < 1e-6 || !brightEdges(i) then cone.visible = false
            else
              cone.visible = true
              val dir  = d * (1.0 / len)
              val back = math.min(len * 0.5, NodeHeight * 0.55)
              cone.position.set(tp.x - dir.x * back, tp.y - dir.y * back, tp.z - dir.z * back)
              cone.quaternion.setFromUnitVectors(coneUp, coneDir.set(dir.x, dir.y, dir.z))
    updateHulls()

  /** The sampled 3D path of edge `i` for the CURRENT node positions: chord
    * between the live endpoints plus the layout's stored offsets (see
    * LayoutState3D.edgeOffsets). Empty = straight stem.
    */
  private def curvedPath(i: Int, sp: Vec3, tp: Vec3): Vector[Vec3] =
    layout.edgeOffsets.lift(i).filter(_.size >= 2) match
      case Some(off) =>
        val n = off.size
        Vector.tabulate(n)(j => sp + (tp - sp) * (j.toDouble / (n - 1)) + off(j))
      case None => Vector.empty

  private def placeStemSegment(stem: three.Mesh, p: Vec3, q: Vec3): Unit =
    val d   = q - p
    val len = d.length
    if len < 1e-6 then stem.visible = false
    else
      stem.visible = true
      val dir = d * (1.0 / len)
      stem.position.set((p.x + q.x) / 2, (p.y + q.y) / 2, (p.z + q.z) / 2)
      // Exact length: the round join at each interior point is a sphere
      // (stemJoints) — overlength here read as a bulge at every bend.
      stem.scale.set(StemRadius, len, StemRadius)
      stem.quaternion.setFromUnitVectors(coneUp, coneDir.set(dir.x, dir.y, dir.z))

  /** Selection tint plus neighborhood focus: with a selection, everything not
    * selected or adjacent fades — in 3D, where occlusion is constant, dimming
    * the rest is what makes a selection findable at all.
    */
  private def applySelection(): Unit =
    val neighborhood: Option[Set[NodeId]] =
      if selectedNodes.isEmpty then None
      else
        Some(
          selectedNodes ++ edges.flatMap: (s, t) =>
            if selectedNodes.contains(s) then s :: t :: Nil
            else if selectedNodes.contains(t) then s :: t :: Nil
            else Nil
        )
    sprites.foreach: (id, ns) =>
      ns.setTint(
        if selectedNodes.contains(id) then SelectedTint else NormalTint,
        if neighborhood.forall(_.contains(id)) then 1.0 else DimOpacity
      )
    writeEdgeHighlight()
    writePositions() // cone visibility follows brightEdges

  /** Camera field of view, degrees (vertical) — mirrored here for fit math. */
  private val CameraFovDeg  = 50.0
  private val DefaultCamDir = Vec3(0.62, 0.4, 0.62)

  /** Mirrors the toolbar's Auto toggle (the same Var the 2D canvas honors).
    * ON: the fit runs every frame — the user owns the ROTATION, the fit owns
    * target and distance, so every part of the drawing stays visible through
    * orbits, morphs and simulation alike. OFF (the default): only a graph
    * load frames. The mount-time binder overwrites this with the Var's value;
    * the initial only matters for the frames before that, so it matches.
    */
  private var autoFitOn = false

  /** With Auto off, a fresh graph still has to END UP framed, not merely start
    * framed: the load-time snap fit sees iteration 0, and the simulation then
    * expands well past that frame while it converges. So the follow runs
    * transiently from load until the first convergence, then the camera is the
    * user's. A user zoom mid-convergence cancels it (same contract as Auto).
    */
  private var convergenceFollow = false

  def setAutoFit(on: Boolean): Unit = autoFitOn = on

  /** One-shot exact fit — the toolbar's Fit button. */
  def fitNow(): Unit = fitCamera(1.0)

  /** Snap orthogonal to the drawing plane: view straight down −z with a level
    * horizon, then exact-fit. The trackball's freedom means there is no force
    * returning the camera to level — this is the way home, and the natural
    * reading view for the planar layout.
    */
  def faceFront(): Unit =
    camUp = Vec3(0, 1, 0)
    val camP = Vec3(camera.position.x, camera.position.y, camera.position.z)
    val dist = math.max(1e-9, (camP - orbitTarget).length)
    camera.position.set(orbitTarget.x, orbitTarget.y, orbitTarget.z + dist)
    applyCameraPose()
    fitCamera(1.0)

  /** The depth-cue fog, re-anchored to the drawing EVERY frame: near at the
    * drawing's current front face, far one depth-spread behind its back face,
    * so the fade discriminates within the drawing (front crisp, back at most
    * ~50%) and never punishes zoom — dollying out shrinks nodes but does not
    * dim them. Anchoring only at fit time froze the band at the fitted
    * distance, and a manual zoom-out (Auto off) pushed the whole drawing past
    * it into invisibility.
    */
  private def updateFog(): Unit =
    if layout.positions.nonEmpty then
      locally:
        val camP = Vec3(camera.position.x, camera.position.y, camera.position.z)
        val toT  = orbitTarget - camP
        val view = toT * (1.0 / math.max(1e-9, toT.length))
        var minD = Double.MaxValue
        var maxD = -Double.MaxValue
        layout.positions.values.foreach: p =>
          val d = (localToWorld(p) - camP).dot(view)
          minD = math.min(minD, d)
          maxD = math.max(maxD, d)
        val spread = math.max(1.0, maxD - minD)
        scene.fog.near = math.max(0.1, minD)
        scene.fog.far = maxD + spread

  /** Screen basis for an orbit pose: `dir` is target→camera; view = where the
    * camera looks, right/up span the screen. Derived from the ROLLING camUp —
    * with trackball rotation there is no world up to reference.
    */
  private def basisFor(dir: Vec3): (Vec3, Vec3, Vec3) =
    val view   = dir * -1.0
    val ref    = if math.abs(view.dot(camUp)) > 0.99 then Vec3(0, 0, 1) else camUp
    val right0 = view.cross(ref) // same convention as rotateBy: right = view × up
    val right =
      if right0.length < 1e-9 then Vec3(1, 0, 0)
      else right0 * (1.0 / right0.length)
    val up = right.cross(view)
    (right, up, view)

  /** Frame the drawing the way the 2D canvas fits a diagram: as large as the
    * viewport allows while fully visible. Not a bounding-sphere heuristic —
    * that leaves acres of slack around anything non-spherical (a wide, shallow
    * dependency graph most of all). Instead every node is projected into the
    * camera's basis, each with its OWN pill half-extents, and the distance is
    * solved exactly: for a pill edge at lateral offset x and depth offset z,
    * visibility requires |x| <= (D + z)·tanθ, so D = max(|x|/tanθ − z) over
    * both axes of every pill. The outermost pill ends up touching the frustum.
    *
    * `alpha` lerps toward that framing, so calling this every animation frame
    * makes the camera FOLLOW the converging layout instead of jumping after
    * it; alpha = 1 snaps (fresh graph load). The fog rides along: clear
    * through the drawing's front face, fading behind it.
    */
  private def fitCamera(alpha: Double): Unit =
    if layout.positions.nonEmpty && !renderer.xr.isPresenting then
      locally:
        val target0 = orbitTarget
        val camP    = Vec3(camera.position.x, camera.position.y, camera.position.z)
        val dirRaw  = camP - target0
        val dir = // target -> camera, the orbit direction we preserve
          if dirRaw.length < 1e-6 then DefaultCamDir * (1.0 / DefaultCamDir.length)
          else dirRaw * (1.0 / dirRaw.length)

        val (right, up, view) = basisFor(dir)

        var minX = Double.MaxValue; var minY = Double.MaxValue; var minZ = Double.MaxValue
        var maxX = -Double.MaxValue; var maxY = -Double.MaxValue; var maxZ = -Double.MaxValue
        layout.positions.values.foreach: p =>
          minX = math.min(minX, p.x); maxX = math.max(maxX, p.x)
          minY = math.min(minY, p.y); maxY = math.max(maxY, p.y)
          minZ = math.min(minZ, p.z); maxZ = math.max(maxZ, p.z)
        val roughCenter = Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2)

        def halfW(nodeId: NodeId) =
          sprites.get(nodeId).map(_.obj.scale.x / 2).getOrElse(NodeHeight) + NodeHeight * 0.25
        def halfH(nodeId: NodeId) =
          sprites.get(nodeId).map(_.obj.scale.y / 2).getOrElse(NodeHeight) + NodeHeight * 0.25

        // Exact perspective-correct fit, solved per screen axis. Each pill
        // imposes two one-sided visibility constraints on the camera axis:
        //   right edge:  x + hw − c ≤ (D + z)·tanθ   →  D ≥ a − c/tanθ
        //   left edge:  −(x − hw − c) ≤ (D + z)·tanθ →  D ≥ b + c/tanθ
        // with a = (x+hw)/tanθ − z and b = (−x+hw)/tanθ − z. The minimal
        // distance is D = (max a + max b)/2 at axis offset c = tanθ·(max a −
        // max b)/2 — which centers the PROJECTED image, magnification and
        // asymmetric pill widths included, where centering the 3D bbox
        // (measured: 0.18 NDC off) never could.
        val tanV = math.tan(math.toRadians(CameraFovDeg / 2))
        val tanH = tanV * math.max(0.3, camera.aspect)

        // (maxAX, maxBX, maxAY, maxBY): the four one-sided constraint maxima
        // about a given axis point; D = (maxA + maxB)/2 per axis is the
        // minimal distance with the axis offset (maxA − maxB)/2 centering it.
        def solveAbout(c: Vec3): (Double, Double, Double, Double) =
          var aX = Double.MinValue; var bX = Double.MinValue
          var aY = Double.MinValue; var bY = Double.MinValue
          layout.positions.foreach: (nodeId, p) =>
            val rel = p - c
            val x   = rel.dot(right)
            val y   = rel.dot(up)
            val z   = rel.dot(view) // positive = farther from the camera
            aX = math.max(aX, (x + halfW(nodeId)) / tanH - z)
            bX = math.max(bX, (-x + halfW(nodeId)) / tanH - z)
            aY = math.max(aY, (y + halfH(nodeId)) / tanV - z)
            bY = math.max(bY, (-y + halfH(nodeId)) / tanV - z)
          (aX, bX, aY, bY)

        val (aX1, bX1, aY1, bY1) = solveAbout(roughCenter)
        val center1 =
          roughCenter + right * (tanH * (aX1 - bX1) / 2) + up * (tanV * (aY1 - bY1) / 2)
        val dist1 = math.max(2.0, math.max((aX1 + bX1) / 2, (aY1 + bY1) / 2))

        // Refinement: equal world-space slack is not equal PROJECTED slack
        // when the extreme pills sit at different depths (measured 0.14 NDC of
        // residual). Project everything from the candidate camera, shift the
        // aim by the measured NDC offset, then re-solve the distance about the
        // shifted center so the tightened side cannot clip.
        val camC = center1 + dir * dist1
        var minNX = Double.MaxValue; var maxNX = -Double.MaxValue
        var minNY = Double.MaxValue; var maxNY = -Double.MaxValue
        layout.positions.foreach: (nodeId, p) =>
          val rel = p - camC
          val dz  = math.max(0.1, rel.dot(view))
          val nx  = rel.dot(right) / (dz * tanH)
          val ny  = rel.dot(up) / (dz * tanV)
          val hw  = halfW(nodeId) / (dz * tanH)
          val hh  = halfH(nodeId) / (dz * tanV)
          minNX = math.min(minNX, nx - hw); maxNX = math.max(maxNX, nx + hw)
          minNY = math.min(minNY, ny - hh); maxNY = math.max(maxNY, ny + hh)
        val center =
          center1 +
            right * ((minNX + maxNX) / 2 * dist1 * tanH) +
            up * ((minNY + maxNY) / 2 * dist1 * tanV)
        val (aX2, bX2, aY2, bY2) = solveAbout(center)
        val fitDist =
          math.max(2.0, math.max((aX2 + bX2) / 2, (aY2 + bY2) / 2)) * 1.04

        val newTarget = target0 + (center - target0) * alpha
        val newDist   = dirRaw.length + (fitDist - dirRaw.length) * alpha
        orbitTarget = newTarget
        camera.position.set(
          newTarget.x + dir.x * newDist,
          newTarget.y + dir.y * newDist,
          newTarget.z + dir.z * newDist
        )
        applyCameraPose()

  // ---------------- lifecycle ----------------

  def start(container: dom.Element): Unit =
    renderer.setPixelRatio(dom.window.devicePixelRatio)
    renderer.domElement.style.display = "block"
    container.appendChild(renderer.domElement)

    attachWheelNavigation()

    resize(container)
    val observer = dom.ResizeObserver((_, _) => resize(container))
    observer.observe(container)
    resizeObserverOpt = Some(observer)

    attachPointerHandlers()
    setupXR()
    renderer.setAnimationLoop(frame)
    // Dev-only introspection, off unless localStorage["gx3d-debug"] is set:
    // drives the simulation to completion synchronously and exposes state.
    // Exists because animation cannot be verified through a background pane —
    // rAF freezes there — and this handle has been re-invented for every 3D
    // debugging session so far.
    if dom.window.localStorage.getItem("gx3d-debug") != null then
      dom.window.asInstanceOf[js.Dynamic].updateDynamic("__gx3d")(
        js.Dynamic.literal(
          scene = scene,
          camera = camera,
          info = () =>
            s"algo=${algo.id} done=${layout.done} temp=${layout.temperature} iter=${layout.iteration} " +
              s"pinned=${layout.pinned.mkString(",")} autoFit=$autoFitOn",
          posOf = (id: String) =>
            layout.positions.get(NodeId(id)).map(p => js.Array(p.x, p.y, p.z)).getOrElse(js.Array[Double]()),
          settle = () =>
            while !layout.done do layout = algo.step(layout)
            writePositions()
            // Same policy as the animation loop: the convergence follow frames
            // the settled drawing exactly once, then hands the camera over.
            if autoFitOn || convergenceFollow then fitCamera(1.0)
            convergenceFollow = false
            updateFog()
            renderer.render(scene, camera)
        )
      )

  /** WebXR is a progressive enhancement of this ordinary WebGL page: the
    * Enter-VR button only materializes when the browser reports a device that
    * can run an immersive-vr session (visionOS Safari says yes; desktops say
    * no and see nothing). The camera is the headset's during a session, so the
    * only spatial work is placing the graph at a comfortable spot: WebXR's
    * origin is the floor, so the drawing lifts to chest height, arm-and-a-half
    * away, scaled so its radius reads about a meter.
    */
  private def setupXR(): Unit =
    renderer.xr.enabled = true
    renderer.xr.addEventListener(
      "sessionstart",
      _ =>
        vrPresenting.set(true)
        enterXRLayout()
    )
    renderer.xr.addEventListener(
      "sessionend",
      _ =>
        xrSessionOpt = None
        vrPresenting.set(false)
        exitXRLayout()
    )
    // On visionOS every pinch is a transient input source whose target ray is
    // the gaze at pinch time — three surfaces them through the controller
    // slots, so binding both covers one- and two-handed pinches.
    bindController(0)
    bindController(1)
    val navXr = js.Dynamic.global.navigator.selectDynamic("xr")
    if !js.isUndefined(navXr) then
      navXr
        .applyDynamic("isSessionSupported")("immersive-vr")
        .asInstanceOf[js.Promise[Boolean]]
        .foreach(supported => if supported then vrSupported.set(true))

  /** Start or end the immersive session. The request happens synchronously
    * inside the button click — WebXR only grants sessions from a user
    * activation. The same session-init features three's own button asks for.
    */
  def toggleVR(): Unit =
    xrSessionOpt match
      case Some(session) =>
        session.applyDynamic("end")()
        () // cleanup happens in the sessionend listener
      case None =>
        val sessionInit = js.Dynamic.literal(
          optionalFeatures = js.Array("local-floor", "bounded-floor", "hand-tracking", "layers")
        )
        js.Dynamic.global.navigator.xr
          .applyDynamic("requestSession")("immersive-vr", sessionInit)
          .asInstanceOf[js.Promise[js.Dynamic]]
          .foreach: session =>
            xrSessionOpt = Some(session)
            renderer.xr.setSession(session)

  private def enterXRLayout(): Unit =
    val radius = layout.positions.values.map(_.length).maxOption.getOrElse(1.0)
    val s      = math.min(1.0, 1.1 / math.max(0.5, radius))
    graphRoot.scale.set(s, s, s)
    graphRoot.position.set(0, 1.3, -1.6)

  private def exitXRLayout(): Unit =
    graphRoot.scale.set(1, 1, 1)
    graphRoot.position.set(0, 0, 0)

  // ------------- VR pinch: a quick pinch selects, a held pinch drags -------------

  private var vrDrag: Option[(three.Object3D, NodeId, Double)] = None // controller, node, grab distance
  private var vrStartDirection = Vec3.zero

  private def bindController(index: Int): Unit =
    val controller = renderer.xr.getController(index)
    scene.add(controller)
    controller.addEventListener("selectstart", _ => vrSelectStart(controller))
    controller.addEventListener("selectend", _ => vrSelectEnd(controller))

  private def setRayFromController(controller: three.Object3D): Unit =
    tempMatrix.identity().extractRotation(controller.matrixWorld)
    raycaster.ray.origin.setFromMatrixPosition(controller.matrixWorld)
    raycaster.ray.direction.set(0, 0, -1).applyMatrix4(tempMatrix)

  private def vrSelectStart(controller: three.Object3D): Unit =
    setRayFromController(controller)
    vrStartDirection = rayDirection
    raycaster
      .intersectObjects(nodesGroup.children, recursive = false)
      .headOption
      .foreach: hit =>
        hit.hitObject.userData
          .get("nodeId")
          .foreach(raw => vrDrag = Some((controller, NodeId(raw.asInstanceOf[String]), hit.distance)))

  /** Pinch released. If the ray barely turned it was a CLICK — toggle the node
    * (deliberately no clear-on-miss in VR: with gaze-driven rays a stray pinch
    * is common, and losing a selection to one would be maddening). A ray that
    * travelled was a drag; just release the pin.
    */
  private def vrSelectEnd(controller: three.Object3D): Unit =
    vrDrag match
      case Some((c, id, _)) if c eq controller =>
        vrDrag = None
        setRayFromController(controller)
        val wasClick = vrStartDirection.dot(rayDirection) > 0.9985 // ray turned < ~3 degrees
        releasePins()
        if wasClick then state.selection.toggle(id)
      case _ => ()

  private def frame(@annotation.unused time: Double): Unit =
    // A live VR drag follows the (gaze) ray at the grab distance, every frame.
    vrDrag.foreach: (controller, id, dist) =>
      setRayFromController(controller)
      pinNodeAt(id, worldToLocal(rayOrigin + rayDirection * dist))
    if !layout.done then
      var s = 0
      while s < StepsPerFrame && !layout.done do
        layout = algo.step(layout)
        s += 1
      writePositions()
    // With Auto on, the framing follows the drawing CONTINUOUSLY — through
    // simulation, morphs, and the user's own orbiting (rotation is theirs;
    // target and distance are the fit's). Suspended during a node drag: the
    // grab-plane mapping assumes a still camera, and a creeping fit would
    // slide the node off the pointer.
    if (autoFitOn || convergenceFollow) && mouseDragNode.isEmpty && vrDrag.isEmpty then
      fitCamera(0.2)
      // The transient follow ends at convergence — with one exact fit, since
      // the soft alpha above always trails the target by a step or two.
      if convergenceFollow && layout.done then
        fitCamera(1.0)
        convergenceFollow = false
    updateFog()
    updateStemHalos()
    renderer.render(scene, camera)
    // The corner viewport pass makes no sense inside a headset.
    if !renderer.xr.isPresenting then renderGizmo()

  /** Place each stem's depth-only casing: same pose, fattened radially, and
    * pushed one small step AWAY from the camera — far enough that the casing
    * never clips its own stem, near enough that a crossing edge one bow-level
    * deeper always is. View-dependent, so it runs per frame, not per layout
    * step.
    */
  private def updateStemHalos(): Unit =
    val vd = cameraDirection()
    var i  = 0
    while i < stemMeshes.size do
      val chain = stemMeshes(i)
      val halos = if i < stemHalos.size then stemHalos(i) else Vector.empty
      var k     = 0
      while k < chain.size && k < halos.size do
        val stem = chain(k)
        val halo = halos(k)
        halo.visible = stem.visible
        if stem.visible then
          halo.position.set(
            stem.position.x + vd.x * HaloDepthOffset,
            stem.position.y + vd.y * HaloDepthOffset,
            stem.position.z + vd.z * HaloDepthOffset
          )
          halo.quaternion.copy(stem.quaternion)
          halo.scale.set(stem.scale.x * HaloRadiusFactor, stem.scale.y, stem.scale.z * HaloRadiusFactor)
        k += 1
      i += 1

  /** A hidden or not-yet-painted pane reports zero sizes; sizing the renderer
    * from those would bake a 0×0 viewport. Skip — the observer fires again when
    * real dimensions arrive.
    */
  private def resize(container: dom.Element): Unit =
    val w = container.clientWidth.toDouble
    val h = container.clientHeight.toDouble
    if w > 0 && h > 0 then
      renderer.setSize(w, h)
      camera.aspect = w / h
      camera.updateProjectionMatrix()

  // ---------------- desktop pointer: click selects, drag tugs ----------------

  /** Fraction of k the simulation is kept warmed to while a drag is live, so
    * neighbors visibly respond to the tug.
    */
  private val DragHeat      = 0.3
  private val ClickSlopPx   = 5.0
  private var mouseDragNode: Option[NodeId] = None
  private var dragPlanePoint  = Vec3.zero // world-space grab point
  private var dragPlaneNormal = Vec3.zero // camera direction at grab
  private var downX = 0.0
  private var downY = 0.0

  /** Write the pose: position is wherever the caller put it; up and lookAt
    * complete the orientation. Every camera mutation funnels through here.
    */
  private def applyCameraPose(): Unit =
    camera.up.set(camUp.x, camUp.y, camUp.z)
    camera.lookAt(orbitTarget.x, orbitTarget.y, orbitTarget.z)

  /** Trackball rotation about the target: yaw about the camera's own up,
    * pitch about its right — both the offset AND the up-vector rotate, so
    * there is no privileged axis, no pole, no clamp. Sensitivity matches the
    * old orbit (2π per canvas-height of travel); `mx/my` are pointer-movement
    * pixels (drag direction; wheel deltas negate into this under natural
    * scrolling).
    */
  private def rotateBy(mx: Double, my: Double): Unit =
    val camP   = Vec3(camera.position.x, camera.position.y, camera.position.z)
    val offset = camP - orbitTarget
    val h      = math.max(1, renderer.domElement.clientHeight).toDouble
    val view   = offset * (-1.0 / math.max(1e-9, offset.length))
    val right0 = view.cross(camUp)
    val right  = right0 * (1.0 / math.max(1e-9, right0.length))
    val up     = right.cross(view) // orthonormalized true screen-up
    val yaw    = -2 * math.Pi * mx / h
    val pitch  = -2 * math.Pi * my / h
    val off1   = offset.rotatedAround(up, yaw)
    val off2   = off1.rotatedAround(right, pitch)
    camUp = up.rotatedAround(right, pitch)
    camera.position.set(orbitTarget.x + off2.x, orbitTarget.y + off2.y, orbitTarget.z + off2.z)
    applyCameraPose()

  /** Translate camera AND target in the screen plane — the 2D canvas's pan,
    * in 3D. `mx/my` are pointer-movement pixels; content follows the pointer
    * (grab semantics), scaled so a pixel of pointer travel moves the drawing
    * one pixel at the target's distance. A pan states framing intent exactly
    * like a zoom, so it disengages Auto and the convergence follow.
    */
  private def panBy(mx: Double, my: Double): Unit =
    convergenceFollow = false
    if state.autoFit.now() then state.autoFit.set(false)
    val camP    = Vec3(camera.position.x, camera.position.y, camera.position.z)
    val offset  = camP - orbitTarget
    val dist    = math.max(1e-9, offset.length)
    val h       = math.max(1, renderer.domElement.clientHeight).toDouble
    val wpp     = 2 * dist * math.tan(CameraFovDeg / 2 * math.Pi / 180) / h // world per pixel at target depth
    val view    = offset * (-1.0 / dist)
    val right0  = view.cross(camUp)
    val right   = right0 * (1.0 / math.max(1e-9, right0.length))
    val up      = right.cross(view)
    // Content follows the pointer: the CAMERA moves the other way.
    val move = right * (-mx * wpp) + up * (my * wpp)
    orbitTarget = orbitTarget + move
    camera.position.set(camP.x + move.x, camP.y + move.y, camP.z + move.z)
    applyCameraPose()

  /** Zoom about the point under the POINTER, not the orbit target: scale the
    * whole camera rig (camera AND target) about the world point the cursor is
    * on, at the target's depth. That point stays screen-fixed — the map-app
    * zoom idiom — the view direction is untouched (the rig scales, it does
    * not turn), and the orbit target drifts toward where the user is zooming,
    * which is exactly where the next rotation should pivot.
    */
  private def dollyBy(factor: Double, e: dom.MouseEvent): Unit =
    // Only user gestures dolly, and a user zoom owns the framing from here on:
    // it cancels the load-time convergence follow just as it disengages Auto.
    convergenceFollow = false
    val camP   = Vec3(camera.position.x, camera.position.y, camera.position.z)
    val offset = camP - orbitTarget
    val dist   = math.max(1e-9, offset.length)
    // Clamp on the resulting distance, then rescale the factor to honor it.
    val nd = math.min(400.0, math.max(0.6, dist * factor))
    val f  = nd / dist
    // The anchor: pointer ray ∩ the screen-parallel plane through the target.
    // Content near that depth stays exactly under the cursor; the fallback
    // (grazing ray) degrades to a plain centered dolly.
    setRayFromPointer(e)
    val view   = offset * (-1.0 / dist)
    val anchor = planeIntersect(orbitTarget, view).getOrElse(orbitTarget)
    orbitTarget = anchor + (orbitTarget - anchor) * f
    camera.position.set(
      anchor.x + (camP.x - anchor.x) * f,
      anchor.y + (camP.y - anchor.y) * f,
      anchor.z + (camP.z - anchor.z) * f
    )
    applyCameraPose()

  /** Wheel: pinch (ctrl/meta) dollies in both modes; ⌥ (alt) PANS in both
    * modes; a plain wheel dollies in mouse mode and ROTATES in trackpad mode
    * — the drag gesture without the click, finger direction matching drag
    * direction under natural scrolling. ⌥ rather than shift: browsers remap
    * shift+wheel to horizontal scroll for mice, mangling the deltas.
    */
  private def attachWheelNavigation(): Unit =
    renderer.domElement.addEventListener(
      "wheel",
      (e: dom.WheelEvent) =>
        if !renderer.xr.isPresenting then
          e.preventDefault()
          if e.altKey then panBy(-e.deltaX, -e.deltaY)
          else if e.ctrlKey || e.metaKey || !trackpadNav then
            // A zoom states an intent about framing that Auto's per-frame
            // re-fit would immediately undo — so zooming disengages Auto
            // (the toolbar button follows, via the shared Var). Rotation
            // stays compatible with Auto and leaves it alone.
            if state.autoFit.now() then state.autoFit.set(false)
            dollyBy(math.exp(e.deltaY * 0.01), e)
          else rotateBy(-e.deltaX, -e.deltaY)
    )

  /** Pointer down on a node begins a drag (orbit is suspended for its
    * duration); on empty space it is the start of an orbit. Which one it was
    * only becomes a CLICK at pointer-up, if the pointer never travelled.
    */
  private def attachPointerHandlers(): Unit =
    renderer.domElement.addEventListener(
      "pointerdown",
      (e: dom.PointerEvent) =>
        downX = e.clientX
        downY = e.clientY
        setRayFromPointer(e)
        hitNodeId() match
          case Some(id) =>
            mouseDragNode = Some(id)
            dragPlanePoint = localToWorld(layout.positions.getOrElse(id, Vec3.zero))
            dragPlaneNormal = cameraDirection()
            renderer.domElement.asInstanceOf[js.Dynamic].setPointerCapture(e.pointerId)
          case None =>
            // empty space: the drag is an orbit
            mouseOrbitLast = Some((e.clientX, e.clientY))
            renderer.domElement.asInstanceOf[js.Dynamic].setPointerCapture(e.pointerId)
    )
    renderer.domElement.addEventListener(
      "pointermove",
      (e: dom.PointerEvent) =>
        mouseDragNode.foreach: id =>
          setRayFromPointer(e)
          // The node slides in the camera-facing plane through its grab point:
          // the one mapping from a 2D pointer to 3D that never surprises.
          planeIntersect(dragPlanePoint, dragPlaneNormal).foreach: world =>
            pinNodeAt(id, worldToLocal(world))
        mouseOrbitLast.foreach: (lx, ly) =>
          // ⌥-drag pans, plain drag orbits — the same split as the wheel.
          if e.altKey then panBy(e.clientX - lx, e.clientY - ly)
          else rotateBy(e.clientX - lx, e.clientY - ly)
          mouseOrbitLast = Some((e.clientX, e.clientY))
    )
    renderer.domElement.addEventListener(
      "pointerup",
      (e: dom.PointerEvent) =>
        val wasClick = math.hypot(e.clientX - downX, e.clientY - downY) < ClickSlopPx
        val additive = e.shiftKey || e.metaKey
        mouseOrbitLast = None
        mouseDragNode match
          case Some(id) =>
            mouseDragNode = None
            releasePins()
            if wasClick then
              if additive then state.selection.toggle(id) else state.selection.set2(id)
          case None =>
            if wasClick && !additive then state.selection.set(ElementIds())
    )

  private def setRayFromPointer(e: dom.MouseEvent): Unit =
    val rect = renderer.domElement.getBoundingClientRect()
    val ndcX = (e.clientX - rect.left) / rect.width * 2 - 1
    val ndcY = -((e.clientY - rect.top) / rect.height * 2 - 1)
    raycaster.setFromCamera(pointerNdc.set(ndcX, ndcY), camera)

  /** First node the current raycaster ray hits. */
  private def hitNodeId(): Option[NodeId] =
    raycaster
      .intersectObjects(nodesGroup.children, recursive = false)
      .headOption
      .flatMap(_.hitObject.userData.get("nodeId"))
      .map(raw => NodeId(raw.asInstanceOf[String]))

  private def rayOrigin: Vec3 =
    Vec3(raycaster.ray.origin.x, raycaster.ray.origin.y, raycaster.ray.origin.z)

  private def rayDirection: Vec3 =
    Vec3(raycaster.ray.direction.x, raycaster.ray.direction.y, raycaster.ray.direction.z)

  private def cameraDirection(): Vec3 =
    camera.getWorldDirection(scratchVec)
    Vec3(scratchVec.x, scratchVec.y, scratchVec.z)

  private def planeIntersect(p0: Vec3, n: Vec3): Option[Vec3] =
    val o     = rayOrigin
    val d     = rayDirection
    val denom = n.dot(d)
    if math.abs(denom) < 1e-6 then None
    else
      val t = n.dot(p0 - o) / denom
      if t <= 0 then None else Some(o + d * t)

  /** graphRoot is identity on the desktop but scaled/lifted inside an XR
    * session; layout coordinates are its LOCAL space.
    */
  private def localToWorld(local: Vec3): Vec3 =
    val s = graphRoot.scale.x
    Vec3(
      local.x * s + graphRoot.position.x,
      local.y * s + graphRoot.position.y,
      local.z * s + graphRoot.position.z
    )

  private def worldToLocal(world: Vec3): Vec3 =
    val s = math.max(1e-9, graphRoot.scale.x)
    Vec3(
      (world.x - graphRoot.position.x) / s,
      (world.y - graphRoot.position.y) / s,
      (world.z - graphRoot.position.z) / s
    )

  /** Hold the node exactly here and keep the simulation warm so everyone else
    * reacts to the tug in real time.
    */
  private def pinNodeAt(id: NodeId, local: Vec3): Unit =
    layout = layout.copy(
      positions = layout.positions.updated(id, local),
      pinned = Set(id),
      temperature = math.max(layout.temperature, DragHeat)
    )

  /** Release with residual heat: the graph resettles around wherever the node
    * was left (force), or the node tweens home (layers).
    */
  private def releasePins(): Unit =
    layout = layout.copy(
      pinned = Set.empty,
      temperature = math.max(layout.temperature, DragHeat)
    )

  def dispose(): Unit =
    renderer.setAnimationLoop(null)
    resizeObserverOpt.foreach(_.disconnect())
    xrSessionOpt.foreach(_.applyDynamic("end")())
    sprites.values.foreach(disposeSprite)
    sprites = Map.empty
    hullMeshes.foreach: (mesh, material) =>
      mesh.geometry.dispose()
      material.dispose()
    hullMeshes = Vector.empty
    coneGeometry.dispose()
    coneMaterial.dispose()
    gizmoLines._1.dispose()
    gizmoLines._2.dispose()
    gizmoAxisTips.foreach((_, mat) => mat.dispose()) // cone geometry is the shared one
    stemGeometry.dispose()
    stemMaterial.dispose()
    stemDimMaterial.dispose()
    renderer.dispose()
    renderer.domElement.remove()
end GraphScene3D
