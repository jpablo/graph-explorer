import { instance } from "@viz-js/viz";
import { writeFileSync, mkdirSync, readFileSync } from "node:fs";
const viz = await instance();
const src = readFileSync("../graphviz/corpus/94-cluster-contig.dot","utf8");
const dir = "../graphviz/golden/94-cluster-contig";
mkdirSync(dir, { recursive: true });
const FORMATS = { dot:"dot", plain:"plain", "plain-ext":"plain-ext", json:"json", json0:"json0", dot_json:"dot_json", xdot:"xdot", svg:"svg" };
for (const [name, format] of Object.entries(FORMATS)) {
  const r = viz.render(src, { format, engine: "dot" });
  if (r.status === "success") writeFileSync(`${dir}/${name}`, r.output);
  else console.log(name, "FAIL");
}
console.log("captured 94-cluster-contig golden set");
