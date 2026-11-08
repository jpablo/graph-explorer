package org.jpablo.graphexplorer.viewer.models

import munit.FunSuite
import upickle.default.{read, write}

/** `localStorage` and the library on disk store a `DiagramId` as
  * `{"value": "..."}`. This spec pins that shape, because a different shape
  * loses the diagrams that a person already has.
  */
class DiagramIdSpec extends FunSuite:

  test("a DiagramId is written as an object with one value field"):
    assertEquals(write(DiagramId("abc")), """{"value":"abc"}""")

  test("a stored DiagramId is read back"):
    assertEquals(read[DiagramId]("""{"value":"abc"}"""), DiagramId("abc"))

  test("derivedFrom joins the namespace and the key"):
    assertEquals(DiagramId.derivedFrom("ls", "p1"), DiagramId("ls-p1"))

  test("random gives a different id each time"):
    assertNotEquals(DiagramId.random, DiagramId.random)
