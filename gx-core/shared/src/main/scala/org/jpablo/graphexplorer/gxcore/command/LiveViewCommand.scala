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
  *
  * A command that changes the live view NAMES its diagram (plan §2). `path` is
  * the canonical path of the diagram file. The page refuses the command with
  * `VIEW_REJECTED` when the live view shows a different diagram, and the
  * refusal names the diagram on the screen. Without the path, a command for
  * one diagram changed the diagram that was on the screen.
  */
enum LiveViewCommand derives CanEqual:
  case Select(path: String, targets: Set[ElementId])
  case AddToSelection(path: String, targets: Set[ElementId])
  case ClearSelection(path: String)
  case ResetView(path: String)

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

  /** Query. It needs no path, because the answer names the diagram on the
    * screen ([[LiveViewSelection]]).
    */
  case WhatIsSelected

  def name: String = LiveViewCommand.nameOf(this)

  def isQuery: Boolean = this match
    case WhatIsSelected => true
    case _              => false

object LiveViewCommand:

  def nameOf(command: LiveViewCommand): String = command match
    case Select(_, _)         => "select"
    case AddToSelection(_, _) => "add-to-selection"
    case ClearSelection(_)    => "clear-selection"
    case ResetView(_)         => "reset-view"
    case PushText(_, _)       => "push-text"
    case WhatIsSelected       => "what-is-selected"

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

  /** The message of a refusal for a command with no `path`. */
  val MissingPath: String =
    "missing required 'path': the canonical path of the diagram file that the command is for"

  private def params(command: LiveViewCommand): ujson.Obj = command match
    case LiveViewCommand.Select(path, targets)         => ujson.Obj("path" -> path, "targets" -> refs(targets))
    case LiveViewCommand.AddToSelection(path, targets) => ujson.Obj("path" -> path, "targets" -> refs(targets))
    case LiveViewCommand.ClearSelection(path)          => ujson.Obj("path" -> path)
    case LiveViewCommand.ResetView(path)               => ujson.Obj("path" -> path)
    case LiveViewCommand.PushText(sessionId, text) =>
      ujson.Obj("sessionId" -> sessionId, "text" -> text)
    case LiveViewCommand.WhatIsSelected => ujson.Obj()

  private def refs(ids: Set[ElementId]): ujson.Arr =
    ujson.Arr.from(ElementRef.renderAll(ids))

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
      case "select"           => path(name, params).flatMap(p => targets(name, params).map(LiveViewCommand.Select(p, _)))
      case "add-to-selection" => path(name, params).flatMap(p => targets(name, params).map(LiveViewCommand.AddToSelection(p, _)))
      case "clear-selection"  => path(name, params).map(LiveViewCommand.ClearSelection(_))
      case "reset-view"       => path(name, params).map(LiveViewCommand.ResetView(_))
      case "what-is-selected" => Right(LiveViewCommand.WhatIsSelected)
      case "push-text"        => pushText(params)
      case other              => Left(CommandError.UnknownCommand(other))

  /** The path is REQUIRED. A command with no path names no diagram, and the
    * page would apply it to the diagram on the screen.
    */
  private def path(command: String, params: ujson.Obj): Either[CommandError, String] =
    params.value.get("path").flatMap(_.strOpt).filter(_.trim.nonEmpty)
      .toRight(CommandError.BadArgument(command, MissingPath))

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

/** The answer of the page to `what-is-selected`: the selection, and the
  * diagram that it is in.
  *
  *   - `path` is the canonical path of the file on the screen. It is `None`
  *     when the screen shows a diagram that is not a file (an example, or a
  *     browser diagram).
  *   - `shows` names the diagram on the screen for a person: the path of a
  *     file, `an example ("Logo")`, or `the browser diagram "Groups"`. A
  *     `VIEW_REJECTED` message uses the same words.
  *   - `selection` has the selected elements in the `ElementRef` spelling.
  *     The caller renders the names against the file.
  */
final case class LiveViewSelection(path: Option[String], shows: String, selection: Vector[String]) derives CanEqual

object LiveViewSelection:

  def encode(answer: LiveViewSelection): ujson.Obj =
    ujson.Obj(
      "path"      -> answer.path.fold(ujson.Null)(ujson.Str(_)),
      "shows"     -> answer.shows,
      "selection" -> ujson.Arr.from(answer.selection.map(ujson.Str(_)))
    )

  /** A different shape is an answer from a page of a different version. */
  def decode(value: ujson.Value): Either[String, LiveViewSelection] =
    val fields = value.objOpt
    val path   = fields.flatMap(_.get("path"))
    val refs   = fields.flatMap(_.get("selection")).flatMap(_.arrOpt).map(_.toVector.flatMap(_.strOpt))
    (path, refs) match
      case (Some(p), Some(selection)) if p.isNull || p.strOpt.isDefined =>
        val shows = fields.flatMap(_.get("shows")).flatMap(_.strOpt).orElse(p.strOpt).getOrElse("a diagram that is not a file")
        Right(LiveViewSelection(p.strOpt, shows, selection))
      case _ => Left(s"the answer has no 'path' and 'selection': ${value.render()}")
