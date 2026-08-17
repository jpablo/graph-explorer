package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.models.{ElementId, NodeId}

/** Which kind of thing an operation is *about* (D7.2).
  *
  * The split is not "who implements it" but what it operates on, and that is
  * what decides whether it can run headless: Document and Record are **state**,
  * on disk, so `gx` can read and write them with no window anywhere. Session is
  * **view**, and there is no live view without one — a limit of the concept,
  * not of the implementation.
  */
enum Tier derives CanEqual:
  case Document, Record, Session

  def headless: Boolean = this != Tier.Session

/** Why a command could not run. Separated from "it ran and the answer is no"
  * because a caller retries one and not the other.
  */
enum CommandError derives CanEqual:
  case UnknownCommand(name: String)
  case BadArgument(command: String, detail: String)
  case NotApplicable(command: String, detail: String)

  def message: String = this match
    case UnknownCommand(name)         => s"unknown command '$name'"
    case BadArgument(command, detail) => s"$command: $detail"
    case NotApplicable(command, detail) => s"$command: $detail"

/** The document tier: operations on the diagram TEXT.
  *
  * Every case here is a name and a serializable argument form over an operation
  * `shared/`'s `Ops` modules already perform. That is the whole point of D7.1 —
  * the vocabulary is not new behaviour, it is the existing behaviour made
  * *addressable*. The UI must eventually go through these rather than calling
  * `Ops` directly, because undo/redo, the audit log and replay all fall out of a
  * named serializable command set and none of them are cheap to retrofit.
  *
  * Queries are here too, and deliberately: "list the nodes" is not expressible
  * as watched state at any level of cleverness, which is the reason D4's channel
  * is request/response rather than fire-and-forget.
  */
enum DocumentCommand derives CanEqual:
  /** Mutations. */
  case SetAttribute(targets: Set[ElementId], attribute: String, value: String)
  case RemoveAttribute(targets: Set[ElementId], attribute: String)
  case ResetAttributes(targets: Set[ElementId])
  case Group(targets: Set[ElementId], label: String)
  case Ungroup(targets: Set[ElementId])
  case CombineIntoRecord(nodes: Set[NodeId])
  case SplitRecord(node: NodeId)
  case TransposeRecord(node: NodeId)

  /** Queries. */
  case ListNodes
  case ListArrows
  case ListGroups
  case GetAttributes(targets: Set[ElementId])

  def name: String = DocumentCommand.nameOf(this)

  def isQuery: Boolean = this match
    case ListNodes | ListArrows | ListGroups | GetAttributes(_) => true
    case _                                                      => false

object DocumentCommand:

  /** The names, in one place.
    *
    * Kebab-case because these are typed by humans on a command line and read by
    * agents out of a JSON frame, not written in Scala. A name is API: it appears
    * in the audit log, in `gx`'s help, and in every recorded command a replay
    * would re-run, so it changes only deliberately.
    */
  def nameOf(command: DocumentCommand): String = command match
    case SetAttribute(_, _, _)  => "set-attribute"
    case RemoveAttribute(_, _)  => "remove-attribute"
    case ResetAttributes(_)     => "reset-attributes"
    case Group(_, _)            => "group"
    case Ungroup(_)             => "ungroup"
    case CombineIntoRecord(_)   => "combine-into-record"
    case SplitRecord(_)         => "split-record"
    case TransposeRecord(_)     => "transpose-record"
    case ListNodes              => "list-nodes"
    case ListArrows             => "list-arrows"
    case ListGroups             => "list-groups"
    case GetAttributes(_)       => "get-attributes"

  /** Every name the document tier answers to, for help text and for the
    * "unknown command" message — which should say what IS known rather than
    * only what is not.
    */
  val names: Vector[String] = Vector(
    "set-attribute",
    "remove-attribute",
    "reset-attributes",
    "group",
    "ungroup",
    "combine-into-record",
    "split-record",
    "transpose-record",
    "list-nodes",
    "list-arrows",
    "list-groups",
    "get-attributes"
  )

  val tier: Tier = Tier.Document
