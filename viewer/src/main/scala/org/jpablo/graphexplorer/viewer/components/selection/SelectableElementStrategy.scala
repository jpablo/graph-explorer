package org.jpablo.graphexplorer.viewer.components.selection

import org.jpablo.graphexplorer.viewer.models.{ArrowId, Arrow, GroupId, NodeId}
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

  /** Extract a NodeId from an SVG group element. */
  def extractNodeId(g: dom.svg.G): NodeId

  /** Extract an ArrowId from an SVG group element. */
  def extractArrowId(g: dom.svg.G): ArrowId

  /** Extract a GroupId from an SVG group element. */
  def extractGroupId(g: dom.svg.G): GroupId

  /** Check if an element is a node. */
  def isNode(g: dom.svg.G): Boolean = g.classList.contains("node")

  /** Check if an element is an edge. */
  def isEdge(g: dom.svg.G): Boolean = g.classList.contains("edge")

  /** Check if an element is a cluster. */
  def isCluster(g: dom.svg.G): Boolean = g.classList.contains("cluster")

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

  override def extractNodeId(g: dom.svg.G): NodeId =
    val title = g.querySelector("title")
    if title != null then NodeId(title.textContent)
    else NodeId(g.id)

  override def extractArrowId(g: dom.svg.G): ArrowId =
    // Try to parse from id attribute first (format: "arrow:A->B/1")
    val svgId = g.id
    Arrow.fromSvg(svgId).getOrElse {
      // Fallback to title content
      val title = g.querySelector("title")
      if title != null then ArrowId(title.textContent)
      else ArrowId(svgId)
    }

  override def extractGroupId(g: dom.svg.G): GroupId =
    val svgId = g.id
    GroupId.fromSvg(svgId).getOrElse {
      val title = g.querySelector("title")
      if title != null then GroupId(title.textContent)
      else GroupId(svgId)
    }

/** Strategy for Mermaid-generated SVGs.
  *
  * Mermaid SVG structure:
  *   - Nodes: `<g class="node" id="flowchart-nodeId-123">...</g>`
  *   - Edges: `<g class="edgePath">...</g>` or edges without consistent IDs
  *   - Subgraphs: `<g class="cluster">...</g>`
  */
object MermaidSelectionStrategy extends SelectableElementStrategy:
  override def nodeSelector: String    = "g.node"
  override def edgeSelector: String    = "g.edgePath, g.edge"
  override def clusterSelector: String = "g.cluster"

  // Pattern to extract node ID from Mermaid's DOM ID format
  // e.g., "flowchart-start-1414" -> "start"
  private val mermaidNodeIdPattern = """flowchart-(.+)-\d+""".r

  override def extractNodeId(g: dom.svg.G): NodeId =
    val svgId = g.id
    svgId match
      case mermaidNodeIdPattern(nodeId) => NodeId(nodeId)
      case _ if svgId.nonEmpty          => NodeId(svgId)
      case _ =>
        // Fallback: try to find a title or use data attributes
        val title = g.querySelector("title")
        if title != null then NodeId(title.textContent)
        else NodeId(s"node-${g.hashCode}")

  override def extractArrowId(g: dom.svg.G): ArrowId =
    // Mermaid edges don't have consistent IDs, so we'll create one from the path
    val svgId = g.id
    if svgId.nonEmpty then ArrowId(svgId)
    else
      // Try to extract edge info from the element structure
      // This is a fallback - ideally we'd have better ID tracking
      val title = g.querySelector("title")
      if title != null then ArrowId(title.textContent)
      else ArrowId(s"edge-${g.hashCode}")

  override def extractGroupId(g: dom.svg.G): GroupId =
    val svgId = g.id
    if svgId.nonEmpty then GroupId(svgId)
    else
      val title = g.querySelector("title")
      if title != null then GroupId(title.textContent)
      else GroupId(s"group-${g.hashCode}")

  override def isEdge(g: dom.svg.G): Boolean =
    g.classList.contains("edge") || g.classList.contains("edgePath")
