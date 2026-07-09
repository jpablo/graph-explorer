package org.jpablo.graphexplorer.viewer.components.attributes.views

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat

class DiagramAttributesViewsSpec extends FunSuite:

  test("every DiagramFormat has a diagram-attributes view (no silent fallback)"):
    // DiagramAttributesViews.byFormat is a parallel enumeration (it isn't driven by the backend
    // registry), so this guards against adding a new DiagramFormat while forgetting its view.
    val missing = DiagramFormat.values.toSet.diff(DiagramAttributesViews.knownFormats)
    assert(
      missing.isEmpty,
      s"DiagramFormat(s) without a diagram-attributes view: $missing. Add an entry to DiagramAttributesViews.byFormat."
    )
