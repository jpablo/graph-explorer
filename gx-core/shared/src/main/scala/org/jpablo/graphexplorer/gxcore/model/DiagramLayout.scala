package org.jpablo.graphexplorer.gxcore.model

import scala.collection.immutable.VectorMap
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import upickle.core.BufferedValue

/** One point of a layout, in Graphviz points (1/72 inch).
  *
  * The frame is the frame of Graphviz: the y axis goes up. A node position is
  * the center of the node.
  */
final case class LayoutPoint(x: Double, y: Double) derives CanEqual:

  /** The point with two decimals. A position from a render has many digits,
    * and two decimals are much less than one screen pixel.
    */
  def rounded: LayoutPoint = LayoutPoint(LayoutPoint.round2(x), LayoutPoint.round2(y))

object LayoutPoint:
  private def round2(v: Double): Double = math.round(v * 100).toDouble / 100

/** The stored position of one node: the center of the node.
  *
  * `other` holds each key of the node that this build does not know, with its
  * JSON text. A write gives it back.
  */
final case class NodePlace(x: Double, y: Double, other: Vector[(String, String)] = Vector.empty) derives CanEqual:
  def point: LayoutPoint = LayoutPoint(x, y)

/** The bend points of one arrow, from its start to its end (part 4 of the
  * feature). The key of an arrow is `FreeLayout.arrowKeys`.
  */
final case class ArrowBends(points: Vector[LayoutPoint], other: Vector[(String, String)] = Vector.empty)
    derives CanEqual

/** The hand positions of the nodes of one diagram (ADR 0004).
  *
  * The layout is not in the source of the diagram. A diagram with a file keeps
  * it in a side file next to the file (`<base name>.layout.json`). A diagram of
  * the browser library keeps the text of that file in its entry.
  *
  * A node with a stored position stays there. A node with no stored position
  * gets an automatic place. A layout with no position changes nothing.
  *
  * The keys of `nodes` are node ids. The layout keeps an id that the graph does
  * not have: an edit of the source can bring the node back, for example an
  * undo of a rename. The render ignores such an id ([[restrictedTo]]).
  */
final case class DiagramLayout(
    nodes:  Map[String, NodePlace] = Map.empty,
    arrows: Map[String, ArrowBends] = Map.empty,
    other:  Vector[(String, String)] = Vector.empty
) derives CanEqual:

  /** True when at least one node has a stored position. */
  def hasPositions: Boolean = nodes.nonEmpty

  def point(id: String): Option[LayoutPoint] = nodes.get(id).map(_.point)

  /** Set the position of each node of `points`. Each other node keeps its position. */
  def placed(points: Map[String, LayoutPoint]): DiagramLayout =
    copy(nodes = points.foldLeft(nodes):
      case (done, (id, p)) => done.updated(id, done.get(id).fold(NodePlace(p.x, p.y))(_.copy(x = p.x, y = p.y))))

  /** Move each stored node of `ids` by `dx` and `dy`. A node with no stored position does not move. The bends of
    * each arrow of `arrows` (by its key) move by the same amount: the caller gives the arrows with both ends in `ids`.
    */
  def movedBy(ids: Set[String], dx: Double, dy: Double, arrows: Set[String] = Set.empty): DiagramLayout =
    copy(
      nodes = nodes.map((id, place) => if ids.contains(id) then id -> place.copy(x = place.x + dx, y = place.y + dy) else id -> place),
      arrows = this.arrows.map: (key, bends) =>
        if arrows.contains(key) then key -> bends.copy(points = bends.points.map(p => LayoutPoint(p.x + dx, p.y + dy)))
        else key -> bends
    )

  /** The bend points of the arrow with the key `key`, from its start to its end. No entry gives no point. */
  def bends(key: String): Vector[LayoutPoint] = arrows.get(key).fold(Vector.empty)(_.points)

  /** Set the bend points of the arrow with the key `key`. No point removes the entry, but an entry with keys that this
    * build does not know stays, with no point.
    */
  def withBends(key: String, points: Vector[LayoutPoint]): DiagramLayout =
    arrows.get(key) match
      case Some(entry) if points.isEmpty && entry.other.nonEmpty => copy(arrows = arrows.updated(key, entry.copy(points = Vector.empty)))
      case _ if points.isEmpty                                 => copy(arrows = arrows - key)
      case Some(entry)                                         => copy(arrows = arrows.updated(key, entry.copy(points = points)))
      case None                                                => copy(arrows = arrows.updated(key, ArrowBends(points)))

  /** Remove the bend points of each arrow of `keys`. Graphviz then routes these arrows again. */
  def withoutBends(keys: Set[String]): DiagramLayout =
    keys.foldLeft(this)((done, key) => done.withBends(key, Vector.empty))

  /** The layout with no position and no bend point. The keys that this build does not know stay. */
  def withoutPositions: DiagramLayout = copy(nodes = Map.empty, arrows = Map.empty)

  /** The positions of the nodes of `ids` only. The render uses this, so that it ignores an id that the graph does
    * not have.
    */
  def restrictedTo(ids: Set[String]): DiagramLayout =
    if nodes.keysIterator.forall(ids.contains) then this
    else copy(nodes = nodes.filter((id, _) => ids.contains(id)))

object DiagramLayout:

  /** The version of the layout file that this build reads and writes. */
  val Version = 1

  /** The end of the name of a layout file: `arch.dot` has `arch.layout.json`. */
  val Suffix = ".layout.json"

  val empty: DiagramLayout = DiagramLayout()

  /** True for the path of a layout file. Such a file is not a diagram. */
  def isLayoutPath(path: String): Boolean = path.toLowerCase.endsWith(Suffix)

  // ------------------------------------------------------------ the file

  private final case class BadLayout(message: String) extends Exception(message)

  /** Read the text of a layout file.
    *
    * The reader refuses a version that is newer than [[Version]], because a
    * write of this build could then remove content. A file with no version is
    * version 1.
    */
  def parse(text: String): Either[String, DiagramLayout] =
    try
      val root = objectOf(JsonText.parse(text), "the layout file")
      val version = root.get("version") match
        case None => Version
        case Some(value) =>
          JsonText.toValue(value) match
            case ujson.Num(n) => n.toInt
            case _            => throw BadLayout(s"the version is not a number: ${JsonText.render(value)}")
      if version > Version then
        throw BadLayout(s"the layout file has version $version, and this build reads version $Version. Use a newer Graph Explorer.")
      val nodes = root.get("nodes").fold(Map.empty[String, NodePlace]): value =>
        objectOf(value, "the nodes").map((id, place) => id -> parseNode(id, place)).toMap
      val arrows = root.get("arrows").fold(Map.empty[String, ArrowBends]): value =>
        objectOf(value, "the arrows").map((key, bends) => key -> parseArrow(key, bends)).toMap
      Right(DiagramLayout(nodes, arrows, unknown(root, Set("version", "nodes", "arrows"))))
    catch
      case BadLayout(message) => Left(message)
      case NonFatal(e)        => Left(s"the layout file is not valid JSON: ${e.getMessage}")

  private def objectOf(value: BufferedValue, what: String): VectorMap[String, BufferedValue] =
    value match
      case obj: BufferedValue.Obj => JsonText.fields(obj)
      case other                  => throw BadLayout(s"$what is not a JSON object: ${JsonText.render(other)}")

  // A write gives each number as a Double. ujson writes a Double that is not
  // finite as a text, and the reader refuses a text. Thus the reader also
  // refuses such a number here, as the board file does.
  private def number(obj: VectorMap[String, BufferedValue], key: String, where: String): Double =
    obj.get(key) match
      case None => throw BadLayout(s"$where has no $key")
      case Some(value) =>
        JsonText.toValue(value) match
          case ujson.Num(n) if n.isFinite => n
          case _                          => throw BadLayout(s"the $key of $where is not a finite number: ${JsonText.render(value)}")

  private def parseNode(id: String, value: BufferedValue): NodePlace =
    val where = s"the node \"$id\""
    val obj   = objectOf(value, where)
    NodePlace(number(obj, "x", where), number(obj, "y", where), unknown(obj, Set("x", "y")))

  private def parseArrow(key: String, value: BufferedValue): ArrowBends =
    val where = s"the arrow \"$key\""
    val obj   = objectOf(value, where)
    val points = obj.get("points") match
      case None                             => Vector.empty
      case Some(BufferedValue.Arr(items, _)) =>
        items.toVector.map: item =>
          val p = objectOf(item, s"a point of $where")
          LayoutPoint(number(p, "x", s"a point of $where"), number(p, "y", s"a point of $where"))
      case Some(other) => throw BadLayout(s"the points of $where are not a list: ${JsonText.render(other)}")
    ArrowBends(points, unknown(obj, Set("points")))

  /** Each key that is not in `known`, with its JSON text. */
  private def unknown(obj: VectorMap[String, BufferedValue], known: Set[String]): Vector[(String, String)] =
    obj.iterator.collect { case (key, value) if !known.contains(key) => key -> JsonText.render(value) }.toVector

  /** The text of a layout file. One layout always gives the same bytes: the
    * nodes and the arrows are in the order of their keys, and each key that
    * this build does not know comes after the known keys, in the order of the
    * read.
    */
  def render(layout: DiagramLayout): String =
    def num(v: Double): BufferedValue = JsonText.of(ujson.Num(v))
    def pointValue(p: LayoutPoint): BufferedValue = JsonText.obj(Vector("x" -> num(p.x), "y" -> num(p.y)), Vector.empty)
    val nodes = JsonText.obj(
      layout.nodes.toVector.sortBy(_._1).map((id, place) =>
        id -> JsonText.obj(Vector("x" -> num(place.x), "y" -> num(place.y)), place.other)),
      Vector.empty
    )
    val known = Vector("version" -> num(Version.toDouble), "nodes" -> nodes) ++
      Option.when(layout.arrows.nonEmpty)(
        "arrows" -> JsonText.obj(
          layout.arrows.toVector.sortBy(_._1).map((key, bends) =>
            key -> JsonText.obj(
              Vector("points" -> BufferedValue.Arr(ArrayBuffer.from(bends.points.map(pointValue)), -1)),
              bends.other
            )),
          Vector.empty
        )
      )
    JsonText.render(JsonText.obj(known, layout.other), indent = 2) + "\n"
