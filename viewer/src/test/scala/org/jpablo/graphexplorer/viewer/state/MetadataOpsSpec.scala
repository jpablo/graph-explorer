package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.DiagramMetadata
import org.jpablo.graphexplorer.viewer.models.{ArrowId, ElementIds, GroupId, NodeId}

/** The typed views of `MetadataOps` over ONE `Var[DiagramMetadata]`.
  *
  * A view parses the `ElementRef` strings, and its setter renders them again.
  * A setter must KEEP each string that the view does not show. A newer `gx` can
  * write a ref kind that this build does not know, and a setter that drops it
  * deletes it on the next edit.
  */
class MetadataOpsSpec extends FunSuite:

  private given Owner = unsafeWindowOwner

  private def opsOver(metadata: DiagramMetadata): MetadataOps = MetadataOps(Var(metadata))

  test("hiddenElements parses each ref kind"):
    val ops = opsOver(DiagramMetadata(hiddenElements = Set("node:a", "arrow:e1", "group:g")))
    assertEquals(ops.hiddenElements.now(), ElementIds(Set(NodeId("a"), ArrowId("e1"), GroupId("g"))))

  test("hiddenElements renders in the ElementRef spelling"):
    val ops = opsOver(DiagramMetadata())
    ops.hiddenElements.set(ElementIds(Set(NodeId("n1"), GroupId("n1"))))
    assertEquals(ops.now().hiddenElements, Set("node:n1", "group:n1"))

  test("hiddenElements keeps a ref that it cannot parse, through an add and a clear"):
    val ops = opsOver(DiagramMetadata(hiddenElements = Set("node:a", "future:x", "node:")))
    assertEquals(ops.hiddenElements.now(), ElementIds(Set(NodeId("a"))), "the view shows only what it parses")
    ops.hiddenElements.update(_ + NodeId("b"))
    assertEquals(ops.now().hiddenElements, Set("node:a", "node:b", "future:x", "node:"))
    ops.hiddenElements.set(ElementIds())
    assertEquals(ops.now().hiddenElements, Set("future:x", "node:"), "a clear removes only what the view shows")

  test("collapsedGroups shows only groups"):
    val ops = opsOver(DiagramMetadata(collapsedGroups = Set("group:g1", "node:a", "future:x")))
    assertEquals(ops.collapsedGroups.now(), Set(GroupId("g1")))

  test("collapsedGroups keeps a non-group ref and an unknown ref through a collapse and an expand"):
    val ops = opsOver(DiagramMetadata(collapsedGroups = Set("group:g1", "node:a", "future:x")))
    ops.collapsedGroups.update(_ + GroupId("g2"))
    assertEquals(ops.now().collapsedGroups, Set("group:g1", "group:g2", "node:a", "future:x"))
    ops.collapsedGroups.set(Set.empty)
    assertEquals(ops.now().collapsedGroups, Set("node:a", "future:x"))

  test("autoDetectFormat reads None as on, and a setter writes Some"):
    val ops = opsOver(DiagramMetadata())
    assert(ops.autoDetectFormat.now())
    ops.autoDetectFormat.set(false)
    assertEquals(ops.now().autoDetectFormat, Some(false))
    ops.autoDetectFormat.set(true)
    assertEquals(ops.now().autoDetectFormat, Some(true))

  test("a view writes only its own field"):
    val start = DiagramMetadata(
      hiddenElements = Set("node:a"),
      collapsedGroups = Set("group:g"),
      tags = List("t"),
      notes = "n",
      autoDetectFormat = Some(false)
    )
    val ops = opsOver(start)
    ops.hiddenElements.update(_ + NodeId("b"))
    assertEquals(
      ops.now(),
      start.copy(hiddenElements = Set("node:a", "node:b"))
    )

  test("each view follows a replacement of the whole metadata"):
    val ops = opsOver(DiagramMetadata())
    ops.set(DiagramMetadata(hiddenElements = Set("node:x"), collapsedGroups = Set("group:y"), autoDetectFormat = Some(false)))
    assertEquals(ops.hiddenElements.now(), ElementIds(Set(NodeId("x"))))
    assertEquals(ops.collapsedGroups.now(), Set(GroupId("y")))
    assertEquals(ops.autoDetectFormat.now(), false)

  test("a set of the value that a view already shows changes nothing"):
    // `None` reads as on. A set of `true` must not write `Some(true)`: the
    // write would make a visit look like an edit.
    val ops = opsOver(DiagramMetadata(hiddenElements = Set("node:a")))
    ops.autoDetectFormat.set(true)
    ops.hiddenElements.set(ElementIds(Set(NodeId("a"))))
    assertEquals(ops.now(), DiagramMetadata(hiddenElements = Set("node:a")))

  test("a view reads a replacement of the whole metadata, with no reader of its own"):
    // A `distinct` view whose signal does not run keeps the value of its last
    // read. Thus a replacement from a different writer did not show, and an
    // update then built on the old value and reverted that replacement.
    val ops = opsOver(DiagramMetadata())
    assertEquals(ops.hiddenElements.now(), ElementIds(), "a first read, before the replacement")
    ops.set(DiagramMetadata(hiddenElements = Set("node:a")))
    assertEquals(ops.hiddenElements.now(), ElementIds(Set(NodeId("a"))))
    ops.hiddenElements.update(_ + NodeId("b"))
    assertEquals(ops.now().hiddenElements, Set("node:a", "node:b"), "the update keeps the replacement")
