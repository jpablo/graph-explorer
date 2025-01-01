package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.widgets.SimpleDialog

def HelpDialog(open: Var[Boolean]) =
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
            tr(
              td(cls := "p-2 border", "Backspace"),
              td(cls := "p-2 border", "Delete selected nodes")
            ),
            tr(
              td(cls := "p-2 border", "a"),
              td(cls := "p-2 border", "Add new node")
            ),
            tr(
              td(cls := "p-2 border", "g"),
              td(cls := "p-2 border", "Group selected nodes")
            ),
            tr(
              td(cls := "p-2 border", "z"),
              td(cls := "p-2 border", "Undo")
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
              td(cls := "p-2 border", "Click + Drag between nodes"),
              td(cls := "p-2 border", "Create a new edge between nodes")
            ),
            tr(
              td(cls := "p-2 border", "⌘ + Mouse wheel"),
              td(cls := "p-2 border", "Zoom in/out")
            ),
            tr(
              td(cls := "p-2 border", "Mouse wheel"),
              td(cls := "p-2 border", "Pan vertically")
            ),
            tr(
              td(cls := "p-2 border", "Shift + Mouse wheel"),
              td(cls := "p-2 border", "Pan horizontally")
            ),
            tr(
              td(cls := "p-2 border", "Click node"),
              td(cls := "p-2 border", "Select/deselect node")
            )
          )
        )
      )
    )
  )