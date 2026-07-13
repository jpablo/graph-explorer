import { instance } from "@viz-js/viz";
import { writeFileSync, mkdirSync, readFileSync } from "node:fs";
const viz = await instance();
const src = readFileSync("../graphviz/corpus/96-nested-cluster.dot","utf8");
const dir = "../graphviz/golden/96-nested-cluster";
mkdirSync(dir, { recursive: true });
for (const fmt of ["dot","plain","plain-ext","json","json0","dot_json","xdot","svg"]) {
  const r = viz.render(src, { format: fmt, engine: "dot" });
  if (r.status === "success") writeFileSync(`${dir}/${fmt}`, r.output);
  else console.log(fmt,"FAIL",r.errors);
}
console.log("captured 96-nested-cluster");
