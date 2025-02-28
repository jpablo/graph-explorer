package org.jpablo.graphexplorer.viewer.utils

type Version = Long

trait Utils:
  def randomUUID(): String
  def randomUUIDSafe(): String

object Utils extends UtilsPlatform

enum ChangeOrigin:
  case CodeMirror, Graph

extension [A](xs: List[A])
  def intersperse(a: A): List[A] = xs match
    case Nil      => Nil
    case x :: Nil => List(x)
    case x :: xs  => x :: a :: xs.intersperse(a)
