package org.jpablo.graphexplorer.viewer.components.scene3d

/** The frustum of an orthographic camera, in the space of the camera. */
final case class OrthoFrustum(left: Double, right: Double, top: Double, bottom: Double)

/** The pure part of the projection switch of the 3D view.
  *
  * The scene keeps one camera rig: a position, a target, and a distance
  * between them. The perspective camera shows that rig directly. The
  * orthographic camera copies the pose of the rig, and its frustum has the
  * size that the perspective camera shows at the target. Thus the plane of the
  * target looks the same in the two projections, and a switch does not jump.
  * A zoom changes the distance of the rig, so it also changes the frustum.
  */
object Projection3D:

  /** The half height of the view at `distance` from a perspective camera.
    * `fovDeg` is the vertical field of view, in degrees.
    */
  def halfHeightAt(distance: Double, fovDeg: Double): Double =
    distance * math.tan(math.toRadians(fovDeg / 2))

  /** The orthographic frustum that shows what a perspective camera shows at
    * `distance`. `aspect` is the width of the viewport divided by its height.
    */
  def matchingFrustum(distance: Double, fovDeg: Double, aspect: Double): OrthoFrustum =
    val halfH = halfHeightAt(distance, fovDeg)
    val halfW = halfH * aspect
    OrthoFrustum(left = -halfW, right = halfW, top = halfH, bottom = -halfH)
