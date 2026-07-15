package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.output.Output

/** Full attribute emission in `dot_json` — gv `write_attrs` (alphabetical,
  * skip-empty-except-`label`). Attributes are geometry-free, so they're
  * byte-exact-checkable even for cases whose *coordinates* the port doesn't
  * yet nail (02 is LR ⇒ bb deviates; its attribute content still matches).
  * This is the M8-critical path: the viewer renders styled nodes/edges from
  * these attributes, previously dropped.
  */
class AttrEmitSpec extends FunSuite:

  private def graph(name: String) =
    OracleHarness.corpusGraph(name)

  private def dotJson(name: String) = Output.dotJson(graph(name))

  // object (by name) → its attribute map (everything but the structural keys).
  private def attrsByName(dj: String): Map[String, Map[String, String]] =
    val v = ujson.read(dj)
    val structural = Set("_gvid", "name", "nodes", "edges")
    v("objects").arr.iterator.map { o =>
      o("name").str -> o.obj.view.filterKeys(!structural(_)).mapValues(_.str).toMap
    }.toMap

  // edges keyed by (tail,head) gvid → attribute map (minus _gvid/tail/head).
  private def edgeAttrs(dj: String): Map[(Int, Int), Map[String, String]] =
    val v = ujson.read(dj)
    val structural = Set("_gvid", "tail", "head")
    v("edges").arr.iterator.map { e =>
      (e("tail").num.toInt, e("head").num.toInt) ->
        e.obj.view.filterKeys(!structural(_)).mapValues(_.str).toMap
    }.toMap

  test("04-ports-compass: dot_json byte-exact (records now emit shape + ports)"):
    assertEquals(dotJson("04-ports-compass"), OracleHarness.golden("04-ports-compass", "dot_json"))

  test("05-strings-comments: node/edge/graph attrs match golden (bb graph-label space deferred)"):
    val o = dotJson("05-strings-comments")
    val g = OracleHarness.golden("05-strings-comments", "dot_json")
    // graph label (concatenated `"title: " + "two parts"`) — exact.
    assertEquals(ujson.read(o).obj.get("label"), ujson.read(g).obj.get("label"))
    // node attrs incl. tooltip w/ escaped quotes; edge attrs incl. HTML label
    // with gv's `\/` escape + the empty-label surfacing rule — all exact.
    assertEquals(attrsByName(o), attrsByName(g), "05 node attrs")
    assertEquals(edgeAttrs(o), edgeAttrs(g), "05 edge attrs")

  test("02-attrs: node & edge attribute content matches golden (bb is LR-deferred)"):
    val o = dotJson("02-attrs")
    val g = OracleHarness.golden("02-attrs", "dot_json")
    assertEquals(attrsByName(o), attrsByName(g), "02 node attrs")
    assertEquals(edgeAttrs(o), edgeAttrs(g), "02 edge attrs")

  test("01-minimal: no attribute noise (label-only nodes, bare edges) — unchanged"):
    assertEquals(dotJson("01-minimal"), OracleHarness.golden("01-minimal", "dot_json"))

  // M8: json0 is the viewer's render format — styling attrs must reach it too,
  // interleaved alphabetically with the layout keys (height/pos/width).
  test("02-attrs: json0 nodes carry the style attrs (M8 render path)"):
    val n0 = ujson.read(Output.json0(graph("02-attrs")))("objects").arr.head.obj
    assertEquals(n0.get("fillcolor").map(_.str), Some("lightblue"))
    assertEquals(n0.get("shape").map(_.str), Some("box"))
    assertEquals(n0.get("style").map(_.str), Some("rounded,filled"))
    // alphabetical merge with geometry keys is preserved
    assert(n0.contains("height") && n0.contains("pos") && n0.contains("width"))

end AttrEmitSpec
