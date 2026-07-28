package org.jpablo.graphexplorer.viewer.utils

import org.scalajs.dom

/**
 * Utility object for distance calculations and geometric operations.
 */
object DistanceUtils {

  type Point = (Double, Double)
  
  /**
   * Calculates the Euclidean distance between two points.
   */
  def distance(p1: Point, p2: Point): Double =
    val (x1, y1) = p1
    val (x2, y2) = p2
    math.hypot(x2 - x1, y2 - y1)

  /**
   * Calculates the center point of an SVG bounding box.
   */
  def boundingBoxCenter(bbox: dom.svg.Rect): Point =
    (bbox.x + bbox.width / 2.0, bbox.y + bbox.height / 2.0)

  /**
   * Center of a CLIENT-space rect (`getBoundingClientRect`), as a [[ClientPoint]]
   * so the type system keeps screen-space and SVG-space coordinates apart.
   */
  def clientRectCenter(r: dom.DOMRect): ClientPoint =
    ClientPoint(r.left + r.width / 2.0, r.top + r.height / 2.0)
  
  /**
   * Finds the point closest to a target point from a sequence of candidate points.
   * Returns None if the candidates sequence is empty.
   */
  def findClosestPoint(target: Point, candidates: Seq[Point]): Option[Point] =
    candidates.minByOption(distance(target,_))
  
  /**
   * Finds the point closest to a target point from a sequence of candidate points,
   * but only considers points within the specified maximum distance threshold.
   * Returns None if no candidates are within the threshold or if candidates is empty.
   */
  def findClosestPointWithinThreshold(target: Point, candidates: Seq[Point], maxDistance: Double): Option[Point] = {
    val withinThreshold = candidates.filter(p => distance(target, p) <= maxDistance)
    findClosestPoint(target, withinThreshold)
  }
  
  /**
   * Checks if a point is within a specified distance from a target point.
   */
  def isWithinDistance(target: Point, point: Point, maxDistance: Double): Boolean = {
    distance(target, point) <= maxDistance
  }
}