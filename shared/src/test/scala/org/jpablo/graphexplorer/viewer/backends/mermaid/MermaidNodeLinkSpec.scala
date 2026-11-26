package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, RoundTripLoss, UnmodeledContent}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Target, Tooltip, URL}
import org.jpablo.graphexplorer.viewer.graph.{InsertItem, NodeInsertion, ViewerGraph}
import org.jpablo.graphexplorer.viewer.models.*

/** The link of a Mermaid node: `click id href "url"`.
  *
  * The graph keeps the link of a node in the DOT attribute `URL`, with `tooltip` and `target`. A file node of a drop
  * has a link, and a command-click on the node opens it. A Mermaid source has the link only on a `click` line. Thus
  * the reader of the app must read the line into `URL`, the printer must write `URL` as the line, and the round-trip
  * guard must not refuse a canvas edit for the line. A `click` line with a callback is still content that the graph
  * does not keep.
  */
class MermaidNodeLinkSpec extends FunSuite:

  private def fromMermaid(text: String): ViewerGraph =
    MermaidFlowchartParser.parse(text) match
      case Right(graph) => toViewerGraph(graph)
      case Left(err)    => fail(s"the Mermaid reader rejects the text: $err\n$text")

  private def attr(g: ViewerGraph, id: String, attrId: AttributeId): Option[String] =
    g.nodes.get(NodeId(id)).flatMap(_.attributes.get(attrId)).map(_.toString)

  private def guard(text: String): Set[UnmodeledContent] = RoundTripLoss.scan(text, DiagramFormat.Mermaid)

  // ------------------------------------------------------------------ the reader

  test("the reader keeps the link of a `click id href` line in URL"):
    val g = fromMermaid("flowchart LR\n  a --> b\n  click a href \"docs/report 2026.pdf\"")
    assertEquals(attr(g, "a", URL.attrId), Some("docs/report 2026.pdf"))
    assertEquals(attr(g, "b", URL.attrId), None)

  test("the reader keeps the short form, the tooltip and the target"):
    val g = fromMermaid(
      "flowchart LR\n  a --> b\n  click a \"https://example.com/a\" \"Open A\" _blank\n  click b href \"file:///tmp/b.txt\" _self"
    )
    assertEquals(attr(g, "a", URL.attrId), Some("https://example.com/a"))
    assertEquals(attr(g, "a", Tooltip.attrId), Some("Open A"))
    assertEquals(attr(g, "a", Target.attrId), Some("_blank"))
    assertEquals(attr(g, "b", URL.attrId), Some("file:///tmp/b.txt"))
    assertEquals(attr(g, "b", Tooltip.attrId), None)
    assertEquals(attr(g, "b", Target.attrId), Some("_self"))

  test("the reader keeps the link of an image node"):
    val g = fromMermaid("flowchart LR\n  pic@{ img: \"arch.assets/img-3f2a.png\", w: 40, h: 20, constraint: \"on\" }\n  click pic href \"arch.assets/img-3f2a.png\"")
    assertEquals(attr(g, "pic", URL.attrId), Some("arch.assets/img-3f2a.png"))

  test("a callback click line gives no link"):
    val g = fromMermaid("flowchart LR\n  a --> b\n  click a call go()\n  click b go")
    assertEquals(attr(g, "a", URL.attrId), None)
    assertEquals(attr(g, "b", URL.attrId), None)

  // ------------------------------------------------------------------ the printer

  test("the printer writes URL as a `click id href` line"):
    val (g, ids) = NodeInsertion.insertAll(
      fromMermaid("flowchart LR\n  a --> b"),
      Seq(InsertItem.File("report 2026.pdf", Some("docs/report 2026.pdf"))),
      None
    )
    val text = viewerGraphToMermaidText(g)
    assert(text.contains(s"""  click ${ids.head.value} href "docs/report 2026.pdf"\n"""), text)

  test("the printer writes the tooltip and the target after the link"):
    val g    = fromMermaid("flowchart LR\n  a --> b\n  click a href \"https://example.com\" \"Open A\" _blank")
    val text = viewerGraphToMermaidText(g)
    assert(text.contains("""  click a href "https://example.com" "Open A" _blank"""), text)

  test("a file node keeps its link, its label and its id through a print and a parse"):
    val (g, ids) = NodeInsertion.insertAll(
      fromMermaid("flowchart LR\n  a --> b"),
      Seq(InsertItem.File("report.pdf", Some("file:///Users/me/report%20x.pdf"))),
      None
    )
    val id   = ids.head
    val back = fromMermaid(viewerGraphToMermaidText(g))
    assertEquals(attr(back, id.value, URL.attrId), Some("file:///Users/me/report%20x.pdf"))
    assertEquals(attr(back, id.value, Label.attrId), Some("report.pdf"))
    // A second print gives the same text: the round trip is stable.
    assertEquals(viewerGraphToMermaidText(back), viewerGraphToMermaidText(g))

  test("a quote in a link or a tooltip does not end the string of the click line"):
    val g = fromMermaid("flowchart LR\n  a --> b")
    val withLink = g
      .modify(_.elements.nodes.index(NodeId("a")))
      .using(_.modifyAttrs.using(_ + (URL.attrId -> AttrValue("docs/a\"b.pdf")) + (Tooltip.attrId -> AttrValue("say \"hi\""))))
    val text = viewerGraphToMermaidText(withLink)
    assert(text.contains("""click a href "docs/a%22b.pdf" "say 'hi'""""), text)

  // ------------------------------------------------------------------ the guard

  test("the guard does not flag a click line with a link"):
    assertEquals(guard("flowchart LR\n  a --> b\n  click a href \"docs/r.pdf\""), Set.empty)
    assertEquals(guard("flowchart LR\n  a --> b\n  click a \"https://example.com\""), Set.empty)
    assertEquals(guard("flowchart LR\n  a --> b\n  click a \"https://example.com\" \"Open\" _blank"), Set.empty)
    assertEquals(guard("flowchart LR\n  a --> b\n  click a href \"https://example.com\" \"Open\""), Set.empty)

  test("the guard flags a click line with a callback"):
    assertEquals(guard("flowchart LR\n  a --> b\n  click a call go()"), Set(UnmodeledContent.ClickHandlers))
    assertEquals(guard("flowchart LR\n  a --> b\n  click a go"), Set(UnmodeledContent.ClickHandlers))
    assertEquals(guard("flowchart LR\n  a --> b\n  click a go \"Tip\""), Set(UnmodeledContent.ClickHandlers))

  test("the guard flags a click line for more than one node, and a link with an unknown target"):
    assertEquals(guard("flowchart LR\n  a --> b\n  click a,b href \"x.pdf\""), Set(UnmodeledContent.ClickHandlers))
    assertEquals(guard("flowchart LR\n  a --> b\n  click a href \"x.pdf\" frame1"), Set(UnmodeledContent.ClickHandlers))

  test("the guard does not flag the printed text of a file node"):
    val (g, _) = NodeInsertion.insertAll(
      fromMermaid("flowchart LR\n  a --> b"),
      Seq(InsertItem.File("report.pdf", Some("docs/report.pdf")), InsertItem.Image("arch.assets/img-3f2a.png", 40, 20)),
      None
    )
    val text = viewerGraphToMermaidText(g)
    assertEquals(guard(text), Set.empty, text)
