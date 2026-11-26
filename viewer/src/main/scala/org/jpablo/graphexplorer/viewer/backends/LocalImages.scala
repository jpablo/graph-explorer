package org.jpablo.graphexplorer.viewer.backends

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.graphviz.html.ImageDim
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotImages
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidImageSources
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Image
import org.jpablo.graphexplorer.viewer.graph.{ImageNode, ViewerGraph}
import org.jpablo.graphexplorer.viewer.utils.{FileLinks, ImageHeader}

/** The images of a diagram that have a path relative to the diagram (`arch.assets/img-3f2a.png`), and the URL that
  * the page loads each one from.
  *
  * The source keeps the path, so that the file stays correct for `gx` and for a different computer. Only the input of
  * a renderer has the URL: the asset URL of the desktop (`convertFileSrc`), which the shell allows for one checked file
  * at a time (`local_image` in `main.rs`). Graphviz also needs the size of each image, from its header.
  *
  * @param images the URL and the size of each relative path, by the path as the source has it
  */
final case class LocalImages(images: Map[String, LocalImages.Loaded]) derives CanEqual:

  def isEmpty: Boolean = images.isEmpty

  def updated(src: String, loaded: LocalImages.Loaded): LocalImages = copy(images = images.updated(src, loaded))

  /** The URL of the relative path `src`, when the page can load it. */
  def urlOf(src: String): Option[String] = images.get(src).map(_.url)

  /** `graph` with the URL in place of each relative path of an image node, for Graphviz. */
  def graph(graph: ViewerGraph): ViewerGraph =
    if isEmpty then graph
    else
      graph
        .modify(_.elements.nodes)
        .using:
          _.transform: (_, node) =>
            ImageNode.of(node.attributes).flatMap(spec => urlOf(spec.src)) match
              case Some(url) => node.modifyAttrs.using(_ + (Image.attrId -> AttrValue(url)))
              case None      => node

  /** The natural size of each image, by its URL, for Graphviz (see [[DotImages]]). */
  def table: ImageDim.Table =
    images.values.flatMap(loaded => loaded.size.map(size => loaded.url -> DotImages.dim(size))).toMap

  /** `text` with the URL in place of each relative path of an image shape, for Mermaid. */
  def mermaidText(text: String): String =
    if isEmpty then text else MermaidImageSources.rewrite(text, urlOf)

object LocalImages:

  /** The URL that the page loads an image from, and the natural size of the image, when its header gives it. */
  final case class Loaded(url: String, size: Option[ImageHeader.Size]) derives CanEqual

  val empty: LocalImages = LocalImages(Map.empty)

  /** The relative paths of the images of `graph`. */
  def relativeSources(graph: ViewerGraph): Set[String] =
    graph.nodes.valuesIterator.flatMap(node => ImageNode.of(node.attributes)).map(_.src).filter(FileLinks.isRelativePath).toSet

  /** An image of 4 × 3 pixels with a light gray fill: `<svg xmlns="http://www.w3.org/2000/svg" width="4" height="3"><rect width="4" height="3" fill="#d4d4d8"/></svg>`. */
  val Placeholder: String =
    "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI0IiBoZWlnaHQ9IjMiPjxyZWN0IHdpZHRoPSI0IiBoZWlnaHQ9IjMiIGZpbGw9IiNkNGQ0ZDgiLz48L3N2Zz4="

  /** `text` with the placeholder in place of each image shape with a relative path.
    *
    * Mermaid stops the whole drawing when one image does not load, and a relative path does not load in the page. The
    * page gives a URL for each image that it can load ([[LocalImages.mermaidText]]). Each other image (a file that is
    * missing, or a diagram with no file) shows as a gray box.
    */
  def withPlaceholders(text: String): String =
    MermaidImageSources.rewrite(text, src => Option.when(FileLinks.isRelativePath(src))(Placeholder))
