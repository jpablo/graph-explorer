package org.jpablo.graphexplorer.gxcore.layout

import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, LayoutPoint}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Arrow, ArrowId, AttributeId, Attributes, NodeId}

/** The graph that Graphviz draws for a diagram in the manual mode (ADR 0004).
  *
  * The free layout pins each node with a stored position (`pos="x,y!"`), and
  * the engine places each other node around the pinned nodes. The pins go
  * only into the text for the render. The source never gets them.
  *
  * The engine is `neato`:
  *   - `neato` keeps each pinned node exactly at its place, also in a group.
  *     `fdp` lays out a group as one unit, and it moves the pinned nodes of a
  *     group and the pinned nodes around it.
  *   - `neato` draws each group (a DOT cluster) as a box around its nodes.
  *   - `inputscale=72` reads a position in points, as the layout stores it.
  *     With no `inputscale`, Graphviz reads the position in inches.
  *   - `splines=true` routes each arrow around the nodes.
  *   - `overlap=true` (the default) turns off the overlap removal. The overlap
  *     removal moves a pinned node.
  */
object FreeLayout:

  /** The engine of the free layout. */
  val Engine = "neato"

  /** The graph attributes of the free layout. They replace the same attributes of the source. */
  val graphAttributes: Vector[(String, String)] =
    Vector("layout" -> Engine, "inputscale" -> "72", "splines" -> "true", "overlap" -> "true")

  /** The graph for the render.
    *
    * The same graph when the layout is not manual (`DiagramLayout.isManual`).
    * Thus an automatic diagram renders exactly as before. A manual layout
    * always uses `neato`, also with no position: an empty diagram in the
    * manual mode, and a node with no position, get an automatic place from
    * `neato`. The caller gives the layout restricted to the nodes of the full
    * graph (`DiagramLayout.restrictedTo`). A stored id that this graph does
    * not have adds no node.
    */
  def renderGraph(graph: ViewerGraph, layout: DiagramLayout): ViewerGraph =
    if !layout.isManual then graph
    else
      val elements = graph.elements
      val nodes = elements.nodes.map: (id, node) =>
        layout.point(id.value) match
          case None    => id -> node
          case Some(p) => id -> node.modifyAttrs.using(_ ++ Attributes.of("pos" -> posValue(p)))
      val engine = graphAttributes.map((k, v) => AttributeId(k) -> AttrValue(v)).toMap
      graph.copy(elements = elements.copy(nodes = nodes, graphAttributes = elements.graphAttributes ++ engine))

  /** The `pos` value of a pinned node: `x,y!`, in points, with no exponent. */
  def posValue(point: LayoutPoint): String = s"${number(point.x)},${number(point.y)}!"

  /** A number with no exponent and no `.0`: `12345678.5`, `0.01`, `27`. */
  private[gxcore] def number(v: Double): String =
    val plain = BigDecimal(v).bigDecimal.stripTrailingZeros.toPlainString
    if plain == "-0" then "0" else plain

  /** The key of each arrow for its bend points (part 4 of the feature).
    *
    * The key is `<source>[:<port>]-><target>[:<port>]#<n>`. `n` counts the
    * arrows with the same ends and ports, from 1, in the order of the source.
    * The key does not use `Arrow.seq`. In DOT, `seq` counts each arrow of the
    * file, so a new arrow before this arrow changes it.
    */
  def arrowKeys(graph: ViewerGraph): Map[ArrowId, String] =
    def ends(a: Arrow): String =
      val sp = a.sourcePort.fold("")(":" + _)
      val tp = a.targetPort.fold("")(":" + _)
      s"${a.source.value}$sp${Arrow.titleIdSeparator}${a.target.value}$tp"
    graph.arrows.values.toVector
      .groupBy(ends)
      .flatMap: (prefix, same) =>
        same.sortBy(_.seq).zipWithIndex.map((arrow, i) => arrow.id -> s"$prefix#${i + 1}")

  /** The bend points of each arrow of `graph` that has bend points, by its id (part 4 of the feature).
    *
    * The bends apply only in a free layout (the manual mode). An automatic diagram draws with its own engine, and that
    * engine routes each arrow. A key that the graph does not have gives nothing, and the layout keeps it.
    */
  def bendsByArrow(graph: ViewerGraph, layout: DiagramLayout): Map[ArrowId, Vector[LayoutPoint]] =
    if !layout.isManual || layout.arrows.isEmpty then Map.empty
    else
      arrowKeys(graph).flatMap: (id, key) =>
        val points = layout.bends(key)
        Option.when(points.nonEmpty)(id -> points)

  /** The keys of the arrows of `graph` with both ends in `nodes`. A move of these nodes moves the bends of these
    * arrows by the same amount.
    */
  def arrowKeysWithin(graph: ViewerGraph, nodes: Set[NodeId]): Set[String] =
    if nodes.isEmpty then Set.empty
    else
      val keys = arrowKeys(graph)
      graph.arrows.values.iterator
        .filter(a => nodes.contains(a.source) && nodes.contains(a.target))
        .flatMap(a => keys.get(a.id))
        .toSet

