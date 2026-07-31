package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.DiagramAttributesViews
import org.jpablo.graphexplorer.viewer.state.RightPanelSection.{diagramAttributes, elements, sources}
import org.jpablo.graphexplorer.viewer.state.{ViewerSettings, ViewerState}
import org.scalajs.dom

def RightPanel(state: ViewerState): Div =
  val activeSection =
    state.rightPanelActiveSection.signal

  val activeSectionPair =
    activeSection.scanLeft(x0 => (x0, x0)) { case ((x, y), next) => (y, next) }

  // Elements is palette-first: unpinned it floats like the attributes card;
  // pinned it docks. The pin toggles this LIVE, which is the whole gesture —
  // the floating card visibly becomes the panel.
  val elementsFloating =
    state.elementsPinned.signal.not

  val useTransition =
    activeSectionPair.combineWithFn(state.elementsPinned.signal): (pair, pinned) =>
      val (curr, next) = pair
      val open  = (curr.isVisible || (curr == diagramAttributes)) && ((next == elements && pinned) || next == sources)
      val close = next.isVisible && ((next == elements && pinned) || next == sources)
      open || close

  val isFloating =
    activeSection.combineWithFn(elementsFloating): (section, elFloating) =>
      section == diagramAttributes || (section == elements && elFloating)

  // Diagram attributes view resolved for the current format (no per-format match here).
  val diagramAttributesContent =
    div(
      child <-- state.currentFormat.map(DiagramAttributesViews.forFormat(_, state))
    )

  div(
    idAttr := "right-panel",
    cls <-- state.rightPanelActiveSection.signal.map(s => if s.isVisible then "visible" else "not-visible"),
    cls("floating card card-xs") <-- isFloating,
    cls("elements-palette") <-- activeSection.combineWithFn(elementsFloating)(_ == elements && _),
    cls("transition-all duration-200") <-- useTransition,
    // Width travels as a custom property rather than an inline `width`, so the drag writes
    // ONE property per frame and the open/close transition above stays a pure CSS concern.
    styleAttr <-- state.rightPanelWidth.signal.map(px => s"--right-panel-width: ${px}px"),
    resizeHandle(state, isFloating),
    div(
      idAttr := "right-panel-content",
      cls("card-body") <-- isFloating,
      List(
        diagramAttributes -> diagramAttributesContent,
        elements          -> ElementsList(state),
        sources           -> SourceTab(state)
      ).map: (section, child) =>
        child.amend(cls := "h-full max-h-full flex flex-col", cls("hidden") <-- state.isSectionActive(section).not)
    )
  )

/** In-flight drag: where the pointer went down, and how wide the panel was at that moment.
  * Anchoring to the START of the drag (rather than to the panel's current edge) keeps the
  * width tracking the pointer exactly, even when a frame is dropped or the width clamps.
  */
private case class PanelDrag(startX: Double, startWidth: Int)

/** The panel's left edge, as a drag target. */
private def resizeHandle(state: ViewerState, isFloating: Signal[Boolean]): Div =
  val drag = Var(Option.empty[PanelDrag])

  // Window-level listeners exist only for the duration of a drag: mousemove fires on every
  // pointer motion anywhere in the app, so an always-on handler would do work on every frame
  // for a gesture that happens a few times a session.
  def whileDragging[A](events: => EventStream[A]): EventStream[A] =
    drag.signal.flatMapSwitch:
      case None    => EventStream.empty
      case Some(_) => events

  val resizingClass = "resizing-h"

  div(
    cls := "right-panel-resizer",
    cls("dragging") <-- drag.signal.map(_.isDefined),
    // Hidden unless the panel's width is actually the user's to set — that is,
    // only when DOCKED. Every floating form sizes itself: the attributes card
    // from its rows, the elements palette from its own fixed width, and the CSS
    // applies the dragged width to `.visible:not(.floating)` alone. Keyed on
    // `isFloating` rather than naming the attributes card, which left the
    // palette showing a col-resize handle that moved nothing.
    cls("hidden") <-- state.rightPanelActiveSection.signal
      .combineWithFn(isFloating)((s, floating) => !s.isVisible || floating),
    // preventDefault stops the browser's native text/image drag, which would swallow the
    // subsequent mouseup and leave the panel stuck to the pointer.
    onMouseDown.preventDefault --> { ev =>
      drag.set(Some(PanelDrag(ev.clientX, state.rightPanelWidth.now())))
      dom.document.documentElement.classList.add(resizingClass)
    },
    onDblClick --> { _ =>
      state.rightPanelWidth.set(ViewerSettings.defaultRightPanelWidth)
    },
    whileDragging(windowEvents(_.onMouseMove)) --> { ev =>
      // The panel is anchored to the right edge, so dragging LEFT (a smaller clientX) widens it.
      drag.now().foreach: d =>
        state.rightPanelWidth.set(
          ViewerSettings.clampRightPanelWidth(d.startWidth + (d.startX - ev.clientX), dom.window.innerWidth)
        )
    },
    whileDragging(windowEvents(_.onMouseUp)) --> { _ =>
      drag.set(None)
      dom.document.documentElement.classList.remove(resizingClass)
    }
  )
