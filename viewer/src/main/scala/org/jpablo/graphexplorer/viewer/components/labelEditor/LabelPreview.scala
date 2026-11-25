package org.jpablo.graphexplorer.viewer.components.labelEditor

import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BgColor, FontColor, FontName, FontSize, Label, Pad, Shape}
import org.jpablo.graphexplorer.viewer.formats.dot.{HtmlLabels, TextUtils}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*

import scala.annotation.tailrec
import scala.collection.immutable.VectorMap

/** The preview of a label in the label dialogs. */
object LabelPreview:

  private val previewId = NodeId("preview")

  /** The attributes of a node that the preview does not keep.
    *
    * The preview draws one node alone. These attributes do not change how the
    * node looks, or they make no sense without the diagram: the label (the
    * dialog gives a new one), the external label, the names and the ids, the
    * layout results (`pos`, `rects`, `vertices`), the links and the tooltips,
    * and the layout hints (`group`, `pin`, `ordering`, `sortv`, `layer`, `z`).
    * The preview keeps each other attribute, as the canvas does.
    */
  private val droppedNodeAttributes = Set(
    "label", "xlabel", "_gvid", "name", "id", "comment",
    "pos", "rects", "vertices",
    "URL", "href", "target", "tooltip", "class",
    "group", "pin", "ordering", "sortv", "layer", "z"
  )

  /** The attributes of an arrow or a group that the preview keeps: the font. */
  private val fontAttributes = Set(FontName.attrId, FontSize.attrId, FontColor.attrId)

  /** A small DOT graph that draws `label` as the element `element` of `graph`
    * draws it: with the same shape, style, colors and font.
    *
    * `label` is the text of the dialog: HTML markup, or plain text with real
    * line breaks. A node keeps its visual attributes over the node defaults
    * that the canvas uses. An arrow label and a group label have no box: the
    * preview draws them as a `plaintext` node with the font of the element.
    * An element that is not in `graph` (a new node, for example) gets only
    * the defaults.
    */
  def previewDot(graph: ViewerGraph, element: ElementId, label: String): String =
    val attributes =
      element match
        case id: NodeId  => nodeAttributes(graph, id)
        case id: ArrowId => arrowAttributes(graph, id) + plaintext
        case id: GroupId => groupAttributes(graph, id) + plaintext
    previewText(graph, defaults(graph, element), attributes + (Label.attrId -> labelValue(label)))

  /** The font of an element: the Graphviz attributes `fontname`, `fontsize`
    * (points) and `fontcolor`. None is the default of Graphviz.
    */
  final case class ElementFont(face: Option[String], size: Option[Double], color: Option[String]) derives CanEqual

  /** The font that the canvas uses for the label of `element`. The rules are
    * the rules of [[previewDot]]: a node gets the node defaults and its own
    * attributes, an arrow and a group get the font of [[arrowAttributes]] and
    * [[groupAttributes]].
    */
  def elementFont(graph: ViewerGraph, element: ElementId): ElementFont =
    val attributes =
      element match
        case id: NodeId  => defaults(graph, element) ++ nodeAttributes(graph, id)
        case id: ArrowId => arrowAttributes(graph, id)
        case id: GroupId => groupAttributes(graph, id)
    def value(id: AttributeId) = attributes.get(id).map(_.toString.trim).filter(_.nonEmpty)
    ElementFont(
      face  = value(FontName.attrId),
      size  = value(FontSize.attrId).flatMap(_.toDoubleOption).filter(_ > 0),
      color = value(FontColor.attrId)
    )

  private val faceAttribute = """(?i)\bFACE\s*=\s*"([^"]*)"""".r

  /** The font faces of the diagram: the `fontname` attributes, and the `FACE`
    * attributes of the HTML-like labels. The list is sorted and has no
    * duplicates.
    */
  def diagramFaces(graph: ViewerGraph): List[String] =
    val sets =
      graph.nodes.values.map(_.attributes) ++
        graph.arrows.values.map(_.attributes) ++
        graph.groups.values.map(_.attributes) ++
        List(graph.elements.graphAttributes, graph.elements.defaultNodeAttributes, graph.elements.defaultArrowAttributes)
    val names = sets.flatMap(_.get(FontName.attrId)).map(_.toString)
    val faces = sets.flatMap(_.get(Label.attrId)).flatMap(l => faceAttribute.findAllMatchIn(l.toString).map(_.group(1)))
    (names ++ faces).map(_.trim).filter(_.nonEmpty).toList.distinct.sorted

  /** The label as the model keeps it: markup as it is, plain text escaped for DOT. */
  private def labelValue(label: String): AttrValue =
    if HtmlLabels.isHtml(label) then AttrValue(label) else AttrValue(TextUtils.escape(label))

  private def plaintext = Shape.attrId -> AttrValue(Shape.plaintext.toString)

  /** The node defaults of the preview graph. The canvas gives each node the
    * default theme (see `AttributesOps.withDefaultTheme`), and a graph can also
    * have defaults of its own. A graph from DOT has no defaults of its own,
    * because the reader gives each node its effective attributes.
    */
  private def defaults(graph: ViewerGraph, element: ElementId): Attributes =
    element match
      case _: NodeId => keepNodeAttributes(graph.defaultNodeTheme ++ graph.elements.defaultNodeAttributes)
      case _         => Attributes.empty

  private def keepNodeAttributes(attributes: Attributes): Attributes =
    attributes.filterKeys(id => !droppedNodeAttributes(id.value) && !id.value.startsWith("mermaid_"))

  private def nodeAttributes(graph: ViewerGraph, id: NodeId): Attributes =
    graph.nodes.get(id).fold(Attributes.empty)(node => keepNodeAttributes(node.attributes))

  /** The font of an arrow: its own font over the arrow defaults. */
  private def arrowAttributes(graph: ViewerGraph, id: ArrowId): Attributes =
    val own = graph.arrows.get(id).fold(Attributes.empty)(_.attributes)
    (graph.defaultEdgeTheme ++ graph.elements.defaultArrowAttributes ++ own).filterKeys(fontAttributes)

  /** The font of a group. In Graphviz a cluster gets the attributes of the
    * graph and of each cluster that holds it. Thus the font of the graph comes
    * first, then the font of each outer group, and last the font of the group.
    */
  private def groupAttributes(graph: ViewerGraph, id: GroupId): Attributes =
    @tailrec
    def outerGroups(current: GroupId, seen: List[GroupId]): List[GroupId] =
      graph.membership(current) match
        // A cycle in the memberships stops the walk.
        case Some(parent) if !seen.contains(parent) && graph.groups.contains(parent) =>
          outerGroups(parent, parent :: seen)
        case _ => seen
    // `outerGroups` gives the outermost group first and `id` last.
    val chain = outerGroups(id, List(id))
    val own   = chain.flatMap(graph.groups.get).map(_.attributes)
    own.foldLeft(graph.elements.graphAttributes)(_ ++ _).filterKeys(fontAttributes)

  /** The DOT text of a graph with one node. The DOT printer of the model
    * writes it, so the quotes, the HTML labels and the style attributes are
    * the same as on the canvas.
    */
  private def previewText(graph: ViewerGraph, nodeDefaults: Attributes, attributes: Attributes): String =
    val elements = ViewerGraphElements(
      nodes = VectorMap(previewId -> ViewerNode.nodeNoDefaults(previewId, attributes)),
      graphAttributes = Attributes(VectorMap(Pad.attrId -> AttrValue("0"), BgColor.attrId -> AttrValue("transparent"))),
      defaultNodeAttributes = nodeDefaults
    )
    ViewerGraph.viewerGraphToText(ViewerGraph(elements, id = "preview", tpe = graph.tpe), omitInternal = true)
