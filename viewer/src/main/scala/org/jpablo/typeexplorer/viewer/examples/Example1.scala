package org.jpablo.typeexplorer.viewer.examples

import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.models.ViewerNode.node

object Example1 {

  val base0 = node("base0")
  val base1 = node("base1")
  val base2 = node("base2")
  val classA = node("classA")
  val classB = node("classB")
  val classC = node("classC")

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
    ViewerGraph(
      arrows = Set(
        base1.id  -> base0.id,
        base2.id  -> base0.id,
        classA.id -> base1.id,
        classA.id -> base2.id,
        classB.id -> classA.id,
        classC.id -> classA.id
      ),
      Set(base0, base1, base2, classA, classB, classC)
    )

}
