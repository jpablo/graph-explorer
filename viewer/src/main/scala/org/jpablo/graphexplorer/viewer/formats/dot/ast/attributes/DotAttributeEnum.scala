package org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes

import scala.reflect.ClassTag

sealed trait DotAttribute[A]:
  def attrId: String = this.getClass.getSimpleName.toLowerCase.replace("$", "")
  def label: String
  def default: A
  def placeholderText = ""
  def values: Array[A]
  def valuesWithLabel: Array[(String,  A)] = values.map(v => (v.toString, v))


trait DotAttributeEnum[A] extends DotAttribute[A]
//  def valueOf(s: String): A

trait DotAttributeSimple[A: ClassTag] extends DotAttribute[A]:
  def values: Array[A] = Array.empty
