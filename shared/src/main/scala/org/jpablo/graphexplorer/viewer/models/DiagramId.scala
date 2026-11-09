package org.jpablo.graphexplorer.viewer.models

import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe
import upickle.default.ReadWriter

/** The primary key of a diagram in the library.
  *
  * The key is stable and internal. It does not come from a value that can
  * change. Thus the key is not the origin URI: a diagram that the app makes has
  * no origin, one file can be the origin of many diagrams, and a person can
  * rename a file (§3.1). The library indexes the origin as an attribute. The
  * watch registry uses the URI as a key, because there one key for two
  * spellings of a file is correct.
  *
  * The viewer, `gx-core` and `gx` use this one type. The JSON form is
  * `{"value": "..."}` in `localStorage` and on disk, so the field name must
  * not change.
  */
final case class DiagramId(value: String) derives ReadWriter, CanEqual

object DiagramId:

  /** A new id for a diagram that the app makes. */
  def random: DiagramId = DiagramId(randomUUIDSafe())

  /** An id that comes from stable external text.
    *
    * The migration uses this. When the migration runs again, an old browser
    * diagram gets the same id, so the library gets no second copy. The id
    * gives this result by its construction, and no lookup can race it.
    */
  def derivedFrom(namespace: String, key: String): DiagramId =
    DiagramId(s"$namespace-$key")
