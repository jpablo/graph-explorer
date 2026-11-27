package org.jpablo.graphexplorer.gxcore.model

import munit.FunSuite

/** The layout file of a diagram (ADR 0004).
  *
  * A person owns the file, and a later build can add keys to it. Thus the
  * literal below is the contract.
  */
class DiagramLayoutSpec extends FunSuite:

  private val layout = DiagramLayout(
    nodes = Map(
      "a"   -> NodePlace(27, 18),
      "a b" -> NodePlace(127.5, -18.25)
    ),
    arrows = Map(
      "a->a b#1" -> ArrowBends(Vector(LayoutPoint(60, 30), LayoutPoint(90, 10)))
    )
  )

  private val layoutText =
    """{
      |  "version": 1,
      |  "nodes": {
      |    "a": {
      |      "x": 27,
      |      "y": 18
      |    },
      |    "a b": {
      |      "x": 127.5,
      |      "y": -18.25
      |    }
      |  },
      |  "arrows": {
      |    "a->a b#1": {
      |      "points": [
      |        {
      |          "x": 60,
      |          "y": 30
      |        },
      |        {
      |          "x": 90,
      |          "y": 10
      |        }
      |      ]
      |    }
      |  }
      |}
      |""".stripMargin

  test("the text of a layout file is the contract"):
    assertEquals(DiagramLayout.render(layout), layoutText)

  test("a read of the text gives the layout again"):
    assertEquals(DiagramLayout.parse(layoutText), Right(layout))

  test("a render is the same for the same layout, in any order of the map"):
    val reordered = layout.copy(nodes = Map("a b" -> NodePlace(127.5, -18.25), "a" -> NodePlace(27, 18)))
    assertEquals(DiagramLayout.render(reordered), layoutText)

  test("an empty layout gives the version only, and a read gives the empty layout"):
    val text = DiagramLayout.render(DiagramLayout.empty)
    assertEquals(DiagramLayout.parse(text), Right(DiagramLayout.empty))
    assert(text.contains("\"version\": 1"), text)
    assert(!DiagramLayout.empty.hasPositions)

  test("a reader refuses a newer version"):
    val result = DiagramLayout.parse("""{"version": 2, "nodes": {}}""")
    assert(result.left.exists(_.contains("version 2")), result.toString)

  test("a reader refuses a file that is not a JSON object, and a node with no number"):
    assert(DiagramLayout.parse("[]").isLeft)
    assert(DiagramLayout.parse("not json").isLeft)
    assert(DiagramLayout.parse("""{"version": 1, "nodes": {"a": {"x": "1", "y": 2}}}""").isLeft)
    assert(DiagramLayout.parse("""{"version": 1, "nodes": {"a": {"y": 2}}}""").isLeft)

  test("a file with no version and no arrows reads as version 1"):
    assertEquals(
      DiagramLayout.parse("""{"nodes": {"a": {"x": 1, "y": 2}}}"""),
      Right(DiagramLayout(nodes = Map("a" -> NodePlace(1, 2))))
    )

  test("a write gives back each key that this build does not know"):
    val text =
      """{"version": 1, "grid": 8, "nodes": {"a": {"x": 1, "y": 2, "locked": true}},
        | "arrows": {"a->b#1": {"points": [], "style": "ortho"}}}""".stripMargin
    val read = DiagramLayout.parse(text).fold(fail(_), identity)
    val back = DiagramLayout.parse(DiagramLayout.render(read)).fold(fail(_), identity)
    assertEquals(back, read)
    val rendered = DiagramLayout.render(read)
    assert(rendered.contains("\"grid\": 8"), rendered)
    assert(rendered.contains("\"locked\": true"), rendered)
    assert(rendered.contains("\"style\": \"ortho\""), rendered)

  test("a node id that the graph does not have stays in the layout, and the restriction leaves it out"):
    val restricted = layout.restrictedTo(Set("a", "c"))
    assertEquals(restricted.nodes.keySet, Set("a"))
    // The layout itself keeps the id, so that an undo of a rename finds it again.
    assertEquals(layout.nodes.keySet, Set("a", "a b"))

  test("placed sets and replaces positions, and movedBy moves only the stored ones"):
    val placed = layout.placed(Map("a" -> LayoutPoint(0, 0), "c" -> LayoutPoint(5, 6)))
    assertEquals(placed.point("a"), Some(LayoutPoint(0, 0)))
    assertEquals(placed.point("c"), Some(LayoutPoint(5, 6)))
    assertEquals(placed.point("a b"), Some(LayoutPoint(127.5, -18.25)))
    val moved = placed.movedBy(Set("a", "missing"), 10, -5)
    assertEquals(moved.point("a"), Some(LayoutPoint(10, -5)))
    assertEquals(moved.point("c"), Some(LayoutPoint(5, 6)))
    assertEquals(moved.point("missing"), None)

  test("withoutPositions removes the nodes and the arrows, and keeps the unknown keys"):
    val read = DiagramLayout.parse("""{"version": 1, "grid": 8, "nodes": {"a": {"x": 1, "y": 2}}}""").fold(fail(_), identity)
    val arranged = read.withoutPositions
    assert(!arranged.hasPositions)
    assertEquals(arranged.arrows, Map.empty)
    assert(DiagramLayout.render(arranged).contains("\"grid\": 8"))

  test("withBends stores the bends of one arrow, and no bend removes its key"):
    val bent = DiagramLayout.empty.withBends("a->b#1", Vector(LayoutPoint(9, 18), LayoutPoint(27, 18)))
    assertEquals(bent.bends("a->b#1"), Vector(LayoutPoint(9, 18), LayoutPoint(27, 18)))
    assertEquals(bent.bends("b->c#1"), Vector.empty)
    val straight = bent.withBends("a->b#1", Vector.empty)
    assertEquals(straight.arrows, Map.empty)
    // A key that this build does not know stays, with no point.
    val withOther = DiagramLayout(arrows = Map("a->b#1" -> ArrowBends(Vector(LayoutPoint(1, 2)), Vector("style" -> "\"ortho\""))))
    assertEquals(withOther.withBends("a->b#1", Vector.empty).arrows("a->b#1"), ArrowBends(Vector.empty, Vector("style" -> "\"ortho\"")))

  test("withoutBends removes the bends of the given arrows only"):
    val bent = DiagramLayout.empty
      .withBends("a->b#1", Vector(LayoutPoint(9, 18)))
      .withBends("b->c#1", Vector(LayoutPoint(0, 0)))
    assertEquals(bent.withoutBends(Set("a->b#1", "missing")).arrows.keySet, Set("b->c#1"))

  test("movedBy with arrow keys moves the bends of those arrows by the same amount as the nodes"):
    val bent = layout.withBends("a->b#1", Vector(LayoutPoint(9, 18))).withBends("b->c#1", Vector(LayoutPoint(0, 0)))
    val moved = bent.movedBy(Set("a"), 10, -5, arrows = Set("a->b#1"))
    assertEquals(moved.point("a"), Some(LayoutPoint(37, 13)))
    assertEquals(moved.bends("a->b#1"), Vector(LayoutPoint(19, 13)))
    assertEquals(moved.bends("b->c#1"), Vector(LayoutPoint(0, 0)))

  test("Arrange removes the bends with the positions"):
    val bent = layout.withBends("a->b#1", Vector(LayoutPoint(9, 18)))
    assertEquals(bent.withoutPositions.arrows, Map.empty)

  test("a point rounds to two decimals"):
    assertEquals(LayoutPoint(1.23456, -7.891).rounded, LayoutPoint(1.23, -7.89))

  test("a layout file path ends with .layout.json"):
    assert(DiagramLayout.isLayoutPath("/w/arch.layout.json"))
    assert(DiagramLayout.isLayoutPath("/w/ARCH.LAYOUT.JSON"))
    assert(!DiagramLayout.isLayoutPath("/w/arch.dot"))
    assert(!DiagramLayout.isLayoutPath("/w/layout.json.dot"))
