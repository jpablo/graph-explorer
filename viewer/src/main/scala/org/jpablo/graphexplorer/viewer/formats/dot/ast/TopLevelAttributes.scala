package org.jpablo.graphexplorer.viewer.formats.dot.ast

object TopLevelAttributes:
  enum Label:
    case Text(value: String)
    case Html(value: String)

    override def toString: String =
      this.value

  object Label:
    extension (l: Label)
      def value: String = l match
        case Text(v) => v
        case Html(v) => v

  enum Keys:
    case rankdir, label, size, splines, bgcolor, margin, fontname, fontsize, fontcolor, overlap
