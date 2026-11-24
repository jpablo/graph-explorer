package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.viewer.utils.BBox
import org.scalajs.dom

private[svgCanvas] object CanvasRoot:
  private val SvgNamespace   = "http://www.w3.org/2000/svg"
  private val RootClass      = "gx-canvas-root"
  private val NonDrawingTags = Set("defs", "style", "title", "desc", "metadata", "script")

  /** Return the single group the canvas can pan and zoom.
    *
    * Graphviz and Mermaid flowcharts already put the whole drawing under one root `<g>`, so preserve that group (including Graphviz's
    * layout translation). Mermaid sequence diagrams instead emit actors, notes, messages and lines as many direct children of the `<svg>`.
    * Wrap those drawing siblings so one transform reaches the complete diagram, while definitions and styles remain at SVG scope.
    */
  def mainGroup(svg: dom.svg.SVG): dom.svg.G =
    Option(svg.querySelector(s":scope > g.$RootClass")).collect { case group: dom.svg.G => group }
      .getOrElse:
        val drawingRoots =
          (0 until svg.children.length)
            .map(svg.children.item)
            .filter(element => !NonDrawingTags.contains(element.tagName.toLowerCase))
            .toVector

        drawingRoots match
          case Vector(group: dom.svg.G) =>
            group.classList.add(RootClass)
            group
          case Vector() => throw Exception("No drawing elements found in the SVG")
          case roots =>
            val group = svg.ownerDocument.createElementNS(SvgNamespace, "g").asInstanceOf[dom.svg.G]
            group.classList.add(RootClass)
            svg.insertBefore(group, roots.head)
            roots.foreach(group.appendChild)
            group

  /** Make the zoom of the canvas scale `group` around the center of `viewBox`.
    *
    * The canvas transform of the group is `scale(z) translate(x y)`. The group
    * scales around its CSS `transform-origin`. Without an origin, the origin is
    * the point (0, 0) of the user space, and Fit (zoom 0.9) moves the drawing
    * toward that point. The point (0, 0) is not the center of the drawing.
    *
    * With `transform-box: view-box`, an origin in `px` is a point in the user
    * space of the SVG, from the point (0, 0) of that space. It is not an offset
    * from the corner of the viewBox. Chromium and WebKit 27 agree on this.
    * Thus the origin is the center of the viewBox in user units.
    *
    * The value is inline, so it applies to DOT and Mermaid, and it does not
    * need the app stylesheet. Each new layout gives a new SVG, and the canvas
    * calls this function again with the viewBox of that SVG.
    * `SvgTransformOps.transformOrigin` reads the computed value of this origin.
    */
  def centerScaleOrigin(group: dom.svg.G, viewBox: BBox): Unit =
    val cx = viewBox.x + viewBox.width / 2
    val cy = viewBox.y + viewBox.height / 2
    group.style.setProperty("transform-box", "view-box")
    group.style.setProperty("transform-origin", s"${cx}px ${cy}px")
