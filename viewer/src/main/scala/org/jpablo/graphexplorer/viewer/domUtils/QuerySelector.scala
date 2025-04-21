package org.jpablo.graphexplorer.viewer.domUtils

/** A Match Type that maps a literal string type representing a tag name to the corresponding Scala.js DOM Element type.
  */
type ElementTypeForSelector[S <: String] <: dom.Element = S match
  // HTML Elements
  case "a"        => dom.html.Anchor
  case "button"   => dom.html.Button
  case "div"      => dom.html.Div
  case "span"     => dom.html.Span
  case "p"        => dom.html.Paragraph
  case "input"    => dom.html.Input
  case "select"   => dom.html.Select
  case "option"   => dom.html.Option
  case "textarea" => dom.html.TextArea
  case "img"      => dom.html.Image
  case "canvas"   => dom.html.Canvas
  case "table"    => dom.html.Table
  case "tr"       => dom.html.TableRow
  case "td"       => dom.html.TableCell
//  case "th"       => dom.html.TableHeaderCellTa
  case "ul"    => dom.html.UList
  case "ol"    => dom.html.OList
  case "li"    => dom.html.LI
  case "form"  => dom.html.Form
  case "label" => dom.html.Label
  case "h1"    => dom.html.Heading
  case "h2"    => dom.html.Heading
  case "h3"    => dom.html.Heading
  case "h4"    => dom.html.Heading
  case "h5"    => dom.html.Heading
  case "h6"    => dom.html.Heading
  // SVG Elements
  case "svg"      => dom.svg.SVG
  case "g"        => dom.svg.G
  case "path"     => dom.svg.Path
  case "rect"     => dom.svg.RectElement
  case "circle"   => dom.svg.Circle
  case "ellipse"  => dom.svg.Ellipse
  case "line"     => dom.svg.Line
  case "text"     => dom.svg.Text
  case "polygon"  => dom.svg.Polygon
  case "polyline" => dom.svg.Polyline
  // Fallback for unknown tags or more complex selectors (like "div.my-class")
  case _ => dom.Element
end ElementTypeForSelector

// Extension method using transparent inline and the match type
extension (node: dom.NodeSelector)

  /** Type-safe querySelector that infers the return type based on a literal tag name selector.
    *
    * Usage: val maybeAnchor = node.querySelectorT("a") // Inferred type: Option[dom.html.Anchor] val maybePath =
    * node.querySelectorT("path") // Inferred type: Option[dom.svg.Path] val maybeDiv = node.querySelectorT("div.some-class") // Inferred
    * type: Option[dom.Element] (fallback)
    *
    * @param selectors
    *   A compile-time constant string literal representing the CSS selector. For precise type inference, this should be a simple tag name
    *   (e.g., "a", "div", "path"). For other selectors or non-literal strings, a compile-time error occurs.
    * @return
    *   An Option containing the found element, cast to the inferred type, or None if not found.
    * @throws scala.compiletime.error
    *   If the selector is not a compile-time constant string literal.
    * @throws ClassCastException
    *   at runtime if the found element does not match the inferred type (e.g., DOM structure mismatch).
    */
  transparent inline def querySelectorT(selectors: String): Option[ElementTypeForSelector[selectors.type]] =
    // 1. Check at compile time if 'selectors' is a literal string
    inline scala.compiletime.constValueOpt[selectors.type] match
      case Some(_) => // Yes, it's a compile-time constant string
        val resultOpt = Option(node.querySelector(selectors))
        // 2. Cast the result to the type inferred by ElementTypeForSelector.
        // The `transparent inline` ensures the method signature reflects this specific type.
        // The cast is safe *if* the DOM matches the selector's expected type.
        // If the selector was complex (e.g., "div.foo") or unknown ("my-tag"),
        // ElementTypeForSelector falls back to dom.Element, so the cast is still to dom.Element.
        resultOpt.map(_.asInstanceOf[ElementTypeForSelector[selectors.type]])

      case None =>
        // No, 'selectors' is not a compile-time constant string (it's a variable, etc.)
        // We cannot guarantee the type inference, so we make it a compile-time error.
        // The user should use the original querySelector or the querySelectorT[T] variant.
        scala.compiletime.error(
          "Selector must be a compile-time constant string literal for type inference using querySelectorT. " +
            "Use querySelector directly or provide an explicit type parameter with a different method (like your querySelectorT[T]) for dynamic selectors."
        )

  def querySelectorAllT[T <: scalajs.js.Object](selectors: String): Seq[T] =
    node.querySelectorAll(selectors).map(_.asInstanceOf[T]).toSeq

end extension
