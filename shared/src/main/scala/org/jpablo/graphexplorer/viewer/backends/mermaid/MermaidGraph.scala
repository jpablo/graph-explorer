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
  * @param img
  *   The image of an image shape (`id@{ img: "..." }`, Mermaid 11)
  * @param assetWidth
  *   The width of the image, in pixels (`w:` of the shape data)
  * @param assetHeight
  *   The height of the image, in pixels (`h:` of the shape data)
  * @param link
  *   The link of a `click id href "url"` line
  * @param linkTarget
  *   The window of the link (`_blank`, `_self`, `_parent`, `_top`)
  * @param tooltip
  *   The tooltip of the click line
  */
case class MermaidVertex(
    id:          String,
    text:        String,
    labelType:   Option[String] = None,
    domId:       Option[String] = None,
    styles:      List[String] = Nil,
    classes:     List[String] = Nil,
    shape:       Option[String] = None,
    img:         Option[String] = None,
    assetWidth:  Option[Double] = None,
    assetHeight: Option[Double] = None,
    link:        Option[String] = None,
    linkTarget:  Option[String] = None,
    tooltip:     Option[String] = None
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
    stroke:    Option[String] = None,
    styles:    List[String] = Nil,
    interpolate: Option[String] = None
) derives ReadWriter

/** Represents a subgraph (group) in a Mermaid flowchart.
  *
  * @param id
  *   The unique identifier for this subgraph
  * @param title
  *   The display title for this subgraph
  * @param nodes
  *   List of vertex IDs contained in this subgraph
  * @param direction
  *   The layout direction of the `direction` line in this subgraph (TB, BT, LR, RL), if it has one
  */
case class MermaidSubgraph(
    id:    String,
    title: Option[String] = None,
    nodes: List[String] = Nil,
    classes: List[String] = Nil,
    direction: Option[String] = None
) derives ReadWriter

/** Represents Mermaid classDef declarations.
  *
  * Mermaid keeps shape styles and text styles separately in its internal DB. We preserve both so effective-style
  * resolution can compose them later without reparsing Mermaid internals.
  */
case class MermaidClassDef(
    styles:     List[String] = Nil,
    textStyles: List[String] = Nil
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
    direction: Option[String] = None,
    title:     Option[String] = None,
    classDefs: Map[String, MermaidClassDef] = Map.empty,
    defaultEdgeStyle: List[String] = Nil,
    defaultEdgeInterpolate: Option[String] = None,
    /** The merged init directive of the source, as JSON text (see [[MermaidInitDirective.configOf]]). */
    initConfig: Option[String] = None,
    /** The outline of the `%%` comments of the source, as JSON text (see [[MermaidComments.outlineOf]]). */
    comments: Option[String] = None
) derives ReadWriter
