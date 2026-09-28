package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.SvgPoint

class SvgTransformOpsSpec extends FunSuite:

  test("anchoredPan keeps the local point under the pointer when zoom changes"):
    val anchor    = SvgPoint(873.7305003065035, 59.99999546623303)
    val oldPan    = SvgPoint.origin
    val oldZoom   = 1.0
    val newZoom   = 1.1333333333333333
    val corrected = SvgTransformOps.anchoredPan(oldPan, anchor, SvgPoint.origin, oldZoom, newZoom)

    assertEqualsDouble(corrected.x, -102.79182356547102, 1e-9)
    assertEqualsDouble(corrected.y, -7.058822996027417, 1e-9)
    assertEqualsDouble(newZoom * (anchor.x + corrected.x), oldZoom * (anchor.x + oldPan.x), 1e-9)
    assertEqualsDouble(newZoom * (anchor.y + corrected.y), oldZoom * (anchor.y + oldPan.y), 1e-9)

  test("anchoredPan preserves an existing pan offset"):
    val anchor    = SvgPoint(250, 120)
    val oldPan    = SvgPoint(-30, 45)
    val oldZoom   = 2.0
    val newZoom   = 1.25
    val corrected = SvgTransformOps.anchoredPan(oldPan, anchor, SvgPoint.origin, oldZoom, newZoom)

    assertEqualsDouble(newZoom * (anchor.x + corrected.x), oldZoom * (anchor.x + oldPan.x), 1e-9)
    assertEqualsDouble(newZoom * (anchor.y + corrected.y), oldZoom * (anchor.y + oldPan.y), 1e-9)

  test("anchoredPan accounts for a nonzero CSS transform origin"):
    val anchor    = SvgPoint(177.3299865491182, -157.03000144588134)
    val origin    = SvgPoint(379.5, -173)
    val oldPan    = SvgPoint.origin
    val oldZoom   = 1.0
    val newZoom   = 1.1333333333333333
    val corrected = SvgTransformOps.anchoredPan(oldPan, anchor, origin, oldZoom, newZoom)

    assertEqualsDouble(corrected.x, 23.784707464809628, 1e-9)
    assertEqualsDouble(corrected.y, -1.878823359308086, 1e-9)
    assertEqualsDouble(newZoom * (anchor.x + corrected.x - origin.x), oldZoom * (anchor.x + oldPan.x - origin.x), 1e-9)
    assertEqualsDouble(newZoom * (anchor.y + corrected.y - origin.y), oldZoom * (anchor.y + oldPan.y - origin.y), 1e-9)
