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
          cls := "min-w-full border-collapse",
          thead(
            tr(
              cls := "bg-gray-100",
              th(cls := "p-2 text-left border", "Key"),
              th(cls := "p-2 text-left border", "Action")
            )
          ),
          tbody(
            for
              (shortcut, command) <- commands.commandsByShortcut.toSeq.sortBy(_._1.mkString(""))
            yield tr(
              td(
                cls := "p-2 border",
                shortcut.map(kbd(cls := "kbd kbd-sm", _)).intersperse(span(" + "))
              ),
              td(cls := "p-2 border", command.description.getOrElse(command.title))
            )
          )
        )
      ),
      // Mouse Controls Section
      div(
        h3(cls := "text-lg font-bold mb-4", "Mouse Controls"),
        table(
          cls := "min-w-full border-collapse",
          thead(
            tr(
              cls := "bg-gray-100",
              th(cls := "p-2 text-left border", "Action"),
              th(cls := "p-2 text-left border", "Description")
            )
          ),
          tbody(
            tr(
              td(
                cls := "p-2 border", 
                kbd(cls := "kbd kbd-sm", "Click"), 
                span(" + "), 
                kbd(cls := "kbd kbd-sm", "Drag"), 
                span(" between nodes")
              ),
              td(cls := "p-2 border", "Create a new edge between nodes")
            ),
            tr(
              td(
                cls := "p-2 border",
                kbd(cls := "kbd kbd-sm", "⌘"),
                span(" + "),
                kbd(cls := "kbd kbd-sm", "Mouse wheel")
              ),
              td(cls := "p-2 border", "Zoom in/out")
            ),
            tr(
              td(
                cls := "p-2 border",
                kbd(cls := "kbd kbd-sm", "Mouse wheel")
              ),
              td(cls := "p-2 border", "Pan vertically")
            ),
            tr(
              td(
                cls := "p-2 border",
                kbd(cls := "kbd kbd-sm", "Shift"),
                span(" + "),
                kbd(cls := "kbd kbd-sm", "Mouse wheel")
              ),
              td(cls := "p-2 border", "Pan horizontally")
            ),
            tr(
              td(
                cls := "p-2 border",
                kbd(cls := "kbd kbd-sm", "Click"),
                span(" element")
              ),
              td(cls := "p-2 border", "Select element")
            ),
            tr(
              td(
                cls := "p-2 border",
                kbd(cls := "kbd kbd-sm", "Shift"),
                span(" + "),
                kbd(cls := "kbd kbd-sm", "Click"),
                span(" element")
              ),
              td(cls := "p-2 border", "Toggle element selection")
            )
          )
        )
      )
    )
  )