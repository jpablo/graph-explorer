package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.{MetadataOps, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.{InputField, InputVariant, TextAreaField, TextAreaVariant}
import org.scalajs.dom

import scala.scalajs.js

/** The section "About" at the end of the Diagram panel: the tags and the notes of the diagram.
  *
  * The tags and the notes are metadata, not text (`DiagramMetadata`). They survive a pull from a
  * file that a tool writes again. The section writes them through `MetadataOps`, as a hide or a
  * fold does. Thus a browser diagram keeps them in `localStorage`, and a file keeps them in its
  * library entry, where `gx tag` and `gx notes` write too. The DOT panel and the Mermaid panel
  * show the same section.
  */
object DiagramAboutView:

  /** The notes box saves after this pause in the typing, or when it loses the focus. */
  val NotesPauseMs = 600

  def apply(state: ViewerState): HtmlElement =
    div(
      idAttr := "diagram-about",
      cls    := "flex-none",
      div(cls := "attributes-section", span("About")),
      div(
        cls := "gx-about-body",
        tagEditor(state.metadata),
        notesBox(state.metadata)
      )
    )

  /** The tags as chips, and a box that adds a tag on Enter. */
  private def tagEditor(ops: MetadataOps): HtmlElement =
    val draft = Var("")
    div(
      cls := "gx-about-field",
      label(cls := "gx-about-label", forId := "diagram-tag-input", "Tags"),
      div(
        cls := "gx-tag-list",
        children <-- ops.tags.signal.split(identity)((tag, _, _) => chip(ops, tag))
      ),
      InputField(
        InputVariant.xs,
        idAttr      := "diagram-tag-input",
        cls         := "gx-tag-input",
        typ         := "text",
        placeholder := "Add a tag, then press Enter",
        controlled(value <-- draft.signal, onInput.mapToValue --> draft),
        // An Enter that ends the composition of an input method selects a word. It does not add a tag.
        onKeyDown.filter(e => e.key == "Enter" && !isComposing(e)).preventDefault --> { _ =>
          ops.addTag(draft.now())
          draft.set("")
        }
      )
    )

  // scalajs-dom has no `isComposing` on KeyboardEvent. Each browser that this app supports has it.
  private def isComposing(e: dom.KeyboardEvent): Boolean =
    e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[js.UndefOr[Boolean]].contains(true)

  private def chip(ops: MetadataOps, tag: String): HtmlElement =
    span(
      cls   := "badge badge-sm badge-soft gx-tag",
      title := tag,
      span(cls := "gx-tag-text", tag),
      button(
        typ        := "button",
        cls        := "gx-tag-remove",
        title      := s"Remove the tag $tag",
        aria.label := s"Remove the tag $tag",
        onClick --> (_ => ops.removeTag(tag)),
        "×"
      )
    )

  /** The notes. The box saves after a pause in the typing, and when it loses the focus.
    *
    * While the box has the focus, a change of the notes from a different writer (`gx notes`) does
    * not replace the text in the box: the person is typing. The next save of the box wins.
    */
  private def notesBox(ops: MetadataOps): HtmlElement =
    val focused = Var(false)
    val typed   = EventBus[String]()
    div(
      cls := "gx-about-field",
      label(cls := "gx-about-label", forId := "diagram-notes", "Notes"),
      // `amend` keeps the type of the element, so the handlers below can read the text.
      TextAreaField(TextAreaVariant.xs).amend(
        idAttr      := "diagram-notes",
        cls         := "gx-notes",
        rows        := 3,
        placeholder := "Notes about this diagram",
        value <-- ops.notes.signal.composeChanges(_.filter(_ => !focused.now())),
        onFocus --> (_ => focused.set(true)),
        onInput.mapToValue --> typed,
        // After a blur, the blur saved the text. A pause that ends later must not save an older text.
        typed.events.debounce(NotesPauseMs).filter(_ => focused.now()) --> ops.notes,
        inContext: el =>
          onBlur --> { _ =>
            focused.set(false)
            ops.notes.set(el.ref.value)
          },
        // The panel can close while the person types (a change of the panel tab, or of the diagram).
        onUnmountCallback: el =>
          if focused.now() then ops.notes.set(el.ref.value)
      )
    )
