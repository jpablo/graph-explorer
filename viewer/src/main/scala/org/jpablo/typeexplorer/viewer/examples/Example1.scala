package org.jpablo.typeexplorer.viewer.examples

import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models

object Example1 {

  private def makeClass(name: String) =
    models.ViewerNode(models.NodeId(name), name)

  val base0 = makeClass("base0")
  val base1 = makeClass("base1")
  val base2 = makeClass("base2")
  val classA = makeClass("classA")
  val classB = makeClass("classB")
  val classC = makeClass("classC")

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
