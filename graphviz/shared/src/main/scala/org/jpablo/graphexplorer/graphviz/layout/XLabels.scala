package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.html.{HtmlLayout, HtmlParser}
import org.jpablo.graphexplorer.graphviz.model.{REdge, RGraph}
import scala.collection.mutable

/** External-label (`xlabel`) placement — port of `addXLabels`
  * (lib/common/postproc.c:402) + `placeLabels`/`xladjust`
  * (lib/label/xlabels.c), gv 13.0.1.
  *
  * Runs in the CANONICAL frame after splines (gv_postprocess order: graph
  * label positions → addXLabels → root-label bb pad → translation). Obstacles
  * are every node, every SET label (dot edge labels), and a size-0 anchor
  * point per unset external label (its edge midpoint); each xlabel tries the
  * 9 corner/side positions around its anchor (left/mid/right × top/mid/bottom
  * in xladjust's exact order, first zero-overlap wins) then the two sliding
  * scans, else the least-overlap position (kept when `forcelabels`, default
  * true).
  *
  * gv finds the intersection candidates with a Hilbert-loaded R-tree. The
  * port uses the same R-tree (see [[RTree]]), because the order of the leaf
  * list changes the result on the no-zero-overlap path: `intrsx` keeps the
  * first object of bucket 5. Until 2026-11-23 the port used a linear scan in
  * object order. That order is correct only by chance.
  *
  * The labels at the ends of an edge (`headlabel`/`taillabel`) have two
  * paths, the same as in gv:
  *  - When the graph declares `labelangle` or `labeldistance` for edges,
  *    `place_portlabel` (lib/common/splines.c:1338) puts the label of each
  *    edge that has a value for one of them. The dotsplines.c:454 loop calls
  *    it after edge_normalize. Such a label is SET: addXLabels uses it as an
  *    obstacle.
  *  - Each other end label is an external label. Its anchor is the end of the
  *    spline (`edgeTailpoint`/`edgeHeadpoint`, postproc.c:240).
  * Per out-edge, the object order is label, tail label, head label, xlabel
  * (postproc.c:491-552). The set labels of the clusters come after all
  * nodes and edges (addClusterObj, postorder).
  */
object XLabels:

  /** A placed external label: canonical-frame CENTER (`lp->pos` after
    * `centerPt`) + FINAL-frame dimen (unswapped `lp->dimen`). */
  final case class Placed(cx: Double, cy: Double, w: Double, h: Double)
  /** @param edges  the placed `xlabel`s, by edge index.
    * @param heads  the placed `headlabel`s, by edge index (both paths).
    * @param tails  the placed `taillabel`s, by edge index (both paths).
    * @param stray  boxes that only grow the graph bb. See [[strayHeads]]. */
  final case class Result(
      nodes: Map[String, Placed],
      edges: Map[Int, Placed],
      heads: Map[Int, Placed] = Map.empty,
      tails: Map[Int, Placed] = Map.empty,
      stray: Vector[Placed]   = Vector.empty
  ):
    def isEmpty: Boolean = nodes.isEmpty && edges.isEmpty && heads.isEmpty && tails.isEmpty
    /** Every placed label. Each one grows the canonical bb (updateBB). */
    def all: Iterator[Placed] =
      nodes.valuesIterator ++ edges.valuesIterator ++ heads.valuesIterator ++ tails.valuesIterator

  /** The text and the font of a `headlabel` or a `taillabel`
    * (common_init_edge, utils.c:530; initFontLabelEdgeAttr, utils.c:461).
    * `labelfontsize`/`labelfontname`/`labelfontcolor` default to the font of
    * the edge. `text` is the RAW attribute. A plain end label goes through
    * make_label as each other plain label: [[NodeSize.labelLinesJust]]
    * decodes its entities (htmlEntityUTF8) for the size and the drawn text. */
  final case class EndLabel(text: String, html: Boolean, fontSize: Double, fontName: String, fontColor: String)

  /** C `strtod` on the start of `s`: the longest numeric prefix, or None. */
  private val NumPrefix = """^\s*([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)""".r.unanchored
  private def strtod(s: String): Option[Double] =
    s match
      case NumPrefix(n) => n.toDoubleOption
      case _            => None

  /** late_double (utils.c:50): the default for a missing, empty or bad
    * value, and the minimum for a value below it. */
  private[graphviz] def lateDouble(v: Option[String], dflt: Double, min: Double): Double =
    v.filter(_.nonEmpty).flatMap(strtod) match
      case Some(d) => if d < min then min else d
      case None    => dflt

  /** The end label of edge `e`: `head` selects `headlabel`, else `taillabel`.
    * None when the edge has no such label or its value is empty. */
  def endLabel(e: REdge, head: Boolean): Option[EndLabel] =
    val key = if head then "headlabel" else "taillabel"
    e.attrs.get(key).filter(_.nonEmpty).map { text =>
      def nn(k: String): Option[String] = e.attrs.get(k).filter(_.nonEmpty)
      val fs  = lateDouble(e.attrs.get("fontsize"), 14.0, 1.0)
      val fn  = nn("fontname").getOrElse("Times-Roman")
      val fc  = nn("fontcolor").getOrElse("black")
      EndLabel(text, e.attrs.isHtml(key),
        lateDouble(e.attrs.get("labelfontsize"), fs, 1.0),
        nn("labelfontname").getOrElse(fn),
        nn("labelfontcolor").getOrElse(fc))
    }

  /** True when the graph declares `labelangle` or `labeldistance` for edges
    * (`E_labelangle || E_labeldistance`). Then the dotsplines.c:454 loop runs.
    * The port sees a declaration only on an edge that has the key. */
  private def portLabelsDeclared(g: RGraph): Boolean =
    g.edges.exists(e => e.attrs.toMap.contains("labelangle") || e.attrs.toMap.contains("labeldistance"))

  private val placeMemo = GraphMemo[Result]()
  def place(g: RGraph): Result = placeMemo(g)(placeImpl(g))

  // xlabels.h: candidate/neighbour grid indices — 6 7 8 / 3 4 5 / 0 1 2,
  // the object of interest at 4; getintrsxi's -1 falls back to bucket 5.
  private val XLPXPY = 0; private val XLCXPY = 1; private val XLNXPY = 2
  private val XLPXCY = 3; private val XLNXCY = 5
  private val XLPXNY = 6; private val XLCXNY = 7; private val XLNXNY = 8
  private val XLXDENOM = 8.0
  private val XLYDENOM = 2.0

  /** xlabel_t: sz is canonical-swapped; pos = box LL corner (mutated during
    * placement). */
  /** The object that owns an external label. */
  private enum Owner:
    case NodeX(id: String)
    case EdgeX(idx: Int)
    case Head(idx: Int)
    case Tail(idx: Int)

  private final class XL(val szx: Double, val szy: Double, val owner: Owner):
    var px, py = 0.0
    var set    = false
  /** object_t: pos = box LL corner; size-0 = an edge-label anchor point. */
  private final class Obj(val px: Double, val py: Double, val sx: Double, val sy: Double, val lbl: XL,
                          val anchor: Boolean = false)

  private type Rect = (Double, Double, Double, Double) // llx, lly, urx, ury

  /** C round(): half away from zero (Scala's math.round is half-up). */
  private def cRound(v: Double): Double =
    if v < 0 then -math.round(-v).toDouble else math.round(v).toDouble

  private def objp2rect(o: Obj): Rect =
    (cRound(o.px), cRound(o.py), cRound(o.px + o.sx), cRound(o.py + o.sy))
  private def objplp2rect(o: Obj): Rect =
    val lp = o.lbl
    (cRound(lp.px), cRound(lp.py), cRound(lp.px + lp.szx), cRound(lp.py + lp.szy))
  /** Boundary enclosing all possible label placements (the rect gv loads in
    * the R-tree). */
  private def objplpmks(o: Obj): Rect =
    val (px, py) = if o.lbl != null then (o.lbl.szx, o.lbl.szy) else (0.0, 0.0)
    (math.floor(o.px - px), math.floor(o.py - py),
     math.ceil(o.px + o.sx + px), math.ceil(o.py + o.sy + py))

  private def overlap(r: Rect, s: Rect): Boolean =
    !(r._1 > s._3 || s._1 > r._3 || r._2 > s._4 || s._2 > r._4)

  /** Intersection area of two overlapping rects (aabbaabb). */
  private def aabbaabb(r: Rect, s: Rect): Double =
    if !overlap(r, s) then 0.0
    else
      val iminx = math.max(r._1, s._1); val iminy = math.max(r._2, s._2)
      val imaxx = math.min(r._3, s._3); val imaxy = math.min(r._4, s._4)
      (imaxx - iminx) * (imaxy - iminy)

  /** Direction bucket of cp relative to op (both labels must be set — while
    * placing, `op`'s label is unset, so this returns -1 and everything lands
    * in bucket 5). */
  private def getintrsxi(op: Obj, cp: Obj): Int =
    val lp = op.lbl; val clp = cp.lbl
    if lp == null || clp == null || !lp.set || !clp.set then return -1
    if (op.px == 0.0 && op.py == 0.0) || (cp.px == 0.0 && cp.py == 0.0) then return -1
    if cp.py < op.py then
      if cp.px < op.px then XLPXPY else if cp.px > op.px then XLNXPY else XLCXPY
    else if cp.py > op.py then
      if cp.px < op.px then XLPXNY else if cp.px > op.px then XLNXNY else XLCXNY
    else if cp.px < op.px then XLPXCY
    else if cp.px > op.px then XLNXCY
    else -1

  /** recordointrsx/recordlintrsx (identical bodies in C). */
  private def recordIntrsx(op: Obj, cp: Obj, rp: Rect, a: Double, intrsx: Array[Obj]): Double =
    var i = getintrsxi(op, cp)
    if i < 0 then i = 5
    if intrsx(i) != null then
      var maxa = 0.0
      val sa1 = aabbaabb(rp, objp2rect(intrsx(i)))
      if sa1 > a then maxa = sa1
      if intrsx(i).lbl != null then
        val sa2 = aabbaabb(rp, objplp2rect(intrsx(i)))
        if sa2 > a then maxa = math.max(sa2, maxa)
      if maxa > 0.0 then return maxa
      intrsx(i) = cp
      a
    else
      intrsx(i) = cp
      a

  /** (n, area, posx, posy) — BestPos_t; pos = the candidate under test. */
  private def xlintersections(objs: Array[Obj], tree: RTree, op: Obj, intrsx: Array[Obj]): (Int, Double, Double, Double) =
    val lp = op.lbl
    var n = 0; var area = 0.0
    // size-0 objects (label anchor points) strictly enclosed by the candidate
    var i = 0
    while i < objs.length do
      val o = objs(i)
      if (o ne op) && !(o.sx > 0 && o.sy > 0)
        && o.px > lp.px && o.px < lp.px + lp.szx
        && o.py > lp.py && o.py < lp.py + lp.szy
      then n += 1
      i += 1
    val rect = objplp2rect(op)
    // RTreeSearch: the candidates, in the order of the leaf list.
    tree.search(rect).foreach { cp =>
      if cp ne op then
        val a = aabbaabb(rect, objp2rect(cp)) // label-object intersect
        if a > 0.0 then
          area += recordIntrsx(op, cp, rect, a, intrsx); n += 1
        if cp.lbl != null && cp.lbl.set then  // label-label intersect
          val a2 = aabbaabb(rect, objplp2rect(cp))
          if a2 > 0.0 then
            area += recordIntrsx(op, cp, rect, a2, intrsx); n += 1
    }
    (n, area, lp.px, lp.py)

  /** xladjust: try the 9 fixed positions (exact C order, zero-overlap returns
    * immediately), then the two sliding scans; else the least-area best. */
  private def xladjust(objs: Array[Obj], tree: RTree, op: Obj): (Int, Double, Double, Double) =
    val lp     = op.lbl
    val xincr  = (2 * lp.szx + op.sx) / XLXDENOM
    val yincr  = (2 * lp.szy + op.sy) / XLYDENOM
    val intrsx = new Array[Obj](9)
    inline def probe() = xlintersections(objs, tree, op, intrsx)
    // x left: top / mid / bottom
    lp.px = op.px - lp.szx
    lp.py = op.py + op.sy
    var bp = probe()
    if bp._1 == 0 then return bp
    lp.py = op.py
    var nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    lp.py = op.py - lp.szy
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    // x mid: top / bottom
    lp.px = op.px
    lp.py = op.py + op.sy
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    lp.py = op.py - lp.szy
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    // x right: top / mid / bottom
    lp.px = op.px + op.sx
    lp.py = op.py + op.sy
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    lp.py = op.py
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    lp.py = op.py - lp.szy
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    // sliding from top left
    if intrsx(XLPXNY) != null || intrsx(XLCXNY) != null || intrsx(XLNXNY) != null
      || intrsx(XLPXCY) != null || intrsx(XLPXPY) != null
    then
      if intrsx(XLCXNY) == null && intrsx(XLNXNY) == null then // some room right?
        lp.px = op.px - lp.szx; lp.py = op.py + op.sy
        while lp.px <= op.px + op.sx do
          nbp = probe()
          if nbp._1 == 0 then return nbp
          if nbp._2 < bp._2 then bp = nbp
          lp.px += xincr
      if intrsx(XLPXCY) == null && intrsx(XLPXPY) == null then // some room down?
        lp.px = op.px - lp.szx; lp.py = op.py + op.sy
        while lp.py >= op.py - lp.szy do
          nbp = probe()
          if nbp._1 == 0 then return nbp
          if nbp._2 < bp._2 then bp = nbp
          lp.py -= yincr
    // sliding from bottom right
    lp.px = op.px + op.sx
    lp.py = op.py - lp.szy
    if intrsx(XLNXPY) != null || intrsx(XLCXPY) != null || intrsx(XLPXPY) != null
      || intrsx(XLNXCY) != null || intrsx(XLNXNY) != null
    then
      if intrsx(XLCXPY) == null && intrsx(XLPXPY) == null then // some room left?
        lp.px = op.px + op.sx; lp.py = op.py - lp.szy
        while lp.px >= op.px - lp.szx do
          nbp = probe()
          if nbp._1 == 0 then return nbp
          if nbp._2 < bp._2 then bp = nbp
          lp.px -= xincr
      if intrsx(XLNXCY) == null && intrsx(XLNXNY) == null then // some room up?
        lp.px = op.px + op.sx; lp.py = op.py - lp.szy
        while lp.py <= op.py + op.sy do
          nbp = probe()
          if nbp._1 == 0 then return nbp
          if nbp._2 < bp._2 then bp = nbp
          lp.py += yincr
    bp

  // ── the R-tree of lib/label (index.c, node.c, split.q.c, rectangle.c) ──────
  /** gv's R-tree for the intersection query. The ORDER of the leaf list
    * matters: on the no-zero-overlap path, `recordIntrsx` keeps the first
    * object in bucket 5, and that changes the area sums. Thus the port
    * builds the same tree as gv:
    *  - xlhdxload loads the objects in the order of a Hilbert key of the
    *    centre of `objplpmks` (a cdt Dtobag; for equal keys the port puts
    *    the later object first).
    *  - Each node has 64 slots (NODECARD). A full node splits with the
    *    quadratic method (MethodZero).
    *  - RTreeSearch prepends the hits of a leaf node (reverse slot order),
    *    and appends the lists of the child nodes in slot order.
    * The port keeps two gv quirks. CombineRect takes `fmin` also for the
    * upper sides, thus a node cover can miss its children. The areas are
    * `uint64_t`, and a subtraction can wrap. */
  private final class RtBranch(var rect: Rect, var child: AnyRef)
  private final class RtNode(var level: Int):
    var count = 0
    val branch: Array[RtBranch] = Array.fill(RTree.NodeCard)(new RtBranch((0.0, 0.0, 0.0, 0.0), null))
    def clear(): Unit =
      count = 0
      branch.foreach { b => b.rect = (0.0, 0.0, 0.0, 0.0); b.child = null }

  private final class RTree:
    import RTree.*
    var root = new RtNode(0)

    def insert(r: Rect, data: Obj): Unit =
      insert2(r, data, root, 0).foreach { nn =>
        val newroot = new RtNode(root.level + 1)
        addBranch(new RtBranch(nodeCover(root), root), newroot)
        addBranch(new RtBranch(nodeCover(nn), nn), newroot)
        root = newroot
      }

    private def insert2(r: Rect, data: Obj, n: RtNode, level: Int): Option[RtNode] =
      if n.level > level then
        val i = pickBranch(r, n)
        val child = n.branch(i).child.asInstanceOf[RtNode]
        insert2(r, data, child, level) match
          case None =>
            n.branch(i).rect = combine(r, n.branch(i).rect)
            None
          case Some(n2) =>
            n.branch(i).rect = nodeCover(child)
            addBranch(new RtBranch(nodeCover(n2), n2), n)
      else addBranch(new RtBranch(r, data), n)

    def search(r: Rect): List[Obj] = searchNode(root, r)

    private def searchNode(n: RtNode, r: Rect): List[Obj] =
      if n.level > 0 then
        val out = List.newBuilder[Obj]
        n.branch.foreach { b =>
          if b.child != null && overlap(r, b.rect) then out ++= searchNode(b.child.asInstanceOf[RtNode], r)
        }
        out.result()
      else
        var out = List.empty[Obj]
        n.branch.foreach { b =>
          if b.child != null && overlap(r, b.rect) then out = b.child.asInstanceOf[Obj] :: out
        }
        out

  private object RTree:
    final val NodeCard = 64

    private def undefined(r: Rect): Boolean = r._1 > r._3
    private val NullRect: Rect = (1.0, 0.0, -1.0, 0.0)

    /** RectArea: `uint64_t`, held in a Long and compared unsigned. */
    def area(r: Rect): Long =
      if undefined(r) then 0L
      else
        val dx = (r._3 - r._1).toLong
        if dx == 0L then 0L
        else
          val dy = (r._4 - r._2).toLong
          if dy == 0L then 0L else dx * dy

    /** CombineRect, with gv's `fmin` for the upper sides. */
    def combine(r: Rect, rr: Rect): Rect =
      if undefined(r) then rr
      else if undefined(rr) then r
      else (math.min(r._1, rr._1), math.min(r._2, rr._2), math.min(r._3, rr._3), math.min(r._4, rr._4))

    private def ult(a: Long, b: Long): Boolean = java.lang.Long.compareUnsigned(a, b) < 0

    def nodeCover(n: RtNode): Rect =
      var r: Rect = (0.0, 0.0, 0.0, 0.0)
      var first = true
      n.branch.foreach { b =>
        if b.child != null then
          if first then { r = b.rect; first = false } else r = combine(r, b.rect)
      }
      r

    def pickBranch(r: Rect, n: RtNode): Int =
      var bestIncr = 0L; var bestArea = 0L; var best = 0; var bestSet = false
      var i = 0
      while i < NodeCard do
        val b = n.branch(i)
        if b.child != null then
          val a    = area(b.rect)
          val incr = area(combine(r, b.rect)) - a
          if !bestSet || ult(incr, bestIncr) then
            best = i; bestArea = a; bestIncr = incr; bestSet = true
          else if incr == bestIncr && ult(a, bestArea) then
            best = i; bestArea = a; bestIncr = incr
        i += 1
      best

    /** AddBranch: the first empty slot, else a split (the new node). */
    def addBranch(b: RtBranch, n: RtNode): Option[RtNode] =
      if n.count < NodeCard then
        val i = n.branch.indexWhere(_.child == null)
        n.branch(i).rect = b.rect; n.branch(i).child = b.child
        n.count += 1
        None
      else Some(splitNode(n, b))

    /** SplitNode + MethodZero (split.q.c). */
    private def splitNode(n: RtNode, b: RtBranch): RtNode =
      val level = n.level
      val buf   = n.branch.map(x => new RtBranch(x.rect, x.child)) :+ new RtBranch(b.rect, b.child)
      n.clear()
      val m         = NodeCard + 1
      val partition = Array.fill(m)(-1)
      val taken     = Array.fill(m)(false)
      val count     = Array(0, 0)
      val cover     = Array(NullRect, NullRect)
      val carea     = Array(0L, 0L)
      def classify(i: Int, group: Int): Unit =
        partition(i) = group; taken(i) = true
        cover(group) = if count(group) == 0 then buf(i).rect else combine(buf(i).rect, cover(group))
        carea(group) = area(cover(group))
        count(group) += 1
      // PickSeeds
      val areas = buf.map(x => area(x.rect))
      var worst = 0L; var seed0 = 0; var seed1 = 0
      var i = 0
      while i < NodeCard do
        var j = i + 1
        while j < m do
          val waste = area(combine(buf(i).rect, buf(j).rect)) - areas(i) - areas(j)
          if java.lang.Long.compareUnsigned(waste, worst) > 0 then { worst = waste; seed0 = i; seed1 = j }
          j += 1
        i += 1
      classify(seed0, 0); classify(seed1, 1)
      while count(0) + count(1) < m && count(0) < m && count(1) < m do
        var biggestSet = false; var biggest = 0L; var chosen = 0; var better = 0
        var k = 0
        while k < m do
          if !taken(k) then
            val r  = buf(k).rect
            val g0 = area(combine(r, cover(0))) - carea(0)
            val g1 = area(combine(r, cover(1))) - carea(1)
            val (diff, group) =
              if java.lang.Long.compareUnsigned(g1, g0) >= 0 then (g1 - g0, 0) else (g0 - g1, 1)
            if !biggestSet || java.lang.Long.compareUnsigned(diff, biggest) > 0 then
              biggest = diff; biggestSet = true; chosen = k; better = group
            else if diff == biggest && count(group) < count(better) then
              chosen = k; better = group
          k += 1
        classify(chosen, better)
      val nn = new RtNode(level)
      n.level = level
      var k = 0
      while k < m do
        if partition(k) == 0 then addBranch(buf(k), n)
        else if partition(k) == 1 then addBranch(buf(k), nn)
        k += 1
      nn

    /** hd_hil_s_from_xy (xlabels.c:116): the Hilbert key, in C int
      * arithmetic (`s` wraps; icompare compares it as a signed int). */
    private def hilbert(px: Int, py: Int, n: Int): Int =
      var x = px; var y = py; var s = 0
      var i = n - 1
      while i >= 0 do
        val xi = (x >> i) & 1
        val yi = (y >> i) & 1
        s = 4 * s + 2 * xi + (xi ^ yi)
        x = x ^ y
        y = y ^ (x & (yi - 1))
        x = x ^ y
        x = x ^ (-xi & (yi - 1))
        y = y ^ (-xi & (yi - 1))
        i -= 1
      s

    /** xlinitialize: xlhdxload (Hilbert order) + xlspdxload (insert). The
      * order of the curve comes from `params.bb`: the nodes and the set
      * labels, not the anchor points (adjustBB in postproc.c). */
    def load(objs: Array[Obj]): RTree =
      var urx = -Double.MaxValue; var ury = -Double.MaxValue
      objs.foreach { o =>
        if !o.anchor then
          urx = math.max(urx, o.px + o.sx); ury = math.max(ury, o.py + o.sy)
      }
      // xlhorder: floor(log2(round(max))) + 1, exact for an integer argument
      val v     = cRound(math.max(urx, ury))
      val order = if v >= 1.0 then 64 - java.lang.Long.numberOfLeadingZeros(v.toLong) else 0
      val keyed = objs.zipWithIndex.map { (o, i) =>
        val r = objplpmks(o)
        val x = r._1 + (r._3 - r._1) / 2
        val y = r._2 + (r._4 - r._2) / 2
        (hilbert(x.toInt, y.toInt, order), i, o, r)
      }
      val tree = new RTree
      keyed.sortBy((k, i, _, _) => (k, -i)).foreach((_, _, o, r) => tree.insert(r, o))
      tree

  // ── edgeMidpoint (splines.c:1297, EDGETYPE_SPLINE branch) ─────────────────
  private def dist2(a: Spline.XY, b: Spline.XY): Double =
    val dx = a.x - b.x; val dy = a.y - b.y
    dx * dx + dy * dy

  /** de Casteljau at t — gv's `Bezier(V, t, NULL, NULL)` (utils.c). */
  private def bezierAt(c: Array[Spline.XY], t: Double): Spline.XY =
    val vx = Array(c(0).x, c(1).x, c(2).x, c(3).x)
    val vy = Array(c(0).y, c(1).y, c(2).y, c(3).y)
    var i = 1
    while i <= 3 do
      var j = 0
      while j <= 3 - i do
        vx(j) = (1.0 - t) * vx(j) + t * vx(j + 1)
        vy(j) = (1.0 - t) * vy(j) + t * vy(j + 1)
        j += 1
      i += 1
    Spline.XY(vx(0), vy(0))

  /** dotneato_closest (utils.c:342): closest spline point to `pt` — nearest
    * control point picks the cubic, then a bisection refines t. */
  private def dotneatoClosest(pts: Vector[Spline.XY], pt: Spline.XY): Spline.XY =
    var besti = -1; var bestdist2 = 1e38
    var j = 0
    while j < pts.length do
      val d2 = dist2(pts(j), pt)
      if besti < 0 || d2 < bestdist2 then { besti = j; bestdist2 = d2 }
      j += 1
    var bestj = besti
    if bestj == pts.length - 1 then bestj -= 1
    val j0 = 3 * (bestj / 3)
    val c  = Array(pts(j0), pts(j0 + 1), pts(j0 + 2), pts(j0 + 3))
    var low = 0.0; var high = 1.0
    var dlow2  = dist2(c(0), pt)
    var dhigh2 = dist2(c(3), pt)
    var pt2 = c(0)
    var go  = true
    while go do
      val t = (low + high) / 2.0
      pt2 = bezierAt(c, t)
      if math.abs(dlow2 - dhigh2) < 1.0 then go = false
      else if math.abs(high - low) < 0.00001 then go = false
      else if dlow2 < dhigh2 then { high = t; dhigh2 = dist2(pt2, pt) }
      else { low = t; dlow2 = dist2(pt2, pt) }
    pt2

  private def edgeMidpoint(spl: Spline.ESpline): Spline.XY =
    val p = spl.sp.getOrElse(spl.pts.head)
    val q = spl.ep.getOrElse(spl.pts.last)
    if dist2(p, q) < 0.001 * 0.001 then p // APPROXEQPT MILLIPOINT: degenerate
    else dotneatoClosest(spl.pts, Spline.XY((q.x + p.x) / 2.0, (p.y + q.y) / 2.0))

  // ── addXLabels driver ──────────────────────────────────────────────────────
  /** Label (w, h) in pt, final frame — HTML-aware like `Coord.edgeLabelDim`. */
  private def labelDim(raw: String, isHtml: Boolean, fs: Double, fn: String, g: RGraph,
                       ctx: NodeSize.LabelCtx): (Double, Double) =
    def plain = (NodeSize.labelWidthPt(raw, fs, fn, ctx),
                 NodeSize.labelHeightPt(raw, fs, ctx))
    if isHtml then
      HtmlParser.parse(raw).map(h => HtmlLayout.size(h, fs, fn, g.images)).getOrElse(plain)
    else plain

  private def xlabelOf(attrs: org.jpablo.graphexplorer.graphviz.model.Attrs): Option[String] =
    attrs.get("xlabel").filter(_.nonEmpty)

  /** place_portlabel (splines.c:1338): the centre of the end label of an edge
    * that has a `labelangle` or a `labeldistance` value, in the canonical
    * frame. The angle is from the end of the spline toward a point near it
    * (the arrow base, or the bezier at t = 0.1 / 0.9), plus `labelangle`
    * (default -25 degrees). The distance is 10 × `labeldistance`. */
  private def portLabelPos(e: REdge, spl: Spline.ESpline, head: Boolean): Option[Spline.XY] =
    val la = e.attrs.get("labelangle").filter(_.nonEmpty)
    val ld = e.attrs.get("labeldistance").filter(_.nonEmpty)
    if la.isEmpty && ld.isEmpty then return None
    val pts = spl.pts
    if pts.length < 4 then return None
    val (pe, pf) =
      if !head then
        spl.sp match
          case Some(sp) => (sp, pts.head)
          case None     => (pts.head, bezierAt(Array(pts(0), pts(1), pts(2), pts(3)), 0.1))
      else
        spl.ep match
          case Some(ep) => (ep, pts.last)
          case None =>
            val n = pts.length
            (pts.last, bezierAt(Array(pts(n - 4), pts(n - 3), pts(n - 2), pts(n - 1)), 0.9))
    val angle = math.atan2(pf.y - pe.y, pf.x - pe.x) + lateDouble(la, -25.0, -180.0) / 180.0 * math.Pi
    val dist  = 10.0 * lateDouble(ld, 1.0, 0.0)
    Some(Spline.XY(pe.x + dist * math.cos(angle), pe.y + dist * math.sin(angle)))

  /** The (w, h) of an end label in pt, final frame (make_label). */
  private def endLabelDim(l: EndLabel, g: RGraph): (Double, Double) =
    labelDim(l.text, l.html, l.fontSize, l.fontName, g, NodeSize.LabelCtx.of(g))

  private def placeImpl(g: RGraph): Result =
    val empty = Result(Map.empty, Map.empty)
    val anyNode = g.nodes.exists(n => xlabelOf(n.attrs).isDefined)
    val anyEdge = g.edges.exists(e => xlabelOf(e.attrs).isDefined)
    val anyEnd  = g.edges.exists(e => endLabel(e, true).isDefined || endLabel(e, false).isDefined)
    if !anyNode && !anyEdge && !anyEnd then return empty

    val flip    = Rank.flip(g)
    val xs      = XCoord.xCoords(g)
    val (ranks, yOf) = Coord.rankY(g)
    val spls    = Spline.splinesEx(g)
    val lps     = Spline.labelPositions(g)
    val nodeSeq = g.nodes.iterator.map(_.id).zipWithIndex.toMap
    val byTail  = g.edges.zipWithIndex.groupBy(_._1.tail)
    // agfstout order: a node's out-edges iterate by (head-node seq, decl idx)
    def outEdges(id: String): Seq[(REdge, Int)] =
      byTail.getOrElse(id, Vector.empty).sortBy((e, i) => (nodeSeq.getOrElse(e.head, Int.MaxValue), i))

    def swapped(w: Double, h: Double): (Double, Double) = if flip then (h, w) else (w, h)

    // ── dotsplines.c:454: place_portlabel for each end label ────────────────
    // The head loop calls updateBB also when place_portlabel fails. Then the
    // label is not set, and its pos is still (0,0): gv grows the graph bb by
    // a box at the canonical origin. The port keeps this box in `stray`.
    val portHeads = mutable.Map.empty[Int, Placed]
    val portTails = mutable.Map.empty[Int, Placed]
    val stray     = Vector.newBuilder[Placed]
    if anyEnd && portLabelsDeclared(g) then
      g.edges.zipWithIndex.foreach { (e, idx) =>
        endLabel(e, true).foreach { l =>
          val (w, h) = endLabelDim(l, g)
          spls.get(idx).flatMap(portLabelPos(e, _, true)) match
            case Some(p) => portHeads(idx) = Placed(p.x, p.y, w, h)
            case None    => stray += Placed(0.0, 0.0, w, h)
        }
        endLabel(e, false).foreach { l =>
          val (w, h) = endLabelDim(l, g)
          spls.get(idx).flatMap(portLabelPos(e, _, false)).foreach { p =>
            portTails(idx) = Placed(p.x, p.y, w, h)
          }
        }
      }

    val objs = mutable.ArrayBuffer.empty[Obj]
    val lbls = mutable.ArrayBuffer.empty[XL]

    /** A set label as an obstacle (addLabelObj): canonical-swapped size. */
    def setObj(p: Placed): Obj =
      val (cw, ch) = swapped(p.w, p.h)
      new Obj(p.cx - cw / 2.0, p.cy - ch / 2.0, cw, ch, null)

    g.nodes.foreach { n =>
      val objBefore = objs.length
      for xPt <- xs.get(n.id); sz <- NodeSize.layoutSize(n, g) do
        val x  = xPt.value
        val y  = yOf(ranks(n.id)).value
        val sx = 2.0 * sz.halfWidthPt.value
        val sy = 2.0 * sz.halfHeightPt.value
        val xl = xlabelOf(n.attrs).map { raw =>
          val fs = n.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(14.0)
          val fn = n.attrs.getOrElse("fontname", "Times")
          val (w, h)   = labelDim(raw, n.attrs.isHtml("xlabel"), fs, fn, g, NodeSize.LabelCtx.of(g, Some(n.id)))
          val (cw, ch) = swapped(w, h)
          new XL(cw, ch, Owner.NodeX(n.id))
        }.orNull
        if xl != null then lbls += xl
        objs += new Obj(x - sx / 2.0, y - sy / 2.0, sx, sy, xl)
      if objs.length == objBefore then () // unplaced node: no obstacle
      outEdges(n.id).foreach { (e, idx) =>
        // SET dot edge label ⇒ obstacle at lp (addLabelObj)
        lps.get(idx).foreach { p =>
          val (w, h)   = Coord.edgeLabelDim(e, g)
          val (cw, ch) = swapped(w, h)
          objs += new Obj(p.x - cw / 2.0, p.y - ch / 2.0, cw, ch, null)
        }
        // tail label, then head label: SET (place_portlabel) ⇒ obstacle;
        // else a size-0 anchor at the end of the spline.
        def endObj(head: Boolean, placed: mutable.Map[Int, Placed]): Unit =
          endLabel(e, head).foreach { l =>
            placed.get(idx) match
              case Some(p) => objs += setObj(p)
              case None =>
                spls.get(idx).foreach { spl =>
                  val (w, h)   = endLabelDim(l, g)
                  val (cw, ch) = swapped(w, h)
                  val xl = new XL(cw, ch, if head then Owner.Head(idx) else Owner.Tail(idx))
                  val at =
                    if head then spl.ep.getOrElse(spl.pts.last) // edgeHeadpoint
                    else spl.sp.getOrElse(spl.pts.head)         // edgeTailpoint
                  lbls += xl
                  objs += new Obj(at.x, at.y, 0.0, 0.0, xl, anchor = true)
                }
          }
        endObj(head = false, portTails)
        endObj(head = true, portHeads)
        // unset edge xlabel ⇒ size-0 anchor at the edge midpoint
        xlabelOf(e.attrs).foreach { raw =>
          spls.get(idx).foreach { spl =>
            val fs = e.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(14.0)
            val fn = e.attrs.getOrElse("fontname", "Times")
            val (w, h)   = labelDim(raw, e.attrs.isHtml("xlabel"), fs, fn, g, NodeSize.LabelCtx.of(g))
            val (cw, ch) = swapped(w, h)
            val xl  = new XL(cw, ch, Owner.EdgeX(idx))
            val mid = edgeMidpoint(spl)
            lbls += xl
            objs += new Obj(mid.x, mid.y, 0.0, 0.0, xl, anchor = true)
          }
        }
      }
    }
    val portOnly = Result(Map.empty, Map.empty, portHeads.toMap, portTails.toMap, stray.result())
    if lbls.isEmpty then return portOnly

    // addClusterObj (postproc.c:376): the set label of each cluster, children
    // before the parent, after all nodes and edges.
    locally {
      val cls = Cluster.clusters(g)
      if cls.nonEmpty then
        val cbbs = Cluster.bbs(g)
        def visit(ci: Int): Unit =
          Cluster.childrenOf(g, ci).foreach(visit)
          val c = cls(ci)
          if c.hasLabel then
            val (lx, ly) = c.labelLp(cbbs(ci), flip)
            objs += setObj(Placed(lx, ly, c.lwidthPt, c.lheightPt))
        Cluster.childrenOf(g, -1).foreach(visit)
    }

    // placeLabels: greedy in object order; forcelabels (default true) keeps
    // the least-overlap position when no clean one exists.
    val force = g.rootAttrs.get("forcelabels").map(_.toLowerCase) match
      case Some("false") | Some("no") => false
      case Some(v)                    => v.toIntOption.forall(_ > 0)
      case None                       => true
    val arr  = objs.toArray
    val tree = RTree.load(arr)
    arr.foreach { op =>
      if op.lbl != null then
        val bp = xladjust(arr, tree, op)
        if bp._1 == 0 then op.lbl.set = true
        else if bp._2 == 0.0 || force then
          op.lbl.px = bp._3; op.lbl.py = bp._4
          op.lbl.set = true
    }

    val nodesOut = Map.newBuilder[String, Placed]
    val edgesOut = Map.newBuilder[Int, Placed]
    val headsOut = mutable.Map.from(portHeads)
    val tailsOut = mutable.Map.from(portTails)
    lbls.foreach { xl =>
      if xl.set then
        val cx = xl.px + xl.szx / 2.0 // centerPt
        val cy = xl.py + xl.szy / 2.0
        val (w, h) = if flip then (xl.szy, xl.szx) else (xl.szx, xl.szy)
        xl.owner match
          case Owner.NodeX(id) => nodesOut += id -> Placed(cx, cy, w, h)
          case Owner.EdgeX(i)  => edgesOut += i -> Placed(cx, cy, w, h)
          case Owner.Head(i)   => headsOut(i) = Placed(cx, cy, w, h)
          case Owner.Tail(i)   => tailsOut(i) = Placed(cx, cy, w, h)
    }
    Result(nodesOut.result(), edgesOut.result(), headsOut.toMap, tailsOut.toMap, stray.result())

end XLabels
