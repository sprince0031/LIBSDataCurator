# MatWeb Composition Data Caching Feature

## Overview
The LIBSDataCurator now includes caching for scraped composition data from MatWeb to avoid repeated network requests for materials that have already been processed.

## How it Works
When processing multiple series of materials (e.g., using the `-s` option), the application now:

1. **Checks cache first**: Before scraping a material GUID from MatWeb, it checks if that GUID has already been processed
2. **Reuses cached data**: If found, uses the cached composition data instead of making a new network request
3. **Preserves series context**: Creates a new MaterialGrade with the cached composition but maintains the current series context (overview GUID, series key)

## Performance Benefits
This feature is especially useful for:
- **Coated materials**: Same base material with different coatings
- **Cross-series materials**: Materials that appear in multiple steel series
- **Large batch processing**: Reducing network overhead when processing many materials

## Example Scenario
Consider processing multiple steel series where some materials overlap:

```bash
# This command will process the same base steel in multiple series if using the out-of-box materials catalogue file
./run.sh -s "Zn-5.0.coated.steels,Sn-1.2.coated.steels"
```

**Without caching**: Each duplicate GUID triggers a new MatWeb request
**With caching**: Duplicate GUIDs are detected and cached data is reused

## Log Output
When caching is active, you'll see log messages like:
```
INFO: Processing material GUID: b76f3b18bd814e449d3a8b4a906af771 from series: Zn-5.0.coated.steels
INFO: Material GUID: b76f3b18bd814e449d3a8b4a906af771 already processed. Using cached data.
```

## Technical Details
- **Cache storage**: In-memory using the existing `materialGrades` List in `InputCompositionProcessor`
- **Cache key**: Material GUID (MatWeb identifier)
- **Cache scope**: Per application run (not persistent across runs)
- **Thread safety**: Single-threaded cache access

## Implementation
The caching logic is implemented in:
- `InputCompositionProcessor.getMaterialsList()` - Main processing loop with cache check
- `InputCompositionProcessor.findMaterialByGuid()` - Helper method to search cache

See the test files for detailed examples:
- `InputCompositionProcessorCacheTest.java` - Unit tests for cache functionality
- `InputCompositionProcessorCacheIntegrationTest.java` - Integration tests demonstrating real-world scenarios