package org.jpablo.graphexplorer.viewer.state

import scala.collection.mutable.ArrayBuffer

/** The steps of undo and redo, for a state of type `S`.
  *
  * A step has the state before a change, the state after it, a label for the user ("Hide", "Edit"), and a group key.
  * Two changes in a row merge into one step when they have the same group key and less than `mergeWindowMs`
  * milliseconds between them. Thus a slider drag or a typed word is one step. A new step after an undo removes the
  * redo steps. The history keeps the last `limit` steps.
  *
  * The type has no reference to the viewer: [[UndoOps]] gives it the snapshots of the diagram.
  */
final class UndoHistory[S](mergeWindowMs: Double = 1000, limit: Int = 200)(using CanEqual[S, S]):

  private final case class Step(before: S, after: S, label: String, group: String, atMs: Double)

  private val done   = ArrayBuffer.empty[Step]
  private val undone = ArrayBuffer.empty[Step]

  /** False after an undo or a redo: the next change starts a new step. */
  private var mergeable = true

  /** Record a change from `before` to `after`. */
  def record(before: S, after: S, label: String, group: String, nowMs: Double): Unit =
    undone.clear()
    done.lastOption match
      case Some(last) if mergeable && last.group == group && nowMs - last.atMs < mergeWindowMs =>
        if last.before == after then done.remove(done.length - 1)
        else done(done.length - 1) = last.copy(after = after, atMs = nowMs)
      case _ =>
        if before != after then
          done += Step(before, after, label, group, nowMs)
          if done.length > limit then done.remove(0)
    mergeable = true

  def canUndo: Boolean = done.nonEmpty
  def canRedo: Boolean = undone.nonEmpty

  /** The label of the step that undo reverts, if any. */
  def undoLabel: Option[String] = done.lastOption.map(_.label)

  /** The label of the step that redo applies again, if any. */
  def redoLabel: Option[String] = undone.lastOption.map(_.label)

  /** Revert the last step: the state before it. */
  def undo(): Option[S] =
    done.lastOption.map: step =>
      done.remove(done.length - 1)
      undone += step
      mergeable = false
      step.before

  /** Apply the last undone step again: the state after it. */
  def redo(): Option[S] =
    undone.lastOption.map: step =>
      undone.remove(undone.length - 1)
      done += step
      mergeable = false
      step.after

  /** Remove all the steps. */
  def clear(): Unit =
    done.clear()
    undone.clear()
    mergeable = true

end UndoHistory
