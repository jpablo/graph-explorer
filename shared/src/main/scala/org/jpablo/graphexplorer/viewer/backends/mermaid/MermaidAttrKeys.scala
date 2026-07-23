package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.models.AttributeId

/** Single home for the synthetic attribute keys used to round-trip Mermaid-specific
  * data through a ViewerGraph. The importer (ToViewerGraph), the exporter
  * (FromViewerGraph), and the effective-attributes resolver (graph.AttributesOps)
  * must all agree on these spellings — keep them here and nowhere else.
  */
object MermaidAttrKeys:
  /** Reserved class-name prefix carrying DOT shapes that have no Mermaid bracket form
    * (record, house, ...) through the round trip: the serializer emits
    * `class <id> gx-shape-<shape>` (undefined classes are inert in Mermaid rendering)
    * and the parser restores the Shape attribute from it — keeping shape-gated
    * features like Split/Transpose Record available in Mermaid mode.
    */
  val ShapeClassPrefix = "gx-shape-"
  val MermaidClassDefPrefix     = "mermaid_classDef_"
  val MermaidClassDefTextPrefix = "mermaid_classDefText_"

  val MermaidClassAttr                  = AttributeId("mermaid_class")
  val MermaidDefaultLinkStyleAttr       = AttributeId("mermaid_linkStyle_default")
  val MermaidDefaultLinkInterpolateAttr = AttributeId("mermaid_linkInterpolate_default")
  val MermaidEdgeStyleAttr              = AttributeId("mermaid_edgeStyle")
  val MermaidEdgeInterpolateAttr        = AttributeId("mermaid_edgeInterpolate")
  val MermaidEdgeTypeAttr               = AttributeId("mermaid_edgeType")
  val MermaidDomIdAttr                  = AttributeId("mermaid_domId")

  /** Tokenizes a `mermaid_class` attribute value (a list of class names) — the one
    * parser for this format, shared by the exporter and the style resolver.
    */
  def parseMermaidClassNames(value: String): Vector[String] =
    value
      .split("[,\\s]+")
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
