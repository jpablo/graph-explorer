package org.jpablo.graphexplorer.viewer.backends.graphviz

import org.jpablo.graphexplorer.graphviz.html.ImageDim
import org.jpablo.graphexplorer.viewer.utils.ImageHeader

/** The size table of the images that a DOT text refers to.
  *
  * Graphviz cannot read an image file. It draws an image (a node `image=`, or an `<IMG>` of an HTML label) only when
  * the caller gives the size of the image: the `images` option of viz-js, and the `images` table of the Scala port.
  * A `data:` URI holds the image itself, so its header gives the size. This scan finds each `data:` URI of an image
  * in the text. Thus each renderer (the canvas, a thumbnail, an export) draws an embedded image with no other input.
  *
  * The scan reads the text, and does not parse it. A data URI has no quote and no blank, so the match is the whole
  * attribute value.
  */
object DotImages:

  private val ImageDataUri = """data:image/[A-Za-z0-9.+-]+(?:;[A-Za-z0-9.+=-]+)*;base64,[A-Za-z0-9+/=_-]+""".r

  /** The natural size of each embedded image of `dot`, in points (a pixel is 72/96 of a point, as in Graphviz). */
  def table(dot: String): ImageDim.Table =
    if !dot.contains("data:image/") then ImageDim.empty
    else
      ImageDataUri
        .findAllIn(dot)
        .distinct
        .flatMap(uri => ImageHeader.ofDataUri(uri).map(size => uri -> dim(size)))
        .toMap

  /** The size of a `size` in pixels, in points. */
  def dim(size: ImageHeader.Size): ImageDim =
    ImageDim(size.width * ImageDim.Scale, size.height * ImageDim.Scale)
