package org.jpablo.graphexplorer.board

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{title as titleAttr, *}
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.gxcore.model.BoardItem
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.components.ErrorAlert
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.widgets.MenuEntry.MenuOption
import org.jpablo.graphexplorer.viewer.widgets.{Button, Dropdown, IconButton, MenuEntry, TooltipPos, ghost, small}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** What the frame of an item shows. */
private enum ItemContent derives CanEqual:
  /** The store did not give the data source of the item yet. */
  case Loading

  /** The data source is missing. The item stays on the board (ADR 0003). */
  case Missing

  /** The diagram of the item, with its own state: its source, its graph, its
    * metadata, and its drawing.
    */
  case Shown(target: ViewTarget, viewer: ViewerState)

  def isMissing: Boolean = this match
    case Missing => true
    case _       => false

/** The page of one board: many diagrams on one canvas (ADR 0003).
  *
  * A person moves an item by its title bar, and moves the view with the
  * wheel or with a drag of the background. Each item shows the drawing of its
  * diagram, with the metadata of the diagram: the hidden elements, the
  * collapsed groups, and the renderer. A double click opens the diagram in
  * its own view, where the person changes it.
  */
def BoardView(store: BoardStore, graphviz: Graphviz, router: Router, errors: EventBus[String]): Div =
  val state = BoardState(store, errors.writer)

  val candidates = Var(List.empty[BoardCandidate])
  def readCandidates(): Unit = store.candidates().foreach(candidates.set)

  // A drag of the background moves the view. The value is the last place of the pointer.
  var pan: Option[(Double, Double)] = None

  val layer = div(
    cls := "board-layer",
    styleAttr <-- state.viewport.signal.map(view => s"transform: ${view.css};"),
    children <-- state.board.signal
      .map(_.items)
      // A different location is a different data source, so it gets a new frame.
      .split(item => s"${item.id}\n${item.location}"): (_, initial, item) =>
        itemFrame(initial, item, state, graphviz, router, errors)
  )

  lazy val canvas: Div = div(
    cls := "board-canvas",
    layer,
    onMountCallback: ctx =>
      state.canvasSize = () => (ctx.thisNode.ref.clientWidth.toDouble, ctx.thisNode.ref.clientHeight.toDouble)
    ,
    // The wheel moves the view. With Ctrl or Cmd, and for a pinch, it changes the scale.
    onWheel.preventDefault --> { event =>
      if event.ctrlKey || event.metaKey then
        val box = canvas.ref.getBoundingClientRect()
        state.zoomAt(event.clientX - box.left, event.clientY - box.top, math.exp(-event.deltaY / 300))
      else state.panBy(-event.deltaX, -event.deltaY)
    },
    onPointerDown --> { event =>
      val onBackground = (event.target eq canvas.ref) || (event.target eq layer.ref)
      if event.button == 0 && onBackground then
        pan = Some((event.clientX, event.clientY))
        state.selected.set(None)
        capturePointer(canvas.ref, event)
    },
    onPointerMove --> { event =>
      pan.foreach: (x, y) =>
        state.panBy(event.clientX - x, event.clientY - y)
        pan = Some((event.clientX, event.clientY))
    },
    onPointerUp --> (_ => pan = None),
    onPointerCancel --> (_ => pan = None),
    child.maybe <-- state.status.signal.combineWithFn(state.board.signal): (status, board) =>
      status match
        case BoardStatus.Failed(message) =>
          Some(div(cls := "board-message", i(cls := "bi bi-exclamation-triangle mr-1"), s"The app cannot read this board: $message"))
        case BoardStatus.Ready if board.items.isEmpty =>
          Some(div(cls := "board-message", idAttr := "board-empty", "This board has no items. Use \"Add diagram\" to put a diagram on it."))
        case _ => None
  )

  // The entries of the add menu. `None` is the note of an empty library.
  val addEntries: Signal[Seq[MenuEntry[Option[BoardCandidate]]]] = candidates.signal.map:
    case Nil => Seq(MenuOption("The library has no diagrams.", None))
    case all => all.map(candidate => MenuOption(candidate.name, Some(candidate)))

  div(
    idAttr := "board-view",
    div(
      cls := "board-bar",
      Button(
        aria.label := "Back to the library",
        i(cls := "bi bi-arrow-left"),
        "Library",
        onClick --> (_ => router.navigateTo(Route.Home))
      ).ghost.small,
      span(cls := "board-name", idAttr := "board-name", i(cls := "bi bi-easel mr-2"), child.text <-- store.name),
      div(cls := "flex-1"),
      IconButton("bi-dash", "Zoom out", TooltipPos.bottom)(state.zoomBy(1 / 1.25)),
      Button(
        idAttr    := "board-zoom",
        cls       := "tabular-nums",
        titleAttr := "Go to the scale 100%",
        child.text <-- state.viewport.signal.map(view => s"${(view.scale * 100).round}%"),
        onClick --> (_ => state.resetZoom())
      ).ghost.small,
      IconButton("bi-plus", "Zoom in", TooltipPos.bottom)(state.zoomBy(1.25)),
      Button(idAttr := "board-fit", "Fit", onClick --> (_ => state.fit())).ghost.small,
      Dropdown[Option[BoardCandidate]](
        title = Seq[Modifier.Base](
          i(cls := "bi bi-plus-circle mr-1"),
          "Add diagram",
          // The list is read at each open, because the library can change.
          onFocus --> (_ => readCandidates())
        ),
        options = addEntries,
        onClickHandler = _ --> { selected =>
          selected.foreach(candidate => state.add(candidate.location, Some(candidate.name)))
          // The menu closes when the focus goes away.
          dom.document.activeElement match
            case focused: dom.HTMLElement => focused.blur()
            case _                        => ()
        },
        menuCls = "board-add-menu"
      ).amend(cls := "dropdown-end", idAttr := "board-add")
    ),
    canvas,
    ErrorAlert(errors)
  )

/** Send each later event of this pointer to `element`, also when the pointer
  * leaves it. Thus a fast drag does not lose its element.
  */
private def capturePointer(element: dom.EventTarget, event: dom.PointerEvent): Unit =
  // The browser refuses a pointer that is not active, for example the pointer
  // of an event that code made. The drag then works while the pointer is on
  // the element.
  try
    element.asInstanceOf[js.Dynamic].setPointerCapture(event.pointerId)
    ()
  catch case _: js.JavaScriptException => ()

/** The frame of one item: a title bar, and the drawing of the diagram.
  *
  * The frame makes the `ViewerState` of its diagram, on an owner of its own.
  * The unmount kills the owner, so an item that leaves the board releases the
  * state of its diagram.
  */
private def itemFrame(
    initial:  BoardItem,
    item:     Signal[BoardItem],
    state:    BoardState,
    graphviz: Graphviz,
    router:   Router,
    errors:   EventBus[String]
): Div =
  val id      = initial.id
  val owner   = new ManualOwner
  val content = Var[ItemContent](ItemContent.Loading)
  // False after the unmount. The store can give the target after it.
  var mounted = true

  state.store
    .target(initial)
    .foreach: target =>
      if mounted then
        content.set(target match
          case Some(shown) => ItemContent.Shown(shown, ViewerState(shown, graphviz, errorBus = errors)(using owner))
          case None        => ItemContent.Missing
        )

  def open(): Unit =
    content.now() match
      case ItemContent.Shown(target, _) => BoardStore.routeOf(target).foreach(router.navigateTo)
      case _                            => ()

  val title: Signal[String] = content.signal.flatMapSwitch:
    case ItemContent.Shown(_, viewer) => viewer.displayTitle
    case _                            => Val(initial.location)

  /** The drawing with the size that the item gives it, and the size of the frame to the board. */
  def sized(drawing: ReactiveSvgElement[dom.svg.SVG], width: Option[Double]): ReactiveSvgElement[dom.svg.SVG] =
    val box     = drawing.ref.viewBox.baseVal
    val natural = if box != null && box.width > 0 && box.height > 0 then (box.width, box.height) else BoardState.DefaultSize
    val factor  = width.fold(1.0)(_ / natural._1)
    drawing.ref.setAttribute("width", (natural._1 * factor).toString)
    drawing.ref.setAttribute("height", (natural._2 * factor).toString)
    // Mermaid sets a maximum width in the style of the drawing.
    drawing.ref.style.maxWidth = "none"
    state.itemSize(id, natural._1 * factor, natural._2 * factor + BoardFrame.TitleHeight)
    drawing

  /** The text in the place of a drawing. `last` is true when no drawing comes
    * after it. Then its size is the size of the item, for the first fit of
    * the view. A text for the wait gives no size, so the fit waits.
    */
  def placeholder(icon: String, text: String, last: Boolean): Div =
    if last then state.itemSize(id, BoardState.DefaultSize._1, BoardState.DefaultSize._2)
    div(cls := "board-item-placeholder", i(cls := s"bi $icon mr-1"), text)

  def waiting: Div = placeholder("bi-hourglass", "Loading…", last = false)

  def bodyOf(shown: ItemContent): Signal[Node] = shown match
    case ItemContent.Loading => Val(waiting)
    case ItemContent.Missing => Val(placeholder("bi-exclamation-triangle", s"Missing: ${initial.location}", last = true))
    case ItemContent.Shown(_, viewer) =>
      viewer.drawing.combineWithFn(viewer.loadStatus.signal, item.map(_.width).distinct): (drawing, status, width) =>
        val node: Node = (drawing, status) match
          case (Some(svg), _)                           => sized(svg, width)
          case (None, DiagramLoadStatus.Failed(reason)) => placeholder("bi-exclamation-triangle", reason, last = true)
          case (None, _)                                => waiting
        node

  // A drag of the title bar moves the item. The value is the place of the
  // pointer and the place of the item at the start of the drag.
  var drag: Option[(Double, Double, Double, Double)] = None

  def endDrag(): Unit =
    if drag.isDefined then
      drag = None
      state.commit()

  div(
    cls := "board-item",
    dataAttr("item-id") := id,
    cls("selected") <-- state.selected.signal.map(_.contains(id)),
    cls("missing") <-- content.signal.map(_.isMissing),
    styleAttr <-- item.map(placed => s"left: ${placed.x}px; top: ${placed.y}px;"),
    onPointerDown --> (_ => state.selected.set(Some(id))),
    div(
      cls := "board-item-title",
      span(cls := "board-item-name", child.text <-- title),
      button(
        cls        := "board-item-button",
        typ        := "button",
        titleAttr  := "Open the diagram",
        aria.label := "Open the diagram",
        i(cls := "bi bi-box-arrow-up-right"),
        disabled <-- content.signal.map(_.isMissing),
        onClick --> (_ => open())
      ),
      button(
        cls        := "board-item-button",
        typ        := "button",
        titleAttr  := "Remove from the board",
        aria.label := "Remove from the board",
        i(cls := "bi bi-x-lg"),
        onClick --> (_ => state.remove(id))
      ),
      onPointerDown --> { event =>
        val onButton = event.target match
          case element: dom.Element => element.closest("button") != null
          case _                    => false
        if event.button == 0 && !onButton then
          state.board.now().item(id).foreach: placed =>
            drag = Some((event.clientX, event.clientY, placed.x, placed.y))
            capturePointer(event.currentTarget, event)
      },
      onPointerMove --> { event =>
        drag.foreach: (pointerX, pointerY, itemX, itemY) =>
          val scale = state.viewport.now().scale
          state.move(id, itemX + (event.clientX - pointerX) / scale, itemY + (event.clientY - pointerY) / scale)
      },
      onPointerUp --> (_ => endDrag()),
      onPointerCancel --> (_ => endDrag())
    ),
    div(
      cls := "board-item-body",
      child <-- content.signal.flatMapSwitch(bodyOf),
      onDblClick --> (_ => open())
    ),
    onUnmountCallback: _ =>
      mounted = false
      content.now() match
        case ItemContent.Shown(_, viewer) => viewer.closePersistence()
        case _                            => ()
      owner.killSubscriptions()
  )

private object BoardFrame:
  /** The height of the title bar of an item, as the style sheet gives it. */
  val TitleHeight = 28.0
