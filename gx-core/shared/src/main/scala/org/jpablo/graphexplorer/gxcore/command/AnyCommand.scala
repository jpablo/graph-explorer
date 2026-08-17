package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.models.{ElementId, GroupId}

/** One command, whichever tier it belongs to.
  *
  * D7.1 says "one vocabulary, several hosts", and a caller should not have to
  * know which tier a name belongs to before it can parse it — `gx run x hide`
  * and `gx run x set-attribute` are the same gesture to the person typing them.
  * The tier decides what the command operates on and therefore how the caller
  * persists the result, which is a fact ABOUT the decoded command rather than
  * something the caller must supply to decode it.
  */
enum AnyCommand derives CanEqual:
  case Doc(command: DocumentCommand)
  case Rec(command: RecordCommand)

  def name: String = this match
    case Doc(c) => c.name
    case Rec(c) => c.name

  def tier: Tier = this match
    case Doc(_) => Tier.Document
    case Rec(_) => Tier.Record

  def isQuery: Boolean = this match
    case Doc(c) => c.isQuery
    case Rec(c) => c.isQuery

object AnyCommand:

  /** Every name the headless tiers answer to. */
  val names: Vector[String] = (DocumentCommand.names ++ RecordCommand.names).sorted

  /** Decode a frame into whichever tier owns the name.
    *
    * The two tiers' names are disjoint, which is checked by a test rather than
    * assumed — a name meaning one thing in one tier and something else in
    * another is the kind of ambiguity a vocabulary exists to prevent.
    */
  def decode(frame: ujson.Value): Either[CommandError, AnyCommand] =
    frame.objOpt.flatMap(_.get("command")).flatMap(_.strOpt) match
      case Some(name) if RecordCommand.names.contains(name) =>
        RecordCodec.decode(frame).map(AnyCommand.Rec(_))
      case _ =>
        CommandCodec.decode(frame).map(AnyCommand.Doc(_))

/** The record tier's wire form. Hand-written for the same reason the document
  * tier's is: these names are API, and a derived codec would tie them to the
  * shape of a Scala enum.
  */
object RecordCodec:

  def encode(command: RecordCommand): ujson.Obj =
    ujson.Obj("command" -> command.name, "params" -> params(command))

  private def params(command: RecordCommand): ujson.Obj = command match
    case RecordCommand.Hide(targets)   => ujson.Obj("targets" -> refs(targets))
    case RecordCommand.Unhide(targets) => ujson.Obj("targets" -> refs(targets))
    case RecordCommand.Collapse(groups) => ujson.Obj("groups" -> refs(groups.map(identity[ElementId])))
    case RecordCommand.Expand(groups)   => ujson.Obj("groups" -> refs(groups.map(identity[ElementId])))
    case RecordCommand.Tag(tags)        => ujson.Obj("tags" -> ujson.Arr.from(tags.map(ujson.Str(_))))
    case RecordCommand.Untag(tags)      => ujson.Obj("tags" -> ujson.Arr.from(tags.map(ujson.Str(_))))
    case RecordCommand.SetNotes(notes)  => ujson.Obj("notes" -> notes)
    case RecordCommand.MoveToFolder(f)  => ujson.Obj("folder" -> f)
    case RecordCommand.Rename(newName)  => ujson.Obj("name" -> newName)
    case RecordCommand.UnhideAll | RecordCommand.ExpandAll | RecordCommand.GetRecord => ujson.Obj()

  private def refs(ids: Set[ElementId]): ujson.Arr =
    ujson.Arr.from(ids.toVector.map(ElementRef.render).sorted)

  def decode(frame: ujson.Value): Either[CommandError, RecordCommand] =
    for
      obj <- frame.objOpt.toRight(CommandError.BadArgument("<frame>", "a command must be a JSON object"))
      name <- obj.get("command").flatMap(_.strOpt)
        .toRight(CommandError.BadArgument("<frame>", "a command must have a 'command' name"))
      params = obj.get("params").flatMap(_.objOpt).map(ujson.Obj.from).getOrElse(ujson.Obj())
      command <- decodeNamed(name, params)
    yield command

  private def decodeNamed(name: String, params: ujson.Obj): Either[CommandError, RecordCommand] =
    name match
      case "hide"       => targets(name, params, "targets").map(RecordCommand.Hide(_))
      case "unhide"     => targets(name, params, "targets").map(RecordCommand.Unhide(_))
      case "unhide-all" => Right(RecordCommand.UnhideAll)
      case "collapse"   => groups(name, params).map(RecordCommand.Collapse(_))
      case "expand"     => groups(name, params).map(RecordCommand.Expand(_))
      case "expand-all" => Right(RecordCommand.ExpandAll)
      case "tag"        => strings(name, params, "tags").map(RecordCommand.Tag(_))
      case "untag"      => strings(name, params, "tags").map(RecordCommand.Untag(_))
      case "set-notes"  => string(name, params, "notes").map(RecordCommand.SetNotes(_))
      case "move-to-folder" => string(name, params, "folder").map(RecordCommand.MoveToFolder(_))
      case "rename-diagram" => string(name, params, "name").map(RecordCommand.Rename(_))
      case "get-record"     => Right(RecordCommand.GetRecord)
      case other            => Left(CommandError.UnknownCommand(other))

  private def string(command: String, params: ujson.Obj, key: String): Either[CommandError, String] =
    params.value.get(key).flatMap(_.strOpt)
      .toRight(CommandError.BadArgument(command, s"missing required string '$key'"))

  private def strings(command: String, params: ujson.Obj, key: String): Either[CommandError, List[String]] =
    params.value.get(key).flatMap(_.arrOpt) match
      case None => Left(CommandError.BadArgument(command, s"missing required '$key' array"))
      case Some(items) =>
        val values = items.flatMap(_.strOpt).toList
        if values.sizeIs != items.size then
          Left(CommandError.BadArgument(command, s"every entry in '$key' must be a string"))
        else if values.isEmpty then Left(CommandError.BadArgument(command, s"'$key' cannot be empty"))
        else Right(values)

  private def targets(
      command: String,
      params:  ujson.Obj,
      key:     String
  ): Either[CommandError, Set[ElementId]] =
    params.value.get(key).flatMap(_.arrOpt) match
      case None => Left(CommandError.BadArgument(command, s"missing required '$key' array"))
      case Some(items) =>
        val texts = items.flatMap(_.strOpt).toVector
        if texts.sizeIs != items.size then
          Left(CommandError.BadArgument(command, "every target must be a string like 'node:a'"))
        else ElementRef.parseAll(texts).left.map(CommandError.BadArgument(command, _))

  /** `collapse` takes groups and nothing else.
    *
    * Accepting a node here would be accepting a request that cannot mean
    * anything — and silently dropping it would collapse nothing while
    * reporting success.
    */
  private def groups(command: String, params: ujson.Obj): Either[CommandError, Set[GroupId]] =
    targets(command, params, "groups").flatMap: ids =>
      val (groups, others) = ids.partitionMap {
        case g: GroupId => Left(g)
        case other      => Right(other)
      }
      if others.nonEmpty then
        Left(
          CommandError.BadArgument(
            command,
            s"expects groups, got ${others.toVector.map(ElementRef.render).sorted.mkString(", ")}"
          )
        )
      else if groups.isEmpty then Left(CommandError.BadArgument(command, "needs at least one group"))
      else Right(groups.toSet)
