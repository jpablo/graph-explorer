package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

/** Helpers for the specs that read or draw a text with the real Mermaid library, as the canvas does. */
object RealMermaid:

  /** The result of `body` in a future that does not fail. A failure of `body` is a `Failure` in the future. */
  def attempt[A](body: => Future[A])(using ExecutionContext): Future[Try[A]] =
    Future.unit.flatMap(_ => body).transform(result => Success(result))

  /** Read the text with real Mermaid, as the canvas does (`MermaidBackend.textToGraph`). */
  def readWithMermaid(text: String)(using ExecutionContext): Future[Try[ViewerGraph]] =
    attempt(MermaidBackend().textToGraph(text))

  /** Draw the text with real Mermaid, as the canvas does (`MermaidBackend.textToSvg`). */
  def drawWithMermaid(text: String)(using ExecutionContext): Future[Try[dom.svg.SVG]] =
    attempt(MermaidBackend().textToSvg(text).map(_.svg.ref))

  /** Run `f` on each item, one item after the other. Mermaid keeps global state. */
  def oneByOne[A, B](items: Seq[A])(f: A => Future[B])(using ExecutionContext): Future[Vector[B]] =
    items.foldLeft(Future.successful(Vector.empty[B]))((done, item) => done.flatMap(bs => f(item).map(bs :+ _)))

  /** The first three lines of the message of `e`, on one line. */
  def firstLine(e: Throwable): String =
    Option(e.getMessage).getOrElse(e.toString).linesIterator.take(3).mkString(" | ")

  /** The text in Scala literal form, so a quote, a backslash, a line break or a tab shows in a failure message. */
  def literal(text: String): String =
    "\"" + text
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\r", "\\r")
      .replace("\n", "\\n")
      .replace("\t", "\\t") + "\""
