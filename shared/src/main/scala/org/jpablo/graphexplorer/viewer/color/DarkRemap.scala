package org.jpablo.graphexplorer.viewer.color

import scala.util.matching.Regex

/** A color in sRGB. Each channel and the alpha are in the range 0 to 1. */
final case class Srgb(r: Double, g: Double, b: Double, alpha: Double = 1.0) derives CanEqual:

  /** True when each channel is in the range 0 to 1, with a small tolerance. */
  def inGamut: Boolean =
    val e = 1e-4
    r >= -e && r <= 1 + e && g >= -e && g <= 1 + e && b >= -e && b <= 1 + e

  def clamped: Srgb = Srgb(Srgb.clamp01(r), Srgb.clamp01(g), Srgb.clamp01(b), Srgb.clamp01(alpha))

  /** The CSS text of the color: `#rrggbb` when it is opaque, else `rgba(…)`. */
  def css: String =
    val c = clamped
    def byte(v: Double) = math.round(v * 255).toInt
    if c.alpha >= 1.0 then f"#${byte(c.r)}%02x${byte(c.g)}%02x${byte(c.b)}%02x"
    else
      val a = math.round(c.alpha * 1000) / 1000.0
      s"rgba(${byte(c.r)}, ${byte(c.g)}, ${byte(c.b)}, $a)"

object Srgb:
  def clamp01(v: Double): Double = math.max(0.0, math.min(1.0, v))

/** A color in the Oklab space. `l` is the perceived lightness (0 to 1). `a` and
  * `b` give the hue and the chroma.
  */
final case class Oklab(l: Double, a: Double, b: Double, alpha: Double = 1.0) derives CanEqual:
  def chroma: Double = math.hypot(a, b)

  /** The hue in degrees, from 0 to 360. */
  def hue: Double =
    val h = math.toDegrees(math.atan2(b, a))
    if h < 0 then h + 360 else h

object Oklab:

  private def toLinear(c: Double): Double =
    if c <= 0.04045 then c / 12.92 else math.pow((c + 0.055) / 1.055, 2.4)

  private def toGamma(c: Double): Double =
    if c <= 0.0031308 then 12.92 * c else 1.055 * math.pow(c, 1.0 / 2.4) - 0.055

  /** The conversion of Björn Ottosson (https://bottosson.github.io/posts/oklab/). */
  def fromSrgb(c: Srgb): Oklab =
    val r = toLinear(c.r)
    val g = toLinear(c.g)
    val b = toLinear(c.b)
    val l = math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    Oklab(
      0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
      1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
      0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
      c.alpha
    )

  /** The inverse of [[fromSrgb]]. The result can be out of the sRGB gamut. */
  def toSrgb(c: Oklab): Srgb =
    val l_ = c.l + 0.3963377774 * c.a + 0.2158037573 * c.b
    val m_ = c.l - 0.1055613458 * c.a - 0.0638541728 * c.b
    val s_ = c.l - 0.0894841775 * c.a - 1.2914855480 * c.b
    val l  = l_ * l_ * l_
    val m  = m_ * m_ * m_
    val s  = s_ * s_ * s_
    Srgb(
      toGamma(+4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
      toGamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
      toGamma(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
      c.alpha
    )

  /** The color from Oklch values: the hue `h` is in degrees. */
  def fromOklch(l: Double, c: Double, h: Double, alpha: Double = 1.0): Oklab =
    val rad = math.toRadians(h)
    Oklab(l, c * math.cos(rad), c * math.sin(rad), alpha)

/** The CSS color forms that the remap reads without a browser: hex, `rgb()`,
  * `hsl()` and `oklch()`. The browser layer turns a named color into one of
  * these forms first.
  */
object CssColor:

  private val Hex      = "#([0-9a-fA-F]{3,8})".r
  private val Function = """(?i)(rgba?|hsla?|oklch)\(\s*([^)]*)\)""".r

  def parse(text: String): Option[Srgb] =
    text.trim match
      case Hex(digits)          => parseHex(digits)
      case Function(name, args) => parseFunction(name.toLowerCase, args)
      case _                    => None

  private def parseHex(d: String): Option[Srgb] =
    def ch(s: String) = Integer.parseInt(if s.length == 1 then s * 2 else s, 16) / 255.0
    d.length match
      case 3 => Some(Srgb(ch(d.substring(0, 1)), ch(d.substring(1, 2)), ch(d.substring(2, 3))))
      case 4 => Some(Srgb(ch(d.substring(0, 1)), ch(d.substring(1, 2)), ch(d.substring(2, 3)), ch(d.substring(3, 4))))
      case 6 => Some(Srgb(ch(d.substring(0, 2)), ch(d.substring(2, 4)), ch(d.substring(4, 6))))
      case 8 => Some(Srgb(ch(d.substring(0, 2)), ch(d.substring(2, 4)), ch(d.substring(4, 6)), ch(d.substring(6, 8))))
      case _ => None

  /** The arguments of a color function. The commas and the slash are optional. */
  private def parseArgs(args: String): Option[(List[String], Option[String])] =
    val (main, slashAlpha) = args.split('/').toList match
      case m :: a :: Nil => (m, Some(a.trim))
      case m :: Nil      => (m, None)
      case _             => return None
    val parts = main.split("[,\\s]+").toList.map(_.trim).filter(_.nonEmpty)
    (parts, slashAlpha) match
      case (p @ List(_, _, _), a)    => Some((p, a))
      case (List(x, y, z, a), None)  => Some((List(x, y, z), Some(a)))
      case _                         => None

  private def number(s: String, percentOf: Double): Option[Double] =
    val t = s.trim.toLowerCase
    if t.endsWith("%") then t.dropRight(1).toDoubleOption.map(_ / 100 * percentOf)
    else if t.endsWith("deg") then t.dropRight(3).toDoubleOption
    else if t == "none" then Some(0.0)
    else t.toDoubleOption

  private def alphaOf(a: Option[String]): Option[Double] =
    a.fold(Some(1.0))(number(_, 1.0))

  private def parseFunction(name: String, args: String): Option[Srgb] =
    parseArgs(args).flatMap: (xs, a) =>
      val List(x, y, z) = xs: @unchecked
      name match
        case "rgb" | "rgba" =>
          for r <- number(x, 255); g <- number(y, 255); b <- number(z, 255); al <- alphaOf(a)
          yield Srgb(r / 255, g / 255, b / 255, al)
        case "hsl" | "hsla" =>
          for h <- number(x, 360); s <- number(y, 1); l <- number(z, 1); al <- alphaOf(a)
          yield hslToSrgb(h, s, l, al)
        case "oklch" =>
          for l <- number(x, 1); c <- number(y, 0.4); h <- number(z, 360); al <- alphaOf(a)
          yield Oklab.toSrgb(Oklab.fromOklch(l, c, h, al)).clamped
        case _ => None

  private def hslToSrgb(h: Double, s: Double, l: Double, alpha: Double): Srgb =
    def f(n: Double) =
      val k = (n + h / 30) % 12
      val a = s * math.min(l, 1 - l)
      l - a * math.max(-1, math.min(math.min(k - 3, 9 - k), 1))
    Srgb(f(0), f(8), f(4), alpha)

  /** The color tokens in a CSS value: hex, the color functions, and words.
    * A word is a color only when the caller can resolve it. A `url(…)` is one
    * token, so a reference such as `url(#abc)` is not a hex color.
    */
  val Token: Regex = """(?i)url\([^)]*\)|#[0-9a-f]{3,8}\b|(?:rgba?|hsla?|oklch|oklab|lab|lch|hwb|color)\([^)]*\)|\b[a-z]+\b""".r

/** The remap of diagram colors for a dark theme.
  *
  * A diagram has colors for a light page. The remap gives each color a new
  * lightness on a scale from the ink to the paper of the theme: white becomes
  * the paper, and black becomes the ink. The hue stays, so a red node stays
  * red. The order of the lightness reverses, so text on a fill keeps its
  * contrast. The chroma decreases a little, because a dark page shows a
  * saturated color brighter.
  *
  * With a white paper, a black ink and `chromaScale = 1`, the remap does not
  * change a color.
  *
  * @param paper       the color that white becomes (the sheet of the diagram)
  * @param ink         the color that black becomes (the text of the theme)
  * @param chromaScale the multiplier of the chroma of each color
  */
final case class DarkRemap(paper: Oklab, ink: Oklab, chromaScale: Double = DarkRemap.DefaultChromaScale) derives CanEqual:

  def apply(color: Srgb): Srgb =
    val c = Oklab.fromSrgb(color)
    val t = math.max(0.0, math.min(1.0, c.l))
    // The tint of the theme at this lightness: a gray becomes this color.
    val tint = Oklab(
      ink.l + (paper.l - ink.l) * t,
      ink.a + (paper.a - ink.a) * t,
      ink.b + (paper.b - ink.b) * t
    )
    val mapped = Oklab(tint.l, tint.a + c.a * chromaScale, tint.b + c.b * chromaScale, c.alpha)
    DarkRemap.intoGamut(mapped, tint)

object DarkRemap:
  val DefaultChromaScale = 0.85

  /** The theme is dark when its paper is darker than its ink. */
  def isDark(paper: Oklab, ink: Oklab): Boolean = paper.l < ink.l

  /** Decrease the chroma of `c` toward `base` until `c` is in the sRGB gamut.
    * The lightness and the hue stay.
    */
  def intoGamut(c: Oklab, base: Oklab): Srgb =
    val direct = Oklab.toSrgb(c)
    if direct.inGamut then direct.clamped
    else
      def at(k: Double) = Oklab.toSrgb(Oklab(c.l, base.a + (c.a - base.a) * k, base.b + (c.b - base.b) * k, c.alpha))
      var lo = 0.0
      var hi = 1.0
      for _ <- 0 until 20 do
        val mid = (lo + hi) / 2
        if at(mid).inGamut then lo = mid else hi = mid
      at(lo).clamped

  /** Replace each color token of a CSS value with its remap. `resolve` turns a
    * token into a color, or gives `None` for a token that is not a color (for
    * example `none`, `url`, or `solid`).
    */
  def remapValue(value: String, resolve: String => Option[Srgb], remap: Srgb => Srgb): String =
    CssColor.Token.replaceAllIn(
      value,
      m => Regex.quoteReplacement(resolve(m.matched).fold(m.matched)(c => remap(c).css))
    )
