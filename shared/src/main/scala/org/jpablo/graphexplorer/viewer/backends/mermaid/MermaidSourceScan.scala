package org.jpablo.graphexplorer.viewer.backends.mermaid

/** Shared source-text scanning helpers for the Mermaid*Fallback parsers, so the
  * directive-skip rules and label normalization cannot drift between fallbacks
  * (the whole-word prefix fix once had to be applied to two identical copies).
  */
private[mermaid] object MermaidSourceScan:

  private val IgnoredLinePrefixes = List(
    "%%",
    "flowchart",
    "graph",
    "subgraph",
    "classdef",
    "class ",
    "style ",
    "linkstyle",
    "click ",
    "end"
  )

  def isIgnoredLine(line: String): Boolean =
    val lower = line.toLowerCase
    IgnoredLinePrefixes.exists(prefix => isIgnoredPrefix(lower, prefix))

  // A directive keyword only ignores a line when it appears as a WHOLE word, so a node
  // or edge id that merely starts with one (e.g. `graphState[...]`, `endNode --> B`) is
  // not dropped. Prefixes already ending in a space, and the `%%` comment marker, keep
  // plain startsWith semantics.
  private def isIgnoredPrefix(lower: String, prefix: String): Boolean =
    if prefix.endsWith(" ") || prefix == "%%" then lower.startsWith(prefix)
    else lower == prefix || (lower.startsWith(prefix) && !isIdentifierChar(lower.charAt(prefix.length)))

  def isIdentifierChar(c: Char): Boolean =
    c.isLetterOrDigit || c == '_' || c == '-'

  /** Strip wrapping quotes and decode the `#quot;` escape used by the Mermaid writer. */
  def normalizeLabel(raw: String): String =
    val trimmed = raw.trim
    val unquoted =
      if trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
      then trimmed.substring(1, trimmed.length - 1)
      else trimmed
    unquoted.replace("#quot;", "\"").trim
