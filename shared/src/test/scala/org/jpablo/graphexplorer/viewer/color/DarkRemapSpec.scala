package org.jpablo.graphexplorer.viewer.color

import munit.FunSuite

class DarkRemapSpec extends FunSuite:

  private def css(text: String): Srgb = CssColor.parse(text).getOrElse(fail(s"not a color: $text"))
  private def lab(text: String): Oklab = Oklab.fromSrgb(css(text))

  /** The paper and the ink of the daisyUI `dark` theme (base-100 and base-content). */
  private val darkTheme = DarkRemap(paper = lab("#1d232a"), ink = lab("#ecf9ff"))

  private def assertClose(actual: Double, expected: Double, tolerance: Double, clue: String)(using munit.Location): Unit =
    assert(math.abs(actual - expected) <= tolerance, s"$clue: $actual is not within $tolerance of $expected")

  // ---- parse ----

  test("parse the hex forms"):
    assertEquals(css("#fff"), Srgb(1, 1, 1))
    assertEquals(css("#000000"), Srgb(0, 0, 0))
    assertEquals(css("#ff000080").alpha, 128 / 255.0)
    assertEquals(css("#f008").alpha, 136 / 255.0)

  test("parse rgb() and rgba() with commas and with spaces"):
    assertEquals(css("rgb(255, 0, 0)"), Srgb(1, 0, 0))
    assertEquals(css("rgba(0, 0, 255, 0.5)"), Srgb(0, 0, 1, 0.5))
    assertEquals(css("rgb(0 255 0 / 25%)"), Srgb(0, 1, 0, 0.25))

  test("parse hsl() as Mermaid writes it"):
    val c = css("hsl(240, 100%, 46.2745098039%)")
    assertClose(c.r, 0, 1e-9, "r")
    assertClose(c.b, 0.925490196, 1e-6, "b")

  test("parse oklch() as daisyUI writes it"):
    val c = Oklab.fromSrgb(css("oklch(25.33% 0.016 252.42)"))
    assertClose(c.l, 0.2533, 1e-3, "lightness")
    assertClose(c.hue, 252.42, 1.0, "hue")

  test("a word or a url is not a color without a browser"):
    assertEquals(CssColor.parse("red"), None)
    assertEquals(CssColor.parse("url(#abc)"), None)

  test("the Oklab conversion returns the same sRGB color"):
    for text <- List("#ff0000", "#00ff00", "#0000ff", "#ffe4c4", "#123456", "#808080") do
      val back = Oklab.toSrgb(lab(text))
      assertEquals(back.css, text)

  // ---- the remap ----

  test("white becomes the paper, and black becomes the ink"):
    assertEquals(darkTheme(css("#ffffff")).css, "#1d232a")
    assertEquals(darkTheme(css("#000000")).css, "#ecf9ff")

  test("a light theme with white paper and black ink does not change a color"):
    val identity = DarkRemap(paper = lab("#ffffff"), ink = lab("#000000"), chromaScale = 1.0)
    for text <- List("#ff0000", "#ffe4c4", "#9370db", "#ececff", "#333333") do
      assertEquals(identity(css(text)).css, text, text)

  test("the remap keeps the hue of a color"):
    for text <- List("#ff0000", "#9370db", "#ffe4c4", "#008000", "#ececff") do
      val before = lab(text)
      val after  = Oklab.fromSrgb(darkTheme(css(text)))
      val diff   = math.abs(((after.hue - before.hue + 540) % 360) - 180)
      // The paper and the ink of the theme have a small blue tint, which moves
      // the hue of a color with a low chroma a little.
      val tolerance = if before.chroma < 0.05 then 40.0 else 12.0
      assert(diff <= tolerance, s"$text: hue ${before.hue} became ${after.hue}")

  test("a light color becomes dark, and a dark color becomes light"):
    val lightFill = Oklab.fromSrgb(darkTheme(css("#ececff")))
    val darkText  = Oklab.fromSrgb(darkTheme(css("#333333")))
    assert(lightFill.l < 0.35, s"the light fill has lightness ${lightFill.l}")
    assert(darkText.l > 0.7, s"the dark text has lightness ${darkText.l}")

  test("the remap reverses the order of the lightness, so text on a fill keeps its contrast"):
    val grays = (0 to 20).map(i => Srgb(i / 20.0, i / 20.0, i / 20.0))
    val out   = grays.map(g => Oklab.fromSrgb(darkTheme(g)).l)
    out.zip(out.tail).foreach((a, b) => assert(b <= a + 1e-9, s"$a then $b"))

  test("each result is in the sRGB gamut, and the alpha stays"):
    for text <- List("#ff0000", "#00ff00", "#0000ff", "#ffff00", "#00ffff", "#ff00ff") do
      val out = darkTheme(css(text))
      assert(out.inGamut, s"$text gives $out")
    assertEquals(darkTheme(css("rgba(255, 0, 0, 0.4)")).alpha, 0.4)

  test("isDark compares the paper with the ink"):
    assert(DarkRemap.isDark(lab("#1d232a"), lab("#ecf9ff")))
    assert(!DarkRemap.isDark(lab("#ffffff"), lab("#1f2937")))

  // ---- CSS values ----

  private def remapCss(v: String) = DarkRemap.remapValue(v, CssColor.parse, darkTheme.apply)

  test("remapValue changes each color token, and keeps the other text"):
    assertEquals(remapCss("1px solid #ffffff"), "1px solid #1d232a")
    assertEquals(remapCss("#000 !important"), "#ecf9ff !important")
    assertEquals(remapCss("none"), "none")

  test("remapValue keeps a url reference, also when its id looks like a hex color"):
    assertEquals(remapCss("url(#abc)"), "url(#abc)")
    assertEquals(remapCss("url(#face) #fff"), "url(#face) #1d232a")
