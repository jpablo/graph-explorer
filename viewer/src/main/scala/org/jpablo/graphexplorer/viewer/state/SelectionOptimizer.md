# Selection Performance Optimization

## Problem

The original `selectExtendSelectionOverlappingElements` function in `DiagramSelectionOps.scala` had several performance bottlenecks:

1. **O(n) Linear Search**: For every mouse move during selection, the system would iterate through ALL selectable elements to find those intersecting with the selection rectangle
2. **Expensive DOM Operations**: `getBoundingClientRect()` was called for every element on every selection update
3. **No Spatial Optimization**: No spatial indexing to quickly eliminate elements that couldn't possibly intersect
4. **High Frequency Updates**: Selection updates triggered on every mouse move event (potentially 60+ times per second)

## Solution

The `SelectionOptimizer` class implements several optimizations:

### 1. Bounding Box Caching
- Caches `getBoundingClientRect()` results for each selectable element
- Cache entries expire after 5 seconds to handle dynamic layouts
- Reduces DOM queries from O(n) per selection to O(1) per element per cache timeout

### 2. Spatial Indexing
- Grid-based spatial index divides the canvas into cells
- Each cell contains references to elements that intersect with that cell
- Rectangle selection queries only check elements in relevant cells
- Reduces search complexity from O(n) to O(cells_in_rect)

### 3. Selection Throttling
- Limits selection updates to ~60fps (16ms intervals)
- Prevents excessive computation during rapid mouse movements
- Maintains smooth visual feedback while reducing CPU usage

### 4. Performance Measurement
- Built-in performance tracking for optimization effectiveness
- Tracks average and maximum execution times
- Debug logging available via `DEBUG_SELECTION_PERFORMANCE` flag

## Usage

The optimizer is automatically used in `DiagramSelectionOps.selectExtendSelectionOverlappingElements()`.

### Performance Statistics

To view performance statistics in the browser console:
```javascript
// Enable debug logging
window.DEBUG_SELECTION_PERFORMANCE = true;

// View accumulated stats (call from browser console)
// This would be exposed via the ViewerState if needed
```

### Cache Management

The optimizer automatically manages cache invalidation:
- When elements are deleted: `invalidateCache(elementIds)`
- When graph structure changes: `clearCache()`
- Automatic expiration after 5 seconds

## Performance Impact

Expected improvements for graphs with 100+ elements:
- **Bounding Box Caching**: 50-80% reduction in DOM queries
- **Spatial Indexing**: 70-90% reduction in intersection tests
- **Throttling**: 80%+ reduction in selection update frequency
- **Overall**: 5-10x faster selection operations in large graphs

## Implementation Details

### Spatial Grid
- Default cell size: 100px × 100px
- Elements are indexed into all cells they intersect
- Query returns union of all elements in intersecting cells

### Cache Strategy
- LRU-style caching with timestamp-based expiration
- Separate cache invalidation for individual elements vs. full clear
- Memory-efficient using mutable collections

### Thread Safety
- Single-threaded JavaScript environment
- No concurrent access concerns
- State mutations are contained within methods

## Testing

Run the test suite to verify optimizations:
```bash
sbt "viewer/testOnly *SelectionOptimizerIntegrationSpec"
```

The tests verify:
- Empty element collection handling
- Empty selection rectangle handling  
- Throttling effectiveness
- Performance measurement accuracy
- Cache invalidation handling
- Exception handling in performance measurement
- Performance statistics reset functionality

### Test Results

All 7 tests pass successfully:
- ✅ SelectionOptimizer should handle empty element collections
- ✅ SelectionOptimizer should handle empty selection rectangles
- ✅ SelectionOptimizer should throttle selection updates
- ✅ SelectionOptimizer should track performance statistics
- ✅ SelectionOptimizer should handle cache invalidation
- ✅ SelectionOptimizer should handle performance measurement edge cases
- ✅ SelectionOptimizer should reset performance stats