package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.color.{CssColor, DarkRemap, Oklab, Srgb}
import org.scalajs.dom

import scala.collection.mutable
import scala.scalajs.js

/** The colors of a diagram on a dark theme.
  *
  * Graphviz and Mermaid draw for a light page. On a dark theme, [[paint]]
  * changes each color of the SVG with a [[DarkRemap]]: the presentation
  * attributes, the inline styles, and the rules of each `<style>` element.
  *
  * The first change of a value keeps the original value in a `data-gx-light-*`
  * attribute. Each later change starts from the original value, so a second
  * theme does not change the colors of the first theme. [[paint]] with `None`
  * puts the original values back. An export uses [[lightCopy]], so an exported
  * SVG has the original colors.
  */
object DiagramColors:

  /** The attribute on the root `<svg>` while the colors are dark. `style.scss`
    * uses it for the sheet behind a Mermaid drawing.
    */
  val DarkAttr = "data-gx-dark"

  private val ColorAttrs = List("fill", "stroke", "color", "stop-color", "flood-color", "lighting-color")

  private val ColorProps: Set[String] = ColorAttrs.toSet ++ Set(
    "background",
    "background-color",
    "border",
    "border-color",
    "border-top-color",
    "border-right-color",
    "border-bottom-color",
    "border-left-color",
    "outline-color",
    "text-decoration-color",
    "caret-color"
  )

  private val AttrMarker  = "data-gx-light-"
  private val StyleMarker = "data-gx-light-style-"
  private val CssMarker   = "data-gx-light-css"
  /** The root has no `fill`, so its text is black by default. The remap adds a
    * `fill` attribute, and this marker records that the original had none.
    */
  private val AddedFill = "data-gx-added-fill"

  /** The words that are never a diagram color, also when the browser accepts them. */
  private val NotColors = Set("none", "transparent", "currentcolor", "inherit", "initial", "unset", "revert", "url")

  private val Declaration = """([a-zA-Z-]+)(\s*:\s*)([^;{}]+)""".r

  // ---- color text in the browser ----

  private lazy val context: dom.CanvasRenderingContext2D =
    dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas].getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]

  private val resolved = mutable.Map.empty[String, Option[Srgb]]

  /** The color of a CSS color token, or `None` when the token is not a color.
    * The canvas of the browser reads the forms that [[CssColor]] does not
    * read, for example a named color. It gives the color as `#rrggbb` or
    * `rgba(…)`. An invalid token does not change the fill of the canvas, so two
    * different start values show an invalid token.
    */
  def resolve(token: String): Option[Srgb] =
    val key = token.trim.toLowerCase
    if key.isEmpty || NotColors.contains(key) || key.startsWith("url(") then None
    else
      CssColor.parse(key).orElse:
        resolved.getOrElseUpdate(
          key, {
            def read(start: String): String =
              context.fillStyle = start
              context.fillStyle = key
              context.fillStyle.toString
            val first = read("#010203")
            if first == read("#040506") then CssColor.parse(first) else None
          }
        )

  /** The remap for the current theme of the page, or `None` for a light theme.
    *
    * White becomes `--color-base-100` and black becomes `--color-base-content`.
    * A theme is light when its canvas (`--gx-canvas-bg`) is lighter than its
    * text. Thus the Workbench theme, which has dark chrome and a light canvas,
    * keeps the original colors.
    */
  def themeRemap(): Option[DarkRemap] =
    val style = dom.window.getComputedStyle(dom.document.documentElement)
    def color(name: String) = resolve(style.getPropertyValue(name)).map(Oklab.fromSrgb)
    for
      paper  <- color("--color-base-100")
      ink    <- color("--color-base-content")
      canvas <- color("--gx-canvas-bg").orElse(Some(paper))
      if DarkRemap.isDark(canvas, ink)
    yield DarkRemap(paper, ink)

  // ---- the SVG ----

  private def elementsOf(root: dom.Element): Seq[dom.Element] =
    val all = root.querySelectorAll("*")
    root +: (0 until all.length).map(all(_))

  private def remapText(text: String, remap: DarkRemap): String =
    DarkRemap.remapValue(text, resolve, remap.apply)

  /** Change the colors of `root` and its descendants with `remap`, or put the
    * original colors back with `None`.
    */
  def paint(root: dom.Element, remap: Option[DarkRemap]): Unit =
    undoContrast(root)
    elementsOf(root).foreach: e =>
      paintAttributes(e, remap)
      paintInlineStyle(e, remap)
      if e.tagName.toLowerCase == "style" then paintStyleSheet(e, remap)
    paintRootFill(root, remap)
    remap match
      case Some(r) =>
        fixContrast(root, r)
        root.setAttribute(DarkAttr, "")
      case None => root.removeAttribute(DarkAttr)

  // ---- the contrast of a label on its fill ----

  private val ContrastMarker = "data-gx-contrast"
  private val ContrastPrev   = "data-gx-contrast-prev"

  /** The least difference of Oklab lightness between a label and its fill. */
  val MinContrast = 0.4

  private def all(root: dom.Element, selector: String): Seq[dom.Element] =
    val found = root.querySelectorAll(selector)
    (0 until found.length).map(found(_))

  /** The `fill` that `e` gets: its own attribute, or the attribute of the
    * nearest ancestor.
    */
  private def fillOf(e: dom.Element): Option[Srgb] =
    Iterator
      .iterate(e)(_.parentNode.asInstanceOf[dom.Element])
      .takeWhile(n => n != null && n.nodeType == dom.Node.ELEMENT_NODE)
      .map(_.getAttribute("fill"))
      .collectFirst { case v if v != null => v }
      .flatMap(resolve)

  /** The fill of the shape of a Graphviz node or group: its first shape with a
    * `fill` attribute. `None` when that shape has no fill, so the label is on
    * the sheet.
    */
  private def shapeFill(group: dom.Element): Option[Srgb] =
    all(group, ":scope > polygon, :scope > ellipse, :scope > rect, :scope > path")
      .find(_.hasAttribute("fill"))
      .flatMap(s => resolve(s.getAttribute("fill")))

  /** A remap keeps the order of the lightness, but it compresses the range from
    * white to black into the range from the paper to the ink. Thus white text
    * on a red fill becomes dark text on a red fill of mid lightness, with less
    * contrast. Each label with less than [[MinContrast]] on its fill gets the
    * ink or the paper, whichever has more contrast.
    */
  private def fixContrast(root: dom.Element, r: DarkRemap): Unit =
    def l(c: Srgb) = Oklab.fromSrgb(c).l
    val choices    = List(r(Srgb(1, 1, 1)), r(Srgb(0, 0, 0)))
    for
      group   <- all(root, "g.node, g.cluster")
      bg      <- shapeFill(group).toList
      text    <- all(group, "text")
      current <- fillOf(text).toList
      contrast = math.abs(l(current) - l(bg))
      if contrast < MinContrast
      best = choices.maxBy(c => math.abs(l(c) - l(bg)))
      if math.abs(l(best) - l(bg)) > contrast
    do
      text.setAttribute(ContrastMarker, "")
      if text.hasAttribute("fill") then text.setAttribute(ContrastPrev, text.getAttribute("fill"))
      text.setAttribute("fill", best.css)

  /** Put back the label colors that [[fixContrast]] changed. */
  private def undoContrast(root: dom.Element): Unit =
    all(root, s"[$ContrastMarker]").foreach: text =>
      if text.hasAttribute(ContrastPrev) then text.setAttribute("fill", text.getAttribute(ContrastPrev))
      else text.removeAttribute("fill")
      text.removeAttribute(ContrastPrev)
      text.removeAttribute(ContrastMarker)

  private def paintAttributes(e: dom.Element, remap: Option[DarkRemap]): Unit =
    // [[paintRootFill]] owns a `fill` that the remap added.
    val attrs = if e.hasAttribute(AddedFill) then ColorAttrs.filterNot(_ == "fill") else ColorAttrs
    attrs.foreach: attr =>
      val marker = AttrMarker + attr
      (remap, e.hasAttribute(marker)) match
        case (Some(r), true) => e.setAttribute(attr, remapText(e.getAttribute(marker), r))
        case (Some(r), false) if e.hasAttribute(attr) =>
          val original = e.getAttribute(attr)
          val changed  = remapText(original, r)
          if changed != original then
            e.setAttribute(marker, original)
            e.setAttribute(attr, changed)
        case (None, true) =>
          e.setAttribute(attr, e.getAttribute(marker))
          e.removeAttribute(marker)
        case _ => ()

  private def paintInlineStyle(e: dom.Element, remap: Option[DarkRemap]): Unit =
    val style = e.asInstanceOf[dom.html.Element].style
    if !js.isUndefined(style) && style != null then
      // The originals first: a property that the remap changed before.
      val markers =
        (0 until e.attributes.length).map(e.attributes(_).name).filter(_.startsWith(StyleMarker))
      markers.foreach: marker =>
        val prop     = marker.stripPrefix(StyleMarker)
        val original = e.getAttribute(marker)
        val priority = style.getPropertyPriority(prop)
        remap match
          case Some(r) => style.setProperty(prop, remapText(original, r), priority)
          case None =>
            style.setProperty(prop, original, priority)
            e.removeAttribute(marker)
      // Then the properties that the remap did not change before.
      remap.foreach: r =>
        val known = markers.map(_.stripPrefix(StyleMarker)).toSet
        val props = (0 until style.length).map(style.item(_)).filter(p => ColorProps.contains(p) && !known.contains(p))
        props.foreach: prop =>
          val original = style.getPropertyValue(prop)
          val changed  = remapText(original, r)
          if changed != original then
            e.setAttribute(StyleMarker + prop, original)
            style.setProperty(prop, changed, style.getPropertyPriority(prop))

  /** Mermaid puts most of its colors in a `<style>` element. Each declaration
    * of a color property gets the remap. The selectors stay.
    */
  private def paintStyleSheet(e: dom.Element, remap: Option[DarkRemap]): Unit =
    val original = if e.hasAttribute(CssMarker) then e.getAttribute(CssMarker) else e.textContent
    remap match
      case Some(r) =>
        val changed = Declaration.replaceAllIn(
          original,
          m =>
            val prop = m.group(1).toLowerCase
            val text = if ColorProps.contains(prop) then m.group(1) + m.group(2) + remapText(m.group(3), r) else m.matched
            scala.util.matching.Regex.quoteReplacement(text)
        )
        if changed != original || e.hasAttribute(CssMarker) then
          e.setAttribute(CssMarker, original)
          e.textContent = changed
      case None =>
        if e.hasAttribute(CssMarker) then
          e.textContent = original
          e.removeAttribute(CssMarker)

  /** A Graphviz label has no `fill`, so it is black. A `fill` attribute on the
    * root gives the default of the remap. A presentation attribute has a lower
    * priority than each CSS rule, so the rules of Mermaid stay.
    */
  private def paintRootFill(root: dom.Element, remap: Option[DarkRemap]): Unit =
    (remap, root.hasAttribute(AddedFill)) match
      case (Some(r), _) if root.hasAttribute(AddedFill) || !root.hasAttribute("fill") =>
        root.setAttribute(AddedFill, "")
        root.setAttribute("fill", r(Srgb(0, 0, 0)).css)
      case (None, true) =>
        root.removeAttribute("fill")
        root.removeAttribute(AddedFill)
      case _ => ()

  /** A copy of `svg` with the original colors and with no markers. */
  def lightCopy[E <: dom.Element](svg: E): E =
    val copy = svg.cloneNode(deep = true).asInstanceOf[E]
    paint(copy, None)
    copy

  /** Keep the colors of `svg` in step with the theme of the page and with the
    * setting. The theme is the `data-theme` attribute of the document.
    */
  def follow(svg: dom.svg.SVG, enabled: Signal[Boolean]): Modifier[ReactiveSvgElement[dom.svg.SVG]] =
    val themeChanges = Var(0)
    val observer     = new dom.MutationObserver((_, _) => themeChanges.update(_ + 1))
    val options      = new dom.MutationObserverInit:
      attributes = true
      attributeFilter = js.Array("data-theme")
    Seq(
      onMountUnmountCallback(
        mount = _ => observer.observe(dom.document.documentElement, options),
        unmount = _ => observer.disconnect()
      ),
      enabled.combineWith(themeChanges.signal) --> { (on, _) =>
        paint(svg, if on then themeRemap() else None)
      }
    )
