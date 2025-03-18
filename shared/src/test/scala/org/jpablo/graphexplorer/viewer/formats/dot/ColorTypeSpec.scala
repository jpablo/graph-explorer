package org.jpablo.graphexplorer.viewer.formats.dot

import munit.FunSuite

class ColorTypeTest extends FunSuite:
  test("parse RGB hex color format (#rrggbb)"):
    assertEquals(
      ColorType.fromString("#ff0000"),
      ColorType.RGB(255, 0, 0)
    )
    assertEquals(
      ColorType.fromString("#00ff00"),
      ColorType.RGB(0, 255, 0)
    )
    assertEquals(
      ColorType.fromString("#0000ff"),
      ColorType.RGB(0, 0, 255)
    )

  test("parse shorthand RGB hex color format (#rgb)"):
    assertEquals(
      ColorType.fromString("#f00"),
      ColorType.RGB(255, 0, 0)
    )
    assertEquals(
      ColorType.fromString("#0f0"),
      ColorType.RGB(0, 255, 0)
    )
    assertEquals(
      ColorType.fromString("#00f"),
      ColorType.RGB(0, 0, 255)
    )

  test("parse RGBA hex color format (#rrggbbaa)"):
    assertEquals(
      ColorType.fromString("#ff0000ff"),
      ColorType.RGBA(255, 0, 0, 1.0)
    )
    assertEquals(
      ColorType.fromString("#00ff0080"),
      ColorType.RGBA(0, 255, 0, 0.5019607843137255)
    )

    assertEquals(
      ColorType.fromString("#eeee0080"),
      ColorType.RGBA(238, 238, 0, 0.5019607843137255)
    )

  test("parse HSV color format"):
    assertEquals(
      ColorType.fromString("0.0, 1.0, 1.0"),
      ColorType.HSV(0.0, 1.0, 1.0)
    )
    assertEquals(
      ColorType.fromString("0.5 0.5 0.5"),
      ColorType.HSV(0.5, 0.5, 0.5)
    )

  test("parse HSVA color format"):
    assertEquals(
      ColorType.fromString("0.0, 1.0, 1.0, 1.0"),
      ColorType.HSVA(0.0, 1.0, 1.0, 1.0)
    )
    assertEquals(
      ColorType.fromString("0.5 0.5 0.5 0.5"),
      ColorType.HSVA(0.5, 0.5, 0.5, 0.5)
    )

  test("parse named colors"):
    assertEquals(
      ColorType.fromString("red"),
      ColorType.named("red")
    )
    assertEquals(
      ColorType.fromString("transparent"),
      ColorType.named("transparent")
    )
