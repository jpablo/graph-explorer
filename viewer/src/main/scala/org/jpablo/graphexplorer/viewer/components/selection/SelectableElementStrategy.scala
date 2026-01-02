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
  override def edgeSelector: String    = "g.edgePath, g.edge, path.flowchart-link, path.edgePath"
  override def clusterSelector: String = "g.cluster"

  // Pattern to extract node ID from Mermaid's DOM ID format
  // e.g., "flowchart-start-1414" -> "start"
  private val mermaidNodeIdPattern = """flowchart-(.+)-\d+""".r
  private val mermaidEdgeIdPattern = """L-(.+)-(.+)-(\d+)""".r

  private def classList(e: dom.Element): Seq[String] =
    (0 until e.classList.length).flatMap(i => Option(e.classList.item(i)))

  private def classPrefixed(e: dom.Element, prefix: String): Option[String] =
    classList(e).collectFirst { case name if name.startsWith(prefix) => name.drop(prefix.length) }

  private def dataAttr(e: dom.Element, name: String): Option[String] =
    Option(e.getAttribute(name)).filter(_.nonEmpty)

  private def edgeIdSource(e: dom.Element): dom.Element =
    e match
      case g: dom.svg.G => Option(g.querySelector("path")).getOrElse(g)
      case _            => e

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
    val edgeElem = edgeIdSource(e)
    val svgId = edgeElem.id
    val seqFromId =
      svgId match
        case mermaidEdgeIdPattern(_, _, idx) => Some(idx.toInt + 1)
        case _                               => None
    val source = classPrefixed(edgeElem, "LS-")
    val target = classPrefixed(edgeElem, "LE-")

    (source, target) match
      case (Some(s), Some(t)) =>
        val seq = seqFromId.getOrElse(1)
        ArrowId(s"$s${Arrow.titleIdSeparator}$t${Arrow.sequenceSeparator}$seq")
      case _ =>
        svgId match
          case mermaidEdgeIdPattern(source, target, idx) =>
            ArrowId(s"$source${Arrow.titleIdSeparator}$target${Arrow.sequenceSeparator}${idx.toInt + 1}")
          case _ =>
            val title = edgeElem.querySelector("title")
            if title != null then ArrowId(title.textContent)
            else ArrowId(s"edge-${edgeElem.hashCode}")

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
