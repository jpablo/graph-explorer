package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The "Paste diagram" command: the clipboard replaces the document, and the
  * language selector follows the pasted text rather than staying on whatever
  * the previous diagram was written in.
  */
class ImportOpsSpec extends FunSuite with TestHelpers:

  import com.raquo.laminar.api.L.unsafeWindowOwner
  given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

  override def munitFixtures = List(mockStorageFixture())

  /** Polls until `condition` holds — for the steps that go through an async
    * parse, where a single microtask turn is not enough.
    */
  private def waitFor(condition: => Boolean, description: String, timeoutMs: Int = 5000): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        p.tryFailure(new RuntimeException(s"Timed out after ${timeoutMs}ms waiting for $description"))
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  private def stateWithClipboard(
      id:            String,
      clipboard:     => Future[String],
      graphviz:      Graphviz,
      initialSource: Option[String] = None
  ) =
    ViewerState(ViewTarget.library(id), graphviz, readText = () => clipboard, initialSource = initialSource)

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

  test("a format mismatch is reported as one, not as the backend's symptom"):
    withGraphvizAsync { graphviz =>
      val mermaid = "flowchart TD\n  A --> B"
      val state =
        stateWithClipboard("mismatch", Future.failed(ClipboardUnavailable), graphviz, initialSource = Some(mermaid))

      for
        _ <- afterMicrotasks(assertEquals(state.formatSelection.now(), DiagramFormat.Mermaid))
        // What the user does with the selector: assert DOT over a Mermaid document.
        _ = state.setDiagramFormat(DiagramFormat.DOT)
        _ <- waitFor(state.editorNotice.now().exists(_.isError), "the mismatch notice")
        _ <- afterMicrotasks {
               val notice = state.editorNotice.now().get
               // Not "key not found: dot_json", and not a syntax error at line 1.
               assertEquals(
                 notice.message,
                 "This looks like a Mermaid diagram, but DOT/Graphviz is selected."
               )
               assertEquals(notice.suggestedFormat, Some(DiagramFormat.Mermaid), "the notice carries its own remedy")
             }
      yield ()
    }

  test("auto-detect is on by default, and choosing a language by hand turns it off"):
    withGraphvizAsync { graphviz =>
      val dot   = "digraph G {\n  a -> b\n}"
      val state = stateWithClipboard("auto-default", Future.failed(ClipboardUnavailable), graphviz, initialSource = Some(dot))

      afterMicrotasks {
        assert(state.autoDetectFormat.now(), "a new document follows its own language")
        assertEquals(state.formatOption.selected.observe.now(), "Auto")

        // Asserting a language has to stop the following, or auto would sit
        // behind every manual choice waiting to overrule it — and the "wrong
        // language" notice could never be read.
        state.setDiagramFormat(DiagramFormat.Mermaid)
        assert(!state.autoDetectFormat.now())
        assertEquals(state.formatOption.selected.observe.now(), "Mermaid")

        // Picking Auto again hands it back.
        state.formatOption.select(state.formatOption.auto)
        assert(state.autoDetectFormat.now())
      }
    }

  test("auto-detect follows the document, and only on evidence"):
    withGraphvizAsync { graphviz =>
      val dot   = "digraph G {\n  a -> b\n}"
      val state = stateWithClipboard("auto", Future.failed(ClipboardUnavailable), graphviz, initialSource = Some(dot))

      for
        _ <- afterMicrotasks(assertEquals(state.formatSelection.now(), DiagramFormat.DOT))
        _ = state.autoDetectFormat.set(true)
        // Text that declares nothing leaves the language where it was — auto
        // detection may only ever act on evidence.
        _ = state.sourceText.set("some notes with no diagram in them")
        _ <- afterMicrotasks(assertEquals(state.formatSelection.now(), DiagramFormat.DOT))
        // A declared one moves it.
        _ = state.sourceText.set("flowchart TD\n  A --> B")
        _ <- afterMicrotasks {
               assertEquals(state.formatSelection.now(), DiagramFormat.Mermaid)
               assertEquals(state.sourceText.now(), "flowchart TD\n  A --> B", "the document itself is untouched")
             }
      yield ()
    }

  test("turning auto-detect on acts on the document already open"):
    withGraphvizAsync { graphviz =>
      val mermaid = "flowchart TD\n  A --> B"
      val state =
        stateWithClipboard("auto-on", Future.failed(ClipboardUnavailable), graphviz, initialSource = Some(mermaid))

      for
        _ <- afterMicrotasks(state.setDiagramFormat(DiagramFormat.DOT))
        _ = state.autoDetectFormat.set(true)
        _ <- afterMicrotasks {
               assertEquals(state.formatSelection.now(), DiagramFormat.Mermaid, "not waiting for the next keystroke")
               assertEquals(state.sourceText.now(), mermaid)
             }
      yield ()
    }

  test("the paste GESTURE acts only on text that declares a language"):
    withGraphvizAsync { graphviz =>
      val dot   = "digraph G {\n  a -> b\n}"
      val state = stateWithClipboard("paste-gesture", Future.failed(ClipboardUnavailable), graphviz)

      var infos = List.empty[String]
      state.infoBus.events.foreach(msg => infos = msg :: infos)

      // The hazard this strictness exists for: "Copy selection as SVG" is bound
      // to `c`, one key away from ⌘V on the same canvas. `detect` would call an
      // SVG blob DOT and replace the diagram with something that cannot parse.
      val svg   = """<svg width="100" height="100"><g id="graph0"><title>G</title></g></svg>"""
      val before = state.sourceText.now()
      state.pasteDiagramFromGesture(svg)

      afterMicrotasks {
        assertEquals(state.sourceText.now(), before, "an SVG blob must not replace the diagram")
        assertEquals(infos, List("The clipboard holds no DOT or Mermaid diagram"))

        // A real diagram still goes through, fence and all.
        state.pasteDiagramFromGesture(s"```dot\n$dot\n```")
        assertEquals(state.sourceText.now(), dot)
      }
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
