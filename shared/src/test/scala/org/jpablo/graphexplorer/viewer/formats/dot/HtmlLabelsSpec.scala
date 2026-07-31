package org.jpablo.graphexplorer.viewer.formats.dot

import munit.FunSuite

class HtmlLabelsSpec extends FunSuite:

  test("multi-line tables are html (the old UI regex was newline-blind)"):
    assert(HtmlLabels.isHtml("<table>\n  <tr><td>a</td></tr>\n</table>"))

  test("uppercase markup is html (the old serializer sniff was case-sensitive)"):
    assert(HtmlLabels.isHtml("<TABLE><TR><TD>a</TD></TR></TABLE>"))
    assert(HtmlLabels.isHtml("a<BR/>b"))

  test("simple formatting tags are html"):
    assert(HtmlLabels.isHtml("<b>bold</b>"))
    assert(HtmlLabels.isHtml("x <font color=\"red\">y</font>"))

  test("plain labels are not html"):
    assert(!HtmlLabels.isHtml("just text"))
    assert(!HtmlLabels.isHtml("a < b > c"))

  test("record labels are not html (ports are not tags)"):
    assert(!HtmlLabels.isHtml("{<f0> alpha | <f1> beta}"))
