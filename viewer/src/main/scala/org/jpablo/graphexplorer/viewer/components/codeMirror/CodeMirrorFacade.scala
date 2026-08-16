package org.jpablo.graphexplorer.viewer.components.codeMirror

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.*

// Hand-written facade for the (small) subset of CodeMirror 6 the source editor
// binds to: @codemirror/state, @codemirror/view, @codemirror/commands, the
// `codemirror` meta-package, and @viz-js/lang-dot.
//
// This replaces the ScalablyTyped-generated `typings.codemirror*` bindings:
// sbt-converter has no sbt 2.x release, so generated facades were dropped in
// the sbt 2 migration. Same approach as the three.js facade
// (backends/threejs/ThreeJS.scala): declare only what CodeMirror.scala uses.
// https://codemirror.net/docs/

type Extension   = js.Any
type StateEffect = js.Any
type KeyBinding  = js.Any

// ---- @codemirror/state -----------------------------------------------------

@js.native
trait Text extends js.Object

@js.native
trait EditorState extends js.Object:
  def doc: Text = js.native

@js.native
@JSImport("@codemirror/state", "Compartment")
class Compartment() extends js.Object:
  def of(extension: Extension): Extension          = js.native
  def reconfigure(extension: Extension): StateEffect = js.native

trait ChangeSpec extends js.Object:
  var from: js.UndefOr[Int]      = js.undefined
  var to: js.UndefOr[Int]        = js.undefined
  var insert: js.UndefOr[String] = js.undefined

trait TransactionSpec extends js.Object:
  var effects: js.UndefOr[StateEffect] = js.undefined
  var changes: js.UndefOr[ChangeSpec]  = js.undefined

// ---- @codemirror/view ------------------------------------------------------

@js.native
trait ViewUpdate extends js.Object:
  def docChanged: Boolean    = js.native
  def state: EditorState     = js.native

trait EditorViewConfig extends js.Object:
  var doc: js.UndefOr[String]           = js.undefined
  var parent: js.UndefOr[dom.Element]   = js.undefined
  var extensions: js.UndefOr[Extension] = js.undefined

@js.native
trait UpdateListenerFacet extends js.Object:
  def of(listener: js.Function1[ViewUpdate, Unit]): Extension = js.native

@js.native
@JSImport("@codemirror/view", "EditorView")
class EditorView(config: EditorViewConfig) extends js.Object:
  def state: EditorState                      = js.native
  def dispatch(specs: TransactionSpec*): Unit = js.native

// Static members of the EditorView class.
@js.native
@JSImport("@codemirror/view", "EditorView")
object EditorView extends js.Object:
  def lineWrapping: Extension             = js.native
  val updateListener: UpdateListenerFacet = js.native

@js.native
@JSImport("@codemirror/view", "keymap")
object keymap extends js.Object:
  def of(bindings: js.Array[KeyBinding]): Extension = js.native

// ---- @codemirror/commands --------------------------------------------------

@js.native
@JSImport("@codemirror/commands", JSImport.Namespace)
object commands extends js.Object:
  val indentWithTab: KeyBinding = js.native
  // Commands accept any {state, dispatch} target; EditorView satisfies that
  // structurally (its `dispatch` is already bound).
  def undo(target: EditorView): Boolean = js.native
  def redo(target: EditorView): Boolean = js.native

// ---- codemirror meta-package -----------------------------------------------

@js.native
@JSImport("codemirror", JSImport.Namespace)
object codemirror extends js.Object:
  val basicSetup: Extension = js.native

// ---- @viz-js/lang-dot ------------------------------------------------------

@js.native
@JSImport("@viz-js/lang-dot", JSImport.Namespace)
object vizJsLangDot extends js.Object:
  def dot(): Extension = js.native
