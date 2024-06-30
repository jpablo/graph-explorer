package org.jpablo.typeexplorer.viewer.utils

import scala.util.matching.Regex
import scala.collection.mutable.ArrayBuffer
import compiletime.asMatchable

case class CSV(rows: Array[Array[String]]):
  def toList =
    rows.toList.map(_.toList)

  def asString(strDelimiter: String = ",") =
    rows.map(_.mkString(strDelimiter)).mkString("\n")

  def equal(other: CSV): Boolean =
    Array.equals(rows.map(_.toList), other.rows.map(_.toList))

val delimiter = "Delimiter"
val quoted = "Quoted"
val unquoted = "Unquoted"

// https://www.bennadel.com/blog/1504-ask-ben-parsing-csv-strings-with-javascript-exec-regular-expression-command.htm
def CSVToArray(strData: String, strDelimiter: String = ","): CSV =
  val objPattern =
    Regex(
      s"(\\Q$strDelimiter\\E|\\r?\\n|\\r|^)" +
        "(?:\"([^\"]*(?:\"\"[^\"]*)*)\"|" +
        s"([^\"\\Q$strDelimiter\\E\\r\\n]*))",
      delimiter,
      quoted,
      unquoted
    )

  val arrData: ArrayBuffer[ArrayBuffer[String]] = ArrayBuffer.empty

  val matches = objPattern.findAllMatchIn(strData).toList

  for (m, i) <- matches.zipWithIndex if m.start != m.end do
    // Get the delimiter that was found.
    val strMatchedDelimiter = m.group(delimiter)

    // Check if the given delimiter has a length (is not the start of string)
    // and if it matches field delimiter. If it does not, then we know
    // that this delimiter is a row delimiter.
    if (arrData.isEmpty || (strMatchedDelimiter.nonEmpty && strMatchedDelimiter != strDelimiter)) then
      // Since we have reached a new row of data,
      // add an empty row to our data ArrayBuffer.
      arrData += ArrayBuffer.empty[String]

    // Check to see which kind of value we captured (quoted or unquoted).
    val strMatchedValue = m.group(quoted) match
      case null   => m.group(unquoted)          // We found a non-quoted value.
      case quoted => quoted.replace("\"\"", "\"") // We found a quoted value. Unescape any double quotes.
    // Add the value to the current row in the data ArrayBuffer.
    if arrData.nonEmpty then
      arrData.last += strMatchedValue

  // Convert ArrayBuffer[ArrayBuffer[String]] to Array[Array[String]]
  CSV(arrData.map(_.toArray).toArray)
