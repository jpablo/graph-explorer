package org.jpablo.graphexplorer.viewer.formats.dot

/** The ONE html-ness predicate for label values.
  *
  * DOT's `label=<...>` html flag is authoritative in the engine's lexer but
  * does not survive the dot_json import (the label arrives as a bare string),
  * so the viewer must sniff content. Before this object, three heuristics
  * disagreed: two newline-blind regexes in the UI (a multi-line `<table>`
  * label was NOT treated as html — and got its newlines escaped to literal
  * `\n` by the label dialog, corrupting it) and a case-sensitive substring
  * check in the DOT serializer (uppercase `<TABLE>` slipped through unless it
  * had a closing tag). Every consumer now delegates here.
  */
object HtmlLabels:

  /** True when a label value should be serialized in DOT's `<...>` html form
    * and edited as markup. Newline-tolerant and case-insensitive.
    */
  def isHtml(value: String): Boolean =
    val v = value.toLowerCase
    v.contains("<") && v.contains(">") &&
      (v.contains("<table") || v.contains("<b>") || v.contains("<i>") ||
        v.contains("<font") || v.contains("<br") || v.contains("<hr") ||
        v.contains("<td") || v.contains("<tr") || v.contains("</"))
