package org.jpablo.graphexplorer.viewer.components.proseMirror

import org.scalajs.dom as sdom

import scala.scalajs.js
import scala.scalajs.js.annotation.*

// A hand-written facade for the subset of ProseMirror that the formatted label
// editor uses: prosemirror-model, prosemirror-state, prosemirror-view,
// prosemirror-commands, prosemirror-keymap and prosemirror-history.
//
// This file has the same style as the CodeMirror facade
// (components/codeMirror/CodeMirrorFacade.scala). It declares only what the
// label editor uses. The facade has its own package, because ProseMirror and
// CodeMirror use the same names (EditorState, EditorView, Transaction, keymap).
// https://prosemirror.net/docs/ref/

/** A value that a `toDOM` function gives: a DOM node, or an array of the form
  * `[tag, attrs?, ...children]`. The number 0 in the array is the hole for the
  * content.
  */
type DOMOutputSpec = js.Any

/** The attributes of a node or a mark. */
type Attrs = js.Dictionary[js.Any]

/** A command: it gets the state and, when it must act, a dispatch function and
  * the view. It returns true when it can act (or acted).
  */
type Command = js.Function3[EditorState, js.UndefOr[js.Function1[Transaction, Unit]], js.UndefOr[EditorView], Boolean]

// ---- prosemirror-model -----------------------------------------------------

/** The spec of one attribute. A missing `default` makes the attribute required. */
trait AttributeSpec extends js.Object:
  var default: js.UndefOr[js.Any] = js.undefined

/** A parse rule for a DOM element (`tag`) or for a CSS property (`style`).
  *
  * `getAttrs` gets the element (for `tag`) or the value of the property (for
  * `style`). It returns the attributes, `null` for no attributes, or `false`
  * when the rule does not match.
  */
trait ParseRule extends js.Object:
  var tag: js.UndefOr[String]                                = js.undefined
  var style: js.UndefOr[String]                              = js.undefined
  var priority: js.UndefOr[Int]                              = js.undefined
  var getAttrs: js.UndefOr[js.Function1[js.Any, js.Any]]     = js.undefined
  /** For a `style` rule: the marks that the style removes. */
  var clearMark: js.UndefOr[js.Function1[Mark, Boolean]]     = js.undefined
  /** True: the element closes the node that is open now. */
  var closeParent: js.UndefOr[Boolean]                       = js.undefined
  /** For a `style` rule: false lets the next rules read the same property. */
  var consuming: js.UndefOr[Boolean]                         = js.undefined
  /** True: the parser ignores the element and its content. */
  var ignore: js.UndefOr[Boolean]                            = js.undefined
  /** For a node rule: `"full"` keeps each space and each line break of the
    * content, `true` keeps the spaces. The default collapses the white space.
    */
  var preserveWhitespace: js.UndefOr[Boolean | String]       = js.undefined

trait NodeSpec extends js.Object:
  var content: js.UndefOr[String]                       = js.undefined
  var marks: js.UndefOr[String]                         = js.undefined
  var group: js.UndefOr[String]                         = js.undefined
  var inline: js.UndefOr[Boolean]                       = js.undefined
  var attrs: js.UndefOr[js.Dictionary[AttributeSpec]]   = js.undefined
  var parseDOM: js.UndefOr[js.Array[ParseRule]]         = js.undefined
  var toDOM: js.UndefOr[js.Function1[Node, DOMOutputSpec]] = js.undefined

trait MarkSpec extends js.Object:
  var attrs: js.UndefOr[js.Dictionary[AttributeSpec]]          = js.undefined
  /** The names of the marks that this mark removes, separated by spaces. */
  var excludes: js.UndefOr[String]                             = js.undefined
  var inclusive: js.UndefOr[Boolean]                           = js.undefined
  var parseDOM: js.UndefOr[js.Array[ParseRule]]                = js.undefined
  var toDOM: js.UndefOr[js.Function2[Mark, Boolean, DOMOutputSpec]] = js.undefined

/** The spec of a schema. The order of the keys is the order of the types:
  * the first block type is the default block type.
  */
trait SchemaSpec extends js.Object:
  var nodes: js.Dictionary[NodeSpec]
  var marks: js.UndefOr[js.Dictionary[MarkSpec]] = js.undefined

@js.native
@JSImport("prosemirror-model", "Schema")
class Schema(spec: SchemaSpec) extends js.Object:
  val nodes: js.Dictionary[NodeType] = js.native
  val marks: js.Dictionary[MarkType] = js.native
  /** A node of the named type. */
  def node(tpe: String, attrs: Attrs | Null, content: js.Array[Node], marks: js.Array[Mark]): Node = js.native
  /** A text node. The text must not be empty. */
  def text(text: String, marks: js.Array[Mark]): Node = js.native
  /** A mark of the named type. */
  def mark(tpe: String, attrs: Attrs | Null): Mark = js.native
  /** A node from the JSON form that `Node.toJSON` gives. */
  def nodeFromJSON(json: js.Any): Node = js.native

@js.native
trait NodeType extends js.Object:
  val name: String   = js.native
  val schema: Schema = js.native
  def create(attrs: Attrs | Null, content: js.Array[Node], marks: js.Array[Mark]): Node = js.native

@js.native
trait MarkType extends js.Object:
  val name: String = js.native
  def create(attrs: Attrs | Null): Mark = js.native
  /** The mark of this type in `set`, or undefined. */
  def isInSet(set: js.Array[Mark]): js.UndefOr[Mark] = js.native

@js.native
trait Mark extends js.Object:
  val `type`: MarkType = js.native
  val attrs: Attrs     = js.native
  def eq(other: Mark): Boolean = js.native

@js.native
trait Fragment extends js.Object:
  val size: Int       = js.native
  val childCount: Int = js.native
  def child(index: Int): Node = js.native

@js.native
trait Node extends js.Object:
  val `type`: NodeType       = js.native
  val attrs: Attrs           = js.native
  val content: Fragment      = js.native
  val marks: js.Array[Mark]  = js.native
  /** The text of a text node; undefined for other nodes. */
  val text: js.UndefOr[String] = js.native
  val isText: Boolean        = js.native
  val isTextblock: Boolean   = js.native
  val nodeSize: Int          = js.native
  val childCount: Int        = js.native
  val textContent: String    = js.native
  def child(index: Int): Node = js.native
  /** True when the two nodes have the same type, attributes, marks and content. */
  def eq(other: Node): Boolean = js.native
  def toJSON(): js.Any         = js.native
  /** Throws a RangeError when the content does not obey the schema. */
  def check(): Unit = js.native

trait ParseOptions extends js.Object:
  var preserveWhitespace: js.UndefOr[Boolean | String] = js.undefined

@js.native
@JSImport("prosemirror-model", "DOMParser")
class DOMParser protected () extends js.Object:
  /** The document of the DOM content of `dom`. */
  def parse(dom: sdom.Node, options: ParseOptions = js.native): Node = js.native

@js.native
@JSImport("prosemirror-model", "DOMParser")
object DOMParser extends js.Object:
  /** A parser with the `parseDOM` rules of the schema. */
  def fromSchema(schema: Schema): DOMParser = js.native

@js.native
@JSImport("prosemirror-model", "DOMSerializer")
class DOMSerializer protected () extends js.Object:
  def serializeFragment(fragment: Fragment): sdom.DocumentFragment = js.native

@js.native
@JSImport("prosemirror-model", "DOMSerializer")
object DOMSerializer extends js.Object:
  /** A serializer with the `toDOM` functions of the schema. */
  def fromSchema(schema: Schema): DOMSerializer = js.native

// ---- prosemirror-state -----------------------------------------------------

@js.native
trait Selection extends js.Object:
  val from: Int      = js.native
  val to: Int        = js.native
  val empty: Boolean = js.native

@js.native
@JSImport("prosemirror-state", "TextSelection")
object TextSelection extends js.Object:
  def create(doc: Node, anchor: Int, head: js.UndefOr[Int] = js.undefined): Selection = js.native

@js.native
@JSImport("prosemirror-state", "Plugin")
class Plugin(spec: js.Object) extends js.Object

@js.native
trait Transaction extends js.Object:
  val doc: Node           = js.native
  val docChanged: Boolean = js.native
  val selection: Selection = js.native
  def insertText(text: String, from: js.UndefOr[Int] = js.undefined, to: js.UndefOr[Int] = js.undefined): Transaction =
    js.native
  def setSelection(selection: Selection): Transaction                 = js.native
  def addMark(from: Int, to: Int, mark: Mark): Transaction            = js.native
  def removeMark(from: Int, to: Int, mark: Mark | MarkType): Transaction = js.native
  /** Change the type or the attributes of the node at `pos`. */
  def setNodeMarkup(pos: Int, tpe: NodeType | Null, attrs: Attrs | Null): Transaction = js.native
  def setStoredMarks(marks: js.Array[Mark] | Null): Transaction       = js.native
  def scrollIntoView(): Transaction                                   = js.native

trait EditorStateConfig extends js.Object:
  var schema: js.UndefOr[Schema]            = js.undefined
  var doc: js.UndefOr[Node]                 = js.undefined
  var selection: js.UndefOr[Selection]      = js.undefined
  var plugins: js.UndefOr[js.Array[Plugin]] = js.undefined

@js.native
trait EditorState extends js.Object:
  val doc: Node              = js.native
  val selection: Selection   = js.native
  val schema: Schema         = js.native
  /** The marks that the next input gets, or null. */
  val storedMarks: js.Array[Mark] | Null = js.native
  /** A new transaction from this state. */
  def tr: Transaction                       = js.native
  def apply(tr: Transaction): EditorState   = js.native

@js.native
@JSImport("prosemirror-state", "EditorState")
object EditorState extends js.Object:
  def create(config: EditorStateConfig): EditorState = js.native

// ---- prosemirror-view ------------------------------------------------------

trait DirectEditorProps extends js.Object:
  var state: EditorState
  var dispatchTransaction: js.UndefOr[js.ThisFunction1[EditorView, Transaction, Unit]] = js.undefined
  var editable: js.UndefOr[js.Function1[EditorState, Boolean]]                         = js.undefined
  /** The attributes of the editable element. */
  var attributes: js.UndefOr[js.Dictionary[String]] = js.undefined

@js.native
@JSImport("prosemirror-view", "EditorView")
class EditorView(place: sdom.Element, props: DirectEditorProps) extends js.Object:
  def state: EditorState               = js.native
  /** The editable element. */
  def dom: sdom.HTMLElement             = js.native
  def hasFocus(): Boolean              = js.native
  def dispatch(tr: Transaction): Unit  = js.native
  def updateState(state: EditorState): Unit = js.native
  def focus(): Unit                    = js.native
  /** Remove the editor from the page. The view is not usable after this call. */
  def destroy(): Unit = js.native

// ---- prosemirror-commands --------------------------------------------------

@js.native
@JSImport("prosemirror-commands", JSImport.Namespace)
object commands extends js.Object:
  /** Add the mark to the selection, or remove it when the whole selection
    * has it. With an empty selection, change the stored marks.
    */
  def toggleMark(markType: MarkType, attrs: Attrs | Null = null): Command = js.native
  /** The default key bindings: Enter splits a line, Backspace joins lines. */
  val baseKeymap: js.Dictionary[Command] = js.native

// ---- prosemirror-keymap ----------------------------------------------------

@js.native
@JSImport("prosemirror-keymap", JSImport.Namespace)
object keymap extends js.Object:
  /** A plugin with the key bindings. A key is a name such as `"Mod-b"`. */
  def keymap(bindings: js.Dictionary[Command]): Plugin = js.native

// ---- prosemirror-history ---------------------------------------------------

@js.native
@JSImport("prosemirror-history", JSImport.Namespace)
object history extends js.Object:
  /** The plugin that records the changes for undo and redo. */
  def history(): Plugin = js.native
  val undo: Command     = js.native
  val redo: Command     = js.native
  def undoDepth(state: EditorState): Int = js.native
