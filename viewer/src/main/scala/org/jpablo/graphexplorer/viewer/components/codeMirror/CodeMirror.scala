package org.jpablo.graphexplorer.viewer.components.codeMirror

import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement
import typings.codemirrorState.mod.TransactionSpec

import scala.scalajs.js
import js.DynamicImplicits.given
import scala.scalajs.js.Dynamic.literal as obj
import com.raquo.laminar.api.L.*
import typings.codemirror.mod.basicSetup
import typings.codemirrorView.mod.{EditorView, EditorViewConfig, ViewUpdate}
import typings.vizJsLangDot.mod.dot

case class CodeMirror(source: Var[String]):

  private def updateSource(update: ViewUpdate): Unit =
    if update.docChanged then
      dom.console.log("update")
      source.set(update.state.doc.toString)

  def element(mods: Modifier[ReactiveHtmlElement.Base]*) =
    div(
      mods,
      onMountCallback: ctx =>
        import ctx.owner
        // Editor -> source
        val editorView = typings.codemirror.mod.EditorView(
          EditorViewConfig()
            .setDoc(source.now())
            .setParent(ctx.thisNode.ref)
            .setExtensions(js.Array(basicSetup, dot(), EditorView.updateListener.of(updateSource(_))))
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
