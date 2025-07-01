package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings.{SimpleGraph, SimpleGraphConverter}
import upickle.default.*

class SimpleGraphConverterRoundtripSpec extends FunSuite:

  test("SimpleGraphConverter should handle round trips") {
    val originalJson =
      """
        |{
        |  "name": "G",
        |  "bb": "0,0,102,254.4",
        |  "_subgraph_cnt": 3,
        |  "objects": [
        |    {
        |      "_gvid": 0,
        |      "name": "g5294cce8",
        |      "bb": "8,8,94,210.4",
        |      "nodes": [
        |        3,
        |        4
        |      ],
        |      "label": "group 1",
        |      "edges": [
        |        0
        |      ],
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
        |      "bb": "16,16,86,92.8",
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
        |      "bb": "16,100.8,86,177.6",
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
        |      "pos": "e,51,145.04 51,218.11 51,201.48 51,176.05 51,156.4"
        |    },
        |    {
        |      "_gvid": 0,
        |      "tail": 4,
        |      "head": 3,
        |      "pos": "e,51,60.419 51,108.64 51,97.947 51,83.915 51,71.572"
        |    }
        |  ],
        |  "label": ""
        |}""".stripMargin

    val originalGraph  = read[SimpleGraph](originalJson)
    val elements       = SimpleGraphConverter.toViewerGraphElements(originalGraph)
    val roundTripGraph = SimpleGraphConverter.fromViewerGraphElements(elements)

    assertEquals(roundTripGraph, originalGraph)
  }
