package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.{StrictSignal, Var}
import org.jpablo.graphexplorer.gxcore.command.ElementRef
import org.jpablo.graphexplorer.gxcore.model.DiagramMetadata
import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds, GroupId}

/** The metadata of the diagram on the screen, and the typed views over it.
  *
  * ONE `Var` holds the metadata. Each view below is a zoom of that `Var`, and
  * not a second copy. Thus a change from a different writer replaces one value,
  * and each view shows it.
  *
  * The metadata keeps each element in the `ElementRef` spelling (`node:a`). A
  * view parses the refs that this build knows. A setter of a view writes the
  * refs that the view shows, and it KEEPS each ref that the view does not show.
  * A newer `gx` can write a ref kind that this build does not know. If a setter
  * drops that ref, the next edit in this viewer deletes it with no message.
  *
  * Each view is `distinct`, so a change to one field does not wake the readers
  * of a different field. A `distinct` view reads a fresh value only while its
  * signal runs. Thus each view keeps one observer for the life of `owner`.
  * Without it, `now()` and `update` read the value from before a different
  * writer replaced the metadata, and an update then reverts that change.
  */
final class MetadataOps(val metadata: Var[DiagramMetadata])(using owner: Owner):

  val signal: StrictSignal[DiagramMetadata] = metadata.signal

  def now(): DiagramMetadata = metadata.now()

  /** Replace all of the metadata, for example with the metadata of a different writer. */
  def set(next: DiagramMetadata): Unit = metadata.set(next)

  val hiddenElements: Var[ElementIds] =
    metadata.zoomLazy(MetadataOps.hiddenIn)(MetadataOps.withHidden).distinct

  /** Only a group can collapse. Thus this view shows only the group refs, and
    * its setter keeps each other ref in the set.
    */
  val collapsedGroups: Var[Set[GroupId]] =
    metadata.zoomLazy(MetadataOps.collapsedIn)(MetadataOps.withCollapsed).distinct

  /** `None` is the value in each diagram from before the mode existed, and the
    * viewer reads it as "on". A setter always writes `Some`.
    */
  val autoDetectFormat: Var[Boolean] =
    metadata.zoomLazy(_.autoDetectFormat.getOrElse(true))((m, on) => m.copy(autoDetectFormat = Some(on))).distinct

  // Keep each view running, so that its `now()` is fresh (see the class comment).
  List[Var[?]](hiddenElements, collapsedGroups, autoDetectFormat).foreach(_.signal.foreach(_ => ()))

end MetadataOps

/** The parse and render rules of the typed views. The browser library uses
  * the same rules for its stored form (`DiagramSnapshot`).
  */
object MetadataOps:

  /** The hidden elements that this build can parse. */
  def hiddenIn(metadata: DiagramMetadata): ElementIds =
    ElementIds(known(metadata.hiddenElements))

  /** Write `ids` as the hidden elements, and keep each ref that does not parse. */
  def withHidden(metadata: DiagramMetadata, ids: ElementIds): DiagramMetadata =
    metadata.copy(hiddenElements = render(ids) ++ metadata.hiddenElements.filter(ref => parse(ref).isEmpty))

  /** The collapsed groups that this build can parse. A ref of a different kind
    * comes from a writer that disagrees. This rule does not change it into a
    * group.
    */
  def collapsedIn(metadata: DiagramMetadata): Set[GroupId] =
    known(metadata.collapsedGroups).collect { case g: GroupId => g }

  /** Write `groups` as the collapsed groups, and keep each ref that is not a group. */
  def withCollapsed(metadata: DiagramMetadata, groups: Set[GroupId]): DiagramMetadata =
    metadata.copy(
      collapsedGroups = groups.map(ElementRef.render) ++ metadata.collapsedGroups.filterNot(ref => parse(ref).exists(_.isGroupId))
    )

  def render(ids: ElementIds): Set[String] =
    ids.ids.map(id => ElementRef.render(id))

  private def known(refs: Set[String]): Set[ElementId] =
    refs.flatMap(parse)

  private def parse(ref: String): Option[ElementId] =
    ElementRef.parse(ref).toOption
