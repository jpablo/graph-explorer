package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.state.Var

/** Persisted by `ordinal` (see Persistence), NOT by any hand-assigned number. */
enum RightPanelSection derives CanEqual:
  case none, diagramAttributes, elements, sources

  def isVisible = this != none

object RightPanelSection:
  /** The section of a stored index (`ViewerSettings.rightPanelTabIndex`). An
    * unknown index gives `none`.
    */
  def fromStored(index: Int): RightPanelSection =
    scala.util.Try(fromOrdinal(index)).getOrElse(none)

/** The 3D settings of a view. They are viewer settings, as the panels are.
  *
  * The diagram workspace makes one value with the stored settings, and gives
  * it to each view. Thus a switch of diagrams keeps the 3D controls of the zoom
  * bar as they are. A view with no workspace gets the default values, and the
  * restore sets them from the stored viewer settings.
  */
final case class Settings3D(
    view3D:           Var[Boolean] = Var(false),
    enable3D:         Var[Boolean] = Var(false),
    layout3D:         Var[String] = Var(org.jpablo.graphexplorer.viewer.layout3d.ForceLayout3D.id),
    nav3DTrackpad:    Var[Boolean] = Var(true),
    label3DBillboard: Var[Boolean] = Var(false)
):
  /** True when the 3D scene shows: the mode is on, and the feature is on. */
  val active: Signal[Boolean] = view3D.signal.combineWithFn(enable3D.signal)(_ && _)

object Settings3D:
  /** The 3D settings that `settings` stores. */
  def stored(settings: ViewerSettings): Settings3D =
    Settings3D(
      view3D = Var(settings.view3D),
      enable3D = Var(settings.enable3D),
      layout3D = Var(storedLayout3D(settings)),
      nav3DTrackpad = Var(settings.nav3DTrackpad),
      label3DBillboard = Var(settings.label3DBillboard)
    )

  /** The 3D layout that `settings` stores. With no stored layout, the force layout. */
  def storedLayout3D(settings: ViewerSettings): String =
    settings.layout3D.getOrElse(org.jpablo.graphexplorer.viewer.layout3d.ForceLayout3D.id)

trait UIState:
  /** The section that the right panel shows. A workspace of diagrams can give
    * one Var to each view that it makes. Thus the value outlives each view.
    */
  val rightPanelActiveSection: Var[RightPanelSection]
  val helpDialogOpen          = Var(false)

  /** True when the Library panel shows. A workspace of diagrams can give one
    * Var to each view that it makes. Thus the value outlives each view.
    */
  val leftPanelVisible: Var[Boolean]

  val canvasContainerFocus    = EventBus[Boolean]()

  /** The URL of the linked element under the pointer, if any. The URL strip of the canvas shows it. */
  val hoveredLink = Var(Option.empty[String])
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

  /** The 3D settings of this view. A workspace of diagrams can give one value
    * to each view that it makes. Thus the values outlive each view.
    */
  val settings3D: Settings3D

  /** Experimental 3D canvas: render the visible graph as a three.js scene
    * (Scene3D) instead of the engine's SVG. A Boolean, not an enum — a third
    * render mode can introduce one when it exists. Persisted in ViewerSettings.
    */
  final def view3D: Var[Boolean] = settings3D.view3D

  /** Feature gate for 3D mode, set in Preferences and off by default: the 3D
    * toggle and its companions stay out of the toolbar until the user opts in.
    * Deliberately separate from [[view3D]] (which remembers whether 3D was ON):
    * disabling the feature hides the mode without erasing that choice, so
    * re-enabling brings the user back to where they left off. Persisted in
    * ViewerSettings.
    */
  final def enable3D: Var[Boolean] = settings3D.enable3D

  /** Whether the 3D scene actually shows: the mode is on AND the feature is
    * enabled. Render sites read this; the raw Vars are for the controls.
    */
  final def view3DActive: Signal[Boolean] = settings3D.active

  def view3DActiveNow: Boolean = view3D.now() && enable3D.now()

  /** Which 3D layout algorithm drives Scene3D, by [[Layout3D.id]]. Persisted
    * in ViewerSettings; unknown stored ids fall back to the force layout.
    */
  final def layout3D: Var[String] = settings3D.layout3D

  /** 3D navigation idiom: true = trackpad (two-finger scroll ORBITS — the
    * drag gesture without the click — and pinch zooms); false = mouse (wheel
    * zooms). Drag orbits in both; the modes differ only in whether rotating
    * needs a click. ⌥ pans in both. Persisted in ViewerSettings.
    */
  final def nav3DTrackpad: Var[Boolean] = settings3D.nav3DTrackpad

  /** Snap the 3D camera orthogonal to the drawing plane (view along −z, up
    * +y) and re-fit — the home view for planar layouts, and the way back to
    * level after free rotation. A bus like fitDiagram: the toolbar emits,
    * the live scene consumes.
    */
  val face3DFront = com.raquo.airstream.eventbus.EventBus[Unit]()

  /** Billboard node labels: ON = every label turns to face the camera; OFF
    * (the default) = labels are sheets with a fixed orientation, facing the
    * front view. Layouts that dictate node geometry (the flat dot layout)
    * always use in-plane sheets — a billboard's border swings off the plane
    * and detaches the edges. Persisted in ViewerSettings.
    */
  final def label3DBillboard: Var[Boolean] = settings3D.label3DBillboard

  /** Show a sheet behind each Mermaid drawing (MermaidBackend.addSheet). The
    * renderer always adds the sheet. When this is false, a class on the canvas
    * hides it, and an export leaves it out. Thus a change does not render the
    * diagram again. Persisted in ViewerSettings.
    */
  val mermaidSheet = Var(true)

  /** Adapt the diagram colors to a dark theme (DiagramColors). A change
    * paints the mounted SVG again, with no new render. Persisted in
    * ViewerSettings.
    */
  val diagramDarkColors = Var(true)

  extension (section: RightPanelSection)
    def isSectionActive: Signal[Boolean] =
      rightPanelActiveSection.signal.map(_ == section)
