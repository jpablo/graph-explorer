package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.graphviz.html.{HtmlTable, HtmlTableLayout}
import org.jpablo.graphexplorer.graphviz.layout.RecordLabel
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.RecordCellOverlay
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.formats.dot.{HtmlLabelOps, HtmlLabels, RecordTree}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrEq, AttrValue}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*

/** One selected CELL of a record or html-table node — a selection level below
  * the element selection: it exists only while its node is the single
  * selected element.
  */
final case class SelectedCell(nodeId: NodeId, path: RecordTree.Path) derives CanEqual

/** Node-local box of one cell (gv frame: centre origin, y-up, points). */
final case class RecordCellBox(
    path: RecordTree.Path,
    port: Option[String],
    llx:  Double,
    lly:  Double,
    urx:  Double,
    ury:  Double
) derives CanEqual:
  def width: Double  = urx - llx
  def height: Double = ury - lly

  def contains(x: Double, y: Double): Boolean =
    x >= llx && x <= urx && y >= lly && y <= ury

  def distanceTo(x: Double, y: Double): Double =
    val dx = math.max(0.0, math.max(llx - x, x - urx))
    val dy = math.max(0.0, math.max(lly - y, y - ury))
    math.hypot(dx, dy)

/** Cell selection and structured (cell-level) label edits, for BOTH label
  * families that have cells:
  *
  *   - `shape=record` labels — paths address the [[RecordTree]] field tree;
  *   - HTML-like `<table>` labels — paths are declared `List(row, cell)`
  *     positions in the top-level table ([[HtmlLabelOps]]).
  *
  * The rendered SVG has no per-cell markup (the engine output is byte-exact
  * vs Graphviz and must stay so), so cell geometry comes from the MODEL: the
  * same layout the engine ran (`RecordLabel.layout` / `HtmlTableLayout.layout`
  * — both table-local = node-local, centre origin, y-up) with parameters read
  * off the node's attributes. Every edit funnels into
  * `ViewerGraph.withRecordLabel` / `withHtmlLabel`.
  */
trait RecordCellOps:
  this: ViewerState =>

  val selectedCellV = Var[Option[SelectedCell]](None)

  /** The cell open in the Edit Cell dialog. */
  val editingCellV = Var[Option[SelectedCell]](None)

  object recordCells:
    // gv const.h defaults — private in NodeSize, mirrored here (stable).
    private val DefFontSize   = 14.0
    private val DefFontName   = "Times-Roman"
    private val DefWidthIn    = 0.75
    private val DefHeightIn   = 0.5
    private val PointsPerInch = 72.0

    private enum CellKind derives CanEqual:
      case Record, Html

    private def nodeAttr(node: ViewerNode, name: String): Option[String] =
      node.attributes.values.get(AttributeId(name)).map(_.toString).filter(_.nonEmpty)

    private def labelIsHtml(node: ViewerNode): Boolean =
      node.label.value match
        case eq: AttrEq => eq.html
        // the html flag never survives the dot_json import — sniff content
        case s: String  => HtmlLabels.isHtml(s)

    /** The node's cell family, when it has one. HTML-in-record labels (a
      * record label whose fields hold html) match neither and stay raw-edited.
      */
    private def kindOf(nodeId: NodeId): Option[CellKind] =
      val g = fullGraphNow()
      g.getNode(nodeId).flatMap: node =>
        if labelIsHtml(node) then
          Option.when(HtmlLabelOps.parseTable(node.label.toString).isDefined)(CellKind.Html)
        else Option.when(g.isRecordNode(nodeId))(CellKind.Record)

    /** Structured cell editing applies to record labels and html `<table>` labels. */
    def isCellEditable(nodeId: NodeId): Boolean = kindOf(nodeId).isDefined

    def selectedCellIsHtml: Boolean =
      selectedCellV.now().exists(c => kindOf(c.nodeId).contains(CellKind.Html))

    private def rankdirNow(): Rankdir =
      fullGraphNow().elements.graphAttributes.values
        .get(Rankdir.attrId)
        .flatMap(attr => Rankdir.values.find(_.toString == attr.toString))
        .getOrElse(Rankdir.TB)

    def topLRNow(): Boolean = RecordTree.topLRFor(rankdirNow())

    private def getNodeNow(nodeId: NodeId): Option[ViewerNode] =
      fullGraphNow().getNode(nodeId)

    /** The record tree of a RECORD node (record ops only). */
    def cellTreeOf(nodeId: NodeId): Option[RecordTree.Group] =
      Option.when(kindOf(nodeId).contains(CellKind.Record))(()).flatMap: _ =>
        getNodeNow(nodeId).map(node => RecordTree.parse(node.label.toString))

    private def htmlTableOf(nodeId: NodeId): Option[HtmlTable] =
      Option.when(kindOf(nodeId).contains(CellKind.Html))(()).flatMap: _ =>
        getNodeNow(nodeId).flatMap(node => HtmlLabelOps.parseTable(node.label.toString))

    /** Node-local cell boxes, from the SAME layout the engine used. */
    def cellBoxes(nodeId: NodeId): Vector[RecordCellBox] =
      kindOf(nodeId) match
        case Some(CellKind.Record) => recordBoxes(nodeId)
        case Some(CellKind.Html)   => htmlBoxes(nodeId)
        case None                  => Vector.empty

    private def recordBoxes(nodeId: NodeId): Vector[RecordCellBox] =
      getNodeNow(nodeId).fold(Vector.empty): node =>
        val margin = nodeAttr(node, "margin").flatMap: s =>
          s.split(",").map(_.trim).flatMap(_.toDoubleOption) match
            case Array(mx, my) => Some((mx * PointsPerInch, my * PointsPerInch))
            case Array(mx)     => Some((mx * PointsPerInch, mx * PointsPerInch))
            case _             => None
        val fixed = nodeAttr(node, "fixedsize").map(_.toLowerCase).exists(v => v == "true" || v == "shape")
        val (_, _, root) = RecordLabel.layout(
          nodeAttr(node, "label").getOrElse("\\N"),
          topLRNow(),
          nodeAttr(node, "fontsize").flatMap(_.toDoubleOption).getOrElse(DefFontSize),
          nodeAttr(node, "fontname").getOrElse(DefFontName),
          nodeAttr(node, "width").flatMap(_.toDoubleOption).getOrElse(DefWidthIn),
          nodeAttr(node, "height").flatMap(_.toDoubleOption).getOrElse(DefHeightIn),
          fixed,
          margin,
          nodeId = node.id.value
        )
        val boxes = Vector.newBuilder[RecordCellBox]
        def walk(f: RecordLabel.Field, path: RecordTree.Path): Unit =
          if f.isLeaf then
            boxes += RecordCellBox(path, f.id.map(_.trim).filter(_.nonEmpty), f.llx, f.lly, f.urx, f.ury)
          else f.flds.zipWithIndex.foreach((c, i) => walk(c, path :+ i))
        root.flds.zipWithIndex.foreach((c, i) => walk(c, List(i)))
        boxes.result()

    private def htmlBoxes(nodeId: NodeId): Vector[RecordCellBox] =
      (for
        node <- getNodeNow(nodeId)
        tbl  <- HtmlLabelOps.parseTable(node.label.toString)
      yield
        // The html label's table is centred on the node, so table-local boxes
        // ARE node-local. Declared paths match layout cell order by construction.
        val laid = HtmlTableLayout.layout(
          tbl,
          nodeAttr(node, "fontsize").flatMap(_.toDoubleOption).getOrElse(DefFontSize),
          nodeAttr(node, "fontname").getOrElse(DefFontName)
        )
        laid.cells.zip(HtmlLabelOps.declaredPaths(tbl)).map: (pc, path) =>
          RecordCellBox(path, pc.cell.attrs.get("port").filter(_.nonEmpty), pc.box.llx, pc.box.lly, pc.box.urx, pc.box.ury)
      ).getOrElse(Vector.empty)

    /** The cell nearest to a point in node-local gv coords (a click on a
      * separator picks the closest cell rather than dropping the gesture). */
    def cellNearestLocalPoint(nodeId: NodeId, x: Double, y: Double): Option[RecordTree.Path] =
      val boxes = cellBoxes(nodeId)
      boxes.find(_.contains(x, y)).orElse(boxes.minByOption(_.distanceTo(x, y))).map(_.path)

    /** The cell under a CLIENT point (click and drop targeting): finds the
      * node's group in the DOM, converts the point to node-local gv coords
      * (getScreenCTM covers viewBox + pan/zoom), hit-tests the model boxes.
      */
    def cellPathAtClientPoint(nodeId: NodeId, clientX: Double, clientY: Double): Option[RecordTree.Path] =
      if !isCellEditable(nodeId) then None
      else
        for
          group      <- nodeGroupInDom(nodeId)
          (lx, ly)   <- clientToLocal(group, clientX, clientY)
          path <- {
            val bbox = RecordCellOverlay.ownGeometryBBox(group)
            cellNearestLocalPoint(nodeId, lx - (bbox.x + bbox.width / 2), (bbox.y + bbox.height / 2) - ly)
          }
        yield path

    private def nodeGroupInDom(nodeId: NodeId): Option[dom.svg.G] =
      dom.document.documentElement
        .querySelectorAllT[dom.Element](s"[id='${nodeId.toSvg}']")
        // an exit ghost keeps the id but wears the PREVIOUS layout's geometry
        .filterNot(_.closest(s".${SelectableElement.exitGhostClass}") != null)
        .collectFirst { case g: dom.svg.G => g }

    private def clientToLocal(group: dom.svg.G, clientX: Double, clientY: Double): Option[(Double, Double)] =
      for
        svgEl <- Option(group.ownerSVGElement)
        ctm   <- Option(group.getScreenCTM())
      yield
        val pt = svgEl.createSVGPoint()
        pt.x = clientX
        pt.y = clientY
        val local = pt.matrixTransform(ctm.inverse())
        (local.x, local.y)

    /** The port of the cell at `path`, MINTED into the label when the cell has
      * none (a fresh `f<n>`). Pure on the given graph, so arrow ops can compose
      * the mint and the arrow write in one update (one undo step).
      */
    def resolvePortIn(g: ViewerGraph, nodeId: NodeId, path: Option[RecordTree.Path]): (ViewerGraph, Option[String]) =
      val resolved =
        for
          p    <- path
          node <- g.getNode(nodeId)
        yield
          if labelIsHtml(node) then resolveHtmlPortIn(g, nodeId, node, p)
          else if g.isRecordNode(nodeId) then resolveRecordPortIn(g, nodeId, node, p)
          else (g, None)
      resolved.getOrElse((g, None))

    private def resolveRecordPortIn(
        g:      ViewerGraph,
        nodeId: NodeId,
        node:   ViewerNode,
        p:      RecordTree.Path
    ): (ViewerGraph, Option[String]) =
      val root = RecordTree.parse(node.label.toString)
      RecordTree.at(root, p) match
        case Some(l: RecordTree.Leaf) =>
          l.port match
            case some @ Some(_) => (g, some)
            case None =>
              val fresh = freshPortName(RecordTree.ports(root))
              (g.withRecordLabel(nodeId, RecordTree.serialize(RecordTree.setPort(root, p, Some(fresh)))), Some(fresh))
        case _ => (g, None)

    private def resolveHtmlPortIn(
        g:      ViewerGraph,
        nodeId: NodeId,
        node:   ViewerNode,
        p:      RecordTree.Path
    ): (ViewerGraph, Option[String]) =
      HtmlLabelOps.parseTable(node.label.toString) match
        case Some(tbl) =>
          HtmlLabelOps.cellAt(tbl, p) match
            case Some(cell) =>
              cell.attrs.get("port").filter(_.nonEmpty) match
                case some @ Some(_) => (g, some)
                case None =>
                  val fresh  = freshPortName(HtmlLabelOps.ports(tbl))
                  val newTbl = HtmlLabelOps.setCellAttr(tbl, p, "port", Some(fresh))
                  (g.withHtmlLabel(nodeId, HtmlLabelOps.printTable(newTbl)), Some(fresh))
            case None => (g, None)
        case None => (g, None)

    private def freshPortName(used: Set[String]): String =
      Iterator.from(0).map(i => s"f$i").filterNot(used).next()

    // ── selection ─────────────────────────────────────────────────────────

    def selectCell(nodeId: NodeId, path: RecordTree.Path): Unit =
      selectedCellV.set(Some(SelectedCell(nodeId, path)))

    def clearCell(): Unit =
      if selectedCellV.now().isDefined then selectedCellV.set(None)

    /** Escape pops one level: cell → node. @return true when consumed.
      *
      * "To the node" means the node is what ends up selected — not the node plus
      * whatever the row's successors were. Clearing only the cell left a
      * multi-selection with no cursor in it, so the record was still buried in
      * its own results and the only way back to it was the mouse. Now Escape
      * lands you on the record with the keyboard still in charge, and a second
      * Escape clears the selection (Commands.clearSelection).
      */
    def escapeCell(): Boolean =
      selectedCellV.now() match
        case Some(cell) =>
          clearCell()
          selection.set2(cell.nodeId)
          true
        case None => false

    /** The cell selection exists only while its node is the single selected
      * element — called on every element-selection change. */
    def pruneAgainstSelection(sel: ElementIds): Unit =
      selectedCellV.now().foreach: cell =>
        if !(sel.size == 1 && sel.contains(cell.nodeId)) then clearCell()

    private def clamped(cell: SelectedCell): SelectedCell =
      kindOf(cell.nodeId) match
        case Some(CellKind.Record) =>
          cellTreeOf(cell.nodeId).fold(cell): root =>
            SelectedCell(cell.nodeId, RecordTree.nearestLeafPath(root, cell.path))
        case Some(CellKind.Html) =>
          htmlTableOf(cell.nodeId).fold(cell): tbl =>
            SelectedCell(cell.nodeId, HtmlLabelOps.nearestPath(tbl, cell.path))
        case None => cell

    /** Enter on a cell-editable node: descend into cells (first cell); Enter
      * on a cell: open the cell editor. @return false when the selection has
      * no cells (caller falls back to the whole-label dialog).
      */
    def enterOrEdit(): Boolean =
      selectedCellV.now() match
        case Some(cell) =>
          editingCellV.set(Some(clamped(cell)))
          true
        case None =>
          singleSelectedCellEditable() match
            case Some(nodeId) =>
              cellBoxes(nodeId).headOption.foreach(b => selectCell(nodeId, b.path))
              true
            case None => false

    def editCell(cell: SelectedCell): Unit =
      selectCell(cell.nodeId, cell.path)
      editingCellV.set(Some(clamped(cell)))

    private def singleSelectedCellEditable(): Option[NodeId] =
      val sel = selection.now()
      if sel.size == 1 then sel.classify.nodes.headOption.filter(isCellEditable)
      else None

    /** Move the cell selection to the previous/next cell, wrapping around.
      *
      * Cycling claims only the record's OWN axis. All four keys used to cycle,
      * which left a selected row with no way to follow its edges — the keyboard
      * could reach a row and then only ever walk to a sibling row. Now the
      * PERPENDICULAR keys fall through to keyboardNav, which (scoped by
      * selectedCellArrows) walks that row's arrows: on a vertical record
      * Up/Down cycles rows and Left/Right leaves along an edge.
      *
      * @return true when the key was consumed by cell cycling.
      */
    def moveCell(dir: NavDirection): Boolean =
      selectedCellV.now() match
        case Some(cell) =>
          if dir.horizontal == cellsStackVertically(cell.nodeId) then false
          else
            val paths = cellBoxes(cell.nodeId).map(_.path)
            if paths.nonEmpty then
              val delta = if dir == NavDirection.NavDown || dir == NavDirection.NavRight then 1 else -1
              val cur   = paths.indexOf(clamped(cell).path).max(0)
              val next  = ((cur + delta) % paths.length + paths.length) % paths.length
              // The cursor OWNS what it derived: row A's successors are not row
              // B's, so moving the cursor drops them. Without this the canvas
              // showed one row's neighbourhood while the cursor sat on another,
              // and comparing two rows meant re-clicking the record between them.
              selection.set2(cell.nodeId)
              selectCell(cell.nodeId, paths(next))
            true
        case None => false

    /** Which axis the record's cells are laid out along, measured from the
      * RENDERED boxes rather than read off `rankdir`: a record's own `{...}`
      * nesting flips orientation independently of the graph's direction, so the
      * geometry is the only honest answer. True = cells stacked vertically (the
      * usual `a|b|c` in a top-to-bottom graph, one row per line).
      *
      * A record with fewer than two cells has no axis; `true` keeps the vertical
      * keys inert-but-consumed there, which is the status quo for a single cell.
      */
    private def cellsStackVertically(nodeId: NodeId): Boolean =
      val boxes = cellBoxes(nodeId)
      if boxes.size < 2 then true
      else
        def spread(centre: RecordCellBox => Double): Double =
          val vs = boxes.map(centre)
          vs.max - vs.min
        spread(b => (b.lly + b.ury) / 2) >= spread(b => (b.llx + b.urx) / 2)

    // ── cell text (dialog plumbing) ───────────────────────────────────────

    def cellDisplayText(cell: SelectedCell): String =
      kindOf(cell.nodeId) match
        case Some(CellKind.Record) =>
          cellTreeOf(cell.nodeId).flatMap(root => RecordTree.at(root, cell.path)) match
            case Some(l: RecordTree.Leaf) => RecordTree.displayText(l.text)
            case _                        => ""
        case Some(CellKind.Html) =>
          (for
            tbl <- htmlTableOf(cell.nodeId)
            c   <- HtmlLabelOps.cellAt(tbl, cell.path)
          yield HtmlLabelOps.cellDisplayText(c)).getOrElse("")
        case None => ""

    def setCellText(cell: SelectedCell, display: String): Unit =
      kindOf(cell.nodeId) match
        case Some(CellKind.Record) =>
          cellTreeOf(cell.nodeId).foreach: root =>
            commitRecord(cell.nodeId, RecordTree.setText(root, cell.path, display))
            selectCell(cell.nodeId, cell.path)
        case Some(CellKind.Html) =>
          htmlTableOf(cell.nodeId).foreach: tbl =>
            commitHtml(cell.nodeId, HtmlLabelOps.setCellText(tbl, cell.path, display))
            selectCell(cell.nodeId, cell.path)
        case None => ()

    /** The node whose row is currently selected, if any. */
    def selectedCellNode: Option[NodeId] = selectedCellV.now().map(_.nodeId)

    /** The selected row itself, for callers that must put it back. */
    def selectedCell: Option[SelectedCell] = selectedCellV.now()

    /** Re-assert a row after an operation that necessarily grew the selection.
      *
      * [[pruneAgainstSelection]] drops a cell the moment its node stops being
      * the WHOLE selection. That is right when the user selects other things,
      * and wrong when a row-scoped operation is the very thing that grew the
      * selection: "select this row's successors" would destroy the row context
      * it just used, so a second press would silently act on the whole record.
      */
    def restoreCell(cell: SelectedCell): Unit = selectedCellV.set(Some(cell))

    def selectedCellPort: Option[String] =
      selectedCellV.now().flatMap(cell => portOfIn(fullGraphNow(), cell))

    /** The arrows attached AT the selected cell — the ROW's own topology rather
      * than the whole record's. Navigation and successor/predecessor selection
      * both route through this, so they cannot disagree about what "this row's
      * edges" means.
      *
      * `None` means no row scope applies, and the caller should treat the whole
      * node as the subject: either no cell is selected, or the selected cell
      * carries no PORT. That second case is not a degenerate one to guard
      * against — in DOT an edge reaches a row only through `node:port`, so a
      * portless row genuinely has no edges of its own. Returning an empty scope
      * there would turn every arrow key and every "select successors" into a
      * silent no-op on a row that looks perfectly connected.
      *
      * `Some(Vector())` is different and is honoured: the row HAS a port and
      * nothing is attached to it, so there is nowhere to go and that is the
      * truthful answer.
      */
    def selectedCellArrows(g: ViewerGraph): Option[Vector[Arrow]] =
      for
        cell <- selectedCellV.now()
        port <- portOfIn(g, cell)
      yield g.arrows.values.iterator
        .filter: a =>
          (a.source == cell.nodeId && a.sourcePort.contains(port)) ||
          (a.target == cell.nodeId && a.targetPort.contains(port))
        .toVector

    /** The selected row's first hop in one direction: the arrows leaving (or
      * arriving at) the row, and the nodes on their far side. */
    def selectedCellHop(g: ViewerGraph, outgoing: Boolean): Option[(Set[ArrowId], Set[NodeId])] =
      selectedCellV.now().flatMap: cell =>
        selectedCellArrows(g).map: arrows =>
          val scoped = arrows.filter(a => if outgoing then a.source == cell.nodeId else a.target == cell.nodeId)
          (scoped.map(_.id).toSet, scoped.map(a => if outgoing then a.target else a.source).toSet)

    /** The selected cell's port, tracking BOTH the selection and the graph:
      * renaming a port must update the chip showing it, and the context strip
      * deliberately does not rebuild on graph changes (that would destroy the
      * very control being typed into).
      */
    def selectedCellPortSignal: Signal[Option[String]] =
      selectedCellV.signal
        .combineWith(phases.fullGraphV.signal)
        .map((cellOpt, g) => cellOpt.flatMap(portOfIn(g, _)))

    private def portOfIn(g: ViewerGraph, cell: SelectedCell): Option[String] =
      g.getNode(cell.nodeId).flatMap: node =>
        if labelIsHtml(node) then
          for
            tbl <- HtmlLabelOps.parseTable(node.label.toString)
            c   <- HtmlLabelOps.cellAt(tbl, cell.path)
            p   <- c.attrs.get("port").filter(_.nonEmpty)
          yield p
        else if g.isRecordNode(cell.nodeId) then
          RecordTree
            .at(RecordTree.parse(node.label.toString), cell.path)
            .collect { case l: RecordTree.Leaf => l }
            .flatMap(_.port)
        else None

    // ── cell attributes (html cells) ──────────────────────────────────────

    /** The selected html cell's markup attributes, as the same
      * `AttributeUpdates` map element attributes use — so every attribute row
      * (color picker, dropdown, number, reset dot) works on a `<td>` unchanged.
      *
      * Reads ALL of the cell's attributes, not just the managed ones, so an
      * attribute the editor has no row for (`sides`, `href`, …) survives an
      * edit instead of being dropped by the write-back.
      */
    def cellAttributeUpdates(cell: SelectedCell): Var[AttributeUpdates] =
      phases.fullGraphV.zoomLazy(readCellAttrs(_, cell))((g, updates) => writeCellAttrs(g, cell, updates))

    private def cellAttrsIn(g: ViewerGraph, cell: SelectedCell): Option[Map[String, String]] =
      for
        node <- g.getNode(cell.nodeId)
        tbl  <- HtmlLabelOps.parseTable(node.label.toString)
        c    <- HtmlLabelOps.cellAt(tbl, cell.path)
      yield c.attrs

    private def readCellAttrs(g: ViewerGraph, cell: SelectedCell): AttributeUpdates =
      AttributeUpdates(
        cellAttrsIn(g, cell)
          .getOrElse(Map.empty)
          .map((k, v) => AttributeId(k) -> AttrStatus.Single(AttrValue(v)))
      )

    private def writeCellAttrs(g: ViewerGraph, cell: SelectedCell, updates: AttributeUpdates): ViewerGraph =
      (for
        node <- g.getNode(cell.nodeId)
        tbl  <- HtmlLabelOps.parseTable(node.label.toString)
        old  <- HtmlLabelOps.cellAt(tbl, cell.path).map(_.attrs)
      yield
        val applied = updates
          .applyTo(Attributes(old.map((k, v) => AttributeId(k) -> AttrValue(v))))
          .values
          .map((k, v) => k.value -> v.toString)
          .filter((_, v) => v.nonEmpty)
        // Colouring a cell whose effective border width is 0 paints NOTHING —
        // the engine only strokes a border it has a width for (gv does the
        // same). Setting a colour states intent to see a border, so mint the
        // minimum width that makes it visible.
        val newAttrs =
          if applied.get("color").exists(c => !old.get("color").contains(c)) &&
            HtmlLabelOps.effectiveCellBorder(tbl, applied) == 0
          then applied + ("border" -> "1")
          else applied
        val newTbl = newAttrs.keySet
          .union(old.keySet)
          .foldLeft(tbl): (acc, key) =>
            HtmlLabelOps.setCellAttr(acc, cell.path, key, newAttrs.get(key))
        // A port rename must follow the edges that name it, or they silently
        // fall back to the whole node (the cell they point at is gone).
        val withLabel = g.withHtmlLabel(cell.nodeId, HtmlLabelOps.printTable(newTbl))
        (old.get("port"), newAttrs.get("port")) match
          case (Some(before), after) if !after.contains(before) =>
            withLabel.renamePort(cell.nodeId, before, after)
          case _ => withLabel
      ).getOrElse(g)

    // ── structure ─────────────────────────────────────────────────────────

    /** Record: insert an empty sibling cell. Html table: insert a COLUMN of
      * empty cells left/right of the selected cell — the record-analog along
      * the row. */
    def insertSibling(after: Boolean): Unit =
      withSelected: (cell, kind) =>
        kind match
          case CellKind.Record =>
            cellTreeOf(cell.nodeId).foreach: root =>
              val (newRoot, newPath) = RecordTree.insertSibling(root, cell.path, after)
              commitRecord(cell.nodeId, newRoot)
              selectCell(cell.nodeId, newPath)
          case CellKind.Html =>
            withSelectedHtml(cell): (tbl, r, c) =>
              val at = if after then c + 1 else c
              (HtmlLabelOps.insertCol(tbl, at), List(r, at))

    /** Html table only: insert a row of empty cells above/below the selected cell. */
    def insertHtmlRow(below: Boolean): Unit =
      withSelected: (cell, kind) =>
        if kind == CellKind.Html then
          withSelectedHtml(cell): (tbl, r, c) =>
            val at = if below then r + 1 else r
            (HtmlLabelOps.insertRow(tbl, at), List(at, c))

    /** Html table only: delete the selected cell's row. */
    def deleteHtmlRow(): Unit =
      withSelected: (cell, kind) =>
        if kind == CellKind.Html then
          withSelectedHtml(cell): (tbl, r, c) =>
            val newTbl = HtmlLabelOps.deleteRow(tbl, r)
            (newTbl, HtmlLabelOps.nearestPath(newTbl, List(r, c)))

    /** Html table only: delete the selected cell's column. */
    def deleteHtmlCol(): Unit =
      withSelected: (cell, kind) =>
        if kind == CellKind.Html then
          withSelectedHtml(cell): (tbl, r, c) =>
            val newTbl = HtmlLabelOps.deleteCol(tbl, c)
            (newTbl, HtmlLabelOps.nearestPath(newTbl, List(r, c)))

    /** Record only: split a cell perpendicular to its group. */
    def splitSelectedCell(): Unit =
      withSelected: (cell, kind) =>
        if kind == CellKind.Record then
          cellTreeOf(cell.nodeId).foreach: root =>
            val (newRoot, newPath) = RecordTree.splitCell(root, cell.path)
            commitRecord(cell.nodeId, newRoot)
            selectCell(cell.nodeId, newPath)

    /** Backspace on a cell. Record: remove the cell. Html table: clear the
      * cell's content (a grid cell cannot disappear alone — use the row/column
      * deletes for structure). @return true when a cell was selected. */
    def removeSelectedCell(): Boolean =
      selectedCellV.now() match
        case Some(_) =>
          withSelected: (cell, kind) =>
            kind match
              case CellKind.Record =>
                cellTreeOf(cell.nodeId).foreach: root =>
                  val (newRoot, newPath) = RecordTree.removeCell(root, cell.path)
                  commitRecord(cell.nodeId, newRoot)
                  selectCell(cell.nodeId, newPath)
              case CellKind.Html =>
                setCellText(clamped(cell), "")
          true
        case None => false

    private def withSelected(f: (SelectedCell, CellKind) => Unit): Unit =
      for
        cell <- selectedCellV.now()
        kind <- kindOf(cell.nodeId)
      do f(clamped(cell), kind)

    /** Run an html-table edit from the selected cell's (row, col); commit and
      * reselect the returned path. */
    private def withSelectedHtml(cell: SelectedCell)(f: (HtmlTable, Int, Int) => (HtmlTable, RecordTree.Path)): Unit =
      for
        tbl <- htmlTableOf(cell.nodeId)
        r   <- cell.path.headOption
        c   <- cell.path.lift(1)
      do
        val (newTbl, newPath) = f(tbl, r, c)
        commitHtml(cell.nodeId, newTbl)
        selectCell(cell.nodeId, newPath)

    private def commitRecord(nodeId: NodeId, root: RecordTree.Group): Unit =
      phases.fullGraphV.update(_.withRecordLabel(nodeId, RecordTree.serialize(root)))

    private def commitHtml(nodeId: NodeId, tbl: HtmlTable): Unit =
      phases.fullGraphV.update(_.withHtmlLabel(nodeId, HtmlLabelOps.printTable(tbl)))

  end recordCells

end RecordCellOps
