package org.jpablo.graphexplorer.viewer.components.attributes.rows

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.*
import AttributeRow.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.models.{AttributeId, Attributes, AttributesUpdates, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.widgets.InputType

class RowBuilder(
    updates:  Var[AttributesUpdates],
    defaults: Option[Signal[Attributes]] = None
):
  type buildRowsInput = DotAttribute[?]
    | String
    | AttributeRow
    | (DotAttribute[?], InputType)

  given CanEqual[buildRowsInput, buildRowsInput] = CanEqual.derived

  def buildRows(dotAttributes: buildRowsInput*): Seq[AttributeRow] =
    dotAttributes
      .filter:
        case "" => false
        case _  => true
      .map:
        case s: String         => AttributeHeader(s)
        case row: AttributeRow => row

        case dotAttr: (DotAttribute[?] | (DotAttribute[?], InputType)) =>
          val (attr, inputType) =
            dotAttr match
              case attr: DotAttributeEnum[?]              => (attr, InputType.select)
              case attr: DotAttributeSimple[?]            => (attr, InputType.text)
              case (attr: DotAttribute[?], it: InputType) => (attr, it)
          simpleRow(attr, inputType)

  def simpleRow(
      attr:        DotAttribute[?],
      inputType:   InputType,
      onReset:     Option[String] = None,
      label:       Option[String] = None,
      placeholder: Option[String] = None
  ): AttributeRow.InputAttribute =
    inputRow(
      attr        = attr -> inputType,
      inputVar    = simpleInputVar(attr.attrId, updates, onReset),
      default     = defaultValue(attr.attrId, attr.default.toString),
      label       = label,
      placeholder = placeholder
    )

  def inputRow(
      attr:        (DotAttribute[?], InputType),
      inputVar:    Var[SelectionAttrValue],
      default:     Signal[String],
      label:       Option[String] = None,
      placeholder: Option[String] = None
  ): InputAttribute =
    attr match
      case (attr: DotAttribute[?], it: InputType) =>
        InputAttribute(
          attrId      = attr.attrId,
          label       = label.getOrElse(attr.label),
          placeholder = placeholder.getOrElse(attr.placeholderText),
          inputType   = it,
          inputVar    = inputVar,
          options     = attr.valuesWithLabel.map((l, v) => RowOption(l, Single(AttrValue(v.toString)), None)).toSeq,
          default     = default
        )

  def simpleInputVar(
      attrId:  AttributeId,
      updates: Var[AttributesUpdates],
      onReset: Option[String] = None
  ): Var[SelectionAttrValue] =
    updates.zoomLazy(_.existing.getOrElse(attrId, Missing))((attrs, value) =>
      value match
        case Single(selection) => attrs + (attrId -> selection)
        case Multiple          => attrs
        case Missing           => onReset.fold(attrs - attrId)(v => attrs + (attrId -> AttrValue(v)))
    )

  // uses the global default if present, otherwise uses the (hardcoded) default value.
  def defaultValue(attrId: AttributeId, default: String): Signal[String] =
    defaults
      .map(_.map(_.get(attrId).map(_.toString).getOrElse(default)))
      .getOrElse(Signal.fromValue(default))
