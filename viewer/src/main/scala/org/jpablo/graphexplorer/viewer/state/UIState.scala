package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.state.Var

enum RightPanelSection(idx: Int) derives CanEqual:
  case none              extends RightPanelSection(-1)
  case diagramAttributes extends RightPanelSection(0)
  case elements          extends RightPanelSection(1)
  case sources           extends RightPanelSection(2)

  def isVisible = this != none

trait UIState:
  val initialRightPanelSection: RightPanelSection
  val initialLeftPanelVisible: Boolean
  val rightPanelActiveSection = Var(initialRightPanelSection)
  val helpDialogOpen          = Var(false)
  val leftPanelVisible        = Var(initialLeftPanelVisible)
  val canvasContainerFocus    = EventBus[Boolean]()
  val aboutDialogOpen         = Var(false)
  val preferencesDialogOpen   = Var(false)
  val renameDialogOpen        = Var(false)

  /** Width of the right panel in px, set by dragging its left edge. Persisted in ViewerSettings. */
  val rightPanelWidth = Var(ViewerSettings.defaultRightPanelWidth)

  /** The Elements list is palette-first: it opens as a floating card by the right
    * rail, and PINNING docks it into the panel. Persisted in ViewerSettings.
    */
  val elementsPinned = Var(false)

  /** Soft-wrap long lines in the source editor. Off by default: DOT and Mermaid are
    * line-oriented, so wrapping trades a scrollbar for a shifting line count.
    */
  val wrapSourceLines = Var(false)

  /** Experimental 3D canvas: render the visible graph as a three.js scene
    * (Scene3D) instead of the engine's SVG. A Boolean, not an enum — a third
    * render mode can introduce one when it exists. Persisted in ViewerSettings.
    */
  val view3D = Var(false)

  /** Feature gate for 3D mode, set in Preferences and off by default: the 3D
    * toggle and its companions stay out of the toolbar until the user opts in.
    * Deliberately separate from [[view3D]] (which remembers whether 3D was ON):
    * disabling the feature hides the mode without erasing that choice, so
    * re-enabling brings the user back to where they left off. Persisted in
    * ViewerSettings.
    */
  val enable3D = Var(false)

  /** Whether the 3D scene actually shows: the mode is on AND the feature is
    * enabled. Render sites read this; the raw Vars are for the controls.
    */
  val view3DActive: Signal[Boolean] =
    view3D.signal.combineWithFn(enable3D.signal)(_ && _)

  def view3DActiveNow: Boolean = view3D.now() && enable3D.now()

  /** Which 3D layout algorithm drives Scene3D, by [[Layout3D.id]]. Persisted
    * in ViewerSettings; unknown stored ids fall back to the force layout.
    */
  val layout3D = Var(org.jpablo.graphexplorer.viewer.layout3d.ForceLayout3D.id)

  /** 3D navigation idiom: true = trackpad (two-finger scroll ORBITS — the
    * drag gesture without the click — and pinch zooms); false = mouse (wheel
    * zooms). Drag orbits in both; the modes differ only in whether rotating
    * needs a click. ⌥ pans in both. Persisted in ViewerSettings.
    */
  val nav3DTrackpad = Var(true)

  /** Snap the 3D camera orthogonal to the drawing plane (view along −z, up
    * +y) and re-fit — the home view for planar layouts, and the way back to
    * level after free rotation. A bus like fitDiagram: the toolbar emits,
    * the live scene consumes.
    */
  val face3DFront = com.raquo.airstream.eventbus.EventBus[Unit]()

  extension (section: RightPanelSection)
    def isSectionActive: Signal[Boolean] =
      rightPanelActiveSection.signal.map(_ == section)
