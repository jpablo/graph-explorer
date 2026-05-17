package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.{AttrResolver, RGraph}

/** M1: DOT default-statement scoping must resolve the way Graphviz applies it. */
class AttrResolveSpec extends FunSuite:

  private def resolve(name: String): RGraph =
    AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get)

  test("02-attrs: node/edge defaults apply to later implicit items; assign → root"):
    val g = resolve("02-attrs")
    assertEquals(g.rootAttrs.get("rankdir"), Some("LR"))
    val start = g.nodes.find(_.id == "start").get
    assertEquals(start.attrs.get("shape"), Some("box"))
    assertEquals(start.attrs.get("fillcolor"), Some("lightblue"))
    val e = g.edges.find(e => e.tail == "start" && e.head == "middle").get
    assertEquals(e.attrs.get("arrowhead"), Some("vee")) // edge default
    assertEquals(e.attrs.get("color"), Some("gray"))     // edge default
    assertEquals(e.attrs.get("label"), Some("go"))       // explicit
    assertEquals(e.attrs.get("weight"), Some("2"))

  test("03: subgraph graph-attr (label) does NOT leak onto nodes"):
    val g   = resolve("03-subgraph-cluster")
    val ids = g.nodes.map(_.id).toSet
    assert(Set("a0", "a1", "a2", "b0", "b1", "start").subsetOf(ids), s"have $ids")
    val a0 = g.nodes.find(_.id == "a0").get
    assertEquals(a0.attrs.get("label"), None) // `label="group A"` was the subgraph's, not a0's
    assertEquals(a0.attrs.get("shape"), None) // no node default in this graph

  test("05: explicit node attrs kept; no label attr ⇒ implicit \\N; graph label → root"):
    val g  = resolve("05-strings-comments")
    val n1 = g.nodes.find(_.id == "node one").get
    assertEquals(n1.attrs.get("label"), None)  // corpus sets no node label; \N is implicit
    assert(n1.attrs.get("tooltip").isDefined)  // explicit on the node
    // `graph [label = "title: " + "two parts"]` concatenated and landed on root
    assertEquals(g.rootAttrs.get("label"), Some("title: two parts"))

  test("06: strict + undirected flags propagate to the resolved graph"):
    val g = resolve("06-undirected")
    assert(g.strict)
    assert(!g.directed)
    assertEquals(g.name, Some("mesh"))

end AttrResolveSpec
