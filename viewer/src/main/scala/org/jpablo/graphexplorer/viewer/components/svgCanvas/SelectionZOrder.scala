package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.scalajs.dom

import scala.collection.mutable

/** Selected arrows paint LAST.
  *
  * SVG has no z-index: paint order is document order, and the backends emit
  * nodes and edges interleaved (`node:now, node:UI, arrow:now->UI, node:IO, …`),
  * so whether a given edge lands above or below a given node is an accident of
  * emission order. A selected edge's casing could therefore end up UNDER a
  * selected node's translucent selection wash — the selection reading as though
  * it were behind the thing it marks.
  *
  * Raising the ARROW is what fixes it, and it is cheap because both backends
  * emit a FLAT structure: nodes, clusters and edges are all direct children of
  * the main group (a node inside a cluster is a sibling of the cluster `<g>`,
  * not a child of it). So this is an insertBefore, not a reparenting — no
  * coordinate space changes and no cluster structure is disturbed.
  *
  * Nodes are deliberately NOT raised. A node's selection decoration is a
  * translucent slab over its whole box; lifting that above everything would bury
  * the edges crossing it, which is the very complaint this fixes, mirrored.
  *
  * Position is restored on deselect, so the drawing's own paint order survives a
  * selection: leaving a deselected edge parked on top would silently change how
  * unselected content paints for the rest of the render.
  */
object SelectionZOrder:

  /** element → the sibling it sat before, so deselecting can put it back. */
  private val raised = mutable.Map.empty[dom.Element, Option[dom.Node]]

  def reflectSelection(
      mainGroup: dom.Element,
      strategy:  SelectableElementStrategy,
      selected:  ElementIds
  ): Unit =
    // A re-render replaces every element, orphaning whatever we remembered.
    raised.filterInPlace((el, _) => el.parentNode != null)

    val wanted: Set[dom.Element] =
      SelectableElement
        .findAll(mainGroup, strategy)
        .filter(se => se.elementId.isArrowId && selected.contains(se.elementId))
        .map(_.ref)
        // ONLY direct children. Graphviz emits everything flat, so its edges
        // qualify. Mermaid nests the whole drawing under one `g#root`, and
        // walking up to "the child of mainGroup" would return that root for
        // every element — raising it would shove the badge layer BENEATH the
        // entire diagram and make its controls unclickable. Skipping is the safe
        // answer: Mermaid keeps today's paint order rather than getting a
        // plausible-looking change that breaks something else.
        .filter(_.parentNode eq mainGroup)
        .toSet

    for (el, before) <- raised.toList if !wanted.contains(el) do
      // `before` may itself have moved since; insertBefore tolerates a node that
      // is no longer a child by throwing, so fall back to the end.
      try mainGroup.insertBefore(el, before.orNull)
      catch case _: Throwable => mainGroup.appendChild(el)
      el.classList.remove(raisedClass)
      raised.remove(el)

    // Before the badge layer, which must stay the topmost thing: its controls
    // are clickable and an edge painted over them would swallow the pointer.
    val ceiling = Option(mainGroup.querySelector("g.gx-badge-layer"))
    for el <- wanted if !raised.contains(el) do
      raised(el) = Option(el.nextSibling)
      el.classList.add(raisedClass)
      mainGroup.insertBefore(el, ceiling.orNull)

  /** Marks an element this object has lifted. RecordCellOverlay parks itself
    * BELOW these, so its veil never dims the very arrow the selection is
    * pointing at. */
  val raisedClass = "gx-raised"
