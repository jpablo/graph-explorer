package org.jpablo.graphexplorer.viewer.components.codeMirror

import com.raquo.laminar.nodes.ReactiveHtmlElement
import typings.codemirrorState.mod.TransactionSpec

import scala.scalajs.js
import js.DynamicImplicits.given
import scala.scalajs.js.Dynamic.literal as obj
import com.raquo.laminar.api.L.*
import typings.codemirror.mod as codemirror
import typings.codemirrorView.mod.{EditorView, EditorViewConfig, ViewUpdate}
import typings.vizJsLangDot.mod.dot

def CodeMirror(sourceText: Var[String], mods: Modifier[ReactiveHtmlElement.Base]*) =

  lazy val extensions =
    js.Array[Any](
      codemirror.basicSetup,
      dot(),
      EditorView.updateListener.of(updateSource(_))
    )

  def updateSource(update: ViewUpdate): Unit =
    if update.docChanged && sourceText.now() != update.state.doc.toString then
//      dom.console.debug(s"[CodeMirror] updateSource: docChanged, updating sourceText Var, ${timeDelta()}")
      sourceText.set(update.state.doc.toString)
    else
      ()
//      dom.console.debug(s"[CodeMirror] updateSource: no changes found, don't update sourceText, ${timeDelta()}")

  div(
    mods,
    onMountCallback: ctx =>
      import ctx.owner
      // Editor -> source
      val editorView = codemirror.EditorView(
        EditorViewConfig()
          .setDoc(sourceText.now())
          .setParent(ctx.thisNode.ref)
          .setExtensions(extensions)
      )
      // Source -> editor
      for newSource <- sourceText.signal do
//        dom.console.debug(s"[CodeMirror] newSource.length: ${newSource.length}")
        val existingSource = editorView.state.doc.toString
        if newSource != existingSource then
//          dom.console.debug(s"[CodeMirror] newSource != existingSource")
//          dom.console.debug(s"[CodeMirror] sourceText Var changed, updating document: ${timeDelta()}")
          editorView.dispatch(
            TransactionSpec().setChanges(
              js.Array(obj(from = 0, to = existingSource.length, insert = newSource))
            )
          )
  )
