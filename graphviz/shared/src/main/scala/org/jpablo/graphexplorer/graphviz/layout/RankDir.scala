package org.jpablo.graphexplorer.graphviz.layout

/** `GD_rankdir` — the rank axis orientation, mirroring `const.h`
  * `RANKDIR_TB=0 / LR=1 / BT=2 / RL=3`. The integer ordinal is preserved
  * exactly because the layout pipeline (and `Rank.flip`, `Coord.rankY`'s
  * comments) reasons in terms of `rd == 1 || rd == 3`.
  */
enum RankDir(val ord: Int) derives CanEqual:
  case TB extends RankDir(0)
  case LR extends RankDir(1)
  case BT extends RankDir(2)
  case RL extends RankDir(3)

  /** True for LR/RL — the rank axis is horizontal, so layout-orientation
    * node sizes are width-height swapped. */
  def isFlipped: Boolean = this == LR || this == RL

object RankDir:
  /** Parse the `rankdir` attribute value (case-insensitive). Anything else
    * — missing, malformed, empty — falls to TB, the Graphviz default. */
  def fromAttr(s: Option[String]): RankDir = s.map(_.toUpperCase) match
    case Some("LR") => LR
    case Some("BT") => BT
    case Some("RL") => RL
    case _          => TB
