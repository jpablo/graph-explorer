package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.{AttributeId, Attributes}

import scala.util.Try

/** The init directive of a Mermaid diagram: `%%{init: {...}}%%`.
  *
  * The directive keeps the global properties of the diagram. Mermaid reads it
  * when it draws, and the file keeps it. The graph keeps four properties in
  * their own graph attributes, so that the Diagram panel can change them:
  *
  *   - `theme` in [[MermaidAttrKeys.MermaidThemeAttr]]
  *   - `themeVariables.background` in [[MermaidAttrKeys.MermaidBackgroundAttr]]
  *   - `themeVariables.fontSize` in [[MermaidAttrKeys.MermaidFontSizeAttr]], with no `px`
  *   - `themeVariables.fontFamily` in [[MermaidAttrKeys.MermaidFontFamilyAttr]]
  *
  * The graph also keeps the full directive, as JSON text, in
  * [[MermaidAttrKeys.MermaidInitAttr]]. The printer starts from that JSON and
  * writes the four properties over it. Thus the printer keeps the keys that
  * the panel does not know, and the order of the keys.
  */
object MermaidInitDirective:

  /** The theme names of Mermaid 11. */
  val Themes: Vector[String] = Vector("default", "neutral", "dark", "forest", "base")

  private val ThemeKey     = "theme"
  private val VariablesKey = "themeVariables"
  private val FontSizeKey  = "fontSize"

  /** The theme variables that have a graph attribute, with that attribute. */
  private val VariableAttrs: Vector[(String, AttributeId)] = Vector(
    "background" -> MermaidBackgroundAttr,
    FontSizeKey  -> MermaidFontSizeAttr,
    "fontFamily" -> MermaidFontFamilyAttr
  )

  // Mermaid 11.12 (`detectDirective`) reads each `%%{type: args}%%`, also over
  // more than one line. The type is `init` or `initialize`.
  private val Directive = raw"(?s)%%\{\s*(?:init|initialize)\s*:\s*(.*?)\s*\}%%".r

  /** The merged arguments of each init directive of `text`, as JSON text.
    *
    * As Mermaid, the function reads a single quote as a double quote, and it
    * merges two directives key by key. A directive that is not a JSON object
    * gives nothing, as in Mermaid, which only logs an error for it.
    */
  def configOf(text: String): Option[String] =
    val configs = Directive.findAllMatchIn(text).flatMap(m => parseObject(m.group(1).replace('\'', '"'))).toList
    configs.reduceOption(merge).map(render)

  /** True when `directive` is one init directive that the printer writes
    * back. `directive` is the full text from `%%{` to `}%%`. The arguments
    * must be a JSON object, after a single quote becomes a double quote, as in
    * [[configOf]]. Thus the function is false for a directive of another type
    * and for arguments that are not a JSON object.
    */
  def isKept(directive: String): Boolean =
    directive.trim match
      case Directive(args) => parseObject(args.replace('\'', '"')).isDefined
      case _               => false

  /** The `themeVariables.background` of the directive of `text`. The canvas
    * paints the sheet behind the drawing with it.
    */
  def backgroundOf(text: String): Option[String] =
    configOf(text).flatMap(parseObject).flatMap(variableOf(_, "background"))

  /** The `theme` of the directive of `text`. */
  def themeOf(text: String): Option[String] =
    configOf(text).flatMap(parseObject).flatMap(_.value.get(ThemeKey)).flatMap(_.strOpt).filter(_.nonEmpty)

  /** The graph attributes of a directive. `config` is the JSON text of [[configOf]]. */
  def toAttributes(config: String): Vector[(AttributeId, AttrValue)] =
    parseObject(config).toVector.flatMap: obj =>
      val theme = obj.value.get(ThemeKey).flatMap(_.strOpt).filter(_.nonEmpty).map(MermaidThemeAttr -> AttrValue(_))
      val variables = VariableAttrs.flatMap: (key, attrId) =>
        variableOf(obj, key).map(v => attrId -> AttrValue(if key == FontSizeKey then v.stripSuffix("px") else v))
      (MermaidInitAttr -> AttrValue(config)) +: (theme.toVector ++ variables)

  /** The directive line of the graph attributes, with no line break. `None`
    * when the graph has no global property: then the printer writes no
    * directive.
    */
  def line(graphAttributes: Attributes): Option[String] =
    def valueOf(attrId: AttributeId) = graphAttributes.get(attrId).map(_.toString.trim).filter(_.nonEmpty)
    val obj = graphAttributes.get(MermaidInitAttr).flatMap(v => parseObject(v.toString)).getOrElse(ujson.Obj())
    put(obj, ThemeKey, valueOf(MermaidThemeAttr))
    val variables = obj.value.get(VariablesKey).flatMap(_.objOpt).map(ujson.Obj.from).getOrElse(ujson.Obj())
    VariableAttrs.foreach: (key, attrId) =>
      put(variables, key, valueOf(attrId).map(v => if key == FontSizeKey then withPx(v) else v))
    if variables.value.nonEmpty then obj(VariablesKey) = variables
    else obj.value.remove(VariablesKey)
    Option.when(obj.value.nonEmpty)(s"%%{init: ${render(obj)}}%%")

  /** Writes `value` at `key`, or removes `key` when there is no value. An
    * update keeps the place of the key in the object.
    */
  private def put(obj: ujson.Obj, key: String, value: Option[String]): Unit =
    value match
      case Some(v) => obj(key) = ujson.Str(v)
      case None    => obj.value.remove(key)

  /** A font size that is a number gets the unit `px`, as Mermaid wants it. */
  private def withPx(size: String): String =
    if size.toDoubleOption.isDefined then s"${size}px" else size

  private def variableOf(obj: ujson.Obj, key: String): Option[String] =
    obj.value.get(VariablesKey).flatMap(_.objOpt).flatMap(_.get(key)).flatMap:
      case ujson.Str(s) => Some(s).filter(_.nonEmpty)
      case ujson.Num(n) => Some(if n.isWhole then n.toLong.toString else n.toString)
      case _            => None

  private def parseObject(json: String): Option[ujson.Obj] =
    Try(ujson.read(json)).toOption.flatMap(_.objOpt).map(ujson.Obj.from)

  /** `b` over `a`, key by key. An object in both is merged too. */
  private def merge(a: ujson.Obj, b: ujson.Obj): ujson.Obj =
    val out = ujson.Obj.from(a.value)
    b.value.foreach: (key, value) =>
      (out.value.get(key), value) match
        case (Some(left: ujson.Obj), right: ujson.Obj) => out(key) = merge(left, right)
        case _                                         => out(key) = value
    out

  /** JSON on one line, with a space after each `:` and `,`, so that a person
    * can read the directive in the source.
    */
  private def render(value: ujson.Value): String =
    value match
      case obj: ujson.Obj =>
        obj.value.iterator.map((k, v) => s"${ujson.write(ujson.Str(k))}: ${render(v)}").mkString("{", ", ", "}")
      case arr: ujson.Arr => arr.value.iterator.map(render).mkString("[", ", ", "]")
      case other          => ujson.write(other)
