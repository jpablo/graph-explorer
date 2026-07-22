package org.jpablo.graphexplorer.viewer.backends

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotJsonToViewerGraph}
import org.jpablo.graphexplorer.viewer.backends.mermaid.{MermaidTestScanner, toViewerGraph as mermaidToViewerGraph, viewerGraphToMermaidText}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BoldStyle, BorderStyle, FillColor, FillStyle, Label}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.read

import scala.collection.immutable.VectorMap

/** Cross-backend FEATURE PARITY matrix.
  *
  * Each row is a one-feature fixture graph pushed through `serialize -> parse` on BOTH
  * backends; a probe extracts the feature-relevant facts and must either survive the
  * round trip or match an explicitly declared `LossyAs` projection. A silent feature
  * loss in either backend fails here; a legitimate format limitation is visible as a
  * documented expectation instead of tribal knowledge.
  *
  * Parse paths: DOT uses the real product path for the `dot` engine (pure-Scala
  * graphviz -> dot_json -> SimpleGraph -> ViewerGraph). Mermaid's real parser is
  * browser-only, so `MermaidTestScanner` (a test-only reader of our serializer's
  * output) stands in for mermaid.js in front of the real `toViewerGraph`.
  */
class FeatureParitySpec extends FunSuite:

  // ------------------------------------------------------------------ expectations

  enum Expectation derives CanEqual:
    case Survives

    /** The feature is transformed by this format in a KNOWN, accepted way. */
    case LossyAs(projected: Any, reason: String)

  import Expectation.*

  final case class ParityRow(
      feature: String,
      graph:   ViewerGraph,
      probe:   ViewerGraph => Any,
      dot:     Expectation = Survives,
      mermaid: Expectation = Survives
  )

  // ------------------------------------------------------------------ round trips

  private def dotRoundTrip(g: ViewerGraph): (String, ViewerGraph) =
    val text   = viewerGraphToText(g, omitInternal = true)
    val result = ScalaGraphviz.renderFormats(text, Seq("dot_json"))
    val json = result.output.getOrElse(
      "dot_json",
      fail(s"DOT parse failed: ${result.errors.mkString("; ")}\n--- serialized ---\n$text")
    )
    (text, dotJsonToViewerGraph(read[SimpleGraph](json)))

  private def mermaidRoundTrip(g: ViewerGraph): (String, ViewerGraph) =
    val text = viewerGraphToMermaidText(g)
    (text, mermaidToViewerGraph(MermaidTestScanner.scan(text)))

  private def check(
      formatName:  String,
      row:         ParityRow,
      expectation: Expectation,
      roundTrip:   ViewerGraph => (String, ViewerGraph)
  ): Unit =
    val (serialized, rt) = roundTrip(row.graph)
    val expected = expectation match
      case Survives                  => row.probe(row.graph)
      case LossyAs(projected, _)     => projected
    val reasonNote = expectation match
      case LossyAs(_, reason) => s" (documented lossy: $reason)"
      case _                  => ""
    assertEquals(
      row.probe(rt),
      expected,
      s"[$formatName / ${row.feature}]$reasonNote\n--- serialized ---\n$serialized"
    )

  // ------------------------------------------------------------------ fixture helpers

  private def n(id: String, attrs: Attributes = Attributes.empty): (NodeId, ViewerNode) =
    NodeId(id) -> ViewerNode.nodeNoDefaults(NodeId(id), attrs)

  private def grp(id: String, attrs: Attributes = Attributes.empty): (GroupId, ViewerGroup) =
    GroupId(id) -> ViewerGroup.group(GroupId(id), attrs)

  private def graphOf(
      nodes:           Seq[(NodeId, ViewerNode)],
      arrows:          Seq[Arrow] = Nil,
      groups:          Seq[(GroupId, ViewerGroup)] = Nil,
      memberships:     Seq[(GroupMemberId, GroupId)] = Nil,
      graphAttributes: Attributes = Attributes.empty
  ): ViewerGraph =
    ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap.from(nodes),
        arrows = arrows.map(a => a.id -> a).toMap,
        memberships = VectorMap.from(memberships),
        groups = groups.toMap,
        graphAttributes = graphAttributes
      )
    )

  private val clusterAttrs = Attributes.of("cluster" -> "true")

  // ------------------------------------------------------------------ probes

  private def nodeAttr(g: ViewerGraph, id: String, attr: String): Option[String] =
    g.nodes.get(NodeId(id)).flatMap(_.attributes.get(AttributeId(attr)).map(_.toString)).filter(_.nonEmpty)

  private def arrowAttr(g: ViewerGraph, attr: String): Option[String] =
    g.arrows.values.headOption.flatMap(_.attributes.get(AttributeId(attr)).map(_.toString)).filter(_.nonEmpty)

  private def graphAttr(g: ViewerGraph, attr: String): Option[String] =
    g.elements.graphAttributes.get(AttributeId(attr)).map(_.toString).filter(_.nonEmpty)

  private def groupAttr(g: ViewerGraph, id: String, attr: String): Option[String] =
    g.groups.get(GroupId(id)).flatMap(_.attributes.get(AttributeId(attr)).map(_.toString)).filter(_.nonEmpty)

  private def edgePairs(g: ViewerGraph): Vector[(String, String)] =
    g.arrows.values.map(a => (a.source.value, a.target.value)).toVector.sorted

  private def nodeIdSet(g: ViewerGraph): Set[String] =
    g.nodes.keySet.map(_.value)

  private def membershipFacts(g: ViewerGraph): Vector[String] =
    g.memberships.toVector.map { (member, parent) =>
      val kind = member match
        case _: GroupId => "group"
        case _: NodeId  => "node"
      s"$kind:${member.value}->${parent.value}"
    }.sorted

  /** The effective edge line style, regardless of encoding: DOT import expands the
    * `style` attribute into the BorderStyle/BoldStyle sub-attributes and removes it,
    * so reading `style` alone would report false losses. Mirrors the resolution in
    * FromViewerGraph.edgeLineStyle.
    */
  private def effectiveEdgeLineStyle(g: ViewerGraph): Option[String] =
    g.arrows.values.headOption.flatMap { a =>
      a.attributes
        .get(AttributeId("style")).map(_.toString).filter(_.nonEmpty).filterNot(_.contains(":"))
        .orElse(Option.when(a.attributes.get(BoldStyle.attrId).exists(_.toString == "true"))("bold"))
        .orElse(a.attributes.get(BorderStyle.attrId).map(_.toString).filter(_.nonEmpty))
    }

  private def cssDecl(style: String, key: String): Option[String] =
    style.split(",").iterator.map(_.trim).collectFirst {
      case d if d.startsWith(s"$key:") => d.split(":", 2)(1).trim
    }

  /** The feature is "this element is filled with color X", regardless of whether the
    * format encodes it as a DOT attribute or a css declaration in `style`.
    */
  private def effectiveFill(attrs: Option[Attributes]): Option[String] =
    attrs
      .flatMap(_.get(AttributeId("fillcolor")).map(_.toString).filter(_.nonEmpty))
      .orElse(attrs.flatMap(_.get(AttributeId("style")).map(_.toString).filter(_.contains(":")).flatMap(cssDecl(_, "fill"))))

  private def effectiveNodeFill(g: ViewerGraph, id: String): Option[String] =
    effectiveFill(g.nodes.get(NodeId(id)).map(_.attributes))

  private def effectiveGroupFill(g: ViewerGraph, id: String): Option[String] =
    effectiveFill(g.groups.get(GroupId(id)).map(_.attributes))

  // ------------------------------------------------------------------ the matrix

  private val rows = List(
    ParityRow(
      "nodes and edges",
      graphOf(Seq(n("a"), n("b"), n("c")), arrows = Seq(Arrow(NodeId("a"), NodeId("b")), Arrow(NodeId("b"), NodeId("c")))),
      g => (nodeIdSet(g), edgePairs(g))
    ),
    ParityRow(
      "parallel edges",
      graphOf(Seq(n("a"), n("b")), arrows = Seq(Arrow(NodeId("a"), NodeId("b"), seq = 1), Arrow(NodeId("a"), NodeId("b"), seq = 2))),
      edgePairs
    ),
    ParityRow(
      "node label",
      graphOf(Seq(n("a", Attributes.of(Label -> "Hello World")))),
      nodeAttr(_, "a", "label")
    ),
    ParityRow(
      "node label with line break",
      graphOf(Seq(n("a", Attributes.of(Label -> "line1\\nline2")))),
      nodeAttr(_, "a", "label")
    ),
    ParityRow(
      "node label with literal backslash-n",
      graphOf(Seq(n("a", Attributes.of(Label -> "a\\\\nb")))),
      nodeAttr(_, "a", "label")
    ),
    ParityRow(
      "edge label",
      graphOf(Seq(n("a"), n("b")), arrows = Seq(Arrow(NodeId("a"), NodeId("b"), attributes = Attributes.of(Label -> "approved")))),
      arrowAttr(_, "label")
    ),
    ParityRow(
      "rankdir",
      graphOf(Seq(n("a")), graphAttributes = Attributes.of("rankdir" -> "LR")),
      graphAttr(_, "rankdir")
    ),
    ParityRow(
      "graph title",
      graphOf(Seq(n("a")), graphAttributes = Attributes.of("label" -> "My Title")),
      graphAttr(_, "label")
    ),
    ParityRow(
      "node shapes in Mermaid's vocabulary",
      graphOf(
        Seq(
          n("a", Attributes.of("shape" -> "box")),
          n("b", Attributes.of("shape" -> "diamond")),
          n("c", Attributes.of("shape" -> "circle")),
          n("d", Attributes.of("shape" -> "hexagon")),
          n("e", Attributes.of("shape" -> "cylinder"))
        )
      ),
      g => Vector("a", "b", "c", "d", "e").map(id => nodeAttr(g, id, "shape"))
    ),
    ParityRow(
      "node shape outside Mermaid's vocabulary",
      graphOf(Seq(n("a", Attributes.of("shape" -> "house")))),
      nodeAttr(_, "a", "shape"),
      mermaid = LossyAs(Some("box"), "Mermaid has no house shape; brackets collapse to a rectangle")
    ),
    ParityRow(
      "edge style dashed",
      graphOf(Seq(n("a"), n("b")), arrows = Seq(Arrow(NodeId("a"), NodeId("b"), attributes = Attributes.of("style" -> "dashed")))),
      effectiveEdgeLineStyle
    ),
    ParityRow(
      "edge style dotted",
      graphOf(Seq(n("a"), n("b")), arrows = Seq(Arrow(NodeId("a"), NodeId("b"), attributes = Attributes.of("style" -> "dotted")))),
      effectiveEdgeLineStyle,
      mermaid = LossyAs(Some("dashed"), "Mermaid's -.-> cannot distinguish dotted from dashed")
    ),
    ParityRow(
      "edge style bold",
      graphOf(Seq(n("a"), n("b")), arrows = Seq(Arrow(NodeId("a"), NodeId("b"), attributes = Attributes.of("style" -> "bold")))),
      effectiveEdgeLineStyle
    ),
    ParityRow(
      "flat group with label and members",
      graphOf(
        Seq(n("a"), n("b")),
        groups = Seq(grp("G1", clusterAttrs ++ Attributes.of(Label -> "Service Layer"))),
        memberships = Seq(NodeId("a") -> GroupId("G1"), NodeId("b") -> GroupId("G1"))
      ),
      g => (membershipFacts(g), groupAttr(g, "G1", "label"))
    ),
    ParityRow(
      "nested groups",
      graphOf(
        Seq(n("a"), n("b")),
        groups = Seq(grp("G1", clusterAttrs), grp("G2", clusterAttrs)),
        memberships = Seq(NodeId("a") -> GroupId("G1"), GroupId("G2") -> GroupId("G1"), NodeId("b") -> GroupId("G2"))
      ),
      membershipFacts
    ),
    ParityRow(
      "edge ports",
      graphOf(Seq(n("a"), n("b")), arrows = Seq(Arrow(NodeId("a"), NodeId("b"), sourcePort = Some("e"), targetPort = Some("w")))),
      g => g.arrows.values.headOption.map(a => (a.sourcePort, a.targetPort)),
      mermaid = LossyAs(Some((None, None)), "Mermaid has no port syntax; ports are dropped")
    ),
    ParityRow(
      "record node",
      graphOf(Seq(n("a", Attributes.of("shape" -> "record", "label" -> "f0|f1")))),
      g => (nodeAttr(g, "a", "shape"), nodeAttr(g, "a", "label")),
      mermaid = LossyAs(
        (Some("box"), Some("f0|f1")),
        "Mermaid has no record shapes; the label text survives, the field structure does not"
      )
    ),
    ParityRow(
      "arrow direction dir=back",
      graphOf(Seq(n("a"), n("b")), arrows = Seq(Arrow(NodeId("a"), NodeId("b"), attributes = Attributes.of("dir" -> "back")))),
      arrowAttr(_, "dir"),
      mermaid = LossyAs(None, "dir attribute is not mapped onto Mermaid's arrow forms (candidate fix: <--)")
    ),
    ParityRow(
      // The app always pairs fillcolor with the filled style sub-attribute
      // (combineStyleAttributes asserts the pairing), so the fixture does too.
      "node fill color (effective)",
      graphOf(Seq(n("a", Attributes.of(FillColor -> "#ff6467", FillStyle -> true)))),
      effectiveNodeFill(_, "a")
    ),
    ParityRow(
      "group fill color (effective)",
      graphOf(
        Seq(n("a")),
        groups = Seq(grp("G1", clusterAttrs ++ Attributes.of(FillColor -> "#bedbff", FillStyle -> true))),
        memberships = Seq(NodeId("a") -> GroupId("G1"))
      ),
      effectiveGroupFill(_, "G1"),
      mermaid = LossyAs(
        None,
        "GAP: the serializer writes `style G1 ...` but MermaidSubgraph has no styles field, so the parse side drops it"
      )
    ),
    ParityRow(
      "mermaid class assignment + classDef",
      graphOf(
        Seq(n("a", Attributes.of("mermaid_class" -> "pink"))),
        graphAttributes = Attributes.of("mermaid_classDef_pink" -> "fill:#f9f")
      ),
      g => (nodeAttr(g, "a", "mermaid_class"), graphAttr(g, "mermaid_classDef_pink")),
      dot = LossyAs(
        (None, None),
        "GAP: custom mermaid_* attributes survive in the DOT text but not the re-parse — SimpleGraph decodes only its known attribute fields"
      )
    )
  )

  rows.foreach { row =>
    test(s"parity: ${row.feature}"):
      check("DOT", row, row.dot, dotRoundTrip)
      check("Mermaid", row, row.mermaid, mermaidRoundTrip)
  }
