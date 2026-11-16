package org.jpablo.graphexplorer.board

/** A rectangle on the board, in the units of the board canvas. */
final case class BoardBounds(x: Double, y: Double, width: Double, height: Double) derives CanEqual:

  def right: Double  = x + width
  def bottom: Double = y + height

  /** The smallest rectangle that contains the two. */
  def union(other: BoardBounds): BoardBounds =
    val left = x.min(other.x)
    val top  = y.min(other.y)
    BoardBounds(left, top, right.max(other.right) - left, bottom.max(other.bottom) - top)

/** The part of the board that the window shows: a translation in pixels, and
  * a scale. A board point `p` is at the pixel `p * scale + (tx, ty)` of the
  * canvas element.
  *
  * This code is pure, so that the tests need no page.
  */
final case class BoardViewport(tx: Double, ty: Double, scale: Double) derives CanEqual:

  /** The board point at a pixel of the canvas element. */
  def toBoard(px: Double, py: Double): (Double, Double) =
    ((px - tx) / scale, (py - ty) / scale)

  def panBy(dx: Double, dy: Double): BoardViewport = copy(tx = tx + dx, ty = ty + dy)

  /** Change the scale by `factor`. The board point at the pixel stays at the pixel. */
  def zoomAt(px: Double, py: Double, factor: Double): BoardViewport =
    val next  = (scale * factor).max(BoardViewport.MinScale).min(BoardViewport.MaxScale)
    val ratio = next / scale
    BoardViewport(px - (px - tx) * ratio, py - (py - ty) * ratio, next)

  /** The CSS transform of the layer that holds the items. */
  def css: String = s"translate(${tx}px, ${ty}px) scale($scale)"

object BoardViewport:

  val MinScale = 0.05
  val MaxScale = 4.0

  /** The start: no zoom, and a margin at the top left corner. */
  val start: BoardViewport = BoardViewport(Margin, Margin, 1)

  private def Margin = 40.0

  /** Show all of `bounds` in a canvas of the given size, in its center. The
    * scale is 1 at most: a small board does not grow.
    */
  def fit(bounds: BoardBounds, width: Double, height: Double): BoardViewport =
    val usableWidth  = (width - 2 * Margin).max(1)
    val usableHeight = (height - 2 * Margin).max(1)
    val scale = (usableWidth / bounds.width.max(1))
      .min(usableHeight / bounds.height.max(1))
      .min(1)
      .max(MinScale)
    BoardViewport(
      tx = (width - bounds.width * scale) / 2 - bounds.x * scale,
      ty = (height - bounds.height * scale) / 2 - bounds.y * scale,
      scale = scale
    )
