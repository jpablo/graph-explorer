package org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes

import org.jpablo.graphexplorer.viewer.models.AttributeId
//import scala.deriving.Mirror

import scala.reflect.ClassTag

sealed trait DotAttribute[A]:
  def attrId: AttributeId = AttributeId(this.getClass.getSimpleName.toLowerCase.replace("$", ""))
  def label: String
  def default: A
  def placeholderText = ""
  def values: Array[A]
  def valuesWithLabel: Array[(String, A)] = values.map(v => (v.toString, v))
  def validLayouts: Set[Layout] = Layout.values.toSet
  // poor's man valueOf
  def fromString(s: String): Option[A] = values.find(_.toString == s)

trait DotAttributeEnum[A] extends DotAttribute[A]

trait DotAttributeSimple[A: ClassTag] extends DotAttribute[A]:
  def values: Array[A] = Array.empty
