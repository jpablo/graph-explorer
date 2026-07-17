# Graphviz `dot` → Scala Port — Plan & Conformance Tracker

> **STATUS: ✅ LAYOUT PIPELINE COMPLETE · ✅ SHAPE CATALOG COMPLETE · ✅ `dot`
> engine is pure-Scala (2026-07-12) · ✅ BYTE-EXACT 157/158
> (2026-07-16).** All milestones M0–M8 done. The viewer routes by layout
> engine: `dot`/unset → the pure-Scala port (the default and common case,
> byte-exact), and the **non-`dot` engines** (`neato`/`fdp`/`sfdp`/`twopi`/
> `circo`/`osage`/`patchwork`) → viz-js, which stays as a runtime dependency
> because those layout algorithms are **not ported**. Full exact-string
> gate vs `@viz-js/viz` 13.0.1 (dot_json + json0 + svg):
> **corpus 149/150 + shipped examples 8/8 = 157/158** — the SINGLE
> remaining diff anywhere is 03-subgraph-cluster, an intentional deferral
> (its golden is gv's own default-mode cluster corruption; the file is
> gated byte-exact against the 03b `newrank` oracle in ClusterSpec
> instead). The `ExamplesByteExactSpec` deferred list is EMPTY. Five
> user-reported diagrams (165–169) landed 2026-07-16 and closed FIVE
> long-deferred subsystems: xlabels (`addXLabels`/`placeLabels`), flat-edge
> mincross (`flat_reorder`/`flat_breakcycles`/`left2right`), per-component
> mincross, dot1 RECURSIVE cluster ranking (`collapse_cluster`/
> `interclust1` slack + the ReMincross phase), plus HTML-in-record fields,
> HTML side ports through the unified channel, `dir`/arrow-type flags, and
> the UTF-8-byte width rule. (History: 91/96 at the M8 cutover → every
> "characterised precision floor" since fell to a real transcription bug —
> clip direction, int division, `arrow_gen` EPSILON — see the §7 log.)
> **Shape catalog closed:** 61/62 builtin node
> shapes are ported and byte-exact (`ShapeCatalogSpec`, 36 single-node probes) —
> the full `poly_init` periphery engine, `round_corners` (containers + all 20
> SBOL bio shapes), cylinder/star generators, M-variants, egg, and generic
> `polygon`+attrs. Only `epsf` (needs an external PostScript file) is out of
> scope. See §4.
>
> Living document. Every milestone and every feature row must be backed by an
> oracle test before its status flips to ✅. Do not mark anything "done" from
> code review alone — done means "the differential harness agrees with
> `@viz-js/viz` within tolerance."

## 0. Open work

The current worklist (kept in sync with the session task tracker; the
fails-when-fixed guards in `CorpusByteExactSpec` / `ExamplesByteExactSpec`
enforce the deferral halves of it):

- **Gallery layout chases** (graphviz.org/gallery sweep, 2026-07-16): all
  23 dot-engine gallery examples triaged, **14 fully byte-exact** — 8
  already passing, 171–174 (go-package, kennedyanc, neural-network, unix)
  closed by the color/labelloc batch, 175-world by `size=` canvas zoom +
  subgraph-endpoint SEQ-order edges, 176-switch by multicolor parallel
  edges + invis nodes + the nested-subgraphs wrap — plus crazy, byte-exact
  in both json formats while viz-js itself crashes rendering its svg.
  177-genetic-programming closed same-day (clustered GD_nlist model +
  cluster-label-width truncation + graph-label-before-clusters).
  Remaining (genuine layout divergences, instrumented-gv work): git, lion_share,
  psg, pprof, profile, Linux_kernel_diagram, siblings, sdh (its
  `ratio=fill` without `size=` is a gv no-op — real divergence).
- Current gate (2026-07-16, after 165–177):
  **corpus 149/150 + examples 8/8 = 157/158 byte-exact** in all three
  formats (corpus rendered through the sidecar-aware `corpusGraph` path,
  examples through the public `renderFormats` facade).
- **03-subgraph-cluster** — the single diff, a permanent INTENTIONAL
  corpus deferral (its goldens are gv's own default-mode cluster
  corruption; gated byte-exact vs the 03b `newrank` oracle in
  `ClusterSpec`), fails-when-fixed guarded. The dot1 cluster-ranking port
  (169) made the exception EXPLICIT in code: a rank set spanning cluster
  boundaries keeps the correct global (newrank) semantics
  (`Rank.rankedImpl` dispatch).

## 1. Goal & locked decisions

Replace the `@viz-js/viz` (Graphviz-in-WASM) runtime dependency with a
**pure-Scala reimplementation** of the Graphviz `dot` pipeline, usable from
Scala.js **and** the JVM.

| Decision | Choice | Consequence |
|---|---|---|
| Engine scope | `dot` only | No neato/fdp/circo/twopi/osage/sfdp. |
| Fidelity bar | Topologically faithful, visually close | Same ranks + within-rank order; geometry within numeric tolerance. Not bit-exact. |
| Strategy | Reimplement from understanding, oracle-tested | Idiomatic Scala from GKNV'93 + **version-matched** Graphviz C as behavioral reference; validate against viz-js. NOT legal clean-room isolation; NOT line-for-line transliteration. |
| Packaging | New pure cross-compiled sbt module `graphviz` | No project-specific or platform deps in shared sources. |
| Output contract | **Match viz-js output strings, not internals** | `dot_json` / `json0` / `svg` strings must parse identically downstream. |

### Key reframe (why this is scoped, not infinite)

`@viz-js/viz` is Graphviz's C code compiled to WASM — there is no JS to
transliterate. But this project only ever calls:

- `renderFormats(dot, ["dot_json"])` — [Graphviz.scala:28](viewer/src/main/scala/org/jpablo/graphexplorer/viewer/backends/graphviz/Graphviz.scala#L28)
- `renderFormats(dot, ["svg", "json0"])` — [Graphviz.scala:45](viewer/src/main/scala/org/jpablo/graphexplorer/viewer/backends/graphviz/Graphviz.scala#L45)

So the *functional contract* is: **DOT text → (`dot_json`, `json0`, `svg`)
using the `dot` engine.** Everything else in Graphviz is out of scope.

## 2. The anti-"miss something" methodology

### 2.1 Oracle harness (built FIRST, in M0, before any layout code)

`@viz-js/viz` stays in `package.json` permanently as the **test oracle**
(not runtime). A Node script feeds the corpus through real viz-js and dumps
golden files for *every intermediate Graphviz format*, so each pipeline stage
is independently verifiable instead of only diffing final pixels:

| Format captured | Pins down | Gates milestone |
|---|---|---|
| `dot` (echoes computed `pos`/`rank`/`width`/`height`) | ranks, node sizes, final coords | M1–M4 |
| `plain` / `plain-ext` | line-based node/edge positions (easy numeric diff) | M2–M5 |
| `json0` / `dot_json` | structured positions, splines, bboxes | M4–M5, M7 |
| `xdot` | low-level draw ops | M7 |
| `svg` | final render | M7–M8 |

Diffing is **tolerance-aware** (coordinates never match bit-for-bit):
- Structural fields (ranks, node order, edge endpoints, attribute values): exact.
- Geometry (x/y, widths, spline control points): within ε (start ε≈2px abs / 2% rel; tighten per milestone).

### 2.2 Conformance matrix (Section 5) — the checklist

Exhaustive by construction: rows derived from Graphviz's own `rtest`
regression suite + the DOT language grammar + the attribute reference, not
from memory. Every row carries: status, backing golden test, known deviation.

### 2.3 Corpus

1. The project's real diagrams (highest-value, must-pass set).
2. Graphviz upstream `graphs/` + `rtest/` regression inputs.
3. Hand-written edge-case probes (one DOT feature per file).

### 2.4 Reference source (version-matched — CRITICAL)

Local Graphviz clone: `/Users/jpablo/GitHub/graphviz` (EPL 2.0). Used as the
**behavioral reference** for tie-breaking, iteration caps, and magic
constants the GKNV'93 paper omits. We reimplement in idiomatic Scala — we do
**not** copy/translate source files (keeps us clear of EPL file-level
copyleft; confirm license-compat + attribution before distribution).

**Version-alignment protocol (non-negotiable):** the reference source must be
the exact Graphviz version `@viz-js/viz` 3.14.0 bundles.
- ✅ **RESOLVED:** bundled Graphviz = **`13.0.1`** (measured via
  `Viz.graphvizVersion`). Clone `main` is `14.1.5` (one major ahead — do
  NOT study `main`). The clone has an exact `13.0.1` tag.
- Pin once: `git -C /Users/jpablo/GitHub/graphviz worktree add ../graphviz-1301 13.0.1`
  and study **only** `../graphviz-1301`. Re-pin only if the oracle is bumped.

Source → milestone map (paths relative to the version-matched worktree):

| Graphviz file | Milestone |
|---|---|
| `lib/common/textspan_lut.{c,h}`, `lib/common/shapes.c` | M1 (font metrics table — **transcribe data directly**; node sizing) |
| `lib/dotgen/acyclic.c`, `rank.c`, `lib/common/ns.c` | M2 (cycle break, rank, network simplex) |
| `lib/dotgen/mincross.c` | M3 (ordering) |
| `lib/dotgen/position.c` | M4 (x-coords) |
| `lib/dotgen/dotsplines.c`, `lib/common/splines.c`, `routespl.c` | M5 (splines) |
| `lib/dotgen/cluster.c`, `flat.c`, `sameport.c` | M6 (clusters/ports/flat edges) |

Plus the GKNV 1993 paper "A Technique for Drawing Directed Graphs" for the
algorithm skeleton and rationale.

### 2.5 Instrument-and-port recipe (REUSABLE — the proven method)

Reading source 3× couldn't find the M4x ω bug; one instrumented build
found it in ~30 min, and one falsified the M5 cheap-spline path with hard
numbers. **When a model detail is unclear, instrument the real Graphviz —
don't keep re-reading.**

```bash
brew install bison                                   # need ≥3.0 (Apple's is 2.3)
GV=/Users/jpablo/GitHub/graphviz-1301                 # pinned 13.0.1 == oracle
# add a getenv-guarded fprintf probe in the relevant fn (e.g. make_aux_edge,
# routesplines, Proutespline), then:
cd "$GV" && rm -rf _dbgbuild
env PATH="/opt/homebrew/opt/bison/bin:$PATH" cmake -S . -B _dbgbuild \
    -DCMAKE_BUILD_TYPE=Release -DBISON_EXECUTABLE=/opt/homebrew/opt/bison/bin/bison
env PATH="/opt/homebrew/opt/bison/bin:$PATH" cmake --build _dbgbuild \
    --target dot gvplugin_core gvplugin_dot_layout -j4
mkdir -p _dbgbuild/_plug && find _dbgbuild -name 'libgvplugin_*.dylib' -exec cp {} _dbgbuild/_plug/ \;
DOT=$(pwd)/_dbgbuild/cmd/dot/dot
LIBS=$(find _dbgbuild -name '*.dylib' -exec dirname {} \; | sort -u | tr '\n' ':')
PLUG=$(pwd)/_dbgbuild/_plug
env DYLD_LIBRARY_PATH="$LIBS" GVBINDIR="$PLUG" "$DOT" -c        # writes config6
env PROBE=1 DYLD_LIBRARY_PATH="$LIBS" GVBINDIR="$PLUG" "$DOT" -Tplain corpus/NN.dot 2>&1 >/dev/null
```

Rules: probe must be `getenv`-guarded; **revert the source and `rm -rf
_dbgbuild` when done — the worktree must end pristine** (`git -C $GV
checkout -- <file>`). Derive the model from the dump, then **verify it
across ≥3 corpus graphs** (never oracle-fit one data point).

## 3. Module design

```
graphviz/                      crossProject(JS, JVM), CrossType.Pure
  src/main/scala/.../graphviz/
    dotlang/      DOT parser + AST          (fastparse; reuse shared/.../formats/dot/ast where possible)
    model/        layout graph model        (mutable internal repr; immutable façade)
    layout/       acyclic → rank → mincross → position → splines
    metrics/      pure font-metric tables   (builtin Times/Helvetica/Courier AFM — identical JVM & JS)
    output/       dotJson / json0 / svg string writers
    Graphviz.scala  renderFormats(dot, formats): MultipleRenderResult-shaped result
  src/test/scala/...           MUnit + differential harness
  reference/                   pinned Graphviz C + paper (read-only)
  corpus/                      *.dot inputs
  golden/                      captured viz-js outputs (committed)
```

Design rules:
- **No `scalajs-dom`, no JVM-only APIs in shared sources.** SVG is emitted as
  a **string**; the viewer keeps using its existing `parseSVG` to make a DOM
  element. Font metrics use embedded AFM tables (no canvas/Graphics2D).
- Public API mirrors the slice of `Viz` we use so M8 integration is a
  one-line call-site swap in [Graphviz.scala](viewer/src/main/scala/org/jpablo/graphexplorer/viewer/backends/graphviz/Graphviz.scala)
  behind a feature flag (viz-js stays as fallback + oracle).
- sbt: `lazy val graphviz = crossProject(...).crossType(Pure)`; `viewer
  .dependsOn(graphviz.js)`; `root.aggregate(graphviz.js, graphviz.jvm)`.

## 4. Milestones

| ID | Milestone | Exit criterion (oracle-gated) | Status |
|----|-----------|-------------------------------|--------|
| M0 | Module scaffold + oracle harness + corpus + golden capture + DOT parser front-end | Harness green: parser round-trips corpus; golden files generated for all formats | ✅ Done — 10/10 tests; goldens frozen @ gv 13.0.1 |
| M1 | Attribute resolution + node-size / text-metrics (transcribe `textspan_lut`) | Node `width`/`height` within ε of echoed `dot` for corpus | ✅ Done — 21/21; sizes match golden < 0.001 in; records deferred to M6 |
| M2 | Cycle breaking + rank assignment (network simplex) | Ranks **exactly** match `json`/`dot` for corpus | ✅ Done — 29/29; rank partition exact for 01/02/04/05/06; 03 (clusters) → M6 |
| M3 | Crossing minimization / within-rank ordering | Within-rank node order matches oracle (acceptable documented deviations) | ✅ Done — 43/43; crossings == oracle (0) incl. `07-cross` 3→0; order matches |
| M4 | Coordinate assignment — **Y** (`set_ycoords`) | Rank-axis Y within tight ε (`plain`) | ✅ Done — 48/48; exact for label-free TB (01/06/07); edge-label rank-doubling deferred |
| M4x | Coordinate assignment — **X** = network simplex on aux graph | Node x within ε (`plain`/`json0`) | ✅ Done — 59/59; X matches `plain` golden for 01/06/07. Closed by instrumenting real gv 13.0.1 (built from pinned source) to dump the actual aux graph |
| M5 | Edge spline routing | Edge geometry within ε; same endpoints/clipping | ✅ Done — 66/66; full box-fit ported (box channel → `Pshortestpath` funnel → `Proutespline` → `clip_and_install`/`bezier_clip`); raw spline byte-exact vs instrumented gv 13.0.1; Hausdorff ≤0.024 in (01 incl. curved `a→c`, 07); 06 now direct-match (X-mirror closed 2026-07-11 via the `build_ranks` tail transpose). **06/82's residual is a CONFIRMED FP-floor `bezier_clip` difference (2026-07-12, §7): raw spline byte-identical to gv, clip algorithm+ellipse+semi-axes all identical, node-relative clip made no difference ⇒ a <0.05pt floating-point-precision floor, not closable by matching the algorithm; visually identical.** |
| M6 | Long tail: clusters, ports, compass, `rankdir`, record & HTML-like labels | Per-feature rows in §5 all ✅ | ✅ **Complete — corpus 91/96 byte-exact (2026-07-12).** Done: records (layout+svg), ports/compass, self-loops/parallel edges, edge-label rank-doubling+Y+lp+spline, svg graph/edge titles, bbox precision, arrow miter + true `arrow_length` + vee/crow, **02 rankdir=LR fully byte-exact**, **03 cluster geometry** (vs newrank oracle — §5.2), full `write_attrs` (dot_json/json0), svg styling + emit-order interleave, box-family + `rounded` + convex polygons + **`point`**, **HTML-like labels** (tables/ports/img/gradient/sub-sup/hr-vr), **node images** (all SCALE modes), **05** (tooltip anchor + multi-line labels + seed-truncation NS fix), **`rank=same/min/max/source/sink`** (source byte-exact; min/max ranking exact, X-order deferred), **`nodesep`/`ranksep`/`ranksep=equally`**, **rankdir=RL/BT** (fully byte-exact — the `ccwrotatepf` custom-formula fix), **flat (same-rank) edges** (adjacent simple/undirected/labeled **and non-adjacent** byte-exact — the non-adjacent case arches over the intervening nodes: `make_flat_edge` box channel + the up-and-over geodesic via a winding-consistent `funnelGeneral`, and the graph height grown by `update_bb_bz`'s tight-bezier bbox — 2026-07-12). ⬜ genuinely-remaining (each no-corpus or a deep tie-break, tracked §5/§7): cluster-aware mincross (**byte-exact 2026-07-12** — 94-cluster-contig (contiguity) + 95-cluster-chains (multi-rank clusters w/ free crossing edges) + 96-nested-cluster (nested), via `class2` skeleton collapse + recurse + expand). ⬜ residual floor only (fully characterised, §5/§7 — a precision-and-tie-break floor, not open work): the 06/82 FP-floor `bezier_clip` difference (<0.05pt, visually identical), the 81/84 rank=min/sink within-rank mincross mirror (Order/segOwner decouple), and the no-corpus items `\E`/`\T`/`\H` edge-label escapes, polygon peripheries (doubleoctagon), non-default `penwidth` outlines |
| M7 | Output writers: `dot_json` → `json0` → `svg` | Emitted strings parse to identical `SimpleGraph`; SVG visually-close | ✅ Done — 81/81; `dot_json`/`json0`/`svg` writers + `renderFormats` facade. Structural-exact + ε geometry vs goldens (strict, no mirror) for label-free TB (01/06/07); records/clusters/edge-labels are their own tracked deferrals |
| M8 | Integration behind flag; differential test on real project corpus; viz-js demoted to oracle | Project diagrams render via Scala backend; harness CI green | ✅ **Cutover landed 2026-07-12** — the Scala backend is now the **default** engine (`EngineMode.ScalaFirst`), with viz-js retained as an automatic **fallback** (hard-failure → viz-js, logged) + oracle. `gx.graphvizEngine` = `scala` (strict, no fallback) / `vizjs` (force old) / unset (ScalaFirst). `DifferentialSpec` promoted: the public `Graphviz.renderFormats` facade is **byte-exact vs the golden across every non-residual corpus file** + graceful-degradation/failure tests. Downstream (`read[SimpleGraph]`/`getEdgePos`/`parseSVG`) unchanged. viz-js demoted to safety-net + golden-capture. The former valid-but-wrong risk (flat edges, custom `ranksep`/`nodesep`, `rank=min/max`, RL/BT, clusters) is now closed — those features are byte-exact; only the fully-characterised residual floor (§5/§7) remains |

### ✅ LAYOUT PIPELINE COMPLETE · ✅ SHAPE CATALOG COMPLETE (2026-07-12)

**All milestones M0–M8 are done; the pure-Scala port is the engine for the `dot`
layout, dispatched by the viewer `Graphviz` class on the graph `layout`
attribute (`usesDotEngine`): `dot`/unset → Scala, everything else → viz-js.**
The port implements ONLY the `dot` engine; the other Graphviz layout engines
(`neato`/`fdp`/`sfdp`/`twopi`/`circo`/`osage`/`patchwork` — force-directed,
radial, circular, treemap) are **not ported**, so viz-js remains a runtime
dependency for them (the shipped `examples/neato/*` + `layout=twopi` examples
exercise this path). The `dot` path has no viz-js fallback — a hard failure
there is a port bug we want surfaced, not masked. The full `dot` LAYOUT
algorithm (rank →
mincross → x/y coords → splines → clusters/nesting/rankdir/records/HTML/images →
all three writers) is complete and **91/96 corpus byte-exact**.

**✅ The shape CATALOG is complete.** 61/62 gv builtin node shapes are ported and
byte-exact (`ShapeCatalogSpec` — 36 single-node `1XX-*` probes × 3 writers, plus
re-joined to `CorpusByteExactSpec`/`DifferentialSpec` end-to-end). Landed in five
faithful transcriptions of `lib/common/shapes.c`:
- **A — polygon engine** (`Polygon.init`): full `poly_init` convex branch —
  concentric peripheries (bisector-offset loop), the `sides≤2 + distortion → 120`
  promotion (egg), and generic `polygon` + user `sides/skew/distortion/
  orientation/regular/peripheries`. Closes doubleoctagon/tripleoctagon/egg/polygon.
- **B+E — `round_corners`** (`RoundCorners.scala`, a 1:1 transcription of
  `round_corners` + `alloc_interpolation_points`): the container shapes
  (note/tab/folder/box3d/component/underline) **and all 20 SBOL biology shapes**
  (promoter…lpromoter). 26 shapes from one function.
- **C — custom generators** (`Polygon.Gen`): cylinder (`cylinder_size`/`_vertices`
  + `cylinder_draw` two-bezier render) and star (`star_size`/`star_vertices`).
- **D — M-variants**: Mdiamond/Msquare via `diagonals_draw`, Mcircle via
  `Mcircle_hack` (two chords).

The one exception is **`epsf`** (embeds an external PostScript file — no sandbox
path and no realistic web use). With the catalog closed, viz-js no longer masks
any builtin shape on the `ScalaFirst` path; dropping it as the render fallback is
now a viable follow-up (would also want the minor no-corpus items below covered).

Corpus **91/96 byte-exact** through the writers and `renderFormats`. The 5
non-byte-exact corpus files are a **fully-characterised precision-and-tie-break
floor, not open work**:

| File(s) | Residual | Status |
|---------|----------|--------|
| 06, 82 | ~~`bezier_clip` <0.05pt "FP floor"~~ | **CLOSED 2026-07-13** — the "floor" was the reversed-edge clip DIRECTION: gv clips in the working parameterization and `swap_spline`s at install; the bisection is not direction-symmetric. Byte-exact. |
| 81, 84 | ~~rank=min/sink mirror~~ | **CLOSED 2026-07-13** — 84 by the cgraph edge-order fix; 81's residual was the clip-direction bug above. Byte-exact. |
| 03 | cluster layout | **Intentional divergence** — its golden is gv's own default-mode cluster corruption; we lay it out correctly and gate vs the `newrank` oracle (03b). |

The layout ALGORITHM has no unported feature, and the shape catalog is closed
(61/62, §4). The remaining gaps are minor no-corpus items — `epsf` (external
PostScript), `\E`/`\T`/`\H` edge-label escapes, non-default `penwidth` outlines
(the `outline` periphery uses `DEFAULT_NODEPENWIDTH`) — all corner-case
coverage-expansion, none affecting the core pipeline or the common corpus, all
reached by the viz-js fallback in the meantime.

## 5. Conformance matrix

Status legend: ⬜ not started · 🟡 partial · ✅ oracle-verified · ⛔ explicit non-goal · ⚠️ known deviation

### 5.1 DOT language (parser)
Parser ✅ = structurally asserted in `DotParserSpec` **and** the same corpus is
accepted by the viz-js oracle (there is no AST oracle; semantic resolution is
later milestones). Backing test: `graphviz/jvm/.../DotParserSpec.scala`.

| Feature | Status | Test | Notes |
|---|---|---|---|
| `graph` / `digraph` / `strict` | ✅ | DotParserSpec | directed/strict flags + graph id |
| Node / edge / subgraph statements | ✅ | DotParserSpec | |
| `node`/`edge`/`graph` default attr statements | ✅ | AttrResolveSpec | scope resolution + inheritance + node/edge merge |
| Attribute lists, `=`, multi-`[]` | ✅ | DotParserSpec | flattened across `[..][..]` |
| Subgraphs & `cluster_*` | ✅ (newrank semantics) | DotParserSpec + ClusterSpec + CorpusByteExactSpec | parsed; resolved into `RGraph.subgraphs` tree (2026-05-29) with cluster-ness/rank/membership/anon-`%N`. **Cluster *geometry* CLOSED 2026-07-11** (see §7): `Cluster`/`Coord.yInfo`/`XCoord pos_clusters`/`Spline cl_bound` + writers — `03b-subgraph-cluster-newrank` byte-exact in all 3 formats; 03-verbatim gated to the same drawing (its own goldens are gv's default-mode corruption — don't-port-the-bug). Future (no corpus): cluster fontsize/labelloc/style, LR cluster labels, cluster-aware mincross, default-mode recursive-ranking parity |
| Ports `node:port` + compass `:n/:ne/...` | ✅ | DotParserSpec | incl. single-`:` compass disambiguation |
| Edge chains `a -> b -> c` | ✅ | DotParserSpec | undirected `--` chains too |
| Quoted / HTML `<...>` / concatenated `+` strings | ✅ | DotParserSpec | `\"`/line-continuation resolved; HTML kept verbatim |
| Comments `//`, `/* */`, `#` lines | ✅ | DotParserSpec | custom fastparse Whitespace |
| Escape sequences in labels (`\n \l \r \N \G \E`) | 🟡 | NodeSize | `\n \l \r \N \G \\` interpreted for sizing; `\E \T \H` (edge ctx) = later |

### 5.2 Layout pipeline
| Stage | Status | Test | Notes |
|---|---|---|---|
| Acyclic (cycle breaking) | ✅ | RankSpec | DFS back-edge reversal, roots in declaration order (matches `acyclic.c`) |
| Rank assignment (network simplex) | ✅ | RankSpec + NetworkSimplexSpec | true NS (`init_rank` + tight feasible tree + cut-value pivots + normalize + TB balance). Oracle-verified end-to-end (corpus ranks unchanged) + unit tests for slack/non-unique/weighted/disconnected. LR balance (mode 2) is a stub until M4x X-wiring |
| `rank=same/min/max/source/sink` | ✅ (`same`; min/max extreme-pin ⬜) | RankSameSpec | **`rank=same` layout CLOSED 2026-05-29 — byte-exact.** `Rank.rankConstraintLeader` ports `collapse_sets` (class1.c): union-find merges each rank-constraint set to one representative; the NS solve runs on the collapsed leaders, then leader ranks expand back to every member. Acyclic + the working edges Order consumes stay on the *original* nodes (only the NS solve collapses) ⇒ additive: identity without rank constraints, unconstrained corpus unchanged. `11-ranksame` probe (`top→a; top→mid→b; {rank=same;a;b}` — forces `a` from rank 1 to rank 2): ranks, node positions, **`dot_json`/`json0`/`svg` all byte-exact**. Also fixed a latent subgraph-`label` emission bug (rank-only `%N` omits `label` unless `label` is a declared graph attr — `write_attrs`; 03's cluster-declared label still reaches its `%7`). ⬜ remaining: the `min`/`source`⇒global-min and `max`/`sink`⇒global-max *extreme* pin (only the same-rank merge is done; no corpus for min/max positioning) |
| `minlen`, `weight`, `ranksep`, `nodesep` | 🟡 | RankSpec | `minlen` honoured; `weight` n/a to longest-path; `ranksep`/`nodesep` are M4 (coords) |
| Virtual nodes for long edges | ✅ | OrderSpec | `class2` unit-span chains; verified via corpus long edges (01/02/05/06) staying 0-crossing |
| Mincross (weighted median + transpose) | 🟡 | OrderSpec | BFS init + `medians`/`reorder` + `transpose`, MaxIter loop. Gate = crossing-count parity (0 across corpus incl. `07` 3→0). pass-0/pass-1 init *alternation*, flat edges, ports, clusters deferred |
| Y-coord assignment (`set_ycoords`) | ✅ | CoordSpec | bottom rank = halfHt; step = halfHt+halfHt+ranksep(36pt). Exact label-free TB |
| X-coord assignment | ✅ | XCoordSpec | `XCoord`: aux graph (`make_LR_constraints` sep = rw+lw+nodesep; `make_edge_pairs` slack nodes; virtual half = 1+nodesep/2) → NS(balance=2) → bbox shift. Matches `plain` for 01/06/07 (strict, no mirror — 06's X-mirror closed by the `build_ranks` tail transpose, mincross.c:1349) |
| Edge-pair ω weight (`virtual_weight`) | ✅ | XCoordSpec | NOT ω=1/2/8-by-virtualness. `t = table[class(tail)][class(head)]`, class = ORDINARY(0)/SINGLETON(1, real & ≤1 incident edge)/VIRTUAL(2); table `[[1,1,1],[1,2,2],[1,2,4]]`. Verified vs instrumented gv 13.0.1 aux dump (07 singletons→C_SS=2; 01/06 branch→C_EE=1) |
| LR_balance (NS balance=2) | ✅ | NetworkSimplexSpec/XCoordSpec | `ns.c` LR_balance ported. With correct ω, a/c become a flat optimum in [b,v]; LR_balance centres them to match Graphviz |
| ~~Virtual-node X separation too wide~~ | **RESOLVED 2026-05-16** | XCoordSpec | **Misdiagnosis corrected.** Separation `minlen=55` was right all along (instrumented gv confirms b→v minlen=55). Real bug = wrong ω model (above). Found by building+instrumenting gv 13.0.1, not by re-reading source |
| Edge-label rank doubling (`ED_minlen*=2`) | ✅ | RankSpec + CoordSpec | **Closed (Y).** `Rank.hasEdgeLabel` ⇒ `acyclic` `minlen*=2` (`edgelabel_ranks`); `Coord` ranksep `(36+1)/2=18`; `make_chain` `label_vnode` half-height = `nLines·fontsize·LINESPACING/2` (`NodeSize.labelHeightPt`, reusing the M1 `\n\l\r` line split — single-line HTML label included) seated at mid rank `(rank t+rank h)/2`; root graph-label space `do_graph_label` = label box + YPAD `2·GAP` (GAP=4) at the labelloc side. CoordSpec 05 deferred-probe **promoted** → 05 in the strict Y list, matches golden ≤0.005 in. (HTML label *width*/table layout stays a separate M6 row — doesn't affect rank-axis Y.) 02 still needs LR (separate row) |
| `rankdir = TB/LR/BT/RL` | 🟡 (blocker 1 closed; blocker 2 quantified) | RankDirSpec | **Blocker (1) RESOLVED 2026-05-17, blocker (2) sharply quantified — honest negative.** (1) `gv_nodesize(n, flip)` ported as `NodeSize.layoutSize` (LR/RL ⇒ w/h swapped: layout `w=trueHeight`, `h=trueWidth`) and threaded through the canonical layout (Coord/XCoord/Spline). `nodeSize` stays the true-size `dot`-oracle contract — `layoutSize == nodeSize` for TB ⇒ **01/06/07/04/05 byte-identical** (106/106; RankDirSpec locks it). The transform (`translate_drawing`/`map_point`, Offset=`(−cbb.UR.y, cbb.LL.x)`; LR ⇒ `final=(cbb.UR.y−y, x−cbb.LL.x)` over the canonical node-extent bbox) is **verified byte-exact vs instrumented gv 13.0.1** — applied to our canonical 02 it gives the **rank axis (final X) within ~3 pt** of the golden (gated as progress). (2) **half-closed 2026-05-17**: edge `weight` threaded into the XCoord ω (faithful `make_edge_pairs` `ED_weight`; default-1 ⇒ 01/06/07 byte-identical, XCoordSpec green) — the `weight=2` `start→middle` edge now **aligns** `start`/`middle` on the canonical order axis (was 45 vs 18, now 18 vs 18, matching gv's `start≡middle`). Still REMAINS: the **edge-label vnode's X under flip** — the `go` label injects an order-axis virtual node our canonical X/mincross doesn't place like gv ⇒ order axis still ~25–34 pt off (not visually close). Genuine remaining sub-part. RankDirSpec carries a **self-flagging deferred-probe** (order-axis still deviates → fails-when-fixed). No fake gate; 02's `go` rank-doubling is ✅. **RECON 2026-07-06 — un-blocked (no gv instrumentation needed):** oracle probes (`json0` `lp` across label widths) show `lp.x = edgeX + labelWidth/2` uniformly ⇒ the edge-label vnode is **asymmetric** — the edge routes through its *left* reference edge (stays straight at `edgeX`), the label extends right by `labelWidth` (`ND_rw = labelWidth`, reserving that order-axis space). The port gives every `LayoutNode.Virtual` a symmetric 10pt. **This is the SAME vnode as blocker (2)** ⇒ one fix (tag the label vnode in `Order` + asymmetric `rw=labelWidth` in `XCoord.make_LR_constraints` + route the spline through the reference x + emit `lp`/label text) closes **both edge labels AND LR order-axis**. Tractable focused session; geometry fully derived. **UPDATE 2026-07-11 — instrumented gv 13.0.1 (§2.5 recipe); order axis ~30pt → ≤6pt, 7/8 canonical nodes byte-exact.** The 2026-07-06 recon was WRONG on two counts, corrected against the real gv canonical dump: (a) the label vnode `rw` is `dimen.y` (label *height*) for a flipped graph, not `labelWidth` (`class2.c label_vnode` swaps ht/rw when `GD_flip`); `lw = nodesep` (not 0 — the 0 was a post-spline artifact); (b) the LR order-axis mirror is NOT the label vnode — it's `build_ranks` **reversing every rank's BFS order when `GD_flip`** (`mincross.c:1334`). Both ported (`Order` flip-reverse; `Coord.labelVnodeWidths`/rankY swap on `Rank.flip`) — TB untouched, so the whole corpus stays byte-exact. gv's true X-solve (`start=46 middle=46 end=12 chains=0 __v1_3=35`, dumped post-`set_xcoords`) now matches mine on **7 of 8 nodes exactly**. ⬜ last residual: `end` (~5pt) — its position comes from `LR_balance` (`ns.c:768`) iterating `Tree_edge` in tight-tree DFS order and doing δ/2 reranks; my Set-based tree iterates by index, so the sequential reranks pick a different entering-edge slack (35 vs gv's 25). Reverse-index closes 02 but breaks 11-ranksame (confirmed) ⇒ needs gv's actual feasible-tree construction order, a deeper NS change. Spline routing + `lp` emission under LR also still pending (separate) |
| Cluster structural model + `dot_json` | ✅ (`dot_json` byte-exact; `json0`-geom/svg deferred) | ClusterSpec + DifferentialSpec | **CLOSED 2026-05-29 — the two "unknowns" cracked by oracle-probing, no instrumented-gv build.** Per §1 (match viz-js *strings*) 03 is geometry-free (viz-js doesn't lay clusters out ⇒ `bb`/`pos` degenerate). Landed: additive `RGraph.subgraphs` tree (`AttrResolver.walk` now keeps what it discarded) → `Output` emits `_subgraph_cnt`, subgraph objects **first** (preorder `label_subgs` gvid 0..cnt-1), real-node `_gvid` offset by `sgCnt`, top-level edges array **AGSEQ-sorted** (gv `qsort`) while `_gvid` stays node-traversal. **03 `dot_json` byte-exact.** The recon's "genuine unknown" (the node→subgraph ownership partition) did **not** need an instrumented `agfstnode(sg)` dump — it's directly observable in ordinary `-Tjson` output (`write_nodes(sg)` = raw `agfstnode(sg)`), so a handful of **probe DOT files through the version-matched oracle (viz-js == gv 13.0.1)** nailed it: **(rule 1 — ownership, SUPERSEDED 2026-07-11)** the observed "eviction" of rank-constrained nodes from cluster node lists was the first stage of gv's *default-ranking corruption* on cross-cluster ranksets (12.2.1 literally warns "deleted from cluster" then errors) — under the correct `newrank` semantics no eviction happens (03b golden keeps `a0` in `cluster_0`); membership is purely additive and the eviction code was removed. **(rule 2 — anon `%N`)** id = `counter*2+1` over the unnamed root + every (keyless) edge + anon subgraphs in parse order (id.c `idmap`) ⇒ the 3 cluster edges tick to 3 before `{rank=same}` ⇒ `%7`. **(rule 3 — root attrs)** gv `write_attrs` prints every declared graph-attr with the root's value, skipping empty *except* `label` ⇒ 03 root gets `label:""` (declared by a cluster) but not `rank` (empty at root). **json0 cluster geometry + svg cluster boxes CLOSED 2026-07-11** (cluster geometry subsystem, §7 — byte-exact vs the 03b newrank goldens). Nested-cluster membership union + non-`cluster`-prefixed subgraph edge cases have no corpus. |
| Spline routing — straight/clipped | ✅ | SplineSpec | All edges route via the real `routesplines` pipeline (no straight-leg special-case). `clip_and_install`+`bezier_clip` ported faithfully (ellipse `insidefn` semi-axes = (sizePt+penwidth)/2). **Arrow clip uses the TRUE `arrow_length_normal`** (≈11.53, not nominal 10 — `Arrow.lengthNormal`, 2026-05-17): the directed corpus spline is now **byte-exact** vs `plain` (07/01/04 Hausdorff ≤0.00004 in, was the documented sub-2px residual) |
| Spline routing — box-fit (bowed curves) | ✅ | SplineSpec | **Closed 2026-05-17 by instrument-and-port (§2.5); residual fully eliminated by the arrow-length port.** Ported box channel (`completeregularpath`/`maximal_bbox`/`rank_box`/`adjustregularpath`/`checkpath`; MINW=16, FUDGE=4, Splinesep=nodesep/4), channel polygon, `Pshortestpath` (taut funnel over box portals), `Proutespline` (recursive least-squares cubic fit + `solve3`). Verified vs instrumented gv 13.0.1: box channel, shortest path AND raw spline reproduce the probe **byte-for-byte** for 01 `a→c`. 01 `a→c` 0.25 → 0.024 → **0.00002 in** (the true `arrow_length` closed the last endpoint residual). Earlier Catmull-Rom cheap-approx (measured 0.25/0.54 in) stays a recorded dead end |
| Self-loops, parallel/multi-edges | ✅ | SplineSpec + OutputSpec + SvgSpec | **Parallel/multi-edge de-merge** (2026-05-17): `Spline` re-keyed `(tail,head)`→**g.edges index**; every consumer correlates by index. **Self-loops** (2026-05-17): added the `08-selfloop` corpus probe (01–07 goldens byte-identical on re-capture); ported `makeSelfEdge`→`selfRight` (no-port: 7-pt 2-cubic bowing right by `rw+(i+1)·nodesep`, `sizey` per the dotsplines.c rank-position rule) + the `make_LR_constraints` `selfRightSpace` (+`SELF_EDGE_SIZE`=18 to `ND_rw`, seen by `dot_compute_bb`/`Output.bbox`). Self-edges don't rank (excluded from acyclic/order); routed separately. **08 byte-exact** vs golden: spline (a→a + a→b), json0/dot_json `bb` (incl. the +18), svg. Additive: portless/non-self graphs byte-identical (01–07 green). Ports-on-self-edges (`selfTop/Left/Bottom`) = documented no-corpus deferral |
| Port/compass-anchored edge endpoints | ✅ | SplineSpec + OutputSpec + RecordSpec | **Closed 2026-05-17 by instrument-and-port (§2.5).** Beyond the model/emission/anchor layer: ported the `beginpath`/`endpath` REGULAREDGE record-port branch — `RecordLabel.pos_reclbl` field `sides`; `compassPort` + `resolvePort`/`closestSide` (a `_`/no-compass port is dynamically resolved to the field side nearest the other node); the side box + ±1 router nudge; `record_path` pbox fallback (`b:n`, b has no TOP ⇒ side 0 ⇒ field box clipped to node height); constrained-tangent `Proutespline` (`evs`); `clip=false` ⇒ skip the ellipse shape-clip. **Also** ported `make_edge_pairs`'s port x-offset (`m0=(int)(headport.p.x−tailport.p.x)`, slack minlens `m0+1`/`m1+1`) into `XCoord` — struct2.x 65.99→**63.99** == golden (the f0-left/f2-right weighted-median balance). Verified: instrumented gv-13.0.1 box channel/start/end/raw spline reproduced byte-for-byte for **both** 04 edges; final splines match the `plain`/`json0` golden — Hausdorff ≤0.04 in (SplineSpec, mirror **disallowed**: 04 is TB & unmirrored, asserted), json0 `e,EX,EY` exact (≤0.5 pt) + spline ≤2 pt (OutputSpec, the `getEdgePos` contract). The ~0.5 pt `f2:s` begin-nudge is the documented spline-pipeline ±1 (now absorbed end-to-end). Reference worktree reverted pristine |

### 5.3 Node shapes & labels
| Feature | Status | Test | Notes |
|---|---|---|---|
| Poly shapes: box, ellipse, circle, square, plaintext, none, plain | 🟡 | NodeSizeSpec + StyleSvgSpec | sized & oracle-verified; **box/rect/rectangle/square render as a rectangle `<polygon>` + `poly_inside` box edge-clipping — `10-box` byte-exact; `style=rounded` ⇒ `<path>` with RBCONST=12 corner arcs — `13-rounded` byte-exact (2026-05-29)**; ellipse/circle = `<ellipse>`. Also fixed: svg `id="edgeN"` = AGSEQ index (was latent). Deferred: diamond/polygon/point; rounded corner-radius clamp for tiny boxes |
| `record` / `Mrecord` (layout/sizing) | ✅ | NodeSizeSpec + RecordSpec | **Closed.** `RecordLabel` ports `parse_reclbl` (grammar `f`, `f\|f`, `{…}` orientation toggle, `<port>`, `\`-escapes/hard-space) + `size_reclbl` (leaf = text dimen + PAD `XPAD 4·GAP`/`YPAD 2·GAP`; LR ⇒ Σx/maxy else Σy/maxx; per-line height = exact `fontsize·LINESPACING`, same finding as the 05 vnode) + `resize_reclbl` (min-size even-int split) + `pos_reclbl` + `record_init` (`+1pt` height kluge). `NodeSize` sizes record/Mrecord (no longer `None`). Verified **exact** vs the 04 `dot` golden: struct1 `1.833×0.51389`, struct2 `0.75×0.70278`; node-local field boxes + node centre == golden absolute `rects` to ≤0.05 pt. Record svg field-line *drawing* ✅ (2026-05-17, see §5.4 `svg`); field-port edge endpoints ✅ (§5.2 ports row). HTML-in-record + exotic escape/UTF-8/control = no-corpus deferrals |
| HTML-like labels (table layout) | ⬜ | | M6 |
| `width`/`height`/`fixedsize`/`margin` | ✅ | NodeSizeSpec | fixedsize true/shape, margin `x[,y]`, min-size floor, regular |
| Font metrics: Times/Helvetica/Courier | ✅ | NodeSizeSpec | `textspan_lut` transcribed (gen_font_metrics.py); Times oracle-verified |
| Non-builtin font fallback | 🟡 | FontMetrics | falls back to Times (matches `get_metrics_for_font_family`); not oracle-diffed |

### 5.4 Output writers
| Format | Status | Test | Notes |
|---|---|---|---|
| `dot_json` | ✅ | OutputSpec + AttrEmitSpec + ClusterSpec | `Output.dotJson`: hand-rolled (no serialization dep). name/`%1`/directed/strict/`_subgraph_cnt`/space-`bb`/subgraph objects (§5.2)/node+edge objects/edges. **Full `write_attrs` (2026-05-29):** every object emits its resolved attributes **alphabetically**, skipping empty values *except* `label` (`json.c` rule) — graph attrs (incl. subgraph-declared surfacing at root, e.g. cluster `label:""`), node attrs (`shape`/`style`/`fillcolor`/`tooltip`/…), edge attrs (`color`/`arrowhead`/`weight`/`style`, `label:""` once any edge is labelled; `tailport`/`headport` are just edge attrs). `stoj` `/`→`\/` escape. `_gvid` offset by `_subgraph_cnt`; edge array AGSEQ-sorted. **byte-exact** vs golden 01/06/07 **and 04** (records now emit `shape`); **02/05 objects+edges byte-exact**, their `bb` deferred (02 LR-layout, 05 graph-label bbox space). bb = **integer** box (floor/ceil of `dot_compute_bb`). |
| `json0` | ✅ | OutputSpec + AttrEmitSpec | `Output.json0`: dot_json attrs **merged alphabetically** with the layout keys (node `pos`/`width`/`height`, edge `pos` spline `e,EX,EY `) into one `write_attrs` stream — so the viewer's render path (`renderFormats(dot,["svg","json0"])`) now receives `fillcolor`/`shape`/`style`/… (previously dropped ⇒ unstyled). comma-`bb` = exact float node-extent (`%.5g`). **byte-exact** vs golden 01/06/07/04; geometry ±ε, **strict, no mirror** (06's X-mirror closed 2026-07-11, cf. XCoordSpec) |
| `svg` | ✅ | SvgSpec | `Output`/`Svg.svg`: header/`<svg>`/`viewBox`/flipped-y `translate`/background bit-exact; node `<ellipse>`+centered `<text>` (baseline y from `emit_label`+`yoffset_centerline`, source-derived not fitted); edge `<path d>` from the installed spline + normal-arrowhead `<polygon>` (full `arrow_type_normal0` + `miter_shape` ported 2026-05-17: `delta_tip` miter incl. the SVG `stroke-miterlimit=4` bevel fallback — **byte-identical** to the golden for non-virtual edges; was the long-deferred sub-2px residual). **Record nodes** (2026-05-17): ports `record_gencode`/`gen_fields` — outer box `<polygon>` + inter-field separator `<polyline>`s (LR table ⇒ vertical at child llx; TB ⇒ horizontal at child ury) + per-leaf centred field `<text>`; **byte-identical** to the 04 golden's per-node `<g>` blocks (exact, not ε — fully determined by the ✅ RecordLabel layout). `gvprintdouble` (`%.2f` trimmed). **Graph/edge titles** (2026-05-17): named graph ⇒ `<!-- Title: NAME -->` + graph `<title>`, anon ⇒ neither (`g.name`); edge `<title>` = `\E` (labels.c) = `tail[:port]op head[:port]` where port = `chkPort` `.name` (after first `:`, ⇒ `f2:s`→`s`), the edge *comment* stays portless (emit.c) — gated vs 04 + the corpus (closed the latent 06/07 untested gap). **Bbox precision** (2026-05-17): `Output.bbox` ports `position.c dot_compute_bb` — node-extent only (NORMAL nodes ± lw/rw + rank ht), **no spline, no floor/ceil**; svg `<svg>`/viewBox = ceil'd int canvas, `translate`/bg = the exact float. 04 svg header/transform/background now **byte-exact** vs golden (`translate(4 127.6)`, bg `135.98`). Well-formed + visually-close ε vs golden 01/06/07/04, strict (no mirror). **Arrow miter + true length ported** (2026-05-17): `Arrow` (shared by Svg + Spline) — arrowhead polygons **byte-identical**, and clipping by the real `arrow_length_normal` (≈11.53) made the directed corpus splines byte-exact, so directed `<path d>` + `<polygon>` now match the golden to float-ε. 06 (undirected ⇒ no arrow clip) now matches directly too — its former ≈0.013 in X-mirror residual was closed 2026-07-11 by the `build_ranks` tail transpose. **Styling + emit order** (2026-05-29, StyleSvgSpec, `09-styled` probe): node `fill` (`filled`⇒`fillcolor`/`color`/`lightgrey`, else `none`), `stroke`=`color`, text `fill`=`fontcolor`; edge `stroke`=`color` + `stroke-dasharray` (`dashed`⇒`5,2`/`dotted`⇒`1,5`) + arrowhead in the edge colour. **Emit order corrected to gv's node/edge interleave** (each edge emitted right after its head node is introduced — `a1,b3,a1→b3,a2,…`), previously all-nodes-then-edges (only latent because SvgSpec parses structurally). **`09-styled` svg byte-exact**; unstyled corpus (01/06/07/04/08) unchanged. Styled diagrams now render styled end-to-end (was black-on-none) |
| `MultipleRenderResult` shape (`status/output/errors`) | ✅ | GraphvizSpec | `Graphviz.renderFormats(dot, formats)` (pure, cross-compiled): parse→resolve→emit. `status`/`output` map/`errors` mirror [VizJS.scala:80](viewer/src/main/scala/org/jpablo/graphexplorer/viewer/backends/graphviz/vizjs/VizJS.scala#L80). Malformed DOT / unsupported format → `failure` (reported, not thrown). The M8 call-site seam |

### 5.5 Explicit non-goals (⛔)
- neato/fdp/circo/twopi/osage/sfdp engines
- Output formats other than `dot_json`/`json0`/`svg` (png, pdf, xdot, plain at runtime)
- Image/`imagepath` embedding, custom font files
- Bit-exact coordinate parity with Graphviz

## 6. Risk register
| Risk | Mitigation |
|---|---|
| Tie-breaking divergence (network simplex, mincross) | Exact-match ranks (M2) early; document & test accepted order deviations (M3) |
| Text metrics drift cascades into all coords | Embed AFM tables; verify node sizes (M1) before any layout work |
| Spline routing complexity | Loose ε first; tighten incrementally; `plain` format isolates the stage |
| HTML-like labels scope creep | Treat as its own sub-track in M6; ship without it behind a capability flag if needed |
| ~~NS-optimisation deferred (M2 uses longest-path)~~ | **RETIRED 2026-05-16.** `NetworkSimplex` implemented; `Rank` now uses true NS+TB-balance; corpus ranks unchanged (oracle-verified) + `NetworkSimplexSpec` covers slack/non-unique/weighted cases |
| Reference source ≠ oracle version (tie-break mismatch, unfixable diffs) | §2.4 version-alignment protocol; study only the version-matched worktree |
| Oracle drift on viz-js bump | Pin `@viz-js/viz` 3.14.0; on any bump re-pin source worktree + regenerate golden + diff review |
| ~~XCoord network-simplex non-termination on dense graphs~~ (found by M8 validation 2026-05-29) | **RESOLVED 2026-05-29.** Not cycling (a `maxIter` cap existed) — an **O(V·(V+E))-per-pivot blowup**: cut values were recomputed from scratch for every tree edge (plus a redundant `cutValue(leaving)` recompute), and `propagateTight` was O(V²). On sbt-deps' ~800-node/1234-edge aux graph that's ~10¹⁴ ops ≈ hang. Fixed by porting `ns.c` `dfs_range` + `dfs_cutval`: all cut values in one **O(V+E)** postorder pass over a rooted low/lim tree (reusing children's values), low/lim subtree membership for the entering-edge search, and adjacency-based `propagateTight`. **Byte-identical** cut values ⇒ corpus 140/140 unchanged. sbt-deps XCoord **∞ → 0.49s** (370 pivots, optimal); module-deps renders in 0.9s. Guard: NetworkSimplexSpec dense 400-node/2800-edge case. |

## 7. Progress log
- **2026-05-16** — Plan created. Reference source confirmed available at
  `/Users/jpablo/GitHub/graphviz` (EPL 2.0), clone HEAD `14.1.5-28-g233597cd4`
  — **too new**; must worktree at the viz-js-3.14.0-matched tag (TBD: read
  `Viz.graphvizVersion` in M0). "Clean-room" reframed to "reimplement from
  understanding". `textspan_lut.{c,h}` identified as a direct M1 accelerator.
- **2026-05-16** — M0 step 1 done early: measured `@viz-js/viz` 3.14.0
  bundles Graphviz **13.0.1** (`engines` include dot/neato/fdp/... — scope
  to `dot` confirmed sound; all oracle formats plain/json0/dot_json/xdot/svg
  present). Clone has an exact `13.0.1` tag → reference worktree can be
  pinned immediately. Version unknown is CLOSED before any code written.
- **2026-05-16** — **M0 complete.** `graphviz` cross-module scaffolded
  (CrossType.Full; shared main is platform-neutral, JVM-only oracle harness in
  `jvm/`). Reference worktree pinned: `/Users/jpablo/GitHub/graphviz-1301` @
  tag `13.0.1`. DOT front-end (`dotlang.Ast` + fastparse `DotParser`, custom
  `//`/`#`/`/* */` whitespace) parses the full seed corpus. Oracle capture
  (`oracle/capture.mjs`) froze 6 corpus files × 8 formats into `golden/` with a
  `_meta.json` version stamp; `OracleContractSpec` fails CI if the bundled
  Graphviz ever drifts from 13.0.1. Suite green: 10/10. Module scalacOptions
  relax `-Xfatal-warnings`/`pureFunctions` (tech-debt, §6) — re-tighten
  post-parser. Next: M1 (attribute resolution + `textspan_lut` font metrics).
- **2026-05-16** — **M1 complete.** Font metrics transcribed from the pinned
  `textspan_lut.c` via `oracle/gen_font_metrics.py` → generated
  `metrics/FontMetricsTables.scala` (Times/Helvetica/Courier, EPL-attributed);
  `FontMetrics` ports `estimate_text_width_1pt`. `model/AttrResolver` resolves
  AST → `RGraph` with DOT default-statement scoping/inheritance/merge.
  `layout/NodeSize` ports `poly_init` for ellipse/box/circle/square/plaintext
  (PAD = +16/+8 pt; ellipse spare-height branch; fixedsize/margin/min-size).
  Oracle-gated: every corpus node's width/height matches the `dot` golden to
  **< 0.001 in** (`middle`→0.76226, `node one`→1.28238 vs 1.2824). Records
  deferred to M6 (`nodeSize`→None). Suite green: 21/21. Next: M2 (cycle
  breaking + rank assignment / network simplex) — exact-match ranks vs `json`.
- **2026-05-16** — **M2 complete.** `layout/Rank`: ports `acyclic.c` DFS
  back-edge reversal (roots in node-declaration order) + longest-path
  `init_rank` (minlen-aware, normalised). Oracle-gated via `RankSpec`: rank
  partition recovered from golden node positions (rank-axis by `rankdir`)
  matches **exactly** for 01/02/04/05/06; cycle breaking verified on 05's
  3-cycle and 06's undirected mesh. Deliberate scoped deferrals (PORT.md
  §5.2/§6): network-simplex optimisation + `balance()` (no corpus exercises
  ranking slack; trigger documented), and `rank=same/*` (needs subgraph
  structure → M6 with clusters). 03 ranks computed but exact-match deferred.
  Suite green: 29/29. Next: M3 (mincross / within-rank ordering).
- **2026-05-16** — **M3 complete.** Realised methodology point: the seed
  corpus had ≤1 real node per rank, so it could not test crossing
  minimisation at all → added the first hand probe `07-cross.dot` (forces
  3 crossings, unique 0-crossing optimum). Regenerated goldens: **01–06
  byte-identical** (oracle determinism confirmed); only `07` added.
  `layout/Order` ports `class2` virtual-node chains + `build_ranks` pass-0
  BFS init + `medians`/`reorder` (weighted median) + `transpose` +
  MaxIter/MinQuit/Convergence loop. `OrderSpec` gates on crossing-count
  parity (the objective): 0 across the whole corpus incl. `07` (3→0), and
  per-rank real-node order matches the oracle (mirror allowed). Deviations
  documented (§5.2): pass-0/pass-1 init alternation simplified, flat
  edges/ports/clusters deferred to M6. Suite green: 43/43. Next: M4
  (x-coordinate assignment) — node positions within ε of `plain`/`json0`.
- **2026-05-16** — **M4 (Y) complete.** Investigating `position.c` revealed
  M4 is two problems: Y is deterministic `set_ycoords`; X is just
  `ND_rank` from a **network simplex on an auxiliary graph** (`set_xcoords`
  is one line). Split accordingly. `layout/Coord.yCoords` ports
  `set_ycoords` (ranksep 36pt + half-heights); `CoordSpec` gates rank-axis
  Y tight (≤0.005 in) for label-free TB (01/06/07) — exact. Found & made
  explicit: edge-label graphs (02/05) need Graphviz rank-doubling
  (`ED_minlen*=2`); `CoordSpec` keeps a *deferred-probe* that asserts 05
  stays mismatched so it self-flags when that lands. X deferred to **M4x**
  = the network-simplex kernel — this also fires M2's long-standing NS
  deferral trigger (one solver retires both). Suite green: 48/48.
- **2026-05-16** — **Network-simplex kernel landed (M4x-1).** `layout/
  NetworkSimplex` ports GKNV §2.3 / `ns.c`: longest-path `init_rank`, tight
  feasible spanning tree, cut-value primal pivots (cut values recomputed per
  pivot — correctness over the C's low/lim), normalize, TB balance. `Rank`
  swapped from the longest-path stand-in to true NS (`balance=1`) — **all
  prior oracle tests stay green** (corpus ranks unchanged ⇒ NS verified
  end-to-end on real input), plus `NetworkSimplexSpec` (7) covers the
  slack/non-unique/weighted/disconnected cases the unique-optimum corpus
  can't. **M2's long-standing NS deferral is RETIRED** (§6). Suite: 55/55.
  Next M4x-2: build the X auxiliary graph (`make_LR_constraints` +
  `make_edge_pairs` omega 1/2/8, virtual widths, LR balance) → x coords.
- **2026-05-16** — **M4x-2: X coordinates (partial).** `layout/XCoord`
  ports `create_aux_edges`+`set_xcoords`: per-rank separation edges
  (`rw+lw+nodesep`), per-segment ω-weighted slack nodes (ω 1/2/8),
  virtual half-width = 1+nodesep/2; solved by the proven `NetworkSimplex`,
  then bbox-shifted. `Order.Result` now exposes layout `segments`.
  **Result: 07 (fully constrained → unique optimum) matches the `plain`
  golden exactly, incl. straight columns.** 01/06 have virtual-node slack
  → multiple equal-cost optima; Graphviz's LR_balance (NS balance=2,
  stubbed) centres, our NS takes an extreme. Honest scope: gate 07 exact;
  01/06 are self-flagging deferred-probes (assert deviation, fail when
  LR_balance lands). New deferral row in §6/§5.2. Suite green: 59/59.
  Next: implement LR_balance to close 01/06 (M4x final), or M5 splines.
- **2026-05-16** — **M4x: LR_balance ported; 01/06 root-caused (still
  🟡).** Implemented `ns.c` LR_balance in `NetworkSimplex` (balance=2:
  cutvalue-0 tree edges → enter_edge → shift tail side by slack/2,
  feasibility-guarded). Hypothesis "01/06 need LR_balance" **falsified by
  data**: with it, 07 still exact, all 59 green, but 01/06 unchanged.
  Diagnostic localised the real blocker quantitatively — 01 Graphviz
  solution costs 90 (`a=c=54,b=27,v≈63`), ours 110 (`a=c=v=82,b=27`);
  Graphviz's optimum needs `v−b=36` but our real↔virtual separation
  `minlen=27+10+18=55` excludes it from the feasible region. So the gap
  is the virtual-node separation/width model, not balancing. Recorded as
  a quantified deferral with the concrete next investigation (re-derive
  virtual `ND_lw/ND_rw` and the real↔virtual `make_LR_constraints` width
  at x-coord time, post-`expand_leaves`). Suite green: 59/59.
- **2026-05-16** — **Dedicated M4x-close attempt: narrowed, not closed.**
  Re-read `class2.c`/`make_LR_constraints`/`make_edge_pairs`/`expand_leaves`
  + 01's `dot` spline. Confirmed: chain virtual = `plain_vnode` ⇒
  lw=rw=10; formula `rw(u)+lw(v)+nodesep`; nodesep=18 (07-verified). Our
  `minlen=55` is a faithful transcription. Yet 01's spline shows v≈(63,90),
  b=(27,90) ⇒ Graphviz's effective gap ≈36 (`≈27+9`, i.e. as if nodesep
  is not added for real↔virtual). Contradiction is real and now sharply
  localised: **something mutates ND_lw/ND_rw or the constraint between
  `class2` and the aux solve** (suspects: `compress_graph`, `pos_clusters`,
  `make_leafslots`, or `make_edge_pairs` port handling) — NOT the formula
  I can read, NOT LR_balance. Deliberately did not oracle-fit the constant
  (≡ fudging ε). Next: instrument the real aux graph (verbose viz-js build
  or back-derive from `json` spline knots, then VERIFY the rule on
  02/06/07, not just 01). No code change this turn; 59/59 green.
- **2026-05-16** — **M4x CLOSED via source instrumentation.** Built
  `dot` from the pinned gv 13.0.1 source (brew bison; cmake; core +
  dot_layout/core plugins) with a one-line probe in `make_aux_edge`
  dumping the real aux graph. This **falsified my earlier diagnosis**:
  the b→v separation IS `minlen=55` (we had it right); the bug was the
  edge-pair ω weight. Real model = `virtual_weight()` class table, not
  ω=1/2/8: `t = tbl[class(tail)][class(head)]`, class = ORDINARY /
  SINGLETON(real, ≤1 incident edge) / VIRTUAL; tbl `[[1,1,1],[1,2,2],
  [1,2,4]]`. Verified against instrumented dumps of 01/06/07 (07's
  a1/b3 are singletons → C_SS=2; 01/06 branch nodes → C_EE=1). Fixed
  `XCoord` accordingly; with correct ω, a/c form a flat optimum that the
  already-ported `LR_balance` centres → **01/06/07 all match `plain`
  exactly**. Reverted source instrumentation (reference pristine).
  XCoordSpec gates 01/06/07 exact. Suite green: 59/59. Lesson: 3 source
  reads couldn't find what 1 instrumented build found in ~30 min.
- **2026-05-16** — **M5 increment 1.** `layout/Spline`: route each edge
  tail→virtual-chain→head, clip ends to the node ellipse boundary, `ARROW_
  LENGTH`=10pt gap at a directed head, emit piecewise cubic Bézier.
  Exposed all-node coords (`Coord.rankY`, `XCoord.solveAll`). **07
  matches `plain` exactly** (no virtual nodes ⇒ Graphviz routes straight).
  Found via the oracle: graphs with virtual nodes have Graphviz bowing
  **even short edges** (06 `a→b` exits a's right side) via the box-router
  — so endpoint geometry too is part of the deferred `routesplines`/
  Piecewise box-fit. Honest gate: 07 strict; 01/06 structural + a
  self-flagging deferred-probe. Suite green: 64/64. Next: box-fit
  sub-port (own milestone) or M7 output writers.
- **2026-05-17** — **M5-close attempt: cheap path falsified, box-fit
  confirmed NS-scale.** Tried a Catmull-Rom smooth spline through
  [clip,virtual,clip] and gated on Hausdorff curve deviation (the
  "visually close" objective, not control-point parity). Measured:
  **01 (a→c) 0.2502 in, 06 (a→b) 0.5444 in** — 18–39 px, an order of
  magnitude past a 4 px bound. Root: Graphviz's box-router bows even
  short edges substantially (06 `a→b` ~0.2 in right) around virtual-node
  regions; no smooth curve through the virtual centers approximates that.
  Catmull-Rom also re-parameterised 07's (identical) straight curve,
  breaking its control-point match for no gain. Reverted the experiment
  (back to committed `ce576ac8`: straight legs, 07 exact, 01/06
  deferred-probe). **Conclusion: M5 cannot be honestly closed cheaply;
  the `routesplines`/`Proutespline` box-fit is a real sub-port (own
  milestone).** Negative result committed so the dead end stays shut.
  Suite green: 64/64.
- **2026-05-17** — **M5 CLOSED via source instrumentation (§2.5).** The
  cheap path was already falsified; closed M5 by porting Graphviz 13.0.1's
  *actual* router. Instrumented `routesplines_` (one getenv-guarded probe,
  reverted) to dump the box channel, shortest path and raw spline for
  01/06/07. Ported, in idiomatic Scala: the box channel
  (`completeregularpath` family — tail/head half-boxes, `rank_box`,
  `maximal_bbox` with neighbour bounds, `adjustregularpath`, `checkpath`;
  derived constants MINW=16/FUDGE=4/Splinesep=nodesep÷4 cross-checked
  against the dump), the channel polygon walk, `Pshortestpath` (taut-string
  funnel over the consecutive box portals — the geodesic, verified equal to
  the dump), `Proutespline` (recursive least-squares cubic fit + barrier
  containment + `solve3/2/1`), and `clip_and_install`/`bezier_clip`
  (de Casteljau `Bezier`, ellipse `insidefn` with outline = size+penwidth,
  `arrowEndClip`). **Verification (the §2.5 discipline): the box channel,
  shortest path AND raw spline reproduce the instrumented gv-13.0.1 dump
  byte-for-byte for 01 `a→c`** (the only genuinely-curved corpus edge) — a
  faithful reimplementation, not a curve fit. Diagnostic dispelled a
  confound: 06's 0.34–0.66 in "deviation" was the documented X-mirror
  (XCoordSpec already allows it), not a routing defect; the lone real
  defect was 01 `a→c` (0.25 in). Gate = dense-sample symmetric Hausdorff
  vs `plain`, mirror-aware (whole-drawing, consistent — cannot mask a
  per-edge defect). Result: 01 `a→c` **0.25 → 0.024 in**; 01 `a→b`/`b→c`
  0.017; 07 0.024 (no-virtual-node guard, tight Eps07=0.03; raw spline
  byte-exact, residual is sub-2px endpoint clip — `arrow_length` kept at
  the documented ARROW_LENGTH=10, NOT back-solved from one graph per the
  no-oracle-fit rule). SplineSpec's deferred-probe promoted to the strict
  curve-deviation gate (Eps=0.04 in ≈ 2.9 px, inside the §1 2–4 px band,
  derived from the spec not force-fit). Suite green: **66/66**; graphvizJS
  compiles (shared stays platform-neutral). Reference worktree reverted to
  pristine (`git checkout`; `rm -rf _dbgbuild`). Scope honoured: 02 LR /
  03 clusters / 04 records / 05 edge-labels remain their tracked deferrals.
  Lesson reconfirmed: instrumenting the real gv (M4x's method) beat
  re-reading source — the byte-exact box/path/spline match made the port
  self-evidently faithful.
- **2026-05-17** — **M7 increment 1: `dot_json` + `json0` writers.**
  After M5 closed, the layout is correct but not yet *emitted* in the §1
  contract formats. Added `output/Output.scala` — hand-rolled JSON string
  builders (zero serialization deps; shared stays platform-neutral). Format
  derived directly from the captured goldens (the oracle) and cross-checked
  against `plugin/core/gvrender_core_json.c` (label always emitted, empty
  attrs skipped, `name`=graph id or `%1`): `dot_json` = structure only
  (space-`bb`); `json0` adds computed node `pos`/`width`/`height`
  (comma-`bb`) and the edge spline `pos` string. Exposed the spline's
  arrow-attach point via `Spline.splinesEx`/`ESpline` (Graphviz's
  `bezier` `sp`/`ep`) for the `e,EX,EY ` prefix (head arrow only).
  Subtlety: edge `_gvid` is cgraph **node-traversal** order (each node's
  out-edges), not declaration order — `Output.doc` reproduces it; verified
  identical for directed (01/07) and undirected (06). `OutputSpec` gates
  tolerance-aware (§2.1): graph attrs / `_gvid` / tail / head / label /
  arrow-prefix exact; `bb` / `pos` / `width` / `height` / spline within ε,
  **whole-drawing X-mirror allowed** (06 — same layout-equivalence
  XCoordSpec/SplineSpec already document; not a writer defect). Number
  format ≈ C `%.5g` (last-digit rounding absorbed by ε). Suite green
  **72/72**; graphvizJS compiles. Honest scope: `svg` is increment 2 (its
  own sub-port — shapes/text/path `d`/arrowhead polygon/flipped-y), the
  `MultipleRenderResult` `renderFormats` facade is increment 3 (M8 prep);
  M7 stays 🟡 until those land.
- **2026-05-17** — **M7 increment 2: `svg` writer.** `output/Svg.scala`
  (dependency-free). Header/`<svg>`/`viewBox`/`scale rotate translate`
  (flipped-y)/background polygon are bit-exact (derived from goldens,
  cross-checked vs `gvrender_core_svg.c`). Per node: `<ellipse>`
  (cx,−cy,rx=lw,ry=ht/2) + centered `<text>`. The text baseline `y` was
  the oracle-fit trap (golden −157.8 vs centre −162); resisted hardcoding
  `0.3·fontsize` and instead traced it to source: `labels.c emit_label`
  `p.y = pos.y + dimen.y/2 − fontsize`, `dimen.y = fontsize·LINESPACING`
  (1.20), then `svg_textspan` `+= 0.1·fontsize` → reproduces −157.8
  exactly. Per edge: `<path d="M..C..">` from the installed spline
  (y-negated) + a normal-arrowhead `<polygon>` = `arrow_type_normal0`'s
  `a[1..3]` (tip = `ESpline.ep`, base = tip+û·10, ±perp·0.35·10); the
  `delta_tip`/`delta_base` miter is the **same documented sub-2px M5
  deferral** (honest under the visual ε gate, NOT oracle-fit). Numbers via
  `gvprintdouble` (`%.2f`, trailing-zero trim, near-0→0); `xml_string`
  escaping (`-`→`&#45;` so `a&#45;&gt;b`). `SvgSpec` gates well-formed +
  structural (svg dims/viewBox/titles/labels exact) + ε geometry
  (path/arrowhead Hausdorff ≤3pt), whole-drawing X-mirror allowed (06).
  Suite green **75/75**; graphvizJS compiles. M7 stays 🟡 — only the
  `MultipleRenderResult` `renderFormats` facade (increment 3, M8 prep)
  remains.
- **2026-05-17** — **M7 COMPLETE (increment 3: `renderFormats` facade).**
  `graphviz/Graphviz.scala` — pure, cross-compiled public entry point
  mirroring the viz-js slice the viewer uses: `renderFormats(dot,
  formats)` → `MultipleRenderResult`(`status`/`output` map/`errors`).
  parse (`DotParser`) → resolve (`AttrResolver`) → emit
  (`Output.dotJson`/`json0`/`Svg.svg`). Failures (malformed DOT,
  unsupported format, internal throw) are *reported* as `status:
  "failure"` + `errors`, never thrown — matching viz-js. No scalajs/JVM
  APIs (§3); the viewer keeps consuming the strings via its existing
  `read[SimpleGraph]`/`getEdgePos`/`parseSVG`. `GraphvizSpec` gates:
  corpus → success with all 3 formats byte-identical to the individual
  writers; format-subset honoured; malformed/unsupported → failure.
  Suite green **81/81**; graphvizJS compiles. This is the M8 call-site
  seam — integration is now a one-line flagged swap in the viewer's
  `Graphviz.scala`, viz-js demoted to oracle + fallback. Scope unchanged:
  M6 long-tail (records/clusters/ports/rankdir/edge-labels) remains its
  own milestone; M7 writers are gated for label-free TB (01/06/07) and
  will extend as M6 features land.
- **2026-05-17** — **M8 seam landed (integration behind a flag) +
  data-driven M6 backlog.** Wired `viewer.dependsOn(graphviz.js)` and a
  feature-flagged adapter in the viewer's `Graphviz`: a backend-neutral
  `renderOutputs(dot, formats)` routes through the pure
  `graphviz.Graphviz.renderFormats` when `localStorage
  gx.graphvizEngine == "scala"`, else viz-js (unchanged default +
  fallback + oracle). Downstream (`read[SimpleGraph]`/`getEdgePos`/
  `parseSVG`) is byte-identical for both engines, so it's a true
  call-site swap, not a fork. Viewer Scala.js compiles; default
  behaviour unchanged (flag off). `DifferentialSpec` exercises the
  *full* pipeline only through the public facade: the must-pass
  label-free TB set (01/06/07) renders end-to-end and matches the
  goldens (graph attrs / node & edge sets exact; svg well-formed) — the
  CI-green integration gate (**85/85**). The M6-feature corpus is
  asserted only to *degrade gracefully* (return, never throw — keeps the
  viewer fallback well-defined) and its structural delta vs golden is
  printed: **03 clusters diverge structurally** (node/edge sets — needs
  cluster subgraph semantics), **02/04/05 are structurally correct** and
  diverge only geometrically (edge-labels / ports). This turns the M6
  backlog from guesswork into a measured list and matches the existing
  §5 deferrals exactly. M8 stays 🟡: the integration mechanism is done
  and safe to ship, but full parity on the real `dist/examples`
  diagrams (records/HTML/clusters/rankdir-heavy) is gated behind M6 —
  not faked green. viz-js stays the default until M6 closes the gap.
- **2026-05-17** — **M6 increment: edge-label rank-doubling (structure).**
  First M6 sub-feature, picked because the M8 differential harness
  measured 02/04/05 as structurally-correct/geometry-pending. Ported
  `rank.c` `edgelabel_ranks`: `Rank.hasEdgeLabel(g)` (any edge with a
  non-empty `label`) ⇒ `acyclic` scales every `minlen` by 2, and `Coord`
  uses the compensating ranksep `(GD_ranksep+1)/2` with **int**
  `GD_ranksep` ⇒ 36→18 (verified the type in `types.h`, not assumed).
  Result for 05: real nodes land on `{0,2,4}` with odd label ranks
  reserved — `RankSpec` gates this and contrasts 01 (`{0,1,2}`, no
  doubling); the golden's 7/7/10 edge-`pos` control-point counts
  independently confirm Graphviz routes through exactly those reserved
  ranks. Scope held honestly: the **exact** 05 geometry (Y, spline bow)
  additionally needs the label-vnode *dimensions* (the `make_chain`
  `label_vnode` — `line1\nline2` text **and** the `<b>html</b>` HTML
  label, the latter its own M6 row) plus graph-label bottom space
  (`do_graph_label`); these stay deferred and the CoordSpec 05 probe's
  self-flag comment now names them precisely (assertion unchanged — it
  still correctly mismatches). `OrderSpec` made doubled-rank-aware: empty
  interleaved label ranks are dropped before the oracle compare — the
  same layout-equivalence principle as the mirror allowance, not a
  reverted regression. Suite green **86/86**; graphvizJS compiles.
  §5.2 edge-label row ⬜→🟡.
- **2026-05-17** — **M6: edge-label Y closed (CoordSpec probe promoted).**
  Followed §2.5 to derive the model from the 05 golden before coding:
  back-solving the Y system gave label-vnode full heights 33.60 / 16.80
  and graph-label space 24.80 — i.e. exactly `nLines·14·1.2` per label
  (2-line text → 33.6; the HTML label is one line → 16.8 — its
  table/width complexity is irrelevant to rank-axis height) and
  `1·14·1.2 + 2·GAP` (GAP=4 confirmed in const.h, YPAD=2·GAP in
  macros.h). Ported faithfully: `NodeSize.labelHeightPt` (reuses the M1
  `\n\l\r` line splitter), `Coord` seats the `make_chain` `label_vnode`
  half-height at the mid rank `(rank tail+rank head)/2` and reserves the
  `do_graph_label` root-label space on the labelloc side (default
  bottom). Result: 05 real-node Y = golden within ≤0.00004 in (tol
  0.005). The CoordSpec 05 self-flagging deferred-probe is **promoted**
  to the strict gate (05 added to the main Y-match list) — the same
  promotion pattern as M5/M7, not a loosened ε. Note resisted: the
  `(int)(fontsize·LINESPACING)` truncation in `make_label` (→16) does
  *not* apply to the label-vnode height — the oracle was unambiguous
  that it's the exact 16.8/line, so the exact form was used (derive from
  oracle, don't assume the first source formula found). Suite green
  **87/87**; graphvizJS compiles. §5.2 edge-label row 🟡→✅; HTML-label
  width/table + LR (02) + clusters (03) remain their own tracked rows.
- **2026-05-17** — **M6 `rankdir=LR`: model reverse-engineered, full port
  deferred (honest negative, recorded for the tracker).** Fully derived
  the transform from `postproc.c`: `translate_drawing` rotates every
  point `ccwrotatepf(p, rankdir·90) − Offset`; LR ⇒ `(bb.UR.y−y,
  x−bb.LL.x)`; layout runs with `gv_nodesize(n, flip)` swapping node w/h
  (`ht=width`). Landed the pure `Rank.rankdir`/`Rank.flip` helpers.
  Prototyped the flip + `RankDir.mapPoint` and **measured** the result
  against 02's golden instead of assuming: nodes form the right LR shape
  but the order-axis (post-rotation Y) is off ~0.35–0.47 in — not
  "visually close". Two real blockers surfaced, both from evidence:
  (1) flipping inside `NodeSize.nodeSize` **broke `NodeSizeSpec`** (it is
  the true-size `dot`-oracle contract; gv's flip is a layout-internal
  ND_lw/rw/ht mutation `translate_drawing` restores) — so the swap needs
  a separate layout-orientation size threaded through Coord/XCoord/Spline;
  (2) even so, the canonical X/order simplex must reproduce the
  edge-label-weighted straightening under flip. This is a genuine
  multi-part sub-port, not a transform one-liner. Per the project
  methodology: reverted the contract-breaking `NodeSize` change and the
  incomplete `RankDir`, kept the suite green (**88/88**) and graphvizJS
  compiling, and recorded the precise model + blockers here rather than
  shipping a fake-green LR gate or a loosened ε. Next session executes
  LR from this entry (the M5-style "plan in the tracker" pattern). 02
  stays LR-deferred; its `go` edge-label rank-doubling is ✅.
- **2026-05-17** — **M6 04 ports/compass: reframed via recon (records
  sub-track), scoped & oracle-identified.** The M8 differential harness
  reported 04 "structure ok", but recon shows 04's corpus is entirely
  `shape=record` and its ports are record *field* ports
  (`struct1:f0`, `struct2:b:n`) — so 04's geometry is gated behind the
  **records** sub-port, not bbox-compass (which has no corpus example).
  Same necessary-not-sufficient lesson as LR: the harness's structural
  check masked a deferral dependency. Records is a milestone-sized
  recursive sub-track (PORT.md §6 anticipated this): `parse_reclbl`
  (shapes.c:3380) record grammar + `record_init` (:3687) field-text
  sizing (reuses the M1 `FontMetrics`) + recursive box packing +
  `record_path` (:64) port anchors. **The oracle is ready and exact**:
  the 04 `dot` golden echoes `width`/`height` + per-field
  `rects="x0,y0,x1,y1 …"` and per-edge `pos`, so each stage (parse →
  field rects → node size → port-anchored endpoints) is independently
  gatable. Per methodology: did not start a large recursive port at the
  tail of a long session; recorded the precise scope, gv entry points
  and ready oracle in §5.2/§5.3 for a focused next session. Suite
  unchanged & green (**88/88**); no fake gate.
- **2026-05-17** — **M6 records (layout/sizing) CLOSED — exact.** First
  real chunk of the records sub-track. Followed §2.5: back-solved the 04
  `dot` golden *before* coding and verified the model reproduces it (incl.
  the subtle bits: per-line leaf height is the **exact** `fontsize·
  LINESPACING` not `make_label`'s `(int)` truncation — same call the 05
  edge-label vnode made; the `record_init` `height += 1pt` kluge; PAD =
  `XPAD 4·GAP` / `YPAD 2·GAP`; `{}` toggles orientation, top-level
  `LR=!realflip`). `RecordLabel` ports `parse_reclbl` +
  `size/resize/pos_reclbl` + `record_init`; `NodeSize` now sizes
  record/Mrecord (the M1-deferred `None` is retired). Result is
  byte-exact: struct1 `1.833×0.51389`, struct2 `0.75×0.70278` (vs the
  `dot` golden; NodeSizeSpec now *includes* them, no longer deferred),
  and every node-local field box + node centre == the golden absolute
  `rects` to ≤0.05 pt (`RecordSpec`). Recon paid off twice: it caught
  that "04 ports" was really records (avoiding a wrong-target port), and
  the pre-derived oracle made the implementation first-try exact. Suite
  green **90/90**; graphvizJS compiles. §5.3 record row ⬜→✅. Remaining
  record work tracked separately: field-port edge endpoints (§5.2 ports,
  increment 2) and record svg field-line drawing (M7-svg follow-up);
  HTML-in-record + exotic escapes stay no-corpus deferrals.
- **2026-05-17** — **M6 ports: model threaded + emitted (🟡).** Recon
  caught a model gap before any geometry work: `node:field:compass` was
  parsed by the AST (`NodeId.port`) but **silently dropped** by
  `AttrResolver`, so 04's two `struct1:f0→struct2:a` /
  `struct1:f2:s→struct2:b:n` edges both collapsed to portless
  `struct1→struct2` (and, being parallel, merged in `Spline`'s
  `(tail,head)` map). Fixed the foundation: `REdge` gains
  `tailPort/headPort: Option[ast.Port]` (**additive, default None** ⇒
  every portless edge is byte-identical, suite stays green),
  `AttrResolver` carries `NodeId.port`, and `Output` emits dot_json/json0
  `headport`/`tailport`. Gated structurally vs the 04 `dot_json` golden:
  the edge port set is exactly `{(struct1,struct2,f0,a),
  (struct1,struct2,f2:s,b:n)}` (91/91; graphvizJS compiles; 01/06/07
  JSON unaffected — no ports). Honest scope: this is the model/emission
  layer; geometric field-anchored endpoints + parallel-edge `Spline`
  de-merge + port-box clip are the next increment (oracle ready: 04
  `dot`/`plain` per-edge `pos`). §5.2 ports row ⬜→🟡. Same recon
  dividend as records: the gap was found and fixed cheaply instead of
  debugging wrong geometry later.
- **2026-05-17** — **M6 ports: `PortAnchor` resolver (record_port +
  compassPort).** Ported `compassPort` over `RecordLabel.fieldBox`:
  no-compass/`_`/`c` ⇒ field-box centre + `clip=true` (visible endpoint =
  post-clip box boundary); `n/s/e/w/ne/…` ⇒ constrained side/corner point
  + `theta`, `clip=false` (that point *is* the endpoint). Verified
  node-local against the 04 golden, isolated from node placement (same
  technique as RecordSpec rects): struct2 `b:n` head anchor == the golden
  `e,` endpoint **exactly** (≤0.05 pt); struct1 `f2:s` tail == golden
  start within ≤1 pt — and the ~0.5 pt residual is precisely the
  `beginpath`/`endpath` ±1 nudge (the spline pipeline's, *not* the
  resolver's), so it's gated honestly with that explained tolerance, not
  a fudge. no-compass `f0`/`a` resolve to the correct field-box centre
  exactly + assert `clip=true` (their visible endpoint needs the
  next-increment clip). Suite **93/93**; graphvizJS compiles. §5.2 stays
  🟡 — remaining is the genuinely deep Spline-core piece (box channel
  through the port boxes + no-compass clip-to-field-box + parallel-edge
  `Spline` key de-merge), scoped with the 04 `dot`/`plain` `pos` oracle
  for a focused next session.
- **2026-05-17** — **M6 ports CLOSED — 04 lays out + routes end-to-end
  (§2.5 instrument-and-port).** One getenv-guarded probe in
  `make_regular_edge` dumped, for **both** 04 edges, the resolved
  tail/head port (`side`/`p`/`theta`/`constrained`/`clip`), the full
  `P->boxes` channel, `P->start`/`P->end` and the raw `routesplines`
  spline. The dump **falsified the "clip the spline to the field box"
  framing**: Graphviz routes record ports through the *same* box-channel
  router with three deltas — (1) start/end come from `compassPort` +
  `resolvePort`/`closestSide` (a `_`/no-compass port is *dynamically*
  resolved to the field side closest to the other node, → constrained,
  `clip=false`), (2) the endpoint tangents are constrained (θ → `evs`
  into `Proutespline` — our `proutespline` already took `ev0/ev1`, just
  always `(0,0)`), (3) the ellipse shape-clip is skipped. Ported, idiomatic:
  `RecordLabel.pos_reclbl` field `sides`; `compassPort`/`resolvePort`/
  `closestSide`; the `beginpath`/`endpath` side box + ±1 nudge and the
  `record_path` pbox fallback (`struct2:b:n` — b has no TOP ⇒ side 0 ⇒
  field box clipped to node height, no nudge); constrained-tangent
  `proutespline`; `clipInstall(tailClip/headClip)` skipping the shape
  clip on a `clip=false` port. **Second defect the dump's node coords
  exposed:** `make_edge_pairs` also reads `ED_*_port.p.x` — ported the
  integer-truncated port x-offset into `XCoord` (slack minlens
  `m0+1`/`m1+1`, `m0=(int)(headport.x−tailport.x)`); `Order.Result`
  gained `segOwner` so each segment finds its edge's ports. struct2.x
  65.99→**63.99** = golden exactly (f0-left vs f2-right weighted-median
  balance) — without it the spline endpoints sat 2 pt off, a wrong
  *placement* masquerading as "visually close". Discipline: gated on the
  **golden**, not the probe; the instrumented box channel/start/end/raw
  spline reproduce byte-for-byte for both edges; final splines match
  `plain`/`json0` — SplineSpec Hausdorff ≤0.04 in with **mirror
  disallowed** (04 is TB & unmirrored — verified, asserted, not assumed),
  OutputSpec json0 `e,EX,EY` exact (≤0.5 pt) + spline ≤2 pt (the viewer
  `getEdgePos` contract). Parallel-edge de-merge: `Spline` re-keyed by
  `g.edges` index; every consumer (Output.doc/json0/dot_json, Svg,
  SplineSpec) correlates by index — additive, 01/06/07 byte-identical.
  No fake gate, no loosened ε, no oracle-fit constant (the `make_edge_
  pairs` int formula is the faithful source rule, cross-checked on both
  edges + the instrumented dump). Suite **95/95**; graphvizJS compiles;
  reference worktree reverted pristine (`git checkout`; `rm -rf
  _dbgbuild`). §5.2 ports + parallel/multi-edge rows ⬜/🟡 → ✅. Remaining
  M6: LR (02), 03 clusters, record svg field-line drawing, self-loops.
- **2026-05-17** — **M7-svg follow-up: record field-line drawing CLOSED —
  byte-exact.** With records (✅ layout) + ports (✅) done, the last
  record sub-item was the *drawing*. Ported `record_gencode` + recursive
  `gen_fields` (shapes.c) into `Svg.svg`: a record node emits the outer
  box `<polygon>` (`gvrender_box`, LL/UL/UR/LR/LL), then per table the
  inter-child separator `<polyline>`s (LR ⇒ vertical at `child.b.LL.x`
  spanning `child.lly..ury`; TB ⇒ horizontal at `child.b.UR.y` spanning
  `llx..urx` — exactly the C `AF[]`), then per leaf its centred field
  `<text>` (reusing the existing `emit_label` baseline `−(cy+dimY/2−
  fs+0.1·fs)` at the **field-box** centre). Non-record nodes keep the
  `<ellipse>` path unchanged (additive — 01/06/07 byte-identical).
  Boxes come straight from the ✅ `RecordLabel` root (node-local,
  un-klugd size) + node centre, so no new geometry. Gated **byte-
  identical** (not ε): both 04 record node `<g>` blocks == the viz-js
  golden's char-for-char (verified by extraction + diff). Honest scope:
  the graph `<title>`/`Title:` comment, edge `<title>` port suffix
  (`struct1:f0->struct2:a`) and bbox float precision (we int-ceil
  translate/bg vs gv's 2-dp) are **pre-existing svg gaps for any
  named/ported graph — NOT record-specific** (SvgSpec's `<ellipse>`-only
  `NodeRe` never tested them; 07 "cross" has the same untested title
  gap); recorded in §5.4 as a separate follow-up, not folded in here
  (one focused commit). Edge path/arrow numbers stay the documented
  sub-2px M5/M7 arrow-miter residual (within ε). Suite **96/96**;
  graphvizJS + viewer compile. §5.3 record row's "svg field-line drawing
  = M7-svg follow-up" → ✅; §5.4 `svg` row notes records + the remaining
  generic-svg gaps. Remaining M6: LR (02), 03 clusters, self-loops;
  remaining svg: graph/edge `<title>` + bbox 2-dp precision.
- **2026-05-17** — **svg graph/edge titles (closes the latent 06/07
  gap).** Continuing the deferred generic-svg follow-up. Traced the
  exact rule in source (not guessed): `svg_begin_edge` →
  `strdup_and_subst_obj("\E", e)` (labels.c) ⇒ edge `<title>` =
  `t_str[:tp]` + `->`/`--` + `h_str[:hp]` where `tp/hp = ED_*_port.name`
  = `chkPort` `.name` (utils.c) = the raw port spec **after its first
  `:`** (else whole) — so `struct1:f2:s` ⇒ port name `s`, while json0
  keeps the full `f2:s` (two genuinely different fields, now both
  modelled: `REdge.tailPortName`/`tailPortStr`). The edge *comment*
  stays portless (`emit_edge` uses bare `agnameof`). Named-graph ⇒
  `<!-- Title: NAME -->` + graph `<title>` (anon `%1` ⇒ neither). Found
  & fixed a real latent bug: 06 "mesh"/07 "cross" are *named* and their
  goldens carry the title, but our Svg never emitted it — SvgSpec's
  `<ellipse>`-only `NodeRe`/header regexes never tested it, so it sat
  green-but-wrong. SvgSpec corpus loop now asserts the `Title:` comment
  + graph `<title>` for **every** corpus graph (01 anon ⇒ none; 06/07
  named), and a 04 test gates the two ported edge `<title>`s
  (`struct1:f0->struct2:a`, `struct1:s->struct2:n`) + portless comment.
  Additive (no geometry); 04 svg now diverges from golden **only** by
  the tracked bbox 2-dp precision + the documented sub-2px M5/M7
  arrow-miter residual. Suite **97/97**; graphvizJS + viewer compile.
  §5.4 `svg` row updated; sole remaining svg item = `Output.bbox` float
  precision (cross-format: dot_json/json0/svg `bb`, its own commit).
  Remaining M6: LR (02), 03 clusters, self-loops.
- **2026-05-17** — **bbox precision CLOSED — byte-exact across all 3
  formats.** The deferred cross-format item. Source-traced (not guessed)
  `position.c dot_compute_bb` (root): graph bb = **node-extent only**
  (NORMAL nodes' `ND_coord ± ND_lw/ND_rw`; y from rank `ht1/ht2`),
  **no spline extent, no floor/ceil** — the splines are channel-bounded
  so gv deliberately omits them. The oracle then showed the three
  formats *disagree by design*: `-Tdot`/`json0` `bb` keep the **exact
  float** (`0,0,131.98,123.6`) while `dot_json` floor/ceils to the
  **integer** box (`0 0 132 124`), and svg uses a ceil'd **int canvas**
  (`width/height`/viewBox) but the **exact float** for `translate`/
  background. Rewrote `Output.bbox` to the exact node-extent (dropped the
  old spline-union + floor/ceil that the ε gates had tolerated); `dotJson`
  re-applies floor/ceil; `json0` + `Svg` use the exact value; `Svg` now
  *shares* `Output.bbox` (removed the duplicated, divergent inline
  computation). Result: `bb` is **byte-exact** vs the golden for the
  whole corpus in all three formats, and 04's svg header/transform/
  background match char-for-char — previously the only non-arrow 04 svg
  divergence. 01/06/07 unchanged (their extents are integer ⇒ exact ==
  the old floor/ceil; the ≥ε gates now pass at dev 0). New byte-exact
  gates: OutputSpec `bb` (dot_json int / json0 float) ×4, SvgSpec 04
  header/transform/bg ×1. Suite **97→102**; graphvizJS + viewer compile;
  no instrumentation needed (pure source trace + oracle measurement).
  04 svg now diverges from golden **only** by the global documented
  sub-2px M5/M7 arrow-miter residual (within ε; not 04-specific).
  Remaining M6: LR (02), 03 clusters, self-loops.
- **2026-05-17** — **M6 `rankdir=LR`: blocker (1) CLOSED, blocker (2)
  sharply quantified (honest negative, real progress).** Re-instrumented
  gv 13.0.1 (`translate_drawing`/`map_point` probe, reverted) to dump the
  **canonical** (pre-transform) coords + `Offset` + canonical bb + final
  coords for 02 (LR) and 01 (TB control). This nailed the model exactly:
  layout runs TB with `gv_nodesize(n, flip)` (LR ⇒ node w/h **swapped**),
  then `map_point` = `ccwrotatepf(p, rd·90) − Offset`; LR Offset =
  `(−cbb.UR.y, cbb.LL.x)` ⇒ `final = (cbb.UR.y − y, x − cbb.LL.x)`
  (reproduced 02's `start/middle/end` final coords + bb from the dump
  byte-exact). Ported §7 **blocker (1)**: `NodeSize.layoutSize`
  (`flip ? swap(w,h) : nodeSize`) threaded through Coord/XCoord/Spline;
  `nodeSize` untouched (true-size oracle). Because `layoutSize ==
  nodeSize` when not flipped, **every TB test stays byte-identical**
  (102→**106/106**, +4 RankDirSpec; 01/06/07/04/05/03 unchanged) — the
  safe-additive property held. Measured the result vs the 02 golden
  (not assumed): the **rank axis (final X) is now within ~3 pt** (gated
  as progress) — the layout-orientation size + transform are correct.
  §7 **blocker (2)** persists and is now precisely root-caused: the
  canonical **order axis** (XCoord under flip) doesn't reproduce gv's
  straightening — gv aligns `start`/`middle` at canon x≈46 via the
  `weight=2` edge + the `go` label-vnode; ours 45 vs 18 ⇒ final Y off
  7–34 pt (not visually close). Closing it needs edge `weight` in the
  XCoord ω (the documented M5+ deferral, which risks the
  instrument-won M4x ω contract) **plus** the edge-label vnode's X under
  doubled ranks — itself multi-part, beyond this focused commit.
  Per methodology: shipped the safe infrastructural progress + a
  **self-flagging deferred-probe** (RankDirSpec asserts the order-axis
  still deviates ⇒ fails-when-fixed), no fake gate, no loosened ε,
  reference worktree reverted pristine. §5.2 LR row ⬜→🟡. Remaining
  M6: LR order-axis (blocker 2), 03 clusters, self-loops.
- **2026-05-17** — **Arrow-miter residual CLOSED (svg-scoped).** The
  sub-2px `delta_tip`/`delta_base` arrowhead residual deferred since
  M5/M7. **Source-traced, not guessed** (`arrows.c` `arrow_type_normal0`
  + `miter_shape`): the normal head's tip is shifted by `delta_tip =
  P3 − P` where `P3` is the stroke line-join (miter) apex of the two
  arrow-base→tip segments, with the SVG `stroke-miterlimit=4` **bevel
  fallback** (midpoint of P1/P2) when exceeded. Ported `miterShape` +
  `arrowNormal0` into `Svg` and hand-verified byte-exact vs the 07 golden
  *before* wiring (delta_tip ≈ (0,−1.5135) reproduced the measured 1.52 pt
  shift exactly). Result: **07 arrowheads byte-identical** to the golden
  (no virtual nodes ⇒ exact spline ⇒ exact arrow base direction); 01's
  straight-edge arrowhead byte-identical too; the curved `a→c`/04 port
  edges now ≤0.5 pt (was ~1.5–2) — the remainder is purely the **upstream
  M5 spline-endpoint feed** into `u` (`Spline.clipInstall`'s nominal
  `ARROW_LENGTH` clip + `delta_base`-shortened attach), a distinct
  spline-pipeline item deliberately left out to keep this commit
  svg-scoped and off the M5 Hausdorff gates. `arrowsize`/`penwidth`
  threaded (corpus defaults ⇒ 10/1). New gate: SvgSpec asserts 07's 3
  arrowhead polygons **byte-identical**; the corpus ≤ε loop now passes
  with a far smaller residual (Eps comment corrected — the miter is no
  longer the deferral). Reference worktree untouched (pure source trace +
  numeric oracle check, no instrumentation). Suite **106→107**;
  graphvizJS + viewer compile. Remaining M6: LR order-axis (blocker 2),
  03 clusters, self-loops; remaining spline: the `clip_and_install`
  arrow attach (`delta_base` + true arrow length) — its own item.
- **2026-05-17** — **Spline arrow-attach: true `arrow_length` ⇒ directed
  corpus byte-exact (M5/M7 residual fully closed).** Followed the
  arrow-miter commit's own breakout item. Source-traced `arrows.c`
  `arrowEndClip`→`arrow_length`→`arrow_length_normal`: the spline is
  trimmed by `full_length − penwidth/2` where `full_length` is the
  `arrow_type_normal0` returned `q.x` (the miter-extended length) — for
  the defaults **≈11.53 pt, not the nominal `ARROW_LENGTH` 10** that M5
  deliberately kept (per the then-correct no-oracle-fit rule; now it's a
  faithful port, not a fit). Extracted `layout/Arrow` (miterShape +
  normal0 + lengthNormal), shared by `Svg` (polygon, de-duplicated from
  the prior commit) and `Spline.clipInstall` (`elen = Arrow.lengthNormal
  (penwidth, arrowsize)` replacing the hard-coded 10). **Measured result
  (not assumed):** every directed corpus edge spline is now **byte-exact**
  vs `plain` — 07 = 0.00000 in (×3), 04 ports ≤0.00003, and 01 `a→c`
  (the M5 curved showcase) **0.024 → 0.00002 in**. The nominal-10 was the
  *sole* remaining endpoint error source. 06 unchanged (undirected ⇒ the
  `g.directed` clip is skipped; its ≈0.013 in is the documented X-mirror,
  not arrow-related). Gates tightened to lock it: `Eps07` 0.03→0.005,
  new `EpsExact`=0.005 byte-exact gate for the directed corpus (01/07/04),
  04 spline gate Eps→EpsExact. `ep`/json0 `e,EX,EY` unaffected (captured
  pre-clip). Svg-/Spline-scoped, additive; pure source trace + numeric
  oracle check (no instrumentation; reference worktree untouched). Suite
  **107→109**; graphvizJS + viewer compile. Lesson: the long-tolerated
  "sub-2px" residual was never noise — it was one missing model term
  (stroke-miter arrow length); a constant axial offset always is.
  Remaining M6: LR order-axis (blocker 2), 03 clusters, self-loops.
- **2026-05-17** — **Self-loops CLOSED — byte-exact end-to-end.** Added
  the first new corpus probe since `07-cross`: `08-selfloop` (`a->a` +
  `a->b`, one feature per file, §2.3). Re-ran `oracle/capture.mjs`:
  01–07 goldens **byte-identical** (oracle determinism reconfirmed), 08
  frozen. Source-traced the whole self-edge path: `makeSelfEdge`→
  `selfRight` (no-port: a 7-point / 2-cubic curve bowing right of the
  node by `rw + (i+1)·nodesep`, `sgn=+1`, `sizey` from the dotsplines.c
  rank-position rule — minrank ⇒ `y(r)−y(r+1)`), routed in a new
  `Spline` pass keyed by the g.edges index (self-loops don't rank, so
  they're cleanly excluded from acyclic/order and handled apart). The
  spline came out **byte-exact on the first run** (the prior commit's
  true `arrow_length` makes the loop's clipped end exact too). A second,
  non-obvious piece the oracle exposed: 08's golden `bb` width is 72,
  not the 54 node-extent — `position.c make_LR_constraints` enlarges
  `ND_rw` by `selfRightSpace` (`SELF_EDGE_SIZE`=18, no-port) and
  `dot_compute_bb` sees it. Ported that into `Output.bbox` (the shared
  bb): a self-looped node's right extent grows +18/loop ⇒ 08 `bb`
  byte-exact across dot_json/json0/svg. Gates added: SplineSpec (08
  spline byte-exact + loop-shape), OutputSpec (08 bb + json0 self-loop
  `pos`), SvgSpec (08 canvas + edges). Additive — portless/non-self
  graphs byte-identical (01–07 green). Pure source trace + numeric
  oracle (no instrumentation; reference worktree pristine). Suite
  **109→114**; graphvizJS + viewer compile. Ports-on-self-edges
  (`selfTop/Left/Bottom`) stay a documented no-corpus deferral.
  Remaining M6: LR order-axis (blocker 2), 03 clusters.
- **2026-05-17** — **LR blocker (2) half-closed: edge `weight` → XCoord
  ω.** Threaded the originating edge's `weight` attr into the
  `make_edge_pairs` slack weight (`w = ω-class × ED_weight`), via the
  `Order.Result.segOwner`→`realEdges` map already added for ports.
  Faithful port (was the documented "× userWeight = M5+" deferral);
  **default weight 1 ⇒ 01/06/07 byte-identical** (XCoordSpec — the
  instrument-won M4x ω contract — stays green; suite 114/114). Measured
  effect on 02: the `weight=2` `start→middle` edge now **aligns** `start`
  and `middle` on the canonical order axis (was x 45 vs 18, now 18 vs 18
  — matching gv's `start≡middle`), a concrete piece of §7 blocker (2).
  The remaining ~25–34 pt order-axis error is the **edge-label vnode's X
  under flip** (the `go` label injects an order-axis virtual node our
  canonical X/mincross doesn't place like gv) — a genuine standalone
  sub-part, not closeable in this commit without a deeper mincross/
  vnode-X port. Per methodology: shipped the safe additive progress,
  narrowed the RankDirSpec self-flagging probe's narrative (assertion
  unchanged — order axis still deviates ⇒ still fails-when-fixed), no
  fake gate, no loosened ε. Pure source trace; reference pristine.
  graphvizJVM 114/114; graphvizJS + viewer compile. **This is the
  honest progress wall for LR this session** (the label-vnode-X-under-
  flip sub-port is the next discrete step). Remaining M6: LR
  label-vnode-X-under-flip, 03 clusters.
- **2026-05-17** — **03 clusters: recon reframes it (major de-risk),
  scoped — honest session-end wall.** Before starting the "hardest part
  of M6", did the records/ports-style recon-first. **Key finding: the
  pinned viz-js 3.14.0 oracle does NOT lay out clusters** — 03's
  `plain`/`dot`/`json0` carry `pos="0,0"`, `bb="0,0,0,0"` throughout.
  So 03 is **not** a recursive cluster-layout port; per §1 (match
  viz-js *strings*) it is a **structural model+emit** task with zero
  geometry. Derived the exact target from the dot_json golden:
  `_subgraph_cnt=3`; `objects` lists the 3 subgraph objects first
  (`cluster_0` nodes=[a1,a2] edges=[a1→a2]; `cluster_1` nodes=[b1];
  anon `{rank=same}`→`%7` nodes=[a0,b0]) then the 6 nodes; node `_gvid`
  offset by `_subgraph_cnt`; cluster objects carry `label`. Measured
  our gap: `_subgraph_cnt=0`, no subgraph objects, node `_gvid`
  unoffset — because `AttrResolver`/`RGraph` is **flat** (no subgraph
  tree, the long-standing M1 simplification). Closing 03 = an additive
  cluster/subgraph-tree in the resolved model + `Output` emission
  (safe: non-clustered corpus stays `_subgraph_cnt=0` ⇒ byte-identical)
  — foundational and milestone-sized. Per the project methodology
  (precedent: records/LR §7), recorded the precise scope + exact oracle
  structure + the model-level blocker in §5.2 for a focused next
  session rather than starting a foundational refactor at the tail of a
  long, 12-commit session. **This is the genuine progress wall**: both
  remaining items (LR label-vnode-X-under-flip; 03 subgraph-tree model)
  are deeper multi-part sub-ports, not safe focused session-tail
  commits. Suite green (114/114); graphvizJS + viewer compile;
  reference worktree pristine. Recon itself committed (the reframe —
  "geometry-free structural task", not "hardest recursive layout" — is
  the de-risking dividend).
- **2026-05-29** — **03 clusters: structural model + `dot_json` byte-exact —
  the "instrumented-gv" blocker dissolved by oracle-probing.** The 2026-05-17
  recon had scoped 03 as "additive subgraph-tree model **+** an
  instrument-derived ownership rule" and flagged the node→subgraph ownership
  partition as *"not inferable… needs an instrumented-gv `agfstnode(sg)`
  dump"*. Reading `plugin/core/gvrender_core_json.c` first showed
  `write_nodes(sg)` emits **raw `agfstnode(sg)` with no dedup** — i.e. the
  per-subgraph membership is *already visible* in ordinary `-Tjson` output, so
  no C build was needed: a handful of **probe DOT files through the
  version-matched oracle** (`@viz-js/viz` == gv 13.0.1, already in
  package.json) nailed every rule empirically. Cracked: **(1)** ownership —
  a `rank`-constraint subgraph evicts its nodes from any *cluster* list
  (plain subgraphs stay additive), and an evicted tail drops its edge from the
  cluster; **(2)** anonymous `%N` = `counter*2+1` over unnamed-root + every
  keyless edge + anon subgraphs in parse order (id.c `idmap`) ⇒ 03's three
  cluster edges tick to 3 ⇒ `%7`; **(3)** root `label:""` via gv `write_attrs`
  (skip-empty-**except-label**); **(4)** clustered graphs get a sentinel
  `bb="0 0 0 0"` because this viz-js bails on cluster layout. Landed additively:
  `RGraph.subgraphs`/`graphAttrKeys` (AttrResolver keeps what `walk` discarded)
  → `Output` emits subgraph objects first (preorder gvid), `sgCnt`-offset node
  gvids, AGSEQ-sorted edge array. **03 `dot_json` byte-exact** (ClusterSpec);
  DifferentialSpec 03 flips `nodes=DIFF edges=DIFF` → `nodes=ok edges=ok`.
  Non-clustered corpus byte-identical (`_subgraph_cnt=0`); suite **114→118**;
  graphvizJS + viewer compile; reference worktree pristine (read-only). The
  instrument-and-port recipe (§2.5) stayed in its box — probing the oracle is
  the cheaper first move when the datum is observable in a supported format.
  **Deferred (tracked §5.2/§5.4):** `json0` cluster-label geometry
  (`lheight`/`lwidth`/`lp` — viz-js emits a buggy *partial* layout for 03, not
  clean zeros) and svg cluster boxes; `rank=same` *layout* constraint (03
  needs none). Remaining M6: LR label-vnode-X-under-flip, HTML-like labels,
  `rank=same` layout, `json0`/svg cluster geometry.
- **2026-05-29** — **Full attribute emission in `dot_json`/`json0` — the
  M8-critical styling path.** The writers previously dropped *every* attribute
  except `label`, so a `fillcolor`/`shape`/`style` diagram rendered **unstyled**
  through the Scala backend (byte-exact layout, no colours). Ported gv
  `write_attrs` (`gvrender_core_json.c`): each object emits its resolved
  attributes **alphabetically**, skipping empty values *except* `label`
  (`json.c`), with the `stoj` `/`→`\/` escape. Nodes always carry `label`
  (default `\N`); edges carry `label` (default `""`) once *any* edge is
  labelled; `tailport`/`headport` are ordinary edge attributes (which is why
  04's port-only edges already sorted right). Graph attrs sort alphabetically
  too (fixed 02's `bgcolor`/`rankdir` order) and surface subgraph-declared keys
  at the root. For `json0` the layout keys (`pos`/`width`/`height`) merge into
  the *same* alphabetical stream — so the viewer's actual render format
  (`renderFormats(dot,["svg","json0"])`) now receives styling. Gated
  (AttrEmitSpec): **04 `dot_json` byte-exact** (records now emit `shape`);
  02/05 objects+edges byte-exact (their `bb` deferred — 02 LR, 05 graph-label
  bbox); 02 `json0` carries the style attrs; 01 unchanged. Suite **118→123**;
  graphvizJS + viewer compile. Additive — no geometry touched. This is the
  single most M8-practical fix in the tail: styling now flows end-to-end.
- **2026-05-29** — **SVG styling + gv emit-order interleave (`09-styled`
  probe).** Completes the M8 styling path end-to-end: the svg writer was
  black-on-none regardless of attrs, so styled diagrams rendered unstyled even
  though the layout was right. Added a hand-written styled probe (methodology
  §2.3), re-captured goldens (**verified all 8 existing goldens byte-identical**
  — only the new `09-styled/` + `_meta` timestamp changed; viz-js determinism
  confirmed). Ported gv node styling (`filled`⇒`fill`=`fillcolor`/`color`/
  `lightgrey`; `stroke`=`color`; text `fill`=`fontcolor`) and edge styling
  (`stroke`=`color`; `dashed`/`dotted`⇒`stroke-dasharray`; arrowhead in colour).
  The probe also surfaced a **latent emit-order bug**: gv interleaves nodes and
  edges (each edge emitted right after its head node is introduced —
  `a1,b3,a1→b3,…`), while the port emitted all nodes then all edges. Only
  latent because SvgSpec parses structurally; `09`'s byte-exact gate caught it.
  Refactored `Svg.svg` into the gv node/edge traversal. **`09-styled` svg
  byte-exact**; unstyled corpus (01/06/07/04/08) byte-identical. Suite
  **123→126**; graphvizJS + viewer compile. Styled diagrams now render styled.
- **2026-05-29** — **`rank=same` layout — byte-exact, the last common
  *layout* feature.** Ported `collapse_sets` (class1.c) as
  `Rank.rankConstraintLeader`: union-find merges each rank-constraint set to
  one representative, the network-simplex solve runs on the collapsed leaders,
  and leader ranks expand back to every member. Key design point — acyclic and
  the working edges `Order` builds its virtual chains from stay on the
  **original** nodes; only the NS solve collapses (Order needs `mid→b`, not
  `mid→leader`). Additive: identity without rank constraints. `11-ranksame`
  probe forces a real change (`a`: rank 1 → 2 to join `b`); **all of ranks,
  positions, `dot_json`/`json0`/`svg` byte-exact** first try — the collapse was
  the whole job, Order/Coord/XCoord placed the rest exactly. The probe also
  surfaced a latent bug: a rank-only `%N` subgraph must **omit** `label` unless
  `label` is a declared graph attr (`write_attrs` skip-empty-except-label over
  the root's attr dict) — 03's cluster-declared label still reaches its `%7`,
  but 11's doesn't. Suite **128→133**; graphvizJS + viewer compile. Remaining:
  the `min`/`max`/`source`/`sink` *extreme-rank* pin (merge done, no corpus).
- **2026-05-29** — **Root graph label (`do_graph_label`) — byte-exact
  (non-widening).** Cracked the geometry by probing three label widths through
  the oracle: `bb_width = max(node_extent, text_width + XPAD)` and lp.y =
  `GAP + labelBoxHeight/2`; `lheight`/`lwidth` are `%.2f`, `lp` is `%.5g`. For
  the common case (label narrower than the drawing ⇒ no re-centering): added
  `NodeSize.labelWidthPt`; `Output.bbox` now reclaims the label-reserved space
  Coord already shifts nodes by; `json0` emits `lp`/`lwidth`/`lheight` merged
  alphabetically into the root `write_attrs` stream; `Svg` renders the centered
  single-line label `<text>`. Snapped a sub-epsilon FP bb artifact (the
  `±pad` round-trip leaves ~1e-15 where gv has 0). `12-glabel` probe:
  `dot_json`/`json0`/`svg` **byte-exact**. Suite **133→138**; graphvizJS +
  viewer compile. ⬜ follow-ups (all understood, no blocker): label **wider**
  than drawing ⇒ global X re-centering (`bb=max(node,text+16)`, nodes shift
  `(Δ)/2`); multi-line + custom-fontsize graph labels; top `labelloc`.
- **2026-05-29** — **M8 real-diagram validation — the corpus-vs-reality gap,
  measured.** Ran the app's *shipped* example diagrams
  (`viewer/src/main/resources/examples/`) through the Scala facade **and**
  viz-js, structurally diffing node/edge sets. **Result: structure is correct
  everywhere.** data-structures (records), finite-state-machine (LR + edge
  labels), groups (clusters), html (HTML labels), logo (styling), shapes (60
  exotic shapes) all **MATCH** the viz-js node/edge sets — the parser /
  attribute resolver / subgraph-cluster model / emitters handle every real
  input; the known gaps (edge labels, HTML, LR, exotic-shape *rendering*) are
  geometry-only and don't corrupt structure. **The one hard finding:
  `sbt-project-dependencies` (36 nodes / 99 edges) HANGS** — pinpointed to
  `XCoord.xCoords` (`Rank`/`Order` finish; the aux-graph network simplex
  loops). A **non-termination robustness bug** that would freeze the viewer,
  completely invisible to the small oracle corpus. This reprioritizes the
  backlog: the NS hang jumps ahead of edge-labels/LR (a freeze is worse than a
  layout gap). Recorded in §6 risk register as **top priority**. Diagnostic
  harness was scratch (absolute paths + oracle side-dir) — removed after
  capturing the findings; suite stays 140/140.
- **2026-05-29** — **Fixed the XCoord network-simplex hang (M8's top
  finding).** Not an infinite loop — an O(V·(V+E))-per-pivot **blowup**: every
  cut value recomputed from scratch per tree edge (comment even flagged it:
  *"correctness over speed; the graphs here are tiny"*), a redundant
  `cutValue(leaving)` recompute in the search, and an O(V²) `propagateTight`.
  Fine for the 13-file corpus (tiny), ~10¹⁴ ops on a real 800-node aux graph.
  Ported `ns.c` `dfs_range` + `dfs_cutval`: rooted **low/lim** tree, all cut
  values in ONE O(V+E) postorder pass reusing children's values; low/lim
  subtree membership replaces the O(V²) `componentOf` in the entering search;
  `propagateTight` walks the tree adjacency (O(V+E)). **Cut values are
  byte-identical**, so pivot decisions / ranks / x-coords are unchanged —
  corpus **140/140 green**, no geometry moved. Perf: sbt-deps XCoord **∞ →
  3.7s → 0.49s** (the propagateTight O(V²)→O(V+E) fix was the 7.5×), 370 pivots
  (optimal, not capped); module-dependencies (213n) renders in 0.9s. Regression
  guard added (dense 400-node NS). **Note** a *separate* inefficiency surfaced:
  `renderFormats` recomputes the full layout ~7× (each of dot_json/json0/svg,
  and each consumer, re-runs Rank/Order/XCoord/Spline) — memoizing the layout
  per graph would cut sbt-deps' full render 2.7s → ~0.5s. Tracked, not a hang.
- **2026-07-06** — **Layout memoization — the ~7× recompute the NS-hang fix
  surfaced.** `renderFormats` emits dot_json/json0/svg, and each internally
  calls `Output.bbox` + `Spline`, so the pure per-graph stages (Rank NS,
  mincross, XCoord NS, Coord, spline routing) ran ~7× on one graph. Added a
  size-1 identity cache (`GraphMemo`, keyed by `RGraph` reference — same
  instance flows through a whole render) around `Rank.ranked`, `Order.order`,
  `Coord.rankY`, `XCoord.xSolve`, `Spline.splinesEx` (each split into a thin
  memoized entry + unchanged `*Impl`). Pure caching ⇒ **corpus 141/141
  unchanged**, byte-exactness untouched. `synchronized` compute makes
  concurrent *different*-graph access merely miss (never torn/wrong); a no-op
  on single-threaded Scala.js; only one graph's layout retained. Full 3-format
  render: sbt-project-dependencies **2.7s → 0.35s** (7.7×), module-dependencies
  **0.93s → 0.16s** (5.8×). Scoped to within-render redundancy — a re-render
  (new parsed `RGraph`) recomputes, which the viewer caches at its own level.
- **2026-07-06** — **"How far from done" audit + edge-label recon (un-blocks
  the last hard layout item).** M8 validation established structure is 100%
  correct on real diagrams; remaining work is **geometry/rendering** in 3
  buckets: (a) edge labels + rankdir=LR, (b) HTML-like table labels, (c) ~40
  exotic shapes (shapes.dot). Cracked (b→a): the edge-label vnode is
  **asymmetric** (`lp.x = edgeX + labelWidth/2`; edge routes through the left
  reference, label extends right by `labelWidth`) — derivable from `json0`
  `lp` probes, **no gv instrumentation**, and the SAME vnode that blocks LR
  ⇒ one focused port closes edge labels + LR together (§5.2 rankdir row). Honest
  completion estimate: **HTML labels is the one genuine long pole** (a parse +
  table-layout + render engine — its own sub-project; §6 already suggests a
  viz-js fallback flag); edge-labels/LR = one focused session; exotic shapes =
  bounded but many. Not one push; no architectural work left — all geometry.
- **2026-07-06** — **Edge labels — straight TB case byte-exact (increment 1).**
  Acting on the recon: `Spline.labelPositions` computes each labelled edge's
  `lp = labelVnode.x + labelWidth/2` at the mid rank (the vnode from
  `XCoord.solveAll`, keyed by g.edges index). `json0` emits `lp` (alphabetical,
  after `label`); `Svg` renders the edge label `<text>` at `lp` inside the edge
  `<g>` (same single-line baseline as node/graph labels). Probe `14-edgelabel`
  (`a→b[label]; b→c`): **dot_json/json0/svg all byte-exact.** Also fixed a
  **pre-existing Coord bug** the probe exposed: intermediate *virtual* ranks of
  a spanning/doubled edge get half-height 0.5 (`ND_ht=1`) — was 0, causing a
  systematic 1pt Y drift on any graph with a virtual-only rank (dense corpus
  never had one, so 01/05/06/07 unaffected — still byte-exact). Suite
  **143→145**; graphvizJS + viewer compile. ⬜ increment 2: the **asymmetric
  label vnode** (`rw = labelWidth` in XCoord/Spline) so the label reserves
  order-axis space against a rank neighbour — closes branches + rankdir=LR
  (finite-state-machine). Straight labels with no right-neighbour don't need it.
- **2026-07-07** — **Edge labels increment 2 — asymmetric label vnode (node
  positions byte-exact).** Ported `class2.c label_vnode`: a label vnode is
  asymmetric — `ND_lw = nodesep`, `ND_rw = labelWidth` (`dimen.x`; flip swaps
  with `dimen.y`) — so it reserves order-axis space and pushes its rank
  neighbour. `Coord.labelVnodeWidths` (vnode name → labelWidth) feeds asymmetric
  `rw`/`lw` into `XCoord.make_LR_constraints` (`rw(u)+lw(v)+nodesep`) and Spline
  bounds. **Safe by construction:** `rw==lw==half` for every non-label node, so
  the arithmetic is identical unless a label vnode is present ⇒ 01/06/07/14
  provably unmoved (corpus 145→148 green). `15-elbranch` (`a→b[WIDE]; a→c`):
  **node positions byte-exact** — `b`/`c` now spread to fit the label (was
  ignoring it). ⬜ increment 3: the label vnode's own x (`lp` off ~21pt) + the
  spline routing around it need `make_edge_pairs`'s label port-offset
  (position.c) — closes branch/LR `lp`+splines. Straight labels (14) already
  fully byte-exact.
- **2026-07-07** — **Edge labels increment 3 — diagnosed, hit a genuine
  `LR_balance` wall.** Measured 15-elbranch's rank-1 layout: the port's label
  vnode `__v0_1` lands at x=**27** (aligned with `b`), gv at ~**48.5**
  (`lp` 45.27 vs 66.73). Root cause is NOT a missing `make_edge_pairs` label
  offset (the first guess): the `a(63)→vnode→b(27)` straightening is a **flat
  optimum** over `[27,63]`, and the port's NS picks the *endpoint* while gv's
  **`LR_balance` centers** the vnode (nudged by the separation against the
  `a→c` virtual). So increment 3 = an `LR_balance` flat-optimum-centering
  subtlety **inside the correctness-critical aux-graph solve** — matching
  gv's exact position needs either an instrumented-gv aux-graph dump or a
  careful `LR_balance`/asymmetric-separation study. Deferred as a focused,
  well-rested task (rushing the NS silently risks the whole port's
  byte-exactness). Increments 1 (straight, byte-exact) + 2 (branch node
  positions byte-exact) stand; the residual is only the label *text* x + its
  spline on branch/LR edges.
- **2026-07-07** — **Edge labels increment 3 — aux-graph traced; residual
  RE-LOCATED from the NS to the spline router. Three faithful NS/aux fidelity
  fixes landed (corpus-neutral, 148/148).** Went back into increment 3 with
  the gv 13.0.1 source (`/Users/jpablo/GitHub/graphviz-1301`, exact oracle
  version) instead of guessing. Read the real `ns.c:768 LR_balance`,
  `position.c:214 make_LR_constraints` + `:325 make_edge_pairs`,
  `class2.c:20 label_vnode`, and `mincross.c:1804` ω-table. **Landed three
  faithful ports** (all keep corpus byte-exact — the affected coords aren't in
  any golden, so they're fidelity-only today):
  1. **`LR_balance` rewrite** — replaced my component-shift-**with-revert-hack**
     by gv's exact `rerank(down-node subtree, ±δ/2)` (down node = smaller
     `ND_lim`); gv needs no feasibility revert because the direction guarantees
     the tight edge loosens (0→δ/2) and `f` tightens (δ→δ/2). Strictly better
     code even if corpus-neutral.
  2. **Odd-rank `nodesep=5`** (`position.c:226`, `sep[i&1]`): with edge labels,
     gv shrinks same-rank separation on the odd (label) ranks 18→5. Was missing.
  3. **`ROUND` not `ceil`** for aux minlens (`make_aux_edge`, `:190`).
  **Instrumented my own aux graph** for `15-elbranch` (temp NS stderr trace):
  weights come out `a→v0=1`, `v0→b=2` — **byte-exact to gv's ω-table**
  (`table[ORD][VIRT]=C_EE=1`, `table[VIRT][SING]=C_VS=2`); the asymmetry is
  real, not a bug. The port's x-solve reaches the **global weighted-length
  optimum** (objective 72: `v0=b=27`, `v1=c=99`). The spline waypoints I'd been
  reading as gv's vnode centres (`48.46`, `85`) imply objective ~107 — **higher
  than optimal**, which a correct NS + cutvalue-0-only `LR_balance` can't
  produce. ⇒ **those are Bézier control points offset by the label/spline
  router (`dotsplines`), not the NS coordinates.** So the increment-3 residual
  (branch/LR `lp` ~21pt off + its spline) is **not in the aux-graph solve at
  all** — the solve is optimal — it's in the spline-around-label offset stage.
  Closing it byte-exact still needs an instrumented-gv `ND_coord.x` dump to
  separate solve-position from spline-offset; that's the heavy path. Increments
  1 (straight, byte-exact) + 2 (branch node positions byte-exact) + these three
  fidelity fixes stand. Net: the earlier "flat-optimum in `LR_balance`"
  diagnosis is **superseded** — the NS is faithful and optimal; the residual
  moved one stage downstream.
- **2026-07-07** — **Convex builtin polygon shapes (12 shapes, byte-exact).**
  Ported `poly_init` vertex generation + sizing (shapes.c) into a new
  `Polygon` module: SQRT2 fit-in-ellipse → `1/cos(π/sides)` fit-in-polygon
  inflation → sector-angle vertex gen (with distortion/skew/orientation) →
  re-derive final bb from vertex extents → rescale. `NodeSize` routes
  diamond/triangle/invtriangle/trapezium/invtrapezium/parallelogram/pentagon/
  house/invhouse/hexagon/septagon/octagon through it (shared `polyMetrics`
  front-end so the layout size and the drawn vertices stay consistent); `Svg`
  draws periphery-0 vertices as a `<polygon>` (translate by node centre, negate
  y, close). **10 single-node probes (16–25) byte-exact** on dot_json + svg —
  covers orientation-45 (diamond), 180 (inv\*), odd sides (triangle),
  distortion (house −0.64, trapezium), skew (parallelogram 0.6), plain n-gons
  (pentagon/hexagon/octagon). Three output-layer fixes fell out and are gated
  by the new probes:
  1. **dot_json omits empty `"edges"`** — gv drops the array entirely for an
     edgeless graph (was always emitting `"edges": []`).
  2. **bb quantization = `ROUND`, not floor/ceil** — gv ROUNDs each GD_bb
     corner (dot_json) and `ROUND(pageSize+2·margin)` the svg canvas
     (emit.c:1288). floor/ceil only ever matched because no prior corpus max
     had a fractional in (0, .5); triangle 61.291 → 61 (not 62) exposed it.
  3. **snap near-integer bbox extents** — a polygon size through sqrt/trig
     carries ~1e-13 noise; harmless except when it straddles the rounding
     boundary (house maxY 36.0000001). Snap-to-nearest-int within 1e-6 keeps
     genuine fractionals (triangle 49.6) untouched. Suite: graphvizJVM 178,
     viewer 42, JS compiles. ⬜ deferred: edges INTO polygons (poly_inside /
     poly_path clipping), special-option shapes (note/tab/folder/box3d/
     cylinder, SBOL set, star), circle/square regular variants beyond ellipse/
     box, Mdiamond/Msquare/Mcircle diagonals, peripheries≥2.
- **2026-07-07** — **Edges into polygon nodes: `poly_inside` clip (byte-exact).**
  Ported `poly_inside` (shapes.c) so edge splines clip to the polygon boundary
  instead of the ellipse/box fallback. `Polygon.init` now also emits the
  **outline** periphery (periphery-0 pushed out by penwidth/2 along each vertex
  bisector — `poly_init`'s peripheries/outline loop); `Spline.insideFn` gains a
  polygon branch using `same_side` (shapes.c:371) against that outline: a point
  is inside ⇔ it's on the node-centre's side of every outline edge (order-
  independent, so the full loop equals gv's optimised segment walk bit-for-bit;
  the `≥0` half-plane test matches so the bezier binary-clip converges to gv's
  exact endpoint). Two probes byte-exact: **26-diamondedge** (vertical clip
  through a diamond vertex, −71.7) and **27-polymix** (triangle→hexagon +
  triangle→diamond branch — angled clips on slanted outline edges). Guarded by
  `Polygon.descOf` so ellipse/box clipping is untouched (corpus 178→183 green,
  JS compiles). ⬜ still deferred: non-default penwidth outline, LR/flip vertex
  rotation, `poly_path` box routing (only matters for multi-segment splines
  through a polygon's port), special-option shapes.
- **2026-07-07** — **`doublecircle` (ellipse peripheries=2, byte-exact).**
  `ShapeKind` gains a `peripheries` count (doublecircle ⇒ 2); `nodeSize` grows
  the final bb by `2·GAP·(peripheries−1)` (poly_init ellipse branch: bb → 2·P
  with `P += GAP` per ring); `Svg` draws `peripheries` concentric ellipses from
  the inner label-fit ring outward (18, 22). `NodeSize.peripheriesOf` resolves
  the shape default + explicit `peripheries` attr. Edge clipping needs no change
  — the node size already IS the outer ring, so the ellipse `insideFn` clips
  there. **28-doublecircle** byte-exact (svg + dot_json), corpus 183→186, JS
  compiles. ⬜ deferred: polygon peripheries (doubleoctagon/tripleoctagon — the
  bisector-offset ring draw), filled multi-periphery fill rules.
- **2026-07-08** — **HTML-like labels: parser + text labels (byte-exact).**
  New `html` package: `HtmlParser` (XML-ish tokenizer + recursive parse →
  `HtmlLabel` = text block or table; handles `<b>/<i>/<u>/<s>/<sub>/<sup>/
  <font>` styling, `<br/>`, entities, `<table>/<tr>/<td>`) and `HtmlLayout`
  (`size_html_txt` port). The parser's `html` flag is now preserved through
  `Attrs` (`htmlKeys` set, `isHtml`) from the already-existing `idHtml` lexer
  rule. `NodeSize` sizes an HTML text label from its parsed content box, then
  reuses the SAME poly_init PAD+fit — so `<hello>` is byte-identical to
  `"hello"` (confirmed: both rx=30.35). `Svg` renders HTML text left-anchored
  (`text-anchor=start`) with per-run font attrs; the baseline reuses the
  quoted-label placement, minus 1pt when the block is **non-simple**
  (`size_html_txt` `simple` flag: bold/italic/mixed-font sets
  `yoffset_centerline=1`, emit_htextspans/svg_textspan:453). Also: dot_json
  omits an empty `edges` array — already handled — and escapes `/`→`\/`.
  Probes byte-exact (svg + dot_json): 30-htmltext `<hello>`, 31-htmlbold,
  32-htmlitalic, 33-htmlfont (`<font color>`), 34-htmlmulti (`<br/>`
  multi-line). Suite: graphvizJVM 195, JS compiles. ⬜ next: table layout
  (`size_html_tbl`/`pos_html_tbl`) — parser already builds the table tree.
- **2026-07-08** — **HTML-like labels: table engine (byte-exact).** Ported
  `size_html_tbl` / `size_html_cell` / `pos_html_tbl` / `pos_html_cell`
  (htmltable.c) into `HtmlTableLayout`: cell size = content + 2·(cellpadding +
  cellborder); table box = (ncols+1)·spacing + 2·border + Σ colWidths (and the
  row analogue); grid positions place each cell box, content centred in the
  border+pad inset. `Svg.htmlTable` draws cell borders + centred content
  (recursing for nested tables), then the outer table border last (gv emit
  order); cell text reuses `htmlText` centred at the cell's content box. Also:
  `plaintext`/`none`/`plain` now draw **no shape border** (Svg) and clip edges
  as a **box** with no penwidth (Spline `insideFn`; they are `sides=4`,
  `peripheries=0`). Probes byte-exact (svg + dot_json): 35-htmltable1 (single
  cell), 36-htmltable2 (2×2 grid), 37-htmltableel (table inside the default
  ellipse), 38-htmltableedge (two plaintext table nodes + a clipped edge).
  Suite: graphvizJVM 213, viewer 42, JS compiles. HTML labels now cover a
  byte-exact core: text (plain/bold/italic/font/multi-line) + tables
  (single/multi-cell, on-ellipse, edge-connected). ⬜ deferred: colspan/rowspan,
  cell `bgcolor`/`align`/`valign`/`port`, `<img>/<hr>/<vr>`, `sub`/`sup`
  vertical offset, non-default per-cell fonts inside tables.
- **2026-07-08** — **HTML labels: cell/table `bgcolor` + `border=0` (byte-exact).**
  `Svg.htmlTable` now fills a table-bgcolor polygon behind the cells and a
  per-cell bgcolor polygon (fill only, no stroke) before each cell border
  (gv emit order). `border=0`/`cellborder=0` already fell out of the layout
  (cell border 0 ⇒ no polygon, table border 0 ⇒ no outer box; the size uses the
  smaller insets). Probes byte-exact: 39-htmlbgcolor (`<td bgcolor>`),
  40-htmlnoborder (`border=0 cellborder=0`). Suite: graphvizJVM 219, JS
  compiles. ⬜ deferred: cell `align`/`valign` (TD attr → span alignment),
  colspan/rowspan, `<img>/<hr>/<vr>`, `sub`/`sup` offset, gradient fills.
- **2026-07-08** — **HTML labels: cell `align` (byte-exact).** `HtmlSpan.align`
  is now `Option` (`None` inherits); `htmlText` takes an explicit `alignWidth`
  (the cell content area, or the text box for a standalone label) + a default
  align, and justifies each line within it (left = area left, right = area
  right − line, centre = line-centred) — matching `emit_htextspans`. Cell text
  inherits the `<td align>` attr. Probe byte-exact: 41-htmlalign (a narrow
  `align=left` cell in a column sized by a wider sibling → content pinned to the
  area's left edge). Suite: graphvizJVM 222, viewer 42, JS compiles. **HTML
  labels now cover a broad byte-exact core** (24 gated cases): text
  (plain/bold/italic/font/multi-line), tables (single/multi-cell, nested,
  on-ellipse, edge-connected), bgcolor, border variants, and cell alignment.
  ⬜ deferred: colspan/rowspan, valign, cell `port`, `<img>/<hr>/<vr>`, sub/sup
  vertical offset, gradient fills.
- **2026-07-08** — **HTML labels: colspan/rowspan (byte-exact).** Ported
  `processTbl`/`findCol` (grid assignment: cells placed row-major at the
  leftmost free column that fits the colspan, skipping rowspan-occupied cells)
  and `set_cell_widths`/`set_cell_heights` (CSS-style span distribution:
  single-span cells set the column/row minimum, then spanning cells widen their
  columns/rows evenly if wider than the span + internal spacing) into
  `HtmlTableLayout`. Cell boxes span `colStart(col+colspan)` /
  `rowStart(row+rowspan)`. Probes byte-exact (svg + dot_json): 42-htmlcolspan
  (`colspan=2` header over 2 cells), 43-htmlrowspan (`rowspan=2` side cell),
  44-htmlspanmix (rowspan + colspan together — `findCol` skips the
  rowspan-occupied column). Suite: graphvizJVM 231, viewer 42, JS compiles.
  HTML labels now cover **30 byte-exact gated cases**. ⬜ deferred: valign,
  cell `port`, `<img>/<hr>/<vr>`, sub/sup vertical offset, gradient fills.
- **2026-07-09** — **HTML labels: cell `valign` (byte-exact).** `Svg.htmlTable`
  now positions each cell's content box within its (taller) content area by the
  `<td valign>` attr: `top` ⇒ content top at the area top (contentCy =
  contentBox.ury − contentH/2), `bottom` ⇒ content bottom at the area bottom,
  `middle` (default) ⇒ centred. Content height comes from `HtmlLayout.size`
  (text or nested table). Probes byte-exact (svg + dot_json): 45-htmlvaligntop,
  46-htmlvalignbot (an `x` cell beside a 3-line `a/b/c` cell → baseline shifts
  to the area top/bottom). Suite: graphvizJVM 237, viewer 42, JS compiles. HTML
  labels: **34 byte-exact gated cases**. ⬜ deferred: cell `port` (edge → cell),
  `<img>/<hr>/<vr>`, sub/sup vertical offset, gradient fills, row/table-level
  valign inheritance.
- **2026-07-09** — **HTML labels: cell `port` (byte-exact TB).** Edges can now
  target a specific `<td port="name">`. `HtmlTableLayout.cellPortBox` looks up
  the port cell's box; `PortAnchor.resolve` falls back to it for HTML nodes;
  `Spline.htmlPortEnd` routes the endpoint like the record **dyna** port — pick
  the cell side closest to the other endpoint, aim at its midpoint with the ±1
  begin/endpath nudge and a constrained tangent (`clip=false`). Since the table
  is centred on the node, the cell box doubles as the node-local field box.
  Probes byte-exact (svg + dot_json): 47-htmlport (`a:p1 -> b`, tail exits the
  cell bottom → (26.5, −78)), 48-htmlporthead (`a -> b:p2`, head enters the cell
  top). Record ports unaffected (guarded on `isHtml`). Suite: graphvizJVM 243,
  viewer 42, JS compiles. HTML labels: **38 byte-exact gated cases**. ⬜ deferred:
  compass on cell ports (`:p:n`), non-TB port sides, nested-table cell ports,
  `<img>/<hr>/<vr>`, sub/sup offset, gradient fills.
- **2026-07-10** — **HTML labels: compass on cell ports (byte-exact, natural
  dirs).** `Spline.htmlPortEnd` now honours a compass on a cell port
  (`a:cell:n`): compassPort maps `n/s/e/w` + corners to the exact cell edge
  point + outward tangent, plus the 1-unit begin/endpath nudge and a constrained
  tangent (`clip=false`); `c`/`_`/absent fall back to the dyna closest-side.
  Probes byte-exact: 50-htmlports (`a:p1:s -> b`, tail exits cell bottom),
  49-htmlportheadn (`a -> b:p2:n`, head enters cell top). ✅ the compass POINT
  resolves for all 8 directions; the SVG spline is byte-exact for the **natural**
  directions (tail-south / head-north — no obstacle). ⬜ against-grain compass
  (e.g. tail-north, which loops around the node) resolves the point but needs
  node-as-obstacle routing for the byte-exact loop; `e/w` on internal cells
  likewise route around. Suite: graphvizJVM 249, viewer 42, JS compiles. HTML
  labels: **42 byte-exact gated cases**. ⬜ still deferred: obstacle-routed
  compass, nested-table cell ports, `<img>/<hr>/<vr>`, sub/sup offset, gradients.
- **2026-07-10** — **HTML labels: sub/sup, nested-cell ports, hr/vr, gradient
  fills (byte-exact).** Cleared the tail:
  - **sub/sup** — `<sub>`/`<sup>` emit `baseline-shift="sub"|"super"` (same font
    size + baseline; the non-simple −1pt offset already applied). 51-htmlsubsup.
  - **nested-table cell ports** — `cellPortBox` now recurses into nested tables,
    offsetting the found inner-cell box by the accumulated content-box centres.
    Resolution gated by a unit test (inner centred cell ⇒ centre ≈ 0); the
    spline THROUGH the nested structure isn't byte-exact (routing class).
  - **`<hr/>`/`<vr/>`** — parser tracks row/column rule boundaries; layout emits
    the rule y/x (`rowStart(b)+space/2`, `colStart(b)−space/2`); Svg draws the
    degenerate black polygon (HR full table width; VR full height minus the
    bottom gap). 52-htmlhr, 53-htmlvr.
  - **gradient bgcolor** — `bgcolor="c0:c1"` emits a `<defs>` linearGradient
    (left→right across the box, doc-wide `l_N` id) + `fill="url(#l_N)"`; solid
    otherwise. Table + cell via a shared `bgFill`. 54-htmlgradient.
  Suite: graphvizJVM 262, viewer 42, JS compiles. HTML labels: **51 byte-exact
  gated cases** + the nested-port resolution unit test. ⛔ genuinely deferred
  (not isolated adds): obstacle-routed edges (against-grain compass loops, `e/w`
  on internal cells — needs general spline obstacle avoidance) and `<img>`
  (external image files — can't gate byte-exact). The HTML label engine is
  otherwise complete.
- **2026-07-10** — **Obstacle-routed edges: against-grain compass port
  (byte-exact).** An against-grain TOP-side tail port (`a:cell:n -> b` with b
  BELOW) exits the cell top and must loop around the node. Ported `beginpath`'s
  TOP construction (splines.c:419): three boxes — `b0` above the node, `b` down
  the go-left/right side (chosen by port.x vs node.x), and the `maximal_bbox`
  clamped copy (make_regular_edge) — threaded into the port box channel via
  `End.top/portY/goLeft` + a `topBoxes` builder. Turned out to be **purely a
  spline problem** (the layout shift — node a x=27→34 to make room — already
  falls out of the PortAnchor x-coord offset). Two fixes closed it byte-exact:
  the 3-box channel, and **dropping boxes that `checkpath`'s overlap-repair
  collapses to zero area** (the clamped maximal_bbox fully overlaps the side box
  → degenerate → `buildPolygon` was pinching the channel and rejecting the
  correct left-bulge spline). 55-htmlporttailn byte-exact. Records + all
  existing edges unaffected (topBoxes only fires for against-grain compass; the
  degenerate-filter is a no-op when no box collapses). Suite: graphvizJVM 265,
  viewer 42, JS compiles. HTML labels: **52 byte-exact gated cases**.
  ⬜ remaining: `e/w` on internal cells (same obstacle mechanism, LEFT/RIGHT side
  box), `<img>` (external files — out of scope).
- **2026-07-10** — **HTML labels: `<img>` + FIXEDSIZE cells (byte-exact
  layout).** A `FIXEDSIZE="TRUE"` cell's box IS its `WIDTH`×`HEIGHT` (points) —
  the content size is ignored (`size_html_cell` sets sz=0 for a fixed cell), so
  an `<img>` cell lays out byte-exact from the DOT alone (no file read needed;
  viz-js emits no `<image>` without the file either). `HtmlParser` now tolerates
  `<img>` (contributes no text); `HtmlTableLayout` sizes a fixed cell to
  width×height. Probes byte-exact (svg + dot_json): 56-htmlimg (single fixed img
  cell 50×40), 57-htmlimgmix (fixed img cell + text cell). Suite: graphvizJVM
  271, viewer 42, JS compiles. HTML labels: **54 byte-exact gated cases**.
  ⛔ genuinely out of scope: the image FILE render (`<image>` element needs the
  external file's pixel dimensions — an I/O dependency); `e/w` compass on an
  *internal* cell (gv resolves the blocked-side port through internal-cell
  special-casing that diverges from the documented side construction).
- **2026-07-10** — **Raster image render: `<image>` element for HTML `<IMG>`
  cells (byte-exact).** Reframed the previously-"out of scope" image FILE render:
  graphviz can't read the file in the sandbox, but it doesn't need to — the
  natural size is caller-supplied metadata (viz-js's `images` render option),
  and only *that* triggers an `<image>` element (probed: no `images` option ⇒ no
  `<image>`, ever). Modelled the metadata as `RGraph.images: Map[src→ImageDim]`
  (mirrors the option), threaded through `HtmlLayout`/`HtmlTableLayout` sizing
  and `Svg`. Derived rules from gv source + oracle: the drawn box = `(int)(natural
  × 72/96)` — **truncated** to whole points because `size_html_img` stores an
  integer `box` (a 50pt image ⇒ 37pt, not 37.5); the img cell's content = that
  drawn box, then standard plaintext-HTML node sizing (`max(54,dw+2·7.92) ×
  max(36,dh+2·3.96)`, ROUND-quantized) wraps it — the same path text/table
  labels already use. The `<image>` attrs (`gvloadimage_core.c`: `width=UR.x−LL.x`,
  `height=UR.y−LL.y`, `x=LL.x`, `y=−UR.y`) print via C `%g` (6 sig-figs, strip
  zeros) — a new `g6` formatter, distinct from the `%.2f` `gvprintdouble` used
  for path/polygon coords. Parser now yields `HtmlLabel.Image(src, scale)` for an
  img-only cell / bare-image label. Capture harness reads a `<name>.images.json`
  sidecar (fresh viz instance per image file — viz-js caches sizes by name).
  Byte-exact (svg + dot_json): 58-htmlimgnat (natural 72×36 cell), 59-htmlimgrow
  (img + text cells in a row), 60-htmlimgfrac (50×34 ⇒ truncation to 37×25).
  Suite: graphvizJVM 280, viewer 42, JS compiles. HTML labels: **57 byte-exact
  gated cases**. ⬜ remaining image work: `SCALE="TRUE"` aspect-fit + centering
  (image smaller than a FIXEDSIZE content box — exercises `g6`'s fractional
  path, e.g. `26.4`/`-40.2`) and the node-level `image=`/`shape=image` attribute
  (ellipse/box fit via `poly_inside`, the `x="3.51219"`-style coords).
- **2026-07-10** — **HTML `<IMG SCALE="TRUE">` aspect-fit + centre (byte-exact).**
  Ported `gvrender_usershape` (gvrender.c:670): the target box starts as the cell
  content box; `SCALE="TRUE"` fits the image preserving aspect using the *smaller*
  of the two axis scales (`s = min(pw/iw, ph/ih)`), then centres it (imagepos
  "mc") in whichever axis it ends up smaller (`b.LL += (p−i)/2`, `b.UR −= …`).
  Key realisation: the fit is **invariant to the image's absolute unit** — the
  `iw` factor cancels in `iw·min(pw/iw, ph/ih)` — so the raw natural pt size
  feeds the fit directly (no 72/96 DPI factor, and *not* the truncated drawn
  size, whose truncation would break proportionality: `nat54 → 40 ≠ 40.5`). This
  generalised the increment-1 emit (default/FALSE still = content box, correct
  when the image ≥ the box, i.e. every natural/non-fixed cell). Byte-exact (svg +
  dot_json): 61-htmlimgscale (72×36 in a 50×40 cell ⇒ 44×22), 62-htmlimgscalefrac
  (20×60 tall image ⇒ 14.6667×44 @ 38.6667 — the fractional `g6` path), 63-
  htmlimgscaleup (40×40 enlarged to 54×54, horizontally centred @ x=24). Suite:
  graphvizJVM 289, viewer 42, JS compiles. HTML labels: **60 byte-exact gated
  cases**. ⬜ remaining image work: node-level `image=`/`shape=image` (ellipse/box
  fit via `poly_inside`, `x="3.51219"`-style coords); `SCALE=WIDTH/HEIGHT/BOTH`
  and non-centre `imagepos` (single-axis scaling — the fit no longer unit-cancels,
  so these need the exact `gvusershape_size_dpi` value first).
- **2026-07-10** — **Node-level `image=` / `shape=image` (box, byte-exact).** A
  node's `image=` grows its bb to hold the image: `bb = max(labelbox, drawnimage
  + 2)` (shapes.c poly_init, `imagesize += 2` fixed padding; drawn = `(int)(nat ×
  72/96)` as the cell path). The image is then placed by `gvrender_usershape`
  against the *full node box* with SCALE default FALSE (natural size, no scaling)
  and centred (imagepos "mc") wherever it ends up smaller — factored into a
  shared `usershapeImage` helper the HTML `<IMG>` path now also uses (so a small
  FALSE image in a fixed cell centres correctly too, not just fills). `shape=
  image` is classified + drawn as a box (border + image + centred label — matches
  the oracle, which draws the border). Scoped to box-family shapes: an ellipse
  must *contain* the image via a SQRT2 poly_inside (the `x="3.51219"`-style
  fractional coords) — deferred. Byte-exact (svg + dot_json): 64-nodeimage
  (100×60 fills a 77×47 node), 65-nodeimagebox (40×40 centred-x in a 54×36 min
  node ⇒ 40×36 @ x=7), 66-nodeimagewh (explicit 1×1in ⇒ 40×40 centred both axes @
  16,−56). Suite: graphvizJVM 298, viewer 42, JS compiles. New `NodeImageSpec`.
  ⬜ remaining: ellipse + `image=` (SQRT2 containment); `SCALE=WIDTH/HEIGHT/BOTH`
  + non-centre `imagepos`.
- **2026-07-10** — **Ellipse / circle `image=` (SQRT2 containment, byte-exact).**
  Turned out to be almost entirely un-gating what already existed: the image
  size folds into the node bb (previous entry, now for all shapes), and the
  ellipse branch already inflates that bb ×SQRT2 to *contain* it. The image is
  then placed by the same `usershapeImage` helper against the node's *bounding
  box* (`2rx × 2ry`) at natural size, centred — so the `x="4.44722"`-style
  fractional coords are just `(2rx − natW)/2` with `2rx = (drawn+2)·SQRT2`, and
  the `%g` (`g6`) decimals finally get exercised on real geometry. Only two
  edits: drop the box-only guard on the size fold, and broaden the emit to
  ellipse-family (`!borderless && no poly desc`). Byte-exact (svg + dot_json):
  67-ellipseimage (100×60 in a 108.9×66.5 ellipse ⇒ image @ 4.44722,−63.234),
  68-circleimage (regular ⇒ 4.44722,−84.4472), 69-ellipseimagesm (40×40 ⇒
  7,−42.6274). Suite: graphvizJVM 307, viewer 42, JS compiles. Image support now
  covers HTML `<IMG>` cells (natural + SCALE=TRUE) and box/ellipse node `image=`.
  ⬜ remaining (narrow): convex-polygon `image=` (diamond/…), `SCALE=WIDTH/HEIGHT/
  BOTH` + non-centre `imagepos` (single-axis scaling needs the exact
  `gvusershape_size_dpi` 96-dpi value the aspect-preserving paths let us skip).
- **2026-07-10** — **`SCALE=WIDTH/HEIGHT/BOTH` (byte-exact).** Completed the
  `gvrender_usershape` scale matrix in `usershapeImage`: `WIDTH` fills the box
  width and keeps natural height, `HEIGHT` the converse, `BOTH` fills both (no
  aspect). The "needs the exact 96-dpi isz" worry dissolved — the node FALSE
  cases already pinned `isz = natural pt` (72 dpi ⇒ the pt value), so the
  single-axis modes just use `natW/natH` directly. Each still centres in the
  unfilled axis where the image is smaller. Byte-exact (svg + dot_json):
  70-htmlimgwidth (72×20 in a 54×44 box ⇒ 54×20 @ 14,−42, centred-y),
  71-htmlimgheight (30×24 ⇒ 30×44 @ 26,−54, centred-x), 72-htmlimgboth (⇒ 54×44
  fills). Suite: graphvizJVM 316, viewer 42, JS compiles. HTML labels: **63
  gated cases**. Image `SCALE` is now complete (FALSE/TRUE/WIDTH/HEIGHT/BOTH).
  ⬜ remaining: convex-polygon `image=` (diamond/…, poly_inside box); non-centre
  `imagepos` (tl/tr/bl/br/… — the only uncovered `gvrender_usershape` branch).
- **2026-07-10** — **`imagepos` non-centre placement (byte-exact) + empty-label
  fix.** The last `gvrender_usershape` branch: `imagepos="<v><h>"` (v∈t/m/b,
  h∈l/c/r, default `mc`) positions a smaller-than-box image in a corner/edge
  instead of centring — applied per-axis only where the image is smaller.
  Threaded a `pos` arg into `usershapeImage` (HTML cells stay `mc`, hardcoded by
  `emit_html_img`; nodes read the `imagepos` attr). Surfaced + fixed a latent
  bug: `label=""` was emitting an empty `<text></text>` — `emit_label` draws
  nothing for an empty label, so `Svg` now guards on `lbl.nonEmpty`. Byte-exact
  (svg + dot_json): 73-nodeimgpostl (@0,−144), 74-nodeimgposbr (@104,−40),
  75-nodeimgpostc (@52,−144). Suite: graphvizJVM 325, viewer 42, JS compiles.
  `gvrender_usershape` is now fully covered (all SCALE modes + all 9 imagepos).
  ⬜ remaining: convex-polygon `image=` (diamond/…) — the only image case left,
  needs the polygon's `poly_inside` box instead of the bounding box.
- **2026-07-10** — **Convex-polygon `image=` (byte-exact) — image feature
  complete.** `gvrender_usershape` gets `AF` = the innermost-periphery *vertices*
  and bounding-boxes them, so the placement box is the vertex bbox — which for
  box/ellipse equals the node box (my earlier shortcut) but for a triangle sits
  asymmetrically inside it (162 of 216 pt tall). Fixed by taking the box from
  `NodeSize.polygon(n,g).vertices` when the shape is a convex polygon, node box
  otherwise. Byte-exact (svg + dot_json): 76-diamondimage (symmetric ⇒ node box),
  77-triangleimage (vertex bbox ≠ node box ⇒ image @ 88,−155). Suite: graphvizJVM
  331, viewer 42, JS compiles. **Raster image support is now complete**: HTML
  `<IMG>` cells and node `image=`/`shape=image` across all shapes (box, ellipse,
  every convex polygon), all SCALE modes (FALSE/TRUE/WIDTH/HEIGHT/BOTH), and all
  9 `imagepos` placements. ⛔ genuinely out of scope: `shape=custom`/`shapefile`
  (external PostScript/shape files); a borderless `shape=none` + `image=` (no
  gated case — rare); actual raster *pixels* (the `<image>` refs the file by
  name — a browser/consumer concern, never the renderer's).
- **2026-07-11** — **rankdir LR order axis: instrumented gv, ~30pt → ≤6pt (7/8
  canonical nodes byte-exact).** Built instrumented gv 13.0.1 (§2.5 recipe) and
  dumped the true canonical layout + X-solve for 02. Two gv-faithful fixes:
  (1) `Order` reverses each rank's BFS order when the graph is flipped
  (`build_ranks`, mincross.c:1334) — this, not the label vnode, is what mirrors
  the LR order axis; (2) `Coord` swaps the label vnode's `rw`/`ht` for a flipped
  graph — `rw = dimen.y` (label height), `ht = dimen.x` (label width), `lw =
  nodesep` (class2.c `label_vnode`). Both fire only for flipped/edge-label graphs
  ⇒ the whole TB corpus stays byte-exact (RankSame/XCoord/Coord/Spline all green,
  331 JVM + 42 viewer). gv's real X-solve (`start=46 middle=46 end=12 chains=0
  __v1_3=35`) now matches mine on 7/8 nodes. The recon (2026-07-06) was wrong: it
  blamed the label vnode `rw` and missed the `build_ranks` flip. ⬜ last ~5pt is
  the `end` node: gv's `LR_balance` (ns.c:768) iterates `Tree_edge` in tight-tree
  DFS order and does δ/2 reranks; my Set-based tree iterates by index → a
  different entering-edge slack (35 vs 25). Reverse-index closes 02 but breaks
  11-ranksame — so a faithful close needs gv's feasible-tree construction order
  (deeper NS change). gv worktree reverted to pristine; `_dbgbuild` removed.
- **2026-07-11** — **NetworkSimplex re-transcribed 1:1 from `ns.c`; 02-LR CLOSED
  byte-exact + node-order audit.** Root-cause reframe (user's insight): the port
  had *reimplemented* the order-sensitive core idiomatically (a `Set`-based tree,
  per-pivot cutvalue recompute, index-scan feasible tree) instead of transcribing
  gv — so every order tie (02's `end`, 11's `top`) needed instrumentation to
  reverse-engineer. Fix: **transcribe, don't reimplement.** `NetworkSimplex` is
  now a faithful port of `ns.c` — subtree-merge `feasible_tree` (size min-heap +
  union-find), the `leave_edge`→`enter_edge`→`update` pivot loop with incremental
  cut values (`treeupdate`) and low/lim (`dfs_range`), ordered `Tree_edge` list,
  `TB_balance`/`LR_balance`. That alone closed 02 by construction (order axis
  ≤6pt → **0.0**). It then exposed the same debt one level up, which the audit
  fixed in `XCoord`: (a) the NS node order must be gv's `GD_nlist` = `decompose`'s
  DFS (decl-order seeds, out-edges first) with slack nodes prepended; (b)
  `make_edge_pairs` creates slacks per-node (GD_nlist order), not per-segment;
  (c) the aux graph is seeded with gv's `make_LR_constraints` initial ranks
  (feasible ⇒ gv skips `init_rank`), and *that* seed decides which feasible_tree
  is built. Each was verified against instrumented gv (`NODE`/`AUX`/`XSOLVE`/
  `TREEEDGE`/`LRBAL` dumps). Result: **02-LR node positions byte-exact (both
  axes)** — RankDirSpec promoted from self-flagging deferred-probe to a STRICT
  gate — and **11-ranksame stays byte-exact**, now via the same faithful NS (no
  special-casing). Whole corpus unchanged: graphvizJVM 330, viewer 42, JS
  compiles. gv reverted pristine, `_dbgbuild` removed. ⬜ 02 full svg still needs
  LR spline routing + `lp` emission (separate); the *layout* is now exact.
- **2026-07-11** — **`Order`/mincross driver re-transcribed faithfully (audit
  follow-up).** Audit of `Order`/`Spline` found `Order`'s mincross *driver* was
  reimplemented (pass-0 init only; the header's own "simplified to pass-0"). Now
  a faithful port of `mincross` (mincross.c:745): the pass-0/1/2 driver with
  `save_best`/`restore_best`, **both** `build_ranks` passes (pass 0 = in-free
  seeds following out-edges, pass 1 = out-free following in), and seeds iterated
  in `decompose`/`GD_nlist` order (not declaration order). Corpus unchanged
  (graphvizJVM 330, viewer 42, JS): 01/07 stay byte-exact, no regression. **But
  it did NOT close 06's mirror** — diagnosis correction: 06's `b`/`c` order is a
  mirror because 06 has a *flat edge* (`b -- c`, same rank), and flat-edge
  handling (`flat_reorder`/`flat_breakcycles`/`flat_search` + the flat adjacency
  model) is a genuinely deferred FEATURE, not the idiomatic drift the audit was
  about. So the driver drift is closed; ⬜ 06 byte-exact now blocks on the
  flat-edge port (a bounded but feature-sized increment). `Spline` audit:
  label-free splines are Hausdorff/mirror-gated (values measure byte-exact);
  byte-exact svg IS enforced for HTML/record/image/rank; LR/flat/label spline
  routing unhandled — feature work, not drift.
- **2026-07-11** — **06's X-mirror CLOSED — the missing `build_ranks` tail
  transpose (mincross.c:1349). Prior flat-edge diagnosis was WRONG.** Instrumented
  gv 13.0.1 (§2.5) to dump `install_in_rank` order, `ND_out`/`ND_in`, flat-edge
  lists, and the rank order at every stage of `mincross`. Findings, in order: (a)
  gv's `build_ranks` install sequence for 06 is `[b, v_ac]` — **identical to
  ours**, and `ND_out(a)=[b, v_ac]` matches too, so the BFS is faithful; (b)
  `HAS_FLAT=0` with **zero flat edges** — 06 has NO flat edge (`b`/`c` are
  different ranks; the previous entry's "`b--c` same rank" was a
  pattern-match error, not read from gv); (c) yet gv's rank 1 comes out `[v_ac,
  b]`. Pointer-tagged probes pinned the swap to a single call **inside**
  `build_ranks`, after the install + `GD_flip` loop: `if (g == dot_root(g) &&
  ncross > 0) transpose(g, false)` (mincross.c:1349-1350). The fresh BFS install
  leaves 1 crossing; this tail transpose removes it → `[v_ac, b]`, and since
  `cur_cross` is then 0 the driver's iteration loop breaks immediately. Our
  `buildRanks` installed and returned **without** this transpose, seeding the
  driver with a mirror-equivalent (also-0-crossing) order whose later median
  passes settled to the opposite tie-break — exactly 06's X-mirror. **Fix:** one
  line — `if ncross > 0 then transpose(false)` right after `buildRanks(pass)` in
  the driver (semantically identical to gv's in-`build_ranks` placement; moved to
  the driver only to avoid a Scala forward-reference over `val mval`). 06 is now
  **byte-exact**: node X `a=54,b=82,c=27,d=54` == golden, `directDev=0.0000`, no
  mirror. **Gates promoted to strict (mirror allowance removed):** `XCoordSpec`
  (direct golden X), `SplineSpec` (identity-only Hausdorff), `OutputSpec` json0
  (direct node + spline dev, dropped the `mir` escape), `OrderSpec` (exact
  within-rank order incl. 02-LR). Whole label-free TB corpus + 02-LR now match
  the oracle order/geometry **directly**. graphvizJVM 330/330 green. Lesson
  (reinforces [[transcribe-not-reimplement]]): a two-line tail of a C function is
  still part of the function — omitting it is transcription drift, and it
  presented as a "missing feature" (flat edges) under pattern-matching. gv
  worktree reverted pristine; `_dbgbuild` removed.
- **2026-07-11** — **15-elbranch edge-label lp + spline byte-exact — ported the
  `maximal_bbox` label clamp + `recover_slack`/`resize_vn`.** 15 (`a→b[WIDE]`;
  `a→c`) had byte-exact node positions + dot_json but its edge `lp` was off by
  labelWidth/2 and the a→b spline was 1 cubic where gv bows 2. Instrumented gv
  13.0.1 (§2.5): dumped `install`/`maximal_bbox`/`recover_slack`/`place_vnlabel`
  and the pre/post-routesplines channel boxes for 14 + 15. Root cause, two gv
  steps never transcribed: (a) **`maximal_bbox`'s label-vnode clamp**
  (dotsplines.c:2276) — a label vnode routes its edge to the LEFT of the label,
  so the box right bound starts at `x+10` (not `x+rw`) and, after the neighbour
  clamp, `-= rw` (leaving the label its own strip): for 15 UR = round(38.5) −
  36.538 = 2.462, the narrow box that bends the spline into 2 cubics; (b)
  **`recover_slack`/`resize_vn`** (dotsplines.c:2126) — after each edge routes,
  every virtual node is snapped to the box it threads: a label vnode to that
  box's RIGHT edge, a plain vnode to its centre; `place_vnlabel` then puts
  `lp = snapped_x + labelWidth/2`. This is **order-dependent** — a→b routes
  first and its snap makes a→c's box start *past* the label (LL = 39.0 = 2.462 +
  labelWidth), so a→c's spline is correct too. Ported both into `Spline`
  (`maximalBbox` label branch; a stateful `recoverSlack` mutating shared
  x/lw/rw between edges; `halfHt` now counts virtual/label vnodes so a
  pure-virtual rank has real box height). The snapped label x is read off the
  **byte-exact routed spline** (its on-curve point at the label-box top) rather
  than reproducing gv's `checkpath` box-collapse — additive, so 01/04/06/07
  splines are untouched. `EdgeLabel2Spec` now gates 15 json0 (lp + spline pos) +
  svg byte-exact; 14 (isolated label) + 12 (graph label) stay byte-exact.
  graphvizJVM 332/332, JS green. gv worktree reverted pristine; `_dbgbuild` gone.
- **2026-07-11** — **`map_point` transform WIRED into the output pipeline — 02-LR
  node pos + bb + lp byte-exact via the real writer.** The LR rotation had been
  a *test-only* computation (`RankDirSpec.finalLR`); `Output.json0` still emitted
  canonical (TB) coordinates for 02. Ported `postproc.c` `map_point` as
  `layout/DrawTransform`: `ccwrotate(p, rankdir·90°)` then subtract the min
  corner of the rotated canonical node-extent bbox (identity for TB — the whole
  corpus stays bit-exact). Wired it through `Output.json0`: node `pos`, edge
  `lp`, spline points, and a rotated-frame `finalBBox` all pass through `tf`.
  Result: 02's `bb` (0,0,249.88,70), all three node positions, and the `go` edge
  `lp` (79,60.4) are now **byte-exact through the actual pipeline** (RankDirSpec
  gate promoted). ⬜ Remaining for full 02: edge spline `pos` under LR is *close*
  but not exact (span-1 edges ~0.1–0.9 pt, the long `start→end` more) — a
  canonical LR-routing refinement; plus svg wiring + the `vee` arrowhead.
  graphvizJVM 333/333, JS green. TB corpus untouched (identity transform).
- **2026-07-11** — **`vee` arrowhead (crow) ported — 02's short/labelled edge
  splines byte-exact.** After the transform landed, 02's edge spline `pos` was
  *close* but off ~0.1–0.4 pt. Instrumented gv's canonical (pre-`map_point`)
  02 splines: the raw routing is **identical** to mine (same degenerate straight
  cubic, modulo the +10 canonical x-shift the transform absorbs) — the drift was
  entirely in the **head arrow clip**, growing toward the head. 02 uses
  `arrowhead=vee` = `ARR_TYPE_CROW | ARR_MOD_INV`, a crow arrow with its own
  `arrow_length_crow` (≈11.22 at the defaults, vs `arrow_length_normal` ≈11.53);
  I was clipping with the normal length. Ported `arrow_type_crow0` + `arrow_
  length_crow` (plain vee: INV, no L/R) into `Arrow` (reusing `miterShape`), and
  made the spline clip pick the length by `arrowhead`. Result: `start→middle` +
  `middle→end` spline `pos` now **byte-exact** (RankDirSpec gate). ⬜ The long
  `start→end` edge still deviates: its rank-1 box is clamped by the label vnode's
  *resized* `lw`, which we approximate from the maximal_bbox rather than gv's
  checkpath-narrowed corridor (`recover_slack` bypass) — the last spline blocker,
  plus the `vee` svg polygon + svg transform wiring for the full-02 svg gate.
  graphvizJVM 334/334, JS green. TB corpus untouched (arrowhead defaults normal).
- **2026-07-11** — **02-LR FULLY byte-exact (dot_json + json0 + svg) — `rankdir`
  closed.** Two final pieces landed. (1) **`limitBoxes`** (routespl.c:242): after
  routesplines fits the spline, gv resets every channel box's x-range and
  re-fills it by finely sampling the spline (INIT_DELTA·boxn pts/segment) — each
  box's `[LL.x, UR.x]` = min/max spline x among samples in its y-range. Ported it
  and switched `recover_slack` back to the narrowed boxes (dropping the 15-era
  spline-shortcut): the label vnode now gets its exact `lw = box.UR − box.LL`,
  which is what clamps the long `start→end` edge's box channel — closing the last
  spline. json0 **byte-exact**. (2) **svg transform wiring + vee polygon**:
  threaded `DrawTransform` through every svg coordinate (node centres, spline
  points, arrow tip/vector, edge-label text) — node/label extents stay true-size
  (rotating the swapped layout size gives back true size, so only centres
  transform) — and emit the `vee` head as gv's 8-point crow `<polygon>`
  (`gvrender_polygon a,8,1`). Also fixed dotJson's `bb` to use the rotated-frame
  `finalBBox`. 02 (LR + rounded/filled boxes + gray/dashed + vee + `go` label)
  is now byte-exact end-to-end; **promoted into DifferentialSpec must-pass**.
  graphvizJVM 336/336, JS green, TB corpus untouched (identity transform). The
  `rankdir` §5 row is ✅ for LR; RL/BT follow the same `DrawTransform`
  construction (no corpus) and spline routing under RL/BT is the only open sub-part.
- **2026-07-11** — **Whole-corpus byte-exact: 73/76 (all 3 formats) + a locking
  gate.** After closing 02/04, a full-corpus exact-string sweep found two quick
  systematic wins and pinned the true residuals. (1) **04 record `rects`**: json0
  emits each leaf field's absolute box from the RecordLabel layout → 04 byte-exact
  (promoted to must-pass). (2) **empty json0 edges**: gv omits the `"edges"` key
  for an edgeless graph (dotJson already did); json0 emitted `"edges": []` —
  omitting it closed every edgeless shape/HTML single-node test, jumping the
  exact count 20→53. With the image size sidecar loaded (as the gated specs do),
  **73 of 76 corpus files are byte-exact across dot_json + json0 + svg**. New
  `CorpusByteExactSpec` gates all three formats for every file (414 tests total),
  with the 3 exclusions asserted fails-when-fixed. Remaining: **03** (viz-js
  leaves clusters unlaid-out — buggy oracle, dot_json matches), **05** (HTML/
  multi-line edge-label metrics + `tooltip` `<a>` anchor + graph-label centering),
  **06** (undirected-mesh spline residual ~0.05 pt, Hausdorff-gated). graphvizJVM
  414/414, JS green.
- **2026-07-11** — **Cluster geometry subsystem, byte-exact vs gv's own
  `newrank` oracle (03b) — the "don't port the bug" rule applied end-to-end.**
  Recon first: 03's `{rank=same; a0; b0}` across two clusters is NOT a viz-js
  bug — gv 13.0.1 CLI emits the same degenerate 0×0 sentinel and gv 12.2.1
  hard-errors (`install_in_rank`) after "a0 … deleted from cluster": the
  DEFAULT recursive cluster ranker cannot represent a cross-cluster rankset.
  The input is satisfiable (unique min-edge-length ranks, provable by hand),
  and gv itself ships the correct semantics as `newrank=true` — which our
  engine already implements by construction (global ranking). So the oracle is
  **gv 13.0.1 + newrank**: new corpus file `03b-subgraph-cluster-newrank`
  (goldens re-captured; the other 76 reproduced byte-identically). Transcribed
  the full cluster geometry: **Y** — `set_ycoords`/`clust_ht` (`Coord.yInfo`):
  per-rank `pht` vs cluster-inflated `ht1/ht2`, label band = padded
  `GD_border[TOP]`, `max(d0, ht2+ht1+CL_OFFSET)` rank spacing, root `GD_ht1/2`
  + baked `translate_drawing` shift; **X** — `pos_clusters` in the aux NS
  (`XCoord`): `ln`/`rn` border slacknodes per cluster (`make_lrvn` + label
  min-width edge), `contain_clustnodes` (weight-128 compaction),
  `keepout_othernodes`, `contain_subclust`, `separate_subclust`, all margin
  CL_OFFSET=8, slacknodes heading GD_nlist (fast_node prepend order);
  **splines** — rank `ht1/ht2` now cluster-inflated + `cl_bound` clips a
  channel at a foreign cluster's bb ± Splinesep; **writers** — real bb
  (sentinel deleted), json0 cluster `bb`/`lheight`/`lp`/`lwidth`, generalized
  subgraph `write_attrs` echo (root-declared attrs surface in every subgraph
  object), svg `<g class="cluster">` border + label. The default-mode
  "eviction" of rankset nodes from clusters (the first stage of gv's
  corruption) is gone — 03b's golden keeps `a0` in `cluster_0`. **03b came out
  byte-exact in all 3 formats on the first full run** (the geometry was
  hand-derived from the C before coding — 76.8 stretched rank gap, boxes
  [8,78]/[86,156], lp 288.4 — every number verified pre-implementation).
  **03-verbatim now renders the same correct drawing**: svg byte-identical to
  the 03b golden; jsons equal modulo the `newrank` attribute echo — gated in
  the rewritten ClusterSpec. Corpus: **74/77 byte-exact** (03 differs only
  from its own corrupted goldens — by design). Future work (no corpus yet):
  cluster fontsize/labelloc/style attrs, flipped (LR) cluster labels,
  cluster-aware mincross where plain ordering ≠ cluster ordering, and
  default-mode (recursive-ranking) cluster parity. graphvizJVM 418/418, JS
  green.
- **2026-07-12** — **05-strings-comments CLOSED byte-exact — the deepest NS
  residual was a 1-line seed-truncation bug, not an unmatchable tie-break.**
  Three parts. (1) **node tooltip/href anchor** (`emit_begin_node` +
  `svg_begin_anchor`): a node with non-empty `href`/`URL`/`tooltip` wraps its
  shape+label in `<g id="a_{objId}"><a …>…</a></g>`; tooltip uses the
  raw/dash/nbsp escape set. (2) **multi-line plain labels** (`emit_label` +
  `svg_textspan`): split on `\n`/`\l`/`\r`, stack from the top, anchor per
  justification — reduces to the existing single-line `textAt`; wired into
  node + edge labels (edge labels also branch HTML vs plain). (3) **the last
  ~3pt on `node one`** — traced via instrumented gv (§2.5) to a genuine
  root cause, NOT a degenerate tie-break as long suspected: `make_LR_constraints`
  left-packs the aux-graph SEED as `ND_rank(v) = (int)(last + width)` — running
  int rank + RAW width, TRUNCATED — while the edge minlen is a separate
  `ROUND(width)`. We used `ROUND` for both, inflating the seed +1 on the `__v2`
  back-edge chain; that +1 flipped `init_graph`'s feasibility so we skipped
  `init_rank` where gv runs it → a different (equally-optimal) `feasible_tree`
  → node one at 60 vs 63. Truncating the seed (edge stays ROUND) made our raw
  seed match gv's exactly (`[41,54,…,73,55,42]`, feasible=false), `init_rank`
  runs identically, node one = 63. **05 byte-exact in all 3 formats**,
  promoted out of the deferral set. The instrument-and-port method paid off
  again: what looked like an unmatchable degenerate basis was a concrete
  rounding divergence. Corpus: **75/77 byte-exact** (only 03 — gated vs its
  newrank oracle — and 06 — accepted 0.05pt spline). graphvizJVM 418/418, JS
  green, gv worktree pristine.
- **2026-07-12** — **Post-cutover feature sweep: nodesep/ranksep, rank=min/max,
  point.** With the corpus byte-exact and M8 cut over to the Scala engine, a
  pass over the common still-unported attributes that arbitrary user DOT hits:
  (1) **`nodesep`/`ranksep`** (+ `ranksep="… equally"` ⇒ exact_ranksep) threaded
  from the graph attrs (were hardcoded 18/36) through Coord/XCoord/Spline —
  corpus 78/79/80 byte-exact. (2) **`rank=min/max/source/sink`** via
  `minmax_edges` (collapse to minset/maxset, reverse the leader's edges on the
  working graph, add zero-weight pins) — ranking byte-exact, `source` fully
  byte-exact (83); `min`/`max`/`sink` need edge reversal whose mincross BFS
  seeds a mirrored within-rank order (valid layout, deferred — 81/82/84).
  Also fixed the XCoord origin shift to use NORMAL nodes only (gv
  `dot_compute_bb`), which a reversed min/max chain exposed. (3) **`shape=point`**
  (point_init: filled circle, no label) — corpus 85 byte-exact. Corpus now
  **84/86 byte-exact**; graphvizJVM 491/491, JS green, gv worktree pristine.
- **2026-07-12** — **RL/BT rankdir + flat edges (the two "deep subsystems"),
  both cracked fast.** (1) **RL/BT** looked like a spline-routing subsystem but
  was a **2-line bug**: `DrawTransform` used the textbook rotations for 180/270,
  but gv's `ccwrotatepf` (geom.c) is a CUSTOM map — 90→(-y,x), 180→(x,-y) [a
  vertical flip], 270→(y,x) [a transpose]. Fixed the formulas + made the writers
  use `finalBBox` whenever rankdir≠TB (BT rotates though flip=false). Splines
  needed nothing (they route canonically + map through tf). 86/87/88/89
  byte-exact incl. label-vnode-under-flip + styled boxes. (2) **Flat (same-rank)
  edges** — ranking/positions were already exact (rank=same); only the spline
  was missing. Added `makeSimpleFlat` ([tp,(2tp+hp)/3,(2hp+tp)/3,hp] + clip),
  and for LABELED flat edges `makeSimpleFlatLabels` ([tp,tp,hp,hp] + label
  placement) plus the `make_LR_constraints` flat_out separation bump
  (`max(ED_minlen·nodesep+width, width+nodesep+ROUND(ED_dist))`, ED_minlen
  doubled under edge labels — which is why a narrow "x" still lands at the
  2·nodesep floor). Coord excludes flat labels from rank-height inflation.
  90/91/92 byte-exact. ⬜ only non-adjacent (box-routed) flat edges remain
  (guarded-skip today). Corpus now **90/93 byte-exact**; graphvizJVM 512/512,
  JS green, gv worktree pristine.
- **2026-07-12** — **Non-adjacent flat edge (`93-flat-nonadj`) byte-exact — the
  last flat sub-case.** Two distinct pieces, both faithful ports. **(1) Routing
  (Spline).** A non-adjacent flat edge can't run straight (it skips ≥1 node), so
  `make_flat_edge` arches it up-and-over. Reconstructed the box channel from gv
  (all 5 boxes byte-exact): the tail/head flat-end box is `maximal_bbox` with
  `LL.y` pulled to `node.y` (the `makeregularend` extension is degenerate and
  dropped ⇒ one box/end); the 3-box channel steps up by `stepy = vspace/(cnt+1)`
  and widens by `stepx = Multisep/(cnt+1)`. Three gv details unlocked the exact
  x-extents: `ND_mval = ND_rw` during spline routing (so the neighbour clamp is
  `left.x + left.rw + nodesep/2`), `LeftBound/RightBound ∓ MINW` **once per
  rank** (the decrement is inside the rank loop), and `vspace = GD_ranksep` at
  the top rank. The corridor is **not y-monotone** (up-then-down), which the old
  `funnel` (a y-monotone stand-in for `Pshortestpath`) can't route — so added
  `funnelGeneral`: each gate is the real shared edge between consecutive boxes,
  its two ends wound consistently by the local travel direction (`rot90cw`) so
  the two boundary chains stay coherent through the U-turn. It **reduces to the
  old gates bit-for-bit for a descending channel** (proven: `d.y<0 ⇒ _1 = smaller
  x`, gate at `y=a.lly`), so every regular edge is untouched. `buildPolygon` was
  already a faithful 1:1 transcription of gv's polygon walk (its "down-only" was
  a mislabel — gv's `flip` is false for the arch, and the walk matches). **(2)
  Graph height (Output).** The arch rises above its rank, so the drawing must
  grow — ported `update_bb_bz` (emit.c): dot grows `GD_bb` per installed spline
  by the spline's **tight** bezier bbox via adaptive de Casteljau subdivision
  (`check_control_points`: within `HW=2pt` of the chord). The naive control-hull
  overshoots (133.94); subdivision recovers the true peak (131.955 → `131.96`).
  A no-op for node-contained regular edges (why node-only `bbox` matched every
  prior file). Promoted `93` out of both deferred sets. Corpus now **88/93
  byte-exact** (residuals: 03 gated vs newrank, 06 accepted 0.05pt spline,
  81/82/84 min/max mincross-order mirror); graphvizJVM **515/515**, JS green, gv
  worktree pristine.
- **2026-07-12** — **Cluster-aware mincross — single-level contiguity byte-exact
  (94-cluster-contig).** Cluster-unaware mincross interleaved cluster nodes
  within a rank (`r→{a,b,c,d}` with clusters `{a,c}`/`{b,d}` gave `a,b,c,d`; gv
  keeps `a,c` left, `b,d` right). Ported gv's collapse mechanism: `runMincross`
  extracted from `orderImpl` as a generic reusable solver (verified byte-
  identical on the whole corpus first), then `orderClustered` reproduces
  `class2`/`cluster.c` — each top-level cluster is collapsed to a **skeleton
  chain** (one virtual rankleader per rank, `build_skeleton`), every edge
  touching a cluster node is redirected to the skeleton (`interclrep`/
  `leader_of`) and intra-cluster edges dropped, so the top-level mincross sees
  each cluster as ONE column and physically can't interleave it. Each cluster's
  interior is ordered by a recursive `runMincross` on its induced subgraph, then
  each skeleton is expanded back into that order. **94-cluster-contig byte-exact
  end-to-end** (order + XCoord cluster constraints for multi-node-per-rank
  clusters + geometry — the last two already existed from 03b but were only
  exercised at ≤1 node/rank). Additive: only clustered graphs hit the path, so
  03/03b + all 86 non-cluster files stay byte-identical. ⬜ **95-cluster-chains**
  deferred (fails-when-fixed) — but the **mincross ORDER is byte-identical to
  gv** (verified node-by-node) and the RELATIVE geometry matches exactly; the
  residual is a uniform **~10.5pt X shift**, isolated to the XCoord post-solve
  normalization (`XCoord` shift = `min(real-node left, cluster-ln − 8)`): with a
  FREE edge routing left of a cluster, gv keeps the cluster border at its raw
  solve x (18.531) and gets its bb origin 0 from the edge SPLINE (`update_bb_bz`),
  whereas my heuristic forces the border to 8. So 95 is an XCoord-anchor gap,
  NOT a mincross one. Nested clusters also TODO (recurse the collapse per level).
  Corpus **89/95** byte-exact; graphvizJVM 520/520, JS green, gv worktree pristine.
- **2026-07-12** — **95-cluster-chains deep-dive (instrumented gv): XCoord is
  byte-identical to gv; residual localized to Spline + Output.** Built
  instrumented gv 13.0.1, probed `make_aux_edge` + `set_xcoords`. The raw
  `set_xcoords` values (a0@56, b0@166, top@138, rank-1 free vnodes @1,111,221,
  259) equal my `XCoord.solveAll` coords + a constant 13 — **the aux-NS solve,
  free virtuals included, matches gv exactly.** (The earlier "free vnodes differ"
  read was an artifact of comparing to imprecise spline-extracted positions.) So
  the ~10.5pt residual is NOT XCoord. Two downstream causes, both precisely
  located: **(1) Output** — gv's `translate_drawing` derives the origin from the
  FULL bb *including splines* (post-routing); my XCoord shift is pre-spline, so a
  spline overhanging left of a cluster leaves my origin ~10.5pt off (a no-op for
  every current byte-exact file, since none overhang). **(2) Spline** — a
  `recover_slack` cascade: top→b1's virtual snaps to a box that then widens
  top→b2's `maximal_bbox` (inner edge 199 vs the symmetric ~227), so its funnel
  hugs the near edge instead of routing through the vnode@246 → the right spline
  is short (max 209.6 vs gv 233.9); top→a2 (left) is byte-identical. gv worktree
  reverted pristine; no source changes (diagnosis only). Corpus 89/95 byte-exact.
- **2026-07-12** — **95-cluster-chains CLOSED byte-exact — four faithful fixes,
  each found via instrumented gv.** The instrumented dump proved `XCoord` was
  already byte-identical to gv; the residuals were all downstream. **(1) Spline
  `maximal_bbox`** — gv's left-neighbour clamp uses `ND_coord(left).x +
  ND_mval(left)`, where `ND_mval` is the PRE-spline rw (a safe copy from
  `set_xcoords`), not the `recover_slack`-snapped `ND_rw`; using the snapped rw
  let top→b2's box creep 7pt left so its funnel hugged the near edge (spline max
  209.6 vs gv 233.9). Added `mvalRw` (rw sans the snap override). **(2) Output
  `translate_drawing`** — the origin comes from the FULL bb *including splines*
  (post-routing), so a spline overhanging a cluster shifts everything; composed
  `(−lx,−ly)` into `tf` + cluster bbs + graph bb (a no-op for all 89 files whose
  splines stay inside the node/cluster box). **(3) cluster edge membership** — a
  CLUSTER owns every edge with both endpoints inside it (95's root-declared
  `a0->a1->a2` belongs to cluster_0), but a PLAIN `{rank=same}` subgraph keeps
  only its directly-declared edges (90's `a->b`). **(4) edge `_gvid`/emit order**
  — `agfstout` returns out-edges by HEAD-node id (declaration), not edge AGSEQ,
  so top→a0,a1,a2,b0,b1,b2 — not top→a0,b0,a1,…; sorted both the json0 gvid pass
  and the svg emit loop. All four are gv-faithful and provably no-op wherever the
  old and new orders coincide (every prior byte-exact file). Corpus **90/95
  byte-exact**; graphvizJVM 521/521, JS green, gv worktree pristine.
- **2026-07-12** — **96-nested-cluster byte-exact — nesting was almost free.**
  A cluster inside a cluster (cluster_1{a,b} ⊂ cluster_0{a,b,c}). The ordering,
  XCoord (`contain_subclust`/`separate_subclust` already ported for 03b) and
  cluster geometry ALL produced byte-exact node positions + bboxes on the first
  run — the collapse/recurse structure already generalised. The only gaps were
  in the Output writer's subgraph emission: (1) a subgraph's `nodes`/`edges`
  membership must be **transitive** (cgraph: a nested cluster's nodes belong to
  the parent too, so cluster_0 rolls up cluster_1's a,b + edge a→b); (2) a
  subgraph object emits a **`"subgraphs"`** array of its child gvids (after
  `_gvid`, before `nodes`). Both additive — no-op for the flat single-level
  clusters. Corpus **91/96 byte-exact**; graphvizJVM 524/524, JS green, gv
  worktree pristine (no instrumentation needed).
- **2026-07-12** — **06/82 residual CONFIRMED (instrumented gv) as a
  floating-point-floor clip difference — NOT Proutespline, not closable by
  matching the algorithm.** Chased the <0.05pt residual on 82's curved `a→c`
  edge (positions byte-exact; only this spline differs). Instrumented gv's
  `routesplines_` to dump the `Pshortestpath` polyline + `Proutespline` output:
  gv's raw `a→c` spline is a **straight line** (control points = doubled
  endpoints) — Proutespline does no least-squares here — and my raw spline is
  **byte-identical to gv's in the final frame** (`(55,89)→(27,19)`). So the
  0.048pt lives entirely in the `bezier_clip` of that straight line against the
  node ellipse. Verified EVERY clip input matches gv: the binary-search
  algorithm (faithful transcription — split, 0.5 termination, do-while), the
  ellipse `insidefn` (`hypot(P/box_UR) < 1`, scale=1 for TB), and the semi-axes
  (`box_URx = (size+penwidth)/2 = 27.5`). Best hypothesis was coordinate
  MAGNITUDE — gv clips "in node coordinates" (splines.c) i.e. small numbers,
  mine in absolute coords, and float rounding over ~11 bisection steps could
  diverge. Implemented the node-relative clip to match gv exactly ⇒ **byte-
  identical output** (no effect), ruling magnitude out too. Conclusion: with
  identical input + identical algorithm, a 0.048pt difference remains — a true
  FP-precision floor (gv's exact `hypot`/de Casteljau bit-pattern), below the
  algorithmic level, with zero visual change (splines pixel-identical, goldens
  differ in the 2nd decimal). Reverted all probes; gv worktree pristine; no
  source change. 06/82 stay accepted FP-floor residuals; the port's 5 residuals
  are now a fully-characterised precision-and-tie-break floor, not open work.
- **2026-07-12** — **Shape catalog closed (61/62 builtins byte-exact).** Filled
  the last real coverage gap: the ~36 node shapes that were crashing the
  pure-Scala path (`NodeSize` → `Infinity` → viz-js fallback) are now ported and
  byte-exact vs the viz-js oracle. Added 36 single-node `1XX-*` corpus probes +
  goldens and a `ShapeCatalogSpec` ratchet (grown per category; the probes are
  now also re-joined to `CorpusByteExactSpec`/`DifferentialSpec` end-to-end).
  Five faithful transcriptions of `lib/common/shapes.c`:
  (A) `Polygon.init` extended to the full `poly_init` convex branch — concentric
  peripheries via the bisector-offset loop, `sides≤2+distortion → 120`-gon (egg),
  and generic `polygon` + user `sides/skew/distortion/orientation/regular/
  peripheries` (`NodeSize.polyDescOf`, `late_int`/`late_double` overlay);
  (B+E) `RoundCorners.scala` — a 1:1 port of `round_corners` +
  `alloc_interpolation_points` closing the container shapes (note/tab/folder/
  box3d/component/underline) **and all 20 SBOL biology shapes** in one function
  (26 shapes); (C) `Polygon.Gen` custom generators — cylinder (`cylinder_size`/
  `_vertices` + `cylinder_draw` two-bezier render) and star (`star_size`/
  `_vertices`); (D) M-variants — Mdiamond/Msquare via `diagonals_draw`, Mcircle
  via `Mcircle_hack`. AF order + coordinate mapping (node-local y-up TR,TL,BL,BR →
  translate + negate-y) verified by hand against the DOGEAR / cylinder / Mcircle
  goldens before coding; all 108 probe assertions passed with no post-hoc fitting.
  Only `epsf` (external PostScript file) remains out of scope. viz-js no longer
  masks any builtin shape on the `ScalaFirst` path. 740 tests green; gv worktree
  pristine. Also: fixed a stray NUL byte in `Svg.scala` (a `'\u0000'` sentinel
  saved as a raw NUL) that made the file read as binary to grep/rg/editors.
- **2026-07-12** — **viewer routes by layout engine (viz-js kept for non-`dot`).**
  Replaced the old `ScalaFirst`-with-fallback dispatch with engine-aware routing
  in the viewer `Graphviz` class: `Graphviz.usesDotEngine(dot)` reads the graph
  `layout` attribute (regex; unset ⇒ `dot`) and sends `dot`/unset graphs to the
  pure-Scala port and every other engine to viz-js. Removed the `EngineMode`
  enum + `gx.graphvizEngine` localStorage toggle (the choice is now the engine,
  not a global mode). **Correction:** an earlier same-day attempt to drop viz-js
  entirely was wrong — the port implements ONLY the `dot` engine, and the app
  ships `neato`/`twopi` examples (`examples/neato/*`) that viz-js must lay out;
  removing it silently mis-rendered them as `dot`. It also surfaced a
  *pre-existing* latent bug: the old `ScalaFirst` fallback only caught *hard*
  failures, so a `neato` graph (which `renderScala` "succeeds" at, as `dot`)
  never fell back — engine routing fixes that. `@viz-js/viz` stays a runtime
  dependency; `simplegraph` (engine-neutral JSON parsing in `VizJsGraph.scala`)
  and the `VizJS` binding are retained. The `dot` path has no viz-js fallback by
  design (surface port bugs). Verified in-browser: dot examples (doublecircles/
  clusters/records/HTML) render via the port, `neato` examples (Colors, Twelve
  Colors) render via viz-js with the correct force-directed layout, no console
  errors. gv worktree pristine.
- **2026-07-13** — **cgraph edge-set ordering ported; 84-ranksink CLOSED.**
  Chased the groups.dot cluster mirror via differential oracle probes + an
  instrumented gv build (mincross build_ranks/enqueue/nlist trace; worktree
  reverted after). The root cause was in the FLAT path all along: cgraph orders
  each node's out-edge set by **(head-node declaration seq, edge seq)** — NOT
  edge-declaration order (`edge.c agedgeseqcmpf`; the out-half's key node is
  the head). Every transcription that iterates out-edges inherited my wrong
  edge-declaration assumption; it only coincides when nodes are edge-implied,
  which the whole corpus was — pre-declared nodes (cluster members before
  edges) exposed it. `Order.orderImpl` now emits class2 chains per node in
  declaration order, out-dedges sorted by (original-head seq, edge idx),
  reversed edges iterated at their original tail. Closed: 84-ranksink
  (byte-exact — its "mirror" was this) + the new minimal probe
  164-cluster-mirror. Re-opened honestly: 95-cluster-chains was byte-exact via
  two cancelling divergences; with faithful input order its collapsed-pass
  INITIAL orders now match gv exactly (instrumented), and the residual is the
  collapsed-pass mincross optimum (gv floats free vnodes outside the skeletons
  — suspect install_cluster + skeleton-edge xpenalty semantics). 163-groups'
  mirror is the same class (its cross-cluster edges make the collapsed
  iterations decide the sides). Next deep-dive: collapsed-pass parity
  (install_cluster, xpenalty, remincross). **Audit TODO:** other `agfstout`
  transcription sites (Rank.acyclic DFS, decompose) still assume declaration
  order — same latent divergence class, no corpus trigger yet. 752 green.
- **2026-07-13** — **Cluster left-right mirror CLOSED** (three stacked causes,
  each found via instrumented gv, worktree reverted after each round):
  (1) cgraph edge-set order — out-edges iterate by (head-node seq, edge seq)
  (closed 84-ranksink + probe 164); (2) `install_cluster` whole-column install
  + `CL_CROSS=1000` skeleton-edge xpenalty with `xp(e1)*xp(e2)` crossing
  weights (re-closed 95 faithfully after the edge-order fix exposed its
  two-cancelling-errors pass); (3) intra-cluster chain vnodes belong to the
  CLUSTER (gv mark_clusters), not the collapsed graph — `cOf` now classifies
  `Virtual(idx,_)` by its owning edge's original endpoints — plus the
  build_ranks tail transpose is root-graph-only (`rootGraph` flag; with the
  CL_CROSS weights it floats cross-cluster chains out from between skeleton
  columns). 163-groups now matches gv's mincross exactly (verified vs the
  instrumented `mincross-END` snapshots); its remaining deltas are the
  pre-characterized non-mirror items (Mdiamond/Helvetica width, a dot_json
  cluster edges-array order nuance, sub-pt cross-cluster spline). 753 green.
- **2026-07-13** — **Mdiamond/font-list width closed.** Differential probes
  (box/diamond/Mdiamond × Times/Helvetica-list) vs the oracle showed gv returns
  IDENTICAL sizes for both fonts: `get_metrics_for_font_family` does
  whole-string permissive matching (case + non-letters ignored) per alias and
  never splits CSS-style font lists — "Helvetica,Arial,sans-serif" matches
  nothing → Times fallback. My `family()` had an invented substring fallback
  (resolved the list to real Helvetica) and `canon()` kept digits; both fixed
  to exact gv semantics. 163's NodeSizeSpec deferral removed. Also: XCoord's
  cluster slice now contains intra-cluster chain vnodes (same rule as Order).
  Remaining 163 residual: the cluster INTERIOR refinement pass — gv's
  mincross_clust iterates with GLOBAL ncross/medians (reverse-pass tie-swaps
  flip equal-median pairs, kept via `<=` save) and expands clusters
  SEQUENTIALLY; my interior pass is cluster-local. Plus the dot_json cluster
  edges-array order + the sub-pt spline. 754 green.
- **2026-07-13** — **Cluster interior refinement pass ported** (mincross_clust).
  Interior install = build_ranks(subg,0) BFS in nlist order (gv's
  `walkbackwards` is a double reversal: fast_node PREPENDS, so walking backward
  restores declaration order — taking it literally regressed 94). Interior
  refinement = mincross(subg,2) run per cluster against the GLOBAL order:
  medians use global positions, ncross counts the whole root, reverse-pass
  tie-swaps flip equal-median pairs, `<=` keeps the latest. 163-groups' final
  per-rank mincross orders now match gv's instrumented snapshots EXACTLY.
  Remaining 163: XCoord in-cluster vnode solve (+18pt, same order different NS
  equilibrium), dot_json cluster edges-array listing order, sub-pt spline.
  754 green.
- **2026-07-13** — **163-groups (the user's diagram) FULLY BYTE-EXACT** — the
  XCoord instrumentation round closed the last three gaps in one sitting:
  (1) intra-cluster chain vnodes keep lw=rw=1 — gv's incr_width reads
  GD_nodesep(subg), never initialized on cluster subgraphs (a gv quirk, ported
  deliberately into XCoord + Spline); the a-column and cluster borders now
  match the golden exactly; (2) the svg font-family attribute is the
  ps_font_equiv.h alias echo — matched names emit family[,svgFamily]
  (+weight/stretch/style), unmatched names (CSS font lists) pass VERBATIM,
  independent of the Times metrics fallback; (3) Msquare/underline/image and
  all special-corner shapes clip edges as a BOX (poly_inside), not an
  inscribed ellipse; plus the subgraph edges arrays list edges in cgraph
  declaration order. dot_json + json0 + svg all byte-exact; promoted into the
  corpus gate. Remaining deferrals: 03 (intentional), 06/82 (FP floor),
  81 (rank=min mirror), 162 (one cross-cluster spline ~0.5pt). 755 green.
- **2026-07-13** — **The "FP-precision floor" is dead: 06, 81, 82 all
  byte-exact.** The 81-rankmin chase found its mirror already gone (killed by
  the session's earlier fixes) and the residual spline off 0.06–0.17pt. An
  instrumented raw-spline dump (clip_and_install input) showed the raw geometry
  IDENTICAL to gv — but parameterized in the OPPOSITE direction: gv routes and
  clips rank-reversed edges in the WORKING direction (orig-head → orig-tail,
  `swap_ends_p`) and flips the finished spline at install
  (`swap_spline`). `bezier_clip`'s bisection is not direction-symmetric, so my
  original-direction clipping landed the cut a hair off — the exact mechanism
  behind what 2026-07-12's exhaustive chase mis-diagnosed as an unclosable
  float-precision floor (it compared identical inputs through the clip but
  never questioned the parameter DIRECTION). `clipInstall` gains
  `reversedWork` (clip roles swapped, `arrowStartClip` for the head arrow at
  the working start, points reversed at install); the three regular-edge call
  sites keep the routed path top-down and pass `reversedWork = rt > rh`. All
  three fails-when-fixed guards fired at once. Deferrals now: 03 (intentional)
  + 162 (one ~0.5pt cross-cluster spline). 758 green; gv pristine.
- **2026-07-13** — **162-cluster-style closed: corpus 137/138, the only
  deferral left is 03 (intentional).** The last residual — ONE cross-cluster
  spline bending 1pt east of gv's — fell to a single character class:
  `dot_splines_` initialises `.Splinesep = GD_nodesep(g) / 4` where
  `GD_nodesep` is an **int** (types.h), so C INTEGER division gives 4 for the
  default nodesep 18; the port computed `NodeSep / 4.0 = 4.5`. (Contrast
  `maximal_bbox`'s `GD_nodesep(g) / 2.` — the trailing dot makes that one
  float.) The cluster-wall channel clamp `round(bb.UR.x + Splinesep)`
  (`maximal_bbox` → `cl_bound`) then produced 83 vs gv's 82, and the routed
  spline pivots exactly at that corridor corner. Found by dumping gv's
  `routesplines` input boxes (`[bx]` probe): gv's head-rank box for `a0->b0`
  starts at x=82 = cluster_0's wall 78 + 4. My `clBound` transcription was
  already faithful — only the constant was off; every other Splinesep use had
  rounded away the 0.5 in the corpus so far. One-line fix in `Spline.scala`.
  162's fails-when-fixed guard fired; promoted in CorpusByteExactSpec +
  DifferentialSpec. 759 green; gv worktree reverted + `_dbgbuild` removed
  (pristine).
- **2026-07-16** — **Shipped-examples byte-exact gate.** New
  `capture-examples.mjs` freezes viz-js goldens (3 formats, engine=dot) for
  every `viewer/src/main/resources/examples/**` .dot file, and
  `ExamplesByteExactSpec` gates the ones the app routes to the Scala port —
  reading the SHIPPED sources directly (no corpus copy to drift) and routing
  with the same shared `EngineRouting.usesDotEngine` the viewer uses (the
  predicate moved from the viewer into the shared module for exactly this).
  First run: 2/8 byte-exact (empty-graph, groups); 6 deferrals triaged, each
  guarded fails-when-fixed: (a) data-structures + finite-state-machine +
  sbt-project-dependencies — ONE mechanism, `finalBBox`'s documented
  rotated-frame gap (no spline-overhang/self-edge/label growth); all node
  positions byte-exact, only the bb line differs; (b) logo — real LR layout
  divergence (nodesep=0.42/pad; positions differ); (c) html — HTML-table
  label layout diverges (user-reported); (d) unsupported/
  multiple-edges-with-commas — DotParser rejects `a -> b, c` edge lists
  (valid DOT). failing/leading-newline is excluded outright: viz-js ITSELF
  crashes on it ("table index is out of bounds"), so no golden exists.
  These deferrals are the correctness worklist. 693 green.
- **2026-07-16** — **`translate_bb` transcribed: the final-frame bbox is the
  transformed CANONICAL box, not a node-extent scan.** gv never re-derives a
  rotated-frame bb: `dot_compute_bb` builds the canonical box (with the
  LAYOUT — swapped — node sizes: ND_lw/rw/GD_ht1), `clip_and_install` grows
  it per spline during routing, `gv_postprocess` adds the root label (for a
  FLIPPED graph the label height lands on canonical X; BT inverts
  top/bottom; a label wider than the drawing widens the bb symmetrically —
  postproc.c:617), the per-rankdir `Offset` comes from that grown box
  (postproc.c:656), and `translate_bb` maps its corners. All of that is now
  `layout/GraphBB` (bbox moved out of Output — gv computes it in layout;
  writers read it) + `DrawTransform.of`'s Offset formulas. Two bugs fixed en
  route: canonical bb used TRUE node sizes (±(w−h)/2 off on both axes for
  flipped ellipses — 86-rankdir-rl), and the label/finalBBox handling for
  rotated graphs was a node-extent approximation. Corpus 694-green.
  **Example re-triage (json0, not dot_json — dot_json has no positions!):**
  the 3 LR examples are NOT one-bb-line: fsm = LR+edgelabel position
  divergence; sbt = node heights 50 vs 58.4pt (font-list sizing);
  data-structures = record `rects` malformed under LR. Deferral notes
  updated in ExamplesByteExactSpec.
- **2026-07-16** — **Example-gate sweep: commas + logo + html CLOSED (5/8
  examples byte-exact), plus the full `size_html_txt` transcription.** Root
  causes, in order: (1) cgraph `nodelist` grammar (`a, b, c` statements +
  comma edge endpoints) + edge endpoints declare their node in textual
  order (appendnode); (2) `constraint=false` edges excluded from the
  ranking graph; (3) `Rank.acyclic` iterates out-edges in `agfstout` order
  — the long-documented open audit; closed fsm's rank mirrors too; (4)
  flat edges clip in the ORDER-normalized working direction (`swap_ends_p`
  within-rank tie-break); (5) `arrowhead=none` ⇒ no trim/`ep`; (6) the
  clip outline is penwidth-inflated (`poly_init` outline ring); (7) svg:
  `pad` attr, `bgcolor` canvas, lowercased hex colors, node/edge
  `stroke-width`, invis edges emit comment-only; (8) the svg HTML renderer
  hardcoded FontSize/Times while sizing honored the node font — the
  user-reported oversized-tasks html bug; (9) **size_html_txt transcribed
  in full**: non-`simple` blocks (any style flag/mixed fonts) use RAW max
  font size per line (not ×LINESPACING), baselines advance by `lfsize`,
  single-span blocks are `mxysize` tall, and the emitter places text at
  `−(baseline + yoffset_centerline)` with the non-simple constant 1 —
  fixed sbt's 36 node heights (50 vs 58.4pt).
  **Remaining (deferred, fails-when-fixed):** fsm = pure XCoord aux
  divergence (order identical, 3 nodes −18/−35 in canonical X; LR + edge
  labels); data-structures (1 rank) + sbt (9 ranks) = mincross
  within-rank-order divergences (records/duplicate parallel edges).
  Corpus 137/138 untouched throughout.
- **2026-07-16 (later)** — **fsm CLOSED (6/8 examples byte-exact): five
  transcriptions, one deep one.** (1) `make_LR_constraints` inflates a
  self-looped node's `ND_rw` by Σ `selfRightSpace` (18 + flip-aware label
  width) BEFORE the aux x solve, parking the original in `ND_mval` —
  XCoord/GraphBB now do the same (fsm's −18/−35 canonical-X gaps).
  (2) `dot_splines_` routes edges in `edgecmp` order (type desc, |Δrank|,
  |Δx|, AGSEQ) — recover_slack's in-flight vnode moves make the order
  load-bearing. (3) **The canonical x frame must be gv's RAW aux-solve
  ranks** (integers, unnormalized): our old normalization shifted by a
  FRACTIONAL amount (leftmost real's `x − lw`), and `maximal_bbox`'s
  `round()` is not translation-invariant — every spline-phase rounding
  landed on a different lattice, cascading 0.033pt drifts through
  recover_slack. Found by instrumented-gv `[x]`/`[bx]`/`[rs]` probes: the
  static solves were IDENTICAL up to the shift; the first divergent box
  bound was `round(128.033)` vs `round(19.000)`. The unit specs
  (XCoordSpec/SplineSpec/RankSameSpec) now translate by the canonical bb
  before comparing against the (post-translation) plain goldens.
  (4) `selfRight` accumulates dx across a node's loops and bumps by
  `labelWidth − stepx` after a labelled loop. (5) self-edge labels get
  `lp = (n.x + dx + width/2, n.y)` — fsm's S(a)/S(b). gv worktree
  reverted; corpus 137/138 + examples + 693/52 green.
- **2026-07-16 (later still)** — **Port-aware mincross transcribed** (the
  ds/sbt chase, task #61 part 1): dot's crossing machinery SEES record/cell
  ports — (1) `medians` keys neighbours by `VAL = MC_SCALE·order +
  port.order` (the angular ordinal shapes.c:2863 stores; 128 for portless)
  with gv's INT arithmetic (case-2 and equal-span medians use C int
  division); (2) `in_cross`/`out_cross` break equal-order ties on the
  tails'/heads' CANONICAL port p.x; (3) `ncross` adds `local_cross` — 
  crossings among one ported node's own edges from port ordering; (4)
  `transpose` uses gv's candidate-rank flags; (5) ports are stored
  CW-ROTATED into the canonical frame (`cwrotatepf(p, 90·rankdir)`) — 
  `PortAnchor.canonical` is the single home, and `XCoord.portX` now uses it
  (its true-frame x was a latent LR bug). Per-segment ports: a chain's
  first/last segment carries the working tail's/head's port (reversed
  dedges swap ends). Verified with MCTRACE probes: our build_ranks +
  every mincross iteration now match gv EXACTLY on data-structures
  (crossings included, cross=5 init). ds is down to ONE rigid −7 aux
  subtree; corpus 137/138 + examples + 693/52 green throughout; gv
  worktree pristine.
- **2026-07-16 (later still 2)** — **ds −7 aux subtree CLOSED: `make_edge_pairs`
  working-orientation ports** (task #61 part 2). Method: twin aux-graph
  dumps — an XTRACE `fprintf` in gv `make_aux_edge` (position.c:176) vs a
  temporary dump of `XCoord`'s NSEdge buffer; both sides create aux edges
  in transcription-identical order so a line diff pins divergent
  constraints directly. Result: 71/71 aux edges, exactly FOUR minlens
  differed, all on ds's two acyclic-REVERSED ported edges
  (`node7:f1→node1:f0`, `node11:f2→node1:f0`). Root cause: the
  `make_edge_pairs` port match in `XCoord` keyed on the ORIGINAL
  orientation (`id == re.tail ⇒ re.tailPort`), but segments run in WORKING
  orientation — gv swaps the ports onto the reversed edge at reversal, so
  the first segment of a reversed chain tails the original HEAD carrying
  the original head's port. Fix: match either end and use that end's own
  port (the same working-orientation rule Order got in part 1). The aux
  graphs are now byte-IDENTICAL and every ds node position matches gv
  exactly. ds's remaining diff is edge SPLINES + bb only — a separate,
  newly-scoped subsystem (route-time `resolvePort`/`closestSide` dyna
  ports, ported `beginpath`/`endpath` channels, field-box `bp` clipping —
  see §0). Corpus 137/138 + examples + all suites green; gv pristine.
- **2026-07-16 (later still 3)** — **data-structures CLOSED (7/8 examples
  byte-exact): route-time port resolution + ported channels + pathscross**
  (task #61 part 3). The spline phase now transcribes gv's real port
  machinery: (1) **`GvPort`** (PortAnchor) — full `compassPort` struct
  (canonical p via `cwrotatepf`, `invflip_side`/`invflip_angle`, clip,
  constrained θ, `dyna`, TRUE-frame `bp` field box) + `record_port`
  (unknown names fall back to name-as-compass over the record box) +
  `resolvePort`/`closestSide` (dyna ports pick the accessible field side
  closest to the OTHER endpoint in the FINAL frame — per edge, at route
  time). (2) **`beginPathR`/`endPathR`** (Spline) — the splines.c:387/584
  REGULAREDGE side branches (canonical TOP loops / BOTTOM / LEFT / RIGHT +
  the ±1 nudges + orig-clip clearing), `record_path` corridors
  (`flip_rec_boxf` = transpose for LR/RL), and the `makeregularend`
  fill boxes; both feed one UNIFIED make_regular_edge channel (ported or
  portless, adjacent or chained — the old TB-adjacent-only `portEnd`
  deleted; HTML cell ports keep their verified legacy branch). Multi-rank
  chains use working-orientation ports (dotsplines.c:1784 `hackflag`; the
  tail dyna resolves vs the FIRST VNODE, the head vs the working TAIL).
  (3) **clip flags off the ORIG port** — initial-resolution clip (false
  for explicit compass, true for dyna/centre) minus side-branch clearing;
  a clipping ported end clips against its FIELD box (`record_inside`:
  ccw-rotate + INSIDE(bp ± penwidth/2)); records/Mrecords clip as boxes.
  (4) **exact `adjustregularpath`** with gv's fb/lb parity rules (incl.
  the size_t-underflow quirk at fb−1). (5) **`pathscross` + gv's real
  `neighbor`** (dotsplines.c:2310/2334): a plain virtual in-rank
  neighbour whose chain CROSSES vn's within two steps is SKIPPED, widening
  `maximal_bbox` past it (ds: node7's channel reaches past the crossing
  V(4,*) chains — this was the last 2-edge/bb residual, found by twin
  `resize_vn` snap traces + route/box dumps vs instrumented gv). Plus two
  svg-writer closes surfaced by ds: record field `<text>` now renders in
  the NODE's fontname/fontsize (was hardcoded Times/14; font-size prints
  fixed %.2f), and edge `<g id>` honours an explicit `id` attr (getObjId).
  Corpus 137/138 (04-ports-compass stays byte-exact THROUGH the new
  machinery — the old approximation is gone), examples 7/8, all suites
  green, gv worktree pristine. Only sbt-project-dependencies remains (§0:
  class2 multi-edge merging + splines=polyline).
- **2026-07-16 (later still 4)** — **class2 multi-edge merge ported** (task
  #61, sbt part 1). gv merges CONSECUTIVE same-endpoint multi-edges in the
  agfstout iteration (class2.c:207): flat parallels via `merge_oneway`,
  inter-rank parallels via `merge_chain` when both are unlabeled with
  equal ports — ONE chain per class, `ED_weight`/`ED_xpenalty` summed,
  `prev` staying the rep so a 3rd duplicate joins the class. Ported:
  `Order` computes `mergedInto` classes in the emit loop (merged dedges
  get no chain; crossing counts weight segments by class size via the
  existing `runMincross` xpenalty hook), `Result` carries `mergedInto`,
  `XCoord` sums the class members' weight attrs into the make_edge_pairs
  slack weight, and `Spline` routes only reps, installing each member's
  copy with the dotsplines.c:1948 interior offsets (−Multisep·(cnt−1)/2,
  then +Multisep per member, declaration order, per-member clip); flat/
  HTML merged classes (no corpus) inherit the rep's spline verbatim.
  Corpus 137/138 + examples 7/8 + all suites green (04's parallels have
  DIFFERENT ports ⇒ correctly not merged). sbt: ranks/x already exact;
  within-rank order still diverges — next: the MCTRACE loop (probes in
  `3c2f3d48^`), then `splines=polyline`.
- **2026-07-16 (later still 5)** — **sbt MCTRACE chase: layout fully
  CLOSED (dot_json + json0 byte-exact; svg within a 2-line FP print
  floor).** Five root causes, found by iteration-level probes vs
  instrumented gv:
  1. **Ranking multi-edge merge** (class1.c:94): the ranking NS carries
     ONE edge per (t,h) pair (`merge_oneway`/`basic_merge`: minlen max,
     weight summed) — unmerged duplicates left the same objective but a
     different feasible-tree path. `Rank` now collapses NS edges by pair.
  2. **TB_balance ties are LIBC-qsort-shaped** (ns.c:835): balanced nodes
     (inweight == outweight) move to the least-populated rank in [lo,hi],
     iterated in `Tree_node` order sorted by rank ONLY — an UNSTABLE
     comparator, so the equal-rank permutation is the libc's. The oracle
     (viz-js) is emscripten/**musl** = smoothsort, and macOS gv actually
     DISAGREES with the golden here (verified: golden follows musl) — the
     instrumented build is not a valid oracle for qsort ties (we patched
     its TB_balance with musl qsort to restore it). Ported **`MuslSort`**
     (musl smoothsort 1:1, verified permutation-identical to compiled C)
     + the `Tree_node` input order = the ranking graph's DECOMPOSE order
     (class1 fast graph, decl-order seeds, out-before-in), threaded as
     `tbOrder` into `NetworkSimplex.solve`.
  3. **sbt HAS edge labels** ("Evicted By" ×32 — rank doubling, label
     vnodes with LR-flipped rw = label height): already handled, but it
     raises the smode threshold to 4+1.
  4. **smode/straight_path** (dotsplines.c:1836): chains with ≥threshold
     x-aligned vnodes route in SEGMENTS — endpath default + θ=π/2 at a
     split vnode, the straight stretch emitted as duplicated points
     (straight_path), beginpath default + θ=−π/2 at the stretch end; each
     segment limit-boxes + recover_slacks separately. Transcribed as the
     new chain driver in Spline (portless corpus unaffected — verified by
     241/241 identical `resize_vn` snaps vs gv).
  5. **`splines=polyline`** (routespl.c:471): the route is the funnel
     polyline through `make_polyline` (endpoints ×2, corners ×3), no
     Proutespline. And the polyline's degenerate cubics exposed a
     boundary-semantics bug: **poly_inside is boundary-INCLUSIVE for
     polygons** (strict bbox rejection + `same_side >= 0`) — our box clip
     used strict `<`, and a polyline midpoint landing EXACTLY on the
     outline (t=0.5 of an (a,a,b,b) cubic aiming at the node centre)
     flipped the whole clip bisection. Also svg: dashed/dotted NODE
     borders now emit stroke-dasharray (like edges).
  Remaining: 2 svg values at a %.2f print boundary (§0). Corpus 137/138,
  examples 7/8 + sbt-layout-exact, all suites green, gv worktree pristine.
- **2026-07-16 (later still 6)** — **sbt fully CLOSED — 8/8 examples
  byte-exact; the examples deferred list is EMPTY.** The "2-line FP print
  floor" was NOT an FP floor: gv's `arrow_gen` (arrows.c:25)
  EPSILON(1e-4)-stabilizes the arrowhead direction vector — `s =
  ARROW_LENGTH/(hypot(u)+EPS)` and ±EPS added to each component BEFORE
  scaling — a real ~1e-4 term our Svg omitted, visible only where a
  polygon corner lands exactly on a `%.2f` print boundary (2 of ~4700 sbt
  svg values). Method that pinned it: rebuilt the instrumented gv with
  **`-ffp-contract=off`** (Apple clang default-contracts FMA, making the
  native build's low bits diverge from BOTH the WASM golden and the JVM)
  + the musl-qsort TB_balance patch — its clip_and_install doubles then
  matched ours BIT-EXACTLY (one 1-ulp `ep` on an unrelated edge), proving
  the layout pipeline exact at the bit level and isolating the flip to
  the render-side arrow math, where 17-digit dumps showed a ~6e-5 gap —
  too big for libm ulps — leading straight to arrow_gen's EPSILONs.
  Lesson for future bit-level chases: compare against a
  `-ffp-contract=off` build, and remember pure +−*/√ pipelines should be
  BIT-identical across C/WASM/JVM — any bigger gap is a missing term,
  not "FP noise". Corpus 137/138 (unchanged — the EPSILON shift never
  crosses a print boundary there and now matches gv's true arithmetic),
  examples 8/8, all suites green, gv worktree pristine.
- **2026-07-16 (final)** — **Full byte-exact sweep confirms 144/145.**
  Re-ran the whole-corpus + shipped-examples exact-string sweep (all
  three formats; corpus via the sidecar-aware `corpusGraph` path — the
  raw-source path false-negatives the 20 image files, exactly as the
  sweep memory warns): corpus 136/137 + examples 8/8 = **144/145**, the
  single diff being the intentional 03-subgraph-cluster deferral. Status
  header + §0 updated; this is the port's steady state: every gated
  input the app can produce through the `dot` engine matches viz-js
  13.0.1 character-for-character.
- **2026-07-16 (165–169)** — **Five user-reported diagrams added and closed
  same-day; corpus 141/142, total gate 149/150.** Each landed a deferred
  subsystem or a frame bug:
  - **165-htmlsides**: per-CELL HTML border/cellpadding resolution
    (size_html_cell chain; `sides` never enters sizing), `doBorder` side
    polylines (all 14 combos), `poly_gencode`'s peripheries==0-but-filled
    box for filled plaintext.
  - **166-uml-class**: HTML-in-record fields (parse_reclbl html mode; each
    field an LT_HTML sub-label), the **xlabel subsystem** (`addXLabels` +
    `placeLabels`/`xladjust` in the new `XLabels.scala`; placed labels grow
    the bb pre-translation), `dir`/arrow-type flags (`arrow_flags`,
    empty/diamond/odiamond, symmetric tail clips + `sp`), HTML side ports
    through the unified record channel (`gvHtmlPort` + pos_html_tbl sides
    mask; legacy path retired), nested-table STRETCH (pos_html_tbl),
    record clips against the FIELD-TREE root box, record field text through
    the label machinery (raw `\N` sizes as the node name — the resize
    int-floor had hidden the wrong base), `estimate_text_width_1pt` walks
    UTF-8 BYTES (2-byte chars = two space widths), x11 gray-ramp colors.
  - **167-grid**: `graph [rank=same]` as an INHERITED default (rank_set_class
    resolves through cgraph defaults), the long-deferred **flat-edge
    mincross machinery** (`flat_breakcycles` matrix + `flat_reorder`
    postorder toposort + the `left2right` guard in reorder AND transpose),
    locally-declared-empty subgraph attrs shadow root defaults. Chased with
    an instrumented gv (MCTRACE stage dumps).
  - **168-orientation**: **per-component mincross** (decompose +
    init_mccomp slices — the LR flip reverses each component's rank slice
    separately; one global reversal mirrors the component stacking),
    `poly_inside` rotates the canonical query into the TRUE frame
    (ccwrotatepf — first LR × rotated-polygon case), `label=""` ⇒ dimen
    (0,0), and the 0×0-node clip semantics (zero-size skips the scale
    division ⇒ scalex stays 0 ⇒ contains EVERY point — the spline
    collapses to gv's degenerate stub).
  - **169-cluster-attr**: `is_a_cluster` accepts the `cluster` ATTR (and the
    name prefix case-insensitively), **dot1 recursive cluster ranking**
    (interior solves + leader collapse + `interclust1` slack pairs with
    CL_BACK=10 — soft constraints absent from acyclic, so cluster-crossing
    cycles never break edges; root solve unbalanced; all six pre-existing
    cluster files stayed byte-exact through the new path), the
    **ReMincross** whole-graph refine phase (free nodes weave between
    cluster columns; boundaries pinned), the cylinder outline
    duplicate-vertex bisector SEED (atan2(0,0) corrupted the ring),
    `poly_inside`'s cached-segment walk (gv-exact on concave rings),
    edge-label vnodes growing the bb (lw=nodesep, rw=label width), and
    cluster `penwidth` ⇒ stroke-width.
  All suites green (graphvizJVM 708, sharedJVM/JS 172 each, viewer 52);
  gv worktree pristine after both instrumented chases.
- _(append dated entries as milestones land)_
