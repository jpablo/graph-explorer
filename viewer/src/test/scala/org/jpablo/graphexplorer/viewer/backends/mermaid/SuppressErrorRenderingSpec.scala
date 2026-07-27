package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import scala.scalajs.js

/** A failed Mermaid render must not leave anything behind in the page.
  *
  * Mermaid's `render` appends a temporary div to `<body>` (we pass no container),
  * draws into it, and removes it when done. On failure that cleanup only happens
  * under `suppressErrorRendering` — otherwise mermaid swaps in its "Syntax error
  * in text" bomb diagram AND skips `removeTempElements()`, so the div is orphaned.
  * Since the editor re-renders per keystroke, a diagram with a syntax error grew a
  * new bomb below the app on every keypress.
  *
  * The trap is that the option that *sounds* right, `suppressErrors`, belongs to
  * `run()`/`parse()` and is ignored by `initialize()` — we shipped it for a long
  * time believing it was handling this. So the assertion here is on the exact key
  * name reaching the config object, not on behaviour we can't observe headlessly.
  */
class SuppressErrorRenderingSpec extends FunSuite:

  private def keys(o: js.Object): Set[String] =
    js.Object.keys(o).toSet

  test("the initialize config carries suppressErrorRendering, not suppressErrors"):
    val cfg = MermaidConfig(suppressErrorRendering = true)
    assert(
      keys(cfg).contains("suppressErrorRendering"),
      s"missing suppressErrorRendering — a syntax error will inject a bomb diagram into <body>. Keys: ${keys(cfg)}"
    )
    assert(
      !keys(cfg).contains("suppressErrors"),
      "suppressErrors is a run()/parse() option; initialize() ignores it, so its presence here is misleading"
    )

  test("suppressErrorRendering is passed through, not hardcoded"):
    val on  = MermaidConfig(suppressErrorRendering = true)
    val off = MermaidConfig(suppressErrorRendering = false)
    assertEquals(on.asInstanceOf[js.Dynamic].suppressErrorRendering.asInstanceOf[Boolean], true)
    assertEquals(off.asInstanceOf[js.Dynamic].suppressErrorRendering.asInstanceOf[Boolean], false)

  test("the default is OFF — only the explicit call site turns it on"):
    // Mirrors mermaid's own default. If this ever flips, the MermaidBackend call
    // site is no longer the single place that decides, and the test above stops
    // proving anything.
    assertEquals(
      MermaidConfig().asInstanceOf[js.Dynamic].suppressErrorRendering.asInstanceOf[Boolean],
      false
    )
