package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.models.ElementId

/** The live view tier: operations on the LIVE VIEW, what a desktop window
  * shows now (D7.2).
  *
  * This is the only tier that cannot run headless. The reason is a limit of the
  * concept, not of the implementation: a live view exists only in a window. When
  * no window shows a diagram, "what is selected" has no answer.
  *
  * Thus this tier is different from the other two. A source command or a
  * metadata command runs where it arrives: `gx` reads a file or a diagram and
  * answers. The desktop *forwards* a live view command. Its Rust shell holds the
  * socket but knows nothing about diagrams (D2.5). The webview knows everything
  * about them but holds no capability (D3). So the shell relays the command to
  * the page, and it relays the answer of the page back.
  *
  * That inverts the webview's usual role. For this tier it is a SERVER — it
  * answers questions — while still gaining nothing: it receives a command and
  * returns a value, which is strictly less than the ambient HTTP credential D3
  * took away.
  */
enum LiveViewCommand derives CanEqual:
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

  def name: String = LiveViewCommand.nameOf(this)

  def isQuery: Boolean = this match
    case WhatIsSelected => true
    case _              => false

object LiveViewCommand:

  def nameOf(command: LiveViewCommand): String = command match
    case Select(_)         => "select"
    case AddToSelection(_) => "add-to-selection"
    case ClearSelection    => "clear-selection"
    case ResetView         => "reset-view"
    case PushText(_, _)    => "push-text"
    case WhatIsSelected    => "what-is-selected"

  val names: Vector[String] =
    Vector("select", "add-to-selection", "clear-selection", "reset-view", "push-text", "what-is-selected")

  val tier: Tier = Tier.LiveView

/** The wire form of the live view tier.
  *
  * It is hand-written like the other two, and it has the same frame shape. Thus
  * a live view command that goes to the page looks like every other command in
  * this system — `{"command": …, "params": {…}}`.
  */
object LiveViewCodec:

  def encode(command: LiveViewCommand): ujson.Obj =
    ujson.Obj("command" -> command.name, "params" -> params(command))

  private def params(command: LiveViewCommand): ujson.Obj = command match
    case LiveViewCommand.Select(targets)         => ujson.Obj("targets" -> refs(targets))
    case LiveViewCommand.AddToSelection(targets) => ujson.Obj("targets" -> refs(targets))
    case LiveViewCommand.PushText(sessionId, text) =>
      ujson.Obj("sessionId" -> sessionId, "text" -> text)
    case LiveViewCommand.ClearSelection | LiveViewCommand.ResetView | LiveViewCommand.WhatIsSelected =>
      ujson.Obj()

  private def refs(ids: Set[ElementId]): ujson.Arr =
    ujson.Arr.from(ids.toVector.map(ElementRef.render).sorted)

  def decode(frame: ujson.Value): Either[CommandError, LiveViewCommand] =
    for
      obj <- frame.objOpt.toRight(CommandError.BadArgument("<frame>", "a command must be a JSON object"))
      name <- obj.get("command").flatMap(_.strOpt)
        .toRight(CommandError.BadArgument("<frame>", "a command must have a 'command' name"))
      params = obj.get("params").flatMap(_.objOpt).map(ujson.Obj.from).getOrElse(ujson.Obj())
      command <- decodeNamed(name, params)
    yield command

  private def decodeNamed(name: String, params: ujson.Obj): Either[CommandError, LiveViewCommand] =
    name match
      case "select"           => targets(name, params).map(LiveViewCommand.Select(_))
      case "add-to-selection" => targets(name, params).map(LiveViewCommand.AddToSelection(_))
      case "clear-selection"  => Right(LiveViewCommand.ClearSelection)
      case "reset-view"       => Right(LiveViewCommand.ResetView)
      case "what-is-selected" => Right(LiveViewCommand.WhatIsSelected)
      case "push-text"        => pushText(params)
      case other              => Left(CommandError.UnknownCommand(other))

  /** Both fields are REQUIRED. A push with no session names no document, which
    * is the shape this command exists to stop accepting.
    */
  private def pushText(params: ujson.Obj): Either[CommandError, LiveViewCommand] =
    for
      sessionId <- params.value.get("sessionId").flatMap(_.strOpt).filter(_.nonEmpty)
        .toRight(CommandError.BadArgument("push-text", "missing required 'sessionId'"))
      text <- params.value.get("text").flatMap(_.strOpt)
        .toRight(CommandError.BadArgument("push-text", "missing required 'text'"))
    yield LiveViewCommand.PushText(sessionId, text)

  private def targets(command: String, params: ujson.Obj): Either[CommandError, Set[ElementId]] =
    params.value.get("targets").flatMap(_.arrOpt) match
      case None => Left(CommandError.BadArgument(command, "missing required 'targets' array"))
      case Some(items) =>
        val texts = items.flatMap(_.strOpt).toVector
        if texts.sizeIs != items.size then
          Left(CommandError.BadArgument(command, "every target must be a string like 'node:a'"))
        else ElementRef.parseAll(texts).left.map(CommandError.BadArgument(command, _))
