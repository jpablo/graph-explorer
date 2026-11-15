package org.jpablo.graphexplorer.board

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.{Board, BoardItem}

import scala.concurrent.ExecutionContext

/** The state of the load of a board. */
enum BoardStatus derives CanEqual:
  case Loading
  case Ready

  /** The store could not read the board. The view then shows no item, and
    * the state writes nothing, so that the stored text stays.
    */
  case Failed(message: String)

/** The state of one board on the screen (ADR 0003).
  *
  * `board` holds the items and their places. A change of a place is live in
  * this `Var`, and [[commit]] writes the board to the store. Thus a drag
  * gives one write at its end, and not one write for each pointer event.
  *
  * The state of each diagram (its source, its graph, its drawing) is not
  * here. The view makes one `ViewerState` for each item.
  */
final class BoardState(val store: BoardStore, errors: Observer[String])(using ExecutionContext):

  val board: Var[Board]             = Var(Board.empty)
  val status: Var[BoardStatus]      = Var(BoardStatus.Loading)
  val viewport: Var[BoardViewport]  = Var(BoardViewport.start)
  val selected: Var[Option[String]] = Var(None)

  /** The size of the canvas element, in pixels. The view gives it. */
  var canvasSize: () => (Double, Double) = () => (0, 0)

  // The size of each item on the board, as the view measured it.
  private var sizes: Map[String, (Double, Double)] = Map.empty

  // True after the first fit, and after the person moved the view. A new item
  // makes it false again, so that the view shows the new item.
  private var viewSettled = false

  store.load().foreach:
    case Right(loaded) =>
      board.set(loaded)
      status.set(BoardStatus.Ready)
      fitWhenMeasured()
    case Left(message) =>
      status.set(BoardStatus.Failed(message))

  // -------------------------------------------------------------- the sizes

  /** The view measured the item `id`. */
  def itemSize(id: String, width: Double, height: Double): Unit =
    sizes = sizes.updated(id, (width, height))
    fitWhenMeasured()

  private def sizeOf(item: BoardItem): (Double, Double) =
    sizes.getOrElse(item.id, BoardState.DefaultSize)

  private def boundsOf(item: BoardItem): BoardBounds =
    val (width, height) = sizeOf(item)
    BoardBounds(item.x, item.y, width, height)

  /** The rectangle that contains each item. `None` for a board with no items. */
  def bounds: Option[BoardBounds] =
    board.now().items.map(boundsOf).reduceOption(_.union(_))

  // --------------------------------------------------------------- the view

  /** Show all of the board. */
  def fit(): Unit =
    val (width, height) = canvasSize()
    bounds.filter(_ => width > 0 && height > 0).foreach: all =>
      viewport.set(BoardViewport.fit(all, width, height))

  // The first view of a board shows all of it. The fit waits for the size of
  // each item, because the size of a drawing comes after its render.
  private def fitWhenMeasured(): Unit =
    val items = board.now().items
    if !viewSettled && status.now() == BoardStatus.Ready && items.nonEmpty && items.forall(item => sizes.contains(item.id)) then
      viewSettled = true
      fit()

  def panBy(dx: Double, dy: Double): Unit =
    viewSettled = true
    viewport.update(_.panBy(dx, dy))

  def zoomAt(px: Double, py: Double, factor: Double): Unit =
    viewSettled = true
    viewport.update(_.zoomAt(px, py, factor))

  /** Zoom at the center of the canvas. */
  def zoomBy(factor: Double): Unit =
    val (width, height) = canvasSize()
    zoomAt(width / 2, height / 2, factor)

  def resetZoom(): Unit =
    val (width, height) = canvasSize()
    zoomAt(width / 2, height / 2, 1 / viewport.now().scale)

  // ------------------------------------------------------------ the changes

  private def ready: Boolean = status.now() == BoardStatus.Ready

  /** Write the board to the store, and give a refusal to the person. */
  def commit(): Unit =
    if ready then store.save(board.now()).foreach(_.foreach(errors.onNext))

  /** Add an item for `location`, at the right of the items of the board. The
    * id of the item comes from `name`, or else from the location.
    */
  def add(location: String, name: Option[String] = None): Unit =
    if ready then
      val (x, y) = bounds.fold((0.0, 0.0))(all => (all.right + BoardState.Gap, all.y))
      val (next, item) = board.now().add(location, x.round.toDouble, y.round.toDouble, name)
      board.set(next)
      selected.set(Some(item.id))
      // The new item can be outside the view. The fit comes when the view
      // measured the item.
      viewSettled = false
      commit()

  /** Move an item, with no write. [[commit]] ends the move. */
  def move(id: String, x: Double, y: Double): Unit =
    if ready then board.update(_.move(id, x.round.toDouble, y.round.toDouble))

  def remove(id: String): Unit =
    if ready then
      board.update(_.remove(id))
      sizes = sizes.removed(id)
      selected.update(_.filterNot(_ == id))
      commit()

object BoardState:

  /** The size of an item before the view measures it. */
  val DefaultSize: (Double, Double) = (320.0, 240.0)

  /** The space between the items of the board and a new item. */
  val Gap = 60.0
