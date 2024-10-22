package org.jpablo.graphexplorer.viewer.components.codeMirror

import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.scalajs.dom.HTMLDivElement
import typings.codemirrorState.mod.TransactionSpec

import scala.scalajs.js
import js.DynamicImplicits.given
import scala.scalajs.js.Dynamic.literal as obj
import com.raquo.laminar.api.L.*
import typings.codemirror.mod as codemirror
import typings.codemirrorView.mod.{EditorView, EditorViewConfig, ViewUpdate}
import typings.vizJsLangDot.mod.dot

def CodeMirror(source: Var[String], mods: Modifier[ReactiveHtmlElement.Base]*) =

  lazy val extensions =
    js.Array[Any](
      codemirror.basicSetup,
      dot(),
      EditorView.updateListener.of(updateSource(_))
    )

  def updateSource(update: ViewUpdate): Unit =
    if update.docChanged then
      source.set(update.state.doc.toString)

  div(
    mods,
    onMountCallback: ctx =>
      import ctx.owner
      // Editor -> source
      val editorView = codemirror.EditorView(
        EditorViewConfig()
          .setDoc(source.now())
          .setParent(ctx.thisNode.ref)
          .setExtensions(extensions)
      )
      // Source -> editor
      for newSource <- source.signal do
        val existingSource = editorView.state.doc.toString
        if newSource != existingSource then
          editorView.dispatch(
            TransactionSpec().setChanges(
              js.Array(obj(from = 0, to = existingSource.length, insert = newSource))
            )
          )
  )
