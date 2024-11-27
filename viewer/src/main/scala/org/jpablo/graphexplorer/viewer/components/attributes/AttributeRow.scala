package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.{DotAttribute, DotAttributeEnum, DotAttributeSimple}
import org.jpablo.graphexplorer.viewer.widgets.InputType

case class AttributeRow(
    attrId:          String,
    label:           String,
    placeholderText: String,
    inputType:       InputType,
    inputValue:      Var[Option[String]] = Var(None),
    options:         Seq[(String, AttrValue)] = Seq.empty,
    default:         String = ""
)

object AttributeRow:
  def attributeRow(attr: DotAttribute[?], inputType: InputType) =
    AttributeRow(
      attrId          = attr.attrId,
      label           = attr.label,
      placeholderText = attr.placeholderText,
      inputType       = inputType,
      options         = attr.values.map(v => (v.toString, v.toString)).toSeq,
      default         = attr.default.toString
    )

  def buildRows(attrs: DotAttribute[?] | (DotAttribute[?], InputType)*): Seq[AttributeRow] =
    attrs.map:
      case (attr: DotAttribute[?], it: InputType) => attributeRow(attr, it)
      case attr: DotAttributeEnum[?]              => attributeRow(attr, InputType.select)
      case attr: DotAttributeSimple[?]            => attributeRow(attr, InputType.text)
