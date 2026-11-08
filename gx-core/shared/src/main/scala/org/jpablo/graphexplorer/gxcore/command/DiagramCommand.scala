package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.models.{ElementId, GroupId}

/** One command on a diagram, from the source tier or the metadata tier.
  *
  * A diagram is its source and its metadata, so these are the commands that
  * `gx run` accepts. A live view command is not one of them.
  *
  * D7.1 says "one vocabulary, several hosts", and a caller should not have to
  * know which tier a name belongs to before it can parse it — `gx run x hide`
  * and `gx run x set-attribute` are the same gesture to the person typing them.
  * The tier decides what the command operates on and therefore how the caller
  * persists the result, which is a fact ABOUT the decoded command rather than
  * something the caller must supply to decode it.
  */
enum DiagramCommand derives CanEqual:
  case Source(command: SourceCommand)
  case Metadata(command: MetadataCommand)

  def name: String = this match
    case Source(c)   => c.name
    case Metadata(c) => c.name

  def tier: Tier = this match
    case Source(_)   => Tier.Source
    case Metadata(_) => Tier.Metadata

  def isQuery: Boolean = this match
    case Source(c)   => c.isQuery
    case Metadata(c) => c.isQuery

object DiagramCommand:

  /** Every name the headless tiers answer to. */
  val names: Vector[String] = (SourceCommand.names ++ MetadataCommand.names).sorted

  /** Decode a frame into whichever tier owns the name.
    *
    * The two tiers' names are disjoint, which is checked by a test rather than
    * assumed — a name meaning one thing in one tier and something else in
    * another is the kind of ambiguity a vocabulary exists to prevent.
    */
  def decode(frame: ujson.Value): Either[CommandError, DiagramCommand] =
    frame.objOpt.flatMap(_.get("command")).flatMap(_.strOpt) match
      case Some(name) if MetadataCommand.names.contains(name) =>
        MetadataCodec.decode(frame).map(DiagramCommand.Metadata(_))
      case _ =>
        SourceCodec.decode(frame).map(DiagramCommand.Source(_))

/** The wire form of the metadata tier. It is hand-written for the same reason
  * as the wire form of the source tier: these names are API, and a derived
  * codec ties them to the shape of a Scala enum.
  */
object MetadataCodec:

  def encode(command: MetadataCommand): ujson.Obj =
    ujson.Obj("command" -> command.name, "params" -> params(command))

  private def params(command: MetadataCommand): ujson.Obj = command match
    case MetadataCommand.Hide(targets)   => ujson.Obj("targets" -> refs(targets))
    case MetadataCommand.Unhide(targets) => ujson.Obj("targets" -> refs(targets))
    case MetadataCommand.Collapse(groups) => ujson.Obj("groups" -> refs(groups.map(identity[ElementId])))
    case MetadataCommand.Expand(groups)   => ujson.Obj("groups" -> refs(groups.map(identity[ElementId])))
    case MetadataCommand.Tag(tags)        => ujson.Obj("tags" -> ujson.Arr.from(tags.map(ujson.Str(_))))
    case MetadataCommand.Untag(tags)      => ujson.Obj("tags" -> ujson.Arr.from(tags.map(ujson.Str(_))))
    case MetadataCommand.SetNotes(notes)  => ujson.Obj("notes" -> notes)
    case MetadataCommand.MoveToFolder(f)  => ujson.Obj("folder" -> f)
    case MetadataCommand.Rename(newName)  => ujson.Obj("name" -> newName)
    case MetadataCommand.UnhideAll | MetadataCommand.ExpandAll | MetadataCommand.GetMetadata => ujson.Obj()

  private def refs(ids: Set[ElementId]): ujson.Arr =
    ujson.Arr.from(ids.toVector.map(ElementRef.render).sorted)

  def decode(frame: ujson.Value): Either[CommandError, MetadataCommand] =
    for
      obj <- frame.objOpt.toRight(CommandError.BadArgument("<frame>", "a command must be a JSON object"))
      name <- obj.get("command").flatMap(_.strOpt)
        .toRight(CommandError.BadArgument("<frame>", "a command must have a 'command' name"))
      params = obj.get("params").flatMap(_.objOpt).map(ujson.Obj.from).getOrElse(ujson.Obj())
      command <- decodeNamed(name, params)
    yield command

  private def decodeNamed(name: String, params: ujson.Obj): Either[CommandError, MetadataCommand] =
    name match
      case "hide"       => targets(name, params, "targets").map(MetadataCommand.Hide(_))
      case "unhide"     => targets(name, params, "targets").map(MetadataCommand.Unhide(_))
      case "unhide-all" => Right(MetadataCommand.UnhideAll)
      case "collapse"   => groups(name, params).map(MetadataCommand.Collapse(_))
      case "expand"     => groups(name, params).map(MetadataCommand.Expand(_))
      case "expand-all" => Right(MetadataCommand.ExpandAll)
      case "tag"        => strings(name, params, "tags").map(MetadataCommand.Tag(_))
      case "untag"      => strings(name, params, "tags").map(MetadataCommand.Untag(_))
      case "set-notes"  => string(name, params, "notes").map(MetadataCommand.SetNotes(_))
      case "move-to-folder" => string(name, params, "folder").map(MetadataCommand.MoveToFolder(_))
      case "rename-diagram" => string(name, params, "name").map(MetadataCommand.Rename(_))
      case "get-metadata"   => Right(MetadataCommand.GetMetadata)
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
