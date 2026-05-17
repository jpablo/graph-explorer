package org.jpablo.graphexplorer.graphviz

import munit.FunSuite

/** Locks the version-alignment invariant from PORT.md §2.4: the goldens MUST
  * have been captured from the same Graphviz the oracle bundles (13.0.1). If
  * `@viz-js/viz` is ever bumped without re-pinning the reference worktree and
  * regenerating goldens, this fails loudly in CI rather than silently drifting.
  */
class OracleContractSpec extends FunSuite:

  test("golden corpus was captured (run `node graphviz/oracle/capture.mjs`)"):
    assert(OracleHarness.goldenDir.isDirectory, "graphviz/golden missing")
    assert(OracleHarness.corpusNames.nonEmpty, "no corpus files found")

  test("oracle Graphviz version is pinned at 13.0.1"):
    assertEquals(OracleHarness.goldenGraphvizVersion, Some("13.0.1"))

  test("every corpus file rendered successfully in every format"):
    val meta = OracleHarness.metaJson
    // crude but dependency-free: no format entry may have recorded a failure
    assert(
      !meta.contains("\"status\": \"failure\"") && !meta.contains("\"status\": \"error\""),
      "some corpus/format combination did not render with the oracle"
    )

end OracleContractSpec
