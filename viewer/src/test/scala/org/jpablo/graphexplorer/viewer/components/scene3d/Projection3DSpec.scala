package org.jpablo.graphexplorer.viewer.components.scene3d

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.threejs as three
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{Settings3D, ViewTarget, ViewerSettings, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, TimerHelpers}
import org.scalajs.dom
import upickle.default.{read, write}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The projection switch of the 3D view: the perspective camera and the
  * orthographic camera. A switch must not move the drawing on the screen.
  */
class Projection3DSpec extends FunSuite with TestHelpers with TimerHelpers with LoggedChecks:

  def checkPrefix = "Projection3DSpec"

  override def munitFixtures = List(mockStorageFixture())

  private val Fov = 50.0

  private def near(a: Double, b: Double, eps: Double = 1e-6) = math.abs(a - b) < eps

  // ---------------------------------------------------------------------------
  // The pure part
  // ---------------------------------------------------------------------------

  test("the half height of the view is the distance times tan(fov / 2)"):
    check(near(Projection3D.halfHeightAt(10, 90), 10), "at 90° the half height is the distance")
    check(near(Projection3D.halfHeightAt(4, 60), 4 * math.tan(math.Pi / 6)), "at 60° the half height is d·tan 30°")

  test("the frustum is symmetric, and its width is its height times the aspect"):
    val f = Projection3D.matchingFrustum(distance = 8, fovDeg = Fov, aspect = 2)
    check(near(f.left, -f.right), s"the frustum is not symmetric in x: $f")
    check(near(f.bottom, -f.top), s"the frustum is not symmetric in y: $f")
    check(near(f.top, 8 * math.tan(math.toRadians(Fov / 2))), s"the top is not d·tan(fov/2): $f")
    check(near((f.right - f.left) / (f.top - f.bottom), 2), s"the width is not two times the height: $f")

  /** Points of the target plane project to the same place in the two cameras.
    * This uses the real three.js cameras, so it also checks the facade.
    */
  test("a point of the target plane has the same screen position in the two cameras"):
    val poses = List(
      // (target, direction from the target to the camera, distance, aspect)
      ((0.0, 0.0, 0.0), (0.0, 0.0, 1.0), 5.0, 1.0),
      ((1.0, -2.0, 0.5), (0.62, 0.4, 0.62), 12.0, 1.6),
      ((-3.0, 1.0, 2.0), (-0.3, 0.9, 0.1), 0.8, 0.5)
    )
    poses.foreach: (target, dir0, dist, aspect) =>
      val len = math.sqrt(dir0._1 * dir0._1 + dir0._2 * dir0._2 + dir0._3 * dir0._3)
      val dir = (dir0._1 / len, dir0._2 / len, dir0._3 / len)
      val eye = (target._1 + dir._1 * dist, target._2 + dir._2 * dist, target._3 + dir._3 * dist)

      val persp = three.PerspectiveCamera(Fov, aspect, 0.1, 2000)
      persp.position.set(eye._1, eye._2, eye._3)
      persp.lookAt(target._1, target._2, target._3)
      persp.updateMatrixWorld()

      val f     = Projection3D.matchingFrustum(dist, Fov, aspect)
      val ortho = three.OrthographicCamera(f.left, f.right, f.top, f.bottom, 0.1, 2000)
      // The distance of an orthographic camera does not change the image.
      // The scene puts it farther back, so this test does that too.
      ortho.position.set(eye._1 + dir._1 * 50, eye._2 + dir._2 * 50, eye._3 + dir._3 * 50)
      ortho.lookAt(target._1, target._2, target._3)
      ortho.updateMatrixWorld()

      // Two directions in the target plane: the camera right and up.
      val worldUp = (0.0, 1.0, 0.0)
      def cross(a: (Double, Double, Double), b: (Double, Double, Double)) =
        (a._2 * b._3 - a._3 * b._2, a._3 * b._1 - a._1 * b._3, a._1 * b._2 - a._2 * b._1)
      val right = cross(worldUp, dir)
      val up    = cross(dir, right)
      List((0.0, 0.0), (0.3, 0.1), (-0.7, 0.4), (0.2, -0.5)).foreach: (a, b) =>
        val p = (
          target._1 + right._1 * a * dist + up._1 * b * dist,
          target._2 + right._2 * a * dist + up._2 * b * dist,
          target._3 + right._3 * a * dist + up._3 * b * dist
        )
        val inPersp = three.Vector3().set(p._1, p._2, p._3).project(persp)
        val inOrtho = three.Vector3().set(p._1, p._2, p._3).project(ortho)
        check(
          near(inPersp.x, inOrtho.x, 1e-4) && near(inPersp.y, inOrtho.y, 1e-4),
          s"the point $p at the target $target goes to (${inPersp.x}, ${inPersp.y}) in perspective " +
            s"and to (${inOrtho.x}, ${inOrtho.y}) in orthographic"
        )

  // ---------------------------------------------------------------------------
  // The settings
  // ---------------------------------------------------------------------------

  test("the orthographic setting round-trips, and old settings load with perspective"):
    val stored = read[ViewerSettings](write(ViewerSettings(orthographic3D = true)))
    same(stored.orthographic3D, true, "the orthographic setting did not round-trip")
    same(Settings3D.stored(stored).orthographic3D.now(), true, "Settings3D.stored did not read the projection")
    val old = read[ViewerSettings]("""{"rightPanelTabIndex":3,"enable3D":true,"label3DBillboard":true}""")
    same(old.orthographic3D, false, "settings stored before the field existed do not load with perspective")
    same(old.label3DBillboard, true, "the other 3D settings did not load")
    same(Settings3D().orthographic3D.now(), false, "the default projection is not perspective")

  // ---------------------------------------------------------------------------
  // The mounted scene
  // ---------------------------------------------------------------------------

  private def isOrtho(camera: three.Camera): Boolean =
    camera.asInstanceOf[js.Dynamic].isOrthographicCamera.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)

  private def frustumOf(camera: three.Camera): (Double, Double) =
    val c = camera.asInstanceOf[three.OrthographicCamera]
    (c.right - c.left, c.top - c.bottom)

  /** Mounts the 3D scene in a box of `width` × `height` px, and gives the box and the scene to `body`. */
  private def withScene(width: Int, height: Int)(body: (dom.html.Div, ViewerState, GraphScene3D) => Future[Unit]) =
    withGraphvizAsync: graphviz =>
      // Embedded: the state does not write the viewer settings of the window.
      // Else its 3D settings stay in the store, and the specs after this one
      // open their diagrams in 3D. The owner stops the subscriptions of the
      // state at the end of the test.
      val owner = ManualOwner()
      val state = ViewerState(ViewTarget.Diagram(DiagramId("projection-3d-spec")), graphviz, embedded = true)(using owner)
      state.enable3D.set(true)
      state.view3D.set(true)
      // The test page does not load the stylesheet of the app. Without this
      // style, the 3D box grows with its canvas, and its ResizeObserver gives
      // the window error "ResizeObserver loop completed with undelivered
      // notifications". That error stops the test run (see SvgCanvasSpec).
      val sizes = dom.document.createElement("style")
      sizes.textContent =
        "#scene3d-container { position: relative; width: 100%; height: 100%; overflow: hidden; }"
      dom.document.head.appendChild(sizes)
      val box = dom.document.createElement("div").asInstanceOf[dom.html.Div]
      box.style.width = s"${width}px"
      box.style.height = s"${height}px"
      box.style.position = "relative"
      box.style.overflow = "hidden"
      dom.document.body.appendChild(box)
      val scene = GraphScene3D(state)
      val root  = render(box, Scene3D(state, scene))
      body(box, state, scene).transform: outcome =>
        root.unmount()
        box.remove()
        sizes.parentNode.removeChild(sizes)
        owner.killSubscriptions()
        outcome

  test("the switch changes the type of the camera, and keeps the view at the target"):
    withScene(800, 600): (_, state, scene) =>
      afterMicrotasks:
        check(!isOrtho(scene.activeCamera), "precondition: the scene does not start with the perspective camera")
        state.orthographic3D.set(true)
      .flatMap: _ =>
        afterMicrotasks:
          check(isOrtho(scene.activeCamera), "after the switch to orthographic, the camera is not orthographic")
          val dist           = scene.rigDistance
          val (fw, fh)       = frustumOf(scene.activeCamera)
          val expectedHeight = 2 * Projection3D.halfHeightAt(dist, Fov)
          check(
            near(fh, expectedHeight, 1e-6),
            s"the frustum height is $fh, but the perspective camera shows $expectedHeight at the target distance $dist"
          )
          check(near(fw / fh, 800.0 / 600.0, 1e-6), s"the frustum aspect is ${fw / fh}, not 800/600")
          state.orthographic3D.set(false)
      .flatMap: _ =>
        afterMicrotasks:
          check(!isOrtho(scene.activeCamera), "after the switch back, the camera is not perspective")

  test("a resize updates the orthographic frustum"):
    withScene(800, 600): (box, state, scene) =>
      state.orthographic3D.set(true)
      waitFor("the orthographic frustum has the aspect 800/600"):
        val (fw, fh) = frustumOf(scene.activeCamera)
        isOrtho(scene.activeCamera) && near(fw / fh, 800.0 / 600.0, 1e-6)
      .flatMap: _ =>
        box.style.width = "300px"
        waitFor(
          s"after the resize, the orthographic frustum has the aspect 300/600 (it is ${
              val (fw, fh) = frustumOf(scene.activeCamera); fw / fh
            })"
        ):
          val (fw, fh) = frustumOf(scene.activeCamera)
          near(fw / fh, 300.0 / 600.0, 1e-6)
