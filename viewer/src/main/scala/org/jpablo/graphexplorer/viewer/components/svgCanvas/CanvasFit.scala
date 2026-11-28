package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.state.{CanvasTransform, FitMode}
import org.jpablo.graphexplorer.viewer.utils.BBox
import org.scalajs.dom

/** The measures of the 2D canvas for Fit, Fit width and Fit height (see [[CanvasTransform.fit]]). */
object CanvasFit:

  /** The elements that put marks on the screen. A label is a `text` (Graphviz, Mermaid) or a `foreignObject`
    * (Mermaid).
    */
  private val MarkSelector =
    "path, rect, circle, ellipse, line, polyline, polygon, text, image, use, foreignObject"

  /** The containers whose marks are not a part of the drawing.
    *
    *   - Definitions: markers, symbols, clip paths, masks and patterns show only through a reference.
    *   - The overlays of the app: the count badges and the link icons, the marks of the selection, the controls of a
    *     new arrow and of the ends of an arrow, the arrow that a drag draws, the overlays of a record cell, the exit
    *     ghosts of a layout transition. Thus a fit does not change with the selection.
    *   - The hit area of an arrow. It is a copy of the path, so it adds nothing.
    */
  private val NotDrawingSelector =
    Seq(
      "defs",
      "marker",
      "symbol",
      "clipPath",
      "mask",
      "pattern",
      s".${CountBadges.layerClass}",
      s".${SelectableElement.decorationClass}",
      s".${SelectableElement.selectionRectClass}",
      s".${SelectableElement.selectionCasingClass}",
      "g.new-arrow-control",
      "g.edge-endpoint-disk",
      "#dragging-arrow-group",
      s"#${RecordCellOverlay.overlayId}",
      s"#${RecordCellOverlay.dropId}",
      s".${SelectableElement.exitGhostClass}",
      s".${SelectableElement.hitAreaClass}"
    ).mkString(", ")

  /** The background of the drawing. It is not a part of the drawing for a fit.
    *
    *   - Mermaid: the sheet that the app adds (`MermaidBackend.addSheet`). It covers the viewBox, with the padding of
    *     Mermaid.
    *   - Graphviz: the first polygon of the root graph group. It covers the graph and its `pad`.
    */
  private def backgroundsOf(group: dom.svg.G): Set[dom.Element] =
    val sheets = group.querySelectorAllT[dom.Element](s"rect.${MermaidBackend.sheetClass}").toSet
    val polygon =
      if group.classList.contains("graph") then Option(group.querySelector(":scope > polygon")) else None
    sheets ++ polygon

  /** The bounds of the drawing of `svg`, in the user space inside the drawing group (`CanvasRoot.mainGroup`).
    *
    * The function measures the screen box of each mark, and converts the union to user units with the inverse of the
    * screen transform of the group. That transform has a scale and a translation only, so a box stays a box.
    *
    * It is empty when the drawing has no mark with a size, or when the svg has no screen transform (it is not in
    * the document, or it does not show).
    */
  def drawingBounds(svg: dom.svg.SVG): Option[BBox] =
    val group       = CanvasRoot.mainGroup(svg)
    val backgrounds = backgroundsOf(group)
    var left        = Double.PositiveInfinity
    var top         = Double.PositiveInfinity
    var right       = Double.NegativeInfinity
    var bottom      = Double.NegativeInfinity
    group
      .querySelectorAllT[dom.Element](MarkSelector)
      .foreach: el =>
        if !backgrounds.contains(el) && el.closest(NotDrawingSelector) == null then
          val r = el.getBoundingClientRect()
          // An element with `display: none`, an empty text and an empty label have no box.
          if r.width > 0 || r.height > 0 then
            left = left min r.left
            top = top min r.top
            right = right max r.right
            bottom = bottom max r.bottom
    Option(group.getScreenCTM())
      .filter(_ => left <= right && top <= bottom)
      .map: ctm =>
        val inverse = ctm.inverse()
        def toUser(x: Double, y: Double) =
          val p = svg.createSVGPoint()
          p.x = x
          p.y = y
          p.matrixTransform(inverse)
        val a = toUser(left, top)
        val b = toUser(right, bottom)
        BBox(a.x min b.x, a.y min b.y, (b.x - a.x).abs, (b.y - a.y).abs)

  /** The transform of the fit `mode` of the drawing of `svg`, with a zoom of `maxZoom` or less.
    *
    * It is empty when the svg has no size on the screen: it is not in the document, or the canvas is hidden.
    */
  def transform(svg: dom.svg.SVG, mode: FitMode, maxZoom: Double = Double.PositiveInfinity): Option[CanvasTransform] =
    val box = svg.getBoundingClientRect()
    val vb  = svg.viewBox.baseVal
    Option
      .when(box.width > 0 && box.height > 0 && vb != null && vb.width > 0 && vb.height > 0):
        BBox(vb.x, vb.y, vb.width, vb.height)
      .map: viewBox =>
        val drawing = drawingBounds(svg).getOrElse(viewBox)
        CanvasTransform.fit(mode, box.width, box.height, viewBox, drawing, maxZoom = maxZoom)
