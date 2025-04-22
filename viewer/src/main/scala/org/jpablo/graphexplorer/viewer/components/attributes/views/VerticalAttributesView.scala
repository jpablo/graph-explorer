package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, InputElement}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Missing, Multiple}
import org.jpablo.graphexplorer.viewer.widgets.*

def VerticalAttributesView(
    id:    String,
    rows:  Seq[AttributeRow],
    extra: Seq[AttributeRow] = Seq.empty
) =
  // TODO: Finish implementing this
  //  def getFonts(): js.Dynamic = js.Dynamic.global.window.queryLocalFonts().`then`(x => dom.console.log(x))
  val extraVisible = Var(false)
  div(
    idAttr := id,
    cls    := "attributes-view",
    LabeledCheckbox(id + "extra-visible", "extra", extraVisible).amend(
      cls           := "flex justify-end mr-2",
      cls("hidden") := extra.isEmpty
    ),
    children(buildFieldSets(rows)) <-- extraVisible.signal.map(!_),
    children(buildFieldSets(extra)) <-- extraVisible.signal
  )

private def AttributesViewRow(attRow: AttributeRow) =
  attRow match
    case _: InputElement => Seq.empty
    case row: InputAttribute =>
      row.inputType match
        case InputType.multiText(_) =>
          Seq(
            label(cls := "fieldset-label", inputLabel(row)),
            div(cls   := "fieldset-input", buildInputCell(row))
          )

        case InputType.range(s, e, step) =>
          Seq(
            label(
              cls := "fieldset-label fieldset-input flex justify-between",
              inputLabel(row),
              buildInputCell(row.copy(inputType = InputType.number(s, e, step)))
                .amend(cls := "w-16 text-[.6rem] input-ghost")
            ),
            div(
              cls := "fieldset-input",
              buildInputCell(row)
            )
          )

        case InputType.checkbox =>
          Seq(
            label(
              cls := "fieldset-label fieldset-input",
              span(row.label),
              buildInputCell(row)
            )
          )

        case _ =>
          if row.singleRow then
            Seq(
              label(
                cls := "fieldset-label fieldset-input flex justify-between",
                inputLabel(row),
                buildInputCell(row).amend(cls := "w-40")
              )
            )
          else
            Seq(
              label(cls := "fieldset-label", inputLabel(row)),
              div(cls   := "fieldset-input", buildInputCell(row))
            )

private def inputLabel(row: InputAttribute): Div =
  val multipleValues = row.inputVar.signal.map(_ == Multiple)
  div(
    cls := "flex items-center justify-start",
    div(cls("font-bold") <-- row.isChanged, row.label),
    div(
      cls("w-6 flex items-center justify-center") <-- multipleValues.combineWithFn(row.isChanged)(_ || _),
      child(span(title := s"Multiple values", i(cls := "bi bi-exclamation-triangle text-warning"))) <-- multipleValues,
      child(a(title := s"reset ${row.label}", onClick --> row.inputVar.set(Missing), i(cls := "bi bi-x")).tiny) <-- row.isChanged
    )
  )

def buildFieldSets(rows: Seq[AttributeRow]) =
  for
    row <- rows.collect { case ia: InputAttribute => ia }
  yield
    fieldSet(
      cls := "fieldset",
      AttributesViewRow(row).map(_.amend(cls("hidden") <-- row.hidden))
    )

private def buildInputCell(row: InputAttribute) =
  row.inputType match
    case InputType.menuWithExtra(initial, dir, cardClass) => MenuWithExtraDropdown(row, initial, dir, cardClass)
    case InputType.select                                 => SelectWithValue(row)
    case InputType.checkbox                               => Checked(row)
    case InputType.multiText(setFocus)                    => TextAreaWithValue(row, setFocus = setFocus)
    case _                                                => InputWithValue(row)
