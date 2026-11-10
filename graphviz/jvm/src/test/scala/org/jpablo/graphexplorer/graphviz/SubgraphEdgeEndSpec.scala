package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.{AttrResolver, RGraph, RSubgraph}

/** An edge to a subgraph, as cgraph makes it.
  *
  * cgraph puts a node in its subgraph and in each outer subgraph, up to the
  * root. For a subgraph end, the parser makes one edge for each node of the
  * subgraph (`agfstnode`/`agnxtnode`), in the order in which the nodes were
  * made. Thus an edge to a subgraph also goes to the nodes of its inner
  * subgraphs.
  *
  * The edges of each test are the edges of `dot -Tjson0` (Graphviz 14.1.5)
  * for the input. The order is the order in which cgraph makes the edges.
  */
class SubgraphEdgeEndSpec extends FunSuite:

  private def resolve(dot: String): RGraph =
    AttrResolver.resolve(DotParser.parse(dot).fold(e => fail(s"parse: $e\n$dot"), identity))

  private def edges(g: RGraph): Vector[String] = g.edges.map(e => s"${e.tail}->${e.head}")
  private def edges(dot: String): Vector[String] = edges(resolve(dot))

  test("an edge to a subgraph goes to the nodes of its inner subgraph"):
    assertEquals(edges("digraph G { x -> subgraph s { subgraph t { a } b } }"), Vector("x->a", "x->b"))

  test("an edge from a subgraph comes from the nodes of its inner subgraph"):
    assertEquals(edges("digraph G { subgraph s { subgraph t { a } b } -> x }"), Vector("a->x", "b->x"))

  test("the edges go to the nodes in the order in which the nodes were made"):
    // `b` comes before `a`, so cgraph makes `x -> b` first.
    assertEquals(edges("digraph G { b; x -> subgraph s { subgraph t { a } b } }"), Vector("x->b", "x->a"))

  test("the nodes of each inner level count, also in anonymous subgraphs"):
    assertEquals(edges("digraph G { x -> { { { a } } b } }"), Vector("x->a", "x->b"))

  test("an inner subgraph from an earlier body of the subgraph counts"):
    assertEquals(
      edges("digraph G { subgraph s { subgraph t { a } } x -> subgraph s { b } }"),
      Vector("x->a", "x->b")
    )

  test("a node in the subgraph and in its inner subgraph gets one edge"):
    assertEquals(edges("digraph G { x -> subgraph s { subgraph t { a } a } }"), Vector("x->a"))

  test("an edge between two subgraphs goes between the nodes of their inner subgraphs"):
    assertEquals(
      edges("digraph G { subgraph s { subgraph t { a } } -> subgraph u { subgraph v { b } } }"),
      Vector("a->b")
    )

  test("an undirected edge to a cluster goes to the nodes of its inner cluster"):
    assertEquals(
      edges("graph G { x -- subgraph cluster_s { subgraph cluster_t { a } b } }"),
      Vector("x->a", "x->b")
    )

  test("an edge inside the inner subgraph stays, and comes first"):
    // cgraph reads the body of `t` before it makes the edges from `x`.
    assertEquals(
      edges("digraph G { x -> subgraph s { subgraph t { a -> c } b } }"),
      Vector("a->c", "x->a", "x->c", "x->b")
    )

  test("an edge statement in a subgraph puts each edge in that subgraph"):
    // dot -Tjson0: the edges of `p` are `x -> a` and `x -> b`.
    val g = resolve("digraph G { subgraph p { x -> subgraph s { subgraph t { a } b } } }")
    assertEquals(edges(g), Vector("x->a", "x->b"))
    def find(subs: Vector[RSubgraph], id: String): Option[RSubgraph] =
      subs.iterator.map(s => if s.id == id then Some(s) else find(s.children, id)).collectFirst { case Some(s) => s }
    val p = find(g.subgraphs, "p").getOrElse(fail("no subgraph p"))
    assertEquals(p.edgeIdxs.map(i => s"${g.edges(i).tail}->${g.edges(i).head}"), Vector("x->a", "x->b"))
