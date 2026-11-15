package org.jpablo.graphexplorer.gxcore.model

import scala.util.control.NonFatal

/** One thing on a board, with its place (CONTEXT.md, "Item"; ADR 0003).
  *
  * `location` is the text that the board file holds: a path relative to the
  * board file, an absolute path, or a URI. [[BoardLocation.resolve]] gives its
  * meaning. `x` and `y` are the top left corner of the item, in the units of
  * the board canvas. `width` is the width that the person gave to the item.
  * `None` is the natural width of the drawing.
  *
  * `other` holds each key of the item that this build does not know, with its
  * value as JSON text. A write gives them back. Thus an older build does not
  * remove the work of a newer build from a file that the person owns.
  */
final case class BoardItem(
    id:        String,
    location:  String,
    x:         Double,
    y:         Double,
    extractor: Option[String] = None,
    width:     Option[Double] = None,
    other:     Vector[(String, String)] = Vector.empty
) derives CanEqual

/** A board: the list of its items (ADR 0003).
  *
  * A board refers to its diagrams, and does not contain them. The place and
  * the size of an item are here, because they are the source of the board.
  * The hidden elements and the collapsed groups of a diagram are not here:
  * they are the metadata of the diagram.
  *
  * The order of the items is the order of the drawing: a later item is above
  * an earlier item.
  */
final case class Board(
    items: Vector[BoardItem] = Vector.empty,
    other: Vector[(String, String)] = Vector.empty
) derives CanEqual:

  def item(id: String): Option[BoardItem] = items.find(_.id == id)

  /** Add an item for `location`, with a new id. The id comes from `name`, or
    * else from the name in the location. A location with no name of its own
    * (an opaque id) needs `name`. The result is the new board and the new item.
    */
  def add(location: String, x: Double, y: Double, name: Option[String] = None): (Board, BoardItem) =
    val added = BoardItem(Board.freshId(name.getOrElse(location), items.map(_.id).toSet), location, x, y)
    (copy(items = items :+ added), added)

  /** Move an item. A board with no such item does not change. */
  def move(id: String, x: Double, y: Double): Board =
    update(id)(_.copy(x = x, y = y))

  /** Set the width of an item. `None` gives the natural width back. */
  def resize(id: String, width: Option[Double]): Board =
    update(id)(_.copy(width = width))

  def remove(id: String): Board =
    copy(items = items.filterNot(_.id == id))

  private def update(id: String)(change: BoardItem => BoardItem): Board =
    copy(items = items.map(item => if item.id == id then change(item) else item))

object Board:

  /** The version of the board file that this build reads and writes. */
  val Version = 1

  /** The extension of a board file. */
  val Extension = "gxboard"

  val empty: Board = Board()

  def isBoardPath(path: String): Boolean = path.toLowerCase.endsWith(s".$Extension")

  /** A new id for an item: the base name of `source` with no extension, in
    * lower case, with `-` for each other character. `source` is a location or
    * a name. A number makes the id different from each id in `taken`.
    */
  def freshId(source: String, taken: Set[String]): String =
    val name  = LibraryEntry.nameOf(source.substring(source.lastIndexOf(':') + 1))
    val slug  = name.toLowerCase.map(c => if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') then c else '-')
    val clean = slug.split('-').filter(_.nonEmpty).mkString("-")
    val base  = if clean.isEmpty then "item" else clean
    if !taken.contains(base) then base
    else Iterator.from(2).map(n => s"$base-$n").find(id => !taken.contains(id)).get

  // ------------------------------------------------------------ the file

  /** Read the text of a board file.
    *
    * The reader refuses a version that is newer than [[Version]], because a
    * write of this build could then remove content. It also refuses two items
    * with one id, because an id names one item.
    *
    * An item with no `x` or no `y` is at 0, so that a person can write a board
    * file by hand with only the locations.
    */
  def parse(text: String): Either[String, Board] =
    try
      val root = ujson.read(text).objOpt.getOrElse(throw BadBoard("the board file is not a JSON object"))
      val version = root.get("version") match
        case None                 => Version
        case Some(ujson.Num(n))   => n.toInt
        case Some(other)          => throw BadBoard(s"the version is not a number: ${other.render()}")
      if version > Version then
        throw BadBoard(s"the board file has version $version, and this build reads version $Version. Use a newer Graph Explorer.")
      val items = root.get("items") match
        case None                => Vector.empty
        case Some(ujson.Arr(xs)) => xs.toVector.zipWithIndex.map(parseItem)
        case Some(other)         => throw BadBoard(s"the items are not a list: ${other.render()}")
      val twice = items.groupBy(_.id).collect { case (id, same) if same.size > 1 => id }.toVector.sorted
      if twice.nonEmpty then throw BadBoard(s"more than one item has the id ${twice.mkString(", ")}")
      Right(Board(items, unknown(root, Set("version", "items"))))
    catch
      case BadBoard(message) => Left(message)
      case NonFatal(e)       => Left(s"the board file is not valid JSON: ${e.getMessage}")

  private final case class BadBoard(message: String) extends Exception(message)

  private val ItemKeys = Set("id", "location", "extractor", "x", "y", "width")

  private def parseItem(value: ujson.Value, index: Int): BoardItem =
    val where = s"item ${index + 1}"
    val obj   = value.objOpt.getOrElse(throw BadBoard(s"$where is not a JSON object"))
    def text(key: String): Option[String] = obj.get(key) match
      case None                      => None
      case Some(value) if value.isNull => None
      case Some(ujson.Str(s))      => Some(s)
      case Some(other)             => throw BadBoard(s"the $key of $where is not a text: ${other.render()}")
    def number(key: String): Option[Double] = obj.get(key) match
      case None                      => None
      case Some(value) if value.isNull => None
      case Some(ujson.Num(n))      => Some(n)
      case Some(other)             => throw BadBoard(s"the $key of $where is not a number: ${other.render()}")
    BoardItem(
      id = text("id").filter(_.nonEmpty).getOrElse(throw BadBoard(s"$where has no id")),
      location = text("location").filter(_.nonEmpty).getOrElse(throw BadBoard(s"$where has no location")),
      x = number("x").getOrElse(0.0),
      y = number("y").getOrElse(0.0),
      extractor = text("extractor"),
      width = number("width"),
      other = unknown(obj, ItemKeys)
    )

  private def unknown(obj: collection.Map[String, ujson.Value], known: Set[String]): Vector[(String, String)] =
    obj.iterator.collect { case (key, value) if !known.contains(key) => key -> value.render() }.toVector

  /** The text of a board file. One board always gives the same bytes: the
    * keys have a fixed order, and each key that this build does not know
    * comes after them, in the order of the read.
    */
  def render(board: Board): String =
    val root = ujson.Obj("version" -> ujson.Num(Version.toDouble), "items" -> ujson.Arr(board.items.map(renderItem)*))
    board.other.foreach((key, json) => root(key) = ujson.read(json))
    root.render(indent = 2) + "\n"

  private def renderItem(item: BoardItem): ujson.Value =
    val obj = ujson.Obj("id" -> ujson.Str(item.id), "location" -> ujson.Str(item.location))
    item.extractor.foreach(id => obj("extractor") = ujson.Str(id))
    obj("x") = ujson.Num(item.x)
    obj("y") = ujson.Num(item.y)
    item.width.foreach(w => obj("width") = ujson.Num(w))
    item.other.foreach((key, json) => obj(key) = ujson.read(json))
    obj

/** The meaning of the location of a board item (ADR 0003).
  *
  * A location in a board is relative to the board file, so that the folder of
  * a board moves as one unit. An absolute path and a URI are also possible.
  * This code is pure: it does not read the file system, and it does not
  * follow links. The caller makes the result canonical.
  */
object BoardLocation:

  enum Resolved derives CanEqual:
    /** A file, as an absolute path with `/` separators. */
    case File(path: String)

    /** A data source that is not a file, as its URI. */
    case Uri(uri: String)

  /** A scheme and a colon, with two or more letters. One letter is a Windows drive. */
  private val Scheme = "^[A-Za-z][A-Za-z0-9+.-]+:".r

  private val Drive = "^[A-Za-z]:/".r

  private def slashed(path: String): String = path.replace('\\', '/')

  private def isAbsolute(path: String): Boolean =
    path.startsWith("/") || Drive.findPrefixOf(path).isDefined

  /** The folder of a file path, with no last separator. The root gives the empty text. */
  private def folderOf(path: String): String =
    val p = slashed(path)
    p.substring(0, p.lastIndexOf('/').max(0))

  /** Remove each `.` segment, and remove each `..` segment with the segment before it. */
  private def normalize(path: String): String =
    val (root, rest) =
      Drive.findPrefixOf(path) match
        case Some(drive)                => (drive, path.substring(drive.length))
        case None if path.startsWith("/") => ("/", path.substring(1))
        case None                       => ("", path)
    val segments = rest.split('/').foldLeft(Vector.empty[String]):
      case (done, "" | ".")                                   => done
      case (done, "..") if done.nonEmpty && done.last != ".." => done.init
      case (done, "..") if root.nonEmpty                      => done
      case (done, segment)                                    => done :+ segment
    root + segments.mkString("/")

  /** The data source of `location` in the board file at `boardPath`. */
  def resolve(boardPath: String, location: String): Resolved =
    if location.startsWith("file://") then
      OriginUri.parse(location).toOption.flatMap(_.filePath) match
        case Some(path) => Resolved.File(normalize(slashed(path)))
        case None       => Resolved.Uri(location)
    else if Scheme.findPrefixOf(location).isDefined then Resolved.Uri(location)
    else
      val path = slashed(location)
      if isAbsolute(path) then Resolved.File(normalize(path))
      else Resolved.File(normalize(s"${folderOf(boardPath)}/$path"))

  /** The location to store for the file at `filePath`, in the board file at
    * `boardPath`: a relative path, when the two files have a common root.
    * [[resolve]] of the result gives the file again.
    */
  def relativize(boardPath: String, filePath: String): String =
    val from = normalize(folderOf(boardPath)).split('/').toVector
    val to   = normalize(slashed(filePath)).split('/').toVector
    // The first segment is the root: the empty text, or a Windows drive.
    if !isAbsolute(slashed(filePath)) || from.headOption != to.headOption then slashed(filePath)
    else
      val common   = from.zip(to).takeWhile(_ == _).size
      val relative = (Vector.fill(from.size - common)("..") ++ to.drop(common)).mkString("/")
      // A name such as `ab:c.dot` looks like a URI. `./` makes it a path again.
      if Scheme.findPrefixOf(relative).isDefined then s"./$relative" else relative
