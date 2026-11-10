package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.NodeId

/** The flowchart title through the headless path: `MermaidFlowchartParser`,
  * then the printer. The `gx` CLI uses this path on the JVM.
  *
  * The printer writes the title as the YAML front matter `title:`. In plain
  * YAML, `: ` makes a second mapping, and ` #` starts a comment. Thus such a
  * title must stay in quotes, and the parser must read the quotes back.
  * `FlowchartTitleSpec` in the viewer shows what Mermaid itself draws.
  */
class MermaidTitleRoundTripSpec extends FunSuite:

  private def parse(text: String) =
    toViewerGraph(MermaidFlowchartParser.parse(text).fold(e => fail(s"could not parse:\n$text\n$e"), identity))

  private def titleOf(text: String): Option[String] =
    parse(text).elements.graphAttributes.values.get(Label.attrId).map(_.toString)

  private def print(text: String): String = viewerGraphToMermaidText(parse(text))

  private def source(titleLine: String) = s"---\n$titleLine\n---\nflowchart TD\n  A --> B\n"

  private val titles = List("My chart", "Deps: v2", "Bug #1", "say \"hi\"", "it's", "a \\ b")

  test("a printed title is written in a form that the YAML front matter keeps"):
    assert(!print(source("title: \"Deps: v2\"")).contains("title: Deps: v2"), print(source("title: \"Deps: v2\"")))
    assert(!print(source("title: \"Bug #1\"")).contains("title: Bug #1"), print(source("title: \"Bug #1\"")))
    assert(print(source("title: My chart")).contains("title: My chart\n"), print(source("title: My chart")))

  test("the parser reads the printed title back"):
    for title <- titles do
      val graph   = parse(source("title: My chart"))
      val attrs   = graph.elements.graphAttributes
      val titled  = graph.copy(elements = graph.elements.copy(graphAttributes =
        attrs.copy(values = attrs.values.updated(Label.attrId, AttrValue(title)))
      ))
      val printed = viewerGraphToMermaidText(titled)
      assertEquals(titleOf(printed), Some(title), s"printed:\n$printed")

  test("a second print does not change the title"):
    for title <- titles do
      val once  = print(source(s"title: \"${title.replace("\\", "\\\\").replace("\"", "\\\"")}\""))
      val twice = print(once)
      assertEquals(twice, once, s"the title changed on the second print")
      assertEquals(titleOf(once), Some(title), s"printed:\n$once")

  test("an accTitle is not the title, and not a node"):
    val text  = "flowchart TD\n  accTitle: Service map\n  A --> B\n"
    val graph = parse(text)
    assertEquals(graph.nodeIds, Set(NodeId("A"), NodeId("B")))
    assertEquals(titleOf(text), None)
    assert(!print(text).contains("title:"), print(text))
