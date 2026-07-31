package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.graphviz.layout.RecordLabel
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.RecordCellOverlay
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.formats.dot.RecordTree
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrEq
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*

/** One selected CELL of a record node — a selection level below the element
  * selection: it exists only while its record is the single selected element.
  */
final case class SelectedCell(nodeId: NodeId, path: RecordTree.Path) derives CanEqual

/** Node-local box of one record leaf cell (gv frame: centre origin, y-up, points). */
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

/** Record CELL selection and structured (cell-level) record-label edits.
  *
  * The rendered SVG has no per-field markup (the engine output is byte-exact
  * vs Graphviz and must stay so), so cell geometry comes from the MODEL: the
  * same [[RecordLabel.layout]] the engine ran, with parameters read off the
  * node's attributes (mirrors NodeSize.recordLayoutImpl). Cell → label-string
  * plumbing goes through [[RecordTree]]; every edit funnels into
  * `ViewerGraph.withRecordLabel`.
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

    private def getRecordNode(nodeId: NodeId): Option[ViewerNode] =
      val g = fullGraphNow()
      if g.isRecordNode(nodeId) then g.getNode(nodeId) else None

    /** Structured editing covers labels in the RECORD grammar; HTML-in-record
      * labels use different escaping and stay raw-text-edited. */
    def isEditableRecord(nodeId: NodeId): Boolean =
      getRecordNode(nodeId).exists(node => !labelIsHtml(node))

    private def rankdirNow(): Rankdir =
      fullGraphNow().elements.graphAttributes.values
        .get(Rankdir.attrId)
        .flatMap(attr => Rankdir.values.find(_.toString == attr.toString))
        .getOrElse(Rankdir.TB)

    def topLRNow(): Boolean = RecordTree.topLRFor(rankdirNow())

    def cellTreeOf(nodeId: NodeId): Option[RecordTree.Group] =
      getRecordNode(nodeId).map(node => RecordTree.parse(node.label.toString))

    /** Node-local leaf-cell boxes, from the SAME layout the engine used. */
    def cellBoxes(nodeId: NodeId): Vector[RecordCellBox] =
      getRecordNode(nodeId).fold(Vector.empty): node =>
        def attr(name: String) =
          node.attributes.values.get(AttributeId(name)).map(_.toString).filter(_.nonEmpty)
        val margin = attr("margin").flatMap: s =>
          s.split(",").map(_.trim).flatMap(_.toDoubleOption) match
            case Array(mx, my) => Some((mx * PointsPerInch, my * PointsPerInch))
            case Array(mx)     => Some((mx * PointsPerInch, mx * PointsPerInch))
            case _             => None
        val fixed = attr("fixedsize").map(_.toLowerCase).exists(v => v == "true" || v == "shape")
        val (_, _, root) = RecordLabel.layout(
          attr("label").getOrElse("\\N"),
          topLRNow(),
          attr("fontsize").flatMap(_.toDoubleOption).getOrElse(DefFontSize),
          attr("fontname").getOrElse(DefFontName),
          attr("width").flatMap(_.toDoubleOption).getOrElse(DefWidthIn),
          attr("height").flatMap(_.toDoubleOption).getOrElse(DefHeightIn),
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

    /** The leaf cell nearest to a point in node-local gv coords (a click on a
      * separator picks the closest cell rather than dropping the gesture). */
    def cellNearestLocalPoint(nodeId: NodeId, x: Double, y: Double): Option[RecordTree.Path] =
      val boxes = cellBoxes(nodeId)
      boxes.find(_.contains(x, y)).orElse(boxes.minByOption(_.distanceTo(x, y))).map(_.path)

    /** The record cell under a CLIENT point (click and drop targeting): finds
      * the node's group in the DOM, converts the point to node-local gv coords
      * (getScreenCTM covers viewBox + pan/zoom), hit-tests the model boxes.
      */
    def cellPathAtClientPoint(nodeId: NodeId, clientX: Double, clientY: Double): Option[RecordTree.Path] =
      if !isEditableRecord(nodeId) then None
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
          node <- g.getNode(nodeId) if g.isRecordNode(nodeId) && !labelIsHtml(node)
          root  = RecordTree.parse(node.label.toString)
          leaf <- RecordTree.at(root, p).collect { case l: RecordTree.Leaf => l }
        yield leaf.port match
          case some @ Some(_) => (g, some)
          case None =>
            val fresh = freshPortName(root)
            (g.withRecordLabel(nodeId, RecordTree.serialize(RecordTree.setPort(root, p, Some(fresh)))), Some(fresh))
      resolved.getOrElse((g, None))

    private def labelIsHtml(node: ViewerNode): Boolean =
      node.label.value match
        case eq: AttrEq => eq.html
        case _: String  => false

    private def freshPortName(root: RecordTree.Group): String =
      val used = RecordTree.ports(root)
      Iterator.from(0).map(i => s"f$i").filterNot(used).next()

    // ── selection ─────────────────────────────────────────────────────────

    def selectCell(nodeId: NodeId, path: RecordTree.Path): Unit =
      selectedCellV.set(Some(SelectedCell(nodeId, path)))

    def clearCell(): Unit =
      if selectedCellV.now().isDefined then selectedCellV.set(None)

    /** Escape pops one level: cell → record. @return true when consumed. */
    def escapeCell(): Boolean =
      val had = selectedCellV.now().isDefined
      clearCell()
      had

    /** The cell selection exists only while its record is the single selected
      * element — called on every element-selection change. */
    def pruneAgainstSelection(sel: ElementIds): Unit =
      selectedCellV.now().foreach: cell =>
        if !(sel.size == 1 && sel.contains(cell.nodeId)) then clearCell()

    private def clamped(cell: SelectedCell): SelectedCell =
      cellTreeOf(cell.nodeId).fold(cell): root =>
        SelectedCell(cell.nodeId, RecordTree.nearestLeafPath(root, cell.path))

    /** Enter on a record: descend into cells (first leaf); Enter on a cell:
      * open the cell editor. @return false when the selection is not a
      * structurally-editable record (caller falls back to the label dialog).
      */
    def enterOrEdit(): Boolean =
      selectedCellV.now() match
        case Some(cell) =>
          editingCellV.set(Some(clamped(cell)))
          true
        case None =>
          singleSelectedEditableRecord() match
            case Some(nodeId) =>
              cellTreeOf(nodeId)
                .flatMap(root => RecordTree.leafPaths(root).headOption)
                .foreach(selectCell(nodeId, _))
              true
            case None => false

    def editCell(cell: SelectedCell): Unit =
      selectCell(cell.nodeId, cell.path)
      editingCellV.set(Some(clamped(cell)))

    private def singleSelectedEditableRecord(): Option[NodeId] =
      val sel = selection.now()
      if sel.size == 1 then sel.classify.nodes.headOption.filter(isEditableRecord)
      else None

    /** Move the cell selection to the previous/next leaf, wrapping around.
      * @return true when a cell was selected (the key was consumed). */
    def moveCell(delta: Int): Boolean =
      selectedCellV.now() match
        case Some(cell) =>
          cellTreeOf(cell.nodeId).foreach: root =>
            val paths = RecordTree.leafPaths(root)
            if paths.nonEmpty then
              val cur  = paths.indexOf(RecordTree.nearestLeafPath(root, cell.path)).max(0)
              val next = ((cur + delta) % paths.length + paths.length) % paths.length
              selectCell(cell.nodeId, paths(next))
          true
        case None => false

    // ── cell text (dialog plumbing) ───────────────────────────────────────

    def cellDisplayText(cell: SelectedCell): String =
      cellTreeOf(cell.nodeId).flatMap(root => RecordTree.at(root, cell.path)) match
        case Some(l: RecordTree.Leaf) => RecordTree.displayText(l.text)
        case _                        => ""

    def setCellText(cell: SelectedCell, display: String): Unit =
      cellTreeOf(cell.nodeId).foreach: root =>
        commit(cell.nodeId, RecordTree.setText(root, cell.path, display))
        selectCell(cell.nodeId, cell.path)

    def selectedCellPort: Option[String] =
      for
        cell <- selectedCellV.now()
        root <- cellTreeOf(cell.nodeId)
        leaf <- RecordTree.at(root, cell.path).collect { case l: RecordTree.Leaf => l }
        p    <- leaf.port
      yield p

    // ── structure ─────────────────────────────────────────────────────────

    def insertSibling(after: Boolean): Unit =
      withSelected: (cell, root) =>
        val (newRoot, newPath) = RecordTree.insertSibling(root, cell.path, after)
        commit(cell.nodeId, newRoot)
        selectCell(cell.nodeId, newPath)

    def splitSelectedCell(): Unit =
      withSelected: (cell, root) =>
        val (newRoot, newPath) = RecordTree.splitCell(root, cell.path)
        commit(cell.nodeId, newRoot)
        selectCell(cell.nodeId, newPath)

    /** Remove the selected cell. @return true when a cell was selected (so
      * Backspace deletes the CELL, not the node). */
    def removeSelectedCell(): Boolean =
      selectedCellV.now() match
        case Some(_) =>
          withSelected: (cell, root) =>
            val (newRoot, newPath) = RecordTree.removeCell(root, cell.path)
            commit(cell.nodeId, newRoot)
            selectCell(cell.nodeId, newPath)
          true
        case None => false

    private def withSelected(f: (SelectedCell, RecordTree.Group) => Unit): Unit =
      for
        cell <- selectedCellV.now()
        root <- cellTreeOf(cell.nodeId)
      do f(clamped(cell), root)

    private def commit(nodeId: NodeId, root: RecordTree.Group): Unit =
      phases.fullGraphV.update(_.withRecordLabel(nodeId, RecordTree.serialize(root)))

  end recordCells

end RecordCellOps
