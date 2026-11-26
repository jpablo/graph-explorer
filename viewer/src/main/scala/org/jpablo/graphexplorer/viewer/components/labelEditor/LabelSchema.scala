package org.jpablo.graphexplorer.viewer.components.labelEditor

import org.jpablo.graphexplorer.graphviz.metrics.FontMetrics
import org.jpablo.graphexplorer.viewer.components.proseMirror.*
import org.scalajs.dom

import scala.scalajs.js

/** The ProseMirror schema of a Graphviz text label.
  *
  * The schema allows only what a Graphviz text label can have:
  *
  *   - The document is one or more lines. A line is a block with text only. It
  *     has the attribute `align`: null (the default of the label), "left",
  *     "center", "right" or "text". There is no hard break: each line break
  *     starts a new line.
  *   - The marks are `bold`, `italic`, `underline`, `overline`, `strike`,
  *     `sub` and `sup`, and `font` with the attributes `face`, `size` (points)
  *     and `color`. Each attribute of `font` can be null. A text has `sub` or
  *     `sup`, not the two.
  *
  * A table label has a document with one `table`. A table has `row`s, a row
  * has `cell`s, and a cell has lines, or one `fixed` node for a cell that the
  * editor cannot edit (a nested table or an image). The table, the rows and
  * the cells are the structure of the label: the editor does not change them
  * (see `FormattedEditor`). A cell has the attributes `colspan`, `rowspan`,
  * `align` and `bgcolor` of its `<TD>`, for the display only.
  *
  * The DOM of a line and of a mark has the CSS of the canvas: the font
  * family, the size in pixels (1 pt is 4/3 px, as in the preview), the color,
  * the weight, the style, the decorations and the vertical position.
  *
  * The parse rules read a paste from a web page. A paste keeps the lines, the
  * text and the style marks. A paste does not keep the font family, the size
  * and the color of a web page, because these come from the style sheet of the
  * page and are almost never what the label needs. Only the DOM of this schema
  * gives a `font` mark: a copy from a label editor keeps the fonts.
  */
object LabelSchema:

  // The names of the node types.
  val Doc   = "doc"
  val Line  = "line"
  val Text  = "text"
  val Table = "table"
  val Row   = "row"
  val Cell  = "cell"
  val Fixed = "fixed"

  /** The name of the attribute of a fixed cell: "table" or "image". */
  val FixedKind = "kind"

  // The names of the mark types. The order is the order of the marks in the
  // DOM, from the outer element to the inner element. It is the order of the
  // tags that HtmlWriter writes.
  val Font      = "font"
  val Bold      = "bold"
  val Italic    = "italic"
  val Underline = "underline"
  val Overline  = "overline"
  val Strike    = "strike"
  val Sub       = "sub"
  val Sup       = "sup"

  /** The name of the attribute of a line. */
  val AlignName = "align"

  // The values of the `align` attribute of a line.
  val AlignLeft   = "left"
  val AlignCenter = "center"
  val AlignRight  = "right"
  val AlignText   = "text"

  private val alignValues = Set(AlignLeft, AlignCenter, AlignRight, AlignText)

  // The data attributes of the DOM of this schema.
  private val LineAttr  = "data-gx-line"
  private val AlignAttr = "data-align"
  private val FontAttr  = "data-gx-font"
  private val FaceAttr  = "data-face"
  private val SizeAttr  = "data-size"
  private val ColorAttr = "data-color"

  lazy val schema: Schema =
    val nodeSpecs = js.Dictionary[NodeSpec](
      Doc   -> nodeSpec(content = s"$Line+ | $Table"),
      Line  -> lineSpec,
      Text  -> nodeSpec(group = "inline"),
      Table -> tableSpec,
      Row   -> rowSpec,
      Cell  -> cellSpec,
      Fixed -> fixedSpec
    )
    val markSpecs = js.Dictionary[MarkSpec](
      Font      -> fontSpec,
      Bold      -> boldSpec,
      Italic    -> italicSpec,
      Underline -> decorationSpec("underline", List(tagRule("u")), "u"),
      Overline  -> decorationSpec("overline", Nil, "span"),
      Strike    -> decorationSpec("line-through", List("s", "strike", "del").map(tagRule(_)), "s"),
      Sub       -> verticalSpec("sub", "sub", Sup),
      Sup       -> verticalSpec("sup", "super", Sub)
    )
    new Schema(new SchemaSpec:
      var nodes = nodeSpecs
      marks = markSpecs
    )

  /** The parser of the `parseDOM` rules. */
  lazy val parser: DOMParser = DOMParser.fromSchema(schema)

  /** The document of an HTML text, as a paste gives it. The HTML goes into an
    * inert document, so the browser loads no image and runs no script.
    */
  def parseHtml(html: String): Node =
    val body = new dom.DOMParser().parseFromString(html, dom.MIMEType.`text/html`).asInstanceOf[dom.HTMLDocument].body
    parser.parse(body)

  // ---- the lines -------------------------------------------------------------

  /** The block elements that give a line. */
  private val lineTags = List("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "pre", "blockquote", "dd", "dt")

  /** The block elements. An element that holds one of these is not a line:
    * the parser reads its children, and each child gives a line.
    */
  private val blockTags = lineTags.toSet ++ Set(
    "ul", "ol", "dl", "table", "section", "article", "header", "footer", "address", "figure", "hr", "form"
  )

  private def lineSpec: NodeSpec =
    // The DOM of this schema. The rule keeps all the white space of the text.
    val own = tagRule(s"div[$LineAttr]", el => alignAttrs(Option(el.getAttribute(AlignAttr)).filter(alignValues)))
    own.priority = 60
    own.preserveWhitespace = "full"
    val others = lineTags.map(tag => tagRule(tag, el => if hasBlockChild(el) then NoMatch else alignAttrs(cssAlign(el))))
    // A <br> closes the line. The text after it goes into a new line.
    val br = tagRule("br")
    br.closeParent = true
    val spec = nodeSpec(content = "text*", marks = "_")
    spec.attrs = js.Dictionary(AlignName -> attrSpec(null))
    spec.parseDOM = js.Array((own :: br :: others)*)
    val toDOM: js.Function1[Node, DOMOutputSpec] = node =>
      val align = stringAttr(node.attrs, AlignName)
      val attrs = js.Dictionary[js.Any](LineAttr -> "", "style" -> s"text-align: ${align.fold("center")(cssTextAlign)};")
      align.foreach(a => attrs(AlignAttr) = a)
      js.Array[js.Any]("div", attrs, 0)
    spec.toDOM = toDOM
    spec

  private def alignAttrs(align: Option[String]): js.Any =
    js.Dictionary[js.Any](AlignName -> stringOrNull(align))

  /** The alignment of a pasted block, from its CSS `text-align`. */
  private def cssAlign(el: dom.HTMLElement): Option[String] =
    el.style.textAlign match
      case "left"   => Some(AlignLeft)
      case "center" => Some(AlignCenter)
      case "right"  => Some(AlignRight)
      case _        => None

  /** The CSS `text-align` of a line. The default of Graphviz is the center.
    * The layout of the canvas draws a line with the alignment "text" at the
    * center too.
    */
  private def cssTextAlign(align: String): String =
    align match
      case AlignLeft  => "left"
      case AlignRight => "right"
      case _          => "center"

  private def hasBlockChild(el: dom.HTMLElement): Boolean =
    (0 until el.children.length).exists(i => blockTags(el.children(i).tagName.toLowerCase))

  // ---- the tables ------------------------------------------------------------

  // The DOM of the tables has no parse rules: a paste never gives a table.

  private def tableSpec: NodeSpec =
    val spec = nodeSpec(content = s"$Row+")
    spec.isolating = true
    val toDOM: js.Function1[Node, DOMOutputSpec] = _ =>
      js.Array[js.Any]("table", js.Dictionary[js.Any]("class" -> "gx-label-table"), js.Array[js.Any]("tbody", 0))
    spec.toDOM = toDOM
    spec

  private def rowSpec: NodeSpec =
    val spec = nodeSpec(content = s"$Cell+")
    val toDOM: js.Function1[Node, DOMOutputSpec] = _ => js.Array[js.Any]("tr", 0)
    spec.toDOM = toDOM
    spec

  private def cellSpec: NodeSpec =
    val spec = nodeSpec(content = s"$Line+ | $Fixed")
    spec.isolating = true
    spec.attrs = js.Dictionary(
      "colspan" -> attrSpec(1),
      "rowspan" -> attrSpec(1),
      "align"   -> attrSpec(null),
      "bgcolor" -> attrSpec(null)
    )
    val toDOM: js.Function1[Node, DOMOutputSpec] = node =>
      val attrs = js.Dictionary[js.Any]("data-gx-cell" -> "")
      numberAttr(node.attrs, "colspan").filter(_ > 1).foreach(n => attrs("colspan") = n)
      numberAttr(node.attrs, "rowspan").filter(_ > 1).foreach(n => attrs("rowspan") = n)
      // The lines with no alignment of their own get the alignment of the cell (style.scss).
      stringAttr(node.attrs, "align").foreach(a => attrs("data-cell-align") = a)
      stringAttr(node.attrs, "bgcolor").filter(isCssColor).foreach(c => attrs("style") = s"background-color: $c;")
      js.Array[js.Any]("td", attrs, 0)
    spec.toDOM = toDOM
    spec

  private def fixedSpec: NodeSpec =
    val spec = nodeSpec()
    spec.atom = true
    spec.selectable = false
    spec.attrs = js.Dictionary(FixedKind -> attrSpec("table"))
    val toDOM: js.Function1[Node, DOMOutputSpec] = node =>
      val what = if stringAttr(node.attrs, FixedKind).contains("image") then "image" else "table"
      js.Array[js.Any](
        "span",
        js.Dictionary[js.Any]("class" -> "gx-label-fixed", "contenteditable" -> "false"),
        s"($what: edit it in the source)"
      )
    spec.toDOM = toDOM
    spec

  // ---- the marks -------------------------------------------------------------

  private def fontSpec: MarkSpec =
    val own = tagRule(
      s"span[$FontAttr]",
      el =>
        js.Dictionary[js.Any](
          "face"  -> stringOrNull(Option(el.getAttribute(FaceAttr))),
          "size"  -> numberOrNull(Option(el.getAttribute(SizeAttr)).flatMap(_.toDoubleOption)),
          "color" -> stringOrNull(Option(el.getAttribute(ColorAttr)))
        )
    )
    own.priority = 60
    val spec = markSpec(List(own))
    spec.attrs = js.Dictionary("face" -> attrSpec(null), "size" -> attrSpec(null), "color" -> attrSpec(null))
    val toDOM: js.Function2[Mark, Boolean, DOMOutputSpec] = (mark, _) =>
      val face  = stringAttr(mark.attrs, "face")
      val size  = numberAttr(mark.attrs, "size")
      val color = stringAttr(mark.attrs, "color")
      val attrs = js.Dictionary[js.Any](FontAttr -> "")
      face.foreach(f => attrs(FaceAttr) = f)
      size.foreach(s => attrs(SizeAttr) = exactNumber(s))
      color.foreach(c => attrs(ColorAttr) = c)
      val css =
        face.map(f => s"font-family: ${fontFamilyCss(f)}") ++
          size.map(s => s"font-size: ${number(s * 4 / 3)}px") ++
          color.filter(isCssColor).map(c => s"color: $c")
      if css.nonEmpty then attrs("style") = css.map(_ + ";").mkString(" ")
      js.Array[js.Any]("span", attrs, 0)
    spec.toDOM = toDOM
    spec

  private def boldSpec: MarkSpec =
    val rules = List(
      tagRule("strong"),
      // Google Docs puts the whole copy in <b style="font-weight: normal">.
      tagRule("b", el => if el.style.fontWeight == "normal" || el.style.fontWeight == "400" then NoMatch else NoAttrs),
      clearRule("font-weight=400", Bold),
      clearRule("font-weight=normal", Bold),
      styleRule("font-weight", v => "^(bold(er)?|[6-9]\\d{2,})$".r.matches(v.trim))
    )
    markSpec(rules, "b", "font-weight: bold")

  private def italicSpec: MarkSpec =
    val rules = List(
      tagRule("i"),
      tagRule("em"),
      styleRule("font-style", _.trim == "italic"),
      clearRule("font-style=normal", Italic)
    )
    markSpec(rules, "i", "font-style: italic")

  /** A mark of a CSS `text-decoration` line: underline, overline or line-through. */
  private def decorationSpec(line: String, tags: List[ParseRule], tag: String): MarkSpec =
    def has(value: String) = value.split("[\\s,]+").contains(line)
    // One value can have more than one line, for example "underline
    // line-through". The rule does not consume the property, so the rules of
    // the other lines read it too.
    val styles = List("text-decoration", "text-decoration-line").map(styleRule(_, has))
    styles.foreach(_.consuming = false)
    val rules = tags ++ styles
    markSpec(rules, tag, s"text-decoration: $line")

  /** The mark `sub` or `sup`. The canvas draws it with the size of the run and
    * a shift of the baseline (`baseline-shift`). The editor does the same with
    * `vertical-align`: it does not make the text smaller.
    */
  private def verticalSpec(tag: String, cssValue: String, other: String): MarkSpec =
    val rules = List(tagRule(tag), styleRule("vertical-align", _.trim == cssValue))
    val spec  = markSpec(rules, tag, s"vertical-align: $cssValue; font-size: inherit")
    spec.excludes = s"$tag $other"
    spec

  // ---- CSS -------------------------------------------------------------------

  /** The CSS generic families. They have no quotes. */
  private val genericFamilies = Set(
    "serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui",
    "ui-serif", "ui-sans-serif", "ui-monospace", "ui-rounded", "math", "emoji", "fangsong"
  )

  /** The CSS `font-family` of a Graphviz font name.
    *
    * The rule is the rule of the SVG of the canvas: a monospaced font name that
    * gets the Courier widths (`FontMetrics.isMonospacedAlias`) gets the
    * fallback `monospace`. The canvas also changes a PostScript name (for
    * example "Times-Roman") into a family. The editor does not: the browser
    * uses its default font for that name.
    */
  def fontFamilyCss(face: String): String =
    val name = face.trim
    if genericFamilies(name.toLowerCase) then name.toLowerCase
    else
      val quoted = "\"" + name.flatMap {
        case '\\' => "\\\\"
        case '"'  => "\\\""
        case '\n' => "\\A "
        case c    => c.toString
      } + "\""
      if FontMetrics.isMonospacedAlias(name) then s"$quoted, monospace" else quoted

  /** True for a color that CSS knows with the same meaning: a hex color or a
    * name. A Graphviz color such as "0.5 0.3 0.9" (HSV) or "/x11/red" gets no
    * CSS color, and the text has the color of the editor.
    */
  private[labelEditor] def isCssColor(color: String): Boolean =
    "#[0-9A-Fa-f]{3,8}|[A-Za-z]+".r.matches(color)

  /** A whole number has no ".0". Other numbers have 3 decimals at most. */
  private def number(d: Double): String = exactNumber(math.round(d * 1000) / 1000.0)

  /** A whole number has no ".0". Other numbers keep all their decimals, so
    * the parse of the text gives the same number.
    */
  private def exactNumber(d: Double): String =
    if d.isWhole && math.abs(d) < 1e15 then d.toLong.toString else d.toString

  // ---- helpers ---------------------------------------------------------------

  /** `getAttrs` returns false: the rule does not match. */
  private val NoMatch: js.Any = false

  /** `getAttrs` returns null: the rule matches, with no attributes. */
  private val NoAttrs: js.Any = null

  private[labelEditor] def stringAttr(attrs: Attrs, name: String): Option[String] =
    attrs.get(name).filter(v => js.typeOf(v) == "string").map(_.asInstanceOf[String])

  private[labelEditor] def numberAttr(attrs: Attrs, name: String): Option[Double] =
    attrs.get(name).filter(v => js.typeOf(v) == "number").map(_.asInstanceOf[Double])

  /** The value of an attribute: the string, or null. */
  private[labelEditor] def stringOrNull(value: Option[String]): js.Any = value.map(s => s: js.Any).getOrElse(null)

  /** The value of an attribute: the number, or null. */
  private[labelEditor] def numberOrNull(value: Option[Double]): js.Any = value.map(d => d: js.Any).getOrElse(null)

  private def attrSpec(value: js.Any): AttributeSpec =
    val spec = new AttributeSpec {}
    spec.default = value
    spec

  private def nodeSpec(content: String = "", group: String = "", marks: String = ""): NodeSpec =
    val spec = new NodeSpec {}
    if content.nonEmpty then spec.content = content
    if group.nonEmpty then spec.group = group
    if marks.nonEmpty then spec.marks = marks
    spec

  /** A mark with these parse rules. Its DOM is `tag` with the CSS `css`. */
  private def markSpec(rules: List[ParseRule], tag: String = "", css: String = ""): MarkSpec =
    val spec = new MarkSpec {}
    spec.parseDOM = js.Array(rules*)
    if tag.nonEmpty then
      val toDOM: js.Function2[Mark, Boolean, DOMOutputSpec] =
        (_, _) => js.Array[js.Any](tag, js.Dictionary[js.Any]("style" -> s"$css;"), 0)
      spec.toDOM = toDOM
    spec

  private def tagRule(selector: String, attrs: dom.HTMLElement => js.Any = _ => NoAttrs): ParseRule =
    val rule = new ParseRule {}
    rule.tag = selector
    val getAttrs: js.Function1[js.Any, js.Any] = n => attrs(n.asInstanceOf[dom.HTMLElement])
    rule.getAttrs = getAttrs
    rule

  /** A rule for the CSS property `property`. It matches when `test` is true for the value. */
  private def styleRule(property: String, test: String => Boolean): ParseRule =
    val rule = new ParseRule {}
    rule.style = property
    val getAttrs: js.Function1[js.Any, js.Any] = v => if test(v.asInstanceOf[String]) then NoAttrs else NoMatch
    rule.getAttrs = getAttrs
    rule

  /** A rule that removes the mark `markName`, for example `font-weight: normal`. */
  private def clearRule(style: String, markName: String): ParseRule =
    val rule = new ParseRule {}
    rule.style = style
    val clear: js.Function1[Mark, Boolean] = _.`type`.name == markName
    rule.clearMark = clear
    rule

end LabelSchema
