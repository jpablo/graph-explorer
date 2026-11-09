package org.jpablo.graphexplorer.gxcore.store

/** The JSON of an old record, in the shape that the old writer gave.
  *
  * Only tests write an old record now. The writer gives each key that the old
  * writer gave, so that a test reads what an old library on the disk holds
  * (`LegacyRecordSpec` pins the shape).
  */
object OldRecordJson:

  private[store] def of(r: LegacyRecord): String =
    val m = r.metadata
    val metadata = ujson.Obj()
    if m.hiddenElements.nonEmpty then metadata("hiddenElements") = ujson.Arr.from(m.hiddenElements.toSeq.sorted)
    if m.collapsedGroups.nonEmpty then metadata("collapsedGroups") = ujson.Arr.from(m.collapsedGroups.toSeq.sorted)
    if m.tags.nonEmpty then metadata("tags") = ujson.Arr.from(m.tags)
    if m.notes.nonEmpty then metadata("notes") = m.notes
    m.autoDetectFormat.foreach(on => metadata("autoDetectFormat") = on)
    ujson.write(
      ujson.Obj(
        "id"     -> ujson.Obj("value" -> r.id),
        "name"   -> r.name,
        "folder" -> ujson.Obj("segments" -> ujson.Arr.from(r.folder.segments)),
        "format" -> r.format,
        "text"   -> r.text,
        "binding" -> r.origin.fold(ujson.Null): origin =>
          ujson.Obj("origin" -> origin, "mode" -> "Sync", "baseHash" -> "00", "lastSyncAt" -> 1),
        "metadata"  -> metadata,
        "createdAt" -> r.createdAt.toDouble,
        "updatedAt" -> r.updatedAt.toDouble
      ),
      indent = 2
    )
