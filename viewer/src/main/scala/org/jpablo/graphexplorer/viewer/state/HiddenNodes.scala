package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.scalajs.dom
import upickle.default.writeJs

import scala.scalajs.js.JSON

/** The Ids of nodes displayed in the diagram
  */
type HiddenElements = ElementIds

class HiddenNodesOps(val hiddenNodesV: Var[HiddenElements]):

  def now(): HiddenElements = hiddenNodesV.now()

  val signal = hiddenNodesV.signal.tapEach(s => dom.console.debug("hiddenNodesV:", JSON.parse(writeJs(s).toString)))

  def toggle(s: NodeId): Unit =
    hiddenNodesV.update(elements => elements.toggle(s))

  def extend(s: NodeId): Unit =
    hiddenNodesV.update(_ + s)

//  def extend(ss: collection.Seq[NodeId]): Unit =
//    hiddenNodesV.update(_ ++ ss)

  def add(ss: Set[NodeId]): Unit =
    hiddenNodesV.update(_ ++ ElementIds(ss))

  def remove(ss: Set[NodeId]): Unit =
    hiddenNodesV.update(_ -- ElementIds(ss))

  def clear(): Unit =
    hiddenNodesV.set(ElementIds())

end HiddenNodesOps
