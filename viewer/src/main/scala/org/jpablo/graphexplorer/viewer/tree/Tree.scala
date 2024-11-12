package org.jpablo.graphexplorer.viewer.tree

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.tree.Tree.Label

enum Tree[+A]:
  case Branch(label: Label, path: List[Label], children: List[Tree[A]])
  case Leaf(label: Label, data: A)

  def label: Label

object Tree:
  type Label = String
  type LeafWithPath[A] = (List[Label], Label, A)

  def getChildren[A]: Tree[A] => List[Tree[A]] =
    case b: Branch[_] => b.children
    case _            => List.empty

  def fromPaths[A](paths: List[LeafWithPath[A]], sep: String = "/", prefix: List[Label] = List.empty): Tree[A] =
    val leaves = paths.collect { case (Nil, label, data) => Leaf(label, data) }
    val nonEmptyPaths = paths.collect { case (h :: t, label, data) => (h :: t, label, data) }

    val leafGroups: List[(Label, List[LeafWithPath[A]])] =
      nonEmptyPaths
        .groupBy((path, _, _) => path.head)
        .transform((_, group) => group.map(pathTail))
        .toList

    val nodes =
      for (groupLabel, groupPaths) <- leafGroups yield
        val prefix1 = prefix :+ groupLabel
        val subtrees = getChildren(fromPaths(groupPaths, sep, prefix1))
        node(groupLabel, subtrees, prefix1, sep)

    Tree.Branch(
      label    = prefix.mkString(sep),
      path     = prefix,
      children = nodes.sortBy(_.label) ++ leaves.sortBy(_.label)
    )

  private def pathTail[B](path: (List[Label], Label, B)): LeafWithPath[B] =
    path.copy(_1 = path._1.tail)

  private def node[A](label: Label, trees: List[Tree[A]], path: List[Label], sep: String): Tree[A] =
    trees match
      case List(d: Branch[A]) => d.modify(_.label)(label + sep + _)
      case _                  => Branch(label, path, trees)
