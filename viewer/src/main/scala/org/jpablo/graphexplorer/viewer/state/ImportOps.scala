package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, PastedDiagram}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** Bringing a whole document IN — the mirror of [[ExportOps]], which only ever
  * sends one out.
  */
trait ImportOps:
  this: ViewerState =>

  /** Replace the entire document with `text`, read as `format`.
    *
    * Text and language land together — see `InternalPhases.replaceDocument` for
    * why they cannot be two writes: in between them the outgoing document is
    * observable under the incoming backend, which every consumer then fails on.
    */
  def replaceSource(text: String, format: DiagramFormat): Unit =
    phases.replaceDocument(text, format)

  /** As [[replaceSource]], with the language read off the text itself. */
  def replaceSourceDetectingFormat(text: String): DiagramFormat =
    val format = DiagramFormat.detect(text)
    replaceSource(text, format)
    format

  /** A whole document restored from the editor's own history (undo/redo).
    *
    * The selector has to travel with it, or undoing a paste hands the old DOT
    * back to the Mermaid parser and the recovery reads as a syntax error. Only a
    * DECLARED format moves it though — an undo must not second-guess a language
    * the user set by hand over text that declares nothing.
    */
  def restoreSource(text: String): Unit =
    DiagramFormat.declared(text) match
      // Undeclared: the language is staying put, so this is an ordinary text
      // change and there is no pair to keep consistent.
      case None         => sourceText.set(text)
      case Some(format) => replaceSource(text, format)

  /** Replace the diagram with the DOT or Mermaid source on the system clipboard.
    *
    * Recoverable rather than guarded by a confirmation: the swap goes through
    * `sourceText`, so it lands in CodeMirror's history and Undo puts the old
    * document back. Nothing is touched when the clipboard holds no text — an
    * empty clipboard must not be a way to erase the diagram.
    */
  def pasteDiagram(): Unit =
    readText().onComplete:
      case Success(raw) =>
        PastedDiagram.from(raw) match
          case Some(pasted) =>
            replaceSource(pasted.source, pasted.format)
            infoBus.emit(s"Pasted ${pasted.format.displayName} diagram from the clipboard")
          case None =>
            infoBus.emit("Nothing to paste: the clipboard holds no text")
      case Failure(err) =>
        errorBus.emit(s"Could not read the clipboard: ${err.getMessage}")
