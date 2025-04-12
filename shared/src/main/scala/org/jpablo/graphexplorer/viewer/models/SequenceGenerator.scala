package org.jpablo.graphexplorer.viewer.models

trait SequenceGenerator:
  def resetSequence(): Unit
  def nextSequence(): Int

// Default implementation of the capability
class DefaultSequenceGenerator extends SequenceGenerator:
  private var _seq = 0

  def resetSequence(): Unit =
    _seq = 0

  def nextSequence(): Int =
    _seq += 1
    _seq
