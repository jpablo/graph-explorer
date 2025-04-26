package org.jpablo.graphexplorer.viewer.components

import buildinfo.BuildInfo
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.SimpleDialog
import scala.scalajs.js.Date

def AboutDialog(isOpen: Var[Boolean]): HtmlElement =
  SimpleDialog(
    isOpen,
    div(
      cls := "space-y-4",
      // Wrap title and icon in a flex container
      div(
        cls := "flex items-center gap-2",
        img(src := "/favicon.svg", cls := "h-6 w-6"), // Added favicon
        h3(cls := "text-xl font-semibold text-gray-900", "Graph Explorer")
      ),
      table(
        cls := "w-full text-sm",
        tbody(
          tr(
            td(cls := "font-bold text-gray-600 py-1 pr-2", "Version:"),
            td(cls := "text-gray-700 py-1", BuildInfo.version)
          ),
          tr(
            td(cls := "font-bold text-gray-600 py-1 pr-2", "Scala Version:"),
            td(cls := "text-gray-700 py-1", BuildInfo.scalaVersion)
          ),
          tr(
            td(cls := "font-bold text-gray-600 py-1 pr-2", "Build date:"),
            td(cls := "text-gray-700 py-1", new Date(BuildInfo.builtAtString).toUTCString())
          )
        )
      ),
      p(cls := "text-xs text-gray-500 pt-2",
        "© 2025 Juan Pablo Romero & contributors.",
      ),
      p( cls := "text-xs text-gray-500",
        "Crafted with ❤️ in Northern California"
      )
    )
  )

