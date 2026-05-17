package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.*

/** M0 exit gate: the DOT front-end must parse the entire corpus, plus targeted
  * structural checks for the language features each corpus file isolates.
  */
class DotParserSpec extends FunSuite:

  private def parseOk(name: String): Graph =
    DotParser.parse(OracleHarness.corpusSource(name)) match
      case Right(g)  => g
      case Left(err) => fail(s"failed to parse corpus '$name':\n$err")

  test("every corpus .dot file parses"):
    OracleHarness.corpusNames.foreach(parseOk)

  test("01-minimal: directed, three 2-ended edges"):
    val g = parseOk("01-minimal")
    assert(g.directed); assert(!g.strict)
    assertEquals(g.id, None)
    assertEquals(g.stmts.size, 3)
    g.stmts.foreach:
      case Stmt.EdgeStmt(ends, _) => assertEquals(ends.size, 2)
      case other                  => fail(s"expected EdgeStmt, got $other")

  test("06-undirected: strict graph with id and an a--b--c--a chain"):
    val g = parseOk("06-undirected")
    assert(g.strict); assert(!g.directed)
    assertEquals(g.id.map(_.value), Some("mesh"))
    g.stmts.head match
      case Stmt.EdgeStmt(ends, _) => assertEquals(ends.size, 4)
      case other                  => fail(s"expected edge chain, got $other")

  test("02-attrs: leading `rankdir = LR` assign + node attr defaults"):
    val g = parseOk("02-attrs")
    g.stmts.head match
      case Stmt.Assign(n, v) =>
        assertEquals(n.value, "rankdir"); assertEquals(v.value, "LR")
      case other => fail(s"expected Assign, got $other")
    val nodeDefaults =
      g.stmts.collect { case Stmt.AttrStmt(AttrTarget.Node, as) => as }.flatten
    assert(
      nodeDefaults.exists((k, v) => k.value == "shape" && v.value == "box"),
      s"node defaults missing shape=box: $nodeDefaults"
    )

  test("05-strings-comments: comments skipped, quoted-string concatenation"):
    val g = parseOk("05-strings-comments")
    assert(g.directed)
    val graphAttrs =
      g.stmts.collect { case Stmt.AttrStmt(AttrTarget.Graph, as) => as }.flatten
    assert(
      graphAttrs.exists((k, v) => k.value == "label" && v.value == "title: two parts"),
      s"string concatenation not applied: $graphAttrs"
    )

  test("05-strings-comments: HTML-like label kept verbatim with html flag"):
    val g       = parseOk("05-strings-comments")
    val allAttrs = g.stmts.collect { case Stmt.EdgeStmt(_, as) => as }.flatten
    assert(
      allAttrs.exists((_, v) => v.html && v.value.contains("<b>html</b>")),
      s"html label not captured: $allAttrs"
    )

  test("04-ports-compass: record ports parsed (struct1:f0 -> struct2:a)"):
    val g = parseOk("04-ports-compass")
    val ports = g.stmts
      .collect { case Stmt.EdgeStmt(ends, _) => ends }
      .flatten
      .collect { case EdgeEnd.Node(NodeId(_, Some(p))) => p }
    assert(ports.exists(_.name.map(_.value).contains("f0")), s"ports: $ports")
    assert(
      ports.exists(p => p.compass.contains(Compass.S) || p.compass.contains(Compass.N)),
      s"compass points not parsed: $ports"
    )

end DotParserSpec
