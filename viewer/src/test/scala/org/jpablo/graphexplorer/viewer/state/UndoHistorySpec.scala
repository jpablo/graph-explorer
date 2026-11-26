package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite

/** The pure undo history: the steps, the redo steps, and the merge of continuous input.
  *
  * A step has the state before the change and the state after it. Undo gives the state before the last step, and
  * redo gives the state after it again. A new step after an undo removes the redo steps.
  *
  * Two changes in a row merge into one step when they have the same group key and the time between them is short: a
  * slider drag or a typed word is one step. A change with a different key is a new step.
  */
class UndoHistorySpec extends FunSuite:

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message.
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[UndoHistorySpec] $clue")

  private def history = UndoHistory[String](mergeWindowMs = 1000, limit = 3)

  test("an empty history has nothing to undo or to redo"):
    val h = history
    check(!h.canUndo && !h.canRedo, "the empty history can undo or redo")
    check(h.undo().isEmpty && h.redo().isEmpty, "the empty history gives a state")

  test("undo gives the state before the step, and redo the state after it"):
    val h = history
    h.record("a", "b", "edit", group = "g1", nowMs = 0)
    check(h.undo().contains("a"), "undo did not give the state before the step")
    check(h.canRedo && !h.canUndo, "after the only undo, redo is not the only choice")
    check(h.redo().contains("b"), "redo did not give the state after the step")

  test("changes with the same group key in a short time are one step"):
    val h = history
    h.record("a", "b", "drag", group = "g1", nowMs = 0)
    h.record("b", "c", "drag", group = "g1", nowMs = 400)
    h.record("c", "d", "drag", group = "g1", nowMs = 800)
    check(h.undo().contains("a"), "the merged step does not go back to the first state")
    check(!h.canUndo, "the drag is more than one step")
    check(h.redo().contains("d"), "redo does not give the last state of the drag")

  test("a different group key, or a long pause, starts a new step"):
    val h = history
    h.record("a", "b", "delete", group = "g1", nowMs = 0)
    h.record("b", "c", "delete", group = "g2", nowMs = 100)
    h.record("c", "d", "delete", group = "g2", nowMs = 5000)
    check(h.undo().contains("c"), "the pause did not start a new step")
    check(h.undo().contains("b"), "the new group key did not start a new step")
    check(h.undo().contains("a"), "the first step is missing")

  test("a new step after an undo removes the redo steps"):
    val h = history
    h.record("a", "b", "edit", group = "g1", nowMs = 0)
    h.record("b", "c", "edit", group = "g2", nowMs = 0)
    h.undo()
    h.record("b", "x", "edit", group = "g3", nowMs = 0)
    check(!h.canRedo, "the redo step of the old branch is still there")
    check(h.undo().contains("b") && h.undo().contains("a"), "the steps before the new branch changed")

  test("after an undo, the next change does not merge into the undone step"):
    val h = history
    h.record("a", "b", "typing", group = "text", nowMs = 0)
    h.undo()
    h.record("a", "c", "typing", group = "text", nowMs = 100)
    check(h.undo().contains("a"), "the new typing merged with the undone typing")

  test("the history keeps only the last `limit` steps"):
    val h = history
    List("a", "b", "c", "d", "e").sliding(2).zipWithIndex.foreach:
      case (List(before, after), i) => h.record(before, after, "edit", group = s"g$i", nowMs = 0)
      case _                        => ()
    val undone = Iterator.continually(h.undo()).takeWhile(_.isDefined).flatten.toList
    check(undone == List("d", "c", "b"), s"the undo steps are $undone")

  test("the labels of the next undo and the next redo"):
    val h = history
    h.record("a", "b", "Hide", group = "g1", nowMs = 0)
    check(h.undoLabel.contains("Hide") && h.redoLabel.isEmpty, s"labels ${h.undoLabel} ${h.redoLabel}")
    h.undo()
    check(h.undoLabel.isEmpty && h.redoLabel.contains("Hide"), s"labels ${h.undoLabel} ${h.redoLabel}")

  test("a change back to the state before the step removes the step"):
    val h = history
    h.record("a", "b", "typing", group = "text", nowMs = 0)
    h.record("b", "a", "typing", group = "text", nowMs = 100)
    check(!h.canUndo, "a typed and deleted letter left an empty step")

end UndoHistorySpec
