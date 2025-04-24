package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, InputElement}
import org.jpablo.graphexplorer.viewer.widgets.*

def VerticalCardWithPreview(builder: RowBuilder, id: String, iAttrs: InputAttribute*) =
  DropdownHeader(
    div(
      cls := "flex gap-4 bg-base-100 rounded-md px-3",
      iAttrs.map(ia => child <-- ia.selectedOption)
    ),
    body =
      div(
        cls := "card card-border card-xs bg-base-100 shadow-sm w-48",
        div(
          cls := "card-body",
          VerticalAttributesView(
            id = id,
            rows = builder.rows(iAttrs*)
          )
        )
      )
  )

def VerticalCardWithButton(id: String, title: String, iars: Seq[AttributeRow]) =
  DropdownHeader(
    title,
    body =
      div(
        cls := "card card-border card-xs bg-base-100 shadow-sm w-48",
        div(
          cls := "card-body",
          VerticalAttributesView(
            id = id,
            rows = iars
          )
        )
      )
  )

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
            label(cls := "fieldset-label", InputLabelWithResetButton(row)),
            div(cls   := "fieldset-input", buildInputCell(row))
          )

        case InputType.range(s, e, step) =>
          Seq(
            label(
              cls := "fieldset-label fieldset-input flex justify-between",
              InputLabelWithResetButton(row),
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
                InputLabelWithResetButton(row),
                buildInputCell(row).amend(cls := "w-40")
              )
            )
          else
            Seq(
              label(cls := "fieldset-label", InputLabelWithResetButton(row)),
              div(cls   := "fieldset-input", buildInputCell(row))
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
