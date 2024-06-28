package org.jpablo.typeexplorer.viewer.utils

import scala.util.matching.Regex
import scala.collection.mutable.ArrayBuffer

case class CSV(rows: Array[Array[String]])

// https://www.bennadel.com/blog/1504-ask-ben-parsing-csv-strings-with-javascript-exec-regular-expression-command.htm
def CSVToArray(strData: String, strDelimiter: String = ","): CSV =
  val objPattern =
    Regex(
      s"(\\Q$strDelimiter\\E|\\r?\\n|\\r|^)" +
        "(?:\"([^\"]*(?:\"\"[^\"]*)*)\"|" +
        s"([^\"\\Q$strDelimiter\\E\\r\\n]*))",
      "Delimiter",
      "Quoted",
      "Unquoted"
    )

  val arrData = ArrayBuffer(ArrayBuffer.empty[String])

  for m <- objPattern.findAllMatchIn(strData) do
    // Get the delimiter that was found.
    val strMatchedDelimiter = m.group("Delimiter")

    // Check if the given delimiter has a length (is not the start of string)
    // and if it matches field delimiter. If it does not, then we know
    // that this delimiter is a row delimiter.
    if (strMatchedDelimiter.nonEmpty && strMatchedDelimiter != strDelimiter) then
      // Since we have reached a new row of data,
      // add an empty row to our data ArrayBuffer.
      arrData += ArrayBuffer.empty[String]

    // Check to see which kind of value we captured (quoted or unquoted).
    val strMatchedValue = m.group("Quoted") match
      case null   => m.group("Unquoted")          // We found a non-quoted value.
      case quoted => quoted.replace("\"\"", "\"") // We found a quoted value. Unescape any double quotes.

    // Add the value to the current row in the data ArrayBuffer.
    arrData.last += strMatchedValue

  // Convert ArrayBuffer[ArrayBuffer[String]] to Array[Array[String]]
  CSV(arrData.map(_.toArray).toArray)
