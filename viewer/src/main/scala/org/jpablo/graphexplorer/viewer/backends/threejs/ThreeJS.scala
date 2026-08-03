package org.jpablo.graphexplorer.viewer.backends.threejs

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Float32Array

// Hand-written facade for the (small) subset of three.js the 3D canvas binds to.
// `three` is deliberately in build.sbt's stIgnore: letting ScalablyTyped convert
// its full .d.ts surface would be a large, slow codegen for a handful of classes.
// https://threejs.org/docs/

@js.native
@JSImport("three", "Object3D")
class Object3D extends js.Object:
  val position: Vector3                 = js.native
  val scale: Vector3                    = js.native
  val matrixWorld: Matrix4              = js.native
  var visible: Boolean                  = js.native
  var frustumCulled: Boolean            = js.native
  val userData: js.Dictionary[js.Any]   = js.native
  val children: js.Array[Object3D]      = js.native
  def add(objects: Object3D*): this.type    = js.native
  def remove(objects: Object3D*): this.type = js.native
  def lookAt(x: Double, y: Double, z: Double): Unit = js.native
  def getWorldDirection(target: Vector3): Vector3   = js.native
  // From three's EventDispatcher (not the DOM's): controller select events etc.
  def addEventListener(tpe: String, listener: js.Function1[js.Any, Unit]): Unit = js.native

@js.native
@JSImport("three", "Scene")
class Scene extends Object3D

@js.native
@JSImport("three", "Group")
class Group extends Object3D

@js.native
@JSImport("three", "PerspectiveCamera")
class PerspectiveCamera(fov: Double, aspectRatio: Double, near: Double, far: Double) extends Object3D:
  var aspect: Double                   = js.native
  def updateProjectionMatrix(): Unit   = js.native

@js.native
@JSImport("three", "Vector2")
class Vector2 extends js.Object:
  def set(x: Double, y: Double): this.type = js.native

@js.native
@JSImport("three", "Vector3")
class Vector3 extends js.Object:
  var x: Double = js.native
  var y: Double = js.native
  var z: Double = js.native
  def set(x: Double, y: Double, z: Double): this.type    = js.native
  def setFromMatrixPosition(m: Matrix4): this.type       = js.native
  def applyMatrix4(m: Matrix4): this.type                = js.native

@js.native
@JSImport("three", "Matrix4")
class Matrix4 extends js.Object:
  def identity(): this.type              = js.native
  def extractRotation(m: Matrix4): this.type = js.native

@js.native
@JSImport("three", "Color")
class Color extends js.Object:
  def setHex(hex: Double): this.type = js.native

@js.native
@JSImport("three", "Texture")
class Texture extends js.Object:
  var needsUpdate: Boolean = js.native
  var colorSpace: String   = js.native
  def dispose(): Unit      = js.native

@js.native
@JSImport("three", "CanvasTexture")
class CanvasTexture(canvas: dom.html.Canvas) extends Texture

@js.native
@JSImport("three", "SpriteMaterial")
class SpriteMaterial(parameters: js.Object) extends js.Object:
  val color: Color    = js.native
  def dispose(): Unit = js.native

object SpriteMaterial:
  def params(map: Texture, transparent: Boolean): js.Object =
    js.Dynamic.literal(map = map, transparent = transparent)

@js.native
@JSImport("three", "Sprite")
class Sprite(spriteMaterial: SpriteMaterial) extends Object3D:
  val material: SpriteMaterial = js.native

// NOT Float32BufferAttribute: that convenience subclass COPIES its input
// (`new Float32Array(array)` in its constructor), which silently orphans a
// caller-held buffer that is mutated per frame. BufferAttribute aliases the
// typed array, so writes + needsUpdate reach the GPU.
@js.native
@JSImport("three", "BufferAttribute")
class BufferAttribute(array: Float32Array, itemSize: Int) extends js.Object:
  var needsUpdate: Boolean = js.native

@js.native
@JSImport("three", "BufferGeometry")
class BufferGeometry extends js.Object:
  def setAttribute(name: String, attribute: BufferAttribute): this.type = js.native
  def dispose(): Unit = js.native

@js.native
@JSImport("three", "LineBasicMaterial")
class LineBasicMaterial(parameters: js.Object) extends js.Object:
  def dispose(): Unit = js.native

object LineBasicMaterial:
  def params(vertexColors: Boolean, transparent: Boolean, opacity: Double): js.Object =
    js.Dynamic.literal(vertexColors = vertexColors, transparent = transparent, opacity = opacity)

@js.native
@JSImport("three", "LineSegments")
class LineSegments(geometry: BufferGeometry, lineMaterial: LineBasicMaterial) extends Object3D

@js.native
trait WebXRManager extends js.Object:
  var enabled: Boolean         = js.native
  val isPresenting: Boolean    = js.native
  def getController(index: Int): Object3D = js.native
  def setSession(session: js.Any): js.Promise[Unit] = js.native
  def addEventListener(tpe: String, listener: js.Function1[js.Any, Unit]): Unit = js.native

@js.native
@JSImport("three", "WebGLRenderer")
class WebGLRenderer(parameters: js.Object) extends js.Object:
  val domElement: dom.html.Canvas = js.native
  val xr: WebXRManager            = js.native
  def setPixelRatio(value: Double): Unit          = js.native
  def setSize(width: Double, height: Double): Unit = js.native
  def render(scene: Object3D, camera: PerspectiveCamera): Unit = js.native
  def setAnimationLoop(callback: js.Function1[Double, Unit] | Null): Unit = js.native
  def dispose(): Unit = js.native

object WebGLRenderer:
  def params(antialias: Boolean, alpha: Boolean): js.Object =
    js.Dynamic.literal(antialias = antialias, alpha = alpha)

@js.native
trait Intersection extends js.Object:
  val distance: Double       = js.native
  @JSName("object")
  val hitObject: Object3D    = js.native

@js.native
trait Ray extends js.Object:
  val origin: Vector3    = js.native
  val direction: Vector3 = js.native

@js.native
@JSImport("three", "Raycaster")
class Raycaster extends js.Object:
  val ray: Ray = js.native
  def setFromCamera(coords: Vector2, camera: PerspectiveCamera): Unit = js.native
  def intersectObjects(objects: js.Array[Object3D], recursive: Boolean): js.Array[Intersection] = js.native

// three maps "three/addons/*" to its examples/jsm/* via the package's export map.
@js.native
@JSImport("three/addons/controls/OrbitControls.js", "OrbitControls")
class OrbitControls(camera: PerspectiveCamera, domElement: dom.Element) extends js.Object:
  var enabled: Boolean       = js.native
  var enableDamping: Boolean = js.native
  var dampingFactor: Double  = js.native
  def update(): Boolean      = js.native
  def dispose(): Unit        = js.native
