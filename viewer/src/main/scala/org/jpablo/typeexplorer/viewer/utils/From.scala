package org.jpablo.typeexplorer.viewer.utils

trait From[T, Self]:
  def from(t: T): Self
