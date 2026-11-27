package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.LayoutPoint
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The label of a group in a free layout (ADR 0004).
  *
  * `dot` keeps space for the label of a group (a DOT cluster) above its nodes. `neato` draws the free layout. It draws
  * each group as a box around its nodes, with no space for the label. The label then goes on the nodes, and a node
  * hides it.
  *
  * Found on 2026-11-27 in the example "Groups": after Manual, the node `b0` hid the label "process #2".
  */
class ClusterLabelSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix     = "ClusterLabelSpec"
  def canvasDiagramId = "cluster-label"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val groups =
    """digraph G {
      |  subgraph cluster_0 {
      |    style=filled; color=lightgrey; node [style=filled, color=white]
      |    a0 -> a1 -> a2 -> a3
      |    label = "process #1"
      |  }
      |  subgraph cluster_1 {
      |    node [style=filled]
      |    b0 -> b1 -> b2 -> b3
      |    label = "process #2"
      |    color = blue
      |  }
      |  start -> a0
      |  start -> b0
      |  a1 -> b3
      |  b2 -> a3
      |  a3 -> a0
      |  a3 -> end
      |  b3 -> end
      |  start [shape=Mdiamond]
      |  end [shape=Msquare]
      |}""".stripMargin

  private type Box = (Double, Double, Double, Double) // left, top, right, bottom

  private def boxOf(e: dom.Element): Box =
    val r = e.getBoundingClientRect()
    (r.left, r.top, r.right, r.bottom)

  private def overlaps(p: Box, q: Box): Boolean =
    p._1 < q._3 - 0.5 && q._1 < p._3 - 0.5 && p._2 < q._4 - 0.5 && q._2 < p._4 - 0.5

  private def inside(p: Box, q: Box): Boolean =
    p._1 >= q._1 - 1 && p._3 <= q._3 + 1 && p._2 >= q._2 - 1 && p._4 <= q._4 + 1

  private def fmt(b: Box): String = f"(${b._1}%.1f,${b._2}%.1f,${b._3}%.1f,${b._4}%.1f)"

  /** Each fault of the group labels: a label that a node covers, or a label outside the box of its group. */
  private def labelFaults(canvas: TestCanvas): Seq[String] =
    val nodes = canvas.box.querySelectorAll("g.node").toSeq.collect { case g: dom.Element => g }.map: g =>
      val shape = Option(g.querySelector("ellipse")).orElse(Option(g.querySelector("polygon"))).getOrElse(g)
      (Option(g.querySelector("title")).map(_.textContent).getOrElse(g.id), boxOf(shape))
    val clusters = canvas.box.querySelectorAll("g.cluster").toSeq.collect { case g: dom.Element => g }
    clusters.flatMap: cluster =>
      val name  = Option(cluster.querySelector("title")).map(_.textContent).getOrElse(cluster.id)
      val frame = Option(cluster.querySelector("polygon")).orElse(Option(cluster.querySelector("path"))).map(boxOf)
      cluster.querySelectorAll("text").toSeq.collect { case t: dom.Element => t }.flatMap: text =>
        val label   = boxOf(text)
        val covered = nodes.filter((_, b) => overlaps(label, b)).map((id, b) => s"$id ${fmt(b)}")
        val coveredFault =
          Option.when(covered.nonEmpty)(s"$name: the label '${text.textContent}' ${fmt(label)} is under the nodes ${covered.mkString(", ")}")
        val outsideFault = frame.filterNot(f => inside(label, f)).map: f =>
          s"$name: the label '${text.textContent}' ${fmt(label)} is not in the box of its group ${fmt(f)}"
        coveredFault.toSeq ++ outsideFault.toSeq

  /** Each group box, and each label, that the sheet of the drawing does not hold. The sheet is the background polygon
    * of Graphviz, and the svg must show all of it.
    */
  private def sheetFaults(canvas: TestCanvas): Seq[String] =
    Option(canvas.svg.querySelector("g.graph > polygon")).map(boxOf).fold(Seq("the drawing has no sheet")): sheet =>
      val parts = canvas.box.querySelectorAll("g.cluster polygon, g.cluster path, g.cluster text").toSeq.collect { case e: dom.Element => e }
      parts.map(boxOf).filterNot(inside(_, sheet)).map(b => s"the part ${fmt(b)} is not on the sheet ${fmt(sheet)}")

  /** Freeze the diagram, and wait for the drawing of the free layout. */
  private def freeze(canvas: TestCanvas, first: NodeId): Future[Unit] =
    val before = canvas.svg
    canvas.state.history.markGesture()
    canvas.state.freezeLayout()
    Waits
      .settle(!(canvas.svg eq before) && canvas.nodeGroup(first).isDefined, timeoutMs = 10000)
      .flatMap(_ => Waits.after(100))
      .map(_ => check(canvas.state.layout.now().isManual, "precondition: Freeze did not set the manual mode"))

  test("in a free layout, the label of each group is in the box of the group, and no node covers it"):
    withCanvas(groups, NodeId("a0")): canvas =>
      canvas.state.animateLayoutChanges.set(false)
      Waits
        .after(300)
        .flatMap: _ =>
          // The automatic layout (dot) keeps space for each label: this is the look to keep.
          val auto = labelFaults(canvas)
          check(auto.isEmpty, s"precondition: the automatic layout already has a fault: ${auto.mkString("; ")}")
          freeze(canvas, NodeId("a0"))
        .map: _ =>
          val faults = labelFaults(canvas) ++ sheetFaults(canvas)
          check(faults.isEmpty, s"in the free layout: ${faults.mkString("; ")}")

  private val bottomRight =
    """digraph G {
      |  subgraph cluster_x {
      |    labelloc = b; labeljust = r
      |    label = "short"
      |    p -> q
      |  }
      |  r -> p
      |}""".stripMargin

  test("in a free layout, labelloc=b and labeljust=r put the label at the bottom right of the box"):
    withCanvas(bottomRight, NodeId("p")): canvas =>
      canvas.state.animateLayoutChanges.set(false)
      // Two nodes far apart: the box is wider than the label, so the place of the label shows the justification.
      val before = canvas.svg
      canvas.state.history.markGesture()
      canvas.state.placeNodes(Map(NodeId("p") -> LayoutPoint(0, 0), NodeId("q") -> LayoutPoint(200, -90), NodeId("r") -> LayoutPoint(0, 120)))
      Waits
        .settle(!(canvas.svg eq before) && canvas.nodeGroup(NodeId("q")).isDefined, timeoutMs = 10000)
        .flatMap(_ => Waits.after(200))
        .map: _ =>
          val faults = labelFaults(canvas) ++ sheetFaults(canvas)
          check(faults.isEmpty, s"in the free layout: ${faults.mkString("; ")}")
          val cluster = Option(canvas.box.querySelector("g.cluster"))
          check(cluster.exists(c => c.querySelector("polygon") != null && c.querySelector("text") != null), s"no group box with a label: ${cluster.map(_.outerHTML)}")
          val frame = boxOf(cluster.get.querySelector("polygon"))
          val label = boxOf(cluster.get.querySelector("text"))
          val q     = boxOf(canvas.nodeGroup(NodeId("q")).get)
          check(label._2 >= q._4, s"the label ${fmt(label)} is not below the lowest node ${fmt(q)}")
          check(frame._3 - label._3 < (label._1 - frame._1) / 4, s"the label ${fmt(label)} is not at the right of the box ${fmt(frame)}")

  private val mermaidGroups =
    """flowchart TB
      |  subgraph one [process number one]
      |    A --> B
      |  end
      |  S --> A
      |""".stripMargin

  test("in a free layout, the label of a Mermaid subgraph is in the box of the group, and no node covers it"):
    withCanvasWhen(mermaidGroups, "the Mermaid drawing shows a group")(_.box.querySelector("g.cluster") != null): canvas =>
      canvas.state.animateLayoutChanges.set(false)
      val before = canvas.svg
      canvas.state.history.markGesture()
      canvas.state.freezeLayout()
      Waits
        .settle(!(canvas.svg eq before) && canvas.nodeGroup(NodeId("A")).isDefined, timeoutMs = 10000)
        .flatMap(_ => Waits.after(100))
        .map: _ =>
          check(canvas.state.layout.now().isManual, "precondition: Freeze did not set the manual mode")
          check(canvas.box.querySelector("g.cluster text") != null, "precondition: the free layout shows no group label")
          val faults = labelFaults(canvas) ++ sheetFaults(canvas)
          check(faults.isEmpty, s"in the free layout: ${faults.mkString("; ")}")
