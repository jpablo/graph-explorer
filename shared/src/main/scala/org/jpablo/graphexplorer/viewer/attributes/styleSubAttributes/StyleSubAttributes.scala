package org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import StyleSubAttributes.missing
import scala.collection.mutable.ArrayBuffer

/** Provides "virtual" attributes that correspond to sub-attributes of the "style" attribute.
  *
  * The "style" attribute is a comma-separated list of sub-attributes, which complicates its usage in the application.
  *
  * These attributes are not part of the dot language.
  */
case class StyleSubAttributes(
    // automatically set based on the FillColor attribute
    fill: AttrStatus[Boolean] = Missing,
    // bold is not used but kept for compatibility
    bold:      AttrStatus[Boolean] = Missing,
    invisible: AttrStatus[Boolean] = Missing,
    border:    AttrStatus[BorderStyle] = Missing,
    corner:    AttrStatus[CornerStyle] = Missing
) derives CanEqual:

  /** If an attribute is missing in this instance, the value from the other instance is used.
    *
    * @param other
    *   the other StyleSubAttributes instance to be combined with this instance
    * @return
    *   a new StyleSubAttributes instance representing the combined attributes
    */
  def combine(other: StyleSubAttributes): StyleSubAttributes =
    StyleSubAttributes(
      fill = fill.orElse(other.fill),
      bold = bold.orElse(other.bold),
      invisible = invisible.orElse(other.invisible),
      border = border.orElse(other.border),
      corner = corner.orElse(other.corner)
    )

  private def toKV[A](attrId: AttributeId, status: AttrStatus[A])(using
      CanEqual[A, A]
  ): Option[(AttributeId, AttrValue)] =
    status match
      case Single(b) => Some(attrId -> AttrValue(b.toString))
      case _         => None

  def toAttributes: Attributes =
    Attributes(
      Seq(
        toKV(FillStyle.attrId, fill),
        toKV(BoldStyle.attrId, bold),
        toKV(InvisibleStyle.attrId, invisible),
        toKV(BorderStyle.attrId, border),
        toKV(CornerStyle.attrId, corner)
      ).flatten.toMap
    )

  /** Converts the current sub-attribute configuration into a simple style string representation. The resulting string is a comma-separated
    * list of individual style components. This method works in "simple" mode, meaning it is used only when the style is not a combination
    * of multiple styles.
    *
    * @return
    *   A comma-separated string of style components, including fill, bold, invisible attributes, and non-default border and corner styles.
    */
  /** Renders the sub-attributes as a comma-separated `style` value.
    *
    * @param dropDefaults
    *   when true, corner/border tokens equal to their defaults are omitted (element style, which inherits from emitted defaults); when
    *   false every Single value is emitted (the default attribute statements themselves).
    */
  private def styleTokens(dropDefaults: Boolean): Option[String] =
    if this == missing then None
    else
      val parts = ArrayBuffer.empty[String]
      if fill.is(true) then parts += FillStyle.filled
      if bold.is(true) then parts += BoldStyle.bold
      if invisible.is(true) then parts += InvisibleStyle.invis

      corner match
        case Single(c) if !dropDefaults || c != CornerStyle.default => parts += c.toString
        case _                                                      =>

      border match
        case Single(b) if !dropDefaults || b != BorderStyle.default => parts += b.toString
        case _                                                      =>

      // A non-`missing` StyleSubAttributes can legitimately emit no tokens (e.g. only fill=false).
      if parts.isEmpty then None else Some(parts.mkString(","))

  /** Style string for default attribute statements: every Single value is emitted. */
  def toStyleStringNoDefaults: Option[String] = styleTokens(dropDefaults = false)

  /** Style string for elements: emits only explicit, non-default tokens. */
  def toStyleStrings: Option[String] = styleTokens(dropDefaults = true)



object StyleSubAttributes:

  val subAttributeIds =
    Set(FillStyle, BoldStyle, InvisibleStyle, BorderStyle, CornerStyle).map(_.attrId)

  val default = StyleSubAttributes(
    fill = Single(FillStyle.default),
    bold = Single(BoldStyle.default),
    invisible = Single(InvisibleStyle.default),
    border = Single(BorderStyle.default),
    corner = Single(CornerStyle.default)
  )

  val missing = StyleSubAttributes()

  /** Extracts a `StyleSubAttributes` instance from the given `Attributes` by mapping relevant sub-attributes of the "style" attribute to
    * their corresponding properties.
    *
    * @param attrs
    *   the `Attributes` instance containing sub-attributes STRINGS from which `StyleSubAttributes` will be constructed
    * @return
    *   a `StyleSubAttributes` instance with properties derived from the input attributes
    */
  def fromExpandedAttributes(attrs: Attributes): StyleSubAttributes =
    val borderStr = attrs.get(BorderStyle).map(_.toString)
    val cornerStr = attrs.get(CornerStyle).map(_.toString)
    val border    = BorderStyle.values.find(style => borderStr.contains(style.toString))
    val corner    = CornerStyle.values.find(style => cornerStr.contains(style.toString))

    // Do not infer fill=true from fillcolor here. Normalization to true happens on update paths.
    // However, if an explicit transparent color is set, reflect that as fill=false even if FillStyle=true.
    val fillFromStyle = attrs.get(FillStyle).map(_.isTrue)
    val fillIsNone =
      fillFromStyle.contains(true) && (
        attrs.get(FillColor).exists(_.toString == FillColor.none) ||
        attrs.get(Color).exists(_.toString == "none")
      )

    val fillStatus =
      if fillIsNone then Single(false)
      else fillFromStyle.fold(Missing)(Single(_))

    StyleSubAttributes(
      fill = fillStatus,
      bold = attrs.get(BoldStyle).map(_.isTrue).fold(Missing)(Single(_)),
      invisible = attrs.get(InvisibleStyle).map(_.isTrue).fold(Missing)(Single(_)),
      border = border.map(Single(_)).getOrElse(Missing),
      corner = corner.map(Single(_)).getOrElse(Missing)
    )

  /** Parses a style attribute string into a `StyleSubAttributes` instance, representing the individual sub-attributes derived from the
    * comma-separated "style" attribute string. Handles sub-attributes such as `filled`, `bold`, `invisible`, and specific `border` and
    * `corner` styles.
    *
    * @param attrValue
    *   The `AttrValue` representing the "style" attribute string. It is expected to contain a comma-separated list of style attributes,
    *   which may include keywords like "filled", "bold", "invis", and named border or corner styles.
    * @return
    *   A `StyleSubAttributes` instance with each property mapped to its corresponding sub-attribute from the input string. If no valid
    *   sub-attribute is found, defaults (e.g., `Missing`) will be assigned.
    */
  def fromStyleString(styleStr: Option[String]): StyleSubAttributes =
    // viz-js will transform style="" (RESET) into an element WITHOUT a style attribute.
    // So the case Some("") is redundant. Keeping it for clarity.
    styleStr match
      // No style specified, missing, that will be interpreted as the harc-coded default
      case None | Some("") => missing
      case Some(str) =>
        val parts = str.split(",").map(_.trim).filterNot(_.isEmpty).toSet
        if parts.isEmpty then
          // https://graphviz.org/docs/attrs/style/
          // "If the default style attribute has been set for a component,
          // an individual component can use style="" to revert to the normal default."
          missing
        else
          StyleSubAttributes(
            fill = singleOrMissing(NodeStyle.filled.toString in parts),
            bold = singleOrMissing(NodeStyle.bold.toString in parts),
            invisible = singleOrMissing(NodeStyle.invis.toString in parts),
            border = BorderStyle.values.find(_.toString in parts).map(Single(_)).getOrElse(Missing),
            corner = CornerStyle.values.find(_.toString in parts).map(Single(_)).getOrElse(Missing)
          )

  // This is used at a phase when there are no global defaults.
  def removeIncorrectCombos(attrs: Attributes, subAttrs: StyleSubAttributes): Attributes =
    val fillColor = attrs.get(FillColor)
    // It doesn't make sense to have a fill color without the fill style,
    // because Graphviz will just ignore it. Let's remove it here to avoid confusion.
    if fillColor.isDefined && !subAttrs.fill.is(true) then
      attrs - FillColor
    else
      attrs

  private inline def singleOrMissing(value: Boolean): AttrStatus[Boolean] =
    if value then Single(true) else Missing
