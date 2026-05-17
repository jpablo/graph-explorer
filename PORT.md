# Graphviz `dot` → Scala Port — Plan & Conformance Tracker

> Living document. Every milestone and every feature row must be backed by an
> oracle test before its status flips to ✅. Do not mark anything "done" from
> code review alone — done means "the differential harness agrees with
> `@viz-js/viz` within tolerance."

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
| M5 | Edge spline routing | Edge geometry within ε; same endpoints/clipping | ✅ Done — 66/66; full box-fit ported (box channel → `Pshortestpath` funnel → `Proutespline` → `clip_and_install`/`bezier_clip`); raw spline byte-exact vs instrumented gv 13.0.1; Hausdorff ≤0.024 in (01 incl. curved `a→c`, 07); 06 mirror-equivalent |
| M6 | Long tail: clusters, ports, compass, `rankdir`, record & HTML-like labels | Per-feature rows in §5 all ✅ | ⬜ |
| M7 | Output writers: `dot_json` → `json0` → `svg` | Emitted strings parse to identical `SimpleGraph`; SVG visually-close | ✅ Done — 81/81; `dot_json`/`json0`/`svg` writers + `renderFormats` facade. Structural-exact + ε geometry vs goldens (mirror-aware) for label-free TB (01/06/07); records/clusters/edge-labels are their own tracked deferrals |
| M8 | Integration behind flag; differential test on real project corpus; viz-js demoted to oracle | Project diagrams render via Scala backend; harness CI green | 🟡 seam landed — `viewer.dependsOn(graphviz.js)`; flagged adapter (`localStorage gx.graphvizEngine=scala`, viz-js default/fallback); viewer Scala.js compiles. `DifferentialSpec`: must-pass label-free TB (01/06/07) renders end-to-end via the facade & matches goldens (85/85 CI green); 02/04/05 structurally correct + 03 clusters structurally divergent — full real-corpus parity tracked behind **M6** |

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
| Subgraphs & `cluster_*` | 🟡 | DotParserSpec | parsed; `cluster_` semantics = M6 |
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
| `rank=same/min/max/source/sink` | ⬜ | | needs subgraph structure (M1 `RGraph` is flat) — bundled with cluster ranking M6 |
| `minlen`, `weight`, `ranksep`, `nodesep` | 🟡 | RankSpec | `minlen` honoured; `weight` n/a to longest-path; `ranksep`/`nodesep` are M4 (coords) |
| Virtual nodes for long edges | ✅ | OrderSpec | `class2` unit-span chains; verified via corpus long edges (01/02/05/06) staying 0-crossing |
| Mincross (weighted median + transpose) | 🟡 | OrderSpec | BFS init + `medians`/`reorder` + `transpose`, MaxIter loop. Gate = crossing-count parity (0 across corpus incl. `07` 3→0). pass-0/pass-1 init *alternation*, flat edges, ports, clusters deferred |
| Y-coord assignment (`set_ycoords`) | ✅ | CoordSpec | bottom rank = halfHt; step = halfHt+halfHt+ranksep(36pt). Exact label-free TB |
| X-coord assignment | ✅ | XCoordSpec | `XCoord`: aux graph (`make_LR_constraints` sep = rw+lw+nodesep; `make_edge_pairs` slack nodes; virtual half = 1+nodesep/2) → NS(balance=2) → bbox shift. Matches `plain` for 01/06/07 (mirror allowed) |
| Edge-pair ω weight (`virtual_weight`) | ✅ | XCoordSpec | NOT ω=1/2/8-by-virtualness. `t = table[class(tail)][class(head)]`, class = ORDINARY(0)/SINGLETON(1, real & ≤1 incident edge)/VIRTUAL(2); table `[[1,1,1],[1,2,2],[1,2,4]]`. Verified vs instrumented gv 13.0.1 aux dump (07 singletons→C_SS=2; 01/06 branch→C_EE=1) |
| LR_balance (NS balance=2) | ✅ | NetworkSimplexSpec/XCoordSpec | `ns.c` LR_balance ported. With correct ω, a/c become a flat optimum in [b,v]; LR_balance centres them to match Graphviz |
| ~~Virtual-node X separation too wide~~ | **RESOLVED 2026-05-16** | XCoordSpec | **Misdiagnosis corrected.** Separation `minlen=55` was right all along (instrumented gv confirms b→v minlen=55). Real bug = wrong ω model (above). Found by building+instrumenting gv 13.0.1, not by re-reading source |
| Edge-label rank doubling (`ED_minlen*=2`) | ✅ | RankSpec + CoordSpec | **Closed (Y).** `Rank.hasEdgeLabel` ⇒ `acyclic` `minlen*=2` (`edgelabel_ranks`); `Coord` ranksep `(36+1)/2=18`; `make_chain` `label_vnode` half-height = `nLines·fontsize·LINESPACING/2` (`NodeSize.labelHeightPt`, reusing the M1 `\n\l\r` line split — single-line HTML label included) seated at mid rank `(rank t+rank h)/2`; root graph-label space `do_graph_label` = label box + YPAD `2·GAP` (GAP=4) at the labelloc side. CoordSpec 05 deferred-probe **promoted** → 05 in the strict Y list, matches golden ≤0.005 in. (HTML label *width*/table layout stays a separate M6 row — doesn't affect rank-axis Y.) 02 still needs LR (separate row) |
| `rankdir = TB/LR/BT/RL` | ⬜ (model reverse-engineered) | RankSpec/OrderSpec already rankdir-aware | **Model fully derived (2026-05-17), port deferred — honest negative.** `Rank.rankdir/flip` helpers landed (RANKDIR_TB0/LR1/BT2/RL3; flip = LR\|RL). Transform = `translate_drawing`/`map_point`: `ccwrotatepf(p, rankdir·90)` then `−Offset`; LR ⇒ `(x,y)→(bb.UR.y−y, x−bb.LL.x)` over the canonical bbox. Layout runs with `gv_nodesize(n, flip)` w/h **swapped** (`ht=width, lw=rw=height/2`). **Blocker found by measurement (not guessed):** even with the transform, 02's order-axis (post-rotation Y) is off ~0.35–0.47 in — the swap must thread a *layout-orientation* size through Coord/XCoord/Spline (NOT `NodeSize.nodeSize`, which is the true-size `dot`-oracle contract — proven by NodeSizeSpec breaking when flipped there) **and** the canonical X/order simplex must reproduce the edge-label-weighted straightening under flip. Genuine multi-part sub-port; 02 also has the `go` edge label (✅ rank-doubling). No fake gate added |
| Cluster layout (recursive) | ⬜ | | hardest part of M6 |
| Spline routing — straight/clipped | ✅ | SplineSpec | All edges route via the real `routesplines` pipeline (no straight-leg special-case). `clip_and_install`+`bezier_clip` ported faithfully (ellipse `insidefn` semi-axes = (sizePt+penwidth)/2; `ARROW_LENGTH`=10pt). 07 raw spline byte-exact vs instrumented gv; Hausdorff 0.024 in |
| Spline routing — box-fit (bowed curves) | ✅ | SplineSpec | **Closed 2026-05-17 by instrument-and-port (§2.5).** Ported box channel (`completeregularpath`/`maximal_bbox`/`rank_box`/`adjustregularpath`/`checkpath`; MINW=16, FUDGE=4, Splinesep=nodesep/4), channel polygon, `Pshortestpath` (taut funnel over box portals), `Proutespline` (recursive least-squares cubic fit + `solve3`). Verified vs instrumented gv 13.0.1: box channel, shortest path AND raw spline reproduce the probe **byte-for-byte** for 01 `a→c` (the curved long edge). 01 `a→c` 0.25 → **0.024 in** Hausdorff vs `plain`. Earlier Catmull-Rom cheap-approx (measured 0.25/0.54 in) stays a recorded dead end |
| Self-loops, parallel/multi-edges | ⬜ | | |
| Port/compass-anchored edge endpoints | 🟡 | OutputSpec (04 ports) | **Model + emission done.** AST `NodeId.port` (`field`/`field:compass`) was parsed but dropped by `AttrResolver`; now threaded into `REdge.tailPort/headPort` (additive, default None ⇒ portless edges byte-unchanged) and emitted as dot_json/json0 `tailport`/`headport`. 04's two parallel `struct1→struct2` edges are now distinguished by port and match the golden set `{(f0,a),(f2:s,b:n)}` exactly. **Remaining (next increment):** geometric anchoring — endpoint to the (now-available, `RecordLabel.fieldBox`) field rect + compass side, the parallel-edge `Spline` `(tail,head)`-key merge, and routing/clip into the port box (`beginpath`/`endpath` port branch read in M5). Oracle: 04 `dot`/`plain` per-edge `pos` |

### 5.3 Node shapes & labels
| Feature | Status | Test | Notes |
|---|---|---|---|
| Poly shapes: box, ellipse, circle, square, plaintext, none, plain | 🟡 | NodeSizeSpec | sized & oracle-verified; diamond/polygon/point = later |
| `record` / `Mrecord` (layout/sizing) | ✅ | NodeSizeSpec + RecordSpec | **Closed.** `RecordLabel` ports `parse_reclbl` (grammar `f`, `f\|f`, `{…}` orientation toggle, `<port>`, `\`-escapes/hard-space) + `size_reclbl` (leaf = text dimen + PAD `XPAD 4·GAP`/`YPAD 2·GAP`; LR ⇒ Σx/maxy else Σy/maxx; per-line height = exact `fontsize·LINESPACING`, same finding as the 05 vnode) + `resize_reclbl` (min-size even-int split) + `pos_reclbl` + `record_init` (`+1pt` height kluge). `NodeSize` sizes record/Mrecord (no longer `None`). Verified **exact** vs the 04 `dot` golden: struct1 `1.833×0.51389`, struct2 `0.75×0.70278`; node-local field boxes + node centre == golden absolute `rects` to ≤0.05 pt. Record svg field-line *drawing* = M7-svg follow-up; field-port edge endpoints = the §5.2 ports row (increment 2). HTML-in-record + exotic escape/UTF-8/control = no-corpus deferrals |
| HTML-like labels (table layout) | ⬜ | | M6 |
| `width`/`height`/`fixedsize`/`margin` | ✅ | NodeSizeSpec | fixedsize true/shape, margin `x[,y]`, min-size floor, regular |
| Font metrics: Times/Helvetica/Courier | ✅ | NodeSizeSpec | `textspan_lut` transcribed (gen_font_metrics.py); Times oracle-verified |
| Non-builtin font fallback | 🟡 | FontMetrics | falls back to Times (matches `get_metrics_for_font_family`); not oracle-diffed |

### 5.4 Output writers
| Format | Status | Test | Notes |
|---|---|---|---|
| `dot_json` | ✅ | OutputSpec | `Output.dotJson`: hand-rolled (no serialization dep). name/`%1`/directed/strict/`_subgraph_cnt`/space-`bb`/objects(`_gvid`,name,label)/edges(`_gvid` by cgraph node-traversal order, tail,head). Structure-exact + bb ±ε vs golden 01/06/07 |
| `json0` | ✅ | OutputSpec | `Output.json0`: dot_json + node `pos`/`width`/`height`, comma-`bb`, edge `pos` spline string (`e,EX,EY ` iff head arrow, via `Spline.splinesEx` `ESpline.ep`). Geometry ±ε, **mirror-aware** (06 X mirrored, layout-equivalent — cf. XCoordSpec). Number format ≈ C `%.5g` |
| `svg` | ✅ | SvgSpec | `Output`/`Svg.svg`: header/`<svg>`/`viewBox`/flipped-y `translate`/background bit-exact; node `<ellipse>`+centered `<text>` (baseline y from `emit_label`+`yoffset_centerline`, source-derived not fitted); edge `<path d>` from the installed spline + normal-arrowhead `<polygon>` (`arrow_type_normal0` a[1..3]; miter `delta_tip/base` = same M5-deferred sub-2px). `gvprintdouble` (`%.2f` trimmed). Well-formed + visually-close ε vs golden 01/06/07, mirror-aware |
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
- _(append dated entries as milestones land)_
