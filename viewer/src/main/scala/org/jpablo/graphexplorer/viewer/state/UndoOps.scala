package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.{Signal, Transaction}
import com.raquo.airstream.ownership.{Owner, Subscription}
import com.raquo.airstream.state.{StrictSignal, Var}
import org.jpablo.graphexplorer.gxcore.model.DiagramLayout
import org.jpablo.graphexplorer.viewer.models.{ElementIds, GroupId}
import org.scalajs.dom

import scala.scalajs.js

/** Undo and redo of one view of a diagram.
  *
  * A step holds the source text, the hidden elements, the folded groups and the layout (the stored positions of the
  * nodes, ADR 0004), before and after a change, and the selection. The history watches these values, and does not wait for each writer to report a change. Thus each path
  * records its steps: the canvas, the panels, the dialogs, the source editor and the commands of `gx`.
  *
  * Only a change that comes after a gesture of the user is a step: the load of a diagram is not a step. A click or a
  * key on the canvas starts a new step ([[markGesture]]). The changes of one gesture, and the typing with no pause, are
  * one step: a slider drag or a typed word is one step (see [[UndoHistory]]).
  *
  * Undo puts back the state before the step, with the selection at the start of its gesture. Redo puts back the state
  * after the step.
  *
  * Before this class, undo was the history of the source editor (CodeMirror). It had no hide and no fold, and ⌘Z on the
  * canvas did nothing.
  *
  * @param restoreText writes a text of the history, as an undo of the source editor did (`ImportOps.restoreSource`)
  */
final class UndoOps(
    text:        StrictSignal[String],
    restoreText: String => Unit,
    hidden:      Var[ElementIds],
    collapsed:   Var[Set[GroupId]],
    layout:      Var[DiagramLayout],
    selection:   Signal[ElementIds],
    selectionNow: () => ElementIds,
    select:      ElementIds => Unit,
    nowMs:       () => Double = () => js.Date.now()
)(using owner: Owner):

  /** The part of the state that a step changes. */
  final case class Content(text: String, hidden: ElementIds, collapsed: Set[GroupId], layout: DiagramLayout)

  /** A state of the history: the content, and the selection that goes with it. */
  final case class Snapshot(content: Content, selection: ElementIds)

  // `ElementIds` is a case class: its equality compares the ids.
  private given CanEqual[ElementIds, ElementIds] = CanEqual.derived
  private given CanEqual[Content, Content]   = CanEqual.derived
  private given CanEqual[Snapshot, Snapshot] = CanEqual.derived

  private val history = UndoHistory[Snapshot]()

  private def contentNow(): Content = Content(text.now(), hidden.now(), collapsed.now(), layout.now())

  /** The last state that the history knows. A change is the difference from it. */
  private var current = Snapshot(contentNow(), selectionNow())

  /** True after the first gesture of the user. Before it, a change is a load, not a step. */
  private var interacted = false

  /** The number of the gesture. The changes of one gesture are one step. */
  private var gesture = 0

  /** The selection at the start of the gesture. The delete of a node can clear the selection before the text changes,
    * and an undo must select the node again.
    */
  private var selectionAtGesture = Option.empty[ElementIds]

  /** The content of an undo or a redo while its writes arrive. The writes of one step can come in more than one
    * transaction: the text, the hidden elements, the folds and the layout. A mix of the old values and the new values
    * is not a new step. Before this, an undo of a step that changed the text and the layout (a new node at a place,
    * ADR 0004) recorded the mix as a new step, and the new step removed the redo.
    */
  private var restoring = Option.empty[Content]

  private val state = Var((false, false, Option.empty[String], Option.empty[String]))

  /** True when there is a step to undo. */
  val canUndo: Signal[Boolean] = state.signal.map(_._1).distinct

  /** True when there is a step to redo. */
  val canRedo: Signal[Boolean] = state.signal.map(_._2).distinct

  /** The label of the step that undo reverts, for example "Hide". */
  val undoLabel: Signal[Option[String]] = state.signal.map(_._3).distinct

  /** The label of the step that redo applies again. */
  val redoLabel: Signal[Option[String]] = state.signal.map(_._4).distinct

  def canUndoNow: Boolean = history.canUndo
  def canRedoNow: Boolean = history.canRedo

  private def refresh(): Unit =
    state.set((history.canUndo, history.canRedo, history.undoLabel, history.redoLabel))

  /** A click or a key of the user outside of a text field: the next change is a new step. */
  def markGesture(): Unit =
    restoring = None
    interacted = true
    gesture += 1
    selectionAtGesture = Some(selectionNow())

  /** A key in a text field: the user acts, but the typing continues the same step. */
  def markTyping(): Unit =
    restoring = None
    interacted = true

  /** Take the layout of the store with no step: the load of a side file is not a step (ADR 0004). */
  def adoptLayout(next: DiagramLayout): Unit =
    current = current.copy(content = current.content.copy(layout = next))
    layout.set(next)

  def undo(): Unit = history.undo().foreach(apply)
  def redo(): Unit = history.redo().foreach(apply)

  /** Remove all the steps, for example when a different diagram loads into the view. */
  def clear(): Unit =
    restoring = None
    history.clear()
    current = Snapshot(contentNow(), selectionNow())
    refresh()

  private def apply(target: Snapshot): Unit =
    // The history knows the target first. Thus the changes below are not a new step.
    // With no difference, no write comes, and the next change is a new step.
    restoring = Option.when(contentNow() != target.content)(target.content)
    current = target
    Transaction: _ =>
      if text.now() != target.content.text then restoreText(target.content.text)
      hidden.set(target.content.hidden)
      collapsed.set(target.content.collapsed)
      layout.set(target.content.layout)
      select(target.selection)
    refresh()

  private def onChange(next: Content): Unit =
    restoring match
      case Some(target) =>
        // A write of an undo or of a redo. The history follows the content, and records no step.
        current = current.copy(content = next)
        if next == target then restoring = None
      case None => recordChange(next)

  private def recordChange(next: Content): Unit =
    if next != current.content then
      val before = current.copy(selection = selectionAtGesture.getOrElse(current.selection))
      current = Snapshot(next, selectionNow())
      if interacted then
        history.record(before, current, label(before.content, next), s"gesture-$gesture", nowMs())
        refresh()
      selectionAtGesture = None

  private def label(before: Content, after: Content): String =
    val textChanged = before.text != after.text
    val hideChanged = before.hidden != after.hidden
    val foldChanged   = before.collapsed != after.collapsed
    val layoutChanged = before.layout != after.layout
    if !textChanged && !hideChanged && !foldChanged && layoutChanged then
      if !after.layout.isManual then "Arrange"
      else if !before.layout.isManual then "Freeze layout"
      else "Move"
    else if !textChanged && hideChanged && !foldChanged && !layoutChanged then
      if after.hidden.size >= before.hidden.size then "Hide" else "Show"
    else if !textChanged && foldChanged && !hideChanged && !layoutChanged then
      if after.collapsed.size >= before.collapsed.size then "Fold" else "Unfold"
    else "Edit"

  Signal
    .combine(text, hidden.signal, collapsed.signal, layout.signal)
    .foreach((t, h, c, l) => onChange(Content(t, h, c, l)))

  selection.foreach: sel =>
    current = current.copy(selection = sel)

  // The gestures of the user. A key in an editable element is typing; another key or a press is a new gesture.
  private val onPointerDown: js.Function1[dom.Event, Unit] = _ => markGesture()
  private val onKeyDown: js.Function1[dom.KeyboardEvent, Unit] = ev =>
    if !UndoOps.isModifierKey(ev.key) then
      if UndoOps.isEditable(ev.target) then markTyping() else markGesture()
  // Edit → Undo and Edit → Redo of the desktop menu. The shell sends them to the page as window events.
  //
  // A ⌘Z key does not undo twice. On macOS, WKWebView gives the key to the page first. The key equivalent of the
  // menu item fires only when the page does not call `preventDefault`. The canvas, CodeMirror and ProseMirror call
  // `preventDefault` for ⌘Z.
  private val onMenuUndo: js.Function1[dom.Event, Unit] = _ => menuAction(undo(), "undo")
  private val onMenuRedo: js.Function1[dom.Event, Unit] = _ => menuAction(redo(), "redo")

  private def menuAction(onDiagram: => Unit, textCommand: String): Unit =
    UndoOps.menuTarget(dom.document.activeElement) match
      case UndoOps.MenuTarget.Diagram => onDiagram
      case UndoOps.MenuTarget.Text    => dom.document.execCommand(textCommand): Unit
      case UndoOps.MenuTarget.Nothing => ()

  dom.document.addEventListener("pointerdown", onPointerDown, useCapture = true)
  dom.document.addEventListener("keydown", onKeyDown, useCapture = true)
  dom.window.addEventListener("ge:menu.undo", onMenuUndo)
  dom.window.addEventListener("ge:menu.redo", onMenuRedo)
  new Subscription(
    owner,
    () =>
      dom.document.removeEventListener("pointerdown", onPointerDown, useCapture = true)
      dom.document.removeEventListener("keydown", onKeyDown, useCapture = true)
      dom.window.removeEventListener("ge:menu.undo", onMenuUndo)
      dom.window.removeEventListener("ge:menu.redo", onMenuRedo)
  )

end UndoOps

object UndoOps:

  private val modifierKeys = Set("Shift", "Meta", "Control", "Alt", "CapsLock")

  def isModifierKey(key: String): Boolean = modifierKeys(key)

  /** True for a text field, a text area, a select, or an element with editable content (the source editor, the
    * formatted label editor).
    */
  def isEditable(target: dom.EventTarget): Boolean =
    target match
      case el: dom.html.Element =>
        val tag = el.tagName.toUpperCase
        tag == "INPUT" || tag == "TEXTAREA" || tag == "SELECT" || el.isContentEditable
      case _ => false

  /** The receiver of Edit → Undo and Edit → Redo of the desktop menu. */
  enum MenuTarget derives CanEqual:
    /** The history of the diagram. */
    case Diagram

    /** The native undo of a text field. */
    case Text

    /** No receiver. */
    case Nothing

  /** The receiver of a menu undo, from the element with the focus.
    *
    *   - The source editor (CodeMirror) shares the history of the diagram.
    *   - Another editable element (a text field, the formatted label editor) uses its native undo, as the predefined
    *     menu item did.
    *   - An open dialog has no undo.
    *   - Any other element, for example the canvas, uses the history of the diagram.
    */
  def menuTarget(focus: dom.Element): MenuTarget =
    if focus == null then MenuTarget.Diagram
    else if focus.closest(".cm-editor") != null then MenuTarget.Diagram
    else if isEditable(focus) then MenuTarget.Text
    else if focus.closest("dialog[open]") != null then MenuTarget.Nothing
    else MenuTarget.Diagram

  /** The undo chord of the platforms: ⌘Z or Ctrl+Z. Shift gives redo. */
  def isUndoKey(ev: dom.KeyboardEvent): Boolean =
    (ev.metaKey || ev.ctrlKey) && !ev.altKey && !ev.shiftKey && ev.key.equalsIgnoreCase("z")

  /** The redo chords of the platforms: ⇧⌘Z, Ctrl+Shift+Z, or Ctrl+Y (⌘Y). */
  def isRedoKey(ev: dom.KeyboardEvent): Boolean =
    (ev.metaKey || ev.ctrlKey) && !ev.altKey &&
      ((ev.shiftKey && ev.key.equalsIgnoreCase("z")) || (!ev.shiftKey && ev.key.equalsIgnoreCase("y")))

end UndoOps
