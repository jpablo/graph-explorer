package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.models.ElementId
import org.scalajs.dom
import upickle.default.writeJs

import scala.scalajs.js.JSON

/** The Ids of nodes displayed in the diagram
  */
type HiddenNodes = Set[ElementId]

class HiddenNodesOps(val hiddenNodesV: Var[Set[ElementId]]):

  val signal = hiddenNodesV.signal.tapEach(s => dom.console.debug("hiddenNodesV:", JSON.parse(writeJs(s).toString)))

  def toggle(s: ElementId): Unit =
    hiddenNodesV.update(_.toggle(s))

  def extend(s: ElementId): Unit =
    hiddenNodesV.update(_ + s)

  def extend(ss: collection.Seq[ElementId]): Unit =
    hiddenNodesV.update(_ ++ ss)

  def add(ss: Set[ElementId]): Unit =
    hiddenNodesV.update(_ ++ ss)

  def remove(ss: Set[ElementId]): Unit =
    hiddenNodesV.update(_ -- ss)

  def clear(): Unit =
    hiddenNodesV.set(Set.empty)

end HiddenNodesOps
