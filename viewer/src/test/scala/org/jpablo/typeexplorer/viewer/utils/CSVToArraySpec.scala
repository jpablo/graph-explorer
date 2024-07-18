package org.jpablo.typeexplorer.viewer.utils

import org.jpablo.typeexplorer.viewer.formats.CSV

class CSVToArraySpec extends munit.FunSuite:
  test("empty string"):
    val csv = CSV.fromString("")
    assert(csv.equal(CSV(Array.empty)))

  test("single row"):
    val csv = CSV.fromString("a,b")
    assert(csv.equal(CSV(Array(Array("a", "b")))))
