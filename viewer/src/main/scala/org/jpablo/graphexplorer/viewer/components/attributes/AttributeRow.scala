package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.{DotAttribute, DotAttributeEnum, DotAttributeSimple}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.models.{AttributeId, Attributes, AttributesUpdates, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.widgets.InputType

enum AttributeRow:
  case AttributeHeader(title: String)

  case InputAttribute(
      attrId:      AttributeId,
      label:       String,
      placeholder: String,
      inputType:   InputType,
      inputVar:    Var[SelectionAttrValue],
      options:     Seq[AttributeRow.RowOption] = Seq.empty,
      default:     Signal[String]
  )

object AttributeRow:
  case class RowOption(
      name:    String,
      value:   SelectionAttrValue,
      preview: Option[() => ReactiveSvgElement[dom.SVGSVGElement]] = None
  ):
    def hasValue(s: String) =
      value.exists(_.toString == s)

import AttributeRow.*

class RowBuilder(
    updates:  Var[AttributesUpdates],
    defaults: Option[Signal[Attributes]] = None
):

  def buildRows(
      dotAttributes: DotAttribute[?]
        | String
        | AttributeRow
        | (DotAttribute[?], InputType)*
  ): Seq[AttributeRow] =
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
    updates.zoomLazy(_.attrs.getOrElse(attrId, Missing))((attrs, value) =>
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
