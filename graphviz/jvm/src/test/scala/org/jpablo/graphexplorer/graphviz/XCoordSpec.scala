package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.layout.XCoord

/** M4x exit gate: cross-axis X within ε of the `plain` golden.
  *
  * Scoped to label-free TB graphs (01/06/07) — same deferrals as M4-Y
  * (02 LR-rotation, 04 records, 03 clusters, edge-label rank-doubling).
  * Mirror symmetry of the whole drawing is an accepted deviation.
  */
class XCoordSpec extends FunSuite:

  private val PlainNode =
    """(?m)^node ("(?:[^"\\]|\\.)*"|\S+) (\S+) (\S+) """.r

  private def unquote(s: String): String =
    if s.startsWith("\"") && s.endsWith("\"") then
      s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    else s

  private def goldenX(name: String): Map[String, Double] =
    PlainNode
      .findAllMatchIn(OracleHarness.golden(name, "plain"))
      .map(m => unquote(m.group(1)) -> m.group(2).toDouble)
      .toMap

  private def ourX(name: String): Map[String, Double] =
    val g = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get)
    XCoord.xCoords(g).view.mapValues(_.value / 72.0).toMap

  // ~2px abs / 5% rel to start (PORT.md §2.1); tighten once stable.
  private val tol = OracleHarness.Tol(abs = 0.03, rel = 0.05)

  /** A whole-drawing horizontal mirror is layout-equivalent and allowed. */
  private def matchesAllowingMirror(name: String): Boolean =
    val exp = goldenX(name)
    val got = ourX(name)
    val w   = exp.values.max + exp.values.min // mirror axis ≈ (min+max)
    exp.forall { case (id, ex) =>
      got.get(id).exists { gx =>
        OracleHarness.close(gx, ex, tol) || OracleHarness.close(w - gx, ex, tol)
      }
    }

  // Label-free TB graphs: X must match the plain golden (mirror allowed).
  // Closed after instrumenting real Graphviz: the omega model is
  // virtual_weight()'s class table, not ω=1/2/8-by-virtualness.
  List("01-minimal", "06-undirected", "07-cross").foreach { name =>
    test(s"$name: node X within ε of the plain golden (mirror allowed)"):
      assert(matchesAllowingMirror(name), s"$name X ${ourX(name)} vs golden ${goldenX(name)}")
  }

  test("07-cross: columns straight (a_i aligned with its matched b)"):
    val x = ourX("07-cross")
    // mincross order pairs a1-b3, a2-b2, a3-b1; each pair shares an x column
    assert(OracleHarness.close(x("a1"), x("b3"), tol), x.toString)
    assert(OracleHarness.close(x("a2"), x("b2"), tol), x.toString)
    assert(OracleHarness.close(x("a3"), x("b1"), tol), x.toString)

end XCoordSpec
