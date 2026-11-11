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

/** The source editor (`CodeMirror`), as `SourceTab` mounts it: the mount
  * makes a CodeMirror `EditorView`, and the unmount must destroy it.
  */
class CodeMirrorSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "CodeMirrorSpec"

  override def munitFixtures = List(mockStorageFixture())

  // `CodeMirror` makes an `EditorView` in its mount callback. Before the fix,
  // no unmount callback called `EditorView.destroy()`. The subscriptions of
  // the element stopped with its owner, but the EditorView stayed alive:
  // CodeMirror adds listeners to the window, the document and the ancestors
  // of the editor, and only `destroy` removes them. These listeners keep the
  // EditorView and its whole document. `SourceTab` mounts the editor for each
  // diagram view, so each navigation to a diagram left one EditorView alive.
  //
  // A detach is not a destroy. The unmount takes the element of `CodeMirror`
  // out of the page, and `.cm-editor` stays in it. `destroy` takes
  // `.cm-editor` out of its parent (`this.dom.remove()`).
  // Failure before the fix: after the unmount, `.cm-editor` is still in the
  // element of `CodeMirror`.
  test("the unmount of the source editor destroys its EditorView"):
    withEditor: (state, box) =>
      val root    = render(box, CodeMirror(state))
      val element = box.firstElementChild
      afterMicrotasks:
        check(element != null, "precondition: the render put no element in the box")
        check(element.querySelector(".cm-editor") != null, "precondition: the mount made no CodeMirror editor")
        root.unmount()
        check(!box.contains(element), "precondition: the unmount did not take the element out of the page")
        check(
          element.querySelector(".cm-editor") == null,
          "after the unmount, .cm-editor stays in the element of CodeMirror: the EditorView is only detached, not destroyed"
        )

  /** The EditorView of the one `.cm-editor` in `element`, or None. */
  private def viewIn(element: dom.Element): Option[EditorView] =
    Option(element.querySelector(".cm-editor")).flatMap(editor => Option(EditorView.findFromDOM(editor)))

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
