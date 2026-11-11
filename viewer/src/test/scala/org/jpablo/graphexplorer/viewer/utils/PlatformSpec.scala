package org.jpablo.graphexplorer.viewer.utils

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks

/** `Platform`: the platform of the browser, and the command key of each one. */
class PlatformSpec extends FunSuite with LoggedChecks:

  def checkPrefix = "PlatformSpec"

  test("the command key is Meta on a Mac, and Ctrl on each other platform"):
    // (meta, ctrl, isMac) -> the command key is held
    val table = List(
      (true, false, true)   -> true,
      (false, true, true)   -> false,
      (false, false, true)  -> false,
      (false, true, false)  -> true,
      (true, false, false)  -> false,
      (false, false, false) -> false
    )
    val wrong = table.collect:
      case ((meta, ctrl, isMac), expected) if Platform.commandKey(meta, ctrl, isMac) != expected =>
        s"meta=$meta ctrl=$ctrl isMac=$isMac gives ${!expected}"
    same(wrong, Nil, "the command key is wrong")

  test("the names of the Apple platforms are Mac platforms, and the others are not"):
    val apple = List(
      "macOS",
      "MacIntel",
      "iPad",
      "iPhone",
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
    )
    val other = List(
      "Windows",
      "Win32",
      "Linux x86_64",
      "Chrome OS",
      "Android",
      "",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )
    same(apple.filterNot(Platform.isAppleName), Nil, "an Apple platform is not a Mac platform")
    same(other.filter(Platform.isAppleName), Nil, "a different platform is a Mac platform")

  test("the platform of the test browser reads with no error"):
    // The value depends on the host. The read must give a value, and not throw.
    val isMac = Platform.isMac
    check(isMac || !isMac, "no value")
