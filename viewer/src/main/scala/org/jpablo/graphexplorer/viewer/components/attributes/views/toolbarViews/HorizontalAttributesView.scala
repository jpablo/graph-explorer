package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.*
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
      row match
        case ia: InputAttribute => fieldSet(cls := "fieldset", AttributesViewRow(ia))
        case ie: InputElement   => fieldSet(cls := "fieldset", ie.element)
        // A section break IS the cluster boundary here: one rule between groups of
        // related controls, instead of the rule after every single one that made six
        // controls read as six equal blocks.
        case sh: SectionHeader =>
          div(cls := "attr-cluster-sep", role := "separator", title := sh.title)
    ) <-- row.hidden.not

/** One attribute, as a toolbar control.
  *
  * The value IS the label here. Every control in this bar already shows its own state — a
  * swatch for a colour, a preview for a shape, "Times New Roman 14" for a font — so the
  * caption beside it was a second, wordier copy of what the control already said, and it
  * cost the width: "Vertical alignment" ran about eight times the width of the icon it
  * captioned, in the one place where horizontal space is scarcest. Names move to tooltips,
  * where they cost nothing.
  *
  * A checkbox is the exception and keeps its words: an unlabelled tick box states nothing.
  */
private def AttributesViewRow(row: InputAttribute) =
  row.inputType match
    case InputType.checkbox =>
      Seq(
        label(
          cls := "fieldset-label fieldset-input",
          span(row.label),
          buildInputCell(row)
        )
      )

    case _ =>
      Seq(
        div(
          cls   := "attr-control",
          title := row.label,
          buildInputCell(row),
          changedMarker(row)
        )
      )

/** Marks an attribute that differs from its default, and undoes it.
  *
  * "Which of these have I actually set?" is the question a properties bar exists to
  * answer, and this one could not: every control looked identical whether it carried a
  * value or a default. The side panel has always known — it bolds those labels — so the
  * fact was computed and thrown away here.
  *
  * The marker itself is [[ResetMarker]], shared with the panel and card rows: one symbol
  * for "reset this attribute", in all three places.
  */
private def changedMarker(row: InputAttribute) =
  child.maybe <-- row.isChanged.map: changed =>
    Option.when(changed)(ResetMarker(row))

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
