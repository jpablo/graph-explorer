package org.jpablo.graphexplorer.viewer.formats.dot

import munit.FunSuite

class TextUtilsSpec extends FunSuite:

  test("escape converts newlines and backslashes to DOT escapes"):
    assertEquals(TextUtils.escape("a\nb"), "a\\nb")
    assertEquals(TextUtils.escape("a\\b"), "a\\\\b")

  test("unescape is the inverse of escape"):
    // The literal text \n (backslash + n) is the interacting case: stored as \\n,
    // it must unescape back to backslash + n, not to a line break.
    for text <- List("a\nb", "a\\b", "a\\nb", "line1\nline2\n", "\\", "plain") do
      assertEquals(TextUtils.unescape(TextUtils.escape(text)), text, s"text: $text")

  test("unescape leaves unknown escapes untouched"):
    assertEquals(TextUtils.unescape("a\\lb"), "a\\lb")
