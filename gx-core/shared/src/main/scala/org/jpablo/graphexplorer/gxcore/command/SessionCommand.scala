package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.models.ElementId

/** The session tier: operations on the LIVE VIEW (D7.2).
  *
  * The only tier that cannot run headless, and the reason is a limit of the
  * concept rather than of the implementation: there is no live view without a
  * window, so "what is selected" has no answer when nothing is displaying.
  *
  * Which makes this tier structurally different from the other two. Document and
  * Record commands are interpreted where they are received — `gx` reads a file
  * or a record and answers. A session command is *forwarded*: the desktop's Rust
  * shell holds the socket but knows nothing about diagrams (D2.5), and the
  * webview knows everything about them but holds no capability (D3). So the
  * shell relays the command to the page and relays the page's answer back.
  *
  * That inverts the webview's usual role. For this tier it is a SERVER — it
  * answers questions — while still gaining nothing: it receives a command and
  * returns a value, which is strictly less than the ambient HTTP credential D3
  * took away.
  */
enum SessionCommand derives CanEqual:
  case Select(targets: Set[ElementId])
  case AddToSelection(targets: Set[ElementId])
  case ClearSelection
  case ResetView

  /** Replace the text of ONE named document session.
    *
    * The session id is what makes this safe. Before it, a text push carried
    * text and nothing else, and the page put it into whichever viewer happened
    * to be on screen — a write aimed at no particular document, landing on
    * whatever the person was looking at.
    *
    * A plain `String` rather than a `DocumentSessionId`, because that type
    * lives in the viewer's module and this one does not depend on it. The page
    * parses it.
    */
  case PushText(sessionId: String, text: String)

  /** Query. */
  case WhatIsSelected

  def name: String = SessionCommand.nameOf(this)

  def isQuery: Boolean = this match
    case WhatIsSelected => true
    case _              => false

object SessionCommand:

  def nameOf(command: SessionCommand): String = command match
    case Select(_)         => "select"
    case AddToSelection(_) => "add-to-selection"
    case ClearSelection    => "clear-selection"
    case ResetView         => "reset-view"
    case PushText(_, _)    => "push-text"
    case WhatIsSelected    => "what-is-selected"

  val names: Vector[String] =
    Vector("select", "add-to-selection", "clear-selection", "reset-view", "push-text", "what-is-selected")

  val tier: Tier = Tier.Session

/** The session tier's wire form.
  *
  * Hand-written like the other two, and sharing their frame shape exactly, so a
  * session command travelling to the page looks like every other command in this
  * system — `{"command": …, "params": {…}}`.
  */
object SessionCodec:

  def encode(command: SessionCommand): ujson.Obj =
    ujson.Obj("command" -> command.name, "params" -> params(command))

  private def params(command: SessionCommand): ujson.Obj = command match
    case SessionCommand.Select(targets)         => ujson.Obj("targets" -> refs(targets))
    case SessionCommand.AddToSelection(targets) => ujson.Obj("targets" -> refs(targets))
    case SessionCommand.PushText(sessionId, text) =>
      ujson.Obj("sessionId" -> sessionId, "text" -> text)
    case SessionCommand.ClearSelection | SessionCommand.ResetView | SessionCommand.WhatIsSelected =>
      ujson.Obj()

  private def refs(ids: Set[ElementId]): ujson.Arr =
    ujson.Arr.from(ids.toVector.map(ElementRef.render).sorted)

  def decode(frame: ujson.Value): Either[CommandError, SessionCommand] =
    for
      obj <- frame.objOpt.toRight(CommandError.BadArgument("<frame>", "a command must be a JSON object"))
      name <- obj.get("command").flatMap(_.strOpt)
        .toRight(CommandError.BadArgument("<frame>", "a command must have a 'command' name"))
      params = obj.get("params").flatMap(_.objOpt).map(ujson.Obj.from).getOrElse(ujson.Obj())
      command <- decodeNamed(name, params)
    yield command

  private def decodeNamed(name: String, params: ujson.Obj): Either[CommandError, SessionCommand] =
    name match
      case "select"           => targets(name, params).map(SessionCommand.Select(_))
      case "add-to-selection" => targets(name, params).map(SessionCommand.AddToSelection(_))
      case "clear-selection"  => Right(SessionCommand.ClearSelection)
      case "reset-view"       => Right(SessionCommand.ResetView)
      case "what-is-selected" => Right(SessionCommand.WhatIsSelected)
      case "push-text"        => pushText(params)
      case other              => Left(CommandError.UnknownCommand(other))

  /** Both fields are REQUIRED. A push with no session names no document, which
    * is the shape this command exists to stop accepting.
    */
  private def pushText(params: ujson.Obj): Either[CommandError, SessionCommand] =
    for
      sessionId <- params.value.get("sessionId").flatMap(_.strOpt).filter(_.nonEmpty)
        .toRight(CommandError.BadArgument("push-text", "missing required 'sessionId'"))
      text <- params.value.get("text").flatMap(_.strOpt)
        .toRight(CommandError.BadArgument("push-text", "missing required 'text'"))
    yield SessionCommand.PushText(sessionId, text)

  private def targets(command: String, params: ujson.Obj): Either[CommandError, Set[ElementId]] =
    params.value.get("targets").flatMap(_.arrOpt) match
      case None => Left(CommandError.BadArgument(command, "missing required 'targets' array"))
      case Some(items) =>
        val texts = items.flatMap(_.strOpt).toVector
        if texts.sizeIs != items.size then
          Left(CommandError.BadArgument(command, "every target must be a string like 'node:a'"))
        else ElementRef.parseAll(texts).left.map(CommandError.BadArgument(command, _))
