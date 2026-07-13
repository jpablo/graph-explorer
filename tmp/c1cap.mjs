import { instance } from "@viz-js/viz";
import { writeFileSync, mkdirSync } from "node:fs";
const viz = await instance();
const src = `digraph {
  r -> a; r -> b; r -> c; r -> d;
  subgraph cluster_0 { a; c; }
  subgraph cluster_1 { b; d; }
}
`;
mkdirSync("c1gold", { recursive: true });
for (const fmt of ["dot_json","json0","svg"]) {
  const r = viz.render(src, { format: fmt, engine: "dot" });
  writeFileSync(`c1gold/${fmt}`, r.output);
}
writeFileSync("c1gold/94.dot", src);
console.log("captured C1 golden");
