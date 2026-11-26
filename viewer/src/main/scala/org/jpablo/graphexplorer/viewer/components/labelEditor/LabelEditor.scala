package org.jpablo.graphexplorer.viewer.components.labelEditor

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.graphviz.html.{HtmlLabel, HtmlParser}
import org.jpablo.graphexplorer.viewer.components.codeMirror.*
import org.jpablo.graphexplorer.viewer.components.svgCanvas.DiagramColors
import org.jpablo.graphexplorer.viewer.formats.dot.HtmlLabels
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.ElementId
import org.scalajs.dom

import scala.scalajs.js
import scala.util.Try

/** The editor of the label dialogs: two tabs, a live preview, and the parse
  * error of the label.
  *
  * The tab "Formatted" shows the label with its fonts and a toolbar for the
  * formats ([[FormattedEditor]]). The tab "Source" shows the text of the label
  * (CodeMirror). Formatted is available only for a label that
  * [[ProseLabel.canEdit]] accepts, and only for Graphviz. When the text
  * changes from outside (the dialog opens), the editor shows Formatted if it
  * is available, else Source. A change of the tab loads the current text into
  * the editor of the tab, and does not change the text.
  *
  * @param text        the label text, in the form of the dialog
  * @param graph       the diagram
  * @param element     the element of the label; a new element has an id that
  *                    is not in the graph
  * @param render      draws a DOT text as an SVG, with the engine of the canvas
  * @param onSave      Enter
  * @param onCancel    Escape
  * @param hint        the text of the empty editor
  * @param graphviz    true when the canvas draws the label with Graphviz.
  *                    False hides the preview and the HTML error, because
  *                    these use the rules of Graphviz.
  * @param darkColors  true changes the colors of the preview for a dark theme,
  *                    as the canvas does (see `DiagramColors`)
  * @param previewLabel the label that the preview draws for the text. The
  *                    cell dialog gives the label of the whole node, with the
  *                    text in its cell.
  * @param previewAsText true draws the preview as an external label: text
  *                    with the font of the element, and no shape
  */
def LabelEditor(
    text:        Var[String],
    graph:       Signal[ViewerGraph],
    element:     Signal[ElementId],
    render:      String => Try[dom.svg.SVG],
    onSave:      () => Unit,
    onCancel:    () => Unit,
    hint:        String = "Enter label text...",
    graphviz:    Signal[Boolean] = Signal.fromValue(true),
    darkColors:  Signal[Boolean] = Signal.fromValue(false),
    previewLabel: String => String = identity,
    previewAsText: Boolean = false
): HtmlElement =
  val origin = LabelEditorParts.EditOrigin()

  // The error line and the preview use this text. An edit in the editor gets
  // there after a pause of the person, so the two do not change at each key.
  // A change from outside (a dialog that opens) gets there immediately. Thus
  // a dialog does not show the error and the drawing of the last label.
  // After the pause, the text is the current text, also when a change from
  // outside came during the pause.
  val settledText = Var(text.now())

  import LabelEditorParts.Tab
  val chosen = Var(Tab.Formatted)
  // The reason why Formatted is not available, or None.
  val blocked: Signal[Option[String]] =
    text.signal.combineWith(graphviz).map(LabelEditorParts.formattedBlock).distinct
  val tab: Signal[Tab] =
    chosen.signal.combineWith(blocked).map((c, b) => if b.isEmpty then c else Tab.Source).distinct

  lazy val preview = LabelEditorParts.preview(settledText.signal.map(previewLabel), graph, element, render, darkColors, previewAsText)

  div(
    cls := "gx-label-editor flex flex-col gap-1",
    // The signal, not its changes: a mount also gives the current text.
    text.signal --> { label => if !origin.fromEditor then settledText.set(label) },
    text.signal.changes
      .filter(_ => origin.fromEditor)
      .debounce(LabelEditorParts.pauseMs)
      .map(_ => text.now()) --> settledText.writer,
    // A change from outside selects the default tab.
    text.signal.combineWith(graphviz) --> { (label, isGraphviz) =>
      if !origin.fromEditor then
        chosen.set(if LabelEditorParts.formattedBlock(label, isGraphviz).isEmpty then Tab.Formatted else Tab.Source)
    },
    LabelEditorParts.tabs(tab, blocked, chosen.writer),
    child <-- tab.map:
      case Tab.Formatted => FormattedEditor(text, origin, graph, element, darkColors, onSave, onCancel)
      case Tab.Source    => LabelEditorParts.source(text, origin, onSave, onCancel, hint)
    ,
    div(
      cls := "gx-label-error text-xs text-error min-h-4",
      child.text <-- settledText.signal.combineWithFn(graphviz): (label, isGraphviz) =>
        if isGraphviz then LabelEditorParts.htmlError(label) else ""
    ),
    child.maybe <-- graphviz.distinct.map(Option.when(_)(preview)),
    p(cls := "text-xs text-base-content/60", "Press Shift+Enter to add a new line")
  )

/** The parts of [[LabelEditor]]. */
object LabelEditorParts:

  /** The time from the last edit to the new error line and preview. */
  val pauseMs = 150

  /** True while the editor sends an edit to the text. A change of the text
    * at another time comes from outside the editor.
    */
  final class EditOrigin:
    var fromEditor = false

  /** The tabs of the label editor. */
  enum Tab derives CanEqual:
    case Formatted, Source

  /** The reason why the tab Formatted is not available for `label`, or None
    * when it is available.
    *
    * @param graphviz false for a Mermaid diagram
    */
  def formattedBlock(label: String, graphviz: Boolean): Option[String] =
    if !graphviz then Some("Mermaid labels are not Graphviz HTML labels. Edit the source.")
    else if ProseLabel.canEdit(label) then None
    else
      HtmlParser.parseOrError(label) match
        case Right(HtmlLabel.Table(_)) => Some("The editor cannot read the cells of this table. Edit it in the source.")
        case Right(HtmlLabel.Image(_, _)) => Some("The label is an image. Edit it in the source.")
        case Right(HtmlLabel.Text(_)) => Some("The label has an image. Edit it in the source.")
        case Left(_)                  => Some("The markup has an error. Correct it in the source.")

  /** The tab bar: "Formatted" and "Source". A tab that is not available has
    * its reason in the title, and next to the tabs.
    */
  private[labelEditor] def tabs(current: Signal[Tab], blocked: Signal[Option[String]], select: Observer[Tab]): HtmlElement =
    def tabButton(t: Tab, label: String, reason: Signal[Option[String]]) =
      button(
        typ  := "button",
        role := "tab",
        cls  := "tab",
        dataAttr("gx-tab") := t.toString.toLowerCase,
        cls("tab-active") <-- current.map(_ == t),
        aria.selected <-- current.map(_ == t),
        // aria-disabled, not disabled: the browser shows the title of the button.
        cls("tab-disabled") <-- reason.map(_.isDefined),
        aria.disabled <-- reason.map(_.isDefined),
        title <-- reason.map(_.getOrElse("")),
        onMouseDown.preventDefault --> Observer.empty,
        onClick.compose(_.sample(reason).filter(_.isEmpty)) --> (_ => select.onNext(t)),
        label
      )
    div(
      cls := "flex items-center gap-2",
      div(
        role := "tablist",
        cls  := "tabs tabs-box tabs-xs",
        tabButton(Tab.Formatted, "Formatted", blocked),
        tabButton(Tab.Source, "Source", Signal.fromValue(None))
      ),
      span(cls := "text-xs text-base-content/60 truncate", child.text <-- blocked.map(_.getOrElse("")))
    )

  /** The maximum height of the preview, in pixels. */
  val previewMaxHeight = 200

  /** The parse error of an HTML-like label, or an empty text. A plain label
    * has no parse error.
    */
  def htmlError(label: String): String =
    if HtmlLabels.isHtml(label) then HtmlParser.parseOrError(label).left.getOrElse("") else ""

  /** The CodeMirror editor of the label source. The editor and `text` show
    * the same text. A change of `text` from outside goes into the editor, and
    * a change in the editor goes into `text`.
    */
  private[labelEditor] def source(
      text:     Var[String],
      origin:   EditOrigin,
      onSave:   () => Unit,
      onCancel: () => Unit,
      hint:     String
  ): HtmlElement =

    def binding(name: String, command: () => Unit): KeyBinding =
      new KeyBindingSpec:
        key = name
        run = (_: EditorView) => { command(); true }
        // The dialog also closes on Escape. One handler is sufficient.
        stopPropagation = true

    // A binding of high precedence comes before the default bindings, which
    // put a line break and its indentation on Enter.
    val keys: Extension = Prec.highest(
      keymap.of(
        js.Array(
          binding("Enter", onSave),
          binding("Escape", onCancel),
          new KeyBindingSpec:
            key = "Shift-Enter"
            run = (view: EditorView) => commands.insertNewline(view)
        )
      )
    )

    def sendToText(update: ViewUpdate): Unit =
      if update.docChanged then
        val newText = update.state.doc.toString
        if text.now() != newText then
          // The set runs its observers before it returns, because an edit
          // comes from an event of the browser, outside of a transaction.
          origin.fromEditor = true
          try text.set(newText)
          finally origin.fromEditor = false

    def mountEditor(ctx: MountContext[Div]): EditorView =
      import ctx.owner
      val initial = text.now()
      val view = new EditorView(new EditorViewConfig {
        doc        = initial
        parent     = ctx.thisNode.ref
        selection  = cursorAt(initial.length)
        extensions = js.Array[Any](
          keys,
          codemirror.minimalSetup,
          langXml.xml(),
          EditorTheme.extension,
          EditorView.lineWrapping,
          viewModule.placeholder(hint),
          EditorView.updateListener.of(sendToText(_))
        )
      })

      // `text` -> the editor. The editor sends its own changes to `text`,
      // and these come back here with the same text, so the check stops them.
      for newText <- text.signal do
        val current = view.state.doc.toString
        if newText != current then
          view.dispatch(new TransactionSpec {
            changes = new ChangeSpec {
              from   = 0
              to     = current.length
              insert = newText
            }
            selection = cursorAt(newText.length)
          })

      view.focus()
      // The modal dialog can move the focus when it opens, after this mount.
      // Thus the editor takes the focus again after the open.
      // A destroyed view is no longer in the element.
      js.timers.setTimeout(0):
        if ctx.thisNode.ref.querySelector(".cm-editor") != null then view.focus()
      view

    div(
      cls := "gx-label-source rounded border border-base-300 overflow-hidden",
      // The unmount destroys the EditorView, as in `CodeMirror`. The
      // subscriptions of the mount stop first, so no change gets to a
      // destroyed view.
      onMountUnmountCallbackWithState[Div, EditorView](
        mount = mountEditor,
        unmount = (_, view) => view.foreach(_.destroy())
      )
    )

  /** The size of a drawing in the preview: the size of the drawing, scaled
    * down to the width of the editor and to [[previewMaxHeight]].
    *
    * The width gets all three limits, and the height follows the aspect of
    * the `viewBox`. Thus the drawing keeps its aspect, and a small drawing
    * does not grow. A Graphviz SVG gives its size in points.
    */
  private[labelEditor] def fitStyle(drawing: dom.svg.SVG): String =
    def pixels(attribute: String): Option[Double] =
      Option(drawing.getAttribute(attribute))
        .flatMap(_.stripSuffix("pt").toDoubleOption)
        .filter(_ > 0)
        .map(_ * 4 / 3)
    val width =
      (pixels("width"), pixels("height")) match
        case (Some(w), Some(h)) => s"min(${w}px, 100%, ${previewMaxHeight * w / h}px)"
        case _                  => "100%"
    s"display: block; width: $width; height: auto; max-height: ${previewMaxHeight}px"

  private def cursorAt(position: Int): SelectionSpec =
    new SelectionSpec:
      var anchor = position

  /** The label as the canvas draws it.
    *
    * The preview draws again after each change of the label, the graph or the
    * element. A label that does not draw keeps the last drawing, dimmed.
    */
  private[labelEditor] def preview(
      label:      Signal[String],
      graph:      Signal[ViewerGraph],
      element:    Signal[ElementId],
      render:     String => Try[dom.svg.SVG],
      darkColors: Signal[Boolean],
      asText:     Boolean = false
  ): HtmlElement =
    val drawing = Var(Option.empty[dom.svg.SVG])
    val stale   = Var(false)
    // The label has its own pause. The graph and the element have none,
    // because they change only when the dialog opens.
    val request = label.combineWith(graph, element).distinct

    def draw(label: String, graph: ViewerGraph, element: ElementId): Unit =
      Try(LabelPreview.previewDot(graph, element, label, asText)).flatMap(render) match
        case scala.util.Success(svgElement) =>
          // Graphviz writes a <title> for the graph and for the node. The
          // browser shows a title as a tooltip, so the preview showed the
          // text "preview" under the pointer. Remove the titles.
          svgElement.querySelectorAll("title").foreach(_.remove())
          drawing.set(Some(svgElement))
          stale.set(false)
        case scala.util.Failure(_) =>
          stale.set(true)

    div(
      cls := "gx-label-preview flex justify-center items-center rounded p-2",
      opacity <-- stale.signal.map(if _ then "0.35" else "1"),
      request --> { (label, graph, element) => draw(label, graph, element) },
      child.maybe <-- drawing.signal.map(_.map(svgElement =>
        foreignSvgElement(svg.svg, svgElement).amend(
          svg.style := fitStyle(svgElement),
          DiagramColors.follow(svgElement, darkColors)
        )
      ))
    )
