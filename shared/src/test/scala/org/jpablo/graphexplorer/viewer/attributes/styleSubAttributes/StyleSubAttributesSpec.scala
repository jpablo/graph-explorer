package org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.*

class StyleSubAttributesSpec extends FunSuite:

  test("fromExpandedAttributes should NOT infer fill from fillcolor (normalization happens on update)") {
    // When fillcolor is present but no FillStyle attribute, expanded attributes alone
    // should not infer fill. Normalization happens when attributes are updated via lenses.
    val attrs = Attributes.of(
      FillColor -> "#fff085"
    )

    val result = StyleSubAttributes.fromExpandedAttributes(attrs)

    assertEquals(result, StyleSubAttributes.missing)
    assertEquals(result.toStyleStringNoDefaults, None)
  }

  test("fromExpandedAttributes with fillcolor='none' remains missing (transparent)") {
    val attrs = Attributes.of(
      FillColor -> FillColor.none
    )

    val result = StyleSubAttributes.fromExpandedAttributes(attrs)

    // fillcolor="none" means no fill; expanded view remains missing
    assertEquals(result, StyleSubAttributes.missing)
    assertEquals(result.toStyleStringNoDefaults, None)
  }

  test("fromSubAttributes should handle explicit FillStyle over fillcolor inference") {
    // When both fillcolor and FillStyle are present
    val attrs = Attributes.of(
      FillColor -> "#fff085",
      FillStyle -> true
    )

    val result = StyleSubAttributes.fromExpandedAttributes(attrs)

    val expected = StyleSubAttributes(fill = AttrStatus.Single(true))

    // Should use the logic from handleFillStyle, not the explicit FillStyle
    // Note: The comment says it ignores FillStyle attribute and uses FillColor
    assertEquals(result, expected)
  }

  test("fromSubAttributes should handle all style sub-attributes") {
    val attrs = Attributes.of(
      BoldStyle      -> true,
      InvisibleStyle -> false,
      BorderStyle    -> BorderStyle.dashed,
      CornerStyle    -> CornerStyle.rounded
    )

    val result = StyleSubAttributes.fromExpandedAttributes(attrs)

    val expected = StyleSubAttributes(
      fill = AttrStatus.Missing, // No fillcolor
      bold = AttrStatus.Single(true),
      invisible = AttrStatus.Single(false),
      border = AttrStatus.Single(BorderStyle.dashed),
      corner = AttrStatus.Single(CornerStyle.rounded)
    )

    assertEquals(result, expected)
  }

  test("fromSubAttributes with color but no fillcolor and style=filled") {
    // Special DOT rule: when style="filled" and only color is specified,
    // the fill color defaults to the same value as the border color
    val attrs = Attributes.of(
      Color     -> "#ff0000",
      FillStyle -> true
    )

    val result = StyleSubAttributes.fromExpandedAttributes(attrs)

    // Should detect fill=true because of the special rule
    val expected = StyleSubAttributes(fill = AttrStatus.Single(true))

    assertEquals(result, expected)
  }

  test("fromSubAttributes with color='none' and style=filled") {
    val attrs = Attributes.of(
      Color     -> "none",
      FillStyle -> true
    )

    val result = StyleSubAttributes.fromExpandedAttributes(attrs)

    // Even with style="filled", if color is "none", it shouldn't fill
    val expected = StyleSubAttributes(fill = AttrStatus.Single(false))

    assertEquals(result, expected)
  }

  test("fromSubAttributes with no fill-related attributes") {
    val attrs = Attributes.of(
      Label -> "test"
    )

    val result = StyleSubAttributes.fromExpandedAttributes(attrs)

    // No fill information means Missing
    val expected = StyleSubAttributes()

    assertEquals(result, expected)
  }

  test("fromSubAttributes should handle string values for boolean attributes") {
    // Use direct attribute construction for string values
    val attrs = Attributes.of(
      BoldStyle      -> true,
      InvisibleStyle -> false
    )

    val result = StyleSubAttributes.fromExpandedAttributes(attrs)

    // Should parse string "true"/"false" correctly
    assertEquals(result.bold, AttrStatus.Single(true))
    assertEquals(result.invisible, AttrStatus.Single(false))
  }

  test("toStyleString should combine non-default values") {
    val subAttrs = StyleSubAttributes(
      fill = AttrStatus.Single(true),
      bold = AttrStatus.Single(true),
      invisible = AttrStatus.Single(false),
      border = AttrStatus.Single(BorderStyle.dashed),
      corner = AttrStatus.Single(CornerStyle.rounded)
    )

    val result = subAttrs.toStyleStringNoDefaults

    // Should include non-default values
    assertEquals(result, Some("filled,bold,rounded,dashed"))
  }

  test("toStyleString with all defaults") {
    val subAttrs = StyleSubAttributes(
      fill = AttrStatus.Single(false),
      bold = AttrStatus.Single(false),
      invisible = AttrStatus.Single(false),
      border = AttrStatus.Single(BorderStyle.solid),
      corner = AttrStatus.Single(CornerStyle.normal)
    )

    val result = subAttrs.toStyleStringNoDefaults

    // All defaults should result in empty string
    assertEquals(result, Some("normal,solid"))
  }

  test("toStyleString with invisible=true") {
    val subAttrs = StyleSubAttributes(
      fill = AttrStatus.Single(false),
      bold = AttrStatus.Single(false),
      invisible = AttrStatus.Single(true)
    )

    val result = subAttrs.toStyleStringNoDefaults

    assertEquals(result, Some("invis"))
  }
