package org.jpablo.graphexplorer.graphviz

/** The output formats this backend can emit. Internal enum used inside
  * [[Graphviz]]; the public [[Graphviz.renderFormats]] entry point remains
  * String-typed to mirror viz-js's `Viz.renderFormats(dot, formats)`
  * contract (the M8 plan is a one-line viewer call-site swap, so signature
  * parity matters more than internal type safety at the boundary).
  */
enum Format(val name: String) derives CanEqual:
  case DotJson extends Format("dot_json")
  case Json0   extends Format("json0")
  case Svg     extends Format("svg")

object Format:
  def fromName(s: String): Option[Format] = values.find(_.name == s)
  val supportedNames: Set[String] = values.iterator.map(_.name).toSet

/** Public-API render outcome — the String values `"success"` / `"failure"`
  * are the viz-js wire contract. Internal code should use this enum and
  * convert at the boundary via [[wire]]. */
enum RenderStatus(val wire: String) derives CanEqual:
  case Success extends RenderStatus("success")
  case Failure extends RenderStatus("failure")
