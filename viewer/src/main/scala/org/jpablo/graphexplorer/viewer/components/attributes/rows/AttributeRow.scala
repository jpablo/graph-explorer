package org.jpablo.graphexplorer.viewer.components.attributes.rows

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveElement
import com.raquo.laminar.nodes.ReactiveElement.Base
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrEq, AttrValue}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Layout
import org.jpablo.graphexplorer.viewer.models.{AttrStatus, AttrValueWithStatus, AttributeId}
import org.jpablo.graphexplorer.viewer.widgets.InputType

sealed trait AttributeRow:
  def hidden: Signal[Boolean]

object AttributeRow:
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
      missingRowOption: Option[String => ReactiveElement.Base] = None,
      // Shown after a numeric value. Graphviz measures pad/ranksep/nodesep in inches,
      // which the panel never said — a bare "0.5" gives no way to guess what one unit
      // of separation is.
      unit: Option[String] = None,
      // Identity glyph for the dropdown TRIGGER (the Docs/Canva idiom): a symbol naming
      // WHAT the row affects, drawn over a bar showing the current color. Without it,
      // three color rows in a toolbar are three anonymous circles — you had to hover to
      // learn which one paints the fill, the border, or the text.
      triggerGlyph: Option[() => ReactiveElement.Base] = None
  ) extends AttributeRow

  case class InputElement(element: ReactiveElement.Base, hidden: Signal[Boolean] = Signal.fromValue(false)) extends AttributeRow

  /** A named break in a list of rows: everything after it belongs to this section, until the
    * next one. Purely presentational — it carries no attribute — but it lives in the row list
    * because that is where the grouping decision belongs: beside the attributes it groups,
    * readable as one block, rather than in the view that happens to draw it.
    *
    * Its own `hidden` is derived, not given: a section disappears when every row it owns is
    * hidden, so a group whose attributes are all inapplicable leaves no dangling heading.
    */
  case class SectionHeader(title: String, hidden: Signal[Boolean] = Signal.fromValue(false)) extends AttributeRow

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

    /*
     * This method is used to determine if the current value of the attribute is
     * one of the options in the list. If it is, it returns the corresponding
     * element. If not, it returns a span with the current value.
     *
     * A matching option with no preview shows its NAME, never its raw DOT value. The
     * menu offers "South East"; a trigger reading back "se" makes the reader translate
     * between the two vocabularies, and the raw value is the one they did not choose.
     * Only a value matching NO option falls back to printing itself.
     */
    def selectedOption: Signal[ReactiveElement.Base] =
      row.combineDefaultString.map: attrValueStr =>
        row.options
          .find(_.value.toString == attrValueStr)
          .map(option => option.elem.fold(span(option.name))(_()))
          .orElse(row.missingRowOption.map(_(attrValueStr)))
          .getOrElse(span(attrValueStr))

  case class RowOption(
      name:  String,
      value: AttrValueWithStatus,
      elem:  Option[() => ReactiveElement.Base] = None
  ):
    def hasValue(s: String) =
      value.exists(_.toString == s)
