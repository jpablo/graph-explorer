package org.jpablo.graphexplorer.graphviz

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.graphviz.html.*
import org.scalacheck.{Gen, Prop}

/** A property of the writer of HTML-like text labels: the parser reads the
  * written markup back to the same text.
  *
  * The parser joins adjacent runs of the same font and drops empty runs, so
  * the check compares texts with such runs joined. The generator makes empty
  * lines too, also as the last line. The writer keeps each line, so the check
  * expects the same number of lines.
  */
class HtmlWriterPropSpec extends ScalaCheckSuite:

  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(500)

  /** `t` with adjacent runs of the same font joined, and empty runs removed. */
  private def joined(t: HtmlText): HtmlText =
    HtmlText(t.spans.map { span =>
      val items = span.items.filter(_.str.nonEmpty).foldLeft(List.empty[HtmlItem]) {
        case (prev :: rest, item) if prev.font == item.font => prev.copy(str = prev.str + item.str) :: rest
        case (acc, item)                                     => item :: acc
      }
      span.copy(items = items.reverse)
    })

  // Printable characters, with the characters that markup uses.
  private val genChar: Gen[Char] =
    Gen.frequency(
      3 -> Gen.choose(' ', '~'),
      2 -> Gen.oneOf('<', '>', '&', '"', '\'', ';', '/', '=', '#', ' '),
      1 -> Gen.oneOf('é', '•', '−', ' ')
    )

  private val genStr: Gen[String] =
    Gen.frequency(1 -> Gen.const(""), 6 -> Gen.choose(1, 8).flatMap(n => Gen.listOfN(n, genChar).map(_.mkString)))

  private val genFont: Gen[HtmlFont] =
    for
      size      <- Gen.option(Gen.oneOf(8.0, 10.5, 12.0, 14.25, 16.0))
      name      <- Gen.option(Gen.oneOf("Menlo", "Helvetica Neue", "", "a\"b<c>&d"))
      color     <- Gen.option(Gen.oneOf("#104A41", "red", "/blues9/3", "x>\"&<"))
      bold      <- Gen.prob(0.3)
      italic    <- Gen.prob(0.3)
      underline <- Gen.prob(0.2)
      overline  <- Gen.prob(0.2)
      strike    <- Gen.prob(0.2)
      sub       <- Gen.prob(0.15)
      sup       <- Gen.prob(0.15)
    yield HtmlFont(size, name, color, bold, italic, underline, strike, sub, sup, overline)

  // A small pool of fonts makes adjacent runs with the same font frequent.
  private val genItems: Gen[List[HtmlItem]] =
    for
      fonts <- Gen.listOfN(2, genFont)
      n     <- Gen.choose(0, 4)
      items <- Gen.listOfN(n, for s <- genStr; f <- Gen.oneOf(HtmlFont() :: fonts) yield HtmlItem(s, f))
    yield items

  private val genSpan: Gen[HtmlSpan] =
    for
      items <- genItems
      align <- Gen.option(Gen.oneOf(HtmlAlign.values.toList))
    yield HtmlSpan(items, align)

  private val genText: Gen[HtmlText] =
    Gen.choose(1, 4).flatMap(n => Gen.listOfN(n, genSpan)).map(HtmlText.apply)

  property("the parser reads the written markup back to the same text"):
    Prop.forAll(genText) { t =>
      val markup = HtmlWriter.write(t)
      HtmlParser.parse(markup) match
        case Some(HtmlLabel.Text(back)) => assertEquals(joined(back), joined(t), s"markup: $markup")
        case other                      => fail(s"the written markup does not parse as text: $markup -> $other")
    }
