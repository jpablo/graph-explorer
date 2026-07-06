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
| M6 | Long tail: clusters, ports, compass, `rankdir`, record & HTML-like labels | Per-feature rows in §5 all ✅ | 🟡 in progress (128/128) — ✅ done: records (layout+svg), **ports/compass**, **self-loops/parallel edges**, edge-label rank-doubling+Y, svg graph/edge titles, bbox precision, arrow miter + true `arrow_length`, **03 clusters structural** (§5.2), **full `write_attrs` attribute emission** (dot_json/json0, M8 styling — §5.4), **svg styling** (fill/stroke/dash/fontcolor) + gv emit-order interleave, **box-family shapes** (`<polygon>` + `poly_inside` edge-clipping, `10-box` byte-exact — §5.3). 🟡 `rankdir`: blocker 1 (layout-size) ✅, blocker 2 half (weight ✅, label-vnode-X-under-flip open). ⬜ remaining: `rankdir` label-vnode-X (deep mincross), `rank=same` *layout* constraint, HTML-like labels, `rounded` box path, 05 graph-label bbox, `json0`/svg cluster geometry (viz-js buggy). M6 stays ⬜-at-milestone-level until *all* §5 rows ✅; per-row truth = §5, narrative = §7 |
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
| Subgraphs & `cluster_*` | 🟡 | DotParserSpec + ClusterSpec | parsed; resolved into `RGraph.subgraphs` tree (2026-05-29) with cluster-ness/rank/membership/anon-`%N` — `dot_json` byte-exact (§5.2 Cluster row). `cluster_` *layout* geometry = M6 |
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
| X-coord assignment | ✅ | XCoordSpec | `XCoord`: aux graph (`make_LR_constraints` sep = rw+lw+nodesep; `make_edge_pairs` slack nodes; virtual half = 1+nodesep/2) → NS(balance=2) → bbox shift. Matches `plain` for 01/06/07 (mirror allowed) |
| Edge-pair ω weight (`virtual_weight`) | ✅ | XCoordSpec | NOT ω=1/2/8-by-virtualness. `t = table[class(tail)][class(head)]`, class = ORDINARY(0)/SINGLETON(1, real & ≤1 incident edge)/VIRTUAL(2); table `[[1,1,1],[1,2,2],[1,2,4]]`. Verified vs instrumented gv 13.0.1 aux dump (07 singletons→C_SS=2; 01/06 branch→C_EE=1) |
| LR_balance (NS balance=2) | ✅ | NetworkSimplexSpec/XCoordSpec | `ns.c` LR_balance ported. With correct ω, a/c become a flat optimum in [b,v]; LR_balance centres them to match Graphviz |
| ~~Virtual-node X separation too wide~~ | **RESOLVED 2026-05-16** | XCoordSpec | **Misdiagnosis corrected.** Separation `minlen=55` was right all along (instrumented gv confirms b→v minlen=55). Real bug = wrong ω model (above). Found by building+instrumenting gv 13.0.1, not by re-reading source |
| Edge-label rank doubling (`ED_minlen*=2`) | ✅ | RankSpec + CoordSpec | **Closed (Y).** `Rank.hasEdgeLabel` ⇒ `acyclic` `minlen*=2` (`edgelabel_ranks`); `Coord` ranksep `(36+1)/2=18`; `make_chain` `label_vnode` half-height = `nLines·fontsize·LINESPACING/2` (`NodeSize.labelHeightPt`, reusing the M1 `\n\l\r` line split — single-line HTML label included) seated at mid rank `(rank t+rank h)/2`; root graph-label space `do_graph_label` = label box + YPAD `2·GAP` (GAP=4) at the labelloc side. CoordSpec 05 deferred-probe **promoted** → 05 in the strict Y list, matches golden ≤0.005 in. (HTML label *width*/table layout stays a separate M6 row — doesn't affect rank-axis Y.) 02 still needs LR (separate row) |
| `rankdir = TB/LR/BT/RL` | 🟡 (blocker 1 closed; blocker 2 quantified) | RankDirSpec | **Blocker (1) RESOLVED 2026-05-17, blocker (2) sharply quantified — honest negative.** (1) `gv_nodesize(n, flip)` ported as `NodeSize.layoutSize` (LR/RL ⇒ w/h swapped: layout `w=trueHeight`, `h=trueWidth`) and threaded through the canonical layout (Coord/XCoord/Spline). `nodeSize` stays the true-size `dot`-oracle contract — `layoutSize == nodeSize` for TB ⇒ **01/06/07/04/05 byte-identical** (106/106; RankDirSpec locks it). The transform (`translate_drawing`/`map_point`, Offset=`(−cbb.UR.y, cbb.LL.x)`; LR ⇒ `final=(cbb.UR.y−y, x−cbb.LL.x)` over the canonical node-extent bbox) is **verified byte-exact vs instrumented gv 13.0.1** — applied to our canonical 02 it gives the **rank axis (final X) within ~3 pt** of the golden (gated as progress). (2) **half-closed 2026-05-17**: edge `weight` threaded into the XCoord ω (faithful `make_edge_pairs` `ED_weight`; default-1 ⇒ 01/06/07 byte-identical, XCoordSpec green) — the `weight=2` `start→middle` edge now **aligns** `start`/`middle` on the canonical order axis (was 45 vs 18, now 18 vs 18, matching gv's `start≡middle`). Still REMAINS: the **edge-label vnode's X under flip** — the `go` label injects an order-axis virtual node our canonical X/mincross doesn't place like gv ⇒ order axis still ~25–34 pt off (not visually close). Genuine remaining sub-part. RankDirSpec carries a **self-flagging deferred-probe** (order-axis still deviates → fails-when-fixed). No fake gate; 02's `go` rank-doubling is ✅ |
| Cluster structural model + `dot_json` | ✅ (`dot_json` byte-exact; `json0`-geom/svg deferred) | ClusterSpec + DifferentialSpec | **CLOSED 2026-05-29 — the two "unknowns" cracked by oracle-probing, no instrumented-gv build.** Per §1 (match viz-js *strings*) 03 is geometry-free (viz-js doesn't lay clusters out ⇒ `bb`/`pos` degenerate). Landed: additive `RGraph.subgraphs` tree (`AttrResolver.walk` now keeps what it discarded) → `Output` emits `_subgraph_cnt`, subgraph objects **first** (preorder `label_subgs` gvid 0..cnt-1), real-node `_gvid` offset by `sgCnt`, top-level edges array **AGSEQ-sorted** (gv `qsort`) while `_gvid` stays node-traversal. **03 `dot_json` byte-exact.** The recon's "genuine unknown" (the node→subgraph ownership partition) did **not** need an instrumented `agfstnode(sg)` dump — it's directly observable in ordinary `-Tjson` output (`write_nodes(sg)` = raw `agfstnode(sg)`), so a handful of **probe DOT files through the version-matched oracle (viz-js == gv 13.0.1)** nailed it: **(rule 1 — ownership)** a node in a `rank`-constraint subgraph is evicted from any *cluster* node list (plain-subgraph membership is additive); a cluster edge whose tail was evicted leaves too (`cluster_0.edges=[1]`). **(rule 2 — anon `%N`)** id = `counter*2+1` over the unnamed root + every (keyless) edge + anon subgraphs in parse order (id.c `idmap`) ⇒ the 3 cluster edges tick to 3 before `{rank=same}` ⇒ `%7`. **(rule 3 — root attrs)** gv `write_attrs` prints every declared graph-attr with the root's value, skipping empty *except* `label` ⇒ 03 root gets `label:""` (declared by a cluster) but not `rank` (empty at root). **Deferred (tracked §5.4):** `json0` cluster-label geometry (`lheight`/`lwidth`/`lp` — viz-js emits a *buggy partial* layout for 03, not clean zeros) and svg cluster boxes. Nested-cluster membership union + non-`cluster`-prefixed subgraph edge cases have no corpus. |
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
| `json0` | ✅ | OutputSpec + AttrEmitSpec | `Output.json0`: dot_json attrs **merged alphabetically** with the layout keys (node `pos`/`width`/`height`, edge `pos` spline `e,EX,EY `) into one `write_attrs` stream — so the viewer's render path (`renderFormats(dot,["svg","json0"])`) now receives `fillcolor`/`shape`/`style`/… (previously dropped ⇒ unstyled). comma-`bb` = exact float node-extent (`%.5g`). **byte-exact** vs golden 01/06/07/04; geometry ±ε, **mirror-aware** (06 X mirrored, cf. XCoordSpec) |
| `svg` | ✅ | SvgSpec | `Output`/`Svg.svg`: header/`<svg>`/`viewBox`/flipped-y `translate`/background bit-exact; node `<ellipse>`+centered `<text>` (baseline y from `emit_label`+`yoffset_centerline`, source-derived not fitted); edge `<path d>` from the installed spline + normal-arrowhead `<polygon>` (full `arrow_type_normal0` + `miter_shape` ported 2026-05-17: `delta_tip` miter incl. the SVG `stroke-miterlimit=4` bevel fallback — **byte-identical** to the golden for non-virtual edges; was the long-deferred sub-2px residual). **Record nodes** (2026-05-17): ports `record_gencode`/`gen_fields` — outer box `<polygon>` + inter-field separator `<polyline>`s (LR table ⇒ vertical at child llx; TB ⇒ horizontal at child ury) + per-leaf centred field `<text>`; **byte-identical** to the 04 golden's per-node `<g>` blocks (exact, not ε — fully determined by the ✅ RecordLabel layout). `gvprintdouble` (`%.2f` trimmed). **Graph/edge titles** (2026-05-17): named graph ⇒ `<!-- Title: NAME -->` + graph `<title>`, anon ⇒ neither (`g.name`); edge `<title>` = `\E` (labels.c) = `tail[:port]op head[:port]` where port = `chkPort` `.name` (after first `:`, ⇒ `f2:s`→`s`), the edge *comment* stays portless (emit.c) — gated vs 04 + the corpus (closed the latent 06/07 untested gap). **Bbox precision** (2026-05-17): `Output.bbox` ports `position.c dot_compute_bb` — node-extent only (NORMAL nodes ± lw/rw + rank ht), **no spline, no floor/ceil**; svg `<svg>`/viewBox = ceil'd int canvas, `translate`/bg = the exact float. 04 svg header/transform/background now **byte-exact** vs golden (`translate(4 127.6)`, bg `135.98`). Well-formed + visually-close ε vs golden 01/06/07/04, mirror-aware. **Arrow miter + true length ported** (2026-05-17): `Arrow` (shared by Svg + Spline) — arrowhead polygons **byte-identical**, and clipping by the real `arrow_length_normal` (≈11.53) made the directed corpus splines byte-exact, so directed `<path d>` + `<polygon>` now match the golden to float-ε. Only 06 (undirected ⇒ no arrow clip) keeps its documented ≈0.013 in layout-equivalent X-mirror residual. **Styling + emit order** (2026-05-29, StyleSvgSpec, `09-styled` probe): node `fill` (`filled`⇒`fillcolor`/`color`/`lightgrey`, else `none`), `stroke`=`color`, text `fill`=`fontcolor`; edge `stroke`=`color` + `stroke-dasharray` (`dashed`⇒`5,2`/`dotted`⇒`1,5`) + arrowhead in the edge colour. **Emit order corrected to gv's node/edge interleave** (each edge emitted right after its head node is introduced — `a1,b3,a1→b3,a2,…`), previously all-nodes-then-edges (only latent because SvgSpec parses structurally). **`09-styled` svg byte-exact**; unstyled corpus (01/06/07/04/08) unchanged. Styled diagrams now render styled end-to-end (was black-on-none) |
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
- _(append dated entries as milestones land)_
