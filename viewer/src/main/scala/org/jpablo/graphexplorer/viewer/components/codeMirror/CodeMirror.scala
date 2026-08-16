package org.jpablo.graphexplorer.viewer.components.codeMirror

import org.jpablo.graphexplorer.viewer.state.ViewerState

import scala.scalajs.js
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.Mods

/** One edit on its way out of the editor. `fromHistory` distinguishes a
  * keystroke from an undo/redo: the latter restores a WHOLE document, so the
  * language selector has to follow it back (see ImportOps.restoreSource).
  */
private case class EditorEdit(text: String, fromHistory: Boolean)

def CodeMirror(state: ViewerState, mods: Mods*) =
  // Create a local EventBus for debouncing text updates
  val textUpdates = new EventBus[EditorEdit]

  // Line wrapping lives in a compartment so it can be swapped by a transaction. Rebuilding
  // the EditorView instead would discard the doc's history, selection and scroll position
  // every time the toggle is pressed.
  val wrapping = new Compartment()

  // An empty array is CodeMirror's canonical "no extension".
  def wrapExtension(enabled: Boolean): Extension =
    if enabled then EditorView.lineWrapping else js.Array[Any]()

  lazy val baseExtensions =
    js.Array[Any](
      codemirror.basicSetup,
      keymap.of(js.Array(commands.indentWithTab)),
      vizJsLangDot.dot(),
      wrapping.of(wrapExtension(state.wrapSourceLines.now())),
      EditorView.updateListener.of(updateSource(_))
    )

  def updateSource(update: ViewUpdate): Unit =
    if update.docChanged then
      val newText = update.state.doc.toString
      if state.sourceText.now() != newText then
        // Read off the transactions rather than tracked around the undo/redo
        // calls below, so in-editor ⌘Z (basicSetup's own history keymap, which
        // never touches undoEvent) is recognized just the same.
        val fromHistory =
          update.transactions.exists(tr => tr.isUserEvent("undo") || tr.isUserEvent("redo"))
        textUpdates.emit(EditorEdit(newText, fromHistory))

  div(
    mods,
    onMountCallback: ctx =>
      import ctx.owner

      // Set up debounced stream that updates the state
      textUpdates.events
//        .debounce(100) // TODO: Make this configurable or dynamic.
        .foreach { edit =>
          if edit.fromHistory then state.restoreSource(edit.text)
          else state.sourceText.set(edit.text)
        }

      // Editor -> source
      val editorView = new EditorView(new EditorViewConfig {
        doc        = state.sourceText.now()
        parent     = ctx.thisNode.ref
        extensions = baseExtensions
      })

      for wrap <- state.wrapSourceLines.signal.changes do
        editorView.dispatch(new TransactionSpec {
          effects = wrapping.reconfigure(wrapExtension(wrap))
        })

      for _ <- state.undoEvent.events do
        commands.undo(editorView)

      for _ <- state.redoEvent.events do
        commands.redo(editorView)

      // Source -> editor
      for newSource <- state.sourceText.signal.distinct do
        val existingSource = editorView.state.doc.toString
        if newSource != existingSource then
          editorView.dispatch(new TransactionSpec {
            changes = new ChangeSpec {
              from   = 0
              to     = existingSource.length
              insert = newSource
            }
          })
  )
