package org.jpablo.graphexplorer.graphviz

import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** Public entry point (M7 increment 3): the `renderFormats` facade.
  *
  * Mirrors the slice of viz-js's `Viz.renderFormats(dot, formats)` the
  * viewer uses — a `MultipleRenderResult`-shaped value (`status` /
  * `output` map / `errors`) — so M8 is a one-line call-site swap in
  * [Graphviz.scala](viewer/src/main/scala/org/jpablo/graphexplorer/viewer/backends/graphviz/Graphviz.scala)
  * behind a feature flag (viz-js stays as the oracle + fallback).
  *
  * Pure & cross-compiled: parse → resolve → emit strings. No scalajs / JVM
  * APIs (PORT.md §3); the viewer keeps consuming the strings via its
  * existing `read[SimpleGraph]` / `getEdgePos` / `parseSVG`.
  */
object Graphviz:

  final case class RenderError(level: Option[String], message: String) derives CanEqual

  final case class MultipleRenderResult(
      status: String,                 // "success" | "failure"
      output: Map[String, String],    // requested format → emitted string
      errors: Vector[RenderError]
  ) derives CanEqual

  /** Formats this backend can emit (the slice the viewer requests). */
  val supported: Set[String] = Set("dot_json", "json0", "svg")

  private def emit(f: String, g: org.jpablo.graphexplorer.graphviz.model.RGraph): String =
    f match
      case "dot_json" => Output.dotJson(g)
      case "json0"    => Output.json0(g)
      case "svg"      => Svg.svg(g)
      case other      => throw new IllegalArgumentException(s"unsupported format: $other")

  def renderFormats(dot: String, formats: Seq[String]): MultipleRenderResult =
    DotParser.parse(dot) match
      case Left(err) =>
        MultipleRenderResult("failure", Map.empty, Vector(RenderError(Some("error"), err)))
      case Right(ast) =>
        val unsupported = formats.filterNot(supported.contains)
        if unsupported.nonEmpty then
          MultipleRenderResult(
            "failure", Map.empty,
            unsupported.toVector.map(f => RenderError(Some("error"), s"unsupported format: $f"))
          )
        else
          try
            val g   = AttrResolver.resolve(ast)
            val out = formats.iterator.map(f => f -> emit(f, g)).toMap
            MultipleRenderResult("success", out, Vector.empty)
          catch
            case e: Throwable =>
              MultipleRenderResult(
                "failure", Map.empty,
                Vector(RenderError(Some("error"), Option(e.getMessage).getOrElse(e.toString)))
              )

end Graphviz
