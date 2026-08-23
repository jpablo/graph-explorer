package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite

class ViewOnlyChipPulseSpec extends FunSuite:

  test("the pulse leaves horizontal centering to CSS"):
    val transforms = viewOnlyChipPulseKeyframes.toSeq.map(_.transform.asInstanceOf[String])

    assertEquals(transforms, Seq("scale(1)", "scale(1.15)", "scale(1)"))
    assert(transforms.forall(!_.contains("translate")))
