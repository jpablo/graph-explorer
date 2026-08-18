package org.jpablo.graphexplorer.gxcore.command

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, viewerGraphElementsToText}
import upickle.default.read

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** P8 / D2.3: the layout-free path must read the SAME graph as the layout path.
  *
  * This is the contract that lets `gx run` skip a `dot` layout it never needed.
  * Both paths share `parse -> resolve` verbatim, so agreement is structural
  * rather than hopeful — but "structural" is a claim about code someone read,
  * and the corpus is 168 files of what graphviz actually does. The claim is
  * worth exactly what this sweep says it is worth.
  *
  * Same shape as V-13: one contract, two implementations, cross-checked on real
  * data rather than on examples chosen by whoever wrote the implementation.
  */
class StructureAgreementSpec extends FunSuite:

  private val corpus: Path =
    val here = Paths.get("").toAbsolutePath.resolve("graphviz/corpus")
    if Files.isDirectory(here) then here
    else Paths.get("").toAbsolutePath.getParent.getParent.resolve("graphviz/corpus")

  private lazy val files: Vector[Path] =
    Files.list(corpus).iterator().asScala.toVector
      .filter(_.getFileName.toString.endsWith(".dot"))
      .sortBy(_.getFileName.toString)

  private def viaLayout(text: String): Option[ViewerGraph] =
    Graphviz.renderFormats(text, Seq("dot_json")).output.get("dot_json")
      .map(j => toViewerGraph(read[SimpleGraph](j)))

  private def viaStructure(text: String): Option[ViewerGraph] =
    Graphviz.structureJson(text).toOption.map(j => toViewerGraph(read[SimpleGraph](j)))

  test("the corpus is where it is expected to be"):
    assert(Files.isDirectory(corpus), s"no corpus at $corpus")
    assert(files.size > 100, s"only ${files.size} corpus files found at $corpus")

  test("every corpus file reads the same graph with and without a layout"):
    val mismatched = Vector.newBuilder[String]
    val skipped    = Vector.newBuilder[String]
    var compared   = 0

    for file <- files do
      val name = file.getFileName.toString
      val text = Files.readString(file)
      (viaLayout(text), viaStructure(text)) match
        case (Some(slow), Some(fast)) =>
          compared += 1
          if fast != slow then mismatched += name
        case (slow, fast) =>
          // A file the layout path itself cannot render is not evidence about
          // the fast path. Recorded rather than passed over in silence.
          skipped += s"$name (layout=${slow.isDefined}, structure=${fast.isDefined})"

    val bad  = mismatched.result()
    val skip = skipped.result()
    println(s"[P8] compared $compared corpus files; ${bad.size} mismatched, ${skip.size} skipped")
    if skip.nonEmpty then println(s"[P8] skipped: ${skip.mkString(", ")}")
    assert(compared > 100, s"only $compared files were actually compared")
    assertEquals(bad, Vector.empty[String], s"these read differently without a layout: ${bad.mkString(", ")}")

  /** The property a user actually experiences: the bytes written back over
    * their file by `gx run set-attribute`.
    *
    * **Today this is implied by the clause above, and the comment that first
    * stood here said otherwise.** The reasoning was that `arrows`, `groups` and
    * `memberships` are plain `Map`s, whose equality ignores order while a
    * serializer iterates it — so equal graphs could still render differently.
    * That is wrong for this serializer: `ViewerGraphElementsToText` orders
    * nodes, child groups and arrows by the `_gvid` attribute (and `Arrow.seq`),
    * which equality already compares. Reversing a graph's node order and
    * re-rendering produces identical text — checked, rather than assumed a
    * second time.
    *
    * Kept regardless, for two reasons. It asserts the user-visible property
    * directly instead of a proxy for it — the same instinct as measuring a
    * rendered position rather than the state that should produce it. And the
    * implication runs through the serializer's current ordering rule: if that
    * ever starts depending on `Map` iteration order, equality will not notice
    * and this will.
    */
  test("every corpus file renders back to byte-identical DOT"):
    val mismatched = Vector.newBuilder[String]
    var compared   = 0

    for file <- files do
      val name = file.getFileName.toString
      val text = Files.readString(file)
      (viaLayout(text), viaStructure(text)) match
        case (Some(slow), Some(fast)) =>
          compared += 1
          val a = viewerGraphElementsToText(slow.elements, omitInternal = true)
          val b = viewerGraphElementsToText(fast.elements, omitInternal = true)
          if a != b then mismatched += name
        case _ => ()

    val bad = mismatched.result()
    println(s"[P8] rendered $compared corpus files; ${bad.size} differed")
    assert(compared > 100, s"only $compared files were actually compared")
    assertEquals(bad, Vector.empty[String], s"these render differently without a layout: ${bad.mkString(", ")}")
