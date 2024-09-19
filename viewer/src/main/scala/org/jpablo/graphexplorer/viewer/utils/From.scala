package org.jpablo.graphexplorer.viewer.utils

trait From[T, Self]:
  def from(t: T): Self
