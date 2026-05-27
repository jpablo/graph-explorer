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
  * The public signature stays String-typed (`formats: Seq[String]`,
  * `status: String`) to preserve viz-js parity; the [[Format]] and
  * [[RenderStatus]] enums are used internally and converted at the
  * boundary.
  *
  * Pure & cross-compiled: parse → resolve → emit strings. No scalajs / JVM
  * APIs (PORT.md §3); the viewer keeps consuming the strings via its
  * existing `read[SimpleGraph]` / `getEdgePos` / `parseSVG`.
  */
object Graphviz:

  final case class RenderError(level: Option[String], message: String) derives CanEqual

  final case class MultipleRenderResult(
      status: String,                 // RenderStatus.wire — "success" | "failure"
      output: Map[String, String],    // requested format name → emitted string
      errors: Vector[RenderError]
  ) derives CanEqual

  /** Formats this backend can emit (the slice the viewer requests). */
  val supported: Set[String] = Format.supportedNames

  private def emit(f: Format, g: org.jpablo.graphexplorer.graphviz.model.RGraph): String =
    f match
      case Format.DotJson => Output.dotJson(g)
      case Format.Json0   => Output.json0(g)
      case Format.Svg     => Svg.svg(g)

  private def failure(errors: Vector[RenderError]): MultipleRenderResult =
    MultipleRenderResult(RenderStatus.Failure.wire, Map.empty, errors)

  def renderFormats(dot: String, formats: Seq[String]): MultipleRenderResult =
    DotParser.parse(dot) match
      case Left(err) =>
        failure(Vector(RenderError(Some("error"), err)))
      case Right(ast) =>
        val parsed: Seq[(String, Option[Format])] =
          formats.map(name => name -> Format.fromName(name))
        val unsupported = parsed.collect { case (name, None) => name }
        if unsupported.nonEmpty then
          failure(unsupported.toVector.map(f => RenderError(Some("error"), s"unsupported format: $f")))
        else
          try
            val g   = AttrResolver.resolve(ast)
            val out = parsed.iterator.collect {
              case (name, Some(fmt)) => name -> emit(fmt, g)
            }.toMap
            MultipleRenderResult(RenderStatus.Success.wire, out, Vector.empty)
          catch
            case e: Throwable =>
              failure(Vector(RenderError(Some("error"), Option(e.getMessage).getOrElse(e.toString))))

end Graphviz
