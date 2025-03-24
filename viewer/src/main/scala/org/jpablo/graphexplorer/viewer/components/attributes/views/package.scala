package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.formats.dot.ColorType
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single

package object views:
  val colorRowOptions =
    ColorType.x11BasicColors.toSeq
      .map: (name, hex) =>
        val cssColor = if name == "none" then "unset" else hex
        val dotColor = if name == "none" then "none" else hex
        RowOption(
          name = name,
          value = Single(AttrValue(dotColor)),
          elem =
            Some(() =>
              if name == "none" then
                div(cls := "w-5 h-5 mt-[-3px]", i(cls := "bi bi-ban", styleAttr := "font-size: 18px"))
              else
                div(
                  cls       := s"w-5 h-5 rounded-full border border-solid border-neutral",
                  styleAttr := s"background-color: $cssColor"
                )
            )
        )
