package org.jpablo.graphexplorer.viewer.components.scene3d

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.backends.threejs as three
import org.jpablo.graphexplorer.viewer.formats.dot.HtmlLabels
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.layout3d.{ForceLayout3D, Layout3D, LayoutGraph, LayoutState3D, Vec3}
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId, ViewerNode}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Button, ghost, tiny}
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
        input(
          typ      := "range",
          cls      := "range range-xs flex-1",
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

  private case class NodeSprite(
      sprite:   three.Sprite,
      material: three.SpriteMaterial,
      texture:  three.CanvasTexture,
      label:    String
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
  private val lineMaterial =
    three.LineBasicMaterial(three.LineBasicMaterial.params(vertexColors = true, transparent = true, opacity = 0.95))

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
  private var sprites               = Map.empty[NodeId, NodeSprite]
  private var edges                 = Vector.empty[(NodeId, NodeId)]
  private var selectedNodes         = Set.empty[NodeId]
  private var lastNodeCount         = -1

  private var controlsOpt: Option[three.OrbitControls]        = None
  private var resizeObserverOpt: Option[dom.ResizeObserver]   = None

  /** True once the browser reports an immersive-vr-capable device; gates the
    * Enter-VR button so desktops never render it.
    */
  val vrSupported = Var(false)

  /** Mirrors renderer.xr session state for the button label. */
  val vrPresenting = Var(false)

  private var xrSessionOpt: Option[js.Dynamic] = None
  private var lineSegmentsOpt: Option[three.LineSegments]     = None
  private var lineGeometryOpt: Option[three.BufferGeometry]   = None
  private var linePosAttrOpt: Option[three.BufferAttribute]   = None
  private var linePositions: Float32Array                     = new Float32Array(0)
  private var lineColorAttrOpt: Option[three.BufferAttribute] = None
  private var lineColors: Float32Array                        = new Float32Array(0)
  private var brightEdges: Array[Boolean]                     = Array.empty

  // One shared cone geometry/material for every arrowhead; meshes per edge.
  private val coneGeometry = three.ConeGeometry(0.05, 0.14, 10)
  private val coneMaterial = three.MeshBasicMaterial(
    three.MeshBasicMaterial.params(color = 0x5a9df2, transparent = true, opacity = 0.95, depthWrite = true, side = 0)
  )
  private var coneMeshes = Vector.empty[three.Mesh]
  private val coneUp     = three.Vector3().set(0, 1, 0)
  private val coneDir    = three.Vector3()

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
    controlsOpt.foreach: controls =>
      val camP  = Vec3(camera.position.x, camera.position.y, camera.position.z)
      val t     = Vec3(controls.target.x, controls.target.y, controls.target.z)
      val toCam = camP - t
      val dir   = toCam * (1.0 / math.max(1e-9, toCam.length))
      gizmoCamera.position.set(dir.x * GizmoCamDist, dir.y * GizmoCamDist, dir.z * GizmoCamDist)
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
    val nodeIds = g.nodes.keys.toVector
    edges = g.arrows.values.map(a => (a.source, a.target)).toVector
    clusterSets = visibleClusters(g, nodeIds)
    layout = algo.sync(layout, LayoutGraph(nodeIds, edges, clusterSets))

    val labels: Map[NodeId, String] =
      g.nodes.map((id, node) => id -> displayLabel(id, node)).toMap
    val (keep, drop) = sprites.partition((id, ns) => labels.get(id).contains(ns.label))
    drop.values.foreach(disposeSprite)
    sprites = labels.foldLeft(keep):
      case (acc, (id, label)) =>
        if acc.contains(id) then acc else acc.updated(id, createSprite(id, label))

    rebuildLines()
    rebuildHulls()
    applySelection()
    writePositions()
    if nodeIds.size != lastNodeCount then
      lastNodeCount = nodeIds.size
      userNavigated = false
      fitCamera(1.0) // snap: a fresh graph frames correctly from its very first paint

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
      val (texture, _) = paintLabel(ns.label)
      ns.material.map = texture
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
    controlsOpt.foreach(_.enableZoom = !trackpad)

  def setAlgorithm(algoId: String): Unit =
    val next = Layout3D.byId(algoId).getOrElse(ForceLayout3D)
    if next.id != baseAlgo.id then
      baseAlgo = next
      knobValuesV.set(defaultKnobValues(next))
      algo = next
      layout = algo.sync(layout, layout.graph)
      userNavigated = false // a chosen layout switch deserves a fresh framing of the morph

  /** What the sprite shows: the label attribute when it is plain text, the id
    * otherwise. Record/HTML markup would render as raw source, so it falls back
    * to the id; DOT line breaks (\n \l \r) flatten to spaces for now.
    */
  private def displayLabel(id: NodeId, node: ViewerNode): String =
    val raw = node.label.toString
    val text =
      if raw.isEmpty || raw == "\\N" || HtmlLabels.isHtml(raw) || raw.contains('|') then id.value
      else raw.replaceAll("""\\[nlr]""", " ")
    if text.length > 40 then text.take(39) + "…" else text

  private def createSprite(id: NodeId, label: String): NodeSprite =
    val (texture, aspect) = paintLabel(label)
    val material          = three.SpriteMaterial(three.SpriteMaterial.params(map = texture, transparent = true))
    val sprite            = three.Sprite(material)
    val width             = math.min(NodeHeight * aspect, MaxNodeWidth)
    sprite.scale.set(width, width / aspect, 1)
    sprite.userData("nodeId") = id.value
    nodesGroup.add(sprite)
    NodeSprite(sprite, material, texture, label)

  private def disposeSprite(ns: NodeSprite): Unit =
    nodesGroup.remove(ns.sprite)
    ns.material.dispose()
    ns.texture.dispose()

  /** Draw the label as a rounded pill on a 2D canvas and wrap it in a texture.
    * Colors come from the daisyUI theme variables so 3D follows the app theme.
    * In-scene text (not a DOM overlay) on purpose: DOM overlays do not exist
    * inside an immersive WebXR session, and this canvas is on the VR path.
    */
  private def paintLabel(label: String): (three.CanvasTexture, Double) =
    val fontPx  = 64.0
    val padX    = fontPx * 0.55
    val padY    = fontPx * 0.32
    val borderW = 4.0

    val canvas = dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas]
    val ctx    = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
    val font   = s"${fontPx}px ${dom.window.getComputedStyle(dom.document.body).fontFamily}"

    ctx.font = font
    val textWidth = ctx.measureText(label).width
    canvas.width = math.ceil(textWidth + 2 * padX + 2 * borderW).toInt.max(1)
    canvas.height = math.ceil(fontPx * 1.25 + 2 * padY + 2 * borderW).toInt

    ctx.font = font // canvas state resets when the canvas is resized
    ctx.fillStyle = themeColor("--color-base-100", "#ffffff")
    ctx.strokeStyle = themeColor("--color-base-content", "#333333")
    ctx.lineWidth = borderW
    val radius = (canvas.height - borderW) / 4.0
    ctx.beginPath()
    ctx.asInstanceOf[js.Dynamic].roundRect(
      borderW / 2, borderW / 2, canvas.width - borderW, canvas.height - borderW, radius)
    ctx.fill()
    ctx.stroke()
    ctx.fillStyle = themeColor("--color-base-content", "#333333")
    ctx.textAlign = "center"
    ctx.textBaseline = "middle"
    ctx.fillText(label, canvas.width / 2.0, canvas.height / 2.0)

    val texture = three.CanvasTexture(canvas)
    texture.colorSpace = "srgb"
    (texture, canvas.width.toDouble / canvas.height.toDouble)

  private def themeColor(cssVar: String, fallback: String): String =
    val v = dom.window.getComputedStyle(dom.document.documentElement).getPropertyValue(cssVar).trim
    if v.isEmpty then fallback else v

  /** One LineSegments object for all edges: a single draw call, with per-vertex
    * colors giving each edge a dim source end and an accent target end — the
    * direction cue until real arrowheads exist. Positions are (re)written every
    * layout step into a preallocated buffer.
    */
  private def rebuildLines(): Unit =
    lineSegmentsOpt.foreach(graphRoot.remove(_))
    lineGeometryOpt.foreach(_.dispose())
    coneMeshes.foreach(graphRoot.remove(_)) // geometry/material are shared — only the meshes go
    coneMeshes = Vector.empty
    lineSegmentsOpt = None
    lineGeometryOpt = None
    linePosAttrOpt = None
    lineColorAttrOpt = None
    if edges.nonEmpty then
      linePositions = new Float32Array(edges.size * 6)
      // RGBA per vertex: alpha is how the neighborhood dim reaches lines
      // without a second material (needs material.transparent, which is on).
      lineColors = new Float32Array(edges.size * 8)
      brightEdges = Array.fill(edges.size)(true)
      val geometry = three.BufferGeometry()
      val posAttr  = three.BufferAttribute(linePositions, 3)
      val colAttr  = three.BufferAttribute(lineColors, 4)
      geometry.setAttribute("position", posAttr)
      geometry.setAttribute("color", colAttr)
      val segments = three.LineSegments(geometry, lineMaterial)
      // Positions mutate every frame; skip bounding-sphere culling instead of
      // recomputing it per step.
      segments.frustumCulled = false
      graphRoot.add(segments)
      lineSegmentsOpt = Some(segments)
      lineGeometryOpt = Some(geometry)
      linePosAttrOpt = Some(posAttr)
      lineColorAttrOpt = Some(colAttr)
      coneMeshes = edges.map: _ =>
        val cone = three.Mesh(coneGeometry, coneMaterial)
        graphRoot.add(cone)
        cone
      writeLineColors()

  /** Rewrites the RGBA line buffer from the current highlight: with a
    * selection, only edges incident to a selected node stay opaque (they ARE
    * the neighborhood); with none, everything is. Arrowheads follow their
    * edge by visibility.
    */
  private def writeLineColors(): Unit =
    for i <- edges.indices do
      val (s, t) = edges(i)
      brightEdges(i) = selectedNodes.isEmpty || selectedNodes.contains(s) || selectedNodes.contains(t)
      val alpha = if brightEdges(i) then 0.95f else 0.10f
      // Brighter than the old paper-background values: these must read on the
      // dark environment.
      lineColors(i * 8 + 0) = 0.55f; lineColors(i * 8 + 1) = 0.58f
      lineColors(i * 8 + 2) = 0.64f; lineColors(i * 8 + 3) = alpha
      lineColors(i * 8 + 4) = 0.35f; lineColors(i * 8 + 5) = 0.62f
      lineColors(i * 8 + 6) = 0.95f; lineColors(i * 8 + 7) = alpha
    lineColorAttrOpt.foreach(_.needsUpdate = true)

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
      layout.positions.get(id).foreach(p => ns.sprite.position.set(p.x, p.y, p.z))
    for i <- edges.indices do
      val (s, t) = edges(i)
      for sp <- layout.positions.get(s); tp <- layout.positions.get(t) do
        linePositions(i * 6 + 0) = sp.x.toFloat
        linePositions(i * 6 + 1) = sp.y.toFloat
        linePositions(i * 6 + 2) = sp.z.toFloat
        linePositions(i * 6 + 3) = tp.x.toFloat
        linePositions(i * 6 + 4) = tp.y.toFloat
        linePositions(i * 6 + 5) = tp.z.toFloat
        // Arrowhead: a cone just short of the target pill, aimed along the edge.
        if i < coneMeshes.size then
          val cone = coneMeshes(i)
          val d    = tp - sp
          val len  = d.length
          if len < 1e-6 || !brightEdges(i) then cone.visible = false
          else
            cone.visible = true
            val dir  = d * (1.0 / len)
            val back = math.min(len * 0.5, NodeHeight * 0.55)
            cone.position.set(tp.x - dir.x * back, tp.y - dir.y * back, tp.z - dir.z * back)
            cone.quaternion.setFromUnitVectors(coneUp, coneDir.set(dir.x, dir.y, dir.z))
    linePosAttrOpt.foreach(_.needsUpdate = true)
    updateHulls()

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
      ns.material.color.setHex(if selectedNodes.contains(id) then SelectedTint else NormalTint)
      ns.material.opacity = if neighborhood.forall(_.contains(id)) then 1.0 else DimOpacity
    writeLineColors()
    writePositions() // cone visibility follows brightEdges

  /** Camera field of view, degrees (vertical) — mirrored here for fit math. */
  private val CameraFovDeg  = 50.0
  private val DefaultCamDir = Vec3(0.62, 0.4, 0.62)

  /** True since the user last took the camera (orbit/dolly); auto-fit stands
    * down until the next graph load or algorithm switch.
    */
  private var userNavigated = false

  /** Screen basis for an orbit pose: `dir` is target→camera; view = where the
    * camera looks, right/up span the screen (matching three's lookAt).
    */
  private def basisFor(dir: Vec3): (Vec3, Vec3, Vec3) =
    val view    = dir * -1.0
    val worldUp = if math.abs(view.y) > 0.99 then Vec3(0, 0, 1) else Vec3(0, 1, 0)
    val right0 = Vec3(
      view.y * worldUp.z - view.z * worldUp.y,
      view.z * worldUp.x - view.x * worldUp.z,
      view.x * worldUp.y - view.y * worldUp.x
    )
    val right = right0 * (1.0 / math.max(1e-9, right0.length))
    val up = Vec3(
      right.y * view.z - right.z * view.y,
      right.z * view.x - right.x * view.z,
      right.x * view.y - right.y * view.x
    )
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
      controlsOpt.foreach: controls =>
        val target0 = Vec3(controls.target.x, controls.target.y, controls.target.z)
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
          sprites.get(nodeId).map(_.sprite.scale.x / 2).getOrElse(NodeHeight) + NodeHeight * 0.25
        def halfH(nodeId: NodeId) =
          sprites.get(nodeId).map(_.sprite.scale.y / 2).getOrElse(NodeHeight) + NodeHeight * 0.25

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
        var minDepth = Double.MaxValue
        var maxDepth = -Double.MaxValue
        layout.positions.foreach: (nodeId, p) =>
          val rel = p - camC
          val dz  = math.max(0.1, rel.dot(view))
          val nx  = rel.dot(right) / (dz * tanH)
          val ny  = rel.dot(up) / (dz * tanV)
          val hw  = halfW(nodeId) / (dz * tanH)
          val hh  = halfH(nodeId) / (dz * tanV)
          minNX = math.min(minNX, nx - hw); maxNX = math.max(maxNX, nx + hw)
          minNY = math.min(minNY, ny - hh); maxNY = math.max(maxNY, ny + hh)
          minDepth = math.min(minDepth, rel.dot(view) - dist1)
          maxDepth = math.max(maxDepth, rel.dot(view) - dist1)
        val center =
          center1 +
            right * ((minNX + maxNX) / 2 * dist1 * tanH) +
            up * ((minNY + maxNY) / 2 * dist1 * tanV)
        val (aX2, bX2, aY2, bY2) = solveAbout(center)
        val fitDist =
          math.max(2.0, math.max((aX2 + bX2) / 2, (aY2 + bY2) / 2)) * 1.04

        val newTarget = target0 + (center - target0) * alpha
        val newDist   = dirRaw.length + (fitDist - dirRaw.length) * alpha
        controls.target.set(newTarget.x, newTarget.y, newTarget.z)
        camera.position.set(
          newTarget.x + dir.x * newDist,
          newTarget.y + dir.y * newDist,
          newTarget.z + dir.z * newDist
        )
        // Front face crisp; the far side recedes but never vanishes (the
        // farthest node sits at ~half fog).
        val depthSpread = math.max(1.0, maxDepth - minDepth)
        scene.fog.near = newDist + minDepth
        scene.fog.far = newDist + maxDepth + depthSpread

  // ---------------- lifecycle ----------------

  def start(container: dom.Element): Unit =
    renderer.setPixelRatio(dom.window.devicePixelRatio)
    renderer.domElement.style.display = "block"
    container.appendChild(renderer.domElement)

    val controls = three.OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    controls.dampingFactor = 0.08
    controls.enableZoom = !trackpadNav
    controls.addEventListener("start", _ => userNavigated = true)
    controlsOpt = Some(controls)
    attachWheelNavigation(controls)

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
              s"pinned=${layout.pinned.mkString(",")} navigated=$userNavigated",
          posOf = (id: String) =>
            layout.positions.get(NodeId(id)).map(p => js.Array(p.x, p.y, p.z)).getOrElse(js.Array[Double]()),
          settle = () =>
            while !layout.done do layout = algo.step(layout)
            writePositions()
            if !userNavigated then fitCamera(1.0)
            controlsOpt.foreach(_.update())
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
    // The camera follows the drawing until the user takes over: no giant
    // first frame, no post-convergence jump — the framing converges WITH the
    // layout. Suspended during a node drag: the grab-plane mapping assumes a
    // still camera, and a creeping fit would slide the node off the pointer.
    if !userNavigated && mouseDragNode.isEmpty && vrDrag.isEmpty then fitCamera(0.2)
    // During a session the headset owns the camera; OrbitControls' damping
    // writes would fight it.
    if !renderer.xr.isPresenting then controlsOpt.foreach(_.update())
    renderer.render(scene, camera)
    // The corner viewport pass makes no sense inside a headset.
    if !renderer.xr.isPresenting then renderGizmo()

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

  /** Trackpad-mode wheel: two-finger scroll ORBITS — the same gesture a drag
    * performs, minus the click — and pinch dollies. The rotation uses
    * OrbitControls' own sensitivity (2π per canvas-height of travel) so the
    * two grips feel identical; finger direction maps to drag direction
    * (fingers right ≡ drag right, under natural scrolling's inverted deltas).
    */
  private def attachWheelNavigation(controls: three.OrbitControls): Unit =
    renderer.domElement.addEventListener(
      "wheel",
      (e: dom.WheelEvent) =>
        if trackpadNav && !renderer.xr.isPresenting then
          e.preventDefault()
          userNavigated = true
          val target = Vec3(controls.target.x, controls.target.y, controls.target.z)
          val camP   = Vec3(camera.position.x, camera.position.y, camera.position.z)
          val toCam  = camP - target
          val dist   = math.max(0.5, toCam.length)
          if e.ctrlKey || e.metaKey then
            // pinch (browsers report it as ctrl+wheel) or meta+scroll: dolly,
            // exactly what the wheel does in mouse mode
            val dir = toCam * (1.0 / math.max(1e-9, toCam.length))
            val nd  = math.min(400.0, math.max(0.6, dist * math.exp(e.deltaY * 0.01)))
            camera.position.set(target.x + dir.x * nd, target.y + dir.y * nd, target.z + dir.z * nd)
          else
            // spherical orbit about the target, three's convention:
            // theta = azimuth around Y, phi = polar angle from +Y
            val heightPx = math.max(1, renderer.domElement.clientHeight).toDouble
            val theta    = math.atan2(toCam.x, toCam.z) + 2 * math.Pi * e.deltaX / heightPx
            val phi0     = math.acos(math.min(1.0, math.max(-1.0, toCam.y / dist)))
            val phi      = math.min(math.Pi - 0.05, math.max(0.05, phi0 + 2 * math.Pi * e.deltaY / heightPx))
            camera.position.set(
              target.x + dist * math.sin(phi) * math.sin(theta),
              target.y + dist * math.cos(phi),
              target.z + dist * math.sin(phi) * math.cos(theta)
            )
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
            controlsOpt.foreach(_.enabled = false)
            renderer.domElement.asInstanceOf[js.Dynamic].setPointerCapture(e.pointerId)
          case None => ()
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
    )
    renderer.domElement.addEventListener(
      "pointerup",
      (e: dom.PointerEvent) =>
        val wasClick = math.hypot(e.clientX - downX, e.clientY - downY) < ClickSlopPx
        val additive = e.shiftKey || e.metaKey
        mouseDragNode match
          case Some(id) =>
            mouseDragNode = None
            controlsOpt.foreach(_.enabled = true)
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
    controlsOpt.foreach(_.dispose())
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
    lineGeometryOpt.foreach(_.dispose())
    lineMaterial.dispose()
    renderer.dispose()
    renderer.domElement.remove()
end GraphScene3D
