package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The "Paste diagram" command: the clipboard replaces the document, and the
  * language selector follows the pasted text rather than staying on whatever
  * the previous diagram was written in.
  */
class ImportOpsSpec extends FunSuite with TestHelpers:

  import com.raquo.laminar.api.L.unsafeWindowOwner
  given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

  override def munitFixtures = List(mockStorageFixture())

  private def stateWithClipboard(
      id:            String,
      clipboard:     => Future[String],
      graphviz:      Graphviz,
      initialSource: Option[String] = None
  ) =
    ViewerState(ProjectId(id), graphviz, readText = () => clipboard, initialSource = initialSource)

  test("pasting Mermaid over a DOT diagram switches the selected format"):
    withGraphvizAsync { graphviz =>
      val mermaid = "flowchart TD\n  A --> B"
      val state   = stateWithClipboard("paste-mermaid", Future.successful(mermaid), graphviz)

      assertEquals(state.formatSelection.now(), DiagramFormat.DOT, "the default document is DOT")
      state.pasteDiagram()

      afterMicrotasks {
        assertEquals(state.sourceText.now(), mermaid)
        assertEquals(state.formatSelection.now(), DiagramFormat.Mermaid)
      }
    }

  test("a fenced paste is unwrapped before it reaches the editor"):
    withGraphvizAsync { graphviz =>
      val clipboard = "```dot\ndigraph G {\n  a -> b\n}\n```"
      val state     = stateWithClipboard("paste-fenced", Future.successful(clipboard), graphviz)

      state.pasteDiagram()

      afterMicrotasks {
        assertEquals(state.sourceText.now(), "digraph G {\n  a -> b\n}")
        assertEquals(state.formatSelection.now(), DiagramFormat.DOT)
      }
    }

  test("no observable state pairs the outgoing text with the incoming backend"):
    withGraphvizAsync { graphviz =>
      val mermaid = "flowchart TD\n  A --> B"
      val dot     = "digraph G {\n  a -> b\n}"
      // A document with CONTENT, deliberately: an empty one takes the format
      // observer's other branch (it re-serializes the empty graph into the new
      // language instead of reinterpreting the text), so it never exhibits this.
      val state =
        stateWithClipboard("replace-atomic", Future.successful(mermaid), graphviz, initialSource = Some(dot))

      // Every (text, language) pair the pipeline makes visible. Replacing the two
      // separately exposes one where they disagree, and everything downstream
      // runs against it: the parser rejects the text, and Mermaid's renderer —
      // which draws from the source rather than the graph — fails on it.
      var pairs = List.empty[(String, DiagramFormat)]
      state.sourceText.signal
        .combineWith(state.currentFormat)
        .foreach((text, format) => pairs = (text, format) :: pairs)

      for
        _ <- afterMicrotasks(assert(state.fullGraphNow().nodes.nonEmpty, "the initial DOT parse should have landed"))
        _ = state.pasteDiagram()
        _ <- afterMicrotasks {
               state.restoreSource(dot) // the undo direction, which disagrees the other way
               val disagreeing = pairs.filter((text, format) => DiagramFormat.declared(text).exists(_ != format))
               assert(disagreeing.isEmpty, s"text under the wrong backend: $disagreeing")
               assert(pairs.exists((text, _) => text == mermaid), s"the paste should be in the log: $pairs")
             }
      yield ()
    }

  test("an empty clipboard leaves the diagram alone"):
    withGraphvizAsync { graphviz =>
      val state  = stateWithClipboard("paste-empty", Future.successful("   \n  "), graphviz)
      val before = state.sourceText.now()

      var infos = List.empty[String]
      state.infoBus.events.foreach(msg => infos = msg :: infos)

      state.pasteDiagram()

      afterMicrotasks {
        assertEquals(state.sourceText.now(), before, "an empty clipboard must not erase the document")
        assertEquals(infos, List("Nothing to paste: the clipboard holds no text"))
      }
    }

  test("restoring the pre-paste document brings its language back with it"):
    withGraphvizAsync { graphviz =>
      val dot   = "digraph G {\n  a -> b\n}"
      val state = stateWithClipboard("paste-undo", Future.successful("flowchart TD\n  A --> B"), graphviz)
      state.pasteDiagram()

      afterMicrotasks {
        assertEquals(state.formatSelection.now(), DiagramFormat.Mermaid)
        // What CodeMirror's undo hands back: the previous document, whole.
        state.restoreSource(dot)
        assertEquals(state.sourceText.now(), dot)
        assertEquals(state.formatSelection.now(), DiagramFormat.DOT, "the selector has to travel with the text")
      }
    }

  test("restoring text that declares no language leaves the selector alone"):
    withGraphvizAsync { graphviz =>
      val state = stateWithClipboard("restore-undeclared", Future.successful(""), graphviz)
      state.setDiagramFormat(DiagramFormat.Mermaid)
      // Undo must not reinterpret a language the user chose by hand: this text
      // declares neither, and `detect` would answer DOT anyway.
      state.restoreSource("just some notes")

      afterMicrotasks {
        assertEquals(state.formatSelection.now(), DiagramFormat.Mermaid)
      }
    }

  test("a denied clipboard is reported, not mistaken for an empty one"):
    withGraphvizAsync { graphviz =>
      val state =
        stateWithClipboard("paste-denied", Future.failed(new RuntimeException("Read permission denied.")), graphviz)
      val before = state.sourceText.now()

      var errors = List.empty[String]
      state.errorBus.events.foreach(msg => errors = msg :: errors)

      state.pasteDiagram()

      afterMicrotasks {
        assertEquals(state.sourceText.now(), before)
        assertEquals(errors, List("Could not read the clipboard: Read permission denied."))
      }
    }
