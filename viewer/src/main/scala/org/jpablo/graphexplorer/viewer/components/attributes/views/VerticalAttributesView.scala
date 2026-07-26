package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, InputElement, SectionHeader}
import org.jpablo.graphexplorer.viewer.widgets.*

def VerticalCardWithPreview(builder: RowBuilder, id: String, iAttrs: InputAttribute*) =
  DropdownHeader(
    div(
      cls := "flex gap-4 bg-base-100 rounded-box px-3",
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
    LabeledCheckboxFormControl(id + "extra-visible", "extra", extraVisible).amend(
      cls           := "flex justify-end mr-2",
      cls("hidden") := extra.isEmpty
    ),
    children(buildFieldSets(rows)) <-- extraVisible.signal.map(!_),
    children(buildFieldSets(extra)) <-- extraVisible.signal
  )

/** One attribute, as a row.
  *
  * The default is two columns — label left, control right, on ONE line. Stacking the label
  * above its control doubled the height of every row, which is what pushed a twelve-control
  * panel into scrolling; a single column of left-aligned labels is also the thing the eye
  * scans when hunting for a control.
  *
  * Only a multi-line text box stacks, because it needs the panel's full width to be usable.
  */
private def AttributesViewRow(attRow: AttributeRow) =
  attRow match
    case _: InputElement  => Seq.empty
    case _: SectionHeader => Seq.empty
    case row: InputAttribute =>
      row.inputType match
        case InputType.multiText(_) =>
          Seq(
            label(cls := "fieldset-label", InputLabelWithResetButton(row)),
            div(cls   := "fieldset-input", buildInputCell(row))
          )

        // A slider keeps its track, but the number beside it is the editable one: a track
        // alone cannot express "exactly 0.5", and the raw value is what people came to set.
        case InputType.range(s, e, step) =>
          Seq(
            label(
              cls := "attr-row",
              InputLabelWithResetButton(row),
              span(
                cls := "attr-value flex items-center gap-1",
                buildInputCell(row).amend(cls := "range-nano w-20"),
                buildInputCell(row.copy(inputType = InputType.number(s, e, step)))
                  .amend(cls := "w-12 text-[.6rem] input-ghost text-right")
              )
            )
          )

        case _ =>
          Seq(
            label(
              cls := "attr-row",
              InputLabelWithResetButton(row),
              span(cls := "attr-value", buildInputCell(row))
            )
          )

/** Renders a row list, honouring the section breaks in it.
  *
  * A SectionHeader owns every row up to the next one, and hides itself when all of them are
  * hidden — otherwise a heading like "Title" would sit over an empty gap whenever its
  * attributes did not apply to the current layout.
  */
def buildFieldSets(rows: Seq[AttributeRow]): Seq[HtmlElement] =
  val sections = splitIntoSections(rows)
  sections.flatMap: (header, sectionRows) =>
    val inputs = sectionRows.collect { case ia: InputAttribute => ia }
    val fieldSets =
      inputs.map: row =>
        fieldSet(
          cls := "fieldset",
          AttributesViewRow(row).map(_.amend(cls("hidden") <-- row.hidden))
        )
    header.toSeq.map(h => sectionHeading(h, inputs)) ++ fieldSets

private def sectionHeading(header: SectionHeader, rows: Seq[InputAttribute]) =
  val allHidden =
    if rows.isEmpty then Signal.fromValue(true)
    else Signal.combineSeq(rows.map(_.hidden)).map(_.forall(identity))
  div(
    cls := "attributes-section",
    cls("hidden") <-- allHidden,
    span(header.title)
  )

/** Groups rows by the section headers between them; rows before the first header (or in a
  * list with no headers at all) form one leading group with no heading.
  */
private def splitIntoSections(rows: Seq[AttributeRow]): Seq[(Option[SectionHeader], Seq[AttributeRow])] =
  val (leading, rest) = rows.span { case _: SectionHeader => false; case _ => true }
  val grouped =
    rest
      .foldLeft(List.empty[(Option[SectionHeader], List[AttributeRow])]):
        case (acc, h: SectionHeader) => (Some(h), Nil) :: acc
        case (Nil, row)              => (None, row :: Nil) :: Nil
        case ((h, rs) :: tail, row)  => (h, row :: rs) :: tail
      .map((h, rs) => (h, rs.reverse))
      .reverse
  if leading.isEmpty then grouped else (None, leading) +: grouped

private def buildInputCell(row: InputAttribute) =
  row.inputType match
    case InputType.menuWithExtra(initial, dir, cardClass) => MenuWithExtraDropdown(row, initial, dir, cardClass)
    case InputType.select                                 => SelectWithValue(row)
    case InputType.checkbox                               => Checked(row)
    case InputType.multiText(setFocus)                    => TextAreaWithValue(row, setFocus = setFocus)
    case _                                                => InputWithValue(row)
