package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** Node-level `image=` / `shape=image` (Graphviz `poly_init` image sizing +
  * `gvrender_usershape` placement). The node box grows to hold the drawn image
  * (`bb = max(label, drawnImage + 2)`); the image is drawn at natural size
  * (SCALE default FALSE) and centred in the box where it is smaller. Dimensions
  * come from the `<name>.images.json` sidecar (viz-js's `images` option). Scoped
  * to box-family shapes — an ellipse's SQRT2 containment fit is not modelled. */
class NodeImageSpec extends FunSuite:
  private def g(n: String) =
    val r = OracleHarness.corpusGraph(n)
    r.copy(images = OracleHarness.corpusImages(n))

  private val cases = List(
    "64-nodeimage", "65-nodeimagebox", "66-nodeimagewh",
    // ellipse/circle: the bb (with the image) is inflated ×SQRT2 to contain the
    // image, then the image is centred in the bounding box (fractional coords).
    "67-ellipseimage", "68-circleimage", "69-ellipseimagesm",
    // imagepos: place a smaller image in a corner/edge of the node box.
    "73-nodeimgpostl", "74-nodeimgposbr", "75-nodeimgpostc",
    // convex polygons: image centred in the vertex bounding box (triangle tests
    // that the node centre = bbox centre even for a vertically-asymmetric shape).
    "76-diamondimage", "77-triangleimage"
  )

  cases.foreach { name =>
    test(s"$name: dot_json byte-exact (node image size)"):
      assertEquals(Output.dotJson(g(name)), OracleHarness.golden(name, "dot_json"))
    test(s"$name: svg byte-exact (box border + <image> + label)"):
      assertEquals(Svg.svg(g(name)), OracleHarness.golden(name, "svg"))
  }
