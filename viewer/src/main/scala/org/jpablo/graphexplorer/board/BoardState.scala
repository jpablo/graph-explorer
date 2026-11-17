package org.jpablo.graphexplorer.board

import com.raquo.airstream.ownership.Subscription
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.{Board, BoardItem}

import scala.concurrent.ExecutionContext
import scala.scalajs.js

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
final class BoardState(
    val store: BoardStore,
    errors: Observer[String],
    fitWaitMs: Double = BoardState.FitWaitMs
)(using Owner, ExecutionContext):

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

  // The timer of the limited wait for the sizes. `waitEnded` is true after
  // the end of the current wait, so that a new size starts no new wait.
  private var fitTimer: Option[js.timers.SetTimeoutHandle] = None
  private var waitEnded                                   = false

  // True after the end of the owner. A timer then does nothing.
  private var killed = false

  new Subscription(summon[Owner], () =>
    killed = true
    stopWait()
  )

  store.load().foreach:
    case Right(loaded) =>
      board.set(loaded)
      status.set(BoardStatus.Ready)
      fitWhenMeasured()
    case Left(message) =>
      status.set(BoardStatus.Failed(message))

  // A different writer changed the board, for example a tool that wrote the
  // board file. The page shows that version. A version that the store cannot
  // read stops the writes of this page, so that the text of the person stays.
  store.changes.foreach:
    case Right(theirs) =>
      if theirs != board.now() then
        board.set(theirs)
        sizes = sizes.filter((id, _) => theirs.item(id).isDefined)
        selected.update(_.filter(id => theirs.item(id).isDefined))
      status.set(BoardStatus.Ready)
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
  // each item, because the size of a drawing comes after its render. All the
  // Mermaid renders go in one queue, thus the sizes can come late. The wait
  // has a limit of `fitWaitMs`. At the end of the wait, the fit uses the
  // default size for an item with no size. That fit does not settle the view,
  // thus the size of the last item gives a new fit.
  private def fitWhenMeasured(): Unit =
    val items = board.now().items
    if !viewSettled && ready && items.nonEmpty then
      if items.forall(item => sizes.contains(item.id)) then
        settle()
        fit()
      else if fitTimer.isEmpty && !waitEnded && !killed then
        fitTimer = Some(js.timers.setTimeout(fitWaitMs)(endWait()))

  // The wait for the sizes ended before the last size came.
  private def endWait(): Unit =
    fitTimer = None
    waitEnded = true
    if !killed && !viewSettled && ready then fit()

  // The view does not move again by itself, thus the wait stops.
  private def settle(): Unit =
    viewSettled = true
    stopWait()

  private def stopWait(): Unit =
    fitTimer.foreach(js.timers.clearTimeout)
    fitTimer = None
    waitEnded = false

  def panBy(dx: Double, dy: Double): Unit =
    settle()
    viewport.update(_.panBy(dx, dy))

  def zoomAt(px: Double, py: Double, factor: Double): Unit =
    settle()
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
      // measured the item, or at the end of a new limited wait.
      stopWait()
      viewSettled = false
      fitWhenMeasured()
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

  /** The maximum time in milliseconds that the first fit waits for the sizes
    * of the items. After this time, the board fits with the default size for
    * an item with no size. The size of the last item then gives a new fit.
    */
  val FitWaitMs = 3000.0
