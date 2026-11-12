package org.jpablo.graphexplorer.viewer.components

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks

import scala.scalajs.js
import scala.util.Try

/** `Command.execute`: what each command does before its action. */
class CommandExecuteSpec extends FunSuite with LoggedChecks:

  def checkPrefix = "CommandExecuteSpec"

  // `Command.execute` sends an event to Google Analytics through `gtag`. The
  // page of the app defines `gtag` (index.html). A page with no `gtag` (a
  // test page, or a page that blocks the script) must still run the
  // command. The read of an undeclared global name throws ReferenceError in
  // JavaScript, and `typeof` of that name does not.
  // Failure: the command throws "ReferenceError: gtag is not defined", and
  // its action does not run.
  test("a command runs on a page with no gtag"):
    check(js.typeOf(js.Dynamic.global.gtag) == "undefined", "precondition: the test page defines gtag")
    var ran     = false
    val command = Command("Probe", () => ran = true, Command.always)
    val outcome = Try(command.execute())
    same(outcome.failed.toOption.map(_.toString), None, "the command threw on a page with no gtag")
    check(ran, "the action of the command did not run")
