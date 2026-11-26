package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{AttributeId, ElementIds, GroupId, NodeId}

/** The `%%` comments of a flowchart and the `direction` of its subgraphs, through a print.
  *
  * A canvas edit prints the whole graph again. Before, the printer dropped the
  * comments and the `direction` lines of the subgraphs, so the viewer refused
  * each canvas edit on a file that had them. Now the graph keeps them: the
  * group keeps its direction in `rankdir`, and the graph keeps an outline of the
  * comments (see [[MermaidComments]]).
  */
class MermaidCommentsSpec extends FunSuite:

  // The sbt client shows a munit failure with no message. An AssertionError shows the clue.
  private def check(clue: => String, cond: Boolean): Unit = if !cond then throw AssertionError(clue)

  private def parse(text: String): ViewerGraph =
    toViewerGraph(MermaidFlowchartParser.parse(text).fold(e => throw AssertionError(s"could not parse:\n$text\n$e"), identity))

  private def print(graph: ViewerGraph): String = viewerGraphToMermaidText(graph)

  private def linesOf(text: String): Vector[String] = text.linesIterator.map(_.trim).filter(_.nonEmpty).toVector

  private def commentsOf(text: String): List[String] = linesOf(text).filter(_.startsWith("%%")).toList

  /** The lines of the subgraph `name`, from its `subgraph` line to its `end` line, with no nested subgraph. */
  private def block(text: String, name: String): Vector[String] =
    val lines = linesOf(text)
    val start = lines.indexWhere(_.startsWith(s"subgraph $name"))
    check(s"the text has no subgraph $name:\n$text", start >= 0)
    lines.drop(start + 1).takeWhile(_ != "end")

  private val source =
    """%% Browser tool architecture.
      |%% Main evidence:
      |%% - rust/genesis/src/tools/builtin/browser/
      |
      |flowchart LR
      |    user["User"]
      |    model["Model / provider"]
      |
      |    subgraph desktop["Desktop host"]
      |        direction TB
      |        %% the API layer
      |        api["API"]
      |        ui["UI"]
      |    end
      |
      |    subgraph native["Native process"]
      |        direction LR
      |        db["DB"]
      |        %% before the end
      |    end
      |
      |    %% the main path
      |    user --> api
      |    api --> db
      |    model --> ui
      |    %% the last line""".stripMargin

  private val header = List("%% Browser tool architecture.", "%% Main evidence:", "%% - rust/genesis/src/tools/builtin/browser/")

  // ------------------------------------------------------------ the parse

  test("the parse keeps the direction of each subgraph in the rankdir of its group"):
    val graph = parse(source)
    val dirs  = graph.groups.view.mapValues(_.attributes.values.get(Rankdir.attrId).map(_.toString)).toMap
    check(s"directions: $dirs", dirs.get(GroupId("desktop")).flatten.contains("TB"))
    check(s"directions: $dirs", dirs.get(GroupId("native")).flatten.contains("LR"))

  test("a text with no comment gives no outline"):
    check("an outline for a text with no comment", MermaidComments.outlineOf("flowchart LR\n  a --> b").isEmpty)
    val graph = parse("flowchart LR\n  a --> b")
    check("the graph has the comments attribute", !graph.elements.graphAttributes.values.contains(MermaidAttrKeys.MermaidCommentsAttr))

  test("the outline reads the statements after each comment"):
    import MermaidComments.Item.*
    val items = MermaidComments.scan("%% top\nflowchart LR\n  %% one\n  a & b --> c --> d\n  %% two\n  a --> c\n  subgraph s [S]\n    %% three\n  end")
    val expected = Vector(
      Comments(List("%% top")), Header, Comments(List("%% one")),
      Edge("a", "c", 1), Edge("b", "c", 1), Edge("c", "d", 1),
      Comments(List("%% two")), Edge("a", "c", 2),
      Subgraph("s"), Comments(List("%% three")), End("s")
    )
    check(s"items: $items", items == expected)

  test("the outline survives its JSON text, and bad JSON gives no item"):
    val json = MermaidComments.outlineOf(source).getOrElse(throw AssertionError("no outline"))
    check(s"the JSON lost items: $json", MermaidComments.parse(json) == MermaidComments.scan(source))
    check("bad JSON gave items", MermaidComments.parse("not json").isEmpty)

  // ------------------------------------------------------------ the print

  test("a print keeps the comments, in their order, and the comments at the top stay at the top"):
    val text = print(parse(source))
    check(s"the comments at the top changed:\n$text", linesOf(text).take(3).toList == header)
    val expected = header ++ List("%% the API layer", "%% before the end", "%% the main path", "%% the last line")
    check(s"the comments changed:\n$text", commentsOf(text) == expected)

  test("a print keeps the direction of each subgraph inside it, and a comment inside a subgraph stays inside it"):
    val text = print(parse(source))
    check(s"desktop lost its direction:\n$text", block(text, "desktop").headOption.contains("direction TB"))
    check(s"native lost its direction:\n$text", block(text, "native").headOption.contains("direction LR"))
    check(s"the comment left desktop:\n$text", block(text, "desktop").contains("%% the API layer"))
    check(s"the comment left native:\n$text", block(text, "native").lastOption.contains("%% before the end"))
    val desktop = block(text, "desktop")
    check(s"the comment is not before its node:\n$text", desktop.indexOf("%% the API layer") + 1 == desktop.indexWhere(_.startsWith("api")))

  test("a comment goes before the arrow after it, and the last comment stays at the end"):
    val text  = print(parse(source))
    val lines = linesOf(text)
    val at    = lines.indexOf("%% the main path")
    check(s"the comment is not before its arrow:\n$text", lines.lift(at + 1).contains("user --> api"))
    check(s"the last comment moved:\n$text", lines.lastOption.contains("%% the last line"))

  test("a second print does not change the text"):
    val once  = print(parse(source))
    val twice = print(parse(once))
    check(s"the second print changed the text:\n$once\n---\n$twice", once == twice)

  test("a comment before the init directive stays before it, and a comment after it stays after it"):
    val text    = "%% first\n%%{init: {\"theme\": \"dark\"}}%%\n%% second\nflowchart LR\n  a --> b"
    val printed = print(parse(text))
    check(s"the order changed:\n$printed", linesOf(printed).take(4).toList == List("%% first", "%%{init: {\"theme\": \"dark\"}}%%", "%% second", "flowchart LR"))

  // ------------------------------------------------------------- the edits

  test("an attribute edit keeps the comments and the directions"):
    val graph  = parse(source)
    val edited = graph.copy(elements = graph.elements.copy(nodes = graph.nodes.updatedWith(NodeId("api"))(_.map(n =>
      n.modifyAttrs.using(a => a.copy(values = a.values + (AttributeId("color") -> AttrValue("red"))))
    ))))
    val text = print(edited)
    check(s"the edit did not apply:\n$text", text.contains("red"))
    check(s"the comments changed:\n$text", commentsOf(text) == commentsOf(print(graph)))
    check(s"desktop lost its direction:\n$text", block(text, "desktop").contains("direction TB"))

  test("when an edit removes the anchor of a comment, the comment goes before the next statement of its scope"):
    val text    = print(parse(source).removeElements(ElementIds(Set(NodeId("api")))))
    val desktop = block(text, "desktop")
    check(s"the comment left desktop:\n$text", desktop.contains("%% the API layer"))
    check(s"the comment is not before the next node:\n$text", desktop.indexOf("%% the API layer") + 1 == desktop.indexWhere(_.startsWith("ui")))

  test("when an edit removes a subgraph, its comments go to the scope that contained it"):
    val text = print(parse(source).removeElements(ElementIds(Set(GroupId("native")))))
    check(s"the subgraph is still there:\n$text", !linesOf(text).exists(_.startsWith("subgraph native")))
    check(s"a comment was dropped:\n$text", commentsOf(text).contains("%% before the end"))
    check(s"the comments changed:\n$text", commentsOf(text).size == commentsOf(print(parse(source))).size)

  test("a node that moves to a new group keeps its comment, and the other subgraph keeps its direction"):
    val graph = parse(source).ungroupSelection(ElementIds(Set(NodeId("api")))).moveToNewGroup(ElementIds(Set(NodeId("api"))), "Moved")
    val text  = print(graph)
    val lines = linesOf(text)
    val at    = lines.indexOf("%% the API layer")
    check(s"the comment is not before its node:\n$text", at >= 0 && lines.lift(at + 1).exists(_.startsWith("api")))
    check(s"desktop lost its direction:\n$text", block(text, "desktop").contains("direction TB"))

  test("the printer writes no direction that Mermaid does not accept"):
    val graph   = parse("flowchart LR\n  subgraph s\n    a\n  end")
    val group   = graph.groups(GroupId("s"))
    val bad     = group.modifyAttrs.using(a => a.copy(values = a.values + (Rankdir.attrId -> AttrValue("sideways"))))
    val printed = print(graph.copy(elements = graph.elements.copy(groups = graph.groups.updated(GroupId("s"), bad))))
    check(s"the printer wrote the direction:\n$printed", !printed.contains("direction"))
