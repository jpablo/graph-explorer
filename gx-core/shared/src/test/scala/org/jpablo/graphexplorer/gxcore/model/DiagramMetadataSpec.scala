package org.jpablo.graphexplorer.gxcore.model

import munit.FunSuite

import scala.compiletime.constValueTuple
import scala.deriving.Mirror

/** The metadata of a diagram has no name and no folder (CONTEXT.md).
  *
  * The name of a diagram is the title in its source, or else its file name.
  * The library has no folders: a person uses tags and the folders of the file
  * system. When the type has no field for them, no code path can write them.
  */
class DiagramMetadataSpec extends FunSuite:

  test("the metadata has exactly the fields of plan §3, and no name and no folder"):
    val mirror = summon[Mirror.ProductOf[DiagramMetadata]]
    val fields = constValueTuple[mirror.MirroredElemLabels].toList.map(_.toString)
    assertEquals(fields, List("hiddenElements", "collapsedGroups", "tags", "notes", "autoDetectFormat"))
