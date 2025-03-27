package org.jpablo.graphexplorer.viewer.color

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.color.ColorFormat.{Hex, OKLCH, RGB, RGBA, fromString, named, toHex, toHexNoAlpha}

class ColorFormatSpec extends FunSuite:
  test("parse RGB hex color format (#rrggbb)"):
    assertEquals(
      fromString("#ff0000"),
      RGB(255, 0, 0)
    )
    assertEquals(
      fromString("#00ff00"),
      RGB(0, 255, 0)
    )
    assertEquals(
      fromString("#0000ff"),
      RGB(0, 0, 255)
    )

  test("parse shorthand RGB hex color format (#rgb)"):
    assertEquals(
      fromString("#f00"),
      RGB(255, 0, 0)
    )
    assertEquals(
      fromString("#0f0"),
      RGB(0, 255, 0)
    )
    assertEquals(
      fromString("#00f"),
      RGB(0, 0, 255)
    )

  test("parse RGBA hex color format (#rrggbbaa)"):
    assertEquals(
      fromString("#ff0000ff"),
      RGBA(255, 0, 0, 1.0)
    )
    assertEquals(
      fromString("#00ff0080"),
      RGBA(0, 255, 0, 0.5019607843137255)
    )

    assertEquals(
      fromString("#eeee0080"),
      RGBA(238, 238, 0, 0.5019607843137255)
    )

  test("parse named colors"):
    assertEquals(
      fromString("red"),
      named("red")
    )
    assertEquals(
      fromString("transparent"),
      named("transparent")
    )

  test("toHex converts RGB to hex"):
    assertEquals(
      toHex(RGB(255, 0, 0)),
      Hex("#ff0000")
    )
    assertEquals(
      toHex(RGB(0, 255, 0)),
      Hex("#00ff00")
    )
    assertEquals(
      toHex(RGB(0, 0, 255)),
      Hex("#0000ff")
    )
    assertEquals(
      toHex(RGB(255, 255, 255)),
      Hex("#ffffff")
    )
    assertEquals(
      toHex(RGB(0, 0, 0)),
      Hex("#000000")
    )

  test("toHex converts RGBA to hex"):
    assertEquals(
      toHex(RGBA(255, 0, 0, 1.0)),
      Hex("#ff0000ff")
    )
    assertEquals(
      toHex(RGBA(0, 255, 0, 0.5)),
      Hex("#00ff0080")
    )
    assertEquals(
      toHex(RGBA(0, 0, 255, 0.0)),
      Hex("#0000ff00")
    )

  test("toHex converts named colors to their X11 hex values"):
    assertEquals(
      toHex(named("red")),
      Hex("#ff0000")
    )
    assertEquals(
      toHex(named("blue")),
      Hex("#0000ff")
    )
    assertEquals(
      toHex(named("green")),
      Hex("#00ff00")
    )

  test("toHexNoAlpha converts RGB colors correctly"):
    assertEquals(
      toHexNoAlpha(RGB(255, 0, 0)),
      Hex("#ff0000")
    )
    assertEquals(
      toHexNoAlpha(RGB(0, 255, 0)),
      Hex("#00ff00")
    )
    assertEquals(
      toHexNoAlpha(RGB(0, 0, 255)),
      Hex("#0000ff")
    )

  test("toHexNoAlpha discards alpha from RGBA colors"):
    assertEquals(
      toHexNoAlpha(RGBA(255, 0, 0, 1.0)),
      Hex("#ff0000")
    )
    assertEquals(
      toHexNoAlpha(RGBA(0, 255, 0, 0.5)),
      Hex("#00ff00")
    )
    assertEquals(
      toHexNoAlpha(RGBA(0, 0, 255, 0.0)),
      Hex("#0000ff")
    )

  test("toHexNoAlpha converts named colors to their X11 hex values"):
    assertEquals(
      toHexNoAlpha(named("red")),
      Hex("#ff0000")
    )
    assertEquals(
      toHexNoAlpha(named("blue")),
      Hex("#0000ff")
    )
    assertEquals(
      toHexNoAlpha(named("green")),
      Hex("#00ff00")
    )

  test("toHex converts OKCLH to hex"):
    // Red in OKCLH
    assertEquals(
      toHex(OKLCH(0.627, 0.237, 25.331)),
      Hex("#f72633")
    )
    // Green in OKCLH
    assertEquals(
      toHex(OKLCH(0.723, 0.219, 149.579)),
      Hex("#00c950")
    )
    // Blue in OKCLH
    assertEquals(
      toHex(OKLCH(0.546, 0.245, 262.881)),
      Hex("#155dfc")
    )

  test("toHexNoAlpha converts OKCLH to hex"):
    // Red in OKCLH
    assertEquals(
      toHexNoAlpha(OKLCH(0.627, 0.237, 25.331)),
      Hex("#f72633")
    )
    // Green in OKCLH
    assertEquals(
      toHexNoAlpha(OKLCH(0.723, 0.219, 149.579)),
      Hex("#00c950")
    )
    // Blue in OKCLH
    assertEquals(
      toHexNoAlpha(OKLCH(0.546, 0.245, 262.881)),
      Hex("#155dfc")
    )
