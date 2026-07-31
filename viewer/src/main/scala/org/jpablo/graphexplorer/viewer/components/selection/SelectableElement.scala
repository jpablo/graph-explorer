package org.jpablo.graphexplorer.viewer.components.selection

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.utils.BBox
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native
@JSGlobal("CSS")
private object CSSGlobal extends js.Object:
  def escape(value: String): String = js.native

/** Base trait for interactive graph elements in the SVG canvas.
  *
  * Provides selection functionality, element identification, and DOM manipulation for nodes, edges, and clusters in the graph
  * visualization.
  *
  * @param ref
  *   The underlying SVG group element
  * @param strategy
  *   The strategy for extracting element IDs from the SVG
  */
sealed trait SelectableElement(val ref: dom.svg.Element, val strategy: SelectableElementStrategy):
  def selectedClass: String

  protected lazy val refTitle: String =
    val title = ref.querySelector("title")
    if title != null then title.textContent else ""
  // example: <g id="edge:id"> ...
  protected val svgIdAttr = ref.id

  def elementId: ElementId
  def nodeId: Option[NodeId]          = elementId match { case n: NodeId => Some(n); case _ => None }
  def arrowId: Option[models.ArrowId] = elementId match { case a: models.ArrowId => Some(a); case _ => None }
  def groupId: Option[models.GroupId] = elementId match { case g: models.GroupId => Some(g); case _ => None }

  val selectionRectClass = "selected-border"

  def unselect(): Unit =
    ref.classList.remove(selectedClass)
    val rect = ref.querySelector(s"rect.$selectionRectClass")
    if rect != null then
      rect.remove()

  def select(): Unit =
    ref.classList.add(selectedClass)
    val rect = ref.querySelector(s"rect.$selectionRectClass")
    if rect == null then
      val newRect = SelectedRect().ref
      // Mermaid nodes render text inside foreignObject (within g.label).
      // Appending the rect after foreignObject hides the HTML text content.
      // Insert before the label group so text renders on top of the highlight.
      val labelGroup = ref.querySelector("g.label")
      if labelGroup != null then
        ref.insertBefore(newRect, labelGroup)
      else
        ref.appendChild(newRect)
      // Decorations paint ABOVE the selection wash: with no g.label anchor
      // (mermaid clusters name theirs cluster-label) the rect lands after the
      // decoration, and its translucent fill + border drew OVER the control —
      // which read as see-through. appendChild MOVES the existing nodes to the
      // end, listeners intact. (The count/fold badges have since moved to their
      // own overlay layer, above every edge; this stays for any decoration that
      // does ride an element.)
      ref
        .querySelectorAllT[dom.Element](s".${SelectableElement.decorationClass}")
        .foreach(d => ref.appendChild(d))

  private def SelectedRect() =
    // The element's OWN geometry, not its decorations: a decoration inside the
    // element (to ride its transform) inflates the measured box — a selected
    // group's border visibly included the fold badge circle back when the badge
    // lived here. display="none" removes them from getBBox for the duration of
    // the measurement; no frame is produced in between, so nothing flashes.
    val decorations = ref.querySelectorAllT[dom.Element](s".${SelectableElement.decorationClass}")
    decorations.foreach(_.setAttribute("display", "none"))
    val bbox = ref.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
    decorations.foreach(_.removeAttribute("display"))
    svg.rect(
      svg.cls := selectionRectClass,
      // Selection decorations must be invisible to hit-testing: the rect has a painted
      // fill, so without this it becomes an elementsFromPoint target over the whole
      // bbox (for clusters, the entire group area) and click resolution can steer back
      // to the decorated element instead of what the user aimed at (same rationale as
      // #dragging-arrow-group in style.scss).
      svg.pointerEvents := "none",
      svg.x             := bbox.x.toString,
      svg.y             := bbox.y.toString,
      svg.width         := bbox.width.toString,
      svg.height        := bbox.height.toString
    )

object SelectableElement:

  /** Class marking overlay CONTROLS drawn inside a diagram element (the count/fold
    * badges): they ride the element's transform but are not part of its geometry —
    * measurements of the element itself (the selection border) must exclude them.
    */
  val decorationClass = "gx-decoration"

  /** Class marking invisible pointer-target decorations (MermaidBackend.addEdgeHitAreas).
    * They exist ONLY to catch clicks near thin edges: click resolution may go through
    * them (fromDomElement), but they must never be surfaced as the CANONICAL element
    * for an id — geometry/visual consumers (selection highlight, the endpoint-drag
    * preview which clones the path, bbox readers) need the real rendered element.
    */
  val hitAreaClass = "edge-hit-area"

  /** The one definition of the invisible edge hit-halo's presentation:
    * non-scaling-stroke keeps the halo ~14 SCREEN px at any canvas zoom;
    * dasharray:none because with pointer-events:stroke a dashed clone would
    * only hit-test on the dashes. Both backends' halos share this string —
    * style.scss's `.edge-hit-area` counter-rules assume the same 14px figure.
    */
  val hitHaloStyle: String =
    "fill:none;stroke:transparent;stroke-width:14px;stroke-dasharray:none;stroke-linecap:round;" +
      "pointer-events:stroke;vector-effect:non-scaling-stroke"

  /** Clone `path` as an invisible hit halo: id stripped (never a canonical element),
    * [[hitAreaClass]] applied, [[hitHaloStyle]] inlined. Backend-specific plumbing
    * (data-edge-id, marker stripping, class merging, insertion position) stays with
    * the caller.
    */
  def hitHaloClone(path: dom.Element): dom.Element =
    val hit = path.cloneNode(false).asInstanceOf[dom.Element]
    hit.removeAttribute("id")
    hit.setAttribute("class", hitAreaClass)
    hit.setAttribute("style", hitHaloStyle)
    hit

  /** Invisible rect covering an edge LABEL (MermaidBackend.addEdgeHitAreas): label text
    * lives in a foreignObject, whose XHTML content the selection machinery's namespace
    * filter drops — the rect gives label clicks an SVG-namespace target that resolves to
    * the edge via data-edge-id. Also carries [[hitAreaClass]], so findAll excludes it.
    */
  val edgeLabelHitClass = "edge-label-hit"

  /** Create a SelectableElement from a DOM element using the specified strategy. */
  def fromDomElement(e: dom.Element, strategy: SelectableElementStrategy): Option[SelectableElement] =
    if strategy.isNode(e) then
      e match
        case g: dom.svg.G => Some(NodeElement(g, strategy))
        case _            => None
    else if strategy.isEdge(e) then
      e match
        case se: dom.svg.Element => Some(EdgeElement(se, strategy))
        case _                   => None
    else if strategy.isCluster(e) then
      e match
        case g: dom.svg.G => Some(ClusterElement(g, strategy))
        case _            => None
    else None

  // NOTE: no default-strategy overloads/defaults here on purpose — a call site that
  // omits the strategy would compile fine and silently extract nothing from Mermaid
  // SVGs, undoing the strategy injection. The compiler forces callers to pass one.

  /** Marks a layout-transition exit ghost (the wrap AND the adopted element).
    * Ghosts KEEP their original classes — Mermaid styles nodes/edges through
    * class-scoped CSS inside the svg, and stripping the class made every
    * ghosted rect/path render with SVG's default black fill — so exclusion
    * from selection/capture happens here, by marker, not by re-classing.
    */
  val exitGhostClass = "gx-exit-ghost"

  /** Find all selectable elements in a container using the specified strategy.
    * Hit-area clones are excluded: they duplicate their original's id, and being
    * inserted BEFORE it they would otherwise win headOption-style lookups.
    * Exit ghosts are excluded: they are scenery from the PREVIOUS layout, and
    * capturing or selecting one would resurrect a departed element.
    */
  def findAll(ref: dom.Element, strategy: SelectableElementStrategy): Seq[SelectableElement] =
    ref
      .querySelectorAllT[dom.Element](strategy.allSelector)
      .filterNot(_.classList.contains(hitAreaClass))
      .filterNot(_.closest(s".$exitGhostClass") != null)
      .flatMap(fromDomElement(_, strategy))

  /** Query specific elements by ID using the specified strategy. */
  def query(ref: dom.Element, elems: ElementIds, strategy: SelectableElementStrategy): Seq[SelectableElement] =
    if elems.isEmpty then
      Seq.empty
    else
      strategy.idSelectorFor(elems) match
        case Some(selector) =>
          ref.querySelectorAllT[dom.Element](selector).flatMap(fromDomElement(_, strategy))
        case None =>
          findAll(ref, strategy).filter(elem => elems.contains(elem.elementId))

end SelectableElement

case class NodeElement(ref0: dom.svg.G, strat: SelectableElementStrategy)
    extends SelectableElement(ref0, strat):
  val selectedClass = "selected"
  lazy val elementId: NodeId = strat.extractNodeId(ref)

case class EdgeElement(ref0: dom.svg.Element, strat: SelectableElementStrategy)
    extends SelectableElement(ref0, strat):
  val selectedClass = "selected"

  lazy val elementId: ArrowId = strat.extractArrowId(ref)

  override def select(): Unit =
    ref.classList.add(selectedClass)
    // For Mermaid paths with marker-end, create a selected-state marker
    styleMarkerForSelection(selected = true)
    toggleEdgeLabelSelection(add = true)

  override def unselect(): Unit =
    ref.classList.remove(selectedClass)
    // Restore original marker
    styleMarkerForSelection(selected = false)
    toggleEdgeLabelSelection(add = false)

  /** Mirror the edge's selected class onto its LABEL group.
    *
    * Mermaid renders edge labels in a separate `g.edgeLabel`, correlated to the edge
    * only by mermaid's `data-id` stamp on the inner `g.label` — the label carries no
    * `.selected` of its own, so state-driven css (the focus-mode dimming) would fade
    * the label of a selected edge. DOT needs none of this: its labels live inside the
    * selected `g.edge` group, so the lookup finds nothing and this is a no-op.
    */
  private def toggleEdgeLabelSelection(add: Boolean): Unit =
    val domId = ref.id
    if domId.nonEmpty then
      Option(ref.asInstanceOf[js.Dynamic].ownerSVGElement.asInstanceOf[dom.Element]).foreach { svgRoot =>
        val inner = svgRoot.querySelector(s"""g.label[data-id="${CSSGlobal.escape(domId)}"]""")
        if inner != null then
          val labelGroup = inner.closest(".edgeLabel")
          if labelGroup != null then
            if add then labelGroup.classList.add(selectedClass)
            else labelGroup.classList.remove(selectedClass)
      }

  /** Style the arrow marker (arrowhead) for selection state.
    * Mermaid uses SVG markers which can't be styled via CSS cascade.
    * We create a cloned marker with selection styling and swap the marker-end reference.
    * Note: Mermaid may place markers in a <g> element rather than <defs>.
    */
  private def styleMarkerForSelection(selected: Boolean): Unit =
    ref match
      case path: dom.svg.Path =>
        val markerEnd = Option(path.getAttribute("marker-end")).filter(_.nonEmpty)
        markerEnd.foreach { url =>
          // Extract marker ID from url(#markerId)
          val markerId = url.stripPrefix("url(#").stripSuffix(")")

          if selected then {
            // Skip if already using a selected marker
            if !markerId.endsWith("-selected") then {
              val selectedMarkerId = s"$markerId-selected"

              // Find the SVG root and the original marker
              val svgRoot = path.ownerSVGElement
              if svgRoot != null then {
                val originalMarker = svgRoot.querySelector(s"#${CSSGlobal.escape(markerId)}")

                if originalMarker != null then {
                  // Use the marker's parent (could be <defs> or <g> in Mermaid)
                  val markerParent = originalMarker.parentNode
                  if markerParent != null then {
                    // Check if selected marker already exists in the SVG
                    val existingSelected = svgRoot.querySelector(s"#${CSSGlobal.escape(selectedMarkerId)}")
                    if existingSelected == null then {
                      // Clone marker and style for selection
                      val clonedMarker = originalMarker.cloneNode(true).asInstanceOf[dom.Element]
                      clonedMarker.setAttribute("id", selectedMarkerId)
                      // Style the path inside the marker with selection color
                      val markerPath = clonedMarker.querySelector("path")
                      if markerPath != null then {
                        markerPath.setAttribute("fill", "#2c70ff")
                        markerPath.setAttribute("stroke", "#2c70ff")
                      }
                      markerParent.appendChild(clonedMarker)
                    }
                    // Point to selected marker
                    path.setAttribute("marker-end", s"url(#$selectedMarkerId)")
                  }
                }
              }
            }
          } else {
            // Restore original marker by removing "-selected" suffix if present
            if markerId.endsWith("-selected") then {
              val originalMarkerId = markerId.stripSuffix("-selected")
              path.setAttribute("marker-end", s"url(#$originalMarkerId)")
            }
          }
        }
      case _ => ()

end EdgeElement

case class ClusterElement(ref0: dom.svg.G, strat: SelectableElementStrategy)
    extends SelectableElement(ref0, strat):
  val selectedClass = "selected"
  lazy val elementId: GroupId = strat.extractGroupId(ref)

// ------------------------------
// dom.Element extensions
// ------------------------------

extension (e: dom.Element)
  def parentNodes: LazyList[dom.Element] =
    e +: LazyList.unfold(e)(e => Option(e.parentNode.asInstanceOf[dom.Element]).map(e => (e, e)))

  def styleMap: Map[String, String] =
    styleToMap(e.getAttribute("style"))

  private def mapToStyle(m: Map[String, String]): String =
    m.map(_ + ":" + _).mkString(";")

  private def styleToMap(style: String | Null): Map[String, String] =
    if style == null || style.isEmpty
    then Map.empty
    else
      style
        .split(";")
        .filterNot(_.isEmpty)
        .map: str =>
          val arr = str.split(":")
          arr.head -> arr.tail.headOption.getOrElse("")
        .toMap

  def replaceStyle(keyValues: (String, String)*): Unit =
    e.setAttribute("style", mapToStyle(keyValues.toMap))

  def updateStyle(keyValues: (String, String)*): Unit =
    e.setAttribute("style", mapToStyle(e.styleMap ++ keyValues.toMap))

  def removeStyle(styleName: String): Unit =
    replaceStyle((e.styleMap - styleName).toList*)

def getUnionBBox(bboxes: Seq[dom.SVGRect]): BBox =
  // Initialize with extreme values in opposite directions
  var minX = Double.PositiveInfinity
  var minY = Double.PositiveInfinity
  var maxX = Double.NegativeInfinity
  var maxY = Double.NegativeInfinity

  for bbox <- bboxes do
    minX = Math.min(minX, bbox.x)
    minY = Math.min(minY, bbox.y)
    maxX = Math.max(maxX, bbox.x + bbox.width)
    maxY = Math.max(maxY, bbox.y + bbox.height)

  BBox(
    x = minX,
    y = minY,
    width = maxX - minX,
    height = maxY - minY
  )
