package org.jpablo.graphexplorer.viewer.formats.svg

import scala.util.parsing.combinator.*

type Coordinate = (x: Double, y: Double)

enum PathCommand:
  case MoveTo(absolute: Boolean, points: List[Coordinate])
  case ClosePath()
  case LineTo(absolute: Boolean, points: List[Coordinate])
  case HorizontalLineTo(absolute: Boolean, values: List[Double])
  case VerticalLineTo(absolute: Boolean, values: List[Double])
  case CurveTo(absolute: Boolean, points: List[(Coordinate, Coordinate, Coordinate)])
  case SmoothCurveTo(absolute: Boolean, points: List[(Coordinate, Coordinate)])
  case QuadraticBezierCurveTo(absolute: Boolean, points: List[(Coordinate, Coordinate)])
  case SmoothQuadraticBezierCurveTo(absolute: Boolean, points: List[Coordinate])
  case EllipticalArc(absolute: Boolean, args: List[(Double, Double, Double, Boolean, Boolean, Coordinate)])

  // Helper to format coordinate pairs
  private def formatPair(p: (x: Double, y: Double)): String = s"${p.x},${p.y}"

  // Convert a single command to its string representation
  def asString: String = this match
    case MoveTo(abs, pts) =>
      val cmd = if (abs) "M" else "m"
      cmd + pts.map(formatPair).mkString(" ")
    case ClosePath() => "Z"
    case LineTo(abs, pts) =>
      val cmd = if (abs) "L" else "l"
      cmd + pts.map(formatPair).mkString(" ")
    case HorizontalLineTo(abs, vals) =>
      val cmd = if (abs) "H" else "h"
      cmd + vals.mkString(" ")
    case VerticalLineTo(abs, vals) =>
      val cmd = if (abs) "V" else "v"
      cmd + vals.mkString(" ")
    case CurveTo(abs, pts) =>
      val cmd = if (abs) "C" else "c"
      cmd + pts.map { case (p1, p2, p3) => s"${formatPair(p1)} ${formatPair(p2)} ${formatPair(p3)}" }.mkString(" ")
    case SmoothCurveTo(abs, pts) =>
      val cmd = if (abs) "S" else "s"
      cmd + pts.map { case (p1, p2) => s"${formatPair(p1)} ${formatPair(p2)}" }.mkString(" ")
    case QuadraticBezierCurveTo(abs, pts) =>
      val cmd = if (abs) "Q" else "q"
      cmd + pts.map { case (p1, p2) => s"${formatPair(p1)} ${formatPair(p2)}" }.mkString(" ")
    case SmoothQuadraticBezierCurveTo(abs, pts) =>
      val cmd = if (abs) "T" else "t"
      cmd + pts.map(formatPair).mkString(" ")
    case EllipticalArc(abs, args) =>
      val cmd = if (abs) "A" else "a"
      cmd + args.map { case (rx, ry, angle, largeArc, sweep, point) =>
        s"$rx $ry $angle ${if (largeArc) 1 else 0} ${if (sweep) 1 else 0} ${formatPair(point)}"
      }.mkString(" ")

object PathCommand:
  // Convert a list of commands to the full SVG path data string
  def toData(commands: List[PathCommand]): String =
    commands.map(_.asString).mkString(" ")

  /** Move the path's END to `point`, keeping the rest of the shape.
    *
    * Mermaid terminates its edge paths with a short straight stub (`...C... L end`);
    * bending only that stub leaves the curve frozen and stretches the stub — a sharp
    * elbow at the joint. When the last command is a single-point LineTo preceded by a
    * CurveTo, drop the stub and move the CURVE's endpoint instead — the same smooth
    * re-bend Graphviz paths get (they end in the curve itself).
    */
  def moveTarget(commands: List[PathCommand], point: Coordinate): List[PathCommand] =
    val trimmed = commands match
      case init :+ (c: CurveTo) :+ LineTo(_, _ :: Nil) => init :+ c
      case other                                       => other
    trimmed match
      case init :+ LineTo(a, pts) => init :+ LineTo(a, pts.init :+ point)
      case init :+ CurveTo(a, points) =>
        val (c1, c2, _) = points.last
        init :+ CurveTo(a, points.init :+ (c1, c2, point))
      case other => other

  /** Move the path's START to `point` (mirror of [[moveTarget]]: Mermaid also opens
    * with a `M start L p C ...` stub, which would hinge at `p`).
    */
  def moveOrigin(commands: List[PathCommand], point: Coordinate): List[PathCommand] =
    val trimmed = commands match
      case (m: MoveTo) :: LineTo(_, _ :: Nil) :: (c: CurveTo) :: rest => m :: c :: rest
      case other                                                     => other
    trimmed match
      case MoveTo(a, _ :: pt) :: ct => MoveTo(a, point :: pt) :: ct
      case other                    => other

class SVGPathParser extends RegexParsers:
  override def skipWhitespace = true // Let's handle whitespace automatically

  // Basic parsers
  def number: Parser[Double] = """-?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""".r ^^ { _.toDouble }
  def comma: Parser[String]  = ","

  // The SVG spec allows comma OR whitespace separators between ANY two values.
  // Accepting an optional leading comma before every value makes both dialects
  // parse: Graphviz separates pairs with spaces (`C1,2 3,4 5,6`) while Mermaid
  // separates everything with commas (`C1,2,3,4,5,6`) — the latter previously
  // failed, which silently froze the endpoint-drag preview via its getOrElse
  // fallback (whitespace is consumed by skipWhitespace).
  def coordinate: Parser[Double] = opt(comma) ~> number
  def flag: Parser[Boolean]      = opt(comma) ~> (("0" ^^^ false) | ("1" ^^^ true))

  def coordinatePair: Parser[Coordinate] = coordinate ~ coordinate ^^ {
    case x ~ y => (x, y)
  }

  // Command parsers
  def moveTo: Parser[PathCommand] =
    ("M" | "m") >> { cmdChar =>
      rep1(coordinatePair) ^^ { pairs =>
        PathCommand.MoveTo(cmdChar.head.isUpper, pairs)
      }
    }

  def closePath: Parser[PathCommand] =
    ("Z" | "z") ^^^ PathCommand.ClosePath()

  def lineTo: Parser[PathCommand] =
    ("L" | "l") >> { cmdChar =>
      rep1(coordinatePair) ^^ { pairs =>
        PathCommand.LineTo(cmdChar.head.isUpper, pairs)
      }
    }

  def horizontalLineTo: Parser[PathCommand] =
    ("H" | "h") >> { cmdChar =>
      rep1(coordinate) ^^ { coords =>
        PathCommand.HorizontalLineTo(cmdChar.head.isUpper, coords)
      }
    }

  def verticalLineTo: Parser[PathCommand] =
    ("V" | "v") >> { cmdChar =>
      rep1(coordinate) ^^ { coords =>
        PathCommand.VerticalLineTo(cmdChar.head.isUpper, coords)
      }
    }

  def curveTo: Parser[PathCommand] =
    ("C" | "c") >> { cmdChar =>
      rep1(coordinatePair ~ coordinatePair ~ coordinatePair) ^^ { triplets =>
        val points = triplets.map { case a ~ b ~ c => (a, b, c) }
        PathCommand.CurveTo(cmdChar.head.isUpper, points)
      }
    }

  def smoothCurveTo: Parser[PathCommand] =
    ("S" | "s") >> { cmdChar =>
      rep1(coordinatePair ~ coordinatePair) ^^ { doubles =>
        val points = doubles.map { case a ~ b => (a, b) }
        PathCommand.SmoothCurveTo(cmdChar.head.isUpper, points)
      }
    }

  def quadraticBezierCurveTo: Parser[PathCommand] =
    ("Q" | "q") >> { cmdChar =>
      rep1(coordinatePair ~ coordinatePair) ^^ { doubles =>
        val points = doubles.map { case a ~ b => (a, b) }
        PathCommand.QuadraticBezierCurveTo(cmdChar.head.isUpper, points)
      }
    }

  def smoothQuadraticBezierCurveTo: Parser[PathCommand] =
    ("T" | "t") >> { cmdChar =>
      rep1(coordinatePair) ^^ { pairs =>
        PathCommand.SmoothQuadraticBezierCurveTo(cmdChar.head.isUpper, pairs)
      }
    }

  def ellipticalArc: Parser[PathCommand] =
    ("A" | "a") >> { cmdChar =>
      rep1(coordinate ~ coordinate ~ coordinate ~ flag ~ flag ~ coordinatePair) ^^ { args =>
        val points = args.map { case rx ~ ry ~ angle ~ largeArc ~ sweep ~ point =>
          (rx, ry, angle, largeArc, sweep, point)
        }
        PathCommand.EllipticalArc(cmdChar.head.isUpper, points)
      }
    }

  def command: Parser[PathCommand] =
    moveTo | closePath | lineTo | horizontalLineTo | verticalLineTo |
      curveTo | smoothCurveTo | quadraticBezierCurveTo |
      smoothQuadraticBezierCurveTo | ellipticalArc

  def svgPath: Parser[List[PathCommand]] =
    phrase(rep1(command))

  def parse(input: String): Either[String, List[PathCommand]] =
    parseAll(svgPath, input) match
      case Success(result, _) => Right(result)
      case failure            => Left(s"Error parsing path: $failure")

object SVGPathParser:
  def parse(input: String): Either[String, List[PathCommand]] =
    (new SVGPathParser).parse(input)
