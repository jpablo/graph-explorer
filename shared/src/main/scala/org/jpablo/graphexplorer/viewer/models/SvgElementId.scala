package org.jpablo.graphexplorer.viewer.models

//enum SvgElementId(tpe: String, seq: Int):
//  case NodeId(nodeId: String, seq: Int) extends SvgElementId("node", seq)
//  case EdgeId(title: String, seq: Int)  extends SvgElementId("arrow", seq)
//
//  case ClusterId(groupId: String, seq: Int) extends SvgElementId("group", seq)

object SvgEdgeElementId:

  val edgeIdPattern = raw"edge:(\d+)".r

  def getSeq(svgIdAttr: String): Option[Int] =
    svgIdAttr match
      case edgeIdPattern(seq) => Some(seq.toInt)
      case _                  => None

  def toSvgIdAttr(seq: Int): String = s"edge:$seq"


object SvgNodeElementId:

  val nodeIdPattern = raw"node:(.+)".r

  def getId(svgIdAttr: String): Option[NodeId] =
    svgIdAttr match
      case nodeIdPattern(seq) => Some(NodeId(seq))
      case _                  => None

  // the same as "node:\\N" in DOT
  def toSvgIdAttr(id: NodeId): String = s"node:$id"

object SvgGroupElementId:

  val groupIdPattern = raw"group:(.+)".r

  def getId(svgIdAttr: String): Option[GroupId] =
    svgIdAttr match
      case groupIdPattern(seq) => Some(GroupId(seq))
      case _                  => None

  // the same as "node:\\N" in DOT
  def toSvgIdAttr(id: GroupId): String = s"group:$id"
