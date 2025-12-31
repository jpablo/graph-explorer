package org.jpablo.graphexplorer.viewer.backends.mermaid

import upickle.default.*

/** Represents a vertex (node) in a Mermaid flowchart.
  *
  * @param id
  *   The unique identifier for this vertex
  * @param text
  *   The display text/label for this vertex
  * @param labelType
  *   The type of label (e.g., "text", "markdown")
  * @param domId
  *   The DOM element ID (e.g., "flowchart-start-1414")
  * @param styles
  *   Inline styles applied to this vertex
  * @param classes
  *   CSS classes applied to this vertex
  * @param shape
  *   The shape of the node (e.g., "rect", "circle", "diamond")
  */
case class MermaidVertex(
    id:        String,
    text:      String,
    labelType: Option[String] = None,
    domId:     Option[String] = None,
    styles:    List[String] = Nil,
    classes:   List[String] = Nil,
    shape:     Option[String] = None
) derives ReadWriter

/** Represents an edge (arrow/link) in a Mermaid flowchart.
  *
  * @param start
  *   The source vertex ID
  * @param end
  *   The target vertex ID
  * @param edgeType
  *   The type of arrow (e.g., "arrow_point", "arrow_open", "arrow_cross")
  * @param text
  *   Optional label text on the edge
  * @param labelType
  *   The type of label
  * @param stroke
  *   The stroke style (e.g., "normal", "dotted", "thick")
  */
case class MermaidEdge(
    start:     String,
    end:       String,
    edgeType:  Option[String] = None,
    text:      Option[String] = None,
    labelType: Option[String] = None,
    stroke:    Option[String] = None
) derives ReadWriter

/** Represents a subgraph (group) in a Mermaid flowchart.
  *
  * @param id
  *   The unique identifier for this subgraph
  * @param title
  *   The display title for this subgraph
  * @param nodes
  *   List of vertex IDs contained in this subgraph
  */
case class MermaidSubgraph(
    id:    String,
    title: Option[String] = None,
    nodes: List[String] = Nil
) derives ReadWriter

/** Represents a parsed Mermaid flowchart diagram.
  *
  * @param vertices
  *   Map of vertex ID to vertex data
  * @param edges
  *   List of edges in the diagram
  * @param subgraphs
  *   List of subgraphs in the diagram
  * @param direction
  *   The layout direction (TB, BT, LR, RL)
  */
case class MermaidGraph(
    vertices:  Map[String, MermaidVertex] = Map.empty,
    edges:     List[MermaidEdge] = Nil,
    subgraphs: List[MermaidSubgraph] = Nil,
    direction: Option[String] = None
) derives ReadWriter
