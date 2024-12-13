package org.jpablo.graphexplorer.viewer.components.codeMirror

import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.state.ViewerState
import typings.codemirrorState.anon.{Dispatch, From}
import typings.codemirrorState.mod.{Transaction, TransactionSpec}

import scala.scalajs.js
import com.raquo.laminar.api.L.*
import typings.codemirror.mod as codemirror
import typings.codemirrorView.mod.{EditorView, EditorViewConfig, ViewUpdate, keymap}
import typings.vizJsLangDot.mod.dot
import typings.codemirrorCommands.mod.indentWithTab
import typings.codemirrorCommands.mod.{undo, redo}

def CodeMirror(state: ViewerState, mods: Modifier[ReactiveHtmlElement.Base]*) =

  lazy val extensions =
    js.Array[Any](
      codemirror.basicSetup,
      keymap.of(js.Array(indentWithTab)),
      dot(),
      EditorView.updateListener.of(updateSource(_))
    )

  def updateSource(update: ViewUpdate): Unit =
    if update.docChanged && state.sourceText.now() != update.state.doc.toString then
      state.sourceText.set(update.state.doc.toString)

  div(
    mods,
    onMountCallback: ctx =>
      import ctx.owner
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
      for newSource <- state.sourceText.signal do
        val existingSource = editorView.state.doc.toString
        if newSource != existingSource then
          editorView.dispatch(
            TransactionSpec().setChanges(
              From(0).setTo(existingSource.length).setInsert(newSource)
            )
          )
  )
