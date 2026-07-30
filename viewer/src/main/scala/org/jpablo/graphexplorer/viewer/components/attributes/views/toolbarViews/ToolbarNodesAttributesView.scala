package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.Command
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputElement, SectionHeader}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.views.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.AttributeUpdates
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.Icons.bigXIcon
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, number, range}
import org.jpablo.graphexplorer.viewer.widgets.*

def ToolbarNodesAttributesView(
    state:           ViewerState,
    resetAttributes: Command[Nothing],
    updates:         Var[AttributeUpdates]
) =
  val multiSelection = state.selection.signal.map(_.size != 1)

  val builder = RowBuilder(updates, state.graphLayout)
  import builder.{row, rows}

  val labelRow = row(Label, InputType.multiText(), onReset = Some("")).copy(hidden = multiSelection)

  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  val shapeRow = row(Shape, InputType.currentValueWithSelector(cardClass = Some("narrow-card")))
    .copy(options = shapesOptions)
  val shapeIsNotPolygon = shapeRow.inputVar.signal.map(_.exists(_.toString != Shape.polygon.toString))
  val shapeIsPlainOrPlainText = shapeRow.inputVar.signal.map(_.exists { shape =>
    shape.toString == Shape.plain.toString || shape.toString == Shape.plaintext.toString
  })

  val sidesRow =
    row(
      attr = Sides,
      inputType = number(start = Some(3), end = Some(10), step = Some(1)),
      hidden = Some(
        builder.invalidLayout(Sides) || shapeIsNotPolygon
      )
    )

  // Clustered by what the controls DO — geometry, then paint, then text — so the bar has
  // three landmarks instead of eight equally-weighted blocks. The separators come from the
  // section breaks; nothing else draws a rule.
  HorizontalAttributesView(
    rows = rows(
      SectionHeader("Shape"),
      shapeRow,
      row(CornerStyle, InputType.dropdown).copy(options = cornerStyleOptions),
      SectionHeader("Paint"),
      row(FillColor, InputType.currentValueWithSelector())
        .copy(
          options = lightRows11 ++ colorOptions,
          hidden = builder.invalidLayout(FillColor),
          missingRowOption = Some(missingColorHandler),
          triggerGlyph = Some(() => i(cls := "bi bi-paint-bucket"))
        ),
      row(Color, InputType.currentValueWithSelector()).copy(
        options = mediumRows11 ++ colorOptions,
        hidden = shapeIsPlainOrPlainText,
        missingRowOption = Some(missingColorHandler),
        triggerGlyph = Some(() => i(cls := "bi bi-square"))
      ),
      InputElement(
        VerticalCardWithPreview(
          builder,
          id = "nodes-border-attributes",
          row(BorderStyle, InputType.menuWithExtra(3)).copy(options = borderStyleOptions),
          row(PenWidth, range(start = Some(0.1), end = Some(4), step = Some(0.25)))
        ),
        hidden = shapeIsPlainOrPlainText
      ),
      SectionHeader("Text"),
      row(NodeLabelLoc, InputType.dropdown).copy(
        options = nodeLabelVerticalAlignOptions,
        hidden = labelRelatedHidden
      ),
      row(FontColor, InputType.currentValueWithSelector(MenuDirection.end)).copy(
        options = mediumRows11 ++ colorOptions,
        hidden = labelRelatedHidden,
        missingRowOption = Some(missingColorHandler),
        triggerGlyph = Some(() => span(cls := "gx-glyph-a", "A"))
      ),
      InputElement(
        VerticalCardWithPreview(
          builder,
          id = "nodes-font-attributes",
          // A menu, not a native <select>. A select opens a picker the browser owns, and
          // that picker takes focus out of this card -- which daisyUI keeps open only while
          // focus is inside it, so reaching for a font shut the card you were reaching from.
          row(FontName, InputType.dropdown).copy(hidden = labelRelatedHidden),
          row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
        ),
        hidden = shapeIsPlainOrPlainText
      ),
      // --- Advanced or extra attributes ---
      // "extra" named the mechanism rather than its contents, and was the only control in
      // the bar whose label told you nothing. An overflow glyph says the same thing in the
      // width of an icon.
      InputElement(
        VerticalCardWithButton(
          id = "extra-node-attributes",
          i(cls := "bi-three-dots", title := "More attributes"),
          rows(
            InvisibleStyle -> checkbox,
            row(XLabel, InputType.text),
            sidesRow,
            Regular     -> checkbox,
            Orientation -> range(start = Some(0), end = Some(360), step = Some(1)),
            Peripheries -> number(start = Some(1), end = Some(10), step = Some(1)),
            FixedSize   -> checkbox,
            Width       -> range(start = Some(Width.min), end = Some(5), step = Some(.1)),
            Height      -> range(start = Some(Height.min), end = Some(5), step = Some(.1)),
            row(URL, InputType.text)
          )
        )
      ),
      // Reset-everything sits at the far end, not first. Leading the bar with a destructive
      // action put it where the eye lands and where a control belongs; the per-attribute
      // dots handle the common case of undoing one thing.
      SectionHeader("Reset"),
      InputElement(
        Button(
          title := "Reset all attributes",
          span().bigXIcon,
          onClick --> resetAttributes.execute()
        ).tiny.ghost
      )
    )
  )
