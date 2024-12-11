package org.jpablo.graphexplorer.viewer.logging

import scala.scalajs.js.Date

var lastDate: Date = null
var step = 0

enum Level:
  case Debug, Info, Warn, Error, None

  def toConsole = this match
    case Debug => dom.console.debug(_)
    case Info  => dom.console.info(_)
    case Warn  => dom.console.warn(_)
    case Error => dom.console.error(_)
    case None  => (_: Any) => ()

import Level.*

def timeDelta() =
  if lastDate == null then
    lastDate = new Date()
  val currentDate = new Date()
  val delta = currentDate.getTime() - lastDate.getTime()
  lastDate = currentDate
  s"${delta / 1000.0} s,  at: ${currentDate.toISOString().split('T')(1)}"

inline def withLog[A](
    label:     String,
    resetStep: Boolean = false,
    level:     Level = None
)(body: => A): A =
  step = if resetStep then 1 else step + 1
  val numberedLabel = s"($step) $label"
  val log = level.toConsole
  log(s"$numberedLabel [-->]: ${timeDelta()}")
  timeDelta()
  val a = body
//  println(a)
//  fn(s"$numberedLabel [<--]: ${timeDelta()}")
  a

def simpleLog(label: String, level: Level = None): Unit =
  level.toConsole(label)
