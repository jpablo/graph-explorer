package org.jpablo.graphexplorer.viewer.backends.mermaid

import scala.util.matching.Regex

/** The images of the image shapes of a Mermaid text: `id@{ img: "...", ... }`.
  *
  * Mermaid loads each image before it draws (`imageSquare` waits for `img.decode()`), and an image that does not load
  * stops the whole drawing. A path relative to the diagram (`arch.assets/img-3f2a.png`) does not load in the page.
  * Thus the text for the renderer has a different image source, and the source of the diagram keeps the path.
  *
  * The scan reads the text, and does not parse it. It looks only inside the shape data (`@{ ... }`). There, a value in
  * quotes has no `"`, because Mermaid ends a string at the next quote, and the text has `#quot;` for a quote.
  */
object MermaidImageSources:

  private val ShapeData = """@\{[^}]*\}""".r
  private val Img       = """(\bimg\s*:\s*)(?:"([^"]*)"|'([^']*)')""".r

  private def srcOf(m: Regex.Match): String =
    MermaidSourceScan.decodeQuotes(Option(m.group(2)).getOrElse(m.group(3)))

  /** The image sources of `text`, in their order. */
  def sources(text: String): List[String] =
    if !text.contains("img") then Nil
    else ShapeData.findAllIn(text).flatMap(block => Img.findAllMatchIn(block).map(srcOf)).toList

  /** `text` with the source of each image that `to` gives a new source for. The rest of the text stays the same. */
  def rewrite(text: String, to: String => Option[String]): String =
    if !text.contains("img") then text
    else
      ShapeData.replaceAllIn(
        text,
        block =>
          Regex.quoteReplacement(
            Img.replaceAllIn(
              block.matched,
              m =>
                Regex.quoteReplacement(
                  to(srcOf(m)).fold(m.matched)(src => s"""${m.group(1)}"${MermaidSourceScan.encodeQuotes(src)}"""")
                )
            )
          )
      )
