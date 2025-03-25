package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.previews.{
  ArrowPreview,
  ArrowStylePreview,
  BorderStylePreview,
  CornerPreview,
  ShapePreview
}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ColorType
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{
  ArrowType,
  BorderStyle,
  ClusterLabelLoc,
  CornerStyle,
  EdgeStyle,
  GroupLabelLoc,
  LabelJust,
  NodeLabelLoc,
  Shape
}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single

package object views:

  val colorOptions =
    ColorType.x11BasicColors
      .toSeq
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

  val shapesOptions =
    Shape.valuesWithLabel
      .filterNot((_, s) => s in Shape.synonyms).toSeq
      .map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), ShapePreview(style, 20, 20))

  val borderStyleOptions =
    BorderStyle.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), BorderStylePreview(style, 20))

  val cornerStyleOptions =
    CornerStyle.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), CornerPreview(style))

  val nodeLabelLocIcons = Map(
    NodeLabelLoc.t -> "bi-align-top",
    NodeLabelLoc.c -> "bi-align-middle",
    NodeLabelLoc.b -> "bi-align-bottom"
  )

  val nodeLabelVerticalAlignOptions =
    NodeLabelLoc.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), Some(() => i(cls := s"bi ${nodeLabelLocIcons(style)}")))

  val arrowStyleOptions =
    EdgeStyle.valuesWithLabel.toSeq.map: (label, style) =>
      RowOption(label, Single(AttrValue(style.toString)), ArrowStylePreview(style, 20))

  val arrowTypeOptions =
    ArrowType.values.toSeq.map: arrowType =>
      RowOption(arrowType.toString, Single(AttrValue(arrowType.toString)), ArrowPreview(arrowType, 20))

  val validCornerStyle = Set(CornerStyle.normal, CornerStyle.rounded)
  
  val graphCornerStyleOptions = CornerStyle.valuesWithLabel.filter((_, s) => validCornerStyle(s))
    .toSeq.map: (label, style) =>
      RowOption(label, Single(AttrValue(style.toString)), CornerPreview(style))

  val groupVAlignIcons = Map(
    GroupLabelLoc.t -> "bi-align-top",
    GroupLabelLoc.b -> "bi-align-bottom"
  )
  val hAlignIcons = Map(
    LabelJust.l -> "bi-align-start",
    LabelJust.c -> "bi-align-center",
    LabelJust.r -> "bi-align-end"
  )
  val verticalAlignmentOptions =
    ClusterLabelLoc.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), Some(() => i(cls := s"bi ${groupVAlignIcons(style)}")))

  val horizontalAlignmentOptions =
    LabelJust.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), Some(() => i(cls := s"bi ${hAlignIcons(style)}")))
