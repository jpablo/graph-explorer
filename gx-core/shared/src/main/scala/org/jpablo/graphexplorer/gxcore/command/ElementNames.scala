package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, dotSubgraphIds}
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidGraph
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, dotSubgraphNames}
import org.jpablo.graphexplorer.viewer.models.{Arrow, ArrowId, ElementId, GroupId, NodeId}

import scala.collection.immutable.VectorMap

/** One way to read `src..dst` or `src..dst#n`. `index` counts from 1. */
final case class ArrowName(source: String, target: String, index: Option[Int]) derives CanEqual

/** A name for an element, as a person or an agent writes it (plan §2.1),
  * before a resolver compares it with a diagram.
  *
  *   - `api` is the node `api`, or the group whose source name is `api`.
  *   - `api..db` is each arrow from `api` to `db`. `api..db#2` is the second.
  *   - `node:x`, `arrow:a->b/0` and `group:x` are the [[ElementRef]] forms.
  *
  * The arrow form uses `..` and not `->`, because a shell reads `>` as a
  * redirect. Then `gx set f api->db color=red` writes into a file `db`, with
  * no message.
  */
enum ElementName derives CanEqual:

  /** `node:x`, `arrow:a->b/0`, `group:x`. */
  case Prefixed(id: ElementId)

  /** Each other text. A node id can contain `..` or `#`, so the text can
    * also be one or more of the arrow readings.
    */
  case Bare(text: String, arrows: Vector[ArrowName])

object ElementName:

  private val Indexed = "(.+)#([0-9]+)".r

  /** A text with a known prefix is an [[ElementRef]]. A text with an
    * unknown prefix is a bare name, because a node id can contain a colon.
    */
  def parse(text: String): Either[String, ElementName] =
    text.split(":", 2) match
      case Array(kind, _) if ElementRef.Kinds.contains(kind) => ElementRef.parse(text).map(Prefixed(_))
      case _                                      => Right(Bare(text, arrowReadings(text)))

  /** Each reading of `src..dst` and `src..dst#n` in the text. Each `..` can be
    * the separator, because a node id can contain `..` too. The resolver
    * keeps the readings whose nodes exist.
    */
  def arrowReadings(text: String): Vector[ArrowName] =
    (1 until text.length - 2).toVector
      .filter(text.startsWith("..", _))
      .flatMap: i =>
        val source = text.substring(0, i)
        val rest   = text.substring(i + 2)
        val indexed = rest match
          case Indexed(target, n) => Some(ArrowName(source, target, Some(n.toIntOption.getOrElse(Int.MaxValue))))
          case _                  => None
        ArrowName(source, rest, None) +: indexed.toVector

/** The names of the elements of one diagram: the resolver of the names that
  * a person writes, and the names that `gx nodes`, `gx arrows` and
  * `gx groups` print.
  *
  * The rule that holds the two together: for each element, [[resolve]] of
  * [[render]] gives back exactly that element. When the short name of an
  * element also names a different element, [[render]] gives the form with a
  * prefix.
  *
  * `sourceNames` has the name that the source gives each group.
  * `GroupId.fromDot` removes the `cluster` prefix from the id of a DOT group
  * (`cluster_svc` becomes `svc`, and `clusterB` becomes `B`). When two
  * subgraphs give one id, the second gets `<id>_2` (see
  * `GroupId.fromDotNames`). [[ElementNames.dotGroupNames]] gets the names from
  * the structure that the DOT reader gives. A group with no name there gets
  * the name that the DOT writer prints (`dotSubgraphNames`), so a name and a
  * write agree.
  */
final class ElementNames private (
    graph:       ViewerGraph,
    val format:  DiagramFormat,
    sourceNames: VectorMap[GroupId, String]
):
  private val directed = graph.tpe.isDirected

  /** The name that the source gives a group. A group with no name in
    * `sourceNames` gets the name that the DOT writer gives it.
    */
  def groupName(id: GroupId): String =
    sourceNames.getOrElse(id, writtenName(id))

  private def writtenName(id: GroupId): String = format match
    case DiagramFormat.Mermaid => id.value
    case DiagramFormat.DOT     => writtenDotNames.getOrElse(id, id.value)

  private lazy val writtenDotNames: Map[GroupId, String] = dotSubgraphNames(graph.groups.values)

  /** The groups in the order of the source, then the other groups by id. */
  private lazy val orderedGroups: Vector[GroupId] =
    val named = sourceNames.keys.toVector.filter(graph.groups.contains)
    named ++ (graph.groupIds -- named).toVector.sortBy(_.value)

  private lazy val groupsByName: Map[String, Vector[GroupId]] = orderedGroups.groupBy(groupName)

  /** The arrows in the order of the source. `Arrow.seq` is that order: the
    * `_gvid` of the edge in DOT, and a count for each pair of nodes in
    * Mermaid.
    */
  private lazy val orderedArrows: Vector[Arrow] =
    graph.arrows.values.toVector.sortBy(a => (a.seq, a.id.value))

  /** The key of the arrows "between the same nodes". In an undirected graph
    * the order of the two nodes has no meaning.
    */
  private def pair(source: NodeId, target: NodeId): (NodeId, NodeId) =
    if directed || source.value <= target.value then (source, target) else (target, source)

  private lazy val between: Map[(NodeId, NodeId), Vector[Arrow]] =
    orderedArrows.groupBy(a => pair(a.source, a.target))

  // ------------------------------------------------ resolve

  /** The elements that a name gives. A name gives one element, or each arrow
    * of `src..dst`. The error tells why, in words that a person or an agent
    * can act on.
    */
  def resolve(text: String): Either[String, Vector[ElementId]] =
    ElementName.parse(text).flatMap:
      case ElementName.Prefixed(id)        => resolvePrefixed(id)
      case ElementName.Bare(bare, readings) => resolveBare(bare, readings)

  /** Resolve each name. The error reports each name that does not resolve,
    * not only the first: an agent then corrects all of them in one step.
    */
  def resolveAll(texts: Seq[String]): Either[String, Vector[ElementId]] =
    val (bad, good) = texts.map(resolve).partitionMap(identity)
    if bad.isEmpty then Right(good.flatten.distinct.toVector) else Left(bad.mkString("; "))

  private def resolvePrefixed(id: ElementId): Either[String, Vector[ElementId]] = id match
    case n: NodeId =>
      if graph.nodes.contains(n) then Right(Vector(n)) else Left(s"there is no node '${n.value}'")
    case a: ArrowId =>
      if graph.arrows.contains(a) then Right(Vector(a)) else Left(s"there is no arrow '${a.value}'")
    case g: GroupId =>
      // The id first, so that the form in the metadata (`group:svc`) keeps
      // its meaning. Then the name that the source gives the group.
      if graph.groups.contains(g) then Right(Vector(g))
      else
        groupsByName.getOrElse(g.value, Vector.empty) match
          case Vector(one) => Right(Vector(one))
          case Vector()    => Left(s"there is no group '${g.value}'")
          // These groups have one source name, so `group:<name>` names each
          // of them. Thus the hint gives `group:<id>`, which names one group.
          // `prefixed` would come back to this line, with no end.
          case several => Left(ambiguous(s"group:${g.value}", several, ElementRef.render))

  private def resolveBare(text: String, readings: Vector[ArrowName]): Either[String, Vector[ElementId]] =
    val node   = Option.when(graph.nodes.contains(NodeId(text)))(Vector[ElementId](NodeId(text)))
    val groups = groupsByName.getOrElse(text, Vector.empty).map(g => Vector[ElementId](g))
    val arrows = readings.flatMap(arrowsFor)

    val found  = (node.toVector ++ groups ++ arrows.flatMap(_.toOption)).distinctBy(_.toSet)
    val misses = arrows.flatMap(_.left.toOption).distinct

    found match
      case Vector(one) => Right(one)
      case Vector()    => Left(notFound(text, misses))
      case several     => Left(ambiguous(text, several.flatten.distinct))

  /** The arrows of one reading. `None` when a node of the reading does not
    * exist: then the reading is not a meaning of the text.
    */
  private def arrowsFor(r: ArrowName): Option[Either[String, Vector[ElementId]]] =
    val source = NodeId(r.source)
    val target = NodeId(r.target)
    (graph.nodes.contains(source), graph.nodes.contains(target)) match
      case (true, true) =>
        val all = between.getOrElse(pair(source, target), Vector.empty)
        val ids = all.map(a => a.id: ElementId)
        Some:
          r.index match
            case _ if all.isEmpty                   => Left(s"there is no arrow ${relation(r.source, r.target)}")
            case None                               => Right(ids)
            case Some(n) if n >= 1 && n <= all.size => Right(Vector(ids(n - 1)))
            case Some(n) =>
              val count   = if all.size == 1 then "is 1 arrow" else s"are ${all.size} arrows"
              val choices = (1 to all.size).map(k => s"${r.source}..${r.target}#$k").mkString(", ")
              Left(s"'${r.source}..${r.target}#$n' is out of range: there $count ${relation(r.source, r.target)} ($choices)")
      case (true, false) => Some(Left(s"there is no node '${r.target}'"))
      case (false, true) => Some(Left(s"there is no node '${r.source}'"))
      case (false, false) => None

  private def relation(source: String, target: String): String =
    if directed then s"from $source to $target" else s"between $source and $target"

  private def notFound(text: String, misses: Vector[String]): String =
    val why = if misses.isEmpty then "" else misses.mkString(": ", "; ", "")
    // `->` reaches gx only in quotes, because a shell reads `>` as a
    // redirect. An agent that wrote it wants the arrow form.
    val hint =
      if text.contains("->") || text.contains("--") then
        s". Write an arrow as ${text.replace("->", "..").replace("--", "..")}"
      else ""
    s"'$text' names no node, arrow or group$why$hint"

  private def ambiguous(text: String, ids: Vector[ElementId], name: ElementId => String = prefixed): String =
    s"'$text' names more than one element. Write one of these: ${ids.map(name).mkString(", ")}"

  // ------------------------------------------------ render

  /** The name that `gx nodes`, `gx arrows` and `gx groups` print: the node id,
    * `src..dst` (with `#k` only when there are several between the same
    * nodes), and the source name of a group. It is the form with a prefix
    * when the short name also names a different element.
    */
  def render(id: ElementId): String =
    val short = id match
      case n: NodeId  => n.value
      case g: GroupId => groupName(g)
      case a: ArrowId => graph.arrows.get(a).fold(a.value)(shortArrow)
    resolve(short) match
      case Right(Vector(only)) if only == id => short
      case _                                 => prefixed(id)

  private def shortArrow(a: Arrow): String =
    val all  = between.getOrElse(pair(a.source, a.target), Vector(a))
    val base = s"${a.source.value}..${a.target.value}"
    if all.sizeIs <= 1 then base else s"$base#${all.indexWhere(_.id == a.id) + 1}"

  /** The name with a prefix. It always names exactly one element. A group
    * gets its source name when that name is not the id of a different group.
    */
  def prefixed(id: ElementId): String = id match
    case g: GroupId =>
      val bySource = s"group:${groupName(g)}"
      resolve(bySource) match
        case Right(Vector(only)) if only == g => bySource
        case _                                => ElementRef.render(g)
    case other => ElementRef.render(other)

  /** Each node with its name, in the order of the source. */
  lazy val nodes: Vector[(NodeId, String)] = graph.nodes.keys.toVector.map(n => n -> render(n))

  /** Each arrow with its name, in the order of the source. */
  lazy val arrows: Vector[(ArrowId, String)] = orderedArrows.map(a => a.id -> render(a.id))

  /** Each group with its name, in the order of the source. */
  lazy val groups: Vector[(GroupId, String)] = orderedGroups.map(g => g -> render(g))

object ElementNames:

  def apply(
      graph:       ViewerGraph,
      format:      DiagramFormat,
      sourceNames: VectorMap[GroupId, String] = VectorMap.empty
  ): ElementNames =
    new ElementNames(graph, format, sourceNames)

  /** The name of each DOT subgraph, as the structure reader gives it, by the
    * id of its group, in the order of the source. `dotSubgraphIds` gives the
    * ids, as in `ToViewerGraph`. Thus each subgraph has its own group and its
    * own name, also when `clusterB` and `cluster_B` give one id.
    */
  def dotGroupNames(simpleGraph: SimpleGraph): VectorMap[GroupId, String] =
    VectorMap.from(dotSubgraphIds(simpleGraph).map((subgraph, id) => id -> subgraph.name))

  /** A Mermaid group has the id of its subgraph. */
  def mermaidGroupNames(graph: MermaidGraph): VectorMap[GroupId, String] =
    graph.subgraphs.foldLeft(VectorMap.empty[GroupId, String]): (names, s) =>
      if names.contains(GroupId(s.id)) then names else names.updated(GroupId(s.id), s.id)
