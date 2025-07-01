package org.jpablo.graphexplorer.viewer.formats.dot

case class DotText(value: String):

  override def toString: String =
    value

object DotText:
  lazy val empty = DotText("digraph G { }")

end DotText
