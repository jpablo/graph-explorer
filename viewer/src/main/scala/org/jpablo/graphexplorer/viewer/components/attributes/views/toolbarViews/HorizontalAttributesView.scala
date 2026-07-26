package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, InputElement, SectionHeader}
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.InputType.number

def HorizontalAttributesView(rows: Seq[AttributeRow]) =
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
          // A section break has no horizontal equivalent yet: this bar already separates
          // its groups with a rule between fieldsets. When the toolbar starts clustering
          // by kind, this is where a header becomes that cluster boundary.
          case _: SectionHeader => emptyNode
      )
    ) <-- row.hidden.not

private def AttributesViewRow(row: InputAttribute) =
  row.inputType match
    case InputType.multiText(_) =>
      Seq(
        label(cls := "fieldset-label", InputLabelWithResetButton(row)),
        div(cls   := "fieldset-input", buildInputCell(row))
      )

    case InputType.range(s, e, step) =>
      Seq(
        label(
          cls := "fieldset-label fieldset-input flex justify-between",
          InputLabelWithResetButton(row),
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
            InputLabelWithResetButton(row),
            buildInputCell(row).amend(cls := "w-40")
          )
        )
      else
        Seq(
          label(cls := "fieldset-label", InputLabelWithResetButton(row)),
          div(cls   := "fieldset-input", buildInputCell(row))
        )

private def buildInputCell(row: InputAttribute) =
  row.inputType match
    case InputType.menuWithExtra(initial, dir, cardClass)         => MenuWithExtraDropdown(row, initial, dir, cardClass)
    case InputType.currentValueWithSelector(dir, cardClass, open) => DropdownWithCurrentValue(row, dir, cardClass, open)
    case InputType.dropdown                                       => DropdownForRow(row)
    case InputType.select                                         => SelectWithValue(row).amend(cls := "ml-1")
    case InputType.checkbox                                       => Checked(row)
    case InputType.multiText(setFocus)                            => TextAreaWithValue(row, setFocus = setFocus)
    case _: number => InputWithValue(row).amend(cls := "horizontal-attribute-input no-outline")
    case _         => InputWithValue(row)
