package org.jpablo.graphexplorer.viewer.components.attributes.rows

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveElement
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Layout
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.models.{AttributeId, AttrValueWithStatus}
import org.jpablo.graphexplorer.viewer.widgets.InputType

sealed trait AttributeRow

object AttributeRow:
  case class AttributeHeader(title: String) extends AttributeRow

  case class InputAttribute(
      attrId:       AttributeId,
      label:        String,
      placeholder:  String,
      inputType:    InputType,
      inputVar:     Var[AttrValueWithStatus],
      options:      Seq[AttributeRow.RowOption] = Seq.empty,
      default:      Signal[String],
      validLayouts: Set[Layout],
      hidden:       Signal[Boolean],
      singleRow:    Boolean = false
  ) extends AttributeRow

  def _combineDefault(row: InputAttribute): Signal[(AttrValueWithStatus, String)] =
    row.inputVar.signal.combineWith(row.default)

  extension (row: InputAttribute)

    def combineDefault = _combineDefault(row)

    def isChanged: Signal[Boolean] =
      row.combineDefault.map { (attr, d) => attr.exists(_.toString != d) }
      
    def isSelected(rowOption: RowOption): Signal[Boolean] =
      row.combineDefault.map((sv, d) => rowOption.hasValue(sv.getOrElse(d).toString))

    def combineDefaultString: Signal[String] =
      row.combineDefault.map((v, d) => v.getOrElse(d).toString)

    def combineDefaultBoolean: Signal[Boolean] =
      row.combineDefaultString.map(_ == true.toString)

  case class RowOption(
      name:    String,
      value:   AttrValueWithStatus,
      elem: Option[() => ReactiveElement[dom.Element]] = None
  ):
    def hasValue(s: String) =
      value.exists(_.toString == s)
