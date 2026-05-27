package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.layout.NodeSize

/** M1 exit gate: ported node `width`/`height` must match the values Graphviz
  * 13.0.1 echoes in the `dot` golden, within tolerance, for every supported
  * (non-record) node across the corpus.
  */
class NodeSizeSpec extends FunSuite:

  // Node stanzas in `dot` output: `\t<name>\t[ ... width=.. height=.. ];`
  // `\t+` so cluster-nested nodes (indented with several tabs) also match.
  private val NodeStanza =
    """(?m)^\t+("(?:[^"\\]|\\.)*"|[A-Za-z_][A-Za-z0-9_]*)\t\[([^\]]*)\]""".r
  private val WidthRe  = """\bwidth=([0-9.]+)""".r
  private val HeightRe = """\bheight=([0-9.]+)""".r
  private val Keywords = Set("graph", "node", "edge")

  private def unquote(s: String): String =
    if s.startsWith("\"") && s.endsWith("\"") then
      s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    else s

  /** name -> (width,height) inches, as echoed by the oracle. */
  private def goldenSizes(name: String): Map[String, (Double, Double)] =
    val dot = OracleHarness.golden(name, "dot")
    NodeStanza
      .findAllMatchIn(dot)
      .flatMap { m =>
        val nm   = unquote(m.group(1))
        val body = m.group(2)
        if Keywords.contains(nm) then None
        else
          for
            w <- WidthRe.findFirstMatchIn(body).map(_.group(1).toDouble)
            h <- HeightRe.findFirstMatchIn(body).map(_.group(1).toDouble)
          yield nm -> (w, h)
      }
      .toMap

  // Generous vs. the <0.001 in deviation we actually observe.
  private val tol = OracleHarness.Tol(abs = 0.01, rel = 0.02)

  OracleHarness.corpusNames.foreach { name =>
    test(s"$name: node width/height match the dot golden"):
      val g        = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get)
      val expected = goldenSizes(name)
      var checked  = 0
      var deferred = 0
      g.nodes.foreach { n =>
        NodeSize.nodeSize(n, g) match
          case None =>
            deferred += 1 // records/HTML — M6
          case Some(sz) =>
            expected.get(n.id) match
              case None =>
                fail(s"node '${n.id}' not found in $name dot golden (have ${expected.keys.toList.sorted})")
              case Some((ew, eh)) =>
                checked += 1
                assert(
                  OracleHarness.close(sz.width.value, ew, tol),
                  s"$name '${n.id}' width: got ${sz.width.value} expected $ew"
                )
                assert(
                  OracleHarness.close(sz.height.value, eh, tol),
                  s"$name '${n.id}' height: got ${sz.height.value} expected $eh"
                )
      }
      assert(checked + deferred == g.nodes.size, s"unaccounted nodes in $name")
  }

  test("metric path is actually exercised (not just the 0.75x0.5 floor)"):
    // `middle` (box) and `node one` (ellipse) must exceed the min-size floor.
    val g2 = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource("02-attrs")).toOption.get)
    val middle = g2.nodes.find(_.id == "middle").flatMap(NodeSize.nodeSize(_, g2)).get
    assert(middle.width.value > 0.75, s"middle should exceed floor, got ${middle.width.value}")
    assert(OracleHarness.close(middle.width.value, 0.76226, OracleHarness.Tol(abs = 0.001, rel = 0.005)))

    val g5 = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource("05-strings-comments")).toOption.get)
    val nodeOne = g5.nodes.find(_.id == "node one").flatMap(NodeSize.nodeSize(_, g5)).get
    assert(OracleHarness.close(nodeOne.width.value, 1.2824, OracleHarness.Tol(abs = 0.001, rel = 0.005)))

end NodeSizeSpec
