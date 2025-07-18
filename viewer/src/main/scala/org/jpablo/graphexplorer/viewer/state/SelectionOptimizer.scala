package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.utils.MouseActionRect
import org.jpablo.graphexplorer.viewer.models.ElementId

import scala.collection.mutable
import scala.scalajs.js

/**
 * Optimizes selection operations by caching bounding boxes and implementing spatial indexing.
 * This reduces the O(n) complexity of rectangle-based selection operations.
 */
class SelectionOptimizer:
  
  private case class CachedElement(
    element: SelectableElement,
    bbox: dom.DOMRect,
    timestamp: Double
  )
  
  private val cache = mutable.Map[ElementId, CachedElement]()
  private val cacheTimeout = 5000.0 // 5 seconds cache timeout
  
  /**
   * Spatial index using a simple grid-based approach for O(1) spatial queries.
   * Each cell contains elements that intersect with that grid cell.
   */
  private case class SpatialGrid(
    cellSize: Double = 100.0,
    cells: mutable.Map[(Int, Int), mutable.Set[ElementId]] = mutable.Map()
  ):
    def addElement(elementId: ElementId, bbox: dom.DOMRect): Unit =
      val (minCellX, minCellY) = (
        math.floor(bbox.left / cellSize).toInt,
        math.floor(bbox.top / cellSize).toInt
      )
      val (maxCellX, maxCellY) = (
        math.floor(bbox.right / cellSize).toInt,
        math.floor(bbox.bottom / cellSize).toInt
      )
      
      for
        x <- minCellX to maxCellX
        y <- minCellY to maxCellY
      do
        val cellKey = (x, y)
        cells.getOrElseUpdate(cellKey, mutable.Set()).add(elementId)
    
    def query(rect: MouseActionRect): Set[ElementId] =
      val x = rect.start.x min rect.end.x
      val y = rect.start.y min rect.end.y
      val width = math.abs(rect.end.x - rect.start.x)
      val height = math.abs(rect.end.y - rect.start.y)
      
      val (minCellX, minCellY) = (
        math.floor(x / cellSize).toInt,
        math.floor(y / cellSize).toInt
      )
      val (maxCellX, maxCellY) = (
        math.floor((x + width) / cellSize).toInt,
        math.floor((y + height) / cellSize).toInt
      )
      
      val candidates = mutable.Set[ElementId]()
      for
        cx <- minCellX to maxCellX
        cy <- minCellY to maxCellY
      do
        cells.get((cx, cy)).foreach(candidates ++= _)
      
      candidates.toSet
    
    def clear(): Unit = cells.clear()
  
  private var spatialIndex = SpatialGrid()
  private var lastUpdateTime = 0.0
  
  /**
   * Gets cached bounding box for an element, computing it if not cached or expired.
   */
  def getCachedBBox(element: SelectableElement): dom.DOMRect =
    val now = js.Date.now()
    cache.get(element.elementId) match
      case Some(cached) if (now - cached.timestamp) < cacheTimeout =>
        cached.bbox
      case _ =>
        val bbox = element.ref.getBoundingClientRect()
        cache(element.elementId) = CachedElement(element, bbox, now)
        bbox
  
  /**
   * Optimized rectangle intersection test using cached bounding boxes.
   */
  def isElementInRect(element: SelectableElement, rect: MouseActionRect): Boolean =
    val bbox = getCachedBBox(element)
    val x = rect.start.x min rect.end.x
    val y = rect.start.y min rect.end.y
    val width = math.abs(rect.end.x - rect.start.x)
    val height = math.abs(rect.end.y - rect.start.y)
    
    !(bbox.right < x ||
      bbox.left > x + width ||
      bbox.bottom < y ||
      bbox.top > y + height)
  
  /**
   * Builds spatial index for all selectable elements.
   */
  def buildSpatialIndex(elements: Seq[SelectableElement]): Unit =
    val now = js.Date.now()
    if (now - lastUpdateTime) < 1000.0 then return // Don't rebuild too frequently
    
    spatialIndex.clear()
    
    elements.foreach: element =>
      val bbox = getCachedBBox(element)
      spatialIndex.addElement(element.elementId, bbox)
    
    lastUpdateTime = now
  
  /**
   * Optimized selection using spatial indexing.
   * Returns only elements that potentially intersect with the rectangle.
   */
  def findElementsInRect(
    allElements: Seq[SelectableElement], 
    rect: MouseActionRect
  ): Seq[SelectableElement] =
    if rect.isEmpty then return Seq.empty
    
    // Use spatial index to get candidates
    val candidates = spatialIndex.query(rect)
    
    // Filter candidates using precise intersection test
    allElements.filter: element =>
      candidates.contains(element.elementId) && isElementInRect(element, rect)
  
  /**
   * Invalidates cache for specific elements (call when graph changes).
   */
  def invalidateCache(elementIds: Set[ElementId]): Unit =
    elementIds.foreach(cache.remove)
  
  /**
   * Clears all caches (call when graph structure changes significantly).
   */
  def clearCache(): Unit =
    cache.clear()
    spatialIndex.clear()
    lastUpdateTime = 0.0
  
  /**
   * Throttling mechanism to limit how often expensive operations are performed.
   */
  private var lastSelectionUpdate = 0.0
  private val selectionThrottleMs = 16.0 // ~60fps, 16ms between updates
  
  /**
   * Checks if enough time has passed since last selection update.
   */
  def shouldUpdateSelection(): Boolean =
    val now = js.Date.now()
    if (now - lastSelectionUpdate) >= selectionThrottleMs then
      lastSelectionUpdate = now
      true
    else
      false
  
  /**
   * Performance measurement utilities for tracking optimization effectiveness.
   */
  private var performanceStats = mutable.Map[String, mutable.ListBuffer[Double]]()
  
  def measurePerformance[T](operation: String)(block: => T): T =
    val start = js.Date.now()
    try
      val result = block
      val end = js.Date.now()
      val duration = end - start
      
      performanceStats.getOrElseUpdate(operation, mutable.ListBuffer()).append(duration)
      
      // Log performance if enabled (for debugging)
      try
        val debugEnabled = js.Dynamic.global.DEBUG_SELECTION_PERFORMANCE.asInstanceOf[js.UndefOr[Boolean]]
        if debugEnabled.isDefined && debugEnabled.get then
          js.Dynamic.global.console.log(s"[$operation] ${duration}ms")
      catch
        case _: Exception => // Ignore errors in debug logging
      
      result
    catch
      case ex: Exception =>
        val end = js.Date.now()
        val duration = end - start
        
        // Record the performance even if the operation failed
        performanceStats.getOrElseUpdate(operation, mutable.ListBuffer()).append(duration)
        
        // Re-throw the exception
        throw ex
  
  def getPerformanceStats(): Map[String, (Double, Double, Int)] =
    performanceStats.view.mapValues: times =>
      val avg = times.sum / times.length
      val max = times.max
      val count = times.length
      (avg, max, count)
    .toMap
  
  def resetPerformanceStats(): Unit =
    performanceStats.clear()

end SelectionOptimizer