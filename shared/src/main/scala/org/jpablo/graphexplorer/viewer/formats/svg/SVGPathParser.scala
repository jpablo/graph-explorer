package org.jpablo.graphexplorer.viewer.formats.svg

import scala.util.parsing.combinator.*

enum Command:
  case MoveTo(absolute: Boolean, points: List[(Double, Double)])
  case ClosePath()
  case LineTo(absolute: Boolean, points: List[(Double, Double)])
  case HorizontalLineTo(absolute: Boolean, values: List[Double])
  case VerticalLineTo(absolute: Boolean, values: List[Double])
  case CurveTo(absolute: Boolean, points: List[((Double, Double), (Double, Double), (Double, Double))])
  case SmoothCurveTo(absolute: Boolean, points: List[((Double, Double), (Double, Double))])
  case QuadraticBezierCurveTo(absolute: Boolean, points: List[((Double, Double), (Double, Double))])
  case SmoothQuadraticBezierCurveTo(absolute: Boolean, points: List[(Double, Double)])
  case EllipticalArc(absolute: Boolean, args: List[(Double, Double, Double, Boolean, Boolean, (Double, Double))])

  // Helper to format coordinate pairs
  private def formatPair(p: (Double, Double)): String = s"${p._1},${p._2}"

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

object Command:
  // Convert a list of commands to the full SVG path data string
  def toData(commands: List[Command]): String =
    commands.map(_.asString).mkString(" ")

class SVGPathParser extends RegexParsers:
  override def skipWhitespace = true // Let's handle whitespace automatically

  // Basic parsers
  def number: Parser[Double] = """-?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""".r ^^ { _.toDouble }
  def flag: Parser[Boolean]  = ("0" ^^^ false) | ("1" ^^^ true)
  def comma: Parser[String]  = ","

  // Coordinate parsers
  def coordinate: Parser[Double] = number

  def coordinatePair: Parser[(Double, Double)] = coordinate ~ opt(comma) ~ coordinate ^^ {
    case x ~ _ ~ y => (x, y)
  }

  // Command parsers
  def moveTo: Parser[Command] =
    ("M" | "m") ~> rep1(coordinatePair) ^^ {
      case pairs => Command.MoveTo(pairs.head._1.toString.head.isUpper, pairs)
    }

  def closePath: Parser[Command] =
    ("Z" | "z") ^^^ Command.ClosePath()

  def lineTo: Parser[Command] =
    ("L" | "l") ~> rep1(coordinatePair) ^^ {
      case pairs => Command.LineTo(pairs.head._1.toString.head.isUpper, pairs)
    }

  def horizontalLineTo: Parser[Command] =
    ("H" | "h") ~> rep1(coordinate) ^^ {
      case coords => Command.HorizontalLineTo(coords.head.toString.head.isUpper, coords)
    }

  def verticalLineTo: Parser[Command] =
    ("V" | "v") ~> rep1(coordinate) ^^ {
      case coords => Command.VerticalLineTo(coords.head.toString.head.isUpper, coords)
    }

  def curveTo: Parser[Command] =
    ("C" | "c") ~> rep1(coordinatePair ~ coordinatePair ~ coordinatePair) ^^ {
      case triplets =>
        val points = triplets.map { case a ~ b ~ c => (a, b, c) }
        Command.CurveTo(triplets.head._1._1.toString.head.isUpper, points)
    }

  def smoothCurveTo: Parser[Command] =
    ("S" | "s") ~> rep1(coordinatePair ~ coordinatePair) ^^ {
      case doubles =>
        val points = doubles.map { case a ~ b => (a, b) }
        Command.SmoothCurveTo(doubles.head._1._1.toString.head.isUpper, points)
    }

  def quadraticBezierCurveTo: Parser[Command] =
    ("Q" | "q") ~> rep1(coordinatePair ~ coordinatePair) ^^ {
      case doubles =>
        val points = doubles.map { case a ~ b => (a, b) }
        Command.QuadraticBezierCurveTo(doubles.head._1._1.toString.head.isUpper, points)
    }

  def smoothQuadraticBezierCurveTo: Parser[Command] =
    ("T" | "t") ~> rep1(coordinatePair) ^^ {
      case pairs => Command.SmoothQuadraticBezierCurveTo(pairs.head._1.toString.head.isUpper, pairs)
    }

  def ellipticalArc: Parser[Command] =
    ("A" | "a") ~> rep1(coordinate ~ coordinate ~ coordinate ~ flag ~ flag ~ coordinatePair) ^^ {
      case args =>
        val points = args.map { case rx ~ ry ~ angle ~ largeArc ~ sweep ~ point =>
          (rx, ry, angle, largeArc, sweep, point)
        }
        Command.EllipticalArc(args.head._1.toString.head.isUpper, points)
    }

  def command: Parser[Command] =
    moveTo | closePath | lineTo | horizontalLineTo | verticalLineTo |
      curveTo | smoothCurveTo | quadraticBezierCurveTo |
      smoothQuadraticBezierCurveTo | ellipticalArc

  def svgPath: Parser[List[Command]] =
    phrase(rep1(command))

  def parse(input: String): Either[String, List[Command]] =
    parseAll(svgPath, input) match
      case Success(result, _) => Right(result)
      case failure            => Left(s"Error parsing path: $failure")

object SVGPathParser:
  def parse(input: String): Either[String, List[Command]] =
    (new SVGPathParser).parse(input)

// Example usage
@main def testSVGPathParser(): Unit =
//  val testPath = "M10 10 L 20 20 H 30 V 40 C 10 20 30 40 50 60 Z"
  val testPath = "M325.6,-264.61C298.88,-252.16 262.71,-235.31 234.01,-221.94"
  println(testPath)
  SVGPathParser.parse(testPath) match
    case Right(commands) =>
      println("Successfully parsed:")
      commands.foreach(println)
      println(Command.toData(commands))
    case Left(error) =>
      println(error)
