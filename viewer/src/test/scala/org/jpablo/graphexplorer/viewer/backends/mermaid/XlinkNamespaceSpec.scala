package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.scalajs.dom

/** Mermaid's C4 renderer emits `<image xlink:href="...">` (embedded Person/System icons)
  * without declaring `xmlns:xlink` on the svg root. The strict XML parser used by parseSVG
  * rejects the whole document over the undeclared prefix, so C4 diagrams rendered as a blank
  * canvas. [[MermaidBackend.declareMissingXlinkNamespace]] must repair the string first.
  */
class XlinkNamespaceSpec extends FunSuite:

  private def xmlParse(svgText: String): dom.Document =
    dom.DOMParser().parseFromString(svgText, dom.MIMEType.`image/svg+xml`)

  private val c4Like =
    """<svg id="m1" width="100%" viewBox="0 -70 1148 474" xmlns="http://www.w3.org/2000/svg">
      |  <image width="48" height="48" xlink:href="data:image/png;base64,AAAA"/>
      |</svg>""".stripMargin

  test("an undeclared xlink prefix (C4 output) gains the xmlns:xlink declaration"):
    val fixed = MermaidBackend.declareMissingXlinkNamespace(c4Like)
    assert(fixed.contains("""xmlns:xlink="http://www.w3.org/1999/xlink""""))
    val doc = xmlParse(fixed)
    assertEquals(doc.documentElement.tagName, "svg", "the XML parser must accept the repaired document")
    assert(doc.querySelector("parsererror") == null)

  test("the unrepaired C4-like string is indeed rejected by the XML parser (fixture sanity)"):
    val doc = xmlParse(c4Like)
    assert(
      doc.documentElement.tagName != "svg" || doc.querySelector("parsererror") != null,
      "if this starts passing, mermaid fixed the namespace upstream and the helper can go"
    )

  test("a document that already declares xmlns:xlink is left unchanged"):
    val declared = c4Like.replaceFirst("<svg", """<svg xmlns:xlink="http://www.w3.org/1999/xlink"""")
    assertEquals(MermaidBackend.declareMissingXlinkNamespace(declared), declared)

  test("a document with no xlink references is left unchanged"):
    val plain = """<svg xmlns="http://www.w3.org/2000/svg"><rect width="1" height="1"/></svg>"""
    assertEquals(MermaidBackend.declareMissingXlinkNamespace(plain), plain)
