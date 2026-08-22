package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.state.{DiagramPersistence, SaveResult, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global

/** Phase 2 item 4: the store follows the target (§6).
  *
  * The property under test is the one §6 states: a loose file cannot persist
  * through a `ProjectId`. It is asserted by measuring the reported behaviour —
  * what reaches `localStorage` — and not by reading which class was built.
  */
class DiagramPersistenceSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit = DesktopDocumentRegistry.reset()
  override def afterEach(context: AfterEach): Unit   = DesktopDocumentRegistry.reset()

  private def storedProjectKeys: List[String] =
    (0 until dom.window.localStorage.length).toList
      .flatMap(i => Option(dom.window.localStorage.key(i)))
      .filter(_.contains("graph-explorer.project."))

  test("a loose file writes no project to localStorage, and a library record does") {
    withGraphvizAsync { graphviz =>
      val open = DesktopDocumentRegistry.record("/tmp/loose.dot", "rev-1", "digraph G { a -> b }")

      val loose = ViewerState(ViewTarget.LooseFile(open.id), graphviz)

      afterMicrotasks {
        loose.project.name.set("renamed while looking at a file")
        loose.addNodeWithSmartConnection()

        assertEquals(
          storedProjectKeys,
          Nil,
          "a loose file must not stamp a library record — §6 exists to make this impossible"
        )

        // The same edits through a library target DO reach storage, so the
        // assertion above measures the target and not a broken test.
        val library = ViewerState(ViewTarget.library("a-real-record"), graphviz)
        library.project.name.set("a record")
        library.addNodeWithSmartConnection()

        assertEquals(storedProjectKeys.size, 1)
      }
    }
  }

  test("a loose viewer opens on the file the registry holds") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/opened.dot", "rev-1", "digraph G { x -> y }")
      val state = ViewerState(ViewTarget.LooseFile(open.id), graphviz)

      afterMicrotasks {
        assertEquals(state.sourceText.now(), "digraph G { x -> y }")
        // §13: the base name is the title. The path is not.
        assertEquals(state.project.name.now(), "opened.dot")
      }
    }
  }

  test("an example reports that it has nowhere to save") {
    val example = DiagramPersistence.forTarget(ViewTarget.Example("logo", "Logo"), Some("digraph G { a }"))

    assertEquals(example.initial.projectName, "Logo")
    example.saveNow(example.initial).map: result =>
      assert(
        result match
          case SaveResult.Unsupported(_) => true
          case _                         => false,
        s"an example must say it cannot be saved, got $result"
      )
      assertEquals(storedProjectKeys, Nil)
  }

  test("a loose save refuses when its session is gone") {
    val open        = DesktopDocumentRegistry.record("/tmp/vanishing.dot", "rev-1", "digraph G { a }")
    val persistence = LooseFilePersistence(open.id)
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

  test("a loose file keeps its edits in memory until a save") {
    // §7.1: a keystroke does not write a file. The edit is held, so the save
    // that follows has the current text and not the text the shell sent.
    val open        = DesktopDocumentRegistry.record("/tmp/typing.dot", "rev-1", "digraph G { a }")
    val persistence = LooseFilePersistence(open.id)

    persistence.update(persistence.initial.copy(source = "digraph G { a -> b }"))

    assertEquals(persistence.latest.source, "digraph G { a -> b }")
    assertEquals(
      DesktopDocumentRegistry.get(open.id).map(_.sourceText),
      Some("digraph G { a }"),
      "an edit must not reach the registry before a save"
    )
  }
