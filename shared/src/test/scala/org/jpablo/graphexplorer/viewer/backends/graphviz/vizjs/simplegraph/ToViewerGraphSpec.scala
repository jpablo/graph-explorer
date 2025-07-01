package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import upickle.default.*

class ToViewerGraphSpec extends FunSuite:

  test("single node, no style") {
    // digraph "G" {
    //    "a" [label="a"];
    // }

    // produced with:
    //  pbpaste | viz-cli -f dot_json
    val graphJson =
      """|{
         |  "name": "G",
         |  "directed": true,
         |  "objects": [
         |    {
         |      "_gvid": 0,
         |      "name": "a",
         |      "label": "a"
         |    }
         |  ]
         |}""".stripMargin
    val simpleGraph = read[SimpleGraph](graphJson)

    val viewerElements = toViewerGraph(simpleGraph).elements

    // Expectation: only the label should be present, no substyle attributes
    val expected =
      """|{
         |  "nodes": {
         |    "a": {
         |      "$type": "ViewerNode",
         |      "id": "a",
         |      "attributes": {
         |        "_gvid": "0",
         |        "label": "a"
         |      }
         |    }
         |  }
         |}""".stripMargin

    assertNoDiff(write(viewerElements, indent = 2), expected)
  }

  test("single node, style=dashed") {
    // digraph "G" {
    //    "a" [
    //        label="a",
    //        style="dashed"
    //    ];
    // }
    val graphJson =
      """|{
         |  "name": "G",
         |  "directed": true,
         |  "strict": false,
         |  "objects": [
         |    {
         |      "_gvid": 0,
         |      "name": "a",
         |      "label": "a",
         |      "style": "dashed"
         |    }
         |  ]
         |}""".stripMargin

    val simpleGraph    = read[SimpleGraph](graphJson)
    val viewerElements = toViewerGraph(simpleGraph).elements

    // Expectation: only the label and borderstyle should be present
    val expected =
      """|{
         |  "nodes": {
         |    "a": {
         |      "$type": "ViewerNode",
         |      "id": "a",
         |      "attributes": {
         |        "_gvid": "0",
         |        "label": "a",
         |        "borderstyle": "dashed"
         |      }
         |    }
         |  }
         |}""".stripMargin

    assertNoDiff(write(viewerElements, indent = 2), expected)
  }

  test("two nodes, one with style RESET") {
    // digraph "G" {
    //    "a" [label="a"];
    //    "b" [
    //        style="",
    //        label="b"
    //    ];
    // }
    val graphJson =
      """|{
         |  "name": "G",
         |  "directed": true,
         |  "strict": false,
         |  "objects": [
         |    {
         |      "_gvid": 0,
         |      "name": "a",
         |      "label": "a"
         |    },
         |    {
         |      "_gvid": 1,
         |      "name": "b",
         |      "label": "b"
         |    }
         |  ]
         |}""".stripMargin

    val simpleGraph    = read[SimpleGraph](graphJson)
    val viewerElements = toViewerGraph(simpleGraph).elements

    // Expectation: neither node should have substyle attributes.
    val expected =
      """|{
         |  "nodes": {
         |    "a": {
         |      "$type": "ViewerNode",
         |      "id": "a",
         |      "attributes": {
         |        "_gvid": "0",
         |        "label": "a"
         |      }
         |    },
         |    "b": {
         |      "$type": "ViewerNode",
         |      "id": "b",
         |      "attributes": {
         |        "_gvid": "1",
         |        "label": "b"
         |      }
         |    }
         |  }
         |}""".stripMargin

    assertNoDiff(write(viewerElements, indent = 2), expected)
  }

  test("Three nodes, combined styles") {
    // digraph G {
    //    // 1. Establish a NEW global default for all nodes.
    //    // By default, every node should now be filled and rounded.
    //    node [style="filled,rounded", shape=box, fillcolor=lightblue];
    //
    //    // 2. Node 'n1' has its 'style' attribute MISSING.
    //    // It will INHERIT the new default we just set.
    //    n1 [label="n1: Inherits Default"];
    //
    //    // 3. Node 'n2' has 'style=""'.
    //    // This is an ACTIVE RESET command. It will IGNORE the 'filled,rounded'
    //    // default and revert to a node's most primitive form.
    //    n2 [label="n2: Resets to Primitive", style=""];
    //
    //    // 4. Node 'n3' has 'style="solid"'.
    //    // This is often thought of as "the default style". Let's see.
    //    // This explicitly sets the style, overriding the 'filled,rounded' default.
    //    n3 [label="n3: Explicitly 'solid'", style="solid"];
    //
    //    n1 -> n2 -> n3;
    // }
    val simpleGraphJson =
      """|{
       |  "name": "G",
       |  "directed": true,
       |  "objects": [
       |    {
       |      "_gvid": 0,
       |      "name": "n1",
       |      "fillcolor": "lightblue",
       |      "label": "n1: Inherits Default",
       |      "shape": "box",
       |      "style": "filled,rounded"
       |    },
       |    {
       |      "_gvid": 1,
       |      "name": "n2",
       |      "fillcolor": "lightblue",
       |      "label": "n2: Resets to Primitive",
       |      "shape": "box"
       |    },
       |    {
       |      "_gvid": 2,
       |      "name": "n3",
       |      "fillcolor": "lightblue",
       |      "label": "n3: Explicitly 'solid'",
       |      "shape": "box",
       |      "style": "solid"
       |    }
       |  ],
       |  "edges": [
       |    { "_gvid": 0, "tail": 0, "head": 1 },
       |    { "_gvid": 1, "tail": 1, "head": 2 }
       |  ]
       |}""".stripMargin

    // Note: n2 shows that a missing style attrs should be interpreted as
    // an explicit OVERRIDE to the hardcoded default style.

    val simpleGraph    = read[SimpleGraph](simpleGraphJson)
    val viewerElements = toViewerGraph(simpleGraph).elements

    val expected =
      """|{
         |  "nodes": {
         |    "n1": {
         |      "$type": "ViewerNode",
         |      "id": "n1",
         |      "attributes": {
         |        "_gvid": "0",
         |        "label": "n1: Inherits Default",
         |        "fillcolor": "lightblue",
         |        "fillstyle": "true",
         |        "cornerstyle": "rounded"
         |      }
         |    },
         |    "n2": {
         |      "$type": "ViewerNode",
         |      "id": "n2",
         |      "attributes": {
         |        "_gvid": "1",
         |        "label": "n2: Resets to Primitive"
         |      }
         |    },
         |    "n3": {
         |      "$type": "ViewerNode",
         |      "id": "n3",
         |      "attributes": {
         |        "_gvid": "2",
         |        "label": "n3: Explicitly 'solid'",
         |        "borderstyle": "solid"
         |      }
         |    }
         |  },
         |  "arrows": {
         |    "n1->n2/0": {
         |      "$type": "Arrow",
         |      "source": "n1",
         |      "target": "n2",
         |      "attributes": {
         |        "_gvid": "0"
         |      },
         |      "seq": 0
         |    },
         |    "n2->n3/1": {
         |      "$type": "Arrow",
         |      "source": "n2",
         |      "target": "n3",
         |      "attributes": {
         |        "_gvid": "1"
         |      }
         |    }
         |  },
         |  "defaultNodeAttributes": {
         |    "shape": "box"
         |  }
         |}""".stripMargin

    assertNoDiff(write(viewerElements, indent = 2), expected)
  }
