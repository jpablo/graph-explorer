package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.widgets.SimpleDialog
import org.jpablo.graphexplorer.viewer.utils.intersperse

def HelpDialog(open: Var[Boolean], commands: Commands) =
  SimpleDialog(
    open = open,
    contents = div(
      cls := "p-4 space-y-6",
      // Keyboard Shortcuts Section
      div(
        h3(cls := "text-lg font-bold mb-4", "Keyboard Shortcuts"),
        table(
          cls := "table table-xs min-w-full border-collapse",
          thead(
            tr(
              th("Key"),
              th("Action")
            )
          ),
          tbody(
            for
              (shortcut, command) <- commands.byShortcut.toSeq.sortBy(_._1.toList.mkString(""))
            yield tr(
              td(cls := "whitespace-nowrap", shortcut.toList.map(kbd(cls := "kbd kbd-sm", _)).intersperse(span(" + "))),
              td(command.description.getOrElse(command.shortLabel))
            )
          )
        )
      ),
      // Mouse Controls Section
      div(
        h3(cls := "text-lg font-bold mb-4", "Mouse Controls"),
        table(
          cls := "table table-xs min-w-full border-collapse",
          thead(
            tr(
              th("Action"),
              th("Description")
            )
          ),
          tbody(
            tr(
              td(
                kbd(cls := "kbd kbd-sm", "Click"),
                span(" + "),
                kbd(cls := "kbd kbd-sm", "Drag"),
                span(" between nodes")
              ),
              td("Create a new edge between nodes")
            ),
            tr(
              td(
                kbd(cls := "kbd kbd-sm", "⌘"),
                span(" + "),
                kbd(cls := "kbd kbd-sm", "Mouse wheel")
              ),
              td("Zoom in/out")
            ),
            tr(
              td(
                kbd(cls := "kbd kbd-sm", "Mouse wheel")
              ),
              td("Pan vertically")
            ),
            tr(
              td(
                kbd(cls := "kbd kbd-sm", "Shift"),
                span(" + "),
                kbd(cls := "kbd kbd-sm", "Mouse wheel")
              ),
              td("Pan horizontally")
            ),
            tr(
              td(
                kbd(cls := "kbd kbd-sm", "Click")
              ),
              td("Select element")
            ),
            tr(
              td(
                kbd(cls := "kbd kbd-sm", "Shift"),
                span(" + "),
                kbd(cls := "kbd kbd-sm", "Click")
              ),
              td(cls := "p-2", "Toggle element selection")
            )
          )
        )
      )
    )
  )
