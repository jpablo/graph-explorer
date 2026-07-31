package org.jpablo.graphexplorer.viewer.formats.dot

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.graphviz.html.{HtmlAlign, HtmlCell, HtmlFont, HtmlItem, HtmlLabel, HtmlSpan, HtmlTable, HtmlText}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class HtmlLabelOpsSpec extends ScalaCheckSuite:

  // All fields before the first test(): -Wsafe-init.

  private val genRunText: Gen[String] =
    Gen.choose(1, 8).flatMap: n =>
      Gen
        .listOfN(
          n,
          Gen.frequency(
            8 -> Gen.alphaNumChar,
            2 -> Gen.const(' '),
            1 -> Gen.oneOf('<', '>', '&', '.', ',', '-')
          )
        )
        .map(_.mkString)

  private val genBool = Gen.oneOf(true, false)

  private val genFont: Gen[HtmlFont] =
    for
      size      <- Gen.option(Gen.choose(6, 30).map(_.toDouble))
      name      <- Gen.option(Gen.identifier.map(_.take(6)))
      color     <- Gen.option(Gen.oneOf("red", "blue", "gray50"))
      bold      <- genBool
      italic    <- genBool
      underline <- genBool
      strike    <- genBool
      sub       <- genBool
      sup       <- genBool
    yield HtmlFont(size, name, color, bold, italic, underline, strike, sub, sup)

  private val genSpan: Gen[HtmlSpan] =
    for
      n     <- Gen.choose(0, 3)
      items <- Gen.listOfN(n, for s <- genRunText; f <- genFont yield HtmlItem(s, f))
      align <- Gen.option(Gen.oneOf(HtmlAlign.Left, HtmlAlign.Center, HtmlAlign.Right))
    yield HtmlSpan(items, align)

  private val genTextBlock: Gen[HtmlText] =
    Gen.choose(1, 3).flatMap(n => Gen.listOfN(n, genSpan)).map(spans => HtmlLabelOps.normalizeText(HtmlText(spans)))

  private val genCellAttrs: Gen[Map[String, String]] =
    for
      bg   <- Gen.option(Gen.oneOf("lightblue", "gray90"))
      port <- Gen.option(Gen.identifier.map(_.take(5)))
      cs   <- Gen.option(Gen.choose(1, 3))
    yield Map.empty[String, String]
      ++ bg.map("bgcolor" -> _)
      ++ port.map("port" -> _)
      ++ cs.map(c => "colspan" -> c.toString)

  private def genCell(depth: Int): Gen[HtmlCell] =
    for
      attrs <- genCellAttrs
      content <-
        if depth <= 0 then genTextBlock.map(HtmlLabel.Text.apply)
        else
          Gen.frequency(
            4 -> genTextBlock.map(HtmlLabel.Text.apply),
            1 -> genTable(depth - 1).map(HtmlLabel.Table.apply)
          )
    yield HtmlCell(content, attrs)

  private def genTable(depth: Int): Gen[HtmlTable] =
    for
      nr   <- Gen.choose(1, 3)
      rows <- Gen.listOfN(nr, Gen.choose(1, 3).flatMap(nc => Gen.listOfN(nc, genCell(depth))))
      tattrs <- for
        b  <- Gen.option(Gen.choose(0, 2))
        cb <- Gen.option(Gen.choose(0, 1))
        bg <- Gen.option(Gen.const("white"))
      yield Map.empty[String, String]
        ++ b.map(v => "border" -> v.toString)
        ++ cb.map(v => "cellborder" -> v.toString)
        ++ bg.map("bgcolor" -> _)
      hrs <- Gen.someOf(0 to nr).map(_.toSet)
      vrs <- Gen.someOf(0 to rows.head.length).map(_.toSet)
    yield HtmlLabelOps.withDerived(rows, tattrs, hrs, vrs)

  private val simple = "<table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>"

  // ── properties ───────────────────────────────────────────────────────────

  property("parseTable ∘ printTable = identity on canonical tables"):
    forAll(genTable(1)): tbl =>
      assertEquals(HtmlLabelOps.parseTable(HtmlLabelOps.printTable(tbl)), Some(tbl))

  property("cell text round-trips through the dialog form"):
    forAll(genTable(0)): tbl =>
      val paths = HtmlLabelOps.declaredPaths(tbl)
      paths.headOption.foreach: p =>
        val cell    = HtmlLabelOps.cellAt(tbl, p).get
        val display = HtmlLabelOps.cellDisplayText(cell)
        val back    = HtmlLabelOps.setCellText(tbl, p, display)
        assertEquals(
          HtmlLabelOps.cellAt(back, p).map(_.content),
          Some(cell.content)
        )

  // ── unit tests ───────────────────────────────────────────────────────────

  test("parse + print of a plain 2x2 table is canonical"):
    val t = HtmlLabelOps.parseTable(simple).get
    assertEquals(HtmlLabelOps.parseTable(HtmlLabelOps.printTable(t)), Some(t))
    assertEquals(HtmlLabelOps.declaredPaths(t), Vector(List(0, 0), List(0, 1), List(1, 0), List(1, 1)))

  test("uppercase corpus-style markup parses and canonicalizes"):
    val t = HtmlLabelOps.parseTable("""<TABLE BORDER="0" CELLBORDER="1"><TR><TD PORT="p1">x</TD></TR></TABLE>""").get
    assertEquals(t.border, 0)
    assertEquals(t.cellborder, Some(1))
    assertEquals(HtmlLabelOps.ports(t), Set("p1"))
    assertEquals(HtmlLabelOps.parseTable(HtmlLabelOps.printTable(t)), Some(t))

  test("styled text round-trips (bold + font + br alignment)"):
    val markup = """<table><tr><td><b>hi</b><br align="left"/><font color="red">lo</font></td></tr></table>"""
    val t      = HtmlLabelOps.parseTable(markup).get
    assertEquals(HtmlLabelOps.parseTable(HtmlLabelOps.printTable(t)), Some(t))

  test("hr and vr boundaries survive the round trip"):
    val markup = "<table><tr><td>a</td><vr/><td>b</td></tr><hr/><tr><td>c</td><td>d</td></tr></table>"
    val t      = HtmlLabelOps.parseTable(markup).get
    assertEquals(t.hrAfter, Set(1))
    assertEquals(t.vrAfter, Set(1))
    assertEquals(HtmlLabelOps.parseTable(HtmlLabelOps.printTable(t)), Some(t))

  test("insertRow adds a row of empty cells and shifts hr boundaries"):
    val t  = HtmlLabelOps.parseTable("<table><tr><td>a</td><td>b</td></tr><hr/><tr><td>c</td><td>d</td></tr></table>").get
    val t2 = HtmlLabelOps.insertRow(t, 1)
    assertEquals(t2.rows.length, 3)
    assertEquals(t2.rows(1).length, 2)
    assertEquals(t2.hrAfter, Set(2))

  test("deleteRow removes and never leaves an empty table"):
    val t  = HtmlLabelOps.parseTable("<table><tr><td>a</td></tr></table>").get
    val t2 = HtmlLabelOps.deleteRow(t, 0)
    assertEquals(t2.rows, List(List(HtmlLabelOps.emptyCell)))

  test("insertCol / deleteCol act on every row"):
    val t  = HtmlLabelOps.parseTable(simple).get
    val t2 = HtmlLabelOps.insertCol(t, 1)
    assert(t2.rows.forall(_.length == 3))
    val t3 = HtmlLabelOps.deleteCol(t2, 1)
    assertEquals(t3.rows.map(_.length), List(2, 2))

  test("setCellText: plain text with newlines becomes line breaks"):
    val t  = HtmlLabelOps.parseTable(simple).get
    val t2 = HtmlLabelOps.setCellText(t, List(0, 0), "one\ntwo")
    val cell = HtmlLabelOps.cellAt(t2, List(0, 0)).get
    cell.content match
      case HtmlLabel.Text(txt) => assertEquals(txt.spans.map(_.items.map(_.str).mkString), List("one", "two"))
      case other               => fail(s"expected text content, got $other")
    assertEquals(HtmlLabelOps.cellDisplayText(cell), "one\ntwo")

  test("setCellText: markup input stays markup"):
    val t    = HtmlLabelOps.parseTable(simple).get
    val t2   = HtmlLabelOps.setCellText(t, List(0, 0), "<b>bold</b>")
    val cell = HtmlLabelOps.cellAt(t2, List(0, 0)).get
    cell.content match
      case HtmlLabel.Text(txt) => assertEquals(txt.spans.head.items.head.font.bold, true)
      case other               => fail(s"expected text content, got $other")

  test("setCellAttr sets and clears (port minting path)"):
    val t  = HtmlLabelOps.parseTable(simple).get
    val t2 = HtmlLabelOps.setCellAttr(t, List(1, 1), "port", Some("f0"))
    assertEquals(HtmlLabelOps.ports(t2), Set("f0"))
    assertEquals(HtmlLabelOps.ports(HtmlLabelOps.setCellAttr(t2, List(1, 1), "port", None)), Set.empty[String])

  test("effectiveCellBorder: cell wins, then cellborder, then the table border"):
    val plain = HtmlLabelOps.parseTable("<table><tr><td>a</td></tr></table>").get
    // the parser substitutes gv's DEFAULT_BORDER when the attr is absent
    assertEquals(HtmlLabelOps.effectiveCellBorder(plain, Map.empty), 1)

    val borderless = HtmlLabelOps.parseTable("""<table border="0"><tr><td>a</td></tr></table>""").get
    assertEquals(HtmlLabelOps.effectiveCellBorder(borderless, Map.empty), 0)

    val viaCellBorder = HtmlLabelOps.parseTable("""<table border="0" cellborder="2"><tr><td>a</td></tr></table>""").get
    assertEquals(HtmlLabelOps.effectiveCellBorder(viaCellBorder, Map.empty), 2)
    assertEquals(HtmlLabelOps.effectiveCellBorder(viaCellBorder, Map("border" -> "0")), 0)

  test("nearestPath clamps after structure changes"):
    val t = HtmlLabelOps.parseTable(simple).get
    assertEquals(HtmlLabelOps.nearestPath(t, List(5, 7)), List(1, 1))
    assertEquals(HtmlLabelOps.nearestPath(t, List(0, 9)), List(0, 1))
