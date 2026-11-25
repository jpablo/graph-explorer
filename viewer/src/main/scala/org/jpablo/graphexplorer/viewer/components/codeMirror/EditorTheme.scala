package org.jpablo.graphexplorer.viewer.components.codeMirror

import scala.scalajs.js

/** The colors of the source editor.
  *
  * CodeMirror has a light default theme, and it knows nothing about the theme
  * of the app. With no theme of its own, the editor showed a light gutter, a
  * light selection and dark blue text in each dark theme of the app.
  *
  * Each color here is a CSS expression on the daisyUI color tokens
  * (`--color-base-100`, `--color-primary`, …). The browser resolves the tokens
  * from the `data-theme` in effect. Thus one theme is correct for each theme
  * of the app, and a theme change needs no change of the editor.
  *
  * No color is a fixed value, and no rule reads the name of a theme. A color
  * of a surface is a mix of tokens. A color of a token has the hue of a
  * semantic token, at a lightness that is readable in the color scheme of the
  * theme (see `ink`).
  */
private[components] object EditorTheme:

  private val base100 = "var(--color-base-100)"
  private val base200 = "var(--color-base-200)"
  private val base300 = "var(--color-base-300)"
  private val content = "var(--color-base-content)"

  /** `percent` of `color`, and `other` for the remainder. */
  private def mix(color: String, percent: Int, other: String = "transparent"): String =
    s"color-mix(in oklab, $color $percent%, $other)"

  /** A text color with the hue and the chroma of a semantic token.
    *
    * The semantic tokens of daisyUI are colors for surfaces. In some themes
    * `primary` or `accent` is too pale for text on `base-100`: the `accent` of
    * `signal` is a yellow, and its `base-100` is white. So the lightness has
    * a limit: a maximum in a light color scheme, and a minimum in a dark one.
    * A token that is in the limit keeps its exact color, which is the case
    * for the app themes `workbench` and `drafting`.
    *
    * With these limits, each token color has a contrast of 4.5 or more on
    * `base-100` in each theme of `daisyThemes`, and of 3 or more in a
    * selection.
    *
    * A browser with no `light-dark()` or no relative color ignores the
    * declaration. The token then has the color of the text.
    */
  private def ink(token: String): String =
    val color = s"var(--color-$token)"
    s"light-dark(oklch(from $color min(l, 0.45) c h), oklch(from $color max(l, 0.8) c h))"

  /** Text of less importance: comments and punctuation. */
  private val muted = mix(content, 85)

  /** Text at the edge of the editor: line numbers and placeholders. */
  private val faint = mix(content, 70)

  /** The selection has the hue of `primary`. The part of `base-content` keeps
    * it visible in a theme with a pale `primary`.
    */
  private val selection = mix(mix("var(--color-primary)", 60, content), 30)

  private val hairline = s"1px solid $base300"

  private def css(properties: (String, js.Any)*): js.Any = js.Dictionary(properties*)

  /** The surfaces of the editor.
    *
    * CodeMirror gives each light default the selector `&light …`, and this
    * theme does not set the dark flag. A rule here has the same specificity
    * as the `&light` rule that it replaces, and a theme comes after the base
    * theme in the stylesheet, so the rule here wins. Thus each `&light` rule
    * of CodeMirror with a color needs a rule here. The selection rule below
    * repeats the long selector of CodeMirror for the same reason.
    */
  private val surfaces: Extension =
    EditorView.theme(
      js.Dictionary[js.Any](
        "&"           -> css("color" -> content),
        ".cm-content" -> css("caretColor" -> content),
        ".cm-cursor, .cm-dropCursor" -> css("borderLeftColor" -> content),
        // The gutter stays in position when the text scrolls to the side, so
        // it needs an opaque background. It has the surface of the toolbar.
        ".cm-gutters" -> css(
          "backgroundColor" -> base200,
          "color"           -> faint,
          "borderRight"     -> hairline
        ),
        ".cm-activeLineGutter" -> css("backgroundColor" -> mix(content, 10), "color" -> content),
        // The selection is in a layer behind the lines. A translucent active
        // line lets the selection show through.
        ".cm-activeLine" -> css("backgroundColor" -> mix(content, 6)),
        "&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground" ->
          css("backgroundColor" -> selection),
        ".cm-content ::selection" -> css("backgroundColor" -> selection),
        ".cm-selectionMatch"      -> css("backgroundColor" -> mix(content, 14)),
        ".cm-searchMatch"         -> css("backgroundColor" -> mix("var(--color-warning)", 35)),
        ".cm-searchMatch-selected" -> css("backgroundColor" -> mix("var(--color-warning)", 65)),
        ".cm-foldPlaceholder" -> css(
          "backgroundColor" -> mix(content, 12),
          "border"          -> "none",
          "color"           -> faint
        ),
        ".cm-placeholder"  -> css("color" -> faint),
        ".cm-specialChar"  -> css("color" -> ink("error")),
        ".cm-snippetField" -> css("backgroundColor" -> mix(content, 14)),
        // The search panel and the completion list.
        ".cm-panels"        -> css("backgroundColor" -> base200, "color" -> content),
        ".cm-panels-top"    -> css("borderBottom" -> hairline),
        ".cm-panels-bottom" -> css("borderTop" -> hairline),
        ".cm-textfield"     -> css("backgroundColor" -> base100, "border" -> hairline),
        ".cm-button" -> css(
          "backgroundImage" -> "none",
          "backgroundColor" -> base300,
          "border"          -> hairline,
          "color"           -> content,
          "&:active"        -> css("backgroundImage" -> "none", "backgroundColor" -> mix(content, 25, base300))
        ),
        ".cm-tooltip" -> css("backgroundColor" -> base100, "border" -> hairline, "color" -> content),
        ".cm-tooltip-section:not(:first-child)" -> css("borderTop" -> hairline),
        ".cm-tooltip-autocomplete ul li[aria-selected]" -> css(
          "background" -> "var(--color-primary)",
          "color"      -> "var(--color-primary-content)"
        ),
        ".cm-tooltip-autocomplete-disabled ul li[aria-selected]" -> css("background" -> mix(content, 30))
      )
    )

  /** The colors of the tokens. Three hues show the three roles in a diagram
    * source: structure (keywords), names of properties, and values (strings
    * and numbers). The name of a node and an operator keep the color of the
    * text, because they are most of a source.
    *
    * `literal` is the tag that the DOT grammar gives to a bare word that is
    * not the name of a node. In practice this is the name of an attribute, the
    * name of the graph, or a value with no quotes (`shape = box`). The grammar
    * has a tag for the name of an attribute, but its selector does not match
    * the syntax tree (@viz-js/lang-dot 1.0.4), so `propertyName` applies only
    * to the HTML of an HTML label.
    */
  private val tokens: Extension =
    def style(tag: Tag, properties: (String, js.Any)*): js.Object =
      js.Dictionary[js.Any]((("tag" -> tag) +: properties)*).asInstanceOf[js.Object]

    language.syntaxHighlighting(
      language.HighlightStyle.define(
        js.Array(
          style(tags.keyword, "color"      -> ink("primary")),
          style(tags.typeName, "color"     -> ink("primary")),
          style(tags.propertyName, "color" -> ink("secondary")),
          style(tags.literal, "color"      -> ink("secondary")),
          style(tags.string, "color"       -> ink("accent")),
          style(tags.number, "color"       -> ink("accent")),
          style(tags.comment, "color"      -> muted, "fontStyle" -> "italic"),
          style(tags.meta, "color"         -> muted),
          style(tags.punctuation, "color"  -> muted),
          style(tags.invalid, "color"      -> ink("error"))
        )
      )
    )

  val extension: Extension = js.Array[Any](surfaces, tokens)
