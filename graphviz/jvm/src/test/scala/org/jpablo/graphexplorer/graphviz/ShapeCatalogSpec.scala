package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** Shape-catalog build-out gate (the `1XX-*` corpus probes). Every gv 13.0.1
  * builtin node shape beyond the original convex-polygon set — periphery-driven
  * polygons, the special-corner container shapes (note/tab/folder/box3d/
  * component/underline), the custom generators (cylinder/star), the M-variants,
  * and the SBOL biological-circuit set — is driven here as a single-node probe
  * and asserted byte-exact (dot_json + json0 + svg) vs the viz-js oracle.
  *
  * `done` grows category by category as each lands; the remaining probes stay
  * listed (documented, un-gated) so the target set is visible. When `done`
  * covers all of them the shape catalog is closed and viz-js can be dropped as
  * the render fallback.
  */
class ShapeCatalogSpec extends FunSuite:

  // ── landed (gated byte-exact) ─────────────────────────────────────────────
  private val done: List[String] = List(
    // Category A — generic polygon engine: peripheries + user attrs + egg
    "100-doubleoctagon", "101-tripleoctagon", "102-egg", "103-polygon-user",
    "104-peripheries3",
    // Category B — round_corners container shapes
    "110-note", "111-tab", "112-folder", "113-box3d", "114-component", "115-underline",
    // Category E — SBOL biological-circuit shapes (share round_corners)
    "140-promoter", "141-cds", "142-terminator", "143-utr", "144-insulator",
    "145-ribosite", "146-rnastab", "147-proteasesite", "148-proteinstab",
    "149-primersite", "150-restrictionsite", "151-fivepoverhang", "152-threepoverhang",
    "153-noverhang", "154-assembly", "155-signature", "156-rpromoter",
    "157-rarrow", "158-larrow", "159-lpromoter"
  )

  // ── remaining targets (documented; not yet byte-exact) ────────────────────
  //   B containers : 110-note 111-tab 112-folder 113-box3d 114-component 115-underline
  //   C generators : 120-cylinder 121-star
  //   D M-variants : 130-Mdiamond 131-Msquare 132-Mcircle
  //   E SBOL bio   : 140-promoter … 159-lpromoter

  private def g(n: String) =
    AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(n)).toOption.get)
      .copy(images = OracleHarness.corpusImages(n))

  done.foreach { n =>
    test(s"$n: dot_json byte-exact"):
      assertEquals(Output.dotJson(g(n)), OracleHarness.golden(n, "dot_json"))
    test(s"$n: json0 byte-exact"):
      assertEquals(Output.json0(g(n)), OracleHarness.golden(n, "json0"))
    test(s"$n: svg byte-exact"):
      assertEquals(Svg.svg(g(n)), OracleHarness.golden(n, "svg"))
  }

end ShapeCatalogSpec
