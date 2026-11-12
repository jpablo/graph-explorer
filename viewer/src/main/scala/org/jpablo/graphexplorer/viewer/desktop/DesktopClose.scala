package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.viewer.state.{LeaveIntent, ViewerState}
import org.scalajs.dom

import scala.concurrent.ExecutionContext
import scala.scalajs.js

/** The window closing while an edit is not on disk (§7.4).
  *
  * The shell refuses that close and asks the page, because it cannot ask during
  * teardown: §7.4 rules out doing the work in `pagehide`, since IPC completion
  * is not guaranteed once the window is going. `beforeunload` cannot serve here
  * either — it offers one generic prompt, and this question has three answers.
  *
  * The page therefore keeps the shell's flag CURRENT as it types, rather than
  * being asked at the last moment. A close with nothing unsaved then costs no
  * round trip at all, and a close with something unsaved is refused by the
  * shell before anything is lost.
  */
object DesktopClose:

  private val CloseRequestedEvent = "ge:close.requested"
  private val SetUnsaved          = "set_unsaved"
  private val ConfirmClose        = "confirm_close"

  private var target: Option[ViewerState] = None

  /** Kept so [[reset]] can REMOVE it. A flag alone would leave the listener
    * registered, and a second install would add another beside it — every one
    * of them answering the same close request.
    */
  private var listener: Option[js.Function1[dom.Event, Unit]] = None

  private[desktop] def reset(): Unit =
    listener.foreach(dom.window.removeEventListener(CloseRequestedEvent, _))
    listener = None
    target = None

  /** Follow this view's unsaved state, and answer the shell's question for it.
    *
    * The listener is process-global, like the other desktop listeners, while
    * the target moves with navigation.
    *
    * The subscription lives on the owner of the VIEW (`state.owner`). Thus it
    * stops when the view unmounts, and it does not keep the view in memory.
    * Before, it lived on the window owner. A view that went away then still
    * reported for its file, and an outside change of that file put the flag
    * to false while the view on the screen held an edit.
    */
  def install(state: ViewerState)(using ExecutionContext): Unit =
    target = Some(state)

    // Report as it changes. A flag the shell already holds is what lets a
    // close decide synchronously. The first value is the value of this view
    // now. Only the view on the screen reports: Laminar mounts the new view
    // before it unmounts the old view, and the old view must not change the
    // flag in that time.
    state.documentDirty.foreach(dirty => if target.exists(_ eq state) then report(dirty))(using state.owner)

    if listener.isEmpty then
      val handler: js.Function1[dom.Event, Unit] = _ =>
        target match
          case Some(current) if current.documentIsDirty =>
            // The same dialog a navigation raises. The question is identical,
            // and only the last step differs.
            current.pendingLeave.set(Some(LeaveIntent.CloseWindow))
          case _ =>
            // Nothing to lose. This is the belt for a flag that went stale —
            // the shell should not have asked at all.
            confirm()
      dom.window.addEventListener(CloseRequestedEvent, handler)
      listener = Some(handler)

  /** Release a view that is going away, and tell the shell it has nothing
    * unsaved any more.
    *
    * Without the report, a dirty view that the person navigated away from
    * would leave the shell refusing every close for an edit that no longer
    * exists. A view that is not the target sends nothing: the new view has
    * reported already.
    */
  def detach(state: ViewerState)(using ExecutionContext): Unit =
    if target.exists(_ eq state) then
      target = None
      report(false)

  private def report(unsaved: Boolean)(using ExecutionContext): Unit =
    DesktopIpc
      .invoke(SetUnsaved, js.Dynamic.literal(unsaved = unsaved))
      .failed
      .foreach(_ => ()) // outside the shell there is nothing to tell

  /** The person answered. Let the window go. */
  def confirm()(using ExecutionContext): Unit =
    DesktopIpc
      .invoke(ConfirmClose, js.Dynamic.literal())
      .failed
      .foreach(error => dom.console.warn(s"could not close the window: ${error.getMessage}"))
