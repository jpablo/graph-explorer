package org.jpablo.graphexplorer.viewer.components.codeMirror

import org.jpablo.graphexplorer.viewer.state.ViewerState
import typings.codemirrorState.anon.{Dispatch, From}
import typings.codemirrorState.mod.{Transaction, TransactionSpec}

import scala.scalajs.js
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.Mods
import typings.codemirror.mod as codemirror
import typings.codemirrorView.mod.{EditorView, EditorViewConfig, ViewUpdate, keymap}
import typings.vizJsLangDot.mod.dot
import typings.codemirrorCommands.mod.indentWithTab
import typings.codemirrorCommands.mod.{redo, undo}

def CodeMirror(state: ViewerState, mods: Mods*) =
  // Create a local EventBus for debouncing text updates
  val textUpdates = new EventBus[String]

  lazy val extensions =
    js.Array[Any](
      codemirror.basicSetup,
      keymap.of(js.Array(indentWithTab)),
      dot(),
      EditorView.updateListener.of(updateSource(_))
    )

  def updateSource(update: ViewUpdate): Unit =
    if update.docChanged then
      val newText = update.state.doc.toString
      if state.sourceText.now() != newText then
        textUpdates.emit(newText)

  div(
    mods,
    onMountCallback: ctx =>
      import ctx.owner
      
      // Set up debounced stream that updates the state
      textUpdates.events
//        .debounce(100) // TODO: Make this configurable or dynamic.
        .foreach { text =>
          state.sourceTextWriter.onNext(text)
        }
      
      // Editor -> source
      val editorView = codemirror.EditorView(
        EditorViewConfig()
          .setDoc(state.sourceText.now())
          .setParent(ctx.thisNode.ref)
          .setExtensions(extensions)
      )

      for _ <- state.undoEvent.events do
        undo(Dispatch(editorView.dispatch, editorView.state))

      for _ <- state.redoEvent.events do
        redo(Dispatch(editorView.dispatch, editorView.state))

      // Source -> editor
      for newSource <- state.sourceText.signal.distinct do
        val existingSource = editorView.state.doc.toString
        if newSource != existingSource then
          editorView.dispatch(
            TransactionSpec().setChanges(
              From(0).setTo(existingSource.length).setInsert(newSource)
            )
          )
  )
