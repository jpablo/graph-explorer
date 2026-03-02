package org.jpablo.graphexplorer.viewer.backends.mermaid

object MermaidClassDefFallback:
  private val MermaidClassDefPrefix = "classdef"

  /** Mermaid parser output can occasionally omit classDef entries (notably `default`) depending on runtime version.
    * Merge missing classDefs from raw source text while keeping parser-provided entries authoritative when present.
    */
  def withSourceClassDefs(
      sourceText: String,
      classDefs:  Map[String, MermaidClassDef]
  ): Map[String, MermaidClassDef] =
    extractClassDefsFromText(sourceText).foldLeft(classDefs) { case (acc, (className, fromSource)) =>
      acc.get(className) match
        case Some(existing) if existing.styles.nonEmpty || existing.textStyles.nonEmpty => acc
        case _                                                                           => acc + (className -> fromSource)
    }

  /** Parses Mermaid `classDef` declarations from text.
    *
    * Supports declarations with multiple class names: `classDef a,b fill:#fff,stroke:#333`.
    */
  def extractClassDefsFromText(sourceText: String): Map[String, MermaidClassDef] =
    sourceText.linesIterator
      .map(_.trim)
      .filter(line => line.toLowerCase.startsWith(MermaidClassDefPrefix))
      .flatMap { line =>
        val withoutPrefix = line.drop(MermaidClassDefPrefix.length).trim
        val firstSpace    = withoutPrefix.indexOf(' ')
        if firstSpace < 0 then Nil
        else
          val rawNames    = withoutPrefix.substring(0, firstSpace).trim
          val body        = withoutPrefix.substring(firstSpace + 1).trim.stripSuffix(";")
          val classNames  = rawNames.split(",").map(_.trim).filter(_.nonEmpty)
          val declarations =
            body
              .split(",")
              .toList
              .map(_.trim)
              .filter(fragment => fragment.contains(":"))

          if declarations.nonEmpty then
            classNames.map(_ -> declarations)
          else Nil
      }
      .foldLeft(Map.empty[String, MermaidClassDef]) { case (acc, (className, declarations)) =>
        val mergedStyles = acc.get(className).map(_.styles).getOrElse(Nil) ++ declarations
        acc + (className -> MermaidClassDef(styles = mergedStyles))
      }
