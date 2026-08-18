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

  /** The graph's STRUCTURE, without laying it out (P8 / D2.3).
    *
    * `parse -> resolve` is shared with [[renderFormats]] verbatim, so this is
    * not a second reading of the DOT language: attribute scoping, node
    * deduplication, edge chains, ports, cluster membership and cgraph's
    * `agfstout` edge ordering all come from the same `AttrResolver` the layout
    * path uses, and are already oracle-verified by the corpus sweep.
    *
    * The only thing skipped is the bounding box — see
    * `Output.dotJsonStructure`. On the largest corpus file that is ~2ms against
    * ~89ms, because a query like "what nodes exist" was paying for a full `dot`
    * layout to answer.
    */
  def structureJson(dot: String): Either[String, String] =
    DotParser.parse(dot) match
      case Left(err) => Left(err)
      case Right(ast) =>
        try Right(Output.dotJsonStructure(AttrResolver.resolve(ast)))
        catch case e: Throwable => Left(Option(e.getMessage).getOrElse(e.toString))
        finally
          // Same hygiene as renderFormats: the doc memo keys on the RGraph, so
          // a long-lived session would otherwise pin the last graph read.
          org.jpablo.graphexplorer.graphviz.layout.GraphMemo.clearAll()

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
          finally
            // Every call parses a fresh RGraph, so the stage memos can never
            // hit across calls — drop them so the last graph's full layout
            // isn't pinned for the life of a long-lived (browser) session.
            org.jpablo.graphexplorer.graphviz.layout.GraphMemo.clearAll()

end Graphviz
