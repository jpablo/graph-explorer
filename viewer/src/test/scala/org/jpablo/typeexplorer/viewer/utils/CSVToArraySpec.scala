package org.jpablo.typeexplorer.viewer.utils

import org.jpablo.typeexplorer.viewer.formats.CSV

class CSVToArraySpec extends munit.FunSuite:
  test("empty string"):
    val csv = CSV("")
    assert(csv.equal(CSV(Array.empty[Array[String]])))

  test("single row"):
    val csv = CSV("a,b")
    assert(csv.equal(CSV(Array(Array("a", "b")))))
