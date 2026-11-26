package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.graphviz.html.ImageDim
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, RoundTripLoss}
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotImages
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotToViewerGraph}
import org.jpablo.graphexplorer.viewer.backends.mermaid.{MermaidFlowchartParser, toViewerGraph as mermaidToViewerGraph, viewerGraphToMermaidText}
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Image, Label, URL}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.utils.ImageHeader
import upickle.default.read

/** A paste or a drop puts a new node on the canvas (`NodeInsertion`). The node must print correctly in DOT and in
  * Mermaid, and a parse of the printed text must give the same node again. A later canvas edit prints the graph
  * again, so the node must survive that round trip.
  */
class NodeInsertionSpec extends FunSuite:
  import NodeInsertionSpec.*

  // ------------------------------------------------------------------ helpers

  private def fromDot(dot: String): ViewerGraph =
    ScalaGraphviz.structureJson(dot) match
      case Right(json) => dotToViewerGraph(read[SimpleGraph](json))
      case Left(err)   => fail(s"the DOT reader rejects the text: $err\n$dot")

  private def fromMermaid(text: String): ViewerGraph =
    MermaidFlowchartParser.parse(text) match
      case Right(graph) => mermaidToViewerGraph(graph)
      case Left(err)    => fail(s"the Mermaid reader rejects the text: $err\n$text")

  private def dot(g: ViewerGraph): String = ViewerGraph.viewerGraphToText(g, omitInternal = true)

  private def labelOf(g: ViewerGraph, id: NodeId): Option[String] =
    g.nodes.get(id).flatMap(_.attributes.get(Label.attrId)).map(_.toString)

  private val dotStart     = fromDot("digraph G {\n  a -> b\n}")
  private val mermaidStart = fromMermaid("flowchart TD\n  a --> b")

  // ------------------------------------------------------------------ text

  test("a text item gives a node whose label is the text, with an id from the text"):
    val (g, id) = NodeInsertion.insert(dotStart, InsertItem.Text("Hello world"))
    assertEquals(id, NodeId("hello_world"))
    assertEquals(labelOf(g, id), Some("Hello world"))
    assert(g.arrows.size == 1, "a paste adds no arrow")

  test("the id of a new node does not take the id of a node or a group"):
    val withGroup = fromDot("digraph G {\n  hello_world\n  subgraph cluster_x { label=\"x\"; c }\n}")
    val (g1, id1) = NodeInsertion.insert(withGroup, InsertItem.Text("Hello world"))
    assertEquals(id1, NodeId("hello_world_2"))
    val (_, id2) = NodeInsertion.insert(g1, InsertItem.Text("hello world"))
    assertEquals(id2, NodeId("hello_world_3"))
    val groupId  = withGroup.groupIds.find(_ != GroupId(ViewerGraphElements.defaultRootId.value)).get
    val (_, id3) = NodeInsertion.insert(withGroup, InsertItem.Text(groupId.value))
    assert(id3.value != groupId.value, s"the new node took the id of the group: $id3")

  test("a text with no letters, or a keyword, gets a numbered id"):
    val (g1, id1) = NodeInsertion.insert(dotStart, InsertItem.Text("123 !!"))
    assertEquals(id1, NodeId("text1"))
    val (_, id2) = NodeInsertion.insert(g1, InsertItem.Text("end"))
    assertEquals(id2, NodeId("text2"))
    val (_, id3) = NodeInsertion.insert(dotStart, InsertItem.Text("subgraph"))
    assertEquals(id3, NodeId("text1"))

  test("a long text gives a short id"):
    val (_, id) = NodeInsertion.insert(dotStart, InsertItem.Text("The quick brown fox jumps over the lazy dog"))
    assert(id.value.length <= 24, s"the id is too long: $id")
    assert(id.value.startsWith("the_quick"), s"the id does not come from the text: $id")

  test("the text is stored with the escapes of a DOT label, and keeps its line breaks"):
    val text     = "line one\r\nsay \"hi\" \\ there\nend"
    val (g, id)  = NodeInsertion.insert(dotStart, InsertItem.Text(text))
    assertEquals(labelOf(g, id), Some(TextUtils.escape("line one\nsay \"hi\" \\ there\nend")))

  test("the blanks at the ends of the text go away"):
    val (g, id) = NodeInsertion.insert(dotStart, InsertItem.Text("  \n  hello  \n\n"))
    assertEquals(labelOf(g, id), Some("hello"))

  for text <- SpecialTexts do
    test(s"DOT: a text node keeps its label through a print and a parse: ${pprint(text)}"):
      val (g, id) = NodeInsertion.insert(dotStart, InsertItem.Text(text))
      val printed = dot(g)
      val back    = fromDot(printed)
      assertEquals(labelOf(back, id), labelOf(g, id), s"--- printed ---\n$printed")
      assertEquals(dot(back), printed, "a second round trip changes the text")

    test(s"Mermaid: a text node keeps its label through a print and a parse: ${pprint(text)}"):
      val (g, id) = NodeInsertion.insert(mermaidStart, InsertItem.Text(text))
      val printed = viewerGraphToMermaidText(g)
      val back    = fromMermaid(printed)
      assertEquals(labelOf(back, id), labelOf(g, id), s"--- printed ---\n$printed")
      assertEquals(viewerGraphToMermaidText(back), printed, "a second round trip changes the text")

  test("DOT: a text that looks like HTML is not printed as an HTML label"):
    val (g, id) = NodeInsertion.insert(dotStart, InsertItem.Text("use <b>bold</b> here"))
    val printed = dot(g)
    assert(!printed.contains("<use"), s"the label is an HTML label:\n$printed")
    assert(
      printed.contains(s"\"${id.value}\" [label=\"use &lt;b&gt;bold&lt;/b&gt; here\"]"),
      s"the label is not a quoted string:\n$printed"
    )

  // ------------------------------------------------------------------ image

  test("the size of an image is its natural size, at most 400 pixels wide, with the same aspect ratio"):
    assertEquals(NodeInsertion.displaySize(2, 1), (2, 1))
    assertEquals(NodeInsertion.displaySize(400, 50), (400, 50))
    assertEquals(NodeInsertion.displaySize(800, 600), (400, 300))
    assertEquals(NodeInsertion.displaySize(1000, 1), (400, 1))
    assertEquals(NodeInsertion.displaySize(0, 0), (1, 1))

  test("an image item gives an image node with no text and a numbered id"):
    val (g1, id1) = NodeInsertion.insert(dotStart, InsertItem.Image(Png2x1, 2, 1))
    val (g2, id2) = NodeInsertion.insert(g1, InsertItem.Image(Png2x1, 2, 1))
    assertEquals((id1, id2), (NodeId("image1"), NodeId("image2")))
    assertEquals(labelOf(g2, id1), Some(""))
    assertEquals(ImageNode.of(g2.nodes(id1).attributes), Some(ImageNode.Spec(Png2x1, Some((2, 1)), "")))
    assertEquals(g2.nodes(id1).attributes.get(Image.attrId).map(_.toString), Some(Png2x1))

  test("DOT: an image node prints with its image, its size, and no text"):
    val (g, id) = NodeInsertion.insert(dotStart, InsertItem.Image(Png2x1, 800, 600))
    val printed = dot(g)
    val start   = printed.indexOf(s"\"${id.value}\" [")
    assert(start >= 0, printed)
    val line = printed.substring(start, printed.indexOf("]", start))
    assert(line.contains(s"""image="$Png2x1""""), line)
    assert(line.contains("""label="""""), line)
    assert(line.contains("""fixedsize="true""""), line)
    assert(line.contains("""imagescale="true""""), line)
    assert(line.contains("""width="5.5556"""") && line.contains("""height="4.1667""""), line)

  test("DOT: an image node keeps its form through a print and a parse"):
    val (g, id) = NodeInsertion.insert(dotStart, InsertItem.Image(Png2x1, 800, 600))
    val printed = dot(g)
    val back    = fromDot(printed)
    assertEquals(ImageNode.of(back.nodes(id).attributes), Some(ImageNode.Spec(Png2x1, Some((400, 300)), "")))
    assertEquals(dot(back), printed, "a second round trip changes the text")

  test("Mermaid: an image node prints as the image shape of Mermaid 11"):
    val (g, id) = NodeInsertion.insert(mermaidStart, InsertItem.Image(Png2x1, 800, 600))
    val printed = viewerGraphToMermaidText(g)
    assert(
      printed.contains(s"""  ${id.value}@{ img: "$Png2x1", w: 400, h: 300, constraint: "on" }"""),
      s"--- printed ---\n$printed"
    )
    assert(!printed.contains(s"style ${id.value}"), s"the image form must not print a style line:\n$printed")

  test("Mermaid: an image node keeps its form through a print and a parse"):
    val (g, id) = NodeInsertion.insert(mermaidStart, InsertItem.Image(Png2x1, 800, 600))
    val printed = viewerGraphToMermaidText(g)
    val back    = fromMermaid(printed)
    assertEquals(ImageNode.of(back.nodes(id).attributes), Some(ImageNode.Spec(Png2x1, Some((400, 300)), "")))
    assertEquals(viewerGraphToMermaidText(back), printed, "a second round trip changes the text")

  test("Mermaid: an image node with an arrow keeps the arrow and the image"):
    val (g, id) = NodeInsertion.insert(mermaidStart, InsertItem.Image(Png2x1, 2, 1))
    val linked  = g.addArrow(NodeId("a"), id)._1
    val printed = viewerGraphToMermaidText(linked)
    val back    = fromMermaid(printed)
    assert(back.arrows.values.exists(a => a.source == NodeId("a") && a.target == id), printed)
    assertEquals(ImageNode.of(back.nodes(id).attributes).map(_.src), Some(Png2x1))
    assertEquals(viewerGraphToMermaidText(back), printed)

  test("Mermaid: a written image shape with a label and no size reads as an image node"):
    val back = fromMermaid("flowchart TD\n  pic@{ img: \"https://example.com/a.png\", label: \"A #quot;pic#quot;\" }\n  pic --> b")
    assertEquals(
      ImageNode.of(back.nodes(NodeId("pic")).attributes),
      Some(ImageNode.Spec("https://example.com/a.png", None, "A \"pic\""))
    )

  test("the image node does not trip the round-trip loss guard"):
    val (g, _) = NodeInsertion.insert(mermaidStart, InsertItem.Image(Png2x1, 2, 1))
    assertEquals(RoundTripLoss.scan(viewerGraphToMermaidText(g), DiagramFormat.Mermaid), Set.empty)
    val (d, _) = NodeInsertion.insert(dotStart, InsertItem.Image(Png2x1, 2, 1))
    assertEquals(RoundTripLoss.scan(dot(d), DiagramFormat.DOT), Set.empty)

  // ------------------------------------------------------------------ file

  test("a file item gives a node with the file name and its link"):
    val (g, id) = NodeInsertion.insert(dotStart, InsertItem.File("Report 2026.pdf", Some("file:///tmp/Report%202026.pdf")))
    assertEquals(id, NodeId("report_2026"))
    assertEquals(labelOf(g, id), Some("Report 2026.pdf"))
    assertEquals(g.nodes(id).attributes.get(URL.attrId).map(_.toString), Some("file:///tmp/Report%202026.pdf"))
    val (_, noName) = NodeInsertion.insert(dotStart, InsertItem.File("1.txt", None))
    assertEquals(noName, NodeId("file1"))

  // ------------------------------------------------------------------ the size of an image for Graphviz

  test("the header of an image gives its natural size"):
    assertEquals(ImageHeader.ofDataUri(Png2x1), Some(ImageHeader.Size(2, 1)))
    assertEquals(ImageHeader.ofDataUri("data:image/gif;base64,R0lGODlhAwAFAAAAADs="), Some(ImageHeader.Size(3, 5)))
    assertEquals(
      ImageHeader.ofDataUri("data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/wAALCAAHAAkBAREA/9k="),
      Some(ImageHeader.Size(9, 7))
    )
    assertEquals(
      ImageHeader.ofDataUri("data:image/svg+xml;base64," + base64("""<svg xmlns="http://www.w3.org/2000/svg" width="30" height="20"></svg>""")),
      Some(ImageHeader.Size(30, 20))
    )
    assertEquals(
      ImageHeader.ofDataUri("data:image/svg+xml;base64," + base64("""<svg viewBox="0 0 12.5 4"></svg>""")),
      Some(ImageHeader.Size(13, 4))
    )
    assertEquals(ImageHeader.ofDataUri("data:image/png;base64,AAAA"), None)
    assertEquals(ImageHeader.ofDataUri("not a data uri"), None)

  test("the size table of a DOT text has each data URI of an image, in points"):
    val (g, _) = NodeInsertion.insert(dotStart, InsertItem.Image(Png2x1, 2, 1))
    assertEquals(DotImages.table(dot(g)), Map(Png2x1 -> ImageDim(1.5, 0.75)))
    assertEquals(DotImages.table("digraph { a [image=\"x.png\"] }"), ImageDim.empty)

  test("Graphviz draws the image of an image node at the size of the node"):
    val src     = pngHeader(800, 600)
    val (g, id) = NodeInsertion.insert(dotStart, InsertItem.Image(src, 800, 600))
    val text    = dot(g)
    val result  = ScalaGraphviz.renderFormats(text, Seq("svg"), DotImages.table(text))
    assertEquals(result.status, "success", result.errors.toString)
    val svg   = result.output("svg")
    val image = ImageTag.findFirstIn(svg).getOrElse(fail(s"the svg has no image:\n${svg.take(2000)}"))
    assert(image.contains(s"""xlink:href="$src""""), image)
    def px(name: String): Double =
      s"""$name="([0-9.]+)px"""".r.findFirstMatchIn(image).map(_.group(1).toDouble).getOrElse(fail(image))
    assert(math.abs(px("width") - 400) < 0.01 && math.abs(px("height") - 300) < 0.01, image)
    assert(!svg.contains(s">${id.value}</text>"), "the image node must show no text")

  // ------------------------------------------------------------------ the stored image

  test("an image wider than 800 pixels is stored at 800 pixels wide, with the same aspect ratio"):
    assertEquals(NodeInsertion.storedSize(1600, 800), Some((800, 400)))
    assertEquals(NodeInsertion.storedSize(2560, 1600), Some((800, 500)))
    assertEquals(NodeInsertion.storedSize(5000, 3), Some((800, 1)))

  test("an image 800 pixels wide or less is stored as it is"):
    assertEquals(NodeInsertion.storedSize(800, 600), None)
    assertEquals(NodeInsertion.storedSize(300, 150), None)

  test("a stored image of 800 pixels shows at 400 pixels"):
    val (w, h) = NodeInsertion.storedSize(1600, 800).get
    assertEquals(NodeInsertion.displaySize(w, h), (400, 200))

  test("a JPEG source stays a JPEG, and the other sources become a PNG"):
    assertEquals(NodeInsertion.storedType("image/jpeg"), "image/jpeg")
    assertEquals(NodeInsertion.storedType("image/JPG"), "image/jpeg")
    List("image/png", "image/gif", "image/webp", "image/bmp", "").foreach: t =>
      assertEquals(NodeInsertion.storedType(t), "image/png", t)

  test("an SVG image is never redrawn"):
    assert(!NodeInsertion.redrawable("image/svg+xml"))
    assert(NodeInsertion.redrawable("image/png"))
    assert(NodeInsertion.redrawable("image/jpeg"))

  // ------------------------------------------------------------------ groups and several items

  test("a new node goes into the given group"):
    val withGroup = fromDot("digraph G {\n  a\n  subgraph cluster_x { label=\"x\"; c }\n}")
    val group     = withGroup.membership(NodeId("c")).get
    val (g, id)   = NodeInsertion.insert(withGroup, InsertItem.Text("Hello world"), Some(group))
    assertEquals(g.membership(id), Some(group))
    assert(dot(g).contains("cluster_x"), dot(g))
    val printed = fromDot(dot(g))
    assertEquals(printed.membership(id).map(_.value), Some(group.value), dot(g))

  test("several items give several nodes with different ids, in one graph"):
    val items = List(
      InsertItem.Image(Png2x1, 2, 1),
      InsertItem.Image(Png2x1, 2, 1),
      InsertItem.File("report.pdf", None),
      InsertItem.File("report.pdf", None)
    )
    val (g, ids) = NodeInsertion.insertAll(dotStart, items, None)
    assertEquals(ids, List(NodeId("image1"), NodeId("image2"), NodeId("report"), NodeId("report_2")))
    assertEquals(g.nodeIds.toSet, Set(NodeId("a"), NodeId("b")) ++ ids)

end NodeInsertionSpec

object NodeInsertionSpec:

  /** A PNG image of 2 × 1 pixels. */
  val Png2x1 =
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8DwH4QBEfcD/ePF9e8AAAAASUVORK5CYII="

  val ImageTag = "<image [^>]*>".r

  /** Texts with the characters that a DOT or a Mermaid label must escape. */
  val SpecialTexts = List(
    "plain words",
    "say \"hello\"",
    "back\\slash and \\n literal",
    "two\nlines",
    "brackets [x] (y) {z} |w|",
    "a; b: c # d",
    "less < and > more"
  )

  def pprint(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  def base64(text: String): String = base64(text.getBytes("UTF-8"))

  def base64(bytes: Array[Byte]): String = java.util.Base64.getEncoder.encodeToString(bytes)

  /** A `data:` URI with only the start of a PNG file: the signature and the IHDR chunk. The CRC is not real. */
  def pngHeader(width: Int, height: Int): String =
    def int(n: Int): Seq[Byte] = Seq(n >>> 24, n >>> 16, n >>> 8, n).map(_.toByte)
    val signature = Seq(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)
    val ihdr      = int(13) ++ "IHDR".getBytes("US-ASCII") ++ int(width) ++ int(height) ++ Seq[Byte](8, 6, 0, 0, 0) ++ int(0)
    "data:image/png;base64," + base64((signature ++ ihdr).toArray)
