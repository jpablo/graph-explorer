package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.models.{ElementId, NodeId}

/** A command on the wire: `{"command": "group", "params": {...}}`.
  *
  * Hand-written rather than derived. A derived codec encodes the SHAPE of the
  * Scala enum — case names, field names, whatever upickle's tagging happens to
  * do — which means renaming a case or reordering a field silently changes the
  * protocol. These names are API (D7.1: the UI, socket clients, and later
  * scripting all speak them), so they are written down once, here, where
  * changing one is a visible edit rather than a side effect.
  *
  * The frame shape matches D4's RPC exactly, so a command IS an RPC method
  * call — there is no second envelope to learn.
  */
object CommandCodec:

  def encode(command: DocumentCommand): ujson.Obj =
    ujson.Obj("command" -> command.name, "params" -> params(command))

  private def params(command: DocumentCommand): ujson.Obj = command match
    case DocumentCommand.SetAttribute(targets, attribute, value) =>
      ujson.Obj("targets" -> refs(targets), "name" -> attribute, "value" -> value)
    case DocumentCommand.RemoveAttribute(targets, attribute) =>
      ujson.Obj("targets" -> refs(targets), "name" -> attribute)
    case DocumentCommand.ResetAttributes(targets) => ujson.Obj("targets" -> refs(targets))
    case DocumentCommand.Group(targets, label) =>
      ujson.Obj("targets" -> refs(targets), "label" -> label)
    case DocumentCommand.Ungroup(targets)      => ujson.Obj("targets" -> refs(targets))
    case DocumentCommand.CombineIntoRecord(ns) => ujson.Obj("nodes" -> refs(ns.map(identity[ElementId])))
    case DocumentCommand.SplitRecord(node)     => ujson.Obj("node" -> ElementRef.render(node))
    case DocumentCommand.TransposeRecord(node) => ujson.Obj("node" -> ElementRef.render(node))
    case DocumentCommand.GetAttributes(targets) => ujson.Obj("targets" -> refs(targets))
    case DocumentCommand.ListNodes | DocumentCommand.ListArrows | DocumentCommand.ListGroups =>
      ujson.Obj()

  private def refs(ids: Set[ElementId]): ujson.Arr =
    // Sorted, so an encoded command is stable. A Set has no order, and an
    // unstable encoding would make the audit log diff-noisy and two identical
    // commands compare unequal.
    ujson.Arr.from(ids.toVector.map(ElementRef.render).sorted)

  def decode(frame: ujson.Value): Either[CommandError, DocumentCommand] =
    for
      obj <- frame.objOpt.toRight(CommandError.BadArgument("<frame>", "a command must be a JSON object"))
      name <- obj.get("command").flatMap(_.strOpt)
        .toRight(CommandError.BadArgument("<frame>", "a command must have a 'command' name"))
      params = obj.get("params").flatMap(_.objOpt).map(ujson.Obj.from).getOrElse(ujson.Obj())
      command <- decodeNamed(name, params)
    yield command

  private def decodeNamed(name: String, params: ujson.Obj): Either[CommandError, DocumentCommand] =
    name match
      case "set-attribute" =>
        for
          targets <- targetsOf(name, params)
          attr <- stringOf(name, params, "name")
          value <- stringOf(name, params, "value")
        yield DocumentCommand.SetAttribute(targets, attr, value)

      case "remove-attribute" =>
        for
          targets <- targetsOf(name, params)
          attr <- stringOf(name, params, "name")
        yield DocumentCommand.RemoveAttribute(targets, attr)

      case "reset-attributes" => targetsOf(name, params).map(DocumentCommand.ResetAttributes(_))

      case "group" =>
        for
          targets <- targetsOf(name, params)
          // An unlabelled group is legal — GroupsOps defaults it — so this is
          // optional rather than required-and-often-empty.
          label = params.value.get("label").flatMap(_.strOpt).getOrElse("")
        yield DocumentCommand.Group(targets, label)

      case "ungroup" => targetsOf(name, params).map(DocumentCommand.Ungroup(_))

      case "combine-into-record" =>
        nodesOf(name, params, "nodes").map(DocumentCommand.CombineIntoRecord(_))

      case "split-record"     => nodeOf(name, params).map(DocumentCommand.SplitRecord(_))
      case "transpose-record" => nodeOf(name, params).map(DocumentCommand.TransposeRecord(_))

      case "list-nodes"     => Right(DocumentCommand.ListNodes)
      case "list-arrows"    => Right(DocumentCommand.ListArrows)
      case "list-groups"    => Right(DocumentCommand.ListGroups)
      case "get-attributes" => targetsOf(name, params).map(DocumentCommand.GetAttributes(_))

      case other => Left(CommandError.UnknownCommand(other))

  private def stringOf(command: String, params: ujson.Obj, key: String): Either[CommandError, String] =
    params.value.get(key).flatMap(_.strOpt)
      .toRight(CommandError.BadArgument(command, s"missing required string '$key'"))

  private def targetsOf(command: String, params: ujson.Obj): Either[CommandError, Set[ElementId]] =
    params.value.get("targets").flatMap(_.arrOpt) match
      case None => Left(CommandError.BadArgument(command, "missing required 'targets' array"))
      case Some(items) =>
        val texts = items.flatMap(_.strOpt).toVector
        if texts.sizeIs != items.size then
          Left(CommandError.BadArgument(command, "every target must be a string like 'node:a'"))
        else ElementRef.parseAll(texts).left.map(CommandError.BadArgument(command, _))

  private def nodesOf(
      command: String,
      params:  ujson.Obj,
      key:     String
  ): Either[CommandError, Set[NodeId]] =
    params.value.get(key).flatMap(_.arrOpt) match
      case None => Left(CommandError.BadArgument(command, s"missing required '$key' array"))
      case Some(items) =>
        ElementRef.parseAll(items.flatMap(_.strOpt).toVector)
          .left.map(CommandError.BadArgument(command, _))
          .flatMap(onlyNodes(command, _))

  private def nodeOf(command: String, params: ujson.Obj): Either[CommandError, NodeId] =
    for
      text <- stringOf(command, params, "node")
      id   <- ElementRef.parse(text).left.map(CommandError.BadArgument(command, _))
      node <- onlyNodes(command, Set(id)).map(_.head)
    yield node

  /** Refuse the wrong KIND explicitly.
    *
    * `combine-into-record group:g1` is not a missing argument, it is a
    * category error, and saying so beats silently dropping the reference —
    * which would leave the command doing something adjacent to what was asked.
    */
  private def onlyNodes(command: String, ids: Set[ElementId]): Either[CommandError, Set[NodeId]] =
    val (nodes, others) = ids.partitionMap {
      case n: NodeId => Left(n)
      case other     => Right(other)
    }
    if others.isEmpty && nodes.nonEmpty then Right(nodes.toSet)
    else if nodes.isEmpty && others.isEmpty then
      Left(CommandError.BadArgument(command, "needs at least one node"))
    else
      Left(
        CommandError.BadArgument(
          command,
          s"expects nodes, got ${others.toVector.map(ElementRef.render).sorted.mkString(", ")}"
        )
      )
