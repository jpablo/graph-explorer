package org.jpablo.graphexplorer.viewer.color

enum ColorType:
  case RGB(r: Int, g: Int, b: Int)
  case RGBA(r: Int, g: Int, b: Int, a: Double)
  case OKCLH(l: Double, c: Double, h: Double)
  case named(value: String)

object ColorType:

  def x11ColorSchemeToHex(color: String): String =
    x11Colors.getOrElse(color.toLowerCase, "#000000") // default to black if color not found

  // --------------------------------
  def oklchToRgb(l: Double, c: Double, h: Double): RGB = {
    // Implementation of the OKLCH to RGB conversion
    // This is a simplified version - a full implementation would require
    // multiple conversion steps: OKLCH → OKLAB → Linear RGB → sRGB

    // Convert OKLCH to OKLAB
    val hRad = h * Math.PI / 180.0 // Convert degrees to radians
    val a    = c * Math.cos(hRad)
    val bLab = c * Math.sin(hRad)  // Renamed to bLab to avoid conflict

    // Convert OKLAB to linear RGB
    val l_ = l + 0.3963377774 * a + 0.2158037573 * bLab
    val m_ = l - 0.1055613458 * a - 0.0638541728 * bLab
    val s_ = l - 0.0894841775 * a - 1.2914855480 * bLab

    val l_cubed = l_ * l_ * l_
    val m_cubed = m_ * m_ * m_
    val s_cubed = s_ * s_ * s_

    // Linear RGB
    val linearR = +4.0767416621 * l_cubed - 3.3077115913 * m_cubed + 0.2309699292 * s_cubed
    val linearG = -1.2684380046 * l_cubed + 2.6097574011 * m_cubed - 0.3413193965 * s_cubed
    val linearB = -0.0041960863 * l_cubed - 0.7034186147 * m_cubed + 1.7076147010 * s_cubed

    // Convert linear RGB to sRGB
    val rOut = linearToSrgb(linearR) // Renamed to rOut
    val gOut = linearToSrgb(linearG) // Renamed to gOut
    val bOut = linearToSrgb(linearB) // Renamed to bOut

    RGB(rOut, gOut, bOut)
  }

  def linearToSrgb(c: Double): Int = {
    val v = if (c <= 0.0) {
      0
    } else if (c >= 1.0) {
      255
    } else {
      val srgb = if (c <= 0.0031308) {
        12.92 * c
      } else {
        1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
      }
      Math.round(srgb * 255).toInt
    }

    Math.max(0, Math.min(255, v))
  }

  // --------------------------------

  def toHex(color: ColorType): String =
    color match
      case RGB(r, g, b)     => f"#$r%02x$g%02x$b%02x"
      case RGBA(r, g, b, a) =>
        // Using Math.round to ensure correct rounding of alpha values
        val alpha = Math.round(a * 255).toInt
        f"#$r%02x$g%02x$b%02x$alpha%02x"

      case named(value)   => x11ColorSchemeToHex(value)
      case OKCLH(l, c, h) => toHex(oklchToRgb(l, c, h))

  /** Converts any ColorType to a hex format suitable for HTML color input (discarding alpha) */
  def toHexNoAlpha(color: ColorType): String =
    color match
      case RGB(_, _, _) | named(_) | OKCLH(_, _, _) =>
        toHex(color)
      case RGBA(r, g, b, _) => toHex(RGB(r, g, b))

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

      // Named color
      case name => named(name)
