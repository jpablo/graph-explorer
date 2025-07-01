package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraphElements}
import upickle.default.*

class VizViewerGraphElements2Spec extends FunSuite:

  test("compare expanded style attributes with default attributes") {
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

    val simpleGraph = read[SimpleGraph](simpleGraphJson)
    val elements = toViewerGraphElements(simpleGraph).expandAndExtractDefaultAttributes

    val defaults =
      """|  "defaultNodeAttributes": {
         |    "shape": "box"
         |  }
         |""".stripMargin
    assert(write(elements, indent = 2).contains(defaults))
  }
