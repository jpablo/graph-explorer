package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG

import scala.scalajs.js

class ThumbnailSvgCacheSpec extends FunSuite:

  private def installJsDom(): Unit =
    js.eval(
      """
        const { JSDOM } = require('jsdom');
        const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
        global.window = dom.window;
        global.document = dom.window.document;
        global.DOMParser = dom.window.DOMParser;
        global.Node = dom.window.Node;
        global.Element = dom.window.Element;
        global.HTMLElement = dom.window.HTMLElement;
        global.SVGElement = dom.window.SVGElement;
      """
    )

  test("ThumbnailSvgCache returns mount-safe clones") {
    installJsDom()

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
