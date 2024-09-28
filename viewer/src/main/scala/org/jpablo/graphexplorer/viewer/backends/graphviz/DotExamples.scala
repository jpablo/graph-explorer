package org.jpablo.graphexplorer.viewer.backends.graphviz

object DotExamples:
  private val finiteStateMachine =
  """digraph finite_state_machine {
    |    rankdir=LR;
    |    size="8,5"
    |    node [shape = doublecircle]; LR_0 LR_3 LR_4 LR_8;
    |    node [shape = circle];
    |    LR_0 -> LR_2 [ label = "SS(B)" ];
    |    LR_0 -> LR_1 [ label = "SS(S)" ];
    |    LR_1 -> LR_3 [ label = "S($end)" ];
    |    LR_2 -> LR_6 [ label = "SS(b)" ];
    |    LR_2 -> LR_5 [ label = "SS(a)" ];
    |    LR_2 -> LR_4 [ label = "S(A)" ];
    |    LR_5 -> LR_7 [ label = "S(b)" ];
    |    LR_5 -> LR_5 [ label = "S(a)" ];
    |    LR_6 -> LR_6 [ label = "S(b)" ];
    |    LR_6 -> LR_5 [ label = "S(a)" ];
    |    LR_7 -> LR_8 [ label = "S(b)" ];
    |    LR_7 -> LR_5 [ label = "S(a)" ];
    |    LR_8 -> LR_6 [ label = "S(b)" ];
    |    LR_8 -> LR_5 [ label = "S(a)" ];
    |}
    |""".stripMargin

  private val emptyGraph =
    """digraph G {
       |}
       |""".stripMargin

  private val clusters =
    """digraph G {
      |	fontname="Helvetica,Arial,sans-serif"
      |	node [fontname="Helvetica,Arial,sans-serif"]
      |	edge [fontname="Helvetica,Arial,sans-serif"]
      |
      |	subgraph cluster_0 {
      |		style=filled;
      |		color=lightgrey;
      |		node [style=filled,color=white];
      |		a0 -> a1 -> a2 -> a3;
      |		label = "process #1";
      |	}
      |
      |	subgraph cluster_1 {
      |		node [style=filled];
      |		b0 -> b1 -> b2 -> b3;
      |		label = "process #2";
      |		color=blue
      |	}
      |	start -> a0;
      |	start -> b0;
      |	a1 -> b3;
      |	b2 -> a3;
      |	a3 -> a0;
      |	a3 -> end;
      |	b3 -> end;
      |
      |	start [shape=Mdiamond];
      |	end [shape=Msquare];
      |}""".stripMargin

  val examples = Map(
    "Finite State Machine" -> finiteStateMachine,
    "Empty Graph"          -> emptyGraph,
    "Clusters"             -> clusters
  )
