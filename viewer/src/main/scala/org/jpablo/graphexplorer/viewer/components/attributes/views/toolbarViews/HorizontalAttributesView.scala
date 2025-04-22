package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, InputElement}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Missing, Multiple}
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.InputType.number

def HorizontalAttributesView(
    rows:  Seq[AttributeRow],
    extra: Seq[AttributeRow] = Seq.empty
) =
  div(
    cls := "horizontal-attributes-view",
    buildFieldSets(rows)
  )

private def buildFieldSets(rows: Seq[AttributeRow]) =
  for row <- rows
  yield
    child(
      fieldSet(
        cls := "fieldset",
        row match
          case ia: InputAttribute => AttributesViewRow(ia)
          case ie: InputElement   => ie.element
      )
    ) <-- row.hidden.not

private def AttributesViewRow(row: InputAttribute) =
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
    cls := "flex items-center justify-start text-nowrap",
    div(cls("font-bold") <-- row.isChanged, row.label),
    div(
      cls("w-3 flex items-center justify-center") <-- multipleValues.combineWithFn(row.isChanged)(_ || _),
      child(span(title := s"Multiple values", i(cls := "bi bi-exclamation-triangle text-warning"))) <-- multipleValues,
      child(
        a(
          cls   := "btn btn-xs btn-circle btn-ghost ml-[1px] w-4 h-4",
          title := s"reset ${row.label}",
          i(cls := "bi bi-x text-[.6rem] text-base-content/50"),
          onClick --> row.inputVar.set(Missing)
        )
      ) <-- row.isChanged
    )
  )

private def buildInputCell(row: InputAttribute) =
  row.inputType match
    case InputType.menuWithExtra(initial, dir, cardClass)   => MenuWithExtraDropdown(row, initial, dir, cardClass)
    case InputType.currentValueWithSelector(dir, cardClass) => DropdownWithCurrentValue(row, dir, cardClass)
    case InputType.dropdown                                 => DropdownForRow(row)
    case InputType.select                                   => SelectWithValue(row).amend(cls := "ml-1")
    case InputType.checkbox                                 => Checked(row)
    case InputType.multiText(setFocus)                      => TextAreaWithValue(row, setFocus = setFocus)
    case _: number                                          => InputWithValue(row).amend(cls := "horizontal-attribute-input no-outline")
    case _                                                  => InputWithValue(row)
