package org.jpablo.graphexplorer.viewer.components.attributes.rows

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.models.{AttributeId, SelectionAttrValue}
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
  extension (row: InputAttribute)
    def isChanged =
      row.inputVar.signal.combineWith(row.default).map { (attr, d) => attr.exists(_.toString != d) }


  case class RowOption(
      name:    String,
      value:   SelectionAttrValue,
      preview: Option[() => ReactiveSvgElement[dom.SVGSVGElement]] = None
  ):
    def hasValue(s: String) =
      value.exists(_.toString == s)
