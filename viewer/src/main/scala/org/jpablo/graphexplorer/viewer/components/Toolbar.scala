package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.leftPanel.CommandsPanel
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.models.{AttributeId, AttrStatus, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue

def Toolbar(projectName: Signal[String], commands: Commands, state: ViewerState) =
  val hiddenNodesIsEmpty =
    state.hiddenElements.signal.map(_.isEmpty)

  // Shape selector setup
  val boxValue = AttrValue("box")
  val circleValue = AttrValue("circle")
  val diamondValue = AttrValue("diamond")

  val shapeVar = Var[SelectionAttrValue](AttrStatus.Single(boxValue))
  val shapeOptions = Seq(
    RowOption("Rectangle", AttrStatus.Single(boxValue), Some(() => div(cls := "w-5 h-4 border border-base-content"))),
    RowOption(
      "Circle",
      AttrStatus.Single(circleValue),
      Some(() => div(cls := "w-4 h-4 rounded-full border border-base-content"))
    ),
    RowOption(
      "Diamond",
      AttrStatus.Single(diamondValue),
      Some(() => div(cls := "w-5 h-4 rotate-45 border border-base-content"))
    )
  )

  val shapeRow = InputAttribute(
    attrId       = AttributeId("shape"),
    label        = "Shape",
    placeholder  = "Select a shape",
    inputType    = InputType.text,
    inputVar     = shapeVar,
    options      = shapeOptions,
    default      = Signal.fromValue("box"),
    validLayouts = Set.empty,
    hidden       = Signal.fromValue(false)
  )

  div(
    idAttr := "toolbar",
    cls    := "floating-toolbar",
    // -------- Navigation --------
    div(
      cls := "breadcrumbs text-md py-0",
      ul(
        li(
          a(cls := "link", title := "Home", span().houseIcon, onClick --> commands.navigateHome.action())
        ),
        li(
          a(
            cls   := "link",
            title := "Change title",
            text <-- projectName,
            onClick --> commands.changeProjectName.action()
          )
        )
      )
    ),
    span(cls := "divider divider-horizontal mx-0"),
    // -------- new node button --------
    Button(span().biSquareIcon, onClick --> commands.addNode.action())
      .tiny.toTooltip(commands.addNode.titleWithShortcut),
    // -------- node shape selector --------
    SelectWithPreview(shapeRow),
    // Only trigger when shape selection changes
    shapeVar.signal.changes --> { shape =>
      state.addNodeWithSmartConnection(shape.toOption)
    },
    // -------- show all --------
    Button(
      commands.showAll.title,
      cls := "btn-primary",
      disabled <-- hiddenNodesIsEmpty,
      onClick --> commands.showAll.action()
    ).tiny.toTooltip(commands.showAll.titleWithShortcut),
    // -------- actions toolbar --------
    Dropdown(
      placeholderText = "Copy as",
      options         = commands.sections.exportAs.map(cmd => cmd.title -> cmd.action),
      onClickHandler  = _ --> (action => action())
    ),
    Dropdown(
      placeholderText = "Examples",
      options         = examples.toSeq,
      onClickHandler =
        _.flatMap(FetchStream.get(_)) --> { source =>
          state.showAllNodes()
          state.sourceText.set(source)
        }
    ),
    CommandsPanel(state, commands),
    // ---------- Undo/Redo ----------
    Join(
      Button(
        span(cls := "bi bi-arrow-counterclockwise").toTooltip(commands.undo.titleWithShortcut),
        onClick --> commands.undo.action()
      ).tiny,
      Button(
        span(cls := "bi bi-arrow-clockwise").toTooltip(commands.redo.titleWithShortcut),
        onClick --> commands.redo.action()
      ).tiny
    ),
    Join(
      Button(
        span(cls := "bi bi-question-circle").toTooltip(commands.keyboardShortcuts.titleWithShortcut),
        onClick --> commands.keyboardShortcuts.action()
      ).tiny,
      a(
        cls    := "btn btn-xs",
        href   := "https://github.com/jpablo/graph-explorer/tree/viewer",
        target := "_blank",
        i(cls := "bi bi-github")
      )
    )
  )
