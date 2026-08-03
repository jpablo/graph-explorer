package org.jpablo.graphexplorer.viewer.components.scene3d

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.backends.threejs as three
import org.jpablo.graphexplorer.viewer.formats.dot.HtmlLabels
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
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
    // Bottom-LEFT: the zoom toolbar centers over the whole middle area, and
    // with the right panel open its tail reaches the canvas's right edge — the
    // left corner is the one spot no floating chrome owns. Only rendered when
    // an immersive session is actually available, so desktops never see it.
    child.maybe <-- scene.vrSupported.signal.map(av => Option.when(av)(vrButton(scene)))
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

  scene.add(graphRoot)
  graphRoot.add(nodesGroup)

  private var algo: Layout3D        = ForceLayout3D
  private var layout: LayoutState3D = algo.initial(LayoutGraph(Vector.empty, Vector.empty))
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

  // ---------------- graph -> scene ----------------

  def setGraph(g: ViewerGraph): Unit =
    val nodeIds = g.nodes.keys.toVector
    edges = g.arrows.values.map(a => (a.source, a.target)).toVector
    layout = algo.sync(layout, LayoutGraph(nodeIds, edges))

    val labels: Map[NodeId, String] =
      g.nodes.map((id, node) => id -> displayLabel(id, node)).toMap
    val (keep, drop) = sprites.partition((id, ns) => labels.get(id).contains(ns.label))
    drop.values.foreach(disposeSprite)
    sprites = labels.foldLeft(keep):
      case (acc, (id, label)) =>
        if acc.contains(id) then acc else acc.updated(id, createSprite(id, label))

    rebuildLines()
    applySelection()
    writePositions()
    if nodeIds.size != lastNodeCount then
      lastNodeCount = nodeIds.size
      frameCamera(nodeIds.size)

  def setSelection(sel: ElementIds): Unit =
    selectedNodes = sel.ids.collect { case n: NodeId => n }.toSet
    applySelection()

  /** Switch layout algorithms. The new algorithm adopts the CURRENT state —
    * its sync sees a foreign algoId and re-adopts even though the graph is
    * unchanged — so the drawing morphs live from one shape to the other.
    */
  def setAlgorithm(algoId: String): Unit =
    val next = Layout3D.byId(algoId).getOrElse(ForceLayout3D)
    if next.id != algo.id then
      algo = next
      layout = algo.sync(layout, layout.graph)

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
    lineSegmentsOpt = None
    lineGeometryOpt = None
    linePosAttrOpt = None
    if edges.nonEmpty then
      linePositions = new Float32Array(edges.size * 6)
      val colors = new Float32Array(edges.size * 6)
      for i <- edges.indices do
        // source end: neutral gray; target end: accent blue
        colors(i * 6 + 0) = 0.42f; colors(i * 6 + 1) = 0.45f; colors(i * 6 + 2) = 0.50f
        colors(i * 6 + 3) = 0.16f; colors(i * 6 + 4) = 0.42f; colors(i * 6 + 5) = 0.78f
      val geometry = three.BufferGeometry()
      val posAttr  = three.BufferAttribute(linePositions, 3)
      geometry.setAttribute("position", posAttr)
      geometry.setAttribute("color", three.BufferAttribute(colors, 3))
      val segments = three.LineSegments(geometry, lineMaterial)
      // Positions mutate every frame; skip bounding-sphere culling instead of
      // recomputing it per step.
      segments.frustumCulled = false
      graphRoot.add(segments)
      lineSegmentsOpt = Some(segments)
      lineGeometryOpt = Some(geometry)
      linePosAttrOpt = Some(posAttr)

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
    linePosAttrOpt.foreach(_.needsUpdate = true)

  private def applySelection(): Unit =
    sprites.foreach: (id, ns) =>
      ns.material.color.setHex(if selectedNodes.contains(id) then SelectedTint else NormalTint)

  private def frameCamera(nodeCount: Int): Unit =
    val r = ForceLayout3D.radiusFor(nodeCount, ForceLayout3D.defaultParams.k)
    val d = math.max(4.0, r * 2.6)
    camera.position.set(d * 0.7, d * 0.45, d * 0.7)
    camera.lookAt(0, 0, 0)

  // ---------------- lifecycle ----------------

  def start(container: dom.Element): Unit =
    renderer.setPixelRatio(dom.window.devicePixelRatio)
    renderer.domElement.style.display = "block"
    container.appendChild(renderer.domElement)

    val controls = three.OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    controls.dampingFactor = 0.08
    controlsOpt = Some(controls)

    resize(container)
    val observer = dom.ResizeObserver((_, _) => resize(container))
    observer.observe(container)
    resizeObserverOpt = Some(observer)

    attachPointerHandlers()
    setupXR()
    renderer.setAnimationLoop(frame)

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
      if layout.done then fitCameraToLayout()
    // During a session the headset owns the camera; OrbitControls' damping
    // writes would fight it.
    if !renderer.xr.isPresenting then controlsOpt.foreach(_.update())
    renderer.render(scene, camera)

  /** Once the simulation settles, dolly the camera along its current view ray
    * so the whole drawing fits — the equilibrium spreads well past the initial
    * placement sphere that frameCamera assumed. Direction is preserved, so an
    * orbit the user already started is respected.
    */
  private def fitCameraToLayout(): Unit =
    if layout.positions.nonEmpty && !renderer.xr.isPresenting then
      val r = layout.positions.values.map(_.length).max + NodeHeight * 2
      val d = math.max(4.0, r * 2.4)
      val p = camera.position
      val len = math.max(1e-6, math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z))
      val f = d / len
      p.set(p.x * f, p.y * f, p.z * f)

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
    lineGeometryOpt.foreach(_.dispose())
    lineMaterial.dispose()
    renderer.dispose()
    renderer.domElement.remove()
end GraphScene3D
