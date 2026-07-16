// Golden capture for the SHIPPED viewer examples (PORT.md — example gate).
//
// Runs every viewer/src/main/resources/examples/**/*.dot through the REAL
// @viz-js/viz (the pinned 3.14.0 / Graphviz 13.0.1 oracle) with engine=dot
// and freezes the three formats the byte-exact gates compare. The sources
// stay where they ship — ExamplesByteExactSpec reads them directly, so the
// gate can never drift from what the app actually serves.
//
//   node graphviz/oracle/capture-examples.mjs
//
// Outputs: graphviz/golden-examples/<relative-path>/<format>
//          graphviz/golden-examples/_meta.json
//
// Non-`dot`-engine examples (neato/fdp/…) are captured too — with engine=dot,
// so their goldens are meaningless and the gate ignores them (in the app they
// route to viz-js itself and are identical by construction). The Scala side
// decides which files to gate via the shared EngineRouting predicate.

import { instance } from "@viz-js/viz";
import { readdir, readFile, writeFile, mkdir, rm } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";

const examplesDir = fileURLToPath(new URL("../../viewer/src/main/resources/examples/", import.meta.url));
const goldenDir   = fileURLToPath(new URL("../golden-examples/", import.meta.url));

const FORMATS = { dot_json: "dot_json", json0: "json0", svg: "svg" };

const sha256 = (s) => createHash("sha256").update(s).digest("hex");

const viz = await instance();

const meta = {
  graphvizVersion: viz.graphvizVersion,
  vizJsPackage: "3.14.0",
  engine: "dot",
  generatedAt: new Date().toISOString(),
  examples: {},
};

await rm(goldenDir, { recursive: true, force: true });
await mkdir(goldenDir, { recursive: true });

const entries = await readdir(examplesDir, { recursive: true });
const files = entries.filter((f) => f.endsWith(".dot")).sort();

for (const rel of files) {
  const base = rel.replace(/\.dot$/, "");
  const src = await readFile(examplesDir + rel, "utf8");
  const outDir = `${goldenDir}${base}/`;
  await mkdir(outDir, { recursive: true });

  const entry = { input: rel, sha256: sha256(src), formats: {} };

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
  meta.examples[base] = entry;
  console.log(`captured ${base}`);
}

await writeFile(`${goldenDir}_meta.json`, JSON.stringify(meta, null, 2) + "\n");
console.log(`\nGraphviz ${meta.graphvizVersion} · ${files.length} example files → ${goldenDir}`);
