package org.jpablo.graphexplorer.viewer.state

/** A message about the current document, shown next to the source editor.
  *
  * The two levels share the same state-machine consequences (the graph is out of sync with the
  * text, so canvas edits are ignored) but differ in presentation:
  *   - `Error`: the parse failed — the document needs the user's attention.
  *   - `Info`: expected/benign, e.g. a render-only diagram kind ([[org.jpablo.graphexplorer.viewer.backends.RenderOnlyDiagram]]) —
  *     the drawing is correct, nothing is broken.
  */
case class EditorNotice(level: EditorNotice.Level, message: String) derives CanEqual:
  def isError: Boolean = level == EditorNotice.Level.Error

object EditorNotice:
  enum Level derives CanEqual:
    case Info, Error

  def info(message: String): EditorNotice  = EditorNotice(Level.Info, message)
  def error(message: String): EditorNotice = EditorNotice(Level.Error, message)
