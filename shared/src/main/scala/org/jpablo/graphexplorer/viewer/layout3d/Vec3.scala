package org.jpablo.graphexplorer.viewer.layout3d

/** Immutable 3D vector for layout math. A layout type, not a rendering type:
  * the renderer maps these into whatever scene units it uses.
  */
final case class Vec3(x: Double, y: Double, z: Double) derives CanEqual:
  def +(o: Vec3): Vec3   = Vec3(x + o.x, y + o.y, z + o.z)
  def -(o: Vec3): Vec3   = Vec3(x - o.x, y - o.y, z - o.z)
  def *(k: Double): Vec3 = Vec3(x * k, y * k, z * k)

  def dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

  def cross(o: Vec3): Vec3 =
    Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

  /** Rodrigues rotation about a UNIT axis. */
  def rotatedAround(axis: Vec3, angle: Double): Vec3 =
    val cosA = math.cos(angle)
    val sinA = math.sin(angle)
    this * cosA + axis.cross(this) * sinA + axis * (axis.dot(this) * (1 - cosA))

  def length: Double = math.sqrt(x * x + y * y + z * z)

  def isFinite: Boolean =
    java.lang.Double.isFinite(x) && java.lang.Double.isFinite(y) && java.lang.Double.isFinite(z)

object Vec3:
  val zero: Vec3 = Vec3(0, 0, 0)
