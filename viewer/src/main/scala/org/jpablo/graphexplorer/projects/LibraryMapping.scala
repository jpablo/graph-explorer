package org.jpablo.graphexplorer.projects

import org.jpablo.graphexplorer.gxcore.command.ElementRef
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.{ElementIds, GroupId}
import org.jpablo.graphexplorer.viewer.state.PersistedDiagramState

/** The viewer's saved page state, as a library record and back (D7.3).
  *
  * Under D7.3 the store IS the live state, so these two are the same thing in
  * two vocabularies rather than two things kept in sync. Every field has to
  * survive the round trip or a UI edit would quietly drop whatever the mapping
  * forgot.
  *
  * Two traps live here:
  *
  *   - `PersistedDiagramState.source` is the diagram TEXT, not where it came
  *     from. The record spells it `text` and uses `origin` for provenance.
  *   - view state is stored in the `ElementRef` spelling (`node:a`, not `a`),
  *     because a `Set[String]` of bare ids cannot say whether `n1` meant the
  *     node or the group. The record tier already writes this spelling, so a
  *     headless `gx run hide` and a click in the UI produce the same bytes.
  */
object LibraryMapping:

  /** Ids the record carries that this viewer cannot parse are KEPT.
    *
    * A record may have been written by a newer `gx` that knows a kind this
    * build does not. Dropping the unknown ones would mean opening a diagram in
    * an older build silently deletes them on the next save.
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
      // Only groups can be collapsed; anything else in the set is a record
      // written by something that disagreed, and is preserved as unparsed
      // rather than coerced into a GroupId it is not.
      collapsedGroups = collapsed.collect { case g: GroupId => g },
      projectName = diagram.name,
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
      hiddenElements = state.hiddenElements.ids.map(ElementRef.render) ++ unparsed.hidden,
      collapsedGroups = state.collapsedGroups.map(ElementRef.render) ++ unparsed.collapsed,
      // Organisation is the record's, not the page's: the viewer has no UI for
      // tags or notes, and rebuilding metadata from the page alone would erase
      // whatever `gx run tag` put there.
      tags = previous.map(_.metadata.tags).getOrElse(Nil),
      notes = previous.map(_.metadata.notes).getOrElse(""),
      autoDetectFormat = state.autoDetectFormat
    )
    Diagram(
      id = id,
      name = state.projectName,
      folder = previous.map(_.folder).getOrElse(FolderPath.root),
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
