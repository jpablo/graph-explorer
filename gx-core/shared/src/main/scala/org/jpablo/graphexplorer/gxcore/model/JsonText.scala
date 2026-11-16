package org.jpablo.graphexplorer.gxcore.model

import scala.collection.immutable.VectorMap
import scala.collection.mutable.ArrayBuffer

import upickle.core.BufferedValue

/** JSON values that keep the text of each number.
  *
  * A `ujson.Value` holds each number as a Double. Thus an integer with more
  * than 15 digits changes, and `1e999` becomes Infinity. A `BufferedValue`
  * keeps the text of the number. The board file and the library entry use it
  * for each key that this build does not know, so that a write gives back the
  * exact value of a later build.
  */
private[model] object JsonText:

  /** Parse a JSON text. Each number keeps its text. */
  def parse(text: String): BufferedValue =
    ujson.Readable.fromString(text).transform(BufferedValue.Builder)

  /** The JSON text of a value. Each number has its text from the read. */
  def render(value: BufferedValue, indent: Int = -1): String =
    BufferedValue.transform(value, ujson.StringRenderer(indent)).toString

  /** The value as a `ujson.Value`. Each number becomes a Double. */
  def toValue(value: BufferedValue): ujson.Value = BufferedValue.transform(value, ujson.Value)

  def of(value: ujson.Value): BufferedValue = value.transform(BufferedValue.Builder)

  /** The fields of an object, in order. A key that occurs again replaces the
    * value of the first occurrence, as in a `ujson.Obj`.
    */
  def fields(obj: BufferedValue.Obj): VectorMap[String, BufferedValue] =
    obj.value0.foldLeft(VectorMap.empty[String, BufferedValue]):
      case (done, (key, value)) => done.updated(keyOf(key), value)

  /** An object with `known`, and then each pair of `other` (a key and its
    * JSON text). A key of `other` that is also in `known` replaces its value.
    */
  def obj(known: Iterable[(String, BufferedValue)], other: Iterable[(String, String)]): BufferedValue.Obj =
    val all = VectorMap.from(known) ++ other.map((key, json) => key -> parse(json))
    BufferedValue.Obj(ArrayBuffer.from(all.map((key, value) => (BufferedValue.Str(key, -1), value))), true, -1)

  private def keyOf(key: BufferedValue): String = key match
    case BufferedValue.Str(s, _) => s.toString
    case other                   => render(other)
