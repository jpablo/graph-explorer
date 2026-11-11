package org.jpablo.graphexplorer.viewer.utils

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks

/** `Platform`: the names of the Apple platforms. CommandsPanelSpec tests the
  * command key on each platform.
  */
class PlatformSpec extends FunSuite with LoggedChecks:

  def checkPrefix = "PlatformSpec"

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

