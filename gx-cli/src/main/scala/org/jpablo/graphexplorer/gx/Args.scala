package org.jpablo.graphexplorer.gx

/** A parsed command line.
  *
  * Hand-rolled rather than a parser library, for one reason: `gx` ships as a
  * native-image binary, and every dependency is one more thing that has to
  * survive it. The surface is a few dozen words; the cost of a library is a
  * standing risk to the build. See D2.1.
  */
final case class Args(
    positional: Vector[String],
    values:     Map[String, String],
    switches:   Set[String]
):
  def has(switch: String): Boolean         = switches.contains(switch)
  def value(name: String): Option[String]  = values.get(name)
  def positionalAt(i: Int): Option[String] = positional.lift(i)
  def json: Boolean                        = has("json")

  /** Each flag on the command line, without the `--`. */
  def flags: Set[String] = switches ++ values.keySet

object Args:
  /** Flags that take a value. Each other word that starts with `--` is a
    * switch. Thus a typo becomes an unknown switch, which the command refuses,
    * and it does not take the next argument as its value.
    */
  private val ValueFlags = Set("label", "tag", "interval")

  /** `--` ends the flags: each word after it is positional. Use it for a
    * note or an element name that starts with `--`.
    */
  def parse(raw: Vector[String]): Either[String, Args] =
    def loop(
        rest:       List[String],
        positional: Vector[String],
        values:     Map[String, String],
        switches:   Set[String]
    ): Either[String, Args] =
      rest match
        case Nil => Right(Args(positional, values, switches))

        case "--" :: tail => Right(Args(positional ++ tail, values, switches))

        case arg :: tail if arg.startsWith("--") =>
          val name = arg.stripPrefix("--")
          if name.contains('=') then
            val (k, v) = name.span(_ != '=')
            loop(tail, positional, values + (k -> v.drop(1)), switches)
          else if ValueFlags.contains(name) then
            tail match
              case v :: more if !v.startsWith("--") =>
                loop(more, positional, values + (name -> v), switches)
              case _ => Left(s"--$name needs a value, for example --$name x")
          else loop(tail, positional, values, switches + name)

        case arg :: tail => loop(tail, positional :+ arg, values, switches)

    loop(raw.toList, Vector.empty, Map.empty, Set.empty)
