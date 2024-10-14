package org.jpablo.graphexplorer.viewer.components.codeMirror

import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import js.DynamicImplicits.given
import scala.scalajs.js.Dynamic.literal as obj

import com.raquo.laminar.api.L.*

case class CodeMirrorElement(source: Var[String]):
  def element(mods: Modifier[ReactiveHtmlElement.Base]*) =
    div(
      mods,
      onMountCallback: ctx =>
        import ctx.owner

        val editorView = new EditorView(
          obj(
            // -- EditorState --
            doc = source.now(),
            extensions = js.Array(
              basicSetup,
              dot(),
              EditorView.updateListener.of: update =>
                if update.docChanged then
                  dom.console.log("update")
                  source.set(update.state.doc.toString)
            ),
            // -----------------
            parent = ctx.thisNode.ref
          )
        )

        for newSource <- source.signal do
          dom.console.log("source changed", newSource.length)
          val existingSource = editorView.state.doc.toString
          if newSource != existingSource then
            editorView.dispatch:
              obj(
                changes = obj(
                  from   = 0,
                  to     = existingSource.length,
                  insert = newSource
                )
              )
    )

@js.native
@JSImport("codemirror")
object basicSetup extends js.Object

@js.native
@JSImport("@codemirror/commands")
object defaultKeymap extends js.Object

@js.native
@JSImport("@codemirror/state")
object EditorState extends js.Object:
  def create(opts: js.Object): js.Object = js.native

@js.native
@JSImport("@codemirror/view")
class EditorView(args: js.Object) extends js.Object:
  def dispatch(args: js.Object): Unit = js.native
  def state: js.Dynamic = js.native

@js.native
@JSImport("@codemirror/view")
object EditorView extends js.Object:
  def updateListener: Facet = js.native

@js.native
@JSImport("@codemirror/view")
object keymap extends js.Object:
  def of(args: js.Object): js.Object = js.native

@js.native
trait Facet extends js.Object:
  def of(listener: js.Function1[js.Dynamic, Unit]): js.Object = js.native

@js.native
@JSImport("@viz-js/lang-dot")
class dot extends js.Object

//@js.native
//@JSImport("@codemirror/lang-javascript")
//class javascript() extends js.Object
