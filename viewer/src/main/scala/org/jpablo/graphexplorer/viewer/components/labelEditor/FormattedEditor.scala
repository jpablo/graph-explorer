package org.jpablo.graphexplorer.viewer.components.labelEditor

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.labelEditor.LabelSchema.*
import org.jpablo.graphexplorer.viewer.components.proseMirror.*
import org.jpablo.graphexplorer.viewer.components.proseMirror.Node as PMNode
import org.jpablo.graphexplorer.viewer.color.Srgb
import org.jpablo.graphexplorer.viewer.components.svgCanvas.DiagramColors
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.ElementId
import org.jpablo.graphexplorer.viewer.widgets.{InputField, InputVariant, SelectBox, SelectVariant}
import org.scalajs.dom

import scala.collection.mutable.ListBuffer
import scala.scalajs.js

/** The "Formatted" mode of the label dialogs: a ProseMirror editor on
  * [[LabelSchema]], with a toolbar for the formats.
  *
  * The editor shows the label as the canvas does: the base font is the font
  * of the element, and each run has its own font. The editor and `text` show
  * the same label:
  *
  *   - An edit in the editor goes into `text` through
  *     [[ProseLabel.toLabelText]]. The `original` of that call is the text of
  *     the last load. Thus an edit and its undo give the loaded text again,
  *     byte for byte.
  *   - A change of `text` from outside (the dialog opens) loads the document
  *     again. The editor knows the text that it wrote last, so its own
  *     changes do not come back.
  *
  * The keys are the keys of the source editor: Enter saves, Shift+Enter
  * starts a new line, and Escape cancels. Mod-b, Mod-i and Mod-u toggle bold,
  * italic and underline. Mod-z and Shift-Mod-z undo and redo.
  *
  * A table label shows as a grid, with one editable cell for each cell of the
  * label. Tab and Shift+Tab move the cursor to the next cell and to the cell
  * before. The editor does not change the structure of the table: a
  * transaction that adds, removes or moves a cell does not act.
  *
  * Use the editor only for a text that [[ProseLabel.canEdit]] accepts.
  */
object FormattedEditor:

  /** The common font faces of the face list. */
  val commonFaces: List[String] = List("Times", "Helvetica", "Courier", "Menlo", "Arial")

  /** The name of the DOM property that holds the view, for [[viewOf]]. */
  private val ViewProperty = "gxProseView"

  /** The CSS class of the element that holds the ProseMirror view. */
  private val BoxClass = "gx-label-formatted-box"

  /** The view of the formatted editor in `root`, or None. The tests use it,
    * as `EditorView.findFromDOM` of CodeMirror.
    */
  def viewOf(root: dom.Element): Option[EditorView] =
    val box = if root.classList.contains(BoxClass) then root else root.querySelector(s".$BoxClass")
    Option(box)
      .flatMap(b => b.asInstanceOf[js.Dynamic].selectDynamic(ViewProperty).asInstanceOf[js.UndefOr[EditorView]].toOption)
      .filter(_ != null)

  /** The editor and its toolbar.
    *
    * @param text     the label text, in the form of the dialog
    * @param origin   true while an editor sends an edit to `text`
    * @param graph    the diagram, for the font of the element and the faces
    * @param element  the element of the label
    * @param dark     true changes the colors for a dark theme, as the preview does
    * @param startCell the cell of a table (row, column) where the cursor starts;
    *                 None puts it at the end of the label
    */
  def apply(
      text:     Var[String],
      origin:   LabelEditorParts.EditOrigin,
      graph:    Signal[ViewerGraph],
      element:  Signal[ElementId],
      dark:     Signal[Boolean],
      onSave:   () => Unit,
      onCancel: () => Unit,
      startCell: () => Option[List[Int]] = () => None
  ): HtmlElement =
    // The state of the view, for the toolbar. Each transaction sets it.
    val current = Var(Option.empty[EditorState])
    var viewRef = Option.empty[EditorView]
    // The text of the last load: the `original` of `toLabelText`.
    var loaded = text.now()
    // The text of the document: the last text that the editor loaded or wrote.
    var shown = text.now()

    def dispatchTo(view: EditorView): js.Function1[Transaction, Unit] = tr => view.dispatch(tr)

    def applyTransaction(view: EditorView, tr: Transaction): Unit =
      val next = view.state.apply(tr)
      view.updateState(next)
      current.set(Some(next))
      if tr.docChanged then
        val newText = ProseLabel.toLabelText(next.doc, loaded)
        shown = newText
        if text.now() != newText then
          // The set runs its observers before it returns, because an edit
          // comes from an event of the browser, outside of a transaction.
          origin.fromEditor = true
          try text.set(newText)
          finally origin.fromEditor = false

    def keys: Plugin =
      def run(action: () => Unit): Command = (_, _, _) => { action(); true }
      def toggle(name: String): Command = commands.toggleMark(schema.marks(name))
      keymap.keymap(
        js.Dictionary[Command](
          "Enter"       -> run(onSave),
          "Shift-Enter" -> Commands.splitLine,
          "Escape"      -> run(onCancel),
          "Mod-b"       -> toggle(Bold),
          "Mod-i"       -> toggle(Italic),
          "Mod-u"       -> toggle(Underline),
          "Tab"         -> Commands.moveCell(1),
          "Shift-Tab"   -> Commands.moveCell(-1),
          "Mod-z"       -> history.undo,
          "Shift-Mod-z" -> history.redo,
          "Mod-y"       -> history.redo
        )
      )

    def stateOf(initial: PMNode): EditorState =
      EditorState.create(new EditorStateConfig:
        doc = initial
        // The cursor is at the end of the label, as in the source editor, or at
        // the end of the text of the start cell.
        selection = startCell()
          .flatMap(Commands.cellTextEnd(initial, _))
          .fold(Selections.atEnd(initial))(TextSelection.create(initial, _))
        plugins = js.Array(Commands.keepStructure, keys, keymap.keymap(commands.baseKeymap), history.history())
      )

    /** Load `newText` into the view. A text that the editor cannot edit
      * changes nothing: the label editor then shows the source.
      */
    def load(view: EditorView, newText: String): Unit =
      ProseLabel.fromLabelText(newText).foreach: doc =>
        val state = stateOf(doc)
        view.updateState(state)
        current.set(Some(state))
        loaded = newText
        shown = newText

    def mountEditor(ctx: MountContext[Div]): EditorView =
      import ctx.owner
      val initial = text.now()
      loaded = initial
      shown = initial
      val doc = ProseLabel.fromLabelText(initial).getOrElse(ProseLabel.fromPlain(""))
      val view = new EditorView(
        ctx.thisNode.ref,
        new DirectEditorProps:
          var state = stateOf(doc)
          dispatchTransaction = js.ThisFunction.fromFunction2((v: EditorView, tr: Transaction) => applyTransaction(v, tr))
          attributes = js.Dictionary("aria-label" -> "Label", "spellcheck" -> "false")
      )
      ctx.thisNode.ref.asInstanceOf[js.Dynamic].updateDynamic(ViewProperty)(view)
      viewRef = Some(view)
      current.set(Some(view.state))

      // `text` -> the view. The editor sends its own changes to `text`, and
      // these come back here with the text in `shown`, so the check stops them.
      for newText <- text.signal do
        if newText != shown then load(view, newText)

      view.focus()
      // The modal dialog can move the focus when it opens, after this mount.
      js.timers.setTimeout(0):
        if viewRef.contains(view) then view.focus()
      view

    def unmountEditor(ref: dom.html.Div, view: Option[EditorView]): Unit =
      viewRef = None
      ref.asInstanceOf[js.Dynamic].updateDynamic(ViewProperty)(js.undefined)
      view.foreach(_.destroy())

    /** Run `f` on the view, then give the focus back to the editor. */
    def act(f: EditorView => Unit): Unit =
      viewRef.foreach: view =>
        f(view)
        view.focus()

    def command(cmd: Command): Unit =
      act(view => cmd(view.state, dispatchTo(view), view))

    val baseFont = graph
      .combineWith(element, dark)
      .map((g, e, d) => Style.base(LabelPreview.elementFont(g, e), LabelPreview.elementFill(g, e), d))

    div(
      cls := "gx-label-formatted flex flex-col gap-1",
      Toolbar(current.signal, graph, command, act, f => viewRef.foreach(f)),
      div(
        cls := s"$BoxClass rounded border border-base-300",
        cls("gx-dark") <-- baseFont.map(_.dark),
        styleAttr <-- baseFont.map(_.css),
        // The dialog also closes on Escape. The editor cancels, so the key
        // stops here.
        onKeyDown.filter(_.key == "Escape") --> (_.stopPropagation()),
        // The unmount destroys the view. The subscriptions of the mount stop
        // first, so no change gets to a destroyed view.
        onMountUnmountCallbackWithState[Div, EditorView](
          mount = mountEditor,
          unmount = (el, view) => unmountEditor(el.ref, view)
        )
      )
    )

  // ---- the base font -----------------------------------------------------------

  private object Style:

    final case class Base(css: String, dark: Boolean)

    /** The CSS of the editor box: the font of the element, its color, and the
      * fill of the element. Without the fill, a white text on a dark node was
      * white on the white box. On a dark theme the colors change as in the
      * preview (`DiagramColors`).
      */
    def base(font: LabelPreview.ElementFont, fill: Option[String], darkColors: Boolean): Base =
      val remap = if darkColors then DiagramColors.themeRemap() else None
      def css(color: String) = DiagramColors.resolve(color).map(c => remap.fold(c)(_.apply(c)).css)
      val color      = css(font.color.getOrElse("black"))
      val background = fill.flatMap(css)
      val rules = List(
        Some(s"font-family: ${family(font.face.getOrElse("Times-Roman"))}"),
        Some(s"font-size: ${number(font.size.getOrElse(14.0) * 4 / 3)}px"),
        color.map(c => s"color: $c"),
        background.map(c => s"background-color: $c")
      ).flatten.map(_ + ";").mkString(" ")
      Base(rules, remap.isDefined)

    /** The CSS family of a Graphviz face, with a fallback for the PostScript
      * names of Graphviz. The browser does not know "Times-Roman", for example.
      */
    def family(face: String): String =
      val css   = LabelSchema.fontFamilyCss(face)
      val lower = face.trim.toLowerCase
      if lower.startsWith("times") then s"""$css, Times, "Times New Roman", serif"""
      else if lower.startsWith("helvetica") || lower.startsWith("arial") then s"$css, Helvetica, Arial, sans-serif"
      else css

  // ---- the commands ------------------------------------------------------------

  /** The commands and the queries of the toolbar. */
  private[labelEditor] object Commands:

    /** The marks that text at the cursor gets. */
    def cursorMarks(state: EditorState): js.Array[Mark] =
      Option(state.storedMarks.asInstanceOf[js.Array[Mark]]).getOrElse(state.selection.$from.marks())

    /** Each text node in the selection, with the part in the selection. */
    def textRanges(state: EditorState): List[(PMNode, Int, Int)] =
      val sel = state.selection
      val out = ListBuffer.empty[(PMNode, Int, Int)]
      state.doc.nodesBetween(
        sel.from,
        sel.to,
        (node, pos) =>
          if node.isText then out += ((node, math.max(pos, sel.from), math.min(pos + node.nodeSize, sel.to)))
          true
      )
      out.toList

    /** Each line in the selection, with its position. */
    def lines(state: EditorState): List[(PMNode, Int)] =
      val sel = state.selection
      val out = ListBuffer.empty[(PMNode, Int)]
      state.doc.nodesBetween(
        sel.from,
        sel.to,
        (node, pos) =>
          if node.`type`.name == Line then out += ((node, pos))
          false
      )
      out.toList

    /** True when the selection has the mark `name`. An empty selection uses
      * the marks of the next input.
      */
    def markActive(state: EditorState, name: String): Boolean =
      val tpe = state.schema.marks(name)
      val sel = state.selection
      if sel.empty then tpe.isInSet(cursorMarks(state)).isDefined
      else state.doc.rangeHasMark(sel.from, sel.to, tpe)

    /** The value of the font attribute `attr` ("face", "size" or "color") in
      * the selection, when all the text has the same value. None for the
      * default or for different values.
      */
    def fontValue(state: EditorState, attr: String): Option[String] =
      val font = state.schema.marks(Font)
      def valueIn(marks: js.Array[Mark]): Option[String] =
        font.isInSet(marks).toOption.flatMap: m =>
          stringAttr(m.attrs, attr).orElse(numberAttr(m.attrs, attr).map(number))
      val values =
        if state.selection.empty then List(valueIn(cursorMarks(state)))
        else textRanges(state).map((node, _, _) => valueIn(node.marks))
      values.distinct match
        case List(v) => v
        case _       => None

    /** Set the font attribute `attr` of the selection to `value`; null is the
      * default. A font mark with no attribute goes away.
      */
    def setFont(view: EditorView, attr: String, value: js.Any): Unit =
      val state = view.state
      val font  = state.schema.marks(Font)
      def withValue(marks: js.Array[Mark]): Option[Mark] =
        val old   = font.isInSet(marks).toOption.map(_.attrs)
        val attrs = js.Dictionary[js.Any](List("face", "size", "color").map(a => a -> old.flatMap(_.get(a)).getOrElse(null))*)
        attrs(attr) = value
        Option.when(attrs.values.exists(v => v != null))(font.create(attrs))
      if state.selection.empty then
        val marks = cursorMarks(state)
        val next  = withValue(marks).fold(font.removeFromSet(marks))(_.addToSet(marks))
        view.dispatch(state.tr.setStoredMarks(next))
      else
        val tr = state.tr
        textRanges(state).foreach: (node, from, to) =>
          withValue(node.marks) match
            case Some(mark) => tr.addMark(from, to, mark)
            case None       => tr.removeMark(from, to, font)
        view.dispatch(tr)

    /** The alignment of the lines in the selection, when all have the same
      * one. The center is also the default, null.
      */
    def alignOf(state: EditorState): Option[String] =
      lines(state).map((node, _) => stringAttr(node.attrs, AlignName).filter(_ != AlignText).getOrElse(AlignCenter)).distinct match
        case List(a) => Some(a)
        case _       => None

    /** Set the alignment of the lines in the selection. The center is the
      * default of Graphviz, so it removes the alignment.
      */
    def setAlign(view: EditorView, align: String): Unit =
      val value: js.Any = if align == AlignCenter then null else align
      val tr            = view.state.tr
      lines(view.state).foreach((_, pos) => tr.setNodeMarkup(pos, null, js.Dictionary(AlignName -> value)))
      view.dispatch(tr)

    /** Remove the marks of the selection and the alignment of its lines. */
    def clear(view: EditorView): Unit =
      val state = view.state
      val sel   = state.selection
      val tr    = state.tr
      if sel.empty then tr.setStoredMarks(js.Array())
      else state.schema.marks.values.foreach(tpe => tr.removeMark(sel.from, sel.to, tpe))
      lines(state).foreach: (node, pos) =>
        if stringAttr(node.attrs, AlignName).isDefined then tr.setNodeMarkup(pos, null, js.Dictionary(AlignName -> (null: js.Any)))
      view.dispatch(tr)

    /** The structure of a document: "text" for a text label, or the kind of
      * each cell of each row of a table.
      */
    private def structure(doc: PMNode): String =
      if doc.childCount == 1 && doc.child(0).`type`.name == Table then
        val table = doc.child(0)
        (0 until table.childCount)
          .map: r =>
            val row = table.child(r)
            (0 until row.childCount).map(c => row.child(c).child(0).`type`.name).mkString(",")
          .mkString("|")
      else "text"

    /** The plugin that refuses a transaction that changes the structure of the
      * document: a selection across cells and a delete, for example. The save
      * writes again only the content of the cells, so it must find each cell of
      * the label in the document.
      */
    val keepStructure: Plugin =
      val filter: js.Function2[Transaction, EditorState, Boolean] =
        (tr, state) => !tr.docChanged || structure(tr.doc) == structure(state.doc)
      new Plugin(js.Dynamic.literal(filterTransaction = filter))

    /** The editable cells of a table document: the position of each cell, and
      * the position at the end of its text.
      */
    private def editableCells(doc: PMNode): List[(Int, Int)] =
      val out = ListBuffer.empty[(Int, Int)]
      doc.nodesBetween(
        0,
        doc.content.size,
        (node, pos) =>
          if node.`type`.name == Cell then
            if node.child(0).`type`.name == Line then out += ((pos, pos + node.nodeSize - 2))
            false
          else true
      )
      out.toList

    /** The position at the end of the text of the editable cell at `path`
      * (row, column) of a table document.
      */
    def cellTextEnd(doc: PMNode, path: List[Int]): Option[Int] =
      path match
        case List(row, col) if doc.childCount == 1 && doc.child(0).`type`.name == Table =>
          val table = doc.child(0)
          Option
            .when(row >= 0 && row < table.childCount && col >= 0 && col < table.child(row).childCount)(table.child(row))
            .filter(r => r.child(col).child(0).`type`.name == Line)
            .map: r =>
              val rowStart = 1 + (0 until row).map(table.child(_).nodeSize).sum
              val cellPos  = rowStart + 1 + (0 until col).map(r.child(_).nodeSize).sum
              cellPos + r.child(col).nodeSize - 2
        case _ => None

    /** Tab (`step` 1) and Shift+Tab (`step` -1): put the cursor at the end of
      * the next editable cell, or of the editable cell before. At the first and
      * at the last cell, the cursor stays. In a text label, the key does what
      * the browser does.
      */
    def moveCell(step: Int): Command = (state, dispatch, _) =>
      val cells = editableCells(state.doc)
      if cells.isEmpty then false
      else
        val from    = state.selection.from
        val current = cells.lastIndexWhere((pos, _) => pos <= from)
        val target  = cells.lift(if current < 0 then 0 else current + step)
        target.foreach: (_, end) =>
          dispatch.foreach(d => d(state.tr.setSelection(TextSelection.create(state.doc, end))))
        true

    /** The new line keeps the type and the alignment of the line. */
    private val keepLine: js.Function3[PMNode, Boolean, ResolvedPos, js.Any] =
      (node, _, _) => js.Dynamic.literal(`type` = node.`type`, attrs = node.attrs)

    /** Shift+Enter: split the line at the cursor. The new line keeps the
      * alignment of the line, and the next input keeps the marks at the
      * cursor, as `splitBlockKeepMarks` does.
      */
    val splitLine: Command = (state, dispatch, view) =>
      val sel   = state.selection
      val marks = Option(state.storedMarks.asInstanceOf[js.Array[Mark]]).orElse(Option.when(sel.$to.parentOffset > 0)(sel.$from.marks()))
      val withMarks: js.UndefOr[js.Function1[Transaction, Unit]] =
        dispatch.map: d =>
          (tr: Transaction) =>
            marks.foreach(tr.ensureMarks)
            d(tr)
      commands.splitBlockAs(keepLine)(state, withMarks, view)

  end Commands

  // ---- the toolbar -------------------------------------------------------------

  private def Toolbar(
      state:   Signal[Option[EditorState]],
      graph:   Signal[ViewerGraph],
      command:  Command => Unit,
      act:      (EditorView => Unit) => Unit,
      withView: (EditorView => Unit) => Unit
  ): HtmlElement =

    def button_(name: String, tip: String, active: Signal[Boolean], content: Modifier[HtmlElement])(action: => Unit) =
      button(
        typ        := "button",
        cls        := "gx-icon-btn",
        title      := tip,
        aria.label := tip,
        dataAttr("gx-format") := name,
        cls("active") <-- active,
        aria.pressed <-- active.map(_.toString),
        // The button does not take the focus, so the editor keeps its selection.
        onMouseDown.preventDefault --> Observer.empty,
        onClick --> (_ => action),
        content
      )

    def markButton(name: String, tip: String, content: Modifier[HtmlElement]) =
      button_(name, tip, state.map(_.exists(Commands.markActive(_, name))), content):
        command(commands.toggleMark(schema.marks(name)))

    def alignButton(align: String, tip: String, icon: String) =
      button_(s"align-$align", tip, state.map(_.flatMap(Commands.alignOf).contains(align)), i(cls := icon)):
        act(Commands.setAlign(_, align))

    def fontValue(attr: String) = state.map(_.flatMap(Commands.fontValue(_, attr))).distinct

    val face  = fontValue("face")
    val faces = graph.map(LabelPreview.diagramFaces).combineWith(face).map: (diagram, current) =>
      (diagram ++ commonFaces ++ current.toList).distinct

    val faceSelect = SelectBox(
      SelectVariant.xs,
      cls        := "w-28",
      title      := "Font face",
      aria.label := "Font face",
      dataAttr("gx-format") := "face",
      // The split keeps the option elements, so the select keeps its value.
      children <-- faces.map("" :: _).distinct.split(identity)((f, _, _) => option(value := f, if f.isEmpty then "Default font" else f)),
      value <-- face.map(_.getOrElse("")),
      onChange.mapToValue --> { v => act(Commands.setFont(_, "face", if v.isEmpty then null else v)) }
    )

    val sizeInput = InputField(
      typ         := "number",
      InputVariant.xs,
      cls         := "w-16",
      minAttr     := "1",
      stepAttr    := "1",
      placeholder := "size",
      title       := "Font size (points); empty for the default",
      aria.label  := "Font size",
      dataAttr("gx-format") := "size",
      value <-- fontValue("size").map(_.getOrElse("")),
      onChange.mapToValue --> { v =>
        val size: js.Any = v.trim.toDoubleOption.filter(_ > 0).fold(null)(d => d)
        act(Commands.setFont(_, "size", size))
      }
    )

    val color = fontValue("color")
    val colorInput = input(
      typ        := "color",
      cls        := "gx-label-color",
      title      := "Font color",
      aria.label := "Font color",
      dataAttr("gx-format") := "color",
      cls("gx-label-color-default") <-- color.map(_.isEmpty),
      value <-- color.map(c => c.flatMap(hexOf).getOrElse("#000000")),
      // The picker stays open during the input, so the editor does not take
      // the focus back.
      onInput.mapToValue --> { v => withView(Commands.setFont(_, "color", v)) }
    )

    div(
      cls := "gx-label-format-toolbar flex flex-wrap items-center gap-0.5",
      markButton(Bold, "Bold (Mod-B)", i(cls := "bi bi-type-bold")),
      markButton(Italic, "Italic (Mod-I)", i(cls := "bi bi-type-italic")),
      markButton(Underline, "Underline (Mod-U)", i(cls := "bi bi-type-underline")),
      markButton(Strike, "Strikethrough", i(cls := "bi bi-type-strikethrough")),
      markButton(Overline, "Overline", span(cls := "gx-overline-glyph", "O")),
      markButton(Sub, "Subscript", i(cls := "bi bi-subscript")),
      markButton(Sup, "Superscript", i(cls := "bi bi-superscript")),
      span(cls := "gx-label-format-sep"),
      faceSelect,
      sizeInput,
      colorInput,
      button_("color-default", "Default color", Signal.fromValue(false), i(cls := "bi bi-x-lg text-xs")):
        act(Commands.setFont(_, "color", null))
      ,
      span(cls := "gx-label-format-sep"),
      alignButton(AlignLeft, "Align left", "bi bi-text-left"),
      alignButton(AlignCenter, "Align center (default)", "bi bi-text-center"),
      alignButton(AlignRight, "Align right", "bi bi-text-right"),
      span(cls := "gx-label-format-sep"),
      button_("clear", "Clear format", Signal.fromValue(false), i(cls := "bi bi-eraser")):
        act(Commands.clear)
    )

  end Toolbar

  /** The `#rrggbb` form of a color, for the color input. */
  private def hexOf(color: String): Option[String] =
    DiagramColors.resolve(color).map: c =>
      def byte(v: Double) = f"${math.round(Srgb.clamp01(v) * 255).toInt}%02x"
      s"#${byte(c.r)}${byte(c.g)}${byte(c.b)}"

  /** A whole number has no ".0". */
  private def number(d: Double): String =
    if d.isWhole && math.abs(d) < 1e15 then d.toLong.toString else (math.round(d * 1000) / 1000.0).toString

end FormattedEditor
