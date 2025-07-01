package org.jpablo.graphexplorer.viewer.logging

import scala.scalajs.js.Date

var lastDate: Date = null

enum Level derives CanEqual:
  case Debug, Info, Warn, Error, None

  def toConsoleLog = this match
    case Debug => dom.console.debug(_)
    case Info  => dom.console.info(_)
    case Warn  => dom.console.warn(_)
    case Error => dom.console.error(_)
    case None  => (_: Any) => ()

object Level:
  def fromString(s: String): Level = s.toLowerCase match
    case "debug" => Debug
    case "info"  => Info
    case "warn"  => Warn
    case "error" => Error
    case "none"  => None
    case _       => Info // default to Info for invalid values

import Level.*

def timeDelta() =
  if lastDate == null then
    lastDate = new Date()
  val currentDate = new Date()
  val delta       = currentDate.getTime() - lastDate.getTime()
  lastDate = currentDate
  s"${delta / 1000.0} s,  at: ${currentDate.toISOString().split('T')(1)}"

inline def withLog[A](
    label: String,
    level: Level = None
)(body: => A): A =
  val log = level.toConsoleLog
  if level != Level.None then
    dom.console.group(s"$label: ${timeDelta()}")

  timeDelta()
  val a = body

  log(s"$a")
  if level != Level.None then
    dom.console.groupEnd()
//  fn(s"$numberedLabel [<--]: ${timeDelta()}")
  a
end withLog

def simpleLog(label: String, level: Level = None): Unit =
  level.toConsoleLog(label)
