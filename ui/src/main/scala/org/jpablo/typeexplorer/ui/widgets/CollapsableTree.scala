package org.jpablo.typeexplorer.ui.widgets

import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.shared.tree.Tree

def CollapsableTree[A](
  t: Tree[A]
)(
  renderNode: (String, List[String]) => HtmlElement,
  renderLeaf: (String, A) => HtmlElement,
  mkControl : String => Collapsable.Control
)
: HtmlElement = t match
  case Tree.Node(label, path, children) =>
    val key = path.mkString(".") ++ "." + label
    Collapsable(
      nodeLabel    = renderNode(label, path),
      nodeContents = children.map(tree => CollapsableTree(tree)(renderNode, renderLeaf, mkControl)),
      mkControl(key)
    )
  case Tree.Leaf(label, data) =>
    renderLeaf(label, data)
