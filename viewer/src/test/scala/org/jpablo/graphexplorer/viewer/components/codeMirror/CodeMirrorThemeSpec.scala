package org.jpablo.graphexplorer.viewer.components.codeMirror

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.util.Try

/** The colors of the source editor (`CodeMirror`) in a theme of the app.
  *
  * The editor had no theme of its own, so CodeMirror used its light default
  * colors in each theme of the app. In a dark theme this gave a light gutter
  * in a dark panel, dark blue text on a dark background, and a light
  * selection behind light text.
  *
  * The test page has no stylesheet of the app. Each test puts the color
  * tokens of one theme on the box of the editor, as `style.css` does for
  * `[data-theme]`. The test reads the colors that the browser computes, and
  * it calculates the WCAG contrast ratio.
  */
class CodeMirrorThemeSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "CodeMirrorThemeSpec"

  override def munitFixtures = List(mockStorageFixture())

  /** WCAG AA for body text. */
  private val textContrast = 4.5

  /** WCAG AA for large text and for parts of the interface. The line numbers
    * and the text in a selection use this limit.
    */
  private val secondaryContrast = 3.0

  /** The gutter is a part of the panel. A larger ratio than this one shows a
    * strip of a different tone, for example a light strip in a dark panel.
    */
  private val maxGutterStep = 1.3

  /** A selection with a smaller ratio than this one is not visible. */
  private val minSelectionStep = 1.2

  /** One theme of the app: its color scheme and its color tokens, as
    * `style.css` declares them.
    */
  private case class AppTheme(name: String, scheme: String, tokens: Seq[(String, String)])

  private val workbench = AppTheme(
    "workbench",
    "dark",
    Seq(
      "--color-base-100"     -> "#1d2021",
      "--color-base-200"     -> "#191b1c",
      "--color-base-300"     -> "#2f3436",
      "--color-base-content" -> "#e6e4df",
      "--color-primary"      -> "#d9a441",
      "--color-secondary"    -> "#7fb2d9",
      "--color-accent"       -> "#8fbf7f",
      "--color-neutral"      -> "#242829",
      "--color-info"         -> "#7fb2d9",
      "--color-success"      -> "#8fbf7f",
      "--color-warning"      -> "#d9a441",
      "--color-error"        -> "#d9776b"
    )
  )

  private val drafting = AppTheme(
    "drafting",
    "light",
    Seq(
      "--color-base-100"     -> "#f7f8f6",
      "--color-base-200"     -> "#f2f4f2",
      "--color-base-300"     -> "#c3ccc6",
      "--color-base-content" -> "#1d3640",
      "--color-primary"      -> "#0b5e7d",
      "--color-secondary"    -> "#5c7480",
      "--color-accent"       -> "#8a5a12",
      "--color-neutral"      -> "#1d3640",
      "--color-info"         -> "#0b5e7d",
      "--color-success"      -> "#4a6b2f",
      "--color-warning"      -> "#8a5a12",
      "--color-error"        -> "#a8322a"
    )
  )

  /** A source with each kind of token that the DOT grammar colors: a comment,
    * a keyword, a name, an operator, an attribute name, a string and a number.
    */
  private val source =
    """// The comment
      |digraph G {
      |  rankdir = LR;
      |  a -> b [label = "text", weight = 2];
      |  subgraph cluster_x { c; }
      |}
      |""".stripMargin

  // ---- Colors ---------------------------------------------------------------

  /** A color with its channels in the range 0 to 1. */
  private case class Rgba(r: Double, g: Double, b: Double, a: Double):

    /** This color on top of an opaque background. */
    def over(bg: Rgba): Rgba =
      Rgba(r * a + bg.r * (1 - a), g * a + bg.g * (1 - a), b * a + bg.b * (1 - a), 1)

    /** The relative luminance of WCAG 2. */
    def luminance: Double =
      def linear(c: Double) = if c <= 0.03928 then c / 12.92 else math.pow((c + 0.055) / 1.055, 2.4)
      0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)

    def hex: String =
      def channel(c: Double) = f"${math.round(c * 255).toInt}%02x"
      s"#${channel(r)}${channel(g)}${channel(b)}"

  /** The contrast ratio of WCAG 2 between two opaque colors. */
  private def contrast(x: Rgba, y: Rgba): Double =
    val (light, dark) = (math.max(x.luminance, y.luminance), math.min(x.luminance, y.luminance))
    (light + 0.05) / (dark + 0.05)

  /** The channels of a CSS color. A computed color can be `rgb(…)`,
    * `oklab(…)` or `color(srgb …)`, so a canvas paints it and gives the pixel.
    */
  private def resolve(cssColor: String): Rgba =
    val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
    canvas.width  = 1
    canvas.height = 1
    val context = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
    // A canvas ignores a color that it cannot read. The marker shows this.
    val marker = "#010203"
    context.fillStyle = marker
    context.fillStyle = cssColor
    check(
      cssColor == marker || context.fillStyle.toString != marker,
      s"the canvas cannot read the color '$cssColor'"
    )
    context.fillRect(0, 0, 1, 1)
    val pixel = context.getImageData(0, 0, 1, 1).data
    val alpha = pixel(3) / 255.0
    // The canvas keeps a transparent pixel as transparent black.
    if alpha == 0 then Rgba(0, 0, 0, 0)
    else Rgba(pixel(0) / 255.0, pixel(1) / 255.0, pixel(2) / 255.0, alpha)

  private def colorOf(element: dom.Element): Rgba =
    resolve(dom.window.getComputedStyle(element).color)

  private def ownBackgroundOf(element: dom.Element): Rgba =
    resolve(dom.window.getComputedStyle(element).backgroundColor)

  /** The opaque color behind `element`: the backgrounds of the elements from
    * `root` down to `element`, each one on top of the one before.
    */
  private def backgroundOf(element: dom.Element, root: dom.Element, under: Option[Rgba] = None): Rgba =
    def fromRoot(e: dom.Element): List[dom.Element] =
      if e eq root then List(e) else fromRoot(e.parentNode.asInstanceOf[dom.Element]) :+ e
    fromRoot(element).foldLeft(under.getOrElse(Rgba(1, 1, 1, 1)))((below, e) => ownBackgroundOf(e).over(below))

  // ---- The editor -----------------------------------------------------------

  private def all(root: dom.Element, selector: String): Seq[dom.Element] =
    val found = root.querySelectorAll(selector)
    (0 until found.length).map(found(_))

  /** Each visible piece of text in `line`, with the element that colors it. */
  private def textPieces(line: dom.Element): Seq[(String, dom.Element)] =
    def pieces(node: dom.Node): Seq[(String, dom.Element)] =
      if node.nodeType == dom.Node.TEXT_NODE then
        Option(node.textContent).map(_.trim).filter(_.nonEmpty).map(_ -> node.parentNode.asInstanceOf[dom.Element]).toSeq
      else (0 until node.childNodes.length).flatMap(i => pieces(node.childNodes(i)))
    pieces(line)

  /** The block runs after two frames. CodeMirror measures and draws the
    * selection in a frame, not in the dispatch.
    */
  private def afterFrames[A](block: => A): Future[A] =
    val p = Promise[A]()
    dom.window.requestAnimationFrame: _ =>
      dom.window.requestAnimationFrame: _ =>
        p.complete(Try(block))
    p.future

  /** Mount the editor with `source` in a box that has the tokens of `theme`,
    * and run `body` with the box. The end of the test removes the box.
    */
  private def withThemedEditor(theme: AppTheme)(body: dom.Element => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div")
    val tokens = theme.tokens.map((name, value) => s"$name: $value").mkString("; ")
    box.setAttribute(
      "style",
      s"$tokens; color-scheme: ${theme.scheme}; width: 600px; height: 300px;" +
        " background-color: var(--color-base-100); color: var(--color-base-content)"
    )
    dom.document.body.appendChild(box)
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.diagram("code-mirror-theme-spec"), graphviz)(using owner)
      state.sourceText.set(source)
      val root = render(box, CodeMirror(state))
      afterFrames(()).flatMap(_ => body(box)).transform: outcome =>
        root.unmount()
        outcome
    .transform: outcome =>
      box.parentNode.removeChild(box)
      owner.killSubscriptions()
      outcome

  private def editorIn(box: dom.Element): EditorView =
    val editor = box.querySelector(".cm-editor")
    check(editor != null, "precondition: the mount made no CodeMirror editor")
    EditorView.findFromDOM(editor)

  /** Check each piece of text in each line against the color behind it. */
  private def checkLines(box: dom.Element, minimum: Double, where: String, under: Option[Rgba] = None): Unit =
    val lines = all(box, ".cm-content .cm-line")
    check(lines.nonEmpty, "precondition: the editor shows no line")
    val scroller = box.querySelector(".cm-scroller")
    for
      line          <- lines
      (text, owner) <- textPieces(line)
    do
      // A selection is in a layer behind the content. The backgrounds of the
      // content and of the line paint on top of it.
      val behind = under match
        case None            => backgroundOf(owner, box)
        case Some(selection) => backgroundOf(owner, scroller, Some(selection))
      val color = colorOf(owner).over(behind)
      val ratio = contrast(color, behind)
      check(
        ratio >= minimum,
        f"$where: '$text' has the color ${color.hex} on ${behind.hex}. The contrast is $ratio%.2f, the minimum is $minimum%.1f"
      )

  for theme <- Seq(workbench, drafting) do

    // Failure before the fix, in the dark theme: the gutter has the light
    // default background of CodeMirror (#f5f5f5) in a dark panel.
    test(s"${theme.name}: the gutter has the tone of the panel, and its numbers are readable"):
      withThemedEditor(theme): box =>
        Future:
          val panel  = backgroundOf(box, box)
          val gutter = backgroundOf(box.querySelector(".cm-gutters"), box)
          val step   = contrast(gutter, panel)
          check(
            step <= maxGutterStep,
            f"the gutter is ${gutter.hex} in a panel that is ${panel.hex}. The contrast is $step%.2f, the maximum is $maxGutterStep%.1f"
          )
          val numbers = all(box, ".cm-lineNumbers .cm-gutterElement").filter(_.textContent.trim.nonEmpty)
          check(numbers.nonEmpty, "precondition: the gutter shows no line number")
          for number <- numbers do
            val behind = backgroundOf(number, box)
            val color  = colorOf(number).over(behind)
            val ratio  = contrast(color, behind)
            check(
              ratio >= secondaryContrast,
              f"the line number '${number.textContent.trim}' is ${color.hex} on ${behind.hex}. The contrast is $ratio%.2f, the minimum is $secondaryContrast%.1f"
            )

    // Failure before the fix, in the dark theme: the light default colors of
    // CodeMirror on a dark background, for example a name in #0000ff.
    test(s"${theme.name}: each token of the source is readable"):
      withThemedEditor(theme): box =>
        Future(checkLines(box, textContrast, "source"))

    // Failure before the fix, in the dark theme: the light default selection
    // of CodeMirror behind the light text of the theme.
    for focused <- Seq(false, true) do
      val focusName = if focused then "focused" else "unfocused"
      test(s"${theme.name}: each token is readable in a selection ($focusName editor)"):
        withThemedEditor(theme): box =>
          val view = editorIn(box).asInstanceOf[js.Dynamic]
          if focused then view.focus()
          view.dispatch(js.Dynamic.literal(selection = js.Dynamic.literal(anchor = 0, head = source.length)))
          afterFrames:
            val editor = box.querySelector(".cm-editor")
            same(editor.classList.contains("cm-focused"), focused, "precondition: the focus state of the editor is wrong")
            val marks = all(box, ".cm-selectionBackground")
            check(marks.nonEmpty, "precondition: the editor drew no selection")
            val below = backgroundOf(box.querySelector(".cm-scroller"), box)
            for mark <- marks.take(1) do
              val selection = ownBackgroundOf(mark).over(below)
              val step      = contrast(selection, below)
              check(
                step >= minSelectionStep,
                f"the selection is ${selection.hex} on ${below.hex}. The contrast is $step%.2f, the minimum is $minSelectionStep%.1f"
              )
              checkLines(box, secondaryContrast, s"selection ($focusName)", Some(selection))
