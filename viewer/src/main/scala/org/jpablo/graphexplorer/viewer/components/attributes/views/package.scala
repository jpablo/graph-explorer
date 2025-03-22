package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.formats.dot.ColorType
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single

package object views:
  val colorRowOptions: Seq[RowOption] =
    ColorType.x11BasicColors.toSeq
      .sortBy(_._2)(Ordering.String.reverse)
      .map: (name, hex) =>
        RowOption(
          name,
          Single(AttrValue(hex)),
          Some(() => div(cls := s"w-8 h-4 rounded border-1 border-solid", styleAttr := s"background-color: $hex"))
        )

