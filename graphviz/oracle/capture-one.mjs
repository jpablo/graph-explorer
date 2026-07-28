// Targeted golden capture for a SINGLE corpus file (PORT.md §2.1).
//
//   node graphviz/oracle/capture-one.mjs 192-rank-gap-callgraph
//
// capture.mjs `rm -rf`s graphviz/golden/ and re-captures all of it, which is
// right when the oracle version moves but far too blunt when you are just
// adding one file — it rewrites 160+ goldens you did not mean to touch, and any
// unrelated viz-js nondeterminism rides along in the same commit. This writes
// only graphviz/golden/<base>/ and merges that one entry into _meta.json.
import { instance } from "@viz-js/viz";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";

const base = process.argv[2];
if (!base) {
  console.error("usage: node graphviz/oracle/capture-one.mjs <corpus-basename>");
  process.exit(2);
}
const corpusDir = fileURLToPath(new URL("../corpus/", import.meta.url));
const goldenDir = fileURLToPath(new URL("../golden/", import.meta.url));

// Same format set as capture.mjs — the staged-verification methodology diffs
// against every intermediate stage, not just the final svg.
const FORMATS = {
  dot: "dot", plain: "plain", "plain-ext": "plain-ext", json: "json",
  json0: "json0", dot_json: "dot_json", xdot: "xdot", svg: "svg",
};
const sha256 = (s) => createHash("sha256").update(s).digest("hex");

const viz = await instance();
const src = await readFile(`${corpusDir}${base}.dot`, "utf8");
const outDir = `${goldenDir}${base}/`;
await mkdir(outDir, { recursive: true });

const entry = { input: `${base}.dot`, sha256: sha256(src), formats: {} };
for (const [name, format] of Object.entries(FORMATS)) {
  const result = viz.render(src, { format });
  if (result.status === "success") {
    await writeFile(`${outDir}${name}`, result.output);
    entry.formats[name] = { status: "success", sha256: sha256(result.output) };
    console.log(`  ${name}: ${result.output.length} bytes`);
  } else {
    entry.formats[name] = { status: "failure", errors: result.errors };
    console.log(`  ${name}: FAILURE ${JSON.stringify(result.errors)}`);
  }
}

const metaPath = `${goldenDir}_meta.json`;
const meta = JSON.parse(await readFile(metaPath, "utf8"));
meta.corpus[`${base}.dot`] = entry;
// keep the file key-sorted so a targeted add produces a minimal diff
meta.corpus = Object.fromEntries(
  Object.entries(meta.corpus).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
);
await writeFile(metaPath, JSON.stringify(meta, null, 2) + "\n");
console.log(`merged ${base}.dot into _meta.json`);
