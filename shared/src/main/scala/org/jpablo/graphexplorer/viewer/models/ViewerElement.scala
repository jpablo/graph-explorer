package org.jpablo.graphexplorer.viewer.models

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{ClusterLabelLoc, Id, Label, LabelJust}
import org.jpablo.graphexplorer.viewer.models.Arrow.titleIdSeparator
import org.jpablo.graphexplorer.viewer.models.ViewerElement.idAttributeKey
import upickle.default.*

type ViewerKind = Option[String]

/** Internal representation of graphical elements in the viewer (nodes, arrows, groups).
  */
sealed trait ViewerElement:
  def id: ElementId

  def attributes: Attributes

  def label: AttrValue =
    attributes.values.getOrElse(Label.attrId, AttrValue.empty)

  def idAttr: AttrValue =
    attributes.values.getOrElse(idAttributeKey, AttrValue.empty)

object ViewerElement:
  val idAttributeKey = Id.attrId

// ------------------
//      Nodes
// ------------------

case class ViewerNode private (
    override val id: NodeId,
    attributes:      Attributes = Attributes.empty,
    kind:            ViewerKind = None
) extends ViewerElement:
  def modifyAttrs = this.modify(_.attributes)

object ViewerNode:

  val defaultNodeAttributes = Attributes.of(Label -> "")

  def nodeNoDefaults(nodeId: NodeId, attributes: Attributes = Attributes.empty) =
    ViewerNode(nodeId, attributes)

  def nodeWithDefaults(nodeId: NodeId, attributes: Attributes = Attributes.empty) =
    ViewerNode(nodeId, defaultNodeAttributes ++ attributes)

  def nodeWithId(nodeIdOrString: NodeId | String, attrs: (String, String)*) =
    val nodeId =
      nodeIdOrString match
        case id: NodeId  => id
        case str: String => NodeId(str)
    nodeId -> nodeWithDefaults(nodeId, Attributes.of(attrs*))

// ------------------
//      Arrows
// ------------------

enum ArrowDirection derives CanEqual:
  case forward, backward

  def isForward = this == forward

enum ArrowEndpointId derives CanEqual:
  case SourceId(nodeId: NodeId)
  case TargetId(nodeId: NodeId)

case class Arrow(
    source:     NodeId,
    target:     NodeId,
    attributes: Attributes = Attributes.empty,
    seq:        Int = 1
) extends ViewerElement:

  // Re-create the string used by graphviz in the `<title>` element of the SVG.
  override val id: ArrowId =
    ArrowId(s"${source.value}$titleIdSeparator${target.value}:$seq")

  def toSvg: String = s"arrow:$id"

  def endpoints = Seq(source, target)
end Arrow

object Arrow:

  given SequenceGenerator = new DefaultSequenceGenerator()

  val titleIdSeparator = "->"

  def nextArrow(t: (String, String), attrs: Attributes = Attributes.empty)(using seq: SequenceGenerator): Arrow =
    arrow(t, attrs, seq.nextSequence())

  def arrow(t: (String, String), attrs: Attributes = Attributes.empty, seq: Int): Arrow =
    new Arrow(NodeId(t._1), NodeId(t._2), attrs, seq)

  def arrow(s: NodeId, t: NodeId) =
    val a = Arrow(s, t)
    a.id -> a

  // example:
  // A->B:1
  val edgeTitlePattern = raw"(.+)$titleIdSeparator(.+):(\d+)".r

  def fromArrowId(arrowId: ArrowId): Option[Arrow] =
    arrowId.value match
      case edgeTitlePattern(l, r, i) if l.trim.nonEmpty && r.trim.nonEmpty => Some(arrow(l.trim -> r.trim, seq = i.toInt))
      case _                                                               => None

  val arrowId = raw"arrow:(.+)".r

  def fromSvg(idAttr: String): Option[ArrowId] =
    idAttr match
      case arrowId(id) => Some(ArrowId(id))
      case _           => None

end Arrow

// -----------------
//     groups
// -----------------

case class ViewerGroup private (
    override val id: GroupId,
    attributes:      Attributes = Attributes.empty
) extends ViewerElement derives CanEqual:
  def modifyAttrs = this.modify(_.attributes)

object ViewerGroup:
  val defaultGroupAttributes: Attributes =
    Attributes.of(
      Label           -> "",
      ClusterLabelLoc -> ClusterLabelLoc.default,
      LabelJust       -> LabelJust.default
    )

  def group(
      groupId:    GroupId,
      attributes: Attributes = Attributes.empty
  ) =
    ViewerGroup(groupId, defaultGroupAttributes ++ attributes)

  def empty(groupId: GroupId) = ViewerGroup(groupId)

  def groupWithId(groupId: GroupId) =
    groupId -> group(groupId)

end ViewerGroup
