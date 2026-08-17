package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*

/** What running a command produced.
  *
  * A mutation returns a graph; a query returns an answer and no graph. Keeping
  * them in one type is what lets a caller — `gx`, the UI, an agent — run a
  * command without first knowing which kind it is, which is the whole point of
  * one vocabulary (D7.1).
  */
enum CommandResult derives CanEqual:
  case Updated(graph: ViewerGraph)
  case Answered(value: ujson.Value)

  /** `updatedGraph` rather than `graph`: the case already binds a field of that
    * name, and an accessor that shadows it reads as the same thing while
    * meaning something different (a `ViewerGraph` vs an `Option` of one).
    */
  def updatedGraph: Option[ViewerGraph] = this match
    case Updated(g)  => Some(g)
    case Answered(_) => None

  def answer: Option[ujson.Value] = this match
    case Answered(v) => Some(v)
    case Updated(_)  => None

/** The document tier, interpreted over `shared/`'s `Ops`.
  *
  * This adds no behaviour. Every case delegates to the operation the UI already
  * calls today — that is D7.1's point: the vocabulary is the existing ops made
  * addressable, so a command and a menu item cannot drift into doing two
  * different things.
  *
  * Pure, and in `gx-core/shared` rather than `jvm/`, because all three hosts
  * need it: `gx` (JVM), the viewer (Scala.js), and later the session tier's
  * fast path. Nothing here touches a filesystem or a window.
  */
object DocumentCommands:

  def run(graph: ViewerGraph, command: DocumentCommand): Either[CommandError, CommandResult] =
    resolve(graph, command).flatMap(_ => interpret(graph, command))

  /** Every element a command names must exist.
    *
    * This is the one place the command tier adds a rule the op underneath does
    * not have, and it is worth the exception. `updateAttributes` handles an
    * unknown id ASYMMETRICALLY: arrows and groups are filtered out, but a node
    * is CREATED — `nodes.getOrElse(id, nodeWithDefaults(id))`. In the UI that
    * never shows, because a selection can only contain elements that exist. On
    * a socket it shows immediately: one stale or mistyped `node:foo` from an
    * agent silently adds a node to the user's diagram, and the reply says the
    * command succeeded.
    *
    * Refusing is not drift from the op. It is the same thing the UI does by
    * greying out a menu item for a selection the operation cannot apply to —
    * and a socket client has no menu to grey out, so the check has to live with
    * the command.
    */
  private def resolve(graph: ViewerGraph, command: DocumentCommand): Either[CommandError, Unit] =
    val named: Set[ElementId] = command match
      case DocumentCommand.SetAttribute(targets, _, _)   => targets
      case DocumentCommand.RemoveAttribute(targets, _)   => targets
      case DocumentCommand.ResetAttributes(targets)      => targets
      case DocumentCommand.Group(targets, _)             => targets
      case DocumentCommand.Ungroup(targets)              => targets
      case DocumentCommand.CombineIntoRecord(nodes)      => nodes.map(identity[ElementId])
      case DocumentCommand.SplitRecord(node)             => Set(node)
      case DocumentCommand.TransposeRecord(node)         => Set(node)
      case DocumentCommand.GetAttributes(targets)        => targets
      case DocumentCommand.ListNodes | DocumentCommand.ListArrows | DocumentCommand.ListGroups =>
        Set.empty

    val missing = named.filterNot(exists(graph, _))
    if missing.isEmpty then Right(())
    else
      Left(
        CommandError.NotApplicable(
          command.name,
          s"no such element: ${missing.toVector.map(ElementRef.render).sorted.mkString(", ")}"
        )
      )

  private def exists(graph: ViewerGraph, id: ElementId): Boolean = id match
    case n: NodeId  => graph.nodes.contains(n)
    case a: ArrowId => graph.arrows.contains(a)
    case g: GroupId => graph.elements.groups.contains(g)

  private def interpret(
      graph:   ViewerGraph,
      command: DocumentCommand
  ): Either[CommandError, CommandResult] =
    command match

      case DocumentCommand.SetAttribute(targets, attribute, value) =>
        val updates = AttributeUpdates(Map(AttributeId(attribute) -> AttrStatus.Single(AttrValue(value))))
        Right(CommandResult.Updated(graph.updateAttributes(ElementIds(targets), updates)))

      case DocumentCommand.RemoveAttribute(targets, attribute) =>
        val updates = AttributeUpdates.remove(Set(AttributeId(attribute)))
        Right(CommandResult.Updated(graph.updateAttributes(ElementIds(targets), updates)))

      case DocumentCommand.ResetAttributes(targets) =>
        Right(CommandResult.Updated(graph.resetAttributes(ElementIds(targets))))

      case DocumentCommand.Group(targets, label) =>
        Right(CommandResult.Updated(graph.moveToNewGroup(ElementIds(targets), label)))

      case DocumentCommand.Ungroup(targets) =>
        Right(CommandResult.Updated(graph.ungroupSelection(ElementIds(targets))))

      case DocumentCommand.CombineIntoRecord(nodes) =>
        // The applicability check the UI already does before enabling the menu
        // item. A socket client has no menu to grey out, so the check has to
        // live with the command rather than with the button.
        if graph.canCombineNodes(nodes) then
          Right(CommandResult.Updated(graph.combineIntoRecord(nodes)))
        else
          Left(
            CommandError.NotApplicable(
              command.name,
              s"these nodes cannot be combined into a record: ${render(nodes)}"
            )
          )

      case DocumentCommand.SplitRecord(node) =>
        if graph.canSplitRecord(node) then Right(CommandResult.Updated(graph.splitRecordNode(node)))
        else
          Left(CommandError.NotApplicable(command.name, s"${ElementRef.render(node)} is not a record node"))

      case DocumentCommand.TransposeRecord(node) =>
        if graph.isRecordNode(node) then Right(CommandResult.Updated(graph.transposeRecord(node)))
        else
          Left(CommandError.NotApplicable(command.name, s"${ElementRef.render(node)} is not a record node"))

      case DocumentCommand.ListNodes =>
        Right(CommandResult.Answered(listing(graph.nodeIds.toVector.map(id => id -> labelOf(graph, id)))))

      case DocumentCommand.ListArrows =>
        Right(
          CommandResult.Answered(
            ujson.Arr.from(
              graph.arrows.toVector.sortBy(_._1.value).map: (id, arrow) =>
                ujson.Obj(
                  "ref"    -> ElementRef.render(id),
                  "source" -> ElementRef.render(arrow.source),
                  "target" -> ElementRef.render(arrow.target)
                )
            )
          )
        )

      case DocumentCommand.ListGroups =>
        Right(
          CommandResult.Answered(
            ujson.Arr.from(
              graph.groups.toVector.sortBy(_._1.value).map: (id, group) =>
                ujson.Obj("ref" -> ElementRef.render(id), "label" -> group.label.toString)
            )
          )
        )

      case DocumentCommand.GetAttributes(targets) =>
        Right(
          CommandResult.Answered(
            ujson.Obj.from(
              targets.toVector.sortBy(ElementRef.render).map: id =>
                ElementRef.render(id) -> ujson.Obj.from(
                  graph.getAttributesById(id).values.toVector
                    .map((k, v) => k.value -> ujson.Str(v.toString))
                    .sortBy(_._1)
                )
            )
          )
        )

  private def listing(entries: Vector[(NodeId, Option[String])]): ujson.Arr =
    // Sorted, because a query's answer is compared, diffed and asserted on. A
    // VectorMap has an order, but it is the order the file happened to be
    // parsed in — a property of the input, not an answer to the question.
    ujson.Arr.from(
      entries.sortBy(_._1.value).map: (id, label) =>
        ujson.Obj("ref" -> ElementRef.render(id), "label" -> label.map(ujson.Str(_)).getOrElse(ujson.Null))
    )

  /** A label is an `AttrValue`, and an absent one is the empty value rather
    * than a `None` — so "no label" and "an empty label" are the same thing in
    * the model. Reported as null rather than as `""`, since a client asking
    * what the nodes are called should not have to know that.
    */
  private def labelOf(graph: ViewerGraph, id: NodeId): Option[String] =
    graph.nodes.get(id).map(_.label.toString).filter(_.nonEmpty)

  private def render(ids: Set[NodeId]): String =
    ids.toVector.map(ElementRef.render).sorted.mkString(", ")
