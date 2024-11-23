package org.jpablo.graphexplorer.viewer.formats.dot.ast

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUID

import scala.annotation.tailrec

enum AttributeTarget:
  case node, edge, graph

extension (ast: DotAST)

  def renderToDot: DotText =
    DotText(ast.render(true))

  def toViewerGraph: ViewerGraph =
    ViewerGraph(
      arrows = ast.allArrows,
      nodes  = ast.allViewerNodes
    )

  def addRandomNode(): DotAST =
    val label = Attr("label", "")
    val newNode = NodeStmt(DotNodeId(randomUUID()), List(label))
    ast.modify(_.children).using(_ ++ List(Newline(), Pad(), newNode, Newline()))

  def addEdge(source: NodeId, target: NodeId): DotAST =
    val newEdge = EdgeStmt(List(DotNodeId(source.value), DotNodeId(target.value)), Nil)
    ast.modify(_.children).using(_ ++ List(Newline(), Pad(), newEdge, Newline()))

  def addNodeAndEdge(source: NodeId): DotAST =
    val newNodeId = randomUUID()
    val label = Attr("label", "")
    val newNode = NodeStmt(DotNodeId(newNodeId), List(label))
    val newEdge = EdgeStmt(List(DotNodeId(source.value), DotNodeId(newNodeId)), Nil)
    ast.modify(_.children).using(_ ++ List(Newline(), Pad(), newNode, Newline(), newEdge, Newline()))

  def updateDiagramAttributes(target: AttributeTarget)(attrs: Map[String, String]): DotAST =
    val targetStr = target.toString
    var attrMap = attrs

    def updateAttrs(attrs: List[Attr]): List[Attr] =
      for attr <- attrs
      yield
        if attrMap.contains(attr.id) then
          val newAttrValue = attrMap(attr.id)
          attrMap -= attr.id
          Attr(attr.id, newAttrValue)
        else
          attr
    // first update existing attributes
    val updatedChildren =
      ast.children.map:
        case AttrStmt(`targetStr`, attrs) => AttrStmt(targetStr, updateAttrs(attrs))
        case other                        => other
    // then add remaining attributes to a single AttrStmt
    val newAttrs = AttrStmt(targetStr, attrMap.map((k, v) => Attr(k, v)).toList)
    ast.copy(children = newAttrs :: updatedChildren)

  def getDiagramAttributes(target: AttributeTarget): Map[String, String] =
    val targetStr = target.toString
    ast.children
      .collect:
        case AttrStmt(`targetStr`, attrs) => attrs.map(attr => attr.id -> attr.value)
      .flatten
      .toMap

  /** Unsupported features:
    *   - graph size (results in an incorrect layout)
    */
  def removeUnsupportedFeatures: DotAST =
    ast.modify(_.children).using:
      _.filter:
        case AttrStmt("graph", List(Attr("size", _))) => false
        case _                                        => true

  def setDefaultTheme: DotAST =
    ast.modify(_.children).using: children =>
      Newline() :: Pad() :: AttrStmt("node", List(Attr("style", "filled"))) :: children

  def attachInternalAttributes: DotAST =
    EdgeStmt.resetId()
    ast.modify(_.children).using(_.map(_.attachId))

  def removeNodes(idsToRemove: Set[NodeId]): DotAST =
    ast
      .modify(_.children)
      .using(_.flatMap { element =>
        dom.console.log(s"---> removeNodes")
        pprint.log(element)
        val removed = element.removeGraphNodes1(idsToRemove.map(_.value))
//        dom.console.log(r.toString)
        pprint.log(removed)
        removed
      })

  def groupNodes(ids: Set[NodeId]): DotAST =
    val idsStr = ids.map(_.value)
    val clusterId = s"cluster_${randomUUID().replace("-", "")}"
    // TODO: we need to get the attributes!
    val cluster = Subgraph(
      children = idsStr.toList.map(id => NodeStmt(DotNodeId(id), attr_list = Nil)),
      id       = Some(clusterId)
    )
    ast.removeNodes(ids).modify(_.children).using(_ :+ cluster)

  def optimize: DotAST =
    @tailrec
    def loop(children: List[GraphElement], state: List[GraphElement] = Nil): List[GraphElement] =
      children match
        case h :: EdgeStmt(Nil, _) :: t => loop(h :: t, state) // why the focus on the 2nd element?
        case Pad() :: Newline() :: t    => loop(t, state)
        case h :: t                     => loop(t, h :: state)
        case Nil                        => state.reverse

    ast.modify(_.children).using(loop(_))

  // TODO: Produces incorrect results, fix it
  def format: DotAST =
    @tailrec
    def loop(stack: List[(List[GraphElement], List[GraphElement])], finalAcc: List[GraphElement]): List[GraphElement] =
      stack match
        case Nil => finalAcc

        case (Nil, acc) :: t => loop(stack = t, finalAcc = acc.reverse ::: finalAcc)
        // Remove redundant padding
        case (Pad() :: Pad() :: rest, acc) :: t                  => loop((Pad() :: rest, acc) :: t, finalAcc)
        case (Newline() :: Newline() :: rest, acc) :: t          => loop((Newline() :: rest, acc) :: t, finalAcc)
        case (Newline() :: Pad() :: Newline() :: rest, acc) :: t => loop((Newline() :: rest, acc) :: t, finalAcc)
        // Format sub graphs
        case ((s @ Subgraph(children, _)) :: rest, acc) :: t =>
          // Push the subgraph's children to be processed first, then continue with rest
          loop((children, Nil) :: (rest, Newline() :: Pad() :: acc) :: t, finalAcc)
        // Format node statements
        case ((n: NodeStmt) :: rest, acc) :: t => loop((rest, Pad() :: n :: acc) :: t, finalAcc)
        // Format edge statements
        case ((e: EdgeStmt) :: rest, acc) :: t => loop((rest, Pad() :: e :: acc) :: t, finalAcc)
        // Format attribute statements
        case ((a: AttrStmt) :: rest, acc) :: t => loop((rest, Pad() :: a :: acc) :: t, finalAcc)
        // Skip comments and other elements
        case (Comment() :: rest, acc) :: t => loop(stack = (rest, acc) :: t, finalAcc = finalAcc)
        case (c :: rest, acc) :: t         => loop(stack = (rest, c :: acc) :: t, finalAcc = finalAcc)

    ast.copy(children = loop(List((ast.children, List(Newline()))), Nil))
