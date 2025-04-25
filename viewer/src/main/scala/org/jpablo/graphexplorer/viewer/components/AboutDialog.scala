package org.jpablo.graphexplorer.viewer.components

import buildinfo.BuildInfo
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.SimpleDialog
import scala.scalajs.js.Date

def AboutDialog(isOpen: Var[Boolean]): HtmlElement =
  SimpleDialog(
    isOpen,
    div(
      cls := "p-6 space-y-3", // Add padding and vertical spacing
      h3(cls := "text-xl font-semibold text-gray-900", "About Graph Explorer"), // Style title
      div( // Group info paragraphs
        p(cls := "text-sm text-gray-700",
          span(cls := "font-medium text-gray-600", "Version: "), // Style label
          BuildInfo.version
        ),
        p(cls := "text-sm text-gray-700",
          span(cls := "font-medium text-gray-600", "Scala Version: "), // Style label
          BuildInfo.scalaVersion
        ),
        p(cls := "text-sm text-gray-700",
          span(cls := "font-medium text-gray-600", "Build date: "), // Style label
          new Date(BuildInfo.builtAtString).toUTCString()
        )
      ),
      p(cls := "text-xs text-gray-500 pt-2", // Style copyright, add padding top
        "© 2025 Juan Pablo Romero"
      )
    )
  )

