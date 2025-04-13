package org.jpablo.graphexplorer.viewer.models

//enum SvgElementId(tpe: String, seq: Int):
//  case NodeId(nodeId: String, seq: Int) extends SvgElementId("node", seq)
//  case EdgeId(title: String, seq: Int)  extends SvgElementId("arrow", seq)
//
//  case ClusterId(groupId: String, seq: Int) extends SvgElementId("group", seq)

object SvgEdgeElementId:

  val edgeSeq = raw"arrow:(\d+)".r

  def getSeq(svgIdAttr: String): Option[Int] =
    svgIdAttr match
      case edgeSeq(seq) => Some(seq.toInt)
      case _                  => None

  def toSvgIdAttr(seq: Int): String = s"arrow:$seq"

object SvgNodeElementId:

  val nodeId = raw"node:(.+)".r

  def getId(svgIdAttr: String): Option[NodeId] =
    svgIdAttr match
      case nodeId(seq) => Some(NodeId(seq))
      case _            => None

  // the same as "node:\\N" in DOT
  def toSvgIdAttr(id: NodeId): String = s"node:$id"

object SvgGroupElementId:

  val groupId = raw"group:(.+)".r

  def getId(svgIdAttr: String): Option[GroupId] =
    svgIdAttr match
      case groupId(seq) => Some(GroupId(seq))
      case _            => None

  // the same as "node:\\N" in DOT
  def toSvgIdAttr(id: GroupId): String = s"group:$id"
