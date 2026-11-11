package org.jpablo.graphexplorer.viewer.components.codeMirror

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** The static lookup of CodeMirror: the EditorView of a `.cm-editor`
  * element, or null. Only the tests use it.
  */
@js.native
@JSImport("@codemirror/view", "EditorView")
private object EditorViewLookup extends js.Object:
  def findFromDOM(element: dom.Element): EditorView = js.native

/** The source editor (`CodeMirror`), as `SourceTab` mounts it: the mount
  * makes a CodeMirror `EditorView`, and the unmount must destroy it.
  */
class CodeMirrorSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "CodeMirrorSpec"

  override def munitFixtures = List(mockStorageFixture())

  /** Record each listener that the page adds to an event target, until
    * `restore`. A removal of the listener deletes its record. Thus `live`
    * holds the listeners that are on their targets now.
    *
    * `outside()` gives the live listeners whose target is not a node out of
    * the page, as "target type" strings. A listener on a node out of the page
    * (a node of an unmounted editor) goes away with the node. A listener on
    * the window, on the document, on a media query, on an EditContext, or on
    * a node in the page (an ancestor of the editor) keeps its function, and
    * the function keeps the editor.
    */
  private def recordListeners(): js.Dynamic =
    js.eval(
      """(function() {
        |  var proto = EventTarget.prototype;
        |  var add = proto.addEventListener, remove = proto.removeEventListener;
        |  var live = [];
        |  function capture(options) {
        |    return typeof options === 'boolean' ? options : !!(options && options.capture);
        |  }
        |  function index(target, type, fn, c) {
        |    for (var i = 0; i < live.length; i++) {
        |      var e = live[i];
        |      if (e.target === target && e.type === type && e.fn === fn && e.capture === c) return i;
        |    }
        |    return -1;
        |  }
        |  function name(target) {
        |    if (target === window) return 'window';
        |    if (target === document) return 'document';
        |    if (typeof MediaQueryList !== 'undefined' && target instanceof MediaQueryList)
        |      return 'matchMedia(' + target.media + ')';
        |    if (target instanceof Element) return '<' + target.tagName.toLowerCase() + '>';
        |    return String(target);
        |  }
        |  proto.addEventListener = function(type, fn, options) {
        |    var c = capture(options);
        |    if (fn && index(this, type, fn, c) < 0) live.push({ target: this, type: type, fn: fn, capture: c });
        |    return add.call(this, type, fn, options);
        |  };
        |  proto.removeEventListener = function(type, fn, options) {
        |    var i = index(this, type, fn, capture(options));
        |    if (i >= 0) live.splice(i, 1);
        |    return remove.call(this, type, fn, options);
        |  };
        |  return {
        |    outside: function() {
        |      return live
        |        .filter(function(e) { return !(e.target instanceof Node && !e.target.isConnected); })
        |        .map(function(e) { return name(e.target) + ' ' + e.type; });
        |    },
        |    restore: function() { proto.addEventListener = add; proto.removeEventListener = remove; }
        |  };
        |})()""".stripMargin
    ).asInstanceOf[js.Dynamic]

  private def outsideListeners(recorder: js.Dynamic): List[String] =
    recorder.outside().asInstanceOf[js.Array[String]].toList.sorted

  // `CodeMirror` makes an `EditorView` in its mount callback. Before the fix,
  // no unmount callback called `EditorView.destroy()`. The subscriptions of
  // the element stop with its owner, but the EditorView stays alive.
  // CodeMirror's DOMObserver adds "resize" and "scroll" to the window,
  // "selectionchange" to the document, "change" to `matchMedia("print")`, and
  // "scroll" to each ancestor of the editor (here also <body> and <html>).
  // Its tooltip plugin adds a second "resize" to the window. On Chromium, the
  // EditContext of the editor also has listeners. Only `destroy` removes
  // them. These listeners keep the EditorView and its whole document.
  // `SourceTab` mounts the editor for each diagram view, so each navigation
  // to a diagram leaves one EditorView alive.
  //
  // A detach is not a destroy. The unmount takes the element of `CodeMirror`
  // out of the page, and the `.cm-editor` element stays in it. `destroy`
  // takes `.cm-editor` out of its parent (`this.dom.remove()`), and it
  // removes the listeners above.
  //
  // Failure before the fix: after the unmount, the listeners on the window,
  // the document, the media query, the EditContext and the ancestors in the
  // page are still on their targets, and `.cm-editor` is still in the
  // element of `CodeMirror`.
  test("the unmount of the source editor destroys its EditorView"):
    val owner = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state    = ViewerState(ViewTarget.diagram("code-mirror-spec"), graphviz)(using owner)
      val box      = dom.document.createElement("div")
      dom.document.body.appendChild(box)
      val recorder = recordListeners()
      val root     = render(box, CodeMirror(state))
      var mounted  = true
      val element  = box.firstElementChild
      afterMicrotasks:
        check(element != null, "precondition: the render put no element in the box")
        check(element.querySelector(".cm-editor") != null, "precondition: the mount made no CodeMirror editor")
        val whileMounted = outsideListeners(recorder)
        check(
          whileMounted.contains("document selectionchange") && whileMounted.contains("window resize"),
          s"precondition: the mounted editor has no listeners on the window and the document: $whileMounted"
        )
        root.unmount()
        mounted = false
        check(!box.contains(element), "precondition: the unmount did not take the element out of the page")
        same(
          outsideListeners(recorder),
          Nil,
          "after the unmount, the EditorView keeps its listeners outside the editor: it is not destroyed"
        )
        check(
          element.querySelector(".cm-editor") == null,
          "after the unmount, .cm-editor stays in the element of CodeMirror: the EditorView is only detached, not destroyed"
        )
      .transform: outcome =>
        if mounted then root.unmount()
        recorder.restore()
        box.parentNode.removeChild(box)
        owner.killSubscriptions()
        outcome

  /** The EditorView of the one `.cm-editor` in `element`, or None. */
  private def viewIn(element: dom.Element): Option[EditorView] =
    Option(element.querySelector(".cm-editor")).flatMap(editor => Option(EditorViewLookup.findFromDOM(editor)))

  /** Mount the editor of a new diagram in a box in the document, and run
    * `body` after the mount. The end of the test removes the box.
    */
  private def withEditor(body: (ViewerState, dom.Element) => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div")
    dom.document.body.appendChild(box)
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.diagram("code-mirror-spec"), graphviz)(using owner)
      afterMicrotasks(()).flatMap(_ => body(state, box))
    .transform: outcome =>
      box.parentNode.removeChild(box)
      owner.killSubscriptions()
      outcome

  // The unmount stops the subscriptions of the mount, and then it destroys
  // the view. A source change, a wrap change, an undo and a redo after the
  // unmount must not get to the destroyed view. A destroyed EditorView takes
  // a dispatch with no error, so the test reads the document of the view.
  // Failure: the destroyed view has the text of the later source change.
  test("after the unmount, no change of the state gets to the destroyed EditorView"):
    withEditor: (state, box) =>
      val root    = render(box, CodeMirror(state))
      val element = box.firstElementChild
      afterMicrotasks:
        val view = viewIn(element)
        check(view.isDefined, "precondition: the mount made no EditorView")
        val textBefore = view.get.state.doc.toString
        same(textBefore, state.sourceText.now(), "precondition: the editor does not show the source")
        root.unmount()
        state.sourceText.set("digraph after { x }")
        state.wrapSourceLines.update(!_)
        state.undoEvent.emit(())
        state.redoEvent.emit(())
        same(view.get.state.doc.toString, textBefore, "a change of the state got to the EditorView after the unmount")

  // A second mount of the same element makes a new EditorView. The unmount
  // destroyed the first view, so the element has one editor, and the new
  // view gets the changes in the two directions.
  // Failure with no destroy: the element has two `.cm-editor` elements.
  test("a second mount of the editor makes one new EditorView, and it works"):
    withEditor: (state, box) =>
      val editor = CodeMirror(state)
      render(box, editor).unmount()
      val root    = render(box, editor)
      val element = editor.ref
      afterMicrotasks:
        same(element.querySelectorAll(".cm-editor").length, 1, "the second mount does not give exactly one editor")
        val view = viewIn(element)
        check(view.isDefined, "the second mount made no EditorView")
        same(view.get.state.doc.toString, state.sourceText.now(), "the new editor does not show the source")
        // Source -> editor.
        state.sourceText.set("digraph second { a }")
        same(view.get.state.doc.toString, "digraph second { a }", "a source change did not get to the new editor")
        // Editor -> source.
        val length = view.get.state.doc.toString.length
        view.get.dispatch(new TransactionSpec {
          changes = new ChangeSpec {
            from   = 0
            to     = length
            insert = "digraph typed { b }"
          }
        })
        same(state.sourceText.now(), "digraph typed { b }", "an edit in the new editor did not get to the source")
        root.unmount()
