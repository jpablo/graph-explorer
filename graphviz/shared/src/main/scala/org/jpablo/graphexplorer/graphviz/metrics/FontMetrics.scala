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

  private val tableAliases: Map[String, FontFamilyMetrics] =
    FontMetricsTables.families.flatMap(f => f.names.map(n => canon(n) -> f)).toMap

  /** The Courier family. The lookup uses the alias, not the list index. */
  private val courierFamily: FontFamilyMetrics =
    tableAliases.getOrElse(
      "courier",
      throw IllegalStateException("FontMetricsTables has no family with the alias \"courier\"")
    )

  /** Monospaced font names that get the Courier metrics.
    *
    * This list is a departure from the Graphviz estimator. We add it on
    * purpose. Graphviz gives the Times metrics to each font name that its
    * tables do not know. Real Graphviz with Pango measures the real font, so
    * it does not have this problem. The browser draws the real font. A
    * monospaced font such as Menlo is about 0.6 em wide for each character,
    * as Courier is. Times is much narrower. Thus, without this list, the text
    * goes past the border of the node, and the edge labels overlap.
    *
    * The names are in the `canon` form: lowercase ASCII letters only. The
    * rule is the same as for the table aliases: the WHOLE name must be equal.
    * A CSS font list such as `"Menlo, monospace"` is not equal to a name in
    * this list, so it gets the Times metrics, as in Graphviz. A name that the
    * tables know (for example `consolas`) keeps the metrics of the tables.
    */
  private val monospacedAliases: Set[String] = Set(
    "menlo",
    "monaco",
    "sfmono",
    "sfmonoregular",
    "uimonospace",
    "monospace",
    "dejavusansmono",
    "firacode",
    "firamono",
    "jetbrainsmono",
    "sourcecodepro",
    "ubuntumono",
    "robotomono",
    "cascadiacode",
    "cascadiamono",
    "andalemono",
    "lucidaconsole",
    "ibmplexmono",
    "inconsolata",
    "hack"
  )

  /** The table aliases come last, so they win over a monospaced alias. */
  private val byCanonAlias: Map[String, FontFamilyMetrics] =
    monospacedAliases.map(_ -> courierFamily).toMap ++ tableAliases

  /** True when `fontName` gets the Courier metrics through
    * [[monospacedAliases]]. The rule is the same as for [[family]]: the
    * `canon` form of the WHOLE name must be equal to an alias. A name that
    * the tables know gives false. The SVG writer uses this method to add a
    * `monospace` fallback to the `font-family` attribute.
    */
  def isMonospacedAlias(fontName: String): Boolean =
    val c = canon(fontName)
    monospacedAliases.contains(c) && !tableAliases.contains(c)

  /** Graphviz default font family (`DEFAULT_FONTNAME = "Times-Roman"`). */
  private def timesFamily: FontFamilyMetrics =
    FontMetricsTables.families.head

  /** Family for `fontName`, falling back to Times — Graphviz's
    * `get_metrics_for_font_family`, plus the [[monospacedAliases]] (a
    * deliberate departure): WHOLE-string permissive equality against
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
    * `estimate_text_width_1pt`, which walks the UTF-8 BYTES (`(unsigned
    * char)*c`): every byte ≥ 128 counts as one space width — so a 2-byte
    * char like `«` contributes TWO space widths; `-1` → `0`.
    */
  def estimateTextWidth1pt(
      fontName: String,
      text:     String,
      bold:     Boolean = false,
      italic:   Boolean = false
  ): Double =
    val fam   = family(fontName)
    val w     = variant(fam, bold, italic)
    val bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    var sum = 0
    var i   = 0
    while i < bytes.length do
      val code     = bytes(i) & 0xff
      val rawWidth = w(if code >= 128 then ' '.toInt else code).toInt
      sum += (if rawWidth == -1 then 0 else rawWidth)
      i += 1
    sum.toDouble / fam.unitsPerEm

end FontMetrics
