package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG

class ThumbnailSvgCacheSpec extends FunSuite:

  test("ThumbnailSvgCache returns mount-safe clones") {

    val svgHtml =
      "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><circle cx='5' cy='5' r='4'/></svg>"

    val source = "digraph G { a }"
    val proto  = parseSVG(svgHtml).ref

    ThumbnailSvgCache.put(DiagramFormat.DOT, source, proto)

    val cachedProto = ThumbnailSvgCache.get(DiagramFormat.DOT, source).getOrElse(fail("Expected cached SVG prototype"))
    val a           = ThumbnailSvgCache.cloneSvg(cachedProto)
    val b           = ThumbnailSvgCache.cloneSvg(cachedProto)

    assert(a.ref ne b.ref)
    assertEquals(a.ref.outerHTML, b.ref.outerHTML)
  }
