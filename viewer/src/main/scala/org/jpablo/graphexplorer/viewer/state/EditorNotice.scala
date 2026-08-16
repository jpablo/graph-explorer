package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.backends.DiagramFormat

/** A message about the current document, shown next to the source editor.
  *
  * The two levels share the same state-machine consequences (the graph is out of sync with the
  * text, so canvas edits are ignored) but differ in presentation:
  *   - `Error`: the parse failed — the document needs the user's attention.
  *   - `Info`: expected/benign, e.g. a render-only diagram kind ([[org.jpablo.graphexplorer.viewer.backends.RenderOnlyDiagram]]) —
  *     the drawing is correct, nothing is broken.
  *
  * `suggestedFormat` carries a REMEDY, not just a diagnosis: it is set when the
  * document declares a language other than the selected one, which is the one
  * parse failure the notice can offer to fix outright rather than describe.
  */
case class EditorNotice(
    level:           EditorNotice.Level,
    message:         String,
    suggestedFormat: Option[DiagramFormat] = None
) derives CanEqual:
  def isError: Boolean = level == EditorNotice.Level.Error

object EditorNotice:
  enum Level derives CanEqual:
    case Info, Error

  def info(message: String): EditorNotice  = EditorNotice(Level.Info, message)
  def error(message: String): EditorNotice = EditorNotice(Level.Error, message)

  /** The document declares `actual`, but `selected` is what is parsing it.
    *
    * Worth its own message because the backend's own is about a symptom it
    * cannot explain — Graphviz reports a syntax error at line 1, Mermaid an
    * "UnknownDiagramError" — while the cause is one field away and fixable in
    * one click.
    */
  def formatMismatch(actual: DiagramFormat, selected: DiagramFormat): EditorNotice =
    EditorNotice(
      Level.Error,
      s"This looks like a ${actual.displayName} diagram, but ${selected.displayName} is selected.",
      suggestedFormat = Some(actual)
    )
