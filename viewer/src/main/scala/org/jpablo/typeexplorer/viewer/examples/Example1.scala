package org.jpablo.typeexplorer.viewer.examples

import org.jpablo.typeexplorer.viewer.graph.InheritanceGraph
import org.jpablo.typeexplorer.viewer.models

object Example1 {

  private def makeClass(name: String) =
    models.Namespace(models.GraphSymbol(name), name, models.NamespaceKind.Class)

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

  val diagram = InheritanceGraph(
    arrows = Set(
      base1.symbol -> base0.symbol,
      base2.symbol -> base0.symbol,
      classA.symbol -> base1.symbol,
      classA.symbol -> base2.symbol,
      classB.symbol -> classA.symbol,
      classC.symbol -> classA.symbol,
    ),
    Set(base0, base1, base2, classA, classB, classC)
  )

}
