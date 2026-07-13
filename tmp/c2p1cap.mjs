import { instance } from "@viz-js/viz";
import { writeFileSync, mkdirSync } from "node:fs";
const viz = await instance();
const probes = {
  "95-cluster-chains": `digraph {
  subgraph cluster_0 { a0; a1; a2; }
  subgraph cluster_1 { b0; b1; b2; }
  top -> a0; top -> b0; top -> a1; top -> b1; top -> a2; top -> b2;
  a0->a1->a2; b0->b1->b2;
}
`,
};
for (const [name, src] of Object.entries(probes)) {
  const dir = `../graphviz/golden/${name}`;
  mkdirSync(dir, { recursive: true });
  for (const fmt of ["dot","plain","plain-ext","json","json0","dot_json","xdot","svg"]) {
    const r = viz.render(src, { format: fmt, engine: "dot" });
    if (r.status === "success") writeFileSync(`${dir}/${fmt}`, r.output);
  }
  writeFileSync(`../graphviz/corpus/${name}.dot`, src);
  console.log("captured", name);
}
