package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.desktop.ExternalLink
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.scalajs.dom

import scala.scalajs.js

/** The link icon of a linked element.
  *
  * A DOT `URL` or `href` attribute, or a Mermaid `click X href`, puts an element in an svg `<a>` link. The drawing does
  * not show that the element has a link. Thus each element with a link that the page can open gets a link icon: an
  * http or https link, and on the desktop, a link to a local file (see `ExternalLink.isOpenableHere`). The icon shows
  * while the pointer is on the element or on the icon, as the fold badge of a group does. A click on the icon opens
  * the link in the browser of the system, with no command key. While the icon shows, the canvas also shows the URL in
  * a strip (`hoveredLink` in `UIState`).
  *
  * The icon is at the top-left corner of a node or a group: the count badges use the right side and the left middle.
  * The icon of an arrow is just right of the top-right corner of its label, or at the middle of its line when it has
  * no label.
  *
  * The icons are in the overlay layer of [[CountBadges]], so they are above the arrows, and they keep their size on
  * the screen at each zoom.
  */
object LinkBadges:

  val badgeClass = "gx-link-badge"

  /** The key that ties an element to its icon. */
  private val keyAttr = "data-gx-link"

  /** The URL of an icon. */
  private val urlAttr = "data-gx-url"

  /** The key of the element under the pointer, on the svg. */
  private val hoveredAttr = "data-gx-link-hovered"

  private val SvgNS   = "http://www.w3.org/2000/svg"
  private val XlinkNS = "http://www.w3.org/1999/xlink"

  // The glyph `link-45deg` of Bootstrap Icons, in a 16 x 16 box.
  private val glyph = Seq(
    "M4.715 6.542 3.343 7.914a3 3 0 1 0 4.243 4.243l1.828-1.829A3 3 0 0 0 8.586 5.5L8 6.086a1 1 0 0 0-.154.199 2 2 0 0 1 .861 3.337L6.88 11.45a2 2 0 1 1-2.83-2.83l.793-.792a4 4 0 0 1-.128-1.287z",
    "M6.586 4.672A3 3 0 0 0 7.414 9.5l.775-.776a2 2 0 0 1-.896-3.346L9.12 3.55a2 2 0 1 1 2.83 2.83l-.793.792c.112.42.155.855.128 1.287l1.372-1.372a3 3 0 1 0-4.243-4.243z"
  )

  /** The `<a>` element of an svg that holds `target`, if any. */
  def svgLinkOf(target: dom.EventTarget): Option[dom.Element] =
    target match
      case el: dom.Element => Option(el.closest("a")).filter(_.namespaceURI == SvgNS)
      case _               => None

  /** The URL of an svg link: its `href`, or else its `xlink:href`. */
  def linkUrl(link: dom.Element): Option[String] =
    Option(link.getAttribute("href"))
      .orElse(Option(link.getAttributeNS(XlinkNS, "href")))
      .filter(_.nonEmpty)

  /** The http or https URL of an element. Graphviz puts the link in the group of the element. Mermaid puts the group
    * of the element in the link.
    */
  private def urlOf(el: dom.Element): Option[String] =
    val inside = Option(el.querySelector("a")).filter(_.namespaceURI == SvgNS)
    val around = Option(el.parentNode).collect { case p: dom.Element if p.namespaceURI == SvgNS && p.tagName == "a" => p }
    inside.orElse(around).flatMap(linkUrl).filter(ExternalLink.isOpenableHere)

  private def bbox(el: dom.Element): dom.SVGRect = el.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]

  /** The point of the icon, in the user space of `el`. */
  private def anchor(el: dom.Element, strategy: SelectableElementStrategy): (Double, Double) =
    if strategy.isEdge(el) then
      val texts = el.querySelectorAllT[dom.Element]("text").map(bbox).filter(b => b.width > 0 || b.height > 0)
      if texts.nonEmpty then (texts.map(b => b.x + b.width).max, texts.map(_.y).min)
      else
        Option(el.querySelector("path")) match
          case Some(path) =>
            val p      = path.asInstanceOf[js.Dynamic]
            val length = p.getTotalLength().asInstanceOf[Double]
            val point  = p.getPointAtLength(length / 2).asInstanceOf[dom.SVGPoint]
            (point.x, point.y)
          case None =>
            val b = bbox(el)
            (b.x + b.width / 2, b.y + b.height / 2)
    else
      val b = bbox(el)
      (b.x, b.y)

  /** Add the link icons for the current drawing. Call it after each mount, as [[CountBadges.decorate]]. A click on an
    * icon gives its URL to `open`.
    */
  def decorate(
      mainGroup: dom.Element,
      strategy:  SelectableElementStrategy,
      open:      String => Unit = url => ExternalLink.open(url)
  ): Unit =
    def isGhost(el: dom.Element): Boolean = el.closest(s".${LayoutTransition.ghostClass}") != null
    val layer = CountBadges.layerOf(mainGroup)
    layer.querySelectorAllT[dom.Element](s"g.$badgeClass").foreach(_.remove())
    mainGroup
      .querySelectorAllT[dom.Element](strategy.allSelector)
      .filterNot(isGhost)
      .zipWithIndex
      .foreach: (el, index) =>
        el.removeAttribute(keyAttr)
        urlOf(el).foreach: url =>
          val key      = index.toString
          val (x, y)   = anchor(el, strategy)
          val (lx, ly) = CountBadges.toLayer(el, layer, x, y)
          val b        = badge(url, open)
          el.setAttribute(keyAttr, key)
          b.setAttribute(keyAttr, key)
          layer.appendChild(b)
          // The label of an arrow has no border, so the icon goes next to it and not on it.
          val ox = if strategy.isEdge(el) && el.querySelector("text") != null then CountBadges.clientDiameter / 2 else 0.0
          CountBadges.place(b, lx, ly, layer, ox)
    // The pointer can rest on a linked element while the drawing renders again.
    Option(mainGroup.closest("svg")).foreach: root =>
      Option(root.getAttribute(hoveredAttr)).filter(_.nonEmpty).foreach(applyHovered(root, _))

  /** Show the icon of the linked element under the pointer, and give its URL to `onHover`.
    *
    * One listener on the svg covers each drawing that renders in it. The caller installs it one time for each svg
    * element, as [[CountBadges.installHover]].
    */
  def installHover(mainGroup: dom.Element, strategy: SelectableElementStrategy, onHover: Option[String] => Unit): Unit =
    val root = Option(mainGroup.closest("svg")).getOrElse(mainGroup)
    def set(key: Option[String]): Unit =
      val next = key.getOrElse("")
      if root.getAttribute(hoveredAttr) != next then
        root.setAttribute(hoveredAttr, next)
        onHover(applyHovered(root, next))
    root.addEventListener(
      "mouseover",
      ((e: dom.Event) => set(keyUnder(e.target.asInstanceOf[dom.Element], strategy))): js.Function1[dom.Event, Unit]
    )
    root.addEventListener("mouseleave", ((_: dom.Event) => set(None)): js.Function1[dom.Event, Unit])

  /** The key of the linked element under the pointer. The icon is not in the group of its element, so the icon counts
    * as its element. Otherwise the icon goes away when the pointer moves onto it.
    */
  private def keyUnder(target: dom.Element, strategy: SelectableElementStrategy): Option[String] =
    Option(target.closest(s"g.$badgeClass"))
      .orElse(Option(target.closest(strategy.allSelector)))
      .flatMap(el => Option(el.getAttribute(keyAttr)))
      .filter(_.nonEmpty)

  /** Mark the icon of `key` as active, and give its URL. */
  private def applyHovered(root: dom.Element, key: String): Option[String] =
    root
      .querySelectorAllT[dom.Element](s"g.$badgeClass")
      .flatMap: b =>
        if key.nonEmpty && b.getAttribute(keyAttr) == key then
          b.classList.add(CountBadges.activeClass)
          Option(b.getAttribute(urlAttr))
        else
          b.classList.remove(CountBadges.activeClass)
          None
      .headOption

  private def badge(url: String, open: String => Unit): dom.Element =
    val g = dom.document.createElementNS(SvgNS, "g")
    g.setAttribute("class", s"$badgeClass ${SelectableElement.decorationClass}")
    g.setAttribute(urlAttr, url)

    val title = dom.document.createElementNS(SvgNS, "title")
    title.textContent = if ExternalLink.isOpenable(url) then "Open the link in the browser" else "Open the file"
    g.appendChild(title)

    val c = dom.document.createElementNS(SvgNS, "circle")
    c.setAttribute("cx", "0")
    c.setAttribute("cy", "0")
    c.setAttribute("r", "7")
    g.appendChild(c)

    // The glyph is 16 units wide. A scale of 0.6 gives about 10 units in the circle of 14.
    val icon = dom.document.createElementNS(SvgNS, "g")
    icon.setAttribute("transform", "scale(0.6) translate(-8 -8)")
    glyph.foreach: d =>
      val p = dom.document.createElementNS(SvgNS, "path")
      p.setAttribute("d", d)
      icon.appendChild(p)
    g.appendChild(icon)

    // The icon is its own control: the canvas must not take the press as a press on the element.
    g.addEventListener("pointerdown", (ev: dom.Event) => ev.stopPropagation())
    g.addEventListener("mousedown", (ev: dom.Event) => ev.stopPropagation())
    g.addEventListener(
      "click",
      { (ev: dom.Event) =>
        ev.stopPropagation()
        ev.preventDefault()
        open(url)
      }
    )
    g
