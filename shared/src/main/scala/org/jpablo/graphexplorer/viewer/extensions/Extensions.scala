package org.jpablo.graphexplorer.viewer.extensions

import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds}
import scala.annotation.targetName

extension [A](a: A)
  inline def orElse(b: Boolean, f: A => A): A =
    if b then a else f(a)

  inline infix def in(sa: Set[A]): Boolean =
    sa.contains(a)

  @targetName("inSet")
  inline infix def in(ids: ElementIds)(using A <:< ElementId): Boolean =
    ids.contains(a)

  inline infix def in(sa: Map[A, ?]): Boolean =
    sa.contains(a)

  @targetName("notInSet")
  inline infix def notIn(ids: ElementIds)(using A <:< ElementId): Boolean =
    !ids.contains(a)


  inline infix def notIn(sa: Set[A]): Boolean =
    !sa.contains(a)

extension [A](set: Set[A])
  def toggle(a: A) =
    set.toggleWith(a, a notIn set)

  def toggleWith(a: A, b: Boolean) =
    if b then set + a else set - a

extension [K](map: Map[K, Boolean])
  def toggle(k: K, initial: Boolean = false) =
    map + (k -> !map.getOrElse(k, initial))
