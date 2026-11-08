package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

/** A delete of a diagram must remove BOTH halves of its storage: the index entry
  * and the payload. Two bugs made this false, and each bug made the other worse:
  *
  *   - `deleteDiagram` cleared the payload through an unobserved laminext storedString.
  *     That write never reaches localStorage, so each delete left an orphan payload.
  *   - The empty-index guard in `updateIndex` read those orphans as "the library is
  *     not really empty", and it refused the write. Thus a person could not delete
  *     the LAST diagram. It came back into the library silently.
  */
class DeleteDiagramSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  private def payloadKey(id: DiagramId): String =
    s"[StoredString]graph-explorer.project.${id.value}"

  private def payloadOf(id: DiagramId): Option[String] =
    Option(dom.window.localStorage.getItem(payloadKey(id)))

  /** Give the diagram the payload that an open diagram writes. */
  private def writePayload(id: DiagramId): Unit =
    dom.window.localStorage.setItem(payloadKey(id), """{"projectName":"p","source":"digraph G {}"}""")

  /** Empty the library, so that the next new diagram is really the only one. */
  private def clearLibrary(): Unit =
    BrowserLibrary.indexNow().diagrams.foreach(d => BrowserLibrary.deleteDiagram(d.id))

  test("deleting the LAST diagram removes its index entry"):
    clearLibrary()
    val id = BrowserLibrary.createEmptyDiagram("only diagram")
    writePayload(id)
    assert(BrowserLibrary.diagramExists(id), "precondition: the diagram is in the index")

    BrowserLibrary.deleteDiagram(id)

    assert(
      !BrowserLibrary.diagramExists(id),
      "the last diagram must be deletable — the guard used to refuse this write, leaving it in the library forever"
    )
    assertEquals(BrowserLibrary.indexNow().diagrams, Nil, "the library is now legitimately empty")

  test("deleting a diagram removes its payload from storage"):
    clearLibrary()
    val id = BrowserLibrary.createEmptyDiagram("with payload")
    writePayload(id)
    assert(payloadOf(id).isDefined, "precondition: the payload is stored")

    BrowserLibrary.deleteDiagram(id)

    assertEquals(payloadOf(id), None, "the payload must go with the diagram, not linger as an orphan")

  test("deleting one of several diagrams leaves the others intact"):
    clearLibrary()
    val keep = BrowserLibrary.createEmptyDiagram("keep")
    val drop = BrowserLibrary.createEmptyDiagram("drop")
    writePayload(keep)
    writePayload(drop)

    BrowserLibrary.deleteDiagram(drop)

    assert(BrowserLibrary.diagramExists(keep), "the surviving diagram keeps its index entry")
    assert(payloadOf(keep).isDefined, "the surviving diagram keeps its payload")
    assert(!BrowserLibrary.diagramExists(drop))
    assertEquals(payloadOf(drop), None)

  test("an orphaned payload does not block deleting the last diagram"):
    clearLibrary()
    // A payload with no index entry. The old delete left exactly this, and the old
    // guard thought that it was a live library.
    dom.window.localStorage.setItem(payloadKey(DiagramId("orphan")), """{"projectName":"ghost","source":"digraph G {}"}""")

    val id = BrowserLibrary.createEmptyDiagram("last")
    writePayload(id)
    BrowserLibrary.deleteDiagram(id)

    assert(!BrowserLibrary.diagramExists(id), "an unrelated orphan must not veto a legitimate delete")
    assertEquals(BrowserLibrary.indexNow().diagrams, Nil)
