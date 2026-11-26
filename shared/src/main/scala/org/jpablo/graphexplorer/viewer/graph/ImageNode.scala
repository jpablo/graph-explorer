package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{FixedSize, Height, Image, ImageScale, Label, PenWidth, Shape, Width}
import org.jpablo.graphexplorer.viewer.models.{AttributeId, Attributes}

import scala.math.BigDecimal.RoundingMode

/** The node form that shows one image. The graph keeps the form in DOT attributes, and each language prints it in its
  * own way.
  *
  * DOT: `shape=box, penwidth=0, label="", image="<src>", fixedsize=true, imagescale=true, width=<w>, height=<h>`. The
  * size is in inches, and one pixel of the image is one point: a 400 px image is 400 pt wide. `fixedsize` makes the
  * node exactly that size, and `imagescale=true` fits the image in the node with its aspect ratio. `penwidth=0` hides
  * the border of the box. Graphviz still needs the natural size of the image (see `DotImages`).
  *
  * Mermaid 11: `id@{ img: "<src>", label: "<label>", w: <w>, h: <h>, constraint: "on" }`, with the size in pixels.
  * The Mermaid reader gives the same attributes back, so a canvas edit keeps the node.
  */
object ImageNode:

  /** One image node.
    *
    * @param src   a `data:` URI, a URL, or a path relative to the diagram
    * @param size  the size of the node in pixels, when the form gives it
    * @param label the text under the image, in the stored (DOT-escaped) form. It is empty for no text.
    */
  final case class Spec(src: String, size: Option[(Int, Int)], label: String) derives CanEqual

  /** The attributes of the node form of `spec`. */
  def attributes(spec: Spec): Attributes =
    val size = spec.size.map((w, h) => (inches(w), inches(h)))
    // The order is the order of the DOT reader (see `simplegraph.toViewerGraphElements`). Thus a parse of the printed
    // text gives the same order, and a later canvas edit does not move the attributes.
    Attributes.fromOrdered(
      List(Label.attrId -> AttrValue(spec.label)) ++
        size.toList.flatMap((w, h) => List(Height.attrId -> AttrValue(h), Width.attrId -> AttrValue(w))) ++
        List(Shape.attrId -> AttrValue("box"), PenWidth.attrId -> AttrValue("0")) ++
        size.map(_ => FixedSize.attrId -> AttrValue("true")) ++
        List(Image.attrId -> AttrValue(spec.src)) ++
        size.map(_ => ImageScale.attrId -> AttrValue("true"))
    )

  /** The image node of `attrs`, or `None` when the node shows no image. Each node with an `image` attribute is an
    * image node. The size is there only when the node has the fixed size of the form.
    */
  def of(attrs: Attributes): Option[Spec] =
    def value(id: AttributeId): Option[String] = attrs.get(id).map(_.toString.trim).filter(_.nonEmpty)
    value(Image.attrId).map: src =>
      val size =
        for
          _ <- value(FixedSize.attrId).filter(_ == "true")
          w <- value(Width.attrId).flatMap(pixels)
          h <- value(Height.attrId).flatMap(pixels)
        yield (w, h)
      Spec(src, size, attrs.get(Label.attrId).map(_.toString).getOrElse(""))

  /** The attributes of the node form that a printer of a different language must not write again. A Mermaid printer
    * writes the image form, and it must not also write `penwidth=0` as a `stroke-width` style.
    */
  def withoutForm(attrs: Attributes): Attributes =
    if of(attrs).isEmpty then attrs
    else
      Attributes.fromOrdered(
        attrs.values.filterNot((id, v) => FormValues.get(id).exists(isForm => isForm(v.toString.trim)))
      )

  /** Each attribute that the image form writes, and its values in the form. The image form prints the size. */
  private val FormValues: Map[AttributeId, String => Boolean] = Map(
    Shape.attrId      -> (_ == "box"),
    PenWidth.attrId   -> (_ == "0"),
    FixedSize.attrId  -> (_ == "true"),
    ImageScale.attrId -> (_ == "true"),
    Width.attrId      -> (_ => true),
    Height.attrId     -> (_ => true)
  )

  /** Inches for `px` pixels, with one pixel as one point: four decimals, with no zeros at the end. */
  def inches(px: Int): String =
    (BigDecimal(px) / 72).setScale(4, RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString

  /** Pixels for a size in inches, with one pixel as one point. */
  def pixels(inches: String): Option[Int] =
    inches.trim.toDoubleOption.filter(v => v > 0 && !v.isInfinite).map(v => math.max(1, math.round(v * 72).toInt))
