package org.jpablo.graphexplorer.viewer.components.attributes.rows

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveElement
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrEq, AttrValue}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Layout
import org.jpablo.graphexplorer.viewer.models.{AttrStatus, AttrValueWithStatus, AttributeId}
import org.jpablo.graphexplorer.viewer.widgets.InputType

sealed trait AttributeRow

object AttributeRow:
  case class AttributeHeader(title: String) extends AttributeRow

  case class InputAttribute(
      attrId:           AttributeId,
      label:            String,
      placeholder:      String,
      inputType:        InputType,
      inputVar:         Var[AttrValueWithStatus],
      options:          Seq[AttributeRow.RowOption] = Seq.empty,
      default:          Signal[String],
      validLayouts:     Set[Layout],
      hidden:           Signal[Boolean],
      singleRow:        Boolean = false,
      missingRowOption: Option[String => ReactiveElement.Base] = None
  ) extends AttributeRow

  def _combineDefault(row: InputAttribute): Signal[(AttrValueWithStatus, String)] =
    row.inputVar.signal.combineWith(row.default)

  val htmlRegex         = """<([a-zA-Z][a-zA-Z0-9]*)[^>]*>.*?</\1>""".r
  def isHtml(s: String) = htmlRegex.matches(s)

  /** Converts a Var[AttrValueWithStatus] to a String, escaping HTML entities.
    */
  def toRawText(inputVar: Var[AttrValueWithStatus], default: String) = inputVar
    .bimap(
      // DOT -> UI
      getThis = dotText => TextUtils.unescape(dotText.getOrElse(default).toString)
    )(
      // UI -> DOT
      getParent = uiText =>
        val dotText = TextUtils.escape(uiText)
        AttrStatus.Single(AttrValue(if isHtml(uiText) then AttrEq(dotText, true) else dotText))
    )

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
      name:  String,
      value: AttrValueWithStatus,
      elem:  Option[() => ReactiveElement.Base] = None
  ):
    def hasValue(s: String) =
      value.exists(_.toString == s)
