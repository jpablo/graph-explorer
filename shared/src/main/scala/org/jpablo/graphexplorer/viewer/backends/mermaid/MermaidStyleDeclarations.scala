package org.jpablo.graphexplorer.viewer.backends.mermaid

import scala.collection.immutable.VectorMap

object MermaidStyleDeclarations:

  /** Parses Mermaid CSS-like declarations into a normalized map.
    *
    * Input declarations use Mermaid's comma-separated `key:value` format.
    *
    * Normalization:
    *   - trim whitespace around key/value
    *   - lowercase keys
    *   - keep value casing
    *   - split only on first `:`
    *   - ignore fragments without `:`
    *   - last key occurrence wins
    */
  def parse(styleText: String): VectorMap[String, String] =
    parse(styleText.split(",").toIndexedSeq)

  /** Parses declarations when already split into Mermaid style fragments. */
  def parse(fragments: Seq[String]): VectorMap[String, String] =
    fragments.foldLeft(VectorMap.empty[String, String]) { (acc, rawFragment) =>
      val fragment = rawFragment.trim
      val separatorIdx = fragment.indexOf(':')
      if separatorIdx < 0 then acc
      else
        val key = fragment.substring(0, separatorIdx).trim.toLowerCase
        if key.isEmpty then acc
        else
          val value = fragment.substring(separatorIdx + 1).trim
          acc + (key -> value)
    }
