package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.metrics.FontMetrics
import org.jpablo.graphexplorer.graphviz.units.Length
import org.jpablo.graphexplorer.graphviz.units.Length.In
import scala.collection.mutable

/** `record`/`Mrecord` field layout — port of `lib/common/shapes.c`
  * `parse_reclbl` + `size_reclbl`/`resize_reclbl`/`pos_reclbl`/`record_init`
  * (gv 13.0.1).
  *
  * The record label grammar: `f | f | f` (fields at one level), `{ … }`
  * toggles orientation (LR ↔ TB), `<id>` names the port of the next field,
  * `\{ \} \| \< \> \ ` are literal/hard-space escapes. Top-level
  * orientation `LR = !realflip` (TB graph ⇒ horizontal fields).
  *
  * Layout: leaf size = label box + PAD (`XPAD 4·GAP`, `YPAD 2·GAP`,
  * GAP=4) or `margin`; a table sums child sizes along its axis and maxes
  * across; the node min-size grows the tree (`resize_reclbl`, even integer
  * split); `pos_reclbl` assigns boxes from the upper-left, node-local
  * (centre origin, y-up). Verified exact against the 04 `dot` golden
  * (`width`/`height` + per-field `rects`).
  *
  * Scope: the corpus grammar (`|`,`{}`,`<port>`, text). HTML-in-record,
  * exotic `\`-escapes/control/UTF-8 continuation are documented deferrals
  * (no corpus exercise).
  */
object RecordLabel:

  private val GAP        = 4.0
  private val XPad       = 4.0 * GAP // XPAD(d): d.x += 4*GAP
  private val YPad       = 2.0 * GAP // YPAD(d): d.y += 2*GAP
  private val LineSpace  = 1.20
  private val Pt         = 72.0

  // const.h side bits: BOTTOM=1<<0, RIGHT=1<<1, TOP=1<<2, LEFT=1<<3.
  val Bottom = 1
  val Right  = 2
  val Top    = 4
  val Left   = 8
  private val AllSides = Bottom | Right | Top | Left

  /** A field: either a leaf (`text`) or a table (`flds`). Boxes are filled
    * by `layout`, node-local (centre origin, y-up). */
  final class Field(
      val id:   Option[String],
      val text: Option[String],
      val lr:   Boolean,
      val flds: Vector[Field]
  ):
    var sx, sy = 0.0                       // size (points)
    var llx, lly, urx, ury = 0.0           // box (node-local, y-up)
    var sides = 0                          // pos_reclbl: accessible sides bitmask
    def isLeaf: Boolean = flds.isEmpty

  // ── parse_reclbl ─────────────────────────────────────────────────────────
  private final class P(val s: String):
    var i = 0
    def eof: Boolean = i >= s.length
    def cur: Char    = s.charAt(i)

  /** Parse one level; `lr` = this level's orientation. Stops at the
    * matching `}` or end. */
  private def parseLevel(p: P, lr: Boolean): Vector[Field] =
    val fields = mutable.ArrayBuffer.empty[Field]
    val txt    = new StringBuilder
    var portId: Option[String]   = None
    var subTable: Option[Field]  = None
    var inPort = false
    val port   = new StringBuilder

    def pushText(c: Char): Unit =
      // collapse internal whitespace; leading space ignored (none buffered)
      if !(c == ' ' && (txt.isEmpty || txt.last == ' ')) then txt += c
    def flush(): Unit =
      val t = txt.toString.trim
      val f = subTable match
        case Some(st) => new Field(portId, None, st.lr, st.flds)
        case None     => new Field(portId, Some(if t.isEmpty then " " else t), true, Vector.empty)
      fields += f
      txt.clear(); portId = None; subTable = None

    var closed = false
    while !closed && !p.eof do
      val c = p.cur
      if c == '\\' && p.i + 1 < p.s.length then
        p.i += 1
        val n = p.cur
        if n == ' ' then txt += ' ' else txt += n // hard space / literal special
        p.i += 1
      else
        c match
          case '<' => inPort = true; port.clear(); p.i += 1
          case '>' => portId = Some(port.toString.trim); inPort = false; p.i += 1
          case '{' =>
            p.i += 1
            subTable = Some(new Field(None, None, !lr, parseLevel(p, !lr)))
          case '}' => p.i += 1; flush(); closed = true
          case '|' => p.i += 1; flush()
          case _ =>
            if inPort then port += c else pushText(c)
            p.i += 1
    if !closed then flush()
    fields.toVector

  /** Parse `label` into a record tree. `topLR` = `!realflip`. */
  def parse(label: String, topLR: Boolean): Field =
    new Field(None, None, topLR, parseLevel(new P(label), topLR))

  // ── size_reclbl ──────────────────────────────────────────────────────────
  private def textDimen(text: String, fontSizePt: Double, fontName: String): (Double, Double) =
    val bold   = fontName.toLowerCase.contains("bold")
    val italic = fontName.toLowerCase.contains("italic") || fontName.toLowerCase.contains("oblique")
    val lines  = if text.isEmpty then List("") else text.split("\\\\n|\\\\l|\\\\r", -1).toList
    val w = lines.iterator.map(l =>
      if l.isEmpty then 0.0 else fontSizePt * FontMetrics.estimateTextWidth1pt(fontName, l, bold, italic)
    ).maxOption.getOrElse(0.0)
    val h = lines.iterator.map(l =>
      if l.isEmpty then (fontSizePt * LineSpace).toInt.toDouble else fontSizePt * LineSpace
    ).sum
    (w, h)

  private def sizeOf(f: Field, fontSizePt: Double, fontName: String, margin: Option[(Double, Double)]): Unit =
    if f.isLeaf then
      val (tw, th) = textDimen(f.text.getOrElse(" "), fontSizePt, fontName)
      if tw > 0.0 || th > 0.0 then
        margin match
          case Some((mx, my)) => f.sx = tw + 2 * mx; f.sy = th + 2 * my
          case None           => f.sx = tw + XPad;   f.sy = th + YPad
      else { f.sx = tw; f.sy = th }
    else
      var x = 0.0; var y = 0.0
      f.flds.foreach { c =>
        sizeOf(c, fontSizePt, fontName, margin)
        if f.lr then { x += c.sx; y = math.max(y, c.sy) }
        else { y += c.sy; x = math.max(x, c.sx) }
      }
      f.sx = x; f.sy = y

  // ── resize_reclbl: grow tree to `sz`, even integer split ─────────────────
  private def resize(f: Field, sx: Double, sy: Double): Unit =
    f.sx = sx; f.sy = sy
    if f.flds.nonEmpty then
      val n   = f.flds.length
      val d   = if f.lr then sx - f.flds.iterator.map(_.sx).sum
                else sy - f.flds.iterator.map(_.sy).sum
      val inc = d / n
      var i = 0
      while i < n do
        val amt = math.floor((i + 1) * inc).toInt - math.floor(i * inc).toInt
        val c   = f.flds(i)
        if f.lr then resize(c, c.sx + amt, sy) else resize(c, sx, c.sy + amt)
        i += 1

  // ── pos_reclbl: boxes from upper-left, node-local (centre origin) ────────
  // `sides` = the set of record-perimeter sides this field is exposed on,
  // propagated to children via the same first/middle/last masks Graphviz
  // uses (LR table vs TB table). `record_port` passes a leaf's `sides` to
  // `compassPort`, which is what makes `struct2:b:n` (b has no TOP) fall to
  // the `record_path` pbox branch instead of the side-anchored one.
  private def pos(f: Field, ulx: Double, uly: Double, sides: Int): Unit =
    f.sides = sides
    f.llx = ulx; f.lly = uly - f.sy; f.urx = ulx + f.sx; f.ury = uly
    var x = ulx; var y = uly
    val last = f.flds.length - 1
    f.flds.iterator.zipWithIndex.foreach { case (c, i) =>
      val mask =
        if sides == 0 then 0
        else if f.lr then
          if i == 0 then (if i == last then AllSides else Top | Bottom | Left)
          else if i == last then Top | Bottom | Right
          else Top | Bottom
        else if i == 0 then (if i == last then AllSides else Top | Right | Left)
        else if i == last then Left | Bottom | Right
        else Left | Right
      pos(c, x, y, sides & mask)
      if f.lr then x += c.sx else y -= c.sy
    }

  /** Full record layout. @return (nodeWidthIn, nodeHeightIn, root field
    * with boxes filled, node-local centre-origin y-up). */
  def layout(
      label: String, topLR: Boolean, fontSizePt: Double, fontName: String,
      minWIn: Double, minHIn: Double, fixed: Boolean, margin: Option[(Double, Double)]
  ): (In, In, Field) =
    val root = parse(label, topLR)
    sizeOf(root, fontSizePt, fontName, margin)
    val mw = minWIn * Pt; val mh = minHIn * Pt
    val sx = if fixed then mw else math.max(root.sx, mw)
    val sy = if fixed then mh else math.max(root.sy, mh)
    resize(root, sx, sy)
    pos(root, -sx / 2.0, sy / 2.0, AllSides)
    (In(root.sx / Pt), In((root.sy + 1.0) / Pt), root) // record_init: height += 1pt kluge

  /** Resolve a port id to its field (`map_rec_port`), node-local boxes set. */
  def field(root: Field, portId: String): Option[Field] =
    if root.id.contains(portId) then Some(root)
    else root.flds.iterator.flatMap(f => field(f, portId)).nextOption()

  /** Resolve a port id to its field box (node-local). */
  def fieldBox(root: Field, portId: String): Option[(Double, Double, Double, Double)] =
    field(root, portId).map(f => (f.llx, f.lly, f.urx, f.ury))

end RecordLabel
