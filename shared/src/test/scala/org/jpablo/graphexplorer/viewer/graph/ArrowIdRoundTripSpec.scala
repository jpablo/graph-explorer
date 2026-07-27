package org.jpablo.graphexplorer.viewer.graph

import com.softwaremill.quicklens.*
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithId

import scala.collection.immutable.VectorMap

/** An arrow advertises its id twice: once as the key it is stored under
  * ([[Arrow.id]]) and once as the `id` attribute written into the DOT, which
  * becomes the `id` of the SVG element and therefore the id a click hands back.
  * Those two have to be the same string, or the round trip
  *
  *   arrows map -> DOT -> SVG -> click -> arrows map
  *
  * lands on nothing.
  *
  * When they disagreed, an edge WITH A PORT was invisible to the attributes
  * toolbar in both directions: `getAttributesById` missed, so every control read
  * its default and no row looked changed; and `updateAttributes` filters by the
  * same key, so edits to it were silently dropped. Ports are the only thing that
  * ever differed, which is why plain edges behaved and only ported ones did not.
  */
class ArrowIdRoundTripSpec extends FunSuite:

  private val a = NodeId("A")
  private val b = NodeId("B")

  private def elementsWith(arrow: Arrow) =
    ViewerGraphElements(
      nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
      arrows = Map(arrow.id -> arrow)
    )

  private def graphWith(arrow: Arrow) = ViewerGraph(elementsWith(arrow))

  /** The `id="arrow:..."` the serializer writes for the one edge in the text. */
  private def emittedArrowId(text: String): Option[ArrowId] =
    raw"""id="(arrow:[^"]+)"""".r.findFirstMatchIn(text).flatMap(m => Arrow.fromSvg(m.group(1)))

  // A string-valued attribute on purpose: Double.toString differs between the JVM ("3.0")
  // and Scala.js ("3"), and this suite is about ids, not number formatting.
  private val ported =
    Arrow(a, b, Attributes.of(Color -> "red"), sourcePort = Some("se"), seq = 0)

  private val plain =
    Arrow(a, b, Attributes.of(Color -> "red"), seq = 0)

  test("a ported arrow serializes the id it is keyed by"):
    val graph = graphWith(ported)
    val text  = viewerGraphElementsToText(graph.elements)
    assertEquals(emittedArrowId(text), Some(ported.id))

  test("a plain arrow serializes the id it is keyed by"):
    val graph = graphWith(plain)
    val text  = viewerGraphElementsToText(graph.elements)
    assertEquals(emittedArrowId(text), Some(plain.id))

  // A port lives in two places — the structural sourcePort/targetPort that import fills in
  // and Arrow.id is built from, and the tailport/headport attribute the toolbar row edits.
  // Emitting both produced an edge that contradicted itself, and Graphviz sides with the
  // node-reference port, so the drawing kept the value the panel had just replaced.
  test("an edited port wins over the structural one, and is emitted once"):
    val edited = ported.modify(_.attributes).using(_ + (TailPort.attrId -> AttrValue("n")))
    val text   = viewerGraphElementsToText(elementsWith(edited))

    assert(text.contains(""""A":"n" -> "B""""), s"expected the edited port on the edge, got:\n$text")
    assert(!text.contains(""""A":"se""""), s"stale structural port survived:\n$text")
    assert(!text.contains("tailport"), s"port emitted twice, as port AND attribute:\n$text")

  test("an untouched structural port is still emitted"):
    val text = viewerGraphElementsToText(elementsWith(ported))
    assert(text.contains(""""A":"se" -> "B""""), s"expected the imported port, got:\n$text")

  // The property the toolbar actually depends on: take the id back off the
  // serialized edge, as a click would, and the attributes have to come back.
  for (name, arrow) <- List("ported" -> ported, "plain" -> plain) do
    test(s"attributes of a $name arrow are reachable by its serialized id"):
      val graph    = graphWith(arrow)
      val text     = viewerGraphElementsToText(graph.elements)
      val clicked  = emittedArrowId(text).getOrElse(fail("no id attribute was emitted"))
      val statuses = graph.getAttributesUpdatesById(ElementIds.from(clicked)).statuses
      assertEquals(statuses.get(Color.attrId).map(_.toString), Some("red"))

    test(s"an edit addressed by a $name arrow's serialized id is applied"):
      val graph   = graphWith(arrow)
      val text    = viewerGraphElementsToText(graph.elements)
      val clicked = emittedArrowId(text).getOrElse(fail("no id attribute was emitted"))
      val edited  = graph.updateAttributes(ElementIds.from(clicked), AttributeUpdates.of(Color -> "blue"))
      assertEquals(
        edited.arrows.values.head.attributes.get(Color).map(_.toString),
        Some("blue")
      )
