package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe
import upickle.default.*
import upickle.implicits.key

enum AttributeTarget derives CanEqual:
  case node, edge, graph

case class AttrValue(value: String | AttrEq) derives CanEqual:
  override def toString: String = value match
    case s: String => s
    case a: AttrEq => a.value

  // hack
  def isTrue: Boolean =
    this.toString == true.toString

object AttrValue:
  val empty = AttrValue("")

  given ReadWriter[String | AttrEq] =
    readwriter[ujson.Value].bimap[String | AttrEq](
      {
        case s: String => writeJs(s)
        case a: AttrEq => writeJs(a)
      },
      {
        case ujson.Str(s) => s
        case jsValue      => read[AttrEq](jsValue)
      }
    )

  given ReadWriter[AttrValue] =
    readwriter[String | AttrEq].bimap[AttrValue](_.value, AttrValue(_))

end AttrValue

@key("attr")
case class Attr(id: String, @key("eq") attrEq: AttrValue) derives ReadWriter

object Attr:
  def apply(id: String, value: String): Attr = Attr(id, AttrValue(value))

case class AttrEq(value: String, html: Boolean = false) derives ReadWriter

object SubGraph:
  // TODO: find a better way to generate unique ids
  def randomId(): String = "g" + randomUUIDSafe().take(8)
