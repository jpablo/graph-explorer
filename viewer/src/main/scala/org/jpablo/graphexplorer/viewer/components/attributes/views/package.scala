package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.color
import org.jpablo.graphexplorer.viewer.color.ColorFormat.toHex
import org.jpablo.graphexplorer.viewer.color.{ColorFormat, TailWindColors}
import org.jpablo.graphexplorer.viewer.components.attributes.previews.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.color.TailWindColors.ColorName
import org.jpablo.graphexplorer.viewer.color.TailWindColors.ColorName.*

import scala.collection.immutable.VectorMap

package object views:
  val twColorOptions: VectorMap[String, RowOption] =
    TailWindColors.rgbColors.transform(rowOption)

  private def rowOption(name: String, rgb: ColorFormat.RGB) =
    // Special "invisible" color
    val (cssColor, dotColor) =
      if name == "none" then ("unset", "none") else (s"rgb(${rgb.r} ${rgb.g} ${rgb.b})", toHex(rgb).value)
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

  private val noneRow = rowOption("none", ColorFormat.RGB(0, 0, 0))

  private val basic7 = List(red, yellow, green, blue, indigo, sky, slate)
  private val basic4 = List(red, yellow, green, blue)

  val mediumRows7 = noneRow :: basic7.map(c => s"$c-500").map(twColorOptions)
  val lightRows7  = noneRow :: basic7.map(c => s"$c-200").map(twColorOptions)
  val mediumRows4 = noneRow :: basic4.map(c => s"$c-500").map(twColorOptions)
  val lightRows4  = noneRow :: basic4.map(c => s"$c-200").map(twColorOptions)

  val colorOptions = twColorOptions.values.toSeq

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
  val clusterVerticalAlignmentOptions =
    ClusterLabelLoc.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), Some(() => i(cls := s"bi ${groupVAlignIcons(style)}")))

  val horizontalAlignmentOptions =
    LabelJust.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), Some(() => i(cls := s"bi ${hAlignIcons(style)}")))

  def buildDirectedVar(graphType: Var[GraphType]): Var[AttrStatus[AttrValue]] =
    graphType.zoomLazy(tpe =>
      AttrStatus.Single(AttrValue((tpe == GraphType.digraph).toString))
    ): (_, status) =>
      status match
        case AttrStatus.Single(value) => if value.isTrue then GraphType.digraph else GraphType.graph
        case AttrStatus.Multiple      => GraphType.default
        case AttrStatus.Missing       => GraphType.default

  // ---- diagram attributes ----

  val vAlignIcons = Map(
    GroupLabelLoc.t -> "bi-align-top",
    GroupLabelLoc.b -> "bi-align-bottom"
  )

  val directionIcons = Map(
    Rankdir.TB -> "bi-arrow-down",
    Rankdir.LR -> "bi-arrow-right",
    Rankdir.BT -> "bi-arrow-up",
    Rankdir.RL -> "bi-arrow-left"
  )

  val directionOptions =
    Rankdir.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), Some(() => i(cls := s"bi ${directionIcons(style)}")))
