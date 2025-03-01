package org.jpablo.graphexplorer.viewer.utils

trait MathOps[A]:
  extension (a: A)
    def -(b: A): A
    def *(z: A): A

type Point2d[A] = (x: A, y: A)


extension [A](a: Point2d[A])(using MathOps[A])
  def -(b: Point2d[A]): Point2d[A] = (x = a.x - b.x, y = a.y - b.y)
  def *(b: A): Point2d[A] = (a.x * b, a.y * b)

