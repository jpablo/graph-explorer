// Oracle golden-capture for the Graphviz → Scala port (PORT.md §2.1).
//
// Runs every graphviz/corpus/*.dot through the REAL @viz-js/viz (the pinned
// 3.14.0 / Graphviz 13.0.1 oracle) and freezes its output for every
// intermediate format. Each future pipeline milestone diffs the Scala port
// against these goldens with tolerance (PORT.md §2.1).
//
//   node graphviz/oracle/capture.mjs
//
// Outputs: graphviz/golden/<corpus>/<format>  and  graphviz/golden/_meta.json

import { instance } from "@viz-js/viz";
import { readdir, readFile, writeFile, mkdir, rm } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";

const corpusDir = fileURLToPath(new URL("../corpus/", import.meta.url));
const goldenDir = fileURLToPath(new URL("../golden/", import.meta.url));

// All intermediate formats the staged-verification methodology relies on.
const FORMATS = {
  dot: "dot",
  plain: "plain",
  "plain-ext": "plain-ext",
  json: "json",
  json0: "json0",
  dot_json: "dot_json",
  xdot: "xdot",
  svg: "svg",
};

const sha256 = (s) => createHash("sha256").update(s).digest("hex");

const viz = await instance();

const meta = {
  graphvizVersion: viz.graphvizVersion,
  vizJsPackage: "3.14.0",
  engine: "dot",
  engines: viz.engines,
  formats: viz.formats,
  generatedAt: new Date().toISOString(),
  corpus: {},
};

await rm(goldenDir, { recursive: true, force: true });
await mkdir(goldenDir, { recursive: true });

const files = (await readdir(corpusDir)).filter((f) => f.endsWith(".dot")).sort();

for (const file of files) {
  const base = file.replace(/\.dot$/, "");
  const src = await readFile(corpusDir + file, "utf8");
  const outDir = `${goldenDir}${base}/`;
  await mkdir(outDir, { recursive: true });

  const entry = { input: file, sha256: sha256(src), formats: {} };

  for (const [name, format] of Object.entries(FORMATS)) {
    try {
      const result = viz.render(src, { format, engine: "dot" });
      if (result.status === "success") {
        await writeFile(`${outDir}${name}`, result.output);
        entry.formats[name] = { status: "success", sha256: sha256(result.output) };
      } else {
        entry.formats[name] = { status: "failure", errors: result.errors };
      }
    } catch (e) {
      entry.formats[name] = { status: "error", message: String(e) };
    }
  }
  meta.corpus[base] = entry;
  console.log(`captured ${base}`);
}

await writeFile(`${goldenDir}_meta.json`, JSON.stringify(meta, null, 2) + "\n");
console.log(`\nGraphviz ${meta.graphvizVersion} · ${files.length} corpus files → ${goldenDir}`);
