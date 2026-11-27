package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ChildNode
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.components.{Commands, toSvgPoint}
import org.jpablo.graphexplorer.viewer.components.scene3d.Scene3D
import org.jpablo.graphexplorer.viewer.desktop.DesktopDrop
import org.jpablo.graphexplorer.viewer.state.{CanvasTransform, EditorNotice, FitMode, ManualLayoutOps, UndoOps, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, DistanceUtils, Platform, TransferItems}
import org.jpablo.graphexplorer.viewer.widgets.{Button, IconButtonTitled, Tooltip, TooltipPos, primary, tiny}

import scala.scalajs.js

/** Creates a container div for the SVG canvas with mouse and keyboard interaction handlers
  *
  * @param state
  *   The viewer state containing diagram data and event handlers
  * @param fitDiagram
  *   Event stream that triggers fitting the diagram to the viewport
  * @return
  *   A div element containing the SVG canvas with interaction handlers
  */
def CanvasContainer(state: ViewerState, commands: Commands) =
  // A press on the inert canvas of a view-only diagram pulses the chip: the
  // answer to "why won't it select?" arrives at the moment the question is asked.
  val viewOnlyNudge = EventBus[Unit]()
  // True while the command key (⌘ on a Mac, Ctrl on other platforms) is down.
  // The stylesheet then shows the link icon of each linked element
  // (LinkBadges), and gives a linked element the hand pointer.
  val linksRevealed = Var(false)
  // The last client point of the pointer over the canvas, or None when the pointer is not over the canvas.
  var pointer = Option.empty[ClientPoint]
  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    // The setting hides the sheet behind a Mermaid drawing. The renderer
    // always adds the sheet, thus a change of the setting does not render again.
    cls(MermaidBackend.noSheetClass) <-- state.mermaidSheet.signal.map(!_),
    // While a pan moves the drawing, the drawing takes no pointer events (see
    // `gx-panning` in style.scss). The wheel listener is on this div, so the
    // pan continues.
    cls("gx-panning") <-- state.panning,
    // A fit needs the size of the SVG box and the bounds of the drawing (see `CanvasFit`). The drawing is the SVG
    // child of this div. The state has no DOM, thus this div gives the measure to the state while it is mounted.
    // Fit (f, the Fit button, `gx fit`) then measures through `resetView`. In 3D, no SVG child shows: Fit uses the
    // fallback of the state, and the scene does its own fit.
    inContext: container =>
      def fitOf(mode: FitMode): Option[CanvasTransform] =
        Option(container.ref.querySelector(":scope > svg"))
          .flatMap(el => CanvasFit.transform(el.asInstanceOf[dom.svg.SVG], mode))
      var unregister = () => ()
      // The place of a new node in a free layout (ADR 0004, part 3): the state converts a client point with this
      // geometry. The last place of the pointer over the canvas comes from the moves below.
      val geometry = ManualLayoutOps.PlaceGeometry(
        drawingPoint = at => drawingPointAt(container.ref, at),
        pointer = () => pointer,
        visibleCenter = () => visibleCenterOf(container.ref)
      )
      Seq(
        onMountUnmountCallback(
          mount = _ =>
            val stopFit    = state.measureFitWith(fitOf)
            val stopPlaces = state.measurePlacesWith(geometry)
            unregister = () =>
              stopFit()
              stopPlaces()
          ,
          unmount = _ =>
            unregister()
            unregister = () => ()
            pointer = None
        ),
        // The pointer over the canvas. A paste and New node put a new node there in a free layout.
        onMouseMove --> { e => pointer = Some(ClientPoint(e.clientX, e.clientY)) },
        onMouseLeave --> { _ => pointer = None },
        state.fitDiagram.events --> {
          case FitMode.Page => state.resetView()
          case mode         => fitOf(mode).foreach(state.fitAxis)
        }
      )
    ,
    // the main canvas!! Either the engine's SVG or the experimental three.js
    // scene — a fresh Scene3D per toggle-on, so 3D state never outlives the
    // mode. view3DActive, not view3D: disabling the 3D feature in Preferences
    // drops straight back to the SVG even if the mode Var still says 3D.
    child.maybe <-- state.view3DActive.distinct.flatMapSwitch:
      case true  => Signal.fromValue(Some(Scene3D(state)): Option[ChildNode.Base])
      case false => state.finalSVG.map(svg => svg: Option[ChildNode.Base])
    ,
    // Render-only diagram kinds (Mermaid beyond flowcharts): the notice used to
    // live only inside the sources panel, invisible unless that panel was open.
    // The chip sits on the canvas itself — the place where the limitation bites.
    child.maybe <-- state.editorNotice.signal
      .map(_.filter(n => !n.isError))
      .map(_.map(ViewOnlyChip(_, viewOnlyNudge.events))),
    onMouseDown --> { _ =>
      if state.editorNotice.now().exists(n => !n.isError) then viewOnlyNudge.emit(())
    },
    // A drag on a node of an automatic layout offers Freeze (ADR 0004). The offer goes away when the diagram becomes
    // a free layout, for example by the command or by an undo.
    child.maybe <-- state.freezeOffer.signal.map(Option.when(_)(FreezeOffer(state))),
    state.hasFreeLayout --> { free => if free && state.freezeOffer.now() then state.freezeOffer.set(false) },
    // A DOT `URL` or `href` attribute and a Mermaid `click X href` put the
    // element in an svg `<a>` link. A click on the canvas only selects. Thus the
    // canvas cancels the link, so that the window stays on the diagram. The
    // event continues to bubble, and the selection does not change.
    // A click with the command key (⌘ on a Mac, Ctrl on other platforms)
    // opens the link in the browser of the system.
    onClick --> { ev =>
      LinkBadges.svgLinkOf(ev.target).foreach: link =>
        ev.preventDefault()
        if Platform.commandKey(ev.metaKey, ev.ctrlKey, Platform.isMac) then
          LinkBadges.linkUrl(link).foreach(state.openLink)
    },
    // The URL of the linked element under the pointer, as a browser shows it.
    child.maybe <-- state.hoveredLink.signal.map(_.map(LinkStatus)),
    cls("gx-links-revealed") <-- linksRevealed.signal.distinct,
    documentEvents(_.onKeyDown) --> { e => if isCommandKeyName(e.key) then linksRevealed.set(true) },
    // The keys of the canvas when the focus is not on the canvas. After a diagram opens, the focus is on the body.
    // After a click on a button of the toolbar or of a panel, the focus is on the button. The canvas handles its own
    // keys below, thus this listener ignores a key from inside the canvas. This listener also gives ⌘Z and ⇧⌘Z to
    // the history when the focus is on a button. See `routesToCanvas` for the keys that stay with their owners.
    // A board item shows only the drawing and has no canvas, and the workspace mounts one canvas. The check of
    // `embedded` makes sure that an embedded view never takes the keys of the page.
    inContext: container =>
      documentEvents(_.onKeyDown).filter(e => !state.embedded && routesToCanvas(e, container.ref)) -->
        commands.handleKeyDown
    ,
    documentEvents(_.onKeyUp) --> { e => if isCommandKeyName(e.key) then linksRevealed.set(false) },
    // A window that loses the focus while the key is down (⌘-Tab) gets no
    // key-up. A blur ends the state, and each mouse move gives the real state.
    windowEvents(_.onBlur) --> linksRevealed.set(false),
    onMouseMove --> { e => linksRevealed.set(Platform.commandKey(e.metaKey, e.ctrlKey, Platform.isMac)) },
    // we need a way to move the focus here after certain events
    focus <-- state.canvasContainerFocus,
    // abort ongoing mouse actions when the focus is lost
    // TODO: this makes the "ArrowFromSourceToPointer" disappear when the focus is lost.
    // Perhaps the solution is to make sure the focus is not lost when clicking on the arrow?
//    onBlur --> state.mouseAction.inactive(),
    // The keys of the canvas when the canvas has the focus. A handled key stops here, and the document-level
    // listener above ignores a key from inside the canvas. Thus a key runs its command one time.
    onKeyDown --> commands.handleKeyDown,
    // ⌘V / Ctrl+V, bound as the browser's own PASTE EVENT rather than as a
    // keyboard shortcut in `handleKeyDown`. Three things fall out of that:
    //   - the text arrives ON the event, so there is no clipboard-read
    //     permission prompt — the gesture IS the grant;
    //   - Edit ▸ Paste and Linux middle-click paste come along free;
    //   - the browser dispatches `paste` to the FOCUSED element, so pasting
    //     into the source editor still means "insert at the cursor" and never
    //     reaches this handler.
    // Consuming the keydown instead would suppress this event entirely, which
    // is why Commands.handleKeyDown deliberately lets the chord through.
    //
    // An image on the clipboard (a screenshot) becomes an image node. Else the
    // text replaces the diagram when it declares one, or becomes a text node
    // (see `ImportOps.pasteTextFromGesture`).
    onPaste.preventDefault --> { ev =>
      // Edit ▸ Paste has no key of its own, so the paste marks its own gesture:
      // the history then has the paste as one step.
      state.history.markGesture()
      Option(ev.clipboardData) match
        case None => state.pasteTextFromGesture("")
        case Some(transfer) =>
          // In a free layout, the new node goes to the pointer, or to the center of the visible canvas.
          val at = state.newNodePlaceNow()
          TransferItems.imageFile(transfer) match
            case Some(file) => state.pasteImageFromGesture(TransferItems.readImage(file), at)
            case None       => state.pasteTextFromGesture(TransferItems.text(transfer), at)
    },
    // A drop of files, links or text from outside of the page: each file becomes a node (an image node for an
    // image), and so does each link. Text becomes a text node (see `ImportOps.dropFilesFromGesture`).
    dropTarget(state),
    // The canvas CONSUMES the wheel gesture (it pans/zooms a transform, the page
    // never scrolls), so it must preventDefault — without it the browser sees an
    // unconsumed horizontal scroll chaining to an unscrollable viewport, and
    // macOS classifies the leftward overscroll as the history back-swipe.
    // Also keeps trackpad pinch (wheel + ctrlKey) from zooming the whole page.
    // preventDefault works here because the div's listener is non-passive —
    // Chrome's passive-by-default applies only to window/document/body.
    // In 3D, the scene owns the wheel gesture; the guard keeps the 2D
    // transform from silently mutating under an unmounted SVG. preventDefault
    // still applies (it runs at the listener, before the filter), so the
    // back-swipe protection above holds in both modes.
    onWheel.preventDefault(_.filter(_ => !state.view3DActiveNow).withCurrentValueOf(state.finalSVG)) --> (
      (e, svgElemO) => svgElemO.foreach: s =>
        val mainGroup = CanvasRoot.mainGroup(s.ref)
        state.handleWheel(e, s.ref.viewBox.baseVal, mainGroup)
    ),
    // WebKit (Safari and the macOS desktop window) sends a trackpad pinch as
    // gesture events, not as wheel events. The canvas cancels gesturestart and
    // gesturechange, so that Safari does not zoom the whole page. As with the
    // wheel, the cancel occurs also in 3D, and the guard stops the 2D zoom.
    // gestureend has no guard: it always ends the pinch.
    onGestureStart.preventDefault(_.filter(_ => !state.view3DActiveNow)) --> state.pinchStart(),
    onGestureChange.preventDefault(_.filter(_ => !state.view3DActiveNow).withCurrentValueOf(state.finalSVG)) --> (
      (e, svgElemO) =>
        for
          s     <- svgElemO
          scale <- gestureNumber(e, "scale").filter(_ > 0)
        do
          // Use the center of the svg if the event has no client point.
          val at =
            (gestureNumber(e, "clientX"), gestureNumber(e, "clientY")) match
              case (Some(x), Some(y)) => ClientPoint(x, y)
              case _                  => DistanceUtils.clientRectCenter(s.ref.getBoundingClientRect())
          state.pinchChange(scale, at, CanvasRoot.mainGroup(s.ref))
    ),
    onGestureEnd --> state.pinchEnd()
  )

/** The point of the drawing under the client point `at`, in points with the y axis up, or None with no drawing.
  *
  * The drawing group has the transform of the canvas (the pan and the zoom), and Graphviz draws the place `(x, y)` at
  * the point `(x, -y)` of that group (see `NodeDrag`). Thus the inverse of the screen matrix of the group gives the
  * point of the group, and the y axis turns around.
  */
private def drawingPointAt(container: dom.Element, at: ClientPoint): Option[(Double, Double)] =
  Option(container.querySelector(":scope > svg")).collect { case svg: dom.svg.SVG => svg }.flatMap: svg =>
    Option(CanvasRoot.mainGroup(svg).getScreenCTM()).filter(m => m.a != 0 && m.d != 0).map: ctm =>
      val local = at.toSvgPoint(ctm)
      (local.x, -local.y)

/** The client point at the center of the visible part of the canvas: the box of the canvas inside the window. A canvas
  * out of the window gives the center of its box.
  */
private def visibleCenterOf(container: dom.Element): Option[ClientPoint] =
  val r      = container.getBoundingClientRect()
  val left   = math.max(r.left, 0)
  val top    = math.max(r.top, 0)
  val right  = math.min(r.right, dom.window.innerWidth.toDouble)
  val bottom = math.min(r.bottom, dom.window.innerHeight.toDouble)
  if r.width <= 0 || r.height <= 0 then None
  else if right > left && bottom > top then Some(ClientPoint((left + right) / 2, (top + bottom) / 2))
  else Some(ClientPoint(r.left + r.width / 2, r.top + r.height / 2))

// scalajs-dom has no type for the WebKit `GestureEvent`, and Chromium has no
// such class. Thus the canvas reads the properties of the event dynamically.
private val onGestureStart  = eventProp[dom.Event]("gesturestart")
private val onGestureChange = eventProp[dom.Event]("gesturechange")
private val onGestureEnd    = eventProp[dom.Event]("gestureend")

/** The number property `name` of the event `e`. It is empty if the property is
  * not a finite number. */
private def gestureNumber(e: dom.Event, name: String): Option[Double] =
  val v = e.asInstanceOf[js.Dynamic].selectDynamic(name)
  if js.typeOf(v) == "number" then Some(v.asInstanceOf[Double]).filter(x => !x.isNaN && !x.isInfinite)
  else None

/** The keys that a focused control uses: the browser clicks a button on Enter or Space, and moves in a group or
  * scrolls the panel on an arrow key.
  */
private val controlKeys = Set("Enter", " ", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight")

/** The elements that the browser or ARIA treats as an interactive control. */
private val controlSelector =
  "button, a[href], summary, [role=button], [role=link], [role=menuitem], [role=menuitemcheckbox], " +
    "[role=menuitemradio], [role=tab], [role=option], [role=checkbox], [role=radio], [role=switch], " +
    "[role=slider], [role=spinbutton], [role=listbox], [role=menu], [role=menubar], [role=tablist], " +
    "[role=radiogroup], [role=tree], [role=treeitem], [role=grid]"

/** True when the document-level listener gives the key `e` to the commands of the canvas.
  *
  * The key stays with its owner in these cases:
  *   - A different listener handled the key (it called `preventDefault`).
  *   - The key comes from inside the canvas. The canvas has its own listener, and a second call runs the command two
  *     times.
  *   - The focus is on a text field, a text area, a select, or an element with editable content (the source editor,
  *     the label editor, the search of the command palette, the filter of the element list).
  *   - The focus is inside an open dialog.
  *   - The key is Escape inside the element list, which closes the list.
  *   - The focus is on a button, a link or another control, and the key is one of `controlKeys`.
  */
private[svgCanvas] def routesToCanvas(e: dom.KeyboardEvent, canvas: dom.Element): Boolean =
  !e.defaultPrevented && (e.target match
    case el: dom.Element =>
      !canvas.contains(el) &&
        !UndoOps.isEditable(el) &&
        el.closest("dialog[open]") == null &&
        !(e.key == "Escape" && el.closest("#elements-list") != null) &&
        !(controlKeys(e.key) && el.matches(controlSelector))
    case _ => true
  )

/** True when `key` (a `KeyboardEvent.key`) is the command key of the platform. */
private def isCommandKeyName(key: String): Boolean =
  if Platform.isMac then key == "Meta" else key == "Control"

/** The strip at the bottom-left corner of the canvas that shows the URL of the linked element under the pointer, and
  * how to open it. The strip takes no pointer events, so it does not hide the drawing from the pointer.
  */
private def LinkStatus(url: String) =
  val key = if Platform.isMac then "⌘-" else "Ctrl+"
  div(
    idAttr := "gx-link-status",
    i(cls := "bi bi-link-45deg"),
    span(cls := "gx-link-url", url),
    span(cls := "gx-link-hint", s"${key}click to open")
  )

private[svgCanvas] val viewOnlyChipPulseKeyframes = js.Array(
  js.Dynamic.literal(transform = "scale(1)"),
  js.Dynamic.literal(transform = "scale(1.15)"),
  js.Dynamic.literal(transform = "scale(1)")
)

/** The persistent "this diagram is view-only" pill, floating top-center on the
  * canvas. The full explanation lives in its tooltip; a nudge (a click on the
  * inert canvas) plays a small scale pulse to draw the eye.
  */
private def ViewOnlyChip(notice: EditorNotice, nudge: EventStream[Unit]) =
  div(
    idAttr := "view-only-chip",
    cls    := "floating-toolbar",
    Tooltip(
      text = notice.message,
      cls := TooltipPos.bottom,
      span(cls := "flex items-center gap-1.5", i(cls := "bi bi-eye"), "View-only")
    ),
    inContext { el =>
      nudge --> { _ =>
        if !dom.window.matchMedia("(prefers-reduced-motion: reduce)").matches then
          el.ref
            .asInstanceOf[js.Dynamic]
            .animate(
              viewOnlyChipPulseKeyframes,
              js.Dynamic.literal(duration = 350, easing = "ease-out")
            )
      }
    }
  )

/** The offer of Freeze after a drag on a node of an automatic layout. A drag does not change the layout in silence:
  * the person decides. Freeze stores the place of each node, and then a drag moves the nodes.
  */
private def FreezeOffer(state: ViewerState) =
  div(
    idAttr := "gx-freeze-offer",
    cls    := "floating-toolbar",
    role   := "status",
    span(cls := "gx-freeze-text", "The layout is automatic. Freeze it to move nodes by hand."),
    Button(cls := "gx-freeze-accept", "Freeze layout", onClick --> { _ => state.acceptFreezeOffer() }).tiny.primary,
    IconButtonTitled("bi bi-x", "Close", cls := "gx-freeze-dismiss")(state.freezeOffer.set(false)),
    onKeyDown.filter(_.key == "Escape") --> { _ => state.freezeOffer.set(false) }
  )

extension (e: dom.MouseEvent)
  def clientCoords    = (ClientPoint(e.clientX, e.clientY), e.shiftKey)
  def leftButton      = e.button == 0
  def leftButtonMoved = e.buttons == 1

/** The class of the canvas container while a drag of files, links or text is over the canvas (see style.scss). */
private val DropTargetClass = "gx-drop-target"

/** The handlers of a drop on the canvas, and the drop target that shows during the drag.
  *
  * The browser opens a dropped file in the window, and the diagram goes away. A `preventDefault` on `dragover` and on
  * `drop` stops that, and lets the canvas take the drop.
  *
  * The browser sends `dragleave` each time the drag goes from the container to a child (the drawing), and `dragenter`
  * for the child first. Thus a count of the enters, less the leaves, tells if the drag is over the canvas. The drop
  * target goes away when the count is zero, and after the drop.
  */
private def dropTarget(state: ViewerState): Modifier[HtmlElement] =
  val over  = Var(false)
  var depth = 0
  // The desktop gives a drop of files as Tauri events, and not as DOM events (see DesktopDrop).
  val desktopOver = Var(false)
  def accepts(ev: dom.DragEvent): Boolean = Option(ev.dataTransfer).exists(TransferItems.droppable)
  Seq(
    cls(DropTargetClass) <-- over.signal.combineWithFn(desktopOver.signal)(_ || _).distinct,
    onMountUnmountCallbackWithState[HtmlElement, () => Unit](
      mount = ctx =>
        // A board item has no canvas of its own, and the window has one drop.
        if state.embedded || !DesktopDrop.available then () => ()
        else
          DesktopDrop.install(
            ctx.thisNode.ref,
            desktopOver.set,
            (paths, point) =>
              // A drop has no key and no press on the canvas, so it marks its own gesture: the drop is one step.
              state.history.markGesture()
              // In a free layout, the new nodes go to the drop point.
              state.dropPathsFromGesture(paths, point.flatMap(state.layoutPointAt))
          ),
      unmount = (_, stop) => stop.foreach(_())
    ),
    onDragEnter --> { ev =>
      if accepts(ev) then
        ev.preventDefault()
        depth += 1
        over.set(true)
    },
    onDragOver --> { ev =>
      if accepts(ev) then
        ev.preventDefault()
        ev.dataTransfer.dropEffect = dom.DataTransferDropEffectKind.copy
        over.set(true)
    },
    onDragLeave --> { ev =>
      if accepts(ev) then
        depth = math.max(0, depth - 1)
        if depth == 0 then over.set(false)
    },
    onDrop --> { ev =>
      depth = 0
      over.set(false)
      Option(ev.dataTransfer).filter(TransferItems.droppable).foreach: transfer =>
        ev.preventDefault()
        // A drop has no key and no press on the canvas, so it marks its own gesture: the drop is one step.
        state.history.markGesture()
        val files = TransferItems.files(transfer)
        val links = TransferItems.links(transfer)
        // In a free layout, the new nodes go to the drop point.
        val at = state.layoutPointAt(ClientPoint(ev.clientX, ev.clientY))
        if files.nonEmpty then state.dropFilesFromGesture(files.exists(TransferItems.isImage), TransferItems.readItems(files), at)
        else if links.nonEmpty then state.dropLinksFromGesture(links, at)
        else state.dropTextFromGesture(TransferItems.text(transfer), at)
    }
  )
