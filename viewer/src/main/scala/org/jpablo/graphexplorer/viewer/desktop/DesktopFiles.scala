package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.viewer.backends.LocalImages
import org.jpablo.graphexplorer.viewer.graph.InsertItem
import org.jpablo.graphexplorer.viewer.utils.{ImageHeader, TransferItems}
import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

/** The files next to a diagram on the desktop: the shell commands `write_asset`, `local_image`, `open_file_link`,
  * `read_layout` and `write_layout`, and the asset URL of a file (`convertFileSrc`).
  *
  * A diagram with a file keeps an image of a paste or a drop as a file next to the diagram, and not as a `data:` URI
  * in its source (see `local_files.rs`). The page cannot read or write a file by itself, so it asks the shell.
  */
object DesktopFiles:

  val WriteAsset   = "write_asset"
  val LocalImage   = "local_image"
  val OpenFileLink = "open_file_link"
  val ReadLayout   = "read_layout"
  val WriteLayout  = "write_layout"

  /** The layout side file of a diagram (`<base name>.layout.json`, ADR 0004) as the shell read it: its text, and
    * the content hash of its bytes.
    */
  final case class LayoutOnDisk(text: String, revision: String) derives CanEqual

  /** The side file of the layout of the diagram at `diagramPath`, or `None` when the diagram has no side file. */
  def readLayout(diagramPath: String)(using ExecutionContext): Future[Option[LayoutOnDisk]] =
    DesktopIpc
      .invoke(ReadLayout, js.Dynamic.literal(diagramPath = diagramPath))
      .transform(identity, failureOf)
      .map: value =>
        if value == null || js.isUndefined(value) then None
        else
          val text     = DesktopIpc.asString(value, "text").getOrElse(throw IllegalStateException("the side file has no text"))
          val revision = DesktopIpc.asString(value, "revision").getOrElse(throw IllegalStateException("the side file has no revision"))
          Some(LayoutOnDisk(text, revision))

  /** Write the side file of the layout of the diagram at `diagramPath`, with a compare-and-swap on `baseRevision`.
    * `None` as the base: the shell expects no side file. `None` as the text: the shell removes the side file. The
    * result is the revision of the new side file, or `None` when no side file stays. A side file that changed on
    * disk gives the failure `LAYOUT_CONFLICT`.
    */
  def writeLayout(diagramPath: String, text: Option[String], baseRevision: Option[String])(using
      ExecutionContext
  ): Future[Option[String]] =
    val args = js.Dynamic.literal(diagramPath = diagramPath, text = text.orNull, baseRevision = baseRevision.orNull)
    DesktopIpc
      .invoke(WriteLayout, args)
      .transform(identity, failureOf)
      .map(value => if value == null || js.isUndefined(value) then None else DesktopIpc.asString(value, "revision"))

  /** The extensions of an image file, and the media type of each one. */
  private val ImageTypes: Map[String, String] = Map(
    "png"  -> "image/png",
    "jpg"  -> "image/jpeg",
    "jpeg" -> "image/jpeg",
    "gif"  -> "image/gif",
    "webp" -> "image/webp",
    "bmp"  -> "image/bmp",
    "svg"  -> "image/svg+xml"
  )

  /** The extension of the file of an image of the media type `mime`. `IMAGE_EXTENSIONS` in `local_files.rs` has each
    * one.
    */
  private def extensionOf(mime: String): Option[String] =
    mime.trim.toLowerCase match
      case "image/jpeg" | "image/jpg" | "image/pjpeg" => Some("jpg")
      case "image/svg+xml"                             => Some("svg")
      case other                                       => ImageTypes.collectFirst { case (ext, `other`) => ext }

  /** The media type of the image file at `path`, from its extension. `None` for a file that is not an image. */
  def imageTypeOf(path: String): Option[String] =
    val name = baseName(path)
    val dot  = name.lastIndexOf('.')
    Option.when(dot > 0)(name.substring(dot + 1).toLowerCase).flatMap(ImageTypes.get)

  /** The name of the file at `path`, with its extension. */
  def baseName(path: String): String =
    path.split("[/\\\\]").filter(_.nonEmpty).lastOption.getOrElse(path)

  private def child(parent: js.Dynamic, name: String): Option[js.Dynamic] =
    if js.isUndefined(parent) || parent == null then None
    else
      val value = parent.selectDynamic(name)
      if js.isUndefined(value) || value == null then None else Some(value)

  /** The asset URL of the file at `path`, as the shell serves it (`window.__TAURI__.core.convertFileSrc`). The URL
    * loads only a file that the shell allows.
    */
  def convertFileSrc(path: String): Option[String] =
    for
      tauri   <- child(js.Dynamic.global.window, "__TAURI__")
      core    <- child(tauri, "core")
      convert <- child(core, "convertFileSrc") if js.typeOf(convert) == "function"
    yield convert.asInstanceOf[js.Function1[String, String]](path)

  private val DataUri = """(?s)data:([^,;]*)((?:;[^,;]*)*),(.*)""".r

  /** Save the image of `image` (a `data:` URI) next to the diagram at `diagramPath`, and give the image with the path
    * of the file, relative to the diagram. The bytes are the bytes of the `data:` URI: an image wider than 800 pixels
    * is already smaller (see `TransferItems.readImage`).
    */
  def writeAsset(diagramPath: String, image: InsertItem.Image)(using ExecutionContext): Future[InsertItem.Image] =
    image.src match
      case DataUri(mime, params, data) if params.split(';').exists(_.trim.equalsIgnoreCase("base64")) =>
        extensionOf(mime) match
          case None => Future.failed(IllegalArgumentException(s"the image type $mime has no file extension"))
          case Some(ext) =>
            DesktopIpc
              .invoke(WriteAsset, js.Dynamic.literal(diagramPath = diagramPath, ext = ext, bytesBase64 = data))
              .transform(identity, failureOf)
              .map: value =>
                if js.typeOf(value) == "string" then image.copy(src = value.asInstanceOf[String])
                else throw IllegalStateException("the shell gave no path for the image")
      case _ => Future.failed(IllegalArgumentException("the image is not a base64 data URI"))

  /** The URL and the natural size of the image `src` (a path relative to the diagram at `diagramPath`). The shell
    * allows the file for the asset protocol, and gives the start of its bytes, so that the page reads the size from
    * the header of the image. The diagram does not have to be open: the thumbnail of the library also uses this.
    */
  def localImage(diagramPath: String, src: String)(using ExecutionContext): Future[LocalImages.Loaded] =
    DesktopIpc
      .invoke(LocalImage, js.Dynamic.literal(diagramPath = diagramPath, src = src))
      .transform(identity, failureOf)
      .map: value =>
        val path = DesktopIpc.asString(value, "path").getOrElse(throw IllegalStateException(s"no path for $src"))
        val url  = convertFileSrc(path).getOrElse(throw IllegalStateException("the shell has no asset URL"))
        val head = DesktopIpc.asString(value, "headBase64").map(bytesOf).getOrElse(Array.emptyByteArray)
        LocalImages.Loaded(url, ImageHeader.ofHead(head))

  /** The image item of the image file at `path`, from a drop: the bytes come from the asset URL of the file. Tauri
    * allows each dropped file for the asset protocol. The image becomes a `data:` URI first, with the downscale of
    * `TransferItems.readImage`, and the insert then saves it next to the diagram.
    */
  def readDroppedImage(path: String)(using ExecutionContext): Future[InsertItem.Image] =
    val mime = imageTypeOf(path).getOrElse("image/png")
    convertFileSrc(path) match
      case None => Future.failed(IllegalStateException("the shell has no asset URL"))
      case Some(url) =>
        dom
          .fetch(url)
          .toFuture
          .flatMap: response =>
            if !response.ok then throw IllegalStateException(s"could not read ${baseName(path)} (${response.status})")
            response.blob().toFuture
          .flatMap(blob => TransferItems.readImage(blob, mime))

  /** Ask the shell to open the local file of `link`. The answer is `"opened"`, or `"revealed"` for a file that can
    * run a program: the shell then shows it in the file manager (`open_action` in `local_files.rs`).
    */
  def openFileLink(diagramPath: Option[String], link: String)(using ExecutionContext): Future[String] =
    val args = js.Dynamic.literal(link = link)
    diagramPath.foreach(p => args.updateDynamic("diagramPath")(p))
    DesktopIpc
      .invoke(OpenFileLink, args)
      .transform(identity, failureOf)
      .map(value => if js.typeOf(value) == "string" then value.asInstanceOf[String] else "opened")

  /** The message of a rejected command: the `message` of the `IpcError` of the shell. */
  private def failureOf(error: Throwable): Throwable =
    error match
      case js.JavaScriptException(value) =>
        val message = DesktopIpc.asString(value.asInstanceOf[js.Any], "message").getOrElse(String.valueOf(value))
        val code    = DesktopIpc.asString(value.asInstanceOf[js.Any], "code").getOrElse("IPC_FAILED")
        DesktopIpc.IpcFailure(code, message)
      case other => other

  private def bytesOf(base64: String): Array[Byte] =
    val binary = dom.window.atob(base64)
    Array.tabulate(binary.length)(i => binary.charAt(i).toByte)
