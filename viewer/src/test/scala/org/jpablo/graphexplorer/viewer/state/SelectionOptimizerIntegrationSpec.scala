package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.{MouseActionRect, ClientPoint}
import org.jpablo.graphexplorer.viewer.models.{ElementId, NodeId}

class SelectionOptimizerIntegrationSpec extends FunSuite:
  
  test("SelectionOptimizer should handle empty element collections") {
    val optimizer = new SelectionOptimizer()
    
    val rect = MouseActionRect(
      start = ClientPoint(0, 0),
      end = ClientPoint(100, 100),
      shift = false
    )
    
    val result = optimizer.findElementsInRect(Seq.empty, rect)
    assert(result.isEmpty)
  }
  
  test("SelectionOptimizer should handle empty selection rectangles") {
    val optimizer = new SelectionOptimizer()
    
    val emptyRect = MouseActionRect(
      start = ClientPoint(100, 100),
      end = ClientPoint(100, 100),
      shift = false
    )
    
    val result = optimizer.findElementsInRect(Seq.empty, emptyRect)
    assert(result.isEmpty)
  }
  
  test("SelectionOptimizer should throttle selection updates") {
    val optimizer = new SelectionOptimizer()
    
    // First call should return true
    assert(optimizer.shouldUpdateSelection())
    
    // Immediate second call should return false (throttled)
    assert(!optimizer.shouldUpdateSelection())
    
    // After waiting, should return true again
    // Note: In a real test, you would use a timer or mock the date
    // For now, we'll just test that throttling works initially
    assert(!optimizer.shouldUpdateSelection())
  }
  
  test("SelectionOptimizer should track performance statistics") {
    val optimizer = new SelectionOptimizer()
    
    // Perform some measured operations
    val result1 = optimizer.measurePerformance("test-operation") {
      // Simulate some work (without Thread.sleep which isn't available in Scala.js)
      var sum = 0
      for (i <- 1 to 100) sum += i
      42
    }
    
    val result2 = optimizer.measurePerformance("test-operation") {
      // Simulate more work
      var sum = 0
      for (i <- 1 to 1000) sum += i
      84
    }
    
    assertEquals(result1, 42)
    assertEquals(result2, 84)
    
    val stats = optimizer.getPerformanceStats()
    
    assert(stats.contains("test-operation"))
    val (avg, max, count) = stats("test-operation")
    
    assertEquals(count, 2)
    assert(avg >= 0) // Performance might be 0 for very fast operations
    assert(max >= 0)
    assert(max >= avg)
  }
  
  test("SelectionOptimizer should handle cache invalidation") {
    val optimizer = new SelectionOptimizer()
    
    val elementIds = Set[ElementId](NodeId("test-node-1"), NodeId("test-node-2"))
    
    // Should not throw errors
    optimizer.invalidateCache(elementIds)
    optimizer.clearCache()
    
    // Should work without errors
    assert(optimizer.getPerformanceStats().isEmpty)
  }
  
  test("SelectionOptimizer should handle performance measurement edge cases") {
    val optimizer = new SelectionOptimizer()
    
    // Measure operation that returns Unit
    optimizer.measurePerformance("void-operation") {
      // Do nothing
    }
    
    // Measure operation that throws exception
    try {
      optimizer.measurePerformance("failing-operation") {
        throw new RuntimeException("Test exception")
      }
    } catch {
      case _: RuntimeException => // Expected
    }
    
    val stats = optimizer.getPerformanceStats()
    
    // Should have recorded the void operation
    assert(stats.contains("void-operation"))
    // The failing operation should also be recorded since measurePerformance wraps the exception
    assert(stats.contains("failing-operation"))
  }
  
  test("SelectionOptimizer should reset performance stats") {
    val optimizer = new SelectionOptimizer()
    
    // Add some stats
    optimizer.measurePerformance("test-op") { 42 }
    
    assert(optimizer.getPerformanceStats().nonEmpty)
    
    // Reset stats
    optimizer.resetPerformanceStats()
    
    assert(optimizer.getPerformanceStats().isEmpty)
  }

end SelectionOptimizerIntegrationSpec