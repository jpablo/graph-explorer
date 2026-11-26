package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.AttributeId

/** The global properties of a Mermaid diagram through the init directive.
  *
  * The directive `%%{init: {...}}%%` keeps the theme and the theme variables
  * `background`, `fontSize` and `fontFamily`. The parser puts them in graph
  * attributes, and the printer writes them back in one directive. The printer
  * keeps the other keys of the directive, and it writes no directive when the
  * graph has no global property.
  */
class MermaidInitDirectiveSpec extends FunSuite:

  // The sbt client shows a munit failure with no message. An AssertionError shows the clue.
  private def check(clue: String, cond: Boolean): Unit = if !cond then throw AssertionError(clue)

  private def parse(text: String): ViewerGraph =
    toViewerGraph(MermaidFlowchartParser.parse(text).fold(e => throw AssertionError(s"could not parse:\n$text\n$e"), identity))

  private def attrsOf(graph: ViewerGraph): Map[AttributeId, String] =
    graph.elements.graphAttributes.values.view.mapValues(_.toString).toMap

  private def withAttrs(graph: ViewerGraph)(f: Map[AttributeId, AttrValue] => Map[AttributeId, AttrValue]): ViewerGraph =
    val attrs = graph.elements.graphAttributes
    graph.copy(elements = graph.elements.copy(graphAttributes = attrs.copy(values = f(attrs.values))))

  private def print(graph: ViewerGraph): String = viewerGraphToMermaidText(graph)

  private def configOf(text: String): ujson.Obj =
    MermaidInitDirective.configOf(text).map(ujson.read(_).obj).map(ujson.Obj.from).getOrElse(throw AssertionError(s"no directive in:\n$text"))

  private val body = "flowchart LR\n  A --> B\n"

  private val full =
    """%%{init: {"theme": "dark", "themeVariables": {"background": "#eeeeee", "fontSize": "18px", "fontFamily": "Georgia"}}}%%
      |""".stripMargin + body

  test("the parser reads the theme and the three theme variables"):
    val attrs = attrsOf(parse(full))
    check(s"theme: $attrs", attrs.get(MermaidThemeAttr).contains("dark"))
    check(s"background: $attrs", attrs.get(MermaidBackgroundAttr).contains("#eeeeee"))
    check(s"font size: $attrs", attrs.get(MermaidFontSizeAttr).contains("18"))
    check(s"font family: $attrs", attrs.get(MermaidFontFamilyAttr).contains("Georgia"))

  test("the printer writes the properties back, and a second print does not change the text"):
    val once = print(parse(full))
    check(s"no directive at the top:\n$once", once.startsWith("%%{init: "))
    val config = configOf(once)
    check(s"theme: $config", config("theme").str == "dark")
    val vars = config("themeVariables").obj
    check(s"background: $config", vars("background").str == "#eeeeee")
    check(s"font size: $config", vars("fontSize").str == "18px")
    check(s"font family: $config", vars("fontFamily").str == "Georgia")
    val twice = print(parse(once))
    check(s"the second print changed the text:\n$once\n---\n$twice", twice == once)

  test("a change of a property keeps the other keys of the directive"):
    val source =
      """%%{init: {"flowchart": {"curve": "basis"}, "theme": "forest", "themeVariables": {"primaryColor": "#ff0000", "fontSize": "12px"}}}%%
        |""".stripMargin + body
    val changed = withAttrs(parse(source))(_.updated(MermaidThemeAttr, AttrValue("neutral")).updated(MermaidBackgroundAttr, AttrValue("#fafafa")))
    val printed = print(changed)
    val config  = configOf(printed)
    check(s"theme:\n$printed", config("theme").str == "neutral")
    check(s"the flowchart key is lost:\n$printed", config.value.get("flowchart").exists(_("curve").str == "basis"))
    val vars = config("themeVariables").obj
    check(s"primaryColor is lost:\n$printed", vars.get("primaryColor").exists(_.str == "#ff0000"))
    check(s"fontSize is lost:\n$printed", vars.get("fontSize").exists(_.str == "12px"))
    check(s"background:\n$printed", vars.get("background").exists(_.str == "#fafafa"))
    check(s"the keys changed order:\n$printed", config.value.keys.toList == List("flowchart", "theme", "themeVariables"))

  test("a reset of a property removes its key, and keeps the other keys"):
    val source =
      """%%{init: {"theme": "forest", "themeVariables": {"primaryColor": "#ff0000", "fontSize": "12px"}}}%%
        |""".stripMargin + body
    val printed = print(withAttrs(parse(source))(_ - MermaidThemeAttr - MermaidFontSizeAttr))
    val config  = configOf(printed)
    check(s"theme is still there:\n$printed", !config.value.contains("theme"))
    val vars = config("themeVariables").obj
    check(s"fontSize is still there:\n$printed", !vars.contains("fontSize"))
    check(s"primaryColor is lost:\n$printed", vars.get("primaryColor").exists(_.str == "#ff0000"))

  test("the printer writes no directive when the graph has no global property"):
    val plain = print(parse(body))
    check(s"a plain flowchart got a directive:\n$plain", !plain.contains("%%{"))
    val source  = """%%{init: {"theme": "dark", "themeVariables": {"fontSize": "20px"}}}%%""" + "\n" + body
    val cleared = print(withAttrs(parse(source))(_ - MermaidThemeAttr - MermaidFontSizeAttr))
    check(s"an empty directive is still there:\n$cleared", !cleared.contains("%%{"))

  test("a new property on a plain flowchart makes a directive"):
    val printed = print(withAttrs(parse(body))(_.updated(MermaidFontSizeAttr, AttrValue("20"))))
    check(s"no directive:\n$printed", printed.startsWith("""%%{init: {"themeVariables": {"fontSize": "20px"}}}%%""" + "\n"))

  test("the directive comes after the front matter, and the title stays"):
    val source  = "---\ntitle: Plan\n---\n" + full
    val graph   = parse(source)
    check(s"title: ${attrsOf(graph)}", attrsOf(graph).get(Label.attrId).contains("Plan"))
    val printed = print(graph)
    check(s"the front matter is not first:\n$printed", printed.startsWith("---\ntitle: Plan\n---\n%%{init: "))
    check(s"the theme is lost:\n$printed", attrsOf(parse(printed)).get(MermaidThemeAttr).contains("dark"))

  test("the parser reads the forms that Mermaid reads: initialize, single quotes, and more than one line"):
    val forms = List(
      """%%{initialize: {"theme": "dark"}}%%""",
      """%%{init: {'theme': 'dark'}}%%""",
      "%%{\n  init: {\n    \"theme\": \"dark\"\n  }\n}%%"
    )
    for form <- forms do
      val attrs = attrsOf(parse(form + "\n" + body))
      check(s"theme of $form: $attrs", attrs.get(MermaidThemeAttr).contains("dark"))

  test("the parser merges two init directives, as Mermaid does"):
    val source =
      """%%{init: {"theme": "dark", "themeVariables": {"fontSize": "20px"}}}%%
        |%%{init: {"themeVariables": {"fontFamily": "Verdana"}}}%%
        |""".stripMargin + body
    val attrs = attrsOf(parse(source))
    check(s"theme: $attrs", attrs.get(MermaidThemeAttr).contains("dark"))
    check(s"font size: $attrs", attrs.get(MermaidFontSizeAttr).contains("20"))
    check(s"font family: $attrs", attrs.get(MermaidFontFamilyAttr).contains("Verdana"))

  test("a font size that is not in px stays as it is"):
    val source  = """%%{init: {"themeVariables": {"fontSize": "1.2em"}}}%%""" + "\n" + body
    val graph   = parse(source)
    check(s"font size: ${attrsOf(graph)}", attrsOf(graph).get(MermaidFontSizeAttr).contains("1.2em"))
    check(s"font size:\n${print(graph)}", configOf(print(graph))("themeVariables")("fontSize").str == "1.2em")

  test("a directive that is not JSON gives no property and no error"):
    val source = """%%{init: {theme: dark}}%%""" + "\n" + body
    val attrs  = attrsOf(parse(source))
    check(s"properties from bad JSON: $attrs", !attrs.contains(MermaidThemeAttr))

  test("the state-diagram printer writes the directive too"):
    val diagram = MermaidStateGraph
      .fromLayout(nodes = Seq(MermaidStateGraph.LayoutNode("A", "A"), MermaidStateGraph.LayoutNode("B", "B")), edges = Seq(MermaidStateGraph.LayoutEdge("edge0", "A", "B")))
      .copy(initConfig = Some("""{"theme": "forest"}"""))
    val graph   = stateDiagramToViewerGraph(diagram)
    check(s"theme: ${attrsOf(graph)}", attrsOf(graph).get(MermaidThemeAttr).contains("forest"))
    val printed = print(withAttrs(graph)(_.updated(MermaidFontFamilyAttr, AttrValue("Verdana"))))
    check(s"no directive:\n$printed", printed.startsWith("""%%{init: {"theme": "forest", "themeVariables": {"fontFamily": "Verdana"}}}%%""" + "\n" + StateDiagramHeader))

  test("the background of a text is the background variable of its directive"):
    check("background", MermaidInitDirective.backgroundOf(full).contains("#eeeeee"))
    check("no background", MermaidInitDirective.backgroundOf(body).isEmpty)
