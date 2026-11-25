package org.jpablo.graphexplorer.viewer.components.labelEditor

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.html.*
import org.jpablo.graphexplorer.viewer.components.proseMirror.*
import org.jpablo.graphexplorer.viewer.formats.dot.HtmlLabels
import org.scalajs.dom

import scala.scalajs.js

/** The model of the formatted label editor: the schema, and the converters
  * between a label and a ProseMirror document.
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js
  * test bridge and the sbt client, a munit FailException arrives with no message.
  */
class ProseLabelSpec extends FunSuite:

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then
      println(s"[ProseLabelSpec] $message")
      throw AssertionError(message)

  private def schema = LabelSchema.schema

  private def line(align: Option[HtmlAlign], items: (String, HtmlFont)*): HtmlSpan =
    HtmlSpan(items.toList.map((s, f) => HtmlItem(s, f)), align)

  private val plain = HtmlFont()

  /** The text of each line of a document. */
  private def lineTexts(doc: Node): List[String] =
    (0 until doc.childCount).toList.map(i => doc.child(i).textContent)

  private def json(node: Node): String = js.JSON.stringify(node.toJSON())

  private def stateOf(d: Node): EditorState = EditorState.create(new EditorStateConfig { doc = d })

  // ---- HtmlText → doc → HtmlText -------------------------------------------

  private val roundTripLabels: List[(String, HtmlText)] = List(
    "fonts" -> HtmlText(List(
      line(None,
        "Range" -> HtmlFont(size = Some(16), name = Some("Menlo"), bold = true),
        " split" -> plain,
        " [0, 3]" -> HtmlFont(color = Some("#438173"))
      ),
      line(None, "middle" -> HtmlFont(size = Some(9.12345)))
    )),
    "each flag" -> HtmlText(List(
      line(None,
        "b" -> HtmlFont(bold = true),
        "i" -> HtmlFont(italic = true),
        "u" -> HtmlFont(underline = true),
        "s" -> HtmlFont(strike = true),
        "o" -> HtmlFont(overline = true),
        "sub" -> HtmlFont(sub = true),
        "sup" -> HtmlFont(sup = true)
      )
    )),
    "all flags with a font" -> HtmlText(List(
      line(None,
        "x" -> HtmlFont(Some(12), Some("Helvetica"), Some("red"), true, true, true, true, false, true, true),
        "y" -> HtmlFont(Some(12), Some("Helvetica"), Some("red"), true, true, true, true, true, false, true)
      )
    )),
    "alignments" -> HtmlText(List(
      line(Some(HtmlAlign.Left), "left" -> plain),
      line(Some(HtmlAlign.Center), "center" -> plain),
      line(Some(HtmlAlign.Right), "right" -> plain),
      line(Some(HtmlAlign.Text_), "text" -> plain),
      line(None, "none" -> plain)
    )),
    "empty lines" -> HtmlText(List(
      line(None),
      line(None, "a" -> plain),
      line(Some(HtmlAlign.Left)),
      line(None)
    )),
    "one empty line" -> HtmlText(List(line(None))),
    "special characters" -> HtmlText(List(
      line(None,
        "<a> & \"b\"" -> HtmlFont(name = Some("Face \"<&>\""), color = Some("#00ff00")),
        " 'c' > d" -> plain
      )
    )),
    "adjacent runs of the same font" -> HtmlText(List(
      line(None, "a" -> HtmlFont(bold = true), "b" -> HtmlFont(bold = true), "" -> plain, "c" -> plain)
    ))
  )

  roundTripLabels.foreach: (name, text) =>
    test(s"a label goes to a document and back: $name"):
      val back = ProseLabel.fromDoc(ProseLabel.toDoc(text))
      val want = ProseLabel.normalize(text)
      check(back == want, s"$name: fromDoc(toDoc(t)) != normalize(t)\n got: $back\nwant: $want")

    test(s"the markup of a document reads back to the same label: $name"):
      val markup = HtmlWriter.write(ProseLabel.fromDoc(ProseLabel.toDoc(text)))
      val back   = HtmlParser.parse(markup)
      val want   = HtmlLabel.Text(ProseLabel.normalize(text))
      check(back.contains(want), s"$name: parse(write(fromDoc(toDoc(t)))) != normalize(t)\nmarkup: $markup\n got: $back")

  test("normalize joins adjacent runs of the same font and removes empty runs"):
    val text = HtmlText(List(line(None, "a" -> HtmlFont(bold = true), "" -> plain, "b" -> HtmlFont(bold = true), "c" -> plain)))
    val want = HtmlText(List(line(None, "ab" -> HtmlFont(bold = true), "c" -> plain)))
    check(ProseLabel.normalize(text) == want, s"got ${ProseLabel.normalize(text)}")

  test("a document obeys the schema"):
    roundTripLabels.foreach: (name, text) =>
      val doc = ProseLabel.toDoc(text)
      try doc.check()
      catch case e: js.JavaScriptException => throw AssertionError(s"$name: the document is not valid: ${e.getMessage}")

  test("a font mark holds only the attributes of the run itself"):
    val markup = """<FONT FACE="Menlo"><B>a</B><FONT POINT-SIZE="16">b</FONT></FONT>c"""
    val doc    = ProseLabel.fromMarkup(markup).getOrElse(throw AssertionError("the markup is not a text label"))
    val l      = doc.child(0)
    check(l.childCount == 3, s"want 3 text nodes, got ${json(l)}")
    def font(i: Int) = schema.marks("font").isInSet(l.child(i).marks).toOption.map(m => js.JSON.stringify(m.attrs))
    check(font(0).contains("""{"face":"Menlo","size":null,"color":null}"""), s"a: ${font(0)}")
    check(font(1).contains("""{"face":"Menlo","size":16,"color":null}"""), s"b: ${font(1)}")
    check(font(2).isEmpty, s"c: ${font(2)}")
    check(schema.marks("bold").isInSet(l.child(0).marks).isDefined, "a is not bold")

  test("a run with sub and sup keeps sub, as the canvas draws it"):
    val text = HtmlText(List(line(None, "x" -> HtmlFont(sub = true, sup = true))))
    val doc  = ProseLabel.toDoc(text)
    doc.check()
    val back = ProseLabel.fromDoc(doc)
    check(back == HtmlText(List(line(None, "x" -> HtmlFont(sub = true)))), s"got $back")

  // ---- the no-op rule ------------------------------------------------------

  private val handWritten = List(
    """<FONT FACE="Menlo"><FONT POINT-SIZE="16"><B><B>Range</B></B></FONT></FONT><BR/>middle""",
    """<b>lower</b> <i>case</i> tags""",
    """<FONT FACE="Menlo" POINT-SIZE="16"><B>Range</B></FONT><BR/>middle""",
    """a<BR ALIGN="LEFT"/>b<BR ALIGN="RIGHT"/>""",
    """<font color="red">x</font><font color="red">y</font>"""
  )

  handWritten.foreach: m =>
    test(s"an unchanged document keeps the hand-written markup: $m"):
      val doc = ProseLabel.fromMarkup(m).getOrElse(throw AssertionError(s"not a text label: $m"))
      val out = ProseLabel.toLabelText(doc, m)
      check(out == m, s"got $out")

  test("a changed document saves as canonical markup"):
    val m     = handWritten.head
    val doc   = ProseLabel.fromMarkup(m).getOrElse(throw AssertionError("not a text label"))
    val state = stateOf(doc)
    val end   = 1 + doc.child(0).content.size
    val tr    = state.tr.insertText("!", end)
    val out   = ProseLabel.toLabelText(tr.doc, m)
    val want  = """<FONT FACE="Menlo" POINT-SIZE="16"><B>Range!</B></FONT><BR/>middle"""
    check(out == want, s"got $out")

  // ---- plain text ----------------------------------------------------------

  test("a plain text gives one line for each line break"):
    val doc = ProseLabel.fromPlain("first\nsecond\n\nfourth")
    check(lineTexts(doc) == List("first", "second", "", "fourth"), s"got ${json(doc)}")
    doc.check()

  test("an unchanged plain text saves unchanged"):
    List("", "one", "a\nb", "a\n\n", "x & <y>").foreach: s =>
      val out = ProseLabel.toLabelText(ProseLabel.fromPlain(s), s)
      check(out == s, s"'$s' saves as '$out'")

  test("a document with only plain text saves as plain text"):
    val doc = ProseLabel.fromPlain("first\nsecond\n\nfourth")
    val out = ProseLabel.toLabelText(doc, "an other label")
    check(out == "first\nsecond\n\nfourth", s"got $out")

  test("bold makes the plain text markup"):
    val doc   = ProseLabel.fromPlain("first\nsecond\n\nfourth")
    val state = stateOf(ProseLabel.fromPlain("first\nsecond\n\nfourth"))
    val tr    = state.tr.addMark(1, 6, schema.mark("bold", null))
    val out   = ProseLabel.toLabelText(tr.doc, "first\nsecond\n\nfourth")
    check(doc.eq(state.doc), "setup: the two documents are not equal")
    check(out == "<B>first</B><BR/>second<BR/><BR/>fourth", s"got $out")
    check(HtmlLabels.isHtml(out), s"not markup: $out")

  test("an alignment makes the plain text markup"):
    val state = stateOf(ProseLabel.fromPlain("first\nsecond"))
    val tr    = state.tr.setNodeMarkup(0, null, js.Dictionary[js.Any]("align" -> "left"))
    val out   = ProseLabel.toLabelText(tr.doc, "first\nsecond")
    check(out == """first<BR ALIGN="LEFT"/>second""", s"got $out")

  test("a plain text that looks like markup saves as markup with the same text"):
    val doc = ProseLabel.fromPlain("a <b>c</b>")
    val out = ProseLabel.toLabelText(doc, "")
    check(HtmlLabels.isHtml(out), s"the dialog reads '$out' as plain text")
    val back = HtmlParser.parse(out)
    check(back.contains(HtmlLabel.Text(HtmlText(List(line(None, "a <b>c</b>" -> plain))))), s"'$out' reads as $back")

  test("fromLabelText reads plain text and markup, as the dialogs do"):
    val p = ProseLabel.fromLabelText("a\nb").getOrElse(throw AssertionError("plain text is not editable"))
    check(p.eq(ProseLabel.fromPlain("a\nb")), s"plain: ${json(p)}")
    val m = ProseLabel.fromLabelText("<B>a</B>").getOrElse(throw AssertionError("markup is not editable"))
    check(lineTexts(m) == List("a"), s"markup: ${json(m)}")
    check(ProseLabel.fromLabelText("<TABLE><TR><TD>a</TD></TR></TABLE>").isEmpty, "a table is editable")

  // ---- canEdit -------------------------------------------------------------

  test("canEdit accepts plain text and text markup"):
    List("", "hello", "a\nb", "<B>x</B>", """<FONT FACE="Menlo">a</FONT><BR/>b""", "a<BR/>b").foreach: s =>
      check(ProseLabel.canEdit(s), s"'$s' is not editable")

  test("canEdit refuses tables, images and invalid markup"):
    List(
      "<TABLE><TR><TD>a</TD></TR></TABLE>",
      """<IMG SRC="a.png"></IMG>""",
      """<B>x</B><IMG SRC="a.png"/>""",
      "<B>x</I>",
      "<B>x"
    ).foreach: s =>
      check(!ProseLabel.canEdit(s), s"'$s' is editable")

  // ---- the DOM parser (paste) ----------------------------------------------

  private def pasted(html: String): HtmlText = ProseLabel.fromDoc(LabelSchema.parseHtml(html))

  test("a paste keeps only the lines, the text and the allowed marks"):
    val got  = pasted("""<h1>Title</h1><p>a <strong>b</strong> <a href="x">c</a> <img src="y"></p>""")
    val want = HtmlText(List(line(None, "Title" -> plain), line(None, "a " -> plain, "b" -> HtmlFont(bold = true), " c" -> plain)))
    check(got == want, s"got $got")

  test("a paste reads the style tags and the CSS styles"):
    val got = pasted(
      """<p><span style="font-weight: 700">w</span><em>e</em><span style="font-style: italic">i</span>""" +
        """<u>u</u><s>s</s><del>d</del><strike>k</strike><sub>1</sub><sup>2</sup>""" +
        """<span style="text-decoration: overline">o</span><span style="text-decoration: underline line-through">x</span></p>"""
    )
    val want = HtmlText(List(line(None,
      "w" -> HtmlFont(bold = true),
      "ei" -> HtmlFont(italic = true),
      "u" -> HtmlFont(underline = true),
      "sdk" -> HtmlFont(strike = true),
      "1" -> HtmlFont(sub = true),
      "2" -> HtmlFont(sup = true),
      "o" -> HtmlFont(overline = true),
      "x" -> HtmlFont(underline = true, strike = true)
    )))
    check(got == want, s"got $got")

  test("a paste does not keep the fonts of a web page"):
    val got = pasted("""<p><span style="font-family: Arial; font-size: 20px; color: red">x</span><font face="Arial">y</font></p>""")
    check(got == HtmlText(List(line(None, "xy" -> plain))), s"got $got")

  test("a paste of a Google Docs wrapper is not bold"):
    val got = pasted("""<b style="font-weight: normal" id="docs-internal-guid-1"><p>a</p><p><span style="font-weight: 700">b</span></p></b>""")
    check(got == HtmlText(List(line(None, "a" -> plain), line(None, "b" -> HtmlFont(bold = true)))), s"got $got")

  test("a paste of block elements and line breaks gives one line for each"):
    val got = pasted("""<div><p>a</p><p>b</p></div><ul><li>c</li><li>d</li></ul><p>e<br>f</p><p style="text-align: right">g</p>""")
    val want = HtmlText(List(
      line(None, "a" -> plain), line(None, "b" -> plain), line(None, "c" -> plain), line(None, "d" -> plain),
      line(None, "e" -> plain), line(None, "f" -> plain), line(Some(HtmlAlign.Right), "g" -> plain)
    ))
    check(got == want, s"got $got")

  test("the DOM of a document parses back to the same document"):
    roundTripLabels.foreach: (name, text) =>
      val doc = ProseLabel.toDoc(text)
      val div = dom.document.createElement("div")
      div.appendChild(DOMSerializer.fromSchema(schema).serializeFragment(doc.content))
      val back = LabelSchema.parseHtml(div.innerHTML)
      check(back.eq(doc), s"$name: ${div.innerHTML}\n got: ${json(back)}\nwant: ${json(doc)}")

  private def domOf(text: HtmlText): dom.Element =
    val div = dom.document.createElement("div")
    div.appendChild(DOMSerializer.fromSchema(schema).serializeFragment(ProseLabel.toDoc(text).content))
    div

  test("the DOM of a document has the CSS of the canvas"):
    val text = HtmlText(List(
      line(Some(HtmlAlign.Left), "m" -> HtmlFont(size = Some(12), name = Some("Menlo"), color = Some("#438173"), bold = true)),
      line(None, "t" -> HtmlFont(name = Some("Times-Roman"), italic = true, sub = true), "o" -> HtmlFont(overline = true, underline = true))
    ))
    // The browser writes the CSS in its own form: no quotes when the name
    // needs none, and the color as rgb().
    val html = domOf(text).innerHTML
    List(
      "text-align: left",
      "text-align: center",
      "font-family: Menlo, monospace",
      "font-family: Times-Roman;",
      "font-size: 16px",
      "color: rgb(67, 129, 115)",
      "font-weight: bold",
      "font-style: italic",
      "vertical-align: sub",
      "text-decoration: underline",
      "text-decoration: overline"
    ).foreach: css =>
      check(html.contains(css), s"no '$css' in $html")

  // ---- the editor view -------------------------------------------------------

  test("an editor view toggles bold, and undo and redo work"):
    val place = dom.document.createElement("div")
    dom.document.body.appendChild(place)
    val initial = EditorState.create(new EditorStateConfig:
      doc = ProseLabel.fromPlain("hello")
      plugins = js.Array(history.history(), keymap.keymap(commands.baseKeymap))
    )
    val view = new EditorView(place, new DirectEditorProps { var state = initial })
    try
      val dispatch: js.Function1[Transaction, Unit] = tr => view.dispatch(tr)
      view.dispatch(view.state.tr.setSelection(TextSelection.create(view.state.doc, 1, 6)))
      val toggled = commands.toggleMark(schema.marks(LabelSchema.Bold))(view.state, dispatch, view)
      check(toggled, "toggleMark did not act")
      check(ProseLabel.toLabelText(view.state.doc, "hello") == "<B>hello</B>", s"bold: ${json(view.state.doc)}")
      check(Option(view.dom.querySelector("b")).isDefined, s"no <b> in ${view.dom.innerHTML}")
      check(history.undoDepth(view.state) == 1, s"undo depth ${history.undoDepth(view.state)}")
      check(history.undo(view.state, dispatch, view), "undo did not act")
      check(ProseLabel.toLabelText(view.state.doc, "hello") == "hello", s"undo: ${json(view.state.doc)}")
      check(history.redo(view.state, dispatch, view), "redo did not act")
      check(ProseLabel.toLabelText(view.state.doc, "hello") == "<B>hello</B>", s"redo: ${json(view.state.doc)}")
    finally
      view.destroy()
      place.remove()

  test("a font name and a color do not add CSS"):
    val text = HtmlText(List(line(None, "x" -> HtmlFont(name = Some("Evil; color: red"), color = Some("blue; font-size: 99px")))))
    val span = domOf(text).querySelector("span").asInstanceOf[dom.HTMLElement]
    check(span.style.color == "", s"color: ${span.style.color}")
    check(span.style.fontSize == "", s"font-size: ${span.style.fontSize}")
    check(span.style.fontFamily.contains("Evil; color: red"), s"font-family: ${span.style.fontFamily}")

end ProseLabelSpec
