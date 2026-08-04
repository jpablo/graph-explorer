package org.jpablo.graphexplorer.viewer.formats.dot

import munit.FunSuite

class LabelSummarySpec extends FunSuite:

  // The shape from the task-board example: a title cell beside a number column.
  private val taskTable =
    """<table border="1" cellborder="0" cellspacing="0">
      |  <tr><td><b>Task 1</b></td><td>1</td></tr>
      |  <tr><td>Choose Menu</td><td>2</td></tr>
      |  <tr><td bgcolor="yellow"><font color="green">done</font></td><td>3</td></tr>
      |</table>""".stripMargin

  test("a table summarizes to its first meaningful cell, not the whole grid"):
    assertEquals(LabelSummary.short(taskTable), "Task 1")

  test("a weak first cell pulls in the next one"):
    // a numbering column first: "1" alone identifies nothing
    val numbered = """<table><tr><td>1</td><td>Buy ingredients</td></tr></table>"""
    assertEquals(LabelSummary.short(numbered), "1 Buy ingredients")

  test("a symbol-only first cell pulls in the next one"):
    val icon = """<table><tr><td>✓</td><td>Cook</td></tr></table>"""
    assertEquals(LabelSummary.short(icon), "✓ Cook")

  test("an empty first cell is skipped entirely"):
    val leading = """<table><tr><td></td><td>Send invitation</td></tr></table>"""
    assertEquals(LabelSummary.short(leading), "Send invitation")

  test("pulling in extra parts stops after a few"):
    val allWeak = """<table><tr><td>1</td><td>2</td><td>3</td><td>4</td></tr></table>"""
    assertEquals(LabelSummary.short(allWeak), "1 2 3")

  test("markup never reaches the summary"):
    val summary = LabelSummary.short(taskTable)
    assert(!summary.contains("<"), s"markup leaked: $summary")
    assert(!summary.contains("cellborder"), s"attributes leaked: $summary")

  test("html text labels lose their tags"):
    assertEquals(LabelSummary.short("<b>bold</b> and <i>italic</i>"), "bold and italic")

  test("nested tables flatten in reading order"):
    val nested = """<table><tr><td><table><tr><td>Inner</td></tr></table></td><td>Outer</td></tr></table>"""
    // the summary stops at the first strong cell — the nested one comes first
    assertEquals(LabelSummary.short(nested), "Inner")
    assertEquals(LabelSummary.full(nested), "Inner Outer")

  test("record labels summarize to their first field"):
    assertEquals(LabelSummary.short("{<f0> alpha | <f1> beta}", isRecord = true), "alpha")

  test("record escapes are unescaped, not shown raw"):
    assertEquals(LabelSummary.short("{<f0> a \\| b | <f1> c}", isRecord = true), "a | b")

  test("plain labels unescape and collapse to one line"):
    assertEquals(LabelSummary.short("two\\nlines"), "two lines")

  test("long summaries truncate with an ellipsis"):
    val long = "<table><tr><td>" + "x" * 100 + "</td></tr></table>"
    val s    = LabelSummary.short(long, maxLen = 12)
    assertEquals(s.length, 12)
    assert(s.endsWith("…"))

  test("full text keeps every cell — this is what search matches"):
    val f = LabelSummary.full(taskTable)
    assert(f.contains("Choose Menu"), f)
    assert(f.contains("done"), f)
    assert(!f.contains("cellborder"), s"attribute names must not be searchable: $f")

  test("markup the engine cannot render falls back to the raw value"):
    // <span> is outside the html-label subset, so the parser rejects the whole
    // label — the engine will not render it either, and inventing text for it
    // would hide that.
    val unsupported = "<span>nope</span>"
    assertEquals(LabelSummary.short(unsupported, maxLen = 100), unsupported)

  test("a truncated-but-parseable table still summarizes (the parser is lenient)"):
    assertEquals(LabelSummary.short("<table><tr><td>unclosed", maxLen = 100), "unclosed")

  test("an empty label summarizes to nothing (callers fall back to the id)"):
    assertEquals(LabelSummary.short(""), "")

  // ---- lines (the 3D pill's stacked view) --------------------------------

  test("lines keeps an HTML text label's BR structure"):
    // label VALUES carry the markup without DOT's outer <...> delimiters
    val dep = "org.scala-js<BR/>scalajs-library_2.13<BR/>1.7.1"
    assertEquals(LabelSummary.lines(dep), Vector("org.scala-js", "scalajs-library_2.13", "1.7.1"))

  test("lines splits a plain label on \\n and justified breaks"):
    assertEquals(LabelSummary.lines("""first\nsecond\lthird"""), Vector("first", "second", "third"))

  test("lines of a record label are its fields"):
    assertEquals(LabelSummary.lines("RecordName|{x|y}", isRecord = true), Vector("RecordName", "x", "y"))

  test("lines caps the count with an ellipsis line"):
    val many = (1 to 12).map(i => s"line$i").mkString("""\n""")
    val ls   = LabelSummary.lines(many, maxLines = 4)
    assertEquals(ls, Vector("line1", "line2", "line3", "…"))

  test("a single-line label is a single line"):
    assertEquals(LabelSummary.lines("plain"), Vector("plain"))
