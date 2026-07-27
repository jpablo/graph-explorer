# `graphviz` — a pure-Scala port of the Graphviz `dot` layout engine

This module is a from-scratch, cross-compiled (JVM + Scala.js)
reimplementation of the Graphviz **`dot`** pipeline: DOT text in,
`dot_json` / `json0` / `svg` strings out. It replaces the
[`@viz-js/viz`](https://github.com/mdaines/viz-js) WASM bundle as the
viewer's default rendering engine, while viz-js stays in the project as the
**test oracle** and as the runtime engine for the layouts that are *not*
ported (see [Scope](#scope--limitations)).

The fidelity bar is unusually strict: output is **byte-exact** against real
Graphviz — not "visually close", not "within tolerance". The oracle is
`@viz-js/viz` 3.14.0, which bundles Graphviz **13.0.1** compiled to WASM, so
matching its output strings byte-for-byte means matching real Graphviz.

## Why

The viewer only ever calls two things:
`renderFormats(dot, ["dot_json"])` and `renderFormats(dot, ["svg", "json0"])`.
That makes the functional contract small enough to port: **DOT text →
(`dot_json`, `json0`, `svg`) using the `dot` engine**. A pure-Scala engine
removes the WASM runtime dependency for the common case, runs identically on
the JVM (tests, tooling, servers), and is debuggable/extensible in ways a
WASM blob is not.

## Status

**The port is complete for the `dot` engine** (all milestones M0–M8 in
[PORT.md](../PORT.md), as of 2026-07-16):

| Gate | Result |
|---|---|
| Corpus (`corpus/*.dot`, 137 files × 3 formats) | **136/137 byte-exact** (every file but one) |
| Shape catalog (61 of 62 builtin node shapes) | byte-exact (`ShapeCatalogSpec`) |
| Shipped viewer examples routed to the port | 5/8 byte-exact, 3 documented deferrals |
| Full layout pipeline (rank → mincross → coords → splines) | ported |
| Clusters, records, HTML-like labels, node images, rankdir, ports/compass, flat edges, self-loops, `rank=same` | ported, byte-exact |

There are two corpus deferrals. `03-subgraph-cluster` is **intentional**: its
golden captures Graphviz's own default-mode cluster corruption on
cross-cluster ranksets. We lay it out correctly instead and gate it against
the `newrank` oracle (`03b`) — see "don't port the bug" in PORT.md §7.
`191-scala-type-graph` is **open work** (added 2026-07-26): the first corpus
file whose clusters carry their own `fontsize`/`labeljust`/`rounded` style.
Its cluster-label, ranking and collapsed-mincross divergences are all closed
(2026-07-27 — label metrics, the flipped `GD_border`, `adjustRanks`,
`place_flip_graph_label`, the rounded cluster path, the dot1 `acyclic`
decompose seed order, the cluster-interior flip reversal, the class2
emission-ordered skeleton adjacency, and reorder's `###` sawclust rule). All
32 nodes land on gv's ranks, the cluster blocks land in gv's within-rank
order, and 8 of 10 cluster boxes have the right size. Lazy cluster expansion — gv's
one-`expand_cluster`-per-`mincross_clust` loop, the rest still collapsed — is
ported too, and **every mincross crossing count now matches gv end to end**
(the collapsed pass step for step, all ten cluster refines iteration for
iteration), putting 5 of 6 ranks in gv's exact within-rank order. What still differs is a
single +-1-crossing tie in the ReMincross tail, which permutes one cluster's
four members on one rank. See PORT.md §0.

## Scope & limitations

- **Only the `dot` engine is ported.** `neato`, `fdp`, `sfdp`, `twopi`,
  `circo`, `osage`, and `patchwork` are entirely different layout algorithms
  and are **not** implemented — the viewer routes graphs by their `layout`
  attribute via [`EngineRouting.usesDotEngine`](shared/src/main/scala/org/jpablo/graphexplorer/graphviz/EngineRouting.scala)
  (`dot`/unset → this module; anything else → viz-js). Do not drop the
  viz-js dependency without porting those engines.
- **Only three output formats**: `dot_json`, `json0`, `svg` (the slice the
  viewer consumes). No `png`/`pdf`/`xdot`/`plain` at runtime.
- **Known example deferrals** (each guarded by a fails-when-fixed test in
  `ExamplesByteExactSpec`): `data-structures` (record `rects` under
  `rankdir=LR`), `finite-state-machine` (LR + edge-label positions),
  `sbt-project-dependencies` (font-list node sizing).
- **No-corpus corner cases**, reachable but unexercised: the `epsf` shape
  (needs an external PostScript file), `\E`/`\T`/`\H` edge-label escapes,
  polygon peripheries beyond the catalog, and a few others tracked in
  PORT.md §5.
- Non-builtin fonts fall back to Times metrics (same rule as Graphviz's
  `get_metrics_for_font_family`).

## How the port was built

The methodology matters more than the code — it is what makes byte-exactness
achievable and provable rather than aspirational:

1. **Oracle harness first.** Before any layout code, `oracle/capture.mjs`
   froze golden outputs for every corpus file across 8 Graphviz formats
   (`golden/`), stamped with the bundled Graphviz version.
   `OracleContractSpec` fails if the oracle ever drifts from 13.0.1.
2. **Transcribe, don't reimplement.** The layout code is a deliberately
   1:1 transcription of the version-matched Graphviz C source (a worktree
   pinned at tag `13.0.1`). Order-sensitive algorithms — network simplex,
   mincross, spline routing, coordinate assignment — depend on iteration
   order, tie-breaks, and magic constants that "idiomatic" rewrites silently
   change. Even C integer division mattered (`GD_nodesep(g) / 4` vs `/ 4.0`
   was a real, final-spline-moving bug).
3. **Instrument, don't re-read.** When behavior was unclear, we built the
   pinned Graphviz with `getenv`-guarded `fprintf` probes and diffed its
   internal state (aux graphs, box channels, raw splines) against ours.
   Several "unfixable precision floors" turned out to be model errors this
   way — e.g. the reversed-edge clip *direction*. The recipe is in
   PORT.md §2.5.
4. **Byte-exact gates + fails-when-fixed guards.** `CorpusByteExactSpec`
   and `DifferentialSpec` diff all three formats as exact strings. Every
   known deferral has an inverted test asserting it *still* fails, so a fix
   anywhere in the pipeline immediately flags the deferral list as stale.

## Module layout

```
graphviz/
  shared/src/main/scala/.../graphviz/
    Graphviz.scala      renderFormats(dot, formats) — the public facade,
                        mirroring viz-js's MultipleRenderResult shape
    EngineRouting.scala the dot-vs-other-engine routing predicate
    dotlang/            DOT parser (fastparse) + AST
    model/              AttrResolver: AST → RGraph (attr scoping/inheritance)
    layout/             the pipeline: Rank (acyclic + network simplex),
                        Order (mincross), Coord/XCoord (y/x coords),
                        Spline (routesplines port), Cluster, RecordLabel,
                        Polygon/RoundCorners (shape catalog), Arrow, RankDir
    html/               HTML-like label parsing + table layout
    metrics/            embedded AFM font tables (transcribed textspan_lut)
    output/             Output (dot_json/json0) + Svg writers
  jvm/src/test/scala/   MUnit oracle-gated suites (JVM-only: they read files)
  corpus/               137 hand-written + real-world DOT probes
                        (plus *.images.json size sidecars for image tests)
  golden/               frozen viz-js outputs, one dir per corpus file × 8 formats
  golden-examples/      frozen goldens for the shipped viewer examples
  oracle/               capture.mjs / capture-examples.mjs / gen_font_metrics.py
```

The shared sources are platform-pure: no `scalajs-dom`, no JVM-only APIs.
SVG is emitted as a string; font metrics come from embedded tables, not a
canvas. That is what lets the identical code run under both Scala.js (the
viewer) and the JVM (the entire test suite).

## Running the tests

```bash
sbt --client graphvizJVM/test                              # full oracle suite
sbt --client "graphvizJVM/testOnly *CorpusByteExactSpec"   # the corpus gate
sbt --client "graphvizJVM/testOnly *ExamplesByteExactSpec" # shipped examples
```

To regenerate goldens (only after deliberately bumping the `@viz-js/viz`
oracle — re-pin the C reference worktree and diff-review first, PORT.md §6):

```bash
node graphviz/oracle/capture.mjs            # corpus goldens
node graphviz/oracle/capture-examples.mjs   # shipped-example goldens
```

## Reference material & licensing

- [PORT.md](../PORT.md) — the living conformance tracker: milestone log,
  per-feature matrix, every closed bug with its root cause. Read it before
  touching layout code.
- The behavioral reference is Graphviz **13.0.1** C source (EPL-2.0), studied
  from a version-pinned worktree. The font-metric tables in `metrics/` are
  generated directly from `textspan_lut.c` and carry EPL attribution; the
  layout code transcribes Graphviz's algorithms. Confirm license
  compatibility and attribution requirements before distributing this module
  independently of the project.
