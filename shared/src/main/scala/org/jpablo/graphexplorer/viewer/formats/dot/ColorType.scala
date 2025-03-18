package org.jpablo.graphexplorer.viewer.formats.dot


enum ColorType:
  case RGB(r: Int, g: Int, b: Int)
  case RGBA(r: Int, g: Int, b: Int, a: Double)
  case HSV(h: Double, s: Double, v: Double)
  case HSVA(h: Double, s: Double, v: Double, a: Double)
  case named(value: String)

object ColorType:
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

