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

  /** Which 3D layout algorithm drives Scene3D, by [[Layout3D.id]]. Persisted
    * in ViewerSettings; unknown stored ids fall back to the force layout.
    */
  val layout3D = Var(org.jpablo.graphexplorer.viewer.layout3d.ForceLayout3D.id)

  /** 3D navigation idiom: true = trackpad (two-finger scroll pans, pinch
    * zooms — the 2D canvas's idiom, hence the default); false = mouse (wheel
    * zooms). Drag orbits in both. Persisted in ViewerSettings.
    */
  val nav3DTrackpad = Var(true)

  extension (section: RightPanelSection)
    def isSectionActive: Signal[Boolean] =
      rightPanelActiveSection.signal.map(_ == section)
