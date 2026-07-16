package org.jpablo.graphexplorer.viewer.backends.graphviz

import munit.FunSuite

/** `Graphviz.usesDotEngine` routes each graph to the Scala `dot` port or to
  * viz-js (every non-`dot` engine). Regression spec for the 2026-07-16 bug:
  * a string-blanking "robustness" pass erased quoted attribute VALUES too, so
  * `layout="neato"` (the common quoted style) became invisible and neato
  * graphs were silently laid out by the `dot` port.
  */
class EngineRoutingSpec extends FunSuite:

  test("no layout attr ⇒ dot"):
    assert(Graphviz.usesDotEngine("digraph { a -> b }"))

  test("layout=dot (unquoted) ⇒ dot"):
    assert(Graphviz.usesDotEngine("digraph { layout=dot; a -> b }"))

  test("layout=neato (unquoted) ⇒ viz-js"):
    assert(!Graphviz.usesDotEngine("digraph { layout=neato; a -> b }"))

  test("layout=\"neato\" (quoted value — the common style) ⇒ viz-js"):
    assert(!Graphviz.usesDotEngine("""digraph { graph [layout="neato"]; a -> b }"""))

  test("quoted layout after #-color strings (twelve-colors shape) ⇒ viz-js"):
    val dot = """digraph "Twelve_colors" {
                 |  graph [label="Twelve colors. Neato layout", layout="neato", start="regular"];
                 |  node [style="filled", color="#00000088", shape="circle"];
                 |  "cyan" [fillcolor="cyan"];
                 |  "green" -> "cyan" [color="green"];
                 |}""".stripMargin
    assert(!Graphviz.usesDotEngine(dot))

  test("layout=dot mentioned only inside a label string ⇒ engine unset ⇒ dot"):
    assert(Graphviz.usesDotEngine("""digraph { a [label="try layout=twopi someday"]; a -> b }"""))

  test("label mentioning layout=dot does NOT shadow a real layout=\"neato\""):
    val dot = """digraph {
                 |  a [label="layout=dot demo"];
                 |  graph [layout="neato"];
                 |  a -> b
                 |}""".stripMargin
    assert(!Graphviz.usesDotEngine(dot))

  test("// and /* */ comments mentioning layout=dot do not shadow layout=neato"):
    val dot = """digraph {
                 |  // layout=dot
                 |  /* layout=dot */
                 |  layout=neato
                 |  a -> b
                 |}""".stripMargin
    assert(!Graphviz.usesDotEngine(dot))

  test("# comment at line start mentioning layout=dot does not shadow layout=neato"):
    val dot = "digraph {\n# layout=dot\nlayout=neato\na -> b\n}"
    assert(!Graphviz.usesDotEngine(dot))

  test("layout = \"twopi\" (spaces around = and quotes) ⇒ viz-js"):
    assert(!Graphviz.usesDotEngine("""digraph { layout = "twopi" ; a -> b }"""))

end EngineRoutingSpec
