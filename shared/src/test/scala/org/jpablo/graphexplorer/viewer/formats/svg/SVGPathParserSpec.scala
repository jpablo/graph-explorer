package org.jpablo.graphexplorer.viewer.formats.svg

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.svg.PathCommand.*

class SVGPathParserSpec extends FunSuite:

  test("parses Graphviz-style paths (comma within pairs, spaces between pairs)"):
    val d = "M657.85,204.19C662.29,204.19 666.83,204.19 671.37,204.19"
    val parsed = SVGPathParser.parse(d)
    assert(parsed.isRight, s"Should parse, got: $parsed")
    val cmds = parsed.toOption.get
    assertEquals(cmds.length, 2)
    assert(cmds.last.isInstanceOf[CurveTo])

  test("parses Mermaid-style paths (commas between ALL values)"):
    // Verbatim `d` of a rendered Mermaid 11 edge. Failing to parse this silently
    // froze the endpoint-drag preview (its getOrElse falls back to the original
    // path, so the line never followed the pointer).
    val d =
      "M659.852,204.194L669.018,206.934C678.184,209.674,696.516,215.153,714.848,217.893" +
        "C733.18,220.633,751.512,220.633,774.025,234.896C796.539,249.159,823.234,277.686," +
        "836.581,291.949L849.929,306.212"
    val parsed = SVGPathParser.parse(d)
    assert(parsed.isRight, s"Should parse, got: $parsed")
    val cmds = parsed.toOption.get
    assertEquals(cmds.length, 6)
    // The endpoint-drag preview updates the LAST command's final point
    cmds.last match
      case LineTo(true, List(p)) =>
        assertEquals(p.x, 849.929)
        assertEquals(p.y, 306.212)
      case other => fail(s"Expected a single-point absolute LineTo, got: $other")

  test("round-trips through toData (output stays parseable)"):
    val d = "M659.852,204.194L669.018,206.934C678.184,209.674,696.516,215.153,714.848,217.893"
    val once  = SVGPathParser.parse(d).toOption.get
    val twice = SVGPathParser.parse(PathCommand.toData(once))
    assertEquals(twice, Right(once))

  test("parses comma-separated H/V and arc commands"):
    val parsed = SVGPathParser.parse("M0,0H10,20V5,15A1,1,0,0,1,10,10")
    assert(parsed.isRight, s"Should parse, got: $parsed")
