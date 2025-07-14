package org.jpablo.graphexplorer.viewer.utils

trait Utils:
  def randomUUID(): String
  def randomUUIDSafe(): String

object Utils extends UtilsPlatform

enum ChangeOrigin derives CanEqual:
  case CodeMirror, Graph

extension [A](xs: List[A])
  def intersperse(a: A): List[A] = xs match
    case Nil      => Nil
    case x :: Nil => List(x)
    case x :: xs  => x :: a :: xs.intersperse(a)
