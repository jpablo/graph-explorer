package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.backends.LocalImages
import org.jpablo.graphexplorer.viewer.desktop.{DesktopFiles, DesktopIpc, ExternalLink}
import org.jpablo.graphexplorer.viewer.graph.InsertItem
import org.jpablo.graphexplorer.viewer.utils.FileLinks

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/** The files next to the file of a diagram on the desktop: its images, and the files that its links open.
  *
  * A diagram with a file keeps each image of a paste or a drop as a file in `<name>.assets/` next to it, and its
  * source has the path of the image relative to the diagram. The page draws such an image from the asset URL that the
  * shell gives for it ([[localImages]]). A diagram with no file (the library of the browser) keeps the `data:` URI.
  */
trait LocalFileOps:
  this: ViewerState =>

  /** The URL and the size of each image of the diagram that has a path relative to its file. The renderers draw
    * these images from their URLs (see `DiagramRenderInputs.localImages`).
    */
  lazy val localImages: Var[LocalImages] = Var(LocalImages.empty)

  /** The images that the page asked the shell for, by the path of the diagram and the path of the image. The page
    * asks one time for each image. An image that the shell does not find stays a gray box (Mermaid) or draws nothing
    * (Graphviz) until the diagram opens again.
    */
  private var requestedImages: Set[(String, String)] = Set.empty

  /** The path of the diagram that [[localImages]] holds the images of. */
  private var imagesOf: Option[String] = None

  /** Ask the shell for each new image with a relative path, while the diagram has a file. Call it one time, after the
    * graph exists.
    */
  private[state] def followLocalImages(): Unit =
    val wanted = documentSession
      .map(_.map(_.path))
      .distinct
      .combineWith(fullGraph.map(LocalImages.relativeSources).distinct)
    wanted.foreach((path, sources) => requestImages(path, sources))(using owner)

  private def requestImages(path: Option[String], sources: Set[String]): Unit =
    if imagesOf != path then
      // A rename moves the file, and a relative path then names a different file.
      imagesOf = path
      requestedImages = Set.empty
      localImages.set(LocalImages.empty)
    for diagram <- path if DesktopIpc.available; src <- sources if !requestedImages.contains((diagram, src)) do
      requestedImages += ((diagram, src))
      DesktopFiles
        .localImage(diagram, src)
        .onComplete:
          case Success(loaded) => if imagesOf.contains(diagram) then localImages.update(_.updated(src, loaded))
          case Failure(error)  => org.scalajs.dom.console.warn(s"The image $src does not show: ${error.getMessage}")

  /** The path of the file of the diagram, while the desktop can write next to it. */
  private def assetFolderOwner: Option[String] =
    documentSessionNow.map(_.path).filter(_ => DesktopIpc.available)

  /** `items` with each `data:` image saved next to the file of the diagram, and the relative path of the file as its
    * source. With no file, the items stay as they are.
    */
  private[state] def storeImages(items: Seq[InsertItem]): Future[Seq[InsertItem]] =
    assetFolderOwner match
      case None => Future.successful(items)
      case Some(diagram) =>
        Future.sequence(items.map:
          case image: InsertItem.Image if image.src.startsWith("data:") => DesktopFiles.writeAsset(diagram, image)
          case other                                                    => Future.successful(other))

  /** The items of a drop of the files at `paths` from the desktop: an image item for an image file, and a file item
    * with a link to the file for each other file. The link is relative to the diagram when the file is in its folder
    * (see `FileLinks.linkFor`).
    */
  private[state] def readDroppedPaths(paths: Seq[String]): Future[Seq[InsertItem]] =
    val diagram = documentSessionNow.map(_.path)
    Future.sequence(paths.map: path =>
      if DesktopFiles.imageTypeOf(path).isDefined then DesktopFiles.readDroppedImage(path)
      else Future.successful(InsertItem.File(DesktopFiles.baseName(path), Some(FileLinks.linkFor(diagram, path)))))

  /** Open the link `url` of an element: a web link in the browser, or on the desktop, a local file. */
  def openLink(url: String): Unit =
    ExternalLink.open(url, documentSessionNow.map(_.path), infoBus.emit, errorBus.emit)
