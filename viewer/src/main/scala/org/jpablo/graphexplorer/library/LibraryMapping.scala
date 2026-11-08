package org.jpablo.graphexplorer.library

import org.jpablo.graphexplorer.gxcore.command.ElementRef
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.{DiagramId, ElementIds, GroupId}
import org.jpablo.graphexplorer.viewer.state.PersistedDiagramState

/** The saved page state of the viewer, as a `Diagram` and back (D7.3).
  *
  * Under D7.3 the store IS the live state. Thus the two types are one thing
  * with two sets of names, and not two things that the code keeps in sync.
  * Each field must stay through the round trip. If not, a UI edit silently
  * loses each field that this object forgot.
  *
  * Two traps are here:
  *
  *   - `PersistedDiagramState.source` is the TEXT of the diagram, not the
  *     place it came from. A `Diagram` calls it `text`, and uses `origin` for
  *     the place.
  *   - The view state uses the `ElementRef` form (`node:a`, not `a`).
  *     A `Set[String]` of bare ids cannot tell if `n1` is the node or the
  *     group. The metadata commands already write this form, so a
  *     headless `gx run hide` and a click in the UI give the same bytes.
  */
object LibraryMapping:

  /** The mapping KEEPS the ids in a diagram that this viewer cannot parse.
    *
    * A newer `gx` can write a diagram with a kind of id that this build does
    * not know. If this object removes the unknown ids, an older build deletes
    * them silently on the next save of the diagram.
    */
  final case class Unparsed(hidden: Set[String], collapsed: Set[String]):
    def isEmpty: Boolean = hidden.isEmpty && collapsed.isEmpty

  object Unparsed:
    val none: Unparsed = Unparsed(Set.empty, Set.empty)

  def toPersisted(diagram: Diagram): (PersistedDiagramState, Unparsed) =
    val (hiddenIds, hiddenBad)   = partitionRefs(diagram.metadata.hiddenElements)
    val (collapsed, collapsedBad) = partitionRefs(diagram.metadata.collapsedGroups)
    val state = PersistedDiagramState(
      hiddenElements = ElementIds(hiddenIds),
      // Only a group can collapse. Each other id in the set comes from a
      // writer that disagrees. This object keeps it as unparsed, and does not
      // change it into a GroupId.
      collapsedGroups = collapsed.collect { case g: GroupId => g },
      projectName = diagram.metadata.name,
      source = diagram.text,
      format = Option(diagram.format).filter(_.nonEmpty),
      autoDetectFormat = diagram.metadata.autoDetectFormat
    )
    val strayCollapsed = collapsed.filterNot(_.isInstanceOf[GroupId]).map(ElementRef.render)
    (state, Unparsed(hiddenBad, collapsedBad ++ strayCollapsed))

  def toDiagram(
      id:       DiagramId,
      state:    PersistedDiagramState,
      previous: Option[Diagram],
      unparsed: Unparsed,
      now:      Long
  ): Diagram =
    val metadata = DiagramMetadata(
      name = state.projectName,
      folder = previous.map(_.metadata.folder).getOrElse(FolderPath.root),
      hiddenElements = state.hiddenElements.ids.map(ElementRef.render) ++ unparsed.hidden,
      collapsedGroups = state.collapsedGroups.map(ElementRef.render) ++ unparsed.collapsed,
      // The tags and the notes belong to the diagram, not to the page. The
      // viewer has no UI for them. Metadata from the page only erases the
      // values that `gx run tag` wrote.
      tags = previous.map(_.metadata.tags).getOrElse(Nil),
      notes = previous.map(_.metadata.notes).getOrElse(""),
      autoDetectFormat = state.autoDetectFormat
    )
    Diagram(
      id = id,
      format = state.format.getOrElse(""),
      text = state.source,
      // Likewise the binding: the page knows nothing about origins, so a save
      // from the UI must not unbind a diagram that `gx import` bound to a file.
      binding = previous.flatMap(_.binding),
      metadata = metadata,
      createdAt = previous.map(_.createdAt).getOrElse(now),
      updatedAt = now
    )

  private def partitionRefs(refs: Set[String]) =
    val parsed = refs.map(r => r -> ElementRef.parse(r))
    (
      parsed.collect { case (_, Right(id)) => id },
      parsed.collect { case (raw, Left(_)) => raw }
    )
