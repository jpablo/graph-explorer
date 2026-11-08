package org.jpablo.graphexplorer.library

import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot

/** The snapshot of the viewer, as a `Diagram` and back (D7.3).
  *
  * Under D7.3 the store IS the live state. The viewer and the library use the
  * same `DiagramMetadata`, so the metadata goes through without a change. Only
  * the source, the format, and the fields that the viewer does not own need a
  * rule.
  *
  * Two traps are here:
  *
  *   - `DiagramSnapshot.source` is the TEXT of the diagram, not the place it
  *     came from. A `Diagram` calls it `text`, and uses `origin` for the place.
  *   - The metadata uses the `ElementRef` form (`node:a`, not `a`). A
  *     `Set[String]` of bare ids cannot tell if `n1` is the node or the group.
  *     The metadata commands already write this form, so a headless
  *     `gx run hide` and a click in the UI give the same bytes.
  */
object LibraryMapping:

  def toSnapshot(diagram: Diagram): DiagramSnapshot =
    DiagramSnapshot(
      source = diagram.text,
      format = Option(diagram.format).filter(_.nonEmpty),
      metadata = diagram.metadata
    )

  /** THE merge rule for a save from the viewer.
    *
    * A save must not overwrite what the viewer does not edit. Thus each field
    * has one owner:
    *
    *   - The viewer owns the source, the format, `name`, `hiddenElements`,
    *     `collapsedGroups` and `autoDetectFormat`. They come from `snapshot`.
    *   - The library owns `folder`, `tags`, `notes`, the binding and
    *     `createdAt`. They come from `stored`. The viewer has no UI for them,
    *     so a value from the viewer can only be an old copy. For example, it
    *     erases the tags that `gx run tag` wrote, or unbinds a diagram that
    *     `gx import` bound to a file.
    *
    * `stored` must be the diagram as it is NOW, at the time of the write, and
    * not as it was when the viewer opened it. That is the compare-and-swap
    * (`DesktopLibrary.writeNow`). When no diagram is stored, the snapshot is
    * the only value for each field.
    */
  def mergeViewerSave(id: DiagramId, snapshot: DiagramSnapshot, stored: Option[Diagram], now: Long): Diagram =
    val fromViewer  = snapshot.metadata
    val fromLibrary = stored.fold(fromViewer)(_.metadata)
    Diagram(
      id = id,
      format = snapshot.format.getOrElse(""),
      text = snapshot.source,
      binding = stored.flatMap(_.binding),
      metadata = DiagramMetadata(
        name = fromViewer.name,
        folder = fromLibrary.folder,
        hiddenElements = fromViewer.hiddenElements,
        collapsedGroups = fromViewer.collapsedGroups,
        tags = fromLibrary.tags,
        notes = fromLibrary.notes,
        autoDetectFormat = fromViewer.autoDetectFormat
      ),
      createdAt = stored.fold(now)(_.createdAt),
      updatedAt = now
    )
