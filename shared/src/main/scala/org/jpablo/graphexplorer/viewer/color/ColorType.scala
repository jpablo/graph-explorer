package org.jpablo.graphexplorer.viewer.color

enum ColorType:
  case RGB(r: Int, g: Int, b: Int)
  case nRGB(r: Double, g: Double, b: Double)
  case RGBA(r: Int, g: Int, b: Int, a: Double)
  case HSV(h: Double, s: Double, v: Double)
  case HSVA(h: Double, s: Double, v: Double, a: Double)
  case OKCLH(l: Double, c: Double, h: Double)
  case named(value: String)

object ColorType:

  def x11ColorSchemeToHex(color: String): String =
    x11Colors.getOrElse(color.toLowerCase, "#000000") // default to black if color not found

  def oklchTonRGB(l: Double, c: Double, h: Double): nRGB =
    // Step 1: Convert OKLCH to OKLab
    val hRad = h * Math.PI / 180.0
    val a    = c * Math.cos(hRad)
    val b    = c * Math.sin(hRad)

    // Step 2: Convert OKLab to linear RGB
    val l_ = l + 0.3963377774 * a + 0.2158037573 * b
    val m_ = l - 0.1055613458 * a - 0.0638541728 * b
    val s_ = l - 0.0894841775 * a - 1.2914855480 * b

    val l_cubed = l_ * l_ * l_
    val m_cubed = m_ * m_ * m_
    val s_cubed = s_ * s_ * s_

    val r  = 4.0767416621 * l_cubed - 3.3077115913 * m_cubed + 0.2309699292 * s_cubed
    val g  = -1.2684380046 * l_cubed + 2.6097574011 * m_cubed - 0.3413193965 * s_cubed
    val b_ = -0.0041960863 * l_cubed - 0.7034186147 * m_cubed + 1.7076147010 * s_cubed

    // Step 3: Clamp and apply gamma correction to get sRGB
    val r_srgb = gammaCorrect(clamp(r))
    val g_srgb = gammaCorrect(clamp(g))
    val b_srgb = gammaCorrect(clamp(b_))
    nRGB(r_srgb, g_srgb, b_srgb)

  def nRGBToHsv(rbg: nRGB): HSV =
    val (r_srgb, g_srgb, b_srgb) = (rbg.r, rbg.g, rbg.b)

    val max   = Math.max(Math.max(r_srgb, g_srgb), b_srgb)
    val min   = Math.min(Math.min(r_srgb, g_srgb), b_srgb)
    val delta = max - min
    // Calculate HSV components
    val v = max
    val s = if (max > 0) delta / max else 0
    val h_hsv =
      if delta == 0 then
        0.0 // Undefined, default to 0
      else if max == r_srgb then
        ((g_srgb - b_srgb) / delta + (if (g_srgb < b_srgb) 6 else 0)) / 6
      else if max == g_srgb then
        ((b_srgb - r_srgb) / delta + 2) / 6
      else // max == b_srgb
        ((r_srgb - g_srgb) / delta + 4) / 6

    HSV(h_hsv, s, v)

  private def clamp(value: Double): Double = Math.max(0, Math.min(1, value))

  private def gammaCorrect(linear: Double): Double =
    if linear <= 0.0031308 then
      12.92 * linear
    else
      1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055

  def hsvToRgb(hsv: HSV): RGB =
    val HSV(h, s, v) = hsv
    // Convert HSV to RGB
    val c = v * s
    val x = c * (1 - math.abs((h / 60) % 2 - 1))
    val m = v - c

    val (r1, g1, b1) = h match
      case h if h < 60  => (c, x, 0.0)
      case h if h < 120 => (x, c, 0.0)
      case h if h < 180 => (0.0, c, x)
      case h if h < 240 => (0.0, x, c)
      case h if h < 300 => (x, 0.0, c)
      case _            => (c, 0.0, x)

    RGB(
      ((r1 + m) * 255).toInt,
      ((g1 + m) * 255).toInt,
      ((b1 + m) * 255).toInt
    )

  def nRGBtoRGB(nrgb: nRGB): RGB =
    RGB(
      (nrgb.r * 255).toInt,
      (nrgb.g * 255).toInt,
      (nrgb.b * 255).toInt
    )

  def toHex(color: ColorType): String =
    color match
      case RGB(r, g, b)     => f"#$r%02x$g%02x$b%02x"
      case RGBA(r, g, b, a) =>
        // Using Math.round to ensure correct rounding of alpha values
        val alpha = Math.round(a * 255).toInt
        f"#$r%02x$g%02x$b%02x$alpha%02x"
      case hsv: HSV =>
        val rgb = hsvToRgb(hsv)
        f"#${rgb.r}%02x${rgb.g}%02x${rgb.b}%02x"

      case HSVA(h, s, v, a) =>
        val rgbHex = toHex(HSV(h, s, v))
        // Using Math.round to ensure correct rounding of alpha values
        val alpha = Math.round(a * 255).toInt
        rgbHex + f"$alpha%02x"

      case named(value)   => x11ColorSchemeToHex(value)
      case OKCLH(l, c, h) => toHex(nRGBToHsv(oklchTonRGB(l, c, h)))
      case c: nRGB        => toHex(nRGBtoRGB(c))

  /** Converts any ColorType to a hex format suitable for HTML color input (discarding alpha) */
  def toHexNoAlpha(color: ColorType): String =
    color match
      case RGB(_, _, _) | HSV(_, _, _) | named(_) | OKCLH(_, _, _) | nRGB(_, _, _) =>
        toHex(color)
      case RGBA(r, g, b, _) => toHex(RGB(r, g, b))
      case HSVA(h, s, v, _) => toHex(HSV(h, s, v))

  def fromString(s: String): ColorType =
    val trimmed = s.trim
    trimmed match
      // RGB format "#rrggbb"
      case rgb if rgb.matches("^#[0-9a-fA-F]{6}$") =>
        val hex = rgb.substring(1)
        val r   = Integer.parseInt(hex.substring(0, 2), 16)
        val g   = Integer.parseInt(hex.substring(2, 4), 16)
        val b   = Integer.parseInt(hex.substring(4, 6), 16)
        RGB(r, g, b)

      // Shorthand RGB format "#rgb"
      case shortRgb if shortRgb.matches("^#[0-9a-fA-F]{3}$") =>
        val hex = shortRgb.substring(1)
        val r   = Integer.parseInt(hex.substring(0, 1) * 2, 16)
        val g   = Integer.parseInt(hex.substring(1, 2) * 2, 16)
        val b   = Integer.parseInt(hex.substring(2, 3) * 2, 16)
        RGB(r, g, b)

      // RGBA format "#rrggbbaa"
      case rgba if rgba.matches("^#[0-9a-fA-F]{8}$") =>
        val hex = rgba.substring(1)
        val r   = Integer.parseInt(hex.substring(0, 2), 16)
        val g   = Integer.parseInt(hex.substring(2, 4), 16)
        val b   = Integer.parseInt(hex.substring(4, 6), 16)
        val a   = Integer.parseInt(hex.substring(6, 8), 16) / 255.0
        RGBA(r, g, b, a)

      // HSV format "H[, ]+S[, ]+V" where H,S,V are between 0.0 and 1.0
      case hsv if hsv.matches("^\\d*\\.?\\d+[, ]+\\d*\\.?\\d+[, ]+\\d*\\.?\\d+$") =>
        val parts = hsv.split("[, ]+").map(_.toDouble)
        HSV(parts(0), parts(1), parts(2))

      // HSVA format "H[, ]+S[, ]+V[, ]+A" where H,S,V,A are between 0.0 and 1.0
      case hsva if hsva.matches("^\\d*\\.?\\d+[, ]+\\d*\\.?\\d+[, ]+\\d*\\.?\\d+[, ]+\\d*\\.?\\d+$") =>
        val parts = hsva.split("[, ]+").map(_.toDouble)
        HSVA(parts(0), parts(1), parts(2), parts(3))

      // Named color
      case name => named(name)
