package org.jpablo.typeexplorer.shared.inheritance

import zio.test.*
import zio.test.Assertion.*

import org.jpablo.typeexplorer.shared.models

object InheritanceDiagramSpec extends ZIOSpecDefault {

  // sbt> testOnly org.jpablo.typeexplorer.shared.inheritance.*

  def makeClass (name: String) = 
    models.Namespace(models.Symbol(name), name, models.NamespaceKind.Class)

  val base0  = makeClass("base0")
  val base1  = makeClass("base1")
  val base2  = makeClass("base2")
  val classA = makeClass("classA")
  val classB = makeClass("classB")
  val classC = makeClass("classC")


  val diagram = InheritanceDiagram(
    arrows = List(
      base1.symbol  -> base0.symbol,
      base2.symbol  -> base0.symbol,
      classA.symbol -> base1.symbol,
      classA.symbol -> base2.symbol,
      classB.symbol -> classA.symbol,
      classC.symbol -> classA.symbol,
    ),
    List(base1, base2, classA, classB, classC)
  )

  def spec = suite("Related symbols spec")(
    test("Find all parents") {
      val filtered = 
        diagram.findParents(List(classB.symbol, classC.symbol))

      val expected = 
        List(
          base1.symbol  -> base0.symbol,
          base2.symbol  -> base0.symbol,
          classA.symbol -> base1.symbol,
          classA.symbol -> base2.symbol,
          classB.symbol -> classA.symbol,
          classC.symbol -> classA.symbol,
        )

      assertTrue(filtered.toSet == expected.toSet)
    },
    // test("Parents") {
    //   val filtered = 
    //     diagram.filterSymbols(List(classA.symbol -> Set(Related.Parents)))

    //   val expected = 
    //     InheritanceDiagram(arrows = List.empty, List(base1, base2, classA))  

    //   assertTrue(filtered == expected)
    // }
  )
}
