package org.jpablo.graphexplorer.viewer.components.labelEditor

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotToViewerGraph}
import org.jpablo.graphexplorer.viewer.components.proseMirror.{EditorView, TextSelection}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*
import org.scalajs.dom
import upickle.default.read

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.util.Try

/** The "Formatted" mode of the label dialogs: a ProseMirror editor that shows
  * the label with its fonts, a toolbar for the formats, and the tabs
  * "Formatted" and "Source".
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js
  * test bridge and the sbt client, a munit FailException arrives with no message.
  */
class FormattedEditorSpec extends FunSuite:

  override def munitTimeout = scala.concurrent.duration.Duration(20, "s")

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then
      println(s"[FormattedEditorSpec] $message")
      throw AssertionError(message)

  private val diagram =
    """digraph G {
      |  node [shape=box];
      |  plain [label="hello world"];
      |  styled [fontname="Courier", fontsize=21, fontcolor="#ff0000", label="styled"];
      |}""".stripMargin

  private val graph: ViewerGraph =
    ScalaGraphviz.structureJson(diagram) match
      case Right(json) => dotToViewerGraph(read[SimpleGraph](json))
      case Left(err)   => throw AssertionError(s"setup: the fixture does not parse: $err")

  private def renderSvg(dot: String): Try[dom.svg.SVG] = Try:
    val r = ScalaGraphviz.renderFormats(dot, Seq("svg"))
    if r.status != "success" then throw Exception(r.errors.toString)
    dom.DOMParser()
      .parseFromString(r.output("svg"), dom.MIMEType.`image/svg+xml`)
      .documentElement
      .asInstanceOf[dom.svg.SVG]

  /** Completes when `cond` is true, or fails after `ms` with `what`. */
  private def eventually(ms: Double, what: => String)(cond: => Boolean): Future[Unit] =
    val p     = Promise[Unit]()
    val start = dom.window.performance.now()
    def poll(): Unit =
      if Try(cond).getOrElse(false) then p.success(())
      else if dom.window.performance.now() - start > ms then p.failure(AssertionError(what))
      else js.timers.setTimeout(20)(poll())
    poll()
    p.future

  private final class Fixture(val text: Var[String], val box: dom.html.Div, val root: RootNode):
    var saves   = 0
    var cancels = 0
    /** The keydown events that got out of the editor. */
    var escaped = 0
    def find(selector: String): Option[dom.html.Element] =
      Option(box.querySelector(selector)).map(_.asInstanceOf[dom.html.Element])
    def prose: Option[dom.html.Element]   = find(".ProseMirror")
    def source: Option[dom.html.Element]  = find(".cm-content")
    def tab(name: String): dom.html.Element =
      find(s"[data-gx-tab=$name]").getOrElse(throw AssertionError(s"no tab '$name' in ${box.innerHTML.take(400)}"))
    def control(name: String): dom.html.Element =
      find(s"[data-gx-format=$name]").getOrElse(throw AssertionError(s"no control '$name' in the toolbar"))
    def view: EditorView =
      FormattedEditor.viewOf(box).getOrElse(throw AssertionError("no formatted editor view"))
    def previewText: String = find(".gx-label-preview svg").map(_.textContent).getOrElse("")
    def lineCount: Int      = view.state.doc.childCount
    def close(): Unit =
      root.unmount()
      box.remove()

  private def mounted(initial: String, element: ElementId, graphviz: Boolean): Fixture =
    val text = Var(initial)
    val box  = dom.document.createElement("div").asInstanceOf[dom.html.Div]
    dom.document.body.appendChild(box)
    var fixture: Fixture = null
    val editor = LabelEditor(
      text     = text,
      graph    = Signal.fromValue(graph),
      element  = Signal.fromValue(element),
      render   = renderSvg,
      onSave   = () => fixture.saves += 1,
      onCancel = () => fixture.cancels += 1,
      graphviz = Signal.fromValue(graphviz)
    )
    fixture = Fixture(text, box, render(box, editor))
    // The listener counts the Escape keys that get to the dialog.
    box.addEventListener("keydown", (e: dom.KeyboardEvent) => if e.key == "Escape" then fixture.escaped += 1)
    fixture

  private def withEditor(initial: String, element: ElementId = NodeId("plain"), graphviz: Boolean = true)(
      body: Fixture => Future[Unit]
  ): Future[Unit] =
    val f = mounted(initial, element, graphviz)
    body(f).transform(r => { f.close(); r })

  /** Mounts the editor and waits for the formatted editor. */
  private def withFormatted(initial: String, element: ElementId = NodeId("plain"))(body: Fixture => Unit): Future[Unit] =
    withEditor(initial, element): f =>
      eventually(3000, s"no formatted editor for '$initial'")(f.prose.isDefined && FormattedEditor.viewOf(f.box).isDefined)
        .map(_ => body(f))

  private def keydown(target: dom.Element, key: String, shift: Boolean = false, mod: Boolean = false): Unit =
    val mac  = dom.window.navigator.platform.toLowerCase.contains("mac")
    val init = js.Dynamic.literal(
      key = key,
      shiftKey = shift,
      metaKey = mod && mac,
      ctrlKey = mod && !mac,
      bubbles = true,
      cancelable = true
    )
    target.dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))

  /** Select the text from `from` to `to` (ProseMirror positions). */
  private def select(f: Fixture, from: Int, to: Int): Unit =
    f.view.dispatch(f.view.state.tr.setSelection(TextSelection.create(f.view.state.doc, from, to)))

  private def selectAll(f: Fixture): Unit =
    select(f, 1, f.view.state.doc.content.size - 1)

  private def typeAtEnd(f: Fixture, s: String): Unit =
    val end = f.view.state.doc.content.size - 1
    f.view.dispatch(f.view.state.tr.insertText(s, end))

  private def setValue(el: dom.html.Element, value: String, event: String): Unit =
    el.asInstanceOf[js.Dynamic].value = value
    el.dispatchEvent(new dom.Event(event, js.Dynamic.literal(bubbles = true).asInstanceOf[dom.EventInit]))

  private def isActive(el: dom.html.Element): Boolean = el.classList.contains("active")

  // ---- the tabs --------------------------------------------------------------

  test("an HTML text label opens in Formatted"):
    withFormatted("<B>Hello</B>"): f =>
      check(f.source.isEmpty, "the source editor shows too")
      check(f.tab("formatted").classList.contains("tab-active"), "the Formatted tab is not active")
      check(f.prose.exists(_.textContent == "Hello"), s"the editor shows '${f.prose.map(_.textContent)}'")
      check(f.prose.exists(_.querySelector("b") != null), s"the text is not bold: ${f.prose.map(_.innerHTML)}")

  test("a plain label opens in Formatted"):
    withFormatted("one\ntwo"): f =>
      check(f.lineCount == 2, s"the document has ${f.lineCount} lines")
      check(f.text.now() == "one\ntwo", s"the open changed the text: '${f.text.now()}'")

  test("a table label opens in Source, and Formatted is disabled with a reason"):
    val table = "<TABLE><TR><TD>a</TD></TR></TABLE>"
    withEditor(table): f =>
      eventually(3000, "no source editor")(f.source.isDefined).map: _ =>
        val formatted = f.tab("formatted")
        check(f.prose.isEmpty, "the formatted editor shows")
        check(formatted.getAttribute("aria-disabled") == "true", "the Formatted tab is not disabled")
        check(Option(formatted.getAttribute("title")).exists(_.toLowerCase.contains("table")), s"no reason: ${formatted.getAttribute("title")}")
        formatted.click()
        check(f.prose.isEmpty && f.source.isDefined, "a click on the disabled tab opened Formatted")
        check(f.text.now() == table, s"the text changed: '${f.text.now()}'")

  test("markup with an error opens in Source"):
    withEditor("<B>Hello"): f =>
      eventually(3000, "no source editor")(f.source.isDefined).map: _ =>
        check(f.tab("formatted").getAttribute("aria-disabled") == "true", "the Formatted tab is not disabled")
        check(Option(f.tab("formatted").getAttribute("title")).exists(_.toLowerCase.contains("error")), "no reason")

  test("a Mermaid label opens in Source, and Formatted is disabled"):
    withEditor("hello", graphviz = false): f =>
      eventually(3000, "no source editor")(f.source.isDefined).map: _ =>
        check(f.prose.isEmpty, "the formatted editor shows")
        check(f.tab("formatted").getAttribute("aria-disabled") == "true", "the Formatted tab is not disabled")
        check(Option(f.tab("formatted").getAttribute("title")).exists(_.contains("Mermaid")), "no reason")

  test("Formatted → Source → Formatted without an edit does not change the text"):
    val markup = """<FONT COLOR="red" FACE="Menlo"><B><B>x</B></B></FONT>"""
    withEditor(markup): f =>
      eventually(3000, "no formatted editor")(f.prose.isDefined).flatMap: _ =>
        f.tab("source").click()
        eventually(3000, "no source editor")(f.source.isDefined).flatMap: _ =>
          check(f.text.now() == markup, s"Source changed the text: '${f.text.now()}'")
          check(f.source.exists(_.textContent == markup), s"Source shows '${f.source.map(_.textContent)}'")
          f.tab("formatted").click()
          eventually(3000, "no formatted editor")(f.prose.isDefined).map: _ =>
            check(f.text.now() == markup, s"Formatted changed the text: '${f.text.now()}'")
            check(f.prose.exists(_.textContent == "x"), s"Formatted shows '${f.prose.map(_.textContent)}'")

  test("an edit in Source shows in Formatted"):
    withEditor("<B>a</B>"): f =>
      eventually(3000, "no formatted editor")(f.prose.isDefined).flatMap: _ =>
        f.tab("source").click()
        eventually(3000, "no source editor")(f.source.isDefined).flatMap: _ =>
          val cm = org.jpablo.graphexplorer.viewer.components.codeMirror.EditorView.findFromDOM(f.box.querySelector(".cm-editor"))
          cm.dispatch(new org.jpablo.graphexplorer.viewer.components.codeMirror.TransactionSpec {
            changes = new org.jpablo.graphexplorer.viewer.components.codeMirror.ChangeSpec {
              from = 0
              to = cm.state.doc.length
              insert = "<I>b</I>"
            }
          })
          check(f.text.now() == "<I>b</I>", s"the text is '${f.text.now()}'")
          f.tab("formatted").click()
          eventually(3000, "no formatted editor")(f.prose.isDefined).map: _ =>
            check(f.prose.exists(_.querySelector("i") != null), s"Formatted shows ${f.prose.map(_.innerHTML)}")
            check(f.text.now() == "<I>b</I>", s"the text is '${f.text.now()}'")

  // ---- the text -------------------------------------------------------------

  test("typing in Formatted updates the text and the preview"):
    withEditor("<B>Hello</B>"): f =>
      eventually(3000, "no formatted editor")(f.prose.isDefined).flatMap: _ =>
        typeAtEnd(f, "!")
        check(f.text.now() == "<B>Hello!</B>", s"the text is '${f.text.now()}'")
        eventually(3000, s"the preview does not show Hello!: '${f.previewText}'")(f.previewText.contains("Hello!"))

  test("a change of the text from outside goes into the editor, with no echo"):
    withEditor("<B>Hello</B>"): f =>
      eventually(3000, "no formatted editor")(f.prose.isDefined).flatMap: _ =>
        f.text.set("<I><I>World</I></I>")
        eventually(3000, s"the editor shows '${f.prose.map(_.textContent)}'")(f.prose.exists(_.textContent == "World")).map: _ =>
          check(f.prose.exists(_.querySelector("i") != null), "World is not italic")
          check(f.text.now() == "<I><I>World</I></I>", s"the editor rewrote the text: '${f.text.now()}'")

  test("a change from outside to a table shows Source"):
    withEditor("<B>Hello</B>"): f =>
      eventually(3000, "no formatted editor")(f.prose.isDefined).flatMap: _ =>
        f.text.set("<TABLE><TR><TD>a</TD></TR></TABLE>")
        eventually(3000, "no source editor")(f.source.isDefined && f.prose.isEmpty)

  test("bold on a selection of a plain label makes markup, and bold again gives plain text"):
    withFormatted("hello world"): f =>
      select(f, 1, 6)
      val bold = f.control("bold")
      check(!isActive(bold), "the bold button is active on plain text")
      bold.click()
      check(f.text.now() == "<B>hello</B> world", s"the text after bold is '${f.text.now()}'")
      check(isActive(bold), "the bold button is not active on bold text")
      bold.click()
      check(f.text.now() == "hello world", s"the text after the second bold is '${f.text.now()}'")

  test("Mod-b toggles bold, and Mod-z undoes it"):
    withFormatted("hello"): f =>
      selectAll(f)
      keydown(f.prose.get, "b", mod = true)
      check(f.text.now() == "<B>hello</B>", s"the text after Mod-b is '${f.text.now()}'")
      keydown(f.prose.get, "z", mod = true)
      check(f.text.now() == "hello", s"the text after Mod-z is '${f.text.now()}'")

  test("each style button gives its tag"):
    List("italic" -> "<I>", "underline" -> "<U>", "strike" -> "<S>", "overline" -> "<O>", "sub" -> "<SUB>", "sup" -> "<SUP>")
      .foldLeft(Future.unit): (acc, pair) =>
        val (name, tag) = pair
        acc.flatMap: _ =>
          withFormatted("hello"): f =>
            selectAll(f)
            f.control(name).click()
            check(f.text.now() == s"${tag}hello${tag.replace("<", "</")}", s"$name: the text is '${f.text.now()}'")

  test("the font face, the size and the color go into a FONT tag, and default removes them"):
    withFormatted("hello"): f =>
      selectAll(f)
      setValue(f.control("face"), "Helvetica", "change")
      check(f.text.now() == """<FONT FACE="Helvetica">hello</FONT>""", s"face: '${f.text.now()}'")
      selectAll(f)
      setValue(f.control("size"), "20", "change")
      check(f.text.now().contains("""POINT-SIZE="20""""), s"size: '${f.text.now()}'")
      selectAll(f)
      setValue(f.control("color"), "#00ff00", "input")
      check(f.text.now().toLowerCase.contains("""color="#00ff00""""), s"color: '${f.text.now()}'")
      selectAll(f)
      check(f.control("face").asInstanceOf[dom.html.Select].value == "Helvetica", "the face control does not show the face")
      check(f.control("size").asInstanceOf[dom.html.Input].value == "20", "the size control does not show the size")
      setValue(f.control("face"), "", "change")
      selectAll(f)
      setValue(f.control("size"), "", "change")
      selectAll(f)
      f.control("color-default").click()
      check(f.text.now() == "hello", s"default: '${f.text.now()}'")

  test("the alignment buttons change the lines of the selection"):
    withFormatted("a\nb"): f =>
      select(f, 1, 1)
      f.control("align-left").click()
      check(f.text.now() == """a<BR ALIGN="LEFT"/>b""", s"left: '${f.text.now()}'")
      check(isActive(f.control("align-left")), "the left button is not active")
      f.control("align-center").click()
      check(f.text.now() == "a\nb", s"center: '${f.text.now()}'")

  test("clear format removes the marks and the alignment"):
    withFormatted("""<B>a</B><FONT FACE="Menlo">b</FONT><BR ALIGN="RIGHT"/>""".stripMargin): f =>
      selectAll(f)
      f.control("clear").click()
      check(f.text.now() == "ab", s"the text is '${f.text.now()}'")

  // ---- the keys ---------------------------------------------------------------

  test("Enter saves, Shift+Enter adds a line, and Escape cancels"):
    withFormatted("Hello"): f =>
      val content = f.prose.get
      keydown(content, "Enter", shift = true)
      check(f.saves == 0, "Shift+Enter saved the label")
      check(f.lineCount == 2, s"the document has ${f.lineCount} lines after Shift+Enter")
      check(f.text.now() == "Hello\n", s"the text after Shift+Enter is '${f.text.now()}'")
      keydown(content, "Enter")
      check(f.saves == 1, s"Enter did not save the label (saves: ${f.saves})")
      check(f.lineCount == 2, s"Enter added a line: ${f.lineCount} lines")
      keydown(content, "Escape")
      check(f.cancels == 1, s"Escape did not cancel (cancels: ${f.cancels})")
      check(f.escaped == 0, "the Escape key got to the dialog too")

  test("Shift+Enter keeps the alignment and the marks of the line"):
    withFormatted("""<B>a</B><BR ALIGN="LEFT"/>"""): f =>
      keydown(f.prose.get, "Enter", shift = true)
      typeAtEnd(f, "b")
      check(f.text.now() == """<B>a</B><BR ALIGN="LEFT"/><B>b</B><BR ALIGN="LEFT"/>""", s"the text is '${f.text.now()}'")

  // ---- no rewrite -------------------------------------------------------------

  List(
    """<B><B>x</B></B>""",
    """<FONT COLOR="red" FACE="Menlo">x</FONT>""",
    """<b>lower</b> <i>case</i>"""
  ).foreach: markup =>
    test(s"an open and a save with no change keep the markup: $markup"):
      withFormatted(markup): f =>
        keydown(f.prose.get, "Enter")
        check(f.saves == 1, "Enter did not save")
        check(f.text.now() == markup, s"the text is '${f.text.now()}'")

  test("an edit and its undo give the original markup again"):
    val markup = """<FONT COLOR="red" FACE="Menlo"><B><B>x</B></B></FONT>"""
    withFormatted(markup): f =>
      typeAtEnd(f, "y")
      check(f.text.now() != markup, "the edit did not change the text")
      keydown(f.prose.get, "z", mod = true)
      check(f.text.now() == markup, s"the text after undo is '${f.text.now()}'")

  // ---- the fonts --------------------------------------------------------------

  private def style(e: dom.Element): dom.CSSStyleDeclaration = dom.window.getComputedStyle(e)

  test("the editor shows the fonts of the runs"):
    withFormatted("""<FONT FACE="Menlo">code</FONT> text"""): f =>
      val span = f.prose.get.querySelector("span[data-gx-font]")
      check(span != null, s"no font span in ${f.prose.get.innerHTML}")
      check(style(span).fontFamily.contains("Menlo"), s"the font family is '${style(span).fontFamily}'")

  test("the editor has the font of the element"):
    withFormatted("styled", NodeId("styled")): f =>
      val s = style(f.prose.get)
      check(s.fontFamily.contains("Courier"), s"the font family is '${s.fontFamily}'")
      check(s.fontSize == "28px", s"the font size is '${s.fontSize}'")
      check(s.color == "rgb(255, 0, 0)", s"the color is '${s.color}'")

  test("the face list has the faces of the diagram and the common faces"):
    withFormatted("hello"): f =>
      val options = f.control("face").querySelectorAll("option")
      val values  = (0 until options.length).map(i => options(i).asInstanceOf[dom.html.Option].value).toSet
      List("", "Courier", "Times", "Helvetica", "Menlo", "Arial").foreach: v =>
        check(values.contains(v), s"no face '$v' in $values")

end FormattedEditorSpec
