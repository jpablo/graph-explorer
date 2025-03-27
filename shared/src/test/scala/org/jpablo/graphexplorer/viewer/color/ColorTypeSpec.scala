package org.jpablo.graphexplorer.viewer.color

import munit.FunSuite

class ColorTypeSpec extends FunSuite:
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

  test("parse named colors"):
    assertEquals(
      ColorType.fromString("red"),
      ColorType.named("red")
    )
    assertEquals(
      ColorType.fromString("transparent"),
      ColorType.named("transparent")
    )

  test("toHex converts RGB to hex"):
    assertEquals(
      ColorType.toHex(ColorType.RGB(255, 0, 0)),
      "#ff0000"
    )
    assertEquals(
      ColorType.toHex(ColorType.RGB(0, 255, 0)),
      "#00ff00"
    )
    assertEquals(
      ColorType.toHex(ColorType.RGB(0, 0, 255)),
      "#0000ff"
    )
    assertEquals(
      ColorType.toHex(ColorType.RGB(255, 255, 255)),
      "#ffffff"
    )
    assertEquals(
      ColorType.toHex(ColorType.RGB(0, 0, 0)),
      "#000000"
    )

  test("toHex converts RGBA to hex"):
    assertEquals(
      ColorType.toHex(ColorType.RGBA(255, 0, 0, 1.0)),
      "#ff0000ff"
    )
    assertEquals(
      ColorType.toHex(ColorType.RGBA(0, 255, 0, 0.5)),
      "#00ff0080"
    )
    assertEquals(
      ColorType.toHex(ColorType.RGBA(0, 0, 255, 0.0)),
      "#0000ff00"
    )

  test("toHex converts named colors to their X11 hex values"):
    assertEquals(
      ColorType.toHex(ColorType.named("red")),
      "#ff0000"
    )
    assertEquals(
      ColorType.toHex(ColorType.named("blue")),
      "#0000ff"
    )
    assertEquals(
      ColorType.toHex(ColorType.named("green")),
      "#00ff00"
    )

  test("toHexNoAlpha converts RGB colors correctly"):
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.RGB(255, 0, 0)),
      "#ff0000"
    )
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.RGB(0, 255, 0)),
      "#00ff00"
    )
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.RGB(0, 0, 255)),
      "#0000ff"
    )

  test("toHexNoAlpha discards alpha from RGBA colors"):
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.RGBA(255, 0, 0, 1.0)),
      "#ff0000"
    )
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.RGBA(0, 255, 0, 0.5)),
      "#00ff00"
    )
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.RGBA(0, 0, 255, 0.0)),
      "#0000ff"
    )

  test("toHexNoAlpha converts named colors to their X11 hex values"):
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.named("red")),
      "#ff0000"
    )
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.named("blue")),
      "#0000ff"
    )
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.named("green")),
      "#00ff00"
    )

  test("toHex converts OKCLH to hex".ignore):
    // Red in OKCLH
    assertEquals(
      ColorType.toHex(ColorType.OKCLH(0.627, 0.237, 25.331)),
      "#f62926"
    )
    // Green in OKCLH
    assertEquals(
      ColorType.toHex(ColorType.OKCLH(0.723, 0.219, 149.579)),
      "#00ff00"
    )
    // Blue in OKCLH
    assertEquals(
      ColorType.toHex(ColorType.OKCLH(0.546, 0.245, 262.881)),
      "#0000ff"
    )

  test("toHexNoAlpha converts OKCLH to hex".ignore):
    // Red in OKCLH
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.OKCLH(0.627, 0.237, 25.331)),
      "#f62926"
    )
    // Green in OKCLH
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.OKCLH(0.723, 0.219, 149.579)),
      "#00ff00"
    )
    // Blue in OKCLH
    assertEquals(
      ColorType.toHexNoAlpha(ColorType.OKCLH(0.546, 0.245, 262.881)),
      "#0000ff"
    )
