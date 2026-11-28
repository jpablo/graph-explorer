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

  /** The id of the renderer that the person selected (`Renderer.id`). `None` is
    * the renderer of the language of the source.
    */
  val renderer: Var[Option[String]] =
    metadata.zoomLazy(_.renderer)((m, id) => m.copy(renderer = id)).distinct

  /** The id of the extractor that the person selected (`Extractor.id`). `None`:
    * the app finds the extractor from the content, or else from the file name.
    */
  val extractor: Var[Option[String]] =
    metadata.zoomLazy(_.extractor)((m, id) => m.copy(extractor = id)).distinct

  /** True when the person told the app not to write to the data source. */
  val locked: Var[Boolean] =
    metadata.zoomLazy(_.locked)((m, on) => m.copy(locked = on)).distinct

  /** The tags of the diagram, in the order that the person added them. `gx tag`
    * writes the same list.
    */
  val tags: Var[List[String]] =
    metadata.zoomLazy(_.tags)((m, ts) => m.copy(tags = ts)).distinct

  /** The notes of the diagram: free text. An empty text is no note. */
  val notes: Var[String] =
    metadata.zoomLazy(_.notes)((m, text) => m.copy(notes = text)).distinct

  /** Add the tag that the person typed ([[MetadataOps.withTag]]). */
  def addTag(typed: String): Unit = tags.update(MetadataOps.withTag(_, typed))

  def removeTag(tag: String): Unit = tags.update(_.filterNot(_ == tag))

  // Keep each view running, so that its `now()` is fresh (see the class comment).
  List[Var[?]](hiddenElements, collapsedGroups, autoDetectFormat, renderer, extractor, locked, tags, notes)
    .foreach(_.signal.foreach(_ => ()))

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

  /** True when the view of the diagram is not the drawing of its source: an element is hidden, or a group is
    * collapsed. Then a renderer that can draw the source draws the visible graph. The view and the thumbnail of the
    * library both use this rule.
    */
  def viewDiffersFromSource(metadata: DiagramMetadata): Boolean =
    hiddenIn(metadata).nonEmpty || collapsedIn(metadata).nonEmpty

  /** `tags` with the tag that the person typed at the end. The rule trims the
    * text. It ignores an empty text and a tag that `tags` has, as `gx tag`
    * does. The comparison is exact: `Infra` and `infra` are two tags.
    */
  def withTag(tags: List[String], typed: String): List[String] =
    val tag = typed.trim
    if tag.isEmpty || tags.contains(tag) then tags else tags :+ tag

  def render(ids: ElementIds): Set[String] =
    ids.ids.map(id => ElementRef.render(id))

  private def known(refs: Set[String]): Set[ElementId] =
    refs.flatMap(parse)

  private def parse(ref: String): Option[ElementId] =
    ElementRef.parse(ref).toOption
