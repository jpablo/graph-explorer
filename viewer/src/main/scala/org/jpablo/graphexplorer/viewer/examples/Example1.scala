package org.jpablo.graphexplorer.viewer.examples

import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.NodeId

object Example1 {

  val base0 = NodeId("base0")
  val base1 = NodeId("base1")
  val base2 = NodeId("base2")
  val classA = NodeId("classA")
  val classB = NodeId("classB")
  val classC = NodeId("classC")

  /*
          ┌─────┐
          │base0│
          └─────┘
             ▲
     ┌───────┴──────┐
     │              │
  ┌───────┐      ┌───────┐
  │ base1 │      │ base2 │
  └───────┘      └───────┘
      ▲              ▲
      └───────┬──────┘
              │
          ┌──────┐
          │classA│
          └──────┘
              ▲
        ┌─────┴─────┐
        │           │
    ┌──────┐    ┌──────┐
    │classB│    │classC│
    └──────┘    └──────┘
   */

  val graph =
    ViewerGraph.basic(
      base1  -> base0,
      base2  -> base0,
      classA -> base1,
      classA -> base2,
      classB -> classA,
      classC -> classA
    )

}
