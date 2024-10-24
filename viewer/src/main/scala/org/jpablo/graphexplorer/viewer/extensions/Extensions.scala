package org.jpablo.graphexplorer.viewer.extensions

extension [A](a: A)
  def orElse(b: Boolean, f: A => A): A =
    if b then a else f(a)

  infix def in(sa: Set[A]): Boolean =
    sa.contains(a)

  infix def notIn(sa: Set[A]): Boolean =
    !sa.contains(a)

extension [A](set: Set[A])
  def toggle(a: A) =
    set.toggleWith(a, a notIn set)

  def toggleWith(a: A, b: Boolean) =
    if b then set + a else set - a

extension [K](map: Map[K, Boolean])
  def toggle(k: K, initial: Boolean = false) =
    map + (k -> !map.getOrElse(k, initial))
