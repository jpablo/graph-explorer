package org.jpablo.graphexplorer.graphviz.metrics

/** Pure-Scala port of Graphviz's built-in text-width estimator
  * (`estimate_text_width_1pt` in lib/common/textspan_lut.c, gv 13.0.1).
  *
  * viz-js runs the WASM build with no real font system, so Graphviz always
  * falls back to these hard-coded metrics — which means matching this exactly
  * reproduces the oracle's node sizes. Data lives in the generated
  * [[FontMetricsTables]]; this is just the (trivial) lookup + sum loop.
  */
object FontMetrics:

  /** Canonicalise a font name the way Graphviz's permissive matcher does
    * (`font_name_equal_permissive`): case-insensitive, everything except
    * ASCII LETTERS ignored (`Times-Roman` ≡ `times roman` ≡ `tim8esroman`).
    */
  private def canon(s: String): String =
    s.toLowerCase.filter(c => c >= 'a' && c <= 'z')

  private val byCanonAlias: Map[String, FontFamilyMetrics] =
    FontMetricsTables.families.flatMap(f => f.names.map(n => canon(n) -> f)).toMap

  /** Graphviz default font family (`DEFAULT_FONTNAME = "Times-Roman"`). */
  private def timesFamily: FontFamilyMetrics =
    FontMetricsTables.families.head

  /** Family for `fontName`, falling back to Times — exactly Graphviz's
    * `get_metrics_for_font_family`: WHOLE-string permissive equality against
    * each family alias, nothing fuzzier. In particular a CSS-style font list
    * (`"Helvetica,Arial,sans-serif"`) matches NO alias — gv does not split
    * lists in the metrics path — and so falls back to Times, which is exactly
    * what the viz-js oracle sizes with.
    */
  def family(fontName: String): FontFamilyMetrics =
    byCanonAlias.getOrElse(canon(fontName), timesFamily)

  private def variant(f: FontFamilyMetrics, bold: Boolean, italic: Boolean): Array[Short] =
    (bold, italic) match
      case (true, true)  => f.boldItalic
      case (true, false) => f.bold
      case (false, true) => f.italic
      case _             => f.regular

  /** Width of `text` in points at a 1pt font size. Mirrors
    * `estimate_text_width_1pt`: non-ASCII → width of space; `-1` → `0`.
    */
  def estimateTextWidth1pt(
      fontName: String,
      text:     String,
      bold:     Boolean = false,
      italic:   Boolean = false
  ): Double =
    val fam = family(fontName)
    val w   = variant(fam, bold, italic)
    var sum = 0
    var i   = 0
    while i < text.length do
      val ch       = text.charAt(i)
      val code     = if ch >= 128 then ' '.toInt else ch.toInt
      val rawWidth = w(code).toInt
      sum += (if rawWidth == -1 then 0 else rawWidth)
      i += 1
    sum.toDouble / fam.unitsPerEm

end FontMetrics
