package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.state.{DiagramPersistence, SaveResult, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global

/** Phase 2 item 4: the store follows the target (§6).
  *
  * The property under test is the one §6 states: a file cannot persist
  * through a `DiagramId`. It is asserted by measuring the reported behaviour —
  * what reaches `localStorage` — and not by reading which class was built.
  */
class DiagramPersistenceSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit = DesktopDocumentRegistry.reset()
  override def afterEach(context: AfterEach): Unit   = DesktopDocumentRegistry.reset()

  private def storedDiagramKeys: List[String] =
    (0 until dom.window.localStorage.length).toList
      .flatMap(i => Option(dom.window.localStorage.key(i)))
      .filter(_.contains("graph-explorer.project."))

  test("a file writes no diagram to localStorage, and a diagram of the browser library does") {
    withGraphvizAsync { graphviz =>
      val open = DesktopDocumentRegistry.record("/tmp/loose.dot", "rev-1", "digraph G { a -> b }")

      val loose = ViewerState(ViewTarget.File(open.id), graphviz)

      afterMicrotasks {
        loose.metadata.name.set("renamed while looking at a file")
        loose.addNodeWithSmartConnection()

        assertEquals(
          storedDiagramKeys,
          Nil,
          "a file must not stamp a diagram — §6 exists to make this impossible"
        )

        // The same edits through a library target DO reach storage, so the
        // assertion above measures the target and not a broken test.
        val library = ViewerState(ViewTarget.diagram("a-real-record"), graphviz)
        library.metadata.name.set("a record")
        library.addNodeWithSmartConnection()

        assertEquals(storedDiagramKeys.size, 1)
      }
    }
  }

  test("a viewer of a file opens on the file the registry holds") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/opened.dot", "rev-1", "digraph G { x -> y }")
      val state = ViewerState(ViewTarget.File(open.id), graphviz)

      val title = state.displayTitle.observe(using state.owner)
      afterMicrotasks {
        assertEquals(state.sourceText.now(), "digraph G { x -> y }")
        // §13: the file name is the title. The path is not. CONTEXT.md: the
        // name of a diagram is its file name with no extension, when its
        // source declares no title.
        assertEquals(title.now(), "opened")
      }
    }
  }

  test("an example reports that it has nowhere to save") {
    val example = DiagramPersistence.forTarget(ViewTarget.Example("logo", "Logo"), Some("digraph G { a }"))

    assertEquals(example.initial.metadata.name, "Logo")
    example.saveNow(example.initial).map: result =>
      assert(
        result match
          case SaveResult.Unsupported(_) => true
          case _                         => false,
        s"an example must say it cannot be saved, got $result"
      )
      assertEquals(storedDiagramKeys, Nil)
  }

  test("a save of a file refuses when its session is gone") {
    val open        = DesktopDocumentRegistry.record("/tmp/vanishing.dot", "rev-1", "digraph G { a }")
    val persistence = FilePersistence(open.id, None)
    val state       = persistence.initial

    DesktopDocumentRegistry.forget(open.id)

    persistence.saveNow(state).map: result =>
      assert(
        result match
          case SaveResult.Failed(_) => true
          case _                    => false,
        s"a save with no session must fail rather than write somewhere else, got $result"
      )
  }

  test("a file keeps its edits in memory until a save") {
    // §7.1: a keystroke does not write a file. The edit is held, so the save
    // that follows has the current text and not the text the shell sent.
    val open        = DesktopDocumentRegistry.record("/tmp/typing.dot", "rev-1", "digraph G { a }")
    val persistence = FilePersistence(open.id, None)

    persistence.update(persistence.initial.copy(source = "digraph G { a -> b }"))

    assertEquals(persistence.latest.source, "digraph G { a -> b }")
    assertEquals(
      DesktopDocumentRegistry.get(open.id).map(_.sourceText),
      Some("digraph G { a }"),
      "an edit must not reach the registry before a save"
    )
  }
