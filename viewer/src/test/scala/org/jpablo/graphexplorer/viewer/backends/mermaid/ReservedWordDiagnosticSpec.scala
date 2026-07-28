package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite

/** Mermaid's sequence lexer matches ~30 keywords case-insensitively and
  * whole-word, so none can be a participant id. Its own error names neither the
  * word nor the line that introduced it:
  *
  * {{{
  * Parse error on line 12: ...Caller->>Actor: create/load s
  * Expecting '+', '-', 'ACTOR', got 'participant_actor'
  * }}}
  *
  * — line 12 is the first message mentioning it; the declaration on line 4 parsed
  * fine. [[MermaidSourceScan.explainParseFailure]] adds the missing half.
  *
  * It runs only after mermaid has already rejected the text, so a false positive
  * costs nothing but noise — but a false NEGATIVE on a valid diagram would be a
  * real bug if it ever gated. The "says nothing" cases below pin that.
  */
class ReservedWordDiagnosticSpec extends FunSuite:

  private def explain(src: String) = MermaidSourceScan.explainParseFailure(src)

  // the diagram from the bug report, trimmed to the relevant lines
  private val reported =
    """sequenceDiagram
      |    autonumber
      |    participant Caller as Session create/load or reload
      |    participant Actor as AgentActor
      |    participant Runtime as prometheus::mcp_runtime
      |
      |    Caller->>Actor: create/load session or RefreshMcp
      |    Actor->>Runtime: discover_mcp_registry(working_directory)
      |""".stripMargin

  test("names the word AND the line that declared it, not the line mermaid blames"):
    val hint = explain(reported).getOrElse(fail("expected a diagnosis"))
    assert(hint.contains("'Actor'"), hint)
    assert(hint.contains("line 4"), s"should point at the DECLARATION, not mermaid's line 7: $hint")
    assert(hint.contains("participant Agent as Actor"), s"should show the fix: $hint")

  test("catches an undeclared participant introduced by a message"):
    // mermaid auto-creates participants, so the id can appear only in a message
    val src = "sequenceDiagram\n  Caller->>Note: hi\n"
    val hint = explain(src).getOrElse(fail("expected a diagnosis"))
    assert(hint.contains("'Note'"), hint)
    assert(hint.contains("line 2"), hint)

  test("sees through the +/- activation marks"):
    val src = "sequenceDiagram\n  Caller->>+End: hi\n"
    assert(explain(src).exists(_.contains("'End'")), explain(src).toString)

  test("matches whole words, ignoring case — like the lexer"):
    assert(explain("sequenceDiagram\n  participant ACTOR\n").isDefined, "ACTOR is the keyword")
    assert(explain("sequenceDiagram\n  participant actor\n").isDefined, "actor is the keyword")
    assertEquals(explain("sequenceDiagram\n  participant Actors\n"), None, "Actors is a fine id")
    assertEquals(explain("sequenceDiagram\n  participant MyActor\n"), None, "MyActor is a fine id")

  test("says nothing about a clean sequence diagram"):
    assertEquals(explain("sequenceDiagram\n  participant A\n  participant B\n  A->>B: hi\n"), None)

  test("says nothing about other diagram types — Actor is a fine flowchart node"):
    assertEquals(explain("flowchart TD\n  Actor --> B\n"), None)
    assertEquals(explain("classDiagram\n  class Actor\n"), None)

  test("`as` is the one keyword that IS usable as an id, and is not flagged"):
    // verified against mermaid 11.12: 29 of its 30 sequence keywords break as a
    // participant id, `as` being the sole survivor.
    assertEquals(explain("sequenceDiagram\n  participant A\n  A->>as: hi\n"), None)

  test("reports the FIRST offender when there are several"):
    val src = "sequenceDiagram\n  participant Caller\n  participant Loop\n  participant Note\n"
    val hint = explain(src).getOrElse(fail("expected a diagnosis"))
    assert(hint.contains("'Loop'"), s"should report line 3 first: $hint")

  test("the raw mermaid message is kept — the hint goes in front of it"):
    val composed = MermaidBackend.explain("parsing", "Error: Parse error on line 7", reported)
    assert(composed.contains("'Actor'"), composed)
    assert(composed.contains("Error: Parse error on line 7"), s"raw message must survive: $composed")

  test("composing over an unrecognised failure leaves the raw message alone"):
    val clean = "sequenceDiagram\n  participant A\n  A->>B: hi\n"
    assertEquals(MermaidBackend.explain("parsing", "boom", clean), "Mermaid parsing failed: boom")
