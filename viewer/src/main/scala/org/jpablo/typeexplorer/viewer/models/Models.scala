package org.jpablo.typeexplorer.viewer.models

import zio.json.*

enum NamespaceKind derives JsonCodec:
  case Object
  case PackageObject
  case Package
  case Class
  case Trait
  case Unknown
//  case Other(name: String)

opaque type ViewerNodeId = String

object ViewerNodeId:
  def apply(value: String): ViewerNodeId = value
  def empty: ViewerNodeId = ""
  extension (s: ViewerNodeId) def toString: String = s
  given JsonCodec[ViewerNodeId] = JsonCodec.string
  given JsonFieldDecoder[ViewerNodeId] = JsonFieldDecoder.string
  given JsonFieldEncoder[ViewerNodeId] = JsonFieldEncoder.string


case class ViewerNode(
    nodeId:      ViewerNodeId,
    displayName: String,
    kind:        NamespaceKind = NamespaceKind.Class,
) derives JsonCodec

case class Method(symbol: ViewerNodeId, displayName: String, returnType: Option[ViewerNode]) derives JsonCodec

object Method:
  def apply(name: String, returnType: Option[ViewerNode] = None): Method =
    Method(ViewerNodeId(name), name, returnType)
