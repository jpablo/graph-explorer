package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.state.DocumentSessionId

/** Phase 2 items 1 and 3: the destination a loose file needs.
  *
  * The route holds an id, so the registry must map that id back to the file,
  * and must return the SAME id for the same file. §4.2 is the reason: a second
  * `gx open` of one path must reach the session that is already open.
  */
class DesktopDocumentRegistrySpec extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = DesktopDocumentRegistry.reset()
  override def afterEach(context:  AfterEach): Unit  = DesktopDocumentRegistry.reset()

  test("a recorded file gets a session that maps back to its path") {
    val session = DesktopDocumentRegistry.record("/tmp/a.dot", "rev-1", "digraph G { a }")

    assertEquals(DesktopDocumentRegistry.get(session.id).map(_.path), Some("/tmp/a.dot"))
    assertEquals(DesktopDocumentRegistry.get(session.id).map(_.revision), Some("rev-1"))
    assertEquals(DesktopDocumentRegistry.get(session.id).map(_.sourceText), Some("digraph G { a }"))
  }

  test("a second record of one path keeps the first id") {
    // §4.2: display repeats, the session does not. A new id here would give the
    // same file two routes, and the back button would walk through dead ones.
    val first  = DesktopDocumentRegistry.record("/tmp/a.dot", "rev-1", "one")
    val second = DesktopDocumentRegistry.record("/tmp/a.dot", "rev-2", "two")

    assertEquals(first.id, second.id)
    assertEquals(DesktopDocumentRegistry.all.size, 1)
  }

  test("a second record of one path refreshes the revision and the text") {
    val first = DesktopDocumentRegistry.record("/tmp/a.dot", "rev-1", "one")
    DesktopDocumentRegistry.record("/tmp/a.dot", "rev-2", "two")

    val current = DesktopDocumentRegistry.get(first.id)
    assertEquals(current.map(_.revision), Some("rev-2"))
    assertEquals(current.map(_.sourceText), Some("two"))
  }

  test("two paths get two sessions") {
    val a = DesktopDocumentRegistry.record("/tmp/a.dot", "rev-1", "one")
    val b = DesktopDocumentRegistry.record("/tmp/b.dot", "rev-1", "two")

    assertNotEquals(a.id, b.id)
    assertEquals(DesktopDocumentRegistry.all.size, 2)
  }

  test("find locates a session by its path") {
    val session = DesktopDocumentRegistry.record("/tmp/a.dot", "rev-1", "one")

    assertEquals(DesktopDocumentRegistry.find("/tmp/a.dot").map(_.id), Some(session.id))
    assertEquals(DesktopDocumentRegistry.find("/tmp/other.dot"), None)
  }

  test("forget releases a session") {
    val session = DesktopDocumentRegistry.record("/tmp/a.dot", "rev-1", "one")
    DesktopDocumentRegistry.forget(session.id)

    assertEquals(DesktopDocumentRegistry.get(session.id), None)
    assertEquals(DesktopDocumentRegistry.find("/tmp/a.dot"), None)
  }

  test("a session id does not contain the path") {
    // §13: the id travels in the URL. A person who reads the URL must not learn
    // the file. This is why the id is random and not a hash or an encoding.
    val session = DesktopDocumentRegistry.record("/Users/someone/secret/plan.dot", "rev-1", "one")

    assert(!session.id.value.contains("secret"))
    assert(!session.id.value.contains("plan"))
    assert(!session.id.value.contains("/"))
  }

  test("the base name is the display name, on either separator") {
    val posix   = DesktopDocumentRegistry.record("/Users/someone/plan.dot", "rev-1", "one")
    val windows = DesktopDocumentRegistry.record("C:\\Users\\someone\\plan.dot", "rev-1", "one")

    assertEquals(posix.baseName, "plan.dot")
    assertEquals(windows.baseName, "plan.dot")
  }

  test("an id is recognised, and other text is not") {
    val session = DesktopDocumentRegistry.record("/tmp/a.dot", "rev-1", "one")

    assertEquals(DocumentSessionId.parse(session.id.value), Some(session.id))
    // A `ProjectId` value reaches this parse if a route confuses the two. The
    // prefix is what makes that confusion fail here instead of later.
    assertEquals(DocumentSessionId.parse("7a1f9c22-0b3e-4a11-9f0d-2c6b8e4d1a55"), None)
    assertEquals(DocumentSessionId.parse(""), None)
    assertEquals(DocumentSessionId.parse("doc-"), None)
  }
