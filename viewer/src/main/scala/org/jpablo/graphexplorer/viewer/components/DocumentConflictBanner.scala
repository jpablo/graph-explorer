package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Button, primary, tiny}

/** The file changed on disk while this page held an edit (§7.3).
  *
  * The strip exists because §7.3 forbids the alternative. Replacing the text
  * silently is what the desktop used to do, and an external write while the
  * person was typing threw the typing away with no message.
  *
  * Both answers end the conflict the same way in the registry: the base becomes
  * the file's version, so the next save's compare-and-swap succeeds. They differ
  * only in whether the editor adopts the file's text.
  */
def DocumentConflictBanner(state: ViewerState): Div =
  div(
    child <-- state.documentConflict.map:
      case None => emptyNode
      case Some(_) =>
        div(
          cls := "document-conflict-banner",
          span(cls := "document-conflict-title", "This file changed on disk"),
          span(cls := "document-conflict-note", "Your edit is still here. Choose which version to keep."),
          Button(
            "Load the file",
            onClick --> (_ => state.resolveDocumentConflict(adoptRemoteText = true))
          ).tiny,
          Button(
            "Keep my edit",
            onClick --> (_ => state.resolveDocumentConflict(adoptRemoteText = false))
          ).primary.tiny
        )
  )
