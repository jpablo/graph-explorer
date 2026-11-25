package org.jpablo.graphexplorer.viewer.components.codeMirror

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.*

// Hand-written facade for the (small) subset of CodeMirror 6 the source editor
// binds to: @codemirror/state, @codemirror/view, @codemirror/commands,
// @codemirror/language, @lezer/highlight, the `codemirror` meta-package,
// @viz-js/lang-dot, and @codemirror/lang-xml.
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
trait Text extends js.Object:
  /** The number of characters in the document. */
  def length: Int = js.native

@js.native
trait EditorState extends js.Object:
  def doc: Text = js.native

@js.native
trait ReadOnlyFacet extends js.Object:
  def of(readOnly: Boolean): Extension = js.native

// Static members of the EditorState class.
@js.native
@JSImport("@codemirror/state", "EditorState")
object EditorState extends js.Object:
  /** With `true`, no command of the user changes the document. A dispatch
    * from code still does, so the editor can show a new text of the file.
    */
  val readOnly: ReadOnlyFacet = js.native

/** The precedence of an extension. An extension with a higher precedence
  * comes first, so its key bindings run before the bindings of other
  * extensions.
  */
@js.native
@JSImport("@codemirror/state", "Prec")
object Prec extends js.Object:
  def highest(extension: Extension): Extension = js.native

/** A selection: the cursor is at `anchor` when `head` is not set. */
trait SelectionSpec extends js.Object:
  var anchor: Int
  var head: js.UndefOr[Int] = js.undefined

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
  var selection: js.UndefOr[SelectionSpec] = js.undefined

// ---- @codemirror/view ------------------------------------------------------

@js.native
trait Transaction extends js.Object:
  /** True for the named user event and any of its sub-events — `"undo"` and
    * `"redo"` are what @codemirror/commands annotates history moves with.
    */
  def isUserEvent(event: String): Boolean = js.native

@js.native
trait ViewUpdate extends js.Object:
  def docChanged: Boolean    = js.native
  def state: EditorState     = js.native
  /** The transactions this update batches — the only place the PROVENANCE of a
    * change survives; `state.doc` alone cannot say where the text came from.
    */
  def transactions: js.Array[Transaction] = js.native

trait EditorViewConfig extends js.Object:
  var doc: js.UndefOr[String]           = js.undefined
  var parent: js.UndefOr[dom.Element]   = js.undefined
  var extensions: js.UndefOr[Extension] = js.undefined
  var selection: js.UndefOr[SelectionSpec] = js.undefined

@js.native
trait UpdateListenerFacet extends js.Object:
  def of(listener: js.Function1[ViewUpdate, Unit]): Extension = js.native

@js.native
@JSImport("@codemirror/view", "EditorView")
class EditorView(config: EditorViewConfig) extends js.Object:
  def state: EditorState                      = js.native
  def dispatch(specs: TransactionSpec*): Unit = js.native
  /** Move the keyboard focus to the editor. */
  def focus(): Unit = js.native

  /** Remove the editor from the page, and remove its listeners from the
    * window, the document and the ancestors of the editor. The view is not
    * usable after this call.
    */
  def destroy(): Unit = js.native

// Static members of the EditorView class.
@js.native
@JSImport("@codemirror/view", "EditorView")
object EditorView extends js.Object:
  def lineWrapping: Extension             = js.native
  val updateListener: UpdateListenerFacet = js.native
  /** The EditorView of a `.cm-editor` element, or null. */
  def findFromDOM(element: dom.Element): EditorView = js.native
  /** An extension with the CSS rules of `spec`. Each key is a selector in
    * the scope of the editor, and `&` is the editor element.
    */
  def theme(spec: js.Dictionary[js.Any]): Extension = js.native

@js.native
@JSImport("@codemirror/view", "keymap")
object keymap extends js.Object:
  def of(bindings: js.Array[KeyBinding]): Extension = js.native

/** A key binding. `run` gets the key, and `shift` gets the key with Shift.
  * A command that returns true handles the key: the editor then calls
  * `preventDefault` on the event, and also `stopPropagation` when
  * `stopPropagation` is true.
  */
trait KeyBindingSpec extends js.Object:
  var key: js.UndefOr[String]                           = js.undefined
  var run: js.UndefOr[js.Function1[EditorView, Boolean]] = js.undefined
  var stopPropagation: js.UndefOr[Boolean]              = js.undefined

@js.native
@JSImport("@codemirror/view", JSImport.Namespace)
object viewModule extends js.Object:
  /** An extension that shows `text` in an empty editor. */
  def placeholder(text: String): Extension = js.native

// ---- @codemirror/commands --------------------------------------------------

@js.native
@JSImport("@codemirror/commands", JSImport.Namespace)
object commands extends js.Object:
  val indentWithTab: KeyBinding = js.native
  // Commands accept any {state, dispatch} target; EditorView satisfies that
  // structurally (its `dispatch` is already bound).
  def undo(target: EditorView): Boolean = js.native
  def redo(target: EditorView): Boolean = js.native
  /** Put a line break at the cursor, with no indentation. */
  def insertNewline(target: EditorView): Boolean = js.native

// ---- @codemirror/language --------------------------------------------------

@js.native
trait HighlightStyle extends js.Object

@js.native
trait HighlightStyleStatic extends js.Object:
  /** Each spec has a `tag` (one tag or an array of tags) and CSS properties. */
  def define(specs: js.Array[js.Object]): HighlightStyle = js.native

@js.native
@JSImport("@codemirror/language", JSImport.Namespace)
object language extends js.Object:
  val HighlightStyle: HighlightStyleStatic = js.native
  /** A highlight style from this function replaces the fallback style of
    * `basicSetup`.
    */
  def syntaxHighlighting(style: HighlightStyle): Extension = js.native

// ---- @lezer/highlight ------------------------------------------------------

type Tag = js.Any

/** The highlight tags that the theme of the editor colors. A style for a tag
  * applies to its sub-tags also: `punctuation` includes brackets and
  * separators. `literal` includes `string` and `number`, and the style of the
  * sub-tag has priority.
  */
@js.native
@JSImport("@lezer/highlight", "tags")
object tags extends js.Object:
  val keyword: Tag      = js.native
  val typeName: Tag     = js.native
  val propertyName: Tag = js.native
  val literal: Tag      = js.native
  val string: Tag       = js.native
  val number: Tag       = js.native
  val comment: Tag      = js.native
  val meta: Tag         = js.native
  val punctuation: Tag  = js.native
  val invalid: Tag      = js.native

// ---- codemirror meta-package -----------------------------------------------

@js.native
@JSImport("codemirror", JSImport.Namespace)
object codemirror extends js.Object:
  val basicSetup: Extension = js.native
  /** The history, the selection and the default key bindings, with no
    * gutter, no autocompletion and no search.
    */
  val minimalSetup: Extension = js.native

// ---- @viz-js/lang-dot ------------------------------------------------------

@js.native
@JSImport("@viz-js/lang-dot", JSImport.Namespace)
object vizJsLangDot extends js.Object:
  def dot(): Extension = js.native

// ---- @codemirror/lang-xml --------------------------------------------------

@js.native
@JSImport("@codemirror/lang-xml", JSImport.Namespace)
object langXml extends js.Object:
  /** The XML language: the highlight of tags and attributes, and the
    * automatic close of a tag.
    */
  def xml(): Extension = js.native
