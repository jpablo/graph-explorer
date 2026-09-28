package org.jpablo.graphexplorer.viewer.components.svgCanvas

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
