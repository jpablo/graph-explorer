package org.jpablo.graphexplorer.viewer.components.selection

import org.jpablo.graphexplorer.viewer.models.{Arrow, ArrowId, ElementIds, GroupId, NodeId}
import org.scalajs.dom

/** Strategy for extracting element IDs from SVG elements.
  *
  * Different diagram formats (Graphviz, Mermaid) generate SVGs with different structures. This trait abstracts the ID
  * extraction logic so the selection system can work with any format.
  */
trait SelectableElementStrategy:
  /** CSS selector for node elements. */
  def nodeSelector: String

  /** CSS selector for edge elements. */
  def edgeSelector: String

  /** CSS selector for cluster/group elements. */
  def clusterSelector: String

  /** Combined selector for all selectable elements. */
  def allSelector: String = s"$nodeSelector, $edgeSelector, $clusterSelector"

  /** Optional CSS selector for querying elements by ElementId. */
  def idSelectorFor(elems: ElementIds): Option[String] = None

  /** Extract a NodeId from an SVG element. */
  def extractNodeId(e: dom.Element): NodeId

  /** Extract an ArrowId from an SVG element. */
  def extractArrowId(e: dom.Element): ArrowId

  /** Extract a GroupId from an SVG element. */
  def extractGroupId(e: dom.Element): GroupId

  /** Check if an element is a node. */
  def isNode(e: dom.Element): Boolean = e.matches(nodeSelector)

  /** Check if an element is an edge. */
  def isEdge(e: dom.Element): Boolean = e.matches(edgeSelector)

  /** Check if an element is a cluster. */
  def isCluster(e: dom.Element): Boolean = e.matches(clusterSelector)

/** Strategy for Graphviz-generated SVGs.
  *
  * Graphviz SVG structure:
  *   - Nodes: `<g class="node"><title>nodeId</title>...</g>`
  *   - Edges: `<g class="edge" id="arrow:A->B/1"><title>...</title>...</g>`
  *   - Clusters: `<g class="cluster" id="group:clusterId"><title>...</title>...</g>`
  */
object GraphvizSelectionStrategy extends SelectableElementStrategy:
  override def nodeSelector: String    = "g.node"
  override def edgeSelector: String    = "g.edge"
  override def clusterSelector: String = "g.cluster"

  override def idSelectorFor(elems: ElementIds): Option[String] =
    val selectors = elems.ids.map(id => s"[id='${id.toSvg}']")
    if selectors.nonEmpty then Some(selectors.mkString(",")) else None

  override def extractNodeId(e: dom.Element): NodeId =
    val title = e.querySelector("title")
    if title != null then NodeId(title.textContent)
    else NodeId(e.id)

  override def extractArrowId(e: dom.Element): ArrowId =
    // Try to parse from id attribute first (format: "arrow:A->B/1")
    val svgId = e.id
    Arrow.fromSvg(svgId).getOrElse {
      // Fallback to title content
      val title = e.querySelector("title")
      if title != null then ArrowId(title.textContent)
      else ArrowId(svgId)
    }

  override def extractGroupId(e: dom.Element): GroupId =
    val svgId = e.id
    GroupId.fromSvg(svgId).getOrElse {
      val title = e.querySelector("title")
      if title != null then GroupId(title.textContent)
      else GroupId(svgId)
    }

/** Strategy for Mermaid-generated SVGs.
  *
  * Mermaid SVG structure:
  *   - Nodes: `<g class="node" id="flowchart-nodeId-123">...</g>`
  *   - Edges: `<path class="flowchart-link" id="L-A-B-0">...</path>`
  *   - Subgraphs: `<g class="cluster">...</g>`
  */
object MermaidSelectionStrategy extends SelectableElementStrategy:
  override def nodeSelector: String    = "g.node"
  // edge-label-hit: the invisible rect over an edge label (MermaidBackend.addEdgeHitAreas);
  // it resolves to its edge through data-edge-id like the hit-halo clones do.
  override def edgeSelector: String    = s"g.edgePath, g.edge, path.flowchart-link, path.edgePath, rect.${SelectableElement.edgeLabelHitClass}"
  override def clusterSelector: String = "g.cluster"

  // Pattern to extract node ID from Mermaid's DOM ID format
  // e.g., "flowchart-start-1414" -> "start"
  private val mermaidNodeIdPattern = """flowchart-(.+)-\d+""".r
  // Edge DOM ids changed across Mermaid majors: old renderers emitted "L-A-B-0" plus
  // LS-/LE- source/target classes; Mermaid 11 emits "L_A_B_0" with neither class.
  // Missing the v11 form made extractArrowId fall through to a garbage hashCode id,
  // which the stale-selection pruner immediately dropped — edges were unselectable.
  private val mermaidEdgeIdPattern    = """L-(.+)-(.+)-(\d+)""".r
  private val mermaidEdgeIdPatternV11 = """L_(.+)_(.+)_(\d+)""".r
  // A SELF-LOOP is the one edge Mermaid does not render as a single path. It
  // emits three siblings — `<node>-cyclic-special-1`, `-mid`, `-2` — matching
  // neither form above, so this fell through to the `edge-${hashCode}` fallback
  // and cost two bugs at once: an id matching no arrow in the model (Backspace
  // deleted nothing), and a DIFFERENT garbage id per segment, so selecting the
  // loop marked only the segment clicked and the casing covered a third of it.
  //
  // The id is keyed on the NODE, not the edge: two `a --> a` edges produce one
  // set of paths and one drawn loop, so there is no sequence to recover and
  // seq is 1. Selecting either duplicate therefore resolves to the first.
  //
  // `(.+)` is greedy so a hyphenated node keeps its whole name —
  // `my-node-cyclic-special-mid` is `my-node`, not `my`.
  private val mermaidSelfLoopIdPattern = """(.+)-cyclic-special-(?:\d+|mid)""".r

  private def classList(e: dom.Element): Seq[String] =
    (0 until e.classList.length).flatMap(i => Option(e.classList.item(i)))

  private def classPrefixed(e: dom.Element, prefix: String): Option[String] =
    classList(e).collectFirst { case name if name.startsWith(prefix) => name.drop(prefix.length) }

  private def dataAttr(e: dom.Element, name: String): Option[String] =
    Option(e.getAttribute(name)).filter(_.nonEmpty)

  override def extractNodeId(e: dom.Element): NodeId =
    dataAttr(e, "data-id")
      .orElse(dataAttr(e, "data-node-id"))
      .map(NodeId(_))
      .getOrElse {
        val svgId = e.id
        svgId match
          case mermaidNodeIdPattern(nodeId) => NodeId(nodeId)
          case _ if svgId.nonEmpty          => NodeId(svgId)
          case _ =>
            // Fallback: try to find a title or use data attributes
            val title = e.querySelector("title")
            if title != null then NodeId(title.textContent)
            else NodeId(s"node-${e.hashCode}")
      }

  override def extractArrowId(e: dom.Element): ArrowId =
    def arrowIdFromElement(edgeElem: dom.Element): Option[ArrowId] =
      // Hit-area clones (MermaidBackend.addEdgeHitAreas) carry the original path's DOM
      // id in data-edge-id, since element ids must stay unique.
      val domId = dataAttr(edgeElem, "data-edge-id").getOrElse(edgeElem.id)
      val idParts =
        domId match
          // FIRST: a node literally named `L` would make `L-cyclic-special-1`
          // match the old `L-(.+)-(.+)-(\d+)` form as source "cyclic",
          // target "special".
          case mermaidSelfLoopIdPattern(n)        => Some((n, n, 1))
          case mermaidEdgeIdPatternV11(s, t, idx) => Some((s, t, idx.toInt + 1))
          case mermaidEdgeIdPattern(s, t, idx)    => Some((s, t, idx.toInt + 1))
          case _                                  => None
      // LS-/LE- classes (old renderers) are authoritative when present; otherwise
      // fall back to the endpoints parsed from the DOM id.
      val source = classPrefixed(edgeElem, "LS-").orElse(idParts.map(_._1))
      val target = classPrefixed(edgeElem, "LE-").orElse(idParts.map(_._2))
      val seq    = idParts.fold(1)(_._3)

      (source, target) match
        case (Some(s), Some(t)) =>
          Some(ArrowId(s"$s${Arrow.titleIdSeparator}$t${Arrow.sequenceSeparator}$seq"))
        case _ => None

    val candidates =
      e match
        case g: dom.svg.G =>
          Seq(Some(g), Option(g.querySelector("path")).map(_.asInstanceOf[dom.Element])).flatten
        case _ =>
          Seq(Some(e), Option(e.querySelector("path")).map(_.asInstanceOf[dom.Element])).flatten

    candidates.view
      .flatMap(arrowIdFromElement)
      .headOption
      .getOrElse {
        val titleOpt = candidates.iterator
          .flatMap(elem => Option(elem.querySelector("title")).map(_.textContent))
          .find(_.nonEmpty)
        titleOpt.map(ArrowId(_)).getOrElse(ArrowId(s"edge-${e.hashCode}"))
      }

  override def extractGroupId(e: dom.Element): GroupId =
    dataAttr(e, "data-id")
      .orElse(dataAttr(e, "data-group-id"))
      .map(GroupId(_))
      .getOrElse {
        val svgId = e.id
        if svgId.nonEmpty then GroupId(svgId)
        else
          val title = e.querySelector("title")
          if title != null then GroupId(title.textContent)
          else GroupId(s"group-${e.hashCode}")
      }

  override def isEdge(e: dom.Element): Boolean =
    e.classList.contains("edge") || e.classList.contains("edgePath") || e.classList.contains("flowchart-link")
      || e.classList.contains(SelectableElement.edgeLabelHitClass)
