package org.jpablo.graphexplorer.viewer.backends

import munit.FunSuite

/** What the "Paste diagram" command accepts. The interesting cases are all
  * about text that did NOT come from a .dot or .mmd file: a chat answer, a
  * README, an issue comment.
  */
class PastedDiagramSpec extends FunSuite:
  import PastedDiagramSpec.*

  test("bare DOT and bare Mermaid keep their text verbatim"):
    val dot = "digraph G {\n  a -> b\n}"
    assertEquals(PastedDiagram.from(dot), Some(PastedDiagram(dot, DiagramFormat.DOT, declared = true)))
    val mermaid = "flowchart TD\n  A --> B"
    assertEquals(PastedDiagram.from(mermaid), Some(PastedDiagram(mermaid, DiagramFormat.Mermaid, declared = true)))

  test("a fenced block is unwrapped, and its info string names the language"):
    val pasted = PastedDiagram.from("```mermaid\nflowchart TD\n  A --> B\n```")
    assertEquals(pasted, Some(PastedDiagram("flowchart TD\n  A --> B", DiagramFormat.Mermaid, declared = true)))

  test("prose around the fence is dropped"):
    val text =
      """Sure! Here is the diagram you asked for:
        |
        |```dot
        |digraph G {
        |  a -> b
        |}
        |```
        |
        |Let me know if you want it laid out left-to-right.""".stripMargin
    assertEquals(PastedDiagram.from(text), Some(PastedDiagram("digraph G {\n  a -> b\n}", DiagramFormat.DOT, declared = true)))

  test("an unlabelled fence still unwraps; the body decides the format"):
    // The case detection alone gets wrong: a line of backticks is neither DOT
    // nor Mermaid, so an un-stripped fence falls through to the DOT parser.
    assertEquals(
      PastedDiagram.from("```\nsequenceDiagram\n  A->>B: hi\n```"),
      Some(PastedDiagram("sequenceDiagram\n  A->>B: hi", DiagramFormat.Mermaid, declared = true))
    )

  test("an info string we don't speak strips the fence but does not claim a format"):
    assertEquals(
      PastedDiagram.from("```text\ndigraph G { a -> b }\n```"),
      Some(PastedDiagram("digraph G { a -> b }", DiagramFormat.DOT, declared = true))
    )

  test("a truncated paste opens what arrived rather than failing"):
    assertEquals(
      PastedDiagram.from("```mermaid\nflowchart TD\n  A --> B"),
      Some(PastedDiagram("flowchart TD\n  A --> B", DiagramFormat.Mermaid, declared = true))
    )

  test("tildes fence too, and a longer closing marker still closes"):
    assertEquals(
      PastedDiagram.from("~~~mermaid\nflowchart TD\n  A --> B\n~~~~\ntrailing"),
      Some(PastedDiagram("flowchart TD\n  A --> B", DiagramFormat.Mermaid, declared = true))
    )

  test("backticks INSIDE a line are not a fence"):
    val dot = """digraph G { a [label="`x`"] }"""
    assertEquals(PastedDiagram.from(dot), Some(PastedDiagram(dot, DiagramFormat.DOT, declared = true)))

  test("CRLF is normalized — otherwise every label carries a CR"):
    assertEquals(
      PastedDiagram.from("digraph G {\r\n  a -> b\r\n}"),
      Some(PastedDiagram("digraph G {\n  a -> b\n}", DiagramFormat.DOT, declared = true))
    )

  test("nothing to paste: blank clipboard, and a fence with a blank body"):
    // Both must be None, not an empty document — this is what stops an empty
    // clipboard from erasing the diagram on screen.
    assertEquals(PastedDiagram.from(""), None)
    assertEquals(PastedDiagram.from("   \n\t\n"), None)
    assertEquals(PastedDiagram.from("```mermaid\n\n```"), None)

  test("Mermaid frontmatter survives being pasted"):
    val text = "---\ntitle: Animals\n---\nflowchart LR\n  a --> b"
    assertEquals(PastedDiagram.from(text), Some(PastedDiagram(text, DiagramFormat.Mermaid, declared = true)))

  test("text that declares nothing is still pasteable, but marked as a fallback"):
    // The explicit command takes it; the ⌘V gesture does not. Without the flag
    // those two could not disagree — and copying a selection as SVG (bound to
    // `c`, one key from ⌘V) would replace the diagram with an SVG blob.
    val svg = """<svg width="100"><g id="graph0"><title>G</title></g></svg>"""
    assertEquals(PastedDiagram.from(svg), Some(PastedDiagram(svg, DiagramFormat.DOT, declared = false)))
    assertEquals(PastedDiagram.from("a -> b"), Some(PastedDiagram("a -> b", DiagramFormat.DOT, declared = false)))

  test("`graph LR { … }` is an undirected DOT graph named LR, not a flowchart"):
    // The one point where the two vocabularies collide, and Mermaid is asked
    // first. DOT names its graph and opens a brace; Mermaid's `graph <dir>`
    // header simply ends there.
    assertEquals(DiagramFormat.declared("graph LR { a -- b }"), Some(DiagramFormat.DOT))
    assertEquals(DiagramFormat.declared("graph TD\n{\n  a -- b\n}"), Some(DiagramFormat.DOT), "brace on the next line")
    assertEquals(DiagramFormat.declared("graph LRX { a -- b }"), Some(DiagramFormat.DOT), "a name the direction prefixes")
    // …and a flowchart is still a flowchart, including the one-line `;` form
    // whose statements may themselves contain braces.
    assertEquals(DiagramFormat.declared("graph LR\n  A --> B"), Some(DiagramFormat.Mermaid))
    assertEquals(DiagramFormat.declared("graph TD\n  A --> B"), Some(DiagramFormat.Mermaid))
    assertEquals(DiagramFormat.declared("graph LR; A{Decision} --> B"), Some(DiagramFormat.Mermaid))

  /** The text in Scala literal form, so a line break or a tab shows in a failure message. */
  private def literal(text: String): String =
    "\"" + text.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\""

  private def readsAsDot(text: String): Boolean =
    org.jpablo.graphexplorer.graphviz.Graphviz.structureJson(text).isRight

  /** The language that each entry point gives for a text. */
  final case class Languages(text: String, declared: Option[DiagramFormat], detect: DiagramFormat, pasted: Option[DiagramFormat]):
    def allAre(format: DiagramFormat): Boolean = declared.contains(format) && detect == format && pasted.contains(format)

  private def languagesOf(text: String): Languages =
    Languages(literal(text), DiagramFormat.declared(text), DiagramFormat.detect(text), PastedDiagram.from(text).map(_.format))

  test("a `graph` header with no direction, or with a direction other than LR RL TB BT TD, is a Mermaid flowchart"):
    // Setup: the DOT reader rejects each text, thus no text is a DOT graph.
    FlowchartsWithBareOrOtherDirection.foreach(t => assert(!readsAsDot(t), s"setup: the DOT reader accepts ${literal(t)}"))
    val notMermaid = FlowchartsWithBareOrOtherDirection.map(languagesOf).filterNot(_.allAre(DiagramFormat.Mermaid))
    assertEquals(notMermaid, Vector.empty, s"${notMermaid.size} of ${FlowchartsWithBareOrOtherDirection.size} flowcharts are not Mermaid")

  test("a DOT graph that starts with `graph` stays DOT"):
    // A guard for the fix of the bare `graph` header. It passes before the fix.
    DotGraphsThatStartWithGraph.foreach(t => assert(readsAsDot(t), s"setup: the DOT reader rejects ${literal(t)}"))
    val notDot = DotGraphsThatStartWithGraph.map(languagesOf).filterNot(_.allAre(DiagramFormat.DOT))
    assertEquals(notDot, Vector.empty, s"${notDot.size} of ${DotGraphsThatStartWithGraph.size} DOT graphs are not DOT")

  test("a `graph` header with no body is no evidence of Mermaid"):
    // A person who types `graph G {` has the text `graph` for a moment. Auto-detect
    // follows `declared`, thus these texts must give DOT, as before the fix of the
    // bare `graph` header. A name alone on the next line is no evidence either.
    val texts    = Vector("graph", "graph ", "graph\n", "graph\n\n", "graph >", "graph v", "graph BR", "graph\nG", "graph\n\"my graph\"")
    val obtained = texts.map(t => literal(t) -> DiagramFormat.declared(t))
    assertEquals(obtained, texts.map(t => literal(t) -> Some(DiagramFormat.DOT)))

  test("no part of a DOT graph that starts with `graph` declares Mermaid while a person types it"):
    // Each start of each text, from one character to the full text.
    val mermaidPrefixes = for
      text   <- DotGraphsThatStartWithGraph
      length <- 1 to text.length
      prefix  = text.take(length)
      if DiagramFormat.declared(prefix).contains(DiagramFormat.Mermaid)
    yield literal(prefix)
    assertEquals(mermaidPrefixes, Vector.empty)

  test("frontmatter is not a declaration until a header follows it"):
    // `---` used to be evidence of Mermaid on its own, which made every
    // Markdown document with YAML frontmatter a Mermaid diagram.
    assertEquals(DiagramFormat.declared("---\ntitle: notes\n---\njust prose"), None)
    assertEquals(DiagramFormat.declared("---\ntitle: notes\n"), None, "unterminated frontmatter")
    assertEquals(
      DiagramFormat.declared("---\ntitle: Animals\n---\nflowchart LR\n  a --> b"),
      Some(DiagramFormat.Mermaid)
    )

  test("a directive settles it even when the header keyword is one we don't know"):
    assertEquals(DiagramFormat.declared("%%{init: {\"theme\": \"dark\"}}%%\nbrandnewdiagram\n  a"), Some(DiagramFormat.Mermaid))

  test("`declared` withholds the DOT fallback that `detect` has to make"):
    // The pair that lets undo move the selector only on evidence.
    assertEquals(DiagramFormat.declared("digraph G { a -> b }"), Some(DiagramFormat.DOT))
    assertEquals(DiagramFormat.declared("flowchart TD\n  A --> B"), Some(DiagramFormat.Mermaid))
    assertEquals(DiagramFormat.declared("who knows what this is"), None)
    assertEquals(DiagramFormat.detect("who knows what this is"), DiagramFormat.DOT)

object PastedDiagramSpec:

  // Real Mermaid 11.12 reads each text below as a flowchart. BareGraphHeaderSpec
  // (viewer) checks this with real Mermaid. The Mermaid lexer gives `graph` the
  // directions LR, RL, TB, BT, TD, BR, >, <, ^ and v. A `graph` line with no
  // direction is a flowchart from top to bottom.
  val FlowchartsWithBareOrOtherDirection = Vector(
    "graph\n  A --> B",
    "graph\nA --> B",
    "graph \n  A --> B",
    "graph\r\n  A --> B",
    "graph\n  A{Decision} --> B",
    "---\ntitle: Plan\n---\ngraph\n  A --> B",
    "graph >\n  A --> B",
    "graph <\n  A --> B",
    "graph ^\n  A --> B",
    "graph v\n  A --> B",
    "graph>\n  A --> B",
    "graph BR\n  A --> B",
    "graph\tTD\n  A --> B"
  )

  // Each text below is a DOT graph. Real Mermaid rejects each one.
  val DotGraphsThatStartWithGraph = Vector(
    "graph {\n  a -- b\n}",
    "graph{ a -- b }",
    "graph G {\n  a -- b\n}",
    "graph\n{\n  a -- b\n}",
    "graph\nG {\n  a -- b\n}",
    "graph\n\"my graph\" {\n  a -- b\n}",
    "strict graph {\n  a -- b\n}",
    "strict graph\n{\n  a -- b\n}",
    "graph v { a -- b }",
    "graph BR { a -- b }"
  )
