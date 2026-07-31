package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.graphviz.html.{HtmlTable, HtmlTableLayout}
import org.jpablo.graphexplorer.graphviz.layout.RecordLabel
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.RecordCellOverlay
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.formats.dot.{HtmlLabelOps, HtmlLabels, RecordTree}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrEq
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

    /** Escape pops one level: cell → node. @return true when consumed. */
    def escapeCell(): Boolean =
      val had = selectedCellV.now().isDefined
      clearCell()
      had

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
      * @return true when a cell was selected (the key was consumed). */
    def moveCell(delta: Int): Boolean =
      selectedCellV.now() match
        case Some(cell) =>
          val paths = cellBoxes(cell.nodeId).map(_.path)
          if paths.nonEmpty then
            val cur  = paths.indexOf(clamped(cell).path).max(0)
            val next = ((cur + delta) % paths.length + paths.length) % paths.length
            selectCell(cell.nodeId, paths(next))
          true
        case None => false

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

    def selectedCellPort: Option[String] =
      for
        cell <- selectedCellV.now()
        box  <- cellBoxes(cell.nodeId).find(_.path == clamped(cell).path)
        p    <- box.port
      yield p

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
