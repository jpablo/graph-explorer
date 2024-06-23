package org.jpablo.typeexplorer.viewer.examples

import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models

object Example1 {

  private def makeClass(name: String) =
    models.ViewerNode(models.ViewerNodeId(name), name, models.NamespaceKind.Class)

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

  val diagram = ViewerGraph(
    arrows = Set(
      base1.nodeId -> base0.nodeId,
      base2.nodeId -> base0.nodeId,
      classA.nodeId -> base1.nodeId,
      classA.nodeId -> base2.nodeId,
      classB.nodeId -> classA.nodeId,
      classC.nodeId -> classA.nodeId,
    ),
    Set(base0, base1, base2, classA, classB, classC)
  )

}
