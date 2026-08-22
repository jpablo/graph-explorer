package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.gxcore.command.{ElementRef, SessionCodec, SessionCommand}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom

import scala.scalajs.js
import scala.util.chaining.scalaUtilChainingOps
import scala.util.control.NonFatal

/** The session tier, executed in the page (D7.2).
  *
  * This is the one tier the webview *serves*. A socket client asks the desktop
  * "what is selected"; the Rust shell knows nothing about diagrams (D2.5) and
  * the page knows everything but holds no capability (D3), so the shell relays
  * the question here and relays the answer back.
  *
  * The page gains nothing by answering. It receives a command from its own host
  * and returns a value — strictly less than the ambient HTTP credential D3 took
  * away, and it still cannot initiate anything.
  */
object SessionCommands:

  /** The event the desktop dispatches, carrying `{id, command, params}`. */
  private val CommandEventName = "ge:session.command"

  /** The Tauri command the answer goes back through. */
  private val ReplyCommand = "session_reply"

  private var installed = false
  private var target: Option[ViewerState] = None

  /** Start listening, whether or not a diagram is open.
    *
    * Separate from `attach` because of what happens otherwise: installed only
    * when a diagram view mounts, the app sitting on its library route has no
    * listener at all, so a socket client's question goes unanswered and it waits
    * out the shell's five-second timeout to be told "the page did not answer".
    * That is true and useless — the real answer is "nothing is open", which is
    * a thing the page can say instantly and only if it is listening.
    *
    * Found by running it: every unit test had a diagram, because a test that
    * sets one up is the natural one to write.
    */
  def install(): Unit =
    if !installed then
      val handler: js.Function1[dom.Event, Unit] = event => handle(event)
      dom.window.addEventListener(CommandEventName, handler)
      installed = true

  /** Point the tier at the diagram now on screen. */
  def attach(state: ViewerState): Unit =
    install()
    target = Some(state)

  /** Let go of a viewer that is unmounting, so the tier answers "nothing is
    * open" rather than reporting on a diagram that left the screen.
    *
    * The listener deliberately stays installed — see [[attach]]'s note: a page
    * on its library route must still be able to answer instantly. Only the
    * target goes. Identity-checked for the same reason as
    * [[DesktopBridge.detach]]: the incoming view mounts before the outgoing one
    * unmounts, so an unconditional clear would discard the live target.
    */
  def detach(state: ViewerState): Unit =
    if target.exists(_ eq state) then target = None

  private[desktop] def reset(): Unit =
    installed = false
    target = None

  private[desktop] def currentTarget: Option[ViewerState] = target

  private def handle(event: dom.Event): Unit =
    val detail = event.asInstanceOf[js.Dynamic].selectDynamic("detail")
    // The id is what lets the shell match an answer to the question it asked. A
    // reply without one is unroutable, so there is nothing useful to do with it
    // and nothing to report it to.
    val id = field(detail, "id").flatMap(v => if js.typeOf(v) == "number" then Some(v.asInstanceOf[Double]) else None)
    id match
      case None => dom.console.warn("session command arrived with no id; ignoring")
      case Some(requestId) =>
        val frame =
          try ujson.read(js.JSON.stringify(detail.asInstanceOf[js.Any]))
          catch case NonFatal(_) => ujson.Obj()

        SessionCodec.decode(frame) match
          case Left(error)   => reply(requestId, Left(Refusal("INVALID_REQUEST", error.message)))
          case Right(command) => reply(requestId, run(command))

  /** A refusal, with the code the caller should branch on.
    *
    * `NO_SESSION` is carried rather than inferred from the message: a desktop
    * with nothing open and a desktop with no window are the same situation to
    * whoever asked — their next move is to open something — and matching on
    * prose to discover that would be a coupling waiting to break.
    */
  private[desktop] case class Refusal(code: String, message: String)

  /** Run against the live view.
    *
    * `Left` is a refusal; `Right` is the answer, which is `ujson.Null` for the
    * mutations. Keeping both in one type is what lets the shell relay either
    * without knowing which it asked for.
    */
  private[desktop] def run(command: SessionCommand): Either[Refusal, ujson.Value] =
    target match
      case None =>
        // A desktop with no diagram open. The socket client asked a live-view
        // question of something that has no live view, and saying so beats
        // answering "nothing is selected" — which is true but misleading.
        Refusal("NO_SESSION", "no diagram is open").pipe(Left(_))

      case Some(state) =>
        try
          command match
            case SessionCommand.Select(targets) =>
              state.selection.set1(targets)
              Right(ujson.Null)

            case SessionCommand.AddToSelection(targets) =>
              state.selection.add(targets)
              Right(ujson.Null)

            case SessionCommand.ClearSelection =>
              state.selection.clear()
              Right(ujson.Null)

            case SessionCommand.ResetView =>
              state.resetView()
              Right(ujson.Null)

            case SessionCommand.WhatIsSelected =>
              Right(
                ujson.Arr.from(
                  state.selection.now().ids.toVector.map(ElementRef.render).sorted.map(ujson.Str(_))
                )
              )
        catch case NonFatal(e) => Left(Refusal("SESSION_FAILED", s"${command.name} failed: ${e.getMessage}"))

  private def reply(id: Double, outcome: Either[Refusal, ujson.Value]): Unit =
    val args = outcome match
      case Right(value) =>
        js.Dynamic.literal(id = id, ok = true, result = js.JSON.parse(ujson.write(value)))
      case Left(refusal) =>
        js.Dynamic.literal(id = id, ok = false, code = refusal.code, message = refusal.message)

    // Fire and forget: the shell is waiting on its own timeout, so a failure to
    // deliver the reply is its problem to notice rather than something the page
    // can fix by retrying.
    import scala.concurrent.ExecutionContext.Implicits.global
    DesktopIpc.invoke(ReplyCommand, args).failed.foreach: error =>
      dom.console.warn(s"could not deliver a session reply: ${error.getMessage}")

  private def field(value: js.Any, name: String): Option[js.Any] =
    if js.isUndefined(value) || value == null then None
    else
      val selected = value.asInstanceOf[js.Dynamic].selectDynamic(name)
      if js.isUndefined(selected) || selected == null then None else Some(selected)
