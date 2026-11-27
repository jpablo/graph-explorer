package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, URL}
import org.jpablo.graphexplorer.viewer.formats.dot.{HtmlLabels, TextUtils}
import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.models.{Attributes, GroupId, NodeId, ViewerNode}

/** A thing that a paste or a drop puts on the canvas as a new node. */
enum InsertItem derives CanEqual:

  /** Text. The label of the new node is the text. */
  case Text(text: String)

  /** An image. `src` is a `data:` URI or a relative path. The size is the natural size of the image, in pixels. */
  case Image(src: String, naturalWidth: Int, naturalHeight: Int)

  /** A file. The label of the new node is the name of the file. A command-click opens `link`. */
  case File(name: String, link: Option[String])

/** The one place that turns an [[InsertItem]] into a new node of a graph.
  *
  * The node goes into the graph, and the printer of the language of the diagram writes it: the graph keeps the node in
  * one form for DOT and Mermaid (see [[ImageNode]] for an image). The node gets no arrow. It goes into a group only
  * when the caller gives one (the selected group). The layout gives it its place.
  */
object NodeInsertion:

  /** The largest width of a new image node, in pixels. A wider image keeps its aspect ratio. */
  val MaxImageWidth = 400

  /** The size of a new image node, in pixels: the natural size of the image, at most [[MaxImageWidth]] wide. */
  def displaySize(naturalWidth: Int, naturalHeight: Int): (Int, Int) =
    val w = math.max(1, naturalWidth)
    val h = math.max(1, naturalHeight)
    if w <= MaxImageWidth then (w, h)
    else (MaxImageWidth, math.max(1, math.round(h.toDouble * MaxImageWidth / w).toInt))

  /** The attributes of the new node for `item`. A label is in the stored (DOT-escaped) form. */
  def attributes(item: InsertItem): Attributes =
    item match
      case InsertItem.Text(text) =>
        Attributes.fromOrdered(List(Label.attrId -> AttrValue(storedLabel(normalized(text)))))
      case InsertItem.Image(src, w, h) =>
        ImageNode.attributes(ImageNode.Spec(src, Some(displaySize(w, h)), label = ""))
      case InsertItem.File(name, link) =>
        Attributes.fromOrdered(
          (Label.attrId -> AttrValue(storedLabel(normalized(name)))) :: link.toList.map(url => URL.attrId -> AttrValue(url))
        )

  /** A readable id for the new node that no node and no group of `graph` has. Mermaid has one set of ids for nodes
    * and subgraphs, so the id must not be the id of a group.
    *
    * A text gives its first words (`hello_world`), and a file gives its name with no extension (`report_2026`). When
    * that id is taken, a number follows (`hello_world_2`). An image, and a text or a file that gives no id, get a
    * numbered id: `image1`, `text1`, `file1`.
    */
  def freshId(graph: ViewerGraph, item: InsertItem): NodeId =
    val taken: String => Boolean = id => graph.nodes.contains(NodeId(id)) || graph.groupIds.exists(_.value == id)
    val (numbered, base) = item match
      case InsertItem.Text(text)    => ("text", slug(normalized(text)))
      case InsertItem.Image(_, _, _) => ("image", None)
      case InsertItem.File(name, _) => ("file", slug(withoutExtension(normalized(name))))
    val id = base match
      case Some(b) => Iterator.from(1).map(n => if n == 1 then b else s"${b}_$n").find(!taken(_)).get
      case None    => Iterator.from(1).map(n => s"$numbered$n").find(!taken(_)).get
    NodeId(id)

  /** `graph` with a new node for `item`, and the id of the node. With a `group`, the node goes into that group, as
    * the New node command puts its node into the selected group.
    */
  def insert(graph: ViewerGraph, item: InsertItem, group: Option[GroupId] = None): (ViewerGraph, NodeId) =
    val id = freshId(graph, item)
    // `addNodeWithId` adds the default attributes through an unordered map. The attributes of the item already have a
    // label, so the node takes them as they are, in their order.
    val withNode = graph.modify(_.elements.nodes).using(_ + (id -> ViewerNode.nodeNoDefaults(id, attributes(item))))
    val inGroup  = group.filter(graph.groupIds.contains)
    (inGroup.fold(withNode)(g => withNode.modify(_.elements.memberships).using(_ + (id -> g))), id)

  /** An estimate of the size of the new node for `item`, in points (width, height). The true size is known only after
    * the render. A free layout uses the estimate to put several new nodes in a column with no overlap.
    *
    * An image node has its display size (one pixel is one point). A text node and a file node have the default node
    * of Graphviz (54 by 36 points) at least. A longer label makes the node wider: about 7 points for each character of
    * the longest line, at the default font size of 14 points. Each line adds about 17 points to the height.
    */
  def estimatedSize(item: InsertItem): (Double, Double) =
    def ofText(text: String): (Double, Double) =
      val lines = normalized(text).split('\n').toVector
      val width = lines.map(_.length).maxOption.getOrElse(0) * 7.0 + 16
      (math.max(54.0, width), math.max(36.0, lines.size * 17.0 + 8))
    item match
      case InsertItem.Text(text) => ofText(text)
      case InsertItem.Image(_, w, h) =>
        val (dw, dh) = displaySize(w, h)
        (dw.toDouble, dh.toDouble)
      case InsertItem.File(name, _) => ofText(name)

  /** `graph` with a new node for each item, in the order of the items, and the ids of the nodes. One drop of several
    * files is one change of the graph, so Undo removes all the nodes in one step.
    */
  def insertAll(graph: ViewerGraph, items: Seq[InsertItem], group: Option[GroupId]): (ViewerGraph, List[NodeId]) =
    val (result, ids) = items.foldLeft((graph, List.empty[NodeId])):
      case ((g, acc), item) =>
        val (next, id) = insert(g, item, group)
        (next, id :: acc)
    (result, ids.reverse)

  // ------------------------------------------------------------------ the stored image

  /** The largest width of a stored image, in pixels: two times [[MaxImageWidth]], so that a screen with two device
    * pixels for each CSS pixel (Retina) shows the image sharply.
    *
    * The library of the browser keeps the source of a diagram in `localStorage`, which holds about 5 MB. A screenshot
    * at its natural size can be several MB as a `data:` URI. Thus a wider image is made smaller before it goes into
    * the source.
    */
  val MaxStoredImageWidth = 2 * MaxImageWidth

  /** The size to store an image of `naturalWidth` × `naturalHeight` pixels at, or `None` to store it as it is. A wider
    * image than [[MaxStoredImageWidth]] gets that width, with the same aspect ratio. A smaller image does not grow.
    */
  def storedSize(naturalWidth: Int, naturalHeight: Int): Option[(Int, Int)] =
    if naturalWidth <= MaxStoredImageWidth then None
    else
      val h = math.max(1, math.round(math.max(1, naturalHeight).toDouble * MaxStoredImageWidth / naturalWidth).toInt)
      Some((MaxStoredImageWidth, h))

  /** The media type of a redrawn image of the media type `mime`.
    *
    * A JPEG is a photo: it stays a JPEG, which is much smaller than a PNG of a photo and has no transparency to lose.
    * The other types become a PNG. A screenshot has text and sharp edges, which a JPEG makes blurred, and a PNG keeps
    * the transparency of a PNG, a GIF or a WebP image. WebP is not an output, because Safari (WKWebView, the desktop
    * app) cannot encode it and gives a PNG.
    */
  def storedType(mime: String): String =
    mime.trim.toLowerCase match
      case "image/jpeg" | "image/jpg" | "image/pjpeg" => "image/jpeg"
      case _                                           => "image/png"

  /** The quality of a redrawn JPEG, from 0 to 1. */
  val JpegQuality = 0.85

  /** True when an image of the media type `mime` can be redrawn at a smaller size. An SVG image is a vector image: a
    * smaller size makes it no smaller, and a redraw makes it a bitmap. Thus it stays as it is.
    */
  def redrawable(mime: String): Boolean =
    !mime.trim.toLowerCase.startsWith("image/svg")

  // ------------------------------------------------------------------ text

  /** The text with Unix line breaks and with no blanks at its ends. */
  private def normalized(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').strip

  /** The stored form of a plain-text label.
    *
    * The DOT printer writes a label that looks like HTML as an HTML label (see `HtmlLabels.isHtml`). A pasted text is
    * not markup, so its `<` and `>` become character entities, which Graphviz and Mermaid show as the characters.
    */
  private def storedLabel(text: String): String =
    val stored = TextUtils.escape(text)
    if HtmlLabels.isHtml(stored) then stored.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    else stored

  private def withoutExtension(name: String): String =
    val dot = name.lastIndexOf('.')
    if dot > 0 then name.substring(0, dot) else name

  /** The most words of an id. */
  private val MaxWords = 3

  /** The most characters of an id. */
  private val MaxLength = 24

  /** The ids that a keyword of DOT or of Mermaid makes hard to read or to parse. */
  private val Reserved = Set(
    // DOT
    "node", "edge", "graph", "digraph", "subgraph", "strict",
    // Mermaid flowchart
    "end", "flowchart", "style", "class", "classdef", "linkstyle", "interpolate", "click", "call", "href", "default",
    "direction", "_self", "_blank", "_parent", "_top"
  )

  /** An id from the first words of `text`: lower-case ASCII letters and digits, with `_` between the words. It must
    * start with a letter and have two characters or more, so that it is a bare id in DOT and in Mermaid.
    */
  private def slug(text: String): Option[String] =
    val words = text.toLowerCase.split("[^a-z0-9]+").iterator.filter(_.nonEmpty).take(MaxWords).toList
    val id = words.foldLeft(""): (acc, word) =>
      val next = if acc.isEmpty then word else s"${acc}_$word"
      if acc.nonEmpty && next.length > MaxLength then acc else next
    Option(id.take(MaxLength).stripSuffix("_"))
      .filter(s => s.length >= 2 && s.head.isLetter && !Reserved.contains(s))
