package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import org.jpablo.graphexplorer.viewer.backends.graphviz.*
import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.*

// https://github.com/mdaines/viz-js

@js.native
@JSImport("@viz-js/viz", JSImport.Namespace)
object VizJS extends js.Object:

  val graphvizVersion: String   = js.native
  val formats: js.Array[String] = js.native
  val engines: js.Array[String] = js.native

  def instance(): js.Promise[Viz] = js.native

@js.native
@JSImport("@viz-js/viz", "Viz")
class Viz extends js.Object:

  def graphvizVersion: String   = js.native
  def formats: js.Array[String] = js.native
  def engines: js.Array[String] = js.native

  def render(input:           String | Graph, options: js.UndefOr[RenderOptions] = js.undefined): RenderResult = js.native
  def renderString(input:     String | Graph, options: js.UndefOr[RenderOptions] = js.undefined): String       = js.native
  def renderSVGElement(input: String | Graph, options: js.UndefOr[RenderOptions] = js.undefined): dom.svg.SVG  = js.native
  def renderJSON(input:       String | Graph, options: js.UndefOr[RenderOptions] = js.undefined): js.Object    = js.native
  def renderFormats(
      input:   String | Graph,
      formats: js.Array[String],
      options: js.UndefOr[RenderOptions] = js.undefined
  ): MultipleRenderResult = js.native

@js.native
trait RenderOptions extends js.Object:
  val format: js.UndefOr[String]              = js.native
  val engine: js.UndefOr[String]              = js.native
  val yInvert: js.UndefOr[Boolean]            = js.native
  val reduce: js.UndefOr[Boolean]             = js.native
  val graphAttributes: js.UndefOr[Attributes] = js.native
  val nodeAttributes: js.UndefOr[Attributes]  = js.native
  val edgeAttributes: js.UndefOr[Attributes]  = js.native
  val images: js.UndefOr[js.Array[ImageSize]] = js.native

object RenderOptions:
  def apply(
      format:          js.UndefOr[String] = js.undefined,
      engine:          js.UndefOr[String] = js.undefined,
      yInvert:         js.UndefOr[Boolean] = js.undefined,
      reduce:          js.UndefOr[Boolean] = js.undefined,
      graphAttributes: js.UndefOr[Attributes] = js.undefined,
      nodeAttributes:  js.UndefOr[Attributes] = js.undefined,
      edgeAttributes:  js.UndefOr[Attributes] = js.undefined,
      images:          js.UndefOr[js.Array[ImageSize]] = js.undefined
  ): RenderOptions =
    val obj = js.Dynamic.literal()
    format.foreach(obj.updateDynamic("format")(_))
    engine.foreach(obj.updateDynamic("engine")(_))
    yInvert.foreach(obj.updateDynamic("yInvert")(_))
    reduce.foreach(obj.updateDynamic("reduce")(_))
    graphAttributes.foreach(obj.updateDynamic("graphAttributes")(_))
    nodeAttributes.foreach(obj.updateDynamic("nodeAttributes")(_))
    edgeAttributes.foreach(obj.updateDynamic("edgeAttributes")(_))
    images.foreach(obj.updateDynamic("images")(_))
    obj.asInstanceOf[RenderOptions]

@js.native
trait RenderResult extends js.Object:
  val status: String                = js.native // "success" | "failure"
  val output: String                = js.native // SVG string or JSON string
  val errors: js.Array[RenderError] = js.native // Array of errors if status is "failure"

@js.native
trait MultipleRenderResult extends js.Object:
  val status: String                = js.native // "success" | "failure"
  val output: js.Dictionary[String] = js.native
  val errors: js.Array[RenderError] = js.native

//trait FailureResult extends RenderResult:
//  val status: "failure"
//  val output: js.UndefOr[Nothing]
//  val errors: js.Array[RenderError]

@js.native
trait RenderError extends js.Object:
  val level: js.UndefOr[String] = js.native // "error" | "warning"
  val message: String           = js.native

object RenderError:
  def apply(level: js.UndefOr[String] = js.undefined, message: String): RenderError =
    val obj = js.Dynamic.literal(message = message)
    level.foreach(obj.updateDynamic("level")(_))
    obj.asInstanceOf[RenderError]
