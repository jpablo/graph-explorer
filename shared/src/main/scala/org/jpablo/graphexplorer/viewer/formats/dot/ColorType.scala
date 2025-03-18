package org.jpablo.graphexplorer.viewer.formats.dot


enum ColorType:
  case RGB(r: Int, g: Int, b: Int)
  case RGBA(r: Int, g: Int, b: Int, a: Double)
  case HSV(h: Double, s: Double, v: Double)
  case HSVA(h: Double, s: Double, v: Double, a: Double)
  case named(value: String)

object ColorType:
  def toHex(color: ColorType): String =
    color match
      case RGB(r, g, b) => f"#$r%02x$g%02x$b%02x"
      case RGBA(r, g, b, a) => 
        // Using Math.round to ensure correct rounding of alpha values
        val alpha = Math.round(a * 255).toInt
        f"#$r%02x$g%02x$b%02x$alpha%02x"
      case HSV(h, s, v) =>
        // Convert HSV to RGB
        val c = v * s
        val x = c * (1 - math.abs((h / 60) % 2 - 1))
        val m = v - c
        
        val (r1, g1, b1) = h match
          case h if h < 60 => (c, x, 0.0)
          case h if h < 120 => (x, c, 0.0)
          case h if h < 180 => (0.0, c, x)
          case h if h < 240 => (0.0, x, c)
          case h if h < 300 => (x, 0.0, c)
          case _ => (c, 0.0, x)
        
        val (r, g, b) = (
          ((r1 + m) * 255).toInt,
          ((g1 + m) * 255).toInt,
          ((b1 + m) * 255).toInt
        )
        f"#$r%02x$g%02x$b%02x"
        
      case HSVA(h, s, v, a) =>
        val rgbHex = toHex(HSV(h, s, v))
        // Using Math.round to ensure correct rounding of alpha values
        val alpha = Math.round(a * 255).toInt
        rgbHex + f"$alpha%02x"
        
      case named(value) => value // Named colors are returned as-is

  /** Converts any ColorType to a hex format suitable for HTML color input (discarding alpha) */
  def toHexNoAlpha(color: ColorType): String =
    color match
      case RGB(r, g, b) => f"#$r%02x$g%02x$b%02x"
      case RGBA(r, g, b, _) => f"#$r%02x$g%02x$b%02x"
      case HSV(h, s, v) => toHex(HSV(h, s, v))
      case HSVA(h, s, v, _) => toHex(HSV(h, s, v))
      case named(value) => "#000000" // Default to black for named colors since HTML color input doesn't support names

  def fromString(s: String): ColorType =
    val trimmed = s.trim
    trimmed match
      // RGB format "#rrggbb"
      case rgb if rgb.matches("^#[0-9a-fA-F]{6}$") =>
        val hex = rgb.substring(1)
        val r = Integer.parseInt(hex.substring(0, 2), 16)
        val g = Integer.parseInt(hex.substring(2, 4), 16)
        val b = Integer.parseInt(hex.substring(4, 6), 16)
        RGB(r, g, b)

      // Shorthand RGB format "#rgb"
      case shortRgb if shortRgb.matches("^#[0-9a-fA-F]{3}$") =>
        val hex = shortRgb.substring(1)
        val r = Integer.parseInt(hex.substring(0, 1) * 2, 16)
        val g = Integer.parseInt(hex.substring(1, 2) * 2, 16)
        val b = Integer.parseInt(hex.substring(2, 3) * 2, 16)
        RGB(r, g, b)

      // RGBA format "#rrggbbaa"
      case rgba if rgba.matches("^#[0-9a-fA-F]{8}$") =>
        val hex = rgba.substring(1)
        val r = Integer.parseInt(hex.substring(0, 2), 16)
        val g = Integer.parseInt(hex.substring(2, 4), 16)
        val b = Integer.parseInt(hex.substring(4, 6), 16)
        val a = Integer.parseInt(hex.substring(6, 8), 16) / 255.0
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

