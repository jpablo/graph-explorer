package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.{DotAttribute, DotAttributeEnum, DotAttributeSimple}
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.widgets.InputType

enum AttributeType:
  case AttributeHeader(title: String)

  case AttributeRow(
      attrId:          String,
      label:           String,
      placeholderText: String,
      inputType:       InputType,
      inputValue:      Var[Option[AttrValue]],
      options:         Seq[(String, AttrValue)] = Seq.empty,
      default:         String = ""
  )

import AttributeType.*

class RowBuilder(attrsVar: Var[Attributes]):

  def attributeRow(attr: DotAttribute[?], inputType: InputType, inputValue: Var[Option[AttrValue]]) =
    AttributeRow(
      attrId          = attr.attrId,
      label           = attr.label,
      placeholderText = attr.placeholderText,
      inputType       = inputType,
      inputValue      = inputValue,
      options         = attr.valuesWithLabel.map((l, v) => (l, AttrValue(v.toString))).toSeq,
      default         = attr.default.toString
    )

  def buildRows(
      dotAttributes: DotAttribute[?]
        | String
        | AttributeType
        | (DotAttribute[?], InputType)*
  ): Seq[AttributeType] =
    dotAttributes.map:
      case s: String         => AttributeHeader(s)
      case at: AttributeType => at

      case dotAttr: (DotAttribute[?] | (DotAttribute[?], InputType)) =>
        val (attr, inputType) =
          dotAttr match
            case attr: DotAttributeEnum[?]              => (attr, InputType.select)
            case attr: DotAttributeSimple[?]            => (attr, InputType.text)
            case (attr: DotAttribute[?], it: InputType) => (attr, it)
            
        buildRow(attr -> inputType, simpleInputVar(attr.attrId, attrsVar))

  def buildRow(
      attr:       (DotAttribute[?], InputType),
      inputValue: Var[Option[AttrValue]]
  ): AttributeType =
    attr match
      case (attr: DotAttribute[?], it: InputType) => attributeRow(attr, it, inputValue)

  private def simpleInputVar(attrId: String, attrsVar: Var[Attributes]): Var[Option[AttrValue]] =
    attrsVar.zoomLazy(_.values.get(attrId))((attrs, value) =>
      value match
        case None    => attrs - attrId // Remove override, will fall back to root value
        case Some(v) => attrs + (attrId -> v) // Set override
    )
