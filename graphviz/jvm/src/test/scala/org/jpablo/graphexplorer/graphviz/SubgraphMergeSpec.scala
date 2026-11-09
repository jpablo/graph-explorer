package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.{AttrResolver, Attrs, RGraph, RSubgraph}

/** Subgraph names, as cgraph `agsubg(parent, name, TRUE)` uses them.
  *
  * `agsubg` finds a subgraph by its name among the subgraphs of `parent`.
  * Thus two sibling bodies with one name are one subgraph, and the second
  * body adds to the first. A subgraph with the same name under a different
  * parent is a different subgraph. Each anonymous `{ … }` is a new subgraph.
  *
  * Each test has the `-Tcanon` text of Graphviz 14.1.5 (`dot`) for its input.
  * The canon text has no repeated names, so it is the expected structure: the
  * test reads the canon text with the port, and compares the two structures.
  */
class SubgraphMergeSpec extends FunSuite:

  private def resolve(dot: String): RGraph =
    AttrResolver.resolve(DotParser.parse(dot).fold(e => fail(s"parse: $e\n$dot"), identity))

  /** The attributes that have a value, sorted. An empty value is the same as
    * no value. A canon text puts `node [label="\N"]` on the root, and `\N` is
    * the default node label, so the summary removes it. */
  private def show(a: Map[String, String]): String =
    a.toVector
      .filter { case (k, v) => v.nonEmpty && !(k == "label" && v == "\\N") }
      .sorted
      .map { case (k, v) => s"$k=$v" }
      .mkString("[", ",", "]")
  private def show(a: Attrs): String = show(a.toMap)

  /** The structure of a graph as text, one line for each item.
    *
    * A subgraph line has its name, its attribute values, all of its nodes
    * (also the nodes of its children, as in cgraph), and all of its edges.
    * The nodes and edges are sorted, because a canon text can declare them in
    * a different sequence. An anonymous name becomes `%`, because a canon text
    * can number the anonymous subgraphs in a different sequence.
    */
  private def structure(g: RGraph): String =
    def allNodes(s: RSubgraph): Vector[String] = s.nodeIds ++ s.children.flatMap(allNodes)
    def allEdges(s: RSubgraph): Vector[Int]    = s.edgeIdxs ++ s.children.flatMap(allEdges)
    def edge(ix: Int): String                  = s"${g.edges(ix).tail}->${g.edges(ix).head}"
    def sub(s: RSubgraph, depth: Int): Vector[String] =
      val name = if s.id.startsWith("%") then "%" else s.id
      val line = s"${"  " * depth}subgraph $name ${show(s.attrs)}" +
        s" nodes=${allNodes(s).distinct.sorted.mkString(",")}" +
        s" edges=${allEdges(s).distinct.map(edge).sorted.mkString(",")}"
      line +: s.children.flatMap(sub(_, depth + 1))
    val lines =
      Vector(s"graph ${show(g.rootAttrs)}") ++
        g.subgraphs.flatMap(sub(_, 1)) ++
        g.nodes.sortBy(_.id).map(n => s"node ${n.id} ${show(n.attrs)}") ++
        g.edges.map(e => s"edge ${e.tail}->${e.head} ${show(e.attrs)}").sorted
    lines.mkString("\n")

  /** The structure of `input` must be the structure of the `dot -Tcanon`
    * text of `input`. */
  private def assertCanon(input: String, canon: String)(using munit.Location): Unit =
    assertEquals(structure(resolve(input)), structure(resolve(canon)))

  private def names(g: RGraph): Vector[String] =
    def pre(s: RSubgraph): Vector[String] = s.id +: s.children.flatMap(pre)
    g.subgraphs.flatMap(pre)

  test("two sibling subgraphs with one name are one subgraph: nodes, edges and attributes of both bodies"):
    val input =
      """digraph G { subgraph cluster_x { label="one"; color=red; a; a -> b } subgraph cluster_y { y } subgraph cluster_x { label="two"; c; c -> a } d }"""
    val canon =
      """digraph G {
        |	node [label="\N"];
        |	subgraph cluster_x {
        |		graph [color=red,
        |			label=two
        |		];
        |		a -> b;
        |		c -> a;
        |	}
        |	subgraph cluster_y {
        |		y;
        |	}
        |	d;
        |}""".stripMargin
    assertCanon(input, canon)
    val g = resolve(input)
    assertEquals(names(g), Vector("cluster_x", "cluster_y"))
    val x = g.subgraphs.head
    assertEquals(x.label, "two", "a later label replaces an earlier one")
    assertEquals(x.attrs.get("color"), Some("red"), "the second body keeps the attributes of the first")
    assertEquals(x.nodeIds, Vector("a", "b", "c"))
    assertEquals(x.edgeIdxs.map(g.edges(_)).map(e => (e.tail, e.head)), Vector(("a", "b"), ("c", "a")))
    assert(x.isCluster)

  test("a subgraph that opens again keeps its node and edge defaults, and sees the current defaults of its parent"):
    // dot -Tdot_json: a and b get color=green, c and d get color=blue and
    // shape=box, and both edges get color=red.
    val input =
      """digraph G { node [color=green] subgraph s { node [shape=box] edge [color=red] a -> b } node [color=blue] subgraph s { c -> d } }"""
    val canon =
      """digraph G {
        |	node [color=blue,
        |		label="\N"
        |	];
        |	subgraph s {
        |		node [shape=box];
        |		edge [color=red];
        |		a	[color=green];
        |		b	[color=green];
        |		a -> b;
        |		c;
        |		d;
        |		c -> d;
        |	}
        |}""".stripMargin
    assertCanon(input, canon)
    val g = resolve(input)
    assertEquals(g.nodes.find(_.id == "c").map(n => show(n.attrs)), Some("[color=blue,shape=box]"))
    assertEquals(g.edges.map(e => show(e.attrs)), Vector("[color=red]", "[color=red]"))

  test("a new child of a subgraph that opens again gets the graph attributes of that subgraph"):
    // cgraph `unviewsubgraphsattr`: when the root declares color=blue, the
    // existing subgraph s keeps color=red. dot -Tdot_json gives cluster_t
    // color=red.
    val input =
      """digraph G { color=red; subgraph s { a } color=blue; subgraph s { subgraph cluster_t { b } } }"""
    val canon =
      """digraph G {
        |	graph [color=blue];
        |	node [label="\N"];
        |	subgraph s {
        |		graph [color=red];
        |		subgraph cluster_t {
        |			b;
        |		}
        |		a;
        |	}
        |}""".stripMargin
    assertCanon(input, canon)
    val t = resolve(input).subgraphs.head.children.head
    assertEquals((t.id, t.attrs.get("color")), ("cluster_t", Some("red")))

  test("an edge to a subgraph that opens again goes to the nodes of all of its bodies"):
    val input = """digraph G { subgraph s { a } x -> subgraph s { b } }"""
    val canon =
      """digraph G {
        |	node [label="\N"];
        |	subgraph s {
        |		a;
        |		b;
        |	}
        |	x -> a;
        |	x -> b;
        |}""".stripMargin
    assertCanon(input, canon)
    assertEquals(resolve(input).edges.map(e => (e.tail, e.head)), Vector(("x", "a"), ("x", "b")))

  test("a subgraph that opens again keeps the _gvid of its first body, and its children merge too"):
    val input =
      """digraph G { subgraph A { subgraph B { a } } subgraph C { c } subgraph A { subgraph D { d } subgraph B { b } } }"""
    val canon =
      """digraph G {
        |	node [label="\N"];
        |	subgraph A {
        |		subgraph B {
        |			a;
        |			b;
        |		}
        |		subgraph D {
        |			d;
        |		}
        |	}
        |	subgraph C {
        |		c;
        |	}
        |}""".stripMargin
    assertCanon(input, canon)
    // dot -Tdot_json, the subgraph objects as (name, _gvid, subgraphs, nodes):
    //   (A, 0, [1, 2], [4, 6, 7]), (B, 1, -, [4, 7]), (D, 2, -, [6]), (C, 3, -, [5])
    val json    = ujson.read(Graphviz.structureJson(input).fold(fail(_), identity))
    val objects = json("objects").arr.take(4).map { o =>
      def ints(k: String) = o.obj.get(k).fold(Vector.empty[Int])(_.arr.map(_.num.toInt).toVector)
      (o("name").str, o("_gvid").num.toInt, ints("subgraphs"), ints("nodes"))
    }.toVector
    assertEquals(objects, Vector(
      ("A", 0, Vector(1, 2), Vector(4, 6, 7)),
      ("B", 1, Vector.empty, Vector(4, 7)),
      ("D", 2, Vector.empty, Vector(6)),
      ("C", 3, Vector.empty, Vector(5))
    ))

  test("subgraphs with one name under different parents stay separate"):
    // dot prints `Warning: Duplicate cluster name "cluster_x"` for the json
    // formats, but it keeps and draws all three clusters.
    val input =
      """digraph G { subgraph cluster_p { subgraph cluster_x { a } } subgraph cluster_q { subgraph cluster_x { b } } subgraph cluster_x { c } }"""
    val canon =
      """digraph G {
        |	node [label="\N"];
        |	subgraph cluster_p {
        |		subgraph cluster_x {
        |			a;
        |		}
        |	}
        |	subgraph cluster_q {
        |		subgraph cluster_x {
        |			b;
        |		}
        |	}
        |	subgraph cluster_x {
        |		c;
        |	}
        |}""".stripMargin
    assertCanon(input, canon)
    assertEquals(names(resolve(input)), Vector("cluster_p", "cluster_x", "cluster_q", "cluster_x", "cluster_x"))

  test("a nested subgraph with the name of its outer subgraph is a separate subgraph"):
    // `agsubg` looks only at the children of the outer cluster_x, so the
    // inner cluster_x is a new child. dot draws two clusters.
    val input = """digraph G { subgraph cluster_x { a; subgraph cluster_x { b } } c }"""
    val canon =
      """digraph G {
        |	node [label="\N"];
        |	subgraph cluster_x {
        |		subgraph cluster_x {
        |			b;
        |		}
        |		a;
        |	}
        |	c;
        |}""".stripMargin
    assertCanon(input, canon)
    val outer = resolve(input).subgraphs
    assertEquals(outer.map(_.id), Vector("cluster_x"))
    assertEquals(outer.head.children.map(c => (c.id, c.nodeIds)), Vector(("cluster_x", Vector("b"))))

  test("anonymous subgraphs stay separate"):
    val input =
      """digraph G { subgraph { rank=same; a; b } subgraph { rank=same; c; d } { rank=min; e } { rank=min; f } a -> c }"""
    val canon =
      """digraph G {
        |	node [label="\N"];
        |	{
        |		graph [rank=same];
        |		a;
        |		b;
        |	}
        |	{
        |		graph [rank=same];
        |		c;
        |		d;
        |	}
        |	{
        |		graph [rank=min];
        |		e;
        |	}
        |	{
        |		graph [rank=min];
        |		f;
        |	}
        |	a -> c;
        |}""".stripMargin
    assertCanon(input, canon)
    // dot -Tdot_json names them %1, %3, %5 and %7.
    assertEquals(names(resolve(input)), Vector("%1", "%3", "%5", "%7"))

  test("the layout draws one cluster for two sibling bodies with one name"):
    // dot gives the same svg and json0 for the two texts.
    val twoBodies = """digraph G { subgraph cluster_x { a -> b } subgraph cluster_x { c } d }"""
    val oneBody   = """digraph G { subgraph cluster_x { a -> b; c } d }"""
    def render(dot: String): Map[String, String] =
      val r = Graphviz.renderFormats(dot, Seq("svg", "json0"))
      assertEquals(r.status, "success", r.errors.toString)
      r.output
    val two = render(twoBodies)
    assertEquals(two, render(oneBody))
    assertEquals("<title>cluster_x</title>".r.findAllMatchIn(two("svg")).size, 1)

end SubgraphMergeSpec
