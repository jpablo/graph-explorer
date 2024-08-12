package org.jpablo.typeexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.typeexplorer.viewer.formats.dot.Dot
import upickle.default.*
import org.scalacheck.Prop.*

class DiGraphTest extends ScalaCheckSuite:
//
//  property("scalacheck"):
//    forAll: (n: Int) =>
//      println(n)
//      assert(n >= n)

//  test("serialization test 2"):
//    val ast = Dot(json2).buildAST.headOption
//    println(ast)

  test("serialization test"):
    val g = read[List[DiGraphAST]](json1)

    assert(g.length == 1)

val json2 =
  """
    |digraph G {
    | rankdir=LR;
    | String [shape=box];
    | String -> Dot;
    | Dot -> DotAST -> ViewerGraph;
    | ViewerGraph -> Dot1;
    | Dot1 -> SvgDiagram;
    |}""".stripMargin

val json1 =
  """
    |[
    |  {
    |    "type": "digraph",
    |    "location": {
    |      "start": {
    |        "offset": 0,
    |        "line": 1,
    |        "column": 1
    |      },
    |      "end": {
    |        "offset": 142,
    |        "line": 13,
    |        "column": 1
    |      }
    |    },
    |    "children": [
    |      {
    |        "type": "newline",
    |        "location": {
    |          "start": {
    |            "offset": 11,
    |            "line": 1,
    |            "column": 12
    |          },
    |          "end": {
    |            "offset": 12,
    |            "line": 2,
    |            "column": 1
    |          }
    |        }
    |      },
    |      {
    |        "type": "pad",
    |        "location": {
    |          "start": {
    |            "offset": 12,
    |            "line": 2,
    |            "column": 1
    |          },
    |          "end": {
    |            "offset": 13,
    |            "line": 2,
    |            "column": 2
    |          }
    |        }
    |      },
    |      {
    |        "type": "attr_stmt",
    |        "location": {
    |          "start": {
    |            "offset": 13,
    |            "line": 2,
    |            "column": 2
    |          },
    |          "end": {
    |            "offset": 23,
    |            "line": 2,
    |            "column": 12
    |          }
    |        },
    |        "target": "graph",
    |        "attr_list": [
    |          {
    |            "type": "attr",
    |            "location": {
    |              "start": {
    |                "offset": 13,
    |                "line": 2,
    |                "column": 2
    |              },
    |              "end": {
    |                "offset": 23,
    |                "line": 2,
    |                "column": 12
    |              }
    |            },
    |            "id": "rankdir",
    |            "eq": "TB"
    |          }
    |        ]
    |      },
    |      {
    |        "type": "newline",
    |        "location": {
    |          "start": {
    |            "offset": 23,
    |            "line": 2,
    |            "column": 12
    |          },
    |          "end": {
    |            "offset": 25,
    |            "line": 4,
    |            "column": 1
    |          }
    |        }
    |      },
    |      {
    |        "type": "edge_stmt",
    |        "location": {
    |          "start": {
    |            "offset": 25,
    |            "line": 4,
    |            "column": 1
    |          },
    |          "end": {
    |            "offset": 51,
    |            "line": 4,
    |            "column": 27
    |          }
    |        },
    |        "edge_list": [
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 25,
    |                "line": 4,
    |                "column": 1
    |              },
    |              "end": {
    |                "offset": 29,
    |                "line": 4,
    |                "column": 5
    |              }
    |            },
    |            "id": "text"
    |          },
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 33,
    |                "line": 4,
    |                "column": 9
    |              },
    |              "end": {
    |                "offset": 36,
    |                "line": 4,
    |                "column": 12
    |              }
    |            },
    |            "id": "CSV"
    |          },
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 40,
    |                "line": 4,
    |                "column": 16
    |              },
    |              "end": {
    |                "offset": 51,
    |                "line": 4,
    |                "column": 27
    |              }
    |            },
    |            "id": "ViewerGraph"
    |          }
    |        ],
    |        "attr_list": []
    |      },
    |      {
    |        "type": "stmt_sep",
    |        "location": {
    |          "start": {
    |            "offset": 51,
    |            "line": 4,
    |            "column": 27
    |          },
    |          "end": {
    |            "offset": 52,
    |            "line": 4,
    |            "column": 28
    |          }
    |        }
    |      },
    |      {
    |        "type": "newline",
    |        "location": {
    |          "start": {
    |            "offset": 52,
    |            "line": 4,
    |            "column": 28
    |          },
    |          "end": {
    |            "offset": 53,
    |            "line": 5,
    |            "column": 1
    |          }
    |        }
    |      },
    |      {
    |        "type": "edge_stmt",
    |        "location": {
    |          "start": {
    |            "offset": 53,
    |            "line": 5,
    |            "column": 1
    |          },
    |          "end": {
    |            "offset": 64,
    |            "line": 5,
    |            "column": 12
    |          }
    |        },
    |        "edge_list": [
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 53,
    |                "line": 5,
    |                "column": 1
    |              },
    |              "end": {
    |                "offset": 57,
    |                "line": 5,
    |                "column": 5
    |              }
    |            },
    |            "id": "text"
    |          },
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 61,
    |                "line": 5,
    |                "column": 9
    |              },
    |              "end": {
    |                "offset": 64,
    |                "line": 5,
    |                "column": 12
    |              }
    |            },
    |            "id": "dot"
    |          }
    |        ],
    |        "attr_list": []
    |      },
    |      {
    |        "type": "stmt_sep",
    |        "location": {
    |          "start": {
    |            "offset": 64,
    |            "line": 5,
    |            "column": 12
    |          },
    |          "end": {
    |            "offset": 65,
    |            "line": 5,
    |            "column": 13
    |          }
    |        }
    |      },
    |      {
    |        "type": "newline",
    |        "location": {
    |          "start": {
    |            "offset": 65,
    |            "line": 5,
    |            "column": 13
    |          },
    |          "end": {
    |            "offset": 66,
    |            "line": 6,
    |            "column": 1
    |          }
    |        }
    |      },
    |      {
    |        "type": "edge_stmt",
    |        "location": {
    |          "start": {
    |            "offset": 66,
    |            "line": 6,
    |            "column": 1
    |          },
    |          "end": {
    |            "offset": 94,
    |            "line": 6,
    |            "column": 29
    |          }
    |        },
    |        "edge_list": [
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 66,
    |                "line": 6,
    |                "column": 1
    |              },
    |              "end": {
    |                "offset": 69,
    |                "line": 6,
    |                "column": 4
    |              }
    |            },
    |            "id": "dot"
    |          },
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 73,
    |                "line": 6,
    |                "column": 8
    |              },
    |              "end": {
    |                "offset": 79,
    |                "line": 6,
    |                "column": 14
    |              }
    |            },
    |            "id": "dotAST"
    |          },
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 83,
    |                "line": 6,
    |                "column": 18
    |              },
    |              "end": {
    |                "offset": 94,
    |                "line": 6,
    |                "column": 29
    |              }
    |            },
    |            "id": "ViewerGraph"
    |          }
    |        ],
    |        "attr_list": []
    |      },
    |      {
    |        "type": "stmt_sep",
    |        "location": {
    |          "start": {
    |            "offset": 94,
    |            "line": 6,
    |            "column": 29
    |          },
    |          "end": {
    |            "offset": 95,
    |            "line": 6,
    |            "column": 30
    |          }
    |        }
    |      },
    |      {
    |        "type": "newline",
    |        "location": {
    |          "start": {
    |            "offset": 95,
    |            "line": 6,
    |            "column": 30
    |          },
    |          "end": {
    |            "offset": 96,
    |            "line": 7,
    |            "column": 1
    |          }
    |        }
    |      },
    |      {
    |        "type": "edge_stmt",
    |        "location": {
    |          "start": {
    |            "offset": 96,
    |            "line": 7,
    |            "column": 1
    |          },
    |          "end": {
    |            "offset": 115,
    |            "line": 7,
    |            "column": 20
    |          }
    |        },
    |        "edge_list": [
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 96,
    |                "line": 7,
    |                "column": 1
    |              },
    |              "end": {
    |                "offset": 107,
    |                "line": 7,
    |                "column": 12
    |              }
    |            },
    |            "id": "ViewerGraph"
    |          },
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 111,
    |                "line": 7,
    |                "column": 16
    |              },
    |              "end": {
    |                "offset": 115,
    |                "line": 7,
    |                "column": 20
    |              }
    |            },
    |            "id": "dot1"
    |          }
    |        ],
    |        "attr_list": []
    |      },
    |      {
    |        "type": "stmt_sep",
    |        "location": {
    |          "start": {
    |            "offset": 115,
    |            "line": 7,
    |            "column": 20
    |          },
    |          "end": {
    |            "offset": 116,
    |            "line": 7,
    |            "column": 21
    |          }
    |        }
    |      },
    |      {
    |        "type": "newline",
    |        "location": {
    |          "start": {
    |            "offset": 116,
    |            "line": 7,
    |            "column": 21
    |          },
    |          "end": {
    |            "offset": 117,
    |            "line": 8,
    |            "column": 1
    |          }
    |        }
    |      },
    |      {
    |        "type": "edge_stmt",
    |        "location": {
    |          "start": {
    |            "offset": 117,
    |            "line": 8,
    |            "column": 1
    |          },
    |          "end": {
    |            "offset": 135,
    |            "line": 8,
    |            "column": 19
    |          }
    |        },
    |        "edge_list": [
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 117,
    |                "line": 8,
    |                "column": 1
    |              },
    |              "end": {
    |                "offset": 121,
    |                "line": 8,
    |                "column": 5
    |              }
    |            },
    |            "id": "dot1"
    |          },
    |          {
    |            "type": "node_id",
    |            "location": {
    |              "start": {
    |                "offset": 125,
    |                "line": 8,
    |                "column": 9
    |              },
    |              "end": {
    |                "offset": 135,
    |                "line": 8,
    |                "column": 19
    |              }
    |            },
    |            "id": "svgDiagram"
    |          }
    |        ],
    |        "attr_list": []
    |      },
    |      {
    |        "type": "stmt_sep",
    |        "location": {
    |          "start": {
    |            "offset": 135,
    |            "line": 8,
    |            "column": 19
    |          },
    |          "end": {
    |            "offset": 136,
    |            "line": 8,
    |            "column": 20
    |          }
    |        }
    |      }
    |    ],
    |    "id": "G"
    |  }
    |]
    |""".stripMargin
