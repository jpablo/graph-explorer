package org.jpablo.graphexplorer.viewer.utils

import org.jpablo.graphexplorer.viewer.graph.{InsertItem, NodeInsertion}
import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

/** Reads the content of a `DataTransfer`: the clipboard of a `paste` event, and the data of a `drop` event.
  *
  * The browser gives the data only during the event. A `File` from the event stays readable after it, so the read of
  * an image can complete later.
  */
object TransferItems:

  /** The first image file of `transfer`: a screenshot on the clipboard, or a dropped image file. */
  def imageFile(transfer: dom.DataTransfer): Option[dom.File] =
    files(transfer).find(isImage)

  /** The files of `transfer`. A clipboard image is in `items` (kind "file"). A dropped file is also in `files`. */
  def files(transfer: dom.DataTransfer): Seq[dom.File] =
    val dyn = transfer.asInstanceOf[js.Dynamic]
    val fromItems =
      if js.isUndefined(dyn.items) || dyn.items == null then Seq.empty
      else
        val items = dyn.items
        (0 until items.length.asInstanceOf[Int]).flatMap: i =>
          val item = items.selectDynamic(i.toString)
          if item.kind.asInstanceOf[String] == "file" then Option(item.getAsFile().asInstanceOf[dom.File])
          else None
    val fromFiles =
      if js.isUndefined(dyn.files) || dyn.files == null then Seq.empty
      else
        val list = transfer.files
        (0 until list.length).map(list(_))
    // A clipboard can give one file in both lists.
    if fromItems.nonEmpty then fromItems else fromFiles

  /** The plain text of `transfer`, or "" when it has none. */
  def text(transfer: dom.DataTransfer): String =
    Option(transfer.getData("text/plain")).getOrElse("")

  /** The links of `transfer` (`text/uri-list`): a link from a browser. A line that starts with `#` is a comment. */
  def links(transfer: dom.DataTransfer): Seq[String] =
    Option(transfer.getData("text/uri-list")).getOrElse("").linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toSeq

  /** The types of the data of `transfer`. During `dragenter` and `dragover`, the browser gives only the types. */
  def types(transfer: dom.DataTransfer): Seq[String] =
    val dyn = transfer.asInstanceOf[js.Dynamic]
    if js.isUndefined(dyn.types) || dyn.types == null then Seq.empty
    else
      val list = dyn.types
      (0 until list.length.asInstanceOf[Int]).map(i => list.selectDynamic(i.toString).asInstanceOf[String])

  /** True when the canvas can take a drop of `transfer`: files, a link or text. */
  def droppable(transfer: dom.DataTransfer): Boolean =
    types(transfer).exists(t => t == "Files" || t == "text/uri-list" || t == "text/plain")

  def isImage(file: dom.File): Boolean = Option(file.`type`).exists(_.startsWith("image/"))

  /** The item of a dropped `file`: an image item for an image, else a file item with the name and no link. A file of
    * the browser has no path on the disk, so the node cannot open it.
    */
  def readItem(file: dom.File): Future[InsertItem] =
    if isImage(file) then readImage(file) else Future.successful(InsertItem.File(file.name, None))

  /** The items of the dropped `files`, in their order. */
  def readItems(files: Seq[dom.File]): Future[Seq[InsertItem]] =
    given ExecutionContext = ExecutionContext.global
    Future.sequence(files.map(readItem))

  /** The image item of `file`: its data as a `data:` URI, and its natural size from the browser.
    *
    * An image wider than `NodeInsertion.MaxStoredImageWidth` is redrawn at that width first (see
    * `NodeInsertion.storedSize`), so that the source of the diagram stays small. An SVG image stays as it is.
    */
  def readImage(file: dom.File): Future[InsertItem.Image] =
    readImage(file, Option(file.`type`).getOrElse(""))

  /** The image item of the image data `blob` of the media type `mime` (see [[readImage]]). */
  def readImage(blob: dom.Blob, mime: String): Future[InsertItem.Image] =
    given ExecutionContext = ExecutionContext.global
    // The `data:` URI has the type of the blob. A blob of a fetch can have a different type, or none.
    val typed =
      if blob.`type` == mime then blob
      else js.Dynamic.newInstance(js.Dynamic.global.Blob)(js.Array(blob), js.Dynamic.literal(`type` = mime)).asInstanceOf[dom.Blob]
    for
      uri           <- dataUri(typed)
      (w, h)        <- naturalSize(uri)
      (src, sw, sh) <- NodeInsertion.storedSize(w, h).filter(_ => NodeInsertion.redrawable(mime)) match
                         case Some((sw, sh)) => redraw(uri, sw, sh, NodeInsertion.storedType(mime)).map((_, sw, sh))
                         case None           => Future.successful((uri, w, h))
    // `new` gives the type of the case, not the type of the enum.
    yield new InsertItem.Image(src, sw, sh)

  /** The image at `uri`, drawn at `width` × `height` pixels, as a `data:` URI of the media type `mime`.
    *
    * One draw from a much larger image skips pixels, and the result has jagged edges. Thus the draw halves the image in
    * steps, and the last step gives the exact size.
    */
  def redraw(uri: String, width: Int, height: Int, mime: String): Future[String] =
    given ExecutionContext = ExecutionContext.global
    loadImage(uri).map: img =>
      def canvasOf(w: Int, h: Int): (dom.html.Canvas, dom.CanvasRenderingContext2D) =
        val canvas = dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas]
        canvas.width = w
        canvas.height = h
        val ctx = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
        ctx.asInstanceOf[js.Dynamic].imageSmoothingEnabled = true
        ctx.asInstanceOf[js.Dynamic].imageSmoothingQuality = "high"
        (canvas, ctx)
      var source: dom.html.Element = img
      var w                        = img.naturalWidth
      var h                        = img.naturalHeight
      while w / 2 > width do
        val (canvas, ctx) = canvasOf(w / 2, math.max(1, h / 2))
        ctx.drawImage(source, 0, 0, canvas.width.toDouble, canvas.height.toDouble)
        source = canvas
        w = canvas.width
        h = canvas.height
      val (canvas, ctx) = canvasOf(width, height)
      // A JPEG has no transparency: a transparent pixel becomes black. A white background is the usual answer.
      if mime == "image/jpeg" then
        ctx.fillStyle = "#ffffff"
        ctx.fillRect(0, 0, width.toDouble, height.toDouble)
      ctx.drawImage(source, 0, 0, width.toDouble, height.toDouble)
      canvas.toDataURL(mime, NodeInsertion.JpegQuality)

  /** The data of `file` as a `data:` URI. */
  def dataUri(file: dom.Blob): Future[String] =
    val p      = Promise[String]()
    val reader = new dom.FileReader()
    reader.onload = _ => p.trySuccess(reader.result.asInstanceOf[String])
    reader.onerror = _ => p.tryFailure(new RuntimeException(s"Could not read the file: ${reader.error}"))
    reader.readAsDataURL(file)
    p.future

  /** The natural size of the image at `uri`, in pixels. An image with no size of its own (an SVG with no `width`)
    * gets the size from its header, or else the default size of an image in a browser (300 × 150).
    */
  def naturalSize(uri: String): Future[(Int, Int)] =
    given ExecutionContext = ExecutionContext.global
    def fallback = ImageHeader.ofDataUri(uri).map(s => (s.width, s.height)).getOrElse((300, 150))
    loadImage(uri).map: img =>
      if img.naturalWidth > 0 && img.naturalHeight > 0 then (img.naturalWidth, img.naturalHeight) else fallback

  /** The image at `uri`, after the browser loaded it. */
  private def loadImage(uri: String): Future[dom.html.Image] =
    val p   = Promise[dom.html.Image]()
    val img = dom.document.createElement("img").asInstanceOf[dom.html.Image]
    img.addEventListener("load", (_: dom.Event) => p.trySuccess(img))
    img.addEventListener("error", (_: dom.Event) => p.tryFailure(new RuntimeException("The browser could not read the image.")))
    img.src = uri
    p.future
