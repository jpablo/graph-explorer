package org.jpablo.graphexplorer.viewer.backends

import munit.FunSuite

/** What the "Paste diagram" command accepts. The interesting cases are all
  * about text that did NOT come from a .dot or .mmd file: a chat answer, a
  * README, an issue comment.
  */
class PastedDiagramSpec extends FunSuite:

  test("bare DOT and bare Mermaid keep their text verbatim"):
    val dot = "digraph G {\n  a -> b\n}"
    assertEquals(PastedDiagram.from(dot), Some(PastedDiagram(dot, DiagramFormat.DOT)))
    val mermaid = "flowchart TD\n  A --> B"
    assertEquals(PastedDiagram.from(mermaid), Some(PastedDiagram(mermaid, DiagramFormat.Mermaid)))

  test("a fenced block is unwrapped, and its info string names the language"):
    val pasted = PastedDiagram.from("```mermaid\nflowchart TD\n  A --> B\n```")
    assertEquals(pasted, Some(PastedDiagram("flowchart TD\n  A --> B", DiagramFormat.Mermaid)))

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
    assertEquals(PastedDiagram.from(text), Some(PastedDiagram("digraph G {\n  a -> b\n}", DiagramFormat.DOT)))

  test("an unlabelled fence still unwraps; the body decides the format"):
    // The case detection alone gets wrong: a line of backticks is neither DOT
    // nor Mermaid, so an un-stripped fence falls through to the DOT parser.
    assertEquals(
      PastedDiagram.from("```\nsequenceDiagram\n  A->>B: hi\n```"),
      Some(PastedDiagram("sequenceDiagram\n  A->>B: hi", DiagramFormat.Mermaid))
    )

  test("an info string we don't speak strips the fence but does not claim a format"):
    assertEquals(
      PastedDiagram.from("```text\ndigraph G { a -> b }\n```"),
      Some(PastedDiagram("digraph G { a -> b }", DiagramFormat.DOT))
    )

  test("a truncated paste opens what arrived rather than failing"):
    assertEquals(
      PastedDiagram.from("```mermaid\nflowchart TD\n  A --> B"),
      Some(PastedDiagram("flowchart TD\n  A --> B", DiagramFormat.Mermaid))
    )

  test("tildes fence too, and a longer closing marker still closes"):
    assertEquals(
      PastedDiagram.from("~~~mermaid\nflowchart TD\n  A --> B\n~~~~\ntrailing"),
      Some(PastedDiagram("flowchart TD\n  A --> B", DiagramFormat.Mermaid))
    )

  test("backticks INSIDE a line are not a fence"):
    val dot = """digraph G { a [label="`x`"] }"""
    assertEquals(PastedDiagram.from(dot), Some(PastedDiagram(dot, DiagramFormat.DOT)))

  test("CRLF is normalized — otherwise every label carries a CR"):
    assertEquals(
      PastedDiagram.from("digraph G {\r\n  a -> b\r\n}"),
      Some(PastedDiagram("digraph G {\n  a -> b\n}", DiagramFormat.DOT))
    )

  test("nothing to paste: blank clipboard, and a fence with a blank body"):
    // Both must be None, not an empty document — this is what stops an empty
    // clipboard from erasing the diagram on screen.
    assertEquals(PastedDiagram.from(""), None)
    assertEquals(PastedDiagram.from("   \n\t\n"), None)
    assertEquals(PastedDiagram.from("```mermaid\n\n```"), None)

  test("Mermaid frontmatter survives being pasted"):
    val text = "---\ntitle: Animals\n---\nflowchart LR\n  a --> b"
    assertEquals(PastedDiagram.from(text), Some(PastedDiagram(text, DiagramFormat.Mermaid)))

  test("`declared` withholds the DOT fallback that `detect` has to make"):
    // The pair that lets undo move the selector only on evidence.
    assertEquals(DiagramFormat.declared("digraph G { a -> b }"), Some(DiagramFormat.DOT))
    assertEquals(DiagramFormat.declared("flowchart TD\n  A --> B"), Some(DiagramFormat.Mermaid))
    assertEquals(DiagramFormat.declared("who knows what this is"), None)
    assertEquals(DiagramFormat.detect("who knows what this is"), DiagramFormat.DOT)
