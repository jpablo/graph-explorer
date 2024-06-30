package org.jpablo.typeexplorer.viewer.utils

class CSVToArraySpec extends munit.FunSuite:
  test("empty string"):
    val csv = CSVToArray("")
    assert(csv.equal(CSV(Array.empty)))

  test("single row"):
    val csv = CSVToArray("a,b")
    assert(csv.equal(CSV(Array(Array("a", "b")))))
