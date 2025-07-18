package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings.{SimpleGraph, SimpleGraphConverter}
import upickle.default.*

class SimpleGraphConverterRoundtripSpec extends FunSuite:

  test("SimpleGraphConverter should handle round trips") {
    val originalJson =
      """|{
         |  "name": "G",
         |  "objects": [
         |    {
         |      "_gvid": 0,
         |      "name": "g5294cce8",
         |      "nodes": [],
         |      "label": "group 1",
         |      "subgraphs": [
         |        1,
         |        2
         |      ],
         |      "lheight": "0.23",
         |      "lp": "51,198",
         |      "lwidth": "0.60",
         |      "cluster": "true"
         |    },
         |    {
         |      "_gvid": 1,
         |      "name": "g863fd476",
         |      "nodes": [
         |        3
         |      ],
         |      "label": "group 2",
         |      "lheight": "0.23",
         |      "lp": "51,80.4",
         |      "lwidth": "0.60",
         |      "cluster": "true"
         |    },
         |    {
         |      "_gvid": 2,
         |      "name": "geca09c80",
         |      "nodes": [
         |        4
         |      ],
         |      "label": "group 3",
         |      "lheight": "0.23",
         |      "lp": "51,165.2",
         |      "lwidth": "0.60",
         |      "cluster": "true"
         |    },
         |    {
         |      "_gvid": 3,
         |      "name": "c",
         |      "label": "c",
         |      "pos": "51,42",
         |      "height": "0.5",
         |      "width": "0.75"
         |    },
         |    {
         |      "_gvid": 4,
         |      "name": "b",
         |      "label": "b",
         |      "pos": "51,126.8",
         |      "height": "0.5",
         |      "width": "0.75"
         |    },
         |    {
         |      "_gvid": 5,
         |      "name": "a",
         |      "label": "a",
         |      "pos": "51,236.4",
         |      "height": "0.5",
         |      "width": "0.75"
         |    }
         |  ],
         |  "edges": [
         |    {
         |      "_gvid": 1,
         |      "tail": 5,
         |      "head": 4,
         |      "pos": "e,51,145.04 51,218.11 51,201.48 51,176.05 51,156.4",
         |      "id": "arrow:a->b/1"
         |    },
         |    {
         |      "_gvid": 0,
         |      "tail": 4,
         |      "head": 3,
         |      "pos": "e,51,60.419 51,108.64 51,97.947 51,83.915 51,71.572",
         |      "id": "arrow:b->c/0"
         |    }
         |  ],
         |  "label": ""
         |}""".stripMargin

    val originalGraph  = read[SimpleGraph](originalJson)
    val elements       = SimpleGraphConverter.toViewerGraphElements(originalGraph)
    val roundTripGraph = SimpleGraphConverter.fromViewerGraphElements(elements)

//    assertEquals(roundTripGraph, originalGraph)
    assertNoDiff(write(roundTripGraph, indent = 2), originalJson)
  }
