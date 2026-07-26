package org.jpablo.graphexplorer.viewer.components.attributes.rows

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder.{inputRow, simpleInputVar}
import org.jpablo.graphexplorer.viewer.extensions.notIn
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{DotAttribute, DotAttributeEnum, DotAttributeSimple, Layout}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.models.{AttributeId, AttributeUpdates, AttrValueWithStatus}
import org.jpablo.graphexplorer.viewer.widgets.InputType

class RowBuilder(
    updates:  Var[AttributeUpdates],
    layout:   Signal[Layout]
):
  private type BuildRowsInput = DotAttribute[?]
    | AttributeRow
    | (DotAttribute[?], InputType)

  given CanEqual[BuildRowsInput, BuildRowsInput] = CanEqual.derived

  def rows(dotAttributes: BuildRowsInput*): Seq[AttributeRow] =
    dotAttributes.map:
      case row: AttributeRow => row

      case dotAttr: (DotAttribute[?] | (DotAttribute[?], InputType)) =>
        val (attr, inputType) =
          dotAttr match
            case attr: DotAttributeEnum[?]              => (attr, InputType.select)
            case attr: DotAttributeSimple[?]            => (attr, InputType.text)
            case (attr: DotAttribute[?], it: InputType) => (attr, it)
        row(attr, inputType)

  def invalidLayout(attr: DotAttribute[?]): Signal[Boolean] =
    layout.map(_ notIn attr.validLayouts)

  def row(
      attr:        DotAttribute[?],
      inputType:   InputType,
      onReset:     Option[String] = None,
      label:       Option[String] = None,
      placeholder: Option[String] = None,
      hidden:      Option[Signal[Boolean]] = None,
      unit:        Option[String] = None
  ): AttributeRow.InputAttribute =
    inputRow(
      attr = attr -> inputType,
      inputVar = simpleInputVar(attr.attrId, updates, onReset),
      default = Signal.fromValue(attr.default.toString),
      label = label,
      placeholder = placeholder,
      hidden = hidden.orElse(Some(invalidLayout(attr))),
      unit = unit
    )

object RowBuilder:

  def simpleInputVar(
      attrId:  AttributeId,
      updates: Var[AttributeUpdates],
      onReset: Option[String] = None
  ): Var[AttrValueWithStatus] =
    updates.zoomLazy(_.statuses.getOrElse(attrId, Missing)): (attrs, value) =>
      attrs + (attrId -> (
        value match
          case Missing => onReset.fold(value)(v => Single(AttrValue(v)))
          case _       => value
      ))

  def inputRow(
      attr:        (DotAttribute[?], InputType),
      inputVar:    Var[AttrValueWithStatus],
      default:     Signal[String],
      label:       Option[String] = None,
      placeholder: Option[String] = None,
      hidden:      Option[Signal[Boolean]] = None,
      unit:        Option[String] = None
  ): InputAttribute =
    attr match
      case (attr: DotAttribute[?], it: InputType) =>
        InputAttribute(
          attrId = attr.attrId,
          unit = unit,
          label = label.getOrElse(attr.label),
          placeholder = placeholder.getOrElse(attr.placeholderText),
          inputType = it,
          inputVar = inputVar,
          options = attr.valuesWithLabel.map((l, v) => RowOption(l, Single(AttrValue(v.toString)), None)).toSeq,
          default = default,
          validLayouts = attr.validLayouts,
          hidden = hidden.getOrElse(Signal.fromValue(false))
        )
